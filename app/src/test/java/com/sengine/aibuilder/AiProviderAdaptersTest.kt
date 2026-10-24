package com.sengine.aibuilder

import com.sengine.aibuilder.provider.AiProviderKind
import com.sengine.aibuilder.provider.AiProviderProfileRules
import com.sengine.aibuilder.provider.AiProviderRequestBuilder
import com.sengine.aibuilder.provider.AiProviderResponseParser
import com.sengine.aibuilder.provider.AiRequest
import com.sengine.aibuilder.provider.AiProviderException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderAdaptersTest {
    private val request = AiRequest(
        prompt = "Build a small platformer",
        model = "test-model",
        context = JSONObject().put("recentMessages", "private memory"),
        projectSnapshot = JSONObject().put("projectName", "Tiny Game"),
        preferredTemplateId = "platformer-demo",
    )

    @Test
    fun buildsVendorNativeRequestBodiesWithoutEmbeddingApiKeys() {
        val openAi = AiProviderRequestBuilder.chatCompletions(request, jsonMode = true)
        assertEquals("test-model", openAi.optString("model"))
        assertEquals("json_object", openAi.optJSONObject("response_format")?.optString("type"))

        val gemini = AiProviderRequestBuilder.gemini(request, jsonMode = true)
        assertTrue(gemini.has("systemInstruction"))
        assertEquals("application/json", gemini.optJSONObject("generationConfig")?.optString("responseMimeType"))
        assertFalse(gemini.toString().contains("apiKey"))

        val anthropic = AiProviderRequestBuilder.anthropic(request)
        assertEquals("test-model", anthropic.optString("model"))
        assertTrue(anthropic.has("system"))
        assertFalse(anthropic.toString().contains("secret"))
    }

    @Test
    fun parsesGeminiAndClaudeResponsesIntoOneGameSpecTextShape() {
        val gameSpec = "{\"schemaVersion\":1,\"action\":\"explain\"}"
        val geminiBody = JSONObject()
            .put("candidates", org.json.JSONArray().put(JSONObject()
                .put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", gameSpec))))))
            .put("usageMetadata", JSONObject().put("promptTokenCount", 12).put("candidatesTokenCount", 7))
            .toString()
        val gemini = AiProviderResponseParser.parse(AiProviderKind.GEMINI, geminiBody, "gemini-test")
        assertEquals(gameSpec, gemini.content)
        assertEquals("gemini-test", gemini.model)
        assertEquals(12, gemini.promptTokens)

        val claudeBody = JSONObject()
            .put("model", "claude-test")
            .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", gameSpec)))
            .put("usage", JSONObject().put("input_tokens", 9).put("output_tokens", 4))
            .toString()
        val claude = AiProviderResponseParser.parse(AiProviderKind.CLAUDE, claudeBody, "claude-fallback")
        assertEquals(gameSpec, claude.content)
        assertEquals("claude-test", claude.model)
        assertEquals(4, claude.completionTokens)
    }

    @Test
    fun customEndpointsRequireHttpsAndCanBeBaseOrChatCompletionsUrl() {
        assertEquals("https://api.example.test/v1/chat/completions",
            AiProviderProfileRules.chatCompletionsEndpoint("https://api.example.test/v1"))
        assertEquals("https://api.example.test/v1/chat/completions",
            AiProviderProfileRules.chatCompletionsEndpoint("https://api.example.test/v1/chat/completions"))
        listOf("http://api.example.test/v1", "https://user:pass@api.example.test/v1", "https://api.example.test/v1?token=x")
            .forEach { endpoint ->
                try {
                    AiProviderProfileRules.validateCustomEndpoint(endpoint)
                    throw AssertionError("unsafe custom endpoint should fail: $endpoint")
                } catch (_: IllegalArgumentException) { }
            }
    }

    @Test
    fun malformedGeminiResponseHasProviderSpecificError() {
        try {
            AiProviderResponseParser.parse(AiProviderKind.GEMINI, "{}", "gemini-test")
            throw AssertionError("expected malformed response error")
        } catch (e: AiProviderException) {
            assertTrue(e.message.orEmpty().contains("Gemini"))
        }
    }
}
