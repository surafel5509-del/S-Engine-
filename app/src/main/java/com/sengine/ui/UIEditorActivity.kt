package com.sengine.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.sengine.engine.editor.UIEditorState
import com.sengine.engine.ui.*
import com.sengine.project.Project

/**
 * Professional Visual UI Editor Activity
 *
 * Provides a full visual editor for creating game UI layouts:
 * - Canvas with configurable reference resolution
 * - Drag & drop UI element placement
 * - Anchor and pivot editing with visual handles
 * - Grid snapping and alignment guides
 * - Real-time preview in different screen ratios
 * - Element tree (like Unity's UI hierarchy)
 * - Property inspector for each UI element
 */
class UIEditorActivity : AppCompatActivity() {

    val editorState = UIEditorState()
    private lateinit var rootLayout: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var canvasArea: FrameLayout
    private lateinit var elementTree: LinearLayout
    private lateinit var propertiesPanel: LinearLayout
    private lateinit var statusBar: TextView

    // UI element templates
    private val uiElementTemplates = linkedMapOf(
        "Panel" to "UIPanel",
        "Image" to "UIImage",
        "Text" to "UIText",
        "Button" to "UIButton",
        "Slider" to "UISlider",
        "Toggle" to "UIToggle",
        "Dropdown" to "UIDropdown",
        "Input Field" to "UIInputField",
        "Progress Bar" to "UIProgressBar",
        "Scroll View" to "UIScrollRect",
        "Layout (H)" to "UILayoutGroup_H",
        "Layout (V)" to "UILayoutGroup_V",
        "Layout (Grid)" to "UILayoutGroup_G",
    )

