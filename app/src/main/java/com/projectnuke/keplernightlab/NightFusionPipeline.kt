package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun captureProcessExportNightFusion(
    context: Context,
    cameraId: String,
    frameCount: Int,
    resolutionMode: CaptureResolutionMode,
    zoomRatio: Float,
    focusAeState: FocusAeState = FocusAeState(),
    cleanupPolicy: CacheCleanupPolicy = CacheCleanupPolicy.DELETE_SOURCE_FRAMES_AFTER_VERIFIED_EXPORT,
    frameCountMode: FrameCountMode = FrameCountMode.AUTO,
    autoMinFrames: Int = 4,
    autoMaxFrames: Int = 8,
    manualFrames: Int = 4,
    framePlanReason: String = "Default",
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    fun post(message: String) {
        mainHandler.post { onStatus(message) }
    }

    post("Capturing $frameCount frames...")
    captureYuvBurstColorWithMotion(
        context = context,
        cameraId = cameraId,
        frameCount = frameCount,
        resolutionMode = resolutionMode,
        zoomRatio = zoomRatio,
        focusAeState = focusAeState,
        frameCountMode = frameCountMode,
        autoMinFrames = autoMinFrames,
        autoMaxFrames = autoMaxFrames,
        manualFrames = manualFrames,
        framePlanReason = framePlanReason,
        onComplete = { jobDir ->
            val workerThread = HandlerThread("KeplerCaptureProcessExportThread").apply { start() }
            Handler(workerThread.looper).post {
                try {
                    post("Processing Night Fusion...")
                    val finalFile = processNightFusionJobV02Sync(jobDir) { post(it) }

                    post("Exporting HEIF...")
                    val bitmap = BitmapFactory.decodeFile(finalFile.absolutePath)
                        ?: error("Could not decode final Night Fusion image.")
                    val displayNameBase = "Kepler_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
                    val export = exportNightFusionBitmapToGallery(
                        context = context,
                        bitmap = bitmap,
                        displayNameBase = displayNameBase,
                        requestedFormat = OutputFormat.HEIF
                    )
                    bitmap.recycle()

                    if (!export.success || export.uriString.isNullOrBlank()) {
                        updateNightFusionExportFailure(jobDir, export.errorMessage ?: "Unknown export failure")
                        post("Export failed; keeping cache. ${export.errorMessage}")
                        return@post
                    }

                    post("Verifying gallery output...")
                    val verified = verifyGalleryExport(context, export.uriString)
                    updateNightFusionExportMetadata(jobDir, export, verified)

                    if (!verified) {
                        updateNightFusionExportFailure(jobDir, "Export verification failed")
                        post("Export verification failed; keeping source frames.")
                        return@post
                    }

                    val cleanup = cleanupNightFusionJobAfterVerifiedExport(
                        jobDir = jobDir,
                        policy = cleanupPolicy,
                        onStatus = { post(it) }
                    )
                    val album = "Pictures/Kepler/${export.displayName}"
                    if (export.fallbackUsed) {
                        post("HEIF failed, saved ${export.formatUsed.label} to Gallery: $album\nCleanup complete. Deleted ${cleanup.deletedFiles} files.")
                    } else {
                        post("Saved to Gallery: $album\nCleanup complete. Deleted ${cleanup.deletedFiles} files.")
                    }
                } catch (e: Exception) {
                    post("Night Fusion pipeline failed; keeping cache.\n${e.stackTraceToString()}")
                } finally {
                    workerThread.quitSafely()
                }
            }
        },
        onError = { error ->
            post("Capture failed; keeping cache.\n$error")
        },
        onStatus = { message ->
            post(message)
        }
    )
}

