package com.sengine.engine.lighting2d

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import kotlin.math.*

// ─── 2D Dynamic Lighting System ──────────────────────────────────────────

/**
 * Professional 2D lighting with:
 * - Point lights with falloff
 * - Directional lights
 * - Light masks / cookies
 * - Normal-mapped 2D lighting
 * - Light blending modes
 * - Shadow casting
 */

/** A 2D light source. */
class Light2D : Component() {
    override val type = "Light2D"
    var lightType: Int = 0 // 0=Point, 1=Directional, 2=Spot
    var color: Int = 0xFFFFFFEE.toInt()
    var intensity: Float = 1f
    var range: Float = 5f
    var innerRange: Float = 0f // Inner radius (full brightness)
    var angle: Float = 360f    // For spot lights (degrees)
    var outerAngle: Float = 45f // For spot lights
    var castsShadows: Boolean = false
    var shadowResolution: Int = 512
    var mask: String = ""      // Cookie/light mask texture
    var useNormalMap: Boolean = false
    var blendMode: Int = 0     // 0=Additive, 1=Multiply, 2=Overlay
    var sortingOrder: Int = -10
    var flicker: Boolean = false
    var flickerMin: Float = 0.8f
    var flickerMax: Float = 1.2f
    var flickerSpeed: Float = 5f

    // Runtime
    @Transient var flickerTimer: Float = 0f
    @Transient var currentIntensity: Float = 1f

    override fun props() = listOf(
        Prop.Choice("Type", listOf("Point", "Directional", "Spot"), { lightType }, { lightType = it }),
        Prop.Color("Color", { color }, { color = it }),
        Prop.F("Intensity", { intensity }, { intensity = it.coerceAtLeast(0f) }, 0.1f),
        Prop.F("Range", { range }, { range = it.coerceAtLeast(0.1f) }, 0.5f),
        Prop.F("Inner Range", { innerRange }, { innerRange = it.coerceAtLeast(0f) }, 0.5f),
        Prop.F("Angle", { angle }, { angle = it.coerceIn(0f, 360f) }, 5f),
        Prop.B("Cast Shadows", { castsShadows }, { castsShadows = it }),
        Prop.I("Shadow Resolution", { shadowResolution }, { shadowResolution = it.coerceIn(64, 2048) }),
        Prop.Asset("Mask", com.sengine.engine.core.AssetKind.TEXTURE, { mask }, { mask = it }),
        Prop.B("Normal Map", { useNormalMap }, { useNormalMap = it }),
        Prop.Choice("Blend", listOf("Additive", "Multiply", "Overlay"), { blendMode }, { blendMode = it }),
        Prop.B("Flicker", { flicker }, { flicker = it }),
        Prop.F("Flicker Min", { flickerMin }, { flickerMin = it.coerceIn(0f, 2f) }, 0.05f),
        Prop.F("Flicker Max", { flickerMax }, { flickerMax = it.coerceIn(0f, 3f) }, 0.05f),
        Prop.F("Flicker Speed", { flickerSpeed }, { flickerSpeed = it.coerceAtLeast(0.1f) }, 0.5f),
    )

    fun updateFlicker(dt: Float) {
        if (!flicker) { currentIntensity = intensity; return }
        flickerTimer += dt * flickerSpeed
        val t = (sin(flickerTimer * 6.28f) * 0.5f + 0.5f).toFloat()
        currentIntensity = intensity * (flickerMin + (flickerMax - flickerMin) * t)
    }

    /** Calculate light contribution at a point. */
    fun contributionAt(px: Float, py: Float, lx: Float, ly: Float): Float {
        val dx = px - lx
        val dy = py - ly
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > range) return 0f

        var falloff = if (dist <= innerRange) 1f
        else 1f - (dist - innerRange) / (range - innerRange).coerceAtLeast(0.001f)

        // Spot light angle check
        if (lightType == 2) {
            val lightAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            val halfAngle = outerAngle * 0.5f
            val angleDiff = abs(((lightAngle - angle + 180f) % 360f) - 180f)
            if (angleDiff > halfAngle) falloff *= 1f - (angleDiff - halfAngle) / halfAngle.coerceAtLeast(1f)
        }

        return (falloff * currentIntensity).coerceIn(0f, 1f)
    }
}

/** Light manager — collects and manages all 2D lights in a scene. */
class Light2DManager {
    val lights = mutableListOf<LightData>()
    var ambientColor: Int = 0xFF202030.toInt()
    var ambientIntensity: Float = 0.2f
    var maxLightsPerObject: Int = 4

    fun update(dt: Float) {
        for (light in lights) {
            light.flickerTimer += dt * light.flickerSpeed
            if (light.flicker) {
                val t = (sin(light.flickerTimer * light.flickerSpeed * 6.28f) * 0.5f + 0.5f).toFloat()
                light.currentIntensity = light.intensity * (light.flickerMin + (light.flickerMax - light.flickerMin) * t)
            }
        }
    }

