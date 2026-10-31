package com.sengine.engine.anim

import com.sengine.engine.math.Mat4
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/** Quaternion xyzw. glTF's right-handed coordinate system is retained on import. */
object Quaternion {
    fun normalize(q: FloatArray): FloatArray {
        val len = sqrt(q.fold(0f) { s, v -> s + v * v })
        return if (len < 1e-8f || !len.isFinite()) floatArrayOf(0f, 0f, 0f, 1f) else FloatArray(4) { q[it] / len }
    }
    fun slerp(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        var dot = (0..3).sumOf { (a[it] * b[it]).toDouble() }.toFloat()
        val sign = if (dot < 0f) -1f else 1f
        dot = abs(dot).coerceAtMost(1f)
        if (dot > 0.9995f) return normalize(FloatArray(4) { a[it] + (b[it] * sign - a[it]) * t })
        val theta = kotlin.math.acos(dot)
        val denom = kotlin.math.sin(theta)
        val wa = kotlin.math.sin((1f - t) * theta) / denom
        val wb = kotlin.math.sin(t * theta) / denom * sign
        return normalize(FloatArray(4) { a[it] * wa + b[it] * wb })
    }
    fun matrix(q: FloatArray): FloatArray {
        val (x, y, z, w) = normalize(q)
        val xx = x*x; val yy = y*y; val zz = z*z; val xy = x*y; val xz = x*z; val yz = y*z
        return floatArrayOf(1-2*(yy+zz), 2*(xy+z*w), 2*(xz-y*w), 0f,
            2*(xy-z*w), 1-2*(xx+zz), 2*(yz+x*w), 0f,
            2*(xz+y*w), 2*(yz-x*w), 1-2*(xx+yy), 0f, 0f, 0f, 0f, 1f)
    }
    fun fromEuler(x: Float, y: Float, z: Float): FloatArray {
        val rx = Math.toRadians(x.toDouble()/2); val ry = Math.toRadians(y.toDouble()/2); val rz = Math.toRadians(z.toDouble()/2)
        val sx = kotlin.math.sin(rx).toFloat(); val cx = kotlin.math.cos(rx).toFloat()
        val sy = kotlin.math.sin(ry).toFloat(); val cy = kotlin.math.cos(ry).toFloat()
        val sz = kotlin.math.sin(rz).toFloat(); val cz = kotlin.math.cos(rz).toFloat()
        return floatArrayOf(sx*cy*cz-cx*sy*sz, cx*sy*cz+sx*cy*sz, cx*cy*sz-sx*sy*cz, cx*cy*cz+sx*sy*sz)
    }
    fun toEuler(value: FloatArray): FloatArray {
        val q = normalize(value); val (x,y,z,w) = q
        val sy = (2*(w*y-z*x)).coerceIn(-1f,1f)
        return if (abs(sy) > 0.99999f) floatArrayOf(0f, Math.toDegrees(kotlin.math.asin(sy.toDouble()).toDouble()).toFloat(), Math.toDegrees(kotlin.math.atan2(2*(w*z-x*y).toDouble(), 1-2*(y*y+z*z).toDouble())).toFloat())
        else floatArrayOf(Math.toDegrees(kotlin.math.atan2(2*(w*x+y*z).toDouble(), 1-2*(x*x+y*y).toDouble())).toFloat(), Math.toDegrees(kotlin.math.asin(sy.toDouble())).toFloat(), Math.toDegrees(kotlin.math.atan2(2*(w*z+x*y).toDouble(), 1-2*(y*y+z*z).toDouble())).toFloat())
    }
}

data class Joint(var name: String, var parent: Int = -1,
    var translation: FloatArray = floatArrayOf(0f,0f,0f),
    var rotation: FloatArray = floatArrayOf(0f,0f,0f,1f),
    var scale: FloatArray = floatArrayOf(1f,1f,1f),
    var inverseBind: FloatArray = Mat4.identity()) {
    internal var hasExplicitInverseBind=false
    fun localMatrix() = compose(translation, rotation, scale)
    companion object {
        fun compose(t: FloatArray, r: FloatArray, s: FloatArray): FloatArray {
            val m = Quaternion.matrix(r)
            for (row in 0..2) { m[row] *= s[0]; m[4+row] *= s[1]; m[8+row] *= s[2] }
            m[12] = t[0]; m[13] = t[1]; m[14] = t[2]
            return m
        }
    }
}

