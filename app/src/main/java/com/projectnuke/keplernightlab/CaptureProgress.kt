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
