package com.everydayecho.memorykeeper.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.everydayecho.memorykeeper.llm.LlmConfig
import com.everydayecho.memorykeeper.llm.LlmProvider

/**
 * EncryptedSharedPreferences wrapper holding the active AI provider and a
 * separate key / model / base URL per provider, so switching providers
 * doesn't lose the others' settings.
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

    var provider: LlmProvider
        get() = LlmProvider.fromName(prefs.getString(KEY_PROVIDER, null))
        set(value) {
            prefs.edit().putString(KEY_PROVIDER, value.name).apply()
        }

    fun apiKey(p: LlmProvider): String {
        // Legacy (pre multi-provider) Anthropic key lived under "anthropic_api_key".
        val legacy = if (p == LlmProvider.ANTHROPIC) prefs.getString("anthropic_api_key", "") else ""
        return prefs.getString("key_${p.name}", null) ?: legacy.orEmpty()
    }

    fun setApiKey(p: LlmProvider, value: String) {
        prefs.edit().putString("key_${p.name}", value).apply()
    }

    fun model(p: LlmProvider): String {
        val legacy = if (p == LlmProvider.ANTHROPIC) prefs.getString("anthropic_model", null) else null
        return prefs.getString("model_${p.name}", null) ?: legacy ?: p.defaultModel
    }

    fun setModel(p: LlmProvider, value: String) {
        prefs.edit().putString("model_${p.name}", value).apply()
    }

    fun baseUrl(p: LlmProvider): String =
        prefs.getString("url_${p.name}", null) ?: p.defaultBaseUrl

    fun setBaseUrl(p: LlmProvider, value: String) {
        prefs.edit().putString("url_${p.name}", value).apply()
    }

    fun activeConfig(): LlmConfig {
        val p = provider
        return LlmConfig(
            provider = p,
            apiKey = apiKey(p),
            model = model(p).ifBlank { p.defaultModel },
            baseUrl = baseUrl(p).ifBlank { p.defaultBaseUrl },
        )
    }

    companion object {
        private const val KEY_PROVIDER = "llm_provider"
    }
}
