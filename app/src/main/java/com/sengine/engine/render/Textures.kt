package com.sengine.engine.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.sengine.project.Project
import java.io.File

class Tex(val id: Int, val w: Int, val h: Int)

/**
 * GL texture caches for image assets and rendered text. Must only be used on the GL thread.
 *
 * GPU memory is bounded by [TextureBudget]: when a new upload would exceed [budgetBytes], the least
 * recently used textures that were not used this frame are deleted. Image files are stat-checked at
 * most once per frame, so a sprite draw does not touch the file system.
 */
class TextureCache(private val project: Project, budgetBytes: Long = DEFAULT_BUDGET_BYTES) {

    companion object {
        /** Default GPU texture budget. Chosen, not measured; tune on the low-memory target. */
        const val DEFAULT_BUDGET_BYTES = 64L * 1024 * 1024
        private const val IMG = "i:"
        private const val TXT = "t:"
    }

    private val budget = TextureBudget(budgetBytes)
    private val images = HashMap<String, Tex?>()
    private val stamps = HashMap<String, Long>()
    /** Frame number in which each image's file was last stat-checked. */
    private val stampChecked = HashMap<String, Long>()
    private val texts = HashMap<String, Tex>()

    /** Set by the renderer. Receives cache warnings (level 1) for the engine log. */
    var onLog: (Int, String) -> Unit = { _, _ -> }

    /** Bytes of GPU memory held by this cache (RGBA8888, no mipmaps). */
    val usedBytes: Long get() = budget.usedBytes

    /** Number of resident textures. */
    val residentCount: Int get() = budget.count

    /** Call once at the start of each frame. */
    fun beginFrame() = budget.nextFrame()

    /**
     * Forgets all handles without deleting them. Used after a context loss, when the old GL context
     * already owns the objects.
     */
    fun clear() {
        images.clear(); stamps.clear(); stampChecked.clear(); texts.clear(); budget.clear()
    }

    /** Import settings for an asset (filtering, wrap, mipmaps, max size). */
    fun settingsFor(name: String): com.sengine.project.TextureSettings =
        project.textureSettings().get(name)

    fun image(name: String): Tex? {
        if (name.isBlank()) return null
        val key = IMG + name
        val known = images.containsKey(name)
        if (known && stampChecked[name] == budget.frame) {
            budget.touch(key)
            return images[name]
        }
        val f: File = project.assetFile(name)
        val stamp = f.lastModified()
        stampChecked[name] = budget.frame
        if (known && stamps[name] == stamp) {
            budget.touch(key)
            return images[name]
        }
        images[name]?.let { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) }
        budget.remove(key)
        var bmp = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
        var tex: Tex? = null
        if (bmp != null) {
            val settings = settingsFor(name)
            val (tw, th) = settings.targetSize(bmp.width, bmp.height).let { it[0] to it[1] }
            if (tw != bmp.width || th != bmp.height) {
                val scaled = Bitmap.createScaledBitmap(bmp, tw, th, !settings.pixelArt)
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            tex = uploadBudgeted(key, bmp, settings)
        }
        bmp?.recycle()
        images[name] = tex
        stamps[name] = stamp
        return tex
    }

    fun text(text: String, bold: Boolean, align: Int): Tex {
        val textKey = "$bold|$align|$text"
        val key = TXT + textKey
        texts[textKey]?.let { budget.touch(key); return it }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.textSize = 72f
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val lines = text.split('\n')
        val fm = paint.fontMetrics
        val lineH = (fm.descent - fm.ascent)
        val widths = lines.map { paint.measureText(it) }
        val w = (widths.maxOrNull() ?: 1f).coerceAtLeast(1f).toInt() + 8
        val h = (lineH * lines.size).toInt().coerceAtLeast(1) + 4
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        for ((i, line) in lines.withIndex()) {
            val x = when (align) {
                0 -> 4f
                2 -> w - 4f - widths[i]
                else -> (w - widths[i]) / 2f
            }
            c.drawText(line, x, 2f - fm.ascent + i * lineH, paint)
        }
        val tex = uploadBudgeted(key, bmp, nearest = false)
        bmp.recycle()
        texts[textKey] = tex
        return tex
    }

    /** Evicts what the budget requires, then uploads [bmp] and records it. */
    private fun uploadBudgeted(key: String, bmp: Bitmap, nearest: Boolean): Tex =
        uploadBudgeted(key, bmp, com.sengine.project.TextureSettings().also {
            if (nearest) it.usePixelArt() else it.useSmooth()
        })

    private fun uploadBudgeted(key: String, bmp: Bitmap, settings: com.sengine.project.TextureSettings): Tex {
        val bytes = bmp.width.toLong() * bmp.height * 4
        val plan = budget.plan(bytes)
        for (victim in plan.victims) evict(victim)
        if (plan.overBudget) {
            onLog(1, "Texture cache over budget: ${budget.usedBytes / 1024} KB in use, budget " +
                "${budget.maxBytes / 1024} KB, new texture ${bytes / 1024} KB. Textures used this frame are not evicted.")
        }
        val tex = upload(bmp, settings)
        budget.add(key, bytes)
        return tex
    }

    /** Deletes one resident texture and forgets its handle, so it is reloaded on next use. */
    private fun evict(key: String) {
        budget.remove(key)
        if (key.startsWith(IMG)) {
            val name = key.substring(IMG.length)
            images.remove(name)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) }
            stamps.remove(name)
            stampChecked.remove(name)
        } else {
            val textKey = key.substring(TXT.length)
            texts.remove(textKey)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) }
        }
    }

    private fun upload(bmp: Bitmap, settings: com.sengine.project.TextureSettings): Tex {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, settings.glMinFilter())
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, settings.glMagFilter())
        val wrap = settings.glWrap()
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        if (settings.mipmaps) {
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            if (settings.anisotropic > 0) applyAnisotropy(settings.anisotropic)
        }
        return Tex(ids[0], bmp.width, bmp.height)
    }

    private var maxAnisotropy: Float = -1f

    /** Applies anisotropic filtering when the driver supports EXT_texture_filter_anisotropic. */
    private fun applyAnisotropy(requested: Int) {
        if (maxAnisotropy < 0f) {
            val ext = try { GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: "" } catch (_: Throwable) { "" }
            maxAnisotropy = if (ext.contains("GL_EXT_texture_filter_anisotropic")) {
                val v = FloatArray(1)
                GLES20.glGetFloatv(0x84FF, v, 0)
                if (v[0] > 1f) v[0] else 0f
            } else 0f
        }
        if (maxAnisotropy <= 1f) return
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, 0x84FE, requested.toFloat().coerceIn(1f, maxAnisotropy))
    }
}
