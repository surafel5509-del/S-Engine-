package com.sengine.engine.particlesv2

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import kotlin.math.*

// ─── Advanced Particle System v2 ──────────────────────────────────────────

/**
 * Professional particle system with sub-emitters, trails, force zones,
 * collision, and advanced curve-driven properties.
 */

/** Color-over-lifetime gradient. */
class ColorGradient(
    val colors: MutableList<GradientStop> = mutableListOf(
        GradientStop(0f, 0xFFFFFFFF.toInt()),
        GradientStop(1f, 0x00FFFFFF.toInt())
    )
) {
    fun evaluate(t: Float): Int {
        if (colors.isEmpty()) return 0xFFFFFFFF.toInt()
        if (colors.size == 1) return colors[0].color
        val ct = t.coerceIn(0f, 1f)
        for (i in 0 until colors.size - 1) {
            if (ct >= colors[i].time && ct <= colors[i + 1].time) {
                val range = colors[i + 1].time - colors[i].time
                val frac = if (range > 0f) (ct - colors[i].time) / range else 0f
                return lerpColor(colors[i].color, colors[i + 1].color, frac)
            }
        }
        return colors.last().color
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ar = (a ushr 16) and 0xFF
        val ag = (a ushr 8) and 0xFF
        val ab = a and 0xFF
        val aa = (a ushr 24) and 0xFF
        val br = (b ushr 16) and 0xFF
        val bg = (b ushr 8) and 0xFF
        val bb = b and 0xFF
        val ba = (b ushr 24) and 0xFF
        val r = (ar + (br - ar) * t).toInt()
        val g = (ag + (bg - ag) * t).toInt()
        val bl = (ab + (bb - ab) * t).toInt()
        val al = (aa + (ba - aa) * t).toInt()
        return (al shl 24) or (r shl 16) or (g shl 8) or bl
    }
}

data class GradientStop(val time: Float, val color: Int)

/** Curve for size/speed/rotation over lifetime. */
class ParticleCurve(
    val keys: MutableList<CurveKey> = mutableListOf(
        CurveKey(0f, 1f),
        CurveKey(1f, 0f)
    )
) {
    fun evaluate(t: Float): Float {
        if (keys.isEmpty()) return 1f
        if (keys.size == 1) return keys[0].value
        val ct = t.coerceIn(0f, 1f)
        for (i in 0 until keys.size - 1) {
            if (ct >= keys[i].time && ct <= keys[i + 1].time) {
                val range = keys[i + 1].time - keys[i].time
                val frac = if (range > 0f) (ct - keys[i].time) / range else 0f
                return keys[i].value + (keys[i + 1].value - keys[i].value) * frac
            }
        }
        return keys.last().value
    }
}

data class CurveKey(val time: Float, val value: Float)

/** Force zone that affects particles within its bounds. */
class ForceZone(
    var type: Int = 0, // 0=Point, 1=Directional, 2=Vortex, 3=Noise
    var centerX: Float = 0f,
    var centerY: Float = 0f,
    var radius: Float = 5f,
    var forceX: Float = 0f,
    var forceY: Float = 0f,
    var strength: Float = 1f,
    var falloff: Boolean = true,
) {
    fun apply(particleX: Float, particleY: Float, dt: Float): Pair<Float, Float> {
        val dx = particleX - centerX
        val dy = particleY - centerY
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > radius) return 0f to 0f

        val falloffMult = if (falloff && radius > 0f) 1f - (dist / radius) else 1f

        return when (type) {
            0 -> { // Point — push away from center
                val nx = if (dist > 0.001f) dx / dist else 0f
                val ny = if (dist > 0.001f) dy / dist else 0f
                (nx * strength * falloffMult * dt) to (ny * strength * falloffMult * dt)
            }
            1 -> { // Directional
                (forceX * strength * falloffMult * dt) to (forceY * strength * falloffMult * dt)
            }
            2 -> { // Vortex — tangential force
                val nx = if (dist > 0.001f) -dy / dist else 0f
                val ny = if (dist > 0.001f) dx / dist else 0f
                (nx * strength * falloffMult * dt) to (ny * strength * falloffMult * dt)
            }
            3 -> { // Noise
                val nx = sin(particleX * 0.5f + particleY * 0.3f) * strength * falloffMult * dt
                val ny = cos(particleX * 0.3f + particleY * 0.5f) * strength * falloffMult * dt
                nx to ny
            }
            else -> 0f to 0f
        }
    }
}