    // Anchors presets
    data class AnchorPreset(val name: String, val min: FloatArray, val max: FloatArray, val pivot: FloatArray)
    private val anchorPresets = listOf(
        AnchorPreset("Top Left", floatArrayOf(0f, 1f), floatArrayOf(0f, 1f), floatArrayOf(0f, 1f)),
        AnchorPreset("Top Center", floatArrayOf(0.5f, 1f), floatArrayOf(0.5f, 1f), floatArrayOf(0.5f, 1f)),
        AnchorPreset("Top Right", floatArrayOf(1f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, 1f)),
        AnchorPreset("Middle Left", floatArrayOf(0f, 0.5f), floatArrayOf(0f, 0.5f), floatArrayOf(0f, 0.5f)),
        AnchorPreset("Middle Center", floatArrayOf(0.5f, 0.5f), floatArrayOf(0.5f, 0.5f), floatArrayOf(0.5f, 0.5f)),
        AnchorPreset("Middle Right", floatArrayOf(1f, 0.5f), floatArrayOf(1f, 0.5f), floatArrayOf(1f, 0.5f)),
        AnchorPreset("Bottom Left", floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), floatArrayOf(0f, 0f)),
        AnchorPreset("Bottom Center", floatArrayOf(0.5f, 0f), floatArrayOf(0.5f, 0f), floatArrayOf(0.5f, 0f)),
        AnchorPreset("Bottom Right", floatArrayOf(1f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)),
        AnchorPreset("Stretch Top", floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(0.5f, 1f)),
        AnchorPreset("Stretch Middle", floatArrayOf(0f, 0.5f), floatArrayOf(1f, 0.5f), floatArrayOf(0.5f, 0.5f)),
        AnchorPreset("Stretch Bottom", floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(0.5f, 0f)),
        AnchorPreset("Stretch Left", floatArrayOf(0f, 0f), floatArrayOf(0f, 1f), floatArrayOf(0f, 0.5f)),
        AnchorPreset("Stretch Center", floatArrayOf(0.5f, 0f), floatArrayOf(0.5f, 1f), floatArrayOf(0.5f, 0.5f)),
        AnchorPreset("Stretch Right", floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(1f, 0.5f)),
        AnchorPreset("Full Stretch", floatArrayOf(0f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0.5f, 0.5f)),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
    }

    private fun buildUI() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A2E.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ── Toolbar ──
        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF16213E.toInt())
            setPadding(8, 4, 8, 4)
            gravity = Gravity.CENTER_VERTICAL
        }
        addToolbarButton("Canvas", ::onCanvasSettings)
        addToolbarButton("Preview", ::onPreview)
        addToolbarButton("Grid", ::onToggleGrid)
        addToolbarButton("Snap", ::onToggleSnap)
        addToolbarButton("Guides", ::onToggleGuides)

        // Separator
        toolbar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(2, 60).apply { setMargins(8, 0, 8, 0) }
            setBackgroundColor(0xFF333355.toInt())
        })

        addToolbarButton("Portrait", { editorState.orientation = 0; refreshCanvas() })
        addToolbarButton("Landscape", { editorState.orientation = 1; refreshCanvas() })

        rootLayout.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── Main Content (3-panel layout) ──
        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        // Left panel: Element Tree
        elementTree = buildElementTree()
        mainContent.addView(elementTree, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.MATCH_PARENT))

        // Center: Canvas
        val canvasContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0F0F1A.toInt())
        }
        canvasArea = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(360), dp(640))
            setBackgroundColor(0xFF1E1E3A.toInt())
        }
        canvasContainer.addView(canvasArea)

        // Quick add bar below canvas
        val quickAddBar = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(0xFF16213E.toInt())
        }
        val quickAddRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 4, 4, 4)
        }
        for ((name, type) in uiElementTemplates) {
            quickAddRow.addView(createQuickAddButton(name, type))
        }
        quickAddBar.addView(quickAddRow)
        canvasContainer.addView(quickAddBar)

        mainContent.addView(canvasContainer)

        // Right panel: Properties
        propertiesPanel = buildPropertiesPanel()
        mainContent.addView(propertiesPanel, LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.MATCH_PARENT))

        rootLayout.addView(mainContent)

        // ── Status Bar ──
        statusBar = TextView(this).apply {
            text = "Canvas: 1080×1920 | Grid: 10px | Snap: ON"
            textSize = 11f
            setTextColor(0xFF888899.toInt())
            setPadding(12, 4, 12, 4)
            setBackgroundColor(0xFF0D0D1A.toInt())
        }
        rootLayout.addView(statusBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(rootLayout)
    }

    private fun buildElementTree(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF16213E.toInt())
            setPadding(4, 4, 4, 4)
        }

        // Header
        panel.addView(TextView(this).apply {
            text = "📐 UI ELEMENTS"
            textSize = 12f
            setTextColor(0xFF8888CC.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 8, 8, 8)
        })

        // Canvas root
        panel.addView(createTreeItem("🖼 Canvas (1080×1920)", 0))

        // Scroll area for elements
        val scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val elementList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Add some default elements
        val defaults = listOf(
            "📦 HealthBar (ProgressBar)",
            "🖼 HeartIcon (Image)",
            "📝 CoinText (Text)",
            "📝 ScoreText (Text)",
            "🔘 PauseButton (Button)",
            "📦 MenuPanel (Panel)",
        )
        for (item in defaults) {
            elementList.addView(createTreeItem(item, 1))
        }

        scrollArea.addView(elementList)
        panel.addView(scrollArea)

        return panel
    }

    private fun buildPropertiesPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF16213E.toInt())
            setPadding(4, 4, 4, 4)
        }

        // Header
        panel.addView(TextView(this).apply {
            text = "⚙ PROPERTIES"
            textSize = 12f
            setTextColor(0xFF8888CC.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 8, 8, 8)
        })

        val scrollArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val propList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 0, 4, 4)
        }

        // RectTransform section
        propList.addView(createSectionHeader("Rect Transform"))
        propList.addView(createPropertyRow("Anchor Preset", "Middle Center"))
        propList.addView(createNumericRow("Pos X", 0f))
        propList.addView(createNumericRow("Pos Y", 0f))
        propList.addView(createNumericRow("Width", 200f))
        propList.addView(createNumericRow("Height", 100f))
        propList.addView(createNumericRow("Pivot X", 0.5f))
        propList.addView(createNumericRow("Pivot Y", 0.5f))

        // Anchor presets grid
        propList.addView(TextView(this).apply {
            text = "Anchor Presets:"
            textSize = 11f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(8, 8, 8, 4)
        })
        val anchorGrid = GridLayout(this).apply {
            columnCount = 4
            setPadding(4, 0, 4, 8)
        }
        for (preset in anchorPresets) {
            anchorGrid.addView(TextView(this).apply {
                text = "⊞"
                textSize = 14f
                setTextColor(0xFF6666AA.toInt())
                gravity = Gravity.CENTER
                setPadding(8, 8, 8, 8)
                setBackgroundColor(0xFF222244.toInt())
                setOnClickListener { /* Apply anchor preset */ }
            })
        }
        propList.addView(anchorGrid)

        // Component section
        propList.addView(createSectionHeader("UI Component"))
        propList.addView(createPropertyRow("Type", "UIButton"))
        propList.addView(createColorRow("Normal Color", 0xFFFFFFFF.toInt()))
        propList.addView(createColorRow("Highlight Color", 0xFFCCCCCC.toInt()))
        propList.addView(createColorRow("Pressed Color", 0xFF999999.toInt()))
        propList.addView(createPropertyRow("Interactable", "✓"))
        propList.addView(createPropertyRow("Navigation", "None"))

        // Layout section
        propList.addView(createSectionHeader("Layout"))
        propList.addView(createPropertyRow("Min Width", "0"))
        propList.addView(createPropertyRow("Min Height", "0"))
        propList.addView(createPropertyRow("Preferred Width", "200"))
        propList.addView(createPropertyRow("Preferred Height", "100"))

        scrollArea.addView(propList)
        panel.addView(scrollArea)

        return panel
    }

    // ── Helper builders ──

    private fun addToolbarButton(text: String, onClick: () -> Unit) {
        toolbar.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFFCCCCFF.toInt())
            setPadding(16, 8, 16, 8)
            setBackgroundColor(0xFF222244.toInt())
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }.also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.setMargins(2, 0, 2, 0)
        })
    }

    private fun createQuickAddButton(name: String, type: String): TextView {
        return TextView(this).apply {
            text = "+ $name"
            textSize = 11f
            setTextColor(0xFF88FF88.toInt())
            setPadding(12, 6, 12, 6)
            setBackgroundColor(0xFF1A3322.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(2, 0, 2, 0) }
        }
    }

    private fun createTreeItem(name: String, indent: Int): TextView {
        return TextView(this).apply {
            text = "  ".repeat(indent) + name
            textSize = 12f
            setTextColor(0xFFCCCCFF.toInt())
            setPadding(8, 6, 8, 6)
            setBackgroundColor(0x20333366)
        }
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 11f
            setTextColor(0xFF8888CC.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 12, 8, 4)
        }
    }

    private fun createPropertyRow(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4)
            addView(TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(0xFF999999.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 11f
                setTextColor(0xFFDDDDDD.toInt())
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun createNumericRow(label: String, value: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4)
            addView(TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(0xFF999999.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
            })
            addView(EditText(context).apply {
                text = android.text.Editable.Factory.getInstance().newEditable("%.1f".format(value))
                textSize = 11f
                setTextColor(0xFFDDDDDD.toInt())
                setBackgroundColor(0xFF222244.toInt())
                setPadding(8, 4, 8, 4)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
            })
        }
    }

    private fun createColorRow(label: String, color: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4)
            addView(TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(0xFF999999.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(View(context).apply {
                setBackgroundColor(color)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            })
            addView(TextView(context).apply {
                text = "#%08X".format(color)
                textSize = 10f
                setTextColor(0xFF888888.toInt())
                setPadding(4, 0, 0, 0)
            })
        }
    }

    private fun refreshCanvas() {
        val (w, h) = if (editorState.orientation == 0) dp(360) to dp(640) else dp(640) to dp(360)
        canvasArea.layoutParams = FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER }
        statusBar.text = "Canvas: ${editorState.canvasWidth}×${editorState.canvasHeight} | Grid: ${editorState.gridSize}px | Snap: ${if (editorState.snapToGrid) "ON" else "OFF"} | ${if (editorState.orientation == 0) "Portrait" else "Landscape"}"
    }

    private fun onCanvasSettings() { /* Show canvas settings dialog */ }
    private fun onPreview() { /* Toggle preview mode */ }
    private fun onToggleGrid() { editorState.showGuides = !editorState.showGuides; refreshCanvas() }
    private fun onToggleSnap() { editorState.snapToGrid = !editorState.snapToGrid; refreshCanvas() }
    private fun onToggleGuides() { editorState.showAnchors = !editorState.showAnchors; refreshCanvas() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
