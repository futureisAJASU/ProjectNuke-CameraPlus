package com.projectnuke.keplernightlab

/**
 * Phase 5: pure UI model of the small, non-blocking background-processing
 * status surface. Derived ONLY from [BackgroundProcessingSnapshot] truth plus
 * a caller-owned bounded completion-flash flag. Never gates the shutter and
 * never carries pixel objects or Activity references.
 */
internal data class BackgroundQueueUiModel(
    val visible: Boolean,
    val active: Boolean,
    val queuedCount: Int,
    val activeKindName: String?,
    val primaryLabel: String?,
    val queuedLabel: String?,
    val completionLabel: String?
) {
/** One-line rendering for the existing passive status row (null = hidden). */
    fun combinedLabel(): String? =
        listOf(primaryLabel, queuedLabel, completionLabel)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")
}

/**
 * Phase-10 bounded-backlog policy. The heavy lane is intentionally single and
 * FIFO, so rapid capture can outrun it; the pending queue of DURABLE job refs
 * is therefore explicitly bounded. Admission is prevented BEFORE sensor
 * acquisition once no safe capacity remains - never by deleting durable work.
 */
internal const val MAX_QUEUED_HEAVY_JOBS = 3

internal enum class BackpressureDecision {
    ALLOW,
    BLOCK
}

internal data class BackpressureNotice(
    val decision: BackpressureDecision,
    /** Formal-polite Korean shown only when BLOCKING a new capture. */
    val userMessage: String?
)

internal fun evaluateBackpressure(
    queuedCount: Int,
    active: Boolean,
    maxQueued: Int = MAX_QUEUED_HEAVY_JOBS
): BackpressureNotice {
    val pendingBehindLane = queuedCount.coerceAtLeast(0)
    val capacityLeft = maxQueued - pendingBehindLane - (if (active) 1 else 0)
    return if (capacityLeft <= 0) {
        BackpressureNotice(
            decision = BackpressureDecision.BLOCK,
            userMessage = "처리 대기 중인 사진이 많습니다. 잠시 후 다시 촬영해 주세요."
        )
    } else {
        BackpressureNotice(decision = BackpressureDecision.ALLOW, userMessage = null)
    }
}

internal fun backgroundQueueUiModel(
    snapshot: BackgroundProcessingSnapshot,
    showCompletionFlash: Boolean = false
): BackgroundQueueUiModel {
    val pending = snapshot.hasPendingWork
    return BackgroundQueueUiModel(
        visible = pending || showCompletionFlash,
        active = snapshot.hasActiveWork,
        queuedCount = snapshot.queuedCount,
        activeKindName = snapshot.activeJobKind?.name,
        primaryLabel = if (pending) "사진을 처리하고 있습니다." else null,
        queuedLabel = snapshot.queuedCount.takeIf { it > 0 }?.let { "처리 대기 ${it}건" },
        completionLabel = if (!pending && showCompletionFlash) {
            "사진 처리가 완료되었습니다."
        } else {
            null
        }
    )
}
