package com.sengine.engine.render

import com.sengine.engine.core.GameObject

/**
 * GL-free 2D draw rules shared by [SceneRenderer] and [Renderer2D].
 *
 * These are the rules the 2D frame depends on: draw order, pivot placement, UV and flip mapping,
 * text box sizing and particle interpolation. They live here, with no Android or GL imports, so they
 * can be unit-tested on the JVM. Any future batching path must reproduce these results exactly; the
 * tests in `Draw2DRulesTest` pin the current behaviour.
 *
 * Conventions:
 * - A sprite quad spans [-0.5, 0.5] on X and Y in local space. Artwork coordinates run [0, 1].
 * - Default UVs are `(0, 1, 1, 0)` as `u0, v0, u1, v1`, matching the existing renderer.
 */
object Draw2DRules {

    /**
     * Active objects in draw order: ascending [GameObject.order], ties broken by scene index.
     * Objects that are inactive, destroyed, or under an inactive parent are excluded.
     */
    fun drawOrder(objects: Iterable<GameObject>): List<GameObject> =
        objects.withIndex()
            .filter { it.value.isActiveInHierarchy() }
            .sortedWith(compareBy({ it.value.order }, { it.index }))
            .map { it.value }

    /**
     * Offset that moves the artwork pivot to the object origin. `pivot` is normalized artwork
     * coordinates in [0, 1]; (0.5) is the centre and gives zero offset.
     */
    fun pivotOffset(pivot: Float): Float = 0.5f - pivot

    /**
     * Writes `u0, v0, u1, v1` into [out] (length 4) without allocating. [uv] is `(u0, v0, u1, v1)`
     * or null for the full texture. Flips swap the two ends of the matching axis.
     */
    fun quadUvInto(out: FloatArray, uv: FloatArray?, flipX: Boolean, flipY: Boolean) {
        val q = uv ?: DEFAULT_UV
        out[0] = if (flipX) q[2] else q[0]
        out[2] = if (flipX) q[0] else q[2]
        out[1] = if (flipY) q[3] else q[1]
        out[3] = if (flipY) q[1] else q[3]
    }

    /** Number of text lines: one more than the number of newlines. */
    fun textLineCount(text: String): Int = text.count { it == '\n' } + 1

    /** World height of a text block: `size` per line. */
    fun textHeight(size: Float, lines: Int): Float = size * lines

    /** World width of a text block, keeping the bitmap's aspect ratio at `height`. */
    fun textWidth(height: Float, texW: Float, texH: Float): Float = height * texW / texH

    /**
     * X offset of the text quad's centre from the object origin. The quad is `width` wide.
     * align: 0 = left (text starts at the origin), 1 = centre, 2 = right (text ends at the origin).
     */
    fun textOriginX(align: Int, width: Float): Float = when (align) {
        0 -> width / 2
        2 -> -width / 2
        else -> 0f
    }

    /** Normalized particle age in [0, 1]. */
    fun particleT(age: Float, life: Float): Float = (age / life).coerceIn(0f, 1f)

    /** Particle size at normalized age `t`, scaled by the particle's own factor. */
    fun particleSize(startSize: Float, endSize: Float, t: Float, sizeScale: Float): Float =
        (startSize + (endSize - startSize) * t) * sizeScale

    /**
     * Per-channel linear interpolation of packed ARGB colours. Channels are truncated toward zero,
     * so the midpoint of 0x00 and 0xFF is 0x7F.
     */
    fun lerpColor(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)).toInt() and 0xFF
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val DEFAULT_UV = floatArrayOf(0f, 1f, 1f, 0f)
}
