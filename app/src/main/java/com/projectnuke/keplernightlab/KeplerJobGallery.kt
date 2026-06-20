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
    val storage: KeplerJobStorageInfo,
    val finalPreviewFile: File?,
    val finalExportExists: Boolean,
    val frames: List<KeplerGalleryFrame>,
    val metadata: JSONObject?
)

data class KeplerJobStorageInfo(
    val totalJobBytes: Long,
    val totalJobSizeText: String,
    val finalOutputBytes: Long,
    val finalOutputSizeText: String,
    val rawFramesBytes: Long,
    val intermediateFilesBytes: Long,
    val debugFilesBytes: Long,
    val previewFilesBytes: Long,
    val cacheFilesBytes: Long,
    val cleanableBytes: Long,
    val fileCount: Int
)

enum class KeplerJobCleanupType {
    DEBUG_ONLY,
    SOURCE_FRAMES_ONLY,
    FINAL_ONLY,
    SOURCE_ONLY,
    FAILED_JOB_DELETE
}

data class KeplerJobCleanupResult(
    val bytesFreed: Long,
    val failedPaths: List<String>,
    val metadataWarning: String?
)

data class KeplerGalleryFrame(
    val index: Int,
    val fileName: String,
    val timestampNs: Long?,
    val enabled: Boolean,
    val excludedByUser: Boolean,
    val excludeReason: String?,
    val file: File?,
    val sharpnessScore: Float?,
    val motionScore: Float?,
    val exposureScore: Float?,
    val brightnessMean: Float?,
    val brightnessStdDev: Float?,
    val clippedShadowRatio: Float?,
    val clippedHighlightRatio: Float?,
    val qualityScore: Float?,
    val qualityLabel: String?,
    val recommendedExclude: Boolean,
    val qualityReason: String?
) {
    val included: Boolean get() = enabled && !excludedByUser
}

fun loadJobJson(jobDir: File): JSONObject =
    JSONObject(File(jobDir, JOB_JSON_FILE_NAME).readText())

fun saveJobJson(jobDir: File, job: JSONObject) {
    File(jobDir, JOB_JSON_FILE_NAME).writeText(job.toString(2))
}

fun setFrameExcluded(jobDir: File, frameIndex: Int, excluded: Boolean) {
    val job = loadJobJson(jobDir)
    val frames = job.getJSONArray("frames")
    var found = false
    repeat(frames.length()) { position ->
        val frame = frames.getJSONObject(position)
        if (frame.optInt("index", position) == frameIndex) {
            frame.put("enabled", !excluded)
                .put("excludedByUser", excluded)
                .put("excludeReason", if (excluded) "USER_EXCLUDED" else JSONObject.NULL)
            found = true
        }
    }
    require(found) { "Frame index $frameIndex not found." }
    job.put("updatedAt", System.currentTimeMillis())
    saveJobJson(jobDir, job)
}

fun getEnabledRawFrames(jobDir: File): List<JSONObject> {
    val job = loadJobJson(jobDir)
    val frames = job.optJSONArray("frames") ?: return emptyList()
    return buildList {
        repeat(frames.length()) { position ->
            val frame = frames.getJSONObject(position)
            val fileName = frame.optString("raw16File")
            if (
                frame.optBoolean("enabled", true) &&
                !frame.optBoolean("excludedByUser", false) &&
                fileName.isNotBlank() &&
                File(jobDir, fileName).isFile
            ) {
                add(frame)
            }
        }
    }
}

fun loadKeplerGalleryJobs(context: Context): List<KeplerGalleryJobSummary> {
    return keplerGalleryRoots(context).flatMap { root ->
        root.listFiles()
            ?.filter { it.isDirectory && matchesJobPrefix(root, it.name) }
            .orEmpty()
    }.map(::readKeplerGalleryJob).sortedByDescending { it.createdAt }
}

data class KeplerGalleryStorageSummary(
    val totalBytes: Long,
    val finalOutputBytes: Long,
    val sourceFrameBytes: Long,
    val intermediateBytes: Long,
    val debugDiagnosticBytes: Long,
    val cleanableBytes: Long,
    val rawBytes: Long,
    val yuvBytes: Long,
    val debugCacheBytes: Long,
    val jobCount: Int
)

