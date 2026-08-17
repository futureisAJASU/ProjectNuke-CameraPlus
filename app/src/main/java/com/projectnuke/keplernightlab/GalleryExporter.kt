package com.projectnuke.keplernightlab

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.heifwriter.HeifWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal var mediaStoreAbandonDeleteFailureForTest: Throwable? = null
/**
 * Test seam for the provider cut where changing IS_PENDING may apply its side effect and then
 * throw.  null means use the real provider query; true/false explicitly reports the resulting
 * public state (true = non-pending row, false = still pending/absent).
 */
internal var mediaStorePublicCommitStateForTest: ((Uri) -> Boolean?)? = null

enum class GalleryExportCommitState {
    NOT_COMMITTED,
    PUBLIC_COMMITTED_UNVERIFIED,
    VERIFIED,
    UNKNOWN
}

data class GalleryExportResult(
    val success: Boolean,
    val uriString: String?,
    val displayName: String?,
    val mimeType: String?,
    val fileSizeBytes: Long,
    val formatUsed: OutputFormat,
    val fallbackUsed: Boolean,
    val errorMessage: String?,
    val attemptedFormats: List<OutputFormat> = listOf(formatUsed),
    val candidateFailureReasons: List<String> = emptyList(),
    val verification: GalleryExportVerification? = null,
    /** Explicit public commit state; [success] remains the verified-success compatibility flag. */
    val publicCommitState: GalleryExportCommitState = when {
        success && !uriString.isNullOrBlank() -> GalleryExportCommitState.VERIFIED
        else -> GalleryExportCommitState.NOT_COMMITTED
    }
) {
    val publicCommitted: Boolean
        get() = publicCommitState == GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED ||
            publicCommitState == GalleryExportCommitState.VERIFIED

    val publicCommitKnown: Boolean
        get() = publicCommitState != GalleryExportCommitState.UNKNOWN
}
/**
 * Result of a terminal-journal persistence pass. The PUBLIC_EXPORT owner may release its
 * durable marker only when the pass is [SETTLED]; [DEFERRED] means an exact owner-correlated
 * journal still requires settlement (evidence match, provider resolution, or a pending
 * acknowledgment), so ACTIVE and the lease must be retained for the next acquisition/settlement.
 */
internal enum class MediaStoreExportTerminalSettlementStatus {
    SETTLED,
    DEFERRED
}

/**
 * A journal whose durable record has reached a terminal-stable cut: either its terminal metadata
 * acknowledgement was persisted, or its pre-commit/no-row evidence is proven. [VERIFIED] and
 * [PUBLIC_COMMITTED] journals remain unstable until their terminal ACK is persisted, because the
 * acknowledgement itself is the only durable proof that terminal metadata was written for them.
 */
internal fun MediaStoreExportJournal.isTerminallyStable(): Boolean =
    terminalMetadataPersisted || state in setOf(
        MediaStoreExportState.CLEANED,
        MediaStoreExportState.INSERT_FAILED_NO_ROW
    )

/** Journals in this state band had a public commit reached or attempted; a lagging journal in
 *  this band can never be classified as definite precommit without provider authority. */
internal fun MediaStoreExportJournal.requiresExternalCommitResolution(): Boolean =
    !isTerminallyStable() &&
        state in setOf(
            MediaStoreExportState.ROW_INSERTED,
            MediaStoreExportState.CONTENT_WRITTEN
        ) &&
        !uri.isNullOrBlank()

data class RawSidecarExportResult(
    val success: Boolean,
    val exportedFiles: List<String>,
    val errorMessage: String?,
    val kind: RawSidecarOutcomeKind,
    val cancellationRequested: Boolean = false,
    val expectedCount: Int = 0,
    val locallySavedCount: Int = 0,
    val publicExportedCount: Int = exportedFiles.size,
    val missingFilenames: List<String> = emptyList(),
    val localFailures: List<String> = emptyList(),
    val publicFailures: List<String> = emptyList(),
    val requestedCount: Int = expectedCount,
    val localFailedCount: Int = localFailures.size,
    val publicFailedCount: Int = publicFailures.size,
    val frameResults: List<RawSidecarFrameResult> = emptyList()
) {
    /** Public-export status string persisted alongside the image. */
    val status: String get() = when (kind) {
        RawSidecarOutcomeKind.COMPLETE -> "EXPORTED"
        RawSidecarOutcomeKind.PARTIAL -> "PARTIAL"
        RawSidecarOutcomeKind.FAILED -> "FAILED"
        RawSidecarOutcomeKind.SKIPPED -> "SKIPPED"
        RawSidecarOutcomeKind.UNAVAILABLE -> "UNAVAILABLE"
        RawSidecarOutcomeKind.CANCELLED -> "CANCELLED"
    }

    companion object {
        val SKIPPED = RawSidecarExportResult(
            success = false,
            exportedFiles = emptyList(),
            errorMessage = null,
            kind = RawSidecarOutcomeKind.SKIPPED
        )
        val UNAVAILABLE = RawSidecarExportResult(
            success = false,
            exportedFiles = emptyList(),
            errorMessage = "RAW sidecar unavailable for YUV pipeline.",
            kind = RawSidecarOutcomeKind.UNAVAILABLE
        )
        fun complete(exportedFiles: List<String>) = RawSidecarExportResult(
            success = true,
            exportedFiles = exportedFiles,
            errorMessage = null,
            kind = RawSidecarOutcomeKind.COMPLETE
        )
        fun partial(exportedFiles: List<String>, errorMessage: String) = RawSidecarExportResult(
            success = true,
            exportedFiles = exportedFiles,
            errorMessage = errorMessage,
            kind = RawSidecarOutcomeKind.PARTIAL
        )
        fun failed(errorMessage: String) = RawSidecarExportResult(
            success = false,
            exportedFiles = emptyList(),
            errorMessage = errorMessage,
            kind = RawSidecarOutcomeKind.FAILED
        )
        fun cancelled() = RawSidecarExportResult(
            success = false,
            exportedFiles = emptyList(),
            errorMessage = "RAW sidecar export cancelled before any DNG commit.",
            kind = RawSidecarOutcomeKind.CANCELLED,
            cancellationRequested = true
        )
    }
}

enum class RawSidecarOutcomeKind {
    COMPLETE,
    PARTIAL,
    FAILED,
    SKIPPED,
    UNAVAILABLE,
    CANCELLED
}

fun exportNightFusionBitmapToGallery(
    context: Context,
    bitmap: Bitmap,
    displayNameBase: String,
    requestedFormat: OutputFormat,
    relativeAlbumPath: String = "Pictures/Kepler",
    quality: Int = 92,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    jobDir: File? = null,
    ownerLease: JobOperationLease? = null
): GalleryExportResult {
    val exportOperationId = if (jobDir != null && NoFollowFileSystem.resolveDirectChild(jobDir, JOB_JSON_FILE_NAME, requireFile = true) != null) {
        val existingOperationId = ownerLease?.currentDurableOperationId()
        if (existingOperationId != null) {
            // Public export is a phase transition of the exact enclosing operation.  Reusing the
            // lease's current ID keeps PROCESSING_YUV/PROCESSING_SR/PROCESSING_RAW from being
            // replaced by an ownerless nested PUBLIC_EXPORT ID.
            KeplerJobMetadata.beginActiveOperation(
                jobDir,
                operationId = existingOperationId,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = ownerLease
            )
        } else {
            KeplerJobMetadata.beginActiveOperation(
                jobDir,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = ownerLease
            )
        }
    } else null
    val attempts = when (requestedFormat) {
        OutputFormat.HEIF -> listOf(OutputFormat.HEIF, OutputFormat.JPEG, OutputFormat.PNG)
        OutputFormat.JPEG -> listOf(OutputFormat.JPEG, OutputFormat.PNG)
        OutputFormat.PNG -> listOf(OutputFormat.PNG)
    }
    val errors = mutableListOf<String>()
    attempts.forEach { format ->
        cancellation.throwIfCancelled()
        val result = writeGalleryBitmap(
            context = context,
            bitmap = bitmap,
            displayName = "$displayNameBase.${format.extension}",
            format = format,
            relativeAlbumPath = relativeAlbumPath,
            quality = quality,
            fallbackUsed = format != requestedFormat,
            cancellation = cancellation,
            jobDir = jobDir,
            ownerOperationId = exportOperationId
        )
        if (!result.success && result.publicCommitState == GalleryExportCommitState.NOT_COMMITTED) {
            cancellation.throwIfCancelled()
        }
        if (result.publicCommitState != GalleryExportCommitState.NOT_COMMITTED) {
            return result.copy(
                attemptedFormats = attempts.takeWhile { it != format } + format,
                candidateFailureReasons = errors.toList()
            )
        }
        errors += "${format.label}: ${result.errorMessage}"
    }
    return GalleryExportResult(
        success = false,
        uriString = null,
        displayName = null,
        mimeType = null,
        fileSizeBytes = 0L,
        formatUsed = requestedFormat,
        fallbackUsed = false,
        errorMessage = errors.joinToString("; "),
        attemptedFormats = attempts,
        candidateFailureReasons = errors
    )
}

internal data class OwnedPublicExportEvidence(
    val operationId: String,
    val committed: Boolean,
    val verified: Boolean,
    val uri: String?
)

internal enum class PublicExportInterruptionDisposition {
    CANCELLED,
    FAILED
}

private fun JobOperationLease.registerPublicExportSettlement(
    operationId: String,
    failureMessage: String,
    finalOutputFormat: FinalOutputFormat?,
    disposition: PublicExportInterruptionDisposition
) {
    markPublicExportSettlementPending(
        PendingPublicExportSettlement(
            operationId = operationId,
            failureMessage = failureMessage,
            finalOutputFormat = finalOutputFormat,
            disposition = disposition
        )
    )
}

internal fun publicExportInterruptionTerminalKind(
    evidence: OwnedPublicExportEvidence?,
    cancellationRequested: Boolean,
    committedFallback: Boolean = false,
    requiredOutputCommitted: Boolean = false
): CameraPipelineEvent.Terminal.Kind = when {
    evidence?.committed == true || committedFallback || requiredOutputCommitted -> CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL
    cancellationRequested -> CameraPipelineEvent.Terminal.Kind.CANCELLED
    else -> CameraPipelineEvent.Terminal.Kind.FAILED
}

internal fun exportOutcomeTerminalKind(
    requiredOutputCommitted: Boolean,
    publicExportCommitted: Boolean,
    verified: Boolean
): CameraPipelineEvent.Terminal.Kind = if (
    requiredOutputCommitted || publicExportCommitted
) {
    CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL
} else {
    CameraPipelineEvent.Terminal.Kind.FAILED
}

/** Reads only evidence owned by the exact current PUBLIC_EXPORT operation. */
internal fun inspectOwnedPublicExportEvidence(
    jobDir: File,
    ownerLease: JobOperationLease,
    allowDeadOwner: Boolean = false
): OwnedPublicExportEvidence? {
    check(KeplerJobMetadata.isOperationOwner(jobDir, ownerLease)) {
        "Public export evidence requires the exact owning lease"
    }
    val metadata = KeplerJobMetadata.read(jobDir)
    val operationId = metadata.optString(ACTIVE_OPERATION_ID)
    if (operationId.isBlank() || metadata.optString(ACTIVE_OPERATION_KIND) != KeplerActiveOperationKind.PUBLIC_EXPORT.name) {
        return null
    }
    if (!allowDeadOwner) {
        check(metadata.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id) {
            "Public export evidence requires a current-runtime owner"
        }
    } else {
        check(metadata.optString(ACTIVE_RUNTIME_SESSION_ID) != KeplerRuntimeSession.id) {
            "Public export evidence for a retained dead owner must not borrow a live owner"
        }
    }
    val main = MediaStoreExportJournal.list(jobDir)
        .asSequence()
        .filter { it.ownerOperationId == operationId && it.role == MediaStoreExportRole.MAIN_IMAGE }
        .maxByOrNull { it.updatedAt }
    val hasUri = !main?.uri.isNullOrBlank()
    val verified = hasUri && main?.state == MediaStoreExportState.VERIFIED
    val committed = hasUri && (verified || main?.state == MediaStoreExportState.PUBLIC_COMMITTED)
    return OwnedPublicExportEvidence(
        operationId = operationId,
        committed = committed,
        verified = verified,
        uri = main?.uri?.takeIf { committed && it.isNotBlank() }
    )
}

/**
 * Settles a PUBLIC_EXPORT marker while the enclosing pipeline still owns its lease.
 * The export journal is the authority for commit progress when the caller did not
 * receive a GalleryExportResult (for example, cancellation after IS_PENDING=0).
 */
