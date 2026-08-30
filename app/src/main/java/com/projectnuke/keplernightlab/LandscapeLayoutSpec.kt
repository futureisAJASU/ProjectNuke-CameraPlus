package com.projectnuke.keplernightlab

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    val previewRegion: LandscapeLayoutRect,
    val utilityRailRegion: LandscapeLayoutRect,
    val rightChromeRegion: LandscapeLayoutRect,
    val zoomRegion: LandscapeLayoutRect,
    val primaryActionRegion: LandscapeLayoutRect,
    val modeRegion: LandscapeLayoutRect,
    val systemNavigationSafetyRegion: LandscapeLayoutRect,
    val zoomControlRegion: LandscapeLayoutRect,
    val cameraSwitchControlRegion: LandscapeLayoutRect,
    val shutterControlRegion: LandscapeLayoutRect,
    val resultThumbnailRegion: LandscapeLayoutRect,
    val modeLabelSlots: List<LandscapeLayoutRect>,
    val rotatedModeLabelVisualSlots: List<LandscapeLayoutRect>
) {
    val chromeRegions: List<LandscapeLayoutRect>
        get() = listOf(utilityRailRegion, zoomRegion, primaryActionRegion, modeRegion)
}

/**
 * Samsung-inspired landscape camera grammar, implemented with Kepler-owned
 * surfaces and assets:
 *
 *   [utility rail] [camera preview] [zoom | actions | mode labels]
 *
 * Controls never float randomly on top of the camera image. The preview owns
 * a bounded central rectangle and the camera chrome owns black side gutters.
 * Only the mode-label Text rotates; the rail geometry remains screen-stable.
 */
object LandscapeLayoutSpec {
    val UtilityRailWidth: Dp = 58.dp
    val RightChromeWidth: Dp = 260.dp
    val ChromeInnerPadding: Dp = 6.dp
    val NavigationSafetyWidth: Dp = 32.dp

    val ZoomRailWidth: Dp = 54.dp
    val PrimaryActionWidth: Dp = 92.dp
    val ModeRailWidth: Dp = 54.dp

    val ModeSlotWidthDp: Dp = ModeRailWidth
    val ModeSlotHeightDp: Dp = 58.dp
    val ModeSlotSpacingDp: Dp = 0.dp
    val ModeLaneEstimatedWidthDp: Dp = ModeRailWidth
    val ModeLaneEstimatedHeightDp: Dp = (ModeSlotHeightDp.value * 5f).dp
    val ModeLabelContractWidthDp: Dp = 48.dp
    val ModeLabelContractHeightDp: Dp = 24.dp

    // These values mirror the existing composable geometry. They are a contract surface only;
    // changing them would require an intentional visual-layout change outside this batch.
    val ZoomControlWidthDp: Dp = 48.dp
    val ZoomControlHeightDp: Dp = 212.dp // 4 zoom rows + optional two-source row and spacing
    val CircularControlContainerRotationDegrees: Float = 0f
    val RotatesOnlyModeLabelContent: Boolean = true

    val S24LandscapeUsableHeightDp: Dp = 360.dp
    val CompactLandscapeUsableHeightDp: Dp = 320.dp

