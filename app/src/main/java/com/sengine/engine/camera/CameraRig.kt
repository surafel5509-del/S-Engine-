package com.sengine.engine.camera

import com.sengine.engine.core.Camera2D
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Frame-rate independent camera rig used by [com.sengine.engine.Engine] for 2D follow cameras.
 *
 * Features
 * * exponential smoothing with independent X / Y responsiveness,
 * * dead zone: the camera ignores small movements and stays parked,
 * * look ahead: leads a fast target by its velocity (clamped), which is what makes platformers
 *   feel responsive instead of dragging,
 * * smooth vertical follow with a bias so the player sits slightly below the middle of the screen,
 * * trauma based shake with quadratic falloff (the shake never snaps away in one frame),
 * * world bounds clamping and optional pixel snapping.
 *
 * The class contains no Android types so it is unit tested on a desktop JVM.
 */
class CameraRig {

    /** Current camera centre in world units. */
    var x = 0f
    var y = 0f
    /** Shake energy in 0..1. */
    var trauma = 0f
    /** Seconds since the rig was created; drives the shake noise. */
    var clock = 0f
    /** Smoothed look-ahead offset, kept so the lead eases in and out instead of jumping. */
    var lookX = 0f
    var lookY = 0f

    fun reset(tx: Float, ty: Float, camera: Camera2D) {
        val lead = clampLook(tx, ty, 0f, 0f, camera)
        x = lead[0] + camera.offsetX
        y = lead[1] + camera.offsetY + camera.verticalBias
        lookX = 0f
        lookY = 0f
        trauma = 0f
        clampToBounds(camera)
    }

    /**
     * Advances the rig one frame.
     *
     * @param targetX/Y world position of the follow target
     * @param targetVx/Vy target velocity in units per second (used for look ahead)
     * @param dt frame delta in seconds
     * @param ppu pixels per world unit, only used for pixel snapping
     */
    fun step(
        targetX: Float, targetY: Float, targetVx: Float, targetVy: Float,
        dt: Float, camera: Camera2D, ppu: Float = 0f,
    ) {
        clock += dt
        val dtc = dt.coerceIn(0f, 0.1f)

        // ---- look ahead (eased, clamped)
        val wantLookX = (targetVx * camera.lookAhead).coerceIn(-camera.lookAheadMax, camera.lookAheadMax)
        val wantLookY = (targetVy * camera.lookAhead * 0.5f).coerceIn(-camera.lookAheadMax * 0.5f, camera.lookAheadMax * 0.5f)
        val leadBlend = 1f - exp(-8f * dtc)
        lookX += (wantLookX - lookX) * leadBlend
        lookY += (wantLookY - lookY) * leadBlend

        val desiredX = targetX + lookX + camera.offsetX
        val desiredY = targetY + lookY + camera.offsetY + camera.verticalBias

        // ---- horizontal
        val dx = desiredX - x
        val deadX = camera.deadzoneX
        val goalX = if (deadX > 0f && abs(dx) <= deadX) x else desiredX - sign(dx) * deadX
        val kx = followFactor(if (camera.smoothing > 0f) camera.smoothing else 60f, dtc)
        x += (goalX - x) * kx

        // ---- vertical (own smoothing so jumps feel softer than strafes)
        val dy = desiredY - y
        val deadY = camera.deadzoneY
        val goalY = if (deadY > 0f && abs(dy) <= deadY) y else desiredY - sign(dy) * deadY
        val smoothingY = if (camera.smoothingY > 0f) camera.smoothingY else camera.smoothing
        val ky = followFactor(if (smoothingY > 0f) smoothingY else 60f, dtc)
        y += (goalY - y) * ky

        // ---- shake decay (quadratic trauma falloff feels punchier than linear)
        if (camera.shakeDecay > 0f && trauma > 0f) trauma = (trauma - camera.shakeDecay * dtc * (0.35f + trauma)).coerceAtLeast(0f)

        clampToBounds(camera)
        if (camera.pixelSnap && ppu > 0f) {
            x = Math.round(x * ppu) / ppu
            y = Math.round(y * ppu) / ppu
        }
    }

    /** Adds shake energy (0..1). Use ~0.3 for a hit, ~0.8 for an explosion. */
    fun addTrauma(amount: Float) {
        trauma = min(1f, trauma + max(0f, amount))
    }

    /** Screen-shake offset in world units for the current frame. */
    fun shakeOffset(amplitude: Float, out: FloatArray) {
        if (trauma <= 0f || amplitude <= 0f) { out[0] = 0f; out[1] = 0f; return }
        val s = trauma * trauma * amplitude
        val t = clock
        // Two incommensurate frequencies per axis: reads as noise, stays deterministic for tests.
        out[0] = s * (sin(t * 37.1f) * 0.7f + sin(t * 71.7f + 1.3f) * 0.3f)
        out[1] = s * (sin(t * 41.9f + 2.1f) * 0.7f + sin(t * 83.3f + 0.7f) * 0.3f)
    }

    private fun clampToBounds(camera: Camera2D) {
        if (camera.boundsMaxX <= camera.boundsMinX || camera.boundsMaxY <= camera.boundsMinY) return
        x = x.coerceIn(camera.boundsMinX, camera.boundsMaxX)
        y = y.coerceIn(camera.boundsMinY, camera.boundsMaxY)
    }

    private fun clampLook(tx: Float, ty: Float, vx: Float, vy: Float, camera: Camera2D): FloatArray {
        val lx = (vx * camera.lookAhead).coerceIn(-camera.lookAheadMax, camera.lookAheadMax)
        val ly = (vy * camera.lookAhead * 0.5f).coerceIn(-camera.lookAheadMax * 0.5f, camera.lookAheadMax * 0.5f)
        return floatArrayOf(tx + lx, ty + ly)
    }

    private fun followFactor(smoothing: Float, dt: Float): Float =
        if (smoothing <= 0f) 1f else (1f - exp(-smoothing * dt)).coerceIn(0f, 1f)

    private fun sign(v: Float) = if (v < 0f) -1f else 1f

    companion object {
        /** Camera size that keeps a consistent vertical framing on wide screens. */
        fun sizeForAspect(baseSize: Float, aspect: Float): Float =
            if (aspect >= 1f) baseSize else baseSize / aspect.coerceAtLeast(0.1f)
    }
}