/** A particle trail that follows the particle. */
class TrailConfig(
    var enabled: Boolean = false,
    var time: Float = 0.5f,
    var minWidth: Float = 0f,
    var maxWidth: Float = 0.3f,
    var colorOverTrail: ColorGradient = ColorGradient(),
    var textureMode: Int = 0, // 0=Stretch, 1=Tile
    var dieWithParticle: Boolean = true,
    var minimumVertexDistance: Float = 0.1f,
)

/** Sub-emitter configuration. */
class SubEmitterConfig(
    var enabled: Boolean = false,
    var type: Int = 0, // 0=Birth, 1=Death, 2=Collision, 3=Trigger
    var emitCount: Int = 5,
    var inheritVelocity: Boolean = true,
    var inheritColor: Boolean = true,
    var inheritSize: Boolean = true,
    var subParticleTexture: String = "",
    var subLifetime: Float = 0.5f,
    var subSpeed: Float = 2f,
    var subSize: Float = 0.2f,
)

/** Main particle system v2 component. */
class ParticleSystemV2 : Component() {
    override val type = "ParticleSystemV2"

    // Emission
    var emitting: Boolean = true
    var rate: Float = 20f
    var bursts: MutableList<BurstConfig> = mutableListOf()
    var duration: Float = 5f
    var looping: Boolean = true
    var prewarm: Boolean = false
    var startDelay: Float = 0f

    // Shape
    var shape: Int = 0 // 0=Point, 1=Circle, 2=Rectangle, 3=Cone, 4=Edge
    var shapeRadius: Float = 1f
    var shapeWidth: Float = 2f
    var shapeHeight: Float = 2f
    var shapeAngle: Float = 25f
    var shapeArc: Float = 360f

    // Velocity
    var startSpeed: Float = 5f
    var startSpeedVariance: Float = 1f
    var gravityX: Float = 0f
    var gravityY: Float = -9.81f
    var drag: Float = 0f
    var angularVelocity: Float = 0f
    var angularVelocityVariance: Float = 0f

    // Appearance
    var startSize: Float = 0.5f
    var startSizeVariance: Float = 0.1f
    var sizeOverLifetime: ParticleCurve = ParticleCurve()
    var colorOverLifetime: ColorGradient = ColorGradient()
    var rotationOverLifetime: Float = 0f
    var texture: String = ""
    var blendMode: Int = 0 // 0=Alpha, 1=Additive, 2=Multiply

    // Collision
    var collisionEnabled: Boolean = false
    var collisionRadius: Float = 0.2f
    var collisionBounce: Float = 0.5f
    var collisionLifetimeLoss: Float = 0f
    var collisionWithTag: String = ""

    // Force zones
    var forceZones: MutableList<ForceZone> = mutableListOf()

    // Trail
    var trail: TrailConfig = TrailConfig()

    // Sub-emitters
    var subEmitters: MutableList<SubEmitterConfig> = mutableListOf()

    // Renderer
    var renderMode: Int = 0 // 0=Billboard, 1=Horizontal, 2=Vertical, 3=Stretch
    var sortingOrder: Int = 0
    var maxParticles: Int = 1000

    // Runtime
    @Transient var particles: MutableList<ParticleV2> = mutableListOf()
    @Transient var emitTimer: Float = 0f
    @Transient var age: Float = 0f

    override fun props() = listOf(
        Prop.B("Emitting", { emitting }, { emitting = it }),
        Prop.F("Rate", { rate }, { rate = it.coerceAtLeast(0f) }, 1f),
        Prop.F("Duration", { duration }, { duration = it.coerceAtLeast(0.1f) }, 0.5f),
        Prop.B("Looping", { looping }, { looping = it }),
        Prop.Choice("Shape", listOf("Point", "Circle", "Rectangle", "Cone", "Edge"), { shape }, { shape = it }),
        Prop.F("Shape Radius", { shapeRadius }, { shapeRadius = it.coerceAtLeast(0f) }, 0.5f),
        Prop.F("Start Speed", { startSpeed }, { startSpeed = it }, 0.5f),
        Prop.F("Start Size", { startSize }, { startSize = it.coerceAtLeast(0.01f) }, 0.1f),
        Prop.F("Gravity X", { gravityX }, { gravityX = it }, 0.5f),
        Prop.F("Gravity Y", { gravityY }, { gravityY = it }, 0.5f),
        Prop.F("Drag", { drag }, { drag = it.coerceAtLeast(0f) }, 0.1f),
        Prop.I("Max Particles", { maxParticles }, { maxParticles = it.coerceAtLeast(1) }),
        Prop.Choice("Blend Mode", listOf("Alpha", "Additive", "Multiply"), { blendMode }, { blendMode = it }),
        Prop.B("Collision", { collisionEnabled }, { collisionEnabled = it }),
        Prop.Asset("Texture", com.sengine.engine.core.AssetKind.TEXTURE, { texture }, { texture = it }),
    )

