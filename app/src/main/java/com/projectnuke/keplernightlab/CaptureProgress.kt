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

fun isTerminalStatus(status: String): Boolean {
    val lower = status.lowercase()
    return status.contains("PIPELINE_COMPLETE", ignoreCase = true) ||
            status.contains("CAPTURE_COMPLETE", ignoreCase = true) ||
            status.contains("CAPTURE_COMPLETE_PARTIAL", ignoreCase = true) ||
            status.contains("PIPELINE_FAILED", ignoreCase = true) ||
            status.contains("CAPTURE_TIMEOUT", ignoreCase = true) ||
            status.contains("PROCESS_TIMEOUT", ignoreCase = true) ||
            status.contains("EXPORT_TIMEOUT", ignoreCase = true) ||
            lower.contains("complete") ||
            lower.contains("failed") ||
            lower.contains("timeout") ||
            lower.contains("saved to gallery") ||
            status.contains("완료") ||
            status.contains("실패") ||
            status.contains("오류") ||
            status.contains("연결 해제")
}
