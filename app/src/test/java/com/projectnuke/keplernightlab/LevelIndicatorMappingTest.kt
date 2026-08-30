package com.projectnuke.keplernightlab

import android.view.Surface
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelIndicatorMappingTest {
    @Test
    fun normalForwardPoseIsLevelInPortrait() {
        val level = levelFromDisplayGravity(DisplayRelativeGravity(0f, -1f, 0f))!!
        assertEquals(0f, level.pitchDegrees, 0.01f)
        assertEquals(0f, level.rollDegrees, 0.01f)
    }

    @Test
    fun sameNormalPoseRemapsToLevelInBothLandscapes() {
        val landscapeLeft = remapGravityForDisplay(
            gravity = DisplayRelativeGravity(-1f, 0f, 0f),
            displayRotation = Surface.ROTATION_90
        )
        val landscapeRight = remapGravityForDisplay(
            gravity = DisplayRelativeGravity(1f, 0f, 0f),
            displayRotation = Surface.ROTATION_270
        )

        listOf(landscapeLeft, landscapeRight).forEach { gravity ->
            val level = levelFromDisplayGravity(gravity)!!
            assertEquals(0f, level.pitchDegrees, 0.01f)
            assertEquals(0f, level.rollDegrees, 0.01f)
        }
    }

    @Test
    fun clockwiseAndCounterClockwiseScreenRollHaveOppositeSigns() {
        val clockwise = levelFromDisplayGravity(screenGravity(rollDegrees = 18f))!!
        val counterClockwise = levelFromDisplayGravity(screenGravity(rollDegrees = -18f))!!

        assertEquals(18f, clockwise.rollDegrees, 0.05f)
        assertEquals(-18f, counterClockwise.rollDegrees, 0.05f)
        assertEquals(0f, clockwise.pitchDegrees, 0.05f)
        assertEquals(0f, counterClockwise.pitchDegrees, 0.05f)
    }

    @Test
    fun cameraTiltUpAndDownHaveOppositePitchSigns() {
        val up = levelFromDisplayGravity(screenGravity(pitchDegrees = 14f))!!
        val down = levelFromDisplayGravity(screenGravity(pitchDegrees = -14f))!!

        assertEquals(14f, up.pitchDegrees, 0.05f)
        assertEquals(-14f, down.pitchDegrees, 0.05f)
        assertEquals(0f, up.rollDegrees, 0.05f)
        assertEquals(0f, down.rollDegrees, 0.05f)
    }

    @Test
    fun zeroGravityIsUnavailable() {
        assertNull(levelFromDisplayGravity(DisplayRelativeGravity(0f, 0f, 0f)))
    }

    @Test
    fun rotationMatrixUsesDeviceSpaceGravityFromThirdRow() {
        assertEquals(
            DisplayRelativeGravity(7f, 8f, 9f),
            gravityFromRotationMatrix(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f))
        )
    }

    @Test
    fun remapRotatesOnlyScreenAxes() {
        val gravity = DisplayRelativeGravity(1f, 2f, 3f)
        assertEquals(DisplayRelativeGravity(-2f, 1f, 3f), remapGravityForDisplay(gravity, Surface.ROTATION_90))
        assertEquals(DisplayRelativeGravity(2f, -1f, 3f), remapGravityForDisplay(gravity, Surface.ROTATION_270))
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
}
