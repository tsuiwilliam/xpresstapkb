# Claude Code Agent Prompt — xPressTap Keyboard APK

## Project Overview

Build an Android keyboard APK called **xPressTap** — a fork of HeliBoard that adds an NFC payment card reader button to the keyboard toolbar. When a user focuses a payment field and taps a physical contactless card to their phone, the keyboard reads the card's PAN and expiry via EMV/NFC and injects them into the focused fields. CVV is prompted once per card, encrypted, and stored in Android Keystore.

Target: F-Droid-compatible (no proprietary dependencies in the build itself; glide typing library remains user-loadable as in upstream HeliBoard).

---

## Phase 0 — Environment Verification

Before writing any code, verify the following are available and working:

```bash
# Check Android SDK and build tools
sdkmanager --list | grep "build-tools"
sdkmanager --list | grep "platforms;android"

# Check Java version (needs JDK 17 for modern Android builds)
java -version
javac -version

# Check Gradle
gradle --version

# Verify git is available
git --version
```

If any tool is missing, stop and report what needs to be installed. Do not proceed until the environment is confirmed.

---

## Phase 1 — Fork HeliBoard

### 1.1 Clone upstream

```bash
git clone https://github.com/Helium314/HeliBoard.git xpresstap
cd xpresstap
git remote rename origin upstream
git checkout -b xpresstap-main
```

### 1.2 Rename the application

Make the following find-and-replace changes throughout the project. Do them carefully — use `grep -r` first to find all occurrences before replacing:

| Find | Replace |
|------|---------|
| `helium314.keyboard` (package name) | `com.xpresstap.keyboard` |
| `HeliBoard` (app name strings) | `xPressTap` |
| `heliboard` (lowercase references in build files) | `xpresstap` |

Files that will definitely need changes:
- `app/build.gradle` — `applicationId`
- `app/src/main/AndroidManifest.xml` — package, label
- `app/src/main/res/values/strings.xml` — `app_name`
- All Kotlin files with the old package declaration at the top (use IDE refactor or `find . -name "*.kt" | xargs sed -i`)
- `settings.gradle` — project name

After renaming, confirm the build still compiles before proceeding to Phase 2:

```bash
./gradlew assembleDebug
```

Fix any compilation errors from the rename before moving on. Do not proceed to Phase 2 until `assembleDebug` succeeds.

---

## Phase 2 — Add NFC Permission and Hardware Feature Declaration

Edit `app/src/main/AndroidManifest.xml`. Add the following inside the `<manifest>` block, before `<application>`:

```xml
<!-- NFC card reading -->
<uses-permission android:name="android.permission.NFC" />
<uses-feature
    android:name="android.hardware.nfc"
    android:required="false" />
```

`required="false"` keeps the app installable on devices without NFC — the toolbar button will simply be hidden on those devices.

---

## Phase 3 — CVV Vault: Encrypted Storage via Android Keystore

Create a new file:
`app/src/main/java/com/xpresstap/keyboard/nfc/CvvVault.kt`

This class handles encrypted per-card CVV storage. Use Android Keystore with AES-256-GCM. The key alias should be `"xpresstap_cvv_key"`.

```kotlin
package com.xpresstap.keyboard.nfc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores CVV values encrypted with AES-256-GCM using Android Keystore.
 * Keyed by the last 4 digits of PAN + expiry (e.g. "1234_1226") to avoid
 * storing full PAN anywhere. CVV is never logged.
 */
object CvvVault {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "xpresstap_cvv_key"
    private const val PREFS_FILE = "xpresstap_cvv_vault"
    private const val GCM_TAG_LENGTH = 128

    fun storeCvv(context: Context, cardKey: String, cvv: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString(cardKey, cvv).apply()
    }

    fun retrieveCvv(context: Context, cardKey: String): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString(cardKey, null)
    }

    fun hasCvv(context: Context, cardKey: String): Boolean {
        return retrieveCvv(context, cardKey) != null
    }

    fun deleteCard(context: Context, cardKey: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().remove(cardKey).apply()
    }

    fun listCards(context: Context): Set<String> {
        return getEncryptedPrefs(context).all.keys
    }

    private fun getEncryptedPrefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
}
```

