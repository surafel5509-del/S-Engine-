package com.sengine.project

import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Joint2D
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SpriteRenderer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pre-made 2D objects available from the editor's **Add Object** menu.
 *
 * Every preset builds real scene content (sprites plus colliders plus rigidbodies where it makes sense),
 * not a placeholder: dropping "Bumpy Ground (~)" into a scene gives a playable terrain strip, dropping
 * "Tipping Crate Stack" gives a physics demonstration that already works in Play mode.
 *
 * Coordinates passed to [Preset.build] are world coordinates; everything else is authored in the
 * object's local space so the result can be moved, rotated and re-scaled like any other object.
 */
object ObjectPresets2D {

    class Preset(
        val id: String,
        val name: String,
        val category: String,
        val description: String,
        val build: (Scene, Float, Float) -> GameObject,
    )

    private val presets = ArrayList<Preset>()

    private fun add(id: String, name: String, category: String, description: String, build: (Scene, Float, Float) -> GameObject) {
        presets.add(Preset(id, name, category, description, build))
    }

    val all: List<Preset> get() = presets

    fun find(id: String): Preset? = presets.firstOrNull { it.id == id }

    /** Presets grouped for the editor menu, in a stable display order. */
    fun byCategory(): LinkedHashMap<String, List<Preset>> {
        val order = listOf("Terrain", "Shapes", "Physics", "Gameplay", "Effects")
        val map = LinkedHashMap<String, List<Preset>>()
        for (c in order) {
            val list = presets.filter { it.category == c }
            if (list.isNotEmpty()) map[c] = list
        }
        for (p in presets) if (!map.containsKey(p.category)) {
            map[p.category] = presets.filter { it.category == p.category }
        }
        return map
    }

    // ------------------------------------------------------------------ helpers

    private fun quad(scene: Scene, owner: GameObject, x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int, order: Int = -2) {
        val cx = (x1 + x2) * 0.5f
        val cy = (y1 + y2) * 0.5f
        val len = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
        if (len < 1e-4f) return
        val angle = Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()
        val go = shape(scene, owner, "Surface", cx, cy, len, thickness, color, 0, order)
        go.rotation = angle
    }

    private fun terrainPoints(segments: Int, length: Float, fn: (Float) -> Float): MutableList<Float> {
        val pts = ArrayList<Float>()
        val step = length / segments
        for (i in 0..segments) {
            val t = -length * 0.5f + i * step
            pts.add(t)
            pts.add(fn(t))
        }
        return pts
    }

    private fun buildTerrain(
        scene: Scene, x: Float, y: Float, name: String, points: List<Float>, color: Int,
        thickness: Float = 1.2f, fillColor: Int? = null,
    ): GameObject {
        val go = scene.create(name).also { it.x = x; it.y = y; it.order = 0 }
        go.add(Collider2D().also { c ->
            c.shape = 2
            c.thickness = thickness
            c.setPoints(points)
        })
        var i = 0
        while (i + 3 < points.size) {
            quad(scene, go, points[i], points[i + 1], points[i + 2], points[i + 3], 0.35f, color)
            if (fillColor != null) {
                // A slightly thicker band under the surface reads as soil / rock.
                quad(scene, go, points[i], points[i + 1] - 0.16f, points[i + 2], points[i + 3] - 0.16f, 0.45f, fillColor, -3)
            }
            i += 2
        }
        return go
    }

    /** Adds a sized shape sprite to a child object (sprite scale lives on the transform). */
    private fun shape(scene: Scene, parent: GameObject, name: String, x: Float, y: Float, sw: Float, sh: Float, color: Int, kind: Int = 0, order: Int = 0): GameObject {
        val go = scene.create(name, parent)
        go.x = x; go.y = y; go.scaleX = sw; go.scaleY = sh; go.order = order
        go.add(SpriteRenderer().also { it.color = color; it.shape = kind })
        return go
    }

