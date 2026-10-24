package com.sengine.aibuilder.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.sengine.aibuilder.memory.AiMemoryEvent
import com.sengine.aibuilder.memory.AiMemoryStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only public Supabase client configuration lives in ordinary preferences. */
data class SupabasePublicConfig(val projectUrl: String, val anonKey: String, val model: String) {
    fun endpoint(path: String): String = projectUrl.trimEnd('/') + path
}

data class SupabaseSession(val accessToken: String, val refreshToken: String, val expiresAtMs: Long)

data class AuthOutcome(val message: String, val signedIn: Boolean)

class AiBuilderSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sengine_ai_builder", Context.MODE_PRIVATE)
    private val sessions = SecureSessionStore(context.applicationContext)

    fun config(): SupabasePublicConfig? {
        val url = prefs.getString("supabase_url", "").orEmpty()
        val key = prefs.getString("supabase_anon_key", "").orEmpty()
        val model = prefs.getString("ai_model", DEFAULT_MODEL).orEmpty()
        if (!validUrl(url) || key.length !in 20..2048 || key.any(Char::isWhitespace) || !validModel(model)) return null
        return SupabasePublicConfig(url.trimEnd('/'), key, model)
    }

    fun saveConfig(projectUrl: String, anonKey: String, model: String) {
        val url = projectUrl.trim().trimEnd('/')
        val key = anonKey.trim()
        val normalizedModel = model.trim()
        require(validUrl(url)) { "Supabase project URL must be a valid HTTPS URL without a path, query, or fragment." }
        require(key.length in 20..2048 && key.none(Char::isWhitespace)) { "Supabase publishable/anon key is missing or malformed." }
        require(validModel(normalizedModel)) { "Model name contains unsupported characters." }
        prefs.edit().putString("supabase_url", url).putString("supabase_anon_key", key)
            .putString("ai_model", normalizedModel).apply()
    }

    fun model(): String = prefs.getString("ai_model", DEFAULT_MODEL).orEmpty().takeIf(::validModel) ?: DEFAULT_MODEL

    fun saveModel(value: String) {
        val normalized = value.trim()
        require(validModel(normalized)) { "Model name contains unsupported characters." }
        prefs.edit().putString("ai_model", normalized).apply()
    }

    fun useDemoMode(): Boolean = prefs.getBoolean("use_demo_mode", true)
    fun setUseDemoMode(value: Boolean) {
        prefs.edit().putBoolean("use_demo_mode", value).apply()
        if (value) prefs.edit().putBoolean("use_direct_openrouter", false).apply()
    }
    fun useDirectOpenRouterMode(): Boolean = prefs.getBoolean("use_direct_openrouter", false)
    fun setUseDirectOpenRouterMode(value: Boolean) {
        prefs.edit().putBoolean("use_direct_openrouter", value).apply()
        if (value) prefs.edit().putBoolean("use_demo_mode", false).apply()
    }
    fun rememberOnDevice(): Boolean = prefs.getBoolean("remember_memory", true)
    fun setRememberOnDevice(value: Boolean) { prefs.edit().putBoolean("remember_memory", value).apply() }
    fun hasSession(): Boolean = sessions.read() != null
    fun clearSession() = sessions.clear()
    fun openRouterApiKey(): String? = sessions.readOpenRouterKey()
    fun hasOpenRouterApiKey(): Boolean = openRouterApiKey() != null
    fun saveOpenRouterApiKey(value: String) {
        val key = value.trim()
        require(key.length <= 512 && key.matches(Regex("sk-or-v1-[A-Za-z0-9_-]{16,}"))) {
            "Enter a valid OpenRouter API key (it should start with sk-or-v1-)."
        }
        sessions.saveOpenRouterKey(key)
    }
    fun clearOpenRouterApiKey() = sessions.clearOpenRouterKey()

    internal fun sessionStore() = sessions

    private fun validUrl(value: String): Boolean = try {
        val uri = URI(value.trim())
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
            uri.rawQuery == null && uri.rawFragment == null
    } catch (_: Exception) { false }

    private fun validModel(value: String): Boolean = value.length in 3..120 &&
        value.matches(Regex("[A-Za-z0-9._:/-]+"))

    companion object {
        const val DEFAULT_MODEL = "openai/gpt-4o-mini"
    }
}

