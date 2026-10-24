package com.sengine.engine.ai

import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * AI Assistant - Main orchestration layer for all AI features
 * Manages chat history, task execution, and response processing
 * Uses plain threads (no coroutines) for API calls
 */
class AIAssistant {
    
    // Chat history for context
    private val chatHistory = mutableListOf<ChatMessage>()
    private val maxHistorySize = 20
    
    // Callbacks
    var onStatusChanged: ((String) -> Unit)? = null
    var onResponse: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    // Task tracking
    var currentTaskId: String? = null
    @Volatile var isBusy: Boolean = false
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private fun postMain(block: () -> Unit) {
        mainHandler.post(block)
    }
    
    fun setStatus(status: String) {
        postMain { onStatusChanged?.invoke(status) }
    }
    
    /**
     * Send a chat message and get AI response
     */
    fun chat(userMessage: String, context: String = "") {
        if (isBusy) return
        
        val provider = AISettings.getActiveProvider()
        
        if (AISettings.apiKey.isEmpty()) {
            postMain { onError?.invoke("Please configure your ${AISettings.providerDisplayName} API key in Settings") }
            return
        }
        
        isBusy = true
        currentTaskId = UUID.randomUUID().toString()
        setStatus("Thinking...")
        
        // Add user message to history
        chatHistory.add(ChatMessage("user", userMessage))
        trimHistory()
        
        // Build messages with system prompt and context
        val systemPrompt = buildGameDevSystemPrompt() + if (context.isNotEmpty()) "\n\nAdditional Context:\n$context" else ""
        val messagesWithSystem = mutableListOf(ChatMessage("system", systemPrompt))
        messagesWithSystem.addAll(chatHistory)
        
        Thread {
            try {
                val response = provider.chatCompletion(messagesWithSystem)
                
                // Add assistant response to history
                chatHistory.add(ChatMessage("assistant", response))
                
                postMain {
                    setStatus("Ready")
                    onResponse?.invoke(response)
                    isBusy = false
                    currentTaskId = null
                }
            } catch (e: Exception) {
                postMain {
                    setStatus("Error")
                    onError?.invoke("Error: ${e.message}")
                    isBusy = false
                    currentTaskId = null
                }
            }
        }.start()
    }
    
    /**
     * Generate a complete game script from description
     */
    fun generateScript(description: String, gameObjectType: String, callback: (String) -> Unit) {
        if (isBusy) return
        
        val provider = AISettings.getActiveProvider()
        
        if (AISettings.apiKey.isEmpty()) {
            postMain { onError?.invoke("Please configure API key in Settings") }
            return
        }
        
        isBusy = true
        currentTaskId = UUID.randomUUID().toString()
        setStatus("Generating $gameObjectType script...")
        
        Thread {
            try {
                val code = provider.generateScript(description, gameObjectType)
                
                postMain {
                    setStatus("Ready")
                    callback(code)
                    isBusy = false
                    currentTaskId = null
                }
            } catch (e: Exception) {
                postMain {
                    setStatus("Error")
                    onError?.invoke("Error: ${e.message}")
                    isBusy = false
                    currentTaskId = null
                }
            }
        }.start()
    }
    