    /** Get lights affecting a specific point, sorted by contribution. */
    fun getLightsAt(x: Float, y: Float): List<Pair<LightData, Float>> {
        return lights.mapNotNull { light ->
            val contrib = light.contributionAt(x, y, light.x, light.y)
            if (contrib > 0.01f) light to contrib else null
        }.sortedByDescending { it.second }.take(maxLightsPerObject)
    }

    fun addLight(light: LightData) { lights.add(light) }
    fun removeLight(light: LightData) { lights.remove(light) }
    fun clear() { lights.clear() }
}

/** Runtime light data (extracted from Light2D component). */
class LightData(
    var x: Float = 0f,
    var y: Float = 0f,
    var lightType: Int = 0,
    var color: Int = 0xFFFFFFEE.toInt(),
    var intensity: Float = 1f,
    var range: Float = 5f,
    var innerRange: Float = 0f,
    var angle: Float = 0f,
    var outerAngle: Float = 45f,
    var flicker: Boolean = false,
    var flickerMin: Float = 0.8f,
    var flickerMax: Float = 1.2f,
    var flickerSpeed: Float = 5f,
    var flickerTimer: Float = 0f,
    var currentIntensity: Float = 1f,
    var blendMode: Int = 0,
) {
    fun contributionAt(px: Float, py: Float, lx: Float, ly: Float): Float {
        val dx = px - lx
        val dy = py - ly
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > range) return 0f
        var falloff = if (dist <= innerRange) 1f
        else 1f - (dist - innerRange) / (range - innerRange).coerceAtLeast(0.001f)
        return (falloff * currentIntensity).coerceIn(0f, 1f)
    }
}

/** Global illumination proxy — baked light contribution. */
class LightProbe2D(
    var x: Float = 0f,
    var y: Float = 0f,
    var radius: Float = 2f,
    var color: Int = 0xFF808080.toInt(),
    var intensity: Float = 0.5f,
)

/** Day/Night cycle controller. */
class DayNightCycle : Component() {
    override val type = "DayNightCycle"
    var timeOfDay: Float = 12f // 0-24 hours
    var dayDuration: Float = 600f // Seconds per full day
    var autoAdvance: Boolean = true
    var speed: Float = 1f

    // Color keyframes
    var dawnColor: Int = 0xFFFF9966.toInt()
    var dayColor: Int = 0xFFFFFFFF.toInt()
    var duskColor: Int = 0xFFFF6633.toInt()
    var nightColor: Int = 0xFF111133.toInt()

    override fun props() = listOf(
        Prop.F("Time of Day", { timeOfDay }, { timeOfDay = it.coerceIn(0f, 24f) }, 0.5f),
        Prop.F("Day Duration", { dayDuration }, { dayDuration = it.coerceAtLeast(1f) }, 30f),
        Prop.B("Auto Advance", { autoAdvance }, { autoAdvance = it }),
        Prop.F("Speed", { speed }, { speed = it.coerceAtLeast(0.1f) }, 0.5f),
        Prop.Color("Dawn", { dawnColor }, { dawnColor = it }),
        Prop.Color("Day", { dayColor }, { dayColor = it }),
        Prop.Color("Dusk", { duskColor }, { duskColor = it }),
        Prop.Color("Night", { nightColor }, { nightColor = it }),
    )

    fun update(dt: Float) {
        if (autoAdvance) {
            timeOfDay += (24f / dayDuration) * dt * speed
            if (timeOfDay >= 24f) timeOfDay -= 24f
        }
    }

    /** Get current ambient color based on time of day. */
    fun getAmbientColor(): Int {
        return when {
            timeOfDay < 6f -> lerpColor(nightColor, dawnColor, timeOfDay / 6f)
            timeOfDay < 8f -> lerpColor(dawnColor, dayColor, (timeOfDay - 6f) / 2f)
            timeOfDay < 17f -> dayColor
            timeOfDay < 19f -> lerpColor(dayColor, duskColor, (timeOfDay - 17f) / 2f)
            timeOfDay < 21f -> lerpColor(duskColor, nightColor, (timeOfDay - 19f) / 2f)
            else -> nightColor
        }
    }

    fun getAmbientIntensity(): Float {
        return when {
            timeOfDay < 6f -> 0.1f
            timeOfDay < 8f -> 0.1f + 0.9f * ((timeOfDay - 6f) / 2f)
            timeOfDay < 17f -> 1f
            timeOfDay < 19f -> 1f - 0.7f * ((timeOfDay - 17f) / 2f)
            timeOfDay < 21f -> 0.3f - 0.2f * ((timeOfDay - 19f) / 2f)
            else -> 0.1f
        }
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ct = t.coerceIn(0f, 1f)
        val ar = (a ushr 16) and 0xFF
        val ag = (a ushr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b ushr 16) and 0xFF
        val bg = (b ushr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * ct).toInt()
        val g = (ag + (bg - ag) * ct).toInt()
        val bl = (ab + (bb - ab) * ct).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
