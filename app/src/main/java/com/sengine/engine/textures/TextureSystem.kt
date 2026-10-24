package com.sengine.engine.textures

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// ─── Texture Import Settings ──────────────────────────────────────────────

/** Professional texture import configuration, similar to Unity's TextureImporter. */
class TextureImportSettings {
    var textureType = 0      // 0=Default, 1=Sprite, 2=UI, 3=NormalMap, 4=SpriteSheet, 5=Atlas
    var spriteMode = 0       // 0=Single, 1=Multiple (sliced)
    var pixelsPerUnit = 100f
    var filterMode = 0       // 0=Point (no filter), 1=Bilinear, 2=Trilinear
    var anisoLevel = 1
    var wrapMode = 0         // 0=Repeat, 1=Clamp, 2=Mirror
    var maxTextureSize = 2048
    var compression = 0      // 0=None, 1=LowQuality, 2=NormalQuality, 3=HighQuality
    var generateMipMaps = false
    var alphaIsTransparency = true
    var readWrite = false
    var sRGB = true
    var npotScale = 0        // 0=None, 1=ToNearest, 2=ToLarger, 3=ToSmaller

    fun toJson(): JSONObject = JSONObject()
        .put("textureType", textureType)
        .put("spriteMode", spriteMode)
        .put("pixelsPerUnit", pixelsPerUnit.toDouble())
        .put("filterMode", filterMode)
        .put("anisoLevel", anisoLevel)
        .put("wrapMode", wrapMode)
        .put("maxTextureSize", maxTextureSize)
        .put("compression", compression)
        .put("generateMipMaps", generateMipMaps)
        .put("alphaIsTransparency", alphaIsTransparency)
        .put("readWrite", readWrite)
        .put("sRGB", sRGB)
        .put("npotScale", npotScale)

    companion object {
        fun fromJson(o: JSONObject): TextureImportSettings {
            val s = TextureImportSettings()
            s.textureType = o.optInt("textureType", 0)
            s.spriteMode = o.optInt("spriteMode", 0)
            s.pixelsPerUnit = o.optDouble("pixelsPerUnit", 100.0).toFloat()
            s.filterMode = o.optInt("filterMode", 0)
            s.anisoLevel = o.optInt("anisoLevel", 1)
            s.wrapMode = o.optInt("wrapMode", 0)
            s.maxTextureSize = o.optInt("maxTextureSize", 2048)
            s.compression = o.optInt("compression", 0)
            s.generateMipMaps = o.optBoolean("generateMipMaps", false)
            s.alphaIsTransparency = o.optBoolean("alphaIsTransparency", true)
            s.readWrite = o.optBoolean("readWrite", false)
            s.sRGB = o.optBoolean("sRGB", true)
            s.npotScale = o.optInt("npotScale", 0)
            return s
        }
    }
}

// ─── Sprite Slice ──────────────────────────────────────────────────────────

/** A rectangular region within a texture atlas or sprite sheet. */
class SpriteSlice(
    var name: String = "",
    var x: Int = 0, var y: Int = 0,
    var width: Int = 0, var height: Int = 0,
    var pivotX: Float = 0.5f, var pivotY: Float = 0.5f,
    var borderL: Int = 0, var borderR: Int = 0,
    var borderT: Int = 0, var borderB: Int = 0,
    var tag: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("x", x).put("y", y)
        .put("width", width).put("height", height)
        .put("pivotX", pivotX.toDouble()).put("pivotY", pivotY.toDouble())
        .put("borderL", borderL).put("borderR", borderR)
        .put("borderT", borderT).put("borderB", borderB)
        .put("tag", tag)

    companion object {
        fun fromJson(o: JSONObject): SpriteSlice = SpriteSlice(
            o.optString("name", ""),
            o.optInt("x", 0), o.optInt("y", 0),
            o.optInt("width", 0), o.optInt("height", 0),
            o.optDouble("pivotX", 0.5).toFloat(), o.optDouble("pivotY", 0.5).toFloat(),
            o.optInt("borderL", 0), o.optInt("borderR", 0),
            o.optInt("borderT", 0), o.optInt("borderB", 0),
            o.optString("tag", "")
        )
    }

    /** UV coordinates within a given texture size. */
    fun uv(texW: Int, texH: Int): FloatArray {
        return floatArrayOf(
            x.toFloat() / texW,       // u0
            (x + width).toFloat() / texW, // u1
            y.toFloat() / texH,       // v0
            (y + height).toFloat() / texH  // v1
        )
    }
}

