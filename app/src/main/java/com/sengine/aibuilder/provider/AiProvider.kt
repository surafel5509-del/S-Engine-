package com.sengine.aibuilder.provider

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Provider-neutral request contract. Context/project state are bounded by the caller. */
data class AiRequest(
    val prompt: String,
    val model: String,
    val context: JSONObject,
    val projectSnapshot: JSONObject?,
    val preferredTemplateId: String? = null,
)

data class AiResponse(
    val content: String,
    val model: String = "",
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

/** The app talks directly to the selected vendor; API keys are never sent to an S Engine server. */
enum class AiProviderKind(
    val id: String,
    val displayName: String,
    val defaultModel: String,
    val defaultEndpoint: String,
    val protocol: Protocol,
) {
    OPENROUTER("openrouter", "OpenRouter", "openai/gpt-4o-mini", "https://openrouter.ai/api/v1/chat/completions", Protocol.OPENAI_COMPATIBLE),
    OPENAI("openai", "OpenAI / ChatGPT API", "gpt-4o-mini", "https://api.openai.com/v1/chat/completions", Protocol.OPENAI_COMPATIBLE),
    GEMINI("gemini", "Google Gemini", "gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta", Protocol.GEMINI),
    CLAUDE("claude", "Claude (Anthropic)", "claude-sonnet-4-20250514", "https://api.anthropic.com/v1/messages", Protocol.ANTHROPIC),
    OPENAI_COMPATIBLE("compatible", "Other OpenAI-compatible", "model-name", "", Protocol.OPENAI_COMPATIBLE),
    ;

    enum class Protocol { OPENAI_COMPATIBLE, GEMINI, ANTHROPIC }

    companion object {
        fun fromId(value: String?): AiProviderKind? = entries.firstOrNull { it.id == value }
    }
}

data class AiProviderProfile(
    val kind: AiProviderKind,
    val model: String,
    /** Only editable for the custom OpenAI-compatible profile. */
    val endpoint: String = kind.defaultEndpoint,
)

/** Validation shared by Android settings and JVM tests. */
object AiProviderProfileRules {
    private val modelPattern = Regex("[A-Za-z0-9._:/-]+")

    fun validateModel(value: String): String {
        val model = value.trim()
        require(model.length in 3..160 && modelPattern.matches(model)) {
            "Model name must be 3–160 letters, numbers, dots, underscores, colons, slashes, or hyphens."
        }
        return model
    }

    fun validateApiKey(value: String): String {
        val key = value.trim()
        require(key.length in 16..512 && key.none { it.isWhitespace() || it.isISOControl() }) {
            "API key must be 16–512 characters with no spaces or control characters."
        }
        return key
    }

    fun validateCustomEndpoint(value: String): String {
        val endpoint = value.trim().trimEnd('/')
        val uri = try { URI(endpoint) } catch (e: Exception) {
            throw IllegalArgumentException("Custom endpoint must be a valid HTTPS URL.", e)
        }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath?.length ?: 0) <= 180 && endpoint.none { it.isISOControl() }) {
            "Custom endpoint must use HTTPS and must not contain credentials, a query, or a fragment."
        }
        return endpoint
    }

    fun chatCompletionsEndpoint(base: String): String {
        val endpoint = validateCustomEndpoint(base)
        return if (endpoint.endsWith("/chat/completions")) endpoint else "$endpoint/chat/completions"
    }
}

interface AiProvider {
    @Throws(AiProviderException::class)
    fun generate(request: AiRequest, cancellation: RequestCancellation): AiResponse
}

class AiProviderException(message: String, val httpStatus: Int? = null, cause: Throwable? = null) : Exception(message, cause)
class RequestCancelledException : java.util.concurrent.CancellationException("Request cancelled")

/** Cancellation disconnects an in-flight HTTP request; generation never runs on the UI thread. */
class RequestCancellation {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: java.net.HttpURLConnection? = null

    val isCancelled: Boolean get() = cancelled.get()
    fun cancel() {
        cancelled.set(true)
        connection?.disconnect()
    }
    fun check() { if (isCancelled) throw RequestCancelledException() }
    fun attach(value: java.net.HttpURLConnection) {
        connection = value
        check()
    }
    fun detach(value: java.net.HttpURLConnection) {
        if (connection === value) connection = null
    }
}

/** Parses OpenAI-compatible response envelopes without logging provider responses. */
object OpenAiCompatibleResponseParser {
    private const val MAX_RESPONSE_CHARS = 256_000