internal fun settleOwnedPublicExportInterruption(
    jobDir: File,
    ownerLease: JobOperationLease,
    failureMessage: String,
    finalOutputFormat: FinalOutputFormat? = null,
    disposition: PublicExportInterruptionDisposition = PublicExportInterruptionDisposition.FAILED,
    access: MediaStoreExportRecoveryAccess? = null,
    allowDeadOwner: Boolean = false
): Boolean {
    check(KeplerJobMetadata.isOperationOwner(jobDir, ownerLease)) {
        "Public export settlement requires the exact owning lease"
    }
    val rememberedOperationId = ownerLease.currentDurableOperationId()
    if (rememberedOperationId != null &&
        ownerLease.currentDurableOperationKind() == KeplerActiveOperationKind.PUBLIC_EXPORT
    ) {
        ownerLease.registerPublicExportSettlement(
            operationId = rememberedOperationId,
            failureMessage = failureMessage,
            finalOutputFormat = finalOutputFormat,
            disposition = disposition
        )
    }
    val metadata = KeplerJobMetadata.read(jobDir)
    val operationId = metadata.optString(ACTIVE_OPERATION_ID)
    if (operationId.isBlank() || metadata.optString(ACTIVE_OPERATION_KIND) != KeplerActiveOperationKind.PUBLIC_EXPORT.name) {
        ownerLease.pendingPublicExportSettlement()?.let {
            ownerLease.completePublicExportSettlement(it.operationId)
        }
        if (operationId.isBlank()) rememberedOperationId?.let(ownerLease::clearDurableOperation)
        return true
    }
    if (allowDeadOwner) {
        check(metadata.optString(ACTIVE_RUNTIME_SESSION_ID) != KeplerRuntimeSession.id) {
            "Public export settlement for a retained dead owner must not borrow a live owner"
        }
    } else {
        check(metadata.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id) {
            "Public export settlement requires a current-runtime owner"
        }
    }
    ownerLease.registerPublicExportSettlement(
        operationId = operationId,
        failureMessage = failureMessage,
        finalOutputFormat = finalOutputFormat,
        disposition = disposition
    )
    val invalid = MediaStoreExportJournal.invalidFiles(jobDir)
    val activeStartedAt = metadata.optLong(ACTIVE_OPERATION_STARTED_AT, 0L)
    val currentInvalid = invalid.filter { activeStartedAt <= 0L || it.lastModified() >= activeStartedAt }
    // A malformed file created before this operation is historical forensic
    // debt.  It must not turn the real zero-journal pre-commit cut into an
    // ambiguous export.  Only malformed evidence correlated to this owner's
    // lifetime remains fail-closed.
    check(currentInvalid.isEmpty()) {
        "Invalid export evidence may belong to the current public export operation"
    }

    // Exact journal reconciliation before any pre-commit classification.  A journal in
    // ROW_INSERTED/CONTENT_WRITTEN/PUBLIC_COMMITTED with an exact URI means a public commit was
    // reached or attempted; the journal may legitimately lag the provider (for example the
    // IS_PENDING=0 update applied before the PUBLIC_COMMITTED journal write).  A lagging journal
    // alone is NEVER definite PRE_COMMIT.
    var ownerJournals = MediaStoreExportJournal.list(jobDir)
        .filter { it.ownerOperationId == operationId }
    if (ownerJournals.any { it.requiresExternalCommitResolution() }) {
        if (access == null) {
            // No durable external authority in this cut: the exact candidate may already be
            // publicly committed.  Never classify definite PRE_COMMIT solely from a lagging
            // journal, never delete/rollback the exact candidate, never terminal-ack it, and
            // never clear owner E.  The journals/metadata/lease retain the UNKNOWN debt.
            return false
        }
        val reconciled = recoverMediaStoreExportJournals(jobDir, access)
        val conclusivePreCommit = reconciled
            .filter { result ->
                result.classification in setOf(
                    MediaStoreExportRecoveryClassification.PUBLIC_COMMIT_MISSING,
                    MediaStoreExportRecoveryClassification.PENDING_DELETED,
                    MediaStoreExportRecoveryClassification.CLEANED
                )
            }
            .mapTo(hashSetOf()) { it.attemptId }
        ownerJournals = MediaStoreExportJournal.list(jobDir)
            .filter { it.ownerOperationId == operationId }
        ownerJournals
            .filter { candidate ->
                candidate.exportAttemptId in conclusivePreCommit &&
                    candidate.state in setOf(
                        MediaStoreExportState.ROW_INSERTED,
                        MediaStoreExportState.CONTENT_WRITTEN
                    )
            }
            .forEach { it.transition(jobDir, MediaStoreExportState.CLEANED) }
        ownerJournals = MediaStoreExportJournal.list(jobDir)
            .filter { it.ownerOperationId == operationId }
        if (ownerJournals.any { it.requiresExternalCommitResolution() }) {
            // Provider inspection was inconclusive for an exact candidate (AMBIGUOUS /
            // INSERT_RESULT_UNKNOWN / DELETE_FAILED / verified-row missing): the cut remains
            // unresolved COMMIT_RESOLUTION_REQUIRED debt.
            return false
        }
    }

    // Re-read the durable record after provider reconciliation so the evidence reflects the
    // authoritative external state (a lagging journal caught up to PUBLIC_COMMITTED/VERIFIED).
    val evidence = inspectOwnedPublicExportEvidence(jobDir, ownerLease, allowDeadOwner)
        ?: return true

    // Durable terminal metadata must be written before journal acknowledgement.
    // The active marker remains until both writes succeed, so the exact lease can
    // be retained if either persistence boundary fails.
    KeplerJobMetadata.update(jobDir) { job ->
        check(job.optString(ACTIVE_OPERATION_ID) == evidence.operationId &&
            (allowDeadOwner || job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id) &&
            job.optString(ACTIVE_OPERATION_KIND) == KeplerActiveOperationKind.PUBLIC_EXPORT.name
        ) { "Public export owner changed during settlement" }
        finalOutputFormat?.let { job.put("finalOutputFormatSetting", it.name) }
        job.put(TERMINAL_OPERATION_ID, evidence.operationId)
            .put("exportError", failureMessage)
            .put("exportedAt", System.currentTimeMillis())
if (evidence.committed) {
            // These fields belong to this exact operation only.  On a
            // pre-commit interruption, preserve any previous terminal export
            // linkage instead of replacing it with null/false.
            job.put("exportUri", evidence.uri ?: JSONObject.NULL)
                .put("galleryPublicExportLinkage", evidence.uri ?: JSONObject.NULL)
                .put("galleryExportCommitted", true)
                .put("exportVerified", evidence.verified)
                .put(
                    "exportCommitState",
                    if (evidence.verified) GalleryExportCommitState.VERIFIED.name
                    else GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name
                )
        }
        when {
            evidence.verified -> job.put("currentPipelineStage", "PARTIAL")
                .put("processStatus", "EXPORT_VERIFIED_INTERRUPTED")
                .put("exportStatus", "EXPORTED")
                .put("recoveryState", "STABLE")
                .put("lastRecoveryClassification", KeplerJobRecoveryClassification.PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL.name)
                .put("lastRecoveryMessage", "공개 내보내기 결과를 확인했지만 이전 실행이 종료되어 후속 처리가 중단되었습니다.")
                .remove("recoveryMessage")
            evidence.committed -> job.put("currentPipelineStage", "PARTIAL")
                .put("processStatus", "EXPORT_COMMITTED_PENDING_VERIFICATION")
                .put("exportStatus", "EXPORT_UNVERIFIED")
                .put("recoveryState", "PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION")
                .put("recoveryMessage", "공개 내보내기 결과의 확인이 완료되지 않아 추가 확인이 필요합니다.")
                .put("lastRecoveryClassification", KeplerJobRecoveryClassification.PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION.name)
                .put("lastRecoveryMessage", "공개 내보내기는 완료되었지만 결과 확인이 완료되지 않았습니다.")
            else -> job.put("currentPipelineStage", if (disposition == PublicExportInterruptionDisposition.CANCELLED) "CANCELLED" else "FAILED")
                .put("processStatus", if (disposition == PublicExportInterruptionDisposition.CANCELLED) {
                    "EXPORT_CANCELLED_BEFORE_COMMIT"
                } else {
                    "EXPORT_FAILED_KEEPING_CACHE"
                })
                .put("exportStatus", if (disposition == PublicExportInterruptionDisposition.CANCELLED) "CANCELLED" else "FAILED")
                .put("recoveryState", "STABLE")
                .put("lastRecoveryClassification", KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT.name)
                .put("lastRecoveryMessage", "공개 내보내기 전에 이전 실행이 종료되어 원본 작업 자료를 보존했습니다.")
                .remove("recoveryMessage")
        }
    }
// A journal may acknowledge only after the matching terminal metadata write. The
    // acknowledgement is evidence-matched, so lagging or divergent journals stay deferred
    // for the authoritative settlement/recovery that may later converge them.
    val terminalMetadata = KeplerJobMetadata.read(jobDir)
    ownerJournals.filter { terminalAckEligible(terminalMetadata, it) }
        .forEach { it.markTerminalPersisted(jobDir, evidence.operationId) }
    // Never clear owner E while any exact owner-correlated journal remains unresolved.  The
    // acknowledgement is per-journal durable evidence; an ineligible/lagging journal stays
    // deferred and the next real production acquisition/settlement retries this protocol.
    val pendingOwnerJournals = MediaStoreExportJournal.list(jobDir)
        .filter { it.ownerOperationId == operationId && !it.isTerminallyStable() }
    if (pendingOwnerJournals.isNotEmpty()) return false
    KeplerJobMetadata.update(jobDir) { job ->
        check(job.optString(ACTIVE_OPERATION_ID) == evidence.operationId &&
            (allowDeadOwner || job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id) &&
            job.optString(TERMINAL_OPERATION_ID) == evidence.operationId
        ) { "Public export owner changed before release" }
        job.remove(ACTIVE_RUNTIME_SESSION_ID)
        job.remove(ACTIVE_OPERATION_ID)
        job.remove(ACTIVE_OPERATION_KIND)
        job.remove(ACTIVE_OPERATION_STARTED_AT)
        job.remove(ACTIVE_OPERATION_UPDATED_AT)
    }
    check(ownerLease.completePublicExportSettlement(operationId)) {
        "Public export settlement debt changed during successful settlement"
    }
    ownerLease.clearDurableOperation(operationId)
    return true
}

data class RawSidecarFrameResult(
    val frameIndex: Int,
    val requested: Boolean,
    val localFilename: String?,
    val localStatus: String,
    val localFailure: String?,
    val publicStatus: String,
    val publicUri: String?,
    val publicFailure: String?
)

/** Compatibility wrapper for callers that do not need structured failure details. */
fun verifyGalleryExport(
    context: Context,
    uriString: String,
    @Suppress("UNUSED_PARAMETER") minSizeBytes: Long = 0L
): Boolean = verifyGalleryExportResult(context, uriString) is GalleryExportVerification.Verified

internal fun verifyCommittedGalleryExport(
    context: Context,
    export: GalleryExportResult
): GalleryExportVerification {
    // GalleryExporter owns the authoritative verification.  A second provider read may race
    // with MediaStore visibility and must never downgrade a durable VERIFIED journal/result.
    val authoritative = export.verification
    if (authoritative != null) {
        // A provider verification can succeed immediately before the journal's VERIFIED
        // transition fails.  Until that durable transition is acknowledged, keep the live
        // result at committed-unverified so it agrees with restart recovery.
        if (export.publicCommitState == GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED &&
            authoritative is GalleryExportVerification.Verified
        ) {
            return GalleryExportVerification.RetryableFailure(
                "Public row verified but export verification settlement is pending."
            )
        }
        return authoritative
    }
    return verifyGalleryExportResult(
        context = context,
        uriString = export.uriString.orEmpty(),
        expectation = GalleryExportExpectation(
            format = export.formatUsed
        )
    )
}

fun exportRawSidecarsToPublicStorage(
    context: Context,
    jobDir: File,
    displayNameBase: String,
    relativeRawPath: String = "Pictures/Kepler/RAW",
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
): RawSidecarExportResult {
    val ownerOperationId = KeplerJobMetadata.read(jobDir).optString(ACTIVE_OPERATION_ID).takeIf { it.isNotBlank() }
    val manifest = try {
        loadRawSidecarManifest(jobDir)
    } catch (failure: Error) {
        throw failure
    } catch (failure: Exception) {
        return RawSidecarExportResult.failed("Invalid RAW sidecar manifest: ${failure.message}")
    }
    if (manifest.frames.none { it.requested }) {
        return RawSidecarExportResult.failed("No DNG sidecars were requested in job.json")
    }

val exported = mutableListOf<String>()
    val publicFailures = mutableListOf<String>()
    val publicByFrame = linkedMapOf<Int, String>()
    val publicFailureByFrame = linkedMapOf<Int, String>()
    val commitStateByFrame = linkedMapOf<Int, GalleryExportCommitState>()
    try {
        manifest.expected.forEach { frame ->
            val file = frame.localFile ?: return@forEach
            cancellation.throwIfCancelled()
            val exportName = "${displayNameBase}_${frame.frameIndex.toString().padStart(2, '0')}.dng"
            val sourceDigest = try {
                NoFollowFileSystem.digestVerified(file)
            } catch (failure: Error) {
                throw failure
            } catch (failure: Exception) {
                val message = "${file.name}: source DNG could not be hashed: ${failure.message}"
                publicFailures += message
                publicFailureByFrame[frame.frameIndex] = message
                return@forEach
            }
            val reusable = findReusableRawSidecarJournal(
                journals = MediaStoreExportJournal.list(jobDir),
                frameIndex = frame.frameIndex,
                displayName = exportName,
                expectedSizeBytes = sourceDigest.size
                ,expectedSha256 = sourceDigest.sha256
            ) { journal ->
                val uri = journal.uri?.let(Uri::parse) ?: return@findReusableRawSidecarJournal false
                ContextMediaStoreExportRecoveryAccess(context).inspect(uri, journal).let { inspection ->
                    inspection.exists && !inspection.pending && inspection.verified
                }
            }
if (reusable != null) {
                reusable.transition(jobDir, MediaStoreExportState.VERIFIED, expectedSha256Override = sourceDigest.sha256)
                exported += reusable.uri!!
                publicByFrame[frame.frameIndex] = reusable.uri
                commitStateByFrame[frame.frameIndex] = GalleryExportCommitState.VERIFIED
                return@forEach
            }
            var copiedDigest: NoFollowFileSystem.StreamDigest? = null
            val result = insertPublicFile(
                context = context,
                displayName = exportName,
                mimeType = "image/x-adobe-dng",
                relativePath = relativeRawPath,
                collectionUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                cancellation = cancellation,
                jobDir = jobDir,
                role = MediaStoreExportRole.RAW_DNG_SIDECAR,
                frameIndex = frame.frameIndex,
                expectedSizeBytes = sourceDigest.size,
                expectedSha256 = sourceDigest.sha256
                ,ownerOperationId = ownerOperationId
            ) { output ->
                copiedDigest = NoFollowFileSystem.copyVerified(file, output)
            } ?: run {
                cancellation.throwIfCancelled()
                insertPublicFile(
                    context = context,
                    displayName = exportName,
                    mimeType = "image/x-adobe-dng",
                    relativePath = "Download/Kepler/RAW",
                    collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cancellation = cancellation,
                    jobDir = jobDir,
                    role = MediaStoreExportRole.RAW_DNG_SIDECAR,
                    frameIndex = frame.frameIndex,
                    expectedSizeBytes = sourceDigest.size,
                    expectedSha256 = sourceDigest.sha256
                    ,ownerOperationId = ownerOperationId
                ) { output ->
                    copiedDigest = NoFollowFileSystem.copyVerified(file, output)
                }
            }

if (result == null) {
                val failure = "${file.name}: MediaStore insert/write failed"
                publicFailures += failure
                publicFailureByFrame[frame.frameIndex] = failure
                commitStateByFrame[frame.frameIndex] = GalleryExportCommitState.NOT_COMMITTED
                return@forEach
            }
            val publicEvidence = result.commitState != GalleryExportCommitState.NOT_COMMITTED
            if (publicEvidence) {
                // A sidecar has the same public commit boundary as the main image.  Retain its
                // exact URI even when verification or journal acknowledgement is incomplete.
                publicByFrame[frame.frameIndex] = result.uri.toString()
            }
            if (result.commitState == GalleryExportCommitState.UNKNOWN) {
                val failure = "${file.name}: public DNG commit state could not be determined"
                publicFailures += failure
                publicFailureByFrame[frame.frameIndex] = failure
                commitStateByFrame[frame.frameIndex] = GalleryExportCommitState.UNKNOWN
                return@forEach
            }
            val verificationError = verifyPublicDng(
                context = context,
                uri = result.uri,
                expected = copiedDigest ?: return@forEach
            )
            result.journal?.let { journal ->
                journal.transition(
                    jobDir,
                    journal.state,
                    expectedSha256Override = sourceDigest.sha256
                )
            }
if (verificationError != null) {
                val verificationFailure = IllegalStateException(
                    "${file.name}: $verificationError"
                )
                // RAW_DNG_SIDECAR crosses the same irreversible public boundary as MAIN_IMAGE.
                // Verification failure is partial evidence, never permission to delete a
                // non-pending row that restart recovery would preserve.
                val failure = verificationFailure.message ?: "${file.name}: $verificationError"
                publicFailures += failure
                publicFailureByFrame[frame.frameIndex] = failure
                commitStateByFrame[frame.frameIndex] = GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED
            } else {
                result.journal?.transition(jobDir, MediaStoreExportState.VERIFIED)
                exported += result.uri.toString()
                publicByFrame[frame.frameIndex] = result.uri.toString()
                commitStateByFrame[frame.frameIndex] = GalleryExportCommitState.VERIFIED
            }
        }
} catch (ce: CancellationException) {
        // Any frame that crossed (or may have crossed) the public commit boundary keeps its full
        // per-frame result through cancellation.  The static "before any commit" result is used
        // only when no frame reached the boundary at all.
        if (publicByFrame.isNotEmpty()) {
            return rawSidecarOutcome(
                manifest, exported, publicFailures + "Cancellation after partial public commit",
                true, publicByFrame, publicFailureByFrame, commitStateByFrame
            )
        }
        return RawSidecarExportResult.cancelled()
    }

    return rawSidecarOutcome(
        manifest, exported, publicFailures, false,
        publicByFrame, publicFailureByFrame, commitStateByFrame
    )
}

