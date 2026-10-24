package com.sengine.engine.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Convex collision geometry for the 2D physics world.
 *
 * This file contains no Android types so it can be unit-tested on a desktop JVM. It implements the
 * geometry half of a Box2D-style solver:
 *
 * * separating-axis tests for convex polygons (2 contact points via reference/incident clipping),
 * * circle against convex polygon (1 contact point, vertex/face regions handled),
 * * circle against circle (1 contact point),
 * * polygon mass properties (area and inertia about the local origin).
 *
 * Every manifold normal points **from A to B**. Positive [Manifold.depth] means penetration.
 */
object Collision2D {

    const val MAX_POINTS = 2
    /** Maximum supported vertices per convex shape (cached arrays are sized for this). */
    const val MAX_VERTS = 16
    /** Guard against degenerate geometry in authored point lists. */
    private const val EPS = 1e-6f

    /** A manifold produced by one of the collide functions. */
    class Manifold {
        var nx = 0f
        var ny = 0f
        var count = 0
        val px = FloatArray(MAX_POINTS)
        val py = FloatArray(MAX_POINTS)
        /** Penetration depth at each point (>= 0). */
        val depth = FloatArray(MAX_POINTS)
        /** Accumulated impulses, reset per solver step. */
        val normalImpulse = FloatArray(MAX_POINTS)
        val tangentImpulse = FloatArray(MAX_POINTS)

        fun copyFrom(o: Manifold) {
            nx = o.nx; ny = o.ny; count = o.count
            for (i in 0 until MAX_POINTS) {
                px[i] = o.px[i]; py[i] = o.py[i]; depth[i] = o.depth[i]
                normalImpulse[i] = o.normalImpulse[i]; tangentImpulse[i] = o.tangentImpulse[i]
            }
        }

        fun clearImpulses() { for (i in 0 until MAX_POINTS) { normalImpulse[i] = 0f; tangentImpulse[i] = 0f } }
    }

    // ------------------------------------------------------------------ mass properties

