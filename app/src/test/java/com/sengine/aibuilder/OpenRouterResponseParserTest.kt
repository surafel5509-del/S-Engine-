package com.sengine.aibuilder

import com.sengine.aibuilder.provider.AiProviderException
import com.sengine.aibuilder.provider.OpenRouterResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterResponseParserTest {
    @Test
    fun parsesNormalizedEdgeFunctionResponseAndUsage() {
        val parsed = OpenRouterResponseParser.parse("""{"content":"{\"schemaVersion\":1}","model":"openai/test","usage":{"prompt_tokens":100,"completion_tokens":50}}""")
        assertEquals("{\"schemaVersion\":1}", parsed.content)
        assertEquals("openai/test", parsed.model)
        assertEquals(100, parsed.promptTokens)
        assertEquals(50, parsed.completionTokens)
    }

    @Test
    fun parsesOpenRouterChatEnvelopeAndTextParts() {
        val parsed = OpenRouterResponseParser.parse("""{"model":"provider/model","choices":[{"message":{"role":"assistant","content":[{"type":"text","text":"{\"schemaVersion\":"},{"type":"text","text":"1}"}]}}]}""")
        assertEquals("{\"schemaVersion\":1}", parsed.content)
        assertEquals("provider/model", parsed.model)
    }

    @Test
    fun givesUsefulErrorsForMalformedOrEmptyProviderResponses() {
        assertProviderError("not-json", "malformed JSON")
        assertProviderError("{}", "no content")
        assertProviderError("""{"choices":[]}""", "no choices")
        assertProviderError("""{"error":{"code":"rate_limit","message":"slow down"}}""", "slow down")
    }

    private fun assertProviderError(body: String, messagePart: String) {
        try {
            OpenRouterResponseParser.parse(body)
            throw AssertionError("expected a provider error")
        } catch (e: AiProviderException) {
            assertTrue(e.message.orEmpty().contains(messagePart, ignoreCase = true))
        }
    }
}
