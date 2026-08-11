package com.projectnuke.keplernightlab

internal data class CaptureAttemptUiSnapshot(
    val lensSlot: LensSlot,
    val resolution: CaptureResolutionMode,
    val zoomRatio: Float,
    val focusAeState: FocusAeState,
    val processingSettings: ProcessingSettings,
    val outputFormat: FinalOutputFormat
)
