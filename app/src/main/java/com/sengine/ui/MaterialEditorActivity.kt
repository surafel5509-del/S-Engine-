package com.sengine.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.sengine.engine.textures.MaterialData
import com.sengine.engine.textures.TextureImportSettings
import com.sengine.engine.textures.TextureAtlas
import com.sengine.engine.editor.SpriteEditorData
import com.sengine.project.Project

/**
 * Professional Material & Texture Editor
 *
 * Full material editing experience:
 * - Material properties (albedo, metallic, roughness, normal, emissive)
 * - Texture import settings per texture
 * - Sprite sheet slicing editor
 * - Texture atlas packing
 * - Shader selection and parameter editing
 * - Material presets
 * - Live preview
 */
class MaterialEditorActivity : AppCompatActivity() {

    private var material = MaterialData()
    private var importProfile = TextureImportSettings()
    private var spriteEditor = SpriteEditorData()
    private var atlas = TextureAtlas()

    private lateinit var rootLayout: LinearLayout
    private var currentTab = 0 // 0=Material, 1=Texture, 2=Sprite, 3=Atlas

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        material.name = "DefaultMaterial"
        // importProfile.name = "DefaultImport" // Not needed for TextureImportSettings
        buildUI()
    }

    private fun buildUI() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF12121F.toInt())
        }

        // ── Tab bar ──
        val tabBar = buildTabBar()
        rootLayout.addView(tabBar)

        // ── Content ──
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // Left: Preview
        val previewPanel = buildPreviewPanel()
        content.addView(previewPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        // Right: Properties
        val propsPanel = buildPropertiesPanel()
        content.addView(propsPanel, LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.MATCH_PARENT))

        rootLayout.addView(content)

        // ── Status ──
        rootLayout.addView(TextView(this).apply {
            text = "Material: ${material.name} | Shader: ${material.shader} | Blend: ${blendModes[material.blendMode]}"
            textSize = 10f
            setTextColor(0xFF666688.toInt())
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundColor(0xFF08081A.toInt())
        })

        setContentView(rootLayout)
    }

    private fun buildTabBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0D0D1A.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val tabs = listOf("Material", "Texture Import", "Sprite Editor", "Atlas")
        for ((i, name) in tabs.withIndex()) {
            bar.addView(TextView(this).apply {
                text = name
                textSize = 12f
                setTextColor(if (i == currentTab) 0xFF4488FF.toInt() else 0xFF666688.toInt())
                setPadding(dp(20), dp(8), dp(20), dp(8))
                setBackgroundColor(if (i == currentTab) 0xFF1A1A33.toInt() else Color.TRANSPARENT)
                gravity = Gravity.CENTER
                setOnClickListener { currentTab = i; rebuildTabBar(bar) }
            })
        }
        return bar
    }

    private fun rebuildTabBar(bar: LinearLayout) {
        val parent = bar.parent as? LinearLayout ?: return
        val idx = parent.indexOfChild(bar)
        parent.removeViewAt(idx)
        parent.addView(buildTabBar(), idx)
    }

    private fun buildPreviewPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0A0A14.toInt())
        }

        // Preview area
        panel.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(400), dp(400))
            setBackgroundColor(0xFF1A1A2E.toInt())

            // Material preview (sphere visualization)
            addView(TextView(this@MaterialEditorActivity).apply {
                text = "⬤\nMaterial Preview"
                textSize = 14f
                setTextColor(0xFF888899.toInt())
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        })

        // Quick info
        panel.addView(TextView(this).apply {
            text = buildString {
                appendLine("Material: ${material.name}")
                appendLine("Shader: ${material.shader}")
                appendLine("Blend: ${blendModes[material.blendMode]}")
                appendLine("Color: #%08X".format(material.mainColor))
                appendLine("Metallic: %.2f | Roughness: %.2f".format(material.metallic, material.roughness))
            }
            textSize = 11f
            setTextColor(0xFF666688.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
        })

        return panel
    }

    private fun buildPropertiesPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF141428.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val scroll = ScrollView(this)
        val props = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(0), dp(4), dp(4))
        }

        when (currentTab) {
            0 -> buildMaterialProperties(props)
            1 -> buildTextureImportProperties(props)
            2 -> buildSpriteEditorProperties(props)
            3 -> buildAtlasProperties(props)
        }

        scroll.addView(props)
        panel.addView(scroll)
        return panel
    }

    private fun buildMaterialProperties(container: LinearLayout) {
        // Surface Inputs
        container.addView(section("Surface Inputs"))
        container.addView(assetRow("Albedo / Main Texture", material.mainTexture))
        container.addView(colorRow("Main Color", material.mainColor))
        container.addView(sliderRow("Metallic", material.metallic, 0f, 1f))
        container.addView(sliderRow("Roughness", material.roughness, 0f, 1f))
        container.addView(sliderRow("Smoothness", material.smoothness, 0f, 1f))
        container.addView(assetRow("Normal Map", material.normalMap))
        container.addView(sliderRow("Normal Strength", material.normalStrength, 0f, 2f))
        container.addView(assetRow("Occlusion Map", material.occlusionMap))
        container.addView(sliderRow("Occlusion Strength", material.occlusionStrength, 0f, 1f))
        container.addView(assetRow("Emissive Map", material.emissiveMap))
        container.addView(colorRow("Emissive Color", material.emissiveColor))
        container.addView(sliderRow("Emissive Intensity", material.emissiveIntensity, 0f, 10f))

        // Rendering
        container.addView(section("Rendering"))
        container.addView(choiceRow("Blend Mode", blendModes, material.blendMode))
        container.addView(choiceRow("Culling", listOf("Back", "Front", "Off"), material.culling))
        container.addView(boolRow("Z Write", material.zWrite))
        container.addView(choiceRow("Z Test", listOf("LEqual", "Always", "Less", "Equal"), material.zTest))
        container.addView(sliderRow("Alpha Cutoff", material.alphaCutoff, 0f, 1f))
        container.addView(intRow("Render Queue", material.renderQueue))

        // Shader
        container.addView(section("Shader"))
        container.addView(assetRow("Shader", material.shader))
        container.addView(textRow("Material Name", material.name))
    }

    private fun buildTextureImportProperties(container: LinearLayout) {
        container.addView(section("Texture Type"))
        container.addView(choiceRow("Type", listOf("Default", "Sprite", "UI", "Normal Map", "Sprite Sheet", "Atlas"), importProfile.textureType))
        container.addView(choiceRow("Sprite Mode", listOf("Single", "Multiple"), importProfile.spriteMode))
        container.addView(sliderRow("Pixels Per Unit", importProfile.pixelsPerUnit, 1f, 1000f))

        container.addView(section("Import Settings"))
        container.addView(choiceRow("Filter Mode", listOf("Point", "Bilinear", "Trilinear"), importProfile.filterMode))
        container.addView(choiceRow("Wrap Mode", listOf("Repeat", "Clamp", "Mirror"), importProfile.wrapMode))
        container.addView(intRow("Max Size", importProfile.maxTextureSize))
        container.addView(choiceRow("Compression", listOf("None", "Normal", "High"), importProfile.compression))
        container.addView(boolRow("Generate MipMaps", importProfile.generateMipMaps))
        container.addView(boolRow("Alpha Is Transparency", importProfile.alphaIsTransparency))
        container.addView(boolRow("sRGB (Color Texture)", importProfile.sRGB))

        container.addView(section("Non-Power-of-2"))
        container.addView(choiceRow("NPOT Scale", listOf("None", "To Nearest", "To Larger", "To Smaller"), 0))
    }

    private fun buildSpriteEditorProperties(container: LinearLayout) {
        container.addView(section("Sprite Slicing"))
        container.addView(choiceRow("Slice Mode", listOf("Auto Grid", "Auto Detect", "Manual", "By Tag"), spriteEditor.sliceMode.ordinal))
        container.addView(intRow("Cell Width", spriteEditor.gridCellWidth))
        container.addView(intRow("Cell Height", spriteEditor.gridCellHeight))
        container.addView(intRow("Offset X", spriteEditor.gridOffsetX))
        container.addView(intRow("Offset Y", spriteEditor.gridOffsetY))
        container.addView(intRow("Padding", spriteEditor.gridPadding))

        container.addView(section("Default Pivot"))
        container.addView(sliderRow("Pivot X", spriteEditor.defaultPivotX, 0f, 1f))
        container.addView(sliderRow("Pivot Y", spriteEditor.defaultPivotY, 0f, 1f))

        container.addView(section("Slice List (${spriteEditor.slices.size})"))
        for ((i, slice) in spriteEditor.slices.withIndex().take(20)) {
            container.addView(TextView(this).apply {
                text = "  ${slice.name} (${slice.width}×${slice.height})"
                textSize = 10f
                setTextColor(0xFFAAAAAA.toInt())
                setPadding(dp(12), dp(2), dp(8), dp(2))
            })
        }

        container.addView(section("9-Slice Borders"))
        container.addView(textRow("Selected Slice", if (spriteEditor.slices.isNotEmpty()) spriteEditor.slices[0].name else "None"))
        container.addView(intRow("Left Border", 0))
        container.addView(intRow("Right Border", 0))
        container.addView(intRow("Top Border", 0))
        container.addView(intRow("Bottom Border", 0))
    }

    private fun buildAtlasProperties(container: LinearLayout) {
        container.addView(section("Atlas Settings"))
        container.addView(textRow("Name", atlas.name))
        container.addView(textRow("Texture", atlas.atlasTexture))
        container.addView(intRow("Width", atlas.width))
        container.addView(intRow("Height", atlas.height))
        container.addView(intRow("Padding", atlas.padding))

        container.addView(section("Sprites (${atlas.sprites.size})"))
        for (sprite in atlas.sprites.take(20)) {
            container.addView(TextView(this).apply {
                text = "  ${sprite.name} (${sprite.width}×${sprite.height} at ${sprite.x},${sprite.y})"
                textSize = 10f
                setTextColor(0xFFAAAAAA.toInt())
                setPadding(dp(12), dp(2), dp(8), dp(2))
            })
        }

        container.addView(section("Pack Info"))
        container.addView(TextView(this).apply {
            text = "Utilization: --% | Free space: --px²"
            textSize = 11f
            setTextColor(0xFF888899.toInt())
            setPadding(dp(12), dp(4), dp(8), dp(4))
        })
    }

    // ── UI helpers ──

    private fun section(title: String): TextView = TextView(this).apply {
        text = title; textSize = 12f; setTextColor(0xFF4488FF.toInt()); typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), dp(10), dp(8), dp(4))
    }

    private fun textRow(label: String, value: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(3), dp(8), dp(3))
        addView(TextView(context).apply { text = label; textSize = 11f; setTextColor(0xFF888899.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f) })
        addView(EditText(context).apply { this.text = android.text.Editable.Factory.getInstance().newEditable(value); textSize = 11f; setTextColor(0xFFDDDDDD.toInt()); setBackgroundColor(0xFF222244.toInt()); setPadding(dp(8), dp(4), dp(8), dp(4)); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f) })
    }

    private fun intRow(label: String, value: Int): LinearLayout = textRow(label, value.toString())

    private fun colorRow(label: String, color: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(3), dp(8), dp(3))
        addView(TextView(context).apply { text = label; textSize = 11f; setTextColor(0xFF888899.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        addView(View(context).apply { setBackgroundColor(color); layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)) })
        addView(TextView(context).apply { text = "#%08X".format(color); textSize = 10f; setTextColor(0xFF888888.toInt()); setPadding(dp(4), 0, 0, 0) })
    }

    private fun sliderRow(label: String, value: Float, min: Float, max: Float): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(3), dp(8), dp(3))
        addView(TextView(context).apply { text = label; textSize = 11f; setTextColor(0xFF888899.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.35f) })
        addView(SeekBar(context).apply {
            this.progress = ((value - min) / (max - min) * 100).toInt().coerceIn(0, 100); this.max = 100
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.45f)
        })
        addView(TextView(context).apply { text = "%.2f".format(value); textSize = 10f; setTextColor(0xFFAAAAAA.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.2f); gravity = Gravity.END })
    }

    private fun choiceRow(label: String, options: List<String>, selected: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(3), dp(8), dp(3))
        addView(TextView(context).apply { text = label; textSize = 11f; setTextColor(0xFF888899.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        addView(Spinner(context).apply {
            adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_item, options).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selected.coerceIn(0, options.size - 1))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    private fun boolRow(label: String, value: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(3), dp(8), dp(3))
        addView(TextView(context).apply { text = label; textSize = 11f; setTextColor(0xFF888899.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        addView(CheckBox(context).apply {
            isChecked = value
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun assetRow(label: String, value: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(3), dp(8), dp(3))
        addView(TextView(context).apply { text = label; textSize = 11f; setTextColor(0xFF888899.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f) })
        addView(TextView(context).apply {
            text = if (value.isEmpty()) "None" else value; textSize = 10f; setTextColor(if (value.isEmpty()) 0xFF666666.toInt() else 0xFFAAAAAA.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
            setPadding(dp(4), dp(4), dp(4), dp(4)); setBackgroundColor(0xFF1A1A33.toInt())
        })
        addView(TextView(context).apply {
            text = "⋯"; textSize = 14f; setTextColor(0xFF4488FF.toInt()); gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
    }

    private val blendModes = listOf("Opaque", "Alpha Blend", "Premultiplied", "Additive", "Multiply")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
