package com.sengine.engine.script

import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prop
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

// ─── Extended Scripting API ──────────────────────────────────────────────

/** Professional script extensions — adds coroutine, event, debug, and prefab APIs.
 *  These are exposed to JavaScript via the Rhino binding layer. */

/** Coroutine handle for script-side async operations. */

private fun bounceOut(t: Float): Float {
    return if (t < 1f/2.75f) {
        7.5625f * t * t
    } else if (t < 2/2.75) {
        val t2 = t - 1.5f/2.75f
        7.5625f * t2 * t2 + 0.75f
    } else if (t < 2.5/2.75) {
        val t2 = t - 2.25f/2.75f
        7.5625f * t2 * t2 + 0.9375f
    } else {
        val t2 = t - 2.625f/2.75f
        7.5625f * t2 * t2 + 0.984375f
    }
}

class CoroutineHandle(var id: Int = 0, var running: Boolean = true, var time: Float = 0f)

/** Debug drawing helpers for development. */
object ScriptDebug {
    val lines = mutableListOf<DebugLine>()
    val spheres = mutableListOf<DebugSphere>()

    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, color: Int = 0xFF00FF00.toInt(), duration: Float = 0f) {
        lines.add(DebugLine(x0, y0, x1, y1, color, duration, 0f))
    }

    fun drawRay(x: Float, y: Float, dx: Float, dy: Float, color: Int = 0xFFFFFF00.toInt(), duration: Float = 0f) {
        lines.add(DebugLine(x, y, x + dx, y + dy, color, duration, 0f))
    }

    fun drawSphere(x: Float, y: Float, radius: Float, color: Int = 0xFF00FFFF.toInt(), duration: Float = 0f) {
        spheres.add(DebugSphere(x, y, radius, color, duration, 0f))
    }

    fun drawRect(x: Float, y: Float, w: Float, h: Float, color: Int = 0xFFFFFFFF.toInt(), duration: Float = 0f) {
        drawLine(x, y, x + w, y, color, duration)
        drawLine(x + w, y, x + w, y + h, color, duration)
        drawLine(x + w, y + h, x, y + h, color, duration)
        drawLine(x, y + h, x, y, color, duration)
    }

    fun drawCircle(x: Float, y: Float, radius: Float, segments: Int = 32, color: Int = 0xFFFFFFFF.toInt(), duration: Float = 0f) {
        for (i in 0 until segments) {
            val a0 = 2 * PI.toFloat() * i / segments
            val a1 = 2 * PI.toFloat() * (i + 1) / segments
            drawLine(x + cos(a0) * radius, y + sin(a0) * radius,
                     x + cos(a1) * radius, y + sin(a1) * radius, color, duration)
        }
    }

    fun clear() { lines.clear(); spheres.clear() }

    fun update(dt: Float) {
        lines.removeAll { it.duration > 0 && run { it.elapsed += dt; it.elapsed >= it.duration } }
        spheres.removeAll { it.duration > 0 && run { it.elapsed += dt; it.elapsed >= it.duration } }
    }
}

data class DebugLine(var x0: Float, var y0: Float, var x1: Float, var y1: Float, var color: Int, var duration: Float, var elapsed: Float = 0f)
data class DebugSphere(var x: Float, var y: Float, var radius: Float, var color: Int, var duration: Float, var elapsed: Float = 0f)

// ─── Event System ──────────────────────────────────────────────────────────

/** Global event bus for inter-script communication. */
object EventBus {
    private val listeners = HashMap<String, MutableList<(Any?) -> Unit>>()
    private val queuedEvents = mutableListOf<Pair<String, Any?>>()

    fun on(event: String, callback: (Any?) -> Unit) {
        listeners.getOrPut(event) { mutableListOf() }.add(callback)
    }

    fun off(event: String, callback: (Any?) -> Unit) {
        listeners[event]?.remove(callback)
    }

    fun emit(event: String, data: Any? = null) {
        listeners[event]?.forEach { it(data) }
    }

    fun emitQueued(event: String, data: Any? = null) {
        queuedEvents.add(event to data)
    }

    fun flush() {
        val events = queuedEvents.toList()
        queuedEvents.clear()
        for ((event, data) in events) emit(event, data)
    }

    fun clear() { listeners.clear(); queuedEvents.clear() }
}

// ─── Object Pool ───────────────────────────────────────────────────────────

/** Efficient object pooling for frequently created/destroyed objects. */
class ObjectPool(
    var prefabName: String = "",
    var initialSize: Int = 10,
    var maxSize: Int = 100,
) {
    private val pool = ArrayDeque<GameObject>()

    fun <T> create(factory: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return (pool.removeFirstOrNull() as T?) ?: factory()
    }

    fun release(obj: GameObject) {
        if (pool.size < maxSize) {
            obj.active = false
            pool.addLast(obj)
        }
    }

    fun warmUp(factory: () -> GameObject) {
        repeat(initialSize) {
            val obj = factory()
            obj.active = false
            pool.addLast(obj)
        }
    }

    val available: Int get() = pool.size
    fun clear() { pool.clear() }
}

// ─── Tween System ─────────────────────────────────────────────────────────

/** Lightweight tweening system for smooth property animations. */
class TweenSystem {
    private val tweens = mutableListOf<TweenInstance>()

    fun tween(target: TweenTarget, from: Float, to: Float, duration: Float, ease: EaseType = EaseType.Linear, delay: Float = 0f, onComplete: (() -> Unit)? = null): TweenInstance {
        val t = TweenInstance(target, from, to, duration, ease, delay, onComplete)
        tweens.add(t)
        return t
    }

