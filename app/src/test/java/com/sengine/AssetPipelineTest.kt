package com.sengine

import com.sengine.project.AtlasPacker
import com.sengine.project.TextureSettings
import com.sengine.project.TextureSettingsStore
import com.sengine.project.ZipAssetImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Asset pipeline: zip asset packs, atlas packing and per-texture import settings. */
class AssetPipelineTest {

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ zip packs

    @Test
    fun zipPackKeepsSupportedAssetsAndSkipsJunk() {
        val bytes = zip(
            "images/rock.png" to byteArrayOf(1, 2, 3, 4),
            "scripts/Player.js" to "var speed = 3;".toByteArray(),
            "__MACOSX/._rock.png" to byteArrayOf(9),
            "Thumbs.db" to byteArrayOf(9),
            "model.obj" to "v 0 0 0".toByteArray(),
        )
        val plan = ZipAssetImporter.read(bytes)
        val names = plan.entries.map { it.name }.sorted()
        assertEquals(listOf("Player.js", "model.obj", "rock.png"), names)
        assertTrue("junk is reported", plan.skipped.any { it.contains("Thumbs.db") })
        assertTrue("mac metadata is reported", plan.skipped.any { it.contains("__MACOSX") })
        assertEquals(4 + "var speed = 3;".length + "v 0 0 0".length, plan.totalBytes)
    }

    @Test
    fun zipPackRejectsTraversalAndDuplicates() {
        val bytes = zip(
            "../evil.js" to "alert(1)".toByteArray(),
            "/abs/path/root.js" to "x".toByteArray(),
            "a/rock.png" to byteArrayOf(1),
            "b/rock.png" to byteArrayOf(2),
        )
        val plan = ZipAssetImporter.read(bytes)
        assertEquals("only the two safe images are imported", 2, plan.entries.size)
        assertTrue(plan.skipped.any { it.contains("evil.js") })
        assertTrue(plan.skipped.any { it.contains("root.js") })
        assertEquals("duplicate names get a suffix", 2, plan.entries.map { it.name }.toSet().size)
        assertTrue(plan.entries.any { it.name == "rock.png" })
        assertTrue(plan.entries.any { it.name == "rock_1.png" })
    }

    @Test
    fun zipPackNeverThrowsOnGarbage() {
        val plan = ZipAssetImporter.read(byteArrayOf(0, 1, 2, 3, 4))
        assertTrue(plan.isEmpty)
        assertEquals(listOf("not a zip archive"), plan.skipped)
        assertTrue(ZipAssetImporter.read(ByteArray(0)).isEmpty)
    }

    @Test
    fun zipPackHonoursTheSizeLimit() {
        val big = ByteArray(4096) { 7 }
        val bytes = zip("big.png" to big, "small.png" to byteArrayOf(1, 2))
        val plan = ZipAssetImporter.read(bytes, ZipAssetImporter.Options(maxEntryBytes = 1024))
        assertEquals(1, plan.entries.size)
        assertEquals("small.png", plan.entries[0].name)
        assertTrue(plan.skipped.any { it.contains("big.png") })
    }

    @Test
    fun sheetImportGeneratesAMatchingAnimationClip() {
        val (name, json) = ZipAssetImporter.sheet("hero.png", columns = 4, rows = 2, fps = 10f)
        assertEquals("hero.anim", name)
        val clip = org.json.JSONObject(json)
        assertEquals("hero.png", clip.getString("texture"))
        assertEquals(4, clip.getInt("columns"))
        assertEquals(2, clip.getInt("rows"))
        assertEquals(8, clip.getJSONArray("frames").length())
        assertEquals(10.0, clip.getDouble("fps"), 1e-6)
    }

    // ------------------------------------------------------------------ atlases

