package com.everydayecho.memorykeeper.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Tiny wrapper around EncryptedSharedPreferences for storing the
 * Anthropic API key locally. Keys never leave the device unencrypted.
 */
class SecurePreferences(context: Context) {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "mk_secure_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_API, value).apply()
        }

    var apiModel: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) {
            prefs.edit().putString(KEY_MODEL, value).apply()
        }

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    companion object {
        private const val KEY_API = "anthropic_api_key"
        private const val KEY_MODEL = "anthropic_model"
        const val DEFAULT_MODEL = "claude-opus-4-7"
    }
}
