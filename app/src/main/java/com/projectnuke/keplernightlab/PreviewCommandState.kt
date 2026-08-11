package com.projectnuke.keplernightlab

internal enum class PreviewCommandApplyOutcome {
    APPLIED,
    CAMERA_REQUEST_FAILED,
    DISPATCH_REJECTED,
    DISPATCH_THROWN,
    STALE_GENERATION
}

internal data class PreviewCommandSnapshot(
    val generation: Int = 0,
    val requestedZoomRatio: Float? = null,
    val appliedZoomRatio: Float? = null,
    val requestedFocusAeState: FocusAeState? = null,
    val appliedFocusAeState: FocusAeState? = null,
    val requestedMeteringMode: MeteringMode? = null,
    val appliedMeteringMode: MeteringMode? = null,
    val lastOutcome: PreviewCommandApplyOutcome? = null
)

internal fun PreviewCommandSnapshot.withGenerationOutcome(
    localGeneration: Int,
    outcome: PreviewCommandApplyOutcome
): PreviewCommandSnapshot = if (localGeneration < generation) {
    this
} else {
    copy(generation = localGeneration, lastOutcome = outcome)
}
