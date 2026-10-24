package com.sengine.aibuilder.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local model/provider settings. Only non-secret profile details use normal preferences. */
class AiBuilderSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("sengine_ai_builder", Context.MODE_PRIVATE)
    private val keys = SecureApiKeyStore(appContext)

    init {
        migrateLegacySettings()
    }

    fun activeProvider(): AiProviderKind =
        AiProviderKind.fromId(prefs.getString(KEY_ACTIVE_PROVIDER, null)) ?: AiProviderKind.OPENROUTER

    fun setActiveProvider(value: AiProviderKind) {
        prefs.edit().putString(KEY_ACTIVE_PROVIDER, value.id).apply()
    }

    fun profile(kind: AiProviderKind = activeProvider()): AiProviderProfile {
        val model = prefs.getString(modelKey(kind), null)
            ?.takeIf { runCatching { AiProviderProfileRules.validateModel(it); true }.getOrDefault(false) }
            ?: kind.defaultModel
        val endpoint = when (kind) {
            AiProviderKind.OPENAI_COMPATIBLE -> prefs.getString(endpointKey(kind), null).orEmpty()
            else -> kind.defaultEndpoint
        }
        return AiProviderProfile(kind, model, endpoint)
    }

    fun activeProfile(): AiProviderProfile = profile(activeProvider())
    fun model(kind: AiProviderKind = activeProvider()): String = profile(kind).model

    fun saveProfile(kind: AiProviderKind, model: String, endpoint: String = "") {
        val normalizedModel = AiProviderProfileRules.validateModel(model)
        val editor = prefs.edit().putString(modelKey(kind), normalizedModel)
        if (kind == AiProviderKind.OPENAI_COMPATIBLE) {
            if (endpoint.isBlank()) editor.remove(endpointKey(kind))
            else editor.putString(endpointKey(kind), AiProviderProfileRules.validateCustomEndpoint(endpoint))
        } else if (endpoint.isNotBlank()) {
            require(endpoint == kind.defaultEndpoint) { "This provider uses its official API endpoint." }
        }
        editor.apply()
    }

    fun apiKey(kind: AiProviderKind = activeProvider()): String? = keys.read(kind)
    fun hasApiKey(kind: AiProviderKind = activeProvider()): Boolean = apiKey(kind) != null

    fun saveApiKey(kind: AiProviderKind, value: String) {
        keys.save(kind, AiProviderProfileRules.validateApiKey(value))
    }

    fun clearApiKey(kind: AiProviderKind = activeProvider()) = keys.clear(kind)

    fun useDemoMode(): Boolean = prefs.getBoolean(KEY_DEMO_MODE, false)
    fun setUseDemoMode(value: Boolean) { prefs.edit().putBoolean(KEY_DEMO_MODE, value).apply() }

    fun rememberOnDevice(): Boolean = prefs.getBoolean(KEY_REMEMBER, true)
    fun setRememberOnDevice(value: Boolean) { prefs.edit().putBoolean(KEY_REMEMBER, value).apply() }

    fun agentMaxPasses(): Int = prefs.getInt(KEY_AGENT_PASSES, 3).coerceIn(1, 5)
    fun setAgentMaxPasses(value: Int) {
        require(value in 1..5) { "Autonomous agent passes must be between 1 and 5." }
        prefs.edit().putInt(KEY_AGENT_PASSES, value).apply()
    }

    private fun migrateLegacySettings() {
        // Preserve an existing model preference and encrypted OpenRouter key, but discard all
        // Supabase URL/key/session settings so the retired backend is no longer used or retained.
        val legacyModel = prefs.getString("ai_model", null)
        if (!prefs.contains(modelKey(AiProviderKind.OPENROUTER)) && !legacyModel.isNullOrBlank()) {
            runCatching { prefs.edit().putString(modelKey(AiProviderKind.OPENROUTER), AiProviderProfileRules.validateModel(legacyModel)).apply() }
        }
        if (prefs.getBoolean("use_direct_openrouter", false)) {
            prefs.edit().putString(KEY_ACTIVE_PROVIDER, AiProviderKind.OPENROUTER.id)
                .putBoolean(KEY_DEMO_MODE, false).apply()
        }
        keys.migrateLegacyOpenRouterKey()
        prefs.edit()
            .remove("supabase_url")
            .remove("supabase_anon_key")
            .remove("ai_model")
            .remove("use_direct_openrouter")
            .apply()
    }

    private fun modelKey(kind: AiProviderKind) = "profile_${kind.id}_model"
    private fun endpointKey(kind: AiProviderKind) = "profile_${kind.id}_endpoint"

    companion object {
        private const val KEY_ACTIVE_PROVIDER = "active_provider"
        private const val KEY_DEMO_MODE = "use_demo_mode"
        private const val KEY_REMEMBER = "remember_memory"
        private const val KEY_AGENT_PASSES = "agent_max_passes"
    }
}

/** API keys are AES-GCM encrypted with a non-exportable Android Keystore key. */
private class SecureApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("sengine_ai_provider_keys", Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences("sengine_ai_session", Context.MODE_PRIVATE)
    private val alias = "sengine.ai.provider.keys.v1"
    private val legacyAlias = "sengine.ai.supabase.session.v1"

    @Synchronized fun save(kind: AiProviderKind, value: String) {
        prefs.edit().putString(keyName(kind), encrypt(value, key(alias))).apply()
    }

    @Synchronized fun read(kind: AiProviderKind): String? {
        val stored = prefs.getString(keyName(kind), null)
        if (stored != null) return runCatching { decrypt(stored, key(alias)) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (kind == AiProviderKind.OPENROUTER) {
            val legacy = legacyPrefs.getString("encrypted_openrouter_key", null) ?: return null
            val recovered = runCatching { decrypt(legacy, existingKey(legacyAlias) ?: return null) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: return null
            save(kind, recovered)
            clearLegacyData()
            return recovered
        }
        return null
    }

    @Synchronized fun migrateLegacyOpenRouterKey() {
        if (prefs.contains(keyName(AiProviderKind.OPENROUTER))) {
            clearLegacyData()
            return
        }
        val legacy = legacyPrefs.getString("encrypted_openrouter_key", null)
        if (legacy != null) {
            val recovered = runCatching { decrypt(legacy, existingKey(legacyAlias) ?: return@runCatching null) }.getOrNull()
            if (!recovered.isNullOrBlank()) save(AiProviderKind.OPENROUTER, recovered)
        }
        clearLegacyData()
    }

    @Synchronized fun clear(kind: AiProviderKind) {
        prefs.edit().remove(keyName(kind)).apply()
        if (kind == AiProviderKind.OPENROUTER) clearLegacyData()
    }

    private fun clearLegacyData() {
        legacyPrefs.edit().remove("encrypted_openrouter_key").remove("encrypted_session").apply()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(legacyAlias)
        }
    }

    private fun keyName(kind: AiProviderKind) = "encrypted_${kind.id}_api_key"

    private fun encrypt(value: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, combined, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, combined, cipher.iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String, key: SecretKey): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size < 29) throw IllegalArgumentException("Invalid encrypted API key")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        return String(cipher.doFinal(combined.copyOfRange(12, combined.size)), Charsets.UTF_8)
    }

    private fun existingKey(keyAlias: String): SecretKey? =
        (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(keyAlias, null) as? SecretKey)

    private fun key(keyAlias: String): SecretKey {
        existingKey(keyAlias)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
}
