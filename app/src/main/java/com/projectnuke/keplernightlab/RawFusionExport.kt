package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Date
import java.util.Locale

/**
 * Owns the outer RAW processing operation for the whole export/reprocess wrapper.  Nested
 * processors borrow [lease] and release only their ProcessingAttempt sub-lease; this scope keeps
 * the job operation held through the wrapper's terminal metadata and callback decisions.
 */
internal class RawProcessingOperation internal constructor(
    val lease: JobOperationLease,
    private val ownsLease: Boolean,
    private val jobDir: File,
    private val activeOperationId: String?
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.get()) return
        val operationId = activeOperationId
        val cleared = operationId?.let { KeplerJobMetadata.clearActiveOperation(jobDir, it, lease) } ?: true
        if (!cleared && operationId?.let { KeplerJobMetadata.isCurrentActiveOperation(jobDir, it) } == true) {
            Log.e("KeplerRawPipeline", "retaining RAW operation lease after durable owner clear failure")
            return
        }
        if (!released.compareAndSet(false, true)) return
        if (ownsLease) lease.release()
    }

    /** Reclaims durable evidence after the nested ProcessingAttempt releases its sublease. */
    internal fun reassertActiveOperation(kind: KeplerActiveOperationKind) {
        activeOperationId?.let { operationId ->
            KeplerJobMetadata.beginActiveOperation(jobDir, operationId, kind, ownerLease = lease)
        }
    }
}

internal fun acquireRawProcessingOperation(
    jobDir: File,
    borrowedLease: JobOperationLease? = null
): RawProcessingOperation? {
    val ownsLease = borrowedLease == null
    val lease = borrowedLease ?: try {
        KeplerJobMetadata.acquireRecoveryCheckedOperation(
            jobDir,
            JobRecoveryMutationIntent.PROCESSING_START
        )
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        null
    } ?: return null
    if (!KeplerJobMetadata.isOperationOwner(jobDir, lease)) {
        if (ownsLease) lease.release()
        return null
    }
    val operationId = if (NoFollowFileSystem.resolveDirectChild(jobDir, JOB_JSON_FILE_NAME, requireFile = true) != null) {
        KeplerJobMetadata.beginActiveOperation(
            jobDir,
            kind = KeplerActiveOperationKind.PROCESSING_RAW,
            ownerLease = lease
        )
    } else null
    return RawProcessingOperation(lease, ownsLease, jobDir, operationId)
}

/**
 * Settles a RAW wrapper failure while its outer operation is still authoritative. The caller
 * owns the final release in a `finally`, so no terminal metadata or callback decision can race a
 * subsequent operation.
 */
internal fun recordRawOuterTerminalFailureWhileOwned(
    jobDir: File,
    operation: RawProcessingOperation,
    reason: String,
    beforeMetadata: () -> Unit = {},
    onStatus: () -> Unit
) {
    check(KeplerJobMetadata.isOperationOwner(jobDir, operation.lease)) {
        "RAW terminal failure settlement requires the owning operation"
    }
    beforeMetadata()
    try {
        recordNormalPreCommitTerminal(
            jobDir,
            attemptStatus = "FAILED",
            pipelineStage = "FAILED",
            processStatus = "EXPORT_FAILED_KEEPING_CACHE",
            reason = reason
        )
    } catch (metadataError: Exception) {
        Log.e(
            "KeplerRawPipeline",
            "Failed to persist RAW terminal failure metadata: ${metadataError.message}",
            metadataError
        )
    }
    onStatus()
}

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