/** AES-GCM protected Supabase session; user passwords are never persisted. */
internal class SecureSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("sengine_ai_session", Context.MODE_PRIVATE)
    private val alias = "sengine.ai.supabase.session.v1"

    @Synchronized fun save(session: SupabaseSession) {
        val plain = JSONObject().put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken).put("expiresAtMs", session.expiresAtMs).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, combined, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, combined, cipher.iv.size, encrypted.size)
        prefs.edit().putString("encrypted_session", Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    @Synchronized fun read(): SupabaseSession? {
        val encoded = prefs.getString("encrypted_session", null) ?: return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < 29) throw IllegalArgumentException("Invalid encrypted session")
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            SupabaseSession(json.getString("accessToken"), json.optString("refreshToken"), json.optLong("expiresAtMs"))
                .takeIf { it.accessToken.isNotBlank() }
        } catch (_: Exception) {
            clear()
            null
        }
    }

    @Synchronized fun saveOpenRouterKey(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, combined, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, combined, cipher.iv.size, encrypted.size)
        prefs.edit().putString("encrypted_openrouter_key", Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    @Synchronized fun readOpenRouterKey(): String? {
        val encoded = prefs.getString("encrypted_openrouter_key", null) ?: return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < 29) throw IllegalArgumentException("Invalid encrypted key")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
            String(cipher.doFinal(combined.copyOfRange(12, combined.size)), Charsets.UTF_8)
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            clearOpenRouterKey()
            null
        }
    }

    @Synchronized fun clearOpenRouterKey() { prefs.edit().remove("encrypted_openrouter_key").apply() }
    @Synchronized fun clear() { prefs.edit().remove("encrypted_session").apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
}

/** Email/password are sent only to Supabase Auth for the duration of the request. */
class SupabaseAuthClient(private val settings: AiBuilderSettingsStore) {
    fun signIn(config: SupabasePublicConfig, email: String, password: String, cancellation: RequestCancellation): AuthOutcome =
        authenticate(config, "/auth/v1/token?grant_type=password", email, password, cancellation)

    fun signUp(config: SupabasePublicConfig, email: String, password: String, cancellation: RequestCancellation): AuthOutcome =
        authenticate(config, "/auth/v1/signup", email, password, cancellation)

    fun signOut(config: SupabasePublicConfig, cancellation: RequestCancellation) {
        val token = settings.sessionStore().read()?.accessToken ?: return
        try {
            HttpJson.request(config, config.endpoint("/auth/v1/logout"), "POST", token,
                JSONObject(), cancellation, maxResponseBytes = 8_000)
        } finally { settings.clearSession() }
    }

    /** Refreshes an expired short-lived access token without ever persisting the password. */
    fun validAccessToken(config: SupabasePublicConfig, cancellation: RequestCancellation): String? {
        val store = settings.sessionStore()
        val session = store.read() ?: return null
        if (session.expiresAtMs > System.currentTimeMillis() + 90_000) return session.accessToken
        if (session.refreshToken.isBlank()) { store.clear(); return null }
        val body = JSONObject().put("refresh_token", session.refreshToken)
        val result = HttpJson.request(config, config.endpoint("/auth/v1/token?grant_type=refresh_token"), "POST", null,
            body, cancellation, maxResponseBytes = 32_000)
        if (result.status !in 200..299) {
            store.clear()
            throw authError(result)
        }
        val json = JSONObject(result.body)
        val fresh = parseSession(json) ?: run { store.clear(); return null }
        store.save(fresh)
        return fresh.accessToken
    }

    private fun authenticate(
        config: SupabasePublicConfig,
        path: String,
        email: String,
        password: String,
        cancellation: RequestCancellation,
    ): AuthOutcome {
        if (email.length > 254 || !email.contains('@')) throw AiProviderException("Enter a valid email address.")
        if (password.length < 8 || password.length > 256) throw AiProviderException("Password must be between 8 and 256 characters.")
        val result = HttpJson.request(config, config.endpoint(path), "POST", null,
            JSONObject().put("email", email.trim()).put("password", password), cancellation, maxResponseBytes = 32_000)
        if (result.status !in 200..299) throw authError(result)
        val body = JSONObject(result.body)
        val session = parseSession(body)
        if (session == null) {
            return AuthOutcome("Account created. Check your email to confirm it, then sign in.", false)
        }
        settings.sessionStore().save(session)
        return AuthOutcome("Supabase account connected securely.", true)
    }

