package com.sengine.aibuilder

import com.sengine.aibuilder.engine.GeneratedAssetRecipes
import com.sengine.engine.blueprint.Blueprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

class GeneratedAssetRecipesTest {
    @Test
    fun indexedPixelRecipeProducesTransparentTrueColorPng() {
        val png = GeneratedAssetRecipes.pixelSprite(
            width = 2,
            height = 2,
            palette = listOf("#FFFF0000", "#8000FF00"),
            rows = listOf("01", "1."),
        )
        assertTrue(png.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
        assertEquals(2, readIntBe(png, 16))
        assertEquals(2, readIntBe(png, 20))
        val idatOffset = 8 + 25
        assertEquals("IDAT", String(png.copyOfRange(idatOffset + 4, idatOffset + 8), Charsets.US_ASCII))
        val dataSize = readIntBe(png, idatOffset)
        val rgba = InflaterInputStream(ByteArrayInputStream(png, idatOffset + 8, dataSize)).readBytes()
        assertEquals(18, rgba.size) // 2 scanlines, each with one filter byte and two RGBA pixels
        assertEquals(0, rgba[0].toInt()) // filter type
        assertEquals(255, rgba[1].toInt() and 0xff)
        assertEquals(0, rgba[2].toInt() and 0xff)
        assertEquals(0, rgba[3].toInt() and 0xff)
        assertEquals(255, rgba[4].toInt() and 0xff)
        assertEquals(0, rgba[9].toInt()) // second scanline filter
        assertEquals(128, rgba[13].toInt() and 0xff) // alpha from #AARRGGBB
        assertEquals(0, rgba[17].toInt() and 0xff) // transparent pixel alpha
    }

    @Test
    fun soundIsBoundedPcmWavAndPresetSourcesAreFixed() {
        val wav = GeneratedAssetRecipes.sound("triangle", 880f, 330f, 200, 0.5f)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12), Charsets.US_ASCII))
        assertTrue(wav.size > 44)
        assertTrue(wav.size < 10_000)

        val shader = GeneratedAssetRecipes.shaderPreset("pulse")
        assertTrue(shader.contains("vec4 effect"))
        assertFalse(shader.contains("#include"))
        val graph = Blueprint.parse(GeneratedAssetRecipes.blueprintPreset("platformer_controller"))
        assertTrue(graph.nodes.any { it.type == "Platformer" })
        assertTrue(graph.nodes.any { it.type == "OnUpdate" })
    }

    private fun readIntBe(bytes: ByteArray, offset: Int): Int {
        fun u(index: Int) = bytes[index].toInt() and 0xff
        return (u(offset) shl 24) or (u(offset + 1) shl 16) or (u(offset + 2) shl 8) or u(offset + 3)
    }
}
