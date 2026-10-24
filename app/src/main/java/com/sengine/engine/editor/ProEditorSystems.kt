package com.sengine.engine.editor

import com.sengine.engine.textures.SpriteSlice
import org.json.JSONArray
import org.json.JSONObject

// ─── Sprite Editor ────────────────────────────────────────────────────────

/** Professional sprite editor with auto-slicing, manual slicing, pivot editing,
 *  atlas packing, and sprite metadata management. */

enum class SliceMode {
    AutoGrid,      // Uniform grid based on cell size
    AutoDetect,    // Auto-detect sprite boundaries from transparency
    Manual,        // User-defined rectangles
    ByTag,         // Split by color-tagged regions
}

class SpriteEditorData {
    var textureName: String = ""
    var textureWidth: Int = 0
    var textureHeight: Int = 0
    var pixelsPerUnit: Float = 100f
    var sliceMode: SliceMode = SliceMode.AutoGrid
    var gridCellWidth: Int = 32
    var gridCellHeight: Int = 32
    var gridOffsetX: Int = 0
    var gridOffsetY: Int = 0
    var gridPadding: Int = 0
    var slices = mutableListOf<SpriteSlice>()
    var defaultPivotX: Float = 0.5f
    var defaultPivotY: Float = 0.5f

    /** Generate slices from a uniform grid. */
    fun generateGridSlices() {
        slices.clear()
        val cw = gridCellWidth.coerceAtLeast(1)
        val ch = gridCellHeight.coerceAtLeast(1)
        var idx = 0
        var y = gridOffsetY
        while (y + ch <= textureHeight) {
            var x = gridOffsetX
            while (x + cw <= textureWidth) {
                val name = "${textureName.substringBeforeLast('.')}_${idx}"
                slices.add(SpriteSlice(name, x, y, cw, ch, defaultPivotX, defaultPivotY))
                x += cw + gridPadding
                idx++
            }
            y += ch + gridPadding
        }
    }

    /** Auto-detect sprite regions by scanning for non-transparent bounding boxes. */
    fun autoDetectSlices(alphaMap: BooleanArray, width: Int, height: Int, minSize: Int = 4, padding: Int = 1) {
        slices.clear()
        val visited = BooleanArray(width * height)

        fun floodFill(startX: Int, startY: Int): IntArray {
            var minX = startX; var maxX = startX
            var minY = startY; var maxY = startY
            val stack = ArrayDeque<IntArray>()
            stack.addLast(intArrayOf(startX, startY))
            var count = 0
            while (stack.isNotEmpty()) {
                val (px, py) = stack.removeLast()
                if (px < 0 || px >= width || py < 0 || py >= height) continue
                val idx = py * width + px
                if (visited[idx] || !alphaMap[idx]) continue
                visited[idx] = true
                minX = minOf(minX, px); maxX = maxOf(maxX, px)
                minY = minOf(minY, py); maxY = maxOf(maxY, py)
                count++
                stack.addLast(intArrayOf(px + 1, py)); stack.addLast(intArrayOf(px - 1, py))
                stack.addLast(intArrayOf(px, py + 1)); stack.addLast(intArrayOf(px, py - 1))
            }
            return intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1, count)
        }

