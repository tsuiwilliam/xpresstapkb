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
            onComplete(cardData.pan, cardData.expiry, existing)
            return
        }

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
