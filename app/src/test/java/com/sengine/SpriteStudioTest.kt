package com.sengine

import com.sengine.engine.sprite.Colors
import com.sengine.engine.sprite.PartColors
import com.sengine.engine.sprite.PixelOps
import com.sengine.engine.sprite.SpriteDocument
import com.sengine.engine.sprite.SpriteParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sprite Studio model layer: drawing operations, the frame reel, manual background removal,
 * colour isolation and the pre-made part library. Everything here is Android free, so it runs as a
 * plain JVM unit test.
 */
class SpriteStudioTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun doc(w: Int = 32, h: Int = 32): SpriteDocument = SpriteDocument("test", w, h)

    // ------------------------------------------------------------------ drawing

    @Test
    fun brushAndShapesPaintPixels() {
        val d = doc()
        val px = d.pixels()

        PixelOps.plot(px, 32, 32, 1, 1, red)
        assertEquals(red, d.pixelAt(1, 1))

        // A line is continuous: a horizontal line must hit every column between its ends. The brush
        // stamp is round, so the ends are antialiased — check coverage rather than exact colours.
        PixelOps.line(px, 32, 32, 2f, 5f, 12f, 5f, 1f, green)
        for (x in 3..11) {
            val c = d.pixelAt(x, 5)
            assertTrue("line covers x=$x", Colors.a(c) > 0)
            assertTrue("line is green at x=$x", Colors.g(c) > Colors.r(c) && Colors.g(c) > Colors.b(c))
        }

        PixelOps.rect(px, 32, 32, 4, 10, 12, 18, blue, false)
        assertEquals(blue, d.pixelAt(4, 10))
        assertEquals(blue, d.pixelAt(12, 18))
        assertEquals("rect stays hollow", 0, d.pixelAt(8, 14))

        PixelOps.ellipse(px, 32, 32, 10, 20, 22, 28, red, true)
        assertEquals(red, d.pixelAt(16, 24))
        assertEquals("ellipse ignores its bounding-box corners", 0, d.pixelAt(10, 20))
    }

    @Test
    fun eraserClearsOnlyOpaquePaint() {
        val d = doc()
        val px = d.pixels()
        PixelOps.brush(px, 32, 32, 8f, 8f, 2f, red, 1f)
        PixelOps.brush(px, 32, 32, 20f, 20f, 2f, red, 1f)
        assertEquals(255, Colors.a(d.pixelAt(8, 8)))

        PixelOps.brush(px, 32, 32, 8f, 8f, 2f, 0, 1f, erase = true)
        assertEquals("erased pixels are fully transparent", 0, Colors.a(d.pixelAt(8, 8)))
        assertEquals("the eraser only clears where the user painted", 255, Colors.a(d.pixelAt(20, 20)))
    }

    @Test
    fun floodFillRespectsItsToleranceAndRange() {
        val d = doc()
        val px = d.pixels()
        PixelOps.rect(px, 32, 32, 0, 0, 15, 31, red, true)
        val filled = PixelOps.fill(px, 32, 32, 4, 4, green, tolerance = 0, contiguous = true)
        assertTrue("fill should paint the red block", filled > 400)
        assertEquals(green, d.pixelAt(4, 4))
        assertEquals("fill must not leak into the empty half", 0, d.pixelAt(20, 4))
    }

    @Test
    fun colourIsolationErasesExactlyThePickedColour() {
        val d = doc()
        val px = d.pixels()
        PixelOps.rect(px, 32, 32, 0, 0, 31, 15, red, true)
        PixelOps.rect(px, 32, 32, 0, 16, 31, 31, blue, true)

        val erased = PixelOps.isolateColor(px, 32, 32, 5, 5, red, tolerance = 0, replacement = 0)
        assertTrue("something was erased", erased > 0)
        assertEquals("red gone", 0, d.pixelAt(5, 5))
        assertEquals("blue kept", blue, d.pixelAt(5, 20))
    }

    @Test
    fun manualRemovalMaskOnlyCutsWhatTheUserPainted() {
        val d = doc()
        val px = d.pixels()
        PixelOps.rect(px, 32, 32, 0, 0, 31, 31, red, true)

        // Nothing is removed until the user paints the mask: this is manual background removal.
        PixelOps.applyRemovalMask(px, 32, 32, FloatArray(32 * 32))
        assertEquals(255, Colors.a(d.pixelAt(16, 16)))

        val mask = FloatArray(32 * 32)
        for (y in 0 until 32) for (x in 0 until 8) mask[y * 32 + x] = 1f
        PixelOps.applyRemovalMask(px, 32, 32, mask)
        assertEquals("painted area removed", 0, Colors.a(d.pixelAt(0, 0)))
        assertEquals("unpainted area kept", 255, Colors.a(d.pixelAt(16, 16)))
        assertEquals(red, d.pixelAt(16, 16))
    }

    @Test
    fun partialMaskSoftensInsteadOfDeleting() {
        val d = doc(8, 8)
        val px = d.pixels()
        PixelOps.rect(px, 8, 8, 0, 0, 7, 7, red, true)
        val mask = FloatArray(64) { 0.5f }
        PixelOps.applyRemovalMask(px, 8, 8, mask)
        val alpha = Colors.a(d.pixelAt(3, 3))
        assertTrue("half painted mask halves alpha", alpha in 100..160)
        assertNotEquals(0, alpha)
    }

    // ------------------------------------------------------------------ frames

    @Test
    fun frameReelEditsAndUndoWork() {
        val d = doc()
        assertEquals(1, d.frameCount())
        d.addFrame()
        assertEquals(2, d.frameCount())
        d.addFrame()
        assertEquals(3, d.frameCount())

        d.select(1)
        d.beginStroke()
        PixelOps.plot(d.pixels(), 32, 32, 3, 3, red)
        d.endStroke()
        assertEquals(red, d.pixelAt(3, 3))

        assertTrue("undo removes the stroke", d.undo())
        assertEquals(0, d.pixelAt(3, 3))
        assertTrue("redo restores it", d.redo())
        assertEquals(red, d.pixelAt(3, 3))

        d.select(0)
        assertEquals("frames are independent", 0, d.pixelAt(3, 3))
        d.deleteFrame(2)
        assertEquals(2, d.frameCount())
    }

    @Test
    fun documentSurvivesAJsonRoundTrip() {
        val d = doc(16, 12)
        d.name = "hero"
        d.fps = 12f
        d.loop = false
        d.addFrame()
        d.select(1)
        d.beginStroke()
        PixelOps.rect(d.pixels(), 16, 12, 2, 2, 6, 6, blue, true)
        d.endStroke()

        val back = SpriteDocument.fromJsonText(d.toJsonText())
        assertEquals("hero", back.name)
        assertEquals(16, back.width)
        assertEquals(12, back.height)
        assertEquals(2, back.frameCount())
        assertEquals(12f, back.fps, 1e-6f)
        assertTrue(!back.loop)
        back.select(1)
        assertEquals(blue, back.pixelAt(4, 4))
        assertEquals(0, back.pixelAt(12, 10))
    }

    @Test
    fun resizingKeepsTheExistingArtCentred() {
        val d = doc(16, 16)
        PixelOps.rect(d.pixels(), 16, 16, 6, 6, 9, 9, red, true)
        d.resize(24, 24)
        assertEquals(24, d.width)
        // changeCanvas centres the old buffer: +4 px on both axes.
        assertEquals(red, d.pixelAt(6 + 4, 6 + 4))
    }

    @Test
    fun adoptSizeGuardsAgainstMismatchedBuffers() {
        val d = doc(16, 16)
        d.adoptSize(24, 24)
        assertEquals("buffers do not match, size stays", 16, d.width)
    }

    // ------------------------------------------------------------------ parts

    @Test
    fun partLibraryCoversTheRequestedCategories() {
        val required = listOf("Eyes", "Brows", "Hair", "Facial Hair", "Hats", "Tops", "Shoes", "Wheels", "Lights", "Effects")
        for (category in required) {
            assertTrue("missing category $category", SpriteParts.inCategory(category).isNotEmpty())
        }
        val ids = SpriteParts.all.map { it.id }
        assertEquals("part ids are unique", ids.size, ids.toSet().size)
        assertTrue(SpriteParts.all.size >= 40)
    }

    @Test
    fun everyPartDrawsSomethingAndRespectsThePalette() {
        val palette = PartColors(red, blue, green, 0xFF101010.toInt(), 0xFFFFCC99.toInt())
        for (part in SpriteParts.all) {
            val pixels = SpriteParts.render(part, part.defaultWidth, part.defaultHeight, palette)
            assertTrue("${part.id} draws nothing", pixels.any { Colors.a(it) != 0 })
        }
    }

    @Test
    fun stampingAPartIntoADocumentPaintsIt() {
        val d = doc(64, 64)
        val head = SpriteParts.find("head")!!
        SpriteParts.stampInto(d, head, 32, 32, 1f, PartColors(red, blue, green, 0xFF101010.toInt(), 0xFFFFCC99.toInt()))
        val painted = 64 * 64 - PixelOps.transparentCount(d.pixels())
        assertTrue("head should cover pixels", painted > 20)
    }

    // ------------------------------------------------------------------ colour helpers

    @Test
    fun colourHelpersAgreeWithEachOther() {
        val c = Colors.argb(128, 10, 20, 30)
        assertEquals(128, Colors.a(c))
        assertEquals(10, Colors.r(c))
        assertEquals(20, Colors.g(c))
        assertEquals(30, Colors.b(c))
        assertEquals(c, Colors.parse(Colors.toHex(c)) ?: 0)
        assertTrue(Colors.similar(0xFF102030.toInt(), 0xFF102031.toInt(), 4))
        assertTrue(!Colors.similar(0xFF102030.toInt(), 0xFF203010.toInt(), 4))
    }
}