fun summarizeKeplerGalleryStorage(jobs: List<KeplerGalleryJobSummary>): KeplerGalleryStorageSummary {
    return KeplerGalleryStorageSummary(
        totalBytes = jobs.sumOf { it.storage.totalJobBytes },
        finalOutputBytes = jobs.sumOf { it.storage.finalOutputBytes },
        sourceFrameBytes = jobs.sumOf { it.storage.rawFramesBytes },
        intermediateBytes = jobs.sumOf { it.storage.intermediateFilesBytes },
        debugDiagnosticBytes = jobs.sumOf { it.storage.debugFilesBytes + it.storage.previewFilesBytes },
        cleanableBytes = jobs.sumOf { it.storage.cleanableBytes },
        rawBytes = jobs.filter { it.jobType == "RAW_NIGHT_FUSION" }.sumOf { it.storage.totalJobBytes },
        yuvBytes = jobs.filter { it.jobType == "YUV_NIGHT_FUSION" }.sumOf { it.storage.totalJobBytes },
        debugCacheBytes = jobs.sumOf { it.storage.debugFilesBytes + it.storage.cacheFilesBytes },
        jobCount = jobs.size
    )
}

fun deleteKeplerGalleryJob(context: Context, jobDirectory: File): Result<Unit> = runCatching {
    val target = requireCleanupSafeJobDirectory(context, jobDirectory)
    require(target.isDirectory) { "Job directory no longer exists." }
    check(target.deleteRecursively()) { "Failed to delete ${target.name}." }
}

fun cleanupKeplerGalleryJob(
    context: Context,
    jobDirectory: File,
    cleanupType: KeplerJobCleanupType
): Result<KeplerJobCleanupResult> = runCatching {
    val target = requireCleanupSafeJobDirectory(context, jobDirectory)
    val before = folderSizeBytes(target)
    val job = File(target, JOB_JSON_FILE_NAME).takeIf { it.isFile }?.let { file ->
        runCatching { JSONObject(file.readText()) }.getOrNull()
    } ?: JSONObject()
    val finalFiles = finalFilesForCleanup(target, job)
    if (
        cleanupType != KeplerJobCleanupType.DEBUG_ONLY &&
        cleanupType != KeplerJobCleanupType.SOURCE_ONLY &&
        finalFiles.isEmpty()
    ) {
        throw IllegalStateException("Final output missing; cleanup refused.")
    }
    val filesToDelete = target.walkTopDown()
        .filter { it.isFile }
        .filter { file ->
            when (cleanupType) {
                KeplerJobCleanupType.DEBUG_ONLY -> isDeletableDebugFile(file, finalFiles)
                KeplerJobCleanupType.SOURCE_FRAMES_ONLY -> isDeletableSourceOrIntermediate(file, finalFiles)
                KeplerJobCleanupType.FINAL_ONLY ->
                    file.name != JOB_JSON_FILE_NAME && file.canonicalFile !in finalFiles.map { it.canonicalFile }.toSet()
                KeplerJobCleanupType.SOURCE_ONLY -> isDeletableForSourceOnly(file, finalFiles)
                KeplerJobCleanupType.FAILED_JOB_DELETE -> false
            }
        }
        .toList()
    val failed = mutableListOf<String>()
    filesToDelete.forEach { file ->
        if (file.exists() && !file.delete()) failed += file.absolutePath
    }
    val after = folderSizeBytes(target)
    val sourceAvailable = target.walkTopDown().any { it.isFile && isSourceFrame(it) }
    val debugAvailable = target.walkTopDown().any { it.isFile && isDebugFile(it, finalFiles.map { f -> f.name }.toSet()) }
    val finalOutputAvailable = if (cleanupType == KeplerJobCleanupType.SOURCE_ONLY) {
        false
    } else {
        finalFiles.any { it.isFile }
    }
    val metadataWarning = runCatching {
        val updated = computeKeplerJobStorage(target, job, finalFiles.firstOrNull())
        job.put("cleanupApplied", true)
            .put("cleanupType", cleanupType.name)
            .put("cleanupAt", System.currentTimeMillis())
            .put("bytesFreed", before - after)
            .put("remainingJobBytes", after)
            .put("sourceFramesAvailable", sourceAvailable)
            .put("finalOutputAvailable", finalOutputAvailable)
            .put("debugFilesAvailable", debugAvailable)
            .put("canReprocess", sourceAvailable)
            .put("galleryDisplayUnavailable", cleanupType == KeplerJobCleanupType.SOURCE_ONLY)
            .put("galleryVisible", cleanupType != KeplerJobCleanupType.SOURCE_ONLY)
        putStorageMetadata(job, updated)
        saveJobJson(target, job)
    }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
    KeplerJobCleanupResult(
        bytesFreed = before - after,
        failedPaths = failed,
        metadataWarning = metadataWarning
    )
}

