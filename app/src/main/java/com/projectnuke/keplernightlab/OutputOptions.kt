package com.projectnuke.keplernightlab

enum class OutputFormat(
    val label: String,
    val mimeType: String,
    val extension: String
) {
    HEIF("HEIF", "image/heif", "heic"),
    JPEG("JPEG", "image/jpeg", "jpg"),
    PNG("PNG", "image/png", "png")
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
