package com.projectnuke.keplernightlab

import android.view.Surface
import kotlin.math.atan2
import kotlin.math.hypot

/** A gravity vector expressed in the currently visible display axes. */
internal data class DisplayRelativeGravity(
    val x: Float,
    val y: Float,
    val z: Float
)

/**
 * Remaps natural device axes to the visible display axes. The display Z axis
 * remains the screen normal; X/Y rotate with the display so the same physical
 * camera pose has the same level semantics in portrait and both landscapes.
 */
internal fun remapGravityForDisplay(
    gravity: DisplayRelativeGravity,
    displayRotation: Int
): DisplayRelativeGravity {
    return when (displayRotation) {
        Surface.ROTATION_90 -> DisplayRelativeGravity(
            x = -gravity.y,
            y = gravity.x,
            z = gravity.z
        )
        Surface.ROTATION_180 -> DisplayRelativeGravity(
            x = -gravity.x,
            y = -gravity.y,
            z = gravity.z
        )
        Surface.ROTATION_270 -> DisplayRelativeGravity(
            x = gravity.y,
            y = -gravity.x,
            z = gravity.z
        )
        else -> gravity
    }
}

internal data class DisplayRelativeLevel(
    val pitchDegrees: Float,
    val rollDegrees: Float
)

/**
 * Computes camera-level angles directly from display-relative gravity.
 *
 * A normal forward-looking camera has gravity along screen -Y, not a camera
 * Euler singularity, so both angles are near zero. Roll is the visible
 * horizon angle in the screen plane. Pitch is the camera up/down tilt from
 * that forward-looking pose and uses the screen-normal component rather than
 * raw Android pitch.
 */
internal fun levelFromDisplayGravity(
    gravity: DisplayRelativeGravity
): DisplayRelativeLevel? {
    val magnitude = hypot(hypot(gravity.x, gravity.y), gravity.z)
    if (magnitude <= 0.0001f) return null

    val x = gravity.x / magnitude
    val y = gravity.y / magnitude
    val z = gravity.z / magnitude
    return DisplayRelativeLevel(
        pitchDegrees = Math.toDegrees(atan2(z.toDouble(), hypot(x, y).toDouble())).toFloat(),
        rollDegrees = Math.toDegrees(atan2(x, -y).toDouble()).toFloat()
    )
}

/**
 * Converts a rotation-vector matrix into physical DOWN in natural device
 * axes. Android's rotation matrix exposes the inverse-mapped world-up
 * direction in its third row; negate it once here so the rest of the level
 * pipeline consistently consumes physical down (normal forward pose: -Y).
 */
internal fun gravityFromRotationMatrix(rotationMatrix: FloatArray): DisplayRelativeGravity? {
    if (rotationMatrix.size < 9) return null
    return DisplayRelativeGravity(
        x = -rotationMatrix[6],
        y = -rotationMatrix[7],
        z = -rotationMatrix[8]
    )
}