    /** Unsigned area of a CCW-local polygon. */
    fun area(vx: FloatArray, vy: FloatArray, n: Int): Float {
        var a = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += vx[i] * vy[j] - vx[j] * vy[i]
        }
        return abs(a) * 0.5f
    }

    /**
     * Second moment of inertia about the local origin for unit mass.
     * Uses the shoelace form, valid for any simple polygon (convex assumed by the engine).
     */
    fun inertiaUnit(vx: FloatArray, vy: FloatArray, n: Int): Float {
        var cross = 0f
        var sum = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val c = vx[i] * vy[j] - vx[j] * vy[i]
            cross += c
            sum += c * (vx[i] * vx[i] + vx[i] * vx[j] + vx[j] * vx[j] +
                vy[i] * vy[i] + vy[i] * vy[j] + vy[j] * vy[j])
        }
        if (abs(cross) < EPS) return 0f
        return abs(sum / (12f * (cross * 0.5f)))
    }

    /** Signed area is negative for clockwise winding. */
    fun isClockwise(vx: FloatArray, vy: FloatArray, n: Int): Boolean {
        var a = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += vx[i] * vy[j] - vx[j] * vy[i]
        }
        return a < 0f
    }

    /** Sorts local polygon vertices into counter-clockwise winding (in place). */
    fun makeCcw(vx: FloatArray, vy: FloatArray, n: Int) {
        if (!isClockwise(vx, vy, n)) return
        var i = 0
        var j = n - 1
        while (i < j) {
            val tx = vx[i]; vx[i] = vx[j]; vx[j] = tx
            val ty = vy[i]; vy[i] = vy[j]; vy[j] = ty
            i++; j--
        }
    }

    // ------------------------------------------------------------------ polygon vs polygon

    /** World-space face normal of edge i of the polygon (CCW => outward). */
    private fun edgeNormal(vx: FloatArray, vy: FloatArray, n: Int, i: Int, out: FloatArray) {
        val j = (i + 1) % n
        var ex = vx[j] - vx[i]
        var ey = vy[j] - vy[i]
        val len = sqrt(ex * ex + ey * ey)
        if (len < EPS) { out[0] = 0f; out[1] = 0f; return }
        ex /= len; ey /= len
        out[0] = ey
        out[1] = -ex
    }

    /** Transforms local (px,py) by rotation (cos,sin) + translation. */
    fun worldX(px: Float, py: Float, cosA: Float, sinA: Float, tx: Float) = px * cosA - py * sinA + tx
    fun worldY(px: Float, py: Float, cosA: Float, sinA: Float, ty: Float) = px * sinA + py * cosA + ty

    /**
     * Convex polygon against convex polygon using SAT with reference/incident face clipping.
     * Fills [out] with up to two contact points. Returns false when the shapes do not overlap.
     */
    fun polyPoly(
        ax: FloatArray, ay: FloatArray, an: Int,
        bx: FloatArray, by: FloatArray, bn: Int,
        out: Manifold,
    ): Boolean {
        if (an < 3 || bn < 3) return false
        val nA = FloatArray(2)
        val nB = FloatArray(2)

        // Deepest face of A for B, and deepest face of B for A.
        var sepA = -Float.MAX_VALUE
        var faceA = 0
        for (i in 0 until an) {
            edgeNormal(ax, ay, an, i, nA)
            var minS = Float.MAX_VALUE
            for (k in 0 until bn) {
                val s = nA[0] * (bx[k] - ax[i]) + nA[1] * (by[k] - ay[i])
                if (s < minS) minS = s
            }
            if (minS > sepA) { sepA = minS; faceA = i }
        }
        if (sepA > 0f) return false

        var sepB = -Float.MAX_VALUE
        var faceB = 0
        for (i in 0 until bn) {
            edgeNormal(bx, by, bn, i, nB)
            var minS = Float.MAX_VALUE
            for (k in 0 until an) {
                val s = nB[0] * (ax[k] - bx[i]) + nB[1] * (ay[k] - by[i])
                if (s < minS) minS = s
            }
            if (minS > sepB) { sepB = minS; faceB = i }
        }
        if (sepB > 0f) return false

        // Pick the reference face (the one with the larger separation, i.e. less deep).
        val flip = sepB > sepA + 0.001f
        val refX: FloatArray; val refY: FloatArray; val refN: Int; val refFace: Int
        val incX: FloatArray; val incY: FloatArray; val incN: Int
        if (!flip) {
            refX = ax; refY = ay; refN = an; refFace = faceA; incX = bx; incY = by; incN = bn
        } else {
            refX = bx; refY = by; refN = bn; refFace = faceB; incX = ax; incY = ay; incN = an
        }

        edgeNormal(refX, refY, refN, refFace, nA)
        val rn0 = nA[0]; val rn1 = nA[1]
        val r0 = refFace
        val r1 = (refFace + 1) % refN

        // Incident face: the face of the other polygon most anti-parallel to the reference normal.
        var incFace = 0
        var minDot = Float.MAX_VALUE
        for (i in 0 until incN) {
            edgeNormal(incX, incY, incN, i, nB)
            val d = nB[0] * rn0 + nB[1] * rn1
            if (d < minDot) { minDot = d; incFace = i }
        }
        val i1 = incFace
        val i2 = (incFace + 1) % incN

        // Side planes of the reference face: tangent t = (-n.y, n.x).
        val tx = -rn1
        val ty = rn0
        val t0 = tx * refX[r0] + ty * refY[r0]
        val t1 = tx * refX[r1] + ty * refY[r1]
        val seg = Array(2) { floatArrayOf(0f, 0f) }
        seg[0][0] = incX[i1]; seg[0][1] = incY[i1]
        seg[1][0] = incX[i2]; seg[1][1] = incY[i2]
        val tmp = Array(2) { floatArrayOf(0f, 0f) }
        val c1 = clipSegment(seg, 2, tmp, tx, ty, t0)
        if (c1 == 0) return false
        val clip = Array(2) { floatArrayOf(0f, 0f) }
        val c2 = clipSegment(tmp, c1, clip, -tx, -ty, -t1)
        if (c2 == 0) return false

        out.count = 0
        for (k in 0 until c2) {
            val dx = clip[k][0] - refX[r0]
            val dy = clip[k][1] - refY[r0]
            val separation = rn0 * dx + rn1 * dy
            if (separation <= 0f) {
                val idx = out.count
                out.px[idx] = clip[k][0]
                out.py[idx] = clip[k][1]
                out.depth[idx] = -separation
                out.count++
                if (out.count == MAX_POINTS) break
            }
        }
        if (out.count == 0) return false
        // Normal must point from A to B regardless of which polygon provided the reference face.
        if (!flip) {
            out.nx = rn0; out.ny = rn1
        } else {
            out.nx = -rn0; out.ny = -rn1
        }
        return true
    }

    /** Clips [count] points of [ins] against the plane dot(n, p) = offset; keeps the positive side. */
    private fun clipSegment(ins: Array<FloatArray>, count: Int, out: Array<FloatArray>, nx: Float, ny: Float, offset: Float): Int {
        if (count < 2) {
            if (count == 0) return 0
            val d = nx * ins[0][0] + ny * ins[0][1] - offset
            if (d < 0f) return 0
            out[0][0] = ins[0][0]; out[0][1] = ins[0][1]
            return 1
        }
        val d0 = nx * ins[0][0] + ny * ins[0][1] - offset
        val d1 = nx * ins[1][0] + ny * ins[1][1] - offset
        var n = 0
        if (d0 >= 0f) { out[n][0] = ins[0][0]; out[n][1] = ins[0][1]; n++ }
        if (d1 >= 0f) { out[n][0] = ins[1][0]; out[n][1] = ins[1][1]; n++ }
        if (d0 * d1 < 0f && n < 2) {
            val t = d0 / (d0 - d1)
            out[n][0] = ins[0][0] + (ins[1][0] - ins[0][0]) * t
            out[n][1] = ins[0][1] + (ins[1][1] - ins[0][1]) * t
            n++
        }
        return n
    }

    // ------------------------------------------------------------------ circles

    /** Circle (cx,cy,r) against convex polygon; normal points from polygon to circle. */
    fun polyCircle(
        vx: FloatArray, vy: FloatArray, n: Int,
        cx: Float, cy: Float, radius: Float,
        out: Manifold,
    ): Boolean {
        if (n < 3) return false
        val nrm = FloatArray(2)
        // Is the centre inside the polygon? Use the deepest face.
        var maxSep = -Float.MAX_VALUE
        var maxFace = 0
        for (i in 0 until n) {
            edgeNormal(vx, vy, n, i, nrm)
            val s = nrm[0] * (cx - vx[i]) + nrm[1] * (cy - vy[i])
            if (s > maxSep) { maxSep = s; maxFace = i }
        }
        if (maxSep > radius) return false
        if (maxSep < 0f) {
            edgeNormal(vx, vy, n, maxFace, nrm)
            out.count = 1
            out.nx = nrm[0]; out.ny = nrm[1]
            out.px[0] = cx - nrm[0] * radius
            out.py[0] = cy - nrm[1] * radius
            out.depth[0] = radius - maxSep
            return true
        }
        // Closest point on the polygon boundary.
        var bestD2 = Float.MAX_VALUE
        var qx = 0f
        var qy = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ex = vx[j] - vx[i]
            val ey = vy[j] - vy[i]
            val len2 = ex * ex + ey * ey
            var t = if (len2 < EPS) 0f else ((cx - vx[i]) * ex + (cy - vy[i]) * ey) / len2
            t = t.coerceIn(0f, 1f)
            val px = vx[i] + ex * t
            val py = vy[i] + ey * t
            val dx = cx - px
            val dy = cy - py
            val d2 = dx * dx + dy * dy
            if (d2 < bestD2) { bestD2 = d2; qx = px; qy = py }
        }
        val d = sqrt(bestD2)
        if (d > radius) return false
        if (d < EPS) {
            edgeNormal(vx, vy, n, maxFace, nrm)
            out.count = 1
            out.nx = nrm[0]; out.ny = nrm[1]
            out.px[0] = qx; out.py[0] = qy
            out.depth[0] = radius
            return true
        }
        out.count = 1
        out.nx = (cx - qx) / d; out.ny = (cy - qy) / d
        out.px[0] = qx; out.py[0] = qy
        out.depth[0] = radius - d
        return true
    }

    /** Circle against circle; normal from A to B. */
    fun circleCircle(
        ax: Float, ay: Float, ar: Float,
        bx: Float, by: Float, br: Float,
        out: Manifold,
    ): Boolean {
        val dx = bx - ax
        val dy = by - ay
        val rs = ar + br
        val d2 = dx * dx + dy * dy
        if (d2 >= rs * rs) return false
        val d = sqrt(d2)
        out.count = 1
        if (d < EPS) {
            out.nx = 0f; out.ny = 1f
            out.px[0] = ax; out.py[0] = ay + ar * 0.5f
            out.depth[0] = rs
        } else {
            out.nx = dx / d; out.ny = dy / d
            out.px[0] = ax + out.nx * ar
            out.py[0] = ay + out.ny * ar
            out.depth[0] = rs - d
        }
        return true
    }

    /** Axis-aligned bounds of a world polygon. */
    fun bounds(vx: FloatArray, vy: FloatArray, n: Int, out: FloatArray) {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            if (vx[i] < minX) minX = vx[i]
            if (vx[i] > maxX) maxX = vx[i]
            if (vy[i] < minY) minY = vy[i]
            if (vy[i] > maxY) maxY = vy[i]
        }
        out[0] = minX; out[1] = minY; out[2] = maxX; out[3] = maxY
    }

    /** Ray against polygon, returning the first hit distance (or -1). Normal points outward. */
    fun rayPoly(
        vx: FloatArray, vy: FloatArray, n: Int,
        ox: Float, oy: Float, dx: Float, dy: Float, maxDistance: Float,
        hitNormal: FloatArray,
    ): Float {
        var best = maxDistance
        var found = false
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ex = vx[j] - vx[i]
            val ey = vy[j] - vy[i]
            val denom = dx * ey - dy * ex
            if (abs(denom) < EPS) continue
            val t = ((vx[i] - ox) * ey - (vy[i] - oy) * ex) / denom
            val u = ((vx[i] - ox) * dy - (vy[i] - oy) * dx) / denom
            if (t >= 0f && t <= best && u >= 0f && u <= 1f) {
                best = t
                found = true
                val len = sqrt(ex * ex + ey * ey)
                hitNormal[0] = ey / len; hitNormal[1] = -ex / len
            }
        }
        return if (found) best else -1f
    }

    /** Ray against circle. */
    fun rayCircle(ox: Float, oy: Float, dx: Float, dy: Float, cx: Float, cy: Float, r: Float, maxDistance: Float): Float {
        val mx = ox - cx
        val my = oy - cy
        val b = mx * dx + my * dy
        val c = mx * mx + my * my - r * r
        if (c > 0f && b > 0f) return -1f
        val disc = b * b - c
        if (disc < 0f) return -1f
        var t = -b - sqrt(disc)
        if (t < 0f) t = 0f
        return if (t <= maxDistance) t else -1f
    }

    /** Smallest distance between a point and a polygon boundary (0 when inside). */
    fun pointDistance(vx: FloatArray, vy: FloatArray, n: Int, px: Float, py: Float): Float {
        var inside = true
        val nrm = FloatArray(2)
        var best = Float.MAX_VALUE
        for (i in 0 until n) {
            edgeNormal(vx, vy, n, i, nrm)
            if (nrm[0] * (px - vx[i]) + nrm[1] * (py - vy[i]) > 0f) inside = false
            val j = (i + 1) % n
            val ex = vx[j] - vx[i]; val ey = vy[j] - vy[i]
            val len2 = ex * ex + ey * ey
            var t = if (len2 < EPS) 0f else ((px - vx[i]) * ex + (py - vy[i]) * ey) / len2
            t = t.coerceIn(0f, 1f)
            val dx = px - (vx[i] + ex * t)
            val dy = py - (vy[i] + ey * t)
            best = min(best, sqrt(dx * dx + dy * dy))
        }
        return if (inside) 0f else best
    }

    /** Convex hull (monotone chain) of a point list. Returns the vertex count written to [outX]/[outY]. */
    fun convexHull(px: FloatArray, py: FloatArray, n: Int, outX: FloatArray, outY: FloatArray): Int {
        if (n < 3) {
            for (i in 0 until n) { outX[i] = px[i]; outY[i] = py[i] }
            return n
        }
        val idx = (0 until n).sortedWith(compareBy({ px[it] }, { py[it] }))
        val hull = ArrayList<Int>(n * 2)
        for (i in idx) {
            while (hull.size >= 2 && cross(px, py, hull[hull.size - 2], hull[hull.size - 1], i) <= 0f) hull.removeAt(hull.size - 1)
            hull.add(i)
        }
        val lower = hull.size + 1
        for (k in idx.size - 2 downTo 0) {
            val i = idx[k]
            while (hull.size >= lower && cross(px, py, hull[hull.size - 2], hull[hull.size - 1], i) <= 0f) hull.removeAt(hull.size - 1)
            hull.add(i)
        }
        val count = min(hull.size - 1, outX.size)
        for (i in 0 until count) { outX[i] = px[hull[i]]; outY[i] = py[hull[i]] }
        return max(count, 0)
    }

    private fun cross(px: FloatArray, py: FloatArray, a: Int, b: Int, c: Int): Float =
        (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])
}
