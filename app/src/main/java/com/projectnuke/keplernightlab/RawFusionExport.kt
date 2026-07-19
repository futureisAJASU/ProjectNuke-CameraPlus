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

/**
 * Explicit export-result model returned by [RawFusionExportCoordinator.export]. Each production
 * branch produces exactly one subclass so the export stage never collapses a local candidate
 * output, a local-render failure, or a prior verified public export into a single ambiguous
 * value. The model describes only:
 *
 * - Native RGBA local candidate (no MediaStore commit)
 * - MP24 local candidate (no MediaStore commit)
 * - Kotlin bitmap fallback local candidate (no MediaStore commit)
 * - Local-render failure before public export
 *
 * This model does NOT represent public commit, verification, cache-only public results, or
 * failure after commit. Those are handled by the shared finalizer in Phase 3B2.
 *
 * NORMAL callers persist current export owned-key metadata (success or failure) from the
 * [RawFusionExportResult.metadata] payload inside a single [KeplerJobMetadata.update]. The
 * `REPROCESS_PROGRESS_ONLY` path returns the structured payload to the shared finalizer without
 * writing competing terminal metadata.
 *
 * All branches keep the legacy [RawFusionProcessResult] (`base`) so existing bitmap-source
 * extension functions (`validNativeRgbaFile`, `hasExportableBitmapSource`, `loadExportBitmap`)
 * continue to work unchanged. `metadata` is the export-stage's current-run metadata snapshot and
 * is intentionally a free-form [JSONObject] that the persisted helper copies owned keys out of.
 */
internal sealed class RawFusionExportResult {
    abstract val base: RawFusionProcessResult
    abstract val metadata: JSONObject

    /** Native postprocess RGBA output for the standard path. */
    internal data class NativeRgbaSuccess(
        override val base: RawFusionProcessResult,
        override val metadata: JSONObject
    ) : RawFusionExportResult()

    /** Native 24MP fusion RGBA output plus optional debug PNG. */
    internal data class Mp24Success(
        override val base: RawFusionProcessResult,
        override val metadata: JSONObject
    ) : RawFusionExportResult()

    /** Standard Kotlin bitmap fallback output (local candidate PNG). */
    internal data class BitmapFallbackSuccess(
        override val base: RawFusionProcessResult,
        override val metadata: JSONObject
    ) : RawFusionExportResult()

    /** Local-render failure before any public MediaStore commit. Ownership: current NORMAL failure metadata must reflect this. */
    internal data class LocalRenderFailure(
        override val base: RawFusionProcessResult,
        override val metadata: JSONObject
    ) : RawFusionExportResult()

    val success: Boolean get() = base.success
    val errorMessage: String? get() = base.errorMessage
}

internal val RawFusionExportResult.processResult: RawFusionProcessResult get() = base

/**
 * Explicit RAW public-export outcome model. Captures precisely what happened during the
 * public MediaStore commit, verification, sidecar pass, and any post-commit cancellation —
 * never collapsing a verified export, a verification failure after commit, or a sidecar
 * failure into a single ambiguous value.
 *
 * The legacy [RawFusionProcessResult]-style `success: Boolean` is NOT used here because a
 * verified-after-commit success, a verification failure after commit, and an image success with
 * a sidecar failure all carry different terminal metadata; collapsing them to a single boolean
 * would also collapse terminal ownership.
 *
 * Each variant exposes:
 *
 * - `committed: Boolean` — whether the public URI crossed the MediaStore commit point (i.e. a
 *   successful `IS_PENDING=0`).
 * - `verified: Boolean` — whether the committed URI was verified via [verifyGalleryExport].
 * - `export: GalleryExportResult?` — the committed export value, when present.
 * - `sidecar: RawSidecarExportResult?` — the sidecar outcome, when sidecars were attempted.
 * - `postExportCancellationRequested: Boolean` — true if cancellation was requested after
 *   commit. Diagnostics only; never causes the outcome to be classified as failure.
 * - `currentLocalPreview`, `currentLocalOutput`, `currentError`, `currentWarning` — local
 *   preview/output and current error/warning fields.
 * - `disposition: ReprocessTerminalDisposition` — the corresponding reprocess terminal
 *   disposition used when the same job reaches this state through [reprocessRawJob].
 */