Add the dependency to `app/build.gradle`:
```gradle
implementation "androidx.security:security-crypto:1.1.0-alpha06"
```

---

## Phase 4 — EMV NFC Card Reader

Create:
`app/src/main/java/com/xpresstap/keyboard/nfc/EmvCardReader.kt`

This class handles the APDU command sequence to read PAN and expiry from a contactless EMV card.

```kotlin
package com.xpresstap.keyboard.nfc

import android.nfc.tech.IsoDep

/**
 * Reads PAN and expiry from a contactless EMV card via ISO 7816 APDU commands.
 *
 * Flow:
 *   1. SELECT PPSE (Proximity Payment System Environment)
 *   2. SELECT AID from PPSE response
 *   3. GET PROCESSING OPTIONS
 *   4. READ RECORD(s) to extract PAN and expiry
 *
 * Returns null fields if card does not expose them (some virtual/tokenized cards).
 */
object EmvCardReader {

    data class CardData(
        val pan: String,           // Full PAN, digits only, e.g. "4111111111111111"
        val expiry: String,        // MMYY format, e.g. "1226"
        val last4: String,         // Convenience: last 4 of PAN
        val cardKey: String        // Vault key: last4_expiry e.g. "1111_1226"
    )

    private val PPSE_AID = byteArrayOf(
        0x32, 0x50, 0x41, 0x59, 0x2E, 0x53, 0x59, 0x53,
        0x2E, 0x44, 0x44, 0x46, 0x30, 0x31  // "2PAY.SYS.DDF01"
    )

    fun read(isoDep: IsoDep): CardData? {
        return try {
            isoDep.connect()
            isoDep.timeout = 5000

            // Step 1: SELECT PPSE
            val ppseResponse = isoDep.transceive(buildSelectApdu(PPSE_AID))
            if (!isSuccess(ppseResponse)) return null

            // Step 2: Parse AID from PPSE response and SELECT it
            val aid = parseAidFromPpse(ppseResponse) ?: return null
            val appResponse = isoDep.transceive(buildSelectApdu(aid))
            if (!isSuccess(appResponse)) return null

            // Step 3: GET PROCESSING OPTIONS
            val gpoResponse = isoDep.transceive(buildGpo())
            // GPO may fail on some cards; continue to READ RECORD anyway

            // Step 4: READ RECORDs (SFI 1-3, records 1-8 — covers most cards)
            var pan: String? = null
            var expiry: String? = null

            outer@ for (sfi in 1..3) {
                for (record in 1..8) {
                    val resp = isoDep.transceive(buildReadRecord(sfi, record))
                    if (!isSuccess(resp)) continue
                    val extracted = extractPanAndExpiry(resp)
                    if (extracted.first != null) pan = extracted.first
                    if (extracted.second != null) expiry = extracted.second
                    if (pan != null && expiry != null) break@outer
                }
            }

            if (pan == null || expiry == null) return null

            val last4 = pan.takeLast(4)
            CardData(
                pan = pan,
                expiry = expiry,
                last4 = last4,
                cardKey = "${last4}_${expiry}"
            )
        } catch (e: Exception) {
            null
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    // --- APDU Builders ---

    private fun buildSelectApdu(aid: ByteArray): ByteArray {
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)
    }

    private fun buildGpo(): ByteArray {
        // GET PROCESSING OPTIONS with empty PDOL
        return byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, 0x02, 0x83.toByte(), 0x00, 0x00)
    }

    private fun buildReadRecord(sfi: Int, record: Int): ByteArray {
        val p2 = ((sfi shl 3) or 4).toByte()
        return byteArrayOf(0x00, 0xB2.toByte(), record.toByte(), p2, 0x00)
    }

    // --- Response Parsers ---

    private fun isSuccess(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        return sw1 == 0x90 && sw2 == 0x00
    }

    private fun parseAidFromPpse(response: ByteArray): ByteArray? {
        // Look for tag 0x4F (AID) in TLV structure
        return findTlvTag(response, 0x4F)
    }

    private fun extractPanAndExpiry(response: ByteArray): Pair<String?, String?> {
        // Tag 0x5A = PAN, Tag 0x5F24 = Expiry Date
        val panBytes = findTlvTag(response, 0x5A)
        val expiryBytes = findTlvTag2(response, 0x5F, 0x24)

        val pan = panBytes?.let { bytes ->
            bytes.joinToString("") { "%02X".format(it) }
                .trimEnd('F', 'f')  // Remove padding
                .filter { it.isDigit() }
        }

        val expiry = expiryBytes?.let { bytes ->
            // Format is YYMMDD, we want MMYY
            if (bytes.size >= 2) {
                val yy = "%02X".format(bytes[0])
                val mm = "%02X".format(bytes[1])
                "${mm}${yy}"
            } else null
        }

        return Pair(pan, expiry)
    }

    private fun findTlvTag(data: ByteArray, tag: Int): ByteArray? {
        var i = 0
        while (i < data.size - 1) {
            val currentTag = data[i].toInt() and 0xFF
            i++
            if (i >= data.size) break
            val length = data[i].toInt() and 0xFF
            i++
            if (i + length > data.size) break
            if (currentTag == tag) {
                return data.copyOfRange(i, i + length)
            }
            i += length
        }
        return null
    }

    private fun findTlvTag2(data: ByteArray, tag1: Int, tag2: Int): ByteArray? {
        // Two-byte tag search
        var i = 0
        while (i < data.size - 2) {
            val b1 = data[i].toInt() and 0xFF
            val b2 = data[i + 1].toInt() and 0xFF
            if (b1 == tag1 && b2 == tag2) {
                i += 2
                if (i >= data.size) break
                val length = data[i].toInt() and 0xFF
                i++
                if (i + length > data.size) break
                return data.copyOfRange(i, i + length)
            }
            i++
        }
        return null
    }
}
```

