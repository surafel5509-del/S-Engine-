package com.sengine.aibuilder

import com.sengine.aibuilder.provider.AiProviderException
import com.sengine.aibuilder.provider.AiProviderFactory
import com.sengine.aibuilder.provider.AiProviderKind
import com.sengine.aibuilder.provider.AiProviderProfile
import com.sengine.aibuilder.provider.AiRequest
import com.sengine.aibuilder.provider.GameSpecPromptBuilder
import com.sengine.aibuilder.provider.RequestCancellation
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSpecPromptBuilderTest {
    @Test
    fun rejectsMissingOrMalformedUserKeyBeforeOpeningNetworkConnection() {
        val provider = AiProviderFactory.create(
            AiProviderProfile(AiProviderKind.OPENROUTER, AiProviderKind.OPENROUTER.defaultModel),
            "not-a-key",
        )
        try {
            provider.generate(
                AiRequest("make a game", AiProviderKind.OPENROUTER.defaultModel, JSONObject(), null),
                RequestCancellation(),
            )
            throw AssertionError("expected invalid key to be rejected")
        } catch (e: AiProviderException) {
            assertTrue(e.message.orEmpty().contains("OpenRouter API key"))
        }
    }

    @Test
    fun planningPromptUsesInstalledTemplatesAndOnlySafeOperationsAndAssetRecipes() {
        val prompt = GameSpecPromptBuilder.systemPrompt()
        assertTrue(prompt.contains("GameSpec v1"))
        assertTrue(prompt.contains("Installed templates:"))
        assertTrue(prompt.contains("add_hud_text"))
        assertTrue(prompt.contains("generate_pixel_sprite"))
        assertTrue(prompt.contains("generate_sound"))
        assertTrue(prompt.contains("generate_blueprint_preset"))
        assertTrue(prompt.contains("never emit custom JavaScript/GLSL"))
        assertTrue(prompt.contains("arbitrary files"))
    }
}