    private fun parseSession(json: JSONObject): SupabaseSession? {
        val access = json.optString("access_token", "")
        if (access.isBlank()) return null
        val refresh = json.optString("refresh_token", "")
        val expiresIn = json.optLong("expires_in", 3_600L).coerceIn(60L, 86_400L)
        val absolute = json.optLong("expires_at", 0L).takeIf { it > 0 }
            ?.times(1_000L) ?: System.currentTimeMillis() + expiresIn * 1_000L
        return SupabaseSession(access, refresh, absolute)
    }

    private fun authError(result: HttpResult): AiProviderException = when (result.status) {
        400, 422 -> AiProviderException("Supabase Auth rejected the request. Check the email, password, and email-confirmation settings.", result.status)
        401, 403 -> AiProviderException("Supabase Auth denied the request. Check the public key and account settings.", result.status)
        429 -> AiProviderException("Too many authentication attempts. Wait briefly and retry.", result.status)
        else -> AiProviderException("Supabase Auth request failed (HTTP ${result.status}). Check network and project configuration.", result.status)
    }
}

/** Secure Supabase-path client. OpenRouter credentials remain server-side in this provider path. */
class SupabaseOpenRouterProvider(
    private val config: SupabasePublicConfig,
    private val auth: SupabaseAuthClient,
) : AiProvider {
    override fun generate(request: AiRequest, cancellation: RequestCancellation): AiResponse {
        cancellation.check()
        val token = auth.validAccessToken(config, cancellation)
            ?: throw AiProviderException("Sign in to Supabase before using secure AI. Local demo mode remains available.")
        val body = JSONObject()
            .put("schemaVersion", 1)
            .put("model", request.model)
            .put("prompt", request.prompt)
            .put("context", request.context)
            .put("projectSnapshot", request.projectSnapshot ?: JSONObject.NULL)
            .put("preferredTemplateId", request.preferredTemplateId ?: JSONObject.NULL)
        val result = HttpJson.request(config, config.endpoint("/functions/v1/ai-game-builder"), "POST", token,
            body, cancellation, maxResponseBytes = 300_000)
        if (result.status !in 200..299) {
            val message = when (result.status) {
                401, 403 -> "Secure AI authorization failed. Sign in again and verify the Supabase Auth configuration."
                404 -> "The Supabase function ai-game-builder was not found (HTTP 404). Deploy the function to this Supabase project, or choose Direct OpenRouter mode to bypass Supabase."
                408, 504 -> "AI request timed out. Retry with a shorter prompt or a faster model."
                422 -> "The secure backend rejected this model/request. Check the OpenRouter model ID and backend configuration."
                429 -> "AI usage limit reached. Wait before retrying or ask your project administrator."
                in 500..599 -> "Secure AI backend is temporarily unavailable (HTTP ${result.status})."
                else -> "Secure AI request was rejected (HTTP ${result.status}). Check the Supabase function and backend setup."
            }
            throw AiProviderException(message, result.status)
        }
        return OpenRouterResponseParser.parse(result.body)
    }
}

/** RLS-protected PostgREST sync. The app sends only the caller's access token and public anon key. */
class SupabaseMemoryClient(private val config: SupabasePublicConfig, private val accessToken: String) {
    fun sync(projectKey: String, store: AiMemoryStore, cancellation: RequestCancellation) {
        val incoming = fetch(projectKey, cancellation)
        store.merge(projectKey, incoming)
        val events = store.events(projectKey)
        for (chunk in events.chunked(40)) upsert(chunk, cancellation)
    }

    fun deleteProject(projectKey: String, cancellation: RequestCancellation) {
        val filter = "project_key=eq." + encode(projectKey)
        val result = HttpJson.request(config, config.endpoint("/rest/v1/ai_memory_records?$filter"), "DELETE", accessToken,
            null, cancellation, maxResponseBytes = 8_000)
        if (result.status !in 200..299) throw restError(result)
    }

    private fun fetch(projectKey: String, cancellation: RequestCancellation): List<AiMemoryEvent> {
        val query = "select=client_id,project_key,kind,payload,created_at&project_key=eq.${encode(projectKey)}&order=created_at.asc&limit=300"
        val result = HttpJson.request(config, config.endpoint("/rest/v1/ai_memory_records?$query"), "GET", accessToken,
            null, cancellation, maxResponseBytes = 800_000)
        if (result.status !in 200..299) throw restError(result)
        val array = JSONArray(result.body)
        return (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("client_id")
            val key = row.optString("project_key")
            val kind = row.optString("kind")
            val payload = row.optJSONObject("payload") ?: JSONObject()
            val createdAt = try { java.time.Instant.parse(row.optString("created_at")).toEpochMilli() } catch (_: Exception) { 0L }
            if (id.isBlank() || key != projectKey || kind !in setOf("message", "decision", "build", "repair")) null
            else AiMemoryEvent(id, key, kind, JSONObject(payload.toString()), createdAt)
        }
    }

