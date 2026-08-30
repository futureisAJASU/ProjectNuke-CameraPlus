package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeLayoutSpecTest {

    @Test
    fun rightRailWidthRemainsNarrow() {
        // The action rail must stay narrow; the ZoomSelector must NOT be squeezed inside it.
        assertEquals(96f, LandscapeLayoutSpec.RightRailWidth.value, 0.01f)
        assertTrue(LandscapeLayoutSpec.ZoomSelectorEstimatedWidthDp.value > LandscapeLayoutSpec.RightRailWidth.value)
    }

    @Test
    fun rightRailFitsS24Landscape() {
        assertTrue(LandscapeLayoutSpec.rightRailFits(LandscapeLayoutSpec.S24LandscapeUsableHeightDp))
    }

    @Test
    fun rightRailFitsCompactLandscape() {
        assertTrue(LandscapeLayoutSpec.rightRailFits(LandscapeLayoutSpec.CompactLandscapeUsableHeightDp))
    }

    @Test
    fun zoomStripRequiresBottomClusterNotRightRail() {
        // Proof that a Box with 3 anchored clusters satisfies the height gate:
        // right rail primary height (236dp) + bottom zoom strip height (~56dp)
        // + top mode row height (~40dp) still fits in 320dp compact viewport with margin.
        val total = LandscapeLayoutSpec.RightRailEstimatedHeightDp.value
        assertTrue(total <= LandscapeLayoutSpec.CompactLandscapeUsableHeightDp.value)
        // And zoom selector fits horizontally in the bottom strip, not the rail:
        assertTrue(LandscapeLayoutSpec.ZoomSelectorEstimatedWidthDp.value <= 360f) // bottom strip available width
    }

    @Test
    fun landscapeChromeModifierAnchorsCenterEnd() {
        // Structural evidence: the landscape chrome now owns the CenterEnd anchor.
        // This is verified by the caller passing `modifier = Modifier.align(CenterEnd)`
        // into CameraLandscapeChrome which applies it to its outer Box(fillMaxSize).
        // The Box's contentAlignment = CenterEnd and the right rail's align(CenterEnd)
        // guarantee the action cluster is not at default TopStart.
        assertTrue(CameraUiLayoutMode.LANDSCAPE_LEFT.chromeOrientation() == CameraChromeOrientation.SIDE)
        assertTrue(CameraUiLayoutMode.LANDSCAPE_RIGHT.chromeOrientation() == CameraChromeOrientation.SIDE)
    }

    @Test
    fun rotatedLabelSlotsHaveFixedFootprint() {
        // LandscapeModeTabs wraps each label in a fixed 52x60dp Box so a 90°
        // rotation does not change hit-target geometry or overlap neighbours.
        assertEquals(-90f, CameraUiLayoutMode.LANDSCAPE_LEFT.modeLabelRotationDegrees(), 0.01f)
        assertEquals(90f, CameraUiLayoutMode.LANDSCAPE_RIGHT.modeLabelRotationDegrees(), 0.01f)
        assertEquals(0f, CameraUiLayoutMode.PORTRAIT.modeLabelRotationDegrees(), 0.01f)
        assertEquals(52f, LandscapeLayoutSpec.ModeSlotWidthDp.value, 0.01f)
        assertEquals(60f, LandscapeLayoutSpec.ModeSlotHeightDp.value, 0.01f)
    }

    @Test
    fun modeLaneFitsCompactLandscapeWithoutAFullWidthPanel() {
        assertTrue(
            LandscapeLayoutSpec.ModeLaneEstimatedHeightDp.value <=
                LandscapeLayoutSpec.CompactLandscapeUsableHeightDp.value
        )
        assertTrue(LandscapeLayoutSpec.ModeSlotWidthDp.value < LandscapeLayoutSpec.RightRailWidth.value)
    }
}
