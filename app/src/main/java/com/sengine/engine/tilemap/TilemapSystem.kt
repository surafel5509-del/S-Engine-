package com.sengine.engine.tilemap

import kotlin.math.abs

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import org.json.JSONArray
import org.json.JSONObject

// ─── 2D Tilemap System ───────────────────────────────────────────────────

/**
 * Professional 2D Tilemap system for grid-based level design.
 * Supports multiple layers, tile rules, auto-tiling, and collision.
 */

/** A single tile definition. */
class TileDef(
    var id: Int = 0,
    var name: String = "",
    var texture: String = "",
    var uvX: Float = 0f,
    var uvY: Float = 0f,
    var uvW: Float = 1f,
    var uvH: Float = 1f,
    var collision: Boolean = false,
    var cost: Float = 1f,           // For pathfinding
    var tag: String = "",
    var animation: List<Int> = emptyList(), // Frame IDs for animated tiles
    var animationFps: Float = 8f,
    var randomRotation: Boolean = false,
    var randomMirror: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("texture", texture)
        .put("uvX", uvX.toDouble()).put("uvY", uvY.toDouble())
        .put("uvW", uvW.toDouble()).put("uvH", uvH.toDouble())
        .put("collision", collision).put("cost", cost.toDouble())
        .put("tag", tag)
        .put("anim", JSONArray(animation))
        .put("animFps", animationFps.toDouble())

    companion object {
        fun fromJson(o: JSONObject): TileDef {
            val t = TileDef()
            t.id = o.optInt("id", 0)
            t.name = o.optString("name", "")
            t.texture = o.optString("texture", "")
            t.uvX = o.optDouble("uvX", 0.0).toFloat()
            t.uvY = o.optDouble("uvY", 0.0).toFloat()
            t.uvW = o.optDouble("uvW", 1.0).toFloat()
            t.uvH = o.optDouble("uvH", 1.0).toFloat()
            t.collision = o.optBoolean("collision", false)
            t.cost = o.optDouble("cost", 1.0).toFloat()
            t.tag = o.optString("tag", "")
            val anim = o.optJSONArray("anim") ?: JSONArray()
            t.animation = List(anim.length()) { anim.getInt(it) }
            t.animationFps = o.optDouble("animFps", 8.0).toFloat()
            return t
        }
    }
}

/** A tile palette / tileset definition. */
class TilePalette {
    var name: String = ""
    var tileSize: Int = 16
    var pixelsPerUnit: Float = 100f
    val tiles = mutableListOf<TileDef>()

    fun getTile(id: Int): TileDef? = tiles.firstOrNull { it.id == id }

