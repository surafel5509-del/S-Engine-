package com.sengine.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import com.sengine.engine.Input
import kotlin.math.abs

/**
 * Optional, calibrated phone-tilt input for driving and balance controls.
 * The sensor is low-pass filtered and has a small center dead-zone so a resting phone
 * does not make a vehicle slowly roll.
 */
class TiltInputController(context: Context, private val input: Input) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var registered = false
    private var calibrated = false
    private var neutral = 0f
    private var filtered = 0f

    fun start() {
        if (registered) return
        calibrated = false
        filtered = 0f
        registered = accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: false
        if (!registered) input.rawTiltX = 0f
    }

    fun stop() {
        if (registered) sensorManager.unregisterListener(this)
        registered = false
        calibrated = false
        filtered = 0f
        input.rawTiltX = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val lateral = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> event.values[1]
            Surface.ROTATION_180 -> -event.values[0]
            Surface.ROTATION_270 -> -event.values[1]
            else -> event.values[0]
        } / SensorManager.GRAVITY_EARTH
        if (!calibrated) {
            neutral = lateral
            calibrated = true
        }
        val target = ((lateral - neutral) * 2.8f).coerceIn(-1f, 1f)
        filtered += (target - filtered) * 0.18f
        input.rawTiltX = if (abs(filtered) < 0.045f) 0f else filtered
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
