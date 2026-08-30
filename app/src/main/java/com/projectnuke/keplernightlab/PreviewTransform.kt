package com.projectnuke.keplernightlab

import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import kotlin.math.max

/**
 * Camera2's relative orientation convention.
 *
 * Display rotation is reported counter-clockwise by [Display.getRotation].
 * Camera2's lens-facing-aware formula therefore uses a reversed display term
 * for a back camera and a forward display term for a front camera. This value
 * describes the orientation relationship used to determine whether the
 * TextureView content axes are swapped; it is not a second rotation to apply
 * to the TextureView.
 */
internal fun relativeRotationDegrees(
    sensorOrientationDegrees: Int,
    displayRotation: Int,
    lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
): Int {
    val sensor = normalizeRightAngle(sensorOrientationDegrees) ?: 0
    val display = displayRotationDegrees(displayRotation) ?: 0
    val sign = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) 1 else -1
    return normalizeRightAngle(sensor - display * sign) ?: 0
}

data class PreviewViewfinderInput(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val viewportWidth: Float,
    val viewportHeight: Float
) {
    init {
        require(bufferWidth > 0 && bufferHeight > 0) {
            "Preview buffer must be positive: ${bufferWidth}x$bufferHeight"
        }
        require(viewportWidth > 0f && viewportHeight > 0f) {
            "Preview viewport must be positive: ${viewportWidth}x$viewportHeight"
        }
    }
}

data class PreviewTransformGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val viewportWidth: Float,
    val viewportHeight: Float,
    /** Camera2 relative orientation used for TextureView axis-swap semantics. */
    val relativeRotationDegrees: Int,
    /** Orientation already applied by TextureView for the camera sensor. */
    val cameraOrientationDegrees: Int,
    /** Only this display correction is encoded in the custom Matrix. */
    val displayCompensationDegrees: Int,
    /** Effective buffer-to-viewport rotation after both platform stages. */
    val effectiveRotationDegrees: Int,
    val cameraOrientedWidth: Float,
    val cameraOrientedHeight: Float,
    val logicalWidth: Float,
    val logicalHeight: Float,
    val uniformScale: Float,
    val scaledWidth: Float,
    val scaledHeight: Float,
    /** Legacy signed offsets: negative means pixels are cropped at that edge. */
    val offsetX: Float,
    val offsetY: Float,
    /** Positive number of source-display pixels cropped on each symmetric edge. */
    val cropOffsetX: Float,
    val cropOffsetY: Float,
    val implicitScaleX: Float,
    val implicitScaleY: Float
) {
    val sourceAspectRatio: Float
        get() = logicalWidth / logicalHeight

    val viewportAspectRatio: Float
        get() = viewportWidth / viewportHeight

    /** Maps an unrotated camera buffer point through the complete visual path. */
    fun mapBufferPointToViewport(x: Float, y: Float): PreviewPoint {
        val rotated = rotateBufferPoint(
            x = x,
            y = y,
            rotationDegrees = effectiveRotationDegrees
        )
        return PreviewPoint(
            x = viewportWidth / 2f + rotated.x * uniformScale,
            y = viewportHeight / 2f + rotated.y * uniformScale
        )
    }

    /**
     * Maps a point in the actual display viewport back to the unrotated
     * preview buffer. This is the inverse of [mapBufferPointToViewport],
     * including the symmetric center-crop.
     */
    fun mapDisplayPointToBuffer(x: Float, y: Float): PreviewPoint {
        val displayDx = x.coerceIn(0f, viewportWidth) - viewportWidth / 2f
        val displayDy = y.coerceIn(0f, viewportHeight) - viewportHeight / 2f
        val rotated = PreviewPoint(displayDx / uniformScale, displayDy / uniformScale)
        val unrotated = inverseRotate(rotated, effectiveRotationDegrees)
        return PreviewPoint(
            x = bufferWidth / 2f + unrotated.x,
            y = bufferHeight / 2f + unrotated.y
        )
    }

    fun mapDisplayNormalizedPointToBuffer(point: NormalizedPoint): NormalizedPoint {
        val bufferPoint = mapDisplayPoint(
            x = point.x.coerceIn(0f, 1f) * viewportWidth,
            y = point.y.coerceIn(0f, 1f) * viewportHeight
        )
        return NormalizedPoint(
            x = (bufferPoint.x / bufferWidth.toFloat()).coerceIn(0f, 1f),
            y = (bufferPoint.y / bufferHeight.toFloat()).coerceIn(0f, 1f)
        )
    }

    /**
     * Pure model of camera buffer -> TextureView platform orientation ->
     * TextureView implicit fit -> custom correction Matrix. Tests use this to
     * prove the composed result, including the otherwise non-uniform implicit
     * scale.
     */
    fun mapBufferPointThroughTextureView(x: Float, y: Float): PreviewPoint {
        val cameraOriented = rotateBufferPoint(x, y, cameraOrientationDegrees)
        val texturePoint = PreviewPoint(
            x = viewportWidth / 2f + cameraOriented.x * implicitScaleX,
            y = viewportHeight / 2f + cameraOriented.y * implicitScaleY
        )
        return applyAffine(buildPreviewTransformValues(this), texturePoint)
    }

    private fun mapDisplayPoint(x: Float, y: Float): PreviewPoint =
        mapDisplayPointToBuffer(x, y)

    private fun rotateBufferPoint(
        x: Float,
        y: Float,
        rotationDegrees: Int
    ): PreviewPoint {
        val sourceCenterX = bufferWidth / 2f
        val sourceCenterY = bufferHeight / 2f
        val dx = x - sourceCenterX
        val dy = y - sourceCenterY
        val rotated = rotateVector(dx, dy, rotationDegrees)
        return PreviewPoint(rotated.x, rotated.y)
    }
}