---

## Phase 5 — NFC Read Coordinator

Create:
`app/src/main/java/com/xpresstap/keyboard/nfc/NfcReadCoordinator.kt`

This class manages the NFC reader mode lifecycle and bridges card data back to the keyboard service.

```kotlin
package com.xpresstap.keyboard.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages NFC reader mode. Call startReading() when the user taps the NFC
 * toolbar button. The adapter enters reader mode for 10 seconds then auto-stops.
 * Result is delivered via callback on main thread.
 */
class NfcReadCoordinator(
    private val activity: Activity,
    private val onCardRead: (EmvCardReader.CardData) -> Unit,
    private val onError: (String) -> Unit
) {
    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val scope = CoroutineScope(Dispatchers.IO)

    val isNfcAvailable: Boolean get() = nfcAdapter != null
    val isNfcEnabled: Boolean get() = nfcAdapter?.isEnabled == true

    fun startReading() {
        if (nfcAdapter == null) {
            onError("NFC not available on this device")
            return
        }
        if (!nfcAdapter.isEnabled) {
            onError("NFC is disabled. Enable it in Settings.")
            return
        }

        val flags = NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        nfcAdapter.enableReaderMode(activity, { tag ->
            handleTag(tag)
        }, flags, Bundle())

        // Auto-stop after 10 seconds
        activity.window.decorView.postDelayed({
            stopReading()
        }, 10_000)
    }

    fun stopReading() {
        try { nfcAdapter?.disableReaderMode(activity) } catch (_: Exception) {}
    }

    private fun handleTag(tag: Tag) {
        scope.launch {
            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                withContext(Dispatchers.Main) { onError("Card not supported (not ISO-DEP)") }
                return@launch
            }

            val cardData = EmvCardReader.read(isoDep)

            withContext(Dispatchers.Main) {
                stopReading()
                if (cardData != null) {
                    onCardRead(cardData)
                } else {
                    onError("Could not read card. Try again or enter manually.")
                }
            }
        }
    }
}
```

---

## Phase 6 — CVV Dialog

Create:
`app/src/main/java/com/xpresstap/keyboard/nfc/CvvDialogHelper.kt`

This shows a dialog prompting the user for their CVV after a successful card read, if not already stored.

