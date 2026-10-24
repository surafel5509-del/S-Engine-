package com.sengine.aibuilder.provider

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

internal data class HttpResult(val status: Int, val body: String)

/** Small bounded TLS transport. Redirects are disabled so API keys cannot be forwarded elsewhere. */
internal object HttpJson {
    fun requestUrl(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: JSONObject?,
        cancellation: RequestCancellation,
        maxResponseBytes: Int = 256_000,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 180_000,
    ): HttpResult {
        cancellation.check()
        val uri = try { URI(url) } catch (e: Exception) {
            throw AiProviderException("Configured AI endpoint is invalid.", cause = e)
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw AiProviderException("AI provider requests require a valid HTTPS endpoint.")
        }
        val connection = try { uri.toURL().openConnection() as HttpURLConnection } catch (e: Exception) {
            throw AiProviderException("Could not connect to the configured AI provider.", cause = e)
        }
        try {
            cancellation.attach(connection)
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                if (bytes.size > 128_000) throw AiProviderException("AI request exceeds the 128 KB safety limit.")
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
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
                    if (total > maxResponseBytes) {
                        throw AiProviderException("AI response exceeded the ${maxResponseBytes / 1024} KB safety limit.", status)
                    }
                    out.write(buffer, 0, count)
                }
                String(out.toByteArray(), StandardCharsets.UTF_8)
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

/** Creates vendor-native request bodies around the same bounded GameSpec planning prompt. */
internal object AiProviderRequestBuilder {
    private fun userPayload(request: AiRequest): String = JSONObject()
        .put("request", request.prompt)
        .put("projectMemory", request.context)
        .put("projectSnapshot", request.projectSnapshot ?: JSONObject.NULL)
        .put("preferredTemplateId", request.preferredTemplateId ?: "auto")
        .toString()

    fun chatCompletions(request: AiRequest, jsonMode: Boolean): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", GameSpecPromptBuilder.systemPrompt()))
            .put(JSONObject().put("role", "user").put("content", userPayload(request)))
        return JSONObject()
            .put("model", request.model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", 4_000)
            .also { if (jsonMode) it.put("response_format", JSONObject().put("type", "json_object")) }
    }

    fun gemini(request: AiRequest, jsonMode: Boolean): JSONObject {
        val generation = JSONObject().put("temperature", 0.2).put("maxOutputTokens", 4_000)
        if (jsonMode) generation.put("responseMimeType", "application/json")
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", GameSpecPromptBuilder.systemPrompt()))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userPayload(request))))))
            .put("generationConfig", generation)
    }

    fun anthropic(request: AiRequest): JSONObject = JSONObject()
        .put("model", request.model)
        .put("max_tokens", 4_000)
        .put("temperature", 0.2)
        .put("system", GameSpecPromptBuilder.systemPrompt())
        .put("messages", JSONArray().put(JSONObject()
            .put("role", "user")
            .put("content", userPayload(request))))
}

/** Direct, key-local provider transport for OpenAI, Gemini, Claude, OpenRouter and compatible APIs. */
class ConfiguredAiProvider(
    private val profile: AiProviderProfile,
    private val apiKey: String,
) : AiProvider {
    override fun generate(request: AiRequest, cancellation: RequestCancellation): AiResponse {
        cancellation.check()
        if (request.model != profile.model) throw AiProviderException("The request model does not match the selected provider profile.")
        val key = try { AiProviderProfileRules.validateApiKey(apiKey) } catch (e: IllegalArgumentException) {
            throw AiProviderException("Add a valid ${profile.kind.displayName} API key in AI Settings.", cause = e)
        }
        return when (profile.kind.protocol) {
            AiProviderKind.Protocol.OPENAI_COMPATIBLE -> openAiCompatible(request, key, cancellation)
            AiProviderKind.Protocol.GEMINI -> gemini(request, key, cancellation)
            AiProviderKind.Protocol.ANTHROPIC -> anthropic(request, key, cancellation)
        }
    }

    private fun openAiCompatible(request: AiRequest, key: String, cancellation: RequestCancellation): AiResponse {
        val endpoint = when (profile.kind) {
            AiProviderKind.OPENROUTER, AiProviderKind.OPENAI -> profile.kind.defaultEndpoint
            AiProviderKind.OPENAI_COMPATIBLE -> try { AiProviderProfileRules.chatCompletionsEndpoint(profile.endpoint) }
                catch (e: IllegalArgumentException) { throw AiProviderException(e.message ?: "Custom endpoint is invalid.", cause = e) }
            else -> throw AiProviderException("This provider does not use the OpenAI-compatible API.")
        }
        val headers = linkedMapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
        if (profile.kind == AiProviderKind.OPENROUTER) headers["X-Title"] = "S Engine AI Game Builder"
        var result = HttpJson.requestUrl(endpoint, "POST", headers,
            AiProviderRequestBuilder.chatCompletions(request, jsonMode = true), cancellation)
        if (result.status == 400 && jsonModeRejected(result.body)) {
            result = HttpJson.requestUrl(endpoint, "POST", headers,
                AiProviderRequestBuilder.chatCompletions(request, jsonMode = false), cancellation)
        }
        checkStatus(result, profile.kind)
        return OpenAiCompatibleResponseParser.parse(result.body)
    }

    private fun gemini(request: AiRequest, key: String, cancellation: RequestCancellation): AiResponse {
        val endpoint = "${profile.kind.defaultEndpoint.trimEnd('/')}/models/${request.model}:generateContent"
        val headers = mapOf("x-goog-api-key" to key, "Accept" to "application/json")
        var result = HttpJson.requestUrl(endpoint, "POST", headers,
            AiProviderRequestBuilder.gemini(request, jsonMode = true), cancellation)
        if (result.status == 400 && jsonModeRejected(result.body)) {
            result = HttpJson.requestUrl(endpoint, "POST", headers,
                AiProviderRequestBuilder.gemini(request, jsonMode = false), cancellation)
        }
        checkStatus(result, profile.kind)
        return AiProviderResponseParser.parse(profile.kind, result.body, request.model)
    }

    private fun anthropic(request: AiRequest, key: String, cancellation: RequestCancellation): AiResponse {
        val headers = mapOf(
            "x-api-key" to key,
            "anthropic-version" to "2023-06-01",
            "Accept" to "application/json",
        )
        val result = HttpJson.requestUrl(profile.kind.defaultEndpoint, "POST", headers,
            AiProviderRequestBuilder.anthropic(request), cancellation)
        checkStatus(result, profile.kind)
        return AiProviderResponseParser.parse(profile.kind, result.body, request.model)
    }

    private fun checkStatus(result: HttpResult, kind: AiProviderKind) {
        if (result.status in 200..299) return
        val description = when (result.status) {
            400 -> "The request was rejected. Check the model name and provider settings."
            401, 403 -> "${kind.displayName} rejected the API key or denied access. Check the key in AI Settings."
            402 -> "${kind.displayName} account has insufficient credits or billing is not enabled."
            404 -> "${kind.displayName} could not find the selected model or endpoint. Check the model name."
            408, 504 -> "${kind.displayName} timed out. Retry or choose a faster model."
            429 -> "${kind.displayName} rate limited this request. Check provider limits and retry later."
            413 -> "${kind.displayName} rejected the prompt as too large. Shorten the prompt or project context."
            in 500..599 -> "${kind.displayName} is temporarily unavailable (HTTP ${result.status})."
            else -> "${kind.displayName} rejected the request (HTTP ${result.status}). Check provider settings."
        }
        throw AiProviderException(description, result.status)
    }

    private fun jsonModeRejected(body: String): Boolean =
        Regex("response_format|json.?mode|structured.?output|responseMimeType|response_mime_type", RegexOption.IGNORE_CASE)
            .containsMatchIn(body)
}

/** Tests and callers can construct the currently selected provider from its profile and key. */
object AiProviderFactory {
    fun create(profile: AiProviderProfile, apiKey: String): AiProvider = ConfiguredAiProvider(profile, apiKey)
}
