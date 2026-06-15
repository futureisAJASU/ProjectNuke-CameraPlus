package com.projectnuke.keplernightlab

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class KeplerGalleryJobSummary(
    val id: String,
    val jobType: String,
    val directory: File,
    val createdAt: Long,
    val status: String,
    val requestedFrames: Int,
    val savedFrames: Int,
    val width: Int?,
    val height: Int?,
    val folderSizeBytes: Long,
    val finalPreviewFile: File?,
    val finalExportExists: Boolean,
    val frameFiles: List<File>,
    val metadata: JSONObject?
)

fun loadKeplerGalleryJobs(context: Context): List<KeplerGalleryJobSummary> {
    return keplerGalleryRoots(context).flatMap { root ->
        root.listFiles()
            ?.filter { it.isDirectory && matchesJobPrefix(root, it.name) }
            .orEmpty()
    }.map(::readKeplerGalleryJob).sortedByDescending { it.createdAt }
}

fun deleteKeplerGalleryJob(context: Context, jobDirectory: File): Result<Unit> = runCatching {
    val target = jobDirectory.canonicalFile
    val allowed = keplerGalleryRoots(context).any { root ->
        target.parentFile == root.canonicalFile && matchesJobPrefix(root, target.name)
    }
    require(allowed) { "Refusing to delete directory outside Kepler job roots." }
    require(target.isDirectory) { "Job directory no longer exists." }
    check(target.deleteRecursively()) { "Failed to delete ${target.name}." }
}

private fun keplerGalleryRoots(context: Context): List<File> {
    val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return emptyList()
    return listOf(
        File(pictures, "KeplerRawFusion"),
        File(pictures, "KeplerColorBurst")
    )
}

private fun matchesJobPrefix(root: File, name: String): Boolean = when (root.name) {
    "KeplerRawFusion" -> name.startsWith("KPL_RAW_FUSION_")
    "KeplerColorBurst" -> name.startsWith("KPL_COLOR_BURST_")
    else -> false
}

private fun readKeplerGalleryJob(directory: File): KeplerGalleryJobSummary {
    val job = File(directory, JOB_JSON_FILE_NAME).takeIf { it.isFile }?.let { file ->
        runCatching { JSONObject(file.readText()) }.getOrNull()
    }
    val frameFiles = job?.optJSONArray("frames").frameNames()
        .map { File(directory, it) }
        .filter { it.isFile }
        .ifEmpty {
            directory.listFiles()
                ?.filter { it.isFile && isSourceFrame(it) }
                ?.sortedBy { it.name }
                .orEmpty()
        }
    val finalPreview = resolveFinalPreview(directory, job)
    val createdAt = job?.optLong("createdAt", 0L)
        ?.takeIf { it > 0L }
        ?: directory.lastModified()
    val width = firstPositive(job, "outputWidth", "rawWidth", "inputWidth", "width")
    val height = firstPositive(job, "outputHeight", "rawHeight", "inputHeight", "height")
    val rawType = job?.optString("jobType").orEmpty()
    val jobType = when {
        rawType == "RAW_NIGHT_FUSION" || directory.name.startsWith("KPL_RAW_FUSION_") ->
            "RAW_NIGHT_FUSION"
        else -> "COLOR/YUV"
    }
    val exportExists = finalPreview != null ||
        job?.optBoolean("exportVerified", false) == true ||
        job?.optString("exportStatus").orEmpty().contains("SUCCESS", ignoreCase = true)

    return KeplerGalleryJobSummary(
        id = directory.absolutePath,
        jobType = jobType,
        directory = directory,
        createdAt = createdAt,
        status = job?.optString("status").orEmpty().ifBlank { "UNKNOWN" },
        requestedFrames = job?.optInt("requestedFrames", frameFiles.size) ?: frameFiles.size,
        savedFrames = job?.optInt("savedFrames", frameFiles.size) ?: frameFiles.size,
        width = width,
        height = height,
        folderSizeBytes = folderSizeBytes(directory),
        finalPreviewFile = finalPreview,
        finalExportExists = exportExists,
        frameFiles = frameFiles,
        metadata = job
    )
}

private fun JSONArray?.frameNames(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        repeat(length()) { index ->
            optJSONObject(index)?.optString("file")
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }
    }
}

private fun resolveFinalPreview(directory: File, job: JSONObject?): File? {
    val keys = listOf(
        "finalNightFusionFile",
        "finalFile",
        "averageColorFile",
        "outputFile",
        "previewFile"
    )
    keys.forEach { key ->
        val name = job?.optString(key).orEmpty()
        File(directory, name).takeIf { name.isNotBlank() && it.isFile && it.extension.equals("png", true) }
            ?.let { return it }
    }
    return directory.listFiles()
        ?.filter { it.isFile && it.extension.equals("png", true) && !isSourceFrame(it) }
        ?.maxByOrNull { it.lastModified() }
}

private fun firstPositive(job: JSONObject?, vararg keys: String): Int? {
    return keys.firstNotNullOfOrNull { key -> job?.optInt(key, 0)?.takeIf { it > 0 } }
}

private fun isSourceFrame(file: File): Boolean {
    val name = file.name.lowercase()
    return name.startsWith("frame_") &&
        (name.endsWith(".png") || name.endsWith(".raw16") || name.endsWith(".dng"))
}