/** One terminal-kind mapping for every durable RAW public-export outcome. */
internal fun rawFusionOutcomeTerminalKind(
    outcome: RawFusionPublicExportOutcome?,
    cancellationRequested: Boolean,
    currentLocalOutput: File? = outcome?.currentLocalOutput
): CameraPipelineEvent.Terminal.Kind = when {
    outcome?.committed == true && outcome.verified &&
        !outcome.postExportCancellationRequested &&
        !outcome.postExportWorkSkipped &&
        outcome.currentWarning == null -> CameraPipelineEvent.Terminal.Kind.COMPLETE
    outcome?.committed == true ||
        (outcome?.base?.outputCommitted == true && currentLocalOutput != null) ->
        CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL
    cancellationRequested -> CameraPipelineEvent.Terminal.Kind.CANCELLED
    else -> CameraPipelineEvent.Terminal.Kind.FAILED
}

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
     * `committed=false`, `verified=false`. A current local output may still be committed by the
     * outer reprocess transaction as a usable local-only partial result; public-store failure
     * never makes that current local claim disappear.
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
        override val disposition: ReprocessTerminalDisposition =
            if (base.outputCommitted && (currentLocalOutput?.isFile == true || currentLocalPreview?.isFile == true)) {
                ReprocessTerminalDisposition.COMMITTED_PARTIAL
            } else {
                ReprocessTerminalDisposition.UNCOMMITTED_FAILURE
            }
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
     * MediaStore commit succeeded but cancellation was requested before verification could
     * complete. Distinct from [CommittedVerificationFailure] — this is an intentional
     * cancellation, not a verification failure. The committed URI is retained;
     * `galleryExportCommitted=true`, `exportVerified=false`.
     *
     * No image rollback is permitted. Process status: `EXPORT_COMMITTED_CANCELLED_BEFORE_VERIFICATION`.
     */
    internal data class CommittedCancelledBeforeVerification(
        override val base: RawFusionProcessResult,
        override val finalOutputFormat: FinalOutputFormat,
        override val export: GalleryExportResult,
        override val sidecar: RawSidecarExportResult?,
        override val currentLocalPreview: File?,
        override val currentLocalOutput: File?,
        override val currentError: String = "Cancelled before export verification completed."
    ) : RawFusionPublicExportOutcome() {
        override val committed: Boolean = true
        override val verified: Boolean = false
        override val postExportCancellationRequested: Boolean = true
        override val postExportWorkSkipped: Boolean = true
        override val currentWarning: String? = null
        override val disposition: ReprocessTerminalDisposition = ReprocessTerminalDisposition.COMMITTED_PARTIAL
        override val rawPublicExportAttemptStatus: String? = null
        override val rawPublicExportAttemptError: String? = null
        override val rawPublicExportAttemptAt: Long = 0L
    }

    /**
     * MediaStore commit succeeded but an OOM or exception occurred before verification could
     * run. Distinct from [CommittedVerificationFailure] (verification ran and returned false)
     * and from [CommittedCancelledBeforeVerification] (intentional cancellation). The committed
     * URI is retained; `galleryExportCommitted=true`, `exportVerified=false`.
     *
     * No image rollback is permitted. Process status: `EXPORT_COMMITTED_INTERRUPTED_BEFORE_VERIFICATION`.
     */
    internal data class CommittedInterruptedBeforeVerification(
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
     * Verified committed public export where optional post-export work (sidecar export, preview
     * generation, etc.) has not yet reached an outcome. The committed URI is preserved and
     * verification is true; this is non-rollback-eligible. Process status:
     * `EXPORT_VERIFIED_PENDING_POST_WORK`, pipeline stage: `PROCESSING`.
     *
     * This checkpoint is persisted immediately after [verifyGalleryExport] returns true, before
     * sidecars, so a post-verification crash never leaves the job in an unverified committed state.
     * Final [VerifiedSuccess] is written only after optional post-export work has produced its
     * current outcome.
     */
    internal data class VerifiedPendingPostWork(
        override val base: RawFusionProcessResult,
        override val finalOutputFormat: FinalOutputFormat,
        override val export: GalleryExportResult,
        override val currentLocalPreview: File?,
        override val currentLocalOutput: File?
    ) : RawFusionPublicExportOutcome() {
        override val sidecar: RawSidecarExportResult? = null
        override val committed: Boolean = true
        override val verified: Boolean = true
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
     * Verified committed public export where an OOM or exception occurred after verification
     * but before final post-export work (sidecar export, etc.) completed. The committed URI is
     * preserved, `exportVerified=true`, and `postExportWorkSkipped=true`. Non-rollback-eligible.
     *
     * Process/export status: verified-partial rather than full success or uncommitted failure.
     */
    internal data class VerifiedPostWorkInterrupted(
        override val base: RawFusionProcessResult,
        override val finalOutputFormat: FinalOutputFormat,
        override val export: GalleryExportResult,
        override val sidecar: RawSidecarExportResult?,
        override val currentLocalPreview: File?,
        override val currentLocalOutput: File?,
        override val currentWarning: String? = null,
        override val currentError: String? = null
    ) : RawFusionPublicExportOutcome() {
        override val committed: Boolean = true
        override val verified: Boolean = true
        override val postExportCancellationRequested: Boolean = false
        override val postExportWorkSkipped: Boolean = true
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
    val nativeRgbaDirect: Boolean,
    val appliedRotationDegrees: Int
)

private fun RawFusionProcessResult.validNativeRgbaFile(): File? {
    val file = nativeRgbaFile ?: return null
    if (nativeRgbaWidth <= 0 || nativeRgbaHeight <= 0 || !NoFollowFileSystem.isRealFile(file.toPath())) return null
    val expectedBytes = nativeRgbaWidth.toLong() * nativeRgbaHeight.toLong() * 4L
    val actualBytes = try {
        NoFollowFileSystem.requireSize(file)
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        return null
    }
    return file.takeIf { actualBytes == expectedBytes }
}

private fun RawFusionProcessResult.hasExportableBitmapSource(): Boolean {
    fun File.hasPositiveVerifiedSize(): Boolean = try {
        NoFollowFileSystem.isRealFile(toPath()) && NoFollowFileSystem.requireSize(this) > 0L
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        false
    }
    return validNativeRgbaFile() != null ||
        finalPngFile?.hasPositiveVerifiedSize() == true
}

private fun RawFusionProcessResult.loadExportBitmap(jobDir: File): RawFusionExportBitmap {
    fun orient(bitmap: Bitmap, source: String, native: Boolean): RawFusionExportBitmap {
        val rotation = resolveRawExportRotation(jobDir)
        val degrees = (rotation as? ExportOrientationResolution.Resolved)?.degrees
            ?: throw IllegalStateException((rotation as ExportOrientationResolution.Unsupported).reason)
        ensureSafeRotationAllocation(bitmap, degrees)
        val rotated = rotateBitmapIfNeeded(bitmap, degrees)
        if (rotated !== bitmap) bitmap.recycle()
        return RawFusionExportBitmap(rotated, source, native, degrees)
    }
    val rgbaFile = validNativeRgbaFile()
    if (rgbaFile != null) {
        try {
            return orient(loadRawRgbaBitmap(rgbaFile, nativeRgbaWidth, nativeRgbaHeight), "native_rgba_direct", true)
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (nativeError: Exception) {
            val png = finalPngFile?.takeIf { file ->
                try {
                    NoFollowFileSystem.isRealFile(file.toPath()) && NoFollowFileSystem.requireSize(file) > 0L
                } catch (failure: Error) {
                    throw failure
                } catch (_: Exception) {
                    false
                }
            }
                ?: throw IllegalStateException(
                    "Native RGBA bitmap load failed and no final PNG fallback exists",
                    nativeError
                )
            val bitmap = NoFollowFileSystem.decodeBitmapVerified(png)
                ?: throw IllegalStateException(
                    "Final RAW fusion PNG fallback decode failed",
                    nativeError
                )
            return orient(bitmap, "final_png_decode", false)
        }
    }
    val png = finalPngFile?.takeIf { file ->
        try {
            NoFollowFileSystem.isRealFile(file.toPath()) && NoFollowFileSystem.requireSize(file) > 0L
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            false
        }
    }
        ?: error("No exportable RAW fusion bitmap source")
    val bitmap = NoFollowFileSystem.decodeBitmapVerified(png)
        ?: error("Final RAW fusion PNG decode failed")
    return orient(bitmap, "final_png_decode", false)
}

internal fun resolveRawExportRotation(jobDir: File): ExportOrientationResolution {
    val job = try {
        JSONObject(NoFollowFileSystem.readTextVerified(NoFollowFileSystem.requireDirectChildFile(jobDir, JOB_JSON_FILE_NAME)))
    } catch (failure: Error) {
        throw failure
    } catch (failure: Exception) {
        return ExportOrientationResolution.Unsupported("Cannot read RAW orientation metadata: ${failure.message}")
    }
    val sourceState = when (val value = job.strictOrientationString("sourceOrientationState")) {
        OrientationJsonValue.Missing -> {
        // Explicit legacy fallback: older jobs did not capture UI rotation. Keep their historical
        // sensor-grid behavior rather than guessing and silently rotating a reprocess.
            Log.w("KeplerRawPipeline", "Legacy RAW job has no source orientation metadata; export remains unrotated.")
            return ExportOrientationResolution.Resolved(0)
        }
        OrientationJsonValue.Null -> return ExportOrientationResolution.Unsupported("sourceOrientationState is null")
        is OrientationJsonValue.Malformed -> return ExportOrientationResolution.Unsupported(value.reason)
        is OrientationJsonValue.Value -> value.value
    }
    val sensor = job.strictOrientationInt("sensorOrientation")
    val display = job.strictOrientationInt("displayRotationAtCapture")
    val facing = job.strictOrientationInt("lensFacing")
    val upright = job.strictOrientationBoolean("exportSourceWasDisplayUpright")
    val alreadyApplied = job.strictOrientationBoolean("rotationAppliedAtExportStage")
    val invalid = listOf(sensor, display, facing, upright, alreadyApplied)
        .filterNot { it is OrientationJsonValue.Value || it is OrientationJsonValue.Missing }
        .firstOrNull()
    if (invalid != null) return ExportOrientationResolution.Unsupported(
        (invalid as? OrientationJsonValue.Malformed)?.reason ?: "RAW orientation metadata is null"
    )
    return resolveExportOrientation(
        ExportOrientationInput(
            sensorOrientationDegrees = (sensor as? OrientationJsonValue.Value)?.value,
            displayRotation = (display as? OrientationJsonValue.Value)?.value,
            lensFacing = (facing as? OrientationJsonValue.Value)?.value,
            sourceWasDisplayUpright = ((upright as? OrientationJsonValue.Value)?.value ?: false) || sourceState == "DISPLAY_UPRIGHT",
            rotationAlreadyApplied = sourceState == "DISPLAY_UPRIGHT" && ((alreadyApplied as? OrientationJsonValue.Value)?.value ?: false)
        )
    )
}

private sealed interface OrientationJsonValue<out T> {
    data object Missing : OrientationJsonValue<Nothing>
    data object Null : OrientationJsonValue<Nothing>
    data class Value<T>(val value: T) : OrientationJsonValue<T>
    data class Malformed(val reason: String) : OrientationJsonValue<Nothing>
}

private fun JSONObject.strictOrientationString(key: String): OrientationJsonValue<String> = when {
    !has(key) -> OrientationJsonValue.Missing
    isNull(key) -> OrientationJsonValue.Null
    opt(key) is String -> OrientationJsonValue.Value(getString(key))
    else -> OrientationJsonValue.Malformed("$key must be a string")
}

private fun JSONObject.strictOrientationInt(key: String): OrientationJsonValue<Int> = when {
    !has(key) -> OrientationJsonValue.Missing
    isNull(key) -> OrientationJsonValue.Null
    opt(key) is Number -> {
        val value = (opt(key) as Number).toDouble()
        if (!value.isFinite() || value != value.toInt().toDouble()) OrientationJsonValue.Malformed("$key must be an integer")
        else OrientationJsonValue.Value(value.toInt())
    }
    else -> OrientationJsonValue.Malformed("$key must be numeric")
}

private fun JSONObject.strictOrientationBoolean(key: String): OrientationJsonValue<Boolean> = when {
    !has(key) -> OrientationJsonValue.Missing
    isNull(key) -> OrientationJsonValue.Null
    opt(key) is Boolean -> OrientationJsonValue.Value(optBoolean(key))
    else -> OrientationJsonValue.Malformed("$key must be boolean")
}

private fun ensureSafeRotationAllocation(bitmap: Bitmap, degrees: Int) {
    if (degrees == 0) return
    val bytes = bitmap.width.toLong() * bitmap.height.toLong() * 4L
    val runtime = Runtime.getRuntime()
    val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
    require(bytes <= available / 2L) { "Insufficient heap headroom for full-resolution rotation" }
}

private fun recordRawExportRotationEstimate(jobDir: File, degrees: Int) {
    KeplerJobMetadata.update(jobDir) { job -> job.put("estimatedExportRotationDegrees", degrees) }
}

private fun recordRawExportRotationApplied(jobDir: File, degrees: Int) {
    KeplerJobMetadata.update(jobDir) { job ->
        job.put("appliedExportRotationDegrees", degrees)
            .put("rotationAppliedAtExportStage", degrees != 0)
    }
}

@Suppress("SENSELESS_COMPARISON")
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
    processingParams: ClassicYuvFusionParams = ClassicYuvFusionPreset.NATURAL.params,
    displayRotation: Int = android.view.Surface.ROTATION_0,
    captureCancellationHandle: KeplerCaptureCancellationHandle = NoOpKeplerCaptureCancellationHandle,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    onStatus: (String) -> Unit,
    onPipelineEvent: CameraPipelineEventSink = {}
) {
    // RAW public-export outcomes are an outer protocol boundary. The wrapper owns the job
    // operation from capture completion through terminal export metadata; the nested classic RAW
    // ProcessingAttempt borrows that lease and cannot release it early.
    val callbackLedger = ProcessingCallbackOutcomeLedger()
    val callbackDispatcher = ProcessingCallbackDispatcher(
        Handler(Looper.getMainLooper()),
        "KeplerRawPipeline",
        executionObserver = callbackLedger::recordExecution,
        dispatchObserver = callbackLedger::recordDispatch
    )
    fun post(message: String) {
        val result = callbackDispatcher.dispatch { onStatus(message) }
        if (result != ProcessingCallbackDispatchResult.ACCEPTED) {
            Log.w("KeplerRawPipeline", "status dispatch $result")
        }
    }
    val terminal = CameraPipelineTerminalPublisher(onPipelineEvent)
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
        processingParams = processingParams,
        displayRotation = displayRotation,
        saveDngSidecars = finalOutputFormat.shouldExportRawSidecar,
        captureCancellationHandle = captureCancellationHandle,
        onStatus = { post(it) },
        onComplete = { jobDir ->
            try {
                cancellation.throwIfCancelled()
            } catch (_: CancellationException) {
                try {
                    recordNormalPreCommitTerminal(
                        jobDir,
                        attemptStatus = "CANCELLED",
                        pipelineStage = "CANCELLED",
                        processStatus = "EXPORT_CANCELLED_BEFORE_COMMIT",
                        reason = "Capture cancelled before processing started."
                    )
                } catch (metadataError: Exception) {
                    Log.e(
                        "KeplerRawPipeline",
                        "Failed to persist RAW pre-commit cancellation metadata: ${metadataError.message}",
                        metadataError
                    )
                }
                post("PIPELINE_CANCELLED: Capture timed out; background processing stopped.")
                terminal.publish(
                    CameraPipelineEvent.Terminal.Kind.CANCELLED,
                    message = "Capture cancelled before RAW processing started."
                )
                return@captureRawBurstForFusion
            }
            val processingOperation = acquireRawProcessingOperation(jobDir)
            if (processingOperation == null) {
                Log.w("KeplerRawPipeline", "RAW processing operation is already owned; preserving the existing owner.")
                post("PIPELINE_FAILED: RAW processing is already active; existing operation kept.")
                terminal.publish(
                    CameraPipelineEvent.Terminal.Kind.FAILED,
                    message = "RAW processing is already active; existing operation kept."
                )
                return@captureRawBurstForFusion
            }
            try {
                KeplerJobMetadata.update(jobDir) { current ->
                    current.put("captureMode", CaptureMode.MULTI_FRAME.name)
                        .put("processingPresetName", processingParams.presetName)
                        .put("processingParams", processingParams.clamped().toJson())
                }
            } catch (cancelled: CancellationException) {
                processingOperation.release()
                throw cancelled
            } catch (failure: Throwable) {
                if (failure is Error) {
                    processingOperation.release()
                    throw failure
                }
                Log.e("KeplerRawPipeline", "RAW processing metadata initialization failed", failure)
                try {
                    recordRawOuterTerminalFailureWhileOwned(
                        jobDir,
                        processingOperation,
                        "RAW processing metadata initialization failed: ${failure.message}"
                    ) {
                        post("PIPELINE_FAILED: RAW processing metadata initialization failed; RAW cache kept.")
                    }
                } finally {
                    processingOperation.release()
                }
                return@captureRawBurstForFusion
            }
            Log.i("KeplerRawPipeline", "PROCESSING_STARTED jobDirAbsolutePath=${jobDir.absolutePath}")
            post("PROCESSING_STARTED: RAW capture complete; processing started.")
            val thread = try {
                HandlerThread("KeplerRawFusionPipelineThread").apply { start() }
            } catch (cancelled: CancellationException) {
                processingOperation.release()
                throw cancelled
            } catch (failure: Throwable) {
                if (failure is Error) {
                    processingOperation.release()
                    throw failure
                }
                Log.e("KeplerRawPipeline", "RAW processing worker thread could not start", failure)
                try {
                    recordRawOuterTerminalFailureWhileOwned(
                        jobDir,
                        processingOperation,
                        "RAW processing worker could not start"
                    ) {
                        post("PIPELINE_FAILED: RAW processing worker could not start; RAW cache kept.")
                    }
                } finally {
                    processingOperation.release()
                }
                return@captureRawBurstForFusion
            }
            val workerPosted = try {
                Handler(thread.looper).post {
                var capturedProcess: RawFusionProcessResult? = null
                var committedExport: GalleryExportResult? = null
                var exportVerified = false
                var sidecarResult: RawSidecarExportResult? = null
                var publicOutcome: RawFusionPublicExportOutcome? = null
                var postExportCancellationRequested = false
                try {
                    cancellation.throwIfCancelled()
                    val process = processRawFusionJob(
                        context = context,
                        jobDir = jobDir,
                        saveNativeMp24DebugPng = finalOutputFormat.isDebugPng && rawSpeedMode == RawSpeedMode.QUALITY,
                        cancellation = cancellation,
                        operationLease = processingOperation.lease
                    ) { post(it) }
                    capturedProcess = process
                    processingOperation.reassertActiveOperation(KeplerActiveOperationKind.PUBLIC_EXPORT)
                    if (!process.success || !process.hasExportableBitmapSource()) {
                        val reason = process.errorMessage ?: "RAW fusion process failed"
                        publicOutcome = RawFusionPublicExportOutcome.UncommittedFailure(
                            base = process,
                            finalOutputFormat = finalOutputFormat,
                            currentLocalPreview = process.previewPngFile?.takeIf { it.isFile && it.length() > 0L },
                            currentLocalOutput = process.finalPngFile?.takeIf { it.isFile && it.length() > 0L },
                            currentError = reason
                        )
                        try {
                            recordNormalPreCommitTerminal(
                                jobDir,
                                attemptStatus = "FAILED",
                                pipelineStage = "FAILED",
                                processStatus = "EXPORT_FAILED_KEEPING_CACHE",
                                reason = reason,
                                localOutputCommitted = process.outputCommitted
                            )
                        } catch (metadataError: Exception) {
                            Log.e(
                                "KeplerRawPipeline",
                                "Failed to persist RAW pre-commit failure metadata: ${metadataError.message}",
                                metadataError
                            )
                        }
                        post(
                            "PIPELINE_FAILED: RAW Night Fusion failed; keeping RAW cache. $reason"
                        )
                        return@post
                    }
                    val processedJobFile = File(jobDir, JOB_JSON_FILE_NAME)
                    val processedJob = JSONObject(NoFollowFileSystem.readTextVerified(processedJobFile))
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
                    try {
                        resetRawExportAttemptDiagnostics(jobDir)
                    } catch (resetError: Exception) {
                        Log.e(
                            "KeplerRawPipeline",
                            "RAW export attempt diagnostics reset failed; aborting export: ${resetError.message}",
                            resetError
                        )
                        try {
                            recordNormalPreCommitTerminal(
                                jobDir,
                                attemptStatus = "FAILED",
                                pipelineStage = "FAILED",
                                processStatus = "EXPORT_FAILED_KEEPING_CACHE",
                                reason = "Diagnostics reset failed: ${resetError.message}"
                            )
                        } catch (metadataError: Exception) {
                            Log.e(
                                "KeplerRawPipeline",
                                "Failed to persist RAW pre-commit failure metadata: ${metadataError.message}",
                                metadataError
                            )
                        }
                        post("PIPELINE_FAILED: RAW export diagnostics reset failed; keeping RAW cache. ${resetError.message}")
                        return@post
                    }
                    var exportBitmap: Bitmap? = null
                    var exportRotationDegrees: Int? = null
                    val result = try {
                        cancellation.throwIfCancelled()
                        val loaded = process.loadExportBitmap(jobDir)
                        exportBitmap = loaded.bitmap
                        exportRotationDegrees = loaded.appliedRotationDegrees
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
                        recordRawExportRotationEstimate(jobDir, loaded.appliedRotationDegrees)
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
                            cancellation = cancellation,
                            jobDir = jobDir,
                            ownerLease = processingOperation.lease
                        )
                    } finally {
                        exportBitmap?.takeUnless { it.isRecycled }?.recycle()
                    }
                    if (result.success && !result.uriString.isNullOrBlank()) {
                        committedExport = result
                        post("Verifying gallery export...")
                        val committedOutcome =
                            RawFusionPublicExportOutcome.CommittedPendingVerification(
                                base = process,
                                finalOutputFormat = finalOutputFormat,
                                export = result,
                                currentLocalPreview = process.previewPngFile
                                    ?.takeIf { it.isFile && it.length() > 0L },
                                currentLocalOutput = process.finalPngFile
                                    ?.takeIf { it.isFile && it.length() > 0L }
                            )
                        publicOutcome = committedOutcome
                        try {
                            updateRawPublicExportOutcome(jobDir, committedOutcome)
                        } catch (metadataError: Exception) {
                            Log.e(
                                "KeplerRawPipeline",
                                "Normal commit-checkpoint persistence failed; preserving committed outcome in memory: ${metadataError.message}",
                                metadataError
                            )
                            try {
                                val interruptedOutcome =
                                    RawFusionPublicExportOutcome.CommittedInterruptedBeforeVerification(
                                        base = process,
                                        finalOutputFormat = finalOutputFormat,
                                        export = result,
                                        sidecar = null,
                                        currentLocalPreview = process.previewPngFile
                                            ?.takeIf { it.isFile && it.length() > 0L },
                                        currentLocalOutput = process.finalPngFile
                                            ?.takeIf { it.isFile && it.length() > 0L },
                                        currentError = "Commit-checkpoint persistence failed: ${metadataError.message}"
                                    )
                                updateRawPublicExportOutcome(jobDir, interruptedOutcome)
                            } catch (secondMetadataError: Exception) {
                                Log.e(
                                    "KeplerRawPipeline",
                                    "Committed-interrupted persistence also failed: ${secondMetadataError.message}",
                                    secondMetadataError
                                )
                            }
                            post("PIPELINE_FAILED: Commit checkpoint persistence failed; committed URI retained. ${metadataError.message}")
                            return@post
                        }
                    }
                    if (committedExport == null) {
                        val currentProcess = capturedProcess
                        val currentLocalPreview = currentProcess?.previewPngFile?.takeIf { it.isFile && it.length() > 0L }
                        val currentLocalOutput = currentProcess?.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                        if (cancellation.isCancelled) {
                            publicOutcome = RawFusionPublicExportOutcome.UncommittedFailure(
                                base = currentProcess ?: RawFusionProcessResult(success = false, null, null, null, null, "Export cancelled before commit"),
                                finalOutputFormat = finalOutputFormat,
                                currentLocalPreview = currentLocalPreview,
                                currentLocalOutput = currentLocalOutput,
                                currentError = "Export cancelled before MediaStore commit"
                            )
                            try {
                                recordNormalPreCommitTerminal(
                                    jobDir,
                                    attemptStatus = "CANCELLED",
                                    pipelineStage = "CANCELLED",
                                    processStatus = "EXPORT_CANCELLED_BEFORE_COMMIT",
                                    reason = "Export cancelled before MediaStore commit.",
                                    localOutputCommitted = currentProcess?.outputCommitted == true
                                )
                            } catch (metadataError: Exception) {
                                Log.e(
                                    "KeplerRawPipeline",
                                    "Failed to persist RAW pre-commit cancellation metadata: ${metadataError.message}",
                                    metadataError
                                )
                            }
                            post("PIPELINE_CANCELLED: Export cancelled before MediaStore commit. RAW cache kept.")
                            return@post
                        }
                        val error = result.errorMessage ?: "Export failed"
                        publicOutcome = RawFusionPublicExportOutcome.UncommittedFailure(
                            base = currentProcess ?: RawFusionProcessResult(success = false, null, null, null, null, error),
                            finalOutputFormat = finalOutputFormat,
                            currentLocalPreview = currentLocalPreview,
                            currentLocalOutput = currentLocalOutput,
                            currentError = error
                        )
                        try {
                            recordNormalPreCommitTerminal(
                                jobDir,
                                attemptStatus = "FAILED",
                                pipelineStage = "FAILED",
                                processStatus = "EXPORT_FAILED_KEEPING_CACHE",
                                reason = error,
                                localOutputCommitted = currentProcess?.outputCommitted == true
                            )
                        } catch (metadataError: Exception) {
                            Log.e(
                                "KeplerRawPipeline",
                                "Failed to persist RAW pre-commit failure metadata: ${metadataError.message}",
                                metadataError
                            )
                        }
                        post(
                            "PIPELINE_FAILED: RAW export failed; keeping RAW cache. $error"
                        )
                        return@post
                    }
                    if (cancellation.isCancelled) {
                        val proc = capturedProcess
                        val cancelPrevFile = proc?.previewPngFile?.takeIf { it.isFile && it.length() > 0L }
                        val cancelLocalOutput = proc?.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                        val partial = RawFusionPublicExportOutcome.CommittedCancelledBeforeVerification(
                            base = proc ?: RawFusionProcessResult(success = false, null, null, null, null, "Cancelled after commit"),
                            finalOutputFormat = finalOutputFormat,
                            export = committedExport!!,
                            sidecar = null,
                            currentLocalPreview = cancelPrevFile,
                            currentLocalOutput = cancelLocalOutput
                        )
                        publicOutcome = partial
                        updateRawPublicExportOutcome(jobDir, partial)
                        post("PIPELINE_CANCELLED: Export cancelled after MediaStore commit, before verification. RAW cache kept.")
                        return@post
                    }
                    val committedExportUri = committedExport!!.uriString ?: ""
                    val verified = verifyCommittedGalleryExport(context, committedExport!!) is GalleryExportVerification.Verified
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
                        publicOutcome = outcome
                        updateRawPublicExportOutcome(jobDir, outcome)
                        post("PIPELINE_FAILED: RAW export verification failed; keeping RAW cache.")
                        return@post
                    }
                    recordRawExportRotationApplied(jobDir, exportRotationDegrees ?: 0)
                    val verifiedPendingOutcome = RawFusionPublicExportOutcome.VerifiedPendingPostWork(
                        base = process,
                        finalOutputFormat = finalOutputFormat,
                        export = committedExport!!,
                        currentLocalPreview = process.previewPngFile?.takeIf { it.isFile && it.length() > 0L },
                        currentLocalOutput = process.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                    )
                    publicOutcome = verifiedPendingOutcome
                    try {
                        updateRawPublicExportOutcome(jobDir, verifiedPendingOutcome)
                    } catch (checkpointError: Exception) {
                        Log.e(
                            "KeplerRawPipeline",
                            "Verified-pending checkpoint persistence failed; " +
                                "committed=${committedExport != null}, verified=true, " +
                                "uri=${committedExport?.uriString.orEmpty()}, " +
                                "error=${checkpointError.message}",
                            checkpointError
                        )
                        val interruptedSidecar = ensureSidecarResultForPostWorkInterruption(
                            sidecarResult,
                            finalOutputFormat,
                            "Verified-pending checkpoint persistence failed: ${checkpointError.message}"
                        )
                        val interruptedOutcome = RawFusionPublicExportOutcome.VerifiedPostWorkInterrupted(
                            base = process,
                            finalOutputFormat = finalOutputFormat,
                            export = committedExport!!,
                            sidecar = interruptedSidecar,
                            currentLocalPreview = process.previewPngFile?.takeIf { it.isFile && it.length() > 0L },
                            currentLocalOutput = process.finalPngFile?.takeIf { it.isFile && it.length() > 0L },
                            currentWarning = "Verified-pending checkpoint persistence failed; committed image preserved.",
                            currentError = "Verified-pending checkpoint persistence failed: ${checkpointError.message}"
                        )
                        publicOutcome = interruptedOutcome
                        try {
                            updateRawPublicExportOutcome(jobDir, interruptedOutcome)
                        } catch (secondaryOom: OutOfMemoryError) {
                            secondaryOom.addSuppressed(checkpointError)
                            throw secondaryOom
                        } catch (secondaryError: Exception) {
                            Log.e(
                                "KeplerRawPipeline",
                                "Secondary verified-partial metadata write also failed; " +
                                    "committed=${committedExport != null}, verified=true, " +
                                    "uri=${committedExport?.uriString.orEmpty()}, " +
                                    "original=${checkpointError.message}, " +
                                    "secondary=${secondaryError.message}",
                                secondaryError
                            )
                        }
                        post("PIPELINE_COMPLETE_PARTIAL: Verified-pending checkpoint persistence failed; committed image preserved. ${checkpointError.message}")
                        return@post
                    }
                    if (cancellation.isCancelled) {
                        postExportCancellationRequested = true
                        val cancelSidecar = if (finalOutputFormat.shouldExportRawSidecar) {
                            RawSidecarExportResult.cancelled()
                        } else {
                            null
                        }
                        val outcome = RawFusionPublicExportOutcome.VerifiedWithPostExportCancellation(
                            base = process,
                            finalOutputFormat = finalOutputFormat,
                            export = committedExport!!,
                            sidecar = cancelSidecar,
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
                    if (cancellation.isCancelled || sidecarResult?.cancellationRequested == true) {
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
                    publicOutcome = outcome
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
                            publicOutcome = outcome
                            updateRawPublicExportOutcome(jobDir, outcome)
                            post("PIPELINE_COMPLETE_PARTIAL: Image was saved, but optional post-export work was cancelled. RAW cache kept.")
                        } else {
                            val partial = RawFusionPublicExportOutcome.CommittedCancelledBeforeVerification(
                                base = proc ?: RawFusionProcessResult(success = false, null, null, null, null, "Cancelled after commit"),
                                finalOutputFormat = finalOutputFormat,
                                export = committedExport!!,
                                sidecar = sidecarResult,
                                currentLocalPreview = cancelPrevFile,
                                currentLocalOutput = cancelLocalOutput
                            )
                            publicOutcome = partial
                            updateRawPublicExportOutcome(jobDir, partial)
                            post("PIPELINE_CANCELLED: Export cancelled after MediaStore commit, before verification. RAW cache kept.")
                        }
                    } else {
                        try {
                            recordNormalPreCommitTerminal(
                                jobDir,
                                attemptStatus = "CANCELLED",
                                pipelineStage = "CANCELLED",
                                processStatus = "EXPORT_CANCELLED_BEFORE_COMMIT",
                                reason = "Pipeline cancelled before export commit."
                            )
                        } catch (metadataError: Exception) {
                            Log.e(
                                "KeplerRawPipeline",
                                "Failed to persist RAW pre-commit cancellation metadata: ${metadataError.message}",
                                metadataError
                            )
                        }
                        post("PIPELINE_CANCELLED: Capture timed out; background processing stopped.")
                    }
                } catch (oom: OutOfMemoryError) {
                    if (committedExport != null) {
                        val proc = capturedProcess
                        val oomPrevFile = proc?.previewPngFile?.takeIf { it.isFile && it.length() > 0L }
                        val oomLocalOutput = proc?.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                        val oomReason = "OutOfMemoryError after committed export; cache kept"
                        if (exportVerified) {
                            val oomSidecar = ensureSidecarResultForPostWorkInterruption(
                                sidecarResult,
                                finalOutputFormat,
                                oomReason
                            )
                        val interruptedOutcome = RawFusionPublicExportOutcome.VerifiedPostWorkInterrupted(
                                        base = proc ?: RawFusionProcessResult(success = false, null, null, null, null, "OOM after verified export"),
                                        finalOutputFormat = finalOutputFormat,
                                        export = committedExport!!,
                                        sidecar = oomSidecar,
                                        currentLocalPreview = oomPrevFile,
                                        currentLocalOutput = oomLocalOutput,
                                        currentWarning = "OOM after export; committed image preserved.",
                                        currentError = oomReason
                                    )
                            publicOutcome = interruptedOutcome
                            try {
                                updateRawPublicExportOutcome(jobDir, interruptedOutcome)
                            } catch (metadataOom: OutOfMemoryError) {
                                metadataOom.addSuppressed(oom)
                                throw oom
                            } catch (metadataError: Exception) {
                                Log.e(
                                    "KeplerRawPipeline",
                                    "Post-commit OOM metadata persistence failed; " +
                                        "committed=true, verified=true, " +
                                        "uri=${committedExport?.uriString.orEmpty()}, " +
                                        "original=${oom.message}, " +
                                        "metadataError=${metadataError.message}",
                                    metadataError
                                )
                            }
                            post("PIPELINE_COMPLETE_PARTIAL: Image was saved, but post-export work was interrupted by OOM. RAW cache kept.")
                        } else {
                            val interruptedOutcome = RawFusionPublicExportOutcome.CommittedInterruptedBeforeVerification(
                                        base = proc ?: RawFusionProcessResult(success = false, null, null, null, null, "OOM after commit"),
                                        finalOutputFormat = finalOutputFormat,
                                        export = committedExport!!,
                                        sidecar = null,
                                        currentLocalPreview = oomPrevFile,
                                        currentLocalOutput = oomLocalOutput,
                                        currentError = oomReason
                                    )
                            publicOutcome = interruptedOutcome
                            try {
                                updateRawPublicExportOutcome(jobDir, interruptedOutcome)
                            } catch (metadataOom: OutOfMemoryError) {
                                metadataOom.addSuppressed(oom)
                                throw oom
                            } catch (metadataError: Exception) {
                                Log.e(
                                    "KeplerRawPipeline",
                                    "Post-commit OOM metadata persistence failed; " +
                                        "committed=true, verified=false, " +
                                        "uri=${committedExport?.uriString.orEmpty()}, " +
                                        "original=${oom.message}, " +
                                        "metadataError=${metadataError.message}",
                                    metadataError
                                )
                            }
                            post("PIPELINE_FAILED: RAW export committed but verification interrupted by OOM; keeping RAW cache.")
                        }
                    } else {
                        try {
                            recordNormalPreCommitTerminal(
                                jobDir,
                                attemptStatus = "FAILED",
                                pipelineStage = "FAILED",
                                processStatus = "EXPORT_FAILED_KEEPING_CACHE",
                                reason = "OutOfMemoryError during RAW export; cache kept",
                                localOutputCommitted = capturedProcess?.outputCommitted == true
                            )
                        } catch (metadataError: Exception) {
                            Log.e(
                                "KeplerRawPipeline",
                                "Failed to persist RAW pre-commit failure metadata: ${metadataError.message}",
                                metadataError
                            )
                        }
                        post("PIPELINE_FAILED: RAW export ran out of memory; keeping RAW cache.")
                    }
                    throw oom
                } catch (e: Exception) {
                    if (committedExport != null) {
                        val proc = capturedProcess
                        val excPrevFile = proc?.previewPngFile?.takeIf { it.isFile && it.length() > 0L }
                        val excLocalOutput = proc?.finalPngFile?.takeIf { it.isFile && it.length() > 0L }
                        val excReason = "${e.javaClass.simpleName}: ${e.message}"
                        if (exportVerified) {
                            val excSidecar = ensureSidecarResultForPostWorkInterruption(
                                sidecarResult,
                                finalOutputFormat,
                                excReason
                            )
                            val interruptedOutcome = RawFusionPublicExportOutcome.VerifiedPostWorkInterrupted(
                                        base = proc ?: RawFusionProcessResult(success = false, null, null, null, null, "Exception after verified export"),
                                        finalOutputFormat = finalOutputFormat,
                                        export = committedExport!!,
                                        sidecar = excSidecar,
                                        currentLocalPreview = excPrevFile,
                                        currentLocalOutput = excLocalOutput,
                                        currentWarning = excReason,
                                        currentError = excReason
                                    )
                            publicOutcome = interruptedOutcome
                            try {
                                updateRawPublicExportOutcome(jobDir, interruptedOutcome)
                            } catch (metadataOom: OutOfMemoryError) {
                                metadataOom.addSuppressed(e)
                                throw metadataOom
                            } catch (metadataError: Exception) {
                                Log.e(
                                    "KeplerRawPipeline",
                                    "Post-commit exception metadata persistence failed; " +
                                        "committed=true, verified=true, " +
                                        "uri=${committedExport?.uriString.orEmpty()}, " +
                                        "original=$excReason, " +
                                        "metadataError=${metadataError.message}",
                                    metadataError
                                )
                            }
                            post("PIPELINE_COMPLETE_PARTIAL: Image was saved, but post-export work was interrupted by ${e.javaClass.simpleName}. RAW cache kept.")
                        } else {
                            val interruptedOutcome = RawFusionPublicExportOutcome.CommittedInterruptedBeforeVerification(
                                        base = proc ?: RawFusionProcessResult(success = false, null, null, null, null, "Exception after commit"),
                                        finalOutputFormat = finalOutputFormat,
                                        export = committedExport!!,
                                        sidecar = null,
                                        currentLocalPreview = excPrevFile,
                                        currentLocalOutput = excLocalOutput,
                                        currentError = excReason
                                    )
                            publicOutcome = interruptedOutcome
                            try {
                                updateRawPublicExportOutcome(jobDir, interruptedOutcome)
                            } catch (metadataOom: OutOfMemoryError) {
                                metadataOom.addSuppressed(e)
                                throw metadataOom
                            } catch (metadataError: Exception) {
                                Log.e(
                                    "KeplerRawPipeline",
                                    "Post-commit exception metadata persistence failed; " +
                                        "committed=true, verified=false, " +
                                        "uri=${committedExport?.uriString.orEmpty()}, " +
                                        "original=$excReason, " +
                                        "metadataError=${metadataError.message}",
                                    metadataError
                                )
                            }
                            post("PIPELINE_FAILED: RAW export committed but verification interrupted by ${e.javaClass.simpleName}; keeping RAW cache.")
                        }
                    } else {
                        try {
                            recordNormalPreCommitTerminal(
                                jobDir,
                                attemptStatus = "FAILED",
                                pipelineStage = "FAILED",
                                processStatus = "EXPORT_FAILED_KEEPING_CACHE",
                                reason = "${e.javaClass.simpleName}: ${e.message}",
                                localOutputCommitted = capturedProcess?.outputCommitted == true
                            )
                        } catch (metadataError: Exception) {
                            Log.e(
                                "KeplerRawPipeline",
                                "Failed to persist RAW pre-commit failure metadata: ${metadataError.message}",
                                metadataError
                            )
                        }
                        post(
                            "PIPELINE_FAILED: RAW Night Fusion pipeline failed; keeping RAW cache.\n" +
                                e.stackTraceToString()
                        )
                    }
                } finally {
                    terminal.publish(
                        kind = rawFusionOutcomeTerminalKind(
                            publicOutcome,
                            cancellationRequested = cancellation.isCancelled,
                            currentLocalOutput = publicOutcome?.currentLocalOutput
                                ?: capturedProcess?.finalPngFile?.takeIf {
                                    it.isFile && it.length() > 0L
                                }
                        ),
                        requiredOutputCommitted = publicOutcome?.base?.outputCommitted == true ||
                            capturedProcess?.outputCommitted == true ||
                            currentProcessingAttemptHasRequiredOutputClaimForLease(
                                jobDir,
                                processingOperation.lease
                            ),
                        publicExportCommitted = publicOutcome?.committed == true || committedExport != null,
                        verified = publicOutcome?.verified == true || exportVerified,
                        message = "RAW pipeline terminal settlement"
                    )
                    processingOperation.release()
                    thread.quitSafely()
                }
                }
            } catch (failure: Error) {
                processingOperation.release()
                thread.quitSafely()
                throw failure
            } catch (failure: Exception) {
                Log.e("KeplerRawPipeline", "RAW processing worker dispatch failed", failure)
                false
            }
            if (!workerPosted) {
                terminal.publish(
                    CameraPipelineEvent.Terminal.Kind.FAILED,
                    message = "RAW processing worker could not start; RAW cache kept."
                )
                try {
                    recordRawOuterTerminalFailureWhileOwned(
                        jobDir,
                        processingOperation,
                        "RAW processing worker could not start"
                    ) {
                        post("PIPELINE_FAILED: RAW processing worker could not start; RAW cache kept.")
                    }
                } finally {
                    processingOperation.release()
                    thread.quitSafely()
                }
            }
        },
        onError = {
            post("PIPELINE_FAILED: RAW capture failed; keeping cache.\n$it")
            terminal.publish(
                CameraPipelineEvent.Terminal.Kind.FAILED,
                message = "RAW capture failed; keeping cache. $it"
            )
        }
    )
}

