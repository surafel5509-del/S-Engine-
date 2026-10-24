package com.sengine.engine.sprite

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import kotlin.math.max

/**
 * One editable pixel-art sprite: a size, a frame reel, a frame rate, and an undo history.
 *
 * The document is the model behind the Sprite Studio screen. It owns no Android types, so it is
 * exercised by the desktop JVM test harness, and it serialises to `.sprite` JSON next to the exported
 * PNGs so a sprite can be reopened and edited later (the PNGs are what the game runtime uses).
 */
class SpriteDocument(
    var name: String = "Sprite",
    width: Int = 32,
    height: Int = 32,
) {
    var width = clampSize(width)
        private set
    var height = clampSize(height)
        private set

    val frames = ArrayList<SpriteFrame>()
    /** Playback rate used when the sprite is exported as an animation clip. */
    var fps = 8f
    /** Index of the frame currently being edited. */
    var selected = 0
        private set
    var loop = true
    /** Maximum undo bytes kept; snapshots beyond this are dropped oldest first. */
    var undoBudgetBytes = 24 * 1024 * 1024

    private val undoStack = ArrayList<Snapshot>()
    private val redoStack = ArrayList<Snapshot>()
    private var undoBytes = 0L
    private var strokeOpen = false

    class SpriteFrame(var pixels: IntArray, var label: String = "Frame")

    private class Snapshot(val index: Int, val pixels: IntArray, val label: String)

    init {
        frames.add(SpriteFrame(IntArray(this.width * this.height)))
    }

    // ------------------------------------------------------------------ lifecycle

    fun frameCount(): Int = frames.size
    fun current(): SpriteFrame = frames[selected.coerceIn(0, frames.size - 1)]
    fun pixels(): IntArray = current().pixels
    fun pixelAt(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) 0 else pixels()[y * width + x]

    fun select(index: Int) {
        if (frames.isEmpty()) return
        selected = index.coerceIn(0, frames.size - 1)
    }

    fun addFrame(copyCurrent: Boolean = false): Int {
        val src = if (copyCurrent) pixels().copyOf() else IntArray(width * height)
        frames.add(SpriteFrame(src, "Frame ${frames.size + 1}"))
        selected = frames.size - 1
        pushUndo()
        return selected
    }

    fun duplicateFrame(index: Int = selected): Int {
        if (index !in frames.indices) return selected
        frames.add(index + 1, SpriteFrame(frames[index].pixels.copyOf(), frames[index].label + " copy"))
        selected = index + 1
        pushUndo()
        return selected
    }

    fun deleteFrame(index: Int = selected): Boolean {
        if (frames.size <= 1) return false
        if (index !in frames.indices) return false
        frames.removeAt(index)
        selected = selected.coerceAtMost(frames.size - 1)
        pushUndo()
        return true
    }

    fun moveFrame(from: Int, to: Int) {
        if (from !in frames.indices || to !in frames.indices || from == to) return
        val f = frames.removeAt(from)
        frames.add(to, f)
        selected = to
        pushUndo()
    }

    fun clearFrame() {
        pushUndo()
        java.util.Arrays.fill(pixels(), 0)
    }

    /** Resizes the whole document: every frame is resampled (nearest) and the canvas re-anchored. */
    fun resize(newWidth: Int, newHeight: Int, resample: Boolean = false) {
        val nw = clampSize(newWidth)
        val nh = clampSize(newHeight)
        if (nw == width && nh == height) return
        pushUndo()
        for (f in frames) {
            f.pixels = if (resample) PixelOps.resize(f.pixels, width, height, nw, nh)
            else PixelOps.changeCanvas(f.pixels, width, height, nw, nh)
        }
        width = nw
        height = nh
    }

    /**
     * Sets the canvas size without touching pixels. Only valid when every frame already has exactly
     * `newWidth * newHeight` pixels (used by rotation, which re-lays-out the buffers itself).
     */
    fun adoptSize(newWidth: Int, newHeight: Int) {
        val nw = clampSize(newWidth)
        val nh = clampSize(newHeight)
        if (frames.any { it.pixels.size != nw * nh }) return
        width = nw
        height = nh
    }

    // ------------------------------------------------------------------ undo

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
    fun undoCount(): Int = undoStack.size
    fun redoCount(): Int = redoStack.size

    /** Records the current frame so the next edit can be undone. Call before a stroke starts. */
    fun pushUndo() {
        val snap = Snapshot(selected, pixels().copyOf(), current().label)
        undoStack.add(snap)
        undoBytes += snap.pixels.size * 4L
        while (undoBytes > undoBudgetBytes && undoStack.size > 1) {
            val old = undoStack.removeAt(0)
            undoBytes -= old.pixels.size * 4L
        }
        redoStack.clear()
    }

    /** Groups a multi-touch (paint) stroke into a single undo entry. */
    fun beginStroke() {
        if (strokeOpen) return
        pushUndo()
        strokeOpen = true
    }

    fun endStroke() {
        strokeOpen = false
    }

    fun undo(): Boolean {
        val snap = undoStack.removeLastOrNull() ?: return false
        redoStack.add(Snapshot(selected, pixels().copyOf(), current().label))
        restore(snap)
        return true
    }

    fun redo(): Boolean {
        val snap = redoStack.removeLastOrNull() ?: return false
        undoStack.add(Snapshot(selected, pixels().copyOf(), current().label))
        restore(snap)
        return true
    }

    private fun restore(snap: Snapshot) {
        select(snap.index)
        frames[selected].pixels = snap.pixels.copyOf()
        frames[selected].label = snap.label
    }

    fun clearHistory() {
        undoStack.clear(); redoStack.clear(); undoBytes = 0
    }

    // ------------------------------------------------------------------ composition

    /** Flattened pixels of [index] (there is one frame per "layer" today, kept for future layers). */
    fun composite(index: Int = selected): IntArray =
        frames[index.coerceIn(0, frames.size - 1)].pixels.copyOf()

    /** Longest side, handy for the editor's zoom-to-fit. */
    val longestSide: Int get() = max(width, height)

    // ------------------------------------------------------------------ import / export data

    fun copyFrom(other: SpriteDocument) {
        name = other.name
        width = other.width
        height = other.height
        fps = other.fps
        loop = other.loop
        frames.clear()
        for (f in other.frames) frames.add(SpriteFrame(f.pixels.copyOf(), f.label))
        selected = other.selected.coerceIn(0, frames.size - 1)
        clearHistory()
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("version", 1)
        o.put("name", name)
        o.put("width", width)
        o.put("height", height)
        o.put("fps", fps.toDouble())
        o.put("loop", loop)
        o.put("selected", selected)
        val arr = JSONArray()
        for (f in frames) {
            val fo = JSONObject()
            fo.put("label", f.label)
            fo.put("data", Base64.getEncoder().encodeToString(encodePixels(f.pixels)))
            arr.put(fo)
        }
        o.put("frames", arr)
        return o
    }

    fun toJsonText(): String = toJson().toString(1)

    companion object {
        const val MIN_SIZE = 4
        const val MAX_SIZE = 1024

        private fun clampSize(v: Int) = v.coerceIn(MIN_SIZE, MAX_SIZE)

        /** RGBA bytes, row order, from ARGB ints. */
        fun encodePixels(px: IntArray): ByteArray {
            val out = ByteArray(px.size * 4)
            var k = 0
            for (c in px) {
                out[k++] = Colors.r(c).toByte()
                out[k++] = Colors.g(c).toByte()
                out[k++] = Colors.b(c).toByte()
                out[k++] = Colors.a(c).toByte()
            }
            return out
        }

        fun decodePixels(bytes: ByteArray, expected: Int): IntArray {
            val out = IntArray(expected)
            var k = 0
            var i = 0
            while (i + 3 < bytes.size && k < expected) {
                out[k++] = Colors.argb(bytes[i + 3].toInt() and 0xFF, bytes[i].toInt() and 0xFF,
                    bytes[i + 1].toInt() and 0xFF, bytes[i + 2].toInt() and 0xFF)
                i += 4
            }
            return out
        }

        fun fromJson(o: JSONObject): SpriteDocument {
            val w = clampSize(o.optInt("width", 32))
            val h = clampSize(o.optInt("height", 32))
            val doc = SpriteDocument(o.optString("name", "Sprite"), w, h)
            doc.fps = o.optDouble("fps", 8.0).toFloat()
            doc.loop = o.optBoolean("loop", true)
            val arr = o.optJSONArray("frames")
            if (arr != null && arr.length() > 0) {
                doc.frames.clear()
                for (i in 0 until arr.length()) {
                    val fo = arr.optJSONObject(i) ?: continue
                    val pixels = try {
                        decodePixels(Base64.getDecoder().decode(fo.optString("data", "")), doc.width * doc.height)
                    } catch (_: Exception) {
                        IntArray(doc.width * doc.height)
                    }
                    doc.frames.add(SpriteFrame(pixels, fo.optString("label", "Frame ${i + 1}")))
                }
                if (doc.frames.isEmpty()) doc.frames.add(SpriteFrame(IntArray(doc.width * doc.height)))
            }
            doc.selected = o.optInt("selected", 0).coerceIn(0, doc.frames.size - 1)
            return doc
        }

        fun fromJsonText(text: String): SpriteDocument = fromJson(JSONObject(text))
    }
}