// ─── Texture Atlas ─────────────────────────────────────────────────────────

/** A packed texture atlas that combines multiple sprites into one texture. */
class TextureAtlas {
    var name: String = ""
    var atlasTexture: String = ""
    var width: Int = 2048
    var height: Int = 2048
    var padding: Int = 2
    var sprites = mutableListOf<SpriteSlice>()

    fun findSprite(name: String): SpriteSlice? = sprites.firstOrNull { it.name == name }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("texture", atlasTexture)
        .put("width", width).put("height", height)
        .put("padding", padding)
        .put("sprites", JSONArray().also { arr -> sprites.forEach { arr.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): TextureAtlas {
            val a = TextureAtlas()
            a.name = o.optString("name", "")
            a.atlasTexture = o.optString("texture", "")
            a.width = o.optInt("width", 2048)
            a.height = o.optInt("height", 2048)
            a.padding = o.optInt("padding", 2)
            val arr = o.optJSONArray("sprites") ?: JSONArray()
            for (i in 0 until arr.length()) a.sprites.add(SpriteSlice.fromJson(arr.getJSONObject(i)))
            return a
        }
    }
}

/** Packs sprite slices into an atlas using a shelf-based algorithm. */
object AtlasPacker {
    data class PackRect(val name: String, val w: Int, val h: Int, var x: Int = 0, var y: Int = 0, val pivotX: Float = 0.5f, val pivotY: Float = 0.5f)

    /** Simple shelf packing. Returns placed rects or null if they don't fit. */
    fun pack(rects: List<PackRect>, atlasW: Int, atlasH: Int, padding: Int = 2): List<PackRect>? {
        val sorted = rects.sortedByDescending { it.h }
        var shelfY = 0
        var shelfH = 0
        var shelfX = 0

        for (r in sorted) {
            if (shelfX + r.w + padding > atlasW) {
                shelfY += shelfH + padding
                shelfH = 0
                shelfX = 0
            }
            if (shelfY + r.h + padding > atlasH) return null // Doesn't fit
            r.x = shelfX
            r.y = shelfY
            shelfX += r.w + padding
            shelfH = maxOf(shelfH, r.h)
        }
        return sorted
    }

    /** Auto-generate atlas name for a sprite. */
    fun spriteName(texture: String, index: Int): String {
        val base = texture.substringBeforeLast('.')
        return "${base}_$index"
    }
}

// ─── Material System ──────────────────────────────────────────────────────

/** A professional material with multiple texture channels and parameters. */
class MaterialData {
    var name: String = ""
    var shader = "Default"
    var renderQueue = 2000
    val properties = HashMap<String, Any>()
    val textureSlots = HashMap<String, String>()

