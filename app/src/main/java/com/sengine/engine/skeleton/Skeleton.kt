package com.sengine.engine.skeleton

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import com.sengine.engine.math.Affine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

// ─── Bone ──────────────────────────────────────────────────────────────────

/** A single bone in a skeleton hierarchy. */
class Bone(
    var id: Int = 0,
    var name: String = "root",
    var parentId: Int = -1,
    var localX: Float = 0f,
    var localY: Float = 0f,
    var localRotation: Float = 0f,
    var localScaleX: Float = 1f,
    var localScaleY: Float = 1f,
    var length: Float = 1f,
) {
    /** Cached world transform computed during skeleton update. */
    val worldMatrix = Affine()
    /** Local-to-parent matrix. */
    val localMatrix = Affine()

    fun updateLocal() {
        // localRotation is stored in radians; setTRS expects degrees
        localMatrix.setTRS(localX, localY, Math.toDegrees(localRotation.toDouble()).toFloat(), localScaleX, localScaleY)
    }
}

// ─── Skeleton ──────────────────────────────────────────────────────────────

/** A 2D skeleton: ordered list of bones with parent-child relationships. */
class SkeletonData {
    var name: String = ""
    val bones = mutableListOf<Bone>()
    /** Slot order for draw ordering. */
    val slots = mutableListOf<SlotData>()
    /** Skin name → attachments. */
    val skins = HashMap<String, SkinData>()
    var defaultSkin = "default"

    fun findBone(name: String): Bone? = bones.firstOrNull { it.name == name }
    fun findBone(id: Int): Bone? = bones.firstOrNull { it.id == id }
    fun boneIndex(name: String): Int = bones.indexOfFirst { it.name == name }

    fun addBone(name: String, parentId: Int = -1, x: Float = 0f, y: Float = 0f, rot: Float = 0f): Bone {
        val b = Bone(bones.size, name, parentId, x, y, rot, 1f, 1f, 1f)
        bones.add(b)
        return b
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("bones", JSONArray().also { arr ->
            for (b in bones) {
                arr.put(JSONObject()
                    .put("id", b.id).put("name", b.name).put("parentId", b.parentId)
                    .put("x", b.localX.toDouble()).put("y", b.localY.toDouble())
                    .put("rotation", b.localRotation.toDouble())
                    .put("scaleX", b.localScaleX.toDouble()).put("scaleY", b.localScaleY.toDouble())
                    .put("length", b.length.toDouble()))
            }
        })
        o.put("slots", JSONArray().also { arr ->
            for (s in slots) {
                arr.put(JSONObject()
                    .put("name", s.name).put("boneId", s.boneId)
                    .put("attachment", s.attachment).put("color", String.format("#%08X", s.color)))
            }
        })
        o.put("skins", JSONObject().also { obj ->
            for ((name, skin) in skins) {
                obj.put(name, skin.toJson())
            }
        })
        o.put("defaultSkin", defaultSkin)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SkeletonData {
            val sk = SkeletonData()
            sk.name = o.optString("name", "")
            val bonesArr = o.optJSONArray("bones") ?: JSONArray()
            for (i in 0 until bonesArr.length()) {
                val b = bonesArr.getJSONObject(i)
                val bone = Bone(
                    b.optInt("id", i), b.optString("name", "bone$i"), b.optInt("parentId", -1),
                    b.optDouble("x", 0.0).toFloat(), b.optDouble("y", 0.0).toFloat(),
                    b.optDouble("rotation", 0.0).toFloat(),
                    b.optDouble("scaleX", 1.0).toFloat(), b.optDouble("scaleY", 1.0).toFloat(),
                    b.optDouble("length", 1.0).toFloat()
                )
                sk.bones.add(bone)
            }
            val slotsArr = o.optJSONArray("slots") ?: JSONArray()
            for (i in 0 until slotsArr.length()) {
                val s = slotsArr.getJSONObject(i)
                val color = try {
                    Component.parseColor(s.optString("color", "#FFFFFFFF"))
                } catch (_: Exception) { 0xFFFFFFFF.toInt() }
                sk.slots.add(SlotData(s.optString("name"), s.optInt("boneId", 0), s.optString("attachment", ""), color))
            }
            val skinsObj = o.optJSONObject("skins") ?: JSONObject()
            for (key in skinsObj.keys()) {
                sk.skins[key] = SkinData.fromJson(skinsObj.getJSONObject(key))
            }
            sk.defaultSkin = o.optString("defaultSkin", "default")
            return sk
        }
    }
}

