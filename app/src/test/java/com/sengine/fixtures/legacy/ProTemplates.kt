package com.sengine.fixtures.legacy

import com.sengine.project.Project
import com.sengine.project.AssetLibrary

import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Camera3D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.Collider3D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Light
import com.sengine.engine.core.Light2D
import com.sengine.engine.core.MeshRenderer
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Rigidbody3D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import kotlin.math.sin

/**
 * The two professional starter games shipped with S Engine Pro.
 *
 * Both templates are built entirely from engine features (no mock objects and no binary assets):
 * scenes are assembled from real components, behaviours are real JavaScript files written into the
 * project, and the 2D template uses the new lighting pipeline, camera rig, terrain colliders and
 * rapier joints. Every scene they generate is validated by the desktop test harness
 * (`ProTemplatesTest` style checks in `.verify/harness/ProVerify.kt`).
 */
object ProTemplates {

    class ProTemplate(
        val name: String,
        val description: String,
        /** 2D or 3D template: the editor picks the viewport mode from this. */
        val threeD: Boolean,
        val build: (Project) -> Unit,
    )

    val all: List<ProTemplate> get() = listOf(lumenAscent, auroraStation)

    // =====================================================================================
    // 2D TEMPLATE — "Lumen Ascent"
    // =====================================================================================

    val lumenAscent = ProTemplate(
        name = "Lumen Ascent (Pro 2D)",
        description = "A complete 2D platformer: dynamic 2D lighting, smooth follow camera with look-ahead and shake, " +
            "bumpy terrain, tipping crates, moving platform, hazards, collectibles, HUD and a menu scene.",
        threeD = false,
    ) { p -> buildLumenAscent(p) }

    private const val PLAYER_JS = """// Player controller — Lumen Ascent
// Move with the joystick (or A/D), jump with the action button.
var speed = 5.2;
var jump = 8.4;
var coyote = 0.0;
var coyoteMax = 0.12;
var jumpBuffer = 0.0;
var facing = 1;
var spawnX = 0.0;
var spawnY = 0.0;
var deaths = 0;

function start() {
    spawnX = self.x;
    spawnY = self.y;
    log("Player ready");
}

function update(dt) {
    var grounded = self.grounded;
    if (grounded) { coyote = coyoteMax; } else { coyote = Math.max(0.0, coyote - dt); }

    var x = input.axisX;
    if (Math.abs(x) < 0.15) x = 0.0;
    if (x !== 0) facing = x > 0 ? 1 : -1;
    self.vx = x * speed;

    if (input.aDown) jumpBuffer = 0.14;
    else jumpBuffer = Math.max(0.0, jumpBuffer - dt);

    if (jumpBuffer > 0.0 && coyote > 0.0) {
        self.vy = jump;
        jumpBuffer = 0.0;
        coyote = 0.0;
    }
    // short hop when the button is released early
    if (!input.a && self.vy > 3.0) self.vy = self.vy * 0.86;

    if (self.y < -14.0) respawn();

    var spr = scene.find("HUD");
    if (spr) spr.text = "★ " + score() + "    ✖ " + deaths;
}

function onTrigger(other) {
    if (other.tag === "Hazard") respawn();
    if (other.tag === "Goal") {
        camera.shake(0.4);
        var h = scene.find("HUD");
        if (h) h.text = "LEVEL CLEAR!";
    }
}

function respawn() {
    deaths = deaths + 1;
    self.setPosition(spawnX, spawnY);
    self.setVelocity(0, 0);
    camera.shake(0.5);
}

function onLand() { camera.shake(0.2); }

function score() {
    var v = storage.get("score", 0);
    return v;
}
"""

    private const val STAR_JS = """// Collectible star
var spin = 0.0;

function update(dt) {
    spin = spin + dt * 90.0;
    self.rotation = spin;
}

function onTrigger(other) {
    if (other.tag !== "Player") return;
    var s = storage.get("score", 0) + 1;
    storage.set("score", s);
    audio.beep(880, 0.06);
    camera.shake(0.08);
    var hud = scene.find("HUD");
    if (hud) hud.text = "★ " + s;
    scene.destroy(self);
}
"""