data class PreviewPoint(val x: Float, val y: Float)

internal fun calculatePreviewTransformGeometry(
    bufferWidth: Int,
    bufferHeight: Int,
    viewportWidth: Float,
    viewportHeight: Float,
    sensorOrientationDegrees: Int,
    displayRotation: Int,
    lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
): PreviewTransformGeometry {
    PreviewViewfinderInput(
        bufferWidth = bufferWidth,
        bufferHeight = bufferHeight,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight
    )
    val sensor = normalizeRightAngle(sensorOrientationDegrees) ?: 0
    val displayDegrees = displayRotationDegrees(displayRotation) ?: 0
    val relativeRotation = relativeRotationDegrees(
        sensorOrientationDegrees = sensor,
        displayRotation = displayRotation,
        lensFacing = lensFacing
    )

    // TextureView has already applied the sensor orientation to the camera
    // buffer. The reported sensor orientation carries the platform's
    // lens-facing direction (back sensors are normally 90, front sensors
    // normally 270), so it is applied once as-is. The display correction uses
    // the corresponding Camera2 sign: -display for back and +display for
    // front.
    val cameraOrientation = sensor
    val displayCompensation = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
        normalizeRightAngle(displayDegrees) ?: 0
    } else {
        normalizeRightAngle(-displayDegrees) ?: 0
    }
    val effectiveRotation = normalizeRightAngle(cameraOrientation + displayCompensation) ?: 0
    val cameraSwapped = cameraOrientation == 90 || cameraOrientation == 270
    val cameraOrientedWidth = if (cameraSwapped) bufferHeight else bufferWidth
    val cameraOrientedHeight = if (cameraSwapped) bufferWidth else bufferHeight
    val effectiveSwapped = effectiveRotation == 90 || effectiveRotation == 270
    val logicalWidth = if (effectiveSwapped) bufferHeight else bufferWidth
    val logicalHeight = if (effectiveSwapped) bufferWidth else bufferHeight

    // This is the scale TextureView applies before setTransform: it fits the
    // already sensor-oriented content independently on X and Y. The custom
    // matrix first inverts these two factors and then applies one uniform crop.
    val implicitScaleX = viewportWidth / cameraOrientedWidth.toFloat()
    val implicitScaleY = viewportHeight / cameraOrientedHeight.toFloat()
    val uniformScale = max(
        viewportWidth / logicalWidth.toFloat(),
        viewportHeight / logicalHeight.toFloat()
    )
    val scaledWidth = logicalWidth * uniformScale
    val scaledHeight = logicalHeight * uniformScale
    val cropOffsetX = ((scaledWidth - viewportWidth) / 2f).coerceAtLeast(0f)
    val cropOffsetY = ((scaledHeight - viewportHeight) / 2f).coerceAtLeast(0f)

    return PreviewTransformGeometry(
        bufferWidth = bufferWidth,
        bufferHeight = bufferHeight,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        relativeRotationDegrees = relativeRotation,
        cameraOrientationDegrees = cameraOrientation,
        displayCompensationDegrees = displayCompensation,
        effectiveRotationDegrees = effectiveRotation,
        cameraOrientedWidth = cameraOrientedWidth.toFloat(),
        cameraOrientedHeight = cameraOrientedHeight.toFloat(),
        logicalWidth = logicalWidth.toFloat(),
        logicalHeight = logicalHeight.toFloat(),
        uniformScale = uniformScale,
        scaledWidth = scaledWidth,
        scaledHeight = scaledHeight,
        offsetX = -cropOffsetX,
        offsetY = -cropOffsetY,
        cropOffsetX = cropOffsetX,
        cropOffsetY = cropOffsetY,
        implicitScaleX = implicitScaleX,
        implicitScaleY = implicitScaleY
    )
}

