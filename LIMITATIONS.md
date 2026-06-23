# Known Limitations

1. **CVV is not on the NFC chip** — User must enter CVV once per card. This is by EMV design; the CVV is never transmitted over contactless.

2. **Tokenized cards may not expose PAN** — Virtual cards (Google Pay, some bank-issued digital cards) return a network token instead of the real PAN. The reader returns null for these.

3. **Activity context requirement** — NFC reader mode requires an Activity. The reflection-based context extraction may fail on some OEM Android variants. If it does, the NFC button shows a toast explaining the limitation. Alternative: transparent overlay NfcBridgeActivity.

4. **Glide typing requires external library** — Same as upstream HeliBoard. Not bundled due to licensing.

5. **iOS not supported** — Apple's Core NFC does not permit EMV card reading by third-party apps.
