package com.projectnuke.keplernightlab

internal data class PreviewLifecycleInput(
    val lifecycleStarted: Boolean,
    val cameraScreenVisible: Boolean,
    val pipelineAllowsPreview: Boolean,
    val permissionGranted: Boolean
)

internal fun previewMayRun(input: PreviewLifecycleInput): Boolean =
    input.lifecycleStarted &&
        input.cameraScreenVisible &&
        input.pipelineAllowsPreview &&
        input.permissionGranted
