package com.projectnuke.keplernightlab

import android.graphics.Matrix
import kotlin.math.max

/**
 * The one sensor-to-display orientation basis shared by preview and metering.
 * Camera2's back-camera convention is sensor orientation minus display
 * rotation.  Keeping this in one helper prevents a second display correction
 * from being introduced in either path.
 */
internal fun relativeRotationDegrees(
    sensorOrientationDegrees: Int,
    displayRotation: Int
): Int {
    val sensor = normalizeRightAngle(sensorOrientationDegrees) ?: 0
    val display = displayRotationDegrees(displayRotation) ?: 0
    return normalizeRightAngle(sensor - display) ?: 0
}

internal data class PreviewTransformGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val relativeRotationDegrees: Int,
    val logicalWidth: Float,
    val logicalHeight: Float,
    val uniformScale: Float,
    val scaledWidth: Float,
    val scaledHeight: Float,
    val offsetX: Float,
    val offsetY: Float
) {
    val sourceAspectRatio: Float
        get() = logicalWidth / logicalHeight

    val viewportAspectRatio: Float
        get() = viewportWidth / viewportHeight

    /** Maps a point in the unrotated camera buffer into the viewport. */
    fun mapBufferPointToViewport(x: Float, y: Float): PreviewPoint {
        val sourceCenterX = bufferWidth / 2f
        val sourceCenterY = bufferHeight / 2f
        val dx = x - sourceCenterX
        val dy = y - sourceCenterY
        val rotated = when (relativeRotationDegrees) {
            90 -> PreviewPoint(-dy, dx)
            180 -> PreviewPoint(-dx, -dy)
            270 -> PreviewPoint(dy, -dx)
            else -> PreviewPoint(dx, dy)
        }
        return PreviewPoint(
            x = viewportWidth / 2f + rotated.x * uniformScale,
            y = viewportHeight / 2f + rotated.y * uniformScale
        )
    }
}

internal data class PreviewPoint(val x: Float, val y: Float)

internal fun calculatePreviewTransformGeometry(
    bufferWidth: Int,
    bufferHeight: Int,
    viewportWidth: Float,
    viewportHeight: Float,
    sensorOrientationDegrees: Int,
    displayRotation: Int
): PreviewTransformGeometry {
    require(bufferWidth > 0 && bufferHeight > 0) {
        "Preview buffer must be positive: ${bufferWidth}x$bufferHeight"
    }
    require(viewportWidth > 0f && viewportHeight > 0f) {
        "Preview viewport must be positive: ${viewportWidth}x$viewportHeight"
    }

    val relativeRotation = relativeRotationDegrees(
        sensorOrientationDegrees = sensorOrientationDegrees,
        displayRotation = displayRotation
    )
    val swapped = relativeRotation == 90 || relativeRotation == 270
    val logicalWidth = if (swapped) bufferHeight.toFloat() else bufferWidth.toFloat()
    val logicalHeight = if (swapped) bufferWidth.toFloat() else bufferHeight.toFloat()
    val uniformScale = max(
        viewportWidth / logicalWidth,
        viewportHeight / logicalHeight
    )
    val scaledWidth = logicalWidth * uniformScale
    val scaledHeight = logicalHeight * uniformScale

    return PreviewTransformGeometry(
        bufferWidth = bufferWidth,
        bufferHeight = bufferHeight,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        relativeRotationDegrees = relativeRotation,
        logicalWidth = logicalWidth,
        logicalHeight = logicalHeight,
        uniformScale = uniformScale,
        scaledWidth = scaledWidth,
        scaledHeight = scaledHeight,
        offsetX = (viewportWidth - scaledWidth) / 2f,
        offsetY = (viewportHeight - scaledHeight) / 2f
    )
}

/**
 * Builds a source-buffer -> view matrix.  Rotation is around the buffer
 * centre, followed by one uniform scale and a centre translation.  There is
 * deliberately no fill-fit remapping or independent display correction:
 * either would reintroduce a hidden non-uniform or second rotation.
 */
internal fun buildPreviewTransformMatrix(
    geometry: PreviewTransformGeometry
): Matrix = Matrix().apply {
    setValues(buildPreviewTransformValues(geometry))
}

/** Pure affine coefficients used by [buildPreviewTransformMatrix] and JVM tests. */
internal fun buildPreviewTransformValues(
    geometry: PreviewTransformGeometry
): FloatArray {
    val sourceCenterX = geometry.bufferWidth / 2f
    val sourceCenterY = geometry.bufferHeight / 2f
    val viewCenterX = geometry.viewportWidth / 2f
    val viewCenterY = geometry.viewportHeight / 2f
    val scale = geometry.uniformScale

    val values = when (geometry.relativeRotationDegrees) {
        90 -> floatArrayOf(
            0f, -scale, viewCenterX + scale * sourceCenterY,
            scale, 0f, viewCenterY - scale * sourceCenterX,
            0f, 0f, 1f
        )
        180 -> floatArrayOf(
            -scale, 0f, viewCenterX + scale * sourceCenterX,
            0f, -scale, viewCenterY + scale * sourceCenterY,
            0f, 0f, 1f
        )
        270 -> floatArrayOf(
            0f, scale, viewCenterX - scale * sourceCenterY,
            -scale, 0f, viewCenterY + scale * sourceCenterX,
            0f, 0f, 1f
        )
        else -> floatArrayOf(
            scale, 0f, viewCenterX - scale * sourceCenterX,
            0f, scale, viewCenterY - scale * sourceCenterY,
            0f, 0f, 1f
        )
    }
    return values
}
