package com.sengine.engine.camera

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import kotlin.math.*

// ─── Professional Camera System ──────────────────────────────────────────

/**
 * Cinemachine-style camera system with smart behaviors:
 * - Camera blending between virtual cameras
 * - Look-ahead and target prediction
 * - Screen composition (dead zone, soft zone)
 * - Impulse system (screen shake from events)
 * - Framing and grouping
 * - Confiner (keep within bounds)
 */

/** Virtual camera — defines a camera behavior without being the actual camera. */
class VirtualCamera : Component() {
    override val type = "VirtualCamera"
    var priority: Int = 10
    var followTarget: String = ""
    var lookAtTarget: String = ""
    var offsetX: Float = 0f
    var offsetY: Float = 0f
    var cameraSize: Float = 5f
    var damping: Float = 0.2f

    // Composition
    var deadZoneX: Float = 0.1f
    var deadZoneY: Float = 0.1f
    var softZoneX: Float = 0.4f
    var softZoneY: Float = 0.4f
    var biasX: Float = 0f
    var biasY: Float = 0f

    // Look-ahead
    var useLookAhead: Boolean = true
    var lookAheadTime: Float = 0.5f
    var lookAheadDistance: Float = 2f

    // Confiner
    var useConfiner: Boolean = false
    var confinerMinX: Float = -50f
    var confinerMaxX: Float = 50f
    var confinerMinY: Float = -50f
    var confinerMaxY: Float = 50f

    // Noise / shake
    var noiseAmplitude: Float = 0f
    var noiseFrequency: Float = 0f

    override fun props() = listOf(
        Prop.I("Priority", { priority }, { priority = it }),
        Prop.S("Follow Target", { followTarget }, { followTarget = it }),
        Prop.S("Look At Target", { lookAtTarget }, { lookAtTarget = it }),
        Prop.F("Offset X", { offsetX }, { offsetX = it }, 0.5f),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }, 0.5f),
        Prop.F("Size", { cameraSize }, { cameraSize = it.coerceAtLeast(0.5f) }, 0.5f),
        Prop.F("Damping", { damping }, { damping = it.coerceIn(0f, 2f) }, 0.05f),
        Prop.F("Dead Zone X", { deadZoneX }, { deadZoneX = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Dead Zone Y", { deadZoneY }, { deadZoneY = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.B("Look Ahead", { useLookAhead }, { useLookAhead = it }),
        Prop.F("Look Ahead Time", { lookAheadTime }, { lookAheadTime = it.coerceAtLeast(0f) }, 0.1f),
        Prop.F("Look Ahead Dist", { lookAheadDistance }, { lookAheadDistance = it.coerceAtLeast(0f) }, 0.5f),
        Prop.B("Use Confiner", { useConfiner }, { useConfiner = it }),
        Prop.F("Confiner Min X", { confinerMinX }, { confinerMinX = it }, 5f),
        Prop.F("Confiner Max X", { confinerMaxX }, { confinerMaxX = it }, 5f),
        Prop.F("Confiner Min Y", { confinerMinY }, { confinerMinY = it }, 5f),
        Prop.F("Confiner Max Y", { confinerMaxY }, { confinerMaxY = it }, 5f),
        Prop.F("Noise Amplitude", { noiseAmplitude }, { noiseAmplitude = it.coerceAtLeast(0f) }, 0.1f),
        Prop.F("Noise Frequency", { noiseFrequency }, { noiseFrequency = it.coerceAtLeast(0f) }, 1f),
    )
}

/** Camera blend definition. */
class CameraBlend(
    var fromCamera: String = "",
    var toCamera: String = "",
    var duration: Float = 1f,
    var blendCurve: Int = 0, // 0=Linear, 1=EaseIn, 2=EaseOut, 3=EaseInOut
) {
    var elapsedTime: Float = 0f
    val progress: Float get() = (elapsedTime / duration).coerceIn(0f, 1f)
    val isComplete: Boolean get() = elapsedTime >= duration

    fun update(dt: Float) { elapsedTime += dt }

    fun easedProgress(): Float = when (blendCurve) {
        0 -> progress
        1 -> progress * progress
        2 -> 1f - (1f - progress) * (1f - progress)
        3 -> {
            val t = progress
            if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).let { it * it } / 2f
        }
        else -> progress
    }
}

