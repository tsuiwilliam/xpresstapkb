package com.xpresstap.keyboard.nfc

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object CvvVault {

    private const val PREFS_FILE = "xpresstap_cvv_vault"

    fun storeCvv(context: Context, cardKey: String, cvv: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().putString(cardKey, cvv).apply()
    }

    fun retrieveCvv(context: Context, cardKey: String): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs.getString(cardKey, null)
    }

    fun hasCvv(context: Context, cardKey: String): Boolean {
        return retrieveCvv(context, cardKey) != null
    }

    fun deleteCard(context: Context, cardKey: String) {
        val prefs = getEncryptedPrefs(context)
        prefs.edit().remove(cardKey).apply()
    }

    fun listCards(context: Context): Set<String> {
        return getEncryptedPrefs(context).all.keys
    }

    private fun getEncryptedPrefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
}
