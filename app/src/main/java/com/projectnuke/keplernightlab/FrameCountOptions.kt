package com.projectnuke.keplernightlab

import kotlin.math.roundToInt

const val MIN_CAPTURE_FRAMES = 2
const val MAX_CAPTURE_FRAMES = 16

enum class FrameCountMode(
    val label: String
) {
    AUTO("Auto"),
    MANUAL("Manual")
}

data class FrameCountSettings(
    val mode: FrameCountMode,
    val autoMinFrames: Int,
    val autoMaxFrames: Int,
    val manualFrames: Int
)

data class FramePlan(
    val framesToCapture: Int,
    val maxFrames: Int,
    val reason: String
)

fun currentFrameCountSettings(
    mode: FrameCountMode,
    autoMinFrames: Int,
    autoMaxFrames: Int,
    manualFrames: Int
): FrameCountSettings {
    val clampedMin = autoMinFrames.coerceIn(MIN_CAPTURE_FRAMES, MAX_CAPTURE_FRAMES)
    val clampedMax = autoMaxFrames.coerceIn(clampedMin, MAX_CAPTURE_FRAMES)

    return FrameCountSettings(
        mode = mode,
        autoMinFrames = clampedMin,
        autoMaxFrames = clampedMax,
        manualFrames = manualFrames.coerceIn(MIN_CAPTURE_FRAMES, MAX_CAPTURE_FRAMES)
    )
}

fun estimateFramePlan(
    settings: FrameCountSettings,
    selectedModeLabel: String,
    latestSceneLuma: Double?,
    latestMotionScore: Double?
): FramePlan {
    val normalized = currentFrameCountSettings(
        mode = settings.mode,
        autoMinFrames = settings.autoMinFrames,
        autoMaxFrames = settings.autoMaxFrames,
        manualFrames = settings.manualFrames
    )

    if (normalized.mode == FrameCountMode.MANUAL) {
        return FramePlan(
            framesToCapture = normalized.manualFrames,
            maxFrames = normalized.manualFrames,
            reason = "Manual frame count"
        )
    }

    // TODO: Future version should estimate scene brightness from live preview frames or a quick pre-capture probe.
    // Current version may use the latest processed/captured job or fallback defaults.
    val midpoint = ((normalized.autoMinFrames + normalized.autoMaxFrames) / 2.0).roundToInt()
    var frames = when {
        latestSceneLuma == null -> midpoint
        latestSceneLuma < 55.0 -> normalized.autoMaxFrames
        latestSceneLuma < 115.0 -> midpoint
        else -> normalized.autoMinFrames
    }

    val reasons = mutableListOf<String>()
    when {
        latestSceneLuma == null -> reasons.add("scene luma unknown, using midpoint")
        latestSceneLuma < 55.0 -> reasons.add("dark scene")
        latestSceneLuma < 115.0 -> reasons.add("medium scene")
        else -> reasons.add("bright scene")
    }

    if (selectedModeLabel.contains("야간") || selectedModeLabel.contains("Night", ignoreCase = true)) {
        frames += 1
        reasons.add("night mode bias")
    }

    if (latestMotionScore != null && latestMotionScore > 0.08) {
        frames -= if (latestMotionScore > 0.18) 2 else 1
        reasons.add("motion reduced frames")
    }

    frames = frames.coerceIn(normalized.autoMinFrames, normalized.autoMaxFrames)

    return FramePlan(
        framesToCapture = frames,
        maxFrames = normalized.autoMaxFrames,
        reason = reasons.joinToString(", ")
    )
}
