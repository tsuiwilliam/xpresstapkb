package com.xpresstap.keyboard.nfc

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent, non-interactive Activity that holds NFC reader mode exclusively,
 * preventing any other NFC app from intercepting card taps while active.
 * Launched by LatinIME; finishes immediately after card is read or on timeout.
 */
class NfcForegroundActivity : Activity() {

    private var nfcAdapter: NfcAdapter? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        Toast.makeText(this, "NFC timeout — tap card to retry", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val ACTION_STOP     = "com.xpresstap.keyboard.NFC_STOP"
        const val ACTION_CARD_READ = "com.xpresstap.keyboard.NFC_CARD_READ"
        const val EXTRA_PAN      = "pan"
        const val EXTRA_EXPIRY   = "expiry"
        const val EXTRA_LAST4    = "last4"
        const val EXTRA_CARD_KEY = "cardKey"
        const val EXTRA_ERROR    = "error"
        private const val TIMEOUT_MS = 20_000L

        @JvmStatic
        fun start(context: Context) {
            context.startActivity(
                Intent(context, NfcForegroundActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }

        @JvmStatic
        fun stop(context: Context) {
            context.sendBroadcast(Intent(ACTION_STOP))
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must NOT use FLAG_NOT_FOCUSABLE — NFC enableReaderMode requires the Activity
        // to have true foreground focus ownership or the system ignores our registration
        // and falls through to the global NFC intent dispatch (competing app chooser).
        window.setBackgroundDrawableResource(android.R.color.transparent)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        registerReceiver(stopReceiver, IntentFilter(ACTION_STOP))
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        if (adapter == null || !adapter.isEnabled) {
            sendBroadcast(Intent(ACTION_CARD_READ).putExtra(EXTRA_ERROR, "NFC unavailable on this device"))
            finish()
            return
        }
        adapter.enableReaderMode(
            this,
            ::handleTag,
            NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
        Toast.makeText(this, "xPressTap — tap your payment card", Toast.LENGTH_LONG).show()
    }

    override fun onPause() {
        super.onPause()
        try { nfcAdapter?.disableReaderMode(this) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timeoutRunnable)
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
    }

    private fun handleTag(tag: Tag) {
        handler.removeCallbacks(timeoutRunnable)
        scope.launch {
            val isoDep = IsoDep.get(tag)
            val cardData = if (isoDep != null) EmvCardReader.read(isoDep) else null
            withContext(Dispatchers.Main) {
                val intent = Intent(ACTION_CARD_READ)
                if (cardData != null) {
                    intent.putExtra(EXTRA_PAN, cardData.pan)
                    intent.putExtra(EXTRA_EXPIRY, cardData.expiry)
                    intent.putExtra(EXTRA_LAST4, cardData.last4)
                    intent.putExtra(EXTRA_CARD_KEY, cardData.cardKey)
                } else {
                    intent.putExtra(EXTRA_ERROR, "Could not read card — try again")
                }
                sendBroadcast(intent, null)
                // Finish first so previous app regains focus before accessibility service fills
                finish()
            }
        }
    }
}
