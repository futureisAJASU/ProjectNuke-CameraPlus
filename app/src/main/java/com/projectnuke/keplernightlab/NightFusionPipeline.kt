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
    finalOutputFormat: FinalOutputFormat,
    zoomRatio: Float,
    physicalCameraId: String? = null,
    zoomRoute: ThreeXSourceMode = ThreeXSourceMode.AUTO,
    previewRoute: String? = null,
    routeFallbackReason: String? = null,
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

    post("YUV capture: saved 0/$frameCount")
    captureYuvBurstColorWithMotion(
        context = context,
        cameraId = cameraId,
        frameCount = frameCount,
        resolutionMode = resolutionMode,
        zoomRatio = zoomRatio,
        physicalCameraId = physicalCameraId,
        zoomRoute = zoomRoute,
        previewRoute = previewRoute,
        routeFallbackReason = routeFallbackReason,
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

                    val requestedOutputFormat = requestedOutputFormatForSetting(finalOutputFormat)
                    post("Exporting ${requestedOutputFormat.label}...")
                    val bitmap = BitmapFactory.decodeFile(finalFile.absolutePath)
                        ?: error("Could not decode final Night Fusion image.")
                    val displayNameBase = "Kepler_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
                    val export = exportNightFusionBitmapToGallery(
                        context = context,
                        bitmap = bitmap,
                        displayNameBase = displayNameBase,
                        requestedFormat = requestedOutputFormat
                    )
                    bitmap.recycle()

                    if (!export.success || export.uriString.isNullOrBlank()) {
                        updateExportFailure(
                            jobDir = jobDir,
                            error = export.errorMessage ?: "Unknown export failure",
                            finalOutputFormat = finalOutputFormat,
                            rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar
                        )
                        post("PIPELINE_FAILED: Export failed; keeping cache. ${export.errorMessage}")
                        return@post
                    }

                    post("Verifying gallery output...")
                    val verified = verifyGalleryExport(context, export.uriString)
                    updateExportMetadata(
                        jobDir = jobDir,
                        export = export,
                        verified = verified,
                        finalOutputFormat = finalOutputFormat,
                        rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar
                    )

                    if (!verified) {
                        updateExportFailure(
                            jobDir = jobDir,
                            error = "Export verification failed",
                            finalOutputFormat = finalOutputFormat,
                            rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar
                        )
                        post("PIPELINE_FAILED: Export verification failed; keeping source frames.")
                        return@post
                    }

                    post("Cleanup...")
                    val cleanup = cleanupNightFusionJobAfterVerifiedExport(
                        jobDir = jobDir,
                        policy = cleanupPolicy,
                        onStatus = { post(it) }
                    )
                    val album = "Pictures/Kepler/${export.displayName}"
                    if (finalOutputFormat.shouldExportRawSidecar) {
                        post("RAW sidecar unavailable for YUV pipeline.")
                    }
                    if (export.fallbackUsed && requestedOutputFormat == OutputFormat.HEIF) {
                        post("PIPELINE_COMPLETE: HEIF failed, saved ${export.formatUsed.label} to Gallery: $album\nCleanup complete. Deleted ${cleanup.deletedFiles} files.")
                    } else {
                        post("PIPELINE_COMPLETE: Saved ${export.formatUsed.label} to Gallery: $album\nCleanup complete. Deleted ${cleanup.deletedFiles} files.")
                    }
                } catch (e: Exception) {
                    post("PIPELINE_FAILED: Night Fusion pipeline failed; keeping cache.\n${e.stackTraceToString()}")
                } finally {
                    workerThread.quitSafely()
                }
            }
        },
        onError = { error ->
            post("PIPELINE_FAILED: Capture failed; keeping cache.\n$error")
        },
        onStatus = { message ->
            post(message)
        }
    )
}

