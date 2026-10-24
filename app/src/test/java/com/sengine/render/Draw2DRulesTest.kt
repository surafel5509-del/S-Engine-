package com.sengine.render

import com.sengine.engine.core.GameObject
import com.sengine.engine.render.Draw2DRules
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the GL-free 2D rules that SceneRenderer and Renderer2D depend on. These are the reference
 * results any batching or GL-side change has to reproduce.
 */
class Draw2DRulesTest {

    private fun obj(id: Long, order: Int = 0, active: Boolean = true) =
        GameObject(id, "o$id").also { it.order = order; it.active = active }

    // ------------------------------------------------------------ draw order

    @Test
    fun drawOrderSortsByOrderThenSceneIndex() {
        val a = obj(1, order = 2)
        val b = obj(2, order = 0)
        val c = obj(3, order = 0)
        val d = obj(4, order = 1)
        val sorted = Draw2DRules.drawOrder(listOf(a, b, c, d))
        assertEquals(listOf(2L, 3L, 4L, 1L), sorted.map { it.id })
    }

    @Test
    fun drawOrderExcludesInactiveObjectsAndTheirChildren() {
        val off = obj(1, active = false)
        val child = obj(2).also { it.parent = off }
        val on = obj(3)
        val destroyed = obj(4).also { it.destroyed = true }
        val sorted = Draw2DRules.drawOrder(listOf(off, child, on, destroyed))
        assertEquals(listOf(3L), sorted.map { it.id })
    }

    @Test
    fun drawOrderIsStableForEqualOrderAndEmptyInput() {
        assertTrue(Draw2DRules.drawOrder(emptyList()).isEmpty())
        val xs = (1L..5L).map { obj(it, order = 7) }
        assertEquals(xs.map { it.id }, Draw2DRules.drawOrder(xs).map { it.id })
    }

    // ------------------------------------------------------------ pivots

    @Test
    fun centredPivotGivesNoOffset() {
        assertEquals(0f, Draw2DRules.pivotOffset(0.5f), 0f)
    }

    @Test
    fun pivotOffsetPlacesThePivotPointAtTheOrigin() {
        // Quad local X runs [-0.5, 0.5]; artwork X runs [0, 1]. The artwork point at `pivot` must land at 0.
        for (pivot in listOf(0f, 0.25f, 0.5f, 0.8f, 1f)) {
            val quadX = pivot - 0.5f
            val worldX = quadX + Draw2DRules.pivotOffset(pivot)
            assertEquals("pivot $pivot", 0f, worldX, 1e-6f)
        }
    }

    // ------------------------------------------------------------ UVs and flips

    private fun uv(uv: FloatArray?, flipX: Boolean = false, flipY: Boolean = false): List<Float> {
        val out = FloatArray(4)
        Draw2DRules.quadUvInto(out, uv, flipX, flipY)
        return out.toList()
    }

    @Test
    fun defaultUvIsFullTextureWithVFlipped() {
        // Matches the existing renderer: u0, v0, u1, v1 = 0, 1, 1, 0.
        assertEquals(listOf(0f, 1f, 1f, 0f), uv(null))
    }

    @Test
    fun customUvIsUsedAsIs() {
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.4f), uv(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)))
    }

    @Test
    fun flipXSwapsU() {
        assertEquals(listOf(1f, 1f, 0f, 0f), uv(null, flipX = true))
        assertEquals(listOf(0.3f, 0.2f, 0.1f, 0.4f), uv(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), flipX = true))
    }

    @Test
    fun flipYSwapsV() {
        assertEquals(listOf(0f, 0f, 1f, 1f), uv(null, flipY = true))
        assertEquals(listOf(0.1f, 0.4f, 0.3f, 0.2f), uv(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), flipY = true))
    }

    @Test
    fun flippingBothAxesReversesBoth() {
        assertArrayEquals(
            floatArrayOf(0.3f, 0.4f, 0.1f, 0.2f),
            FloatArray(4).also { Draw2DRules.quadUvInto(it, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), true, true) },
            0f,
        )
    }

    // ------------------------------------------------------------ text

    @Test
    fun textLineCountCountsNewlines() {
        assertEquals(1, Draw2DRules.textLineCount(""))
        assertEquals(1, Draw2DRules.textLineCount("single"))
        assertEquals(3, Draw2DRules.textLineCount("a\nb\nc"))
        assertEquals(2, Draw2DRules.textLineCount("trailing\n"))
    }

    @Test
    fun textHeightIsSizePerLineAndWidthKeepsAspect() {
        val h = Draw2DRules.textHeight(size = 0.5f, lines = 2)
        assertEquals(1f, h, 0f)
        // 256x64 bitmap: width is four times the height.
        assertEquals(4f, Draw2DRules.textWidth(h, 256f, 64f) / h, 1e-6f)
    }

    @Test
    fun textAlignmentKeepsTheTextBoxOnTheRightSideOfTheOrigin() {
        val w = 3f
        // Left: box spans [0, w]. Centre: [-w/2, w/2]. Right: [-w, 0].
        val left = Draw2DRules.textOriginX(0, w)
        assertEquals(0f, left - w / 2, 0f)
        assertEquals(w, left + w / 2, 0f)
        val centre = Draw2DRules.textOriginX(1, w)
        assertEquals(0f, centre, 0f)
        val right = Draw2DRules.textOriginX(2, w)
        assertEquals(-w, right - w / 2, 0f)
        assertEquals(0f, right + w / 2, 0f)
    }

    @Test
    fun unknownAlignmentFallsBackToCentre() {
        assertEquals(0f, Draw2DRules.textOriginX(9, 5f), 0f)
    }

    // ------------------------------------------------------------ particles

    @Test
    fun particleTIsClampedToUnitRange() {
        assertEquals(0f, Draw2DRules.particleT(-1f, 2f), 0f)
        assertEquals(0.5f, Draw2DRules.particleT(1f, 2f), 0f)
        assertEquals(1f, Draw2DRules.particleT(9f, 2f), 0f)
    }

    @Test
    fun particleSizeInterpolatesAndScales() {
        assertEquals(0.25f, Draw2DRules.particleSize(0.25f, 0.02f, 0f, 1f), 1e-6f)
        assertEquals(0.02f, Draw2DRules.particleSize(0.25f, 0.02f, 1f, 1f), 1e-6f)
        // (1 + (0 - 1) * 0.5) * 0.5 = 0.25
        assertEquals(0.25f, Draw2DRules.particleSize(1f, 0f, 0.5f, 0.5f), 1e-6f)
    }

    @Test
    fun lerpColorHitsEndpointsAndTruncatesChannels() {
        val a = 0x11223344
        val b = 0xAABBCCDD.toInt()
        assertEquals(a, Draw2DRules.lerpColor(a, b, 0f))
        assertEquals(b, Draw2DRules.lerpColor(a, b, 1f))
        // Midpoint of 0x00 and 0xFF truncates to 0x7F, not 0x80.
        assertEquals(0x007F7F7F, Draw2DRules.lerpColor(0x00000000, 0x00FFFFFF, 0.5f) and 0x00FFFFFF)
        assertFalse(Draw2DRules.lerpColor(a, b, 0.5f) == a)
    }
}