internal data class RawSidecarManifestFrame(
    val frameIndex: Int,
    val requested: Boolean,
    val localFile: File?,
    val localFilename: String?,
    val localStatus: String,
    val localFailure: String?
)

internal fun findReusableRawSidecarJournal(
    journals: List<MediaStoreExportJournal>,
    frameIndex: Int,
    displayName: String,
    expectedSizeBytes: Long,
    expectedSha256: String,
    verifier: (MediaStoreExportJournal) -> Boolean
): MediaStoreExportJournal? = journals.asSequence()
    .filter { journal ->
        journal.role == MediaStoreExportRole.RAW_DNG_SIDECAR &&
            journal.frameIndex == frameIndex &&
            journal.displayName == displayName &&
            journal.expectedSizeBytes == expectedSizeBytes &&
            journal.expectedSha256 == expectedSha256 &&
            journal.uri != null &&
            journal.state == MediaStoreExportState.VERIFIED
    }
    .firstOrNull(verifier)

internal data class RawSidecarManifest(
    val frames: List<RawSidecarManifestFrame>
) {
    val expected: List<RawSidecarManifestFrame> get() = frames.filter { it.localFile != null }
    val localFailures: List<String> get() = frames.mapNotNull { it.localFailure }
}

internal fun loadRawSidecarManifest(jobDir: File): RawSidecarManifest {
    val jobFile = NoFollowFileSystem.requireDirectChildFile(jobDir, "job.json")
    val job = JSONObject(NoFollowFileSystem.readTextVerified(jobFile))
    val frames = job.optJSONArray("frames") ?: error("job.json has no RAW frame manifest")
    val names = linkedSetOf<String>()
    val directDngNames = NoFollowFileSystem.requireDirectDirectDngNames(jobDir)
    val manifestFrames = mutableListOf<RawSidecarManifestFrame>()
    for (index in 0 until frames.length()) {
        val frame = frames.optJSONObject(index) ?: error("Invalid frame manifest entry $index")
        val frameIdentity = frame.optInt("frameIndex", frame.optInt("index", index))
        val rawStatus = frame.optString("dngSidecarStatus")
        val status = if (rawStatus == "EXPORTED") "LOCAL_SAVED" else rawStatus
        val name = frame.optString("dngFile").takeUnless { it.isBlank() || it == "null" }
        val requested = status != "NOT_REQUESTED" || name != null
        var effectiveStatus = status
        var failure = if (status == "LOCAL_SAVE_FAILED") {
            "frame $frameIdentity: ${frame.optString("dngSidecarError").ifBlank { "local DNG save failed" }}"
        } else null
        if (status == "LOCAL_SAVED") {
            require(name != null && name.lowercase().endsWith(".dng")) { "Invalid locally saved DNG reference at frame $frameIdentity" }
            require(names.add(name)) { "Duplicate DNG reference: $name" }
            if (name !in directDngNames) {
                effectiveStatus = "LOCAL_SAVE_FAILED"
                failure = "frame $frameIdentity: declared local DNG is missing ($name)"
            }
        }
        val localFile = if (effectiveStatus == "LOCAL_SAVED") {
            NoFollowFileSystem.requireDirectChildFile(jobDir, name!!)
        } else null
        manifestFrames += RawSidecarManifestFrame(frameIdentity, requested, localFile, name, effectiveStatus, failure)
    }
    require((directDngNames - names).isEmpty()) { "Unexpected unreferenced DNG sidecar files" }
    val normalized = manifestFrames.map { frame ->
        val file = frame.localFile
        if (file != null && !isDngTiffHeader(NoFollowFileSystem.digestVerified(file).prefix)) {
            frame.copy(
                localFile = null,
                localStatus = "LOCAL_SAVE_FAILED",
                localFailure = "frame ${frame.frameIndex}: malformed local DNG (${file.name})"
            )
        } else frame
    }
    return RawSidecarManifest(normalized)
}

private fun NoFollowFileSystem.requireDirectDirectDngNames(root: File): Set<String> =
    requireDirectChildren(root).filter { it.name.lowercase().endsWith(".dng") }.mapTo(linkedSetOf()) { file ->
        require(isRealFile(file.toPath())) { "Unsafe DNG sidecar: ${file.name}" }
        file.name
    }

private fun verifyPublicDng(
    context: Context,
    uri: Uri,
    expected: NoFollowFileSystem.StreamDigest
): String? {
    return try {
    val digest = MessageDigest.getInstance("SHA-256")
    val prefix = ByteArray(16)
    var prefixCount = 0
    var size = 0L
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            if (prefixCount < prefix.size) {
                val copied = minOf(read, prefix.size - prefixCount)
                buffer.copyInto(prefix, prefixCount, 0, copied)
                prefixCount += copied
            }
            digest.update(buffer, 0, read)
            size += read
        }
    } ?: return "Committed public DNG stream is unavailable"
    when {
        !isDngTiffHeader(prefix.copyOf(prefixCount)) -> "Committed public DNG header is invalid"
        size != expected.size -> "Committed public DNG size mismatch: expected ${expected.size}, actual $size"
        digest.digest().joinToString("") { "%02x".format(it) } != expected.sha256 -> "Committed public DNG SHA-256 mismatch"
        else -> null
    }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        "Committed public DNG verification failed: ${error.javaClass.simpleName}: ${error.message}"
    }
}

/**
 * Per-frame public status for a RAW DNG sidecar, classified by the frame's OWN commit evidence.
 * UNKNOWN commit state is never upgraded to a proven commit: it stays PUBLIC_COMMIT_UNKNOWN.
 */
internal fun sidecarFramePublicStatus(
    commitState: GalleryExportCommitState?,
    publicUri: String?,
    publicFailure: String?
): String = when (commitState) {
    GalleryExportCommitState.VERIFIED -> "PUBLIC_EXPORTED"
    GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED -> "PUBLIC_COMMITTED_UNVERIFIED"
    GalleryExportCommitState.UNKNOWN -> "PUBLIC_COMMIT_UNKNOWN"
    GalleryExportCommitState.NOT_COMMITTED -> "PUBLIC_EXPORT_FAILED"
    null -> when {
        publicFailure != null && publicUri != null -> "PUBLIC_COMMITTED_UNVERIFIED"
        publicUri != null -> "PUBLIC_EXPORTED"
        publicFailure != null -> "PUBLIC_EXPORT_FAILED"
        else -> "NOT_ATTEMPTED"
    }
}

/** Aggregate sidecar kind: any proven committed evidence (verified or committed-unverified)
 *  yields at least PARTIAL.  UNKNOWN-only evidence never becomes a committed claim. */
internal fun rawSidecarAggregateKind(
    complete: Boolean,
    frameResults: List<RawSidecarFrameResult>,
    exported: List<String>
): RawSidecarOutcomeKind = when {
    complete -> RawSidecarOutcomeKind.COMPLETE
    frameResults.any { it.publicStatus == "PUBLIC_EXPORTED" || it.publicStatus == "PUBLIC_COMMITTED_UNVERIFIED" } ->
        RawSidecarOutcomeKind.PARTIAL
    exported.isNotEmpty() -> RawSidecarOutcomeKind.PARTIAL
    else -> RawSidecarOutcomeKind.FAILED
}

/** A publicly committed or commit-unknown DNG is never "missing".  Only not-committed and
 *  not-attempted frames are missing. */
internal fun rawSidecarMissingFilenames(frameResults: List<RawSidecarFrameResult>): List<String> =
    frameResults.filter { it.requested && it.publicStatus in setOf("PUBLIC_EXPORT_FAILED", "NOT_ATTEMPTED") }
        .map { it.localFilename ?: "frame_${it.frameIndex.toString().padStart(2, '0')}.dng" }

private fun rawSidecarOutcome(
    manifest: RawSidecarManifest,
    exported: List<String>,
    publicFailures: List<String>,
    cancelled: Boolean = false,
    publicByFrame: Map<Int, String> = emptyMap(),
    publicFailureByFrame: Map<Int, String> = emptyMap(),
    commitStateByFrame: Map<Int, GalleryExportCommitState> = emptyMap()
): RawSidecarExportResult {
    val requestedCount = manifest.frames.count { it.requested }
    val locallySavedCount = manifest.frames.count { it.localFile != null }
    val localFailedCount = manifest.frames.count { it.localFailure != null }
    val complete = locallySavedCount == requestedCount && exported.size == locallySavedCount && publicFailures.isEmpty() && localFailedCount == 0
    val frameResults = manifest.frames.map { frame ->
        val publicUri = publicByFrame[frame.frameIndex]
        val publicFailure = publicFailureByFrame[frame.frameIndex]
        RawSidecarFrameResult(
            frameIndex = frame.frameIndex,
            requested = frame.requested,
            localFilename = frame.localFilename,
            localStatus = frame.localStatus,
            localFailure = frame.localFailure,
            publicStatus = sidecarFramePublicStatus(commitStateByFrame[frame.frameIndex], publicUri, publicFailure),
            publicUri = publicUri,
            publicFailure = publicFailure
        )
    }
    val kind = rawSidecarAggregateKind(complete, frameResults, exported)
    return RawSidecarExportResult(
        success = kind == RawSidecarOutcomeKind.COMPLETE || kind == RawSidecarOutcomeKind.PARTIAL,
        exportedFiles = exported,
        errorMessage = (manifest.localFailures + publicFailures).takeIf { it.isNotEmpty() }?.joinToString("; "),
        kind = kind,
        cancellationRequested = cancelled,
        expectedCount = requestedCount,
        locallySavedCount = locallySavedCount,
        publicExportedCount = exported.size,
        missingFilenames = rawSidecarMissingFilenames(frameResults),
        localFailures = manifest.localFailures,
        publicFailures = publicFailures,
        requestedCount = requestedCount,
        localFailedCount = localFailedCount,
        publicFailedCount = publicFailures.size,
        frameResults = frameResults
    )
}

fun queryMediaSize(context: Context, uri: Uri): Long {
    val mediaSize = try {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    } catch (failure: Error) {
        throw failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        0L
    }
    if (mediaSize > 0L) return mediaSize
    val descriptorSize = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    } catch (failure: Error) {
        throw failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        0L
    }
    if (descriptorSize > 0L) return descriptorSize
    return try {
        if (uri.scheme == "file") {
            uri.path?.let { java.io.File(it).length() } ?: 0L
        } else 0L
    } catch (failure: Error) {
        throw failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        0L
    }
}

