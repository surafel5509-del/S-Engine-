package com.sengine.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sengine.engine.sprite.Colors
import com.sengine.engine.sprite.PartColors
import com.sengine.engine.sprite.PixelOps
import com.sengine.engine.sprite.SpriteDocument
import com.sengine.engine.sprite.SpritePart
import com.sengine.engine.sprite.SpriteParts
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * **Sprite Studio** — the engine's pixel-art and character editor.
 *
 * Everything here is a real editing operation on the [SpriteDocument] model:
 * drawing tools, a frame reel with onion skin and playback, a built-in library of pre-made body parts
 * and props, image import, manual background removal (the user paints the area to cut out — nothing is
 * detected automatically), colour isolation (erase exactly one colour, or one connected region of it),
 * and export to PNG, sprite sheet, `.anim` clip and `.sprite` document.
 *
 * Opened from the editor menu or from the asset browser; pass `project` and optionally `sprite`
 * (document name) and `texture` (an existing image to trace over).
 */
class SpriteStudioActivity : AppCompatActivity() {

    private lateinit var project: Project
    private lateinit var doc: SpriteDocument
    private lateinit var canvas: SpriteCanvasView
    private lateinit var status: TextView
    private lateinit var frameStrip: LinearLayout
    private lateinit var toolRow: LinearLayout
    private lateinit var swatchRow: LinearLayout
    private lateinit var titleLabel: TextView
    private lateinit var brushLabel: TextView
    private lateinit var playButton: TextView
    private var playing = false
    private var playIndex = 0
    private var dirty = false
    private val handler = Handler(Looper.getMainLooper())
    private var colourTarget: Int = 0

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importImage(uri)
    }

    private val playRunnable = object : Runnable {
        override fun run() {
            if (!playing) return
            playIndex = (playIndex + 1) % doc.frameCount()
            doc.select(playIndex)
            canvas.rebuild()
            refreshFrames()
            handler.postDelayed(this, (1000f / doc.fps.coerceAtLeast(1f)).toLong())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        project = ProjectManager.open(this, intent.getStringExtra("project") ?: "")

        val spriteName = intent.getStringExtra("sprite")
        doc = (if (spriteName != null) project.loadSprite(spriteName) else null)
            ?: newDocument()
        val texture = intent.getStringExtra("texture")
        if (texture != null && doc.frameCount() == 1 && doc.pixels().all { it == 0 }) {
            project.readAssetBytes(texture)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { placeBitmap(it, fit = true) }
            }
        }

        val root = vbox().apply { setBackgroundColor(C.BG) }
        root.addView(buildTopBar(), lp(MATCH, WRAP))

        val middle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        middle.addView(buildToolColumn(), lp(WRAP, MATCH))
        canvas = SpriteCanvasView(this).apply {
            this.doc = doc
            onColorPicked = { c -> setColor(c) }
            onEdited = { dirty = true; refreshFrames() }
            onMaskChanged = { status.text = "Mask painted — press Apply to cut it out" }
            onStatus = { status.text = it }
        }
        middle.addView(canvas, lp(0, MATCH, 1f))
        middle.addView(buildRightColumn(), lp(WRAP, MATCH))
        root.addView(middle, lp(MATCH, 0, 1f))
        root.addView(buildBottomBar(), lp(MATCH, WRAP))

        setContentView(root)
        refreshAll()
        selectTool(SpriteCanvasView.Tool.PENCIL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { confirmExit() }
        })
    }

    override fun onDestroy() {
        playing = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ================================================================== document

    private fun newDocument(): SpriteDocument {
        val name = project.uniqueSpriteName("Sprite")
        return SpriteDocument(name, 32, 32).also { d ->
            // A new sprite starts with a friendly guide: a soft body silhouette placeholder is NOT
            // added, but the first frame is initialised with the standard skin palette ready to draw.
            d.pushUndo()
        }
    }

    private fun documentNameDialog() {
        val f = field(doc.name)
        MaterialAlertDialogBuilder(this).setTitle("Sprite name").setView(f)
            .setPositiveButton("OK") { _, _ ->
                val n = f.text.toString().trim().ifBlank { doc.name }
                if (n != doc.name) {
                    doc.name = project.uniqueSpriteName(n)
                    dirty = true
                    refreshTitle()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun resizeDialog() {
        val row = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val wf = field(doc.width.toString(), numeric = true)
        val hf = field(doc.height.toString(), numeric = true)
        row.addView(label("Width (${SpriteDocument.MIN_SIZE}-${SpriteDocument.MAX_SIZE})", 12f, C.DIM))
        row.addView(wf, lp(MATCH, WRAP))
        row.addView(label("Height", 12f, C.DIM))
        row.addView(hf, lp(MATCH, WRAP))
        var resample = false
        val check = android.widget.CheckBox(this).apply { text = "Resample art (otherwise keep pixels, re-centre)"; setTextColor(C.TEXT) }
        row.addView(check)
        MaterialAlertDialogBuilder(this).setTitle("Canvas size").setView(row)
            .setPositiveButton("Apply") { _, _ ->
                val w = wf.text.toString().toIntOrNull() ?: doc.width
                val h = hf.text.toString().toIntOrNull() ?: doc.height
                doc.resize(w, h, check.isChecked)
                dirty = true
                refreshAll(); canvas.fit()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ================================================================== UI: top bar

    private fun buildTopBar(): View {
        val bar = hbox().apply { setBackgroundColor(C.HEADER); setPadding(dp(6), dp(4), dp(6), dp(4)); gravity = Gravity.CENTER_VERTICAL }
        bar.addView(button("←") { confirmExit() })
        titleLabel = label("", 15f, C.TEXT, true).apply { setPadding(dp(10), 0, dp(10), 0); isSingleLine = true }
        bar.addView(titleLabel, lp(0, WRAP, 1f))
        bar.addView(button("Name") { documentNameDialog() }, lp(WRAP, WRAP).margins(dp(2), 0, dp(2), 0))
        bar.addView(button("Size…") { resizeDialog() }, lp(WRAP, WRAP).margins(dp(2), 0, dp(2), 0))
        bar.addView(button("☰") { editMenu() })
        bar.addView(button("Save", C.ACCENT, 0xFFFFFFFF.toInt()) { save() }, lp(WRAP, WRAP).margins(dp(4), 0, 0, 0))
        return bar
    }

    private fun refreshTitle() {
        titleLabel.text = "${doc.name}   ${doc.width}×${doc.height}" + if (dirty) " •" else ""
    }

    // ================================================================== UI: tools

    private fun buildToolColumn(): View {
        val col = vbox().apply {
            setBackgroundColor(C.PANEL)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        toolRow = vbox()
        val wrap = ScrollView(this).apply { addView(toolRow) }
        col.addView(wrap, lp(WRAP, 0, 1f))
        return col
    }

    private fun rebuildToolButtons() {
        toolRow.removeAllViews()
        for (t in SpriteCanvasView.Tool.values()) {
            val selected = canvas.tool == t
            val b = button(t.short, if (selected) C.SEL else C.PANEL2) { selectTool(t) }
            b.textSize = 17f
            b.gravity = Gravity.CENTER
            toolRow.addView(b, lp(dp(40), dp(38)).margins(0, dp(2), 0, dp(2)))
        }
    }

    private fun selectTool(t: SpriteCanvasView.Tool) {
        canvas.tool = t
        rebuildToolButtons()
        status.text = canvas.toolHint(t)
        brushLabel.text = "Brush ${canvas.brushSize}"
    }

    // ================================================================== UI: right column

    private fun buildRightColumn(): View {
        val col = vbox().apply { setBackgroundColor(C.PANEL); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        col.addView(label("Colour", 12f, C.DIM))
        swatchRow = vbox()
        col.addView(swatchRow)
        col.addView(button("Colour…") { pickColour() }, lp(dp(84), WRAP).margins(0, dp(4), 0, dp(6)))

        brushLabel = label("Brush 2", 12f, C.TEXT, true)
        col.addView(brushLabel)
        val brushBar = SeekBar(this).apply {
            max = 15
            progress = canvas.brushSize - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    canvas.brushSize = p + 1
                    brushLabel.text = "Brush ${p + 1}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        col.addView(brushBar, lp(dp(84), WRAP))

        col.addView(label("Tolerance", 12f, C.DIM))
        val tolLabel = label("${canvas.tolerance}", 11f, C.TEXT)
        col.addView(tolLabel)
        col.addView(SeekBar(this).apply {
            max = 255
            progress = canvas.tolerance
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    canvas.tolerance = p
                    tolLabel.text = "$p"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }, lp(dp(84), WRAP))

        col.addView(button("Apply mask") {
            val n = canvas.applyRemovalMask()
            if (n == 0) toast("Paint over the background first") else {
                status.text = "Removed $n masked pixels"
                dirty = true
                refreshFrames()
            }
        }, lp(dp(84), WRAP).margins(0, dp(6), 0, 0))
        col.addView(button("Clear mask") { canvas.clearMask() }, lp(dp(84), WRAP).margins(0, dp(2), 0, 0))

        col.addView(button("Parts…") { partsDialog() }, lp(dp(84), WRAP).margins(0, dp(8), 0, 0))
        col.addView(button("Import…") { pickImage.launch("image/*") }, lp(dp(84), WRAP).margins(0, dp(2), 0, 0))
        col.addView(button("Undo") { if (doc.undo()) { dirty = true; canvas.rebuild(); refreshFrames() } }, lp(dp(84), WRAP).margins(0, dp(8), 0, 0))
        col.addView(button("Redo") { if (doc.redo()) { dirty = true; canvas.rebuild(); refreshFrames() } }, lp(dp(84), WRAP).margins(0, dp(2), 0, 0))
        col.addView(button("Onion") { canvas.onionSkin = !canvas.onionSkin; canvas.rebuild(); status.text = "Onion skin ${if (canvas.onionSkin) "on" else "off"}" }, lp(dp(84), WRAP).margins(0, dp(8), 0, 0))
        col.addView(button("Grid") { canvas.showGrid = !canvas.showGrid }, lp(dp(84), WRAP).margins(0, dp(2), 0, 0))
        col.addView(button("Fit") { canvas.fit() }, lp(dp(84), WRAP).margins(0, dp(2), 0, 0))
        return col
    }

    private fun rebuildSwatches() {
        swatchRow.removeAllViews()
        val palette = intArrayOf(
            0xFF1B1B1F.toInt(), 0xFFFFFFFF.toInt(), 0xFF9E9E9E.toInt(), 0xFFE5534B.toInt(),
            0xFFFF9800.toInt(), 0xFFE0B341.toInt(), 0xFF57AB5A.toInt(), 0xFF26A69A.toInt(),
            0xFF4C8DFF.toInt(), 0xFF7E57C2.toInt(), 0xFFEC407A.toInt(), 0xFF8D6E63.toInt(),
            0xFFF2C39B.toInt(), 0xFF3E2723.toInt(), 0x00000000,
        )
        var row: LinearLayout? = null
        palette.forEachIndexed { i, c ->
            if (i % 3 == 0) {
                row = hbox()
                swatchRow.addView(row, lp(WRAP, WRAP))
            }
            val v = View(this).apply {
                background = round(if (Colors.a(c) == 0) 0xFF000000.toInt() else c, dp(4).toFloat(), dp(1), 0xFF555555.toInt())
                if (Colors.a(c) == 0) background = round(C.PANEL2, dp(4).toFloat(), dp(1), 0xFF888888.toInt())
                setOnClickListener { setColor(c) }
            }
            row!!.addView(v, lp(dp(24), dp(24)).margins(dp(1), dp(1), dp(1), dp(1)))
        }
    }

    private fun setColor(c: Int) {
        canvas.color = c
        rebuildSwatches()
    }

    private fun pickColour() {
        ColorPickerDialog.show(this, canvas.color) { c -> setColor(c) }
    }

    // ================================================================== UI: frames & bottom

    private fun buildBottomBar(): View {
        val col = vbox().apply { setBackgroundColor(C.PANEL) }
        val head = hbox().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(2)) }
        playButton = button("▶") { togglePlay() }
        head.addView(playButton, lp(WRAP, WRAP))
        head.addView(button("+ Frame") {
            doc.addFrame(false); dirty = true; canvas.rebuild(); refreshFrames()
        }, lp(WRAP, WRAP).margins(dp(3), 0, dp(3), 0))
        head.addView(button("Duplicate") {
            doc.duplicateFrame(); dirty = true; canvas.rebuild(); refreshFrames()
        }, lp(WRAP, WRAP).margins(dp(3), 0, dp(3), 0))
        head.addView(button("Delete") {
            if (!doc.deleteFrame()) toast("A sprite needs at least one frame")
            dirty = true; canvas.rebuild(); refreshFrames()
        }, lp(WRAP, WRAP).margins(dp(3), 0, dp(3), 0))
        head.addView(button("Clear") {
            doc.clearFrame(); dirty = true; canvas.rebuild(); refreshFrames()
        }, lp(WRAP, WRAP).margins(dp(3), 0, dp(3), 0))
        head.addView(button("FPS ${fmt(doc.fps)}") { fpsDialog() }, lp(WRAP, WRAP).margins(dp(3), 0, dp(3), 0))
        head.addView(button("Sheet…") { exportSheetDialog() }, lp(WRAP, WRAP).margins(dp(3), 0, dp(3), 0))
        head.addView(button("Export") { exportPng() }, lp(WRAP, WRAP).margins(dp(3), 0, dp(3), 0))
        col.addView(head, lp(MATCH, WRAP))

        frameStrip = hbox()
        val scroll = HorizontalScrollView(this).apply { addView(frameStrip) }
        col.addView(scroll, lp(MATCH, dp(62)))
        status = label("", 11f, C.DIM).apply { setPadding(dp(8), dp(2), dp(8), dp(4)) }
        col.addView(status, lp(MATCH, WRAP))
        return col
    }

    private fun refreshFrames() {
        frameStrip.removeAllViews()
        for (i in 0 until doc.frameCount()) {
            val cell = FrameLayout(this).apply {
                background = round(if (i == doc.selected) C.SEL else C.PANEL2, dp(4).toFloat(), dp(1), 0xFF444444.toInt())
                setOnClickListener {
                    doc.select(i)
                    canvas.rebuild()
                    refreshFrames()
                }
            }
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(thumbnail(doc.frames[i].pixels))
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            cell.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
            cell.addView(label("${i + 1}", 9f, if (i == doc.selected) 0xFFFFFFFF.toInt() else C.DIM)
                .apply { setPadding(dp(3), 0, 0, 0) }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START))
            frameStrip.addView(cell, lp(dp(50), dp(50)).margins(dp(3), dp(2), dp(3), dp(2)))
        }
        refreshTitle()
    }

    private fun thumbnail(px: IntArray): Bitmap =
        Bitmap.createBitmap(px, doc.width, doc.height, Bitmap.Config.ARGB_8888)

    private fun refreshAll() {
        canvas.doc = doc
        canvas.rebuild()
        canvas.post { canvas.fit() }
        rebuildSwatches()
        rebuildToolButtons()
        refreshFrames()
        refreshTitle()
        status.text = "Ready. Tool: ${canvas.tool.label}"
    }

    private fun togglePlay() {
        playing = !playing
        playButton.text = if (playing) "■" else "▶"
        if (playing) {
            if (doc.frameCount() < 2) toast("Add frames to preview an animation")
            playIndex = doc.selected
            handler.post(playRunnable)
        } else {
            handler.removeCallbacks(playRunnable)
            canvas.rebuild()
            refreshFrames()
        }
    }

    private fun fpsDialog() {
        val f = field(fmt(doc.fps), numeric = true)
        MaterialAlertDialogBuilder(this).setTitle("Frames per second").setView(f)
            .setPositiveButton("OK") { _, _ ->
                doc.fps = f.text.toString().toFloatOrNull()?.coerceIn(1f, 60f) ?: doc.fps
                dirty = true
                refreshFrames()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ================================================================== parts library

    private fun partsDialog() {
        if (playIndex >= 0 && playing) togglePlay()
        val root = vbox().apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        val tabs = HorizontalScrollView(this)
        val tabRow = hbox()
        tabs.addView(tabRow)
        root.addView(tabs, lp(MATCH, WRAP))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(grid) }
        root.addView(scroll, lp(MATCH, dp(320)))

        val dialog = MaterialAlertDialogBuilder(this).setTitle("Parts library").setView(root)
            .setNegativeButton("Close", null).create()
        var category = "All"

        fun fill() {
            grid.removeAllViews()
            var row: LinearLayout? = null
            SpriteParts.inCategory(category).forEachIndexed { i, part ->
                if (i % 3 == 0) {
                    row = hbox()
                    grid.addView(row, lp(MATCH, WRAP))
                }
                val cell = vbox().apply {
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    setOnClickListener { partOptions(part, dialog) }
                }
                val preview = SpriteParts.render(part, 32, 32)
                cell.addView(ImageView(this).apply {
                    setImageBitmap(Bitmap.createBitmap(preview, 32, 32, Bitmap.Config.ARGB_8888))
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    setBackgroundColor(0xFF1B1C20.toInt())
                }, lp(dp(56), dp(56)))
                cell.addView(label(part.name, 9f, C.TEXT).apply { isSingleLine = true }, lp(dp(64), WRAP))
                row!!.addView(cell, lp(0, WRAP, 1f).margins(dp(2), dp(2), dp(2), dp(2)))
            }
        }

        for (c in listOf("All") + SpriteParts.categories) {
            val b = button(c) {
                category = c
                fill()
            }
            b.textSize = 11f
            tabRow.addView(b, lp(WRAP, WRAP).margins(dp(2), 0, dp(2), 0))
        }
        fill()
        dialog.show()
    }

    private fun partOptions(part: SpritePart, parent: android.app.Dialog) {
        val root = vbox().apply { setPadding(dp(20), dp(6), dp(20), 0) }
        root.addView(label(part.description, 12f, C.DIM))
        var scale = 1f
        var primary = 0xFF4A5A6E.toInt()
        var secondary = 0xFFD9A066.toInt()
        var accent = 0xFFE05A47.toInt()
        val previewImage = ImageView(this)
        fun refreshPreview() {
            val art = SpriteParts.render(part, part.defaultWidth, part.defaultHeight, PartColors(primary, secondary, accent, 0xFF1A1A22.toInt()))
            previewImage.setImageBitmap(Bitmap.createBitmap(art, part.defaultWidth, part.defaultHeight, Bitmap.Config.ARGB_8888))
        }
        refreshPreview()
        root.addView(previewImage, lp(dp(96), dp(96)).margins(0, dp(4), 0, dp(4)))
        val scaleLabel = label("Scale ${fmt(scale)}×", 12f, C.TEXT)
        root.addView(scaleLabel)
        root.addView(SeekBar(this).apply {
            max = 30
            progress = 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    scale = max(0.25f, (p + 1) / 10f)
                    scaleLabel.text = "Scale ${fmt(scale)}×"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }, lp(MATCH, WRAP))
        val colors = hbox()
        colors.addView(button("Main", C.PANEL2) { ColorPickerDialog.show(this, primary) { primary = it; refreshPreview() } }, lp(0, WRAP, 1f))
        colors.addView(button("Second", C.PANEL2) { ColorPickerDialog.show(this, secondary) { secondary = it; refreshPreview() } }, lp(0, WRAP, 1f))
        colors.addView(button("Accent", C.PANEL2) { ColorPickerDialog.show(this, accent) { accent = it; refreshPreview() } }, lp(0, WRAP, 1f))
        root.addView(colors, lp(MATCH, WRAP).margins(0, dp(6), 0, 0))

        MaterialAlertDialogBuilder(this).setTitle(part.name).setView(root)
            .setPositiveButton("Add to sprite") { _, _ ->
                doc.pushUndo()
                SpriteParts.stampInto(doc, part, doc.width / 2, doc.height / 2, scale, PartColors(primary, secondary, accent, 0xFF1A1A22.toInt()))
                dirty = true
                canvas.rebuild()
                refreshFrames()
                status.text = "${part.name} added — drag it into place with the drawing tools"
                parent.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ================================================================== image import

    private fun importImage(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run { toast("Could not decode that image"); return }
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "image.png"
            placeBitmap(bmp)
            status.text = "Imported $name (${bmp.width}×${bmp.height}) into frame ${doc.selected + 1}"
            bmp.recycle()
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
    }

    /**
     * Places a decoded image into the current frame. Oversized images are downscaled to fit the canvas
     * so nothing is silently cropped; transparency is preserved.
     */
    private fun placeBitmap(bmp: Bitmap, fit: Boolean = false) {
        doc.pushUndo()
        val targetW = doc.width
        val targetH = doc.height
        var scaled = bmp
        if (bmp.width > targetW || bmp.height > targetH) {
            val k = minOf(targetW.toFloat() / bmp.width, targetH.toFloat() / bmp.height)
            scaled = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true)
        }
        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bmp) scaled.recycle()
        val dx = (targetW - w) / 2
        val dy = (targetH - h) / 2
        PixelOps.stamp(doc.pixels(), targetW, targetH, pixels, w, h, dx, dy, 1f)
        dirty = true
        canvas.rebuild()
        refreshFrames()
        if (fit) canvas.post { canvas.fit() }
    }

    // ================================================================== edit menu

    private fun editMenu() {
        val items = arrayOf(
            "Flip horizontal", "Flip vertical", "Rotate 90° right", "Rotate 90° left",
            "Outline art", "Posterise (8 colours)", "Remove colour…", "Trim transparent border",
            "New frame from import", "Save copy", "Delete sprite", "Document info",
        )
        MaterialAlertDialogBuilder(this).setTitle("Edit").setItems(items) { _, i ->
            // snapshot every destructive edit as one undo entry
            when (i) {
                0 -> { doc.pushUndo(); for (f in doc.frames) f.pixels = PixelOps.flipHorizontal(f.pixels, doc.width, doc.height); done() }
                1 -> { doc.pushUndo(); for (f in doc.frames) f.pixels = PixelOps.flipVertical(f.pixels, doc.width, doc.height); done() }
                2 -> rotate(1)
                3 -> rotate(-1)
                4 -> { doc.pushUndo(); PixelOps.outline(doc.pixels(), doc.width, doc.height, canvas.secondary, 1); done() }
                5 -> { doc.pushUndo(); for (f in doc.frames) f.pixels = PixelOps.posterize(f.pixels, doc.width, doc.height, 8); done() }
                6 -> removeColourDialog()
                7 -> trim()
                8 -> pickImage.launch("image/*")
                9 -> saveCopy()
                10 -> confirmDelete()
                11 -> infoDialog()
            }
        }.show()
    }

    private fun done() {
        dirty = true
        canvas.rebuild()
        refreshFrames()
    }

    private fun rotate(turns: Int) {
        val t = ((turns % 4) + 4) % 4
        if (t == 0) return
        doc.pushUndo()
        val oldW = doc.width
        val oldH = doc.height
        var newW = oldW
        var newH = oldH
        for (f in doc.frames) {
            val (px, w, h) = PixelOps.rotate90(f.pixels, oldW, oldH, t)
            f.pixels = px
            newW = w
            newH = h
        }
        if (newW != oldW || newH != oldH) doc.adoptSize(newW, newH)
        canvas.post { canvas.fit() }
        done()
    }

    private fun removeColourDialog() {
        val f = field("#FFFFFF")
        MaterialAlertDialogBuilder(this).setTitle("Remove colour")
            .setMessage("Erases every pixel of this colour (colour isolation).")
            .setView(f)
            .setPositiveButton("Remove") { _, _ ->
                val c = Colors.parse(f.text.toString()) ?: run { toast("Use #RRGGBB"); return@setPositiveButton }
                doc.pushUndo()
                var n = 0
                for (fr in doc.frames) {
                    val px = fr.pixels
                    for (i in px.indices) if (Colors.similar(px[i], c, canvas.tolerance)) { px[i] = 0; n++ }
                }
                done()
                status.text = "Removed $n pixels of ${Colors.toHex(c)}"
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun trim() {
        val b = PixelOps.bounds(doc.pixels(), doc.width, doc.height) ?: run { toast("Nothing to trim"); return }
        doc.pushUndo()
        for (f in doc.frames) f.pixels = PixelOps.crop(f.pixels, doc.width, doc.height, b[0], b[1], b[2], b[3])
        doc.resize(b[2], b[3], resample = false)
        canvas.post { canvas.fit() }
        done()
    }

    private fun infoDialog() {
        val used = doc.frames.sumOf { fr -> fr.pixels.count { Colors.a(it) != 0 } }
        MaterialAlertDialogBuilder(this).setTitle("Sprite info")
            .setMessage("${doc.name}\n${doc.width}×${doc.height}px\n${doc.frameCount()} frame(s) @ ${fmt(doc.fps)} fps\n" +
                "$used painted pixels\n" + (if (doc.loop) "Loops" else "Plays once"))
            .setPositiveButton("Close", null).show()
    }

    // ================================================================== saving & export

    private fun save(quiet: Boolean = false) {
        if (doc.name.isBlank()) doc.name = project.uniqueSpriteName("Sprite")
        try {
            project.saveSprite(doc)
            val pngs = exportFramesToProject()
            dirty = false
            refreshTitle()
            if (!quiet) toast("Saved ${doc.name} (${pngs} PNG${if (pngs == 1) "" else "s"})")
        } catch (e: Exception) {
            toast("Save failed: ${e.message}")
        }
    }

    /** Writes every frame as a PNG asset (`Name.png`, `Name_2.png`, ...) and returns the count. */
    private fun exportFramesToProject(): Int {
        var n = 0
        for (i in 0 until doc.frameCount()) {
            val name = if (i == 0) "${doc.name}.png" else "${doc.name}_${i + 1}.png"
            val bmp = Bitmap.createBitmap(doc.frames[i].pixels, doc.width, doc.height, Bitmap.Config.ARGB_8888)
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bmp.recycle()
            project.writeAssetBytes(name, bos.toByteArray())
            n++
        }
        return n
    }

    private fun exportPng() {
        save(quiet = true)
        toast("Frames exported to the project assets")
    }

    private fun saveCopy() {
        val copy = SpriteDocument(doc.name, doc.width, doc.height)
        copy.copyFrom(doc)
        copy.name = project.uniqueSpriteName(doc.name + " copy")
        project.saveSprite(copy)
        toast("Saved ${copy.name}")
    }

    /** Builds a horizontal sprite sheet plus a matching `.anim` clip. */
    private fun exportSheetDialog() {
        val root = vbox().apply { setPadding(dp(20), dp(6), dp(20), 0) }
        val nameField = field(doc.name + "_sheet")
        root.addView(label("Sheet file name", 12f, C.DIM))
        root.addView(nameField, lp(MATCH, WRAP))
        var cell = max(doc.width, doc.height)
        var fps = doc.fps
        var loop = doc.loop
        val cellField = field(doc.width.toString(), numeric = true)
        root.addView(label("Cell width (frames are placed left to right)", 12f, C.DIM))
        root.addView(cellField, lp(MATCH, WRAP))
        val check = android.widget.CheckBox(this).apply { text = "Loop"; isChecked = loop; setTextColor(C.TEXT) }
        root.addView(check)
        MaterialAlertDialogBuilder(this).setTitle("Sprite sheet + animation").setView(root)
            .setPositiveButton("Export") { _, _ ->
                cell = cellField.text.toString().toIntOrNull()?.coerceIn(1, 1024) ?: doc.width
                fps = doc.fps
                loop = check.isChecked
                try {
                    val n = doc.frameCount()
                    val sheetName = project.uniqueAssetName(sanitize(nameField.text.toString()).ifBlank { doc.name } + ".png")
                    val sheet = Bitmap.createBitmap(cell * n, max(doc.height, cell), Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(sheet)
                    val paint = android.graphics.Paint().apply { isFilterBitmap = false }
                    for (i in 0 until n) {
                        val frame = Bitmap.createBitmap(doc.frames[i].pixels, doc.width, doc.height, Bitmap.Config.ARGB_8888)
                        c.drawBitmap(frame, (i * cell).toFloat(), 0f, paint)
                        frame.recycle()
                    }
                    val bos = ByteArrayOutputStream()
                    sheet.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    sheet.recycle()
                    project.writeAssetBytes(sheetName, bos.toByteArray())
                    val clipName = sheetName.substringBeforeLast('.') + ".anim"
                    project.writeAsset(clipName, sheetJson(sheetName, n, fps, loop))
                    save(quiet = true)
                    toast("Wrote $sheetName and $clipName ($n frames)")
                } catch (e: Exception) {
                    toast("Export failed: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun sheetJson(texture: String, frames: Int, fps: Float, loop: Boolean): String {
        val sb = StringBuilder("{\n")
        sb.append("  \"texture\": \"").append(texture).append("\",\n")
        sb.append("  \"columns\": ").append(frames).append(",\n")
        sb.append("  \"rows\": 1,\n")
        sb.append("  \"frames\": [")
        for (i in 0 until frames) { if (i > 0) sb.append(", "); sb.append(i) }
        sb.append("],\n")
        sb.append("  \"fps\": ").append(fps).append(",\n")
        sb.append("  \"loop\": ").append(loop).append(",\n")
        sb.append("  \"pivotX\": 0.5,\n  \"pivotY\": 0.5\n}\n")
        return sb.toString()
    }

    private fun sanitize(s: String): String =
        s.trim().replace(' ', '_').filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }

    // ================================================================== exit

    private fun confirmExit() {
        if (!dirty) { finish(); return }
        MaterialAlertDialogBuilder(this).setTitle("Save sprite?")
            .setMessage("You have unsaved changes to ${doc.name}.")
            .setPositiveButton("Save") { _, _ -> save(); finish() }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this).setTitle("Delete ${doc.name}?")
            .setMessage("The .sprite document is deleted. Exported PNG assets are kept.")
            .setPositiveButton("Delete") { _, _ ->
                project.deleteSprite(doc.name)
                dirty = false
                finish()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun fmt(v: Float): String = if (v == Math.floor(v.toDouble()).toFloat()) v.toInt().toString() else String.format("%.1f", v)
}