    private fun boxPreset(scene: Scene, x: Float, y: Float, name: String, w: Float, h: Float, color: Int, dynamic: Boolean): GameObject {
        val go = scene.create(name).also { it.x = x; it.y = y; it.scaleX = w; it.scaleY = h }
        go.add(SpriteRenderer().also { it.color = color; it.shape = 0 })
        go.add(Collider2D().also { it.width = 1f; it.height = 1f })
        if (dynamic) go.add(Rigidbody2D().also { rb -> rb.freezeRotation = false; rb.mass = w * h * 4f })
        return go
    }

    // ------------------------------------------------------------------ presets

    init {
        // ---- Terrain
        add("bumpy_ground", "Bumpy Ground (~)", "Terrain",
            "Uneven rolling ground with sine bumps and a rock band underneath. Rides, jumps and lands correctly."
        ) { s, x, y ->
            val pts = terrainPoints(24, 16f) { t -> 0.22f * sin((t * 1.6f).toDouble()).toFloat() + 0.08f * sin((t * 5.1f).toDouble()).toFloat() }
            buildTerrain(s, x, y, "Bumpy Ground", pts, 0xFF6CBF5A.toInt(), 1.6f, 0xFF5A4632.toInt())
        }

        add("rolling_hills", "Rolling Hills", "Terrain",
            "Long smooth hills for driving, sledding and rolling physics games."
        ) { s, x, y ->
            val pts = terrainPoints(28, 30f) { t -> 1.4f * sin((t * 0.28).toDouble()).toFloat() }
            buildTerrain(s, x, y, "Rolling Hills", pts, 0xFF7FCB63.toInt(), 2.2f, 0xFF4C3B2A.toInt())
        }

        add("flat_ground", "Flat Ground", "Terrain",
            "A wide ground strip with a solid terrain collider. The default floor for new levels."
        ) { s, x, y ->
            buildTerrain(s, x, y, "Ground", listOf(-12f, 0f, 12f, 0f), 0xFF6CBF5A.toInt(), 1.5f, 0xFF5A4632.toInt())
        }

        add("ramp", "Ramp", "Terrain",
            "A 22 degree wedge you can drive up or slide down."
        ) { s, x, y ->
            val go = s.create("Ramp").also { it.x = x; it.y = y }
            val pts = listOf(-3f, 0f, 3f, 2.4f)
            go.add(Collider2D().also { c -> c.shape = 2; c.thickness = 1.8f; c.setPoints(pts) })
            quad(s, go, -3f, 0f, 3f, 2.4f, 0.4f, 0xFF8E7A5B.toInt())
            quad(s, go, -3f, -0.2f, 3f, 2.2f, 1.6f, 0xFF6B5B45.toInt(), -3)
            go
        }

        add("stairs", "Stairs", "Terrain",
            "Five steps. Useful for climbing tests and platformer blockouts."
        ) { s, x, y ->
            val go = s.create("Stairs").also { it.x = x; it.y = y }
            val steps = 5
            val run = 0.8f
            val rise = 0.45f
            val pts = ArrayList<Float>()
            for (i in 0 until steps) {
                pts.add(-steps * run * 0.5f + i * run); pts.add((i + 1) * rise)
                pts.add(-steps * run * 0.5f + (i + 1) * run); pts.add((i + 1) * rise)
            }
            go.add(Collider2D().also { c -> c.shape = 2; c.thickness = 1.6f; c.setPoints(pts) })
            var i = 0
            while (i + 3 < pts.size) {
                quad(s, go, pts[i], pts[i + 1], pts[i + 2], pts[i + 3], 0.18f, 0xFFB7B7B7.toInt())
                i += 4
            }
            go
        }

        add("floating_island", "Floating Island", "Terrain",
            "A small island: flat top, rocky underside, ready for jumping puzzles."
        ) { s, x, y ->
            val go = s.create("Floating Island").also { it.x = x; it.y = y }
            go.add(Collider2D().also { c ->
                c.shape = 3
                c.setPoints(listOf(-3f, 0f, 3f, 0f, 2.2f, -1.6f, 0f, -3f, -2.2f, -1.6f))
            })
            go.add(SpriteRenderer().also { it.color = 0xFF8B6B4A.toInt() })
            quad(s, go, -3f, 0f, 3f, 0f, 0.4f, 0xFF6CBF5A.toInt(), -1)
            go
        }

        // ---- Shapes
        add("crate", "Crate", "Physics",
            "A dynamic wooden crate. Falls, bounces, tips over edges and stacks with other crates."
        ) { s, x, y -> boxPreset(s, x, y, "Crate", 1f, 1f, 0xFFB07A45.toInt(), true) }

        add("crate_stack", "Crate Stack", "Physics",
            "Five crates stacked as a parent group of dynamic bodies — knock it down and they all tumble."
        ) { s, x, y ->
            val root = s.create("Crate Stack").also { it.x = x; it.y = y }
            for (i in 0 until 5) {
                val go = s.create("Crate", root).also { child ->
                    child.x = if (i % 2 == 0) 0f else 0.04f
                    child.y = i * 1.02f
                }
                go.add(SpriteRenderer().also { it.color = 0xFFB07A45.toInt() })
                go.add(Collider2D())
                go.add(Rigidbody2D().also { rb -> rb.freezeRotation = false })
            }
            root
        }

        add("tipping_beam", "Tipping Beam", "Physics",
            "A long plank balanced on a block. Push it and it leans, then tips over once its centre of mass passes the edge."
        ) { s, x, y ->
            val root = s.create("Tipping Beam").also { it.x = x; it.y = y }
            val block = s.create("Pivot", root).also { it.y = 0.25f; it.scaleX = 0.5f; it.scaleY = 0.5f }
            block.add(SpriteRenderer().also { it.color = 0xFF6E6E6E.toInt() })
            block.add(Collider2D().also { c -> c.width = 0.5f; c.height = 0.5f })

            val plank = s.create("Plank", root).also { it.y = 1.1f; it.rotation = 2f; it.scaleX = 5f; it.scaleY = 0.3f }
            plank.add(SpriteRenderer().also { it.color = 0xFFD9A066.toInt() })
            plank.add(Rigidbody2D().also { rb -> rb.freezeRotation = false; rb.mass = 3f; rb.friction = 0.9f })
            plank.add(Collider2D().also { c -> c.width = 5f; c.height = 0.3f })
            root
        }

        add("boulder", "Boulder", "Physics",
            "A round boulder with high friction inertia — rolls downhill and off ledges."
        ) { s, x, y ->
            val go = s.create("Boulder").also { it.x = x; it.y = y; it.scaleX = 1.2f; it.scaleY = 1.2f }
            go.add(SpriteRenderer().also { it.color = 0xFF8A8A8A.toInt(); it.shape = 1 })
            go.add(Rigidbody2D().also { rb -> rb.freezeRotation = false; rb.mass = 3f; rb.bounciness = 0.15f })
            go.add(Collider2D().also { c -> c.shape = 1; c.radius = 0.5f })
            go
        }

        add("rock", "Rock (polygon)", "Shapes",
            "A convex rock built from a polygon collider — demonstrates arbitrary convex shapes."
        ) { s, x, y ->
            val go = s.create("Rock").also { it.x = x; it.y = y }
            go.add(SpriteRenderer().also { it.color = 0xFF7D7F86.toInt() })
            go.add(Collider2D().also { c ->
                c.shape = 3
                c.setPoints(listOf(-1f, -0.4f, -0.3f, -0.9f, 0.6f, -0.7f, 1f, 0.1f, 0.4f, 0.8f, -0.5f, 0.6f))
            })
            go
        }

        add("platform", "Platform", "Shapes",
            "A static platform box with a visible edge highlight."
        ) { s, x, y -> boxPreset(s, x, y, "Platform", 4f, 0.5f, 0xFF9AA4B2.toInt(), false) }

        // ---- Gameplay
        add("wheel", "Vehicle Wheel", "Gameplay",
            "A wheel body with a friction collider. Pair it with a Wheel joint to build cars and bikes."
        ) { s, x, y ->
            val go = s.create("Wheel").also { it.x = x; it.y = y; it.scaleX = 0.9f; it.scaleY = 0.9f }
            go.add(SpriteRenderer().also { it.color = 0xFF2F3136.toInt(); it.shape = 1 })
            go.add(Rigidbody2D().also { rb -> rb.freezeRotation = false; rb.friction = 1.4f; rb.mass = 0.7f })
            go.add(Collider2D().also { c -> c.shape = 1; c.radius = 0.5f })
            go
        }

        add("car_rig", "Car Rig", "Gameplay",
            "Chassis plus two driven wheels wired with Wheel joints. Flip the motor torque sign to reverse."
        ) { s, x, y ->
            val root = s.create("Car").also { it.x = x; it.y = y }
            val chassis = s.create("Chassis", root).also { it.y = 0.55f; it.scaleX = 2.4f; it.scaleY = 0.5f }
            chassis.add(SpriteRenderer().also { it.color = 0xFFE05A47.toInt() })
            chassis.add(Rigidbody2D().also { rb -> rb.mass = 6f; rb.freezeRotation = false; rb.inertiaScale = 2f })
            chassis.add(Collider2D().also { c -> c.width = 2.4f; c.height = 0.5f })
            for (dx in listOf(-0.85f, 0.85f)) {
                val wheel = s.create("Wheel", root).also { it.x = dx; it.y = 0f; it.scaleX = 0.9f; it.scaleY = 0.9f }
                wheel.add(SpriteRenderer().also { it.color = 0xFF23252A.toInt(); it.shape = 1 })
                wheel.add(Rigidbody2D().also { rb -> rb.mass = 0.7f; rb.friction = 1.4f; rb.freezeRotation = false })
                wheel.add(Collider2D().also { c -> c.shape = 1; c.radius = 0.45f })
                wheel.add(Joint2D().also { j ->
                    j.kind = 1
                    j.target = "Chassis"
                    j.targetAnchorX = dx
                    j.targetAnchorY = -0.55f
                    j.frequency = 3.2f
                    j.damping = 0.65f
                    j.motorSpeed = -10f
                    j.maxTorque = 9f
                })
            }
            root
        }

        add("swing", "Swing / Pendulum", "Gameplay",
            "A rope anchor with a bob attached by a distance joint — the base for swings, cranes and grapples."
        ) { s, x, y ->
            val root = s.create("Swing").also { it.x = x; it.y = y }
            val anchor = s.create("Anchor", root).also { it.y = 3f; it.scaleX = 0.25f; it.scaleY = 0.25f }
            anchor.add(SpriteRenderer().also { it.color = 0xFF6E6E6E.toInt() })
            anchor.add(Collider2D().also { c -> c.width = 0.25f; c.height = 0.25f })

            val bob = s.create("Bob", root).also { it.x = 1.6f; it.y = 3f; it.scaleX = 0.5f; it.scaleY = 0.5f }
            bob.add(SpriteRenderer().also { it.color = 0xFF42A5F5.toInt(); it.shape = 1 })
            bob.add(Rigidbody2D().also { rb -> rb.mass = 2f; rb.drag = 0.1f })
            bob.add(Collider2D().also { c -> c.shape = 1; c.radius = 0.3f })
            bob.add(Joint2D().also { j -> j.kind = 3; j.target = "Anchor"; j.restLength = 1.6f })
            root
        }

        add("coin", "Coin (trigger)", "Gameplay",
            "A circular trigger tagged Coin — pair with a script to collect it."
        ) { s, x, y ->
            val go = s.create("Coin").also { it.x = x; it.y = y; it.scaleX = 0.6f; it.scaleY = 0.6f; it.tag = "Coin" }
            go.add(SpriteRenderer().also { it.color = 0xFFFFC107.toInt(); it.shape = 1 })
            go.add(Collider2D().also { c -> c.shape = 1; c.radius = 0.5f; c.isTrigger = true })
            go
        }

        add("spikes", "Spikes", "Gameplay",
            "A hazard strip with a trigger collider and a warning colour."
        ) { s, x, y ->
            val go = s.create("Spikes").also { it.x = x; it.y = y; it.tag = "Hazard" }
            for (i in 0 until 4) shape(s, go, "Spike", -1.5f + i, 0f, 0.6f, 0.8f, 0xFFD64545.toInt(), 2)
            go.add(Collider2D().also { c -> c.width = 4f; c.height = 0.8f; c.offsetY = 0.3f; c.isTrigger = true })
            go
        }

        add("moving_platform", "Moving Platform", "Gameplay",
            "A kinematic platform. Drive it from a script with self.vx / self.vy or an animation clip."
        ) { s, x, y ->
            val go = s.create("Moving Platform").also { it.x = x; it.y = y; it.scaleX = 3f; it.scaleY = 0.35f }
            go.add(SpriteRenderer().also { it.color = 0xFF7E57C2.toInt() })
            go.add(Rigidbody2D().also { rb -> rb.bodyType = 1; rb.startVx = 1.2f })
            go.add(Collider2D())
            go
        }

        // ---- Effects
        add("torch_light", "Torch Light", "Effects",
            "A warm flickering point light with a small flame sprite. Uses the 2D lighting pipeline."
        ) { s, x, y ->
            val go = s.create("Torch Light").also { it.x = x; it.y = y; it.scaleX = 0.35f; it.scaleY = 0.35f }
            go.add(SpriteRenderer().also { it.color = 0xFFFFB74D.toInt(); it.shape = 1 })
            go.add(com.sengine.engine.core.Light2D().also { l ->
                l.kind = 1
                l.color = 0xFFFFB060.toInt()
                l.radius = 6f
                l.innerRadius = 0.6f
                l.intensity = 1.1f
            })
            go
        }

        add("spot_lamp", "Spot Lamp", "Effects",
            "A cone light for corridors, street lamps and stage lighting."
        ) { s, x, y ->
            val go = s.create("Spot Lamp").also { it.x = x; it.y = y }
            go.add(com.sengine.engine.core.Light2D().also { l ->
                l.kind = 2
                l.color = 0xFFBFD4FF.toInt()
                l.radius = 9f
                l.innerRadius = 1f
                l.spotAngle = 42f
                l.direction = -90f
            })
            go
        }

        add("ambient_light", "Ambient Light", "Effects",
            "Raises the ambient level of the whole scene. Add it once to control how dark unlit areas are."
        ) { s, x, y ->
            val go = s.create("Ambient Light").also { it.x = x; it.y = y }
            go.add(com.sengine.engine.core.Light2D().also { l ->
                l.kind = 0
                l.color = 0xFF3A4250.toInt()
                l.intensity = 1f
            })
            go
        }

        add("sun_light", "Directional Light 2D", "Effects",
            "A flat directional tint (moonlight, sunset, underwater). Cheap: one screen-space quad."
        ) { s, x, y ->
            val go = s.create("Sun Light").also { it.x = x; it.y = y }
            go.add(com.sengine.engine.core.Light2D().also { l ->
                l.kind = 3
                l.color = 0xFF9FB6D8.toInt()
                l.intensity = 0.5f
            })
            go
        }
    }