    @Test
    fun atlasPacksEverythingInsideThePageWithoutOverlap() {
        val items = listOf(
            AtlasPacker.Item("a", 64, 32),
            AtlasPacker.Item("b", 100, 100),
            AtlasPacker.Item("c", 16, 16),
            AtlasPacker.Item("d", 200, 40),
        )
        val result = AtlasPacker.pack(items)
        assertTrue("nothing overflows", result.overflow.isEmpty())
        assertEquals(1, result.pages.size)
        val page = result.pages[0]
        assertEquals(items.size, page.placements.size)
        for (p in page.placements) {
            assertTrue("${p.name} fits horizontally", p.x >= 0 && p.x + p.width <= page.width)
            assertTrue("${p.name} fits vertically", p.y >= 0 && p.y + p.height <= page.height)
        }
        for (i in page.placements.indices) for (k in i + 1 until page.placements.size) {
            val a = page.placements[i]
            val b = page.placements[k]
            val overlap = a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
            assertFalse("${a.name} overlaps ${b.name}", overlap)
        }
    }

    @Test
    fun atlasUvIsFlippedForOpenGl() {
        val page = AtlasPacker.pack(listOf(AtlasPacker.Item("a", 32, 32))).pages[0]
        val p = page.placements[0]
        val uv = p.uv(page.width, page.height)
        assertEquals(p.x.toFloat() / page.width, uv[0], 1e-6f)
        assertEquals(1f - (p.y + p.height).toFloat() / page.height, uv[1], 1e-6f)
        assertTrue("v0 is the bottom edge", uv[1] < uv[3])
    }

    @Test
    fun atlasReportsOversizedItems() {
        val result = AtlasPacker.pack(listOf(AtlasPacker.Item("huge", 4096, 4096)), maxSize = 1024)
        assertTrue(result.pages.isEmpty())
        assertEquals("huge", result.overflow.single().name)
    }

    // ------------------------------------------------------------------ texture settings

    @Test
    fun pixelArtAndSmoothPresetsMapToTheirGlFilters() {
        val pixel = TextureSettings()
        pixel.usePixelArt()
        assertTrue(pixel.pixelArt)
        assertFalse(pixel.mipmaps)
        assertEquals(0x2600, pixel.glMagFilter())
        assertEquals(0x2600, pixel.glMinFilter())

        val smooth = TextureSettings().also { it.useSmooth() }
        assertTrue(smooth.mipmaps)
        assertEquals(0x2601, smooth.glMagFilter())
        assertEquals(0x2703, smooth.glMinFilter())
        assertTrue("smooth textures request anisotropy", smooth.anisotropic > 0)
        assertEquals(0x812F, smooth.glWrap())
        assertEquals(0x8370, TextureSettings().also { it.wrap = 2 }.glWrap())
    }

    @Test
    fun textureSettingsRoundTripThroughTheStore() {
        val store = TextureSettingsStore()
        store.set("rock.png", TextureSettings().also { it.useSmooth(); it.maxSize = 1024; it.powerOfTwo = true })
        store.set("hero.png", TextureSettings().also { it.usePixelArt() })

        val back = TextureSettingsStore()
        back.loadFromText(store.saveText())
        assertEquals(2, back.size())
        assertTrue(back.get("rock.png").mipmaps)
        assertEquals(1024, back.get("rock.png").maxSize)
        assertTrue(back.get("rock.png").powerOfTwo)
        assertTrue(back.get("hero.png").pixelArt)
        assertFalse(back.get("hero.png").mipmaps)
    }

    @Test
    fun textureSettingsTargetSizeHonoursLimits() {
        val s = TextureSettings()
        s.maxSize = 256
        val (w, h) = s.targetSize(2048, 1024).toList()
        assertEquals(256, w)
        assertEquals(128, h)

        s.powerOfTwo = true
        s.maxSize = 0
        assertEquals(listOf(2048, 1024), s.targetSize(2048, 1024).toList())
        assertEquals(listOf(64, 64), s.targetSize(50, 40).toList())
    }

    @Test
    fun jpgAndWebpDefaultsToSmoothFiltering() {
        assertFalse(TextureSettings.forFile("photo.jpg").pixelArt)
        assertFalse(TextureSettings.forFile("photo.webp").pixelArt)
        assertTrue("png is treated as pixel art by default", TextureSettings.forFile("sprite.png").pixelArt)
    }
}