internal sealed class RawFusionPublicExportOutcome {
    abstract val base: RawFusionProcessResult
    abstract val finalOutputFormat: FinalOutputFormat
    abstract val export: GalleryExportResult?
    abstract val sidecar: RawSidecarExportResult?
    abstract val committed: Boolean
    abstract val verified: Boolean
    abstract val postExportCancellationRequested: Boolean
    abstract val postExportWorkSkipped: Boolean
    abstract val currentLocalPreview: File?
    abstract val currentLocalOutput: File?
    abstract val currentError: String?
    abstract val currentWarning: String?
    abstract val disposition: ReprocessTerminalDisposition

    /**
     * Failure that occurred before any public MediaStore commit — local render failure, bitmap
     * preparation failure, or MediaStore insert failure with no URI committed.
     *
     * `committed=false`, `verified=false`. No sidecar is meaningful here because the image
     * itself never reached the public store.
     */
    internal data class UncommittedFailure(
        override val base: RawFusionProcessResult,
        override val finalOutputFormat: FinalOutputFormat,
        override val currentLocalPreview: File?,
        override val currentLocalOutput: File?,
        override val currentError: String,
        override val currentWarning: String? = null
    ) : RawFusionPublicExportOutcome() {
        override val export: GalleryExportResult? = null
        override val sidecar: RawSidecarExportResult? = null
        override val committed: Boolean = false
        override val verified: Boolean = false
        override val postExportCancellationRequested: Boolean = false
        override val postExportWorkSkipped: Boolean = false
        override val disposition: ReprocessTerminalDisposition = ReprocessTerminalDisposition.UNCOMMITTED_FAILURE
    }

    /**
     * MediaStore commit succeeded (URI crossed the IS_PENDING=0 commit point) but the post-
     * commit verification failed. The committed URI is retained even though verification failed;
     * `galleryExportCommitted=true`, `exportVerified=false`.
     *
     * No image rollback is permitted: any deletion of the committed MediaStore row is forbidden.
     */
    internal data class CommittedVerificationFailure(
        override val base: RawFusionProcessResult,
        override val finalOutputFormat: FinalOutputFormat,
        override val export: GalleryExportResult,
        override val sidecar: RawSidecarExportResult?,
        override val currentLocalPreview: File?,
        override val currentLocalOutput: File?,
        override val currentError: String
    ) : RawFusionPublicExportOutcome() {
        override val committed: Boolean = true
        override val verified: Boolean = false
        override val postExportCancellationRequested: Boolean = false
        override val postExportWorkSkipped: Boolean = false
        override val currentWarning: String? = null
        override val disposition: ReprocessTerminalDisposition = ReprocessTerminalDisposition.COMMITTED_PARTIAL
    }

    /**
     * Verified committed public export. Optional sidecar work may have completed, partially
     * completed, failed, been skipped, or been unavailable — see [sidecar]. Image success is
     * preserved in every sidecar outcome; sidecar failure does NOT downgrade the image outcome.
     */
    internal data class VerifiedSuccess(
        override val base: RawFusionProcessResult,
        override val finalOutputFormat: FinalOutputFormat,
        override val export: GalleryExportResult,
        override val sidecar: RawSidecarExportResult?,
        override val currentLocalPreview: File?,
        override val currentLocalOutput: File?,
        override val currentWarning: String? = null
    ) : RawFusionPublicExportOutcome() {
        override val committed: Boolean = true
        override val verified: Boolean = true
        override val postExportCancellationRequested: Boolean = false
        override val postExportWorkSkipped: Boolean = false
        override val currentError: String? = null
        override val disposition: ReprocessTerminalDisposition = ReprocessTerminalDisposition.VERIFIED_SUCCESS
    }

