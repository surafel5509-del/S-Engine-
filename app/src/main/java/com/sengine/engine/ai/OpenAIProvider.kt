package com.sengine.engine.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI (ChatGPT) Provider
 * Supports GPT-4, GPT-3.5-turbo, and other OpenAI models
 */
class OpenAIProvider(
    apiKey: String,
    model: String = "gpt-4-turbo-preview",
    temperature: Float = 0.7f,
    maxTokens: Int = 2000,
    topP: Float = 0.9f
) : BaseAIProvider(apiKey, model, temperature, maxTokens, topP) {
    
    override val name = "openai"
    override val displayName = "ChatGPT (OpenAI)"
    
    private val apiUrl = "https://api.openai.com/v1/chat/completions"
    
    override fun chatCompletion(messages: List<ChatMessage>): String {
        try {
            val url = URL(apiUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            
            // Build request body
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
            
            // Send request
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()
            
            // Read response
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
                throw Exception("OpenAI API error: $error")
            }
            
            connection.disconnect()
            return "Error: No response from OpenAI"
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
        val prompt = """
Create a $gameObjectType script for S Engine with the following behavior:
$description

Include all necessary event handlers and make it production-ready.
""".trimIndent()
        return generateCode(prompt, "Game Object Type: $gameObjectType")
    }
    
    override fun analyzeCode(code: String): String {
        val messages = listOf(
            ChatMessage("system", buildSystemPrompt() + "\nYou are a code review expert."),
            ChatMessage("user", """
Analyze this S Engine code and provide:
1. Code quality assessment
2. Potential bugs or issues
3. Performance concerns
4. Suggestions for improvement
5. Best practice recommendations

Code:
```javascript
$code
```
""".trimIndent())
        )
        return chatCompletion(messages)
    }
    
    override fun fixCode(code: String, errorDescription: String): String {
        val messages = listOf(
            ChatMessage("system", buildSystemPrompt() + "\nYou are a debugging expert."),
            ChatMessage("user", """
Fix the bugs in this S Engine code:

Error Description:
$errorDescription

Code:
```javascript
$code
```

Provide the complete fixed code with comments explaining what was fixed.
""".trimIndent())
        )
        return chatCompletion(messages)
    }
    
    override fun generateDialogue(character: String, scenario: String, numberOfLines: Int): String {
        val messages = listOf(
            ChatMessage("system", "You are a creative writer specializing in game dialogue."),
            ChatMessage("user", """
Generate $numberOfLines lines of dialogue for a game character.

Character: $character
Scenario: $scenario

Format each line as:
CharacterName: "Dialogue text"

Make it natural, engaging, and appropriate for the scenario.
""".trimIndent())
        )
        return chatCompletion(messages)
    }
    
    override fun generateLevel(genre: String, difficulty: String, theme: String): String {
        val messages = listOf(
            ChatMessage("system", buildSystemPrompt() + "\nYou are a level design expert."),
            ChatMessage("user", """
Design a game level with the following specifications:

Genre: $genre
Difficulty: $difficulty
Theme: $theme

Provide:
1. Level layout description
2. Key gameplay elements
3. Enemy placement and behavior
4. Power-ups and collectibles
5. Level progression flow
6. Code structure for implementing the level
""".trimIndent())
        )
        return chatCompletion(messages)
    }
    
    override fun explain(content: String): String {
        val messages = listOf(
            ChatMessage("system", buildSystemPrompt() + "\nYou are an educator who explains game development concepts clearly."),
            ChatMessage("user", "Explain the following in detail:\n\n$content")
        )
        return chatCompletion(messages)
    }
    
    override fun optimizeCode(code: String): String {
        val messages = listOf(
            ChatMessage("system", buildSystemPrompt() + "\nYou are a performance optimization expert."),
            ChatMessage("user", """
Optimize this S Engine code for better performance:

```javascript
$code
```

Provide:
1. Optimized code
2. Explanation of optimizations
3. Performance improvements achieved
""".trimIndent())
        )
        return chatCompletion(messages)
    }
}
