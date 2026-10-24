package com.sengine.engine.sprite

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Colour helpers for the sprite editor. Pixels are ARGB ints with straight (non premultiplied) alpha;
 * alpha 0 means "empty" everywhere in this package.
 */
object Colors {
    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun a(c: Int) = (c ushr 24) and 0xFF
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF

    fun withAlpha(c: Int, alpha: Int): Int = (c and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)

    /** Linear interpolation between two ARGB colours. */
    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return argb(
            (Colors.a(a) + (Colors.a(b) - Colors.a(a)) * k).toInt(),
            (Colors.r(a) + (Colors.r(b) - Colors.r(a)) * k).toInt(),
            (Colors.g(a) + (Colors.g(b) - Colors.g(a)) * k).toInt(),
            (Colors.b(a) + (Colors.b(b) - Colors.b(a)) * k).toInt(),
        )
    }

    fun scale(c: Int, factor: Float): Int = argb(
        Colors.a(c),
        (Colors.r(c) * factor).toInt().coerceIn(0, 255),
        (Colors.g(c) * factor).toInt().coerceIn(0, 255),
        (Colors.b(c) * factor).toInt().coerceIn(0, 255),
    )

    fun luminance(c: Int): Float =
        (0.2126f * Colors.r(c) + 0.7152f * Colors.g(c) + 0.0722f * Colors.b(c)) / 255f

    fun hsv(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return rgb(((r1 + m) * 255f).toInt(), ((g1 + m) * 255f).toInt(), ((b1 + m) * 255f).toInt())
    }

    /** True when [a] and [b] are within [tolerance] (0..255) on every channel, alpha included. */
    fun similar(a: Int, b: Int, tolerance: Int): Boolean {
        if (a == b) return true
        if (Colors.a(a) == 0 && Colors.a(b) == 0) return true
        val t = tolerance.coerceIn(0, 255)
        return abs(Colors.a(a) - Colors.a(b)) <= t &&
            abs(Colors.r(a) - Colors.r(b)) <= t &&
            abs(Colors.g(a) - Colors.g(b)) <= t &&
            abs(Colors.b(a) - Colors.b(b)) <= t
    }

    /** Parses "#RRGGBB", "#AARRGGBB" or "RRGGBB". Returns null when the text is not a colour. */
    fun parse(text: String): Int? {
        val s = text.trim().removePrefix("#")
        val hex = s.toLongOrNull(16) ?: return null
        return when (s.length) {
            6 -> 0xFF000000.toInt() or hex.toInt()
            8 -> hex.toInt()
            3 -> {
                val r = s[0].digitToInt(16) * 17
                val g = s[1].digitToInt(16) * 17
                val b = s[2].digitToInt(16) * 17
                rgb(r, g, b)
            }
            else -> null
        }
    }

    fun toHex(c: Int): String = String.format("#%08X", c)
}

/**
 * Pixel operations backing the Sprite Studio tools. Everything works on a plain `IntArray` of
 * ARGB pixels in row order, so it can be unit tested without Android and reused by batch tools
 * (auto outlines, atlas padding, palette swaps).
 */
object PixelOps {

    // ---------------------------------------------------------------- tools

    /** Sets one pixel with a simple alpha blend. */
    fun plot(px: IntArray, w: Int, h: Int, x: Int, y: Int, color: Int, blend: Boolean = true) {
        if (x < 0 || y < 0 || x >= w || y >= h) return
        val i = y * w + x
        px[i] = if (blend && Colors.a(color) > 0) over(color, px[i]) else color
    }

    /** Source-over composite of [src] on [dst]; both are ARGB. */
    fun over(src: Int, dst: Int): Int {
        val sa = Colors.a(src)
        if (sa == 255) return src
        if (sa == 0) return dst
        val da = Colors.a(dst)
        val outA = sa + da * (255 - sa) / 255
        if (outA == 0) return 0
        val r = (Colors.r(src) * sa + Colors.r(dst) * da * (255 - sa) / 255) / outA
        val g = (Colors.g(src) * sa + Colors.g(dst) * da * (255 - sa) / 255) / outA
        val b = (Colors.b(src) * sa + Colors.b(dst) * da * (255 - sa) / 255) / outA
        return Colors.argb(outA, r, g, b)
    }