fun updateExportMetadata(
    jobDir: File,
    export: GalleryExportResult?,
    verified: Boolean,
    finalOutputFormat: FinalOutputFormat,
    rawSidecarResult: RawSidecarExportResult? = null,
    rawSidecarIgnored: Boolean = false,
    postExportCancellationRequested: Boolean = false,
    postExportWorkSkipped: Boolean = false,
    operationLease: JobOperationLease? = null
) {
    lateinit var pipelineStatusForLog: String
    lateinit var finalOutputSourceForLog: String
    lateinit var nativePostprocessRgbaFileForLog: String
    lateinit var rawRenderDebugFileForLog: String
    KeplerJobMetadata.update(jobDir) { job ->
        val publicCommitted = export?.publicCommitted == true
        val publicCommitUnknown = export?.publicCommitState == GalleryExportCommitState.UNKNOWN
        val effectiveVerified = verified &&
            export?.publicCommitState == GalleryExportCommitState.VERIFIED &&
            publicCommitted &&
            !publicCommitUnknown
        val verifiedPartial = effectiveVerified && (postExportCancellationRequested || postExportWorkSkipped)
        val durableStage = when {
            publicCommitted && !effectiveVerified -> "PARTIAL"
            verifiedPartial -> "PARTIAL"
            effectiveVerified -> "COMPLETE"
            else -> "FAILED"
        }
        if (durableStage == "COMPLETE" || durableStage == "PARTIAL") {
            job.optString(ACTIVE_OPERATION_ID).takeIf { it.isNotBlank() }?.let { job.put(TERMINAL_OPERATION_ID, it) }
        }
        job.put("finalOutputFormatSetting", finalOutputFormat.name)
            .put("currentPipelineStage", durableStage)
            .put(
                "processStatus",
                when {
                    publicCommitted && !effectiveVerified -> "EXPORT_COMMITTED_UNVERIFIED"
                    publicCommitUnknown -> "COMMIT_UNKNOWN"
                    verifiedPartial -> "PIPELINE_COMPLETE_PARTIAL"
                    effectiveVerified -> "PIPELINE_COMPLETE"
                    else -> "EXPORT_FAILED_KEEPING_CACHE"
                }
            )
            .put("exportCommitState", export?.publicCommitState?.name ?: GalleryExportCommitState.NOT_COMMITTED.name)
            .put("exportStatus", when {
                export == null -> "FAILED"
                export.publicCommitState == GalleryExportCommitState.UNKNOWN -> "COMMIT_UNKNOWN"
                publicCommitted && !effectiveVerified -> "COMMITTED_UNVERIFIED"
                effectiveVerified -> "EXPORTED"
                else -> "EXPORT_UNVERIFIED"
            })
            .put("exportVerified", effectiveVerified)
            .put("exportUri", export?.uriString ?: JSONObject.NULL)
            .put("exportDisplayName", export?.displayName ?: JSONObject.NULL)
            .put("exportMimeType", export?.mimeType ?: JSONObject.NULL)
            .put("exportFormatRequested", requestedOutputFormatForSetting(finalOutputFormat).label)
            .put("exportFormatUsed", export?.formatUsed?.label ?: JSONObject.NULL)
            .put("exportFormatCommitted", export?.formatUsed?.label ?: JSONObject.NULL)
            .put("exportAttemptedFormats", JSONArray(export?.attemptedFormats?.map { it.label } ?: emptyList<String>()))
            .put("exportCandidateFailureReasons", JSONArray(export?.candidateFailureReasons ?: emptyList<String>()))
            .put("exportCommittedMime", (export?.verification as? GalleryExportVerification.Verified)?.mediaStoreMime ?: JSONObject.NULL)
            .put("exportCommittedWidth", (export?.verification as? GalleryExportVerification.Verified)?.width ?: 0)
            .put("exportCommittedHeight", (export?.verification as? GalleryExportVerification.Verified)?.height ?: 0)
            .put("exportFallbackUsed", export?.fallbackUsed ?: false)
            .put("exportFileSizeBytes", export?.fileSizeBytes ?: 0L)
            .put("galleryExportCommitted", export?.publicCommitted == true)
            .put("postExportCancellationRequested", postExportCancellationRequested)
            .put("postExportWorkSkipped", postExportWorkSkipped)
            .put("rawSidecarRequested", finalOutputFormat.shouldExportRawSidecar)
            .put("rawSidecarExportStatus", when {
                rawSidecarIgnored -> "UNAVAILABLE"
                rawSidecarResult == null && finalOutputFormat.shouldExportRawSidecar -> "SKIPPED"
                rawSidecarResult == null -> "NOT_REQUESTED"
                else -> rawSidecarResult.status
            })
            .put("rawSidecarExportedFiles", JSONArray(rawSidecarResult?.exportedFiles ?: emptyList<String>()))
            .put("rawSidecarExpectedCount", rawSidecarResult?.expectedCount ?: 0)
            .put("rawSidecarRequestedCount", rawSidecarResult?.requestedCount ?: 0)
            .put("rawSidecarLocallySavedCount", rawSidecarResult?.locallySavedCount ?: 0)
            .put("rawSidecarLocalFailedCount", rawSidecarResult?.localFailedCount ?: 0)
            .put("rawSidecarPublicExportedCount", rawSidecarResult?.publicExportedCount ?: 0)
            .put("rawSidecarPublicFailedCount", rawSidecarResult?.publicFailedCount ?: 0)
            .put("rawSidecarMissingFilenames", JSONArray(rawSidecarResult?.missingFilenames ?: emptyList<String>()))
            .put("rawSidecarLocalFailures", JSONArray(rawSidecarResult?.localFailures ?: emptyList<String>()))
            .put("rawSidecarPublicFailures", JSONArray(rawSidecarResult?.publicFailures ?: emptyList<String>()))
            .put("rawSidecarFrameManifest", JSONArray(rawSidecarResult?.frameResults?.map { frame ->
                JSONObject()
                    .put("frameIndex", frame.frameIndex)
                    .put("requested", frame.requested)
                    .put("localFilename", frame.localFilename ?: JSONObject.NULL)
                    .put("localStatus", frame.localStatus)
                    .put("localFailure", frame.localFailure ?: JSONObject.NULL)
                    .put("publicStatus", frame.publicStatus)
                    .put("publicUri", frame.publicUri ?: JSONObject.NULL)
                    .put("publicFailure", frame.publicFailure ?: JSONObject.NULL)
            } ?: emptyList<JSONObject>()))
            .put("rawSidecarError", when {
                rawSidecarIgnored -> "RAW sidecar unavailable for YUV pipeline."
                else -> rawSidecarResult?.errorMessage ?: JSONObject.NULL
            })
            .put("exportedAt", System.currentTimeMillis())
        if (export?.publicCommitted == true && !export.uriString.isNullOrBlank()) {
            job.put("galleryPublicExportLinkage", export.uriString)
        } else if (export?.publicCommitState == GalleryExportCommitState.UNKNOWN && !export.uriString.isNullOrBlank()) {
            // Unknown commit state is preserved as evidence, but is not claimed as committed.
            job.put("galleryPublicExportLinkage", export.uriString)
        } else {
            job.remove("galleryPublicExportLinkage")
        }
        rawSidecarResult?.frameResults?.forEach { frameResult ->
            val frameArray = job.optJSONArray("frames")
            for (index in 0 until (frameArray?.length() ?: 0)) {
                val frame = frameArray?.optJSONObject(index) ?: continue
                val identity = frame.optInt("frameIndex", frame.optInt("index", index))
                if (identity != frameResult.frameIndex) continue
                frame.put("dngSidecarStatus", frameResult.localStatus)
                    .put("dngSidecarPublicStatus", frameResult.publicStatus)
                    .put("publicDngUri", frameResult.publicUri ?: JSONObject.NULL)
                    .put("publicDngError", frameResult.publicFailure ?: JSONObject.NULL)
                break
            }
        }
        val existingPipelineStartedAt = job.optLong("rawCaptureStartedAt", 0L)
            .takeIf { it > 0L }
            ?: job.optLong("createdAt", 0L).takeIf { it > 0L } ?: 0L
        if (existingPipelineStartedAt > 0L) {
            job.put("totalPipelineMs", System.currentTimeMillis() - existingPipelineStartedAt)
        }
        pipelineStatusForLog = job.optString("processStatus")
        finalOutputSourceForLog = job.optString("finalOutputSource")
        nativePostprocessRgbaFileForLog = job.optString("nativePostprocessRgbaFile")
        rawRenderDebugFileForLog = job.optString("rawRenderDebugFile")
    }
    Log.i(
        "KeplerRawPipeline",
        "PIPELINE_COMPLETE jobDirAbsolutePath=${jobDir.absolutePath} processStatus=$pipelineStatusForLog " +
            "finalOutputSource=$finalOutputSourceForLog " +
            "nativePostprocessRgbaFile=$nativePostprocessRgbaFileForLog " +
            "rawRenderDebugFile=$rawRenderDebugFileForLog"
    )
    markMediaStoreExportJournalsTerminalPersisted(jobDir, operationLease)
}

fun updateExportFailure(
    jobDir: File,
    error: String,
    finalOutputFormat: FinalOutputFormat,
    rawSidecarIgnored: Boolean = false,
    export: GalleryExportResult? = null,
    requiredOutputCommitted: Boolean = currentProcessingAttemptHasRequiredOutputClaim(jobDir),
    operationLease: JobOperationLease? = null
) {
    val currentPublicCommit = export?.publicCommitted == true
    val currentLocalCommit = requiredOutputCommitted || if (operationLease != null) {
        currentProcessingAttemptHasRequiredOutputClaimForLease(jobDir, operationLease)
    } else {
        currentProcessingAttemptHasRequiredOutputClaim(jobDir)
    }
    val usableCurrentResult = currentPublicCommit || currentLocalCommit
    KeplerJobMetadata.update(jobDir) { job ->
        job.optString(ACTIVE_OPERATION_ID).takeIf { it.isNotBlank() }?.let { job.put(TERMINAL_OPERATION_ID, it) }
        job.put("finalOutputFormatSetting", finalOutputFormat.name)
            .put(
                "processStatus",
                if (currentPublicCommit) "EXPORT_COMMITTED_UNVERIFIED" else "EXPORT_FAILED_KEEPING_CACHE"
            )
            .put("currentPipelineStage", if (usableCurrentResult) "PARTIAL" else "FAILED")
            .put("exportCommitState", export?.publicCommitState?.name ?: GalleryExportCommitState.NOT_COMMITTED.name)
            .put("exportStatus", when {
                currentPublicCommit -> "COMMITTED_UNVERIFIED"
                export?.publicCommitState == GalleryExportCommitState.UNKNOWN -> "COMMIT_UNKNOWN"
                else -> "FAILED"
            })
            .put("exportError", error)
            .put("exportVerified", false)
            .put("galleryExportCommitted", currentPublicCommit)
            .put("rawSidecarRequested", finalOutputFormat.shouldExportRawSidecar)
            .put("rawSidecarExportStatus", if (rawSidecarIgnored) "UNAVAILABLE" else "SKIPPED")
            .put("rawSidecarError", if (rawSidecarIgnored) "RAW sidecar unavailable for YUV pipeline." else JSONObject.NULL)
            .put("cleanupStatus", "SKIPPED")
            .put("exportedAt", System.currentTimeMillis())
        if (currentPublicCommit) {
            // A committed-but-unverified result is still the current public
            // result. Its exact URI belongs to this export attempt; never
            // replace it with a previous job-level URI.
            job.put("exportVerified", false)
                .put("galleryExportCommitted", true)
                .put("exportUri", export?.uriString ?: JSONObject.NULL)
                .put("exportVerificationFailed", true)
        } else if (export?.publicCommitState == GalleryExportCommitState.UNKNOWN && !export.uriString.isNullOrBlank()) {
            job.put("exportUri", export.uriString)
                .put("galleryPublicExportLinkage", export.uriString)
                .put("exportCommitState", GalleryExportCommitState.UNKNOWN.name)
                .put("exportVerificationFailed", true)
        } else {
            // Preserve a prior export URI as historical evidence, but clear every current
            // commit flag/linkage so an old public result cannot be counted for this attempt.
            // Current-result readers require the explicit commit/verification fields together
            // with the URI, so retaining the string does not make it a new result.
            job.remove("galleryPublicExportLinkage")
        }
    }
    markMediaStoreExportJournalsTerminalPersisted(jobDir, operationLease)
}

/**
 * Evidence-match contract for a journal terminal acknowledgement: the durable terminal record and
 * the journal must agree on the commit state and, for the MAIN_IMAGE result, on the exact public
 * URI. An UNKNOWN record never satisfies the contract, so its journals stay acknowledged only by
 * the authoritative settlement/recovery that also converges the record.  Lagging pre-commit and
 * divergent-URI journals stay deferred for the same authority.
 */
internal fun terminalAckEligible(
    metadata: org.json.JSONObject,
    journal: MediaStoreExportJournal
): Boolean {
    // Role-first: RAW_DNG_SIDECAR journals acknowledge only from THEIR OWN durable per-frame
    // evidence, never by inheriting the MAIN image state.  A verified MAIN image must not force
    // a sidecar journal to VERIFIED, and PUBLIC_COMMIT_UNKNOWN/PUBLIC_EXPORT_FAILED evidence
    // never acknowledges. A committed-unverified sidecar acknowledges only when the exact durable
    // sidecar URI matches the journal URI; a sidecar without an own frame record stays deferred
    // for the authoritative settlement/recovery that records that evidence.
    if (journal.role == MediaStoreExportRole.RAW_DNG_SIDECAR) {
        val frame = if (journal.frameIndex != null) {
            frameRecordByIndex(metadata, journal.frameIndex)
        } else {
            null
        }
        val sidecarStatus = frame?.optString("dngSidecarPublicStatus")
            ?.takeIf { it.isNotBlank() }
        if (sidecarStatus != null) {
            val sidecarUri = frame.optString("publicDngUri").takeIf { it.isNotBlank() }
            val journalUri = journal.uri?.takeIf { it.isNotBlank() }
            return when (sidecarStatus) {
                "PUBLIC_EXPORTED" ->
                    journal.state == MediaStoreExportState.VERIFIED &&
                        (sidecarUri == null || sidecarUri == journalUri)
                "PUBLIC_COMMITTED_UNVERIFIED" ->
                    journal.state in setOf(
                        MediaStoreExportState.PUBLIC_COMMITTED,
                        MediaStoreExportState.VERIFIED
                    ) && (sidecarUri == null || sidecarUri == journalUri)
                else -> false
            }
        }
        // No own frame record: the journal's own durable state is the sidecar's evidence. Never
        // force a sidecar to VERIFIED from MAIN policy; a committed sidecar remains commit-debt.
        return journal.state in setOf(
            MediaStoreExportState.VERIFIED,
            MediaStoreExportState.PUBLIC_COMMITTED
        )
    }
    val explicitCommitState = metadata.optString("exportCommitState")
    val stateEligible: Boolean = when {
        explicitCommitState == GalleryExportCommitState.UNKNOWN.name -> false
        metadata.optBoolean("exportVerified", false) ||
            explicitCommitState == GalleryExportCommitState.VERIFIED.name ->
            journal.state == MediaStoreExportState.VERIFIED
        metadata.optBoolean("galleryExportCommitted", false) ||
            explicitCommitState == GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name ->
            journal.state in setOf(
                MediaStoreExportState.PUBLIC_COMMITTED,
                MediaStoreExportState.VERIFIED
            )
        else -> journal.state in setOf(
            MediaStoreExportState.CLEANED,
            MediaStoreExportState.INSERT_FAILED_NO_ROW
        )
    }
    if (!stateEligible) return false
    if (journal.role == MediaStoreExportRole.MAIN_IMAGE &&
        journal.state in setOf(MediaStoreExportState.PUBLIC_COMMITTED, MediaStoreExportState.VERIFIED)
    ) {
        val claimedUri = metadata.optString("exportUri").takeIf { it.isNotBlank() }
            ?: metadata.optString("galleryPublicExportLinkage").takeIf { it.isNotBlank() }
            ?: return false
        return journal.uri == claimedUri
    }
    return true
}

