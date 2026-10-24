package com.sengine.engine.ai

import org.json.JSONObject
import java.io.File

/**
 * AI Settings - Manages API keys and provider configuration
 * Supports multiple AI providers: OpenAI, Gemini, OpenRouter, Groq, Anthropic
 */
object AISettings {
    private var settingsFile: File? = null
    
    // API Keys (encrypted in production)
    private var openaiKey: String = ""
    private var geminiKey: String = ""
    private var openrouterKey: String = ""
    private var groqKey: String = ""
    private var anthropicKey: String = ""
    
    // Model Selection
    private var openaiModel: String = "gpt-4-turbo-preview"
    private var geminiModel: String = "gemini-pro"
    private var openrouterModel: String = "openai/gpt-4-turbo-preview"
    private var groqModel: String = "llama2-70b-4096"
    private var anthropicModel: String = "claude-3-opus-20240229"
    
    // Active Provider
    var activeProvider: String = "openai" // openai, gemini, openrouter, groq, anthropic
    
    // Generation Settings
    private var temperature: Float = 0.7f
    private var maxTokens: Int = 2000
    private var topP: Float = 0.9f
    
    // Auto-save settings
    var autoSave: Boolean = true
    
    fun init(storageDir: File) {
        settingsFile = File(storageDir, "ai_settings.json")
        load()
    }
    
    fun load() {
        try {
            settingsFile?.let { file ->
                if (file.exists()) {
                    val json = JSONObject(file.readText())
                    openaiKey = json.optString("openaiKey", "")
                    geminiKey = json.optString("geminiKey", "")
                    openrouterKey = json.optString("openrouterKey", "")
                    groqKey = json.optString("groqKey", "")
                    anthropicKey = json.optString("anthropicKey", "")
                    openaiModel = json.optString("openaiModel", "gpt-4-turbo-preview")
                    geminiModel = json.optString("geminiModel", "gemini-pro")
                    openrouterModel = json.optString("openrouterModel", "openai/gpt-4-turbo-preview")
                    groqModel = json.optString("groqModel", "llama2-70b-4096")
                    anthropicModel = json.optString("anthropicModel", "claude-3-opus-20240229")
                    activeProvider = json.optString("activeProvider", "openai")
                    temperature = json.optDouble("temperature", 0.7).toFloat()
                    maxTokens = json.optInt("maxTokens", 2000)
                    topP = json.optDouble("topP", 0.9).toFloat()
                    autoSave = json.optBoolean("autoSave", true)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun save() {
        try {
            settingsFile?.let { file ->
                val json = JSONObject()
                json.put("openaiKey", openaiKey)
                json.put("geminiKey", geminiKey)
                json.put("openrouterKey", openrouterKey)
                json.put("groqKey", groqKey)
                json.put("anthropicKey", anthropicKey)
                json.put("openaiModel", openaiModel)
                json.put("geminiModel", geminiModel)
                json.put("openrouterModel", openrouterModel)
                json.put("groqModel", groqModel)
                json.put("anthropicModel", anthropicModel)
                json.put("activeProvider", activeProvider)
                json.put("temperature", temperature.toDouble())
                json.put("maxTokens", maxTokens)
                json.put("topP", topP.toDouble())
                json.put("autoSave", autoSave)
                file.writeText(json.toString(2))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // Provider getter/setter
    fun getProvider(): String = activeProvider
    fun setProvider(provider: String) { activeProvider = provider }
    
    // API Key getters/setters
    fun getOpenAIKey(): String = openaiKey
    fun setOpenAIKey(key: String) { openaiKey = key }
    
    fun getOpenRouterKey(): String = openrouterKey
    fun setOpenRouterKey(key: String) { openrouterKey = key }
    
    fun getAnthropicKey(): String = anthropicKey
    fun setAnthropicKey(key: String) { anthropicKey = key }
    
    fun getGeminiKey(): String = geminiKey
    fun setGeminiKey(key: String) { geminiKey = key }
    
    fun getGroqKey(): String = groqKey
    fun setGroqKey(key: String) { groqKey = key }
    
    // Model getters/setters
    fun getOpenAIModel(): String = openaiModel
    fun setOpenAIModel(model: String) { openaiModel = model }
    
    fun getOpenRouterModel(): String = openrouterModel
    fun setOpenRouterModel(model: String) { openrouterModel = model }
    
    fun getAnthropicModel(): String = anthropicModel
    fun setAnthropicModel(model: String) { anthropicModel = model }
    
    fun getGeminiModel(): String = geminiModel
    fun setGeminiModel(model: String) { geminiModel = model }
    
    fun getGroqModel(): String = groqModel
    fun setGroqModel(model: String) { groqModel = model }
    
    // Generation settings getters/setters
    fun getTemperature(): Float = temperature
    fun setTemperature(temp: Float) { temperature = temp }
    
    fun getMaxTokens(): Int = maxTokens
    fun setMaxTokens(tokens: Int) { maxTokens = tokens }
    
    fun getTopP(): Float = topP
    fun setTopP(topP: Float) { this.topP = topP }
    
    // Convenience property for API key based on active provider
    val apiKey: String
        get() = when (activeProvider) {
            "openai" -> openaiKey
            "gemini" -> geminiKey
            "openrouter" -> openrouterKey
            "groq" -> groqKey
            "anthropic" -> anthropicKey
            else -> openaiKey
        }
    
    // Convenience property for provider display name
    val providerDisplayName: String
        get() = getProviderDisplayName(activeProvider)
    
    fun getActiveProvider(): AIProvider {
        return when (activeProvider) {
            "openai" -> OpenAIProvider(openaiKey, openaiModel, temperature, maxTokens, topP)
            "gemini" -> GeminiProvider(geminiKey, geminiModel, temperature, maxTokens, topP)
            "openrouter" -> OpenRouterProvider(openrouterKey, openrouterModel, temperature, maxTokens, topP)
            "groq" -> GroqProvider(groqKey, groqModel, temperature, maxTokens, topP)
            else -> OpenAIProvider(openaiKey, openaiModel, temperature, maxTokens, topP)
        }
    }
    
    fun isProviderConfigured(provider: String): Boolean {
        return when (provider) {
            "openai" -> openaiKey.isNotEmpty()
            "gemini" -> geminiKey.isNotEmpty()
            "openrouter" -> openrouterKey.isNotEmpty()
            "groq" -> groqKey.isNotEmpty()
            "anthropic" -> anthropicKey.isNotEmpty()
            else -> false
        }
    }
    
    fun getProviderDisplayName(provider: String): String {
        return when (provider) {
            "openai" -> "ChatGPT (OpenAI)"
            "gemini" -> "Google Gemini"
            "openrouter" -> "OpenRouter"
            "groq" -> "Groq"
            "anthropic" -> "Anthropic (Claude)"
            else -> provider
        }
    }
    
    fun getAllProviders(): List<String> {
        return listOf("openai", "gemini", "openrouter", "groq", "anthropic")
    }
}
