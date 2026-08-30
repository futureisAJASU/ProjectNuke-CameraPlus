package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeLayoutSpecTest {

    @Test
    fun samsungStyleChromeUsesBlackSideGuttersAndBoundedPreview() {
        assertEquals(58f, LandscapeLayoutSpec.UtilityRailWidth.value, 0.01f)
        assertEquals(260f, LandscapeLayoutSpec.RightChromeWidth.value, 0.01f)
        val bounds = LandscapeLayoutSpec.bounds(780f, 360f)
        assertEquals(58f, bounds.previewRegion.left, 0.01f)
        assertEquals(520f, bounds.previewRegion.right, 0.01f)
        assertEquals(520f, bounds.rightChromeRegion.left, 0.01f)
        assertFalse(bounds.previewRegion.intersects(bounds.utilityRailRegion))
        assertFalse(bounds.previewRegion.intersects(bounds.rightChromeRegion))
    }

    @Test
    fun zoomActionsAndModesNeverOverlapPreviewOrEachOther() {
        listOf(780f to 360f, 640f to 320f).forEach { (width, height) ->
            val bounds = LandscapeLayoutSpec.bounds(width, height)
            listOf(bounds.zoomRegion, bounds.primaryActionRegion, bounds.modeRegion).forEach { region ->
                assertTrue("positive region=$region", region.width > 0f && region.height > 0f)
                assertFalse("chrome entered preview region=$region", region.intersects(bounds.previewRegion))
            }
            assertFalse(bounds.zoomRegion.intersects(bounds.primaryActionRegion))
            assertFalse(bounds.zoomRegion.intersects(bounds.modeRegion))
            assertFalse(bounds.primaryActionRegion.intersects(bounds.modeRegion))
            assertTrue(
                "mode rail must leave navigation safety space",
                bounds.modeRegion.right <=
                    bounds.rightChromeRegion.right - LandscapeLayoutSpec.NavigationSafetyWidth.value
            )
            assertFalse(bounds.modeRegion.intersects(bounds.systemNavigationSafetyRegion))
        }
    }

    @Test
    fun actualControlRectanglesStayInTheirIntendedLandscapeLanes() {
        listOf(
            "S24 landscape" to (780f to 360f),
            "compact landscape" to (640f to 320f)
        ).forEach { (label, viewport) ->
            val (width, height) = viewport
            val bounds = LandscapeLayoutSpec.bounds(width, height)
            val controls = listOf(
                "zoom" to bounds.zoomControlRegion,
                "switch" to bounds.cameraSwitchControlRegion,
                "shutter" to bounds.shutterControlRegion,
                "thumbnail" to bounds.resultThumbnailRegion
            )
            controls.forEach { (name, control) ->
                assertTrue("$label $name must be positive: $control", control.width > 0f && control.height > 0f)
                assertFalse("$label $name over preview: $control", control.intersects(bounds.previewRegion))
            }
            assertFalse("$label zoom overlaps switch", bounds.zoomControlRegion.intersects(bounds.cameraSwitchControlRegion))
            assertFalse("$label zoom overlaps shutter", bounds.zoomControlRegion.intersects(bounds.shutterControlRegion))
            assertFalse("$label zoom overlaps thumbnail", bounds.zoomControlRegion.intersects(bounds.resultThumbnailRegion))
            assertFalse("$label thumbnail overlaps shutter", bounds.resultThumbnailRegion.intersects(bounds.shutterControlRegion))
            assertTrue(
                "$label preview must remain useful",
                bounds.previewRegion.width >= 240f
            )
            assertTrue(
                "$label chrome must remain bounded, not a landscape slab",
                bounds.rightChromeRegion.width <= LandscapeLayoutSpec.RightChromeWidth.value
            )
        }
    }

    @Test
    fun rotatedModeLabelVisualSlotsStayAdjacentAndOutsidePreview() {
        listOf(780f to 360f, 640f to 320f).forEach { (width, height) ->
            val bounds = LandscapeLayoutSpec.bounds(width, height)
            assertEquals(5, bounds.modeLabelSlots.size)
            assertEquals(5, bounds.rotatedModeLabelVisualSlots.size)
            bounds.modeLabelSlots.zipWithNext().forEach { (first, second) ->
                assertFalse(first.intersects(second))
            }
            bounds.rotatedModeLabelVisualSlots.zipWithNext().forEach { (first, second) ->
                assertFalse("rotated slots overlap: $first / $second", first.intersects(second))
            }
            bounds.rotatedModeLabelVisualSlots.forEach { visualSlot ->
                assertFalse(visualSlot.intersects(bounds.previewRegion))
                assertFalse(visualSlot.intersects(bounds.systemNavigationSafetyRegion))
            }
        }
    }

    @Test
    fun fiveRotatedModeSlotsFitCompactLandscapeHeight() {
        assertEquals(54f, LandscapeLayoutSpec.ModeSlotWidthDp.value, 0.01f)
        assertEquals(58f, LandscapeLayoutSpec.ModeSlotHeightDp.value, 0.01f)
        assertEquals(290f, LandscapeLayoutSpec.ModeLaneEstimatedHeightDp.value, 0.01f)
        assertTrue(
            LandscapeLayoutSpec.ModeLaneEstimatedHeightDp <=
                LandscapeLayoutSpec.CompactLandscapeUsableHeightDp
        )
        assertEquals(-90f, CameraUiLayoutMode.LANDSCAPE_LEFT.modeLabelRotationDegrees(), 0.01f)
        assertEquals(90f, CameraUiLayoutMode.LANDSCAPE_RIGHT.modeLabelRotationDegrees(), 0.01f)
    }

    @Test
    fun landscapeChromeRemainsSideOriented() {
        assertEquals(CameraChromeOrientation.SIDE, CameraUiLayoutMode.LANDSCAPE_LEFT.chromeOrientation())
        assertEquals(CameraChromeOrientation.SIDE, CameraUiLayoutMode.LANDSCAPE_RIGHT.chromeOrientation())
        assertTrue(LandscapeLayoutSpec.RightChromeWidth.value <= 260f)
        assertEquals(0f, LandscapeLayoutSpec.CircularControlContainerRotationDegrees, 0.01f)
        assertTrue(LandscapeLayoutSpec.RotatesOnlyModeLabelContent)
    }
}
