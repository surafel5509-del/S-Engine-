package com.sengine.engine.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenRouter Provider
 * Aggregates many AI models through a single API (OpenAI-compatible format)
 */
class OpenRouterProvider(
    apiKey: String,
    model: String = "openai/gpt-4-turbo-preview",
    temperature: Float = 0.7f,
    maxTokens: Int = 2000,
    topP: Float = 0.9f
) : BaseAIProvider(apiKey, model, temperature, maxTokens, topP) {
    
    override val name = "openrouter"
    override val displayName = "OpenRouter"
    
    private val apiUrl = "https://openrouter.ai/api/v1/chat/completions"
    
    override fun chatCompletion(messages: List<ChatMessage>): String {
        try {
            val url = URL(apiUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("HTTP-Referer", "https://sengine.app")
            connection.setRequestProperty("X-Title", "S Engine AI Game Builder")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            
            val requestBody = JSONObject()
            requestBody.put("model", model)
            requestBody.put("temperature", temperature.toDouble())
            requestBody.put("max_tokens", maxTokens)
            requestBody.put("top_p", topP.toDouble())
            
            val messagesArray = JSONArray()
            messages.forEach { msg ->
                val msgObj = JSONObject()
                msgObj.put("role", msg.role)
                msgObj.put("content", msg.content)
                messagesArray.put(msgObj)
            }
            requestBody.put("messages", messagesArray)
            
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                
                val responseJson = JSONObject(response)
                val choices = responseJson.getJSONArray("choices")
                if (choices.length() > 0) {
                    return choices.getJSONObject(0).getJSONObject("message").getString("content")
                }
            } else {
                val errorStream = connection.errorStream
                val error = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream)).readText()
                } else {
                    "HTTP $responseCode"
                }
                throw Exception("OpenRouter API error: $error")
            }
            
            connection.disconnect()
            return "Error: No response from OpenRouter"
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
    }
    
    override fun generateCode(prompt: String, context: String): String {
        val messages = listOf(
            ChatMessage("system", buildSystemPrompt()),
            ChatMessage("user", buildCodePrompt(prompt, context))
        )
        return chatCompletion(messages)
    }
    
    override fun generateScript(description: String, gameObjectType: String): String {
        val prompt = "Create a $gameObjectType script for S Engine with the following behavior:\n$description\n\nInclude all necessary event handlers and make it production-ready."
        return generateCode(prompt, "Game Object Type: $gameObjectType")
    }
    
    override fun analyzeCode(code: String): String {
        val messages = listOf(
            ChatMessage("system", buildSystemPrompt() + "\nYou are a code review expert."),
            ChatMessage("user", "Analyze this S Engine code and provide: 1) Code quality assessment 2) Potential bugs 3) Performance concerns 4) Suggestions for improvement 5) Best practice recommendations\n\nCode:\n```javascript\n$code\n```")
        )
        return chatCompletion(messages)
    }
    
    override fun fixCode(code: String, errorDescription: String): String {
        val messages = listOf(
            ChatMessage("system", buildSystemPrompt() + "\nYou are a debugging expert."),
            ChatMessage("user", "Fix the bugs in this S Engine code:\n\nError: $errorDescription\n\nCode:\n```javascript\n$code\n```\n\nProvide the complete fixed code with comments.")
        )
        return chatCompletion(messages)
    }
    
    override fun generateDialogue(character: String, scenario: String, numberOfLines: Int): String {
        val messages = listOf(
            ChatMessage("system", "You are a creative writer specializing in game dialogue."),
            ChatMessage("user", "Generate $numberOfLines lines of dialogue.\n\nCharacter: $character\nScenario: $scenario\n\nFormat: CharacterName: \"Dialogue text\"")
        )
        return chatCompletion(messages)
    }
    
    override fun generateLevel(genre: String, difficulty: String, theme: String): String {
        val messages = listOf(
            ChatMessage("system", buildSystemPrompt() + "\nYou are a level design expert."),
            ChatMessage("user", "Design a game level:\nGenre: $genre\nDifficulty: $difficulty\nTheme: $theme\n\nProvide: 1) Level layout 2) Key gameplay elements 3) Enemy placement 4) Power-ups 5) Progression flow 6) Code structure")
        )
        return chatCompletion(messages)
    }
    
    override fun explain(content: String): String {
        val messages = listOf(
            ChatMessage("system", buildSystemPrompt() + "\nYou are an educator."),
            ChatMessage("user", "Explain the following in detail:\n\n$content")
        )
        return chatCompletion(messages)
    }
    
    override fun optimizeCode(code: String): String {
        val messages = listOf(
            ChatMessage("system", buildSystemPrompt() + "\nYou are a performance optimization expert."),
            ChatMessage("user", "Optimize this S Engine code:\n\n```javascript\n$code\n```\n\nProvide: 1) Optimized code 2) Explanation of optimizations 3) Performance improvements")
        )
        return chatCompletion(messages)
    }
}