fun cleanupNightFusionJobAfterVerifiedExport(
    jobDir: File,
    policy: CacheCleanupPolicy,
    onStatus: (String) -> Unit
): CleanupResult {
    val jobFile = File(jobDir, "job.json")
    if (!jobFile.exists()) return CleanupResult(0, 0L, emptyList())

    val job = JSONObject(jobFile.readText())
    if (!job.optBoolean("exportVerified", false)) {
        onStatus("Cleanup skipped: export not verified.")
        return CleanupResult(0, 0L, jobDir.listFiles()?.map { it.name }.orEmpty())
    }
    if (policy == CacheCleanupPolicy.KEEP_ALL) {
        updateCleanupMetadata(jobFile, policy, "KEPT_ALL", 0, 0L, false)
        return CleanupResult(0, 0L, jobDir.listFiles()?.map { it.name }.orEmpty())
    }

    val deleteNames = mutableSetOf<String>()
    if (
        policy == CacheCleanupPolicy.DELETE_SOURCE_FRAMES_AFTER_VERIFIED_EXPORT ||
        policy == CacheCleanupPolicy.DELETE_INTERMEDIATES_AFTER_VERIFIED_EXPORT ||
        policy == CacheCleanupPolicy.DELETE_ALL_CACHE_AFTER_VERIFIED_EXPORT_KEEP_JOB
    ) {
        jobDir.listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("frame_\\d+_color\\.png")) }
            ?.forEach { deleteNames.add(it.name) }
    }
    if (
        policy == CacheCleanupPolicy.DELETE_INTERMEDIATES_AFTER_VERIFIED_EXPORT ||
        policy == CacheCleanupPolicy.DELETE_ALL_CACHE_AFTER_VERIFIED_EXPORT_KEEP_JOB
    ) {
        deleteNames.add("average_color_rotated.png")
        deleteNames.add("denoise_color.png")
    }
    if (policy == CacheCleanupPolicy.DELETE_ALL_CACHE_AFTER_VERIFIED_EXPORT_KEEP_JOB) {
        deleteNames.add("sharpened_night_fusion.png")
    }

    var deleted = 0
    var freed = 0L
    deleteNames.forEach { name ->
        val file = File(jobDir, name)
        if (file.exists() && file.canonicalPath.startsWith(jobDir.canonicalPath) && file.name != "job.json") {
            val size = file.length()
            if (file.delete()) {
                deleted++
                freed += size
            }
        }
    }

    val sourceDeleted = jobDir.listFiles()?.none { it.name.matches(Regex("frame_\\d+_color\\.png")) } ?: true
    val status = if (sourceDeleted) "SOURCE_FRAMES_DELETED" else "PARTIAL_CLEANUP"
    updateCleanupMetadata(jobFile, policy, status, deleted, freed, sourceDeleted)

    return CleanupResult(
        deletedFiles = deleted,
        freedBytes = freed,
        keptFiles = jobDir.listFiles()?.map { it.name }.orEmpty()
    )
}

private fun updateNightFusionExportMetadata(
    jobDir: File,
    export: GalleryExportResult,
    verified: Boolean
) {
    val jobFile = File(jobDir, "job.json")
    val job = JSONObject(jobFile.readText())
    job.put("exportStatus", if (verified) "EXPORTED" else "EXPORT_UNVERIFIED")
        .put("exportVerified", verified)
        .put("exportUri", export.uriString ?: JSONObject.NULL)
        .put("exportDisplayName", export.displayName ?: JSONObject.NULL)
        .put("exportMimeType", export.mimeType ?: JSONObject.NULL)
        .put("exportFormatRequested", OutputFormat.HEIF.label)
        .put("exportFormatUsed", export.formatUsed.label)
        .put("exportFallbackUsed", export.fallbackUsed)
        .put("exportFileSizeBytes", export.fileSizeBytes)
        .put("exportedAt", System.currentTimeMillis())
    jobFile.writeText(job.toString(2))
}

private fun updateNightFusionExportFailure(jobDir: File, error: String) {
    val jobFile = File(jobDir, "job.json")
    val job = JSONObject(jobFile.readText())
    job.put("processStatus", "EXPORT_FAILED_KEEPING_CACHE")
        .put("exportStatus", "FAILED")
        .put("exportVerified", false)
        .put("exportError", error)
        .put("cleanupStatus", "SKIPPED")
        .put("exportedAt", System.currentTimeMillis())
    jobFile.writeText(job.toString(2))
}

private fun updateCleanupMetadata(
    jobFile: File,
    policy: CacheCleanupPolicy,
    cleanupStatus: String,
    deletedFiles: Int,
    freedBytes: Long,
    sourceFramesDeleted: Boolean
) {
    val job = JSONObject(jobFile.readText())
    job.put("cleanupStatus", cleanupStatus)
        .put("cleanupDeletedFiles", deletedFiles)
        .put("cleanupFreedBytes", freedBytes)
        .put("cleanupPolicy", policy.name)
        .put("sourceFramesDeleted", sourceFramesDeleted)
        .put("cleanedAt", System.currentTimeMillis())
    jobFile.writeText(job.toString(2))
}
