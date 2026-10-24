package com.sengine.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.sengine.engine.ai.AISettings

/**
 * AI Settings Activity - Configure API keys and provider settings
 * Fully programmatic UI matching S Engine's style
 */
class AISettingsActivity : AppCompatActivity() {
    
    private lateinit var openaiKeyInput: EditText
    private lateinit var geminiKeyInput: EditText
    private lateinit var openrouterKeyInput: EditText
    private lateinit var groqKeyInput: EditText
    
    private lateinit var openaiModelSpinner: Spinner
    private lateinit var geminiModelSpinner: Spinner
    private lateinit var openrouterModelSpinner: Spinner
    private lateinit var groqModelSpinner: Spinner
    
    private lateinit var activeProviderSpinner: Spinner
    private lateinit var temperatureSeekBar: SeekBar
    private lateinit var maxTokensSeekBar: SeekBar
    private lateinit var temperatureValue: TextView
    private lateinit var maxTokensValue: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        loadSettings()
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
            text = "⚙ AI Settings"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        
        // Save button
        toolbar.addView(TextView(this).apply {
            text = "💾 Save"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF44CC88.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER
            setOnClickListener { saveSettings() }
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
        
        // ── Active Provider ──
        content.addView(sectionHeader("Active Provider"))
        
        activeProviderSpinner = Spinner(this).apply {
            val providers = listOf("ChatGPT (OpenAI)", "Google Gemini", "OpenRouter", "Groq")
            adapter = ArrayAdapter(this@AISettingsActivity, android.R.layout.simple_spinner_item, providers).also {
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
        content.addView(inputRow("Provider", activeProviderSpinner))
        
        // ── API Keys ──
        content.addView(sectionHeader("API Keys"))
        content.addView(hintText("Enter your API keys below. Keys are stored locally on your device."))
        
        openaiKeyInput = createKeyInput("sk-... (OpenAI API Key)")
        content.addView(labeledInput("OpenAI (ChatGPT)", openaiKeyInput))
        
        geminiKeyInput = createKeyInput("AIza... (Google Gemini API Key)")
        content.addView(labeledInput("Google Gemini", geminiKeyInput))
        
        openrouterKeyInput = createKeyInput("sk-or-... (OpenRouter API Key)")
        content.addView(labeledInput("OpenRouter", openrouterKeyInput))
        
        groqKeyInput = createKeyInput("gsk_... (Groq API Key)")
        content.addView(labeledInput("Groq", groqKeyInput))
        
        // ── Model Selection ──
        content.addView(sectionHeader("Model Selection"))
        
        openaiModelSpinner = createModelSpinner(listOf("gpt-4-turbo-preview", "gpt-4", "gpt-3.5-turbo", "gpt-4o"))
        content.addView(labeledInput("OpenAI Model", openaiModelSpinner))
        
        geminiModelSpinner = createModelSpinner(listOf("gemini-pro", "gemini-1.5-pro", "gemini-1.5-flash"))
        content.addView(labeledInput("Gemini Model", geminiModelSpinner))
        
        openrouterModelSpinner = createModelSpinner(listOf(
            "openai/gpt-4-turbo-preview",
            "anthropic/claude-3-opus",
            "google/gemini-pro",
            "meta-llama/llama-3-70b-instruct",
            "mistralai/mixtral-8x22b-instruct"
        ))
        content.addView(labeledInput("OpenRouter Model", openrouterModelSpinner))
        
        groqModelSpinner = createModelSpinner(listOf("llama2-70b-4096", "mixtral-8x7b-32768", "gemma-7b-it", "llama3-70b-8192"))
        content.addView(labeledInput("Groq Model", groqModelSpinner))
        
        // ── Generation Settings ──
        content.addView(sectionHeader("Generation Settings"))
        
        temperatureValue = TextView(this).apply {
            text = "0.7"
            textSize = 12f
            setTextColor(0xFF88CCFF.toInt())
            gravity = Gravity.END
        }
        
        temperatureSeekBar = SeekBar(this).apply {
            max = 20
            progress = 7
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    AISettings.setTemperature(progress / 10f)
                    temperatureValue.text = "%.1f".format(progress / 10f)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        
        val tempRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(TextView(this@AISettingsActivity).apply {
                text = "Temperature (Creativity)"
                textSize = 12f
                setTextColor(0xFF888899.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f)
            })
            addView(temperatureSeekBar)
            addView(temperatureValue)
        }
        content.addView(tempRow)
        
        content.addView(hintText("Lower = more focused, Higher = more creative"))
        
        maxTokensValue = TextView(this).apply {
            text = "2000"
            textSize = 12f
            setTextColor(0xFF88CCFF.toInt())
            gravity = Gravity.END
        }
        
        maxTokensSeekBar = SeekBar(this).apply {
            max = 80
            progress = 20
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val tokens = (progress + 1) * 100
                    AISettings.setMaxTokens(tokens)
                    maxTokensValue.text = tokens.toString()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        
        val tokensRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(TextView(this@AISettingsActivity).apply {
                text = "Max Tokens"
                textSize = 12f
                setTextColor(0xFF888899.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f)
            })
            addView(maxTokensSeekBar)
            addView(maxTokensValue)
        }
        content.addView(tokensRow)
        
        // ── Links ──
        content.addView(sectionHeader("Get API Keys"))
        
        val links = listOf(
            "🔗 OpenAI API" to "https://platform.openai.com/api-keys",
            "🔗 Google AI Studio" to "https://aistudio.google.com/app/apikey",
            "🔗 OpenRouter" to "https://openrouter.ai/keys",
            "🔗 Groq" to "https://console.groq.com/keys"
        )
        
        for ((label, url) in links) {
            content.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(0xFF4488FF.toInt())
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (_: Exception) {}
                }
            })
        }
        
        // ── Info ──
        content.addView(sectionHeader("About"))
        content.addView(TextView(this).apply {
            text = "S Engine AI Game Builder uses large language models to help you create games faster. " +
                   "AI can generate scripts, design levels, write dialogue, fix bugs, and more.\n\n" +
                   "Your API keys are stored only on this device and sent directly to the AI provider. " +
                   "S Engine does not store or process your API keys or conversations."
            textSize = 11f
            setTextColor(0xFF666688.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(8))
        })
        
        scroll.addView(content)
        rootLayout.addView(scroll)
        
        setContentView(rootLayout)
    }
    
