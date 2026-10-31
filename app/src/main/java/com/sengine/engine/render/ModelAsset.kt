package com.sengine.engine.render

import com.sengine.engine.anim.Skeleton
import com.sengine.engine.math.Mat4
import com.sengine.project.Project
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A mesh part in model space. Static importer parts have their node transforms baked into vertices. */
class ModelPart(val mesh: Mesh, val material: String, val skinned: Boolean, val name: String)

/** Portable multi-material model. `.smodel` is JSON, deliberately independent of OpenGL. */
class ModelAsset(val name: String, val parts: List<ModelPart>, val skeleton: Skeleton?, val clips: List<String>) {
    val min = FloatArray(3) { axis -> parts.minOf { it.mesh.min[axis] } }
    val max = FloatArray(3) { axis -> parts.maxOf { it.mesh.max[axis] } }
    fun copyWithClips(names:List<String>)=ModelAsset(name,parts,skeleton,names.distinct())
    fun toJson() = JSONObject().put("schema",1).put("name",name)
        .put("parts",JSONArray().also { a -> parts.forEach { part ->
            val vertices = FloatArray(part.mesh.vertexCount*8); part.mesh.buffer.position(0); part.mesh.buffer.get(vertices); part.mesh.buffer.position(0)
            val o = JSONObject().put("name",part.name).put("material",part.material).put("skinned",part.skinned).put("vertices",JSONArray(vertices.toList()))
            part.mesh.skin?.let { val data = FloatArray(it.capacity()); it.position(0); it.get(data); it.position(0); o.put("skin",JSONArray(data.toList())) }
            part.mesh.tangents?.let { val data = FloatArray(it.capacity()); it.position(0); it.get(data); it.position(0); o.put("tangents",JSONArray(data.toList())) }
            a.put(o)
        } }).put("clips",JSONArray(clips)).also { o -> skeleton?.let { o.put("skeleton",it.toJson()) } }
    companion object {
        const val MAX_BYTES = 24L*1024*1024
        fun fromJson(o: JSONObject): ModelAsset {
            require(o.optInt("schema") == 1) { "Unsupported model schema" }
            val a = o.getJSONArray("parts"); require(a.length() in 1..256)
            var vertices = 0
            val parts = List(a.length()) {
                val p = a.getJSONObject(it)
                fun data(key: String, stride: Int): FloatArray? {
                    val arr = p.optJSONArray(key) ?: return null
                    require(arr.length() % stride == 0 && arr.length() <= 3_000_000) { "Invalid mesh attribute $key" }
                    return FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat().also { v -> require(v.isFinite()) } }
                }
                val v = requireNotNull(data("vertices",8)); vertices += v.size/8
                require(vertices <= 250_000) { "Model exceeds the 250,000 vertex mobile budget" }
                val skin = data("skin",8); val tangent = data("tangents",4)
                require(skin == null || skin.size == v.size) { "Skin attribute count mismatch" }
                require(tangent == null || tangent.size == v.size/2) { "Tangent attribute count mismatch" }
                ModelPart(Mesh(v,skin,tangent),p.optString("material"),p.optBoolean("skinned"),p.optString("name","Part $it"))
            }
            val c = o.optJSONArray("clips") ?: JSONArray()
            return ModelAsset(o.optString("name","Model"),parts,o.optJSONObject("skeleton")?.let(Skeleton::fromJson),List(c.length()) { c.getString(it) })
        }
    }
}

/** Stat-checked model cache. A changed/removed asset cannot keep an obsolete GPU-independent mesh alive. */
class ModelStore(private val project: Project) {
    private data class Entry(val stamp: String, val model: ModelAsset?)
    private val cache = object: LinkedHashMap<String, Entry>(16,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 16
    }
    val errors = LinkedHashMap<String,String>()
    fun get(name: String): ModelAsset? {
        if (!name.endsWith(".smodel")) return null
        val file = project.assetFile(name)
        if (!file.isFile || file.length() > ModelAsset.MAX_BYTES) return null
        val stamp = "${file.lastModified()}:${file.length()}"
        cache[name]?.let { if (it.stamp == stamp) return it.model }
        val model = try { ModelAsset.fromJson(JSONObject(file.readText())).also { errors.remove(name) } }
        catch (e: Exception) { errors[name] = e.message ?: "Invalid model"; null }
        cache[name] = Entry(stamp,model)
        return model
    }
    fun save(name: String, model: ModelAsset) {
        require(name.endsWith(".smodel"))
        com.sengine.project.AtomicFiles.writeText(project.assetFile(name),model.toJson().toString())
        cache.remove(name)
    }
}

/** CPU linear-blend skinning for ES 2 and rigs larger than the ES 3 uniform-palette budget. */
object CpuSkinning {
    fun apply(source: Mesh, palette: FloatArray, target: Mesh = Mesh(FloatArray(source.vertexCount*8))) {
        requireNotNull(source.skin) { "CPU skinning needs joint indices and weights" }
        val src = source.buffer; val dst = target.buffer; val weights = source.skin
        src.position(0); dst.position(0); weights.position(0)
        val n = source.vertexCount
        fun transform(buffer: java.nio.FloatBuffer, offset: Int, x: Float, y: Float, z: Float, isNormal: Boolean): FloatArray {
            var rx=0f; var ry=0f; var rz=0f
            for (k in 0..3) {
                val joint = weights.get(offset+k).toInt(); val weight = weights.get(offset+4+k)
                if (weight == 0f) continue
                require(joint*16+14 < palette.size) { "Joint palette index out of range" }
                val j=joint*16
                rx += weight*(palette[j]*x+palette[j+4]*y+palette[j+8]*z+if(isNormal) 0f else palette[j+12])
                ry += weight*(palette[j+1]*x+palette[j+5]*y+palette[j+9]*z+if(isNormal) 0f else palette[j+13])
                rz += weight*(palette[j+2]*x+palette[j+6]*y+palette[j+10]*z+if(isNormal) 0f else palette[j+14])
            }
            if (isNormal) { val len = kotlin.math.sqrt(rx*rx+ry*ry+rz*rz).coerceAtLeast(1e-8f); rx/=len; ry/=len; rz/=len }
            return floatArrayOf(rx,ry,rz)
        }
        for (i in 0 until n) {
            val o=i*8; val p=transform(src,o,src.get(o),src.get(o+1),src.get(o+2),false)
            val normal=transform(src,o,src.get(o+3),src.get(o+4),src.get(o+5),true)
            dst.position(o); dst.put(p); dst.put(normal); dst.put(src.get(o+6)); dst.put(src.get(o+7))
        }
        src.position(0); dst.position(0); weights.position(0)
        source.tangents?.let { t ->
            val out = target.tangents!!.also { it.position(0) }
            for (i in 0 until n) {
                val v=transform(src,i*8,t.get(i*4),t.get(i*4+1),t.get(i*4+2),true)
                out.position(i*4); out.put(v); out.put(t.get(i*4+3))
            }
            t.position(0); out.position(0)
        }
        target.updateBounds()
    }
}
