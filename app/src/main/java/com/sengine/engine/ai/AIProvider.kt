package com.sengine.engine.ai

/**
 * Base interface for AI providers
 * All AI providers (OpenAI, Gemini, OpenRouter, Groq) implement this interface
 */
interface AIProvider {
    val name: String
    val displayName: String
    val apiKey: String
    val model: String
    
    var temperature: Float
    var maxTokens: Int
    var topP: Float
    
    /**
     * Send a chat completion request (blocking call - run on background thread)
     * @param messages List of chat messages (role, content pairs)
     * @return AI response text
     */
    fun chatCompletion(messages: List<ChatMessage>): String
    
    /**
     * Generate code from a prompt
     */
    fun generateCode(prompt: String, context: String = ""): String
    
    /**
     * Generate a game script from natural language
     */
    fun generateScript(description: String, gameObjectType: String): String
    
    /**
     * Analyze code and provide suggestions
     */
    fun analyzeCode(code: String): String
    
    /**
     * Fix bugs in code
     */
    fun fixCode(code: String, errorDescription: String): String
    
    /**
     * Generate a dialogue/conversation
     */
    fun generateDialogue(character: String, scenario: String, numberOfLines: Int): String
    
    /**
     * Generate a game level description
     */
    fun generateLevel(genre: String, difficulty: String, theme: String): String
    
    /**
     * Explain code or game mechanics
     */
    fun explain(content: String): String
    
    /**
     * Optimize code for performance
     */
    fun optimizeCode(code: String): String
}

/**
 * Chat message structure
 */
data class ChatMessage(
    val role: String, // "system", "user", "assistant"
    val content: String
)

/**
 * Base implementation with common functionality
 */
abstract class BaseAIProvider(
    override val apiKey: String,
    override val model: String,
    override var temperature: Float = 0.7f,
    override var maxTokens: Int = 2000,
    override var topP: Float = 0.9f
) : AIProvider {
    
    protected fun buildSystemPrompt(): String {
        return """
You are an expert game developer AI assistant for S Engine, a professional 2D/3D game engine for Android.
You help developers create games using JavaScript-like scripting, scene building, and game design.
You understand game development concepts, physics, collision, UI systems, animation, and best practices.
Always provide complete, working code examples when asked.
Use S Engine API conventions: self, scene.find(), transform, gameObject, etc.
""".trimIndent()
    }
    
    protected fun buildCodePrompt(userPrompt: String, context: String): String {
        val ctx = if (context.isNotEmpty()) "\n\nContext:\n$context" else ""
        return """
Generate JavaScript code for S Engine game engine.

Requirements:
- Use S Engine API (self, scene, transform, etc.)
- Include proper event handlers (start, update, onCollision, etc.)
- Add comments explaining the logic
- Make code complete and ready to use
- Follow best practices for game development

User Request:
$userPrompt$ctx

Generate the code:
""".trimIndent()
    }
}