@Suppress("SENSELESS_COMPARISON")
internal fun reprocessRawJob(
    context: Context,
    jobDir: File,
    finalOutputFormat: FinalOutputFormat,
    selectedFrameIndices: Set<Int>? = null,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    operationLease: JobOperationLease? = null,
    onStatus: (String) -> Unit
): ReprocessWorkerRun {
    // When called by KeplerGalleryReprocess, operationLease is the outer transaction's lease and
    // remains owned through public-export terminal metadata. Standalone callers receive an
    // equivalent wrapper-owned lease for the full worker lifetime.
    val callbackLedger = ProcessingCallbackOutcomeLedger()
    val callbackDispatcher = ProcessingCallbackDispatcher(
        Handler(Looper.getMainLooper()),
        "KeplerRawReprocess",
        executionObserver = callbackLedger::recordExecution,
        dispatchObserver = callbackLedger::recordDispatch
    )
    fun post(message: String) {
        val result = callbackDispatcher.dispatch { onStatus(message) }
        if (result != ProcessingCallbackDispatchResult.ACCEPTED) {
            Log.w("KeplerRawReprocess", "status dispatch $result")
        }
    }
    val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
    val processingOperation = acquireRawProcessingOperation(jobDir, operationLease)
    if (processingOperation == null) {
        val failure = ProcessingAlreadyActiveException(jobDir)
        val outcome = RawFusionPublicExportOutcome.UncommittedFailure(
            base = RawFusionProcessResult(false, null, null, null, null, failure.message),
            finalOutputFormat = finalOutputFormat,
            currentLocalPreview = null,
            currentLocalOutput = null,
            currentError = failure.message ?: "RAW reprocess is already active"
        )
        terminal.complete(
            ReprocessWorkerOutcome(
                result = Result.failure(failure),
                publicExportCommitted = false,
                terminalError = failure,
                publicOutcome = outcome
            )
        )
        post("PIPELINE_FAILED: RAW reprocess is already active; existing operation kept.")
        return ReprocessWorkerRun(
            terminal = terminal,
            cancel = { (cancellation as? KeplerPipelineCancellationToken)?.cancel() }
        )
    }
    val thread = try {
        HandlerThread("KeplerRawReprocessThread").apply { start() }
    } catch (cancelled: CancellationException) {
        processingOperation.release()
        throw cancelled
    } catch (failure: Throwable) {
        processingOperation.release()
        if (failure is Error) throw failure
        terminal.complete(
            ReprocessWorkerOutcome(
                result = Result.failure(failure),
                publicExportCommitted = false,
                terminalError = failure,
                publicOutcome = RawFusionPublicExportOutcome.UncommittedFailure(
                    base = RawFusionProcessResult(false, null, null, null, null, failure.message),
                    finalOutputFormat = finalOutputFormat,
                    currentLocalPreview = null,
                    currentLocalOutput = null,
                    currentError = failure.message ?: "RAW reprocess worker could not start"
                )
            )
        )
        post("PIPELINE_FAILED: RAW reprocess worker could not start; cache kept.")
        return ReprocessWorkerRun(
            terminal = terminal,
            cancel = { (cancellation as? KeplerPipelineCancellationToken)?.cancel() }
        )
    }
    val workerPosted = try {
        Handler(thread.looper).post {
        var terminalResult: Result<Unit> = Result.failure(IllegalStateException("RAW reprocess did not reach a terminal state."))
        var fatalReprocessFailure: Error? = null
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
            enabledCount = try {
                getEnabledRawFrames(jobDir).size
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                0
            }
            totalCount = try {
                loadJobJson(jobDir).optJSONArray("frames")?.length() ?: 0
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                0
            }
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
                cancellation = cancellation,
                metadataPolicy = ReprocessMetadataPolicy.REPROCESS_PROGRESS_ONLY,
                operationLease = processingOperation.lease
            ) { post(it) }
            capturedProcess = process
            processingOperation.reassertActiveOperation(KeplerActiveOperationKind.PUBLIC_EXPORT)
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
            try {
                resetRawExportAttemptDiagnostics(jobDir)
            } catch (resetError: Exception) {
                Log.e(
                    "KeplerRawPipeline",
                    "RAW reprocess diagnostics reset failed; aborting export: ${resetError.message}",
                    resetError
                )
                post("RAW reprocess diagnostics reset failed; source frames kept. ${resetError.message}")
                terminalResult = Result.failure(resetError)
                return@post
            }
            var exportBitmap: Bitmap? = null
            var exportRotationDegrees: Int? = null
            val exportAttempted = try {
                val loaded = process.loadExportBitmap(jobDir)
                exportBitmap = loaded.bitmap
                exportRotationDegrees = loaded.appliedRotationDegrees
                updateRawExportBitmapMetadata(
                    jobDir = jobDir,
                    source = loaded.source,
                    nativeRgbaDirectExportUsed = loaded.nativeRgbaDirect,
                    nativeRgbaBitmapLoadedForExport = loaded.nativeRgbaDirect,
                    finalPngDecodeSkippedForExport = loaded.nativeRgbaDirect,
                    exportBitmapWidth = loaded.bitmap.width,
                    exportBitmapHeight = loaded.bitmap.height
                )
                recordRawExportRotationEstimate(jobDir, loaded.appliedRotationDegrees)
                exportNightFusionBitmapToGallery(
                    context = context,
                    bitmap = loaded.bitmap,
                    displayNameBase = "Kepler_RAW_REPROCESS_${
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    }",
                    requestedFormat = requestedFormat,
                    cancellation = cancellation,
                    jobDir = jobDir,
                    ownerLease = processingOperation.lease
                )
            } catch (ce: CancellationException) {
                exportBitmap?.takeUnless { it.isRecycled }?.recycle()
                exportBitmap = null
                throw ce
            } catch (fatal: Error) {
                exportBitmap?.takeUnless { it.isRecycled }?.recycle()
                exportBitmap = null
                throw fatal
            } catch (exportError: Exception) {
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
                val pendingOutcome = RawFusionPublicExportOutcome.CommittedPendingVerification(
                    base = process,
                    finalOutputFormat = finalOutputFormat,
                    export = exportAttempted,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile
                )
                publicOutcome = pendingOutcome
                try {
                    persistReprocessCommitCheckpoint(jobDir, exportAttempted)
                } catch (checkpointError: Exception) {
                    Log.e(
                        "KeplerRawPipeline",
                        "Reprocess commit-checkpoint persistence failed; preserving committed outcome in memory: ${checkpointError.message}",
                        checkpointError
                    )
                    terminalResult = Result.failure(checkpointError)
                    return@post
                }
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
            if (cancellation.isCancelled) {
                publicOutcome = RawFusionPublicExportOutcome.CommittedCancelledBeforeVerification(
                    base = process,
                    finalOutputFormat = finalOutputFormat,
                    export = committedExport!!,
                    sidecar = null,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile
                )
                terminalResult = Result.failure(IllegalStateException("RAW reprocess cancelled after commit, before verification"))
                return@post
            }
            val verified = verifyCommittedGalleryExport(context, committedExport!!) is GalleryExportVerification.Verified
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
            recordRawExportRotationApplied(jobDir, exportRotationDegrees ?: 0)
            val verifiedOutcome = RawFusionPublicExportOutcome.VerifiedSuccess(
                base = process,
                finalOutputFormat = finalOutputFormat,
                export = committedExport!!,
                sidecar = null,
                currentLocalPreview = currentPreviewFile,
                currentLocalOutput = currentOutputFile
            )
            publicOutcome = verifiedOutcome
            try {
                markReprocessCommitCheckpointVerified(jobDir)
            } catch (checkpointError: Exception) {
                Log.e(
                    "KeplerRawPipeline",
                    "Reprocess verified-checkpoint persistence failed; preserving verified outcome in memory: ${checkpointError.message}",
                    checkpointError
                )
                terminalResult = Result.failure(checkpointError)
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
            if (cancellation.isCancelled || reprocessSidecarResult?.cancellationRequested == true) {
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
        } catch (ce: kotlinx.coroutines.CancellationException) {
            if (publicOutcome == null || !publicOutcome!!.committed) {
                post("PIPELINE_CANCELLED: RAW reprocess cancelled; source frames kept.")
                publicOutcome = publicOutcome ?: RawFusionPublicExportOutcome.UncommittedFailure(
                    base = capturedProcess ?: RawFusionProcessResult(success = false, null, null, null, null, "Reprocess cancelled"),
                    finalOutputFormat = finalOutputFormat,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile,
                    currentError = "RAW reprocess cancelled"
                )
                terminalResult = Result.failure(ce)
            } else if (publicOutcome!!.verified) {
                // Already reached verified-with-cancellation inside the try; outcome is already set.
                terminalResult = Result.success(Unit)
            } else {
                publicOutcome = RawFusionPublicExportOutcome.CommittedCancelledBeforeVerification(
                    base = capturedProcess ?: RawFusionProcessResult(success = false, null, null, null, null, "Reprocess cancelled after commit"),
                    finalOutputFormat = finalOutputFormat,
                    export = publicOutcome!!.export!!,
                    sidecar = publicOutcome!!.sidecar,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile
                )
                terminalResult = Result.failure(ce)
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
            fatalReprocessFailure = oom
            terminalResult = Result.success(Unit)
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
            processingOperation.release()
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
                    terminalError = fatalReprocessFailure ?: terminalResult.exceptionOrNull(),
                    sidecar = resolved.sidecar,
                    postExportCancellationRequested = resolved.postExportCancellationRequested,
                    postExportWorkSkipped = resolved.postExportWorkSkipped,
                    currentLocalPreview = currentPreviewFile,
                    currentLocalOutput = currentOutputFile,
                    publicOutcome = resolved
                )
            )
        }
        fatalReprocessFailure?.let { throw it }
    }
    } catch (failure: Error) {
        processingOperation.release()
        thread.quitSafely()
        throw failure
    } catch (failure: Exception) {
        Log.e("KeplerRawReprocess", "RAW reprocess worker dispatch failed", failure)
        false
    }
    if (!workerPosted) {
        thread.quitSafely()
        processingOperation.release()
        val failure = IllegalStateException("RAW reprocess worker could not start")
        terminal.complete(
            ReprocessWorkerOutcome(
                result = Result.failure(failure),
                publicExportCommitted = false,
                terminalError = failure,
                publicOutcome = RawFusionPublicExportOutcome.UncommittedFailure(
                    base = RawFusionProcessResult(success = false, null, null, null, null, failure.message),
                    finalOutputFormat = finalOutputFormat,
                    currentLocalPreview = null,
                    currentLocalOutput = null,
                    currentError = failure.message ?: "RAW reprocess worker could not start"
                )
            )
        )
        post("PIPELINE_FAILED: RAW reprocess worker could not start; cache kept.")
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
    try {
        KeplerJobMetadata.update(jobDir) { job ->
            job.put("rawPublicExportAttemptStatus", status)
                .put("rawPublicExportAttemptError", error)
                .put("rawPublicExportAttemptAt", System.currentTimeMillis())
        }
    } catch (metadataError: Error) {
        throw metadataError
    } catch (metadataError: Exception) {
        Log.e(
            "KeplerRawPipeline",
            "Failed to persist RAW export attempt failure: ${metadataError.message}",
            metadataError
        )
    }
}

