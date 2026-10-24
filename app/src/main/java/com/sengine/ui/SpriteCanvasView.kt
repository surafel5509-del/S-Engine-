package com.sengine.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.sengine.engine.sprite.Colors
import com.sengine.engine.sprite.PixelOps
import com.sengine.engine.sprite.SpriteDocument
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Sprite Studio drawing surface.
 *
 * Renders the current frame as a nearest-neighbour bitmap with a checkerboard transparency grid, the
 * onion skin of the previous frame, an optional pixel grid and the manual background-removal mask.
 * Touch handling implements every tool; pinch zooms and drags pan.
 */
class SpriteCanvasView(ctx: Context) : View(ctx) {

    enum class Tool(val label: String, val short: String) {
        PENCIL("Pencil", "✏"),
        ERASER("Eraser", "⌫"),
        FILL("Fill", "▣"),
        LINE("Line", "／"),
        RECT("Rectangle", "□"),
        ELLIPSE("Ellipse", "◯"),
        PICKER("Pick colour", "⊙"),
        ISOLATE("Colour isolation", "✂"),
        REMOVE_BG("Remove background (manual)", "▨"),
        PAN("Pan", "✋"),
    }

    var doc: SpriteDocument? = null
        set(value) { field = value; rebuild(); invalidate() }

    var tool = Tool.PENCIL
        set(value) { field = value; invalidate() }
    var color = 0xFF1B1B1F.toInt()
        set(value) { field = value; invalidate() }
    var secondary = 0xFFFFFFFF.toInt()
    var brushSize = 2
        set(value) { field = value.coerceIn(1, 64); invalidate() }
    var hardness = 1f
    /** Colour-isolation and fill tolerance, 0..255. */
    var tolerance = 32
    var zoom = 10f
        set(value) { field = value.coerceIn(1f, 64f); invalidate() }
    var onionSkin = false
        set(value) { field = value; invalidate() }
    var showGrid = false
        set(value) { field = value; invalidate() }
    /** True while the manual removal mask covers pixels that have not been applied yet. */
    var maskActive = false
        set(value) { field = value; if (!value) clearMask(); invalidate() }

    var onColorPicked: ((Int) -> Unit)? = null
    var onEdited: (() -> Unit)? = null
    var onMaskChanged: (() -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null

    private var mask: FloatArray? = null
    private var bitmap: Bitmap? = null
    private var onion: Bitmap? = null
    private var offX = 0f
    private var offY = 0f
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var shapeStart: FloatArray? = null
    private var shapePreview: IntArray? = null
    private var pinchDist = 0f
    private var pinchZoom = 1f
    private var panStart = FloatArray(2)
    private val paint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val overlay = Paint().apply { isAntiAlias = true }
    private val framePaint = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true }

    init {
        isFocusable = true
        setBackgroundColor(0xFF15161A.toInt())
    }

    // ---------------------------------------------------------------- public API

    fun rebuild() {
        val d = doc ?: return
        val px = d.pixels()
        val bmp = bitmap?.takeIf { it.width == d.width && it.height == d.height }
            ?: Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        bmp.setPixels(px, 0, d.width, 0, 0, d.width, d.height)
        onion?.recycle()
        onion = null
        if (onionSkin && d.frameCount() > 1) {
            val idx = d.selected - 1
            if (idx >= 0) {
                val ob = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
                ob.setPixels(d.frames[idx].pixels, 0, d.width, 0, 0, d.width, d.height)
                onion = ob
            }
        }
        updateMaskBuffer()
        invalidate()
    }

    fun mask(): FloatArray? = mask

    fun clearMask() {
        mask?.let { java.util.Arrays.fill(it, 0f) }
        invalidate()
    }

    /** Applies the painted removal mask to the current frame (explicit user action). */
    fun applyRemovalMask(): Int {
        val d = doc ?: return 0
        val m = mask ?: return 0
        var painted = 0
        for (v in m) if (v > 0.001f) painted++
        if (painted == 0) return 0
        d.pushUndo()
        PixelOps.applyRemovalMask(d.pixels(), d.width, d.height, m)
        clearMask()
        maskActive = false
        rebuild()
        onEdited?.invoke()
        return painted
    }

