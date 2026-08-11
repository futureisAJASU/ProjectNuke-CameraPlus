package com.projectnuke.keplernightlab

internal enum class GalleryUiAction {
    IDLE,
    REPROCESSING,
    CLEANING,
    DELETING,
    UPDATING_FRAME_SELECTION
}

internal data class GalleryUiActionSession(
    val jobId: String,
    val action: GalleryUiAction,
    val generation: Long
)

internal fun canStartGalleryUiAction(
    active: GalleryUiActionSession?,
    jobId: String,
    action: GalleryUiAction
): Boolean = active == null && action != GalleryUiAction.IDLE && jobId.isNotEmpty()

internal fun acceptsGalleryUiActionCompletion(
    active: GalleryUiActionSession?,
    jobId: String,
    generation: Long
): Boolean = active?.jobId == jobId && active.generation == generation
