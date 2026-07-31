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
    val sourceBytes = checkedMultiply(
        checkedMultiply(request.width.toLong(), request.tileRows.toLong()),
        request.sourceBitmapCount.toLong()
    )
    val reserveAndResidency = checkedAdd(request.safetyReserveBytes, checkedMultiply(sourceBytes, 4L))
    val perPixel = checkedAdd(
        checkedAdd(request.javaBytesPerPixel, request.jniCopyBytesPerPixel),
        request.nativeBytesPerPixel
    )
    if (reserveAndResidency >= budget) {
        return FusionMemoryPlan(
            tileRows = 0,
            candidateBatchSize = 0,
            estimatedPeakBytes = reserveAndResidency,
            budgetBytes = budget,
            fallbackReason = "CannotFit: fixed residency and reserve exceed budget",
            streamingFallback = false,
            cannotFit = true
        )
    }
    val usable = budget - reserveAndResidency
    val desiredPixels = checkedMultiply(request.width.toLong(), request.tileRows.toLong())
    val desiredPacked = checkedAdd(
        reserveAndResidency,
        checkedMultiply(checkedMultiply(desiredPixels, perPixel), request.candidateFrames.toLong())
    )
    if (desiredPacked <= budget) {
        return FusionMemoryPlan(
            request.tileRows,
            request.candidateFrames,
            desiredPacked,
            budget,
            null,
            false
        )
    }

    val bytesPerRowPerFrame = checkedMultiply(request.width.toLong(), perPixel)
    val maxPackedFrames = (usable / bytesPerRowPerFrame)
        .toInt().coerceAtMost(request.candidateFrames)
    if (maxPackedFrames < 1) {
        return FusionMemoryPlan(
            0, 0, reserveAndResidency, budget,
            "CannotFit: one row and one candidate exceed budget", false, true
        )
    }
    val rowsForBatch = (usable / checkedMultiply(bytesPerRowPerFrame, maxPackedFrames.toLong()))
        .toInt().coerceAtMost(request.tileRows)
    if (rowsForBatch < 1) {
        return FusionMemoryPlan(0, 0, reserveAndResidency, budget,
            "CannotFit: no processable tile", false, true)
    }
    val streaming = maxPackedFrames == 1 && request.candidateFrames > 1
    val peak = checkedAdd(
        reserveAndResidency,
        checkedMultiply(
            checkedMultiply(request.width.toLong(), rowsForBatch.toLong()),
            checkedMultiply(perPixel, maxPackedFrames.toLong())
        )
    )
    return FusionMemoryPlan(
        rowsForBatch,
        maxPackedFrames,
        peak,
        budget,
        if (streaming) "candidate_batch_streaming" else "tile_rows_reduced",
        streaming
    )
}
