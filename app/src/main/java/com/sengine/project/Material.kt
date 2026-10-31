package com.sengine.project

import com.sengine.engine.core.Component
import org.json.JSONObject
import java.io.File

/** Reusable, renderer-independent metallic/roughness material. Texture references are project assets. */
data class Material(
    var name: String = "New Material",
    var baseColor: Int = 0xFFB8C5D5.toInt(),
    var metallic: Float = 0f,
    var roughness: Float = 0.5f,
    var baseColorMap: String = "",
    var normalMap: String = "",
    var metallicRoughnessMap: String = "",
    var occlusionMap: String = "",
    var emissiveMap: String = "",
    var normalStrength: Float = 1f,
    var occlusionStrength: Float = 1f,
    var emissiveColor: Int = 0xFF000000.toInt(),
    var emissiveStrength: Float = 0f,
    var tilingX: Float = 1f,
    var tilingY: Float = 1f,
    var offsetX: Float = 0f,
    var offsetY: Float = 0f,
    var alphaMode: Int = 0,
    var alphaCutoff: Float = 0.5f,
    var doubleSided: Boolean = false,
    var unlit: Boolean = false,
) {
    fun copyMaterial() = copy()
    fun normalized() {
        metallic = metallic.coerceIn(0f, 1f); roughness = roughness.coerceIn(0.04f, 1f)
        normalStrength = normalStrength.coerceIn(0f, 2f); occlusionStrength = occlusionStrength.coerceIn(0f, 1f)
        emissiveStrength = emissiveStrength.coerceIn(0f, 16f)
        tilingX = tilingX.coerceIn(0.01f, 128f); tilingY = tilingY.coerceIn(0.01f, 128f)
        alphaCutoff = alphaCutoff.coerceIn(0f, 1f); alphaMode = alphaMode.coerceIn(0, 2)
    }
    fun toJson() = JSONObject().apply {
        put("schema", 1); put("name", name); put("model", "metallic-roughness")
        put("baseColor", hex(baseColor)); put("metallic", metallic.toDouble()); put("roughness", roughness.toDouble())
        put("baseColorMap", baseColorMap); put("normalMap", normalMap)
        put("metallicRoughnessMap", metallicRoughnessMap); put("occlusionMap", occlusionMap); put("emissiveMap", emissiveMap)
        put("normalStrength", normalStrength.toDouble()); put("occlusionStrength", occlusionStrength.toDouble())
        put("emissiveColor", hex(emissiveColor)); put("emissiveStrength", emissiveStrength.toDouble())
        put("tilingX", tilingX.toDouble()); put("tilingY", tilingY.toDouble())
        put("offsetX", offsetX.toDouble()); put("offsetY", offsetY.toDouble())
        put("alphaMode", listOf("Opaque", "Mask", "Blend")[alphaMode.coerceIn(0, 2)])
        put("alphaCutoff", alphaCutoff.toDouble()); put("doubleSided", doubleSided); put("unlit", unlit)
    }
    val isTransparent get() = alphaMode == 2 || (baseColor ushr 24) < 255
    val textureNames get() = listOf(baseColorMap, normalMap, metallicRoughnessMap, occlusionMap, emissiveMap).filter(String::isNotBlank).distinct()

    companion object {
        const val EXTENSION = ".mat"
        fun hex(c: Int) = String.format("#%08X", c)
        fun fromJson(o: JSONObject): Material {
            require(o.optInt("schema", 1) == 1) { "Unsupported material schema" }
            fun color(k: String, fallback: Int) = try { Component.parseColor(o.optString(k, hex(fallback))) } catch (_: Exception) { fallback }
            fun f(k: String, fallback: Float) = o.optDouble(k, fallback.toDouble()).toFloat().takeIf { it.isFinite() } ?: fallback
            return Material(
                name = o.optString("name", "Material"), baseColor = color("baseColor", 0xFFB8C5D5.toInt()),
                metallic = f("metallic", 0f), roughness = f("roughness", 0.5f),
                baseColorMap = o.optString("baseColorMap"), normalMap = o.optString("normalMap"),
                metallicRoughnessMap = o.optString("metallicRoughnessMap"), occlusionMap = o.optString("occlusionMap"),
                emissiveMap = o.optString("emissiveMap"), normalStrength = f("normalStrength", 1f),
                occlusionStrength = f("occlusionStrength", 1f), emissiveColor = color("emissiveColor", 0),
                emissiveStrength = f("emissiveStrength", 0f), tilingX = f("tilingX", 1f), tilingY = f("tilingY", 1f),
                offsetX = f("offsetX", 0f), offsetY = f("offsetY", 0f),
                alphaMode = listOf("Opaque", "Mask", "Blend").indexOf(o.optString("alphaMode", "Opaque")).coerceAtLeast(0),
                alphaCutoff = f("alphaCutoff", 0.5f), doubleSided = o.optBoolean("doubleSided"), unlit = o.optBoolean("unlit"),
            ).also { it.normalized() }
        }
        fun presets(): List<Material> = listOf(
            Material("Ceramic", 0xFFE6E9ED.toInt(), roughness = 0.22f),
            Material("Brushed Steel", 0xFF9AAABF.toInt(), metallic = 1f, roughness = 0.3f),
            Material("Copper", 0xFFD58C61.toInt(), metallic = 1f, roughness = 0.24f),
            Material("Rough Concrete", 0xFF8D939B.toInt(), roughness = 0.92f),
            Material("Signal Emitter", 0xFF183B43.toInt(), roughness = 0.3f, emissiveColor = 0xFF42E8CE.toInt(), emissiveStrength = 2.5f),
        )
    }
}

/** Small stat-checked material cache; no GPU handles live in project data. */
class MaterialStore(private val project: Project) {
    private val cache = LinkedHashMap<String, Pair<String, Material>>()
    private val previews = java.util.concurrent.ConcurrentHashMap<String, Material>()
    fun preview(name:String, material:Material?) { if(material==null)previews.remove(name) else previews[name]=material.copyMaterial() }
    fun clear() { cache.clear() }
    fun get(name: String): Material? {
        if (name.isBlank()) return null
        previews[name]?.let { return it }
        val file = project.assetFile(name)
        if (!file.isFile || file.length() > 1024 * 1024) return null
        return try {
            val stamp = "${file.lastModified()}:${file.length()}"
            cache[name]?.let { if (it.first == stamp) return it.second }
            val value = Material.fromJson(JSONObject(file.readText()))
            if (cache.size >= 128) cache.remove(cache.keys.first())
            cache[name] = stamp to value
            value
        } catch (_: Exception) { null }
    }
    fun save(name: String, material: Material) {
        require(name.endsWith(Material.EXTENSION)) { "Material files must end in .mat" }
        material.normalized()
        AtomicFiles.writeText(project.assetFile(name), material.toJson().toString(2))
        cache.remove(name)
    }
}
