package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeLayoutSpecTest {

    @Test
    fun primaryRailRemainsNarrowAndZoomIsNotSqueezedIntoIt() {
        assertEquals(96f, LandscapeLayoutSpec.RightRailWidth.value, 0.01f)
        assertTrue(LandscapeLayoutSpec.ZoomSelectorEstimatedWidthDp.value > LandscapeLayoutSpec.RightRailWidth.value)
    }

    @Test
    fun rightRailFitsS24AndCompactLandscape() {
        assertTrue(LandscapeLayoutSpec.rightRailFits(LandscapeLayoutSpec.S24LandscapeUsableHeightDp))
        assertTrue(LandscapeLayoutSpec.rightRailFits(LandscapeLayoutSpec.CompactLandscapeUsableHeightDp))
    }

    @Test
    fun allLandscapeRegionsHaveNonIntersectingBoundingBoxesAtRequiredHeights() {
        listOf(
            320f to LandscapeLayoutSpec.S24LandscapeUsableHeightDp.value,
            360f to LandscapeLayoutSpec.S24LandscapeUsableHeightDp.value,
            320f to LandscapeLayoutSpec.CompactLandscapeUsableHeightDp.value,
            360f to LandscapeLayoutSpec.CompactLandscapeUsableHeightDp.value
        ).forEach { (width, height) ->
            val bounds = LandscapeLayoutSpec.bounds(width, height)
            bounds.regions.forEach { region ->
                assertTrue("positive region=$region", region.width > 0f && region.height > 0f)
            }
            bounds.regions.forEachIndexed { index, region ->
                bounds.regions.drop(index + 1).forEach { other ->
                    assertFalse("overlap region=$region other=$other", region.intersects(other))
                }
            }
            assertEquals(76f, bounds.topStatusRegion.bottom, 0.01f)
            assertEquals(60f, bounds.modeRegion.height, 0.01f)
            assertEquals(96f, bounds.primaryActionRegion.width, 0.01f)
        }
    }

    @Test
    fun explicitModeSlotsFitTheModeRegion() {
        assertEquals(40f, LandscapeLayoutSpec.ModeSlotWidthDp.value, 0.01f)
        assertEquals(60f, LandscapeLayoutSpec.ModeSlotHeightDp.value, 0.01f)
        assertEquals(2f, LandscapeLayoutSpec.ModeSlotSpacingDp.value, 0.01f)
        assertEquals(208f, LandscapeLayoutSpec.ModeLaneEstimatedWidthDp.value, 0.01f)
        assertEquals(60f, LandscapeLayoutSpec.ModeLaneEstimatedHeightDp.value, 0.01f)
        assertEquals(-90f, CameraUiLayoutMode.LANDSCAPE_LEFT.modeLabelRotationDegrees(), 0.01f)
        assertEquals(90f, CameraUiLayoutMode.LANDSCAPE_RIGHT.modeLabelRotationDegrees(), 0.01f)
    }

    @Test
    fun landscapeChromeUsesSideOrientationWithoutAFullWidthSlab() {
        assertEquals(CameraChromeOrientation.SIDE, CameraUiLayoutMode.LANDSCAPE_LEFT.chromeOrientation())
        assertEquals(CameraChromeOrientation.SIDE, CameraUiLayoutMode.LANDSCAPE_RIGHT.chromeOrientation())
        assertTrue(LandscapeLayoutSpec.ModeLaneEstimatedWidthDp.value < 240f)
    }
}
