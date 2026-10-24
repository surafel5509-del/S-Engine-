package com.sengine.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.sengine.engine.ai.AIAssistant
import com.sengine.engine.ai.AISettings
import com.sengine.engine.ai.ChatMessage

/**
 * AI Assistant Activity - Chat interface for AI-powered game development
 * Fully programmatic UI matching S Engine's style
 */
class AIAssistantActivity : AppCompatActivity() {
    
    private val assistant = AIAssistant()
    private val messages = mutableListOf<ChatMessage>()
    
    private lateinit var rootLayout: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var sendButton: TextView
    private lateinit var statusText: TextView
    private lateinit var providerSpinner: Spinner
    private lateinit var quickActions: LinearLayout
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        setupAssistant()
    }
    
    private fun buildUI() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF12121F.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        
        // ── Toolbar ──
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF16213E.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        
        // Title
        toolbar.addView(TextView(this).apply {
            text = "🤖 AI Game Assistant"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        
        // Provider spinner
        providerSpinner = Spinner(this).apply {
            val providers = listOf("ChatGPT", "Gemini", "OpenRouter", "Groq")
            adapter = ArrayAdapter(this@AIAssistantActivity, android.R.layout.simple_spinner_item, providers).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val currentIdx = when (AISettings.activeProvider) {
                "openai" -> 0; "gemini" -> 1; "openrouter" -> 2; "groq" -> 3; else -> 0
            }
            setSelection(currentIdx)
            setBackgroundColor(0xFF222244.toInt())
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    AISettings.activeProvider = listOf("openai", "gemini", "openrouter", "groq")[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        toolbar.addView(providerSpinner)
        
        rootLayout.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // ── Quick Actions ──
        quickActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0D0D1A.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        
        val actions = listOf(
            "Generate Script" to { showScriptDialog() },
            "Fix Code" to { showFixCodeDialog() },
            "Analyze" to { showAnalyzeDialog() },
            "Level Design" to { showLevelDialog() },
            "Dialogue" to { showDialogueDialog() },
            "Explain" to { showExplainDialog() },
            "Optimize" to { showOptimizeDialog() }
        )
        
        for ((label, action) in actions) {
            quickActions.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(0xFF88CCFF.toInt())
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundColor(0xFF1A1A33.toInt())
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(dp(2), 0, dp(2), 0)
                layoutParams = lp
                setOnClickListener { action() }
            })
        }
        
        rootLayout.addView(quickActions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // ── Chat Area ──
        chatScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            setBackgroundColor(0xFF0A0A14.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        // Welcome message
        addMessageBubble("assistant", "🤖 Welcome to S Engine AI Assistant!\n\nI can help you with:\n• Generating game scripts\n• Fixing bugs in your code\n• Analyzing code quality\n• Designing levels\n• Writing NPC dialogue\n• Optimizing performance\n\nType a message or use the quick actions above!")
        
        chatScroll.addView(chatContainer)
        rootLayout.addView(chatScroll)
        
        // ── Status Bar ──
        statusText = TextView(this).apply {
            text = "● Ready  •  Provider: ${AISettings.getProviderDisplayName(AISettings.activeProvider)}"
            textSize = 11f
            setTextColor(0xFF44CC88.toInt())
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundColor(0xFF08081A.toInt())
        }
        rootLayout.addView(statusText)
        
        // ── Input Area ──
        val inputArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF16213E.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        
        messageInput = EditText(this).apply {
            hint = "Ask AI anything about game development..."
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666688.toInt())
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputArea.addView(messageInput)
        
        sendButton = TextView(this).apply {
            text = "⬆ Send"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF4488FF.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(8), 0, 0, 0)
            layoutParams = lp
            setOnClickListener { sendMessage() }
        }
        inputArea.addView(sendButton)
        
        rootLayout.addView(inputArea, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        setContentView(rootLayout)
    }
    
    private fun setupAssistant() {
        assistant.onStatusChanged = { status ->
            runOnUiThread {
                val color = when {
                    status == "Ready" -> 0xFF44CC88.toInt()
                    status == "Error" -> 0xFFFF4466.toInt()
                    else -> 0xFFFFAA44.toInt()
                }
                statusText.text = "● $status  •  Provider: ${AISettings.getProviderDisplayName(AISettings.activeProvider)}"
                statusText.setTextColor(color)
                sendButton.isEnabled = status == "Ready" || status == "Error"
            }
        }
        
        assistant.onResponse = { response ->
            runOnUiThread {
                addMessageBubble("assistant", response)
            }
        }
        
        assistant.onError = { error ->
            runOnUiThread {
                addMessageBubble("assistant", "❌ $error")
            }
        }
    }
    
    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        
        addMessageBubble("user", text)
        messageInput.text.clear()
        assistant.chat(text)
    }
    
    private fun addMessageBubble(role: String, content: String) {
        messages.add(ChatMessage(role, content))
        
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, dp(4), 0, dp(4))
            layoutParams = lp
            setPadding(dp(12), dp(8), dp(12), dp(8))
            
            if (role == "user") {
                setBackgroundColor(0xFF1A2744.toInt())
            } else {
                setBackgroundColor(0xFF1A1A33.toInt())
            }
        }
        
        // Role label
        bubble.addView(TextView(this).apply {
            text = if (role == "user") "👤 You" else "🤖 AI Assistant"
            textSize = 11f
            setTextColor(if (role == "user") 0xFF88CCFF.toInt() else 0xFF44CC88.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        
        // Content
        bubble.addView(TextView(this).apply {
            text = content
            textSize = 13f
            setTextColor(0xFFE0E0FF.toInt())
            setPadding(0, dp(4), 0, 0)
            movementMethod = ScrollingMovementMethod()
        })
        
        chatContainer.addView(bubble)
        
        // Scroll to bottom
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }
    
    // ── Quick Action Dialogs ──
    
    private fun showScriptDialog() {
        val input = EditText(this).apply {
            hint = "e.g., Player that can jump and shoot"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666688.toInt())
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@AIAssistantActivity, android.R.layout.simple_spinner_item, 
                listOf("Player", "Enemy", "NPC", "Item", "Platform", "UI Controller", "Custom"))
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Generate Script")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(TextView(this@AIAssistantActivity).apply { text = "Object Type:"; textSize = 12f; setTextColor(0xFF888899.toInt()) })
                addView(typeSpinner)
                addView(TextView(this@AIAssistantActivity).apply { text = "Describe the behavior:"; textSize = 12f; setTextColor(0xFF888899.toInt()); setPadding(0, dp(8), 0, dp(4)) })
                addView(input)
            })
            .setPositiveButton("Generate") { _, _ ->
                val desc = input.text.toString()
                val type = typeSpinner.selectedItem.toString()
                if (desc.isNotEmpty()) {
                    addMessageBubble("user", "Generate a $type script: $desc")
                    assistant.generateScript(desc, type) { code ->
                        runOnUiThread { addMessageBubble("assistant", "```javascript\n$code\n```") }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }
    
    private fun showFixCodeDialog() {
        val codeInput = EditText(this).apply {
            hint = "Paste your code here..."
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666688.toInt())
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minLines = 4
        }
        val errorInput = EditText(this).apply {
            hint = "Describe the error or bug..."
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666688.toInt())
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Fix Code")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(TextView(this@AIAssistantActivity).apply { text = "Your code:"; textSize = 12f; setTextColor(0xFF888899.toInt()) })
                addView(codeInput)
                addView(TextView(this@AIAssistantActivity).apply { text = "Error description:"; textSize = 12f; setTextColor(0xFF888899.toInt()); setPadding(0, dp(8), 0, dp(4)) })
                addView(errorInput)
            })
            .setPositiveButton("Fix") { _, _ ->
                val code = codeInput.text.toString()
                val error = errorInput.text.toString()
                if (code.isNotEmpty() && error.isNotEmpty()) {
                    addMessageBubble("user", "Fix this code: $error\n```\n$code\n```")
                    assistant.fixCode(code, error) { fixed ->
                        runOnUiThread { addMessageBubble("assistant", "✅ Fixed Code:\n```javascript\n$fixed\n```") }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showAnalyzeDialog() {
        val codeInput = EditText(this).apply {
            hint = "Paste code to analyze..."
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666688.toInt())
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minLines = 4
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Analyze Code")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(codeInput)
            })
            .setPositiveButton("Analyze") { _, _ ->
                val code = codeInput.text.toString()
                if (code.isNotEmpty()) {
                    addMessageBubble("user", "Analyze this code:\n```\n$code\n```")
                    assistant.analyzeCode(code) { analysis ->
                        runOnUiThread { addMessageBubble("assistant", "📋 Analysis:\n$analysis") }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showLevelDialog() {
        val themeInput = EditText(this).apply {
            hint = "e.g., Dark forest, Space station, Medieval castle"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666688.toInt())
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val genreSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@AIAssistantActivity, android.R.layout.simple_spinner_item,
                listOf("Platformer", "Puzzle", "RPG", "Shooter", "Racing", "Adventure"))
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        val diffSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@AIAssistantActivity, android.R.layout.simple_spinner_item,
                listOf("Easy", "Medium", "Hard", "Expert"))
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Generate Level")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(TextView(this@AIAssistantActivity).apply { text = "Theme:"; textSize = 12f; setTextColor(0xFF888899.toInt()) })
                addView(themeInput)
                addView(TextView(this@AIAssistantActivity).apply { text = "Genre:"; textSize = 12f; setTextColor(0xFF888899.toInt()); setPadding(0, dp(8), 0, dp(4)) })
                addView(genreSpinner)
                addView(TextView(this@AIAssistantActivity).apply { text = "Difficulty:"; textSize = 12f; setTextColor(0xFF888899.toInt()); setPadding(0, dp(8), 0, dp(4)) })
                addView(diffSpinner)
            })
            .setPositiveButton("Generate") { _, _ ->
                val theme = themeInput.text.toString()
                val genre = genreSpinner.selectedItem.toString()
                val diff = diffSpinner.selectedItem.toString()
                if (theme.isNotEmpty()) {
                    addMessageBubble("user", "Design a $diff $genre level with theme: $theme")
                    assistant.generateLevel(genre, diff, theme) { level ->
                        runOnUiThread { addMessageBubble("assistant", "🗺️ Level Design:\n$level") }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showDialogueDialog() {
        val charInput = EditText(this).apply {
            hint = "e.g., Wise old wizard, Grumpy shopkeeper"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666688.toInt())
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val scenarioInput = EditText(this).apply {
            hint = "e.g., Player asks for a quest, First meeting"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666688.toInt())
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Generate Dialogue")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(TextView(this@AIAssistantActivity).apply { text = "Character:"; textSize = 12f; setTextColor(0xFF888899.toInt()) })
                addView(charInput)
                addView(TextView(this@AIAssistantActivity).apply { text = "Scenario:"; textSize = 12f; setTextColor(0xFF888899.toInt()); setPadding(0, dp(8), 0, dp(4)) })
                addView(scenarioInput)
            })
            .setPositiveButton("Generate") { _, _ ->
                val char = charInput.text.toString()
                val scenario = scenarioInput.text.toString()
                if (char.isNotEmpty() && scenario.isNotEmpty()) {
                    addMessageBubble("user", "Write dialogue for $char in scenario: $scenario")
                    assistant.generateDialogue(char, scenario, 5) { dialogue ->
                        runOnUiThread { addMessageBubble("assistant", "💬 Dialogue:\n$dialogue") }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showExplainDialog() {
        val contentInput = EditText(this).apply {
            hint = "Paste code or describe a concept to explain..."
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666688.toInt())
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minLines = 3
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Explain")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(contentInput)
            })
            .setPositiveButton("Explain") { _, _ ->
                val content = contentInput.text.toString()
                if (content.isNotEmpty()) {
                    addMessageBubble("user", "Explain this:\n$content")
                    assistant.explain(content) { explanation ->
                        runOnUiThread { addMessageBubble("assistant", "📖 Explanation:\n$explanation") }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showOptimizeDialog() {
        val codeInput = EditText(this).apply {
            hint = "Paste code to optimize..."
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666688.toInt())
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minLines = 4
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Optimize Code")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(codeInput)
            })
            .setPositiveButton("Optimize") { _, _ ->
                val code = codeInput.text.toString()
                if (code.isNotEmpty()) {
                    addMessageBubble("user", "Optimize this code:\n```\n$code\n```")
                    assistant.optimizeCode(code) { optimized ->
                        runOnUiThread { addMessageBubble("assistant", "⚡ Optimized Code:\n$optimized") }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