/** Builds the Matrix correction applied after TextureView's implicit fit. */
internal fun buildPreviewTransformMatrix(
    geometry: PreviewTransformGeometry
): Matrix = Matrix().apply {
    setValues(buildPreviewTransformValues(geometry))
}

/**
 * Pure affine coefficients for [buildPreviewTransformMatrix]. These values
 * are intentionally not expected to have equal row norms: they undo
 * TextureView's implicit non-uniform fit. The composed pipeline is uniform.
 */
internal fun buildPreviewTransformValues(
    geometry: PreviewTransformGeometry
): FloatArray {
    val correctionScaleX = geometry.uniformScale / geometry.implicitScaleX
    val correctionScaleY = geometry.uniformScale / geometry.implicitScaleY
    val rotation = geometry.displayCompensationDegrees
    val (a, b, c, d) = when (rotation) {
        90 -> floatArrayOf(0f, -correctionScaleY, correctionScaleX, 0f)
        180 -> floatArrayOf(-correctionScaleX, 0f, 0f, -correctionScaleY)
        270 -> floatArrayOf(0f, correctionScaleY, -correctionScaleX, 0f)
        else -> floatArrayOf(correctionScaleX, 0f, 0f, correctionScaleY)
    }
    val centerX = geometry.viewportWidth / 2f
    val centerY = geometry.viewportHeight / 2f
    return floatArrayOf(
        a, b, centerX - a * centerX - b * centerY,
        c, d, centerY - c * centerX - d * centerY,
        0f, 0f, 1f
    )
}

private fun rotateVector(x: Float, y: Float, rotationDegrees: Int): PreviewPoint = when (rotationDegrees) {
    90 -> PreviewPoint(-y, x)
    180 -> PreviewPoint(-x, -y)
    270 -> PreviewPoint(y, -x)
    else -> PreviewPoint(x, y)
}

private fun inverseRotate(point: PreviewPoint, rotationDegrees: Int): PreviewPoint =
    rotateVector(point.x, point.y, normalizeRightAngle(-rotationDegrees) ?: 0)

private fun applyAffine(values: FloatArray, point: PreviewPoint): PreviewPoint = PreviewPoint(
    x = values[0] * point.x + values[1] * point.y + values[2],
    y = values[3] * point.x + values[4] * point.y + values[5]
)
