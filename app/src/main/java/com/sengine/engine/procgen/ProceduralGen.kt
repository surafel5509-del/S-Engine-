package com.sengine.engine.procgen

import com.sengine.engine.core.Scene
import com.sengine.engine.core.SpriteRenderer
import kotlin.math.*

// ─── Procedural Generation System ────────────────────────────────────────

/**
 * Professional procedural generation utilities:
 * - Perlin/Simplex noise
 * - Dungeon generation (BSP, cellular automata)
 * - Terrain generation
 * - Wave Function Collapse (simplified)
 * - Poisson disk sampling
 * - L-systems for vegetation
 */

object Noise {
    private val permutation = IntArray(512)

    init {
        val p = IntArray(256) { it }
        val rng = kotlin.random.Random(42)
        for (i in 255 downTo 1) {
            val j = rng.nextInt(i + 1)
            val temp = p[i]; p[i] = p[j]; p[j] = temp
        }
        for (i in 0 until 512) permutation[i] = p[i and 255]
    }

    fun setSeed(seed: Int) {
        val p = IntArray(256) { it }
        val rng = kotlin.random.Random(seed.toLong())
        for (i in 255 downTo 1) {
            val j = rng.nextInt(i + 1)
            val temp = p[i]; p[i] = p[j]; p[j] = temp
        }
        for (i in 0 until 512) permutation[i] = p[i and 255]
    }

    private fun fade(t: Float): Float = t * t * t * (t * (t * 6 - 15) + 10)
    private fun lerp(a: Float, b: Float, t: Float): Float = a + t * (b - a)
    private fun grad(hash: Int, x: Float, y: Float): Float {
        val h = hash and 3
        val u = if (h < 2) x else y
        val v = if (h < 2) y else x
        return (if (h and 1 == 0) u else -u) + (if (h and 2 == 0) v else -v)
    }

    /** 2D Perlin noise. Returns -1 to 1. */
    fun perlin2D(x: Float, y: Float): Float {
        val xi = x.toInt() and 255
        val yi = y.toInt() and 255
        val xf = x - floor(x)
        val yf = y - floor(y)
        val u = fade(xf)
        val v = fade(yf)

        val aa = permutation[permutation[xi] + yi]
        val ab = permutation[permutation[xi] + yi + 1]
        val ba = permutation[permutation[xi + 1] + yi]
        val bb = permutation[permutation[xi + 1] + yi + 1]

        val x1 = lerp(grad(aa, xf, yf), grad(ba, xf - 1, yf), u)
        val x2 = lerp(grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1), u)
        return lerp(x1, x2, v)
    }

    /** Fractal Brownian Motion — layered noise. */
    fun fbm(x: Float, y: Float, octaves: Int = 4, lacunarity: Float = 2f, persistence: Float = 0.5f): Float {
        var value = 0f
        var amplitude = 1f
        var frequency = 1f
        var maxValue = 0f
        for (i in 0 until octaves) {
            value += perlin2D(x * frequency, y * frequency) * amplitude
            maxValue += amplitude
            amplitude *= persistence
            frequency *= lacunarity
        }
        return value / maxValue
    }

    /** Generate a heightmap using noise. */
    fun generateHeightmap(width: Int, height: Int, scale: Float = 0.05f, octaves: Int = 4, seed: Int = 42): FloatArray {
        setSeed(seed)
        val map = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                map[y * width + x] = (fbm(x.toFloat() * scale, y.toFloat() * scale, octaves) + 1f) * 0.5f
            }
        }
        return map
    }
}

/** Poisson disk sampling for natural point distribution. */
object PoissonDiskSampling {
    data class Point2D(val x: Float, val y: Float)

    /** Generate Poisson disk distributed points. */
    fun sample(width: Float, height: Float, minDist: Float, maxAttempts: Int = 30, seed: Long = 42): List<Point2D> {
        val rng = kotlin.random.Random(seed)
        val cellSize = minDist / sqrt(2f)
        val gridW = (width / cellSize).toInt() + 1
        val gridH = (height / cellSize).toInt() + 1
        val grid = arrayOfNulls<Point2D>(gridW * gridH)
        val active = mutableListOf<Point2D>()
        val result = mutableListOf<Point2D>()

        // First point
        val first = Point2D(width / 2, height / 2)
        active.add(first)
        result.add(first)
        val gi = (first.x / cellSize).toInt()
        val gj = (first.y / cellSize).toInt()
        if (gi in 0 until gridW && gj in 0 until gridH) grid[gj * gridW + gi] = first

        while (active.isNotEmpty()) {
            val idx = rng.nextInt(active.size)
            val point = active[idx]
            var found = false

            for (attempt in 0 until maxAttempts) {
                val angle = (rng.nextDouble() * 2 * PI).toFloat()
                val dist = (minDist + rng.nextDouble() * minDist).toFloat()
                val ny = point.y + sin(angle).toFloat() * dist
                val nx = point.x + cos(angle).toFloat() * dist

                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue

                val ngi = (nx / cellSize).toInt()
                val ngj = (ny / cellSize).toInt()
                if (ngi !in 0 until gridW || ngj !in 0 until gridH) continue

                // Check neighbors
                var tooClose = false
                for (dx in -2..2) {
                    for (dy in -2..2) {
                        val ci = ngi + dx; val cj = ngj + dy
                        if (ci !in 0 until gridW || cj !in 0 until gridH) continue
                        val neighbor = grid[cj * gridW + ci]
                        if (neighbor != null) {
                            val ddx = neighbor.x - nx; val ddy = neighbor.y - ny
                            if (ddx * ddx + ddy * ddy < minDist * minDist) {
                                tooClose = true; break
                            }
                        }
                    }
                    if (tooClose) break
                }

                if (!tooClose) {
                    val newPoint = Point2D(nx, ny)
                    active.add(newPoint)
                    result.add(newPoint)
                    grid[ngj * gridW + ngi] = newPoint
                    found = true
                    break
                }
            }

            if (!found) active.removeAt(idx)
        }

        return result
    }
}