    // Common material properties
    var mainTexture: String = ""
    var mainColor: Int = 0xFFFFFFFF.toInt()
    var metallic: Float = 0f
    var roughness: Float = 0.5f
    var normalMap: String = ""
    var normalStrength: Float = 1f
    var occlusionMap: String = ""
    var occlusionStrength: Float = 1f
    var emissiveMap: String = ""
    var emissiveColor: Int = 0xFF000000.toInt()
    var emissiveIntensity: Float = 0f
    var alphaCutoff: Float = 0.5f
    var smoothness: Float = 0.5f
    var culling = 0 // 0=Back, 1=Front, 2=Off
    var zWrite = true
    var zTest = 0   // 0=LEqual, 1=Always, 2=Less, 3=Equal
    var blendMode = 0 // 0=Opaque, 1=AlphaBlend, 2=Premultiplied, 3=Additive, 4=Multiply

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("name", name).put("shader", shader).put("renderQueue", renderQueue)
            .put("mainTexture", mainTexture)
            .put("mainColor", String.format("#%08X", mainColor))
            .put("metallic", metallic.toDouble())
            .put("roughness", roughness.toDouble())
            .put("normalMap", normalMap)
            .put("normalStrength", normalStrength.toDouble())
            .put("occlusionMap", occlusionMap)
            .put("occlusionStrength", occlusionStrength.toDouble())
            .put("emissiveMap", emissiveMap)
            .put("emissiveColor", String.format("#%08X", emissiveColor))
            .put("emissiveIntensity", emissiveIntensity.toDouble())
            .put("alphaCutoff", alphaCutoff.toDouble())
            .put("smoothness", smoothness.toDouble())
            .put("culling", culling)
            .put("zWrite", zWrite)
            .put("zTest", zTest)
            .put("blendMode", blendMode)
        o.put("textures", JSONObject().also { j -> textureSlots.forEach { (k, v) -> j.put(k, v) } })
        o.put("properties", JSONObject().also { j ->
            properties.forEach { (k, v) ->
                when (v) {
                    is Float -> j.put(k, v.toDouble())
                    is Int -> j.put(k, v)
                    is Boolean -> j.put(k, v)
                    is String -> j.put(k, v)
                }
            }
        })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): MaterialData {
            val m = MaterialData()
            m.name = o.optString("name", "")
            m.shader = o.optString("shader", "Default")
            m.renderQueue = o.optInt("renderQueue", 2000)
            m.mainTexture = o.optString("mainTexture", "")
            m.mainColor = try { Component.parseColor(o.optString("mainColor", "#FFFFFFFF")) } catch (_: Exception) { 0xFFFFFFFF.toInt() }
            m.metallic = o.optDouble("metallic", 0.0).toFloat()
            m.roughness = o.optDouble("roughness", 0.5).toFloat()
            m.normalMap = o.optString("normalMap", "")
            m.normalStrength = o.optDouble("normalStrength", 1.0).toFloat()
            m.occlusionMap = o.optString("occlusionMap", "")
            m.occlusionStrength = o.optDouble("occlusionStrength", 1.0).toFloat()
            m.emissiveMap = o.optString("emissiveMap", "")
            m.emissiveColor = try { Component.parseColor(o.optString("emissiveColor", "#FF000000")) } catch (_: Exception) { 0xFF000000.toInt() }
            m.emissiveIntensity = o.optDouble("emissiveIntensity", 0.0).toFloat()
            m.alphaCutoff = o.optDouble("alphaCutoff", 0.5).toFloat()
            m.smoothness = o.optDouble("smoothness", 0.5).toFloat()
            m.culling = o.optInt("culling", 0)
            m.zWrite = o.optBoolean("zWrite", true)
            m.zTest = o.optInt("zTest", 0)
            m.blendMode = o.optInt("blendMode", 0)
            val texObj = o.optJSONObject("textures") ?: JSONObject()
            for (k in texObj.keys()) m.textureSlots[k] = texObj.getString(k)
            val propObj = o.optJSONObject("properties") ?: JSONObject()
            for (k in propObj.keys()) {
                val v = propObj.get(k)
                m.properties[k] = v
            }
            return m
        }
    }
}

// ─── Texture Component ────────────────────────────────────────────────────

class TextureComponent : Component() {
    override val type = "TextureImporter"
    var importSettings = TextureImportSettings()
    var slices = mutableListOf<SpriteSlice>()
    var atlas = ""
    var overrideAtlas = ""

    override fun props() = listOf(
        Prop.Choice("Texture Type", listOf("Default", "Sprite", "UI", "Normal Map", "Sprite Sheet", "Atlas"), { importSettings.textureType }, { importSettings.textureType = it }),
        Prop.Choice("Sprite Mode", listOf("Single", "Multiple"), { importSettings.spriteMode }, { importSettings.spriteMode = it }),
        Prop.F("Pixels Per Unit", { importSettings.pixelsPerUnit }, { importSettings.pixelsPerUnit = it.coerceAtLeast(1f) }, 10f),
        Prop.Choice("Filter Mode", listOf("Point", "Bilinear", "Trilinear"), { importSettings.filterMode }, { importSettings.filterMode = it }),
        Prop.Choice("Wrap Mode", listOf("Repeat", "Clamp", "Mirror"), { importSettings.wrapMode }, { importSettings.wrapMode = it }),
        Prop.I("Max Size", { importSettings.maxTextureSize }, { importSettings.maxTextureSize = it.coerceAtLeast(32) }),
        Prop.Choice("Compression", listOf("None", "Low", "Normal", "High"), { importSettings.compression }, { importSettings.compression = it }),
        Prop.B("Generate MipMaps", { importSettings.generateMipMaps }, { importSettings.generateMipMaps = it }),
        Prop.B("Alpha Is Transparency", { importSettings.alphaIsTransparency }, { importSettings.alphaIsTransparency = it }),
        Prop.B("sRGB", { importSettings.sRGB }, { importSettings.sRGB = it }),
        Prop.Choice("Blend Mode", listOf("Opaque", "Alpha Blend", "Premultiplied", "Additive", "Multiply"), { 0 }, { }),
    )
}
