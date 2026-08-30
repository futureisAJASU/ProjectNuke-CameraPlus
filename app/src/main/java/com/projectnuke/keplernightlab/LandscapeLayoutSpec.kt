package com.projectnuke.keplernightlab

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bounded spec for landscape camera chrome so the height gate is testable
 * without a device.
 *
 * The right-side primary action cluster (result, shutter, switch) must remain
 * visible even on a compact landscape viewport. Secondary clusters (zoom strip,
 * mode labels) are anchored separately and may be shortened/hidden before the
 * primary cluster is allowed to overflow.
 */
object LandscapeLayoutSpec {
    /** Narrow right rail — intentionally not widened to accommodate the 200dp ZoomSelector. */
    val RightRailWidth: Dp = 96.dp

    /** Primary action rail estimated height in dp (result 56 + shutter 84 + switch 56 + spacings + padding). */
    val RightRailEstimatedHeightDp: Dp = 236.dp

    /** S24-class landscape viewport usable height (short side minus insets). */
    val S24LandscapeUsableHeightDp: Dp = 360.dp

    /** Smaller bounded landscape viewport used as a stress gate. */
    val CompactLandscapeUsableHeightDp: Dp = 320.dp

    /** ZoomSelector horizontal Row estimated width (4×44 + spacing + padding). */
    val ZoomSelectorEstimatedWidthDp: Dp = 200.dp

    fun rightRailFits(viewportHeightDp: Dp): Boolean =
        RightRailEstimatedHeightDp <= viewportHeightDp
}