private fun frameRecordByIndex(metadata: org.json.JSONObject, frameIndex: Int): org.json.JSONObject? {
    val frames = metadata.optJSONArray("frames") ?: return null
    for (position in 0 until frames.length()) {
        val frame = frames.optJSONObject(position) ?: continue
        if (frame.optInt("frameIndex", frame.optInt("index", -1)) == frameIndex) return frame
    }
    return null
}

internal fun markMediaStoreExportJournalsTerminalPersisted(
    jobDir: File,
    ownerLease: JobOperationLease? = null
): MediaStoreExportTerminalSettlementStatus {
    return try {
        val metadata = try {
            KeplerJobMetadata.read(jobDir)
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            null
        } ?: return MediaStoreExportTerminalSettlementStatus.DEFERRED
        val ownerOperationId = metadata.optString(TERMINAL_OPERATION_ID).takeIf { it.isNotBlank() }
            ?: return MediaStoreExportTerminalSettlementStatus.DEFERRED
        MediaStoreExportJournal.list(jobDir).forEach { journal ->
            if (journal.ownerOperationId == ownerOperationId &&
                !journal.terminalMetadataPersisted &&
                terminalAckEligible(metadata, journal)
            ) {
                journal.markTerminalPersisted(jobDir, ownerOperationId)
            }
        }
        // Clear ACTIVE PUBLIC_EXPORT only when EVERY owner-correlated journal that requires
        // settlement is either terminal-acknowledged or proven terminal-stable.  An ineligible
        // journal (UNKNOWN record, lagging pre-commit, unresolved sidecar, divergent URI) means
        // the owner must be retained so the next acquisition/settlement retries the protocol.
        val pendingOwnerJournals = MediaStoreExportJournal.list(jobDir)
            .filter { it.ownerOperationId == ownerOperationId && !it.isTerminallyStable() }
        if (pendingOwnerJournals.isNotEmpty()) {
            return MediaStoreExportTerminalSettlementStatus.DEFERRED
        }
        val stage = try {
            KeplerJobMetadata.read(jobDir).optString("currentPipelineStage")
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            null
        }
        if (stage != null && stage != "PROCESSING") {
            val cleared = KeplerJobMetadata.clearActiveOperationKind(jobDir, KeplerActiveOperationKind.PUBLIC_EXPORT, ownerLease)
            // If ACTIVE clear fails but the operation is still current, the settlement must
            // remain deferred so the retained lease protects the exact owner.
            if (!cleared && KeplerJobMetadata.isCurrentActiveOperation(jobDir, ownerOperationId)) {
                return MediaStoreExportTerminalSettlementStatus.DEFERRED
            }
        }
        MediaStoreExportTerminalSettlementStatus.SETTLED
    } catch (failure: Error) {
        throw failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        MediaStoreExportTerminalSettlementStatus.DEFERRED
    }
}

/**
 * Persist the explicit [RawFusionPublicExportOutcome] inside a single [KeplerJobMetadata.update].
 * Writes terminal stage/status, `userCanMoveDevice`, committed/verified export metadata, and the
 * public-result linkage owned by this export. NORMAL callers replace the previous RAW sequence of
 * separate `updateExportMetadata(...)` and `updateExportFailure(...)` calls with this helper.
 *
 * Tracks the commit point exactly:
 *
 * - Before the `IS_PENDING=0` MediaStore commit → no committed URI, `galleryExportCommitted=false`.
 * - After the `IS_PENDING=0` MediaStore commit → committed URI is retained even if verification,
 *   sidecar, metadata persistence, or post-commit cancellation later fails.
 *
 * For verified success: a complete success (`currentPipelineStage="COMPLETE"`,
 * `processStatus=`"NIGHT_FUSION_COMPLETE"`, `userCanMoveDevice=true`) is written alongside
 * committed and verified export metadata.
 * For failure before commit: a terminal failure (`currentPipelineStage="FAILED"`,
 * `processStatus="EXPORT_FAILED_KEEPING_CACHE"`, `userCanMoveDevice=true`) is written and no new
 * committed URI is recorded.
 * For verification failure after commit: a committed-partial state (`currentPipelineStage="PARTIAL"`,
 * `processStatus="EXPORT_VERIFICATION_FAILED"`, `userCanMoveDevice=true`,
 * `galleryExportCommitted=true`, `exportVerified=false`) is written and the committed URI is
 * preserved.
 * For cancellation after verified commit: the verified committed success/partial-success state
 * is retained, `postExportCancellationRequested=true`, `postExportWorkSkipped=true`. The verified
 * result is NEVER overwritten with generic `FAILED` because sidecars or later optional work failed.
 */
internal fun updateRawPublicExportOutcome(
    jobDir: File,
    outcome: RawFusionPublicExportOutcome,
    operationLease: JobOperationLease? = null
) {
    val terminalPolicy = deriveRawFusionOutcomePolicy(
        outcome = outcome,
        cancellationRequested = outcome.postExportCancellationRequested,
        currentLocalOutput = outcome.currentLocalOutput
    )
    KeplerJobMetadata.update(jobDir) { job ->
        val requested = requestedOutputFormatForSetting(outcome.finalOutputFormat)
        job.put("finalOutputFormatSetting", outcome.finalOutputFormat.name)
            .put("exportStatus", when (outcome) {
                is RawFusionPublicExportOutcome.CommittedPendingVerification -> "COMMITTED_PENDING"
                is RawFusionPublicExportOutcome.VerifiedPendingPostWork -> "EXPORTED_PENDING_POST_WORK"
                is RawFusionPublicExportOutcome.VerifiedPostWorkInterrupted -> "EXPORTED_VERIFIED_POST_WORK_INTERRUPTED"
                is RawFusionPublicExportOutcome.VerifiedSuccess,
                is RawFusionPublicExportOutcome.VerifiedWithPostExportCancellation -> "EXPORTED"
                is RawFusionPublicExportOutcome.CommittedVerificationFailure -> "EXPORT_UNVERIFIED"
                is RawFusionPublicExportOutcome.CommittedCancelledBeforeVerification -> "EXPORT_COMMITTED_CANCELLED"
                is RawFusionPublicExportOutcome.CommittedInterruptedBeforeVerification -> "EXPORT_COMMITTED_INTERRUPTED"
                is RawFusionPublicExportOutcome.UncommittedFailure ->
                    if (outcome.export?.publicCommitState == GalleryExportCommitState.UNKNOWN) {
                        "COMMIT_UNKNOWN"
                    } else if (outcome.committed) {
                        "COMMITTED_UNVERIFIED"
                    } else if (outcome.postExportCancellationRequested && !terminalPolicy.hasCurrentLocalResult) {
                        "CANCELLED"
                    } else {
                        "FAILED"
                    }
            })
            .put("exportCommitState", outcome.export?.publicCommitState?.name ?: GalleryExportCommitState.NOT_COMMITTED.name)
            .put("exportVerified", terminalPolicy.publicVerified)
            .put("exportUri", outcome.export?.uriString ?: JSONObject.NULL)
            .put("exportDisplayName", outcome.export?.displayName ?: JSONObject.NULL)
            .put("exportMimeType", outcome.export?.mimeType ?: JSONObject.NULL)
            .put("exportFormatRequested", requested.label)
            .put("exportFormatUsed", outcome.export?.formatUsed?.label ?: JSONObject.NULL)
            .put("exportFormatCommitted", outcome.export?.formatUsed?.label ?: JSONObject.NULL)
            .put("exportAttemptedFormats", JSONArray(outcome.export?.attemptedFormats?.map { it.label } ?: emptyList<String>()))
            .put("exportCandidateFailureReasons", JSONArray(outcome.export?.candidateFailureReasons ?: emptyList<String>()))
            .put("exportCommittedMime", (outcome.export?.verification as? GalleryExportVerification.Verified)?.mediaStoreMime ?: JSONObject.NULL)
            .put("exportCommittedWidth", (outcome.export?.verification as? GalleryExportVerification.Verified)?.width ?: 0)
            .put("exportCommittedHeight", (outcome.export?.verification as? GalleryExportVerification.Verified)?.height ?: 0)
            .put("exportFallbackUsed", outcome.export?.fallbackUsed ?: false)
            .put("exportFileSizeBytes", outcome.export?.fileSizeBytes ?: 0L)
            .put("galleryExportCommitted", terminalPolicy.publicCommitted)
            .put("postExportCancellationRequested", outcome.postExportCancellationRequested)
            .put("postExportWorkSkipped", outcome.postExportWorkSkipped)
            .put("rawSidecarRequested", outcome.finalOutputFormat.shouldExportRawSidecar)
        val sidecarResult = outcome.sidecar
        val sidecarStatus = when {
            outcome is RawFusionPublicExportOutcome.UncommittedFailure -> "SKIPPED"
            outcome is RawFusionPublicExportOutcome.CommittedVerificationFailure -> "SKIPPED"
            outcome is RawFusionPublicExportOutcome.CommittedInterruptedBeforeVerification -> "SKIPPED"
            outcome is RawFusionPublicExportOutcome.VerifiedPendingPostWork && sidecarResult == null && outcome.finalOutputFormat.shouldExportRawSidecar -> "PENDING"
            sidecarResult == null && outcome.finalOutputFormat.shouldExportRawSidecar -> "SKIPPED"
            sidecarResult == null -> "NOT_REQUESTED"
            else -> sidecarResult.status
        }
        val sidecarError = when {
            sidecarResult == null -> JSONObject.NULL
            else -> sidecarResult.errorMessage ?: JSONObject.NULL
        }
        job.put("rawSidecarExportStatus", sidecarStatus)
            .put("rawSidecarExportedFiles", JSONArray(sidecarResult?.exportedFiles ?: emptyList<String>()))
            .put("rawSidecarExpectedCount", sidecarResult?.expectedCount ?: 0)
            .put("rawSidecarRequestedCount", sidecarResult?.requestedCount ?: 0)
            .put("rawSidecarLocallySavedCount", sidecarResult?.locallySavedCount ?: 0)
            .put("rawSidecarLocalFailedCount", sidecarResult?.localFailedCount ?: 0)
            .put("rawSidecarPublicExportedCount", sidecarResult?.publicExportedCount ?: 0)
            .put("rawSidecarPublicFailedCount", sidecarResult?.publicFailedCount ?: 0)
            .put("rawSidecarMissingFilenames", JSONArray(sidecarResult?.missingFilenames ?: emptyList<String>()))
            .put("rawSidecarLocalFailures", JSONArray(sidecarResult?.localFailures ?: emptyList<String>()))
            .put("rawSidecarPublicFailures", JSONArray(sidecarResult?.publicFailures ?: emptyList<String>()))
            .put("rawSidecarFrameManifest", JSONArray(sidecarResult?.frameResults?.map { frame ->
                JSONObject()
                    .put("frameIndex", frame.frameIndex)
                    .put("requested", frame.requested)
                    .put("localFilename", frame.localFilename ?: JSONObject.NULL)
                    .put("localStatus", frame.localStatus)
                    .put("localFailure", frame.localFailure ?: JSONObject.NULL)
                    .put("publicStatus", frame.publicStatus)
                    .put("publicUri", frame.publicUri ?: JSONObject.NULL)
                    .put("publicFailure", frame.publicFailure ?: JSONObject.NULL)
            } ?: emptyList<JSONObject>()))
            .put("rawSidecarError", sidecarError)
        sidecarResult?.frameResults?.forEach { frameResult ->
            val frameArray = job.optJSONArray("frames")
            for (index in 0 until (frameArray?.length() ?: 0)) {
                val frame = frameArray?.optJSONObject(index) ?: continue
                val identity = frame.optInt("frameIndex", frame.optInt("index", index))
                if (identity != frameResult.frameIndex) continue
                frame.put("dngSidecarPublicStatus", frameResult.publicStatus)
                    .put("publicDngUri", frameResult.publicUri ?: JSONObject.NULL)
                    .put("publicDngError", frameResult.publicFailure ?: JSONObject.NULL)
                break
            }
        }
        if (outcome.currentWarning != null) {
            job.put("currentWarning", outcome.currentWarning)
        } else {
            job.remove("currentWarning")
        }
        val isCommittedOutcome = outcome.committed ||
            outcome is RawFusionPublicExportOutcome.CommittedPendingVerification ||
            outcome is RawFusionPublicExportOutcome.CommittedVerificationFailure ||
            outcome is RawFusionPublicExportOutcome.CommittedCancelledBeforeVerification ||
            outcome is RawFusionPublicExportOutcome.CommittedInterruptedBeforeVerification ||
            outcome is RawFusionPublicExportOutcome.VerifiedPendingPostWork ||
            outcome is RawFusionPublicExportOutcome.VerifiedPostWorkInterrupted ||
            outcome is RawFusionPublicExportOutcome.VerifiedSuccess ||
            outcome is RawFusionPublicExportOutcome.VerifiedWithPostExportCancellation
        if (isCommittedOutcome) {
            job.remove("rawPublicExportAttemptStatus")
            job.remove("rawPublicExportAttemptError")
            job.remove("rawPublicExportAttemptAt")
        }
        if (outcome is RawFusionPublicExportOutcome.CommittedPendingVerification) {
            job.put("currentPipelineStage", "PROCESSING")
                .put("userCanMoveDevice", true)
                .put("exportError", JSONObject.NULL)
                .put("exportedAt", System.currentTimeMillis())
            if (outcome.finalOutputFormat.shouldExportRawSidecar) {
                job.put("rawSidecarExportStatus", "PENDING")
            }
        } else if (outcome is RawFusionPublicExportOutcome.VerifiedPendingPostWork) {
            job.put("currentPipelineStage", "PROCESSING")
                .put("processStatus", "EXPORT_VERIFIED_PENDING_POST_WORK")
                .put("userCanMoveDevice", true)
                .put("exportError", JSONObject.NULL)
                .put("exportedAt", System.currentTimeMillis())
            if (outcome.finalOutputFormat.shouldExportRawSidecar) {
                job.put("rawSidecarExportStatus", "PENDING")
            }
        } else {
            job.put("exportedAt", System.currentTimeMillis())
        }
        when (outcome) {
            is RawFusionPublicExportOutcome.VerifiedSuccess,
            is RawFusionPublicExportOutcome.VerifiedWithPostExportCancellation -> {
                job.put("currentPipelineStage", terminalPolicy.durablePipelineStage)
                    .put(
                        "processStatus",
                        if (terminalPolicy.durablePipelineStage == "COMPLETE") {
                            "NIGHT_FUSION_COMPLETE"
                        } else {
                            "NIGHT_FUSION_COMPLETE_PARTIAL"
                        }
                    )
                    .put("userCanMoveDevice", true)
                    .put("exportError", outcome.currentWarning ?: JSONObject.NULL)
            }
            is RawFusionPublicExportOutcome.VerifiedPostWorkInterrupted -> {
                job.put("currentPipelineStage", "PARTIAL")
                    .put("processStatus", "EXPORT_VERIFIED_POST_WORK_INTERRUPTED")
                    .put("userCanMoveDevice", true)
                    .put("exportError", outcome.currentError ?: JSONObject.NULL)
            }
            is RawFusionPublicExportOutcome.UncommittedFailure -> {
                job.put("currentPipelineStage", terminalPolicy.durablePipelineStage)
                    .put(
                        "processStatus",
                        if (outcome.postExportCancellationRequested && !terminalPolicy.hasCurrentLocalResult) {
                            "EXPORT_CANCELLED_BEFORE_COMMIT"
                        } else if (terminalPolicy.hasCurrentLocalResult) {
                            "LOCAL_OUTPUT_COMMITTED_EXPORT_FAILED"
                        } else {
                            "EXPORT_FAILED_KEEPING_CACHE"
                        }
                    )
                    .put("userCanMoveDevice", true)
                    .put("exportError", outcome.currentError)
                outcome.rawPublicExportAttemptError?.let {
                    job.put("rawPublicExportAttemptStatus", outcome.rawPublicExportAttemptStatus)
                        .put("rawPublicExportAttemptError", it)
                        .put("rawPublicExportAttemptAt", outcome.rawPublicExportAttemptAt)
                }
            }
            is RawFusionPublicExportOutcome.CommittedVerificationFailure -> {
                job.put("currentPipelineStage", "PARTIAL")
                    .put("processStatus", "EXPORT_VERIFICATION_FAILED")
                    .put("userCanMoveDevice", true)
                    .put("exportError", outcome.currentError)
            }
            is RawFusionPublicExportOutcome.CommittedPendingVerification -> {
                job.put("processStatus", "EXPORT_COMMITTED_PENDING")
            }
            is RawFusionPublicExportOutcome.VerifiedPendingPostWork -> {
                // processStatus already set in the pipeline-stage block above
            }
            is RawFusionPublicExportOutcome.CommittedCancelledBeforeVerification -> {
                job.put("currentPipelineStage", "PARTIAL")
                    .put("processStatus", "EXPORT_COMMITTED_CANCELLED_BEFORE_VERIFICATION")
                    .put("userCanMoveDevice", true)
                    .put("exportError", outcome.currentError)
            }
            is RawFusionPublicExportOutcome.CommittedInterruptedBeforeVerification -> {
                job.put("currentPipelineStage", "PARTIAL")
                    .put("processStatus", "EXPORT_COMMITTED_INTERRUPTED_BEFORE_VERIFICATION")
                    .put("userCanMoveDevice", true)
                    .put("exportError", outcome.currentError)
            }
        }
        if (job.optString("currentPipelineStage") in setOf("COMPLETE", "PARTIAL", "FAILED", "CANCELLED")) {
            job.optString(ACTIVE_OPERATION_ID).takeIf { it.isNotBlank() }?.let { job.put(TERMINAL_OPERATION_ID, it) }
        }
        val exportUri = outcome.export?.uriString
        if (exportUri != null) {
            job.put("galleryPublicExportLinkage", exportUri)
        } else {
            job.remove("galleryPublicExportLinkage")
        }
    }
    markMediaStoreExportJournalsTerminalPersisted(jobDir, operationLease)
}

