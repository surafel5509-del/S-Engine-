package com.sengine.aibuilder.provider

import org.json.JSONArray
import org.json.JSONObject
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

/** Accepts either the Edge Function's normalized payload or an OpenRouter chat-completions envelope. */
object OpenRouterResponseParser {
    private const val MAX_RESPONSE_CHARS = 256_000

    fun parse(body: String): AiResponse {
        if (body.length > MAX_RESPONSE_CHARS) throw AiProviderException("AI response exceeded the 256 KB response limit.")
        val root = try { JSONObject(body) } catch (e: Exception) {
            throw AiProviderException("AI backend returned malformed JSON.", cause = e)
        }
        root.optJSONObject("error")?.let { error ->
            val code = error.optString("code", "")
            val message = error.optString("message", "AI provider returned an error.").take(400)
            throw AiProviderException(if (code.isBlank()) message else "$message (code: $code)")
        }
        val content = when {
            root.opt("content") is String -> root.optString("content")
            else -> {
                val choices = root.optJSONArray("choices") ?: throw AiProviderException("AI backend response has no content or choices.")
                if (choices.length() == 0) throw AiProviderException("AI backend returned no choices.")
                val choice = choices.optJSONObject(0) ?: throw AiProviderException("AI backend returned a malformed choice.")
                val message = choice.optJSONObject("message") ?: throw AiProviderException("AI backend response has no assistant message.")
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