// ─── Slot & Skin ───────────────────────────────────────────────────────────

class SlotData(
    var name: String = "",
    var boneId: Int = 0,
    var attachment: String = "",
    var color: Int = 0xFFFFFFFF.toInt(),
)

class SkinData {
    var name: String = ""
    val attachments = HashMap<String, MutableList<AttachmentData>>()

    fun toJson(): JSONObject {
        val o = JSONObject().put("name", name)
        o.put("attachments", JSONObject().also { obj ->
            for ((slotName, atts) in attachments) {
                obj.put(slotName, JSONArray().also { arr ->
                    for (a in atts) arr.put(a.toJson())
                })
            }
        })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SkinData {
            val s = SkinData()
            s.name = o.optString("name", "")
            val atts = o.optJSONObject("attachments") ?: JSONObject()
            for (key in atts.keys()) {
                val arr = atts.getJSONArray(key)
                val list = mutableListOf<AttachmentData>()
                for (i in 0 until arr.length()) {
                    list.add(AttachmentData.fromJson(arr.getJSONObject(i)))
                }
                s.attachments[key] = list
            }
            return s
        }
    }
}

/** Attachment types: region (sprite), mesh (deformable), bounding box. */
class AttachmentData {
    var name: String = ""
    var type: Int = 0 // 0=Region, 1=Mesh, 2=BoundingBox
    var texture: String = ""
    var x: Float = 0f; var y: Float = 0f
    var width: Float = 1f; var height: Float = 1f
    var rotation: Float = 0f
    var scaleX: Float = 1f; var scaleY: Float = 1f
    var pivotX: Float = 0.5f; var pivotY: Float = 0.5f
    var color: Int = 0xFFFFFFFF.toInt()
    /** Mesh vertices in local space (for mesh attachments). */
    var meshVertices: FloatArray = floatArrayOf()
    var meshUVs: FloatArray = floatArrayOf()
    var meshTriangles: IntArray = intArrayOf()
    var boneWeights: FloatArray = floatArrayOf()
    var boneIndices: IntArray = intArrayOf()

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("type", type).put("texture", texture)
        .put("x", x.toDouble()).put("y", y.toDouble())
        .put("width", width.toDouble()).put("height", height.toDouble())
        .put("rotation", rotation.toDouble())
        .put("scaleX", scaleX.toDouble()).put("scaleY", scaleY.toDouble())
        .put("pivotX", pivotX.toDouble()).put("pivotY", pivotY.toDouble())
        .put("color", String.format("#%08X", color))

    companion object {
        fun fromJson(o: JSONObject): AttachmentData {
            val a = AttachmentData()
            a.name = o.optString("name", "")
            a.type = o.optInt("type", 0)
            a.texture = o.optString("texture", "")
            a.x = o.optDouble("x", 0.0).toFloat()
            a.y = o.optDouble("y", 0.0).toFloat()
            a.width = o.optDouble("width", 1.0).toFloat()
            a.height = o.optDouble("height", 1.0).toFloat()
            a.rotation = o.optDouble("rotation", 0.0).toFloat()
            a.scaleX = o.optDouble("scaleX", 1.0).toFloat()
            a.scaleY = o.optDouble("scaleY", 1.0).toFloat()
            a.pivotX = o.optDouble("pivotX", 0.5).toFloat()
            a.pivotY = o.optDouble("pivotY", 0.5).toFloat()
            a.color = try { Component.parseColor(o.optString("color", "#FFFFFFFF")) } catch (_: Exception) { 0xFFFFFFFF.toInt() }
            return a
        }
    }
}

