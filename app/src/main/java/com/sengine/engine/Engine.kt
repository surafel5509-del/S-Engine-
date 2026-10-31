package com.sengine.engine

import com.sengine.engine.core.AudioSource
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer
import com.sengine.engine.camera.CameraRig
import com.sengine.engine.physics.PhysicsWorld
import com.sengine.engine.render.View2D
import com.sengine.engine.script.ScriptSystem
import com.sengine.project.Project
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The runtime core. All scene mutation happens while holding [lock];
 * simulation runs on the GL thread via [tick].
 */
class Engine(
    val project: Project,
    initialScene: Scene,
    internal val scriptStorageProject: Project = project,
    audioEnabled: Boolean = true,
    scriptsEnabled: Boolean = true,
) {

    enum class Mode { EDIT, PLAY, PAUSED }

    interface Listener {
        fun onLog(level: Int, message: String) {}
        fun onModeChanged(mode: Mode) {}
        fun onSceneReplaced() {}
    }

    val lock = Any()
    @Volatile var scene: Scene = initialScene
        private set
    @Volatile var mode = Mode.EDIT
        private set

    val input = Input()
    val physics = PhysicsWorld()
    val physics3D = com.sengine.engine.physics.PhysicsWorld3D()
    val animation = com.sengine.engine.anim.AnimationSystem(project)
    val models = com.sengine.engine.render.ModelStore(project)
    val skeletalAnimation = com.sengine.engine.anim.SkeletalAnimationSystem(project, models)
    val scripts = ScriptSystem(this, scriptsEnabled)
    val audio = AudioSystem(project, audioEnabled)
    val gameView = View2D()

    var time = 0.0; private set
    var frame = 0L; private set
    @Volatile var fps = 0f; private set
    // profiler (milliseconds, smoothed)
    @Volatile var scriptMs = 0f
    @Volatile var physicsMs = 0f
    @Volatile var renderMs = 0f
    @Volatile var drawCalls = 0
    /** Bytes of GPU texture memory held by the renderer's texture cache (see TextureBudget). */
    @Volatile var textureBytes = 0L
    private var fpsAcc = 0f
    private var fpsFrames = 0

    val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private var snapshot: String? = null
    private var snapshotScene = ""
    private var pendingSceneLoad: String? = null

    val logs = ArrayDeque<String>()

    init {
        physics.listener = scripts
        physics3D.listener = scripts
        skeletalAnimation.onEvent = { go, name -> scripts.sendMessage(go, "onAnimationEvent", name) }
        skeletalAnimation.onLog = { log(2, it) }
    }

    // ---------------------------------------------------------------- commands
    fun post(cmd: () -> Unit) { commands.add(cmd) }

    fun play() = post {
        when (mode) {
            Mode.EDIT -> startPlay()
            Mode.PAUSED -> setMode(Mode.PLAY)
            else -> {}
        }
    }

    fun pause() = post { if (mode == Mode.PLAY) setMode(Mode.PAUSED) }
    fun stop() = post { if (mode != Mode.EDIT) stopPlay() }
    fun stepFrame() = post { if (mode == Mode.PAUSED) runFrame(1f / 60f) }

    /** Replace the edited scene (editor only, call while holding lock). */
    fun replaceScene(s: Scene) {
        scene = s
        listeners.forEach { it.onSceneReplaced() }
    }

    fun requestLoadScene(name: String) { pendingSceneLoad = name }

    fun log(level: Int, msg: String) {
        synchronized(logs) {
            logs.addLast(msg)
            while (logs.size > 300) logs.removeFirst()
        }
        listeners.forEach { it.onLog(level, msg) }
    }

    private fun setMode(m: Mode) {
        mode = m
        listeners.forEach { it.onModeChanged(m) }
    }

    // ---------------------------------------------------------------- play mode
    private fun startPlay() {
        snapshot = SceneSerializer.toJson(scene).toString()
        snapshotScene = scene.name
        time = 0.0; frame = 0
        input.clear()
        setMode(Mode.PLAY)
        beginScene()
        log(0, "▶ Play: ${scene.name}")
    }

    private fun beginScene() {
        for (go in scene.objects) for (c in go.components) c.resetRuntime()
        com.sengine.engine.script.SObject.clearOverrides(); skeletalAnimation.clear()
        physics.reset()
        physics3D.reset()
        audio.start()
        scene.updateTransforms()
        snapCameraToTarget()
        updateCamera3DFollow(10f)
        scene.updateTransforms()
        updateGameView()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            go.get<AudioSource>()?.let { if (it.playOnStart) audio.play(it.clip, it.volume, it.loop) }
        }
        scripts.begin()
    }

    private fun endScene() {
        scripts.end()
        audio.stop()
    }

    private fun stopPlay() {
        endScene()
        val snap = snapshot
        if (snap != null) scene = SceneSerializer.fromJson(JSONObject(snap))
        snapshot = null
        input.clear()
        setMode(Mode.EDIT)
        listeners.forEach { it.onSceneReplaced() }
        log(0, "■ Stopped")
    }

    // ---------------------------------------------------------------- loop
    /** Called on the GL thread with [lock] held. */
    fun tick(dt: Float) {
        while (true) {
            val c = commands.poll() ?: break
            try { c() } catch (e: Exception) { log(2, "Engine error: ${e.message}") }
        }
        fpsAcc += dt; fpsFrames++
        if (fpsAcc >= 0.5f) { fps = fpsFrames / fpsAcc; fpsAcc = 0f; fpsFrames = 0 }

        when (mode) {
            Mode.PLAY -> runFrame(dt)
            Mode.EDIT -> { scene.updateTransforms(); updateParticles(dt); updateAnimators(dt, false) }
            Mode.PAUSED -> scene.updateTransforms()
        }
    }

    private fun runFrame(dt0: Float) {
        val dt = dt0.coerceAtMost(0.1f)
        time += dt; frame++
        scene.updateTransforms()
        updateGameView()
        input.beginFrame(gameView)
        val t0 = System.nanoTime()
        scripts.update(dt)
        val t1 = System.nanoTime()
        physics.step(scene, dt)
        physics3D.step(scene, dt)
        val t2 = System.nanoTime()
        scriptMs = scriptMs * 0.9f + (t1 - t0) / 1e6f * 0.1f
        physicsMs = physicsMs * 0.9f + (t2 - t1) / 1e6f * 0.1f
        cleanupDestroyed()
        scene.updateTransforms()
        updateCameraFollow(dt)
        updateCamera3DFollow(dt)
        updateGameView()
        updateParticles(dt)
        updateAnimators(dt, true)
        decayShake(dt)
        scripts.lateUpdate(dt)
        scene.updateTransforms()

        val load = pendingSceneLoad
        if (load != null) {
            pendingSceneLoad = null
            val snap = snapshot
            val fromSnapshot = snap != null && load == snapshotScene
            if (fromSnapshot || project.sceneExists(load)) {
                endScene()
                scene = if (fromSnapshot) SceneSerializer.fromJson(JSONObject(snap!!)) else project.loadScene(load)
                listeners.forEach { it.onSceneReplaced() }
                beginScene()
                log(0, "Loaded scene $load")
            } else log(2, "Scene not found: $load")
        }
    }

    private fun cleanupDestroyed() {
        if (scene.objects.none { it.destroyed }) return
        val dead = scene.objects.filter { it.destroyed || isUnderDestroyed(it) }
        for (d in dead) { d.destroyed = true; scripts.onDestroyed(d) }
        scene.objects.removeAll(dead.toSet())
        dead.forEach { com.sengine.engine.script.SObject.runtimeMaterials.remove(it.id) }
        listeners.forEach { it.onSceneReplaced() }
    }

    private fun isUnderDestroyed(go: GameObject): Boolean {
        var p = go.parent
        while (p != null) { if (p.destroyed) return true; p = p.parent }
        return false
    }

    fun mainCamera3D(): GameObject? = scene.objects.firstOrNull { it.isActiveInHierarchy() && it.get<com.sengine.engine.core.Camera3D>() != null }

    private fun updateAnimators(dt: Float, playing: Boolean) {
        for (go in scene.objects.toList()) {
            val a = go.getAny<com.sengine.engine.core.SkeletalAnimator>() ?: continue
            if (!playing && !a.previewInEditor) continue
            if (playing && !go.isActiveInHierarchy()) continue
            skeletalAnimation.update(go, a, dt, playing)
        }
        for (go in scene.objects) {
            val a = go.getAny<com.sengine.engine.core.Animator>() ?: continue
            val sr = go.getAny<com.sengine.engine.core.SpriteRenderer>() ?: continue
            if (playing && !go.isActiveInHierarchy()) continue
            animation.update(a, sr, dt, playing)
        }
    }

    private fun updateCamera3DFollow(dt: Float) {
        val camGo = mainCamera3D() ?: return
        val cam = camGo.get<com.sengine.engine.core.Camera3D>()!!
        if (cam.follow.isBlank()) return
        val t = scene.find(cam.follow) ?: return
        val tw = t.world3
        val w = camGo.computeWorld3()
        val k = if (cam.smoothing <= 0f) 1f else (1f - exp(-cam.smoothing * dt))
        val gx = tw[12] + cam.offsetX; val gy = tw[13] + cam.offsetY; val gz = tw[14] + cam.offsetZ
        camGo.setWorldPosition3(w[12] + (gx - w[12]) * k, w[13] + (gy - w[13]) * k, w[14] + (gz - w[14]) * k)
    }

    /** Adds screen shake. 0.2 = small hit, 0.6 = heavy impact, 1.0 = maximum. */
    fun shake(amount: Float) {
        mainCamera()?.let { camGo ->
            camGo.getAny<Camera2D>()?.let { cam ->
                cam.shake = maxOf(cam.shake, amount)
                rigFor(camGo).addTrauma(amount.coerceIn(0f, 1f))
            }
        }
        mainCamera3D()?.getAny<com.sengine.engine.core.Camera3D>()?.let { it.shake = maxOf(it.shake, amount) }
    }

    /** Legacy scalar shake decay for 3D cameras; the 2D rig decays its own trauma. */
    private fun decayShake(dt: Float) {
        mainCamera3D()?.getAny<com.sengine.engine.core.Camera3D>()?.let { it.shake = maxOf(0f, it.shake - dt * 2f * maxOf(1f, it.shake)) }
    }

    fun mainCamera(): GameObject? = scene.objects.firstOrNull { it.isActiveInHierarchy() && it.get<Camera2D>() != null }

    private fun snapCameraToTarget() {
        val camGo = mainCamera() ?: return
        val cam = camGo.get<Camera2D>()!!
        if (cam.follow.isBlank()) return
        val t = scene.find(cam.follow) ?: return
        val rig = rigFor(camGo)
        rig.reset(t.world.tx, t.world.ty, cam)
        camGo.setWorldPosition(rig.x, rig.y)
        targetLast[t.id] = floatArrayOf(t.world.tx, t.world.ty)
        cam.shake = 0f
        cam.shakeOffX = 0f
        cam.shakeOffY = 0f
    }

    private val cameraRigs = HashMap<Long, CameraRig>()
    private val shakeOut = FloatArray(2)
    private val targetLast = HashMap<Long, FloatArray>()

    private fun rigFor(camGo: GameObject) = cameraRigs.getOrPut(camGo.id) { CameraRig() }

    private fun updateCameraFollow(dt: Float) {
        val camGo = mainCamera() ?: return
        val cam = camGo.get<Camera2D>()!!
        val rig = rigFor(camGo)
        if (cam.follow.isBlank()) {
            rig.addTrauma(0f)
            applyCameraShake(cam, rig)
            return
        }
        val t = scene.find(cam.follow) ?: return
        val rb = t.getAny<com.sengine.engine.core.Rigidbody2D>()
        var vx = rb?.vx ?: 0f
        var vy = rb?.vy ?: 0f
        if (rb == null) {
            // No rigidbody: derive the velocity from the movement since last frame.
            val last = targetLast.getOrPut(t.id) { floatArrayOf(t.world.tx, t.world.ty) }
            if (dt > 0f) {
                vx = (t.world.tx - last[0]) / dt
                vy = (t.world.ty - last[1]) / dt
            }
            last[0] = t.world.tx; last[1] = t.world.ty
        }
        val ppu = if (gameView.heightPx > 1) gameView.pixelsPerUnit else 0f
        rig.step(t.world.tx, t.world.ty, vx, vy, dt, cam, ppu)
        camGo.setWorldPosition(rig.x, rig.y)
        applyCameraShake(cam, rig)
    }

    private fun applyCameraShake(cam: Camera2D, rig: CameraRig) {
        rig.shakeOffset(cam.shakeAmplitude, shakeOut)
        cam.shakeOffX = shakeOut[0]
        cam.shakeOffY = shakeOut[1]
    }

    fun updateGameView() {
        val camGo = mainCamera()
        if (camGo == null) {
            gameView.cx = 0f; gameView.cy = 0f; gameView.size = 5f
            return
        }
        val w = camGo.computeWorld()
        gameView.cx = w.tx; gameView.cy = w.ty
        gameView.size = camGo.get<Camera2D>()!!.size
    }

    fun backgroundColor(): Int = mainCamera()?.get<Camera2D>()?.background ?: 0xFF1B2533.toInt()

    // ---------------------------------------------------------------- particles
    private fun updateParticles(dt: Float) {
        for (go in scene.objects) {
            val pe = go.getAny<ParticleEmitter>() ?: continue
            val alive = go.isActiveInHierarchy() && pe.enabled
            val w = go.world
            if (alive && pe.emitting) pe.accumulator += pe.rate * dt
            var toEmit = pe.accumulator.toInt() + pe.pendingBurst
            pe.accumulator -= pe.accumulator.toInt()
            pe.pendingBurst = 0
            if (!alive) toEmit = 0
            while (toEmit-- > 0 && pe.particles.size < pe.maxParticles) {
                val ang = Math.toRadians((pe.direction + w.rotationDeg + (Random.nextFloat() - 0.5f) * pe.spread).toDouble())
                val speedScale = 1f + (Random.nextFloat() * 2f - 1f) * pe.speedVariance
                val startRotation = pe.startRotation + (Random.nextFloat() * 2f - 1f) * pe.rotationVariance
                val spin = pe.rotationSpeed + (Random.nextFloat() * 2f - 1f) * pe.rotationSpeedVariance
                val sizeScale = (1f + (Random.nextFloat() * 2f - 1f) * pe.sizeVariance).coerceAtLeast(0f)
                val sp = pe.speed * speedScale
                pe.particles.add(
                    ParticleEmitter.Particle(
                        w.tx, w.ty, (cos(ang) * sp).toFloat(), (sin(ang) * sp).toFloat(),
                        0f, pe.lifetime * (0.7f + Random.nextFloat() * 0.6f), startRotation, spin, sizeScale
                    )
                )
            }
            val it = pe.particles.iterator()
            while (it.hasNext()) {
                val p = it.next()
                p.age += dt
                if (p.age >= p.life) { it.remove(); continue }
                p.vy += pe.gravity * dt
                if (pe.drag > 0f) {
                    val damping = max(0f, 1f - pe.drag * dt)
                    p.vx *= damping; p.vy *= damping
                }
                p.x += p.vx * dt; p.y += p.vy * dt
                p.rotation += p.angularVelocity * dt
            }
        }
    }

    fun findComponentOwner(c: Component): GameObject? = scene.objects.firstOrNull { c in it.components }

    fun release() {
        synchronized(lock) {
            cameraRigs.clear()
            if (mode != Mode.EDIT) endScene()
            audio.stop()
        }
    }
}
