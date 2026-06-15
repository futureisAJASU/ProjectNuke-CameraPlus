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

private val zeroFailedCounterRegex = Regex("\\bfailed\\s+0\\b", RegexOption.IGNORE_CASE)

fun isTerminalStatus(status: String): Boolean {
    if (status.contains("CAPTURE_COMPLETE_PARTIAL", ignoreCase = true)) {
        return false
    }

    val lower = status.lowercase()
    val hasExplicitFailure = status.contains("PIPELINE_FAILED", ignoreCase = true) ||
        status.contains("CAPTURE_FAILED", ignoreCase = true) ||
        status.contains("PROCESS_FAILED", ignoreCase = true) ||
        status.contains("EXPORT_FAILED", ignoreCase = true)
    val hasGenericFailure = lower.contains("failed") && !zeroFailedCounterRegex.containsMatchIn(status)

    return status.contains("PIPELINE_COMPLETE", ignoreCase = true) ||
            status.contains("CAPTURE_COMPLETE", ignoreCase = true) ||
            hasExplicitFailure ||
            status.contains("CAPTURE_TIMEOUT", ignoreCase = true) ||
            status.contains("PROCESS_TIMEOUT", ignoreCase = true) ||
            status.contains("EXPORT_TIMEOUT", ignoreCase = true) ||
            lower.contains("saved to gallery") ||
            hasGenericFailure ||
            lower.contains("timeout") ||
            status.contains("완료") ||
            status.contains("실패") ||
            status.contains("오류") ||
            status.contains("연결 해제")
}