    /**
     * Verified committed public export where post-export optional work was cancelled
     * (cleanup, additional diagnostics, etc.). The committed URI is preserved and verification
     * remains true. `postExportCancellationRequested=true`, `postExportWorkSkipped=true`.
     *
     * Cancellation-or-skip NEVER downgrades the verified result to a rollback-eligible failure.
     */
    internal data class VerifiedWithPostExportCancellation(
        override val base: RawFusionProcessResult,
        override val finalOutputFormat: FinalOutputFormat,
        override val export: GalleryExportResult,
        override val sidecar: RawSidecarExportResult?,
        override val currentLocalPreview: File?,
        override val currentLocalOutput: File?,
        override val currentWarning: String? = "Optional post-export work was cancelled."
    ) : RawFusionPublicExportOutcome() {
        override val committed: Boolean = true
        override val verified: Boolean = true
        override val postExportCancellationRequested: Boolean = true
        override val postExportWorkSkipped: Boolean = true
        override val currentError: String? = null
        override val disposition: ReprocessTerminalDisposition = ReprocessTerminalDisposition.VERIFIED_SUCCESS
    }

    companion object {
        /** True when this outcome represents an image that crossed the MediaStore commit point. */
        val RawFusionPublicExportOutcome.didCommitMediaStore: Boolean get() = committed
        /** True when this outcome represents a verified public export (success or cancellation-after-commit). */
        val RawFusionPublicExportOutcome.isVerified: Boolean get() = verified
    }
}

/**
 * Wrap a RAW public-export outcome for reprocess dispatch. The reprocess worker computes one
 * of these and hands it to the shared finalizer; the worker must NOT write competing terminal
 * stage, status, or gallery/public-result metadata directly.
 */
