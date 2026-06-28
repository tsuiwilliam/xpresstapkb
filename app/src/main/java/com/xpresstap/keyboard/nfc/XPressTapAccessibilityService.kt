package com.xpresstap.keyboard.nfc

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * Scans the active window (browser, native payment app, etc.) for payment form fields
 * and fills PAN + expiry via ACTION_SET_TEXT after a card is read via NFC.
 */
class XPressTapAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private val cardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val error = intent.getStringExtra(NfcForegroundActivity.EXTRA_ERROR)
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                return
            }
            val pan    = intent.getStringExtra(NfcForegroundActivity.EXTRA_PAN) ?: return
            val expiry = intent.getStringExtra(NfcForegroundActivity.EXTRA_EXPIRY) ?: return
            val last4  = intent.getStringExtra(NfcForegroundActivity.EXTRA_LAST4) ?: pan.takeLast(4)
            // Short delay so the NfcForegroundActivity finishes and previous app regains focus
            handler.postDelayed({ fillPaymentForm(pan, expiry, last4) }, 400)
        }
    }

    override fun onServiceConnected() {
        registerReceiver(cardReceiver, IntentFilter(NfcForegroundActivity.ACTION_CARD_READ))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(cardReceiver) } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}

    private fun fillPaymentForm(pan: String, expiry: String, last4: String) {
        val root = rootInActiveWindow ?: return
        val fields = mutableListOf<Pair<PaymentFieldDetector.FieldType, AccessibilityNodeInfo>>()
        collectPaymentFields(root, fields)

        if (fields.isEmpty()) {
            // No payment fields visible — card data will be used via InputConnection when keyboard refocuses
            return
        }

        var filled = 0
        val panFormatted = PaymentFieldDetector.formatPan(pan)

        for ((type, node) in fields) {
            // Skip fields that already have matching content
            val existing = node.text?.toString() ?: ""
            when (type) {
                PaymentFieldDetector.FieldType.CARD_NUMBER -> {
                    if (existing.filter { it.isDigit() }.length < 13) {
                        setText(node, panFormatted)
                        filled++
                    }
                }
                PaymentFieldDetector.FieldType.EXPIRY -> {
                    if (existing.filter { it.isDigit() }.length < 4) {
                        setText(node, expiry)
                        filled++
                    }
                }
                else -> {}
            }
        }

        if (filled > 0) {
            Toast.makeText(this, "xPressTap: filled card ending $last4", Toast.LENGTH_SHORT).show()
        }
    }

    private fun collectPaymentFields(
        node: AccessibilityNodeInfo,
        results: MutableList<Pair<PaymentFieldDetector.FieldType, AccessibilityNodeInfo>>
    ) {
        if (node.isEditable) {
            val compat = AccessibilityNodeInfoCompat.wrap(node)
            val type = PaymentFieldDetector.classify(compat)
            if (type != PaymentFieldDetector.FieldType.UNKNOWN) {
                results.add(Pair(type, node))
            }
        }
        for (i in 0 until node.childCount) {
            collectPaymentFields(node.getChild(i) ?: continue, results)
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
