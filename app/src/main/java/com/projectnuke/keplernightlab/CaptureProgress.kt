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

fun isCaptureStageCompleteButPipelineStillRunning(status: String): Boolean {
    return status.contains("CAPTURE_COMPLETE", ignoreCase = true) &&
        !status.contains("PIPELINE_COMPLETE", ignoreCase = true)
}

fun isTerminalStatus(status: String): Boolean {
    if (status.contains("CAPTURE_COMPLETE_PARTIAL", ignoreCase = true)) return false
    if (status.contains("CAPTURE_COMPLETE", ignoreCase = true)) return false
    if (status.contains("RAW capture sequence done", ignoreCase = true)) return false

    val terminalPrefixes = listOf(
        "PIPELINE_COMPLETE",
        "EXPORT_COMPLETE",
        "PIPELINE_FAILED",
        "CAPTURE_FAILED",
        "PROCESS_FAILED",
        "EXPORT_FAILED",
        "CAPTURE_TIMEOUT",
        "PROCESS_TIMEOUT",
        "EXPORT_TIMEOUT"
    )
    return terminalPrefixes.any { status.trimStart().startsWith(it, ignoreCase = true) }
}