    /** Fits the sprite into the view. */
    fun fit() {
        val d = doc ?: return
        if (width == 0 || height == 0) return
        zoom = min((width - dp(24)) / d.width.toFloat(), (height - dp(24)) / d.height.toFloat()).coerceIn(1f, 64f)
        center()
        invalidate()
    }

    fun center() {
        val d = doc ?: return
        offX = (width - d.width * zoom) * 0.5f
        offY = (height - d.height * zoom) * 0.5f
        invalidate()
    }

    fun zoomBy(factor: Float) {
        val old = zoom
        zoom = (zoom * factor).coerceIn(1f, 64f)
        // keep the view centre anchored
        val k = zoom / old
        offX = width * 0.5f - (width * 0.5f - offX) * k
        offY = height * 0.5f - (height * 0.5f - offY) * k
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val d = doc ?: return
        val sw = d.width * zoom
        val sh = d.height * zoom
        drawCheckerboard(canvas, offX, offY, sw, sh)

        onion?.let {
            paint.alpha = 90
            canvas.drawBitmap(it, Rect(0, 0, d.width, d.height), Rect(offX.toInt(), offY.toInt(), (offX + sw).toInt(), (offY + sh).toInt()), paint)
            paint.alpha = 255
        }
        bitmap?.let { canvas.drawBitmap(it, Rect(0, 0, d.width, d.height), Rect(offX.toInt(), offY.toInt(), (offX + sw).toInt(), (offY + sh).toInt()), paint) }

        // live shape preview
        shapePreview?.let { preview ->
            val bmp = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(preview, 0, d.width, 0, 0, d.width, d.height)
            paint.alpha = 190
            canvas.drawBitmap(bmp, Rect(0, 0, d.width, d.height), Rect(offX.toInt(), offY.toInt(), (offX + sw).toInt(), (offY + sh).toInt()), paint)
            paint.alpha = 255
            bmp.recycle()
        }

        // manual removal mask overlay
        mask?.takeIf { maskActive }?.let { m ->
            overlay.color = 0xFFFF3B30.toInt()
            overlay.alpha = 110
            val size = max(1f, zoom)
            for (y in 0 until d.height) for (x in 0 until d.width) {
                if (m[y * d.width + x] > 0.01f) {
                    canvas.drawRect(offX + x * zoom, offY + y * zoom, offX + x * zoom + size, offY + y * zoom + size, overlay)
                }
            }
            overlay.alpha = 255
        }

        if (showGrid && zoom >= 6f) {
            framePaint.color = 0x33FFFFFF
            framePaint.strokeWidth = 1f
            for (x in 0..d.width) {
                val px = offX + x * zoom
                canvas.drawLine(px, offY, px, offY + sh, framePaint)
            }
            for (y in 0..d.height) {
                val py = offY + y * zoom
                canvas.drawLine(offX, py, offX + sw, py, framePaint)
            }
        }
        framePaint.color = 0xFF4C8DFF.toInt()
        framePaint.strokeWidth = dp(2).toFloat()
        canvas.drawRect(offX, offY, offX + sw, offY + sh, framePaint)

        // brush cursor
        if (dragging && tool == Tool.PENCIL || dragging && tool == Tool.ERASER || dragging && tool == Tool.REMOVE_BG) {
            val r = brushSize * zoom * 0.5f
            overlay.color = 0x99FFFFFF.toInt()
            overlay.style = Paint.Style.STROKE
            overlay.strokeWidth = dp(1).toFloat()
            canvas.drawCircle(lastX, lastY, max(r, 2f), overlay)
            overlay.style = Paint.Style.FILL
        }
    }