        for (y in 0 until height step (minSize + padding)) {
            for (x in 0 until width step (minSize + padding)) {
                val idx = y * width + x
                if (visited[idx] || !alphaMap[idx]) continue
                val rect = floodFill(x, y)
                if (rect[2] >= minSize && rect[3] >= minSize) {
                    val name = "${textureName.substringBeforeLast('.')}_${slices.size}"
                    slices.add(SpriteSlice(name, rect[0], rect[1], rect[2], rect[3], defaultPivotX, defaultPivotY))
                }
            }
        }
    }

    /** Set the pivot for a specific slice by index. */
    fun setPivot(index: Int, px: Float, py: Float) {
        if (index in slices.indices) {
            slices[index].pivotX = px.coerceIn(0f, 1f)
            slices[index].pivotY = py.coerceIn(0f, 1f)
        }
    }

    /** Set borders (for 9-slice) on a specific slice. */
    fun setBorder(index: Int, l: Int, r: Int, t: Int, b: Int) {
        if (index in slices.indices) {
            slices[index].borderL = l; slices[index].borderR = r
            slices[index].borderT = t; slices[index].borderB = b
        }
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("texture", textureName)
            .put("width", textureWidth).put("height", textureHeight)
            .put("ppu", pixelsPerUnit.toDouble())
            .put("sliceMode", sliceMode.ordinal)
            .put("cellW", gridCellWidth).put("cellH", gridCellHeight)
            .put("offsetX", gridOffsetX).put("offsetY", gridOffsetY)
            .put("padding", gridPadding)
            .put("defaultPivotX", defaultPivotX.toDouble())
            .put("defaultPivotY", defaultPivotY.toDouble())
        o.put("slices", JSONArray().also { arr -> slices.forEach { arr.put(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SpriteEditorData {
            val d = SpriteEditorData()
            d.textureName = o.optString("texture", "")
            d.textureWidth = o.optInt("width", 0)
            d.textureHeight = o.optInt("height", 0)
            d.pixelsPerUnit = o.optDouble("ppu", 100.0).toFloat()
            d.sliceMode = SliceMode.entries.getOrElse(o.optInt("sliceMode", 0)) { SliceMode.AutoGrid }
            d.gridCellWidth = o.optInt("cellW", 32)
            d.gridCellHeight = o.optInt("cellH", 32)
            d.gridOffsetX = o.optInt("offsetX", 0)
            d.gridOffsetY = o.optInt("offsetY", 0)
            d.gridPadding = o.optInt("padding", 0)
            d.defaultPivotX = o.optDouble("defaultPivotX", 0.5).toFloat()
            d.defaultPivotY = o.optDouble("defaultPivotY", 0.5).toFloat()
            val arr = o.optJSONArray("slices") ?: JSONArray()
            for (i in 0 until arr.length()) d.slices.add(SpriteSlice.fromJson(arr.getJSONObject(i)))
            return d
        }
    }
}

// ─── Texture Importer Editor ──────────────────────────────────────────────

/** Texture import configuration that persists with the project. */
class TextureImportProfile {
    var name: String = ""
    var textureType = 0     // 0=Sprite, 1=UI, 2=Normal, 3=Atlas
    var spriteMode = 0      // 0=Single, 1=Multiple
    var pixelsPerUnit = 100f
    var filterMode = 1      // 0=Point, 1=Bilinear, 2=Trilinear
    var wrapMode = 1        // 0=Repeat, 1=Clamp
    var maxSize = 2048
    var compression = 0     // 0=None, 1=Normal, 2=High
    var generateMipMaps = false
    var alphaIsTransparency = true
    var sRGB = true
    var spriteEditor: SpriteEditorData? = null

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("name", name).put("type", textureType)
            .put("spriteMode", spriteMode).put("ppu", pixelsPerUnit.toDouble())
            .put("filter", filterMode).put("wrap", wrapMode)
            .put("maxSize", maxSize).put("compression", compression)
            .put("mipmaps", generateMipMaps).put("alpha", alphaIsTransparency)
            .put("srgb", sRGB)
        spriteEditor?.let { o.put("spriteEditor", it.toJson()) }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): TextureImportProfile {
            val p = TextureImportProfile()
            p.name = o.optString("name", "")
            p.textureType = o.optInt("type", 0)
            p.spriteMode = o.optInt("spriteMode", 0)
            p.pixelsPerUnit = o.optDouble("ppu", 100.0).toFloat()
            p.filterMode = o.optInt("filter", 1)
            p.wrapMode = o.optInt("wrap", 1)
            p.maxSize = o.optInt("maxSize", 2048)
            p.compression = o.optInt("compression", 0)
            p.generateMipMaps = o.optBoolean("mipmaps", false)
            p.alphaIsTransparency = o.optBoolean("alpha", true)
            p.sRGB = o.optBoolean("srgb", true)
            p.spriteEditor = o.optJSONObject("spriteEditor")?.let { SpriteEditorData.fromJson(it) }
            return p
        }
    }
}

// ─── UI Editor ────────────────────────────────────────────────────────────

/** Visual UI editor state — tracks the editing canvas, guides, snapping. */
class UIEditorState {
    var canvasWidth: Int = 1080
    var canvasHeight: Int = 1920
    var showGuides: Boolean = true
    var snapToGrid: Boolean = true
    var gridSize: Int = 10
    var snapDistance: Float = 5f
    var showAnchors: Boolean = true
    var showPivot: Boolean = true
    var showBounds: Boolean = true
    var zoom: Float = 1f
    var offsetX: Float = 0f
    var offsetY: Float = 0f
    var orientation: Int = 0 // 0=Portrait, 1=Landscape
    var safeAreaInsetL: Float = 0f
    var safeAreaInsetR: Float = 0f
    var safeAreaInsetT: Float = 0f
    var safeAreaInsetB: Float = 0f

    /** Convert canvas coordinates to screen coordinates. */
    fun canvasToScreen(cx: Float, cy: Float, screenW: Int, screenH: Int): FloatArray {
        val scaleX = screenW.toFloat() / canvasWidth * zoom
        val scaleY = screenH.toFloat() / canvasHeight * zoom
        return floatArrayOf(
            cx * scaleX + offsetX,
            cy * scaleY + offsetY
        )
    }

    /** Convert screen coordinates to canvas coordinates. */
    fun screenToCanvas(sx: Float, sy: Float, screenW: Int, screenH: Int): FloatArray {
        val scaleX = screenW.toFloat() / canvasWidth * zoom
        val scaleY = screenH.toFloat() / canvasHeight * zoom
        return floatArrayOf(
            (sx - offsetX) / scaleX,
            (sy - offsetY) / scaleY
        )
    }
}