    /** Round brush stamp; [hardness] 0 = soft airbrush edge, 1 = hard pixel edge. */
    fun brush(
        px: IntArray, w: Int, h: Int, cx: Float, cy: Float, radius: Float,
        color: Int, hardness: Float = 1f, erase: Boolean = false,
    ) {
        val r = max(0.5f, radius)
        val x0 = (cx - r).toInt().coerceAtLeast(0)
        val x1 = (cx + r).toInt().coerceAtMost(w - 1)
        val y0 = (cy - r).toInt().coerceAtLeast(0)
        val y1 = (cy + r).toInt().coerceAtMost(h - 1)
        val hard = hardness.coerceIn(0f, 1f)
        for (y in y0..y1) for (x in x0..x1) {
            val d = hypot((x + 0.5f - cx).toDouble(), (y + 0.5f - cy).toDouble()).toFloat()
            if (d > r) continue
            val edge = if (hard >= 0.999f) (if (d <= r - 0.5f) 1f else (r - d).coerceIn(0f, 1f)) else
                ((r - d) / max(0.001f, r * (1f - hard))).coerceIn(0f, 1f)
            if (edge <= 0f) continue
            val i = y * w + x
            if (erase) {
                val a = (Colors.a(px[i]) * (1f - edge)).toInt()
                px[i] = Colors.withAlpha(px[i], a)
            } else {
                val c = Colors.withAlpha(color, (Colors.a(color) * edge).toInt())
                px[i] = over(c, px[i])
            }
        }
    }

    /** Straight line between two points, stamped with a circular brush. */
    fun line(
        px: IntArray, w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float,
        radius: Float, color: Int, hardness: Float = 1f, erase: Boolean = false,
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val steps = max(1, (len / max(0.5f, radius * 0.5f)).toInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            brush(px, w, h, x0 + dx * t, y0 + dy * t, radius, color, hardness, erase)
        }
    }

    /** Axis aligned rectangle outline or filled. */
    fun rect(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, filled: Boolean) {
        val ax = min(x0, x1); val bx = max(x0, x1)
        val ay = min(y0, y1); val by = max(y0, y1)
        if (filled) {
            for (y in ay..by) for (x in ax..bx) plot(px, w, h, x, y, color)
        } else {
            for (x in ax..bx) { plot(px, w, h, x, ay, color); plot(px, w, h, x, by, color) }
            for (y in ay..by) { plot(px, w, h, ax, y, color); plot(px, w, h, bx, y, color) }
        }
    }

    /** Ellipse inscribed in the given rectangle. */
    fun ellipse(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, filled: Boolean) {
        val ax = min(x0, x1).toFloat(); val bx = max(x0, x1).toFloat()
        val ay = min(y0, y1).toFloat(); val by = max(y0, y1).toFloat()
        val cx = (ax + bx) * 0.5f
        val cy = (ay + by) * 0.5f
        val rx = max(0.5f, (bx - ax) * 0.5f)
        val ry = max(0.5f, (by - ay) * 0.5f)
        for (y in ay.toInt()..by.toInt()) for (x in ax.toInt()..bx.toInt()) {
            val nx = (x + 0.5f - cx) / rx
            val ny = (y + 0.5f - cy) / ry
            val d = nx * nx + ny * ny
            val inside = if (filled) d <= 1f else d <= 1f && d >= (1f - 2f / max(rx, ry)).coerceAtLeast(0.1f)
            if (inside) plot(px, w, h, x, y, color)
        }
    }