fun reprocessYuvJob(
    context: Context,
    jobDir: File,
    finalOutputFormat: FinalOutputFormat,
    fusionParams: ClassicYuvFusionParams? = null,
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    fun post(message: String) = mainHandler.post { onStatus(message) }
    val workerThread = HandlerThread("KeplerYuvReprocessThread").apply { start() }
    Handler(workerThread.looper).post {
        val jobFile = File(jobDir, JOB_JSON_FILE_NAME)
        var totalFrames = 0
        var enabledFrames = 0
        try {
            val initialJob = JSONObject(jobFile.readText())
            val frames = initialJob.optJSONArray("frames")
            totalFrames = frames?.length() ?: 0
            repeat(totalFrames) { index ->
                val frame = frames?.optJSONObject(index) ?: return@repeat
                val fileName = frame.optString("file")
                if (
                    frame.optBoolean("enabled", true) &&
                    !frame.optBoolean("excludedByUser", false) &&
                    fileName.isNotBlank() &&
                    File(jobDir, fileName).isFile
                ) {
                    enabledFrames++
                }
            }
            if (enabledFrames < 2) {
                updateYuvReprocessHistory(
                    jobDir, enabledFrames, totalFrames - enabledFrames,
                    "FAILED_NOT_ENOUGH_ENABLED_FRAMES"
                )
                post("Not enough enabled YUV frames to reprocess")
                return@post
            }

            post("YUV reprocess: loading enabled frames...")
            post("YUV reprocess: using $enabledFrames/$totalFrames frames...")
            val finalFile = processClassicYuvFusionJob(
                jobDir = jobDir,
                onStatus = { post(it) },
                requestedParams = fusionParams
            )
            post("YUV reprocess: exporting...")
            val bitmap = BitmapFactory.decodeFile(finalFile.absolutePath)
                ?: error("Could not decode reprocessed YUV image.")
            val requestedFormat = requestedOutputFormatForSetting(finalOutputFormat)
            val export = try {
                exportNightFusionBitmapToGallery(
                    context = context,
                    bitmap = bitmap,
                    displayNameBase = "Kepler_YUV_REPROCESS_${
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    }",
                    requestedFormat = requestedFormat
                )
            } finally {
                bitmap.recycle()
            }
            if (!export.success || export.uriString.isNullOrBlank()) {
                error(export.errorMessage ?: "YUV export failed")
            }
            val verified = verifyGalleryExport(context, export.uriString)
            if (!verified) error("YUV export verification failed")
            updateExportMetadata(
                jobDir = jobDir,
                export = export,
                verified = true,
                finalOutputFormat = finalOutputFormat,
                rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar
            )
            updateYuvReprocessHistory(
                jobDir, enabledFrames, totalFrames - enabledFrames, "SUCCESS"
            )
            post(
                "PIPELINE_COMPLETE: YUV reprocess saved ${export.formatUsed.label}; " +
                    "used $enabledFrames/$totalFrames frames; cache kept."
            )
        } catch (e: Exception) {
            runCatching {
                updateYuvReprocessHistory(
                    jobDir,
                    enabledFrames,
                    (totalFrames - enabledFrames).coerceAtLeast(0),
                    "FAILED: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
            post("PIPELINE_FAILED: YUV reprocess failed; cache kept. ${e.message}")
        } finally {
            workerThread.quitSafely()
        }
    }
}

private fun updateYuvReprocessHistory(
    jobDir: File,
    usedFrameCount: Int,
    excludedFrameCount: Int,
    status: String
) {
    val job = loadJobJson(jobDir)
    job.put("usedFrameCount", usedFrameCount)
        .put("excludedFrameCount", excludedFrameCount)
        .put("reprocessCount", job.optInt("reprocessCount", 0) + 1)
        .put("lastReprocessedAt", System.currentTimeMillis())
        .put("lastReprocessUsedFrameCount", usedFrameCount)
        .put("lastReprocessExcludedFrameCount", excludedFrameCount)
        .put("lastReprocessStatus", status)
    saveJobJson(jobDir, job)
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
