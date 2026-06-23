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
