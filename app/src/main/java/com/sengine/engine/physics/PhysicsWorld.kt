package com.sengine.engine.physics

import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Joint2D
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Impulse based 2D physics world.
 *
 * Bodies are rebuilt from the scene at every fixed step, which keeps scripted velocity writes and
 * editor edits working exactly like before. Each step is solved with:
 *
 * * convex polygon and circle colliders, plus terrain polylines (each segment becomes a solid wedge),
 * * two contact points per manifold through reference/incident face clipping — this is what makes a
 *   crate rest flat, tip over an edge and stack without jitter,
 * * sequential impulses with accumulated normal / friction impulses,
 * * positional projection with a linear slop so bodies never sink into each other,
 * * sleeping bodies for large scenes,
 * * hinge, distance, spring and wheel joints for vehicles, ragdolls and mechanisms.
 *
 * All angles inside the solver are radians. [Rigidbody2D.angularVelocity] is rad/s and
 * `GameObject.rotation` stays in degrees for the editor and scripts.
 */
class PhysicsWorld {

    interface Listener {
        fun onCollisionEnter(a: GameObject, b: GameObject)
        fun onTriggerEnter(a: GameObject, b: GameObject)
        fun onTriggerExit(a: GameObject, b: GameObject)
    }

    /** Runtime counters, refreshed every [step]. Useful for the editor profiler. */
    class Stats {
        var bodies = 0
        var awake = 0
        var sleeping = 0
        var contacts = 0
        var triggers = 0
        var joints = 0
        var solveMs = 0f
    }

    var listener: Listener? = null
    var velocityIterations = 8
    var positionIterations = 3
    var enableSleeping = true
    /** Seconds a body must stay still before it is put to sleep. */
    var timeToSleep = 0.5f
    var linearSleepTolerance = 0.06f
    var angularSleepTolerance = 0.12f
    /** Restitution is only applied above this approach speed, which stops resting bodies buzzing. */
    var restitutionThreshold = 1.0f
    val stats = Stats()

    private var accumulator = 0f
    private val fixedDt = 1f / 60f
    private val maxSteps = 5
    private val linearSlop = 0.004f
    private val maxLinearCorrection = 0.2f
    private val MAX_SPRING_DV = 12f
    private val MAX_SPEED = 400f
    private val MAX_ANGULAR = 120f
    private var scene: Scene? = null

    // ------------------------------------------------------------------ pooled solver types

    private class Body {
        var go: GameObject? = null
        var rb: Rigidbody2D? = null
        var isCircle = false
        var radius = 0f
        var cx = 0f; var cy = 0f
        var cosA = 1f; var sinA = 0f
        var angle = 0f
        var startAngle = 0f
        var originX = 0f; var originY = 0f
        var startCx = 0f; var startCy = 0f
        var ox = 0f; var oy = 0f
        var vx = 0f; var vy = 0f; var w = 0f
        var invMass = 0f; var invI = 0f
        var mass = 1f
        var trigger = false
        var dynamic = false
        var kinematic = false
        var awake = true
        var sleepTime = 0f
        var friction = 0.4f
        var bounciness = 0f
        var primary = true
        val bx = FloatArray(Collision2D.MAX_VERTS)
        val by = FloatArray(Collision2D.MAX_VERTS)
        val wx = FloatArray(Collision2D.MAX_VERTS)
        val wy = FloatArray(Collision2D.MAX_VERTS)
        var n = 0
        var minX = 0f; var minY = 0f; var maxX = 0f; var maxY = 0f

        fun copyTo(o: Body) {
            o.go = go; o.rb = rb; o.isCircle = isCircle; o.radius = radius
            o.cx = cx; o.cy = cy; o.cosA = cosA; o.sinA = sinA; o.angle = angle
            o.vx = vx; o.vy = vy; o.w = w
            o.n = n
            for (k in 0 until n) { o.bx[k] = bx[k]; o.by[k] = by[k] }
        }

        fun deriveWorld() {
            if (isCircle) { bounds(); return }
            for (k in 0 until n) {
                wx[k] = cx + bx[k] * cosA - by[k] * sinA
                wy[k] = cy + bx[k] * sinA + by[k] * cosA
            }
            bounds()
        }

        fun bounds() {
            if (isCircle) {
                minX = cx - radius; minY = cy - radius; maxX = cx + radius; maxY = cy + radius
            } else if (n > 0) {
                var a = Float.MAX_VALUE; var b = Float.MAX_VALUE
                var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
                for (k in 0 until n) {
                    if (wx[k] < a) a = wx[k]
                    if (wx[k] > c) c = wx[k]
                    if (wy[k] < b) b = wy[k]
                    if (wy[k] > d) d = wy[k]
                }
                minX = a; minY = b; maxX = c; maxY = d
            }
        }
    }

    private class Contact {
        lateinit var a: Body
        lateinit var b: Body
        val m = Collision2D.Manifold()
        val bounceBias = FloatArray(Collision2D.MAX_POINTS)
        var restitution = 0f
        var friction = 0f
        var isTrigger = false
    }

    private class Joint {
        lateinit var a: Body
        lateinit var b: Body
        var kind = 0
        var ax = 0f; var ay = 0f
        var bx = 0f; var by = 0f
        var restLength = 0f
        var frequency = 4f
        var damping = 0.7f
        var motorSpeed = 0f
        var maxTorque = 0f
        var motorImpulse = 0f
        var axisX = 0f; var axisY = 1f
        var alx = 0f; var aly = 0f
        var blx = 0f; var bly = 0f
        var localRest = 0f

        /** Recomputes world anchors from the current body transforms. */
        fun updateAnchors() {
            ax = a.cx + alx * a.cosA - aly * a.sinA
            ay = a.cy + alx * a.sinA + aly * a.cosA
            bx = b.cx + blx * b.cosA - bly * b.sinA
            by = b.cy + blx * b.sinA + bly * b.cosA
            val dx = bx - ax
            val dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            axisX = if (len > 1e-4f) dx / len else 0f
            axisY = if (len > 1e-4f) dy / len else 1f
            if (localRest <= 0f) localRest = len
        }
    }

