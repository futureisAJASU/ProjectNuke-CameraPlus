package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.concurrent.CancellationException
import java.util.Date
import java.util.Locale

private data class RawFusionExportBitmap(
    val bitmap: Bitmap,
    val source: String,
    val nativeRgbaDirect: Boolean
)

private fun RawFusionProcessResult.validNativeRgbaFile(): File? {
    val file = nativeRgbaFile ?: return null
    if (nativeRgbaWidth <= 0 || nativeRgbaHeight <= 0 || !file.exists()) return null
    val expectedBytes = nativeRgbaWidth.toLong() * nativeRgbaHeight.toLong() * 4L
    return file.takeIf { it.length() == expectedBytes }
}

private fun RawFusionProcessResult.hasExportableBitmapSource(): Boolean {
    return validNativeRgbaFile() != null ||
        finalPngFile?.let { it.exists() && it.length() > 0L } == true
}

private fun RawFusionProcessResult.loadExportBitmap(): RawFusionExportBitmap {
    val rgbaFile = validNativeRgbaFile()
    if (rgbaFile != null) {
        try {
            return RawFusionExportBitmap(
                bitmap = loadRawRgbaBitmap(rgbaFile, nativeRgbaWidth, nativeRgbaHeight),
                source = "native_rgba_direct",
                nativeRgbaDirect = true
            )
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (nativeError: Exception) {
            val png = finalPngFile?.takeIf { it.exists() && it.length() > 0L }
                ?: throw IllegalStateException(
                    "Native RGBA bitmap load failed and no final PNG fallback exists",
                    nativeError
                )
            val bitmap = BitmapFactory.decodeFile(png.absolutePath)
                ?: throw IllegalStateException(
                    "Final RAW fusion PNG fallback decode failed",
                    nativeError
                )
            return RawFusionExportBitmap(bitmap, "final_png_decode", false)
        }
    }
    val png = finalPngFile?.takeIf { it.exists() && it.length() > 0L }
        ?: error("No exportable RAW fusion bitmap source")
    val bitmap = BitmapFactory.decodeFile(png.absolutePath)
        ?: error("Final RAW fusion PNG decode failed")
    return RawFusionExportBitmap(bitmap, "final_png_decode", false)
}

fun captureProcessExportRawNightFusion(
    context: Context,
    cameraId: String,
    frameCount: Int,
    resolutionMode: CaptureResolutionMode,
    resolutionPlan: ResolutionCapturePlan? = null,
    finalOutputFormat: FinalOutputFormat,
    zoomRatio: Float,
    requestedUiZoomRatio: Float,
    physicalCameraId: String? = null,
    zoomRoute: ThreeXSourceMode = ThreeXSourceMode.OPTICAL,
    previewRoute: String? = null,
    routeFallbackReason: String? = null,
    focusAeState: FocusAeState = FocusAeState(),
    rawSpeedMode: RawSpeedMode = RawSpeedMode.BALANCED,
    captureCancellationHandle: KeplerCaptureCancellationHandle = NoOpKeplerCaptureCancellationHandle,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    onStatus: (String) -> Unit
) {
    val main = Handler(Looper.getMainLooper())
    fun post(message: String) = main.post { onStatus(message) }
    cancellation.throwIfCancelled()
    post("RAW 캡처 중입니다. 기기를 움직이지 마세요. saved 0/$frameCount, images 0/$frameCount, results 0/$frameCount")
    captureRawBurstForFusion(
        context = context,
        cameraId = cameraId,
        frameCount = frameCount,
        resolutionMode = resolutionMode,
        resolutionPlan = resolutionPlan,
        zoomRatio = zoomRatio,
        requestedUiZoomRatio = requestedUiZoomRatio,
        physicalCameraId = physicalCameraId,
        zoomRoute = zoomRoute,
        previewRoute = previewRoute,
        routeFallbackReason = routeFallbackReason,
        focusAeState = focusAeState,
        rawSpeedMode = rawSpeedMode,
        saveDngSidecars = finalOutputFormat.shouldExportRawSidecar,
        captureCancellationHandle = captureCancellationHandle,
        onStatus = { post(it) },
        onComplete = { jobDir ->
            try {
                cancellation.throwIfCancelled()
            } catch (_: CancellationException) {
                post("PIPELINE_CANCELLED: Capture timed out; background processing stopped.")
                return@captureRawBurstForFusion
            }
            Log.i("KeplerRawPipeline", "PROCESSING_STARTED jobDirAbsolutePath=${jobDir.absolutePath}")
            post("PROCESSING_STARTED: RAW capture complete; processing started.")
            val thread = HandlerThread("KeplerRawFusionPipelineThread").apply { start() }
            Handler(thread.looper).post {
                try {
                    cancellation.throwIfCancelled()
                    val process = processRawFusionJob(
                        context = context,
                        jobDir = jobDir,
                        saveNativeMp24DebugPng = finalOutputFormat.isDebugPng && rawSpeedMode == RawSpeedMode.QUALITY,
                        cancellation = cancellation
                    ) { post(it) }
                    if (!process.success || !process.hasExportableBitmapSource()) {
                        updateExportFailure(
                            jobDir = jobDir,
                            error = process.errorMessage ?: "RAW fusion process failed",
                            finalOutputFormat = finalOutputFormat
                        )
                        post(
                            "PIPELINE_FAILED: RAW Night Fusion failed; keeping RAW cache. " +
                                process.errorMessage
                        )
                        return@post
                    }
                    val processedJobFile = File(jobDir, JOB_JSON_FILE_NAME)
                    val processedJob = JSONObject(processedJobFile.readText())
                    val requestedFrames = processedJob.optInt("requestedFrames", frameCount)
                    val usedFrameCount = processedJob.optInt(
                        "usedFrameCount",
                        processedJob.optInt("savedFrames", requestedFrames)
                    )
                    val partialCapture = processedJob.optBoolean(
                        "partialCapture",
                        usedFrameCount < requestedFrames
                    )
                    val requestedOutputFormat = requestedOutputFormatForSetting(finalOutputFormat)
                    post("결과 미리보기를 준비하는 중입니다.")
                    val previewPrepareStartedAt = System.currentTimeMillis()
                    var exportBitmap: Bitmap? = null
                    val result = try {
                        cancellation.throwIfCancelled()
                        val loaded = process.loadExportBitmap()
                        exportBitmap = loaded.bitmap
                        val nativePreviewPrepareMs = System.currentTimeMillis() - previewPrepareStartedAt
                        updateRawExportBitmapMetadata(
                            jobDir = jobDir,
                            source = loaded.source,
                            nativeRgbaDirectExportUsed = loaded.nativeRgbaDirect,
                            nativeRgbaBitmapLoadedForExport = loaded.nativeRgbaDirect,
                            finalPngDecodeSkippedForExport = loaded.nativeRgbaDirect,
                            exportBitmapWidth = loaded.bitmap.width,
                            exportBitmapHeight = loaded.bitmap.height,
                            nativePreviewPrepareMs = nativePreviewPrepareMs
                        )
                        post("결과를 저장하는 중입니다.")
                        updateRawNativeQualityDiagnostics(jobDir, loaded.bitmap)
                        cancellation.throwIfCancelled()
                        exportNightFusionBitmapToGallery(
                            context = context,
                            bitmap = loaded.bitmap,
                            displayNameBase = "Kepler_RAW_${
                                SimpleDateFormat(
                                    "yyyyMMdd_HHmmss",
                                    Locale.US
                                ).format(Date())
                            }",
                            requestedFormat = requestedOutputFormat,
                            cancellation = cancellation
                        )
                    } finally {
                        exportBitmap?.takeUnless { it.isRecycled }?.recycle()
                    }
                    if (!result.success || result.uriString.isNullOrBlank()) {
                        updateExportFailure(
                            jobDir = jobDir,
                            error = result.errorMessage ?: "Export failed",
                            finalOutputFormat = finalOutputFormat
                        )
                        post(
                            "PIPELINE_FAILED: RAW export failed; keeping RAW cache. " +
                                result.errorMessage
                        )
                        return@post
                    }
                    post("Verifying gallery export...")
                    val verified = verifyGalleryExport(context, result.uriString)
                    updateExportMetadata(
                        jobDir = jobDir,
                        export = result,
                        verified = verified,
                        finalOutputFormat = finalOutputFormat,
                        rawSidecarResult = null,
                        postExportCancellationRequested = cancellation.isCancelled,
                        postExportWorkSkipped = cancellation.isCancelled
                    )
                    if (!verified) {
                        updateExportFailure(
                            jobDir = jobDir,
                            error = "Export verification failed",
                            finalOutputFormat = finalOutputFormat
                            ,export = result
                        )
                        post("PIPELINE_FAILED: RAW export verification failed; keeping RAW cache.")
                        return@post
                    }
                    if (cancellation.isCancelled) {
                        updateExportMetadata(jobDir, result, true, finalOutputFormat,
                            rawSidecarResult = null, postExportCancellationRequested = true,
                            postExportWorkSkipped = true)
                        post("PIPELINE_COMPLETE_PARTIAL: Image was saved, but optional post-export work was cancelled. RAW cache kept.")
                        return@post
                    }
                    val rawSidecarResult = if (finalOutputFormat.shouldExportRawSidecar) {
                        exportRawSidecarsToPublicStorage(
                            context = context,
                            jobDir = jobDir,
                            displayNameBase = "Kepler_RAW_${jobDir.name}",
                            cancellation = cancellation
                        ).also { sidecars ->
                            if (sidecars.success) {
                                if (sidecars.status == "PARTIAL") {
                                    post(
                                        "RAW sidecar export partial: " +
                                            "${sidecars.exportedFiles.size} DNG files. " +
                                            "${sidecars.errorMessage.orEmpty()}"
                                    )
                                } else {
                                    post(
                                        "Exported RAW sidecars: " +
                                            "${sidecars.exportedFiles.size} DNG files"
                                    )
                                }
                            } else if (sidecars.errorMessage != null) {
                                post("RAW sidecar export failed: ${sidecars.errorMessage}")
                            }
                        }
                    } else {
                        null
                    }
                    updateExportMetadata(
                        jobDir = jobDir,
                        export = result,
                        verified = true,
                        finalOutputFormat = finalOutputFormat,
                        rawSidecarResult = rawSidecarResult
                    )
                    if (cancellation.isCancelled) {
                        updateExportMetadata(jobDir, result, true, finalOutputFormat,
                            rawSidecarResult = rawSidecarResult,
                            postExportCancellationRequested = true, postExportWorkSkipped = true)
                        post("PIPELINE_COMPLETE_PARTIAL: Image was saved, but optional post-export work was cancelled. RAW cache kept.")
                        return@post
                    }
                    val rawSuffix = if (rawSidecarResult?.status == "EXPORTED") {
                        " + RAW"
                    } else {
                        ""
                    }
                    val rawSidecarCount = rawSidecarResult?.exportedFiles?.size ?: 0
                    val rawSidecarError = rawSidecarResult?.errorMessage?.takeIf { it.isNotBlank() }
                    if (partialCapture || rawSidecarResult?.status == "PARTIAL" || rawSidecarResult?.status == "FAILED") {
                        post("처리가 완료되었습니다.")
                        post(
                            "PIPELINE_COMPLETE_PARTIAL: Saved ${result.formatUsed.label}$rawSuffix. " +
                                "Used $usedFrameCount/$requestedFrames frames. " +
                                "Exported $rawSidecarCount RAW sidecars. " +
                                (rawSidecarError?.let { "Error: $it. " } ?: "") +
                                "RAW cache kept for reprocessing."
                        )
                    } else {
                        post("처리가 완료되었습니다.")
                        post(
                            "PIPELINE_COMPLETE: Saved ${result.formatUsed.label}$rawSuffix. " +
                                "Used $usedFrameCount/$requestedFrames frames.\n" +
                                "RAW cache kept for reprocessing."
                        )
                    }
                } catch (_: CancellationException) {
                    post("PIPELINE_CANCELLED: Capture timed out; background processing stopped.")
                } catch (oom: OutOfMemoryError) {
                    runCatching {
                        updateExportFailure(
                            jobDir = jobDir,
                            error = "OutOfMemoryError during RAW export; cache kept",
                            finalOutputFormat = finalOutputFormat
                        )
                    }
                    post("PIPELINE_FAILED: RAW export ran out of memory; keeping RAW cache.")
                } catch (e: Exception) {
                    runCatching {
                        updateExportFailure(
                            jobDir = jobDir,
                            error = "${e.javaClass.simpleName}: ${e.message}",
                            finalOutputFormat = finalOutputFormat
                        )
                    }
                    post(
                        "PIPELINE_FAILED: RAW Night Fusion pipeline failed; keeping RAW cache.\n" +
                            e.stackTraceToString()
                    )
                } finally {
                    thread.quitSafely()
                }
            }
        },
        onError = { post("PIPELINE_FAILED: RAW capture failed; keeping cache.\n$it") }
    )
}

