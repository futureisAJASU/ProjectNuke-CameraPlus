package com.projectnuke.keplernightlab

enum class CaptureStage {
    IDLE,
    PREPARING,
    CAPTURING,
    PROCESSING,
    DEMOSAICING,
    EXPORTING,
    VERIFYING,
    CLEANING,
    COMPLETE,
    FAILED,
    CANCELLED,
    TIMEOUT
}

data class CaptureProgressState(
    val stage: CaptureStage = CaptureStage.IDLE,
    val message: String = "Ready",
    val requestedFrames: Int = 0,
    val savedFrames: Int = 0,
    val receivedImages: Int = 0,
    val completedResults: Int = 0,
    val progressPercent: Float = 0f
)

/**
 * Authoritative CAMERA-ACQUISITION pair count: a frame counts as acquired only
 * when BOTH pieces of Camera2 evidence exist (the image AND its capture result).
 * Deriving progress from this pair keeps it correct when image/result callbacks
 * arrive out of order. Persisted frames are durability truth and are explicitly
 * NOT sensor-capture progress.
 */
fun cameraAcquisitionPairCount(receivedImages: Int, completedResults: Int): Int =
    minOf(receivedImages, completedResults).coerceAtLeast(0)

/** Acquisition fraction in [0, 1]; monotonic by construction of both counters. */
fun cameraAcquisitionProgressFraction(
    requestedFrames: Int,
    receivedImages: Int,
    completedResults: Int
): Float {
    if (requestedFrames <= 0) return 0f
    val fraction = cameraAcquisitionPairCount(receivedImages, completedResults).toFloat() / requestedFrames
    return fraction.coerceIn(0f, 1f)
}

/**
 * Distinct short state shown while the capture bar HOLDS at 100% after true
 * camera acquisition finished but durable source settlement is still running.
 * The bar must never drop back below 100% during settlement.
 */
const val CAPTURE_SETTLING_MESSAGE = "촬영 데이터를 저장하고 있습니다."

/** Truthful bounded persistence-settlement line (PERSISTED frames, never sensor pairs). */
const val CAPTURE_PERSISTENCE_PROGRESS_PREFIX = "촬영 데이터 저장 중 · "

/**
 * Semantic states of the foreground capture surface:
 *  CAPTURING        - real acquisition fraction 0..100%;
 *  SETTLING_CAPTURE - acquisition holds at 100%; durable settlement still running;
 *  RELEASED         - durable handoff proven, shutter admission restored.
 */
enum class CaptureDisplayState { CAPTURING, SETTLING_CAPTURE, RELEASED }

/** Pure display model for the capture bar / shutter-busy indicator. */
data class CaptureSettlementView(
    val state: CaptureDisplayState,
    val captureComplete: Boolean,
    val acquiredFrames: Int,
    val requestedFrames: Int,
    val persistedFrames: Int,
    val percentText: String
)

fun captureAcquisitionComplete(progress: CaptureProgressState): Boolean =
    progress.requestedFrames > 0 &&
        cameraAcquisitionPairCount(progress.receivedImages, progress.completedResults) >=
        progress.requestedFrames

private fun isPostCaptureTerminalStage(stage: CaptureStage): Boolean = when (stage) {
    CaptureStage.COMPLETE, CaptureStage.FAILED,
    CaptureStage.CANCELLED, CaptureStage.TIMEOUT -> true
    else -> false
}

fun captureSettlementView(
    progress: CaptureProgressState,
    captureResourcesSettled: Boolean = false
): CaptureSettlementView {
    val complete = captureAcquisitionComplete(progress)
    val state = when {
        !complete -> CaptureDisplayState.CAPTURING
        captureResourcesSettled || isPostCaptureTerminalStage(progress.stage) ->
            CaptureDisplayState.RELEASED
        else -> CaptureDisplayState.SETTLING_CAPTURE
    }
    return CaptureSettlementView(
        state = state,
        captureComplete = complete,
        acquiredFrames = cameraAcquisitionPairCount(progress.receivedImages, progress.completedResults),
        requestedFrames = progress.requestedFrames,
        persistedFrames = progress.savedFrames,
        percentText = "${(progress.progressPercent.coerceIn(0f, 1f) * 100).toInt()}%"
    )
}

/** Settlement detail line; count form while persisting, short form once all persisted. */
fun captureSettlementDetailText(view: CaptureSettlementView): String = when {
    view.state != CaptureDisplayState.SETTLING_CAPTURE -> ""
    view.persistedFrames < view.requestedFrames ->
        "$CAPTURE_PERSISTENCE_PROGRESS_PREFIX${view.persistedFrames}/${view.requestedFrames}"
    else -> CAPTURE_SETTLING_MESSAGE
}

fun isCaptureStageCompleteButPipelineStillRunning(status: String): Boolean {
    val normalized = status.trimStart()
    return (normalized.startsWith("CAPTURE_COMPLETE", ignoreCase = true) ||
        normalized.startsWith("CAPTURE_COMPLETE_PARTIAL", ignoreCase = true)) &&
        !normalized.startsWith("PIPELINE_COMPLETE", ignoreCase = true)
}

fun isTerminalStatus(status: String): Boolean {
    val normalized = status.trimStart()
    if (normalized.startsWith("CAPTURE_COMPLETE_PARTIAL", ignoreCase = true)) return false
    if (normalized.startsWith("CAPTURE_COMPLETE", ignoreCase = true)) return false
    if (normalized.startsWith("RAW capture sequence done", ignoreCase = true)) return false

    val terminalPrefixes = listOf(
        "PIPELINE_COMPLETE",
        "EXPORT_COMPLETE",
        "PIPELINE_FAILED",
        "CAPTURE_FAILED",
        "PROCESS_FAILED",
        "EXPORT_FAILED",
        "CAPTURE_TIMEOUT",
        "PROCESS_TIMEOUT",
        "EXPORT_TIMEOUT",
        "PIPELINE_CANCELLED"
    )
    return terminalPrefixes.any { normalized.startsWith(it, ignoreCase = true) }
}