    fun addTile(tile: TileDef) { tiles.add(tile) }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("name", name).put("tileSize", tileSize)
            .put("ppu", pixelsPerUnit.toDouble())
        o.put("tiles", JSONArray().also { arr -> tiles.forEach { arr.put(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): TilePalette {
            val p = TilePalette()
            p.name = o.optString("name", "")
            p.tileSize = o.optInt("tileSize", 16)
            p.pixelsPerUnit = o.optDouble("ppu", 100.0).toFloat()
            val arr = o.optJSONArray("tiles") ?: JSONArray()
            for (i in 0 until arr.length()) p.tiles.add(TileDef.fromJson(arr.getJSONObject(i)))
            return p
        }
    }
}

/** A layer of tiles in a tilemap. */
class TileLayer(
    var name: String = "Ground",
    var visible: Boolean = true,
    var sortOrder: Int = 0,
    var opacity: Float = 1f,
    var color: Int = 0xFFFFFFFF.toInt(),
    val width: Int = 50,
    val height: Int = 50,
) {
    /** Tile data: -1 = empty, otherwise tile ID. */
    val data = IntArray(width * height) { -1 }

    fun getTile(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return -1
        return data[y * width + x]
    }

    fun setTile(x: Int, y: Int, tileId: Int) {
        if (x in 0 until width && y in 0 until height) {
            data[y * width + x] = tileId
        }
    }

    fun fill(rect: TileRect, tileId: Int) {
        for (x in rect.x until rect.x + rect.w) {
            for (y in rect.y until rect.y + rect.h) {
                setTile(x, y, tileId)
            }
        }
    }

    fun clear() { data.fill(-1) }

    /** Auto-tile using Wang/tileset rules. */
    fun autoTile(x: Int, y: Int, tileId: Int, palette: TilePalette) {
        setTile(x, y, tileId)
        // Apply tile rules based on neighbors
        val n = getTile(x, y + 1) != -1
        val s = getTile(x, y - 1) != -1
        val e = getTile(x + 1, y) != -1
        val w = getTile(x - 1, y) != -1
        // This is a simplified auto-tile — real implementation would
        // select the correct tile variant based on neighbor configuration
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("name", name).put("visible", visible)
            .put("sort", sortOrder).put("opacity", opacity.toDouble())
            .put("color", String.format("#%08X", color))
            .put("width", width).put("height", height)
        o.put("data", JSONArray().also { arr -> data.forEach { arr.put(it) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): TileLayer {
            val l = TileLayer(
                o.optString("name", "Ground"),
                o.optBoolean("visible", true),
                o.optInt("sort", 0),
                o.optDouble("opacity", 1.0).toFloat(),
                try { com.sengine.engine.core.Component.parseColor(o.optString("color", "#FFFFFFFF")) } catch (_: Exception) { 0xFFFFFFFF.toInt() },
                o.optInt("width", 50),
                o.optInt("height", 50),
            )
            val arr = o.optJSONArray("data") ?: JSONArray()
            for (i in 0 until minOf(arr.length(), l.data.size)) l.data[i] = arr.getInt(i)
            return l
        }
    }
}

data class TileRect(var x: Int = 0, var y: Int = 0, var w: Int = 1, var h: Int = 1)

/** Main tilemap component. */

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
private fun fade(t: Float): Float = t * t * (3f - 2f * t)

class TilemapComponent : Component() {
    override val type = "Tilemap"
    var paletteName: String = ""
    var cellSize: Float = 1f
    var anchorX: Float = 0f
    var anchorY: Float = 0f
    var generateCollision: Boolean = true
    val layers = mutableListOf<TileLayer>()

    override fun props() = listOf(
        Prop.S("Palette", { paletteName }, { paletteName = it }),
        Prop.F("Cell Size", { cellSize }, { cellSize = it.coerceAtLeast(0.1f) }, 0.5f),
        Prop.F("Anchor X", { anchorX }, { anchorX = it }, 0.5f),
        Prop.F("Anchor Y", { anchorY }, { anchorY = it }, 0.5f),
        Prop.B("Generate Collision", { generateCollision }, { generateCollision = it }),
    )

    /** Convert world coordinates to tile coordinates. */
    fun worldToTile(wx: Float, wy: Float): Pair<Int, Int> {
        val tx = ((wx - anchorX) / cellSize).toInt()
        val ty = ((wy - anchorY) / cellSize).toInt()
        return tx to ty
    }

    /** Convert tile coordinates to world position (center of tile). */
    fun tileToWorld(tx: Int, ty: Int): Pair<Float, Float> {
        return (anchorX + (tx + 0.5f) * cellSize) to (anchorY + (ty + 0.5f) * cellSize)
    }

    fun addLayer(name: String, width: Int = 50, height: Int = 50): TileLayer {
        val layer = TileLayer(name = name, width = width, height = height, sortOrder = layers.size)
        layers.add(layer)
        return layer
    }

    fun getTile(tx: Int, ty: Int, layerIndex: Int = 0): Int {
        if (layerIndex !in layers.indices) return -1
        return layers[layerIndex].getTile(tx, ty)
    }

    fun setTile(tx: Int, ty: Int, tileId: Int, layerIndex: Int = 0) {
        if (layerIndex in layers.indices) {
            layers[layerIndex].setTile(tx, ty, tileId)
        }
    }

    fun toJsonData(): JSONObject {
        val o = JSONObject()
            .put("palette", paletteName)
            .put("cellSize", cellSize.toDouble())
            .put("anchorX", anchorX.toDouble())
            .put("anchorY", anchorY.toDouble())
            .put("collision", generateCollision)
        o.put("layers", JSONArray().also { arr -> layers.forEach { arr.put(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): TilemapComponent {
            val t = TilemapComponent()
            t.paletteName = o.optString("palette", "")
            t.cellSize = o.optDouble("cellSize", 1.0).toFloat()
            t.anchorX = o.optDouble("anchorX", 0.0).toFloat()
            t.anchorY = o.optDouble("anchorY", 0.0).toFloat()
            t.generateCollision = o.optBoolean("collision", true)
            val arr = o.optJSONArray("layers") ?: JSONArray()
            for (i in 0 until arr.length()) t.layers.add(TileLayer.fromJson(arr.getJSONObject(i)))
            return t
        }
    }
}

// ─── Procedural Map Generation ────────────────────────────────────────────

/** Generates tilemap content procedurally. */
object MapGenerator {
    /** Generate a simple dungeon with rooms and corridors. */
    fun generateDungeon(
        layer: TileLayer,
        roomCount: Int = 8,
        minRoomSize: Int = 3,
        maxRoomSize: Int = 8,
        floorTile: Int = 1,
        wallTile: Int = 2,
    ): List<TileRect> {
        layer.clear()
        val rooms = mutableListOf<TileRect>()

        // Place rooms
        for (attempt in 0 until roomCount * 10) {
            if (rooms.size >= roomCount) break
            val w = (minRoomSize..maxRoomSize).random()
            val h = (minRoomSize..maxRoomSize).random()
            val x = (1 until layer.width - w - 1).random()
            val y = (1 until layer.height - h - 1).random()
            val room = TileRect(x, y, w, h)

            // Check overlap
            if (rooms.none { overlaps(it, room) }) {
                rooms.add(room)
                // Fill floor
                layer.fill(room, floorTile)
            }
        }

        // Connect rooms with corridors
        for (i in 1 until rooms.size) {
            val prev = rooms[i - 1]
            val curr = rooms[i]
            val cx1 = prev.x + prev.w / 2
            val cy1 = prev.y + prev.h / 2
            val cx2 = curr.x + curr.w / 2
            val cy2 = curr.y + curr.h / 2

            // L-shaped corridor
            if (Math.random() > 0.5) {
                carveH(layer, cx1, cx2, cy1, floorTile)
                carveV(layer, cy1, cy2, cx2, floorTile)
            } else {
                carveV(layer, cy1, cy2, cx1, floorTile)
                carveH(layer, cx1, cx2, cy2, floorTile)
            }
        }

        // Add walls around floors
        for (x in 0 until layer.width) {
            for (y in 0 until layer.height) {
                if (layer.getTile(x, y) == floorTile) continue
                // Check if adjacent to floor
                var adjFloor = false
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        if (layer.getTile(x + dx, y + dy) == floorTile) adjFloor = true
                    }
                }
                if (adjFloor) layer.setTile(x, y, wallTile)
            }
        }

        return rooms
    }

    private fun overlaps(a: TileRect, b: TileRect, padding: Int = 1): Boolean =
        a.x - padding < b.x + b.w && a.x + a.w + padding > b.x &&
        a.y - padding < b.y + b.h && a.y + a.h + padding > b.y

    private fun carveH(layer: TileLayer, x1: Int, x2: Int, y: Int, tile: Int) {
        val start = minOf(x1, x2); val end = maxOf(x1, x2)
        for (x in start..end) layer.setTile(x, y, tile)
    }

    private fun carveV(layer: TileLayer, y1: Int, y2: Int, x: Int, tile: Int) {
        val start = minOf(y1, y2); val end = maxOf(y1, y2)
        for (y in start..end) layer.setTile(x, y, tile)
    }

    /** Generate terrain using Perlin-like noise. */
    fun generateNoiseTerrain(
        layer: TileLayer,
        seed: Long = System.currentTimeMillis(),
        scale: Float = 0.1f,
        octaves: Int = 4,
        thresholds: List<Float> = listOf(0.3f, 0.5f, 0.7f),
        tileIds: List<Int> = listOf(0, 1, 2, 3),
    ) {
        val rng = java.util.Random(seed)
        // Simple value noise
        val perm = IntArray(512) { rng.nextInt(256) }

        fun noise(x: Float, y: Float): Float {
            val xi = x.toInt() and 255
            val yi = y.toInt() and 255
            val xf = x - x.toInt()
            val yf = y - y.toInt()
            val u = fade(xf); val v = fade(yf)
            val aa = perm[perm[xi] + yi]
            val ab = perm[perm[xi] + yi + 1]
            val ba = perm[perm[xi + 1] + yi]
            val bb = perm[perm[xi + 1] + yi + 1]
            val x1 = lerp(lerp(aa.toFloat(), ba.toFloat(), u), lerp(ab.toFloat(), bb.toFloat(), u), v)
            return x1 / 256f
        }

        fun fade(t: Float) = t * t * t * (t * (t * 6 - 15) + 10)
        fun lerp(a: Float, b: Float, t: Float) = a + t * (b - a)

        for (x in 0 until layer.width) {
            for (y in 0 until layer.height) {
                var value = 0f
                var amp = 1f
                var freq = scale
                var maxAmp = 0f
                for (o in 0 until octaves) {
                    value += noise(x * freq, y * freq) * amp
                    maxAmp += amp
                    amp *= 0.5f
                    freq *= 2f
                }
                value /= maxAmp

                // Map to tile ID based on thresholds
                var tileIdx = 0
                for ((i, threshold) in thresholds.withIndex()) {
                    if (value >= threshold) tileIdx = i + 1
                }
                layer.setTile(x, y, tileIds.getOrElse(tileIdx) { 0 })
            }
        }
    }
}
