package com.sengine.engine.physics

import com.sengine.engine.core.Collider3D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Rigidbody3D
import com.sengine.engine.core.Scene
import com.sengine.engine.math.Mat4
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Impulse-based 3D physics with axis-aligned boxes and spheres.
 * Shares the collision/trigger listener with the 2D world.
 */
class PhysicsWorld3D {
    var listener: PhysicsWorld.Listener? = null
    private var accumulator = 0f
    private val fixedDt = 1f / 60f
    private var prevContacts = HashSet<Long>()
    private var prevTriggers = HashSet<Long>()

    private class Body(val go: GameObject, val rb: Rigidbody3D?, val col: Collider3D) {
        var cx = 0f; var cy = 0f; var cz = 0f
        var hx = 0f; var hy = 0f; var hz = 0f; var r = 0f
        var invIx = 0f; var invIy = 0f; var invIz = 0f
        val sphere get() = col.shape == 1
        val invMass get() = if (rb == null || rb.bodyType != 0) 0f else 1f / rb.mass.coerceAtLeast(0.001f)
    }

    private class M(
        val nx: Float, val ny: Float, val nz: Float, val depth: Float,
        val x: Float, val y: Float, val z: Float,
    )

    fun reset() { accumulator = 0f; prevContacts = HashSet(); prevTriggers = HashSet() }

    fun step(scene: Scene, dt: Float) {
        if (scene.objects.none { it.getAny<Collider3D>() != null || it.getAny<Rigidbody3D>() != null }) return
        accumulator += min(dt, 0.25f)
        var n = 0
        while (accumulator >= fixedDt && n < 5) { fixed(scene, fixedDt); accumulator -= fixedDt; n++ }
        if (n == 5) accumulator = 0f
    }

    private fun move(go: GameObject, dx: Float, dy: Float, dz: Float) {
        if (go.parent == null) { go.x += dx; go.y += dy; go.z += dz } else {
            val w = go.computeWorld3()
            go.setWorldPosition3(w[12] + dx, w[13] + dy, w[14] + dz)
        }
    }

    private fun inverseInertia(go: GameObject, rb: Rigidbody3D): FloatArray {
        if (rb.freezeRotation) return floatArrayOf(0f, 0f, 0f)
        val col = go.get<Collider3D>()
        val mass = rb.mass.coerceAtLeast(0.001f)
        val w = go.computeWorld3()
        val sx = Mat4.scaleOf(w, 0); val sy = Mat4.scaleOf(w, 1); val sz = Mat4.scaleOf(w, 2)
        val moments = if (col?.shape == 1) {
            val radius = col.radius * max(sx, max(sy, sz))
            val i = 0.4f * mass * radius * radius
            floatArrayOf(i, i, i)
        } else if (col != null) {
            val hx = col.sizeX * sx * 0.5f; val hy = col.sizeY * sy * 0.5f; val hz = col.sizeZ * sz * 0.5f
            floatArrayOf(mass * (hy * hy + hz * hz) / 3f,
                mass * (hx * hx + hz * hz) / 3f,
                mass * (hx * hx + hy * hy) / 3f)
        } else floatArrayOf(mass, mass, mass)
        val scale = rb.inertiaScale.coerceAtLeast(0.01f)
        return floatArrayOf(1f / (moments[0] * scale).coerceAtLeast(1e-4f),
            1f / (moments[1] * scale).coerceAtLeast(1e-4f),
            1f / (moments[2] * scale).coerceAtLeast(1e-4f))
    }