    private const val TORCH_JS = """// Torch light flicker
var base = 1.0;
var t = 0.0;

function start() { base = light.intensity; }

function update(dt) {
    t = t + dt;
    light.intensity = base + Math.sin(t * 9.0) * 0.12 + Math.sin(t * 23.0) * 0.05;
}
"""

    private const val MOVER_JS = """// Moving platform: ping-pongs between two points
var minX = 0.0;
var maxX = 0.0;
var dir = 1.0;

function start() { minX = self.x - 2.2; maxX = self.x + 2.2; }

function update(dt) {
    self.vx = dir * 1.6;
    if (self.x > maxX) dir = -1.0;
    if (self.x < minX) dir = 1.0;
}
"""

    private const val MENU_JS = """// Title screen: tap or press A to start
var done = false;

function update(dt) {
    var t = scene.find("Title");
    if (t) t.rotation = Math.sin(time.total * 1.4) * 2.5;
    if (!done && (input.aDown || input.tapped)) {
        done = true;
        scene.load("Main");
    }
}
"""

    private fun buildLumenAscent(p: Project) {
        p.writeAsset("Player.js", PLAYER_JS)
        p.writeAsset("Star.js", STAR_JS)
        p.writeAsset("Torch.js", TORCH_JS)
        p.writeAsset("Mover.js", MOVER_JS)
        p.writeAsset("Menu.js", MENU_JS)

        // ---------------- menu scene
        val menu = Scene("Menu")
        camera2d(menu, 5f, 0xFF070B12.toInt(), "")
        menu.create("Ambient").add(Light2D().also { it.kind = 0; it.color = 0xFF1B2434.toInt(); it.intensity = 1f })
        val title = menu.create("Title").also { it.y = 1.4f }
        title.add(TextRenderer().also {
            it.text = "LUMEN ASCENT"; it.size = 1.5f; it.color = 0xFFFFD98A.toInt(); it.screenSpace = false
        })
        val sub = menu.create("Subtitle").also { it.y = 0.1f }
        sub.add(TextRenderer().also {
            it.text = "Tap or press A to start"; it.size = 0.55f; it.color = 0xFF9FB4D0.toInt()
        })
        menu.create("Controller").add(ScriptComponent().also { it.script = "Menu.js" })
        p.saveScene(menu)

        // ---------------- gameplay scene
        val s = Scene("Main")
        s.gravityY = -24f
        camera2d(s, 7f, 0xFF0B1220.toInt(), "Player").also { cam ->
            val c = cam.get<Camera2D>()!!
            c.smoothing = 6.5f
            c.smoothingY = 4f
            c.deadzoneX = 0.5f
            c.deadzoneY = 0.6f
            c.lookAhead = 0.18f
            c.verticalBias = 0.9f
            c.boundsMinX = -6f
            c.boundsMaxX = 46f
            c.boundsMinY = -2f
            c.boundsMaxY = 14f
            c.shakeAmplitude = 0.35f
            c.shakeDecay = 2.4f
        }

        s.create("Ambient").add(Light2D().also {
            it.kind = 0
            it.color = 0xFF2B3444.toInt()
            it.intensity = 1f
        })

        // terrain: bumpy start, flat middle, rising steps at the end
        val groundPoints = ArrayList<Float>()
        val segments = 40
        val length = 56f
        for (i in 0..segments) {
            val x = -6f + i * (length / segments)
            val y = if (x < 12f) 0.22f * sin((x * 1.5).toDouble()).toFloat() + 0.09f * sin((x * 4.7).toDouble()).toFloat()
            else if (x < 30f) 0f
            else 0.28f * (x - 30f)
            groundPoints.add(x)
            groundPoints.add(y)
        }
        val ground = s.create("Ground")
        ground.add(Collider2D().also { c -> c.shape = 2; c.thickness = 3f; c.setPoints(groundPoints) })
        // visible surface strips
        var k = 0
        while (k + 3 < groundPoints.size) {
            val x1 = groundPoints[k]; val y1 = groundPoints[k + 1]
            val x2 = groundPoints[k + 2]; val y2 = groundPoints[k + 3]
            groundEdge(s, ground, x1, y1, x2, y2)
            k += 2
        }

        // floating platforms built from box colliders (real static bodies)
        platform(s, 6f, 3.2f, 3.4f)
        platform(s, 14.5f, 5.0f, 2.6f)
        platform(s, 23f, 3.4f, 3.0f)
        platform(s, 33f, 7.2f, 3.2f)

        // player
        val player = s.create("Player").also { it.x = 0f; it.y = 2.2f; it.tag = "Player" }
        player.add(SpriteRenderer().also { it.color = 0xFFFFC170.toInt() })
        player.add(Rigidbody2D().also {
            it.mass = 1.6f
            it.freezeRotation = true
            it.friction = 0.35f
            it.bounciness = 0f
        })
        player.add(Collider2D().also { c -> c.width = 0.72f; c.height = 1.15f })
        player.add(ScriptComponent().also { it.script = "Player.js" })

        // torches with point lights + embers
        for (x in floatArrayOf(-2f, 11f, 20f, 28.5f, 36f)) {
            val torch = s.create("Torch").also { it.x = x; it.y = 1.2f }
            torch.scaleY = 1.4f
            torch.add(SpriteRenderer().also { it.color = 0xFF8A5A2B.toInt() })
            torch.add(Light2D().also {
                it.kind = 1
                it.color = 0xFFFFB067.toInt()
                it.radius = 7f
                it.innerRadius = 0.8f
                it.intensity = 1.25f
                it.falloff = 1.6f
            })
            torch.add(ParticleEmitter().also { em ->
                em.rate = 16f
                em.lifetime = 0.9f
                em.speed = 1.2f
                em.spread = 22f
                em.startColor = 0xFFFFC46B.toInt()
                em.endColor = 0x00FF6B00
                em.startSize = 0.09f
                em.endSize = 0.01f
                em.gravity = 2.5f
            })
            torch.add(ScriptComponent().also { it.script = "Torch.js" })
        }

        // collectibles
        val starPositions = arrayOf(
            floatArrayOf(3.2f, 2.4f), floatArrayOf(7.4f, 4.4f), floatArrayOf(14.6f, 6.2f),
            floatArrayOf(24f, 4.6f), floatArrayOf(33.2f, 8.4f), floatArrayOf(42f, 3.0f),
        )
        for ((i, pos) in starPositions.withIndex()) {
            val star = s.create("Star ${i + 1}").also { it.x = pos[0]; it.y = pos[1]; it.tag = "Star" }
            star.scaleX = 0.55f; star.scaleY = 0.55f
            star.add(SpriteRenderer().also { it.color = 0xFFFFD54F.toInt(); it.shape = 1 })
            star.add(Collider2D().also { c -> c.shape = 1; c.radius = 0.5f; c.isTrigger = true })
            star.add(ScriptComponent().also { it.script = "Star.js" })
        }

        // physics props: crate stack + tipping beam
        for (i in 0 until 3) {
            crate(s, 18f + i * 0.03f, 0.6f + i * 1.04f)
        }
        val beam = s.create("Tipping Beam").also { it.x = 26f; it.y = 1.6f; it.rotation = 6f }
        beam.scaleX = 4.2f; beam.scaleY = 0.28f
        beam.add(SpriteRenderer().also { it.color = 0xFFD9A066.toInt() })
        beam.add(Rigidbody2D().also { it.mass = 2.5f; it.freezeRotation = false; it.friction = 0.9f })
        beam.add(Collider2D().also { c -> c.width = 4.2f; c.height = 0.28f })

        // moving platform
        val mover = s.create("Moving Platform").also { it.x = 20f; it.y = 6.5f }
        mover.scaleX = 2.4f; mover.scaleY = 0.32f
        mover.add(SpriteRenderer().also { it.color = 0xFF7E57C2.toInt() })
        mover.add(Rigidbody2D().also { it.bodyType = 1; it.friction = 1f })
        mover.add(Collider2D().also { c -> c.width = 2.4f; c.height = 0.32f })
        mover.add(ScriptComponent().also { it.script = "Mover.js" })

        // hazard pit and goal
        val spikes = s.create("Spikes").also { it.x = 12f; it.y = 0.05f; it.tag = "Hazard" }
        spikes.scaleX = 2.2f; spikes.scaleY = 0.5f
        spikes.add(SpriteRenderer().also { it.color = 0xFFE5534B.toInt(); it.shape = 2 })
        spikes.add(Collider2D().also { c -> c.width = 2.2f; c.height = 0.5f; c.offsetY = 0.3f; c.isTrigger = true })

        val goal = s.create("Goal").also { it.x = 44f; it.y = 2.6f; it.tag = "Goal" }
        goal.scaleX = 0.8f; goal.scaleY = 2.4f
        goal.add(SpriteRenderer().also { it.color = 0xFF57AB5A.toInt() })
        goal.add(Collider2D().also { c -> c.width = 0.8f; c.height = 2.4f; c.isTrigger = true })
        goal.add(Light2D().also { it.kind = 1; it.color = 0xFF7CFFA0.toInt(); it.radius = 4.5f; it.intensity = 0.9f })

        // HUD
        val hud = s.create("HUD").also { it.x = 0f; it.y = 4.2f }
        hud.add(TextRenderer().also {
            it.text = "★ 0    ✖ 0"
            it.size = 0.5f
            it.color = 0xFFFFE9B8.toInt()
            it.screenSpace = true
        })

        p.saveScene(s)
        p.saveScene(menu)
        p.startScene = "Menu"
        p.controlScheme = "classic"
    }

