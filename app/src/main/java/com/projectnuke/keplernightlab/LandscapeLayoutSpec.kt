package com.projectnuke.keplernightlab

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

internal data class LandscapeLayoutRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun intersects(other: LandscapeLayoutRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

internal data class LandscapeLayoutBounds(
    val topStatusRegion: LandscapeLayoutRect,
    val modeRegion: LandscapeLayoutRect,
    val zoomRegion: LandscapeLayoutRect,
    val primaryActionRegion: LandscapeLayoutRect
) {
    val regions: List<LandscapeLayoutRect>
        get() = listOf(topStatusRegion, modeRegion, zoomRegion, primaryActionRegion)
}

/**
 * Bounded screen-space placement for landscape camera chrome. The mode lane
 * is a horizontal row of explicit slots below the top/status region; the zoom
 * cluster occupies the lower left/centre; the primary rail owns the right
 * edge. Their rectangles are disjoint even in compact landscape.
 */
object LandscapeLayoutSpec {
    val RightRailWidth: Dp = 96.dp
    val RightRailEstimatedHeightDp: Dp = 236.dp
    val S24LandscapeUsableHeightDp: Dp = 360.dp
    val CompactLandscapeUsableHeightDp: Dp = 320.dp
    val ZoomSelectorEstimatedWidthDp: Dp = 200.dp

    /** Explicit measured hit-target dimensions for each label. */
    val ModeSlotWidthDp: Dp = 40.dp
    val ModeSlotHeightDp: Dp = 60.dp
    val ModeSlotSpacingDp: Dp = 2.dp
    val TopStatusRegionHeightDp: Dp = 76.dp
    val ClusterGapDp: Dp = 4.dp
    val ZoomRegionWidthDp: Dp = 216.dp
    val ZoomRegionHeightDp: Dp = 120.dp

    val ModeLaneEstimatedWidthDp: Dp =
        (ModeSlotWidthDp.value * 5f + ModeSlotSpacingDp.value * 4f).dp

    /** Retained name for callers that use the lane's cross-axis extent. */
    val ModeLaneEstimatedHeightDp: Dp = ModeSlotHeightDp

    fun rightRailFits(viewportHeightDp: Dp): Boolean =
        RightRailEstimatedHeightDp <= viewportHeightDp

    internal fun bounds(viewportWidthDp: Float, viewportHeightDp: Float): LandscapeLayoutBounds {
        require(viewportWidthDp > 0f && viewportHeightDp > 0f)
        val topHeight = TopStatusRegionHeightDp.value
        val gap = ClusterGapDp.value
        val rightRailWidth = RightRailWidth.value
        val modeWidth = ModeLaneEstimatedWidthDp.value
        val modeHeight = ModeLaneEstimatedHeightDp.value
        val zoomWidth = ZoomRegionWidthDp.value
        val zoomHeight = ZoomRegionHeightDp.value
        val primaryHeight = RightRailEstimatedHeightDp.value
        val primaryLeft = viewportWidthDp - rightRailWidth
        val primaryTop = max(
            topHeight + gap,
            (viewportHeightDp - primaryHeight) / 2f
        )

        return LandscapeLayoutBounds(
            topStatusRegion = LandscapeLayoutRect(0f, 0f, viewportWidthDp, topHeight),
            modeRegion = LandscapeLayoutRect(
                left = gap,
                top = topHeight + gap,
                right = gap + modeWidth,
                bottom = topHeight + gap + modeHeight
            ),
            zoomRegion = LandscapeLayoutRect(
                left = ((viewportWidthDp - rightRailWidth - zoomWidth) / 2f).coerceAtLeast(gap),
                top = viewportHeightDp - zoomHeight,
                right = ((viewportWidthDp - rightRailWidth - zoomWidth) / 2f)
                    .coerceAtLeast(gap) + zoomWidth,
                bottom = viewportHeightDp
            ),
            primaryActionRegion = LandscapeLayoutRect(
                left = primaryLeft,
                top = primaryTop,
                right = viewportWidthDp,
                bottom = primaryTop + primaryHeight
            )
        )
    }
}