/**
 * Shared engine for converging a durable `exportCommitState == UNKNOWN` record at the next
 * mutation entry.  This is the ONE policy implementation for MAIN commit-state convergence;
 * [settleUnknownPublicCommitState] is a thin wrapper and [settleMediaStoreExportDebt] delegates
 * to the same engine, so no duplicated policy engine exists anywhere else in production.
 * A terminal job whose metadata still records `exportCommitState=UNKNOWN` with preserved URI
 * evidence is re-reconciled exactly as restart recovery would reconcile the same evidence:
 *
 * - Every export journal is classified against the provider (verified/pending/absent), and the
 *   same side effects restart recovery applies are applied: complete verifiable content is
 *   committed, unverifiable pending rows are deleted, journal states follow the observed truth.
 * - The linked MAIN_IMAGE classification then converges the metadata:
 *   - committed+verified → VERIFIED (`galleryExportCommitted=true`, `exportVerified=true`, job
 *     again mutable, recovery claims cleared);
 *   - committed+unverified → PUBLIC_COMMITTED_UNVERIFIED with the exact verification debt
 *     restart recovery records (job stays blocked until the committed result is verified);
 *   - proven absent/pending → NOT_COMMITTED (linkage removed, job again mutable);
 *   - delete failure stays CLEANUP_REQUIRED and is recorded as the same ambiguous recovery debt
 *     restart recovery records;
 *   - inconclusive inspection leaves COMMIT_UNKNOWN untouched; restart recovery remains the
 *     authority for that evidence.
 *
 * A job that already records a real committed claim (`galleryExportCommitted=true`) is never
 * touched: UNKNOWN is not a commit claim, so an already-committed result is always preserved.
 * Fatal errors are rethrown without any metadata write; non-fatal failures leave the state
 * untouched. Returns true when the durable evidence converged.
 */