private fun keplerGalleryRoots(context: Context): List<File> {
    val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return emptyList()
    return listOf(
        File(pictures, "KeplerRawFusion"),
        File(pictures, "KeplerYuvFusion"),
        File(pictures, "KeplerColorBurst")
    )
}

private fun cleanupSafeRoots(context: Context): List<File> {
    val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return emptyList()
    return listOf(
        File(pictures, "KeplerRawFusion"),
        File(pictures, "KeplerYuvFusion")
    )
}

private fun requireCleanupSafeJobDirectory(context: Context, jobDirectory: File): File {
    val target = jobDirectory.canonicalFile
    val allowed = cleanupSafeRoots(context).any { root ->
        target.parentFile == root.canonicalFile && matchesJobPrefix(root, target.name)
    }
    require(allowed) { "Refusing to modify directory outside RAW/YUV Kepler job roots." }
    return target
}

private fun matchesJobPrefix(root: File, name: String): Boolean = when (root.name) {
    "KeplerRawFusion" -> name.startsWith("KPL_RAW_FUSION_")
    "KeplerYuvFusion" -> name.startsWith("KPL_YUV_FUSION_")
    "KeplerColorBurst" -> name.startsWith("KPL_COLOR_BURST_")
    else -> false
}

private fun readKeplerGalleryJob(directory: File): KeplerGalleryJobSummary {
    val job = File(directory, JOB_JSON_FILE_NAME).takeIf { it.isFile }?.let { file ->
        runCatching { JSONObject(file.readText()) }.getOrNull()
    }
    val frames = job?.optJSONArray("frames").galleryFrames(directory)
        .orEmpty()
        .ifEmpty {
            directory.listFiles()
                ?.filter { it.isFile && isSourceFrame(it) }
                ?.sortedBy { it.name }
                ?.mapIndexed { index, file ->
                    KeplerGalleryFrame(
                        index, file.name, null, true, false, null, file,
                        null, null, null, null, null, null, null, null, null, false, null
                    )
                }
                .orEmpty()
        }
    val finalPreview = resolveFinalPreview(directory, job)
    val storage = computeKeplerJobStorage(directory, job, finalPreview)
    maybePersistStorageMetadata(directory, job, storage)
    val createdAt = job?.optLong("createdAt", 0L)
        ?.takeIf { it > 0L }
        ?: directory.lastModified()
    val width = firstPositive(job, "outputWidth", "rawWidth", "inputWidth", "width")
    val height = firstPositive(job, "outputHeight", "rawHeight", "inputHeight", "height")
    val rawType = job?.optString("jobType").orEmpty()
    val jobType = when {
        rawType == "RAW_NIGHT_FUSION" || directory.name.startsWith("KPL_RAW_FUSION_") ->
            "RAW_NIGHT_FUSION"
        rawType == "YUV_NIGHT_FUSION" || directory.name.startsWith("KPL_YUV_FUSION_") ->
            "YUV_NIGHT_FUSION"
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
        requestedFrames = job?.optInt("requestedFrames", frames.size) ?: frames.size,
        savedFrames = job?.optInt("savedFrames", frames.size) ?: frames.size,
        width = width,
        height = height,
        folderSizeBytes = storage.totalJobBytes,
        storage = storage,
        finalPreviewFile = finalPreview,
        finalExportExists = exportExists,
        frames = frames,
        metadata = job
    )
}

