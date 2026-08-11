package com.projectnuke.keplernightlab

internal enum class PreviewCommandKind {
    FOCUS_AE,
    METERING,
    ZOOM
}

internal data class PreviewCommand(
    val generation: Int,
    val kind: PreviewCommandKind
)

internal fun acceptsPreviewCommand(
    currentGeneration: Int,
    active: Boolean,
    command: PreviewCommand
): Boolean = active && command.generation == currentGeneration