    private val bodyPool = ArrayList<Body>()
    private var poolUsed = 0
    private val bodies = ArrayList<Body>()
    private val contacts = ArrayList<Contact>()
    private val joints = ArrayList<Joint>()
    private val contactPool = ArrayList<Contact>()
    private var contactPoolUsed = 0
    private val jointPool = ArrayList<Joint>()
    private var jointPoolUsed = 0

    private val scratch = Collision2D.Manifold()

    private val jointPairs = HashSet<Long>()
    private val impulseCache = HashMap<Long, FloatArray>()
    private val sleepTimes = HashMap<Long, Float>()
    private val sleepingIds = HashSet<Long>()

    private var prevContacts = HashMap<Long, LongArray>()
    private var prevTriggers = HashMap<Long, LongArray>()
    private var currentContacts = HashMap<Long, LongArray>()
    private var currentTriggers = HashMap<Long, LongArray>()
    private val nextImpulses = HashMap<Long, FloatArray>()

    fun reset() {
        accumulator = 0f
        prevContacts = HashMap(); prevTriggers = HashMap()
        currentContacts = HashMap(); currentTriggers = HashMap()
        sleepTimes.clear(); sleepingIds.clear()
        bodyPool.clear(); contactPool.clear(); jointPool.clear()
        poolUsed = 0; contactPoolUsed = 0; jointPoolUsed = 0
        bodies.clear(); contacts.clear(); joints.clear()
    }

    // ------------------------------------------------------------------ entry points

    fun step(scene: Scene, dt: Float) {
        this.scene = scene
        val t0 = System.nanoTime()
        accumulator += min(dt, 0.25f)
        var steps = 0
        while (accumulator >= fixedDt && steps < maxSteps) {
            fixedStep(scene, fixedDt)
            accumulator -= fixedDt
            steps++
        }
        if (steps == maxSteps) accumulator = 0f
        stats.solveMs = (System.nanoTime() - t0) / 1e6f
    }

    private fun body(): Body {
        if (poolUsed < bodyPool.size) return bodyPool[poolUsed++]
        val b = Body()
        bodyPool.add(b)
        poolUsed++
        return b
    }

    private fun contact(a: Body, b: Body): Contact {
        val c = if (contactPoolUsed < contactPool.size) contactPool[contactPoolUsed++] else Contact().also { contactPool.add(it) }
        contactPoolUsed.let { }
        c.a = a
        c.b = b
        c.m.count = 0
        c.isTrigger = false
        c.restitution = 0f
        c.friction = 0f
        for (i in 0 until Collision2D.MAX_POINTS) c.bounceBias[i] = 0f
        return c
    }

    private fun joint(): Joint {
        if (jointPoolUsed < jointPool.size) return jointPool[jointPoolUsed++]
        val j = Joint()
        jointPool.add(j)
        jointPoolUsed++
        return j
    }

    // ------------------------------------------------------------------ main loop

    private fun fixedStep(scene: Scene, dt: Float) {
        poolUsed = 0
        contactPoolUsed = 0
        jointPoolUsed = 0
        bodies.clear(); contacts.clear(); joints.clear(); jointPairs.clear()
        nextImpulses.clear()
        val nextContacts = HashMap<Long, LongArray>()
        val nextTriggers = HashMap<Long, LongArray>()

        gather(scene)
        if (bodies.isEmpty()) {
            prevContacts = HashMap(); prevTriggers = HashMap()
            stats.bodies = 0; stats.awake = 0; stats.sleeping = 0
            stats.contacts = 0; stats.triggers = 0; stats.joints = 0
            return
        }

        pickUpScriptVelocities()
        integrateForces(scene, dt)
        buildJoints(scene, dt)
        applyJointForces(dt)
        findContacts(nextContacts, nextTriggers)

        warmStart()
        repeat(velocityIterations) {
            for (j in joints) solveJointVelocity(j, dt)
            for (c in contacts) if (!c.isTrigger) solveContactVelocity(c)
        }

        integratePositions(dt)
        repeat(positionIterations) { solvePositions(); solveJointPositions() }
        storeImpulses()
        writeBack()
        dispatchEvents(nextContacts, nextTriggers)
        account(dt)
    }