private fun computeKeplerJobStorage(
    directory: File,
    job: JSONObject?,
    finalPreview: File?
): KeplerJobStorageInfo {
    val files = directory.walkTopDown().filter { it.isFile }.toList()
    val finalNames = setOfNotNull(
        finalPreview?.name,
        job?.optString("galleryDisplayFile").orEmpty().ifBlank { null },
        job?.optString("finalNightFusionFile").orEmpty().ifBlank { null },
        job?.optString("finalFile").orEmpty().ifBlank { null },
        job?.optString("outputFile").orEmpty().ifBlank { null },
        job?.optString("nativePostprocessRgbaFile").orEmpty().ifBlank { null }
    )
    val finalBytes = files.filter { it.name in finalNames }.sumOf { it.length() }
    val rawBytes = files.filter { isSourceFrame(it) }.sumOf { it.length() }
    val debugBytes = files.filter { isDebugFile(it, finalNames) }.sumOf { it.length() }
    val previewBytes = files.filter { isPreviewFile(it, finalNames) }.sumOf { it.length() }
    val cacheBytes = files.filter { isCacheFile(it, finalNames) }.sumOf { it.length() }
    val intermediateBytes = files.filter { isIntermediateFile(it, finalNames) }.sumOf { it.length() }
    val totalBytes = files.sumOf { it.length() }
    val cleanableBytes = files
        .filter {
            it.name != JOB_JSON_FILE_NAME &&
                it.name !in finalNames &&
                (isSourceFrame(it) || isDebugFile(it, finalNames) || isPreviewFile(it, finalNames) ||
                    isCacheFile(it, finalNames) || isIntermediateFile(it, finalNames))
        }
        .sumOf { it.length() }
    return KeplerJobStorageInfo(
        totalJobBytes = totalBytes,
        totalJobSizeText = formatBytes(totalBytes),
        finalOutputBytes = finalBytes,
        finalOutputSizeText = formatBytes(finalBytes),
        rawFramesBytes = rawBytes,
        intermediateFilesBytes = intermediateBytes,
        debugFilesBytes = debugBytes,
        previewFilesBytes = previewBytes,
        cacheFilesBytes = cacheBytes,
        cleanableBytes = cleanableBytes,
        fileCount = files.size
    )
}

private fun maybePersistStorageMetadata(
    directory: File,
    job: JSONObject?,
    storage: KeplerJobStorageInfo
) {
    if (job == null) return
    if (
        job.optLong("totalJobBytes", -1L) == storage.totalJobBytes &&
        job.optInt("fileCount", -1) == storage.fileCount
    ) return
    runCatching {
        putStorageMetadata(job, storage)
            .put("storageScannedAt", System.currentTimeMillis())
        saveJobJson(directory, job)
    }
}

private fun putStorageMetadata(job: JSONObject, storage: KeplerJobStorageInfo): JSONObject {
    return job.put("totalJobBytes", storage.totalJobBytes)
        .put("totalJobSizeText", storage.totalJobSizeText)
        .put("finalOutputBytes", storage.finalOutputBytes)
        .put("finalOutputSizeText", storage.finalOutputSizeText)
        .put("rawFramesBytes", storage.rawFramesBytes)
        .put("intermediateFilesBytes", storage.intermediateFilesBytes)
        .put("debugFilesBytes", storage.debugFilesBytes)
        .put("previewFilesBytes", storage.previewFilesBytes)
        .put("cacheFilesBytes", storage.cacheFilesBytes)
        .put("cleanableBytes", storage.cleanableBytes)
        .put("fileCount", storage.fileCount)
}

