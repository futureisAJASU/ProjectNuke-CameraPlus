package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraUiLayoutModeTest {
    @Test
    fun derivePortrait() {
        assertEquals(
            CameraUiLayoutMode.PORTRAIT,
            deriveCameraUiLayoutMode(android.view.Surface.ROTATION_0)
        )
    }

    @Test
    fun deriveLandscapeLeft() {
        assertEquals(
            CameraUiLayoutMode.LANDSCAPE_LEFT,
            deriveCameraUiLayoutMode(android.view.Surface.ROTATION_90)
        )
    }

    @Test
    fun deriveLandscapeRight() {
        assertEquals(
            CameraUiLayoutMode.LANDSCAPE_RIGHT,
            deriveCameraUiLayoutMode(android.view.Surface.ROTATION_270)
        )
    }

    @Test
    fun isLandscapeOnlyForLandscapeModes() {
        assertEquals(false, CameraUiLayoutMode.PORTRAIT.isLandscape())
        assertEquals(true, CameraUiLayoutMode.LANDSCAPE_LEFT.isLandscape())
        assertEquals(true, CameraUiLayoutMode.LANDSCAPE_RIGHT.isLandscape())
    }
}
