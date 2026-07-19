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
    abstract val rawPublicExportAttemptStatus: String?
    abstract val rawPublicExportAttemptError: String?
    abstract val rawPublicExportAttemptAt: Long

    /**
     * Failure that occurred before any public MediaStore commit — local render failure, bitmap
     * preparation failure, or MediaStore insert failure with no Media commit.
     *
     * `current=false`, `verified=false`. No sidecar is meaningful here because the image
     * itself never reached the public store.
     */
    internal data class UncommittedFailure(
        override val base: RawFusionProcessResult,
        override val finalOutputFormat: FinalOutputFormat,
        override val currentLocalPreview: File?,
        override val currentLocalOutput: File?,
        override val currentError: String,
        override val currentWarning: String? = null,
        override val rawPublicExportAttemptStatus: String = "FAILED",
        override val rawPublicExportAttemptError: String? = currentError,
        override val rawPublicExportAttemptAt: Long = System.currentTimeMillis()
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
     * MediaStore commit succeeded (URI crossed the IS_PENDING=0 commit point) but verification has
     * not yet been attempted. This checkpoint is persisted immediately after the
     * commit so a post-commit crash or process death never represents the operation as uncommitted.
     */
    internal data class CommittedPendingVerification(
        override val base: RawFusionProcessResult,
        override val finalOutputFormat: FinalOutputFormat,
        override val export: GalleryExportResult,
        override val currentLocalPreview: File?,
        override val currentLocalOutput: File?
    ) : RawFusionPublicExportOutcome() {
        override val sidecar: RawSidecarExportResult? = null
        override val committed: Boolean = true
        override val verified: Boolean = false
        override val postExportCancellationRequested: Boolean = false
        override val postExportWorkSkipped: Boolean = false
        override val currentError: String? = null
        override val currentWarning: String? = null
        override val disposition: ReprocessTerminalDisposition = ReprocessTerminalDisposition.COMMITTED_PARTIAL
        override val rawPublicExportAttemptStatus: String? = null
        override val rawPublicExportAttemptError: String? = null
        override val rawPublicExportAttemptAt: Long = 0L
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
        override val rawPublicExportAttemptStatus: String? = null
        override val rawPublicExportAttemptError: String? = null
        override val rawPublicExportAttemptAt: Long = 0L
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
        override val rawPublicExportAttemptStatus: String? = null
        override val rawPublicExportAttemptError: String? = null
        override val rawPublicExportAttemptAt: Long = 0L
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
        override val rawPublicExportAttemptStatus: String? = null
        override val rawPublicExportAttemptError: String? = null
        override val rawPublicExportAttemptAt: Long = 0L
    }

    companion object {
        /** True when this outcome represents an image that crossed the MediaStore commit point. */
        val RawFusionPublicExportOutcome.didCommitMediaStore: Boolean get() = committed
        /** True when this outcome represents a verified public export (success or cancellation-after-commit). */
        val RawFusionPublicExportOutcome.isVerified: Boolean get() = verified
    }
}

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
                var capturedProcess: RawFusionProcessResult? = null
                var committedExport: GalleryExportResult? = null
                var exportVerified = false
                var sidecarResult: RawSidecarExportResult? = null
                var postExportCancellationRequested = false
                try {
                    cancellation.throwIfCancelled()
                    val process = processRawFusionJob(
                        context = context,
                        jobDir = jobDir,
                        saveNativeMp24DebugPng = finalOutputFormat.isDebugPng && rawSpeedMode == RawSpeedMode.QUALITY,
                        cancellation = cancellation
                    ) { post(it) }
                    capturedProcess = process
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
                    if (result.success && !result.uriString.isNullOrBlank()) {
                        committedExport = result
                        post("Verifying gallery export...")
                        val currentPublicOutcome =
                            RawFusionPublicExportOutcome.CommittedPendingVerification(
                                base = process,
                                finalOutputFormat = finalOutputFormat,
                                export = result,
                                currentLocalPreview = process.previewPngFile
                                    ?.takeIf { it.isFile && it.length() > 0L },
                                currentLocalOutput = process.finalPngFile
                                    ?.takeIf { it.isFile && it.length() > 0L }
                            )
                        updateRawPublicExportOutcome(jobDir, currentPublicOutcome)
                    }
                    if (committedExport == null) {
                        if (cancellation.isCancelled) {
                            val previewFile = process.previewPngFile?.takeIf { it.isFile && it.length() > 0L }
                            val localOutput = process.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                            val outcome = RawFusionPublicExportOutcome.UncommittedFailure(
                                base = process,
                                finalOutputFormat = finalOutputFormat,
                                currentLocalPreview = previewFile,
                                currentLocalOutput = localOutput,
                                currentError = "Export cancelled before MediaStore commit."
                            )
                            updateRawPublicExportOutcome(jobDir, outcome)
                            post("PIPELINE_CANCELLED: Export cancelled before MediaStore commit. RAW cache kept.")
                            return@post
                        }
                        val error = result.errorMessage ?: "Export failed"
                        recordRawPublicExportAttempt(jobDir, "FAILED", error)
                        val previewFile = process.previewPngFile?.takeIf { it.isFile && it.length() > 0L }
                        val localOutput = process.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                        val outcome = RawFusionPublicExportOutcome.UncommittedFailure(
                            base = process,
                            finalOutputFormat = finalOutputFormat,
                            currentLocalPreview = previewFile,
                            currentLocalOutput = localOutput,
                            currentError = error
                        )
                        updateRawPublicExportOutcome(jobDir, outcome)
                        post(
                            "PIPELINE_FAILED: RAW export failed; keeping RAW cache. $error"
                        )
                        return@post
                    }
                    val committedExportUri = committedExport!!.uriString ?: ""
                    val verified = verifyGalleryExport(context, committedExportUri)
                    exportVerified = verified
                    if (!verified) {
                        val outcome = RawFusionPublicExportOutcome.CommittedVerificationFailure(
                            base = process,
                            finalOutputFormat = finalOutputFormat,
                            export = committedExport!!,
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
                        postExportCancellationRequested = true
                        val outcome = RawFusionPublicExportOutcome.VerifiedWithPostExportCancellation(
                            base = process,
                            finalOutputFormat = finalOutputFormat,
                            export = committedExport!!,
                            sidecar = null,
                            currentLocalPreview = process.previewPngFile?.takeIf { it.isFile && it.length() > 0L },
                            currentLocalOutput = process.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                        )
                        updateRawPublicExportOutcome(jobDir, outcome)
                        post("PIPELINE_COMPLETE_PARTIAL: Image was saved, but optional post-export work was cancelled. RAW cache kept.")
                        return@post
                    }
                    sidecarResult = if (finalOutputFormat.shouldExportRawSidecar) {
                        val sidecars = exportRawSidecarsToPublicStorage(
                            context = context,
                            jobDir = jobDir,
                            displayNameBase = "Kepler_RAW_${jobDir.name}",
                            cancellation = cancellation
                        )
                        when (sidecars.kind) {
                            RawSidecarOutcomeKind.COMPLETE -> post("Exported RAW sidecars: ${sidecars.exportedFiles.size} DNG files")
                            RawSidecarOutcomeKind.PARTIAL -> post("RAW sidecar export partial: ${sidecars.exportedFiles.size} DNG files. ${sidecars.errorMessage.orEmpty()}")
                            RawSidecarOutcomeKind.FAILED -> post("RAW sidecar export failed: ${sidecars.errorMessage}")
                            RawSidecarOutcomeKind.CANCELLED -> {}
                            else -> {}
                        }
                        sidecars
                    } else {
                        null
                    }
                    val previewFile = process.previewPngFile?.takeIf { it.isFile && it.length() > 0L }
                    val localOutput = process.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                    if (cancellation.isCancelled) {
                        postExportCancellationRequested = true
                        val outcome = RawFusionPublicExportOutcome.VerifiedWithPostExportCancellation(
                            base = process,
                            finalOutputFormat = finalOutputFormat,
                            export = committedExport!!,
                            sidecar = sidecarResult,
                            currentLocalPreview = previewFile,
                            currentLocalOutput = localOutput
                        )
                        updateRawPublicExportOutcome(jobDir, outcome)
                        post("PIPELINE_COMPLETE_PARTIAL: Image was saved, but optional post-export work was cancelled. RAW cache kept.")
                        return@post
                    }
                    val warning: String? = when {
                        partialCapture -> "Used fewer frames than requested."
                        sidecarResult != null && sidecarResult.kind != RawSidecarOutcomeKind.COMPLETE &&
                            sidecarResult.kind != RawSidecarOutcomeKind.SKIPPED &&
                            sidecarResult.kind != RawSidecarOutcomeKind.UNAVAILABLE -> "Sidecar export incomplete: ${sidecarResult.status}."
                        else -> null
                    }
                    val outcome = RawFusionPublicExportOutcome.VerifiedSuccess(
                        base = process,
                        finalOutputFormat = finalOutputFormat,
                        export = committedExport!!,
                        sidecar = sidecarResult,
                        currentLocalPreview = previewFile,
                        currentLocalOutput = localOutput,
                        currentWarning = warning
                    )
                    updateRawPublicExportOutcome(jobDir, outcome)
                    val rawSuffix = if (sidecarResult?.kind == RawSidecarOutcomeKind.COMPLETE) " + RAW" else ""
                    val rawSidecarCount = sidecarResult?.exportedFiles?.size ?: 0
                    val rawSidecarError = sidecarResult?.errorMessage?.takeIf { it.isNotBlank() }
                    if (warning != null) {
                        post("처리가 완료되었습니다.")
                        post(
                            "PIPELINE_COMPLETE_PARTIAL: Saved ${committedExport!!.formatUsed.label}$rawSuffix. " +
                                "Used $usedFrameCount/$requestedFrames frames. " +
                                "Exported $rawSidecarCount RAW sidecars. " +
                                (rawSidecarError?.let { "Error: $it. " } ?: "") +
                                "RAW cache kept for reprocessing."
                        )
                    } else {
                        post("처리가 완료되었습니다.")
                        post(
                            "PIPELINE_COMPLETE: Saved ${committedExport!!.formatUsed.label}$rawSuffix. " +
                                "Used $usedFrameCount/$requestedFrames frames.\n" +
                                "RAW cache kept for reprocessing."
                        )
                    }
                } catch (_: CancellationException) {
                    if (committedExport != null) {
                        val proc = capturedProcess
                        val cancelPrevFile = proc?.previewPngFile?.takeIf { it.isFile && it.length() > 0L }
                        val cancelLocalOutput = proc?.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                        if (exportVerified) {
                            val outcome = RawFusionPublicExportOutcome.VerifiedWithPostExportCancellation(
                                base = proc ?: RawFusionProcessResult(success = false, null, null, null, null, "Cancelled after verified export"),
                                finalOutputFormat = finalOutputFormat,
                                export = committedExport!!,
                                sidecar = sidecarResult,
                                currentLocalPreview = cancelPrevFile,
                                currentLocalOutput = cancelLocalOutput
                            )
                            updateRawPublicExportOutcome(jobDir, outcome)
                        } else {
                            val partial = RawFusionPublicExportOutcome.CommittedVerificationFailure(
                                base = proc ?: RawFusionProcessResult(success = false, null, null, null, null, "Cancelled after commit"),
                                finalOutputFormat = finalOutputFormat,
                                export = committedExport!!,
                                sidecar = null,
                                currentLocalPreview = cancelPrevFile,
                                currentLocalOutput = cancelLocalOutput,
                                currentError = "Cancelled after MediaStore commit, before verification completed."
                            )
                            updateRawPublicExportOutcome(jobDir, partial)
                        }
                    }
                    post("PIPELINE_CANCELLED: Capture timed out; background processing stopped.")
                } catch (oom: OutOfMemoryError) {
                    runCatching {
                        if (committedExport != null) {
                            val proc = capturedProcess
                            val oomPrevFile = proc?.previewPngFile?.takeIf { it.isFile && it.length() > 0L }
                            val oomLocalOutput = proc?.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                            if (exportVerified) {
                                updateRawPublicExportOutcome(
                                    jobDir,
                                    RawFusionPublicExportOutcome.VerifiedSuccess(
                                        base = proc ?: RawFusionProcessResult(success = false, null, null, null, null, "OOM after verified export"),
                                        finalOutputFormat = finalOutputFormat,
                                        export = committedExport!!,
                                        sidecar = sidecarResult,
                                        currentLocalPreview = oomPrevFile,
                                        currentLocalOutput = oomLocalOutput,
                                        currentWarning = "OOM after export; committed image preserved."
                                    )
                                )
                            } else {
                                updateRawPublicExportOutcome(
                                    jobDir,
                                    RawFusionPublicExportOutcome.CommittedVerificationFailure(
                                        base = proc ?: RawFusionProcessResult(success = false, null, null, null, null, "OOM after commit"),
                                        finalOutputFormat = finalOutputFormat,
                                        export = committedExport!!,
                                        sidecar = null,
                                        currentLocalPreview = oomPrevFile,
                                        currentLocalOutput = oomLocalOutput,
                                        currentError = "OutOfMemoryError after committed export; cache kept"
                                    )
                                )
                            }
                        } else {
                            updateRawPublicExportOutcome(jobDir, RawFusionPublicExportOutcome.UncommittedFailure(
                                base = RawFusionProcessResult(success = false, mergedRawFile = null, mergedDngFile = null, previewPngFile = null, finalPngFile = null, errorMessage = "OutOfMemoryError during RAW export; cache kept"),
                                finalOutputFormat = finalOutputFormat,
                                currentLocalPreview = null,
                                currentLocalOutput = null,
                                currentError = "OutOfMemoryError during RAW export; cache kept"
                            ))
                        }
                    }
                    post("PIPELINE_FAILED: RAW export ran out of memory; keeping RAW cache.")
                } catch (e: Exception) {
                    runCatching {
                        if (committedExport != null) {
                            val proc = capturedProcess
                            val excPrevFile = proc?.previewPngFile?.takeIf { it.isFile && it.length() > 0L }
                            val excLocalOutput = proc?.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                            if (exportVerified) {
                                updateRawPublicExportOutcome(
                                    jobDir,
                                    RawFusionPublicExportOutcome.VerifiedSuccess(
                                        base = proc ?: RawFusionProcessResult(success = false, null, null, null, null, "Exception after verified export"),
                                        finalOutputFormat = finalOutputFormat,
                                        export = committedExport!!,
                                        sidecar = sidecarResult,
                                        currentLocalPreview = excPrevFile,
                                        currentLocalOutput = excLocalOutput,
                                        currentWarning = "${e.javaClass.simpleName}: ${e.message}"
                                    )
                                )
                            } else {
                                updateRawPublicExportOutcome(
                                    jobDir,
                                    RawFusionPublicExportOutcome.CommittedVerificationFailure(
                                        base = proc ?: RawFusionProcessResult(success = false, null, null, null, null, "Exception after commit"),
                                        finalOutputFormat = finalOutputFormat,
                                        export = committedExport!!,
                                        sidecar = null,
                                        currentLocalPreview = excPrevFile,
                                        currentLocalOutput = excLocalOutput,
                                        currentError = "${e.javaClass.simpleName}: ${e.message}"
                                    )
                                )
                            }
                        } else {
                            recordRawPublicExportAttempt(jobDir, "FAILED", "${e.javaClass.simpleName}: ${e.message}")
                            updateRawPublicExportOutcome(jobDir, RawFusionPublicExportOutcome.UncommittedFailure(
                                base = RawFusionProcessResult(success = false, mergedRawFile = null, mergedDngFile = null, previewPngFile = null, finalPngFile = null, errorMessage = "${e.javaClass.simpleName}: ${e.message}"),
                                finalOutputFormat = finalOutputFormat,
                                currentLocalPreview = null,
                                currentLocalOutput = null,
                                currentError = "${e.javaClass.simpleName}: ${e.message}"
                            ))
                        }
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
        var publicOutcome: RawFusionPublicExportOutcome? = null
        var currentOutputFile: File? = null
        var currentPreviewFile: File? = null
        var enabledCount = 0
        var totalCount = 0
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
                publicOutcome = RawFusionPublicExportOutcome.UncommittedFailure(
                    base = RawFusionProcessResult(success = false, mergedRawFile = null, mergedDngFile = null, previewPngFile = null, finalPngFile = null, errorMessage = "Not enough enabled frames to reprocess"),
                    finalOutputFormat = finalOutputFormat,
                    currentLocalPreview = null,
                    currentLocalOutput = null,
                    currentError = "Not enough enabled frames to reprocess"
                )
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
                publicOutcome = RawFusionPublicExportOutcome.UncommittedFailure(
                    base = process,
                    finalOutputFormat = finalOutputFormat,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile,
                    currentError = reason
                )
                terminalResult = Result.failure(IllegalStateException(reason))
                return@post
            }
            val requestedFormat = requestedOutputFormatForSetting(finalOutputFormat)
            post("Exporting reprocessed ${requestedFormat.label}...")
            resetRawExportAttemptDiagnostics(jobDir)
            var exportBitmap: Bitmap? = null
            val exportAttempted = try {
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
                GalleryExportResult(
                    success = false,
                    uriString = null,
                    displayName = null,
                    mimeType = null,
                    fileSizeBytes = 0L,
                    formatUsed = requestedFormat,
                    fallbackUsed = false,
                    errorMessage = "${exportError.javaClass.simpleName}: ${exportError.message}"
                )
            }
            var committedExport: GalleryExportResult? = null
            try {
            if (exportAttempted.success && !exportAttempted.uriString.isNullOrBlank()) {
                committedExport = exportAttempted
                publicOutcome = RawFusionPublicExportOutcome.CommittedPendingVerification(
                    base = process,
                    finalOutputFormat = finalOutputFormat,
                    export = exportAttempted,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile
                )
            }
            if (committedExport == null) {
                recordRawPublicExportAttempt(jobDir, "FAILED", exportAttempted.errorMessage ?: "Export failed")
                publicOutcome = RawFusionPublicExportOutcome.UncommittedFailure(
                    base = process,
                    finalOutputFormat = finalOutputFormat,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile,
                    currentError = exportAttempted.errorMessage ?: "Export failed"
                )
                val reason = exportAttempted.errorMessage ?: "Export failed"
                post("RAW reprocess export failed; source frames kept. $reason")
                terminalResult = Result.failure(IllegalStateException(reason))
                return@post
            }
            val verified = verifyGalleryExport(context, committedExport!!.uriString!!)
            if (!verified) {
                publicOutcome = RawFusionPublicExportOutcome.CommittedVerificationFailure(
                    base = process,
                    finalOutputFormat = finalOutputFormat,
                    export = committedExport!!,
                    sidecar = null,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile,
                    currentError = "Export verification failed"
                )
                val reason = "Export verification failed"
                post("RAW reprocess export failed; source frames kept. $reason")
                terminalResult = Result.failure(IllegalStateException(reason))
                return@post
            }
            var reprocessSidecarResult: RawSidecarExportResult? = null
            if (cancellation.isCancelled) {
                reprocessSidecarResult = if (finalOutputFormat.shouldExportRawSidecar) {
                    RawSidecarExportResult.cancelled()
                } else {
                    RawSidecarExportResult.SKIPPED
                }
                post("RAW reprocess verified; cancelling post-export work.")
                publicOutcome = RawFusionPublicExportOutcome.VerifiedWithPostExportCancellation(
                    base = process,
                    finalOutputFormat = finalOutputFormat,
                    export = committedExport!!,
                    sidecar = reprocessSidecarResult,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile
                )
                terminalResult = Result.success(Unit)
                return@post
            }
            reprocessSidecarResult = if (finalOutputFormat.shouldExportRawSidecar) {
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
                        RawSidecarOutcomeKind.CANCELLED -> {}
                        else -> {}
                    }
                }
            } else {
                RawSidecarExportResult.SKIPPED
            }
            if (currentPreviewFile == null && exportBitmap != null) {
                currentPreviewFile = try {
                    writeBoundedReprocessPreview(jobDir, exportBitmap!!)
                } catch (previewError: Exception) {
                    post("RAW reprocess preview write failed: ${previewError.message}")
                    null
                }
            }
            val warning: String? = when {
                reprocessSidecarResult != null && reprocessSidecarResult.kind != RawSidecarOutcomeKind.COMPLETE &&
                    reprocessSidecarResult.kind != RawSidecarOutcomeKind.SKIPPED &&
                    reprocessSidecarResult.kind != RawSidecarOutcomeKind.UNAVAILABLE &&
                    reprocessSidecarResult.kind != RawSidecarOutcomeKind.CANCELLED -> "Sidecar export incomplete: ${reprocessSidecarResult.status}."
                else -> null
            }
            publicOutcome = RawFusionPublicExportOutcome.VerifiedSuccess(
                base = process,
                finalOutputFormat = finalOutputFormat,
                export = committedExport!!,
                sidecar = reprocessSidecarResult,
                currentLocalPreview = currentPreviewFile,
                currentLocalOutput = currentOutputFile,
                currentWarning = warning
            )
            post("RAW reprocess complete: used $enabledCount frames; source frames kept.")
            terminalResult = Result.success(Unit)
            } finally {
                exportBitmap?.takeUnless { it.isRecycled }?.recycle()
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            if (publicOutcome == null || !publicOutcome!!.committed) {
                post("PIPELINE_CANCELLED: RAW reprocess cancelled; source frames kept.")
                publicOutcome = publicOutcome ?: RawFusionPublicExportOutcome.UncommittedFailure(
                    base = capturedProcess ?: RawFusionProcessResult(success = false, null, null, null, null, "Reprocess cancelled"),
                    finalOutputFormat = finalOutputFormat,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile,
                    currentError = "RAW reprocess cancelled"
                )
                terminalResult = Result.failure(IllegalStateException("RAW reprocess cancelled"))
            } else if (publicOutcome!!.verified) {
                // Already reached verified-with-cancellation inside the try; outcome is already set.
                terminalResult = Result.success(Unit)
            } else {
                publicOutcome = RawFusionPublicExportOutcome.CommittedVerificationFailure(
                    base = capturedProcess ?: RawFusionProcessResult(success = false, null, null, null, null, "Reprocess cancelled after commit"),
                    finalOutputFormat = finalOutputFormat,
                    export = publicOutcome!!.export!!,
                    sidecar = null,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile,
                    currentError = "RAW reprocess cancelled after commit, before verification"
                )
                terminalResult = Result.failure(IllegalStateException("RAW reprocess cancelled after commit"))
            }
        } catch (oom: OutOfMemoryError) {
            publicOutcome = publicOutcome ?: RawFusionPublicExportOutcome.UncommittedFailure(
                base = capturedProcess ?: RawFusionProcessResult(success = false, null, null, null, null, "OOM during reprocess"),
                finalOutputFormat = finalOutputFormat,
                currentLocalPreview = currentPreviewFile,
                currentLocalOutput = currentOutputFile,
                currentError = "OutOfMemoryError during RAW reprocess"
            )
            post("RAW reprocess failed: out of memory; source frames kept.")
            terminalResult = Result.failure(oom)
        } catch (e: Exception) {
            publicOutcome = publicOutcome ?: RawFusionPublicExportOutcome.UncommittedFailure(
                base = capturedProcess ?: RawFusionProcessResult(success = false, null, null, null, null, "Exception during reprocess"),
                finalOutputFormat = finalOutputFormat,
                currentLocalPreview = currentPreviewFile,
                currentLocalOutput = currentOutputFile,
                currentError = "${e.javaClass.simpleName}: ${e.message}"
            )
            post("RAW reprocess failed; source frames kept. ${e.javaClass.simpleName}: ${e.message}")
            terminalResult = Result.failure(e)
        } finally {
            thread.quitSafely()
            val resolved = publicOutcome
                ?: RawFusionPublicExportOutcome.UncommittedFailure(
                    base = capturedProcess ?: RawFusionProcessResult(success = false, null, null, null, null, "Unreachable"),
                    finalOutputFormat = finalOutputFormat,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile,
                    currentError = "Unreachable outcome"
                )
            terminal.complete(
                ReprocessWorkerOutcome(
                    result = terminalResult,
                    publicExportCommitted = resolved.committed,
                    exportVerified = resolved.verified,
                    export = resolved.export,
                    finalOutputFile = currentOutputFile,
                    previewFile = currentPreviewFile ?: currentOutputFile,
                    bytesWritten = currentOutputFile?.length() ?: resolved.export?.fileSizeBytes ?: 0L,
                    disposition = resolved.disposition,
                    terminalError = terminalResult.exceptionOrNull(),
                    sidecar = resolved.sidecar,
                    postExportCancellationRequested = resolved.postExportCancellationRequested,
                    postExportWorkSkipped = resolved.postExportWorkSkipped,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile,
                    publicOutcome = resolved
                )
            )
        }
    }
    return ReprocessWorkerRun(
        terminal = terminal,
        cancel = { (cancellation as? KeplerPipelineCancellationToken)?.cancel() }
    )
}

/**
 * Record current public-export attempt failure diagnostics without clearing or replacing
 * any previously committed export URI, verification, or linkage fields. Writes only narrowly
 * scoped attempt-status/error/timestamp keys into [RAW_PUBLIC_EXPORT_CURRENT_ATTEMPT_KEYS]
 * space. Called before a new MediaStore commit attempt fails; cleared by
 * [CommittedPendingVerification] and subsequent committed outcomes.
 */
private fun recordRawPublicExportAttempt(jobDir: File, status: String, error: String) {
    runCatching {
        KeplerJobMetadata.update(jobDir) { job ->
            job.put("rawPublicExportAttemptStatus", status)
                .put("rawPublicExportAttemptError", error)
                .put("rawPublicExportAttemptAt", System.currentTimeMillis())
        }
    }.onFailure { metadataError ->
        Log.e(
            "KeplerRawPipeline",
            "Failed to persist RAW export attempt failure: ${metadataError.message}",
            metadataError
        )
    }
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