/** BSP Dungeon generator — creates rooms connected by corridors. */
object BspDungeonGenerator {
    data class Room(val x: Int, val y: Int, val w: Int, val h: Int, val centerX: Int, val y2: Int) {
        val cx: Int get() = x + w / 2
        val cy: Int get() = y + h / 2
    }

    class BspNode(
        var x: Int, var y: Int, var w: Int, var h: Int,
        var left: BspNode? = null, var right: BspNode? = null,
        var room: Room? = null,
    )

    /** Generate a BSP dungeon. */
    fun generate(width: Int, height: Int, minRoomSize: Int = 4, maxDepth: Int = 5, seed: Long = 42): Pair<FloatArray, List<Room>> {
        val rng = kotlin.random.Random(seed)
        val tiles = FloatArray(width * height) // 0=wall, 1=floor

        // Build BSP tree
        val root = BspNode(1, 1, width - 2, height - 2)
        splitNode(root, 0, maxDepth, minRoomSize, rng)

        // Create rooms
        val rooms = mutableListOf<Room>()
        createRooms(root, rooms, rng, minRoomSize)

        // Carve rooms
        for (room in rooms) {
            for (x in room.x until room.x + room.w) {
                for (y in room.y until room.y + room.h) {
                    if (x in 0 until width && y in 0 until height) {
                        tiles[y * width + x] = 1f
                    }
                }
            }
        }

        // Connect rooms with corridors
        for (i in 1 until rooms.size) {
            connectRooms(tiles, rooms[i - 1], rooms[i], width, height)
        }

        return tiles to rooms
    }

    private fun splitNode(node: BspNode, depth: Int, maxDepth: Int, minSize: Int, rng: kotlin.random.Random) {
        if (depth >= maxDepth || node.w < minSize * 2 + 2 || node.h < minSize * 2 + 2) return

        val horizontal = if (node.w > node.h) false else if (node.h > node.w) true else rng.nextBoolean()

        if (horizontal) {
            val split = node.h / 2 + rng.nextInt(node.h / 4) - node.h / 8
            node.left = BspNode(node.x, node.y, node.w, split)
            node.right = BspNode(node.x, node.y + split, node.w, node.h - split)
        } else {
            val split = node.w / 2 + rng.nextInt(node.w / 4) - node.w / 8
            node.left = BspNode(node.x, node.y, split, node.h)
            node.right = BspNode(node.x + split, node.y, node.w - split, node.h)
        }

        splitNode(node.left!!, depth + 1, maxDepth, minSize, rng)
        splitNode(node.right!!, depth + 1, maxDepth, minSize, rng)
    }

    private fun createRooms(node: BspNode, rooms: MutableList<Room>, rng: kotlin.random.Random, minSize: Int) {
        if (node.left == null && node.right == null) {
            val w = (minSize until node.w - 1).random(rng)
            val h = (minSize until node.h - 1).random(rng)
            val x = node.x + rng.nextInt((node.w - w).coerceAtLeast(1))
            val y = node.y + rng.nextInt((node.h - h).coerceAtLeast(1))
            node.room = Room(x, y, w, h, x + w / 2, y + h / 2)
            rooms.add(node.room!!)
        } else {
            node.left?.let { createRooms(it, rooms, rng, minSize) }
            node.right?.let { createRooms(it, rooms, rng, minSize) }
        }
    }

    private fun connectRooms(tiles: FloatArray, a: Room, b: Room, width: Int, height: Int) {
        var x = a.cx; var y = a.cy
        val tx = b.cx; val ty = b.cy

        // L-shaped corridor
        while (x != tx) {
            if (x in 0 until width && y in 0 until height) tiles[y * width + x] = 1f
            x += if (tx > x) 1 else -1
        }
        while (y != ty) {
            if (x in 0 until width && y in 0 until height) tiles[y * width + x] = 1f
            y += if (ty > y) 1 else -1
        }
    }
}

/** Simple L-system for generating tree/plant structures. */
object LSystem {
    data class LRule(val from: Char, val to: String)

    /** Generate an L-system string. */
    fun generate(axiom: String, rules: List<LRule>, iterations: Int): String {
        var current = axiom
        repeat(iterations) {
            val sb = StringBuilder()
            for (c in current) {
                val rule = rules.firstOrNull { it.from == c }
                sb.append(rule?.to ?: c.toString())
            }
            current = sb.toString()
        }
        return current
    }

    /** Interpret L-system string as turtle graphics commands. Returns line segments. */
    fun interpret(commands: String, startX: Float, startY: Float, startAngle: Float, stepLength: Float, angleStep: Float): List<FloatArray> {
        val lines = mutableListOf<FloatArray>()
        var x = startX; var y = startY; var angle = startAngle
        val stack = mutableListOf<Triple<Float, Float, Float>>()

        for (c in commands) {
            when (c) {
                'F' -> {
                    val nx = x + cos(Math.toRadians(angle.toDouble())).toFloat() * stepLength
                    val ny = y + sin(Math.toRadians(angle.toDouble())).toFloat() * stepLength
                    lines.add(floatArrayOf(x, y, nx, ny))
                    x = nx; y = ny
                }
                '+' -> angle += angleStep
                '-' -> angle -= angleStep
                '[' -> stack.add(Triple(x, y, angle))
                ']' -> {
                    val (px, py, pa) = stack.removeAt(stack.lastIndex)
                    x = px; y = py; angle = pa
                }
            }
        }
        return lines
    }
}
