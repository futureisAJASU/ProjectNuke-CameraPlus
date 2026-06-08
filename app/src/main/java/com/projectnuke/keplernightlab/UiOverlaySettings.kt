package com.projectnuke.keplernightlab

import android.content.Context

data class UiOverlaySettings(
    val showGrid: Boolean = true,
    val showLevel: Boolean = false
)

object UiOverlaySettingsStore {
    private const val PREFS = "ui_overlay_settings"
    private const val KEY_SHOW_GRID = "show_grid"
    private const val KEY_SHOW_LEVEL = "show_level"

    fun load(context: Context): UiOverlaySettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return UiOverlaySettings(
            showGrid = prefs.getBoolean(KEY_SHOW_GRID, true),
            showLevel = prefs.getBoolean(KEY_SHOW_LEVEL, false)
        )
    }

    fun save(context: Context, settings: UiOverlaySettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_GRID, settings.showGrid)
            .putBoolean(KEY_SHOW_LEVEL, settings.showLevel)
            .apply()
    }
}
