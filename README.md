# xPressTap Keyboard

Android keyboard with NFC payment card autofill and gesture/swipe typing.

**v1.1.0 (2026-06-29):** Clean AOSP LatinIME base — Apache-2.0 only, no GPL, no proprietary code.

---

## Releases

| Version | Base | Swipe | NFC | Notes |
|---------|------|-------|-----|-------|
| **v1.1.0** | AOSP LatinIME (Apache-2.0) | ✓ pure-Java decoder | ✓ EMV BER-TLV | Clean build, publicly distributable |
| v4.0-alpha8 | HeliBoard (GPL-3.0) | ✓ Google gesture lib | ✓ | Internal testing only — see legal notice |

**Latest APK:** https://github.com/tsuiwilliam/xpresstapkb/releases/tag/v1.1.0

---

## v1.1.0 — What's working

**Confirmed on emulator (2026-06-29):**

| Feature | Status |
|---------|--------|
| Tap typing with word suggestions | ✓ |
| Swipe / gesture typing — real words suggested | ✓ |
| NFC tap → EMV PAN (card number) read | ✓ |
| NFC tap → EMV expiry date read | ✓ |
| NFC tap → cardholder name read | ✓ |
| Autofill card number field | ✓ |
| Autofill expiry field | ✓ |
| No proprietary code — distributable under Apache-2.0 | ✓ |

---

## How it works

### Swipe typing

xPressTap uses a pure-Java swipe decoder with no native library dependency:

1. AOSP `PointerTracker` detects gesture start when `GestureEnabler.shouldHandleGesture()` is true.
2. `BatchInputArbiter` accumulates touch points; on UP event fires `onEndBatchInput()`.
3. `DictionaryFacilitatorImpl.getSuggestions(isBatchMode=true)` calls `XpSwipeDecoder.decode()`.
4. Decoder resamples path to 32 points → maps each to nearest key → deduplicates → finds words whose character sequence is a compatible subsequence of the traced key sequence, scored by shape similarity + word frequency.
5. Top suggestions appear in the strip; best match is committed on spacebar.

**Key fixes for gesture enable chain:**
- `config_gesture_input_enabled_by_build_config = true` (was false in AOSP)
- `hasAtLeastOneInitializedMainDictionary()` checks `mXpDictionary.isInitialized()` so `GestureEnabler` learns the dictionary is ready
- `XpWordList.addOnLoadedCallback()` fires `setMainDictionaryAvailability(true)` async after word list loads

### NFC card fill

1. `NfcForegroundActivity` issues APDU SELECT + READ RECORD, parses EMV BER-TLV recursively.
2. Extracts tag 5A (PAN), 5F24 (expiry YYMMDD), 5F20 (cardholder name).
3. Sends local broadcast to `LatinIME`.
4. `PaymentFieldDetector.classify()` identifies the focused field (card number vs expiry vs name).
5. Fills the field via `InputConnection.commitText()`.

---

## Build

```
applicationId: io.xpresstap.keyboard
versionName:   1.1.0
minSdk:        21
targetSdk:     34
Base:          AOSP LatinIME (Apache-2.0)
NDK:           not required — pure Java
```

Signing credentials go in `local.properties` (not tracked):
```
storeFile=../keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

---

## License

Apache License 2.0. See [LICENSE](LICENSE-Apache-2.0).

No GPL code. No proprietary code. Safe for public distribution.

---

## Prior HeliBoard-based builds (internal only)

Releases v4.0-alpha1 through v4.0-alpha8 are based on HeliBoard (GPL-3.0 + Google gesture library).
They are for **internal testing only** and cannot be publicly distributed.
See the legal notice at https://github.com/tsuiwilliam/xpresstapkb/releases/tag/v4.0-alpha8.
