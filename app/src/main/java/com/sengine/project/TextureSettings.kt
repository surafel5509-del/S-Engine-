package com.sengine.project

import org.json.JSONObject

/**
 * Per-asset texture import settings, stored with the project so the same source image can be tuned
 * for crisp pixel art or smooth painted art without touching the scene.
 *
 * The renderer reads these when uploading a texture: sampler filtering, wrap mode, mipmaps,
 * anisotropic filtering, power-of-two normalisation (older mobile GPUs require it for repeat +
 * mipmaps) and the maximum size a huge photo is downscaled to.
 */
class TextureSettings {
    /** 0 = nearest (pixel art), 1 = linear, 2 = linear + mipmaps (trilinear). */
    var filter = 0
    /** 0 = clamp to edge, 1 = repeat, 2 = mirrored repeat. */
    var wrap = 0
    var mipmaps = false
    var anisotropic = 0
    /** Longest side in pixels; 0 keeps the source size. */
    var maxSize = 0
    var powerOfTwo = false
    /** Import scale multiplier applied before upload (0.25 = quarter size). */
    var scale = 1f
    /** Marks the texture as pixel art: nearest + no mipmaps + clamped. */
    var pixelArt = true

    fun copy(): TextureSettings = TextureSettings().also {
        it.filter = filter; it.wrap = wrap; it.mipmaps = mipmaps; it.anisotropic = anisotropic
        it.maxSize = maxSize; it.powerOfTwo = powerOfTwo
        it.scale = scale; it.pixelArt = pixelArt
    }

    fun toJson(): JSONObject = JSONObject().also { o ->
        o.put("filter", filter)
        o.put("wrap", wrap)
        o.put("mipmaps", mipmaps)
        o.put("anisotropic", anisotropic)
        o.put("maxSize", maxSize)
        o.put("powerOfTwo", powerOfTwo)
        o.put("scale", scale.toDouble())
        o.put("pixelArt", pixelArt)
    }

    /** Applies the pixel-art preset. */
    fun usePixelArt() {
        pixelArt = true; filter = 0; mipmaps = false; wrap = 0; powerOfTwo = false
    }

    /** Applies the smooth (painted / photo) preset. */
    fun useSmooth() {
        pixelArt = false; filter = 2; mipmaps = true; wrap = 0; anisotropic = 4
    }

    /** Final upload size for a source of [w] x [h]. Always at least 1 px, never larger than the source. */
    fun targetSize(w: Int, h: Int): IntArray {
        var tw = w
        var th = h
        val s = scale.coerceIn(0.01f, 8f)
        if (s != 1f) {
            tw = Math.max(1, Math.round(w * s))
            th = Math.max(1, Math.round(h * s))
        }
        val limit = if (maxSize > 0) maxSize else 0
        if (limit > 0 && maxOf(tw, th) > limit) {
            val k = limit.toFloat() / maxOf(tw, th)
            tw = Math.max(1, Math.round(tw * k))
            th = Math.max(1, Math.round(th * k))
        }
        if (powerOfTwo) {
            tw = AtlasPacker.pot(tw, 1)
            th = AtlasPacker.pot(th, 1)
        }
        return intArrayOf(tw, th)
    }

    /** Effective GL min filter for the current settings. */
    fun glMinFilter(): Int = when {
        mipmaps && filter == 0 -> 0x2700 // GL_NEAREST_MIPMAP_NEAREST
        mipmaps -> 0x2703 // GL_LINEAR_MIPMAP_LINEAR
        filter == 0 -> 0x2600 // GL_NEAREST
        else -> 0x2601 // GL_LINEAR
    }

    fun glMagFilter(): Int = if (filter == 0) 0x2600 else 0x2601

    fun glWrap(): Int = when (wrap) {
        1 -> 0x2901 // GL_REPEAT
        2 -> 0x8370 // GL_MIRRORED_REPEAT
        else -> 0x812F // GL_CLAMP_TO_EDGE
    }

    override fun toString(): String =
        "TextureSettings(pixelArt=$pixelArt, filter=$filter, wrap=$wrap, mipmaps=$mipmaps, aniso=$anisotropic, max=$maxSize)"

    companion object {
        fun fromJson(o: JSONObject?): TextureSettings {
            val s = TextureSettings()
            if (o == null) return s
            s.filter = o.optInt("filter", 0)
            s.wrap = o.optInt("wrap", 0)
            s.mipmaps = o.optBoolean("mipmaps", false)
            s.anisotropic = o.optInt("anisotropic", 0)
            s.maxSize = o.optInt("maxSize", 0)
            s.powerOfTwo = o.optBoolean("powerOfTwo", false)
            s.scale = o.optDouble("scale", 1.0).toFloat()
            s.pixelArt = o.optBoolean("pixelArt", true)
            return s
        }

        /** Defaults that look right for an extension. */
        fun forFile(name: String): TextureSettings {
            val s = TextureSettings()
            val ext = name.substringAfterLast('.', "").lowercase()
            when (ext) {
                "jpg", "jpeg", "webp" -> s.useSmooth()
                else -> s.usePixelArt()
            }
            return s
        }
    }
}

/** Project-wide texture settings keyed by asset file name. Persisted in `textures.json`. */
class TextureSettingsStore {
    private val map = LinkedHashMap<String, TextureSettings>()

    fun get(name: String): TextureSettings = map.getOrPut(name) { TextureSettings.forFile(name) }
    fun set(name: String, settings: TextureSettings) { map[name] = settings }
    fun contains(name: String) = map.containsKey(name)
    fun remove(name: String) { map.remove(name) }
    fun names(): List<String> = map.keys.toList()
    fun size() = map.size
    fun clear() { map.clear() }

    fun toJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v.toJson())
        return o
    }

    fun load(o: JSONObject?) {
        map.clear()
        if (o == null) return
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = TextureSettings.fromJson(o.optJSONObject(k))
        }
    }

    fun loadFromText(text: String?) {
        if (text.isNullOrBlank()) { map.clear(); return }
        load(try { JSONObject(text) } catch (_: Exception) { null })
    }

    fun saveText(): String = toJson().toString(2)
}