    private fun drawCheckerboard(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val cell = max(dp(6).toFloat(), zoom)
        val cols = (w / cell).toInt() + 1
        val rows = (h / cell).toInt() + 1
        val a = Paint().apply { color = 0xFF2A2C31.toInt() }
        val b = Paint().apply { color = 0xFF1F2125.toInt() }
        canvas.save()
        canvas.clipRect(x, y, x + w, y + h)
        for (r in 0 until rows) for (c in 0 until cols) {
            canvas.drawRect(x + c * cell, y + r * cell, x + (c + 1) * cell, y + (r + 1) * cell, if ((r + c) % 2 == 0) a else b)
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val d = doc ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount > 1) return true
                lastX = event.x
                lastY = event.y
                pinchDist = 0f
                pinchZoom = zoom
                if (tool == Tool.PAN) {
                    dragging = true
                    panStart = floatArrayOf(event.x, event.y, offX, offY)
                    return true
                }
                val p = toPixel(event.x, event.y)
                if (p[0] < 0 || p[1] < 0 || p[0] >= d.width || p[1] >= d.height) {
                    dragging = true
                    panStart = floatArrayOf(event.x, event.y, offX, offY)
                    return true
                }
                when (tool) {
                    Tool.PENCIL, Tool.ERASER -> {
                        d.beginStroke()
                        stroke(d, p[0].toFloat(), p[1].toFloat(), erase = tool == Tool.ERASER)
                    }
                    Tool.REMOVE_BG -> {
                        maskActive = true
                        paintMask(d, p[0].toFloat(), p[1].toFloat())
                    }
                    Tool.FILL -> {
                        d.pushUndo()
                        val before = d.pixels().copyOf()
                        val changed = PixelOps.fill(d.pixels(), d.width, d.height, p[0], p[1], color, tolerance, true)
                        if (changed == 0) d.undo() else { onEdited?.invoke(); onStatus?.invoke("$changed px filled") }
                        rebuild()
                    }
                    Tool.ISOLATE -> {
                        d.pushUndo()
                        val picked = d.pixelAt(p[0], p[1])
                        var changed = 0
                        val px = d.pixels()
                        for (i in px.indices) if (Colors.similar(px[i], picked, tolerance)) { px[i] = 0; changed++ }
                        onStatus?.invoke("Removed ${Colors.toHex(picked)} ($changed px)")
                        // keep the tool's colour swatch in sync with what was cut
                        onColorPicked?.invoke(picked)
                        rebuild(); onEdited?.invoke()
                    }
                    Tool.PICKER -> {
                        val c = d.pixelAt(p[0], p[1])
                        color = c
                        onColorPicked?.invoke(c)
                        onStatus?.invoke("Picked ${Colors.toHex(c)}")
                    }
                    Tool.LINE, Tool.RECT, Tool.ELLIPSE -> {
                        shapeStart = floatArrayOf(p[0].toFloat(), p[1].toFloat())
                        d.beginStroke()
                    }
                    Tool.PAN -> Unit
                }
                dragging = true
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    pinchDist = spread(event)
                    pinchZoom = zoom
                    panStart = floatArrayOf(midX(event), midY(event), offX, offY)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val s = spread(event)
                    if (pinchDist > 0f) {
                        val target = (pinchZoom * (s / pinchDist)).coerceIn(1f, 64f)
                        val k = target / zoom
                        val mx = midX(event)
                        val my = midY(event)
                        offX = mx - (mx - offX) * k
                        offY = my - (my - offY) * k
                        zoom = target
                        invalidate()
                    }
                    return true
                }
                val p = toPixel(event.x, event.y)
                if (tool == Tool.PAN || shapeStart == null && (tool == Tool.PICKER || tool == Tool.ISOLATE) && !dragging) {
                    offX += event.x - lastX
                    offY += event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                    return true
                }
                when (tool) {
                    Tool.PENCIL, Tool.ERASER -> if (dragging) stroke(d, p[0].toFloat(), p[1].toFloat(), erase = tool == Tool.ERASER)
                    Tool.REMOVE_BG -> if (dragging) paintMask(d, p[0].toFloat(), p[1].toFloat())
                    Tool.LINE, Tool.RECT, Tool.ELLIPSE -> if (shapeStart != null) {
                        shapePreview = d.pixels().copyOf()
                        drawShape(shapePreview!!, d, shapeStart!![0].toInt(), shapeStart!![1].toInt(), p[0], p[1])
                        invalidate()
                    }
                    else -> Unit
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.pointerCount <= 1) {
                    if (shapeStart != null) {
                        val p = toPixel(event.x, event.y)
                        drawShape(d.pixels(), d, shapeStart!![0].toInt(), shapeStart!![1].toInt(), p[0], p[1])
                        d.endStroke()
                        shapeStart = null
                        shapePreview = null
                        onEdited?.invoke()
                        rebuild()
                    } else if (dragging && (tool == Tool.PENCIL || tool == Tool.ERASER)) {
                        d.endStroke()
                        onEdited?.invoke()
                        rebuild()
                    } else if (dragging && tool == Tool.REMOVE_BG) {
                        onMaskChanged?.invoke()
                        rebuild()
                    }
                }
                dragging = false
                return true
            }
        }
        return true
    }

    private fun spread(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat()
    }

    private fun midX(e: MotionEvent) = (e.getX(0) + e.getX(1)) * 0.5f
    private fun midY(e: MotionEvent) = (e.getY(0) + e.getY(1)) * 0.5f

    /** Screen to document pixel. */
    fun toPixel(x: Float, y: Float): IntArray =
        intArrayOf(((x - offX) / zoom).toInt(), ((y - offY) / zoom).toInt())

    private fun stroke(d: SpriteDocument, x: Float, y: Float, erase: Boolean) {
        val px = d.pixels()
        val radius = brushSize * 0.5f
        PixelOps.brush(px, d.width, d.height, x + 0.5f, y + 0.5f, radius, color, if (erase) 1f else hardness, erase)
        // Connect the gap between two touch samples so fast strokes stay continuous.
        if (!erase) PixelOps.line(px, d.width, d.height, lastPixelX + 0.5f, lastPixelY + 0.5f, x + 0.5f, y + 0.5f, radius, color, hardness)
        lastPixelX = x
        lastPixelY = y
        rebuild()
    }

    private var lastPixelX = -100f
    private var lastPixelY = -100f

    private fun paintMask(d: SpriteDocument, x: Float, y: Float) {
        val m = mask ?: return
        val radius = brushSize * 0.5f
        val px = IntArray(1)
        val r = max(0.5f, radius)
        for (yy in (y - r).toInt().coerceAtLeast(0)..(y + r).toInt().coerceAtMost(d.height - 1)) {
            for (xx in (x - r).toInt().coerceAtLeast(0)..(x + r).toInt().coerceAtMost(d.width - 1)) {
                val dist = hypot((xx + 0.5f - x).toDouble(), (yy + 0.5f - y).toDouble()).toFloat()
                if (dist > r) continue
                m[yy * d.width + xx] = 1f
            }
        }
        px[0] = 0
        invalidate()
    }

    private fun updateMaskBuffer() {
        val d = doc ?: return
        val need = d.width * d.height
        val m = mask
        if (m == null || m.size != need) mask = FloatArray(need)
    }

    private fun drawShape(px: IntArray, d: SpriteDocument, x0: Int, y0: Int, x1: Int, y1: Int) {
        when (tool) {
            Tool.LINE -> PixelOps.line(px, d.width, d.height, x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), brushSize * 0.5f, color, hardness)
            Tool.RECT -> PixelOps.rect(px, d.width, d.height, x0, y0, x1, y1, color, brushSize > 2)
            Tool.ELLIPSE -> PixelOps.ellipse(px, d.width, d.height, x0, y0, x1, y1, color, brushSize > 2)
            else -> Unit
        }
    }

    /** Called by the activity when the tool changes so the status bar can explain the mode. */
    fun toolHint(t: Tool): String = when (t) {
        Tool.PENCIL -> "Draw pixels. Brush ${brushSize}px."
        Tool.ERASER -> "Erase pixels to transparent."
        Tool.FILL -> "Fill a connected area. Tolerance $tolerance."
        Tool.LINE -> "Drag to draw a straight line."
        Tool.RECT -> "Drag to draw a rectangle."
        Tool.ELLIPSE -> "Drag to draw an ellipse."
        Tool.PICKER -> "Tap to pick a colour from the sprite."
        Tool.ISOLATE -> "Colour isolation: tap a colour to erase every pixel of it (tolerance $tolerance)."
        Tool.REMOVE_BG -> "Paint over the background, then press Apply to cut it out."
        Tool.PAN -> "Drag to pan, pinch to zoom."
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0) fit()
    }

    fun distance(a: Float, b: Float) = abs(a - b)
}