    fun parse(body: String): AiResponse {
        if (body.length > MAX_RESPONSE_CHARS) throw AiProviderException("AI response exceeded the 256 KB response limit.")
        val root = try { JSONObject(body) } catch (e: Exception) {
            throw AiProviderException("AI provider returned malformed JSON.", cause = e)
        }
        root.optJSONObject("error")?.let { error ->
            val code = error.optString("code", "")
            val message = error.optString("message", "AI provider returned an error.").take(400)
            throw AiProviderException(if (code.isBlank()) message else "$message (code: $code)")
        }
        val content = when {
            root.opt("content") is String -> root.optString("content")
            else -> {
                val choices = root.optJSONArray("choices") ?: throw AiProviderException("AI provider response has no content or choices.")
                if (choices.length() == 0) throw AiProviderException("AI provider returned no choices.")
                val choice = choices.optJSONObject(0) ?: throw AiProviderException("AI provider returned a malformed choice.")
                val message = choice.optJSONObject("message") ?: throw AiProviderException("AI provider response has no assistant message.")
                contentFrom(message.opt("content"))
            }
        }
        if (content.isBlank()) throw AiProviderException("AI provider returned an empty plan.")
        if (content.length > 96_000) throw AiProviderException("AI plan exceeded the GameSpec size limit.")
        val usage = root.optJSONObject("usage")
        return AiResponse(
            content = content,
            model = root.optString("model", ""),
            promptTokens = usage?.optInt("prompt_tokens")?.takeIf { it > 0 },
            completionTokens = usage?.optInt("completion_tokens")?.takeIf { it > 0 },
        )
    }

    private fun contentFrom(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                val part = value.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }
        else -> throw AiProviderException("AI assistant content has an unsupported format.")
    }
}

/** Vendor-specific parsers with one normalized response shape for the GameSpec codec. */
object AiProviderResponseParser {
    private const val MAX_RESPONSE_CHARS = 256_000

    fun parse(kind: AiProviderKind, body: String, requestedModel: String): AiResponse {
        if (kind.protocol == AiProviderKind.Protocol.OPENAI_COMPATIBLE) return OpenAiCompatibleResponseParser.parse(body)
        if (body.length > MAX_RESPONSE_CHARS) throw AiProviderException("AI response exceeded the 256 KB response limit.")
        val root = try { JSONObject(body) } catch (e: Exception) {
            throw AiProviderException("${kind.displayName} returned malformed JSON.", cause = e)
        }
        root.optJSONObject("error")?.let { error ->
            val message = error.optString("message", "${kind.displayName} returned an error.").take(400)
            throw AiProviderException(message)
        }
        return when (kind.protocol) {
            AiProviderKind.Protocol.GEMINI -> parseGemini(root, requestedModel)
            AiProviderKind.Protocol.ANTHROPIC -> parseAnthropic(root, requestedModel)
            AiProviderKind.Protocol.OPENAI_COMPATIBLE -> OpenAiCompatibleResponseParser.parse(body)
        }
    }

    private fun parseGemini(root: JSONObject, requestedModel: String): AiResponse {
        val candidates = root.optJSONArray("candidates")
            ?: throw AiProviderException("Gemini response has no candidates. Check model access and safety settings.")
        if (candidates.length() == 0) throw AiProviderException("Gemini returned no candidates. Try a different model or prompt.")
        val candidate = candidates.optJSONObject(0) ?: throw AiProviderException("Gemini returned a malformed candidate.")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiProviderException("Gemini response contained no text. The request may have been blocked by safety filters.")
        val text = buildString {
            for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
        if (text.isBlank()) throw AiProviderException("Gemini returned no text. Try revising the prompt or choosing another model.")
        if (text.length > 96_000) throw AiProviderException("AI plan exceeded the GameSpec size limit.")
        val usage = root.optJSONObject("usageMetadata")
        return AiResponse(
            text,
            requestedModel,
            usage?.optInt("promptTokenCount")?.takeIf { it > 0 },
            usage?.optInt("candidatesTokenCount")?.takeIf { it > 0 },
        )
    }

    private fun parseAnthropic(root: JSONObject, requestedModel: String): AiResponse {
        val content = root.optJSONArray("content") ?: throw AiProviderException("Claude response has no content blocks.")
        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
        if (text.isBlank()) throw AiProviderException("Claude returned no text. Try a different model or prompt.")
        if (text.length > 96_000) throw AiProviderException("AI plan exceeded the GameSpec size limit.")
        val usage = root.optJSONObject("usage")
        return AiResponse(
            text,
            root.optString("model", requestedModel),
            usage?.optInt("input_tokens")?.takeIf { it > 0 },
            usage?.optInt("output_tokens")?.takeIf { it > 0 },
        )
    }
}
