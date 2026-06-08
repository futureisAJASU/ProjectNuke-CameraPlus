package com.projectnuke.keplernightlab

import android.content.Context

enum class FinalOutputFormat(
    val label: String
) {
    HEIF("HEIF"),
    JPEG("JPEG"),
    HEIF_PLUS_RAW("HEIF + RAW"),
    JPEG_PLUS_RAW("JPEG + RAW"),
    PNG_DEBUG("PNG Debug");

    val shouldExportHeif: Boolean
        get() = this == HEIF || this == HEIF_PLUS_RAW

    val shouldExportJpeg: Boolean
        get() = this == JPEG || this == JPEG_PLUS_RAW

    val shouldExportRawSidecar: Boolean
        get() = this == HEIF_PLUS_RAW || this == JPEG_PLUS_RAW

    val isDebugPng: Boolean
        get() = this == PNG_DEBUG
}

enum class OutputFormat(
    val label: String,
    val mimeType: String,
    val extension: String
) {
    HEIF("HEIF", "image/heif", "heic"),
    JPEG("JPEG", "image/jpeg", "jpg"),
    PNG("PNG", "image/png", "png")
}

object OutputSettingsStore {
    private const val PREFS_NAME = "kepler_output_settings"
    private const val KEY_FINAL_OUTPUT_FORMAT = "final_output_format"

    fun load(context: Context): FinalOutputFormat {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_FINAL_OUTPUT_FORMAT, FinalOutputFormat.HEIF.name)
        return FinalOutputFormat.entries.firstOrNull { it.name == name } ?: FinalOutputFormat.HEIF
    }

    fun save(context: Context, format: FinalOutputFormat) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FINAL_OUTPUT_FORMAT, format.name)
            .apply()
    }
}

enum class CacheCleanupPolicy {
    KEEP_ALL,
    DELETE_SOURCE_FRAMES_AFTER_VERIFIED_EXPORT,
    DELETE_INTERMEDIATES_AFTER_VERIFIED_EXPORT,
    DELETE_ALL_CACHE_AFTER_VERIFIED_EXPORT_KEEP_JOB
}

enum class PipelineMode(
    val label: String
) {
    YUV_NIGHT_FUSION("YUV Night Fusion"),
    RAW_NIGHT_FUSION("RAW Night Fusion")
}

data class CleanupResult(
    val deletedFiles: Int,
    val freedBytes: Long,
    val keptFiles: List<String>
)
