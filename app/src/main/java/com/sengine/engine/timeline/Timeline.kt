package com.sengine.engine.timeline

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

// ─── Timeline System ──────────────────────────────────────────────────────

/** A professional timeline-based animation editor system.
 *  Supports multiple tracks, keyframes, curves, and layered animation. */

/** Types of timeline tracks. */
enum class TrackType {
    Animation,     // Transform / bone animation
    Audio,         // Audio clip playback
    Activation,    // Object enable/disable
    Control,       // Trigger sub-timelines or events
    ParticleSystem, // Particle emitter control
    Marker,        // Named markers at time positions
    Script,        // Script method calls at time
}

/** Interpolation curves for keyframes. */
enum class CurveType {
    Constant,      // Hold previous value
    Linear,        // Straight line
    Bezier,        // Cubic bezier with handles
    EaseIn,        // Quadratic ease in
    EaseOut,       // Quadratic ease out
    EaseInOut,     // Quadratic ease in and out
    Spring,        // Spring physics
    Bounce,        // Bounce effect
    Elastic,       // Elastic effect
}

/** A single keyframe on a track. */
data class TimelineKeyframe(
    var time: Float = 0f,
    var value: Float = 0f,
    var valueStr: String = "",   // For string-valued tracks
    var valueInt: Int = 0,       // For int-valued tracks
    var valueBool: Boolean = false,
    var curveType: CurveType = CurveType.Linear,
    var tangentIn: Float = 0f,
    var tangentOut: Float = 0f,
    var broken: Boolean = false, // Independent in/out tangents
    var selected: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time.toDouble())
        .put("value", value.toDouble())
        .put("valueStr", valueStr)
        .put("valueInt", valueInt)
        .put("valueBool", valueBool)
        .put("curve", curveType.ordinal)
        .put("tangentIn", tangentIn.toDouble())
        .put("tangentOut", tangentOut.toDouble())
        .put("broken", broken)

    companion object {
        fun fromJson(o: JSONObject): TimelineKeyframe = TimelineKeyframe(
            o.optDouble("time", 0.0).toFloat(),
            o.optDouble("value", 0.0).toFloat(),
            o.optString("valueStr", ""),
            o.optInt("valueInt", 0),
            o.optBoolean("valueBool", false),
            CurveType.entries.getOrElse(o.optInt("curve", 1)) { CurveType.Linear },
            o.optDouble("tangentIn", 0.0).toFloat(),
            o.optDouble("tangentOut", 0.0).toFloat(),
            o.optBoolean("broken", false)
        )
    }
}

