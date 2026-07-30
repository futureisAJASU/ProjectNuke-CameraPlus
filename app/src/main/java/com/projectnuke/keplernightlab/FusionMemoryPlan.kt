package com.projectnuke.keplernightlab

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
    val streamingFallback: Boolean
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
    val usable = (budget - reserveAndResidency).coerceAtLeast(1L)
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

    val maxPackedFrames = (usable / checkedMultiply(request.width.toLong(), perPixel))
        .toInt().coerceAtLeast(1).coerceAtMost(request.candidateFrames)
    val rowsForBatch = (usable / checkedMultiply(request.width.toLong(), perPixel * maxPackedFrames.toLong()))
        .toInt().coerceAtLeast(1).coerceAtMost(request.tileRows)
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
