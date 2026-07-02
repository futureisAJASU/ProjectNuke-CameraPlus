package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
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
    zoomRoute: ThreeXSourceMode = ThreeXSourceMode.AUTO,
    previewRoute: String? = null,
    routeFallbackReason: String? = null,
    focusAeState: FocusAeState = FocusAeState(),
    rawSpeedMode: RawSpeedMode = RawSpeedMode.BALANCED,
    onStatus: (String) -> Unit
) {
    val main = Handler(Looper.getMainLooper())
    fun post(message: String) = main.post { onStatus(message) }
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
        onStatus = { post(it) },
        onComplete = { jobDir ->
            Log.i("KeplerRawPipeline", "PROCESSING_STARTED jobDirAbsolutePath=${jobDir.absolutePath}")
            post("캡처가 완료되었습니다.")
            val thread = HandlerThread("KeplerRawFusionPipelineThread").apply { start() }
            Handler(thread.looper).post {
                try {
                    val process = processRawFusionJob(
                        context = context,
                        jobDir = jobDir,
                        saveNativeMp24DebugPng = finalOutputFormat.isDebugPng && rawSpeedMode == RawSpeedMode.QUALITY
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
                        exportNightFusionBitmapToGallery(
                            context = context,
                            bitmap = loaded.bitmap,
                            displayNameBase = "Kepler_RAW_${
                                SimpleDateFormat(
                                    "yyyyMMdd_HHmmss",
                                    Locale.US
                                ).format(Date())
                            }",
                            requestedFormat = requestedOutputFormat
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
                    if (!verifyGalleryExport(context, result.uriString)) {
                        updateExportFailure(
                            jobDir = jobDir,
                            error = "Export verification failed",
                            finalOutputFormat = finalOutputFormat
                        )
                        post("PIPELINE_FAILED: RAW export verification failed; keeping RAW cache.")
                        return@post
                    }
                    val rawSidecarResult = if (finalOutputFormat.shouldExportRawSidecar) {
                        exportRawSidecarsToPublicStorage(
                            context = context,
                            jobDir = jobDir,
                            displayNameBase = "Kepler_RAW_${jobDir.name}"
                        ).also { sidecars ->
                            if (sidecars.success) {
                                post(
                                    "Exported RAW sidecars: " +
                                        "${sidecars.exportedFiles.size} DNG files"
                                )
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
                    val rawSuffix = if (finalOutputFormat.shouldExportRawSidecar) {
                        " + RAW"
                    } else {
                        ""
                    }
                    if (partialCapture) {
                        post("처리가 완료되었습니다.")
                        post(
                            "PIPELINE_COMPLETE_PARTIAL: Saved ${result.formatUsed.label}$rawSuffix. " +
                                "Used $usedFrameCount/$requestedFrames frames. " +
                                "Partial fallback was used.\nRAW cache kept for reprocessing."
                        )
                    } else {
                        post("처리가 완료되었습니다.")
                        post(
                            "PIPELINE_COMPLETE: Saved ${result.formatUsed.label}$rawSuffix. " +
                                "Used $usedFrameCount/$requestedFrames frames.\n" +
                                "RAW cache kept for reprocessing."
                        )
                    }
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

fun reprocessRawJob(
    context: Context,
    jobDir: File,
    finalOutputFormat: FinalOutputFormat,
    selectedFrameIndices: Set<Int>? = null,
    onStatus: (String) -> Unit
) {
    val main = Handler(Looper.getMainLooper())
    fun post(message: String) = main.post { onStatus(message) }
    val thread = HandlerThread("KeplerRawReprocessThread").apply { start() }
    Handler(thread.looper).post {
        if (selectedFrameIndices != null) {
            applyExplicitFrameSelection(jobDir, selectedFrameIndices)
        }
        val enabledCount = runCatching { getEnabledRawFrames(jobDir).size }.getOrDefault(0)
        val totalCount = runCatching {
            loadJobJson(jobDir).optJSONArray("frames")?.length() ?: 0
        }.getOrDefault(0)
        if (enabledCount < MIN_RAW_FUSION_FRAMES) {
            updateReprocessHistory(
                jobDir, enabledCount, totalCount - enabledCount,
                "FAILED_NOT_ENOUGH_ENABLED_FRAMES"
            )
            post("Not enough enabled frames to reprocess")
            thread.quitSafely()
            return@post
        }
        try {
            post("Reprocessing RAW: using $enabledCount/$totalCount frames")
            val process = processRawFusionJob(
                context = context,
                jobDir = jobDir,
                saveNativeMp24DebugPng = finalOutputFormat.isDebugPng
            ) { post(it) }
            if (!process.success || !process.hasExportableBitmapSource()) {
                val reason = process.errorMessage ?: "RAW fusion process failed"
                updateReprocessHistory(jobDir, enabledCount, totalCount - enabledCount, "FAILED: $reason")
                post("RAW reprocess failed; source frames kept. $reason")
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
                    requestedFormat = requestedFormat
                )
            } finally {
                exportBitmap?.takeUnless { it.isRecycled }?.recycle()
            }
            if (!export.success || export.uriString.isNullOrBlank() ||
                !verifyGalleryExport(context, export.uriString)
            ) {
                val reason = export.errorMessage ?: "Export or verification failed"
                updateExportFailure(jobDir, reason, finalOutputFormat)
                updateReprocessHistory(jobDir, enabledCount, totalCount - enabledCount, "FAILED: $reason")
                post("RAW reprocess export failed; source frames kept. $reason")
                return@post
            }
            updateExportMetadata(
                jobDir = jobDir,
                export = export,
                verified = true,
                finalOutputFormat = finalOutputFormat,
                rawSidecarResult = null
            )
            updateReprocessHistory(jobDir, enabledCount, totalCount - enabledCount, "SUCCESS")
            post("RAW reprocess complete: used $enabledCount frames; source frames kept.")
        } catch (oom: OutOfMemoryError) {
            updateReprocessHistory(jobDir, enabledCount, totalCount - enabledCount, "FAILED_OUT_OF_MEMORY")
            post("RAW reprocess failed: out of memory; source frames kept.")
        } catch (e: Exception) {
            updateReprocessHistory(
                jobDir, enabledCount, totalCount - enabledCount,
                "FAILED: ${e.javaClass.simpleName}: ${e.message}"
            )
            post("RAW reprocess failed; source frames kept. ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            thread.quitSafely()
        }
    }
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

private fun updateReprocessHistory(
    jobDir: File,
    usedFrameCount: Int,
    excludedFrameCount: Int,
    status: String
) {
    runCatching {
        val job = loadJobJson(jobDir)
        job.put("reprocessCount", job.optInt("reprocessCount", 0) + 1)
            .put("lastReprocessedAt", System.currentTimeMillis())
            .put("lastReprocessUsedFrameCount", usedFrameCount)
            .put("lastReprocessExcludedFrameCount", excludedFrameCount)
            .put("lastReprocessStatus", status)
        saveJobJson(jobDir, job)
    }
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
        jobFile.writeText(job.toString(2))
        referencePreview.recycle()
        finalPreview.recycle()
    }
}