    fun update(dt: Float) {
        val it = tweens.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (!t.active) { it.remove(); continue }
            if (t.delay > 0f) { t.delay -= dt; continue }
            t.elapsed += dt
            val progress = (t.elapsed / t.duration).coerceIn(0f, 1f)
            val eased = applyEase(progress, t.ease)
            val value = t.from + (t.to - t.from) * eased
            t.target.set(value)
            if (progress >= 1f) {
                t.active = false
                t.onComplete?.invoke()
            }
        }
        tweens.removeAll { !it.active }
    }

    fun killAll() { tweens.clear() }
    fun kill(target: TweenTarget) { tweens.removeAll { it.target === target } }

    private fun applyEase(t: Float, ease: EaseType): Float = when (ease) {
        EaseType.Linear -> t
        EaseType.EaseInQuad -> t * t
        EaseType.EaseOutQuad -> 1f - (1f - t) * (1f - t)
        EaseType.EaseInOutQuad -> if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).let { it * it } / 2f
        EaseType.EaseInCubic -> t * t * t
        EaseType.EaseOutCubic -> 1f - (1f - t).let { it * it * it }
        EaseType.EaseInOutCubic -> if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
        EaseType.EaseInBack -> { val c = 1.70158f; (c + 1f) * t * t * t - c * t * t }
        EaseType.EaseOutBack -> { val c = 1.70158f; 1f + (c + 1f) * (t - 1f).let { it * it * it } + c * (t - 1f).let { it * it } }
        EaseType.EaseOutElastic -> if (t == 0f || t == 1f) t else 2f.pow(-10f * t) * sin((t * 10f - 0.75f) * (2f * PI.toFloat() / 3f)) + 1f
        EaseType.EaseOutBounce -> bounceOut(t)
    }
}

fun interface TweenTarget { fun set(value: Float) }

class TweenInstance(
    val target: TweenTarget,
    val from: Float,
    val to: Float,
    val duration: Float,
    val ease: EaseType,
    var delay: Float,
    val onComplete: (() -> Unit)?,
) {
    var elapsed: Float = 0f
    var active: Boolean = true
    var loop: Boolean = false
    var pingPong: Boolean = false

    fun setLoop(loop: Boolean, pingPong: Boolean = false): TweenInstance {
        this.loop = loop; this.pingPong = pingPong; return this
    }
}

enum class EaseType {
    Linear, EaseInQuad, EaseOutQuad, EaseInOutQuad,
    EaseInCubic, EaseOutCubic, EaseInOutCubic,
    EaseInBack, EaseOutBack, EaseOutElastic, EaseOutBounce
}

// ─── Script Profiler ─────────────────────────────────────────────────────

/** Lightweight script profiler for performance analysis. */
class ScriptProfiler {
    data class ProfileEntry(var name: String, var calls: Int = 0, var totalTimeMs: Double = 0.0, var lastTimeMs: Double = 0.0)
    val entries = LinkedHashMap<String, ProfileEntry>()

    fun begin(name: String) {
        val entry = entries.getOrPut(name) { ProfileEntry(name) }
        entry._startTime = System.nanoTime()
    }

    fun end(name: String) {
        val entry = entries[name] ?: return
        val elapsed = (System.nanoTime() - entry._startTime) / 1_000_000.0
        entry.calls++
        entry.totalTimeMs += elapsed
        entry.lastTimeMs = elapsed
    }

    fun reset() { entries.clear() }

    fun report(): String {
        val sb = StringBuilder()
        for ((name, e) in entries.entries.sortedByDescending { it.value.totalTimeMs }) {
            val avg = if (e.calls > 0) e.totalTimeMs / e.calls else 0.0
            sb.appendLine("$name: ${e.calls} calls, avg ${"%.2f".format(avg)}ms, total ${"%.2f".format(e.totalTimeMs)}ms")
        }
        return sb.toString()
    }

    private var ProfileEntry._startTime: Long
        get() = totalTimeMs.toLong() // reuse field temporarily
        set(value) { totalTimeMs = value.toDouble() }
}

// ─── Script Component Extensions ─────────────────────────────────────────

/** Enhanced script component with coroutine and event support. */
class ProScriptComponent : Component() {
    override val type = "ProScript"
    var script = ""
    var params = ""
    var tags = mutableListOf<String>()
    var debugMode = false
    var profilingEnabled = false

    override fun props() = listOf(
        Prop.Asset("Script", com.sengine.engine.core.AssetKind.SCRIPT, { script }, { script = it }),
        Prop.S("Params", { params }, { params = it }),
        Prop.B("Debug Mode", { debugMode }, { debugMode = it }),
        Prop.B("Profiling", { profilingEnabled }, { profilingEnabled = it }),
    )
}

// ─── Serialization Helpers ────────────────────────────────────────────────

object ScriptSerializer {
    fun paramsToJson(params: String): JSONObject {
        val o = JSONObject()
        for (pair in params.split(',').map { it.trim() }.filter { it.contains('=') }) {
            val (key, value) = pair.split('=', limit = 2).map { it.trim() }
            val numVal = value.toFloatOrNull()
            if (numVal != null) o.put(key, numVal.toDouble())
            else if (value.equals("true", true) || value.equals("false", true)) o.put(key, value.toBoolean())
            else o.put(key, value)
        }
        return o
    }
}