private fun finalFilesForCleanup(directory: File, job: JSONObject?): Set<File> {
    val names = setOfNotNull(
        job?.optString("galleryDisplayFile").orEmpty().ifBlank { null },
        job?.optString("galleryThumbnailFile").orEmpty().ifBlank { null },
        job?.optString("finalNightFusionFile").orEmpty().ifBlank { null },
        job?.optString("finalFile").orEmpty().ifBlank { null },
        job?.optString("outputFile").orEmpty().ifBlank { null },
        job?.optString("nativePostprocessRgbaFile").orEmpty().ifBlank { null },
        resolveFinalPreview(directory, job)?.name
    )
    return names.mapNotNull { name -> File(directory, name).takeIf { it.isFile }?.canonicalFile }.toSet()
}

private fun isDeletableDebugFile(file: File, finalFiles: Set<File>): Boolean {
    if (file.name == JOB_JSON_FILE_NAME || file.canonicalFile in finalFiles) return false
    val name = file.name.lowercase()
    if (name == "raw_render_debug.json" || name == "fusion_debug.json" || name == "yuv_debug.json") return false
    return isDebugFile(file, finalFiles.map { it.name }.toSet()) ||
        name.contains("diagnostic") ||
        name.contains("contact") ||
        name.endsWith(".log")
}

private fun isDeletableSourceOrIntermediate(file: File, finalFiles: Set<File>): Boolean {
    if (file.name == JOB_JSON_FILE_NAME || file.canonicalFile in finalFiles) return false
    return isSourceFrame(file) || isIntermediateFile(file, finalFiles.map { it.name }.toSet())
}

private fun isDeletableForSourceOnly(file: File, finalFiles: Set<File>): Boolean {
    if (file.name == JOB_JSON_FILE_NAME || isRequiredSourceOnlyMetadata(file) || isSourceFrame(file)) return false
    val name = file.name.lowercase()
    return file.canonicalFile in finalFiles ||
        isDeletableDebugFile(file, finalFiles) ||
        isIntermediateFile(file, finalFiles.map { it.name }.toSet()) ||
        isCacheFile(file, finalFiles.map { it.name }.toSet()) ||
        isPreviewFile(file, finalFiles.map { it.name }.toSet()) ||
        name.startsWith("final") ||
        name.contains("thumbnail") ||
        name.contains("gallery") ||
        name.contains("temp") ||
        name.contains("tmp")
}

private fun isRequiredSourceOnlyMetadata(file: File): Boolean {
    val name = file.name.lowercase()
    return name == "raw_render_input_metadata.json" ||
        name == "gyro.csv" ||
        name == "rotation_vector.csv" ||
        name == "alignment.json" ||
        name == "capture_metadata.json"
}

private fun isDebugFile(file: File, finalNames: Set<String>): Boolean {
    val name = file.name.lowercase()
    if (file.name in finalNames) return false
    return name.contains("debug") ||
        name.contains("compare") ||
        name.endsWith(".json") ||
        name.endsWith(".log")
}

private fun isPreviewFile(file: File, finalNames: Set<String>): Boolean {
    val name = file.name.lowercase()
    if (file.name in finalNames) return false
    return name.contains("preview") ||
        name.contains("reference") ||
        name.endsWith(".jpg") ||
        name.endsWith(".jpeg") ||
        name.endsWith(".png")
}

private fun isCacheFile(file: File, finalNames: Set<String>): Boolean {
    val name = file.name.lowercase()
    if (file.name in finalNames) return false
    return name.endsWith(".rgba") || name.endsWith(".rgb") || name.endsWith(".bin")
}

private fun isIntermediateFile(file: File, finalNames: Set<String>): Boolean {
    val name = file.name.lowercase()
    if (file.name in finalNames) return false
    return name.startsWith("merged_raw") ||
        name.contains("merged_yuv") ||
        name.contains("intermediate") ||
        name.contains("linear") ||
        name.endsWith(".yuv")
}