/**
 * Persist a NORMAL pre-commit failure or cancellation state in a single [KeplerJobMetadata.update]
 * call. Writes only the narrow attempt-status/error/timestamp keys, the terminal pipeline
 * stage, process status, and `userCanMoveDevice=true`. Does NOT touch export URI, verification,
 * format, timestamp, linkage, sidecar, or warning fields — so a pre-commit failure can never
 * overwrite an earlier verified public export.
 *
 * Propagates metadata persistence failure to the caller. Callers must handle the exception,
 * log or surface the secondary metadata error explicitly, and preserve the original
 * processing/cancellation error.
 *
 * @param attemptStatus the attempt status string (e.g. "FAILED", "CANCELLED")
 * @param pipelineStage the terminal pipeline stage (e.g. "FAILED", "CANCELLED")
 * @param processStatus the terminal process status (e.g. "EXPORT_FAILED_KEEPING_CACHE", "EXPORT_CANCELLED_BEFORE_COMMIT")
 * @param reason the error or cancellation reason
 * @throws Exception if metadata persistence fails
 */
private fun recordNormalPreCommitTerminal(
    jobDir: File,
    attemptStatus: String,
    pipelineStage: String,
    processStatus: String,
    reason: String,
    localOutputCommitted: Boolean = false
) {
    KeplerJobMetadata.update(jobDir) { job ->
        val effectivePartial = localOutputCommitted
        job.put("rawPublicExportAttemptStatus", if (effectivePartial) "PARTIAL" else attemptStatus)
            .put("rawPublicExportAttemptError", reason)
            .put("rawPublicExportAttemptAt", System.currentTimeMillis())
            .put("currentPipelineStage", if (effectivePartial) "PIPELINE_COMPLETE_PARTIAL" else pipelineStage)
            .put("processStatus", if (effectivePartial) "LOCAL_OUTPUT_COMMITTED_EXPORT_FAILED" else processStatus)
            .put("userCanMoveDevice", true)
    }
}