internal data class RawReprocessPublicExport(
    val outcome: RawFusionPublicExportOutcome,
    val rawSidecarResult: RawSidecarExportResult?,
    val lastErrorMessage: String?
)

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
                        val outcome = RawFusionPublicExportOutcome.UncommittedFailure(
                            base = process,
                            finalOutputFormat = finalOutputFormat,
                            currentLocalPreview = process.previewPngFile?.takeIf { it.isFile && it.length() > 0L },
                            currentLocalOutput = process.finalPngFile?.takeIf { it.isFile && it.length() > 0L },
                            currentError = process.errorMessage ?: "RAW fusion process failed"
                        )
                        updateRawPublicExportOutcome(jobDir, outcome)
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
                    resetRawExportAttemptDiagnostics(jobDir)
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
                        val outcome = RawFusionPublicExportOutcome.UncommittedFailure(
                            base = process,
                            finalOutputFormat = finalOutputFormat,
                            currentLocalPreview = process.previewPngFile?.takeIf { it.isFile && it.length() > 0L },
                            currentLocalOutput = process.finalPngFile?.takeIf { it.isFile && it.length() > 0L },
                            currentError = result.errorMessage ?: "Export failed"
                        )
                        updateRawPublicExportOutcome(jobDir, outcome)
                        post(
                            "PIPELINE_FAILED: RAW export failed; keeping RAW cache. " +
                                result.errorMessage
                        )
                        return@post
                    }
                    post("Verifying gallery export...")
                    val verified = verifyGalleryExport(context, result.uriString)
                    if (!verified) {
                        val outcome = RawFusionPublicExportOutcome.CommittedVerificationFailure(
                            base = process,
                            finalOutputFormat = finalOutputFormat,
                            export = result,
                            sidecar = null,
                            currentLocalPreview = process.previewPngFile?.takeIf { it.isFile && it.length() > 0L },
                            currentLocalOutput = process.finalPngFile?.takeIf { it.isFile && it.length() > 0L },
                            currentError = "Export verification failed"
                        )
                        updateRawPublicExportOutcome(jobDir, outcome)
                        post("PIPELINE_FAILED: RAW export verification failed; keeping RAW cache.")
                        return@post
                    }
                    if (cancellation.isCancelled) {
                        val outcome = RawFusionPublicExportOutcome.VerifiedWithPostExportCancellation(
                            base = process,
                            finalOutputFormat = finalOutputFormat,
                            export = result,
                            sidecar = null,
                            currentLocalPreview = process.previewPngFile?.takeIf { it.isFile && it.length() > 0L },
                            currentLocalOutput = process.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                        )
                        updateRawPublicExportOutcome(jobDir, outcome)
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
                            when (sidecars.kind) {
                                RawSidecarOutcomeKind.COMPLETE -> post("Exported RAW sidecars: ${sidecars.exportedFiles.size} DNG files")
                                RawSidecarOutcomeKind.PARTIAL -> post("RAW sidecar export partial: ${sidecars.exportedFiles.size} DNG files. ${sidecars.errorMessage.orEmpty()}")
                                RawSidecarOutcomeKind.FAILED -> post("RAW sidecar export failed: ${sidecars.errorMessage}")
                                else -> { /* no log for SKIPPED/UNAVAILABLE/CANCELLED */ }
                            }
                        }
                    } else {
                        null
                    }
                    val previewFile = process.previewPngFile?.takeIf { it.isFile && it.length() > 0L }
                    val localOutput = process.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                    if (cancellation.isCancelled) {
                        val outcome = RawFusionPublicExportOutcome.VerifiedWithPostExportCancellation(
                            base = process,
                            finalOutputFormat = finalOutputFormat,
                            export = result,
                            sidecar = rawSidecarResult,
                            currentLocalPreview = previewFile,
                            currentLocalOutput = localOutput
                        )
                        updateRawPublicExportOutcome(jobDir, outcome)
                        post("PIPELINE_COMPLETE_PARTIAL: Image was saved, but optional post-export work was cancelled. RAW cache kept.")
                        return@post
                    }
                    val warning: String? = when {
                        partialCapture -> "Used fewer frames than requested."
                        rawSidecarResult != null && rawSidecarResult.kind != RawSidecarOutcomeKind.COMPLETE &&
                            rawSidecarResult.kind != RawSidecarOutcomeKind.SKIPPED &&
                            rawSidecarResult.kind != RawSidecarOutcomeKind.UNAVAILABLE -> "Sidecar export incomplete: ${rawSidecarResult.status}."
                        else -> null
                    }
                    val outcome = RawFusionPublicExportOutcome.VerifiedSuccess(
                        base = process,
                        finalOutputFormat = finalOutputFormat,
                        export = result,
                        sidecar = rawSidecarResult,
                        currentLocalPreview = previewFile,
                        currentLocalOutput = localOutput,
                        currentWarning = warning
                    )
                    updateRawPublicExportOutcome(jobDir, outcome)
                    val rawSuffix = if (rawSidecarResult?.kind == RawSidecarOutcomeKind.COMPLETE) " + RAW" else ""
                    val rawSidecarCount = rawSidecarResult?.exportedFiles?.size ?: 0
                    val rawSidecarError = rawSidecarResult?.errorMessage?.takeIf { it.isNotBlank() }
                    if (warning != null) {
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
                        updateRawPublicExportOutcome(jobDir, RawFusionPublicExportOutcome.UncommittedFailure(
                            base = RawFusionProcessResult(success = false, mergedRawFile = null, mergedDngFile = null, previewPngFile = null, finalPngFile = null, errorMessage = "OutOfMemoryError during RAW export; cache kept"),
                            finalOutputFormat = finalOutputFormat,
                            currentLocalPreview = null,
                            currentLocalOutput = null,
                            currentError = "OutOfMemoryError during RAW export; cache kept"
                        ))
                    }
                    post("PIPELINE_FAILED: RAW export ran out of memory; keeping RAW cache.")
                } catch (e: Exception) {
                    runCatching {
                        updateRawPublicExportOutcome(jobDir, RawFusionPublicExportOutcome.UncommittedFailure(
                            base = RawFusionProcessResult(success = false, mergedRawFile = null, mergedDngFile = null, previewPngFile = null, finalPngFile = null, errorMessage = "${e.javaClass.simpleName}: ${e.message}"),
                            finalOutputFormat = finalOutputFormat,
                            currentLocalPreview = null,
                            currentLocalOutput = null,
                            currentError = "${e.javaClass.simpleName}: ${e.message}"
                        ))
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
        var sidecarResult: RawSidecarExportResult? = null
        var capturedProcess: RawFusionProcessResult? = null
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
                , metadataPolicy = ReprocessMetadataPolicy.REPROCESS_PROGRESS_ONLY
            ) { post(it) }
            capturedProcess = process
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
            resetRawExportAttemptDiagnostics(jobDir)
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
            } catch (exportError: Throwable) {
                exportBitmap?.takeUnless { it.isRecycled }?.recycle()
                exportBitmap = null
                throw exportError
            }
            try {
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
            if (cancellation.isCancelled) {
                sidecarResult = RawSidecarExportResult.SKIPPED
                post("RAW reprocess verified; cancelling post-export work.")
                terminalResult = Result.success(Unit)
                terminalDisposition = ReprocessTerminalDisposition.VERIFIED_SUCCESS
                return@post
            }
            sidecarResult = if (finalOutputFormat.shouldExportRawSidecar) {
                exportRawSidecarsToPublicStorage(
                    context = context,
                    jobDir = jobDir,
                    displayNameBase = "Kepler_RAW_REPROCESS_${jobDir.name}",
                    cancellation = cancellation
                ).also { sc ->
                    when (sc.kind) {
                        RawSidecarOutcomeKind.COMPLETE -> post("Exported RAW sidecars: ${sc.exportedFiles.size} DNG files")
                        RawSidecarOutcomeKind.PARTIAL -> post("RAW sidecar export partial: ${sc.exportedFiles.size} DNG files. ${sc.errorMessage.orEmpty()}")
                        RawSidecarOutcomeKind.FAILED -> post("RAW sidecar export failed: ${sc.errorMessage}")
                        else -> { /* no log for skipped/unavailable */ }
                    }
                }
            } else {
                RawSidecarExportResult.SKIPPED
            }
            if (currentPreviewFile == null && publicExportCommitted) {
                currentPreviewFile = try {
                    writeBoundedReprocessPreview(jobDir, exportBitmap)
                } catch (previewError: Exception) {
                    post("RAW reprocess preview write failed: ${previewError.message}")
                    null
                }
            }
            post("RAW reprocess complete: used $enabledCount frames; source frames kept.")
            terminalResult = Result.success(Unit)
            terminalDisposition = ReprocessTerminalDisposition.VERIFIED_SUCCESS
            } finally {
                exportBitmap?.takeUnless { it.isRecycled }?.recycle()
            }
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
                    exportVerified = terminalDisposition == ReprocessTerminalDisposition.VERIFIED_SUCCESS,
                    export = committedExport,
                    finalOutputFile = currentOutputFile,
                    previewFile = currentPreviewFile ?: currentOutputFile,
                    bytesWritten = currentOutputFile?.length() ?: 0L,
                    disposition = terminalDisposition,
                    terminalError = terminalResult.exceptionOrNull(),
                    sidecar = sidecarResult,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile
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

private fun updateRawNativeQualityDiagnostics(jobDir: File, bitmap: Bitmap) {
    runCatching {
        val finalPreview = saveBoundedDiagnosticPreview(bitmap, File(jobDir, "final_preview.png"))
        val referencePreview = finalPreview.copy(Bitmap.Config.ARGB_8888, false)
        try {
            saveBoundedDiagnosticPreview(referencePreview, File(jobDir, "reference_single_preview.png"))
            saveBoundedDiagnosticPreview(finalPreview, File(jobDir, "fused_before_denoise_preview.png"))
            saveBoundedDiagnosticPreview(finalPreview, File(jobDir, "fused_after_denoise_no_sharpen_preview.png"))
            KeplerJobMetadata.update(jobDir) { job ->
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
            }
        } finally {
            referencePreview.recycle()
            finalPreview.recycle()
        }
    }
}