internal fun reprocessRawJob(
    context: Context,
    jobDir: File,
    finalOutputFormat: FinalOutputFormat,
    selectedFrameIndices: Set<Int>? = null,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    onStatus: (String) -> Unit
): ReprocessWorkerRun {
    val main = Handler(Looper.getMainLooper())
    fun post(message: String) = main.post { onStatus(message) }
    val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
    val thread = HandlerThread("KeplerRawReprocessThread").apply { start() }
    Handler(thread.looper).post {
        var terminalResult: Result<Unit> = Result.failure(IllegalStateException("RAW reprocess did not reach a terminal state."))
        var publicExportCommitted = false
        var committedExport: GalleryExportResult? = null
        var terminalDisposition = ReprocessTerminalDisposition.UNCOMMITTED_FAILURE
        var currentOutputFile: File? = null
        var currentPreviewFile: File? = null
        var enabledCount = 0
        var totalCount = 0
        try {
            cancellation.throwIfCancelled()
            if (selectedFrameIndices != null) {
                applyExplicitFrameSelection(jobDir, selectedFrameIndices)
            }
            enabledCount = runCatching { getEnabledRawFrames(jobDir).size }.getOrDefault(0)
            totalCount = runCatching {
                loadJobJson(jobDir).optJSONArray("frames")?.length() ?: 0
            }.getOrDefault(0)
            if (enabledCount < MIN_RAW_FUSION_FRAMES) {
                post("Not enough enabled frames to reprocess")
                terminalResult = Result.failure(IllegalStateException("Not enough enabled frames to reprocess"))
                return@post
            }
            post("Reprocessing RAW: using $enabledCount/$totalCount frames")
            val process = processRawFusionJob(
                context = context,
                jobDir = jobDir,
                saveNativeMp24DebugPng = finalOutputFormat.isDebugPng,
                cancellation = cancellation
            ) { post(it) }
            currentOutputFile = process.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
            currentPreviewFile = process.previewPngFile?.takeIf { it.isFile && it.length() > 0L }
            if (!process.success || !process.hasExportableBitmapSource()) {
                val reason = process.errorMessage ?: "RAW fusion process failed"
                post("RAW reprocess failed; source frames kept. $reason")
                terminalResult = Result.failure(IllegalStateException(reason))
                return@post
            }
            val requestedFormat = requestedOutputFormatForSetting(finalOutputFormat)
            post("Exporting reprocessed ${requestedFormat.label}...")
            var exportBitmap: Bitmap? = null
            val export = try {
                val loaded = process.loadExportBitmap()
                exportBitmap = loaded.bitmap
                updateRawExportBitmapMetadata(
                    jobDir = jobDir,
                    source = loaded.source,
                    nativeRgbaDirectExportUsed = loaded.nativeRgbaDirect,
                    nativeRgbaBitmapLoadedForExport = loaded.nativeRgbaDirect,
                    finalPngDecodeSkippedForExport = loaded.nativeRgbaDirect,
                    exportBitmapWidth = loaded.bitmap.width,
                    exportBitmapHeight = loaded.bitmap.height
                )
                exportNightFusionBitmapToGallery(
                    context = context,
                    bitmap = loaded.bitmap,
                    displayNameBase = "Kepler_RAW_REPROCESS_${
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    }",
                    requestedFormat = requestedFormat,
                    cancellation = cancellation
                )
            } finally {
                exportBitmap?.takeUnless { it.isRecycled }?.recycle()
            }
            if (export.success && !export.uriString.isNullOrBlank()) {
                publicExportCommitted = true
                committedExport = export
            }
            if (!export.success || export.uriString.isNullOrBlank() ||
                !verifyGalleryExport(context, export.uriString)
            ) {
                if (publicExportCommitted) terminalDisposition = ReprocessTerminalDisposition.COMMITTED_PARTIAL
                val reason = export.errorMessage ?: "Export or verification failed"
                post("RAW reprocess export failed; source frames kept. $reason")
                terminalResult = Result.failure(IllegalStateException(reason))
                return@post
            }
            post("RAW reprocess complete: used $enabledCount frames; source frames kept.")
            terminalResult = Result.success(Unit)
            terminalDisposition = ReprocessTerminalDisposition.VERIFIED_SUCCESS
        } catch (_: kotlinx.coroutines.CancellationException) {
            post("PIPELINE_CANCELLED: RAW reprocess cancelled; source frames kept.")
            terminalResult = Result.failure(IllegalStateException("RAW reprocess cancelled"))
            terminalDisposition = ReprocessTerminalDisposition.CANCELLED
        } catch (oom: OutOfMemoryError) {
            post("RAW reprocess failed: out of memory; source frames kept.")
            terminalResult = Result.failure(oom)
        } catch (e: Exception) {
            post("RAW reprocess failed; source frames kept. ${e.javaClass.simpleName}: ${e.message}")
            terminalResult = Result.failure(e)
        } finally {
            thread.quitSafely()
            terminal.complete(
                ReprocessWorkerOutcome(
                    result = terminalResult,
                    publicExportCommitted = publicExportCommitted,
                    export = committedExport,
                    finalOutputFile = currentOutputFile,
                    previewFile = currentPreviewFile ?: currentOutputFile,
                    bytesWritten = currentOutputFile?.length() ?: 0L,
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
private fun applyExplicitFrameSelection(jobDir: File, selectedFrameIndices: Set<Int>) {
    val job = loadJobJson(jobDir)
    val frames = job.optJSONArray("frames") ?: return
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
    saveJobJson(jobDir, job)
}

private fun updateRawNativeQualityDiagnostics(jobDir: File, bitmap: Bitmap) {
    runCatching {
        val jobFile = File(jobDir, JOB_JSON_FILE_NAME)
        val job = JSONObject(jobFile.readText())
        val finalPreview = saveBoundedDiagnosticPreview(bitmap, File(jobDir, "final_preview.png"))
        val referencePreview = finalPreview.copy(Bitmap.Config.ARGB_8888, false)
        saveBoundedDiagnosticPreview(referencePreview, File(jobDir, "reference_single_preview.png"))
        saveBoundedDiagnosticPreview(finalPreview, File(jobDir, "fused_before_denoise_preview.png"))
        saveBoundedDiagnosticPreview(finalPreview, File(jobDir, "fused_after_denoise_no_sharpen_preview.png"))
        writeFusionQualityDiagnostics(
            job = job,
            jobDir = jobDir,
            prefix = "raw",
            reference = referencePreview,
            fused = finalPreview,
            denoised = finalPreview,
            finalImage = finalPreview,
            compareFileName = "compare_reference_vs_final.png"
        )
        job.put("referenceSinglePreviewFile", "reference_single_preview.png")
            .put("fusedBeforeDenoisePreviewFile", "fused_before_denoise_preview.png")
            .put("fusedAfterDenoiseNoSharpenPreviewFile", "fused_after_denoise_no_sharpen_preview.png")
            .put("finalPreviewFile", "final_preview.png")
            .put("compareReferenceVsFinalFile", "compare_reference_vs_final.png")
            .put("qualityDiagnosticNativeLimited", true)
            .put(
                "qualityDiagnosticNativeLimitedReason",
                "Native RGBA path only exposes final display bitmap to Kotlin export stage."
            )
        saveJobJson(jobDir, job)
        referencePreview.recycle()
        finalPreview.recycle()
    }
}