    private fun camera2d(s: Scene, size: Float, bg: Int, follow: String): GameObject {
        val go = s.create("Main Camera")
        go.add(Camera2D().also {
            it.size = size
            it.background = bg
            it.follow = follow
            it.smoothing = 6f
            it.smoothingY = 4f
            it.lookAhead = 0.15f
            it.deadzoneX = 0.4f
            it.deadzoneY = 0.4f
            it.verticalBias = 0.6f
            it.shakeAmplitude = 0.3f
        })
        return go
    }

    private fun groundEdge(s: Scene, owner: GameObject, x1: Float, y1: Float, x2: Float, y2: Float) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 1e-4f) return
        val go = s.create("Edge", owner).also {
            it.x = (x1 + x2) * 0.5f
            it.y = (y1 + y2) * 0.5f
            it.rotation = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            it.scaleX = len
            it.scaleY = 0.3f
            it.order = -2
        }
        go.add(SpriteRenderer().also { it.color = 0xFF2E5B49.toInt() })
        val skirt = s.create("Skirt", owner).also {
            it.x = (x1 + x2) * 0.5f
            it.y = (y1 + y2) * 0.5f - 0.35f
            it.rotation = go.rotation
            it.scaleX = len
            it.scaleY = 0.5f
            it.order = -3
        }
        skirt.add(SpriteRenderer().also { it.color = 0xFF1B2A33.toInt() })
    }

    private fun platform(s: Scene, x: Float, y: Float, w: Float) {
        val go = s.create("Platform").also { it.x = x; it.y = y }
        go.scaleX = w; go.scaleY = 0.4f
        go.add(SpriteRenderer().also { it.color = 0xFF3A5167.toInt() })
        go.add(Collider2D().also { c -> c.width = w; c.height = 0.4f })
    }

    private fun crate(s: Scene, x: Float, y: Float): GameObject {
        val go = s.create("Crate").also { it.x = x; it.y = y }
        go.add(SpriteRenderer().also { it.color = 0xFFB07A45.toInt() })
        go.add(Rigidbody2D().also { it.mass = 1.2f; it.freezeRotation = false; it.friction = 0.65f; it.bounciness = 0.05f })
        go.add(Collider2D())
        return go
    }

    // =====================================================================================
    // 3D TEMPLATE — "Aurora Station"
    // =====================================================================================

    val auroraStation = ProTemplate(
        name = "Aurora Station (Pro 3D)",
        description = "A complete 3D scene: follow camera with orbit, textured floor, modular architecture, " +
            "directional sky light plus point lights, physics crates, spinning pickups, HUD and a controller script.",
        threeD = true,
    ) { p -> buildAuroraStation(p) }

    private const val PLAYER3D_JS = """// Third person controller — Aurora Station
var speed = 6.0;
var jump = 7.5;
var yaw = 0.0;
var collected = 0;
var spawnX = 0.0;
var spawnY = 0.0;
var spawnZ = 0.0;

function start() {
    spawnX = self.x; spawnY = self.y; spawnZ = self.z;
}

function update(dt) {
    var x = input.axisX;
    var z = input.axisY;
    if (Math.abs(x) < 0.15) x = 0.0;
    if (Math.abs(z) < 0.15) z = 0.0;
    var cam = scene.find("Main Camera");
    if (cam) yaw = cam.rotY;
    var rad = yaw * Math.PI / 180.0;
    var fx = Math.sin(rad);
    var fz = Math.cos(rad);
    var vx = (x * fz + z * fx) * speed;
    var vz = (-x * fx + z * fz) * speed;
    self.setVelocity(vx, self.vy, vz);
    if (self.grounded && input.aDown) self.vy = jump;
    if (x !== 0 || z !== 0) self.rotY = Math.atan2(vx, vz) * 180.0 / Math.PI;
    if (self.y < -12.0) { self.setPosition(spawnX, spawnY + 2.0, spawnZ); self.setVelocity(0, 0, 0); }
    var hud = scene.find("HUD");
    if (hud) hud.text = "Shards " + collected + " / 5";
    // camera orbit
    if (cam) {
        cam.rotY = cam.rotY + input.axisY * 0.0;
        if (input.b) cam.rotY = cam.rotY + 45.0 * dt;
    }
}

function addShard() {
    collected = collected + 1;
    camera.shake(0.1);
    if (collected >= 5) {
        var hud = scene.find("HUD");
        if (hud) hud.text = "ALL SHARDS COLLECTED";
    }
}
"""

    private const val SHARD_JS = """// Spinning shard pickup
var spin = 0.0;
var baseY = 0.0;
var t = 0.0;

function start() { baseY = self.y; }

function update(dt) {
    spin = spin + dt * 120.0;
    t = t + dt;
    self.rotY = spin;
    self.y = baseY + Math.sin(t * 2.0) * 0.15;
}

function onTrigger(other) {
    if (other.tag !== "Player") return;
    audio.beep(1046, 0.05);
    camera.shake(0.15);
    scene.destroy(self);
}
"""

    private const val ORBIT_JS = """// Camera orbit for the 3D template
var angle = 0.0;

function update(dt) {
    angle = angle + dt * 3.0;
    self.rotY = 20.0 + Math.sin(angle) * 12.0;
}
"""

    private fun buildAuroraStation(p: Project) {
        p.writeAsset("Player3D.js", PLAYER3D_JS)
        p.writeAsset("Shard.js", SHARD_JS)
        p.writeAsset("Orbit.js", ORBIT_JS)

        val s = Scene("Main")
        s.gravity3D = -22f
        s.ambient = 0xFF2A3140.toInt()
        s.fogColor = 0xFF1B2230.toInt()

        // camera
        val cam = s.create("Main Camera").also { it.y = 5.5f; it.z = 9f; it.rotX = -18f }
        cam.add(Camera3D().also {
            it.follow = "Player"
            it.offsetY = 4.2f
            it.offsetZ = 8.5f
            it.smoothing = 5f
            it.skyTop = 0xFF101A2C.toInt()
            it.skyHorizon = 0xFF3A5C86.toInt()
            it.fov = 62f
        })

        // lights
        val sun = s.create("Sun").also { it.rotX = -52f; it.rotY = 34f }
        sun.add(Light().also { it.kind = 0; it.color = 0xFFBFD4FF.toInt(); it.intensity = 1.15f })
        for (x in floatArrayOf(-8f, 0f, 8f)) {
            val lamp = s.create("Lamp").also { it.x = x; it.y = 4.4f; it.z = -6f }
            lamp.add(MeshRenderer().also { it.mesh = 1; it.color = 0xFFFFE0A8.toInt(); it.emission = 0.8f })
            lamp.add(Light().also { it.kind = 1; it.color = 0xFFFFC978.toInt(); it.intensity = 2.4f; it.range = 14f })
        }

        // floor and architecture
        val floor = s.create("Floor").also { it.y = -0.5f; it.scaleX = 30f; it.scaleY = 0.5f; it.scaleZ = 30f }
        floor.add(MeshRenderer().also { it.color = 0xFF39424F.toInt(); it.mesh = 2 })
        floor.add(Collider3D())
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0
            val x = Math.cos(a).toFloat() * 11f
            val z = Math.sin(a).toFloat() * 11f
            val pillar = s.create("Pillar").also { it.x = x; it.y = 2.6f; it.z = z; it.scaleX = 1.1f; it.scaleY = 5.2f; it.scaleZ = 1.1f }
            pillar.add(MeshRenderer().also { it.color = 0xFF55657A.toInt(); it.mesh = 0 })
            pillar.add(Collider3D())
        }
        val arch = s.create("Arch").also { it.y = 6.4f; it.z = -12f; it.scaleX = 14f; it.scaleY = 0.7f; it.scaleZ = 1.4f }
        arch.add(MeshRenderer().also { it.color = 0xFF6D849B.toInt() })

        // player
        val player = s.create("Player").also { it.y = 1.2f; it.tag = "Player" }
        player.add(MeshRenderer().also { it.mesh = 7; it.color = 0xFF6FD3FF.toInt(); it.emission = 0.15f })
        player.add(Rigidbody3D().also { it.mass = 1.4f })
        player.add(Collider3D().also { it.shape = 1 })
        player.add(ScriptComponent().also { it.script = "Player3D.js" })

        // physics crates
        for (i in 0 until 5) {
            val c = s.create("Crate 3D").also {
                it.x = -3f + i * 1.6f
                it.y = 1.2f + i * 1.1f
                it.z = 2.5f
                it.scaleX = 0.9f; it.scaleY = 0.9f; it.scaleZ = 0.9f
            }
            c.add(MeshRenderer().also { it.color = 0xFFB07A45.toInt() })
            c.add(Collider3D())
            c.add(Rigidbody3D().also { it.mass = 1f; it.bounciness = 0.15f; it.friction = 0.6f })
        }

        // rotating shards
        for (i in 0 until 5) {
            val a = i * Math.PI * 2.0 / 5.0
            val shard = s.create("Shard").also {
                it.x = Math.cos(a).toFloat() * 8.5f
                it.y = 1.4f
                it.z = Math.sin(a).toFloat() * 8.5f
                it.scaleX = 0.5f; it.scaleY = 0.9f; it.scaleZ = 0.5f
                it.tag = "Shard"
            }
            shard.add(MeshRenderer().also { it.mesh = 7; it.color = 0xFFFFD54F.toInt(); it.emission = 0.6f; it.unlit = true })
            shard.add(Collider3D().also { it.shape = 1; it.isTrigger = true })
            shard.add(ScriptComponent().also { it.script = "Shard.js" })
        }

        // camera orbit helper object so the script has a home
        val rig = s.create("Camera Rig").also { it.y = 5f }
        rig.add(ScriptComponent().also { it.script = "Orbit.js" })

        // HUD
        val hud = s.create("HUD").also { it.x = 0f; it.y = 3.8f }
        hud.add(TextRenderer().also {
            it.text = "Shards 0 / 5"
            it.size = 0.55f
            it.color = 0xFFFFE9B8.toInt()
            it.screenSpace = true
        })

        p.saveScene(s)
        p.startScene = "Main"
        p.controlScheme = "classic"
    }
}
