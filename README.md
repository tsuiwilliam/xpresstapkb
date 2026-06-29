# xPressTap Keyboard

Android keyboard with NFC payment card autofill and gesture/swipe typing.
Built on [HeliBoard](https://github.com/HeliBorg/HeliBoard) (Apache-2.0 / GPL-3.0).

---

## Milestone: v4.0-alpha8 — WORKING ✓

**Confirmed working as of 2026-06-29.**

| Feature | Status |
|---------|--------|
| NFC tap → fill PAN in card number field | ✓ |
| NFC tap → fill expiry in expiry field | ✓ |
| NFC tap → paste PAN + expiry in plain-text fields (e.g. Keep notes) | ✓ |
| Chrome / Stripe pay forms (no EditorInfo metadata) | ✓ |
| Gesture / swipe typing — real words | ✓ |
| Gesture works in auto-caps (uppercase) mode | ✓ |

**APK:** https://github.com/tsuiwilliam/xpresstapkb/releases/tag/v4.0-alpha8

> **Internal testing only.** See [Legal notice](#legal--internal-only-notice) below.

---

## How it works

### NFC card fill
1. `NfcForegroundActivity` reads the card via APDU, extracts PAN + expiry.
2. Sends a local broadcast to `LatinIME`.
3. `tryFillViaInputConnection()` calls `PaymentFieldDetector.classify()` to identify the focused field.
4. Chrome/WebView fields expose no hint/label/fieldName in `EditorInfo` → falls back to `inputType & TYPE_MASK_CLASS` check (numeric = payment field).
5. Fills card number digits one by one via `commitText`, sets `mPanWasFilled = true`, then polls for the expiry field (up to 12 s / 8 retries).
6. Catches Chrome's field auto-advance via `onStartInputInternal` hook — Chrome keeps the keyboard visible when moving between fields, so only `onStartInput` fires, not `onStartInputView`.

### Gesture / swipe typing
- `libjni_latinimegoogle.so` is bundled for all 4 ABIs (see [jniLibs](app/src/main/jniLibs/)). Checksums match HeliBoard's hardcoded values.
- `JniUtils` loads it via `System.loadLibrary("jni_latinimegoogle")` — no user action required.
- Java fallback (`gesturePathToLetters` in `InputLogic.java`) handles devices where the native lib fails to load. Uses zone-transition + minimum-dwell sampling and normalises uppercase key codes.

### Key bugs fixed in this build
| Bug | Root cause | Fix |
|-----|-----------|-----|
| Gesture produced no text | `InputPointers.set()` is a shallow copy — PointerTracker resets the arrays after gesture, zeroing `getPointerSize()` | Deep-copy coordinates into `mLastGestureX/Y/Size` in `onEndBatchInput` before async processing |
| Gesture broken in auto-caps | Shifted keyboard emits codes 65–90; old filter `code < 'a'` rejected them all | `code += 32` normalisation in `gesturePathToLetters` |
| Expiry missing on Chrome/Stripe | Chrome sends empty `EditorInfo` for all web inputs; `classify()` returned UNKNOWN | Check `inputType & TYPE_MASK_CLASS`; numeric UNKNOWN → route by `mPanWasFilled` sequence |
| Expiry double-filled card field | 1.5 s retry timer fired while still on card field | `mPanWasFilled` guard + max-8-retries counter |
| Swipe broke on real device (v15) | `GestureLibExtractor` saved Google lib (wrong JNI package) to `filesDir`; built-in lib was skipped | Deleted extractor; `App.onCreate` purges `filesDir/libjni_latinime.so` before `JniUtils` static init |

---

## Build

CI: GitHub Actions (Ubuntu, JDK 21). Local builds fail — NDK path has a space (`William Theos`) that breaks the NDK build.

```
Repo:    https://github.com/tsuiwilliam/xpresstapkb
Branch:  xpresstap-main
Package: com.xpresstap.keyboard (debug: com.xpresstap.keyboard.debug)
```

Push a `v*` tag to trigger the release APK job. Debug APKs build on every branch push.

**Signing secrets:** `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. If the keystore fails validation, CI generates a temporary key (APK works but requires uninstall between builds with different keys).

---

## Legal / internal-only notice

`libjni_latinimegoogle.so` is Google proprietary code from Gboard, redistributed here for **internal testing only**. It must be removed before any public distribution. For public builds, users obtain it themselves via the built-in import flow (Settings → Advanced → Load gesture typing library).

HeliBoard is dual-licensed Apache-2.0 + GPL-3.0. Public distribution of this fork requires GPL-3.0 compliance (source disclosure, same license on derivative works).

---

## Path to production / clean branded build

See the discussion in this repo for options to ship xPressTap Keyboard publicly without the proprietary lib dependency.

---

## Upstream

Based on [HeliBoard](https://github.com/HeliBorg/HeliBoard) by Helium314 et al.
Original AOSP LatinIME · OpenBoard contributors.

HeliBoard is licensed under GPL-3.0 (with Apache-2.0 for AOSP portions).
See [LICENSE](LICENSE) and [LICENSE-Apache-2.0](LICENSE-Apache-2.0).
