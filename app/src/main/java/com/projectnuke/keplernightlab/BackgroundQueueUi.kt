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