    /**
     * Generate a complete game from high-level description
     */
    fun generateGame(description: String, genre: String, callback: (GameGenerationResult) -> Unit) {
        if (isBusy) return
        
        val provider = AISettings.getActiveProvider()
        
        if (AISettings.apiKey.isEmpty()) {
            postMain { onError?.invoke("Please configure API key in Settings") }
            return
        }
        
        isBusy = true
        currentTaskId = UUID.randomUUID().toString()
        
        Thread {
            try {
                // Step 1: Generate game design document
                setStatus("Creating game design...")
                val design = provider.chatCompletion(listOf(
                    ChatMessage("system", buildGameDevSystemPrompt() + "\nYou are a game designer."),
                    ChatMessage("user", "Create a comprehensive game design for: $description\nGenre: $genre\n\nInclude: Game Overview, Core Mechanics, Player Controls, Game Objects, Level Structure, Win/Lose Conditions, Score System, Visual Style")
                ))
                
                // Step 2: Generate scene structure
                setStatus("Building scene structure...")
                val scene = provider.chatCompletion(listOf(
                    ChatMessage("system", buildGameDevSystemPrompt() + "\nYou are a scene builder. Output only valid JSON."),
                    ChatMessage("user", "Based on this design, generate the scene structure as JSON:\n$design\n\nOutput JSON with objects array containing name, position, scale, and components.")
                ))
                
                // Step 3: Generate scripts
                setStatus("Writing game scripts...")
                val scriptsResponse = provider.chatCompletion(listOf(
                    ChatMessage("system", buildGameDevSystemPrompt() + "\nYou are a game programmer."),
                    ChatMessage("user", "Based on this design, generate all required JavaScript scripts:\n$design\n\nFor each script use format: ---SCRIPT: name.js---\\n[code]\\n---END---")
                ))
                
                // Parse scripts from response
                val scripts = mutableMapOf<String, String>()
                val scriptPattern = "---SCRIPT: (\\S+)---\\n([\\s\\S]*?)---END---".toRegex()
                scriptPattern.findAll(scriptsResponse).forEach { match ->
                    scripts[match.groupValues[1]] = match.groupValues[2].trim()
                }
                
                // Step 4: Generate assets list
                setStatus("Creating asset list...")
                val assets = provider.chatCompletion(listOf(
                    ChatMessage("system", buildGameDevSystemPrompt() + "\nYou are an asset coordinator. Output only valid JSON."),
                    ChatMessage("user", "Based on this design, list all required assets as JSON array:\n$design")
                ))
                
                postMain {
                    setStatus("Ready")
                    callback(GameGenerationResult(design, scene, scripts, assets))
                    isBusy = false
                    currentTaskId = null
                }
            } catch (e: Exception) {
                postMain {
                    setStatus("Error")
                    onError?.invoke("Error: ${e.message}")
                    isBusy = false
                    currentTaskId = null
                }
            }
        }.start()
    }
    
    /**
     * Fix code with AI assistance
     */
    fun fixCode(code: String, errorDescription: String, callback: (String) -> Unit) {
        if (isBusy) return
        
        val provider = AISettings.getActiveProvider()
        isBusy = true
        currentTaskId = UUID.randomUUID().toString()
        setStatus("Fixing code...")
        
        Thread {
            try {
                val fixedCode = provider.fixCode(code, errorDescription)
                postMain {
                    setStatus("Ready")
                    callback(fixedCode)
                    isBusy = false
                    currentTaskId = null
                }
            } catch (e: Exception) {
                postMain {
                    setStatus("Error")
                    onError?.invoke("Error: ${e.message}")
                    isBusy = false
                    currentTaskId = null
                }
            }
        }.start()
    }
    
    /**
     * Analyze code for issues
     */
    fun analyzeCode(code: String, callback: (String) -> Unit) {
        if (isBusy) return
        
        val provider = AISettings.getActiveProvider()
        isBusy = true
        currentTaskId = UUID.randomUUID().toString()
        setStatus("Analyzing code...")
        
        Thread {
            try {
                val analysis = provider.analyzeCode(code)
                postMain {
                    setStatus("Ready")
                    callback(analysis)
                    isBusy = false
                    currentTaskId = null
                }
            } catch (e: Exception) {
                postMain {
                    setStatus("Error")
                    onError?.invoke("Error: ${e.message}")
                    isBusy = false
                    currentTaskId = null
                }
            }
        }.start()
    }
    
    /**
     * Generate dialogue for NPCs
     */
    fun generateDialogue(character: String, scenario: String, numberOfLines: Int, callback: (String) -> Unit) {
        if (isBusy) return
        
        val provider = AISettings.getActiveProvider()
        isBusy = true
        currentTaskId = UUID.randomUUID().toString()
        setStatus("Generating dialogue...")
        
        Thread {
            try {
                val dialogue = provider.generateDialogue(character, scenario, numberOfLines)
                postMain {
                    setStatus("Ready")
                    callback(dialogue)
                    isBusy = false
                    currentTaskId = null
                }
            } catch (e: Exception) {
                postMain {
                    setStatus("Error")
                    onError?.invoke("Error: ${e.message}")
                    isBusy = false
                    currentTaskId = null
                }
            }
        }.start()
    }
    
