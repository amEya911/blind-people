package com.example.blindpeople.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureApiKeyStore(
    private val context: Context,
) : ApiKeyProvider {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun getApiKey(): String = prefs.getString(KEY_GEMINI, "").orEmpty()

    fun setApiKey(value: String) {
        prefs.edit().putString(KEY_GEMINI, value.trim()).apply()
    }

    companion object {
        private const val KEY_GEMINI = "gemini_api_key"
    }
}