internal fun convergeUnknownCommitStateRecord(
    context: Context,
    jobDir: File,
    access: MediaStoreExportRecoveryAccess = ContextMediaStoreExportRecoveryAccess(context)
): Boolean = try {
    var converged = false
    var inconclusive = false
    var settledLinkage = ""
    var classificationByAttempt: Map<String, MediaStoreExportRecoveryResult> = emptyMap()
    KeplerJobMetadata.update(jobDir) { job ->
        if (job.optString("exportCommitState") != GalleryExportCommitState.UNKNOWN.name) return@update
        if (job.optBoolean("galleryExportCommitted", false)) return@update
        settledLinkage = job.optString("galleryPublicExportLinkage").takeIf { it.isNotBlank() } ?: return@update
        val journals = MediaStoreExportJournal.list(jobDir)
        val results = recoverMediaStoreExportJournals(jobDir, access)
        // The export journals are the durable authority for each attempt's evidence. Recover
        // every journal with the exact restart classification (including its provider side
        // effects), then converge the metadata from the linked MAIN_IMAGE classification.
        classificationByAttempt = results.associateBy { it.attemptId }
        val mainJournal = journals.asSequence()
            .filter { it.role == MediaStoreExportRole.MAIN_IMAGE && it.uri == settledLinkage }
            .firstOrNull()
            ?: return@update
        val mainClassification = classificationByAttempt[mainJournal.exportAttemptId]?.classification ?: return@update
        when (mainClassification) {
            MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED,
            MediaStoreExportRecoveryClassification.PENDING_VERIFIED_AND_COMMITTED -> {
                converged = true
                job.put("exportCommitState", GalleryExportCommitState.VERIFIED.name)
                    .put("exportStatus", "EXPORTED")
                    .put("galleryExportCommitted", true)
                    .put("exportVerified", true)
                    .put("recoveryState", "STABLE")
                    .put("lastRecoveryClassification", mainClassification.name)
                    .put("lastRecoveryMessage", "Converged from unknown to verified.")
                    .put("recoveredAt", System.currentTimeMillis())
                    .put("exportDisplayName", mainJournal.displayName)
                    .put("exportMimeType", mainJournal.mimeType)
                job.remove("recoveryMessage")
                job.remove("exportVerificationFailed")
            }
            MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED -> {
                converged = true
                job.put("exportCommitState", GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name)
                    .put("exportStatus", "COMMITTED_UNVERIFIED")
                    .put("galleryExportCommitted", true)
                    .put("exportVerified", false)
                    .put("exportVerificationFailed", true)
                    .put("recoveryState", "PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION")
                    .put("recoveryMessage", "Public commit found but verification pending; recovery required.")
            }
            MediaStoreExportRecoveryClassification.PENDING_DELETED,
            MediaStoreExportRecoveryClassification.PUBLIC_COMMIT_MISSING,
            MediaStoreExportRecoveryClassification.CLEANED -> {
                converged = true
                job.put("exportCommitState", GalleryExportCommitState.NOT_COMMITTED.name)
                    .put("exportStatus", "FAILED")
                    .put("galleryExportCommitted", false)
                    .put("exportVerified", false)
                    .put("recoveryState", "STABLE")
                    .put("lastRecoveryClassification", mainClassification.name)
                    .put("lastRecoveryMessage", "Converged from unknown; no committed row found.")
                    .put("recoveredAt", System.currentTimeMillis())
                    .put("exportError", "Public commit state was unknown; provider inspection proves no committed row.")
                job.remove("recoveryMessage")
                job.remove("exportVerificationFailed")
                job.remove("galleryPublicExportLinkage")
            }
            MediaStoreExportRecoveryClassification.DELETE_FAILED -> {
                converged = true
                job.put("exportCommitState", GalleryExportCommitState.NOT_COMMITTED.name)
                    .put("exportStatus", "FAILED")
                    .put("galleryExportCommitted", false)
                    .put("exportVerified", false)
                    .put("recoveryState", "AMBIGUOUS_RECOVERY_REQUIRED")
                    .put("recoveryMessage", classificationByAttempt[mainJournal.exportAttemptId]?.message
                        ?: "Delete failed; ambiguous recovery state.")
                    .put("exportError", "Public commit state was unknown; provider inspection proves no committed row.")
                job.remove("galleryPublicExportLinkage")
            }
            else -> {
                // AMBIGUOUS / INSERT_RESULT_UNKNOWN: conclusive inspection is impossible, so the
                // UNKNOWN record and its journals stay untouched for restart recovery.
                inconclusive = true
            }
        }
    }
    if (converged && !inconclusive) {
        // A row proven absent is settled evidence: no cleanup work remains for that journal, so a
        // pre-commit journal is moved to CLEANED and can no longer block the mutation gate.
        // Genuine DELETE_FAILED debt is retained as CLEANUP_REQUIRED (the guard excludes it).
        MediaStoreExportJournal.list(jobDir)
            .filter { journal ->
                journal.state in setOf(
                    MediaStoreExportState.PREPARED,
                    MediaStoreExportState.ROW_INSERTED,
                    MediaStoreExportState.CONTENT_WRITTEN
                ) && classificationByAttempt[journal.exportAttemptId]?.classification ==
                    MediaStoreExportRecoveryClassification.PUBLIC_COMMIT_MISSING
            }
            .forEach { journal ->
                journal.transition(jobDir, MediaStoreExportState.CLEANED)
            }
        // Sidecar frames are classified by their OWN evidence, exactly like restart recovery:
        // verified/committed-unverified URIs are preserved, unresolved evidence stays commit-unknown,
        // and rows proven absent stop claiming a public URI.  This keeps live execution, restart
        // recovery, and same-process settlement on the same sidecar truth.
        KeplerJobMetadata.update(jobDir) { job ->
            reconstructRawSidecarJournalEvidence(
                jobDir,
                job,
                classifications = classificationByAttempt.mapValues { it.value.classification }
            )
        }
    }
    converged
} catch (failure: Error) {
    throw failure
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

/** Thin wrapper over the shared [convergeUnknownCommitStateRecord] engine; no policy duplication. */
internal fun settleUnknownPublicCommitState(
    context: Context,
    jobDir: File,
    access: MediaStoreExportRecoveryAccess = ContextMediaStoreExportRecoveryAccess(context)
): Boolean = convergeUnknownCommitStateRecord(context, jobDir, access)

/**
 * Bounded same-process MediaStore debt convergence driven by ACTUAL unresolved current journals,
 * not by `job.exportCommitState == UNKNOWN`.  Every relevant journal is inspected by role:
 *
 * - MAIN_IMAGE journals are reconciled against the provider and the current linkage/metadata is
 *   converged exactly like restart recovery (UNKNOWN records through the shared
 *   [convergeUnknownCommitStateRecord] engine; committed-unverified records only ever move FORWARD
 *   to VERIFIED with exact current journal+URI correlation).
 * - RAW_DNG_SIDECAR journals converge from THEIR OWN evidence (verified / committed-unverified /
 *   commit-unknown / proven precommit) independent of the MAIN image state.
 *
 * Settlement authorities (never two concurrent):
 * - CASE A: an exact retained process-local PUBLIC_EXPORT lease is the single authority: the
 *   provider/journal/metadata convergence runs under that retained owner and releases it on
 *   success; UNKNOWN evidence keeps E retained (`false`) so nothing is lost.
 * - CASE B: otherwise a single temporary recovery authority is reserved under the job lock, the
 *   convergence runs under it, the temporary authority is released, and the caller re-runs the
 *   mutation gate before acquiring its own lease.  No second durable journal is ever created.
 * - A dead ACTIVE PUBLIC_EXPORT owner with converged/terminal metadata is finalized in the same
 *   process (no restart) by the shared dead-owner finalizer.
 *
 * Returns `true` when the pass left no journal in a mutation-gate-blocking state.  An UNKNOWN
 * (inconclusive provider) journal, `DELETE_FAILED`, or a committed-unverified PUBLIC_COMMITTED
 * row intentionally keeps the gate blocked and returns `false`: the exact journals/metadata/URI
 * remain the same reachable debt for restart recovery and the next same-process pass.
 *
 * Never runs while a live current-runtime operation owns the job: that owner performs its own
 * settlement/recovery.  Fatal Errors and CancellationException propagate unchanged.
 */
internal fun settleMediaStoreExportDebt(
    context: Context,
    jobDir: File,
    access: MediaStoreExportRecoveryAccess = ContextMediaStoreExportRecoveryAccess(context)
): Boolean {
    return try {
        val metadata = KeplerJobMetadata.read(jobDir)
        // Resolve any exact retained PUBLIC_EXPORT lease so provider settlement uses
        // the same durable owner authority as restart recovery.
        val retainedLease = KeplerJobMetadata.findOperationLease(jobDir)?.let { lease ->
            if (lease.currentDurableOperationKind() == KeplerActiveOperationKind.PUBLIC_EXPORT &&
                lease.currentDurableOperationId()?.isNotBlank() == true
            ) lease else null
        }
        if (metadata.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id &&
            metadata.optString(ACTIVE_OPERATION_ID).isNotBlank()
        ) {
            // A live current-process operation owns this job; it settles/acks its own journals.
            return false
        }
        if (retainedLease != null) {
            // CASE A: the exact retained lease is the single recovery settlement authority.
            return KeplerJobMetadata.withJobLock(jobDir) {
                settleRetainedPublicExportLease(jobDir, retainedLease, access)
            }
        }
        val journals = MediaStoreExportJournal.list(jobDir)
        val unknownRecord = metadata.optString("exportCommitState") == GalleryExportCommitState.UNKNOWN.name &&
            !metadata.optBoolean("galleryExportCommitted", false)
        val committedUnverifiedUpgradeCandidate = metadata.optString("exportCommitState") ==
            GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name &&
            metadata.optBoolean("galleryExportCommitted", false) &&
            metadata.optString("exportUri").isNotBlank()
        if (journals.none { it.isGateBlocking() } && !unknownRecord && !committedUnverifiedUpgradeCandidate) {
            // No journal debt and no record to converge.  A dead ACTIVE PUBLIC_EXPORT owner with
            // already-terminal durable metadata is still finalized same-process (no restart).
            finalizeConvergedDeadExportOwner(jobDir)
            return MediaStoreExportJournal.list(jobDir).none { it.isGateBlocking() }
        }
        // CASE B: exactly one temporary recovery authority is reserved under the job lock so the
        // provider/journal/metadata convergence can never race a concurrent mutation acquisition.
        KeplerJobMetadata.withJobLock(jobDir) {
            val authority = KeplerJobMetadata.acquireTemporaryRecoveryAuthority(jobDir)
                ?: return@withJobLock false
            try {
                if (unknownRecord) {
                    // Shared engine, single policy: converges the UNKNOWN record and its journals.
                    convergeUnknownCommitStateRecord(context, jobDir, access)
                }
                val results = recoverMediaStoreExportJournals(jobDir, access)
                val classificationByAttempt = results.associateBy { it.attemptId }
                val hasConclusiveEvidence = classificationByAttempt.values.any {
                    it.classification in setOf(
                        MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED,
                        MediaStoreExportRecoveryClassification.PENDING_VERIFIED_AND_COMMITTED,
                        MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED,
                        MediaStoreExportRecoveryClassification.PENDING_DELETED,
                        MediaStoreExportRecoveryClassification.PUBLIC_COMMIT_MISSING,
                        MediaStoreExportRecoveryClassification.CLEANED,
                        MediaStoreExportRecoveryClassification.DELETE_FAILED
                    )
                }
                if (hasConclusiveEvidence) {
                    // Proven pre-commit/no-row evidence moves pre-commit-band journals to CLEANED
                    // so they no longer block the mutation gate.  DELETE_FAILED stays
                    // CLEANUP_REQUIRED (guard excludes it).
                    MediaStoreExportJournal.list(jobDir)
                        .filter { journal ->
                            journal.state in setOf(
                                MediaStoreExportState.PREPARED,
                                MediaStoreExportState.ROW_INSERTED,
                                MediaStoreExportState.CONTENT_WRITTEN
                            ) && classificationByAttempt[journal.exportAttemptId]?.classification in setOf(
                                MediaStoreExportRecoveryClassification.PUBLIC_COMMIT_MISSING,
                                MediaStoreExportRecoveryClassification.PENDING_DELETED,
                                MediaStoreExportRecoveryClassification.CLEANED
                            )
                        }
                        .forEach { journal -> journal.transition(jobDir, MediaStoreExportState.CLEANED) }
                }
                // Role-aware convergence: sidecars from their own evidence; the MAIN record only
                // moves forward (UNKNOWN handled by the shared engine above; committed-unverified
                // upgraded to VERIFIED here, never downgraded).
                convergeMainAndSidecarEvidence(jobDir, classificationByAttempt)
                // Dead ACTIVE PUBLIC_EXPORT owners with terminal/converged evidence finalize in
                // the same process without another restart.
                finalizeConvergedDeadExportOwner(jobDir)
                // The debt remains (still blocked after this deterministic provider pass) whenever
                // any journal still sits in a mutation-gate-blocking state: AMBIGUOUS /
                // INSERT_RESULT_UNKNOWN / DELETE_FAILED / committed-but-unverified rows.
                !MediaStoreExportJournal.list(jobDir).any { it.isGateBlocking() }
            } finally {
                // The single temporary authority is released; the caller re-runs the gate before
                // acquiring its own mutation lease.
                KeplerJobMetadata.releaseOperation(authority)
            }
        }
    } catch (failure: Error) {
        throw failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

/**
 * CASE A: the exact retained process-local PUBLIC_EXPORT lease is the single recovery settlement
 * authority.  If no pending settlement is registered, the exact operation's interruption
 * settlement is registered first, then the shared owner protocol runs with provider access.
 * UNKNOWN/unresolved evidence keeps E retained (`false`); a converged owner is released and its
 * durable marker cleared.  Never creates a second lease or a second durable journal.
 */
private fun settleRetainedPublicExportLease(
    jobDir: File,
    lease: JobOperationLease,
    access: MediaStoreExportRecoveryAccess
): Boolean {
    val operationId = lease.currentDurableOperationId() ?: return false
    if (lease.pendingPublicExportSettlement() == null) {
        lease.registerPublicExportSettlement(
            operationId = operationId,
            failureMessage = "Retained PUBLIC_EXPORT lease settled by the same-process debt coordinator.",
            finalOutputFormat = null,
            disposition = PublicExportInterruptionDisposition.FAILED
        )
    }
    val settled = try {
        settleOwnedPublicExportInterruption(
            jobDir = jobDir,
            ownerLease = lease,
            failureMessage = "Retained PUBLIC_EXPORT lease settled by the same-process debt coordinator.",
            disposition = PublicExportInterruptionDisposition.FAILED,
            access = access,
            allowDeadOwner = true
        )
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        false
    }
    if (!settled) {
        // UNKNOWN or otherwise-unresolved evidence: E stays retained; the exact journals,
        // metadata, and lease remain the same reachable debt for the next entry.
        return false
    }
    lease.completePublicExportSettlement(operationId)
    lease.clearDurableOperation(operationId)
    lease.release()
    return true
}

/**
 * Same-process dead-ACTIVE convergence: an ACTIVE PUBLIC_EXPORT owner whose runtime is dead is
 * finalized here without another restart, exactly like restart recovery would finalize it.
 * Terminal metadata matches go through the terminal finalizer (which preserves the verification
 * policy); otherwise the recovered evidence classifies the cut.  Owner-correlated journals that
 * still require settlement keep the owner retained for the next entry.
 */
private fun finalizeConvergedDeadExportOwner(jobDir: File) {
    val job = try {
        KeplerJobMetadata.read(jobDir)
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        return
    }
    val activeId = job.optString(ACTIVE_OPERATION_ID).takeIf { it.isNotBlank() } ?: return
    if (job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id) return
    if (job.optString(ACTIVE_OPERATION_KIND) != KeplerActiveOperationKind.PUBLIC_EXPORT.name) return
    val ownerJournals = MediaStoreExportJournal.list(jobDir)
        .filter { it.ownerOperationId == activeId }
    if (ownerJournals.any { it.isGateBlocking() || it.requiresExternalCommitResolution() }) return
    if (job.optString(TERMINAL_OPERATION_ID) == activeId &&
        job.optString("currentPipelineStage") in setOf("COMPLETE", "PARTIAL", "FAILED", "CANCELLED")
    ) {
        // Mirror the restart-recovery terminal contract exactly: the owner-correlated journals
        // are acknowledged (terminal-persisted) first; the terminal finalizer runs only when
        // settlement is SETTLED.  A DEFERRED ACK (ineligible or unresolved journal) retains the
        // owner so the next entry retries the same protocol.
        if (markMediaStoreExportJournalsTerminalPersisted(jobDir) !=
            MediaStoreExportTerminalSettlementStatus.SETTLED
        ) {
            return
        }
        check(KeplerJobMetadata.finalizeRecoveredTerminalOperation(jobDir, activeId)) {
            "Could not durably finalize dead terminal operation $activeId"
        }
        return
    }
    val committed = job.optBoolean("galleryExportCommitted", false) &&
        job.optString("exportUri").isNotBlank()
    val verified = job.optBoolean("exportVerified", false)
    val classification = when {
        verified -> KeplerJobRecoveryClassification.PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL
        committed -> KeplerJobRecoveryClassification.PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION
        currentProcessingAttemptHasRequiredOutputClaim(jobDir) ->
            KeplerJobRecoveryClassification.LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL
        else -> KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT
    }
    check(KeplerJobMetadata.finalizeRecoveredInterruptedOperation(
        jobDir,
        activeId,
        classification,
        "같은 프로세스에서 이전 실행의 내보내기 증거를 안전하게 확인했습니다."
    )) { "Could not durably finalize dead interrupted export operation $activeId" }
}

/**
 * Converges every RAW_DNG_SIDECAR frame from its OWN classification and moves the MAIN record
 * only forward: a committed-unverified record may be upgraded to VERIFIED when the exact current
 * journal+URI+operation correlation proves it; VERIFIED is never downgraded by this pass.  The
 * UNKNOWN record converges ONLY through the shared [convergeUnknownCommitStateRecord] engine.
 * Sidecars are always reconstructed by role: a verified MAIN image never forces sidecars, and
 * unresolved sidecar journals stay commit-unknown debt.
 */
private fun convergeMainAndSidecarEvidence(
    jobDir: File,
    classificationByAttempt: Map<String, MediaStoreExportRecoveryResult>
) {
    KeplerJobMetadata.update(jobDir) { job ->
        reconstructRawSidecarJournalEvidence(
            jobDir,
            job,
            classifications = classificationByAttempt.mapValues { it.value.classification }
        )
        // MAIN convergence: the UNKNOWN record is handled ONLY by the shared engine; a VERIFIED
        // record is authoritative and never downgraded; a committed-unverified record may only
        // move FORWARD to VERIFIED with the exact current journal+URI+operation correlation.
        val commitState = job.optString("exportCommitState")
        if (commitState == GalleryExportCommitState.UNKNOWN.name) return@update
        if (commitState == GalleryExportCommitState.VERIFIED.name) return@update
        if (!job.optBoolean("galleryExportCommitted", false)) return@update
        val settledLinkage = job.optString("exportUri").takeIf { it.isNotBlank() }
            ?: job.optString("galleryPublicExportLinkage").takeIf { it.isNotBlank() }
            ?: return@update
        val authorityOperationId = job.optString(TERMINAL_OPERATION_ID).takeIf { it.isNotBlank() }
            ?: job.optString(ACTIVE_OPERATION_ID).takeIf { it.isNotBlank() }
        val journals = MediaStoreExportJournal.list(jobDir)
        val mainJournal = journals.asSequence()
            .filter {
                it.role == MediaStoreExportRole.MAIN_IMAGE &&
                    it.uri == settledLinkage &&
                    (authorityOperationId == null ||
                        it.ownerOperationId == authorityOperationId || it.ownerOperationId.isNullOrBlank())
            }
            .firstOrNull() ?: return@update
        val mainClassification = classificationByAttempt[mainJournal.exportAttemptId]?.classification
            ?: return@update
        when (mainClassification) {
            MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED,
            MediaStoreExportRecoveryClassification.PENDING_VERIFIED_AND_COMMITTED -> {
                job.put("exportCommitState", GalleryExportCommitState.VERIFIED.name)
                    .put("exportStatus", "EXPORTED")
                    .put("galleryExportCommitted", true)
                    .put("exportVerified", true)
                    .put("recoveryState", "STABLE")
                    .put("lastRecoveryClassification", mainClassification.name)
                    .put("lastRecoveryMessage", "공개 내보내기 결과를 확인했습니다.")
                    .put("recoveredAt", System.currentTimeMillis())
                    .put("exportDisplayName", mainJournal.displayName)
                    .put("exportMimeType", mainJournal.mimeType)
                job.remove("recoveryMessage")
                job.remove("exportVerificationFailed")
            }
            // Never downgrade a committed record: classifications that cannot prove VERIFIED
            // (committed-unverified, delete failure, proven pre-commit) leave the authoritative
            // committed record untouched.
            else -> Unit
        }
    }
}

internal fun MediaStoreExportJournal.isGateBlocking(): Boolean =
    !isTerminallyStable() && state in setOf(
        MediaStoreExportState.PREPARED,
        MediaStoreExportState.ROW_INSERTED,
        MediaStoreExportState.CONTENT_WRITTEN,
        MediaStoreExportState.PUBLIC_COMMITTED,
        MediaStoreExportState.CLEANUP_REQUIRED
    )

fun requestedOutputFormatForSetting(finalOutputFormat: FinalOutputFormat): OutputFormat = when {
    finalOutputFormat.shouldExportHeif -> OutputFormat.HEIF
    finalOutputFormat.shouldExportJpeg -> OutputFormat.JPEG
    else -> OutputFormat.PNG
}

private fun writeGalleryBitmap(
    context: Context,
    bitmap: Bitmap,
    displayName: String,
    format: OutputFormat,
    relativeAlbumPath: String,
    quality: Int,
    fallbackUsed: Boolean,
    cancellation: KeplerPipelineCancellation,
    jobDir: File?,
    ownerOperationId: String? = null
): GalleryExportResult {
    val inserted = insertPublicFile(
        context = context,
        displayName = displayName,
        mimeType = format.mimeType,
        relativePath = relativeAlbumPath,
        collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        cancellation = cancellation,
        jobDir = jobDir,
        role = MediaStoreExportRole.MAIN_IMAGE,
        expectedWidth = bitmap.width,
        expectedHeight = bitmap.height,
        ownerOperationId = ownerOperationId
    ) { output ->
        val ok = when (format) {
            OutputFormat.HEIF -> writeHeifViaTempFile(context, bitmap, quality, output)
            OutputFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            OutputFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        if (!ok) error("${format.label} encode returned false")
    } ?: return GalleryExportResult(
        success = false,
        uriString = null,
        displayName = displayName,
        mimeType = format.mimeType,
        fileSizeBytes = 0L,
        formatUsed = format,
        fallbackUsed = fallbackUsed,
        errorMessage = "MediaStore insert/write failed"
    )

    if (inserted.commitState == GalleryExportCommitState.UNKNOWN) {
        return GalleryExportResult(
            success = false,
            uriString = inserted.uri.toString(),
            displayName = displayName,
            mimeType = format.mimeType,
            fileSizeBytes = inserted.size,
            formatUsed = format,
            fallbackUsed = fallbackUsed,
            errorMessage = "MediaStore public commit state could not be determined; preserving exact URI evidence.",
            attemptedFormats = listOf(format),
            candidateFailureReasons = listOf("MediaStore public commit state unknown"),
            verification = null,
            publicCommitState = GalleryExportCommitState.UNKNOWN
        )
    }

    val committedUri = inserted.uri
    val verification = verifyGalleryExportResult(
        context = context,
        uriString = committedUri.toString(),
        expectation = GalleryExportExpectation(
            format = format,
            width = bitmap.width,
            height = bitmap.height
        )
    )

    if (verification !is GalleryExportVerification.Verified) {
        return GalleryExportResult(
            success = false,
            uriString = committedUri.toString(),
            displayName = displayName,
            mimeType = format.mimeType,
            fileSizeBytes = inserted.size,
            formatUsed = format,
            fallbackUsed = fallbackUsed,
            errorMessage = "Verification failed: ${(verification as? GalleryExportVerification.RetryableFailure)?.reason ?: (verification as? GalleryExportVerification.PermanentFailure)?.reason}",
            attemptedFormats = listOf(format),
            candidateFailureReasons = listOf((verification as? GalleryExportVerification.RetryableFailure)?.reason ?: (verification as? GalleryExportVerification.PermanentFailure)?.reason.orEmpty()),
            verification = verification,
            publicCommitState = GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED
        )
    }

    jobDir?.let { owner ->
        try {
            inserted.journal?.transition(owner, MediaStoreExportState.VERIFIED)
        } catch (failure: Error) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return GalleryExportResult(
                success = false,
                uriString = committedUri.toString(),
                displayName = verification.displayName,
                mimeType = verification.mediaStoreMime,
                fileSizeBytes = verification.size,
                formatUsed = verification.detectedFormat,
                fallbackUsed = fallbackUsed,
                errorMessage = "Public row verified but export journal verification could not be persisted: ${failure.message}",
                attemptedFormats = listOf(format),
                candidateFailureReasons = listOf(failure.message ?: "Export journal verification failed"),
                verification = GalleryExportVerification.RetryableFailure(
                    "Export journal verification could not be persisted: ${failure.message}"
                ),
                publicCommitState = GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED
            )
        }
    }

    return GalleryExportResult(
        success = true,
        uriString = committedUri.toString(),
        displayName = verification.displayName,
        mimeType = verification.mediaStoreMime,
        fileSizeBytes = verification.size,
        formatUsed = verification.detectedFormat,
        fallbackUsed = fallbackUsed,
        errorMessage = null,
        attemptedFormats = listOf(format),
        verification = verification,
        publicCommitState = GalleryExportCommitState.VERIFIED
    )
}

private fun abandonMediaStoreAttempt(
    context: Context,
    jobDir: File,
    journal: MediaStoreExportJournal,
    uri: Uri
): MediaStoreExportJournal {
    val abandoned = journal.transition(jobDir, MediaStoreExportState.CLEANUP_REQUIRED, uri.toString())
    val deleted = deleteMediaStoreRowForAbandon(context, uri)
    return if (deleted) abandoned.transition(jobDir, MediaStoreExportState.CLEANED) else abandoned
}

internal fun deleteMediaStoreRowForAbandon(context: Context, uri: Uri): Boolean {
    return try {
        mediaStoreAbandonDeleteFailureForTest?.let { failure ->
            mediaStoreAbandonDeleteFailureForTest = null
            throw failure
        }
        context.contentResolver.delete(uri, null, null) == 1
    } catch (failure: Error) {
        throw failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private data class InsertedPublicFile(
    val uri: Uri,
    val size: Long,
    val journal: MediaStoreExportJournal?,
    val commitState: GalleryExportCommitState = GalleryExportCommitState.VERIFIED
)

/** Returns true for a proven non-pending row, false for an absent/pending row, null when unknown. */
private fun inspectPublicCommitState(context: Context, uri: Uri): Boolean? {
    mediaStorePublicCommitStateForTest?.let { return it(uri) }
    return try {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.IS_PENDING),
            null,
            null,
            null
        ) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return false
            it.getInt(0) == 0
        }
    } catch (failure: Error) {
        throw failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private fun inspectPublicCommitStatePreservingPrimary(
    context: Context,
    uri: Uri,
    primary: Throwable
): Boolean? = try {
    inspectPublicCommitState(context, uri)
} catch (secondary: Throwable) {
    throw requireNotNull(combineSettlementFailure(primary, secondary))
}

private fun insertPublicFile(
    context: Context,
    displayName: String,
    mimeType: String,
    relativePath: String,
    collectionUri: Uri,
    cancellation: KeplerPipelineCancellation,
    jobDir: File? = null,
    role: MediaStoreExportRole = MediaStoreExportRole.MAIN_IMAGE,
    frameIndex: Int? = null,
    expectedSizeBytes: Long? = null,
    expectedSha256: String? = null,
    expectedWidth: Int? = null,
    expectedHeight: Int? = null,
    ownerOperationId: String? = null,
    writer: (OutputStream) -> Unit
): InsertedPublicFile? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000L)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    var uri: Uri? = null
    var journal: MediaStoreExportJournal? = null
    var insertReturned = false
    var publicCommitAttempted = false

    fun preserveObservedPublicRow(
        state: Boolean?,
        primaryFailure: Throwable? = null
    ): InsertedPublicFile? {
        if (uri == null || state == false) return null
        val commitState = if (state == true) {
            // A journal write can fail after the provider has made the row public. Keep the
            // pre-commit evidence in place; recovery will retry the same transition.
            try {
                journal = journal?.transition(jobDir!!, MediaStoreExportState.PUBLIC_COMMITTED, uri.toString())
            } catch (secondary: Throwable) {
                if (secondary is Error || secondary is CancellationException) {
                    throw requireNotNull(combineSettlementFailure(primaryFailure, secondary))
                }
                // The exact URI and provider state remain the authoritative evidence.
            }
            GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED
        } else {
            // Unknown provider state is never safe to abandon or use for fallback.
            GalleryExportCommitState.UNKNOWN
        }
        return InsertedPublicFile(
            uri = uri!!,
                size = try {
                    queryMediaSize(context, uri!!)
                } catch (secondary: Throwable) {
                    if (secondary is Error || secondary is CancellationException) {
                        throw requireNotNull(combineSettlementFailure(primaryFailure, secondary))
                    }
                    0L
                },
            journal = journal,
            commitState = commitState
        )
    }

    return try {
        cancellation.throwIfCancelled()
        journal = jobDir?.let {
            MediaStoreExportJournal.create(
                jobDir = it,
                role = role,
                frameIndex = frameIndex,
                displayName = displayName,
                relativePath = relativePath,
                mimeType = mimeType,
                collectionUri = collectionUri,
                expectedSizeBytes = expectedSizeBytes,
                expectedSha256 = expectedSha256,
                expectedWidth = expectedWidth,
                expectedHeight = expectedHeight
                ,ownerOperationId = ownerOperationId
            )
        }
        val insertedUri = resolver.insert(collectionUri, values)
        insertReturned = true
        if (insertedUri == null) {
            journal = journal?.transition(jobDir!!, MediaStoreExportState.INSERT_FAILED_NO_ROW)
            return null
        }
        uri = insertedUri
        journal = journal?.transition(jobDir!!, MediaStoreExportState.ROW_INSERTED, uri.toString())
        cancellation.throwIfCancelled()
        resolver.openOutputStream(uri)?.use(writer) ?: error("openOutputStream returned null")
        journal = journal?.transition(jobDir!!, MediaStoreExportState.CONTENT_WRITTEN)
        cancellation.throwIfCancelled()
        publicCommitAttempted = true
        val updateCount = try {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        } catch (failure: Exception) {
            preserveObservedPublicRow(
                inspectPublicCommitStatePreservingPrimary(context, uri, failure), failure
            )?.let { return it }
            throw failure
        }
        if (updateCount != 1) {
            preserveObservedPublicRow(inspectPublicCommitState(context, uri))?.let { return it }
            journal?.let { journal = abandonMediaStoreAttempt(context, jobDir!!, it, uri!!) }
            return null
        }
        try {
            journal = journal?.transition(jobDir!!, MediaStoreExportState.PUBLIC_COMMITTED)
        } catch (failure: Exception) {
            preserveObservedPublicRow(true)?.let { return it }
            throw failure
        }
        val mediaSize = try {
            queryMediaSize(context, uri)
        } catch (failure: Error) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            0L
        }
        InsertedPublicFile(uri, mediaSize, journal, GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED)
    } catch (ce: CancellationException) {
        if (publicCommitAttempted) {
            val observed = inspectPublicCommitStatePreservingPrimary(context, uri!!, ce)
            if (observed == true) {
                try {
                    preserveObservedPublicRow(observed, ce)?.let { return it }
                } catch (secondary: Throwable) {
                    throw requireNotNull(combineSettlementFailure(ce, secondary))
                }
            }
            if (observed == null) throw ce
        }
        var cleanupFailure: Throwable? = null
        if (insertReturned && uri != null && journal != null) {
            try {
                journal = abandonMediaStoreAttempt(context, jobDir!!, journal!!, uri!!)
            } catch (failure: Throwable) {
                cleanupFailure = failure
            }
        }
        throw requireNotNull(combineSettlementFailure(ce, cleanupFailure))
    } catch (error: Error) {
        // A fatal provider/journal failure must escape, but a row proven public must not be
        // deleted as though the commit never happened.
        val observed = if (publicCommitAttempted && uri != null) {
            inspectPublicCommitStatePreservingPrimary(context, uri!!, error)
        } else false
        if (observed == true) {
            try {
                journal = journal?.transition(jobDir!!, MediaStoreExportState.PUBLIC_COMMITTED, uri!!.toString())
            } catch (secondary: Throwable) {
                throw requireNotNull(combineSettlementFailure(error, secondary))
            }
        } else if (observed == null) {
            throw error
        } else if (insertReturned && uri != null && journal != null) {
            var cleanupFailure: Throwable? = null
            try {
                journal = abandonMediaStoreAttempt(context, jobDir!!, journal!!, uri!!)
            } catch (failure: Throwable) {
                cleanupFailure = failure
            }
            throw requireNotNull(combineSettlementFailure(error, cleanupFailure))
        }
        throw error
    } catch (error: Exception) {
        if (publicCommitAttempted) {
            preserveObservedPublicRow(
                inspectPublicCommitStatePreservingPrimary(context, uri!!, error), error
            )?.let { return it }
        }
        var cleanupFailure: Throwable? = null
        if (insertReturned && uri != null && journal != null) {
            try {
                journal = abandonMediaStoreAttempt(context, jobDir!!, journal!!, uri!!)
            } catch (failure: Throwable) {
                cleanupFailure = failure
            }
        }
        val combined = combineSettlementFailure(error, cleanupFailure)
        if (combined is Error || combined is CancellationException) throw requireNotNull(combined)
        null
    }
}

@Suppress("RestrictedApi")
private fun writeHeifViaTempFile(
    context: Context,
    bitmap: Bitmap,
    quality: Int,
    output: OutputStream
): Boolean {
    val tempFile = File.createTempFile("kepler_export_", ".heic", context.cacheDir)
    var writer: HeifWriter? = null
    val timeoutMs = heifStopTimeoutMs(bitmap.width, bitmap.height)
    var primaryFailure: Throwable? = null
    return try {
        val createdWriter = HeifWriter.Builder(
            tempFile.absolutePath,
            bitmap.width,
            bitmap.height,
            HeifWriter.INPUT_MODE_BITMAP
        )
            .setQuality(quality)
            .build()
        writer = createdWriter
        createdWriter.start()
        createdWriter.addBitmap(bitmap)
        createdWriter.stop(timeoutMs)
        // stop() is the producer settlement point. Re-inspect the owned temp file only after it
        // returns, so a partially written HEIF can never be copied into MediaStore.
        val digest = NoFollowFileSystem.copyVerified(tempFile, output)
        check(digest.size > 0L) { "HEIF writer produced an empty temporary file" }
        true
    } catch (cancelled: CancellationException) {
        primaryFailure = cancelled
        throw cancelled
    } catch (error: Error) {
        primaryFailure = error
        throw error
    } catch (error: Exception) {
        primaryFailure = error
        Log.w(
            "KeplerGalleryExporter",
            "HEIF encode failed pixels=${bitmap.width.toLong() * bitmap.height} timeoutMs=$timeoutMs: ${error.message}",
            error
        )
        false
    } finally {
        var cleanupFailure: Throwable? = null
        try {
            writer?.close()
        } catch (failure: Throwable) {
            cleanupFailure = failure
            if (failure !is Error && failure !is CancellationException) {
                Log.w("KeplerGalleryExporter", "Failed to close HEIF writer.", failure)
            }
        }
        try {
            if (tempFile.exists() && !tempFile.delete()) {
                Log.w("KeplerGalleryExporter", "Failed to delete temporary HEIF file: ${tempFile.absolutePath}")
            }
        } catch (failure: Throwable) {
            cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
            if (failure !is Error && failure !is CancellationException) {
                Log.w("KeplerGalleryExporter", "Failed to delete temporary HEIF file.", failure)
            }
        }
        val combined = combineSettlementFailure(primaryFailure, cleanupFailure)
        if (combined is Error || combined is CancellationException) {
            throw requireNotNull(combined)
        }
    }
}

/** Resolution-aware upper bound for the asynchronous HeifWriter encoder. */
internal fun heifStopTimeoutMs(width: Int, height: Int): Long {
    val pixels = width.toLong().coerceAtLeast(1L) * height.toLong().coerceAtLeast(1L)
    return (2_000L + pixels / 400L).coerceIn(3_000L, 30_000L)
}
