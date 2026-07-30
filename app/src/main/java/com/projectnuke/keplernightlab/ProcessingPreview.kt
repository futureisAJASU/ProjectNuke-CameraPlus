package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

private const val PROCESSING_PREVIEW_MAX_DIMENSION = 1280

internal fun createProcessingPreviewSource(source: Bitmap): Bitmap {
    require(!source.isRecycled) { "Preview source is recycled" }
    val softwareCopy = source.copy(Bitmap.Config.ARGB_8888, true)
        ?: error("Could not create CPU-readable preview source")
    val longest = max(softwareCopy.width, softwareCopy.height)
    if (longest <= PROCESSING_PREVIEW_MAX_DIMENSION) return softwareCopy

    val scale = PROCESSING_PREVIEW_MAX_DIMENSION.toFloat() / longest.toFloat()
    val width = (softwareCopy.width * scale).roundToInt().coerceAtLeast(1)
    val height = (softwareCopy.height * scale).roundToInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(softwareCopy, width, height, true)
    if (scaled !== softwareCopy) softwareCopy.recycle()
    return scaled
}

internal fun renderProcessingPreview(
    source: Bitmap,
    settings: ProcessingSettings,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
): Bitmap = applyClassicYuvPostProcessing(source, settings.resolvedParams(), cancellation)