    fun emit(count: Int, x: Float, y: Float) {
        for (i in 0 until count) {
            if (particles.size >= maxParticles) break
            val p = spawnParticle(x, y)
            particles.add(p)
        }
    }

    fun update(worldX: Float, worldY: Float, dt: Float) {
        age += dt

        // Emission
        if (emitting) {
            emitTimer += dt
            val interval = if (rate > 0f) 1f / rate else Float.MAX_VALUE
            while (emitTimer >= interval && particles.size < maxParticles) {
                emitTimer -= interval
                particles.add(spawnParticle(worldX, worldY))
            }
        }

        // Update particles
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            if (p.age >= p.lifetime) {
                // Death sub-emitter
                for (sub in subEmitters) {
                    if (sub.enabled && sub.type == 1) {
                        for (j in 0 until sub.emitCount) {
                            // Sub-particles would be spawned here
                        }
                    }
                }
                it.remove()
                continue
            }

            val t = p.age / p.lifetime

            // Apply forces
            for (zone in forceZones) {
                val (fx, fy) = zone.apply(p.x, p.y, dt)
                p.vx += fx; p.vy += fy
            }

            // Gravity
            p.vx += gravityX * dt
            p.vy += gravityY * dt

            // Drag
            if (drag > 0f) {
                p.vx *= (1f - drag * dt)
                p.vy *= (1f - drag * dt)
            }

            // Move
            p.x += p.vx * dt
            p.y += p.vy * dt

            // Rotation
            p.rotation += p.angularVelocity * dt

            // Size over lifetime
            p.currentSize = startSize * sizeOverLifetime.evaluate(t)

            // Color over lifetime
            p.currentColor = colorOverLifetime.evaluate(t)
        }
    }

    private fun spawnParticle(worldX: Float, worldY: Float): ParticleV2 {
        val p = ParticleV2()
        p.lifetime = (0.5f + Math.random().toFloat()) // Simplified

        // Shape-based spawn position
        when (shape) {
            0 -> { p.x = worldX; p.y = worldY }
            1 -> { // Circle
                val angle = Math.random() * 2 * PI
                val r = Math.random().toFloat() * shapeRadius
                p.x = worldX + cos(angle).toFloat() * r
                p.y = worldY + sin(angle).toFloat() * r
            }
            2 -> { // Rectangle
                p.x = worldX + (Math.random().toFloat() - 0.5f) * shapeWidth
                p.y = worldY + (Math.random().toFloat() - 0.5f) * shapeHeight
            }
            3 -> { // Cone
                val angle = Math.toRadians(shapeAngle.toDouble()).toFloat()
                val dir = (Math.random().toFloat() - 0.5f) * 2f * angle
                p.x = worldX + sin(dir) * shapeRadius
                p.y = worldY + cos(dir) * shapeRadius
            }
            4 -> { // Edge
                p.x = worldX + (Math.random().toFloat() - 0.5f) * shapeWidth
                p.y = worldY
            }
        }

        // Velocity
        val speed = startSpeed + (Math.random().toFloat() - 0.5f) * startSpeedVariance * 2f
        val velAngle = Math.random() * 2 * PI
        p.vx = cos(velAngle).toFloat() * speed
        p.vy = sin(velAngle).toFloat() * speed

        // Size
        p.currentSize = startSize + (Math.random().toFloat() - 0.5f) * startSizeVariance * 2f

        // Angular velocity
        p.angularVelocity = angularVelocity + (Math.random().toFloat() - 0.5f) * angularVelocityVariance * 2f

        return p
    }
}

/** A single particle instance. */
class ParticleV2(
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var age: Float = 0f,
    var lifetime: Float = 1f,
    var currentSize: Float = 0.5f,
    var currentColor: Int = 0xFFFFFFFF.toInt(),
    var rotation: Float = 0f,
    var angularVelocity: Float = 0f,
)

data class BurstConfig(
    var time: Float = 0f,
    var count: Int = 10,
    var cycles: Int = 1,
    var interval: Float = 0.1f,
)