// ─── Skeleton Update ───────────────────────────────────────────────────────

/** Runtime skeleton that applies bone transforms from animation. */
class SkeletonInstance(val data: SkeletonData) {
    val bones: Array<Bone> = Array(data.bones.size) { i ->
        val src = data.bones[i]
        Bone(src.id, src.name, src.parentId, src.localX, src.localY, src.localRotation, src.localScaleX, src.localScaleY, src.length)
    }

    /** Update all bone world transforms in hierarchy order. */
    fun updateWorldTransforms() {
        for (bone in bones) {
            bone.updateLocal()
            if (bone.parentId < 0) {
                bone.worldMatrix.set(bone.localMatrix)
            } else {
                val parent = bones[bone.parentId]
                bone.worldMatrix.setMul(parent.worldMatrix, bone.localMatrix)
            }
        }
    }

    /** Get world position of a bone tip (end of bone). */
    fun boneTipWorld(boneId: Int): FloatArray {
        val b = bones[boneId]
        val tipX = b.localX + b.length * cos(b.localRotation)
        val tipY = b.localY + b.length * sin(b.localRotation)
        val m = b.worldMatrix
        return floatArrayOf(m.mapX(tipX, tipY), m.mapY(tipX, tipY))
    }

    /** Get world position of bone origin. */
    fun boneOriginWorld(boneId: Int): FloatArray {
        val m = bones[boneId].worldMatrix
        return floatArrayOf(m.mapX(0f, 0f), m.mapY(0f, 0f))
    }

    /** Simple 2-bone IK solver. */
    fun solveIK2Bone(targetX: Float, targetY: Float, boneA: Int, boneB: Int, bendPositive: Boolean = true) {
        val a = bones[boneA]
        val b = bones[boneB]
        val parentA = if (a.parentId >= 0) bones[a.parentId].worldMatrix else null

        // Convert target to boneA's parent space
        var tLocalX = targetX
        var tLocalY = targetY
        if (parentA != null) {
            val inv = parentA.inverted() ?: return
            tLocalX = inv.mapX(targetX, targetY)
            tLocalY = inv.mapY(targetX, targetY)
        }

        val lenA = a.length
        val lenB = b.length
        val dx = tLocalX - a.localX
        val dy = tLocalY - a.localY
        var dist = sqrt(dx * dx + dy * dy)
        val maxReach = lenA + lenB
        val minReach = abs(lenA - lenB)

        if (dist > maxReach) dist = maxReach
        if (dist < minReach) dist = minReach

        // Law of cosines
        val cosA = ((lenA * lenA + dist * dist - lenB * lenB) / (2 * lenA * dist)).coerceIn(-1f, 1f)
        val angleA = acos(cosA)
        val cosB = ((lenA * lenA + lenB * lenB - dist * dist) / (2 * lenA * lenB)).coerceIn(-1f, 1f)
        val angleB = acos(cosB)

        val baseAngle = atan2(dy, dx)
        val sign = if (bendPositive) 1f else -1f

        a.localRotation = baseAngle + sign * angleA
        b.localRotation = a.localRotation - sign * (PI.toFloat() - angleB) + (if (bendPositive) PI.toFloat() else -PI.toFloat())
    }

    fun resetToBindPose() {
        for (i in bones.indices) {
            val src = data.bones[i]
            bones[i].localX = src.localX
            bones[i].localY = src.localY
            bones[i].localRotation = src.localRotation
            bones[i].localScaleX = src.localScaleX
            bones[i].localScaleY = src.localScaleY
        }
    }
}

// ─── Bone Animation Clip ──────────────────────────────────────────────────

class BoneTrack(
    var boneName: String = "",
    var translateX: MutableList<Keyframe> = mutableListOf(),
    var translateY: MutableList<Keyframe> = mutableListOf(),
    var rotation: MutableList<Keyframe> = mutableListOf(),
    var scaleX: MutableList<Keyframe> = mutableListOf(),
    var scaleY: MutableList<Keyframe> = mutableListOf(),
)