    /** Filled convex/concave polygon (even-odd scanline). */
    fun polygon(px: IntArray, w: Int, h: Int, pts: FloatArray, color: Int) {
        if (pts.size < 6) return
        val n = pts.size / 2
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) { val y = pts[i * 2 + 1]; if (y < minY) minY = y; if (y > maxY) maxY = y }
        val y0 = max(0, minY.toInt())
        val y1 = min(h - 1, maxY.toInt())
        val xs = ArrayList<Float>(8)
        for (y in y0..y1) {
            xs.clear()
            val sy = y + 0.5f
            for (i in 0 until n) {
                val j = (i + 1) % n
                val xa = pts[i * 2]; val ya = pts[i * 2 + 1]
                val xb = pts[j * 2]; val yb = pts[j * 2 + 1]
                if ((ya <= sy && yb > sy) || (yb <= sy && ya > sy)) {
                    xs.add(xa + (sy - ya) / (yb - ya) * (xb - xa))
                }
            }
            if (xs.size < 2) continue
            xs.sort()
            var k = 0
            while (k + 1 < xs.size) {
                val xa = max(0, Math.ceil((xs[k] - 0.5f).toDouble()).toInt())
                val xb = min(w - 1, Math.floor((xs[k + 1] - 0.5f).toDouble()).toInt())
                for (x in xa..xb) plot(px, w, h, x, y, color, blend = false)
                k += 2
            }
        }
    }

    // ---------------------------------------------------------------- fills

    /**
     * Flood fill starting at [x],[y].
     *
     * @param tolerance 0 = exact colour match only
     * @param contiguous false = replace every matching pixel in the image ("global" fill)
     * @param mask optional per-pixel mask (0 = locked); used by selections
     * @return number of pixels changed
     */
    fun fill(
        px: IntArray, w: Int, h: Int, x: Int, y: Int, color: Int,
        tolerance: Int = 0, contiguous: Boolean = true, mask: ByteArray? = null,
    ): Int {
        if (x < 0 || y < 0 || x >= w || y >= h) return 0
        val target = px[y * w + x]
        if (target == color && Colors.a(color) == 255) return 0
        if (!contiguous) {
            var changed = 0
            for (i in px.indices) {
                if (mask != null && mask[i].toInt() == 0) continue
                if (Colors.similar(px[i], target, tolerance)) { px[i] = color; changed++ }
            }
            return changed
        }
        var changed = 0
        val stack = IntArray(w * h)
        var top = 0
        stack[top++] = y * w + x
        val seen = ByteArray(w * h)
        seen[y * w + x] = 1
        while (top > 0) {
            val i = stack[--top]
            if (mask != null && mask[i].toInt() == 0) continue
            if (!Colors.similar(px[i], target, tolerance)) continue
            px[i] = color
            changed++
            val cx = i % w
            val cy = i / w
            if (cx > 0 && seen[i - 1].toInt() == 0) { seen[i - 1] = 1; stack[top++] = i - 1 }
            if (cx < w - 1 && seen[i + 1].toInt() == 0) { seen[i + 1] = 1; stack[top++] = i + 1 }
            if (cy > 0 && seen[i - w].toInt() == 0) { seen[i - w] = 1; stack[top++] = i - w }
            if (cy < h - 1 && seen[i + w].toInt() == 0) { seen[i + w] = 1; stack[top++] = i + w }
        }
        return changed
    }

    /**
     * Colour isolation: removes (or replaces) every pixel matching [target] within [tolerance].
     * With [contiguous] only the connected region around the picked pixel is affected, which is what
     * users expect from a "magic wand" that must not eat the same colour elsewhere in the sprite.
     */
    fun isolateColor(
        px: IntArray, w: Int, h: Int, x: Int, y: Int, target: Int, tolerance: Int,
        replacement: Int = 0, contiguous: Boolean = true,
    ): Int {
        if (x < 0 || y < 0 || x >= w || y >= h) return 0
        val picked = if (target == 0) px[y * w + x] else target
        return fill(px, w, h, x, y, replacement, tolerance, contiguous).takeIf { contiguous }
            ?: run {
                var changed = 0
                for (i in px.indices) if (Colors.similar(px[i], picked, tolerance)) { px[i] = replacement; changed++ }
                changed
            }
    }

    /**
     * Manual background removal. The user paints with [erase] = true into a mask, then this applies the
     * mask as hard (or feathered) transparency. Nothing here inspects colours: removal is entirely
     * driven by what the user painted, as required.
     */
    fun applyRemovalMask(px: IntArray, w: Int, h: Int, mask: FloatArray, globalAlpha: Float = 1f) {
        for (i in px.indices) {
            val m = mask[i]
            if (m <= 0.001f) continue
            val cut = (m * globalAlpha).coerceIn(0f, 1f)
            val a = (Colors.a(px[i]) * (1f - cut)).toInt()
            px[i] = Colors.withAlpha(px[i], a)
        }
    }

    /** Counts pixels with any transparency (used by the "background not removed" warning). */
    fun transparentCount(px: IntArray): Int = px.count { Colors.a(it) == 0 }

    // ---------------------------------------------------------------- transforms

    fun flipHorizontal(px: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = px[y * w + (w - 1 - x)]
        return out
    }

    fun flipVertical(px: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = px[(h - 1 - y) * w + x]
        return out
    }

    /** Nearest-neighbour resample: keeps pixel art crisp. */
    fun resize(px: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        if (nw <= 0 || nh <= 0) return IntArray(0)
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val sy = (y.toFloat() * h / nh).toInt().coerceIn(0, h - 1)
            for (x in 0 until nw) {
                val sx = (x.toFloat() * w / nw).toInt().coerceIn(0, w - 1)
                out[y * nw + x] = px[sy * w + sx]
            }
        }
        return out
    }

    fun crop(px: IntArray, w: Int, h: Int, x: Int, y: Int, cw: Int, ch: Int): IntArray {
        val out = IntArray(cw * ch)
        for (yy in 0 until ch) for (xx in 0 until cw) {
            val sx = x + xx
            val sy = y + yy
            out[yy * cw + xx] = if (sx in 0 until w && sy in 0 until h) px[sy * w + sx] else 0
        }
        return out
    }

    /** Rotates by a multiple of 90 degrees clockwise. */
    fun rotate90(px: IntArray, w: Int, h: Int, turns: Int): Triple<IntArray, Int, Int> {
        var cur = px; var cw = w; var ch = h
        repeat(((turns % 4) + 4) % 4) {
            val out = IntArray(cur.size)
            for (y in 0 until ch) for (x in 0 until cw) {
                // clockwise: (x, y) -> (ch - 1 - y, x) in the new (ch x cw) image
                out[x * ch + (ch - 1 - y)] = cur[y * cw + x]
            }
            cur = out
            val t = cw; cw = ch; ch = t
        }
        return Triple(cur, cw, ch)
    }

    /** Bounding box of non-empty pixels as [x, y, w, h], or null when fully transparent. */
    fun bounds(px: IntArray, w: Int, h: Int, alphaThreshold: Int = 1): IntArray? {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Colors.a(px[y * w + x]) >= alphaThreshold) {
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        return intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** 1 pixel dark/light outline around the opaque region — the classic sprite readability trick. */
    fun outline(px: IntArray, w: Int, h: Int, color: Int, thickness: Int = 1) {
        val src = px.copyOf()
        repeat(thickness.coerceAtLeast(1)) { step ->
            val padded = if (step == 0) src else px.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                if (Colors.a(padded[y * w + x]) != 0) continue
                var touches = false
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h && Colors.a(padded[ny * w + nx]) > 64) { touches = true }
                }
                if (touches) px[y * w + x] = color
            }
        }
    }

    /** Applies a vertical gradient to opaque pixels (cloth shading, metal, sky). */
    fun shadeVertical(px: IntArray, w: Int, h: Int, top: Float, bottom: Float) {
        for (y in 0 until h) {
            val t = y.toFloat() / max(1, h - 1)
            val f = top + (bottom - top) * t
            for (x in 0 until w) {
                val i = y * w + x
                if (Colors.a(px[i]) == 0) continue
                px[i] = Colors.scale(px[i], f)
            }
        }
    }

    /** Simple ordered dither between two colours; used for retro ramps in the part library. */
    fun dither(px: IntArray, w: Int, h: Int, a: Int, b: Int) {
        val m = arrayOf(
            intArrayOf(0, 8, 2, 10), intArrayOf(12, 4, 14, 6),
            intArrayOf(3, 11, 1, 9), intArrayOf(15, 7, 13, 5),
        )
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (Colors.a(px[i]) == 0) continue
            px[i] = if (m[y % 4][x % 4] < 8) a else b
        }
    }

    /** Copies all non-empty pixels of [src] onto [dst] at [dx],[dy] with alpha. */
    fun stamp(dst: IntArray, dw: Int, dh: Int, src: IntArray, sw: Int, sh: Int, dx: Int, dy: Int, opacity: Float = 1f) {
        val o = opacity.coerceIn(0f, 1f)
        for (y in 0 until sh) for (x in 0 until sw) {
            val s = src[y * sw + x]
            if (Colors.a(s) == 0) continue
            val tx = dx + x
            val ty = dy + y
            if (tx < 0 || ty < 0 || tx >= dw || ty >= dh) continue
            val c = Colors.withAlpha(s, (Colors.a(s) * o).toInt())
            val i = ty * dw + tx
            dst[i] = over(c, dst[i])
        }
    }

    /** Rotates a square-ish image around its centre by [degrees] with nearest sampling. */
    fun rotate(px: IntArray, w: Int, h: Int, degrees: Float, fill: Int = 0): IntArray {
        val out = IntArray(px.size)
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val cx = w * 0.5f
        val cy = h * 0.5f
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            val sx = (dx * c + dy * s + cx).toInt()
            val sy = (-dx * s + dy * c + cy).toInt()
            out[y * w + x] = if (sx in 0 until w && sy in 0 until h) px[sy * w + sx] else fill
        }
        return out
    }

    /** Reduces the image to a palette of [count] colours (median cut, used by "posterise"). */
    fun posterize(px: IntArray, w: Int, h: Int, count: Int): IntArray {
        val levels = count.coerceIn(2, 64)
        val out = px.copyOf()
        for (i in out.indices) {
            val c = out[i]
            if (Colors.a(c) == 0) continue
            fun q(v: Int): Int = (Math.round(v * (levels - 1) / 255f) * 255f / (levels - 1)).toInt().coerceIn(0, 255)
            out[i] = Colors.argb(Colors.a(c), q(Colors.r(c)), q(Colors.g(c)), q(Colors.b(c)))
        }
        return out
    }

    /** Resizes the canvas keeping the art centred (padding is transparent). */
    fun changeCanvas(px: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        val out = IntArray(nw * nh)
        val dx = (nw - w) / 2
        val dy = (nh - h) / 2
        stamp(out, nw, nh, px, w, h, dx, dy, 1f)
        return out
    }

    /** Generates a light/dark ramp from a base colour — used by the part library. */
    fun ramp(base: Int, steps: Int = 4): IntArray {
        val n = steps.coerceAtLeast(2)
        return IntArray(n) { i ->
            val t = i.toFloat() / (n - 1)
            Colors.lerp(Colors.scale(base, 0.55f), Colors.lerp(base, 0xFFFFFFFF.toInt(), 0.35f), t)
        }
    }

    fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()

    fun angleDegrees(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()

    fun star(cx: Float, cy: Float, outer: Float, inner: Float, points: Int, rotation: Float = -90f): FloatArray {
        val n = points.coerceIn(3, 32)
        val out = FloatArray(n * 4)
        var k = 0
        for (i in 0 until n * 2) {
            val r = if (i % 2 == 0) outer else inner
            val a = Math.toRadians((rotation + i * 180f / n).toDouble())
            out[k++] = cx + cos(a).toFloat() * r
            out[k++] = cy + sin(a).toFloat() * r
        }
        return out
    }

    fun roundInt(v: Float): Int = v.roundToInt()

    fun clampByte(v: Int): Int = v.coerceIn(0, 255)

    fun sqrtF(v: Float): Float = sqrt(v)
}
