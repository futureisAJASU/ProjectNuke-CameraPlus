package com.projectnuke.keplernightlab

internal fun currentAvailableJavaHeapBytes(runtime: Runtime = Runtime.getRuntime()): Long {
    val used = runtime.totalMemory().checkedSubtract(runtime.freeMemory())
    return runtime.maxMemory().checkedSubtract(used).coerceAtLeast(0L)
}

private fun Long.checkedSubtract(value: Long): Long {
    if (value < 0L || this < value) return 0L
    return this - value
}

internal data class FusionMemoryPlanRequest(
    val width: Int,
    val tileRows: Int,
    val candidateFrames: Int,
    val availableBytes: Long,
    val sourceBitmapCount: Int = 2,
    val javaBytesPerPixel: Long = 20L,
    val jniCopyBytesPerPixel: Long = 4L,
    val nativeBytesPerPixel: Long = 8L,
    val fullOutputBitmapBytes: Long = 0L,
    val postprocessOutputBitmapBytes: Long = 0L,
    val decoderBytesPerTilePixel: Long = 4L,
    val safetyReserveBytes: Long = 64L * 1024L * 1024L
)

internal data class FusionMemoryPlan(
    val tileRows: Int,
    val candidateBatchSize: Int,
    val estimatedPeakBytes: Long,
    val budgetBytes: Long,
    val fallbackReason: String?,
    val streamingFallback: Boolean,
    val cannotFit: Boolean = false
)

private fun checkedMultiply(a: Long, b: Long): Long {
    require(a >= 0L && b >= 0L) { "memory dimensions must be non-negative" }
    if (a != 0L && b > Long.MAX_VALUE / a) error("memory estimate overflow")
    return a * b
}

private fun checkedAdd(a: Long, b: Long): Long {
    if (b > Long.MAX_VALUE - a) error("memory estimate overflow")
    return a + b
}

internal fun planFusionMemory(request: FusionMemoryPlanRequest): FusionMemoryPlan {
    require(request.width > 0 && request.tileRows > 0 && request.candidateFrames > 0)
    require(request.availableBytes > 0L)
    val budget = request.availableBytes
    require(request.sourceBitmapCount >= 0)
    require(request.fullOutputBitmapBytes >= 0L && request.postprocessOutputBitmapBytes >= 0L)
    require(request.decoderBytesPerTilePixel >= 0L)
    val fixedResidency = checkedAdd(
        request.safetyReserveBytes,
        checkedAdd(request.fullOutputBitmapBytes, request.postprocessOutputBitmapBytes)
    )
    val perPixel = checkedAdd(
        checkedAdd(
            checkedAdd(request.javaBytesPerPixel, request.jniCopyBytesPerPixel),
            request.nativeBytesPerPixel
        ),
        checkedAdd(
            checkedMultiply(request.sourceBitmapCount.toLong(), 4L),
            request.decoderBytesPerTilePixel
        )
    )
    if (fixedResidency >= budget) {
        return FusionMemoryPlan(
            tileRows = 0,
            candidateBatchSize = 0,
            estimatedPeakBytes = fixedResidency,
            budgetBytes = budget,
            fallbackReason = "CannotFit: fixed residency and reserve exceed budget",
            streamingFallback = false,
            cannotFit = true
        )
    }
    val usable = budget - fixedResidency
    val desiredPixels = checkedMultiply(request.width.toLong(), request.tileRows.toLong())
    val desiredPacked = checkedAdd(fixedResidency, checkedMultiply(desiredPixels, perPixel))
    if (desiredPacked <= budget) {
        return FusionMemoryPlan(
            request.tileRows,
            1,
            desiredPacked,
            budget,
            if (request.candidateFrames > 1) "candidate_streaming_one_at_a_time" else null,
            request.candidateFrames > 1
        )
    }

    val bytesPerRow = checkedMultiply(request.width.toLong(), perPixel)
    if (usable < bytesPerRow) {
        return FusionMemoryPlan(
            0, 0, fixedResidency, budget,
            "CannotFit: one row and one candidate exceed budget", false, true
        )
    }
    val rowsForBatch = (usable / bytesPerRow).toInt().coerceAtMost(request.tileRows)
    if (rowsForBatch < 1) {
        return FusionMemoryPlan(0, 0, fixedResidency, budget,
            "CannotFit: no processable tile", false, true)
    }
    val peak = checkedAdd(
        fixedResidency,
        checkedMultiply(
            checkedMultiply(request.width.toLong(), rowsForBatch.toLong()),
            perPixel
        )
    )
    return FusionMemoryPlan(
        rowsForBatch,
        1,
        peak,
        budget,
        if (request.candidateFrames > 1) "tile_rows_reduced_candidate_streaming" else "tile_rows_reduced",
        request.candidateFrames > 1
    )
}

internal fun checkedBitmapBytes(width: Int, height: Int, bytesPerPixel: Long = 4L): Long {
    require(width > 0 && height > 0 && bytesPerPixel > 0L)
    return checkedMultiply(
        checkedMultiply(width.toLong(), height.toLong()), bytesPerPixel
    )
}
