package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.concurrent.CancellationException
import java.util.Date
import java.util.Locale

fun captureProcessExportNightFusion(
    context: Context,
    cameraId: String,
    frameCount: Int,
    resolutionMode: CaptureResolutionMode,
    finalOutputFormat: FinalOutputFormat,
    zoomRatio: Float,
    requestedUiZoomRatio: Float,
    physicalCameraId: String? = null,
    zoomRoute: ThreeXSourceMode = ThreeXSourceMode.OPTICAL,
    previewRoute: String? = null,
    routeFallbackReason: String? = null,
    focusAeState: FocusAeState = FocusAeState(),
    cleanupPolicy: CacheCleanupPolicy = CacheCleanupPolicy.DELETE_SOURCE_FRAMES_AFTER_VERIFIED_EXPORT,
    frameCountMode: FrameCountMode = FrameCountMode.AUTO,
    autoMinFrames: Int = 4,
    autoMaxFrames: Int = 8,
    manualFrames: Int = 4,
    framePlanReason: String = "Default",
    captureCancellationHandle: KeplerCaptureCancellationHandle = NoOpKeplerCaptureCancellationHandle,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    fun post(message: String) {
        mainHandler.post { onStatus(message) }
    }

    cancellation.throwIfCancelled()
    post("YUV capture: saved 0/$frameCount")
    captureYuvBurstColorWithMotion(
        context = context,
        cameraId = cameraId,
        frameCount = frameCount,
        resolutionMode = resolutionMode,
        zoomRatio = zoomRatio,
        requestedUiZoomRatio = requestedUiZoomRatio,
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
        captureCancellationHandle = captureCancellationHandle,
        onComplete = { jobDir ->
            try {
                cancellation.throwIfCancelled()
            } catch (_: CancellationException) {
                post("PIPELINE_CANCELLED: Capture timed out; background processing stopped.")
                return@captureYuvBurstColorWithMotion
            }
            val workerThread = HandlerThread("KeplerCaptureProcessExportThread").apply { start() }
            Handler(workerThread.looper).post {
                try {
                    cancellation.throwIfCancelled()
                    post("Processing Night Fusion...")
                    cancellation.throwIfCancelled()
                    val finalFile = processNightFusionJobV02Sync(
                        jobDir,
                        onStatus = { post(it) },
                        cancellation = cancellation
                    )
                    cancellation.throwIfCancelled()

                    val requestedOutputFormat = requestedOutputFormatForSetting(finalOutputFormat)
                    cancellation.throwIfCancelled()
                    post("Exporting ${requestedOutputFormat.label}...")
                    cancellation.throwIfCancelled()
                    val bitmap = BitmapFactory.decodeFile(finalFile.absolutePath)
                        ?: error("Could not decode final Night Fusion image.")
                    val displayNameBase = "Kepler_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
                    val export = try {
                        cancellation.throwIfCancelled()
                        exportNightFusionBitmapToGallery(
                            context = context,
                            bitmap = bitmap,
                            displayNameBase = displayNameBase,
                            requestedFormat = requestedOutputFormat,
                            cancellation = cancellation
                        )
                    } finally {
                        bitmap.recycle()
                    }

                    if (!export.success || export.uriString.isNullOrBlank()) {
                        updateExportFailure(
                            jobDir = jobDir,
                            error = export.errorMessage ?: "Unknown export failure",
                            finalOutputFormat = finalOutputFormat,
                            rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar,
                            export = export
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
                        ,postExportCancellationRequested = cancellation.isCancelled,
                        postExportWorkSkipped = cancellation.isCancelled
                    )

                    if (!verified) {
                        updateExportFailure(
                            jobDir = jobDir,
                            error = "Export verification failed",
                            finalOutputFormat = finalOutputFormat,
                            rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar
                            ,export = export
                        )
                        post("PIPELINE_FAILED: Export verification failed; keeping source frames.")
                        return@post
                    }

                    if (cancellation.isCancelled) {
                        updateExportMetadata(jobDir, export, true, finalOutputFormat,
                            rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar,
                            postExportCancellationRequested = true, postExportWorkSkipped = true)
                        post("PIPELINE_COMPLETE_PARTIAL: Image was saved, but optional post-export work was cancelled. Cache was kept.")
                        return@post
                    }
                    post("Cleanup...")
                    val cleanup = cleanupNightFusionJobAfterVerifiedExport(
                        jobDir = jobDir,
                        policy = cleanupPolicy,
                        cancellation = cancellation,
                        onStatus = { post(it) }
                    )
                    if (cancellation.isCancelled) {
                        updateExportMetadata(
                            jobDir = jobDir,
                            export = export,
                            verified = true,
                            finalOutputFormat = finalOutputFormat,
                            rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar,
                            postExportCancellationRequested = true,
                            postExportWorkSkipped = true
                        )
                        post("PIPELINE_COMPLETE_PARTIAL: Image was saved, but optional post-export work was cancelled. Cache was kept.")
                        return@post
                    }
                    val album = "Pictures/Kepler/${export.displayName}"
                    if (finalOutputFormat.shouldExportRawSidecar) {
                        post("RAW sidecar unavailable for YUV pipeline.")
                    }
                    if (export.fallbackUsed && requestedOutputFormat == OutputFormat.HEIF) {
                        post("PIPELINE_COMPLETE: HEIF failed, saved ${export.formatUsed.label} to Gallery: $album\nCleanup complete. Deleted ${cleanup.deletedFiles} files.")
                    } else {
                        post("PIPELINE_COMPLETE: Saved ${export.formatUsed.label} to Gallery: $album\nCleanup complete. Deleted ${cleanup.deletedFiles} files.")
                    }
                } catch (_: CancellationException) {
                    post("PIPELINE_CANCELLED: Capture timed out; background processing stopped.")
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

internal fun reprocessYuvJob(
    context: Context,
    jobDir: File,
    finalOutputFormat: FinalOutputFormat,
    selectedFrameIndices: Set<Int>? = null,
    fusionParams: ClassicYuvFusionParams? = null,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    onStatus: (String) -> Unit
): ReprocessWorkerRun {
    val mainHandler = Handler(Looper.getMainLooper())
    fun post(message: String) = mainHandler.post { onStatus(message) }
    val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
    val workerThread = HandlerThread("KeplerYuvReprocessThread").apply { start() }
    Handler(workerThread.looper).post {
        val jobFile = File(jobDir, JOB_JSON_FILE_NAME)
        var totalFrames = 0
        var enabledFrames = 0
        var terminalResult: Result<Unit> = Result.failure(IllegalStateException("YUV reprocess did not reach a terminal state."))
        var publicExportCommitted = false
        var committedExport: GalleryExportResult? = null
        var terminalDisposition = ReprocessTerminalDisposition.UNCOMMITTED_FAILURE
        var finalOutputFile: File? = null
        try {
            cancellation.throwIfCancelled()
            if (selectedFrameIndices != null) {
                applyExplicitYuvFrameSelection(jobDir, selectedFrameIndices)
            }
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
                post("Not enough enabled YUV frames to reprocess")
                terminalResult = Result.failure(IllegalStateException("Not enough enabled YUV frames to reprocess"))
                return@post
            }

            post("YUV reprocess: loading enabled frames...")
            post("YUV reprocess: using $enabledFrames/$totalFrames frames...")
            val finalFile = processNightFusionJobV02Sync(
                jobDir = jobDir,
                onStatus = { post(it) },
                requestedParams = fusionParams,
                cancellation = cancellation,
                metadataPolicy = ReprocessMetadataPolicy.REPROCESS_PROGRESS_ONLY
            )
            finalOutputFile = finalFile.takeIf { it.isFile && it.length() > 0L }
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
                    requestedFormat = requestedFormat,
                    cancellation = cancellation
                )
            } finally {
                bitmap.recycle()
            }
            if (!export.success || export.uriString.isNullOrBlank()) {
                error(export.errorMessage ?: "YUV export failed")
            }
            publicExportCommitted = true
            committedExport = export
            val verified = verifyGalleryExport(context, export.uriString)
            if (!verified) {
                terminalDisposition = ReprocessTerminalDisposition.COMMITTED_PARTIAL
                error("YUV export verification failed")
            }
            post(
                "PIPELINE_COMPLETE: YUV reprocess saved ${export.formatUsed.label}; " +
                    "used $enabledFrames/$totalFrames frames; cache kept."
            )
            terminalResult = Result.success(Unit)
            terminalDisposition = ReprocessTerminalDisposition.VERIFIED_SUCCESS
        } catch (_: kotlinx.coroutines.CancellationException) {
            post("PIPELINE_CANCELLED: YUV reprocess cancelled; source frames kept.")
            terminalResult = Result.failure(IllegalStateException("YUV reprocess cancelled"))
            terminalDisposition = ReprocessTerminalDisposition.CANCELLED
        } catch (oom: OutOfMemoryError) {
            post("PIPELINE_FAILED: YUV reprocess failed; cache kept. out of memory")
            terminalResult = Result.failure(oom)
        } catch (e: Exception) {
            post("PIPELINE_FAILED: YUV reprocess failed; cache kept. ${e.message}")
            terminalResult = Result.failure(e)
        } finally {
            workerThread.quitSafely()
            terminal.complete(
                ReprocessWorkerOutcome(
                    result = terminalResult,
                    publicExportCommitted = publicExportCommitted,
                    export = committedExport,
                    finalOutputFile = finalOutputFile,
                    previewFile = finalOutputFile,
                    bytesWritten = finalOutputFile?.length() ?: 0L,
                    disposition = terminalDisposition,
                    terminalError = terminalResult.exceptionOrNull()
                )
            )
        }
    }
    return ReprocessWorkerRun(
        terminal = terminal,
        cancel = { (cancellation as? KeplerPipelineCancellationToken)?.cancel() }
    )
}

private fun applyExplicitYuvFrameSelection(jobDir: File, selectedFrameIndices: Set<Int>) {
    KeplerJobMetadata.update(jobDir) { job ->
    val frames = job.optJSONArray("frames") ?: return@update
    repeat(frames.length()) { position ->
        val frame = frames.optJSONObject(position) ?: return@repeat
        val index = frame.optInt("index", position)
        val included = index in selectedFrameIndices
        frame.put("enabled", included)
            .put("excludedByUser", !included)
            .put("excludeReason", if (included) JSONObject.NULL else "FRAME_SELECTION")
    }
    job.put("includedFrameIndices", org.json.JSONArray(selectedFrameIndices.sorted()))
        .put("frameSelectionUpdatedAt", System.currentTimeMillis())
    }
}

fun cleanupNightFusionJobAfterVerifiedExport(
    jobDir: File,
    policy: CacheCleanupPolicy,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
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
    var cancelledDuringCleanup = cancellation.isCancelled
    deleteNames.forEach { name ->
        if (cancellation.isCancelled) {
            cancelledDuringCleanup = true
            return@forEach
        }
        val file = File(jobDir, name)
        if (file.exists() && file.canonicalPath.startsWith(jobDir.canonicalPath) && file.name != "job.json") {
            val size = file.length()
            if (file.delete()) {
                deleted++
                freed += size
            }
        }
        if (cancellation.isCancelled) {
            cancelledDuringCleanup = true
        }
    }

    val sourceDeleted = jobDir.listFiles()?.none { it.name.matches(Regex("frame_\\d+_color\\.png")) } ?: true
    val status = when {
        cancelledDuringCleanup -> "PARTIAL_CLEANUP"
        sourceDeleted -> "SOURCE_FRAMES_DELETED"
        else -> "PARTIAL_CLEANUP"
    }
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
    saveJobJson(jobFile.parentFile ?: error("Job directory missing"), job)
}