    internal fun bounds(viewportWidthDp: Float, viewportHeightDp: Float): LandscapeLayoutBounds {
        require(viewportWidthDp > 0f && viewportHeightDp > 0f)

        val leftRail = UtilityRailWidth.value
        val rightRail = RightChromeWidth.value
        val rightStart = (viewportWidthDp - rightRail).coerceAtLeast(leftRail)
        val padding = ChromeInnerPadding.value

        val zoomLeft = rightStart + padding
        val zoomRight = zoomLeft + ZoomRailWidth.value
        val actionLeft = zoomRight + padding
        val actionRight = actionLeft + PrimaryActionWidth.value
        val modeLeft = actionRight + padding
        val modeRight = (rightStart + rightRail - NavigationSafetyWidth.value - padding).coerceAtLeast(modeLeft)
        val modeRegion = LandscapeLayoutRect(
            left = modeLeft,
            top = 0f,
            right = modeRight,
            bottom = viewportHeightDp
        )
        val systemNavigationSafetyRegion = LandscapeLayoutRect(
            left = viewportWidthDp - NavigationSafetyWidth.value,
            top = 0f,
            right = viewportWidthDp,
            bottom = viewportHeightDp
        )
        val zoomRegion = LandscapeLayoutRect(
            left = zoomLeft,
            top = 0f,
            right = zoomRight,
            bottom = viewportHeightDp
        )
        val primaryActionRegion = LandscapeLayoutRect(
            left = actionLeft,
            top = 0f,
            right = actionRight,
            bottom = viewportHeightDp
        )
        val zoomControlRegion = centeredRect(
            parent = zoomRegion,
            width = ZoomControlWidthDp.value,
            height = ZoomControlHeightDp.value
        )
        val cameraSwitchControlRegion = centeredRect(
            parent = primaryActionRegion,
            width = 56f,
            height = 56f,
            top = 42f
        )
        val shutterControlRegion = centeredRect(
            parent = primaryActionRegion,
            width = 84f,
            height = 84f
        )
        val resultThumbnailRegion = centeredRect(
            parent = primaryActionRegion,
            width = 56f,
            height = 56f,
            top = viewportHeightDp - 34f - 56f
        )
        val modeLabelSlots = modeLabelSlots(modeRegion, viewportHeightDp)

        return LandscapeLayoutBounds(
            previewRegion = LandscapeLayoutRect(
                left = leftRail,
                top = 0f,
                right = rightStart,
                bottom = viewportHeightDp
            ),
            utilityRailRegion = LandscapeLayoutRect(
                left = 0f,
                top = 0f,
                right = leftRail,
                bottom = viewportHeightDp
            ),
            rightChromeRegion = LandscapeLayoutRect(
                left = rightStart,
                top = 0f,
                right = viewportWidthDp,
                bottom = viewportHeightDp
            ),
            zoomRegion = zoomRegion,
            primaryActionRegion = primaryActionRegion,
            modeRegion = modeRegion,
            systemNavigationSafetyRegion = systemNavigationSafetyRegion,
            zoomControlRegion = zoomControlRegion,
            cameraSwitchControlRegion = cameraSwitchControlRegion,
            shutterControlRegion = shutterControlRegion,
            resultThumbnailRegion = resultThumbnailRegion,
            modeLabelSlots = modeLabelSlots,
            rotatedModeLabelVisualSlots = modeLabelSlots.map { rotateModeLabelContentBounds(it) }
        )
    }

    private fun centeredRect(
        parent: LandscapeLayoutRect,
        width: Float,
        height: Float,
        top: Float = parent.top + (parent.height - height) / 2f
    ): LandscapeLayoutRect {
        val left = parent.left + (parent.width - width) / 2f
        return LandscapeLayoutRect(left, top, left + width, top + height)
    }

    private fun modeLabelSlots(
        modeRegion: LandscapeLayoutRect,
        viewportHeightDp: Float,
        statusBarInsetDp: Float = 0f
    ): List<LandscapeLayoutRect> {
        val availableHeight = (viewportHeightDp - statusBarInsetDp).coerceAtLeast(0f)
        val laneHeight = ModeLaneEstimatedHeightDp.value
        val top = statusBarInsetDp + ((availableHeight - laneHeight) / 2f).coerceAtLeast(0f)
        val left = modeRegion.left + (modeRegion.width - ModeSlotWidthDp.value) / 2f
        return List(5) { index ->
            val slotTop = top + index * (ModeSlotHeightDp.value + ModeSlotSpacingDp.value)
            LandscapeLayoutRect(
                left = left,
                top = slotTop,
                right = left + ModeSlotWidthDp.value,
                bottom = slotTop + ModeSlotHeightDp.value
            )
        }
    }

    /** The visual envelope after the existing +/-90-degree text-only transform. */
    private fun rotateModeLabelContentBounds(slot: LandscapeLayoutRect): LandscapeLayoutRect {
        val width = ModeLabelContractHeightDp.value
        val height = ModeLabelContractWidthDp.value
        return centeredRect(slot, width, height)
    }

    fun previewStartPadding(): Dp = UtilityRailWidth
    fun previewEndPadding(): Dp = RightChromeWidth
}