    private fun integrateRotation(go: GameObject, rb: Rigidbody3D, dt: Float, applyTorque: Boolean) {
        if (rb.freezeRotation) {
            rb.angularVelocityX = 0f; rb.angularVelocityY = 0f; rb.angularVelocityZ = 0f
        } else {
            if (applyTorque) {
                val inv = inverseInertia(go, rb)
                rb.angularVelocityX += rb.torqueX * inv[0] * dt
                rb.angularVelocityY += rb.torqueY * inv[1] * dt
                rb.angularVelocityZ += rb.torqueZ * inv[2] * dt
            }
            if (rb.angularDrag > 0f) {
                val damping = max(0f, 1f - rb.angularDrag * dt)
                rb.angularVelocityX *= damping; rb.angularVelocityY *= damping; rb.angularVelocityZ *= damping
            }
            go.rotX += Math.toDegrees((rb.angularVelocityX * dt).toDouble()).toFloat()
            go.rotY += Math.toDegrees((rb.angularVelocityY * dt).toDouble()).toFloat()
            go.rotation += Math.toDegrees((rb.angularVelocityZ * dt).toDouble()).toFloat()
        }
        rb.torqueX = 0f; rb.torqueY = 0f; rb.torqueZ = 0f
    }

    private fun fixed(scene: Scene, dt: Float) {
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val rb = go.get<Rigidbody3D>() ?: continue
            rb.grounded = false
            when (rb.bodyType) {
                0 -> {
                    rb.vx += scene.gravityX * 0f
                    rb.vy += scene.gravity3D * rb.gravityScale * dt
                    if (rb.drag > 0f) { val k = max(0f, 1f - rb.drag * dt); rb.vx *= k; rb.vy *= k; rb.vz *= k }
                    integrateRotation(go, rb, dt, applyTorque = true)
                    move(go, rb.vx * dt, rb.vy * dt, rb.vz * dt)
                }
                1 -> {
                    integrateRotation(go, rb, dt, applyTorque = false)
                    move(go, rb.vx * dt, rb.vy * dt, rb.vz * dt)
                }
                else -> { rb.torqueX = 0f; rb.torqueY = 0f; rb.torqueZ = 0f }
            }
        }
        val bodies = ArrayList<Body>()
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider3D>() ?: continue
            val b = Body(go, go.get(), col); refresh(b); bodies.add(b)
        }
        val contacts = HashSet<Long>()
        val triggers = HashSet<Long>()
        for (i in bodies.indices) for (j in i + 1 until bodies.size) {
            val a = bodies[i]; val b = bodies[j]
            val trig = a.col.isTrigger || b.col.isTrigger
            if (a.invMass == 0f && b.invMass == 0f && !trig) continue
            val m = collide(a, b) ?: continue
            val key = key(a.go.id, b.go.id)
            if (trig) {
                triggers.add(key)
                if (key !in prevTriggers) listener?.onTriggerEnter(a.go, b.go)
                continue
            }
            contacts.add(key)
            resolve(a, b, m)
            if (key !in prevContacts) listener?.onCollisionEnter(a.go, b.go)
        }
        for (k in prevTriggers) if (k !in triggers) {
            val a = scene.findById(k shr 32); val b = scene.findById(k and 0xFFFFFFFFL)
            if (a != null && b != null) listener?.onTriggerExit(a, b)
        }
        prevContacts = contacts; prevTriggers = triggers
    }

    private fun key(a: Long, b: Long): Long = (min(a, b) shl 32) or (max(a, b) and 0xFFFFFFFFL)

    private fun refresh(b: Body) {
        val w = b.go.computeWorld3()
        val p = Mat4.point(w, b.col.centerX, b.col.centerY, b.col.centerZ)
        b.cx = p[0]; b.cy = p[1]; b.cz = p[2]
        val sx = Mat4.scaleOf(w, 0); val sy = Mat4.scaleOf(w, 1); val sz = Mat4.scaleOf(w, 2)
        b.hx = b.col.sizeX * sx / 2; b.hy = b.col.sizeY * sy / 2; b.hz = b.col.sizeZ * sz / 2
        b.r = b.col.radius * max(sx, max(sy, sz))
        val inv = if (b.rb == null || b.rb.bodyType != 0) floatArrayOf(0f, 0f, 0f) else inverseInertia(b.go, b.rb)
        b.invIx = inv[0]; b.invIy = inv[1]; b.invIz = inv[2]
    }

    private fun collide(a: Body, b: Body): M? = when {
        !a.sphere && !b.sphere -> boxBox(a, b)
        a.sphere && b.sphere -> sphereSphere(a, b)
        !a.sphere && b.sphere -> boxSphere(a, b)
        else -> boxSphere(b, a)?.let { M(-it.nx, -it.ny, -it.nz, it.depth, it.x, it.y, it.z) }
    }

    private fun boxBox(a: Body, b: Body): M? {
        val dx = b.cx - a.cx; val ox = a.hx + b.hx - abs(dx); if (ox <= 0f) return null
        val dy = b.cy - a.cy; val oy = a.hy + b.hy - abs(dy); if (oy <= 0f) return null
        val dz = b.cz - a.cz; val oz = a.hz + b.hz - abs(dz); if (oz <= 0f) return null
        val nx: Float; val ny: Float; val nz: Float; val depth: Float
        when {
            ox <= oy && ox <= oz -> { nx = if (dx < 0) -1f else 1f; ny = 0f; nz = 0f; depth = ox }
            oy <= oz -> { nx = 0f; ny = if (dy < 0) -1f else 1f; nz = 0f; depth = oy }
            else -> { nx = 0f; ny = 0f; nz = if (dz < 0) -1f else 1f; depth = oz }
        }
        val px: Float; val py: Float; val pz: Float
        val midX = (a.cx + b.cx) * 0.5f; val midY = (a.cy + b.cy) * 0.5f; val midZ = (a.cz + b.cz) * 0.5f
        if (nx != 0f) {
            px = (a.cx + a.hx * nx + b.cx - b.hx * nx) * 0.5f
            py = midY.coerceIn(max(a.cy - a.hy, b.cy - b.hy), min(a.cy + a.hy, b.cy + b.hy))
            pz = midZ.coerceIn(max(a.cz - a.hz, b.cz - b.hz), min(a.cz + a.hz, b.cz + b.hz))
        } else if (ny != 0f) {
            px = midX.coerceIn(max(a.cx - a.hx, b.cx - b.hx), min(a.cx + a.hx, b.cx + b.hx))
            py = (a.cy + a.hy * ny + b.cy - b.hy * ny) * 0.5f
            pz = midZ.coerceIn(max(a.cz - a.hz, b.cz - b.hz), min(a.cz + a.hz, b.cz + b.hz))
        } else {
            px = midX.coerceIn(max(a.cx - a.hx, b.cx - b.hx), min(a.cx + a.hx, b.cx + b.hx))
            py = midY.coerceIn(max(a.cy - a.hy, b.cy - b.hy), min(a.cy + a.hy, b.cy + b.hy))
            pz = (a.cz + a.hz * nz + b.cz - b.hz * nz) * 0.5f
        }
        return M(nx, ny, nz, depth, px, py, pz)
    }

    private fun sphereSphere(a: Body, b: Body): M? {
        val dx = b.cx - a.cx; val dy = b.cy - a.cy; val dz = b.cz - a.cz
        val rs = a.r + b.r
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 >= rs * rs) return null
        val d = sqrt(d2)
        return if (d < 1e-5f) M(0f, 1f, 0f, rs, a.cx, a.cy + a.r * 0.5f, a.cz)
        else {
            val nx = dx / d; val ny = dy / d; val nz = dz / d
            M(nx, ny, nz, rs - d, a.cx + nx * a.r, a.cy + ny * a.r, a.cz + nz * a.r)
        }
    }

    private fun boxSphere(box: Body, s: Body): M? {
        val px = s.cx.coerceIn(box.cx - box.hx, box.cx + box.hx)
        val py = s.cy.coerceIn(box.cy - box.hy, box.cy + box.hy)
        val pz = s.cz.coerceIn(box.cz - box.hz, box.cz + box.hz)
        val dx = s.cx - px; val dy = s.cy - py; val dz = s.cz - pz
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 > s.r * s.r) return null
        if (d2 < 1e-8f) {
            val ox = box.hx - abs(s.cx - box.cx); val oy = box.hy - abs(s.cy - box.cy); val oz = box.hz - abs(s.cz - box.cz)
            return when {
                ox <= oy && ox <= oz -> {
                    val nx = if (s.cx < box.cx) -1f else 1f
                    M(nx, 0f, 0f, ox + s.r, box.cx + nx * box.hx,
                        s.cy.coerceIn(box.cy - box.hy, box.cy + box.hy), s.cz.coerceIn(box.cz - box.hz, box.cz + box.hz))
                }
                oy <= oz -> {
                    val ny = if (s.cy < box.cy) -1f else 1f
                    M(0f, ny, 0f, oy + s.r, s.cx.coerceIn(box.cx - box.hx, box.cx + box.hx),
                        box.cy + ny * box.hy, s.cz.coerceIn(box.cz - box.hz, box.cz + box.hz))
                }
                else -> {
                    val nz = if (s.cz < box.cz) -1f else 1f
                    M(0f, 0f, nz, oz + s.r, s.cx.coerceIn(box.cx - box.hx, box.cx + box.hx),
                        s.cy.coerceIn(box.cy - box.hy, box.cy + box.hy), box.cz + nz * box.hz)
                }
            }
        }
        val d = sqrt(d2)
        return M(dx / d, dy / d, dz / d, s.r - d, px, py, pz)
    }

    private fun velocityAt(b: Body, rx: Float, ry: Float, rz: Float): FloatArray {
        val rb = b.rb?.takeIf { it.bodyType != 2 } ?: return floatArrayOf(0f, 0f, 0f)
        val wx = if (rb.freezeRotation) 0f else rb.angularVelocityX
        val wy = if (rb.freezeRotation) 0f else rb.angularVelocityY
        val wz = if (rb.freezeRotation) 0f else rb.angularVelocityZ
        return floatArrayOf(rb.vx + wy * rz - wz * ry,
            rb.vy + wz * rx - wx * rz,
            rb.vz + wx * ry - wy * rx)
    }

    private fun resolve(a: Body, b: Body, m: M) {
        val ia = a.invMass; val ib = b.invMass; val sum = ia + ib
        if (sum == 0f) return
        val corr = max(m.depth - 0.001f, 0f) / sum * 0.9f
        if (ia > 0f) move(a.go, -m.nx * corr * ia, -m.ny * corr * ia, -m.nz * corr * ia)
        if (ib > 0f) move(b.go, m.nx * corr * ib, m.ny * corr * ib, m.nz * corr * ib)
        if (m.ny < -0.5f) a.rb?.grounded = true
        if (m.ny > 0.5f) b.rb?.grounded = true

        val rax = m.x - a.cx; val ray = m.y - a.cy; val raz = m.z - a.cz
        val rbx = m.x - b.cx; val rby = m.y - b.cy; val rbz = m.z - b.cz
        val va = velocityAt(a, rax, ray, raz); val vb = velocityAt(b, rbx, rby, rbz)
        var rvx = vb[0] - va[0]; var rvy = vb[1] - va[1]; var rvz = vb[2] - va[2]
        val vn = rvx * m.nx + rvy * m.ny + rvz * m.nz
        if (vn >= 0f) return

        val anx = ray * m.nz - raz * m.ny
        val any = raz * m.nx - rax * m.nz
        val anz = rax * m.ny - ray * m.nx
        val bnx = rby * m.nz - rbz * m.ny
        val bny = rbz * m.nx - rbx * m.nz
        val bnz = rbx * m.ny - rby * m.nx
        val normalDenom = sum + anx * anx * a.invIx + any * any * a.invIy + anz * anz * a.invIz +
            bnx * bnx * b.invIx + bny * bny * b.invIy + bnz * bnz * b.invIz
        if (normalDenom <= 1e-8f) return
        val e = max(a.rb?.bounciness ?: 0f, b.rb?.bounciness ?: 0f)
        val j = -(1f + e) * vn / normalDenom
        impulse(a, -j * m.nx, -j * m.ny, -j * m.nz, rax, ray, raz)
        impulse(b, j * m.nx, j * m.ny, j * m.nz, rbx, rby, rbz)

        val vaAfter = velocityAt(a, rax, ray, raz); val vbAfter = velocityAt(b, rbx, rby, rbz)
        rvx = vbAfter[0] - vaAfter[0]; rvy = vbAfter[1] - vaAfter[1]; rvz = vbAfter[2] - vaAfter[2]
        var tx = rvx - (rvx * m.nx + rvy * m.ny + rvz * m.nz) * m.nx
        var ty = rvy - (rvx * m.nx + rvy * m.ny + rvz * m.nz) * m.ny
        var tz = rvz - (rvx * m.nx + rvy * m.ny + rvz * m.nz) * m.nz
        val tl = sqrt(tx * tx + ty * ty + tz * tz)
        if (tl < 1e-5f) return
        tx /= tl; ty /= tl; tz /= tl
        val atx = ray * tz - raz * ty; val aty = raz * tx - rax * tz; val atz = rax * ty - ray * tx
        val btx = rby * tz - rbz * ty; val bty = rbz * tx - rbx * tz; val btz = rbx * ty - rby * tx
        val tangentDenom = sum + atx * atx * a.invIx + aty * aty * a.invIy + atz * atz * a.invIz +
            btx * btx * b.invIx + bty * bty * b.invIy + btz * btz * b.invIz
        if (tangentDenom <= 1e-8f) return
        val mu = sqrt((a.rb?.friction ?: 0.5f) * (b.rb?.friction ?: 0.5f))
        val jt = (-(rvx * tx + rvy * ty + rvz * tz) / tangentDenom).coerceIn(-j * mu, j * mu)
        impulse(a, -jt * tx, -jt * ty, -jt * tz, rax, ray, raz)
        impulse(b, jt * tx, jt * ty, jt * tz, rbx, rby, rbz)
    }

    private fun impulse(b: Body, x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float) {
        val inv = b.invMass
        if (inv == 0f) return
        val rb = b.rb ?: return
        rb.vx += x * inv; rb.vy += y * inv; rb.vz += z * inv
        rb.angularVelocityX += (ry * z - rz * y) * b.invIx
        rb.angularVelocityY += (rz * x - rx * z) * b.invIy
        rb.angularVelocityZ += (rx * y - ry * x) * b.invIz
    }

    /** Ray cast against colliders; returns the hit object or null. */
    fun raycast(scene: Scene, ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float): GameObject? {
        var best: GameObject? = null
        var bestT = maxDist
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val col = go.get<Collider3D>() ?: continue
            val b = Body(go, null, col); refresh(b)
            val t = if (b.sphere) raySphere(ox, oy, oz, dx, dy, dz, b) else rayBox(ox, oy, oz, dx, dy, dz, b)
            if (t != null && t >= 0f && t < bestT) { bestT = t; best = go }
        }
        return best
    }

    private fun raySphere(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, b: Body): Float? {
        val lx = ox - b.cx; val ly = oy - b.cy; val lz = oz - b.cz
        val bb = lx * dx + ly * dy + lz * dz
        val c = lx * lx + ly * ly + lz * lz - b.r * b.r
        val disc = bb * bb - c
        if (disc < 0f) return null
        return -bb - sqrt(disc)
    }

    private fun rayBox(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, b: Body): Float? =
        rayAabb(ox, oy, oz, dx, dy, dz, b.cx - b.hx, b.cy - b.hy, b.cz - b.hz, b.cx + b.hx, b.cy + b.hy, b.cz + b.hz)

    companion object {
        fun rayAabb(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float,
                    minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float): Float? {
            var tmin = -Float.MAX_VALUE; var tmax = Float.MAX_VALUE
            val o = floatArrayOf(ox, oy, oz); val d = floatArrayOf(dx, dy, dz)
            val mn = floatArrayOf(minX, minY, minZ); val mx = floatArrayOf(maxX, maxY, maxZ)
            for (i in 0 until 3) {
                if (abs(d[i]) < 1e-8f) { if (o[i] < mn[i] || o[i] > mx[i]) return null } else {
                    var t1 = (mn[i] - o[i]) / d[i]; var t2 = (mx[i] - o[i]) / d[i]
                    if (t1 > t2) { val t = t1; t1 = t2; t2 = t }
                    tmin = max(tmin, t1); tmax = min(tmax, t2)
                    if (tmin > tmax) return null
                }
            }
            if (tmax < 0f) return null
            return if (tmin >= 0f) tmin else tmax
        }
    }
}