class Skeleton(val joints: List<Joint>) {
    init {
        require(joints.isNotEmpty() && joints.size <= MAX_JOINTS) { "Skeleton must have 1–$MAX_JOINTS joints" }
        require(joints.map { it.name }.distinct().size == joints.size) { "Joint names must be unique" }
        joints.forEachIndexed { i, j -> require(j.parent in -1 until i) { "Joint parents must precede children: ${j.name}" } }
    }
    init {
        // Omitted inverse binds (native clips/editor rigs) are derived from the rest pose.
        val pose=SkeletonPose(this);val world=worldMatrices(pose)
        joints.forEachIndexed{i,j->if(!j.hasExplicitInverseBind){val inv=Mat4.identity();require(Mat4.invert(inv,world[i])){"Singular rest joint transform"};j.inverseBind=inv}}
    }
    fun restPose() = SkeletonPose(this)
    fun worldMatrices(pose: SkeletonPose): Array<FloatArray> {
        val result = Array(joints.size) { Mat4.identity() }
        joints.forEachIndexed { i, j ->
            val local = Joint.compose(pose.translation[i], pose.rotation[i], pose.scale[i])
            if (j.parent >= 0) Mat4.mul(result[i], result[j.parent], local) else System.arraycopy(local,0,result[i],0,16)
        }
        return result
    }
    fun palette(pose: SkeletonPose, out: FloatArray = FloatArray(joints.size*16)): FloatArray {
        val world = worldMatrices(pose)
        for (i in joints.indices) Mat4.mul(out, i*16, world[i], 0, joints[i].inverseBind, 0)
        return out
    }
    fun toJson() = JSONObject().put("schema",1).put("joints", JSONArray().also { arr ->
        joints.forEach { j -> arr.put(JSONObject().put("name",j.name).put("parent",j.parent)
            .put("translation",JSONArray(j.translation.toList())).put("rotation",JSONArray(j.rotation.toList()))
            .put("scale",JSONArray(j.scale.toList())).put("inverseBind",JSONArray(j.inverseBind.toList()))) }
    })
    companion object {
        const val MAX_JOINTS = 128
        const val GPU_JOINTS = 32
        fun fromJson(o: JSONObject): Skeleton = Skeleton(List(o.getJSONArray("joints").length()) { i ->
            val j = o.getJSONArray("joints").getJSONObject(i)
            fun floats(key: String, size: Int, default: Float): FloatArray {
                val a = j.optJSONArray(key) ?: return FloatArray(size) { if (size == 4 && it == 3) 1f else default }
                require(a.length() == size) { "Invalid $key for joint $i" }
                return FloatArray(size) { a.getDouble(it).toFloat().also { v -> require(v.isFinite()) } }
            }
            Joint(j.getString("name"),j.optInt("parent",-1),floats("translation",3,0f),
                Quaternion.normalize(floats("rotation",4,0f)),floats("scale",3,1f),floats("inverseBind",16,0f).also {
                    for (k in 0..3) it[k*4+k] = if (j.has("inverseBind")) it[k*4+k] else 1f
                }).also { it.hasExplicitInverseBind = j.has("inverseBind") }
        })
    }
}

/** Mutable local pose. Sampling never allocates a scene object or changes serialized transforms. */
class SkeletonPose(val skeleton: Skeleton) {
    val translation = Array(skeleton.joints.size) { skeleton.joints[it].translation.copyOf() }
    val rotation = Array(skeleton.joints.size) { skeleton.joints[it].rotation.copyOf() }
    val scale = Array(skeleton.joints.size) { skeleton.joints[it].scale.copyOf() }
    fun copyFrom(other: SkeletonPose) {
        require(other.skeleton === skeleton)
        for (i in translation.indices) {
            System.arraycopy(other.translation[i],0,translation[i],0,other.translation[i].size); System.arraycopy(other.rotation[i],0,rotation[i],0,other.rotation[i].size); System.arraycopy(other.scale[i],0,scale[i],0,other.scale[i].size)
        }
    }
    fun blend(a: SkeletonPose, b: SkeletonPose, t: Float) {
        for (i in translation.indices) for (k in 0..2) {
            translation[i][k] = a.translation[i][k] + (b.translation[i][k]-a.translation[i][k])*t
            scale[i][k] = a.scale[i][k] + (b.scale[i][k]-a.scale[i][k])*t
        }
        for (i in rotation.indices) { val q=Quaternion.slerp(a.rotation[i],b.rotation[i],t); System.arraycopy(q,0,rotation[i],0,4) }
    }
}

