package com.sengine.engine.joints

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import kotlin.math.*

// ─── Physics Joints System ────────────────────────────────────────────────

/**
 * Professional 2D physics joints — connects two rigidbodies with constraints.
 * Like Unity's Joint2D components.
 */

/** Spring joint — connects two bodies with a spring force. */
class SpringJoint2D : Component() {
    override val type = "SpringJoint2D"
    var connectedBody: String = ""   // Name of the other game object
    var anchorX: Float = 0f
    var anchorY: Float = 0f
    var connectedAnchorX: Float = 0f
    var connectedAnchorY: Float = 0f
    var restLength: Float = 1f
    var stiffness: Float = 50f
    var damping: Float = 1f
    var breakForce: Float = Float.MAX_VALUE
    var autoConfigure: Boolean = true

    // Runtime
    @Transient var currentLength: Float = 0f
    @Transient var broken: Boolean = false

    override fun props() = listOf(
        Prop.S("Connected Body", { connectedBody }, { connectedBody = it }),
        Prop.F("Anchor X", { anchorX }, { anchorX = it }, 0.1f),
        Prop.F("Anchor Y", { anchorY }, { anchorY = it }, 0.1f),
        Prop.F("Rest Length", { restLength }, { restLength = it.coerceAtLeast(0f) }, 0.1f),
        Prop.F("Stiffness", { stiffness }, { stiffness = it.coerceAtLeast(0f) }, 5f),
        Prop.F("Damping", { damping }, { damping = it.coerceAtLeast(0f) }, 0.1f),
        Prop.F("Break Force", { breakForce }, { breakForce = it.coerceAtLeast(0f) }, 10f),
        Prop.B("Auto Configure", { autoConfigure }, { autoConfigure = it }),
    )

    /** Calculate spring force between two positions. */
    fun calculateForce(ax: Float, ay: Float, bx: Float, by: Float, vaX: Float, vaY: Float, vbX: Float, vbY: Float): FloatArray {
        val dx = bx - ax
        val dy = by - ay
        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        currentLength = dist

        // Spring force (Hooke's law)
        val stretch = dist - restLength
        val nx = dx / dist
        val ny = dy / dist

        // Relative velocity along spring axis
        val relVx = vbX - vaX
        val relVy = vbY - vaY
        val relVelAlongAxis = relVx * nx + relVy * ny

        val springForce = stiffness * stretch
        val dampingForce = damping * relVelAlongAxis
        val totalForce = springForce + dampingForce

        // Break check
        if (abs(totalForce) > breakForce) {
            broken = true
        }

        return floatArrayOf(nx * totalForce, ny * totalForce)
    }
}

/** Distance joint — maintains a fixed distance between two bodies. */
class DistanceJoint2D : Component() {
    override val type = "DistanceJoint2D"
    var connectedBody: String = ""
    var anchorX: Float = 0f
    var anchorY: Float = 0f
    var distance: Float = 2f
    var minDistance: Float = 0f
    var maxDistance: Float = Float.MAX_VALUE
    var stiffness: Float = Float.MAX_VALUE // Very stiff = fixed distance
    var damping: Float = 0f
    var breakForce: Float = Float.MAX_VALUE
    var autoConfigure: Boolean = true

    @Transient var broken: Boolean = false

    override fun props() = listOf(
        Prop.S("Connected Body", { connectedBody }, { connectedBody = it }),
        Prop.F("Distance", { distance }, { distance = it.coerceAtLeast(0.01f) }, 0.1f),
        Prop.F("Min Distance", { minDistance }, { minDistance = it.coerceAtLeast(0f) }, 0.1f),
        Prop.F("Max Distance", { maxDistance }, { maxDistance = it.coerceAtLeast(0f) }, 0.5f),
        Prop.F("Stiffness", { stiffness }, { stiffness = it.coerceAtLeast(0f) }, 10f),
        Prop.F("Damping", { damping }, { damping = it.coerceAtLeast(0f) }, 0.1f),
        Prop.F("Break Force", { breakForce }, { breakForce = it.coerceAtLeast(0f) }, 10f),
    )
}

/** Hinge joint — allows rotation around an anchor point. */
class HingeJoint2D : Component() {
    override val type = "HingeJoint2D"
    var connectedBody: String = ""
    var anchorX: Float = 0f
    var anchorY: Float = 0f
    var useLimits: Boolean = false
    var lowerAngle: Float = -90f
    var upperAngle: Float = 90f
    var useMotor: Boolean = false
    var motorSpeed: Float = 0f
    var maxMotorTorque: Float = 100f
    var breakForce: Float = Float.MAX_VALUE

    @Transient var angle: Float = 0f
    @Transient var broken: Boolean = false

    override fun props() = listOf(
        Prop.S("Connected Body", { connectedBody }, { connectedBody = it }),
        Prop.F("Anchor X", { anchorX }, { anchorX = it }, 0.1f),
        Prop.F("Anchor Y", { anchorY }, { anchorY = it }, 0.1f),
        Prop.B("Use Limits", { useLimits }, { useLimits = it }),
        Prop.F("Lower Angle", { lowerAngle }, { lowerAngle = it }, 5f),
        Prop.F("Upper Angle", { upperAngle }, { upperAngle = it }, 5f),
        Prop.B("Use Motor", { useMotor }, { useMotor = it }),
        Prop.F("Motor Speed", { motorSpeed }, { motorSpeed = it }, 10f),
        Prop.F("Max Motor Torque", { maxMotorTorque }, { maxMotorTorque = it.coerceAtLeast(0f) }, 10f),
        Prop.F("Break Force", { breakForce }, { breakForce = it.coerceAtLeast(0f) }, 10f),
    )
}

