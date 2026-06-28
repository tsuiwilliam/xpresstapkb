package com.xpresstap.keyboard.nfc

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen opaque Activity that exclusively holds NFC reader mode.
 * Must NOT use a translucent theme — windowIsTranslucent=true prevents
 * enableReaderMode from claiming exclusive NFC dispatch, letting the
 * system fall through to the global intent chooser (competing apps).
 */
class NfcForegroundActivity : Activity() {

    private var nfcAdapter: NfcAdapter? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView

    private val timeoutRunnable: Runnable = Runnable {
        if (::statusText.isInitialized) statusText.text = "Timed out — tap your card to retry"
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    companion object {
        const val ACTION_STOP      = "com.xpresstap.keyboard.NFC_STOP"
        const val ACTION_CARD_READ = "com.xpresstap.keyboard.NFC_CARD_READ"
        const val EXTRA_PAN        = "pan"
        const val EXTRA_EXPIRY     = "expiry"
        const val EXTRA_LAST4      = "last4"
        const val EXTRA_CARD_KEY   = "cardKey"
        const val EXTRA_ERROR      = "error"
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

        // Dark full-screen overlay with instructions
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(220, 0, 0, 0))

        statusText = TextView(this).apply {
            text = "Tap your payment card"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 0)
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        root.addView(statusText, lp)

        val hint = TextView(this).apply {
            text = "Tap anywhere to cancel"
            setTextColor(Color.argb(160, 255, 255, 255))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val hintLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = 80 }
        root.addView(hint, hintLp)

        root.setOnClickListener { finish() }
        setContentView(root)

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
        statusText.text = "Tap your payment card"
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
        handler.post { statusText.text = "Reading card..." }
        scope.launch {
            val isoDep = IsoDep.get(tag)
            val cardData = if (isoDep != null) EmvCardReader.read(isoDep) else null
            withContext(Dispatchers.Main) {
                if (cardData != null) {
                    val intent = Intent(ACTION_CARD_READ)
                    intent.putExtra(EXTRA_PAN, cardData.pan)
                    intent.putExtra(EXTRA_EXPIRY, cardData.expiry)
                    intent.putExtra(EXTRA_LAST4, cardData.last4)
                    intent.putExtra(EXTRA_CARD_KEY, cardData.cardKey)
                    sendBroadcast(intent, null)
                    finish()
                } else {
                    statusText.text = "Could not read card — tap again, slower"
                    handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
                }
            }
        }
    }
}