class Keyframe(
    var time: Float = 0f,
    var value: Float = 0f,
    var tangentIn: Float = 0f,
    var tangentOut: Float = 0f,
    var interp: Int = 0, // 0=Linear, 1=Smooth, 2=Constant, 3=CubicBezier
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time.toDouble()).put("value", value.toDouble())
        .put("tangentIn", tangentIn.toDouble()).put("tangentOut", tangentOut.toDouble())
        .put("interp", interp)

    companion object {
        fun fromJson(o: JSONObject): Keyframe = Keyframe(
            o.optDouble("time", 0.0).toFloat(),
            o.optDouble("value", 0.0).toFloat(),
            o.optDouble("tangentIn", 0.0).toFloat(),
            o.optDouble("tangentOut", 0.0).toFloat(),
            o.optInt("interp", 0)
        )
    }
}

/** Interpolate a value from keyframes at the given time. */
fun evaluateTrack(track: List<Keyframe>, time: Float, loop: Boolean, duration: Float): Float {
    if (track.isEmpty()) return 0f
    if (track.size == 1) return track[0].value

    var t = time
    if (loop && duration > 0f) {
        t = t % duration
        if (t < 0) t += duration
    } else {
        t = t.coerceIn(track.first().time, track.last().time)
    }

    // Find surrounding keyframes
    var prev = track.first()
    var next = track.last()
    for (i in track.indices) {
        if (track[i].time >= t) {
            next = track[i]
            prev = if (i > 0) track[i - 1] else track[0]
            break
        }
    }

    if (prev === next) return prev.value

    val range = next.time - prev.time
    val frac = if (range > 0f) ((t - prev.time) / range).coerceIn(0f, 1f) else 0f

    return when (prev.interp) {
        0 -> prev.value + (next.value - prev.value) * frac // Linear
        1 -> { // Smooth (hermite)
            val t2 = frac * frac
            val t3 = t2 * frac
            val h12 = 3f * t2 - 2f * t3
            prev.value + (next.value - prev.value) * h12
        }
        2 -> prev.value // Constant
        3 -> { // Cubic bezier
            val t2 = frac * frac
            val t3 = t2 * frac
            val h1 = 2f * t3 - 3f * t2 + 1f
            val h2 = -2f * t3 + 3f * t2
            val h3 = t3 - 2f * t2 + frac
            val h4 = t3 - t2
            h1 * prev.value + h2 * next.value + h3 * prev.tangentOut * range + h4 * next.tangentIn * range
        }
        else -> prev.value + (next.value - prev.value) * frac
    }
}