    /**
     * Generate level design
     */
    fun generateLevel(genre: String, difficulty: String, theme: String, callback: (String) -> Unit) {
        if (isBusy) return
        
        val provider = AISettings.getActiveProvider()
        isBusy = true
        currentTaskId = UUID.randomUUID().toString()
        setStatus("Designing level...")
        
        Thread {
            try {
                val level = provider.generateLevel(genre, difficulty, theme)
                postMain {
                    setStatus("Ready")
                    callback(level)
                    isBusy = false
                    currentTaskId = null
                }
            } catch (e: Exception) {
                postMain {
                    setStatus("Error")
                    onError?.invoke("Error: ${e.message}")
                    isBusy = false
                    currentTaskId = null
                }
            }
        }.start()
    }
    
    /**
     * Explain code or concepts
     */
    fun explain(content: String, callback: (String) -> Unit) {
        if (isBusy) return
        
        val provider = AISettings.getActiveProvider()
        isBusy = true
        currentTaskId = UUID.randomUUID().toString()
        setStatus("Explaining...")
        
        Thread {
            try {
                val explanation = provider.explain(content)
                postMain {
                    setStatus("Ready")
                    callback(explanation)
                    isBusy = false
                    currentTaskId = null
                }
            } catch (e: Exception) {
                postMain {
                    setStatus("Error")
                    onError?.invoke("Error: ${e.message}")
                    isBusy = false
                    currentTaskId = null
                }
            }
        }.start()
    }
    
    /**
     * Optimize code for performance
     */
    fun optimizeCode(code: String, callback: (String) -> Unit) {
        if (isBusy) return
        
        val provider = AISettings.getActiveProvider()
        isBusy = true
        currentTaskId = UUID.randomUUID().toString()
        setStatus("Optimizing code...")
        
        Thread {
            try {
                val optimized = provider.optimizeCode(code)
                postMain {
                    setStatus("Ready")
                    callback(optimized)
                    isBusy = false
                    currentTaskId = null
                }
            } catch (e: Exception) {
                postMain {
                    setStatus("Error")
                    onError?.invoke("Error: ${e.message}")
                    isBusy = false
                    currentTaskId = null
                }
            }
        }.start()
    }
    
    /**
     * Clear chat history
     */
    fun clearHistory() {
        chatHistory.clear()
    }
    
    /**
     * Trim chat history to max size
     */
    private fun trimHistory() {
        while (chatHistory.size > maxHistorySize) {
            chatHistory.removeAt(0)
        }
    }
    
    /**
     * Build system prompt for game development
     */
    private fun buildGameDevSystemPrompt(): String {
        return """
You are an expert game developer AI assistant for S Engine, a professional 2D/3D game engine for Android.

S Engine Features:
- 2D/3D rendering with OpenGL ES 2.0/3.0
- Physics engine (2D and 3D)
- Scripting with JavaScript-like syntax (Mozilla Rhino)
- UI system (Canvas, Button, Text, Image, etc.)
- Animation system (sprite sheets, skeletal)
- Audio system
- Scene management
- Component-based architecture
- Pathfinding, inventory, dialogue, tilemap systems
- Professional material and texture pipeline

S Engine API Basics:
- Game objects have components (SpriteRenderer, Rigidbody2D, Collider2D, Script, etc.)
- Scripts use: self (current object), scene, input, audio, storage, time
- Lifecycle: start(), update(dt), onCollision(other), onTrigger(other), onTap()
- Scene: scene.find(name), scene.findAll(tag), scene.spawn(name, x, y), scene.load(name)
- Transform: self.x, self.y, self.rotation, self.scaleX, self.scaleY
- Input: input.axisX, input.axisY, input.a, input.b, input.aDown, input.bDown
- Physics: self.vx, self.vy, self.grounded, self.addForce(), self.applyImpulse()
- Audio: audio.play(file), audio.beep()
- Storage: storage.get(key, default), storage.set(key, value)
- Helpers: after(sec, fn), every(sec, fn), random(), clamp(), lerp()

Always provide complete, working code. Follow best practices for game development.
""".trimIndent()
    }
}

/**
 * Result of AI game generation
 */
data class GameGenerationResult(
    val design: String,
    val sceneJson: String,
    val scripts: Map<String, String>,
    val assets: String
)
