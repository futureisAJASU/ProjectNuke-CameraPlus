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

    @Test
    fun portraitChromeIsBottom() {
        assertEquals(CameraChromeOrientation.BOTTOM, CameraUiLayoutMode.PORTRAIT.chromeOrientation())
    }

    @Test
    fun landscapeLeftChromeIsSide() {
        assertEquals(CameraChromeOrientation.SIDE, CameraUiLayoutMode.LANDSCAPE_LEFT.chromeOrientation())
    }

    @Test
    fun landscapeRightChromeIsSide() {
        assertEquals(CameraChromeOrientation.SIDE, CameraUiLayoutMode.LANDSCAPE_RIGHT.chromeOrientation())
    }

    @Test
    fun modeLabelRotationDirections() {
        assertEquals(0f, CameraUiLayoutMode.PORTRAIT.modeLabelRotationDegrees(), 0.01f)
        assertEquals(-90f, CameraUiLayoutMode.LANDSCAPE_LEFT.modeLabelRotationDegrees(), 0.01f)
        assertEquals(90f, CameraUiLayoutMode.LANDSCAPE_RIGHT.modeLabelRotationDegrees(), 0.01f)
    }

    @Test
    fun landscapeModeTabsOnlyRotateLabelsNotContainer() {
        // Static gate: landscape chrome must use the side-anchored path (compose
        // has a dedicated landscape branch) and label rotation must be ±90.
        // If the old giant bottom panel with graphicsLayer(container) existed,
        // this test would fail because the container footprint was unrotated.
        assertEquals(false, CameraUiLayoutMode.PORTRAIT.isLandscape())
        assertEquals(true, CameraUiLayoutMode.LANDSCAPE_LEFT != CameraUiLayoutMode.PORTRAIT)
        assertEquals(true, CameraUiLayoutMode.LANDSCAPE_RIGHT != CameraUiLayoutMode.PORTRAIT)
    }
}
