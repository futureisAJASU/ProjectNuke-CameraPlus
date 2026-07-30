package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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

internal class SerializedPreviewWorker<S, R>(
    private val render: (S) -> R,
    private val recycleSource: (S) -> Unit,
    private val recycleResult: (R) -> Unit,
    private val adopt: (R) -> Unit
) {
    private data class Pending<S>(val generation: Long, val source: S)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong(0L)
    private var pending: Pending<S>? = null

    @Synchronized
    fun submit(source: S): Long {
        val current = generation.incrementAndGet()
        pending?.let { recycleSource(it.source) }
        pending = Pending(current, source)
        executor.execute {
            val request = synchronized(this) {
                pending.also { pending = null }
            } ?: return@execute
            var result: R? = null
            try {
                result = render(request.source)
                if (generation.get() == request.generation) {
                    adopt(result!!)
                    result = null
                }
            } finally {
                recycleSource(request.source)
                result?.let(recycleResult)
            }
        }
        return current
    }

    @Synchronized
    fun close() {
        generation.incrementAndGet()
        pending?.let { recycleSource(it.source) }
        pending = null
        executor.shutdown()
    }

    fun awaitClosed(timeoutMs: Long = 5_000L): Boolean =
        executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
}