data class AnimationTrack(val joint: Int, val path: String, val times: FloatArray, val values: FloatArray, val interpolation: String = "LINEAR") {
    init {
        require(path in setOf("translation","rotation","scale"))
        require(times.isNotEmpty() && times.all { it.isFinite() } && (1 until times.size).all { times[it] > times[it-1] })
        require(values.size == times.size * if (path == "rotation") 4 else 3)
        require(interpolation in setOf("LINEAR","STEP"))
    }
    fun sample(time: Float, out: FloatArray) {
        if (time <= times.first()) { System.arraycopy(values,0,out,0,out.size); return }
        var lo = 0; var hi = times.lastIndex
        while (lo+1 < hi) { val mid = (lo+hi) ushr 1; if (times[mid] <= time) lo = mid else hi = mid }
        if (time >= times.last() || interpolation == "STEP" || times[hi] == times[lo]) {
            System.arraycopy(values,(if (time >= times.last()) hi else lo)*out.size,out,0,out.size); return
        }
        val t = ((time-times[lo])/(times[hi]-times[lo])).coerceIn(0f,1f)
        if (path == "rotation") { val q=Quaternion.slerp(values.sliceArray(lo*4 until lo*4+4),values.sliceArray(hi*4 until hi*4+4),t); System.arraycopy(q,0,out,0,4) }
        else for (k in out.indices) out[k] = values[lo*out.size+k] + (values[hi*out.size+k]-values[lo*out.size+k])*t
    }
    fun toJson() = JSONObject().put("joint",joint).put("path",path).put("interpolation",interpolation)
        .put("times",JSONArray(times.toList())).put("values",JSONArray(values.toList()))
    companion object {
        fun fromJson(o: JSONObject) = AnimationTrack(o.getInt("joint"),o.getString("path"),
            o.getJSONArray("times").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } },
            o.getJSONArray("values").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } },o.optString("interpolation","LINEAR"))
    }
}

data class AnimationEvent(val time: Float, val name: String)
class SkeletalClip(val name: String, val tracks: List<AnimationTrack>, val duration: Float, val events: List<AnimationEvent> = emptyList()) {
    init { require(duration.isFinite() && duration > 0f) { "Clip duration must be positive" }; require(tracks.isNotEmpty()) }
    fun sample(skeleton: Skeleton, time: Float, out: SkeletonPose = SkeletonPose(skeleton)): SkeletonPose {
        for(i in skeleton.joints.indices){System.arraycopy(skeleton.joints[i].translation,0,out.translation[i],0,3);System.arraycopy(skeleton.joints[i].rotation,0,out.rotation[i],0,4);System.arraycopy(skeleton.joints[i].scale,0,out.scale[i],0,3)}
        for (track in tracks) {
            require(track.joint in skeleton.joints.indices) { "Clip targets a missing joint" }
            val target = when(track.path) { "translation" -> out.translation[track.joint]; "scale" -> out.scale[track.joint]; else -> out.rotation[track.joint] }
            track.sample(time.coerceIn(0f,duration),target)
            if (track.path == "rotation") System.arraycopy(Quaternion.normalize(target),0,target,0,Quaternion.normalize(target).size)
        }
        return out
    }
    fun toJson() = JSONObject().put("schema",1).put("name",name).put("duration",duration.toDouble())
        .put("tracks",JSONArray().also { a -> tracks.forEach { a.put(it.toJson()) } })
        .put("events",JSONArray().also { a -> events.forEach { a.put(JSONObject().put("time",it.time.toDouble()).put("name",it.name)) } })
    companion object {
        fun fromJson(o: JSONObject): SkeletalClip {
            require(o.optInt("schema",1) == 1)
            val a = o.getJSONArray("tracks"); val e = o.optJSONArray("events") ?: JSONArray()
            return SkeletalClip(o.optString("name","Clip"),List(a.length()) { AnimationTrack.fromJson(a.getJSONObject(it)) },
                o.getDouble("duration").toFloat(),List(e.length()) { AnimationEvent(e.getJSONObject(it).getDouble("time").toFloat(),e.getJSONObject(it).getString("name")) })
        }
    }
}