    /** Reads colliders out of the scene. One collider may produce several bodies (terrain wedges). */
    private fun gather(scene: Scene) {
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>() ?: continue
            val rb = go.get<Rigidbody2D>()
            val w = go.computeWorld()
            val rot = Math.toRadians(w.rotationDeg.toDouble()).toFloat()
            val cosA = cos(rot)
            val sinA = sin(rot)
            val originX = w.tx
            val originY = w.ty

            fun newBody(): Body {
                val b = body()
                bodies.add(b)
                b.go = go; b.rb = rb; b.trigger = col.isTrigger
                b.friction = rb?.friction ?: 0.6f
                b.bounciness = rb?.bounciness ?: 0f
                b.originX = originX; b.originY = originY
                b.cosA = cosA; b.sinA = sinA; b.angle = rot; b.startAngle = rot
                b.dynamic = rb != null && rb.bodyType == 0
                b.kinematic = rb != null && rb.bodyType == 1
                b.vx = rb?.vx ?: 0f
                b.vy = rb?.vy ?: 0f
                b.w = if (rb != null && !rb.freezeRotation) rb.angularVelocity else 0f
                b.awake = b.dynamic && !sleepingIds.contains(go.id)
                b.sleepTime = sleepTimes[go.id] ?: 0f
                b.isCircle = false; b.n = 0; b.radius = 0f
                b.primary = true
                return b
            }

            when (col.shape) {
                1 -> {
                    val b = newBody()
                    b.isCircle = true
                    b.radius = max(0.001f, col.radius * max(abs(w.scaleX), abs(w.scaleY)))
                    b.cx = w.mapX(col.offsetX, col.offsetY)
                    b.cy = w.mapY(col.offsetX, col.offsetY)
                    b.startCx = b.cx; b.startCy = b.cy
                    b.ox = originX - b.cx; b.oy = originY - b.cy
                    b.bounds()
                    finishMass(b)
                }

                2 -> {
                    // Terrain polyline: every segment becomes a solid wedge below the surface.
                    val pts = col.parsePoints()
                    if (pts.size < 4) continue
                    val thickness = max(0.03f, col.thickness * abs(w.scaleY))
                    var i = 0
                    var first = true
                    while (i + 3 < pts.size) {
                        val x1 = w.mapX(pts[i], pts[i + 1])
                        val y1 = w.mapY(pts[i], pts[i + 1])
                        val x2 = w.mapX(pts[i + 2], pts[i + 3])
                        val y2 = w.mapY(pts[i + 2], pts[i + 3])
                        val b = newBody()
                        b.primary = first
                        first = false
                        val vx = floatArrayOf(x1, x2, x2, x1)
                        val vy = floatArrayOf(y1, y2, y2 - thickness, y1 - thickness)
                        b.cx = (x1 + x2) * 0.5f
                        b.cy = (y1 + y2) * 0.5f - thickness * 0.5f
                        b.angle = rot; b.cosA = cosA; b.sinA = sinA
                        b.n = 4
                        for (k in 0 until 4) {
                            val dx = vx[k] - b.cx
                            val dy = vy[k] - b.cy
                            b.bx[k] = dx * cosA + dy * sinA
                            b.by[k] = -dx * sinA + dy * cosA
                        }
                        if (Collision2D.isClockwise(b.bx, b.by, 4)) Collision2D.makeCcw(b.bx, b.by, 4)
                        b.startCx = b.cx; b.startCy = b.cy
                    b.ox = originX - b.cx; b.oy = originY - b.cy
                        b.deriveWorld()
                        // Terrain is scenery: it never moves, whatever the rigidbody says.
                        b.dynamic = false; b.kinematic = false
                        finishMass(b)
                        b.awake = false
                        i += 2
                    }
                }

                3 -> {
                    val pts = col.parsePoints()
                    if (pts.size < 6) continue
                    val b = newBody()
                    val count = min(pts.size / 2, Collision2D.MAX_VERTS)
                    val vx = FloatArray(count); val vy = FloatArray(count)
                    var sumX = 0f; var sumY = 0f
                    for (k in 0 until count) {
                        vx[k] = w.mapX(pts[k * 2], pts[k * 2 + 1])
                        vy[k] = w.mapY(pts[k * 2], pts[k * 2 + 1])
                        sumX += vx[k]; sumY += vy[k]
                    }
                    b.cx = sumX / count; b.cy = sumY / count
                    b.angle = rot; b.cosA = cosA; b.sinA = sinA
                    b.n = count
                    for (k in 0 until count) {
                        val dx = vx[k] - b.cx
                        val dy = vy[k] - b.cy
                        b.bx[k] = dx * cosA + dy * sinA
                        b.by[k] = -dx * sinA + dy * cosA
                    }
                    if (Collision2D.isClockwise(b.bx, b.by, count)) Collision2D.makeCcw(b.bx, b.by, count)
                    b.startCx = b.cx; b.startCy = b.cy
                    b.ox = originX - b.cx; b.oy = originY - b.cy
                    b.deriveWorld()
                    finishMass(b)
                }

                else -> {
                    val b = newBody()
                    b.n = 4
                    val hw = col.width * 0.5f
                    val hh = col.height * 0.5f
                    b.cx = w.mapX(col.offsetX, col.offsetY)
                    b.cy = w.mapY(col.offsetX, col.offsetY)
                    b.angle = rot; b.cosA = cosA; b.sinA = sinA
                    b.bx[0] = -hw; b.by[0] = -hh
                    b.bx[1] = hw; b.by[1] = -hh
                    b.bx[2] = hw; b.by[2] = hh
                    b.bx[3] = -hw; b.by[3] = hh
                    for (k in 0 until 4) { b.bx[k] *= abs(w.scaleX); b.by[k] *= abs(w.scaleY) }
                    b.startCx = b.cx; b.startCy = b.cy
                    b.ox = originX - b.cx; b.oy = originY - b.cy
                    b.deriveWorld()
                    finishMass(b)
                }
            }
        }
    }

    private fun finishMass(b: Body) {
        val rb = b.rb
        if (b.dynamic && rb != null) {
            val mass = max(0.001f, rb.mass)
            b.mass = mass
            b.invMass = 1f / mass
            val inertia = if (b.isCircle) {
                0.5f * mass * b.radius * b.radius
            } else {
                max(1e-5f, Collision2D.inertiaUnit(b.bx, b.by, b.n) * mass)
            } * rb.inertiaScale.coerceAtLeast(0.01f)
            b.invI = if (rb.freezeRotation) 0f else 1f / max(1e-5f, inertia)
        } else {
            b.mass = if (rb != null) max(0.001f, rb.mass) else 1f
            b.invMass = 0f
            b.invI = 0f
        }
    }

    // ------------------------------------------------------------------ sleeping

    private fun pickUpScriptVelocities() {
        for (b in bodies) {
            if (!b.dynamic) continue
            val rb = b.rb ?: continue
            val changed = abs(rb.vx - b.vx) > 1e-3f || abs(rb.vy - b.vy) > 1e-3f ||
                (!rb.freezeRotation && abs(rb.angularVelocity - b.w) > 1e-3f)
            if (!b.awake && (changed || rb.torque != 0f)) wake(b)
        }
    }

    private fun wake(b: Body) {
        if (!b.dynamic) return
        b.awake = true
        b.sleepTime = 0f
        b.go?.let {
            sleepingIds.remove(it.id)
            sleepTimes.remove(it.id)
        }
    }

    // ------------------------------------------------------------------ forces & positions

    private fun integrateForces(scene: Scene, dt: Float) {
        for (b in bodies) {
            val rb = b.rb ?: continue
            if (!b.dynamic || !b.awake) continue
            b.vx += scene.gravityX * rb.gravityScale * dt
            b.vy += scene.gravityY * rb.gravityScale * dt
            if (rb.torque != 0f && b.invI > 0f) b.w += rb.torque * b.invI * dt
            rb.torque = 0f
            if (rb.drag > 0f) {
                val k = max(0f, 1f - rb.drag * dt)
                b.vx *= k; b.vy *= k
            }
            if (rb.angularDrag > 0f && b.invI > 0f) b.w *= max(0f, 1f - rb.angularDrag * dt)
            if (rb.freezeRotation) b.w = 0f
        }
    }

    private fun clampRunaway(b: Body) {
        if (!b.vx.isFinite() || !b.vy.isFinite() || !b.w.isFinite()) {
            b.vx = 0f; b.vy = 0f; b.w = 0f
            return
        }
        b.vx = b.vx.coerceIn(-MAX_SPEED, MAX_SPEED)
        b.vy = b.vy.coerceIn(-MAX_SPEED, MAX_SPEED)
        b.w = b.w.coerceIn(-MAX_ANGULAR, MAX_ANGULAR)
    }

    private fun integratePositions(dt: Float) {
        for (b in bodies) {
            if (!b.primary) continue
            if (b.dynamic && !b.awake) continue
            if (!b.dynamic && !b.kinematic) continue
            if (b.dynamic) clampRunaway(b)
            b.cx += b.vx * dt
            b.cy += b.vy * dt
            if (b.w != 0f) {
                b.angle += b.w * dt
                b.cosA = cos(b.angle)
                b.sinA = sin(b.angle)
            }
            b.deriveWorld()
        }
    }

    // ------------------------------------------------------------------ joints

    private fun buildJoints(scene: Scene, dt: Float) {
        val byGo = HashMap<GameObject, Body>()
        for (b in bodies) {
            val go = b.go ?: continue
            val existing = byGo[go]
            if (existing == null || (b.primary && !existing.primary)) byGo[go] = b
        }
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            for (comp in go.components) {
                if (comp !is Joint2D || !comp.enabled) continue
                val a = byGo[go] ?: continue
                val target = scene.find(comp.target) ?: continue
                val b = byGo[target] ?: continue
                if (a === b) continue
                if (!a.dynamic && !b.dynamic) continue
                val j = joint()
                j.a = a; j.b = b; j.kind = comp.kind
                val wa = go.computeWorld()
                val wb = target.computeWorld()
                val wax = wa.mapX(comp.anchorX, comp.anchorY)
                val way = wa.mapY(comp.anchorX, comp.anchorY)
                val wbx = wb.mapX(comp.targetAnchorX, comp.targetAnchorY)
                val wby = wb.mapY(comp.targetAnchorX, comp.targetAnchorY)
                j.alx = (wax - a.cx) * a.cosA + (way - a.cy) * a.sinA
                j.aly = -(wax - a.cx) * a.sinA + (way - a.cy) * a.cosA
                j.blx = (wbx - b.cx) * b.cosA + (wby - b.cy) * b.sinA
                j.bly = -(wbx - b.cx) * b.sinA + (wby - b.cy) * b.cosA
                j.localRest = 0f
                j.updateAnchors()
                val len = sqrt((j.bx - j.ax) * (j.bx - j.ax) + (j.by - j.ay) * (j.by - j.ay))
                j.restLength = if (comp.restLength > 0f) comp.restLength else len
                j.frequency = comp.frequency
                j.damping = comp.damping
                j.motorSpeed = comp.motorSpeed
                j.maxTorque = comp.maxTorque
                j.motorImpulse = 0f
                wake(a); wake(b)
                jointPairs.add(pairKey(go.id, target.id))
                joints.add(j)
            }
        }
    }

    /** Springs and suspension forces are integrated once per step, before the impulse iterations. */
    private fun applyJointForces(dt: Float) {
        for (j in joints) {
            j.updateAnchors()
            if (j.kind == 2) {
                val dx = j.bx - j.ax
                val dy = j.by - j.ay
                val len = sqrt(dx * dx + dy * dy)
                if (len > 1e-5f) applySpringForce(j, len, dt, j.axisX, j.axisY)
            } else if (j.kind == 1) {
                wheelFrame(j, wf)
                applySpringForce(j, wf[5], dt, wf[0], wf[1])
            }
        }
    }

    private fun applySpringForce(j: Joint, length: Float, dt: Float, ax: Float, ay: Float) {
        val a = j.a
        val b = j.b
        val invSum = a.invMass + b.invMass
        if (invSum <= 0f) return
        // Stiffness is derived from the *reduced* mass so the spring stays stable at 60 Hz for any mass ratio.
        val effectiveMass = 1f / invSum
        val omega = (2.0 * Math.PI * j.frequency).toFloat()
        val k = omega * omega * effectiveMass
        val c = 2f * j.damping * omega * effectiveMass
        val relVx = b.vx - a.vx
        val relVy = b.vy - a.vy
        val vAlong = relVx * ax + relVy * ay
        val force = -(k * (length - j.restLength) + c * vAlong)
        val dvB = (force * b.invMass * dt).coerceIn(-MAX_SPRING_DV, MAX_SPRING_DV)
        val dvA = (force * a.invMass * dt).coerceIn(-MAX_SPRING_DV, MAX_SPRING_DV)
        if (b.invMass > 0f) {
            b.vx += ax * dvB
            b.vy += ay * dvB
        }
        if (a.invMass > 0f) {
            a.vx -= ax * dvA
            a.vy -= ay * dvA
        }
    }

    private fun solveJointVelocity(j: Joint, dt: Float) {
        val a = j.a
        val b = j.b
        if (j.kind == 2) return // springs are integrated as forces
        j.updateAnchors()
        val rax = j.ax - a.cx
        val ray = j.ay - a.cy
        val rbx = j.bx - b.cx
        val rby = j.by - b.cy
        val dvx = (b.vx - b.w * rby) - (a.vx - a.w * ray)
        val dvy = (b.vy + b.w * rbx) - (a.vy + a.w * rax)
        when (j.kind) {
            0 -> { solveHinge(j, rax, ray, rbx, rby, dvx, dvy); solveMotor(j) }
            1 -> {
                // Wheel joint in the chassis frame: rigid along chassis-right, sprung along chassis-up.
                wheelFrame(j, wf)
                val ux = wf[0]; val uy = wf[1]
                val rx = wf[2]; val ry = wf[3]
                val lateral = wf[4]
                val len = wf[5]
                val vLat = dvx * rx + dvy * ry
                val rnA = rax * ry - ray * rx
                val rnB = rbx * ry - rby * rx
                val kl = a.invMass + b.invMass + a.invI * rnA * rnA + b.invI * rnB * rnB
                if (kl > 1e-8f) {
                    // Velocity plus a small positional term so the wheel stays on its travel line.
                    val lambda = (-vLat - lateral * 4f) / kl
                    applyImpulse(b, rx * lambda, ry * lambda, rbx, rby)
                    applyImpulse(a, -rx * lambda, -ry * lambda, rax, ray)
                }
                // Bump and droop stops keep the suspension inside its travel window.
                val minLen = j.restLength * 0.55f
                val maxLen = j.restLength * 1.45f
                if (len > maxLen || len < minLen) {
                    val vAlong = dvx * ux + dvy * uy
                    val rnA2 = rax * uy - ray * ux
                    val rnB2 = rbx * uy - rby * ux
                    val kn = a.invMass + b.invMass + a.invI * rnA2 * rnA2 + b.invI * rnB2 * rnB2
                    val separating = if (len > maxLen) vAlong > 0f else vAlong < 0f
                    if (kn > 1e-8f && separating) {
                        val lambda = -vAlong / kn
                        applyImpulse(b, ux * lambda, uy * lambda, rbx, rby)
                        applyImpulse(a, -ux * lambda, -uy * lambda, rax, ray)
                    }
                }
                solveMotor(j)
            }
            else -> {
                // Rigid distance joint.
                val vAlong = dvx * j.axisX + dvy * j.axisY
                val rnA = rax * j.axisY - ray * j.axisX
                val rnB = rbx * j.axisY - rby * j.axisX
                val k = a.invMass + b.invMass + a.invI * rnA * rnA + b.invI * rnB * rnB
                if (k <= 1e-8f) return
                val lambda = -vAlong / k
                applyImpulse(b, j.axisX * lambda, j.axisY * lambda, rbx, rby)
                applyImpulse(a, -j.axisX * lambda, -j.axisY * lambda, rax, ray)
            }
        }
    }

    private val wf = FloatArray(6)

    /** Chassis-frame up/right vectors, lateral offset and signed suspension length for a wheel joint. */
    private fun wheelFrame(j: Joint, out: FloatArray) {
        val b = j.b
        val ux = -b.sinA
        val uy = b.cosA
        val rx = b.cosA
        val ry = b.sinA
        val dx = j.bx - j.ax
        val dy = j.by - j.ay
        out[0] = ux; out[1] = uy; out[2] = rx; out[3] = ry
        out[4] = dx * rx + dy * ry
        out[5] = dx * ux + dy * uy
    }

    private fun solveHinge(j: Joint, rax: Float, ray: Float, rbx: Float, rby: Float, dvx: Float, dvy: Float) {
        val a = j.a
        val b = j.b
        val k11 = a.invMass + b.invMass + a.invI * ray * ray + b.invI * rby * rby
        val k12 = -a.invI * rax * ray - b.invI * rbx * rby
        val k22 = a.invMass + b.invMass + a.invI * rax * rax + b.invI * rbx * rbx
        val det = k11 * k22 - k12 * k12
        if (abs(det) < 1e-9f) return
        val inv = 1f / det
        val lx = -(k22 * dvx - k12 * dvy) * inv
        val ly = -(k11 * dvy - k12 * dvx) * inv
        applyImpulse(b, lx, ly, rbx, rby)
        applyImpulse(a, -lx, -ly, rax, ray)
    }

    private fun solveMotor(j: Joint) {
        if (j.maxTorque <= 0f) return
        val a = j.a
        val b = j.b
        val k = a.invI + b.invI
        if (k <= 1e-9f) return
        val desired = (j.motorSpeed - (b.w - a.w)) / k
        val maxImpulse = j.maxTorque * fixedDt
        val accumulated = (j.motorImpulse + desired).coerceIn(-maxImpulse, maxImpulse)
        val impulse = accumulated - j.motorImpulse
        j.motorImpulse = accumulated
        b.w += impulse * b.invI
        a.w -= impulse * a.invI
    }

    // ------------------------------------------------------------------ contacts

    private fun findContacts(nextContacts: HashMap<Long, LongArray>, nextTriggers: HashMap<Long, LongArray>) {
        val n = bodies.size
        for (i in 0 until n) {
            val a = bodies[i]
            for (k in i + 1 until n) {
                val b = bodies[k]
                if (a.go === b.go) continue
                if (!a.dynamic && !b.dynamic && !a.kinematic && !b.kinematic) continue
                if (a.maxX < b.minX || b.maxX < a.minX || a.maxY < b.minY || b.maxY < a.minY) continue
                if (!a.awake && !b.awake) continue
                if (a.invMass == 0f && b.invMass == 0f && !a.trigger && !b.trigger) continue
                // Bodies wired together by a joint never collide with each other.
                if (jointPairs.contains(pairKey(a.go?.id ?: 0L, b.go?.id ?: 0L))) continue
                collidePair(a, b, nextContacts, nextTriggers)
            }
        }
    }

    private fun collidePair(a: Body, b: Body, nextContacts: HashMap<Long, LongArray>, nextTriggers: HashMap<Long, LongArray>) {
        scratch.count = 0
        val hit = when {
            a.isCircle && b.isCircle ->
                Collision2D.circleCircle(a.cx, a.cy, a.radius, b.cx, b.cy, b.radius, scratch)
            a.isCircle -> Collision2D.polyCircle(b.wx, b.wy, b.n, a.cx, a.cy, a.radius, scratch).also {
                if (it) { scratch.nx = -scratch.nx; scratch.ny = -scratch.ny }
            }
            b.isCircle -> Collision2D.polyCircle(a.wx, a.wy, a.n, b.cx, b.cy, b.radius, scratch)
            else -> Collision2D.polyPoly(a.wx, a.wy, a.n, b.wx, b.wy, b.n, scratch)
        }
        if (!hit || scratch.count == 0) return
        val goA = a.go ?: return
        val goB = b.go ?: return
        val isTrigger = a.trigger || b.trigger
        val key = pairKey(goA.id, goB.id)
        if (isTrigger) {
            nextTriggers[key] = longArrayOf(goA.id, goB.id)
            val c = contact(a, b)
            c.m.copyFrom(scratch)
            c.isTrigger = true
            contacts.add(c)
            return
        }
        if (a.invMass == 0f && b.invMass == 0f) return
        nextContacts[key] = longArrayOf(goA.id, goB.id)
        val c = contact(a, b)
        c.m.copyFrom(scratch)
        c.m.clearImpulses()
        c.isTrigger = false
        c.friction = sqrt(a.friction * b.friction)
        c.restitution = max(a.bounciness, b.bounciness)
        for (i in 0 until c.m.count) {
            val rx = c.m.px[i] - a.cx
            val ry = c.m.py[i] - a.cy
            val rbx = c.m.px[i] - b.cx
            val rby = c.m.py[i] - b.cy
            val vn = ((b.vx - b.w * rby) - (a.vx - a.w * ry)) * c.m.nx +
                ((b.vy + b.w * rbx) - (a.vy + a.w * rx)) * c.m.ny
            c.bounceBias[i] = if (vn < -restitutionThreshold) c.restitution * vn else 0f
        }
        contacts.add(c)

        // Grounded flags: the normal points A -> B, so a downward normal means A sits on B.
        if (c.m.ny < -0.5f) a.rb?.grounded = true
        if (c.m.ny > 0.5f) b.rb?.grounded = true
        if (a.dynamic && !a.awake) wake(a)
        if (b.dynamic && !b.awake) wake(b)
    }

    private fun pairKey(ida: Long, idb: Long): Long {
        val lo = min(ida, idb)
        val hi = max(ida, idb)
        return (lo shl 32) or (hi and 0xFFFFFFFFL)
    }

    /**
     * Re-applies the impulses that held the same pair together last step. Warm starting is what keeps
     * tall stacks and resting boxes from jittering.
     */
    private fun warmStart() {
        for (c in contacts) {
            if (c.isTrigger) continue
            val key = pairKey(c.a.go?.id ?: 0L, c.b.go?.id ?: 0L)
            val cached = impulseCache[key] ?: continue
            val m = c.m
            for (i in 0 until m.count) {
                val n = cached.getOrElse(i * 2) { 0f }
                val t = cached.getOrElse(i * 2 + 1) { 0f }
                if (n == 0f && t == 0f) continue
                m.normalImpulse[i] = n
                m.tangentImpulse[i] = t
                val rx = m.px[i] - c.a.cx
                val ry = m.py[i] - c.a.cy
                val rbx = m.px[i] - c.b.cx
                val rby = m.py[i] - c.b.cy
                val nx = m.nx; val ny = m.ny
                val tx = -ny; val ty = nx
                applyImpulse(c.b, nx * n + tx * t, ny * n + ty * t, rbx, rby)
                applyImpulse(c.a, -(nx * n + tx * t), -(ny * n + ty * t), rx, ry)
            }
        }
    }

    private fun storeImpulses() {
        nextImpulses.clear()
        for (c in contacts) {
            if (c.isTrigger) continue
            val m = c.m
            val arr = FloatArray(m.count * 2)
            for (i in 0 until m.count) {
                arr[i * 2] = m.normalImpulse[i]
                arr[i * 2 + 1] = m.tangentImpulse[i]
            }
            nextImpulses[pairKey(c.a.go?.id ?: 0L, c.b.go?.id ?: 0L)] = arr
        }
        impulseCache.clear()
        impulseCache.putAll(nextImpulses)
    }

    private fun solveContactVelocity(c: Contact) {
        val a = c.a
        val b = c.b
        val m = c.m
        val nx = m.nx
        val ny = m.ny
        val tx = -ny
        val ty = nx
        for (i in 0 until m.count) {
            val rx = m.px[i] - a.cx
            val ry = m.py[i] - a.cy
            val rbx = m.px[i] - b.cx
            val rby = m.py[i] - b.cy
            val rnA = rx * ny - ry * nx
            val rnB = rbx * ny - rby * nx
            val kn = a.invMass + b.invMass + a.invI * rnA * rnA + b.invI * rnB * rnB
            if (kn <= 1e-8f) continue
            var vn = ((b.vx - b.w * rby) - (a.vx - a.w * ry)) * nx +
                ((b.vy + b.w * rbx) - (a.vy + a.w * rx)) * ny
            var lambda = -(vn + c.bounceBias[i]) / kn
            val newNormal = max(m.normalImpulse[i] + lambda, 0f)
            lambda = newNormal - m.normalImpulse[i]
            m.normalImpulse[i] = newNormal
            applyImpulse(b, nx * lambda, ny * lambda, rbx, rby)
            applyImpulse(a, -nx * lambda, -ny * lambda, rx, ry)

            // Coulomb friction clamped by the accumulated normal impulse.
            val rtA = rx * ty - ry * tx
            val rtB = rbx * ty - rby * tx
            val kt = a.invMass + b.invMass + a.invI * rtA * rtA + b.invI * rtB * rtB
            if (kt <= 1e-8f) continue
            vn = ((b.vx - b.w * rby) - (a.vx - a.w * ry)) * tx +
                ((b.vy + b.w * rbx) - (a.vy + a.w * rx)) * ty
            val maxFriction = c.friction * m.normalImpulse[i]
            val desired = -vn / kt
            val newTangent = (m.tangentImpulse[i] + desired).coerceIn(-maxFriction, maxFriction)
            val dLambda = newTangent - m.tangentImpulse[i]
            m.tangentImpulse[i] = newTangent
            applyImpulse(b, tx * dLambda, ty * dLambda, rbx, rby)
            applyImpulse(a, -tx * dLambda, -ty * dLambda, rx, ry)
        }
    }

    private fun solvePositions() {
        for (c in contacts) {
            if (c.isTrigger) continue
            val a = c.a
            val b = c.b
            val sum = a.invMass + b.invMass
            if (sum <= 0f) continue
            // Re-measure with the *current* transforms so repeated passes cannot overshoot.
            scratch.count = 0
            val hit = when {
                a.isCircle && b.isCircle ->
                    Collision2D.circleCircle(a.cx, a.cy, a.radius, b.cx, b.cy, b.radius, scratch)
                a.isCircle -> Collision2D.polyCircle(b.wx, b.wy, b.n, a.cx, a.cy, a.radius, scratch).also {
                    if (it) { scratch.nx = -scratch.nx; scratch.ny = -scratch.ny }
                }
                b.isCircle -> Collision2D.polyCircle(a.wx, a.wy, a.n, b.cx, b.cy, b.radius, scratch)
                else -> Collision2D.polyPoly(a.wx, a.wy, a.n, b.wx, b.wy, b.n, scratch)
            }
            if (!hit) continue
            for (i in 0 until scratch.count) {
                val correction = min(max(scratch.depth[i] - linearSlop, 0f), maxLinearCorrection) / sum * 0.35f
                if (correction <= 0f) continue
                shift(a, -scratch.nx * correction * a.invMass, -scratch.ny * correction * a.invMass)
                shift(b, scratch.nx * correction * b.invMass, scratch.ny * correction * b.invMass)
            }
        }
    }

    private fun solveJointPositions() {
        for (j in joints) {
            if (j.kind == 2) continue
            j.updateAnchors()
            val a = j.a
            val b = j.b
            val sum = a.invMass + b.invMass
            if (sum <= 0f) continue
            if (j.kind == 0) {
                // Pull the two anchor points together.
                var ex = (j.bx - j.ax) * 0.25f
                var ey = (j.by - j.ay) * 0.25f
                val mag = sqrt(ex * ex + ey * ey)
                if (mag > 0.2f) { ex = ex / mag * 0.2f; ey = ey / mag * 0.2f }
                shift(a, ex * a.invMass / sum, ey * a.invMass / sum)
                shift(b, -ex * b.invMass / sum, -ey * b.invMass / sum)
            } else if (j.kind == 1) {
                wheelFrame(j, wf)
                val ux = wf[0]; val uy = wf[1]
                val rx = wf[2]; val ry = wf[3]
                val lateral = (wf[4] * 0.3f).coerceIn(-0.15f, 0.15f)
                shift(a, rx * lateral * a.invMass / sum, ry * lateral * a.invMass / sum)
                shift(b, -rx * lateral * b.invMass / sum, -ry * lateral * b.invMass / sum)
                val minLen = j.restLength * 0.55f
                val maxLen = j.restLength * 1.45f
                val len = wf[5]
                val over = when {
                    len > maxLen -> ((len - maxLen) * 0.3f).coerceIn(0f, 0.15f)
                    len < minLen -> ((len - minLen) * 0.3f).coerceIn(-0.15f, 0f)
                    else -> 0f
                }
                if (over != 0f) {
                    shift(a, ux * over * a.invMass / sum, uy * over * a.invMass / sum)
                    shift(b, -ux * over * b.invMass / sum, -uy * over * b.invMass / sum)
                }
            } else {
                val dx = j.bx - j.ax
                val dy = j.by - j.ay
                val len = sqrt(dx * dx + dy * dy)
                if (len < 1e-5f) continue
                val error = (len - j.restLength).coerceIn(-0.2f, 0.2f)
                val ux = dx / len
                val uy = dy / len
                shift(a, ux * error * a.invMass / sum, uy * error * a.invMass / sum)
                shift(b, -ux * error * b.invMass / sum, -uy * error * b.invMass / sum)
            }
        }
    }

    private fun shift(b: Body, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        b.cx += dx
        b.cy += dy
        if (b.isCircle) { b.bounds(); return }
        for (k in 0 until b.n) { b.wx[k] += dx; b.wy[k] += dy }
        b.bounds()
    }

    private fun applyImpulse(b: Body, ix: Float, iy: Float, rx: Float, ry: Float) {
        if (b.invMass == 0f) return
        b.vx += ix * b.invMass
        b.vy += iy * b.invMass
        if (b.invI != 0f) b.w += (rx * iy - ry * ix) * b.invI
    }

    // ------------------------------------------------------------------ write back & events

    private fun writeBack() {
        for (b in bodies) {
            if (!b.primary) continue
            val go = b.go ?: continue
            val rb = b.rb
            if (rb != null) {
                rb.vx = b.vx
                rb.vy = b.vy
                if (!rb.freezeRotation) rb.angularVelocity = b.w
            }
            if (!b.dynamic && !b.kinematic) continue
            if (b.dynamic && !b.awake) continue
            val delta = b.angle - b.startAngle
            val c = cos(delta)
            val s = sin(delta)
            // Keep the authored pivot: the object origin follows the body centre, rotated by the spin.
            val ox = b.originX - b.startCx
            val oy = b.originY - b.startCy
            go.setWorldPosition(b.cx + ox * c - oy * s, b.cy + ox * s + oy * c)
            if (delta != 0f) go.rotation += Math.toDegrees(delta.toDouble()).toFloat()
        }
    }

    private fun dispatchEvents(nextContacts: HashMap<Long, LongArray>, nextTriggers: HashMap<Long, LongArray>) {
        val l = listener
        if (l != null) {
            for ((key, ids) in nextContacts) {
                if (!prevContacts.containsKey(key)) {
                    val a = find(ids[0]); val b = find(ids[1])
                    if (a != null && b != null) l.onCollisionEnter(a, b)
                }
            }
            for ((key, ids) in nextTriggers) {
                if (!prevTriggers.containsKey(key)) {
                    val a = find(ids[0]); val b = find(ids[1])
                    if (a != null && b != null) l.onTriggerEnter(a, b)
                }
            }
            for ((key, ids) in prevTriggers) {
                if (!nextTriggers.containsKey(key)) {
                    val a = find(ids[0]); val b = find(ids[1])
                    if (a != null && b != null) l.onTriggerExit(a, b)
                }
            }
        }
        prevContacts = nextContacts
        prevTriggers = nextTriggers
    }

    private fun find(id: Long): GameObject? = scene?.findById(id)?.takeIf { !it.destroyed }

    private fun account(dt: Float) {
        var awake = 0
        var sleeping = 0
        for (b in bodies) {
            if (!b.dynamic || !b.primary) continue
            if (b.awake) {
                awake++
                if (enableSleeping) {
                    if (b.vx * b.vx + b.vy * b.vy < linearSleepTolerance * linearSleepTolerance &&
                        b.w * b.w < angularSleepTolerance * angularSleepTolerance
                    ) {
                        b.sleepTime += dt
                        if (b.sleepTime > timeToSleep) {
                            b.awake = false
                            b.vx = 0f; b.vy = 0f; b.w = 0f
                            b.rb?.vx = 0f; b.rb?.vy = 0f
                            b.rb?.angularVelocity = 0f
                            b.go?.let { sleepTimes[it.id] = b.sleepTime; sleepingIds.add(it.id) }
                        } else b.go?.let { sleepTimes[it.id] = b.sleepTime }
                    } else {
                        b.sleepTime = 0f
                        b.go?.let { sleepTimes.remove(it.id); sleepingIds.remove(it.id) }
                    }
                }
            } else sleeping++
        }
        stats.bodies = bodies.size
        stats.awake = awake
        stats.sleeping = sleeping
        var contactPoints = 0
        var triggerPairs = 0
        for (c in contacts) {
            if (c.isTrigger) triggerPairs += c.m.count else contactPoints += c.m.count
        }
        stats.contacts = contactPoints
        stats.triggers = triggerPairs
        stats.joints = joints.size
    }

    // ------------------------------------------------------------------ queries

    /** Returns the first active object whose collider contains the world point. */
    fun overlapPoint(scene: Scene, x: Float, y: Float): GameObject? {
        for (go in scene.objects.asReversed()) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>() ?: continue
            val w = go.computeWorld()
            when (col.shape) {
                1 -> {
                    val cx = w.mapX(col.offsetX, col.offsetY)
                    val cy = w.mapY(col.offsetX, col.offsetY)
                    val r = col.radius * max(abs(w.scaleX), abs(w.scaleY))
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) return go
                }
                else -> {
                    val pts = worldPoints(go, col, w)
                    if (pts != null && Collision2D.pointDistance(pts.first, pts.second, pts.first.size, x, y) <= 0.02f) return go
                }
            }
        }
        return null
    }

    private fun worldPoints(go: GameObject, col: Collider2D, w: com.sengine.engine.math.Affine): Pair<FloatArray, FloatArray>? {
        if (col.shape == 2 || col.shape == 3) {
            val pts = col.parsePoints()
            if (pts.size < 6) return null
            val n = pts.size / 2
            val vx = FloatArray(n); val vy = FloatArray(n)
            for (k in 0 until n) {
                vx[k] = w.mapX(pts[k * 2], pts[k * 2 + 1])
                vy[k] = w.mapY(pts[k * 2], pts[k * 2 + 1])
            }
            return vx to vy
        }
        val hw = col.width * 0.5f
        val hh = col.height * 0.5f
        val xs = floatArrayOf(-hw, hw, hw, -hw)
        val ys = floatArrayOf(-hh, -hh, hh, hh)
        val vx = FloatArray(4); val vy = FloatArray(4)
        for (k in 0 until 4) {
            vx[k] = w.mapX(col.offsetX + xs[k], col.offsetY + ys[k])
            vy[k] = w.mapY(col.offsetX + xs[k], col.offsetY + ys[k])
        }
        return vx to vy
    }

    /** First object hit by a ray (colliders only). Returns null when nothing is in range. */
    fun raycast(scene: Scene, x: Float, y: Float, dx: Float, dy: Float, maxDistance: Float): GameObject? {
        var best = maxDistance
        var bestGo: GameObject? = null
        val nrm = FloatArray(2)
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>() ?: continue
            val w = go.computeWorld()
            if (col.shape == 1) {
                val cx = w.mapX(col.offsetX, col.offsetY)
                val cy = w.mapY(col.offsetX, col.offsetY)
                val r = col.radius * max(abs(w.scaleX), abs(w.scaleY))
                val t = Collision2D.rayCircle(x, y, dx, dy, cx, cy, r, best)
                if (t >= 0f && t < best) { best = t; bestGo = go }
            } else {
                val pts = worldPoints(go, col, w) ?: continue
                val t = Collision2D.rayPoly(pts.first, pts.second, pts.first.size, x, y, dx, dy, best, nrm)
                if (t >= 0f && t < best) { best = t; bestGo = go }
            }
        }
        return bestGo
    }

    /** Objects whose collider overlaps the axis aligned box. */
    fun overlapBox(scene: Scene, minX: Float, minY: Float, maxX: Float, maxY: Float): List<GameObject> {
        val out = ArrayList<GameObject>()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider2D>() ?: continue
            val w = go.computeWorld()
            val cx = w.mapX(col.offsetX, col.offsetY)
            val cy = w.mapY(col.offsetX, col.offsetY)
            val hw: Float
            val hh: Float
            if (col.shape == 1) {
                hw = col.radius * max(abs(w.scaleX), abs(w.scaleY)); hh = hw
            } else {
                hw = col.width * 0.5f * abs(w.scaleX); hh = col.height * 0.5f * abs(w.scaleY)
            }
            if (cx - hw <= maxX && cx + hw >= minX && cy - hh <= maxY && cy + hh >= minY) out.add(go)
        }
        return out
    }
}