/**
 * Create a failed [RawSidecarExportResult] for post-work interruption when RAW sidecars were
 * requested but no sidecar result was produced before the interruption. Preserves an existing
 * result unchanged.
 */
private fun ensureSidecarResultForPostWorkInterruption(
    sidecarResult: RawSidecarExportResult?,
    finalOutputFormat: FinalOutputFormat,
    reason: String
): RawSidecarExportResult {
    if (sidecarResult != null) return sidecarResult
    if (!finalOutputFormat.shouldExportRawSidecar) return RawSidecarExportResult.SKIPPED
    return RawSidecarExportResult.failed(reason)
}

/**
 * Persist a reprocess commit-checkpoint immediately after the MediaStore commit succeeds,
 * before verification or sidecar export. Ensures a post-commit crash never loses the
 * committed export identity. The shared finalizer clears these fields after safe terminal
 * persistence.
 *
 * @throws Exception if metadata persistence fails; caller must handle by preserving the
 * committed outcome in memory and terminating through the shared finalizer.
 */
private fun persistReprocessCommitCheckpoint(jobDir: File, export: GalleryExportResult) {
    KeplerJobMetadata.update(jobDir) { job ->
        job.put("reprocessPublicCommitCheckpointUri", export.uriString ?: JSONObject.NULL)
            .put("reprocessPublicCommitCheckpointDisplayName", export.displayName ?: JSONObject.NULL)
            .put("reprocessPublicCommitCheckpointMimeType", export.mimeType ?: JSONObject.NULL)
            .put("reprocessPublicCommitCheckpointFileSizeBytes", export.fileSizeBytes)
            .put("reprocessPublicCommitCheckpointCommitted", true)
            .put("reprocessPublicCommitCheckpointVerified", false)
            .put("reprocessPublicCommitCheckpointAt", System.currentTimeMillis())
    }
}