```kotlin
package com.xpresstap.keyboard.nfc

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.view.ViewGroup.LayoutParams
import android.widget.TextView

object CvvDialogHelper {

    fun promptIfNeeded(
        context: Context,
        cardData: EmvCardReader.CardData,
        onComplete: (pan: String, expiry: String, cvv: String) -> Unit
    ) {
        val existing = CvvVault.retrieveCvv(context, cardData.cardKey)
        if (existing != null) {
            // Already stored — proceed immediately
            onComplete(cardData.pan, cardData.expiry, existing)
            return
        }

        // Build dialog
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
        }

        val label = TextView(context).apply {
            text = "Card ending ${cardData.last4} — enter CVV"
        }

        val cvvInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            hint = "CVV"
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        layout.addView(label)
        layout.addView(cvvInput)

        AlertDialog.Builder(context)
            .setTitle("xPressTap")
            .setView(layout)
            .setPositiveButton("Save & Fill") { _, _ ->
                val cvv = cvvInput.text.toString().trim()
                if (cvv.length in 3..4) {
                    CvvVault.storeCvv(context, cardData.cardKey, cvv)
                    onComplete(cardData.pan, cardData.expiry, cvv)
                }
            }
            .setNegativeButton("Skip") { _, _ ->
                onComplete(cardData.pan, cardData.expiry, "")
            }
            .setCancelable(true)
            .show()
    }
}
```

---

## Phase 7 — Toolbar Button Integration

This phase wires the NFC button into HeliBoard's existing toolbar system.

### 7.1 Add string resources

In `app/src/main/res/values/strings.xml`, add:

```xml
<string name="nfc_toolbar_button_label">NFC Pay</string>
<string name="nfc_reading">Tap your card to the back of your phone…</string>
<string name="nfc_unavailable">NFC not available on this device</string>
<string name="nfc_fill_confirm">Filled card ending %1$s</string>
```

### 7.2 Add NFC toolbar key type

Locate HeliBoard's toolbar key enum — it will be in a file like `ToolbarKey.kt` or `KeyboardActionListener.kt`. Find where toolbar button types are defined (look for constants like `CLIPBOARD`, `EMOJI`, `SETTINGS`).

Add a new entry:
```kotlin
NFC_PAY,
```

### 7.3 Add toolbar button handler

In the class that handles toolbar button clicks (search for the `when` block that handles `ToolbarKey.CLIPBOARD` or similar), add a case:

```kotlin
ToolbarKey.NFC_PAY -> {
    handleNfcToolbarTap()
}
```

### 7.4 Implement the handler

In the keyboard service class (likely `KeyboardService.kt` or `LatinIME.kt`), add:

```kotlin
private fun handleNfcToolbarTap() {
    val activity = getActivityFromContext() ?: run {
        // Fallback: show toast if no activity context available
        showToast(getString(R.string.nfc_unavailable))
        return
    }

    val coordinator = NfcReadCoordinator(
        activity = activity,
        onCardRead = { cardData ->
            showToast(getString(R.string.nfc_reading))
            CvvDialogHelper.promptIfNeeded(this, cardData) { pan, expiry, cvv ->
                injectPaymentFields(pan, expiry, cvv)
                val msg = getString(R.string.nfc_fill_confirm, cardData.last4)
                showToast(msg)
            }
        },
        onError = { msg ->
            showToast(msg)
        }
    )
    showToast(getString(R.string.nfc_reading))
    coordinator.startReading()
}

private fun injectPaymentFields(pan: String, expiry: String, cvv: String) {
    val ic = currentInputConnection ?: return
    // Format PAN with spaces for readability (sites typically handle both)
    val formattedPan = pan.chunked(4).joinToString(" ")
    ic.commitText(formattedPan, 1)
    // Note: expiry and CVV injection into subsequent fields requires
    // user to tap each field. Advanced: use field-type detection (Phase 8).
}

private fun showToast(message: String) {
    android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
}
```

### 7.5 Getting Activity context from IME

The keyboard service is not an Activity, but NFC reader mode requires one. Add this helper — it uses `windowToken` to walk up to the host activity:

