package com.xpresstap.keyboard.nfc

import android.app.Activity
import android.app.PendingIntent
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
 * Full-screen Activity that claims NFC foreground dispatch so competing apps
 * cannot intercept card taps. Uses enableForegroundDispatch (Intent-based)
 * rather than enableReaderMode because the IME window layer can prevent
 * enableReaderMode from receiving focus-based foreground ownership.
 */
class NfcForegroundActivity : Activity() {

    private var nfcAdapter: NfcAdapter? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView

    private val timeoutRunnable: Runnable = Runnable {
        if (::statusText.isInitialized) statusText.text = "Timed out — tap card to retry"
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
    }

    companion object {
        const val ACTION_STOP       = "com.xpresstap.keyboard.NFC_STOP"
        const val ACTION_CARD_READ  = "com.xpresstap.keyboard.NFC_CARD_READ"
        const val EXTRA_PAN         = "pan"
        const val EXTRA_EXPIRY      = "expiry"
        const val EXTRA_LAST4       = "last4"
        const val EXTRA_CARD_KEY    = "cardKey"
        const val EXTRA_ERROR       = "error"
        private const val TIMEOUT_MS = 20_000L

        @JvmStatic
        fun start(context: Context) {
            context.startActivity(
                Intent(context, NfcForegroundActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(230, 0, 0, 0))

        statusText = TextView(this).apply {
            text = "Tap your payment card"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 0)
        }
        root.addView(statusText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        root.addView(TextView(this).apply {
            text = "Tap anywhere to cancel"
            setTextColor(Color.argb(140, 255, 255, 255))
            textSize = 14f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = 80 })

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
            sendBroadcast(Intent(ACTION_CARD_READ).putExtra(EXTRA_ERROR, "NFC unavailable"))
            finish()
            return
        }

        // enableForegroundDispatch routes NFC intents to onNewIntent BEFORE global dispatch,
        // bypassing the app chooser regardless of which window layer has keyboard focus.
        val selfIntent = Intent(this, NfcForegroundActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, selfIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Accept any ISO-DEP (payment card) tag
        val techLists = arrayOf(arrayOf(IsoDep::class.java.name))
        adapter.enableForegroundDispatch(this, pendingIntent, null, techLists)
    }

    override fun onPause() {
        super.onPause()
        try { nfcAdapter?.disableForegroundDispatch(this) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timeoutRunnable)
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
    }

    // NFC tag arrives here via foreground dispatch
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val action = intent.action
        if (action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            @Suppress("DEPRECATION")
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null) handleTag(tag)
        }
    }

    private fun handleTag(tag: Tag) {
        handler.removeCallbacks(timeoutRunnable)
        statusText.text = "Reading card..."
        scope.launch {
            val isoDep = IsoDep.get(tag)
            val cardData = if (isoDep != null) EmvCardReader.read(isoDep) else null
            withContext(Dispatchers.Main) {
                if (cardData != null) {
                    val out = Intent(ACTION_CARD_READ).apply {
                        putExtra(EXTRA_PAN, cardData.pan)
                        putExtra(EXTRA_EXPIRY, cardData.expiry)
                        putExtra(EXTRA_LAST4, cardData.last4)
                        putExtra(EXTRA_CARD_KEY, cardData.cardKey)
                    }
                    sendBroadcast(out, null)
                    finish()
                } else {
                    statusText.text = "Could not read card — tap again, slower"
                    handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
                }
            }
        }
    }
}