/**
 * Mark the reprocess commit-checkpoint as verified after successful export verification.
 * Called immediately after [verifyGalleryExport] returns true. Any subsequent OOM,
 * exception, or cancellation must preserve `verified=true`.
 *
 * @throws Exception if metadata persistence fails; caller must handle by preserving the
 * verified outcome in memory and terminating through the shared finalizer.
 */
private fun markReprocessCommitCheckpointVerified(jobDir: File) {
    KeplerJobMetadata.update(jobDir) { job ->
        job.put("reprocessPublicCommitCheckpointVerified", true)
    }
}

/**
 * Clear reprocess commit-checkpoint fields after safe terminal persistence.
 * Called by the shared finalizer once the committed/verified state is durably written.
 *
 * @throws Exception if metadata persistence fails; caller must quarantine the job
 * instead of silently claiming the checkpoint was consumed.
 */
internal fun clearReprocessCommitCheckpoint(jobDir: File) {
    KeplerJobMetadata.update(jobDir) { job ->
        job.remove("reprocessPublicCommitCheckpointUri")
        job.remove("reprocessPublicCommitCheckpointDisplayName")
        job.remove("reprocessPublicCommitCheckpointMimeType")
        job.remove("reprocessPublicCommitCheckpointFileSizeBytes")
        job.remove("reprocessPublicCommitCheckpointCommitted")
        job.remove("reprocessPublicCommitCheckpointVerified")
        job.remove("reprocessPublicCommitCheckpointAt")
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
    var finalPreview: Bitmap? = null
    var referencePreview: Bitmap? = null
    try {
        finalPreview = saveBoundedDiagnosticPreview(bitmap, File(jobDir, "final_preview.png"))
        referencePreview = finalPreview.copy(Bitmap.Config.ARGB_8888, false)
        val refFile = saveBoundedDiagnosticPreview(referencePreview, File(jobDir, "reference_single_preview.png"))
        refFile.takeUnless { it === referencePreview }?.recycle()
        val fusedFile = saveBoundedDiagnosticPreview(finalPreview, File(jobDir, "fused_before_denoise_preview.png"))
        fusedFile.takeUnless { it === finalPreview }?.recycle()
        val denoisedFile = saveBoundedDiagnosticPreview(finalPreview, File(jobDir, "fused_after_denoise_no_sharpen_preview.png"))
        denoisedFile.takeUnless { it === finalPreview }?.recycle()
        val diagnosticMetrics = writeFusionQualityDiagnostics(
            job = JSONObject(),
            jobDir = jobDir,
            prefix = "raw",
            reference = referencePreview,
            fused = finalPreview,
            denoised = finalPreview,
            finalImage = finalPreview,
            compareFileName = "compare_reference_vs_final.png"
        )
        KeplerJobMetadata.update(jobDir) { job ->
            val metrics = diagnosticMetrics.metrics
            metrics.keys().forEach { key -> job.put(key, metrics.get(key)) }
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
                .put("rawQualityDiagnosticStatus", "COMPLETE")
                .put("rawQualityDiagnosticAt", System.currentTimeMillis())
            job.remove("rawQualityDiagnosticError")
        }
    } catch (oom: OutOfMemoryError) {
        Log.e(
            "KeplerRawPipeline",
            "OOM during pre-commit optional RAW quality diagnostics; propagating fatal error",
            oom
        )
        throw oom
    } catch (e: Exception) {
        Log.e(
            "KeplerRawPipeline",
            "Pre-commit optional RAW quality diagnostics failed; recording failure",
            e
        )
        try {
            KeplerJobMetadata.update(jobDir) { job ->
                job.put("rawQualityDiagnosticStatus", "FAILED")
                    .put("rawQualityDiagnosticError", "${e.javaClass.simpleName}: ${e.message}")
                    .put("rawQualityDiagnosticAt", System.currentTimeMillis())
            }
        } catch (metadataOom: OutOfMemoryError) {
            metadataOom.addSuppressed(e)
            throw metadataOom
        } catch (metadataError: Exception) {
            Log.e(
                "KeplerRawPipeline",
                "Secondary failure while persisting diagnostic failure status during pre-commit quality diagnostics; " +
                    "metadataError=${metadataError.message}",
                metadataError
            )
        }
    } finally {
        referencePreview?.takeUnless { it.isRecycled }?.recycle()
        finalPreview?.takeUnless { it.isRecycled }?.recycle()
    }
}
