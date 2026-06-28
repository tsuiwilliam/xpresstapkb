package com.xpresstap.keyboard.nfc

import android.view.inputmethod.EditorInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

object PaymentFieldDetector {

    enum class FieldType { CARD_NUMBER, EXPIRY, CVV, UNKNOWN }

    private val CARD_NUMBER_WORDS = setOf(
        "card number", "card no", "card num", "credit card", "debit card", "pan",
        "cardnumber", "ccnumber", "card_number", "credit_card", "cardno"
    )
    private val EXPIRY_WORDS = setOf(
        "expir", "exp date", "valid thru", "valid through", "mm/yy", "mm/yyyy",
        "expiry", "expiration", "exp_date", "card_expiry", "expmonth", "expyear"
    )
    private val CVV_WORDS = setOf(
        "cvv", "cvc", "cvn", "csc", "security code", "card code",
        "security_code", "card_code", "cvv2", "cvc2"
    )

    @JvmStatic
    fun classify(editorInfo: EditorInfo): FieldType {
        val hint  = editorInfo.hintText?.toString()?.lowercase() ?: ""
        val label = editorInfo.label?.toString()?.lowercase() ?: ""
        val name  = editorInfo.fieldName?.lowercase() ?: ""
        return classifyStrings(hint, label, name)
    }

    @JvmStatic
    fun classify(node: AccessibilityNodeInfoCompat): FieldType {
        val hint = node.hintText?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val id   = node.viewIdResourceName?.lowercase() ?: ""
        return classifyStrings(hint, desc, id)
    }

    private fun classifyStrings(vararg texts: String): FieldType {
        val combined = texts.joinToString(" ")
        if (CARD_NUMBER_WORDS.any { combined.contains(it) }) return FieldType.CARD_NUMBER
        if (EXPIRY_WORDS.any { combined.contains(it) }) return FieldType.EXPIRY
        if (CVV_WORDS.any { combined.contains(it) }) return FieldType.CVV
        return FieldType.UNKNOWN
    }

    @JvmStatic
    fun isLikelyPaymentForm(editorInfo: EditorInfo): Boolean {
        val hint  = editorInfo.hintText?.toString()?.lowercase() ?: ""
        val label = editorInfo.label?.toString()?.lowercase() ?: ""
        val combined = "$hint $label"
        val paymentWords = setOf("card number", "card no", "credit card", "debit card",
            "cvv", "cvc", "expir", "payment card")
        return paymentWords.any { combined.contains(it) }
    }

    @JvmStatic
    fun formatPan(pan: String): String = pan.chunked(4).joinToString(" ")
}
