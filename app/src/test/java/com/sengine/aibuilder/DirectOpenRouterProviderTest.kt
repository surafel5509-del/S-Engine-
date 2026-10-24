package com.sengine.aibuilder

import com.sengine.aibuilder.provider.AiProviderException
import com.sengine.aibuilder.provider.AiRequest
import com.sengine.aibuilder.provider.DirectOpenRouterProvider
import com.sengine.aibuilder.provider.GameSpecPromptBuilder
import com.sengine.aibuilder.provider.RequestCancellation
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectOpenRouterProviderTest {
    @Test
    fun rejectsMissingOrMalformedUserKeyBeforeOpeningNetworkConnection() {
        val provider = DirectOpenRouterProvider("not-a-key")
        try {
            provider.generate(
                AiRequest("make a game", "openai/gpt-4o-mini", JSONObject(), null),
                RequestCancellation(),
            )
            throw AssertionError("expected invalid key to be rejected")
        } catch (e: AiProviderException) {
            assertTrue(e.message.orEmpty().contains("OpenRouter API key"))
        }
    }

    @Test
    fun directProviderPromptIsBoundToInstalledTemplatesAndSafeOperations() {
        val prompt = GameSpecPromptBuilder.systemPrompt()
        assertTrue(prompt.contains("GameSpec v1"))
        assertTrue(prompt.contains("Installed templates:"))
        assertTrue(prompt.contains("add_hud_text"))
        assertTrue(prompt.contains("set_script_parameter"))
        assertTrue(prompt.contains("No generated source code or arbitrary filesystem operations"))
    }
}
