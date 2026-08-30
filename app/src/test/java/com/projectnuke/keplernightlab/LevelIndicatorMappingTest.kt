package com.projectnuke.keplernightlab

import android.view.Surface
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelIndicatorMappingTest {
    @Test
    fun normalForwardPoseIsLevelInPortraitThroughTheFullPath() {
        assertLevel(
            physicalDown = DisplayRelativeGravity(0f, -1f, 0f),
            displayRotation = Surface.ROTATION_0
        )
    }

    @Test
    fun normalForwardPoseIsLevelInBothLandscapesThroughTheFullPath() {
        assertLevel(
            physicalDown = DisplayRelativeGravity(-1f, 0f, 0f),
            displayRotation = Surface.ROTATION_90
        )
        assertLevel(
            physicalDown = DisplayRelativeGravity(1f, 0f, 0f),
            displayRotation = Surface.ROTATION_270
        )
    }

    @Test
    fun clockwiseAndCounterClockwiseVisibleHorizonRollHaveOppositeSigns() {
        val clockwise = levelFromRotationMatrix(
            screenGravity(rollDegrees = 18f),
            Surface.ROTATION_0
        )!!
        val counterClockwise = levelFromRotationMatrix(
            screenGravity(rollDegrees = -18f),
            Surface.ROTATION_0
        )!!

        assertEquals(18f, clockwise.rollDegrees, 0.05f)
        assertEquals(-18f, counterClockwise.rollDegrees, 0.05f)
        assertEquals(0f, clockwise.pitchDegrees, 0.05f)
        assertEquals(0f, counterClockwise.pitchDegrees, 0.05f)
    }

    @Test
    fun cameraTiltUpAndDownHaveOppositePitchSigns() {
        val up = levelFromRotationMatrix(
            screenGravity(pitchDegrees = 14f),
            Surface.ROTATION_0
        )!!
        val down = levelFromRotationMatrix(
            screenGravity(pitchDegrees = -14f),
            Surface.ROTATION_0
        )!!

        assertEquals(14f, up.pitchDegrees, 0.05f)
        assertEquals(-14f, down.pitchDegrees, 0.05f)
        assertEquals(0f, up.rollDegrees, 0.05f)
        assertEquals(0f, down.rollDegrees, 0.05f)
    }

    @Test
    fun extractionConvertsWorldUpRowToPhysicalDownExactlyOnce() {
        val matrix = matrixForPhysicalDown(DisplayRelativeGravity(0f, -1f, 0f))
        assertEquals(DisplayRelativeGravity(0f, -1f, 0f), gravityFromRotationMatrix(matrix))
        assertOrthonormal(matrix)
    }

    @Test
    fun zeroGravityIsUnavailable() {
        assertNull(levelFromDisplayGravity(DisplayRelativeGravity(0f, 0f, 0f)))
    }

    @Test
    fun remapRotatesOnlyScreenAxes() {
        val gravity = DisplayRelativeGravity(1f, 2f, 3f)
        assertEquals(DisplayRelativeGravity(-2f, 1f, 3f), remapGravityForDisplay(gravity, Surface.ROTATION_90))
        assertEquals(DisplayRelativeGravity(2f, -1f, 3f), remapGravityForDisplay(gravity, Surface.ROTATION_270))
    }

    private fun assertLevel(
        physicalDown: DisplayRelativeGravity,
        displayRotation: Int
    ) {
        val matrix = matrixForPhysicalDown(physicalDown)
        val extracted = gravityFromRotationMatrix(matrix)!!
        val displayGravity = remapGravityForDisplay(extracted, displayRotation)
        val level = levelFromDisplayGravity(displayGravity)!!
        assertEquals(0f, level.pitchDegrees, 0.01f)
        assertEquals(0f, level.rollDegrees, 0.01f)
        assertOrthonormal(matrix)
    }

    private fun levelFromRotationMatrix(
        physicalDownInDisplayAxes: DisplayRelativeGravity,
        displayRotation: Int
    ): DisplayRelativeLevel? {
        // Convert the desired visible/display vector back to the natural sensor
        // axes before exercising extraction and the authoritative-axis remap.
        val natural = inverseRemap(physicalDownInDisplayAxes, displayRotation)
        val matrix = matrixForPhysicalDown(natural)
        return gravityFromRotationMatrix(matrix)
            ?.let { remapGravityForDisplay(it, displayRotation) }
            ?.let(::levelFromDisplayGravity)
            .also { assertOrthonormal(matrix) }
    }

    private fun inverseRemap(
        gravity: DisplayRelativeGravity,
        displayRotation: Int
    ): DisplayRelativeGravity = when (displayRotation) {
        Surface.ROTATION_90 -> DisplayRelativeGravity(gravity.y, -gravity.x, gravity.z)
        Surface.ROTATION_180 -> DisplayRelativeGravity(-gravity.x, -gravity.y, gravity.z)
        Surface.ROTATION_270 -> DisplayRelativeGravity(-gravity.y, gravity.x, gravity.z)
        else -> gravity
    }

    private fun screenGravity(
        rollDegrees: Float = 0f,
        pitchDegrees: Float = 0f
    ): DisplayRelativeGravity {
        val roll = Math.toRadians(rollDegrees.toDouble()).toFloat()
        val pitch = Math.toRadians(pitchDegrees.toDouble()).toFloat()
        return DisplayRelativeGravity(
            x = sin(roll),
            y = -cos(roll) * cos(pitch),
            z = sin(pitch)
        )
    }

    /** Builds a valid right-handed rotation matrix from its physical-down row. */
    private fun matrixForPhysicalDown(gravity: DisplayRelativeGravity): FloatArray {
        val worldUp = DisplayRelativeGravity(-gravity.x, -gravity.y, -gravity.z)
        val reference = if (hypot(worldUp.x, worldUp.y) < 0.9f) {
            DisplayRelativeGravity(0f, 1f, 0f)
        } else {
            DisplayRelativeGravity(0f, 0f, 1f)
        }
        val row1 = normalize(cross(reference, worldUp))
        val row2 = cross(worldUp, row1)
        return floatArrayOf(
            row1.x, row1.y, row1.z,
            row2.x, row2.y, row2.z,
            worldUp.x, worldUp.y, worldUp.z
        )
    }

    private fun cross(a: DisplayRelativeGravity, b: DisplayRelativeGravity) =
        DisplayRelativeGravity(
            x = a.y * b.z - a.z * b.y,
            y = a.z * b.x - a.x * b.z,
            z = a.x * b.y - a.y * b.x
        )

    private fun normalize(value: DisplayRelativeGravity): DisplayRelativeGravity {
        val magnitude = hypot(hypot(value.x, value.y), value.z)
        return DisplayRelativeGravity(value.x / magnitude, value.y / magnitude, value.z / magnitude)
    }

    private fun assertOrthonormal(matrix: FloatArray) {
        for (row in 0..2) {
            val start = row * 3
            val norm = hypot(hypot(matrix[start], matrix[start + 1]), matrix[start + 2])
            assertEquals("row $row norm", 1f, norm, 0.001f)
        }
        assertEquals(0f, dot(matrix, 0, 3), 0.001f)
        assertEquals(0f, dot(matrix, 0, 6), 0.001f)
        assertEquals(0f, dot(matrix, 3, 6), 0.001f)
        assertTrue(matrix.all { it.isFinite() })
    }

    private fun dot(matrix: FloatArray, first: Int, second: Int): Float =
        matrix[first] * matrix[second] +
            matrix[first + 1] * matrix[second + 1] +
            matrix[first + 2] * matrix[second + 2]
}