/** Impulse event — triggers camera shake. */
class CameraImpulse(
    var amplitude: Float = 1f,
    var duration: Float = 0.3f,
    var frequency: Float = 15f,
    var sourceX: Float = 0f,
    var sourceY: Float = 0f,
) {
    var elapsed: Float = 0f
    val isFinished: Boolean get() = elapsed >= duration

    /** Get current shake offset. */
    fun getOffset(time: Float): Pair<Float, Float> {
        if (isFinished) return 0f to 0f
        val decay = 1f - (elapsed / duration)
        val shakeX = sin(time * frequency * 6.28f).toFloat() * amplitude * decay
        val shakeY = cos(time * frequency * 6.28f * 0.7f).toFloat() * amplitude * decay
        return shakeX to shakeY
    }

    fun update(dt: Float) { elapsed += dt }
}

/** Camera manager — handles virtual camera switching, blending, and impulses. */
class CameraManager {
    val impulses = mutableListOf<CameraImpulse>()
    var currentBlend: CameraBlend? = null
    var activeVirtualCamera: String = ""

    /** Trigger a camera shake impulse. */
    fun addImpulse(amplitude: Float = 0.5f, duration: Float = 0.3f, frequency: Float = 15f, sourceX: Float = 0f, sourceY: Float = 0f) {
        impulses.add(CameraImpulse(amplitude, duration, frequency, sourceX, sourceY))
    }

    /** Switch to a virtual camera with optional blending. */
    fun switchTo(cameraName: String, blendDuration: Float = 0.5f) {
        if (cameraName == activeVirtualCamera) return
        currentBlend = CameraBlend(activeVirtualCamera, cameraName, blendDuration)
    }

    /** Update all camera systems. */
    fun update(dt: Float, time: Float): Pair<Float, Float> {
        // Update blend
        currentBlend?.let { blend ->
            blend.update(dt)
            if (blend.isComplete) {
                activeVirtualCamera = blend.toCamera
                currentBlend = null
            }
        }

        // Update impulses
        var totalShakeX = 0f
        var totalShakeY = 0f
        val it = impulses.iterator()
        while (it.hasNext()) {
            val impulse = it.next()
            impulse.update(dt)
            if (impulse.isFinished) {
                it.remove()
            } else {
                val (sx, sy) = impulse.getOffset(time)
                totalShakeX += sx
                totalShakeY += sy
            }
        }

        return totalShakeX to totalShakeY
    }

    /** Clear all impulses. */
    fun clearImpulses() { impulses.clear() }

    /** Smooth damp helper — like Unity's SmoothDamp. */
    fun smoothDamp(current: Float, target: Float, velocity: FloatArray, smoothTime: Float, dt: Float): Float {
        val omega = 2f / smoothTime.coerceAtLeast(0.001f)
        val x = omega * dt
        val exp = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
        val change = current - target
        val temp = (velocity[0] + omega * change) * dt
        velocity[0] = (velocity[0] - omega * temp) * exp
        return target + (change + temp) * exp
    }
}

/** Cinematic camera path — smooth path through waypoints. */
class CameraPath : Component() {
    override val type = "CameraPath"
    val waypoints = mutableListOf<CameraWaypoint>()
    var duration: Float = 10f
    var loop: Boolean = false
    var lookAhead: Boolean = true

    override fun props() = listOf(
        Prop.F("Duration", { duration }, { duration = it.coerceAtLeast(0.1f) }, 1f),
        Prop.B("Loop", { loop }, { loop = it }),
        Prop.B("Look Ahead", { lookAhead }, { lookAhead = it }),
    )

    /** Evaluate position along the path at time t (0 to 1). */
    fun evaluate(t: Float): CameraWaypoint {
        if (waypoints.isEmpty()) return CameraWaypoint()
        if (waypoints.size == 1) return waypoints[0]

        var ct = if (loop) ((t % 1f) + 1f) % 1f else t.coerceIn(0f, 1f)
        val segCount = if (loop) waypoints.size else waypoints.size - 1
        val segT = ct * segCount
        val segIndex = segT.toInt().coerceIn(0, segCount - 1)
        val localT = segT - segIndex

        val a = waypoints[segIndex % waypoints.size]
        val b = waypoints[(segIndex + 1) % waypoints.size]

        // Smooth interpolation
        val eased = localT * localT * (3f - 2f * localT) // Smoothstep
        return CameraWaypoint(
            x = a.x + (b.x - a.x) * eased,
            y = a.y + (b.y - a.y) * eased,
            size = a.size + (b.size - a.size) * eased,
        )
    }
}

data class CameraWaypoint(
    var x: Float = 0f,
    var y: Float = 0f,
    var size: Float = 5f,
    var rotation: Float = 0f,
)