/** A single channel within a track (e.g. Position.x, Position.y). */
class TimelineChannel(
    var name: String = "",
    var keyframes: MutableList<TimelineKeyframe> = mutableListOf(),
    var color: Int = 0xFFFFFFFF.toInt(),
    var visible: Boolean = true,
    var locked: Boolean = false,
) {
    fun addKeyframe(time: Float, value: Float): TimelineKeyframe {
        val kf = TimelineKeyframe(time = time, value = value)
        val idx = keyframes.indexOfFirst { it.time > time }
        if (idx >= 0) keyframes.add(idx, kf) else keyframes.add(kf)
        return kf
    }

    fun removeKeyframeAt(time: Float, tolerance: Float = 0.01f) {
        keyframes.removeAll { abs(it.time - time) < tolerance }
    }

    /** Evaluate this channel at the given time. */
    fun evaluate(time: Float): Float {
        if (keyframes.isEmpty()) return 0f
        if (keyframes.size == 1) return keyframes[0].value

        // Clamp to range
        if (time <= keyframes.first().time) return keyframes.first().value
        if (time >= keyframes.last().time) return keyframes.last().value

        // Find surrounding keyframes
        var prev = keyframes.first()
        var next = keyframes.last()
        for (i in keyframes.indices) {
            if (keyframes[i].time >= time) {
                next = keyframes[i]
                prev = if (i > 0) keyframes[i - 1] else keyframes[0]
                break
            }
        }

        if (prev === next) return prev.value
        val range = next.time - prev.time
        val t = if (range > 0f) ((time - prev.time) / range).coerceIn(0f, 1f) else 0f

        return interpolate(prev.value, next.value, t, prev.curveType, prev.tangentOut, next.tangentIn, range)
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("name", name)
            .put("color", String.format("#%08X", color))
            .put("visible", visible)
            .put("locked", locked)
        o.put("keyframes", JSONArray().also { arr -> keyframes.forEach { arr.put(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): TimelineChannel {
            val ch = TimelineChannel(
                o.optString("name", ""),
                mutableListOf(),
                try { com.sengine.engine.core.Component.parseColor(o.optString("color", "#FFFFFFFF")) } catch (_: Exception) { 0xFFFFFFFF.toInt() },
                o.optBoolean("visible", true),
                o.optBoolean("locked", false)
            )
            val arr = o.optJSONArray("keyframes") ?: JSONArray()
            for (i in 0 until arr.length()) ch.keyframes.add(TimelineKeyframe.fromJson(arr.getJSONObject(i)))
            return ch
        }
    }
}

/** A track in the timeline, targeting a specific object and component property. */
class TimelineTrack(
    var name: String = "",
    var type: TrackType = TrackType.Animation,
    var targetObject: String = "",       // GameObject name
    var targetComponent: String = "",    // Component type
    var targetProperty: String = "",     // Property path
    var channels: MutableList<TimelineChannel> = mutableListOf(),
    var clipName: String = "",           // For audio / control tracks
    var muted: Boolean = false,
    var locked: Boolean = false,
    var color: Int = 0xFF4488FF.toInt(),
) {
    fun addChannel(name: String): TimelineChannel {
        val ch = TimelineChannel(name)
        channels.add(ch)
        return ch
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("name", name).put("type", type.ordinal)
            .put("target", targetObject).put("component", targetComponent)
            .put("property", targetProperty).put("clip", clipName)
            .put("muted", muted).put("locked", locked)
            .put("color", String.format("#%08X", color))
        o.put("channels", JSONArray().also { arr -> channels.forEach { arr.put(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): TimelineTrack {
            val t = TimelineTrack(
                o.optString("name", ""),
                TrackType.entries.getOrElse(o.optInt("type", 0)) { TrackType.Animation },
                o.optString("target", ""),
                o.optString("component", ""),
                o.optString("property", ""),
                mutableListOf(),
                o.optString("clip", ""),
                o.optBoolean("muted", false),
                o.optBoolean("locked", false),
                try { com.sengine.engine.core.Component.parseColor(o.optString("color", "#FF4488FF")) } catch (_: Exception) { 0xFF4488FF.toInt() },
            )
            val arr = o.optJSONArray("channels") ?: JSONArray()
            for (i in 0 until arr.length()) t.channels.add(TimelineChannel.fromJson(arr.getJSONObject(i)))
            return t
        }
    }
}

/** The complete timeline clip. */
class TimelineClip(
    var name: String = "",
    var duration: Float = 5f,
    var fps: Float = 30f,
    var loop: Boolean = false,
    val tracks: MutableList<TimelineTrack> = mutableListOf(),
    val markers: MutableList<TimelineMarker> = mutableListOf(),
) {
    val frameCount: Int get() = (duration * fps).roundToInt()
    val timePerFrame: Float get() = if (fps > 0f) 1f / fps else 1f / 30f

    fun addTrack(name: String, type: TrackType, target: String = ""): TimelineTrack {
        val t = TimelineTrack(name = name, type = type, targetObject = target)
        tracks.add(t)
        return t
    }

    fun addMarker(name: String, time: Float): TimelineMarker {
        val m = TimelineMarker(name, time)
        val idx = markers.indexOfFirst { it.time > time }
        if (idx >= 0) markers.add(idx, m) else markers.add(m)
        return m
    }

    /** Get markers at the given time. */
    fun markersAt(time: Float, tolerance: Float = 0.05f): List<TimelineMarker> =
        markers.filter { abs(it.time - time) < tolerance }

    /** Evaluate all tracks at the given time and apply to scene objects. */
    fun evaluate(time: Float): Map<String, Map<String, Float>> {
        val result = HashMap<String, HashMap<String, Float>>()
        for (track in tracks) {
            if (track.muted || track.locked) continue
            when (track.type) {
                TrackType.Animation -> {
                    val objProps = result.getOrPut(track.targetObject) { HashMap() }
                    for (channel in track.channels) {
                        if (!channel.visible || channel.locked) continue
                        objProps["${track.targetProperty}.${channel.name}"] = channel.evaluate(time)
                    }
                }
                else -> { /* Audio, Control, etc. handled externally */ }
            }
        }
        return result
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("name", name).put("duration", duration.toDouble())
            .put("fps", fps.toDouble()).put("loop", loop)
        o.put("tracks", JSONArray().also { arr -> tracks.forEach { arr.put(it.toJson()) } })
        o.put("markers", JSONArray().also { arr -> markers.forEach { arr.put(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): TimelineClip {
            val clip = TimelineClip(
                o.optString("name", ""),
                o.optDouble("duration", 5.0).toFloat(),
                o.optDouble("fps", 30.0).toFloat(),
                o.optBoolean("loop", false)
            )
            val tracks = o.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until tracks.length()) clip.tracks.add(TimelineTrack.fromJson(tracks.getJSONObject(i)))
            val markers = o.optJSONArray("markers") ?: JSONArray()
            for (i in 0 until markers.length()) clip.markers.add(TimelineMarker.fromJson(markers.getJSONObject(i)))
            return clip
        }
    }
}

/** A named marker at a specific time on the timeline. */
class TimelineMarker(
    var name: String = "",
    var time: Float = 0f,
    var color: Int = 0xFFFF8800.toInt(),
    var notification: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("time", time.toDouble())
        .put("color", String.format("#%08X", color))
        .put("notification", notification)

    companion object {
        fun fromJson(o: JSONObject): TimelineMarker = TimelineMarker(
            o.optString("name", ""), o.optDouble("time", 0.0).toFloat(),
            try { com.sengine.engine.core.Component.parseColor(o.optString("color", "#FFFF8800")) } catch (_: Exception) { 0xFFFF8800.toInt() },
            o.optString("notification", "")
        )
    }
}

// ─── Interpolation Functions ──────────────────────────────────────────────

fun interpolate(a: Float, b: Float, t: Float, curve: CurveType, tangentOut: Float = 0f, tangentIn: Float = 0f, range: Float = 1f): Float {
    return when (curve) {
        CurveType.Constant -> a
        CurveType.Linear -> a + (b - a) * t
        CurveType.Bezier -> {
            val t2 = t * t; val t3 = t2 * t
            val h1 = 2f * t3 - 3f * t2 + 1f
            val h2 = -2f * t3 + 3f * t2
            val h3 = t3 - 2f * t2 + t
            val h4 = t3 - t2
            h1 * a + h2 * b + h3 * tangentOut * range + h4 * tangentIn * range
        }
        CurveType.EaseIn -> a + (b - a) * (t * t)
        CurveType.EaseOut -> a + (b - a) * (1f - (1f - t) * (1f - t))
        CurveType.EaseInOut -> {
            val ct = if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).let { it * it } / 2f
            a + (b - a) * ct
        }
        CurveType.Spring -> {
            val damp = 0.5f
            val freq = 4f
            a + (b - a) * (1f - exp(-damp * t * 10f) * cos(freq * t * PI.toFloat() * 2f).let { (it + 1f) / 2f })
        }
        CurveType.Bounce -> {
            val bt = bounceOut(t)
            a + (b - a) * bt
        }
        CurveType.Elastic -> {
            if (t == 0f || t == 1f) {
                if (t == 0f) a else b
            } else {
                val p = 0.3f
                val s = p / 4f
                a + (b - a) * (2f.pow(-10f * t) * sin((t - s) * (2f * PI.toFloat()) / p) + 1f)
            }
        }
    }
}

fun bounceOut(t: Float): Float {
    return when {
        t < 1f / 2.75f -> 7.5625f * t * t
        t < 2f / 2.75f -> { val t2 = t - 1.5f / 2.75f; 7.5625f * t2 * t2 + 0.75f }
        t < 2.5f / 2.75f -> { val t2 = t - 2.25f / 2.75f; 7.5625f * t2 * t2 + 0.9375f }
        else -> { val t2 = t - 2.625f / 2.75f; 7.5625f * t2 * t2 + 0.984375f }
    }
}

// ─── Animation State Machine ──────────────────────────────────────────────

/** A state in the animation state machine. */
class AnimState(
    var name: String = "",
    var clip: String = "",
    var speed: Float = 1f,
    var loop: Boolean = true,
    var blendMode: Int = 0, // 0=Override, 1=Additive
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("clip", clip)
        .put("speed", speed.toDouble()).put("loop", loop)
        .put("blend", blendMode)

    companion object {
        fun fromJson(o: JSONObject): AnimState = AnimState(
            o.optString("name", ""), o.optString("clip", ""),
            o.optDouble("speed", 1.0).toFloat(),
            o.optBoolean("loop", true), o.optInt("blend", 0)
        )
    }
}

/** A transition between two animation states. */
class AnimTransition(
    var fromState: String = "",
    var toState: String = "",
    var duration: Float = 0.25f,
    var hasExitTime: Boolean = true,
    var exitTime: Float = 0.9f,
    var conditions: MutableList<TransitionCondition> = mutableListOf(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("from", fromState).put("to", toState)
            .put("duration", duration.toDouble())
            .put("hasExitTime", hasExitTime)
            .put("exitTime", exitTime.toDouble())
        o.put("conditions", JSONArray().also { arr -> conditions.forEach { arr.put(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): AnimTransition {
            val t = AnimTransition(
                o.optString("from", ""), o.optString("to", ""),
                o.optDouble("duration", 0.25).toFloat(),
                o.optBoolean("hasExitTime", true),
                o.optDouble("exitTime", 0.9).toFloat()
            )
            val arr = o.optJSONArray("conditions") ?: JSONArray()
            for (i in 0 until arr.length()) t.conditions.add(TransitionCondition.fromJson(arr.getJSONObject(i)))
            return t
        }
    }
}

/** A condition for triggering an animation transition. */
class TransitionCondition(
    var parameter: String = "",
    var mode: Int = 0,     // 0=If, 1=IfNot, 2=Greater, 3=Less, 4=Equals, 5=NotEquals
    var threshold: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("param", parameter).put("mode", mode).put("threshold", threshold.toDouble())

    companion object {
        fun fromJson(o: JSONObject): TransitionCondition = TransitionCondition(
            o.optString("param", ""), o.optInt("mode", 0), o.optDouble("threshold", 0.0).toFloat()
        )
    }
}

/** The complete animation controller (state machine). */
class AnimationController(
    var name: String = "",
    val states: MutableList<AnimState> = mutableListOf(),
    val transitions: MutableList<AnimTransition> = mutableListOf(),
    val parameters: MutableMap<String, AnimParameter> = mutableMapOf(),
    var defaultState: String = "",
) {
    fun addState(name: String, clip: String = name): AnimState {
        val s = AnimState(name, clip)
        states.add(s)
        if (defaultState.isEmpty()) defaultState = name
        return s
    }

    fun addTransition(from: String, to: String, duration: Float = 0.25f): AnimTransition {
        val t = AnimTransition(from, to, duration)
        transitions.add(t)
        return t
    }

    fun setBool(name: String, value: Boolean) {
        val p = parameters.getOrPut(name) { AnimParameter(name, 0) }
        p.boolVal = value
    }

    fun setFloat(name: String, value: Float) {
        val p = parameters.getOrPut(name) { AnimParameter(name, 1) }
        p.floatVal = value
    }

    fun setInt(name: String, value: Int) {
        val p = parameters.getOrPut(name) { AnimParameter(name, 2) }
        p.intVal = value
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("name", name).put("default", defaultState)
        o.put("states", JSONArray().also { arr -> states.forEach { arr.put(it.toJson()) } })
        o.put("transitions", JSONArray().also { arr -> transitions.forEach { arr.put(it.toJson()) } })
        o.put("parameters", JSONObject().also { j -> parameters.forEach { (k, v) -> j.put(k, v.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): AnimationController {
            val c = AnimationController(o.optString("name", ""), mutableListOf(), mutableListOf(), mutableMapOf(), o.optString("default", ""))
            val states = o.optJSONArray("states") ?: JSONArray()
            for (i in 0 until states.length()) c.states.add(AnimState.fromJson(states.getJSONObject(i)))
            val trans = o.optJSONArray("transitions") ?: JSONArray()
            for (i in 0 until trans.length()) c.transitions.add(AnimTransition.fromJson(trans.getJSONObject(i)))
            val params = o.optJSONObject("parameters") ?: JSONObject()
            for (k in params.keys()) c.parameters[k] = AnimParameter.fromJson(k, params.getJSONObject(k))
            return c
        }
    }
}

/** A parameter used to drive animation state transitions. */
class AnimParameter(
    var name: String = "",
    var type: Int = 0, // 0=Bool, 1=Float, 2=Int, 3=Trigger
    var boolVal: Boolean = false,
    var floatVal: Float = 0f,
    var intVal: Int = 0,
    var triggerVal: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("bool", boolVal)
        .put("float", floatVal.toDouble()).put("int", intVal)

    companion object {
        fun fromJson(name: String, o: JSONObject): AnimParameter = AnimParameter(
            name, o.optInt("type", 0),
            o.optBoolean("bool", false),
            o.optDouble("float", 0.0).toFloat(),
            o.optInt("int", 0)
        )
    }
}

/** Runtime animation state machine player. */
class AnimStateMachine(val controller: AnimationController) {
    var currentState: String = controller.defaultState
    var previousState: String = ""
    var stateTime: Float = 0f
    var transitionTime: Float = 0f
    var isTransitioning: Boolean = false
    var transitionTarget: String = ""
    var transitionDuration: Float = 0.25f
    var blendWeight: Float = 0f // 0 = fully in currentState, 1 = fully in transitionTarget

    /** Advance the state machine by dt seconds. Returns current animation clip name and time. */
    fun update(dt: Float, clipDuration: Float): Pair<String, Float> {
        stateTime += dt

        if (isTransitioning) {
            transitionTime += dt
            blendWeight = (transitionTime / transitionDuration).coerceIn(0f, 1f)
            if (blendWeight >= 1f) {
                currentState = transitionTarget
                isTransitioning = false
                stateTime = transitionTime - transitionDuration
                blendWeight = 0f
            }
        } else {
            // Check transitions from current state
            for (trans in controller.transitions) {
                if (trans.fromState != currentState) continue
                if (trans.hasExitTime && clipDuration > 0f) {
                    if (stateTime / clipDuration < trans.exitTime) continue
                }
                if (evaluateConditions(trans)) {
                    startTransition(trans.toState, trans.duration)
                    break
                }
            }
        }

        return currentState to stateTime
    }

    fun startTransition(target: String, duration: Float) {
        previousState = currentState
        transitionTarget = target
        transitionDuration = duration
        transitionTime = 0f
        isTransitioning = true
        blendWeight = 0f
    }

    fun setBool(name: String, value: Boolean) = controller.setBool(name, value)
    fun setFloat(name: String, value: Float) = controller.setFloat(name, value)
    fun setInt(name: String, value: Int) = controller.setInt(name, value)
    fun setTrigger(name: String) {
        val p = controller.parameters.getOrPut(name) { AnimParameter(name, 3) }
        p.triggerVal = true
    }

    private fun evaluateConditions(trans: AnimTransition): Boolean {
        if (trans.conditions.isEmpty()) return trans.hasExitTime.not().not() // Always true if exit time met
        return trans.conditions.all { cond ->
            val p = controller.parameters[cond.parameter] ?: return@all false
            when (cond.mode) {
                0 -> p.boolVal  // If true
                1 -> !p.boolVal // If not true
                2 -> p.floatVal > cond.threshold  // Greater
                3 -> p.floatVal < cond.threshold  // Less
                4 -> p.intVal == cond.threshold.toInt() // Equals
                5 -> p.intVal != cond.threshold.toInt() // Not equals
                else -> false
            }
        }
    }
}