    /** Small geometry helpers reused by templates. */
    object Geometry {
        fun bumpyLine(length: Float, segments: Int, amp: Float, freq: Float): List<Float> {
            val out = ArrayList<Float>()
            for (i in 0..segments) {
                val t = -length * 0.5f + i * (length / segments)
                out.add(t)
                out.add(amp * sin((t * freq).toDouble()).toFloat())
            }
            return out
        }

        fun isConvex(points: List<Float>): Boolean {
            val n = points.size / 2
            if (n < 3) return false
            var sign = 0
            for (i in 0 until n) {
                val x1 = points[i * 2]; val y1 = points[i * 2 + 1]
                val x2 = points[((i + 1) % n) * 2]; val y2 = points[((i + 1) % n) * 2 + 1]
                val x3 = points[((i + 2) % n) * 2]; val y3 = points[((i + 2) % n) * 2 + 1]
                val cross = (x2 - x1) * (y3 - y2) - (y2 - y1) * (x3 - x2)
                if (abs(cross) < 1e-5f) continue
                val s = if (cross > 0) 1 else -1
                if (sign == 0) sign = s else if (s != sign) return false
            }
            return true
        }

        fun length(points: List<Float>): Float {
            var total = 0f
            var i = 0
            while (i + 3 < points.size) {
                total += hypot((points[i + 2] - points[i]).toDouble(), (points[i + 3] - points[i + 1]).toDouble()).toFloat()
                i += 2
            }
            return total
        }
    }
}
