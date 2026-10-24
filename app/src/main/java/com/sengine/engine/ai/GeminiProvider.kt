package com.sengine.engine.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Gemini Provider
 * Supports Gemini Pro and other Google AI models
 */
class GeminiProvider(
    apiKey: String,
    model: String = "gemini-pro",
    temperature: Float = 0.7f,
    maxTokens: Int = 2000,
    topP: Float = 0.9f
) : BaseAIProvider(apiKey, model, temperature, maxTokens, topP) {
    
    override val name = "gemini"
    override val displayName = "Google Gemini"
    
    private val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
    
    override fun chatCompletion(messages: List<ChatMessage>): String {
        try {
            val url = URL("$apiUrl?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            
            // Build request body for Gemini API
            val requestBody = JSONObject()
            val contents = JSONArray()
            
            // Convert chat messages to Gemini format
            messages.forEach { msg ->
                val contentObj = JSONObject()
                val role = if (msg.role == "assistant") "model" else "user"
                contentObj.put("role", role)
                
                val parts = JSONArray()
                val partObj = JSONObject()
                partObj.put("text", msg.content)
                parts.put(partObj)
                
                contentObj.put("parts", parts)
                contents.put(contentObj)
            }
            requestBody.put("contents", contents)
            
            // Add generation config
            val generationConfig = JSONObject()
            generationConfig.put("temperature", temperature.toDouble())
            generationConfig.put("maxOutputTokens", maxTokens)
            generationConfig.put("topP", topP.toDouble())
            requestBody.put("generationConfig", generationConfig)
            
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
                val candidates = responseJson.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val content = candidates.getJSONObject(0).getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    if (parts.length() > 0) {
                        return parts.getJSONObject(0).getString("text")
                    }
                }
            } else {
                val errorStream = connection.errorStream
                val error = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream)).readText()
                } else {
                    "HTTP $responseCode"
                }
                throw Exception("Gemini API error: $error")
            }
            
            connection.disconnect()
            return "Error: No response from Gemini"
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
    }
    
    override fun generateCode(prompt: String, context: String): String {
        val messages = listOf(
            ChatMessage("user", buildSystemPrompt() + "\n\n" + buildCodePrompt(prompt, context))
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
            ChatMessage("user", buildSystemPrompt() + "\nYou are a code review expert.\n\n" + """
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
            ChatMessage("user", buildSystemPrompt() + "\nYou are a debugging expert.\n\n" + """
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
            ChatMessage("user", "You are a creative writer specializing in game dialogue.\n\n" + """
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
            ChatMessage("user", buildSystemPrompt() + "\nYou are a level design expert.\n\n" + """
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
            ChatMessage("user", buildSystemPrompt() + "\nYou are an educator who explains game development concepts clearly.\n\nExplain the following in detail:\n\n$content")
        )
        return chatCompletion(messages)
    }
    
    override fun optimizeCode(code: String): String {
        val messages = listOf(
            ChatMessage("user", buildSystemPrompt() + "\nYou are a performance optimization expert.\n\n" + """
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