/** A complete skeleton animation clip with multiple bone tracks. */
class SkeletonAnimClip(
    var name: String = "",
    var duration: Float = 1f,
    var loop: Boolean = true,
    var sampleRate: Float = 30f,
    val tracks: MutableList<BoneTrack> = mutableListOf(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("name", name).put("duration", duration.toDouble())
            .put("loop", loop).put("sampleRate", sampleRate.toDouble())
        o.put("tracks", JSONArray().also { arr ->
            for (t in tracks) {
                val to = JSONObject().put("bone", t.boneName)
                to.put("translateX", JSONArray(t.translateX.map { it.toJson() }))
                to.put("translateY", JSONArray(t.translateY.map { it.toJson() }))
                to.put("rotation", JSONArray(t.rotation.map { it.toJson() }))
                to.put("scaleX", JSONArray(t.scaleX.map { it.toJson() }))
                to.put("scaleY", JSONArray(t.scaleY.map { it.toJson() }))
                arr.put(to)
            }
        })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SkeletonAnimClip {
            val clip = SkeletonAnimClip(
                o.optString("name", ""), o.optDouble("duration", 1.0).toFloat(),
                o.optBoolean("loop", true), o.optDouble("sampleRate", 30.0).toFloat()
            )
            val arr = o.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val to = arr.getJSONObject(i)
                val track = BoneTrack(to.optString("bone", ""))
                track.translateX = parseKFArray(to.optJSONArray("translateX"))
                track.translateY = parseKFArray(to.optJSONArray("translateY"))
                track.rotation = parseKFArray(to.optJSONArray("rotation"))
                track.scaleX = parseKFArray(to.optJSONArray("scaleX"))
                track.scaleY = parseKFArray(to.optJSONArray("scaleY"))
                clip.tracks.add(track)
            }
            return clip
        }

        private fun parseKFArray(arr: JSONArray?): MutableList<Keyframe> {
            if (arr == null) return mutableListOf()
            val list = mutableListOf<Keyframe>()
            for (i in 0 until arr.length()) list.add(Keyframe.fromJson(arr.getJSONObject(i)))
            return list
        }
    }

    /** Apply this clip to a skeleton at the given time. */
    fun applyTo(skeleton: SkeletonInstance, time: Float) {
        for (track in tracks) {
            val bone = skeleton.data.findBone(track.boneName) ?: continue
            val idx = skeleton.data.boneIndex(track.boneName)
            val rt = skeleton.bones[idx]
            rt.localX = bone.localX + evaluateTrack(track.translateX, time, loop, duration)
            rt.localY = bone.localY + evaluateTrack(track.translateY, time, loop, duration)
            rt.localRotation = bone.localRotation + evaluateTrack(track.rotation, time, loop, duration)
            rt.localScaleX = bone.localScaleX * evaluateTrack(track.scaleX, time, loop, duration)
            rt.localScaleY = bone.localScaleY * evaluateTrack(track.scaleY, time, loop, duration)
        }
    }
}

// ─── Skeleton Component ───────────────────────────────────────────────────

class SkeletonRenderer : Component() {
    override val type = "SkeletonRenderer"
    var skeletonAsset = ""
    var defaultSkin = "default"
    var color = 0xFFFFFFFF.toInt()
    var flipX = false
    var flipY = false
    var debugDraw = false

    override fun props() = listOf(
        Prop.Asset("Skeleton", com.sengine.engine.core.AssetKind.SKELETON, { skeletonAsset }, { skeletonAsset = it }),
        Prop.S("Default Skin", { defaultSkin }, { defaultSkin = it }),
        Prop.Color("Color", { color }, { color = it }),
        Prop.B("Flip X", { flipX }, { flipX = it }),
        Prop.B("Flip Y", { flipY }, { flipY = it }),
        Prop.B("Debug Draw", { debugDraw }, { debugDraw = it }),
    )
}

class SkeletonAnimator : Component() {
    override val type = "SkeletonAnimator"
    var currentClip = ""
    var speed = 1f
    var loop = true
    var playing = true
    /** Time position in the current clip. */
    var time = 0f
    /** State machine: list of animation states with transitions. */
    var statesJson = "[]"
    var currentLayer = 0
    var blendWeight = 1f

    // Runtime
    var internalTime = 0f
    var prevClip = ""
    var crossFadeDuration = 0.2f
    var crossFadeTime = 0f
    var isCrossFading = false

    override fun props() = listOf(
        Prop.Asset("Animation Clip", com.sengine.engine.core.AssetKind.SKELETON_ANIM, { currentClip }, { currentClip = it }),
        Prop.F("Speed", { speed }, { speed = it }, 0.1f),
        Prop.B("Loop", { loop }, { loop = it }),
        Prop.B("Playing", { playing }, { playing = it }),
        Prop.F("Time", { time }, { time = it.coerceAtLeast(0f) }, 0.05f),
        Prop.F("Cross Fade Duration", { crossFadeDuration }, { crossFadeDuration = it.coerceAtLeast(0f) }, 0.05f),
        Prop.F("Blend Weight", { blendWeight }, { blendWeight = it.coerceIn(0f, 1f) }, 0.05f),
    )

    override fun resetRuntime() {
        internalTime = 0f; time = 0f; isCrossFading = false; crossFadeTime = 0f
    }
}
