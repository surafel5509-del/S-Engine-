package com.sengine.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.sengine.engine.ai.AIGameBuilder
import com.sengine.engine.ai.AIAssistant
import com.sengine.engine.ai.AISettings

/**
 * AI Game Builder Activity - Generate complete games from text descriptions
 * Fully programmatic UI matching S Engine's style
 */
class AIGameBuilderActivity : AppCompatActivity() {
    
    private lateinit var assistant: AIAssistant
    private var currentResult: String? = null
    
    private lateinit var gameDescriptionInput: EditText
    private lateinit var genreSpinner: Spinner
    private lateinit var complexitySpinner: Spinner
    private lateinit var generateButton: TextView
    private lateinit var quickGenerateButton: TextView
    private lateinit var fullGameButton: TextView
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var resultScroll: ScrollView
    private lateinit var resultText: TextView
    private lateinit var examplesContainer: LinearLayout
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        assistant = AIAssistant()
        buildUI()
        setupAssistant()
    }
    
    private fun buildUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF12121F.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        
        // ── Toolbar ──
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF16213E.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        
        toolbar.addView(TextView(this).apply {
            text = "🎮 AI Game Builder"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        
        // Settings button
        toolbar.addView(TextView(this).apply {
            text = "⚙"
            textSize = 18f
            setTextColor(0xFF88CCFF.toInt())
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener {
                startActivity(android.content.Intent(this@AIGameBuilderActivity, AISettingsActivity::class.java))
            }
        })
        
        rootLayout.addView(toolbar)
        
        // ── Scrollable Content ──
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        
        // ── Description Input ──
        content.addView(sectionHeader("Game Description"))
        content.addView(TextView(this).apply {
            text = "Describe the game you want to create. Be as detailed as possible for best results."
            textSize = 12f
            setTextColor(0xFF888899.toInt())
            setPadding(dp(8), dp(2), dp(8), dp(4))
        })
        
        gameDescriptionInput = EditText(this).apply {
            hint = "e.g., A space shooter where the player controls a spaceship, dodges asteroids, shoots enemies, collects power-ups, and defeats a boss at the end of each level..."
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666688.toInt())
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minLines = 4
            gravity = Gravity.TOP
        }
        content.addView(gameDescriptionInput)
        
        // ── Options ──
        content.addView(sectionHeader("Options"))
        
        genreSpinner = Spinner(this).apply {
            val genres = listOf("Platformer", "Puzzle", "RPG", "Shooter", "Racing", "Adventure", "Strategy", "Arcade", "Endless Runner", "Tower Defense")
            adapter = ArrayAdapter(this@AIGameBuilderActivity, android.R.layout.simple_spinner_item, genres).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setBackgroundColor(0xFF222244.toInt())
        }
        content.addView(labeledInput("Genre", genreSpinner))
        
        complexitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@AIGameBuilderActivity, android.R.layout.simple_spinner_item,
                listOf("Simple (Quick Prototype)", "Medium (Playable Game)", "Complex (Full Game)"))
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setBackgroundColor(0xFF222244.toInt())
        }
        content.addView(labeledInput("Complexity", complexitySpinner))
        
        // ── Action Buttons ──
        content.addView(sectionHeader("Generate"))
        
        val buttonRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        
        quickGenerateButton = TextView(this).apply {
            text = "⚡ Quick Generate"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF4488FF.toInt())
            setPadding(dp(20), dp(12), dp(20), dp(12))
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(0, 0, dp(4), 0)
            layoutParams = lp
            setOnClickListener { startQuickGeneration() }
        }
        buttonRow1.addView(quickGenerateButton)
        
        fullGameButton = TextView(this).apply {
            text = "🎮 Full Game"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF8844FF.toInt())
            setPadding(dp(20), dp(12), dp(20), dp(12))
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(4), 0, 0, 0)
            layoutParams = lp
            setOnClickListener { startFullGeneration() }
        }
        buttonRow1.addView(fullGameButton)
        
        content.addView(buttonRow1)
        
        // ── Status ──
        progressText = TextView(this).apply {
            text = "Ready to generate"
            textSize = 12f
            setTextColor(0xFF44CC88.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        content.addView(progressText)
        
        // ── Examples ──
        content.addView(sectionHeader("Example Prompts"))
        
        examplesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        val examples = listOf(
            "A 2D platformer where a knight collects coins, jumps on enemies, and rescues a princess",
            "A space shooter with waves of enemies, power-ups, and boss battles",
            "A puzzle game where you match colored gems to clear levels",
            "A top-down RPG with NPCs, quests, inventory, and dungeon exploration",
            "An endless runner with obstacles, coins, and power-ups",
            "A tower defense game with multiple enemy types and upgradeable towers"
        )
        
        for (example in examples) {
            examplesContainer.addView(TextView(this).apply {
                text = "💡 $example"
                textSize = 11f
                setTextColor(0xFF88CCFF.toInt())
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setBackgroundColor(0xFF1A1A33.toInt())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, dp(2), 0, dp(2))
                layoutParams = lp
                setOnClickListener {
                    gameDescriptionInput.setText(example.removePrefix("💡 "))
                }
            })
        }
        content.addView(examplesContainer)
        
        // ── Result Area ──
        content.addView(sectionHeader("Generated Result"))
        
        resultScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300)
            )
            setBackgroundColor(0xFF0A0A14.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
        }
        
        resultText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFE0E0FF.toInt())
            movementMethod = ScrollingMovementMethod()
        }
        resultScroll.addView(resultText)
        content.addView(resultScroll)
        
        // Copy button
        val copyButton = TextView(this).apply {
            text = "📋 Copy to Clipboard"
            textSize = 12f
            setTextColor(0xFF88CCFF.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF1A1A33.toInt())
            visibility = View.GONE
            setOnClickListener {
                currentResult?.let {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AI Result", it))
                    Toast.makeText(this@AIGameBuilderActivity, "Copied!", Toast.LENGTH_SHORT).show()
                }
            }
            tag = "copyButton"
        }
        content.addView(copyButton)
        
        scroll.addView(content)
        rootLayout.addView(scroll)
        
        // ── Status Bar ──
        statusText = TextView(this).apply {
            text = "● Ready  •  Provider: ${AISettings.getProviderDisplayName(AISettings.activeProvider)}"
            textSize = 11f
            setTextColor(0xFF44CC88.toInt())
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundColor(0xFF08081A.toInt())
        }
        rootLayout.addView(statusText)
        
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
                
                val isBusy = assistant.isBusy
                quickGenerateButton.isEnabled = !isBusy
                fullGameButton.isEnabled = !isBusy
            }
        }
        
        assistant.onResponse = { response ->
            runOnUiThread {
                currentResult = response
                resultText.text = response
                resultScroll.visibility = View.VISIBLE
                // Show copy button
                val content = resultScroll.parent as? LinearLayout
                content?.let {
                    for (i in 0 until it.childCount) {
                        val child = it.getChildAt(i)
                        if (child.tag == "copyButton") child.visibility = View.VISIBLE
                    }
                }
                progressText.text = "✅ Generation complete!"
                progressText.setTextColor(0xFF44CC88.toInt())
            }
        }
        
        assistant.onError = { error ->
            runOnUiThread {
                progressText.text = "❌ $error"
                progressText.setTextColor(0xFFFF4466.toInt())
            }
        }
    }
    
    private fun startQuickGeneration() {
        val description = gameDescriptionInput.text.toString().trim()
        if (description.isEmpty()) {
            Toast.makeText(this, "Please enter a game description", Toast.LENGTH_SHORT).show()
            return
        }
        
        val genre = genreSpinner.selectedItem.toString()
        
        resultScroll.visibility = View.GONE
        progressText.text = "⚡ Generating quick prototype..."
        progressText.setTextColor(0xFFFFAA44.toInt())
        
        val prompt = """
Create a simple $genre game prototype:
$description

Generate:
1. Main scene with player and core gameplay
2. Essential scripts (player controller, game logic)
3. Simple scoring/progression

Keep it minimal but functional. Provide complete working code.
""".trimIndent()
        
        assistant.chat(prompt)
    }
    
    private fun startFullGeneration() {
        val description = gameDescriptionInput.text.toString().trim()
        if (description.isEmpty()) {
            Toast.makeText(this, "Please enter a game description", Toast.LENGTH_SHORT).show()
            return
        }
        
        val genre = genreSpinner.selectedItem.toString()
        val complexity = complexitySpinner.selectedItem.toString()
        
        resultScroll.visibility = View.GONE
        progressText.text = "🎮 Generating full game (this may take a minute)..."
        progressText.setTextColor(0xFFFFAA44.toInt())
        
        val prompt = """
Create a complete $complexity $genre game:
$description

Generate everything needed:
1. Complete game design document
2. Scene structure with all game objects (JSON format)
3. All required scripts (player, enemies, UI, game manager, etc.)
4. Asset list

Make it fully playable with:
- Proper game loop
- Win/lose conditions
- Score tracking
- UI elements
- Sound effects (placeholder)
- Professional code quality

Provide the complete implementation.
""".trimIndent()
        
        assistant.chat(prompt)
    }
    
    // ── UI Helpers ──
    
    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 14f
        setTextColor(0xFF4488FF.toInt())
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), dp(12), dp(8), dp(4))
    }
    
    private fun labeledInput(label: String, input: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(TextView(this@AIGameBuilderActivity).apply {
            text = label
            textSize = 12f
            setTextColor(0xFF888899.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f)
        })
        addView(input)
    }
    
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