private fun JSONArray?.galleryFrames(directory: File): List<KeplerGalleryFrame> {
    if (this == null) return emptyList()
    return buildList {
        repeat(length()) { position ->
            val frame = optJSONObject(position) ?: return@repeat
            val fileName = frame.optString("raw16File")
                .ifBlank { frame.optString("file") }
                .ifBlank { frame.optString("dngFile") }
            val file = fileName.takeIf { it.isNotBlank() }?.let { File(directory, it) }
            add(
                KeplerGalleryFrame(
                    index = frame.optInt("index", position),
                    fileName = fileName.ifBlank { "frame_$position" },
                    timestampNs = frame.optLong("timestampNs", 0L).takeIf { it > 0L },
                    enabled = frame.optBoolean("enabled", true),
                    excludedByUser = frame.optBoolean("excludedByUser", false),
                    excludeReason = frame.optString("excludeReason")
                        .takeIf { it.isNotBlank() && it != "null" },
                    file = file?.takeIf { it.isFile },
                    sharpnessScore = frame.optionalFloat("sharpnessScore"),
                    motionScore = frame.optionalFloat("motionScore"),
                    exposureScore = frame.optionalFloat("exposureScore"),
                    brightnessMean = frame.optionalFloat("brightnessMean"),
                    brightnessStdDev = frame.optionalFloat("brightnessStdDev"),
                    clippedShadowRatio = frame.optionalFloat("clippedShadowRatio"),
                    clippedHighlightRatio = frame.optionalFloat("clippedHighlightRatio"),
                    qualityScore = frame.optionalFloat("qualityScore"),
                    qualityLabel = frame.optString("qualityLabel")
                        .takeIf { it.isNotBlank() && it != "null" },
                    recommendedExclude = frame.optBoolean("recommendedExclude", false),
                    qualityReason = frame.optString("qualityReason")
                        .takeIf { it.isNotBlank() && it != "null" }
                )
            )
        }
    }
}

private fun JSONObject.optionalFloat(key: String): Float? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key, Double.NaN).takeUnless { it.isNaN() }?.toFloat()
}

private fun resolveFinalPreview(directory: File, job: JSONObject?): File? {
    val explicit = job?.optString("galleryDisplayFile").orEmpty()
    File(directory, explicit).takeIf { explicit.isNotBlank() && it.isFile && !isDebugPreviewFinalBlocked(it.name) }
        ?.let { return it }
    val keys = listOf(
        "nativePostprocessRgbaFile",
        "finalNightFusionFile",
        "finalFile",
        "outputFile"
    )
    keys.forEach { key ->
        val name = job?.optString(key).orEmpty()
        File(directory, name).takeIf {
            name.isNotBlank() &&
                it.isFile &&
                !isDebugPreviewFinalBlocked(it.name) &&
                (it.extension.equals("png", true) || it.extension.equals("jpg", true) || it.extension.equals("jpeg", true) || it.extension.equals("rgba", true))
        }
            ?.let { return it }
    }
    return directory.listFiles()
        ?.filter {
            it.isFile &&
                it.extension.equals("png", true) &&
                !isSourceFrame(it) &&
                !isDebugPreviewFinalBlocked(it.name)
        }
        ?.maxByOrNull { it.lastModified() }
}

private fun isDebugPreviewFinalBlocked(name: String): Boolean {
    val lower = name.lowercase()
    return lower in setOf(
        "raw_reference_preview.png",
        "raw_fused_classic_v1_preview.png",
        "raw_compare_reference_vs_fused.png",
        "reference_frame.png",
        "fused_classic_yuv_v1.png",
        "compare_reference_vs_fused.png",
        "yuv_reference_preview.png",
        "yuv_fused_preview.png",
        "yuv_compare_reference_vs_fused.png"
    )
}

private fun firstPositive(job: JSONObject?, vararg keys: String): Int? {
    return keys.firstNotNullOfOrNull { key -> job?.optInt(key, 0)?.takeIf { it > 0 } }
}

private fun isSourceFrame(file: File): Boolean {
    val name = file.name.lowercase()
    return name.startsWith("frame_") &&
        (name.endsWith(".png") || name.endsWith(".raw16") || name.endsWith(".dng") ||
            name.endsWith(".yuv") || name.endsWith(".nv21") || name.endsWith(".yuv420"))
}