/** Wheel joint — for vehicle wheels with suspension. */
class WheelJoint2D : Component() {
    override val type = "WheelJoint2D"
    var connectedBody: String = ""
    var anchorX: Float = 0f
    var anchorY: Float = 0f
    var suspensionDirection: Float = 270f // degrees, default = down
    var useMotor: Boolean = false
    var motorSpeed: Float = 0f
    var maxMotorTorque: Float = 100f
    var frequency: Float = 4f     // Suspension frequency (Hz)
    var dampingRatio: Float = 0.7f // 0=no damping, 1=critical
    var breakForce: Float = Float.MAX_VALUE

    @Transient var broken: Boolean = false

    override fun props() = listOf(
        Prop.S("Connected Body", { connectedBody }, { connectedBody = it }),
        Prop.F("Anchor X", { anchorX }, { anchorX = it }, 0.1f),
        Prop.F("Anchor Y", { anchorY }, { anchorY = it }, 0.1f),
        Prop.F("Suspension Angle", { suspensionDirection }, { suspensionDirection = it }, 5f),
        Prop.B("Use Motor", { useMotor }, { useMotor = it }),
        Prop.F("Motor Speed", { motorSpeed }, { motorSpeed = it }, 10f),
        Prop.F("Max Motor Torque", { maxMotorTorque }, { maxMotorTorque = it.coerceAtLeast(0f) }, 10f),
        Prop.F("Frequency", { frequency }, { frequency = it.coerceAtLeast(0.1f) }, 0.5f),
        Prop.F("Damping Ratio", { dampingRatio }, { dampingRatio = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Break Force", { breakForce }, { breakForce = it.coerceAtLeast(0f) }, 10f),
    )
}

/** Target joint — pulls a body toward a target position. */
class TargetJoint2D : Component() {
    override val type = "TargetJoint2D"
    var targetX: Float = 0f
    var targetY: Float = 0f
    var anchorX: Float = 0f
    var anchorY: Float = 0f
    var frequency: Float = 5f
    var dampingRatio: Float = 0.7f
    var maxForce: Float = 1000f

    override fun props() = listOf(
        Prop.F("Target X", { targetX }, { targetX = it }, 0.5f),
        Prop.F("Target Y", { targetY }, { targetY = it }, 0.5f),
        Prop.F("Anchor X", { anchorX }, { anchorX = it }, 0.1f),
        Prop.F("Anchor Y", { anchorY }, { anchorY = it }, 0.1f),
        Prop.F("Frequency", { frequency }, { frequency = it.coerceAtLeast(0.1f) }, 0.5f),
        Prop.F("Damping Ratio", { dampingRatio }, { dampingRatio = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Max Force", { maxForce }, { maxForce = it.coerceAtLeast(0f) }, 50f),
    )

    fun calculateForce(bodyX: Float, bodyY: Float, bodyVx: Float, bodyVy: Float): FloatArray {
        val dx = targetX - bodyX
        val dy = targetY - bodyY
        val omega = 2f * PI.toFloat() * frequency
        val d = 2f * dampingRatio * omega
        val spring = omega * omega
        val fx = (spring * dx - d * bodyVx).coerceIn(-maxForce, maxForce)
        val fy = (spring * dy - d * bodyVy).coerceIn(-maxForce, maxForce)
        return floatArrayOf(fx, fy)
    }
}

/** Rope joint — limits maximum distance between two bodies. */
class RopeJoint2D : Component() {
    override val type = "RopeJoint2D"
    var connectedBody: String = ""
    var maxLength: Float = 5f
    var breakForce: Float = Float.MAX_VALUE

    @Transient var broken: Boolean = false

    override fun props() = listOf(
        Prop.S("Connected Body", { connectedBody }, { connectedBody = it }),
        Prop.F("Max Length", { maxLength }, { maxLength = it.coerceAtLeast(0.01f) }, 0.5f),
        Prop.F("Break Force", { breakForce }, { breakForce = it.coerceAtLeast(0f) }, 10f),
    )
}

/** Relative joint — locks two bodies at a fixed relative position/rotation. */
class RelativeJoint2D : Component() {
    override val type = "RelativeJoint2D"
    var connectedBody: String = ""
    var offsetX: Float = 0f
    var offsetY: Float = 0f
    var offsetRotation: Float = 0f
    var linearStiffness: Float = 1000f
    var angularStiffness: Float = 100f
    var maxForce: Float = Float.MAX_VALUE
    var maxTorque: Float = Float.MAX_VALUE

    override fun props() = listOf(
        Prop.S("Connected Body", { connectedBody }, { connectedBody = it }),
        Prop.F("Offset X", { offsetX }, { offsetX = it }, 0.1f),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }, 0.1f),
        Prop.F("Offset Rotation", { offsetRotation }, { offsetRotation = it }, 5f),
        Prop.F("Linear Stiffness", { linearStiffness }, { linearStiffness = it.coerceAtLeast(0f) }, 50f),
        Prop.F("Angular Stiffness", { angularStiffness }, { angularStiffness = it.coerceAtLeast(0f) }, 10f),
    )
}
