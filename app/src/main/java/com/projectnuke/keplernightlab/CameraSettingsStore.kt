package com.projectnuke.keplernightlab

import android.content.Context

enum class RawSpeedMode(val label: String) {
    BALANCED("Balanced"),
    QUALITY("Quality")
}

data class CameraUiSettings(
    val selectedResolutionName: String,
    val selectedLensSlotName: String,
    val selectedThreeXSourceName: String,
    val pipelineModeName: String,
    val frameCountModeName: String,
    val autoMinFrames: Int,
    val autoMaxFrames: Int,
    val manualFrames: Int,
    val zoomRatio: Float,
    val rawSpeedModeName: String
)

object CameraSettingsStore {
    private const val PREFS_NAME = "kepler_camera_settings"

    fun load(context: Context): CameraUiSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoMin = prefs.getInt("autoMinFrames", 4).coerceIn(MIN_CAPTURE_FRAMES, MAX_CAPTURE_FRAMES)
        val autoMax = prefs.getInt("autoMaxFrames", 8).coerceIn(autoMin, MAX_CAPTURE_FRAMES)
        val selectedThreeXSourceName = parseThreeXSourceModeOrDefault(
            prefs.getString("selectedThreeXSource", ThreeXSourceMode.OPTICAL.name)
        ).name
        return CameraUiSettings(
            selectedResolutionName = prefs.getString("selectedResolution", CaptureResolutionMode.MP12.name)
                ?: CaptureResolutionMode.MP12.name,
            selectedLensSlotName = prefs.getString("selectedLensSlot", LensSlot.MAIN_1X.name)
                ?: LensSlot.MAIN_1X.name,
            selectedThreeXSourceName = selectedThreeXSourceName,
            pipelineModeName = prefs.getString("pipelineMode", PipelineMode.YUV_NIGHT_FUSION.name)
                ?: PipelineMode.YUV_NIGHT_FUSION.name,
            frameCountModeName = prefs.getString("frameCountMode", FrameCountMode.AUTO.name)
                ?: FrameCountMode.AUTO.name,
            autoMinFrames = autoMin,
            autoMaxFrames = autoMax,
            manualFrames = prefs.getInt("manualFrames", 4).coerceIn(MIN_CAPTURE_FRAMES, MAX_CAPTURE_FRAMES),
            zoomRatio = prefs.getFloat("zoomRatio", 1.0f).coerceIn(0.6f, 3.0f),
            rawSpeedModeName = prefs.getString("rawSpeedMode", RawSpeedMode.BALANCED.name)
                ?: RawSpeedMode.BALANCED.name
        )
    }

    fun save(context: Context, settings: CameraUiSettings) {
        val autoMin = settings.autoMinFrames.coerceIn(MIN_CAPTURE_FRAMES, MAX_CAPTURE_FRAMES)
        val autoMax = settings.autoMaxFrames.coerceIn(autoMin, MAX_CAPTURE_FRAMES)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("selectedResolution", settings.selectedResolutionName)
            .putString("selectedLensSlot", settings.selectedLensSlotName)
            .putString("selectedThreeXSource", settings.selectedThreeXSourceName)
            .putString("pipelineMode", settings.pipelineModeName)
            .putString("frameCountMode", settings.frameCountModeName)
            .putInt("autoMinFrames", autoMin)
            .putInt("autoMaxFrames", autoMax)
            .putInt("manualFrames", settings.manualFrames.coerceIn(MIN_CAPTURE_FRAMES, MAX_CAPTURE_FRAMES))
            .putFloat("zoomRatio", settings.zoomRatio.coerceIn(0.6f, 3.0f))
            .putString("rawSpeedMode", settings.rawSpeedModeName)
            .apply()
    }
}
