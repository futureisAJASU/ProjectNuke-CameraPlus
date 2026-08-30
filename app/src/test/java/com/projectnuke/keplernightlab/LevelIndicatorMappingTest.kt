package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Test

class LevelIndicatorMappingTest {
    @Test
    fun portraitPreservesValues() {
        val state = DeviceLevelState(pitchDegrees = 10f, rollDegrees = -5f, available = true)
        val mapped = mapLevelStateForLayout(state, CameraUiLayoutMode.PORTRAIT)
        assertEquals(10f, mapped.pitchDegrees, 0.01f)
        assertEquals(-5f, mapped.rollDegrees, 0.01f)
    }

    @Test
    fun landscapeLeftMapsCorrectly() {
        val state = DeviceLevelState(pitchDegrees = 10f, rollDegrees = -5f, available = true)
        val mapped = mapLevelStateForLayout(state, CameraUiLayoutMode.LANDSCAPE_LEFT)
        // pitch = -roll, roll = pitch
        assertEquals(5f, mapped.pitchDegrees, 0.01f)
        assertEquals(10f, mapped.rollDegrees, 0.01f)
    }

    @Test
    fun landscapeRightMapsCorrectly() {
        val state = DeviceLevelState(pitchDegrees = 10f, rollDegrees = -5f, available = true)
        val mapped = mapLevelStateForLayout(state, CameraUiLayoutMode.LANDSCAPE_RIGHT)
        // pitch = roll, roll = -pitch
        assertEquals(-5f, mapped.pitchDegrees, 0.01f)
        assertEquals(-10f, mapped.rollDegrees, 0.01f)
    }

    @Test
    fun unavailableStatePreserved() {
        val state = DeviceLevelState(available = false)
        val mapped = mapLevelStateForLayout(state, CameraUiLayoutMode.LANDSCAPE_LEFT)
        assertEquals(false, mapped.available)
    }
}