```kotlin
private fun getActivityFromContext(): Activity? {
    return try {
        val windowManager = getSystemService(WINDOW_SERVICE)
        val field = windowManager.javaClass.getDeclaredField("mContext")
        field.isAccessible = true
        field.get(windowManager) as? Activity
    } catch (_: Exception) {
        null
    }
}
```

Note: If this reflection approach fails on tested devices, add a `TODO` comment and note it for Phase 8 refinement. An alternative is a transparent overlay Activity launched via `startActivity`.

---

## Phase 8 — Field Detection (Best Effort)

This phase improves injection by detecting what type of field is focused.

In the toolbar button handler, before injecting, check `currentInputEditorInfo`:

```kotlin
private fun detectFieldType(): PaymentFieldType {
    val info = currentInputEditorInfo ?: return PaymentFieldType.UNKNOWN
    return when {
        info.hintText?.contains("card", ignoreCase = true) == true -> PaymentFieldType.CARD_NUMBER
        info.hintText?.contains("cvv", ignoreCase = true) == true -> PaymentFieldType.CVV
        info.hintText?.contains("expir", ignoreCase = true) == true -> PaymentFieldType.EXPIRY
        // autocomplete attribute — most reliable signal
        info.extras?.getString("android.inputmethodedit.autocomplete") == "cc-number" -> PaymentFieldType.CARD_NUMBER
        info.extras?.getString("android.inputmethodedit.autocomplete") == "cc-exp" -> PaymentFieldType.EXPIRY
        info.extras?.getString("android.inputmethodedit.autocomplete") == "cc-csc" -> PaymentFieldType.CVV
        else -> PaymentFieldType.UNKNOWN
    }
}

enum class PaymentFieldType { CARD_NUMBER, EXPIRY, CVV, UNKNOWN }
```

Use this to inject only the relevant value for the currently focused field, rather than always injecting the full PAN.

---

## Phase 9 — Branding

### 9.1 App icon

Replace HeliBoard's launcher icon with an xPressTap icon. Generate a simple vector icon using Android Studio's Asset Studio (or provide an SVG). The concept: a credit card with a tap/wave symbol. Place in:
- `app/src/main/res/mipmap-hdpi/ic_launcher.png`
- `app/src/main/res/mipmap-xhdpi/ic_launcher.png`
- `app/src/main/res/mipmap-xxhdpi/ic_launcher.png`
- `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`

If icon generation tooling is unavailable, create a placeholder `ic_launcher.xml` vector drawable and note it as TODO for designer.

### 9.2 About screen update

Find HeliBoard's About/Settings screen and update:
- App name → `xPressTap`
- Description → `Privacy-first keyboard with contactless card autofill`
- Version string — keep HeliBoard's versioning base, append `-xpresstap`
- Link to `https://xpresstap.com` in place of HeliBoard's GitHub link

---

## Phase 10 — F-Droid Compatibility Audit

Before considering the build done, verify F-Droid requirements:

```bash
# Check for any non-free network calls or proprietary SDKs
grep -r "firebase" app/src --include="*.kt" --include="*.gradle"
grep -r "google-services" app/src --include="*.kt" --include="*.gradle"
grep -r "crashlytics" app/src --include="*.kt" --include="*.gradle"
grep -r "analytics" app/src --include="*.kt" --include="*.gradle"
```

All of the above should return empty. HeliBoard upstream is already F-Droid clean — verify none of our additions introduced proprietary dependencies.

Check `app/build.gradle` for any `maven { url ... }` blocks that aren't `mavenCentral()` or `google()` — F-Droid requires reproducible builds from standard repos.

Verify `androidx.security:security-crypto` is available from `google()` — it is, so this is fine.

Note for F-Droid metadata: create `metadata/com.xpresstap.keyboard.yml` following the F-Droid metadata format. Minimum fields:
```yaml
Categories:
  - Writing
License: GPL-3.0-only
AuthorName: xPressTap
SourceCode: https://github.com/xpresstap/xpresstap-keyboard
IssueTracker: https://github.com/xpresstap/xpresstap-keyboard/issues
Summary: Privacy keyboard with NFC payment card autofill
Description: |
  xPressTap is a fork of HeliBoard adding contactless payment card reading.
  Tap your physical card to fill card number and expiry in any browser or app.
  CVV is stored encrypted in Android Keystore. No internet permission. 100% offline.
```

