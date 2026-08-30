package com.projectnuke.keplernightlab

fun mapLevelStateForLayout(
    levelState: DeviceLevelState,
    layoutMode: CameraUiLayoutMode
): DeviceLevelState {
    if (!levelState.available) return levelState
    return when (layoutMode) {
        CameraUiLayoutMode.PORTRAIT -> levelState
        CameraUiLayoutMode.LANDSCAPE_LEFT -> {
            // In landscape left: display pitch maps to -device roll,
            // display roll maps to device pitch.
            levelState.copy(
                pitchDegrees = -levelState.rollDegrees,
                rollDegrees = levelState.pitchDegrees
            )
        }
        CameraUiLayoutMode.LANDSCAPE_RIGHT -> {
            // In landscape right: display pitch maps to device roll,
            // display roll maps to -device pitch.
            levelState.copy(
                pitchDegrees = levelState.rollDegrees,
                rollDegrees = -levelState.pitchDegrees
            )
        }
    }
}