    private fun loadSettings() {
        openaiKeyInput.setText(AISettings.getOpenAIKey())
        geminiKeyInput.setText(AISettings.getGeminiKey())
        openrouterKeyInput.setText(AISettings.getOpenRouterKey())
        groqKeyInput.setText(AISettings.getGroqKey())
        
        // Set model spinners
        setSpinnerSelection(openaiModelSpinner, AISettings.getOpenAIModel())
        setSpinnerSelection(geminiModelSpinner, AISettings.getGeminiModel())
        setSpinnerSelection(openrouterModelSpinner, AISettings.getOpenRouterModel())
        setSpinnerSelection(groqModelSpinner, AISettings.getGroqModel())
        
        temperatureSeekBar.progress = (AISettings.getTemperature() * 10).toInt()
        temperatureValue.text = "%.1f".format(AISettings.getTemperature())
        maxTokensSeekBar.progress = (AISettings.getMaxTokens() / 100) - 1
        maxTokensValue.text = AISettings.getMaxTokens().toString()
    }
    
    private fun setSpinnerSelection(spinner: Spinner, value: String) {
        val adapter = spinner.adapter
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i).toString() == value) {
                spinner.setSelection(i)
                return
            }
        }
    }
    
    private fun saveSettings() {
        AISettings.setOpenAIKey(openaiKeyInput.text.toString().trim())
        AISettings.setGeminiKey(geminiKeyInput.text.toString().trim())
        AISettings.setOpenRouterKey(openrouterKeyInput.text.toString().trim())
        AISettings.setGroqKey(groqKeyInput.text.toString().trim())
        AISettings.setOpenAIModel(openaiModelSpinner.selectedItem.toString())
        AISettings.setGeminiModel(geminiModelSpinner.selectedItem.toString())
        AISettings.setOpenRouterModel(openrouterModelSpinner.selectedItem.toString())
        AISettings.setGroqModel(groqModelSpinner.selectedItem.toString())
        
        AISettings.save()
        
        Toast.makeText(this, "✅ Settings saved!", Toast.LENGTH_SHORT).show()
        finish()
    }
    
    // ── UI Helpers ──
    
    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 14f
        setTextColor(0xFF4488FF.toInt())
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), dp(16), dp(8), dp(4))
    }
    
    private fun hintText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(0xFF666688.toInt())
        setPadding(dp(12), dp(2), dp(12), dp(4))
    }
    
    private fun createKeyInput(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        textSize = 12f
        setTextColor(0xFFFFFFFF.toInt())
        setHintTextColor(0xFF666688.toInt())
        setBackgroundColor(0xFF1A1A2E.toInt())
        setPadding(dp(12), dp(8), dp(12), dp(8))
        inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
    }
    
    private fun labeledInput(label: String, input: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(TextView(this@AISettingsActivity).apply {
            text = label
            textSize = 12f
            setTextColor(0xFF888899.toInt())
            setPadding(0, 0, 0, dp(2))
        })
        addView(input)
    }
    
    private fun inputRow(label: String, input: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(TextView(this@AISettingsActivity).apply {
            text = label
            textSize = 12f
            setTextColor(0xFF888899.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
        })
        addView(input)
    }
    
    private fun createModelSpinner(models: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@AISettingsActivity, android.R.layout.simple_spinner_item, models).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setBackgroundColor(0xFF222244.toInt())
    }
    
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