    private fun upsert(events: List<AiMemoryEvent>, cancellation: RequestCancellation) {
        if (events.isEmpty()) return
        val rows = JSONArray()
        events.forEach { event ->
            rows.put(JSONObject().put("client_id", event.clientId).put("project_key", event.projectKey)
                .put("kind", event.kind).put("payload", event.payload)
                .put("created_at", java.time.Instant.ofEpochMilli(event.createdAt).toString()))
        }
        val url = config.endpoint("/rest/v1/ai_memory_records?on_conflict=client_id")
        val result = HttpJson.request(config, url, "POST", accessToken, rows, cancellation,
            maxResponseBytes = 8_000, prefer = "resolution=merge-duplicates,return=minimal")
        if (result.status !in 200..299) throw restError(result)
    }

    private fun restError(result: HttpResult): AiProviderException = when (result.status) {
        401, 403 -> AiProviderException("Private memory sync was denied. Sign in again or verify the Supabase RLS migration.", result.status)
        404 -> AiProviderException("Supabase memory table is not deployed. Apply the documented database migration.", result.status)
        429 -> AiProviderException("Supabase memory rate limit reached. Try again later.", result.status)
        else -> AiProviderException("Supabase private memory request failed (HTTP ${result.status}).", result.status)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

internal data class HttpResult(val status: Int, val body: String)

/** Small HTTP boundary with size, timeout, cancellation, and TLS checks; response bodies are never logged. */
internal object HttpJson {
    fun request(
        config: SupabasePublicConfig,
        url: String,
        method: String,
        accessToken: String?,
        body: Any?,
        cancellation: RequestCancellation,
        maxResponseBytes: Int,
        prefer: String? = null,
    ): HttpResult {
        val headers = linkedMapOf("apikey" to config.anonKey, "Accept" to "application/json")
        if (!accessToken.isNullOrBlank()) headers["Authorization"] = "Bearer $accessToken"
        return requestUrl(url, method, headers, body, cancellation, maxResponseBytes, prefer)
    }

    /** Shared bounded TLS transport used by Supabase and the optional device-key OpenRouter mode. */
    fun requestUrl(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: Any?,
        cancellation: RequestCancellation,
        maxResponseBytes: Int,
        prefer: String? = null,
    ): HttpResult {
        cancellation.check()
        val uri = try { URI(url) } catch (e: Exception) { throw AiProviderException("Configured endpoint is invalid.", cause = e) }
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw AiProviderException("Requests require a valid HTTPS endpoint.")
        }
        val connection = try { uri.toURL().openConnection() as HttpURLConnection } catch (e: Exception) {
            throw AiProviderException("Could not open a connection to the configured endpoint.", cause = e)
        }
        try {
            cancellation.attach(connection)
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = when (body) {
                    is JSONObject -> body.toString().toByteArray(Charsets.UTF_8)
                    is JSONArray -> body.toString().toByteArray(Charsets.UTF_8)
                    else -> throw AiProviderException("Unsupported request payload type.")
                }
                if (bytes.size > 128_000) throw AiProviderException("Request exceeds the 128 KB payload limit.")
                prefer?.let { connection.setRequestProperty("Prefer", it) }
                connection.setFixedLengthStreamingMode(bytes.size)
                cancellation.check()
                connection.outputStream.use { it.write(bytes) }
            }
            cancellation.check()
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = if (stream == null) "" else stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    cancellation.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxResponseBytes) throw AiProviderException("Response exceeded the ${maxResponseBytes / 1024} KB size limit.", status)
                    out.write(buffer, 0, count)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
            cancellation.check()
            return HttpResult(status, response)
        } catch (e: RequestCancelledException) {
            throw e
        } catch (e: AiProviderException) {
            throw e
        } catch (e: Exception) {
            if (cancellation.isCancelled) throw RequestCancelledException()
            throw AiProviderException("Network request failed. Check connectivity and retry.", cause = e)
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
        }
    }
}