---

## Phase 11 — Build Release APK

```bash
# Generate a signing keystore (one-time — save this securely)
keytool -genkey -v \
  -keystore xpresstap-release-key.jks \
  -alias xpresstap \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000

# Configure signing in app/build.gradle under android { signingConfigs { release { ... } } }
# Then build:
./gradlew assembleRelease

# Verify APK
ls -lh app/build/outputs/apk/release/
```

The release APK should be under 15MB (HeliBoard upstream is ~8MB). If significantly larger, check for accidentally bundled resources.

---

## Testing Checklist

Before declaring the build complete, verify each item manually:

- [ ] App installs via `adb install` on a real NFC-capable Android device
- [ ] Keyboard appears in Settings → Language & Input
- [ ] Keyboard can be set as default
- [ ] All standard HeliBoard features work: typing, autocorrect, theme, clipboard
- [ ] Glide typing library loads correctly from Advanced settings
- [ ] NFC toolbar button is visible in the toolbar row
- [ ] On device without NFC: button is hidden or shows "not available" gracefully
- [ ] Tapping NFC button shows "Tap your card" toast
- [ ] Physical contactless Visa or Mastercard tapped: PAN injected into focused text field
- [ ] Expiry detected and stored in CardData
- [ ] CVV dialog appears for new card
- [ ] CVV dialog skipped for previously seen card (vault retrieval works)
- [ ] CVV stored/retrieved correctly via Android Keystore (no plaintext in logs)
- [ ] App has no internet permission (verify in `adb shell dumpsys package com.xpresstap.keyboard`)
- [ ] F-Droid metadata file is valid YAML

---

## Known Limitations to Document

Add a `LIMITATIONS.md` to the repo root noting:

1. **CVV is not on the NFC chip** — user must enter it once per card. This is by EMV design.
2. **Tokenized cards may not expose PAN** — virtual cards (Google Pay card numbers, some bank-issued digital cards) return a network token instead of the real PAN. The reader will return null for these.
3. **Activity context requirement** — NFC reader mode requires an Activity. The reflection-based context extraction may fail on some OEM Android variants. If it does, the button will show a toast explaining the limitation.
4. **Glide typing requires external library** — same as upstream HeliBoard. Not bundled due to licensing.
5. **iOS not supported** — Apple's Core NFC does not permit EMV card reading by third-party apps.

---

## Commit Strategy

Make atomic commits at the end of each phase:

```bash
git add .
git commit -m "phase-1: rename HeliBoard → xPressTap"
git commit -m "phase-2: add NFC permissions to manifest"
git commit -m "phase-3: add CvvVault encrypted storage"
git commit -m "phase-4: add EmvCardReader APDU implementation"
git commit -m "phase-5: add NfcReadCoordinator"
git commit -m "phase-6: add CVV prompt dialog"
git commit -m "phase-7: wire NFC button into HeliBoard toolbar"
git commit -m "phase-8: add payment field type detection"
git commit -m "phase-9: xPressTap branding"
git commit -m "phase-10: F-Droid compatibility audit"
git commit -m "phase-11: release APK build"
```

---

## If You Get Stuck

- If HeliBoard's toolbar key system has changed from what's described here, run `grep -r "CLIPBOARD\|ToolbarKey\|toolbarKey" app/src --include="*.kt" -l` to find the actual files and adapt accordingly.
- If the Activity context reflection fails, create a `NfcBridgeActivity` — a transparent `Activity` with `android:theme="@android:style/Theme.Translucent.NoTitleBar"` that the keyboard service launches via `startActivity`, passes the tag back via a local broadcast or shared ViewModel.
- EMV TLV parsing edge cases: some cards use constructed TLV (tag 0x70 wrapping inner tags). If PAN extraction returns null on real cards, add recursive TLV parsing to `EmvCardReader`.
- Post each phase's output and any errors before proceeding to the next phase.
