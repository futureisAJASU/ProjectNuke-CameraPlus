package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
    physicalCameraId: String? = null,
    focusAeState: FocusAeState = FocusAeState(),
    onStatus: (String) -> Unit
) {
    val main = Handler(Looper.getMainLooper())
    fun post(message: String) = main.post { onStatus(message) }
    post("RAW capture: saved 0/$frameCount, images 0/$frameCount, results 0/$frameCount")
    captureRawBurstForFusion(
        context = context,
        cameraId = cameraId,
        frameCount = frameCount,
        resolutionMode = resolutionMode,
        resolutionPlan = resolutionPlan,
        zoomRatio = zoomRatio,
        physicalCameraId = physicalCameraId,
        focusAeState = focusAeState,
        onStatus = { post(it) },
        onComplete = { jobDir ->
            val thread = HandlerThread("KeplerRawFusionPipelineThread").apply { start() }
            Handler(thread.looper).post {
                try {
                    val process = processRawFusionJob(
                        context = context,
                        jobDir = jobDir,
                        saveNativeMp24DebugPng = finalOutputFormat.isDebugPng
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
                    post("Exporting ${requestedOutputFormat.label}...")
                    var exportBitmap: Bitmap? = null
                    val result = try {
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
                        post(
                            "PIPELINE_COMPLETE_PARTIAL: Saved ${result.formatUsed.label}$rawSuffix. " +
                                "Used $usedFrameCount/$requestedFrames frames. " +
                                "Partial fallback was used.\nRAW cache kept for reprocessing."
                        )
                    } else {
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
