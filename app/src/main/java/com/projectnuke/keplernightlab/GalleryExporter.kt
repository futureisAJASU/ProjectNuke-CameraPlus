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
    val verification: GalleryExportVerification? = null
)

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
        KeplerJobMetadata.beginActiveOperation(
            jobDir,
            kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
            ownerLease = ownerLease
        )
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
        if (!result.success) {
            cancellation.throwIfCancelled()
        }
        if (result.success) {
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

/** Reads only evidence owned by the exact current PUBLIC_EXPORT operation. */
internal fun inspectOwnedPublicExportEvidence(
    jobDir: File,
    ownerLease: JobOperationLease
): OwnedPublicExportEvidence? {
    check(KeplerJobMetadata.isOperationOwner(jobDir, ownerLease)) {
        "Public export evidence requires the exact owning lease"
    }
    val metadata = KeplerJobMetadata.read(jobDir)
    val operationId = metadata.optString(ACTIVE_OPERATION_ID)
    if (operationId.isBlank() || metadata.optString(ACTIVE_OPERATION_KIND) != KeplerActiveOperationKind.PUBLIC_EXPORT.name) {
        return null
    }
    check(metadata.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id) {
        "Public export evidence requires a current-runtime owner"
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
    disposition: PublicExportInterruptionDisposition = PublicExportInterruptionDisposition.FAILED
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
    check(metadata.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id) {
        "Public export settlement requires a current-runtime owner"
    }
    ownerLease.registerPublicExportSettlement(
        operationId = operationId,
        failureMessage = failureMessage,
        finalOutputFormat = finalOutputFormat,
        disposition = disposition
    )
    val invalid = MediaStoreExportJournal.invalidFiles(jobDir)
    val ownerJournals = MediaStoreExportJournal.list(jobDir)
        .filter { it.ownerOperationId == operationId }
    val activeStartedAt = metadata.optLong(ACTIVE_OPERATION_STARTED_AT, 0L)
    val currentInvalid = invalid.filter { activeStartedAt <= 0L || it.lastModified() >= activeStartedAt }
    val evidence = inspectOwnedPublicExportEvidence(jobDir, ownerLease)
        ?: return true
    // A malformed file created before this operation is historical forensic
    // debt.  It must not turn the real zero-journal pre-commit cut into an
    // ambiguous export.  Only malformed evidence correlated to this owner's
    // lifetime remains fail-closed.
    check(currentInvalid.isEmpty()) {
        "Invalid export evidence may belong to the current public export operation"
    }

    // Durable terminal metadata must be written before journal acknowledgement.
    // The active marker remains until both writes succeed, so the exact lease can
    // be retained if either persistence boundary fails.
    KeplerJobMetadata.update(jobDir) { job ->
        check(job.optString(ACTIVE_OPERATION_ID) == evidence.operationId &&
            job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id &&
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
    // A journal may acknowledge only after the matching terminal metadata write.
    ownerJournals.forEach { it.markTerminalPersisted(jobDir, evidence.operationId) }
    KeplerJobMetadata.update(jobDir) { job ->
        check(job.optString(ACTIVE_OPERATION_ID) == evidence.operationId &&
            job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id &&
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
    val previous = export.verification as? GalleryExportVerification.Verified
    return verifyGalleryExportResult(
        context = context,
        uriString = export.uriString.orEmpty(),
        expectation = GalleryExportExpectation(
            format = export.formatUsed,
            width = previous?.width,
            height = previous?.height
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
    val manifest = runCatching { loadRawSidecarManifest(jobDir) }.getOrElse {
        return RawSidecarExportResult.failed("Invalid RAW sidecar manifest: ${it.message}")
    }
    if (manifest.frames.none { it.requested }) {
        return RawSidecarExportResult.failed("No DNG sidecars were requested in job.json")
    }

    val exported = mutableListOf<String>()
    val publicFailures = mutableListOf<String>()
    val publicByFrame = linkedMapOf<Int, String>()
    val publicFailureByFrame = linkedMapOf<Int, String>()
    try {
        manifest.expected.forEach { frame ->
            val file = frame.localFile ?: return@forEach
            cancellation.throwIfCancelled()
            val exportName = "${displayNameBase}_${frame.frameIndex.toString().padStart(2, '0')}.dng"
            val sourceDigest = runCatching { NoFollowFileSystem.digestVerified(file) }.getOrElse {
                val failure = "${file.name}: source DNG could not be hashed: ${it.message}"
                publicFailures += failure
                publicFailureByFrame[frame.frameIndex] = failure
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
                result.journal?.let { abandonMediaStoreAttempt(context, jobDir, it, result.uri) }
                val failure = "${file.name}: $verificationError"
                publicFailures += failure
                publicFailureByFrame[frame.frameIndex] = failure
            } else {
                result.journal?.transition(jobDir, MediaStoreExportState.VERIFIED)
                exported += result.uri.toString()
                publicByFrame[frame.frameIndex] = result.uri.toString()
            }
        }
    } catch (ce: CancellationException) {
        if (exported.isNotEmpty()) {
            return rawSidecarOutcome(manifest, exported, publicFailures + "Cancellation after partial public commit", true, publicByFrame, publicFailureByFrame)
        }
        return RawSidecarExportResult.cancelled()
    }

    return rawSidecarOutcome(manifest, exported, publicFailures, false, publicByFrame, publicFailureByFrame)
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
    } catch (error: Exception) {
        "Committed public DNG verification failed: ${error.javaClass.simpleName}: ${error.message}"
    }
}

private fun rawSidecarOutcome(
    manifest: RawSidecarManifest,
    exported: List<String>,
    publicFailures: List<String>,
    cancelled: Boolean = false,
    publicByFrame: Map<Int, String> = emptyMap(),
    publicFailureByFrame: Map<Int, String> = emptyMap()
): RawSidecarExportResult {
    val requestedCount = manifest.frames.count { it.requested }
    val locallySavedCount = manifest.frames.count { it.localFile != null }
    val localFailedCount = manifest.frames.count { it.localFailure != null }
    val complete = locallySavedCount == requestedCount && exported.size == locallySavedCount && publicFailures.isEmpty() && localFailedCount == 0
    val kind = when {
        complete -> RawSidecarOutcomeKind.COMPLETE
        exported.isNotEmpty() -> RawSidecarOutcomeKind.PARTIAL
        else -> RawSidecarOutcomeKind.FAILED
    }
    val frameResults = manifest.frames.map { frame ->
        val publicUri = publicByFrame[frame.frameIndex]
        val publicFailure = publicFailureByFrame[frame.frameIndex]
        RawSidecarFrameResult(
            frameIndex = frame.frameIndex,
            requested = frame.requested,
            localFilename = frame.localFilename,
            localStatus = frame.localStatus,
            localFailure = frame.localFailure,
            publicStatus = when {
                publicUri != null -> "PUBLIC_EXPORTED"
                publicFailure != null -> "PUBLIC_EXPORT_FAILED"
                else -> "NOT_ATTEMPTED"
            },
            publicUri = publicUri,
            publicFailure = publicFailure
        )
    }
    return RawSidecarExportResult(
        success = kind == RawSidecarOutcomeKind.COMPLETE || kind == RawSidecarOutcomeKind.PARTIAL,
        exportedFiles = exported,
        errorMessage = (manifest.localFailures + publicFailures).takeIf { it.isNotEmpty() }?.joinToString("; "),
        kind = kind,
        cancellationRequested = cancelled,
        expectedCount = requestedCount,
        locallySavedCount = locallySavedCount,
        publicExportedCount = exported.size,
        missingFilenames = frameResults.filter { it.requested && it.publicStatus != "PUBLIC_EXPORTED" }
            .map { it.localFilename ?: "frame_${it.frameIndex.toString().padStart(2, '0')}.dng" },
        localFailures = manifest.localFailures,
        publicFailures = publicFailures,
        requestedCount = requestedCount,
        localFailedCount = localFailedCount,
        publicFailedCount = publicFailures.size,
        frameResults = frameResults
    )
}

fun queryMediaSize(context: Context, uri: Uri): Long {
    val mediaSize = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)
    if (mediaSize > 0L) return mediaSize
    return runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    }.getOrDefault(0L).coerceAtLeast(0L)
        .takeIf { it > 0L }
        ?: runCatching {
            if (uri.scheme == "file") {
                uri.path?.let { java.io.File(it).length() } ?: 0L
            } else 0L
        }.getOrDefault(0L)
}

fun updateExportMetadata(
    jobDir: File,
    export: GalleryExportResult?,
    verified: Boolean,
    finalOutputFormat: FinalOutputFormat,
    rawSidecarResult: RawSidecarExportResult? = null,
    rawSidecarIgnored: Boolean = false,
    postExportCancellationRequested: Boolean = false,
    postExportWorkSkipped: Boolean = false
) {
    lateinit var pipelineStatusForLog: String
    lateinit var finalOutputSourceForLog: String
    lateinit var nativePostprocessRgbaFileForLog: String
    lateinit var rawRenderDebugFileForLog: String
    KeplerJobMetadata.update(jobDir) { job ->
        if (verified) {
            job.optString(ACTIVE_OPERATION_ID).takeIf { it.isNotBlank() }?.let { job.put(TERMINAL_OPERATION_ID, it) }
        }
        job.put("finalOutputFormatSetting", finalOutputFormat.name)
            .put("currentPipelineStage", if (verified) "COMPLETE" else "PROCESSING")
            .put("exportStatus", when {
                export == null -> "FAILED"
                verified -> "EXPORTED"
                else -> "EXPORT_UNVERIFIED"
            })
            .put("exportVerified", verified)
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
            .put("galleryExportCommitted", export?.success == true && !export?.uriString.isNullOrBlank())
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
    markMediaStoreExportJournalsTerminalPersisted(jobDir)
}

fun updateExportFailure(
    jobDir: File,
    error: String,
    finalOutputFormat: FinalOutputFormat,
    rawSidecarIgnored: Boolean = false,
    export: GalleryExportResult? = null
) {
    KeplerJobMetadata.update(jobDir) { job ->
        job.optString(ACTIVE_OPERATION_ID).takeIf { it.isNotBlank() }?.let { job.put(TERMINAL_OPERATION_ID, it) }
        job.put("finalOutputFormatSetting", finalOutputFormat.name)
            .put("processStatus", "EXPORT_FAILED_KEEPING_CACHE")
            .put("currentPipelineStage", "FAILED")
            .put("exportStatus", "FAILED")
            .put("exportVerified", false)
            .put("galleryExportCommitted", export?.success == true && !export?.uriString.isNullOrBlank())
            .put("exportUri", export?.uriString ?: JSONObject.NULL)
            .put("exportError", error)
            .put("rawSidecarRequested", finalOutputFormat.shouldExportRawSidecar)
            .put("rawSidecarExportStatus", if (rawSidecarIgnored) "UNAVAILABLE" else "SKIPPED")
            .put("rawSidecarError", if (rawSidecarIgnored) "RAW sidecar unavailable for YUV pipeline." else JSONObject.NULL)
            .put("cleanupStatus", "SKIPPED")
            .put("exportedAt", System.currentTimeMillis())
    }
    markMediaStoreExportJournalsTerminalPersisted(jobDir)
}

internal fun markMediaStoreExportJournalsTerminalPersisted(jobDir: File) {
    val metadata = runCatching { KeplerJobMetadata.read(jobDir) }.getOrNull()
    val ownerOperationId = metadata?.optString(TERMINAL_OPERATION_ID)?.takeIf { it.isNotBlank() }
        ?: return
    MediaStoreExportJournal.list(jobDir).forEach { journal ->
        if (journal.ownerOperationId == ownerOperationId && !journal.terminalMetadataPersisted) {
            journal.markTerminalPersisted(jobDir, ownerOperationId)
        }
    }
    val stage = runCatching { KeplerJobMetadata.read(jobDir).optString("currentPipelineStage") }.getOrNull()
    if (stage != null && stage != "PROCESSING") {
        KeplerJobMetadata.clearActiveOperationKind(jobDir, KeplerActiveOperationKind.PUBLIC_EXPORT)
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
    outcome: RawFusionPublicExportOutcome
) {
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
                is RawFusionPublicExportOutcome.UncommittedFailure -> "FAILED"
            })
            .put("exportVerified", outcome.verified)
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
            .put("galleryExportCommitted", outcome.committed)
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
        val isCommittedOutcome = outcome is RawFusionPublicExportOutcome.CommittedPendingVerification ||
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
                job.put("currentPipelineStage", "COMPLETE")
                    .put("processStatus", "NIGHT_FUSION_COMPLETE")
                    .put("userCanMoveDevice", true)
                    .put("exportError", JSONObject.NULL)
            }
            is RawFusionPublicExportOutcome.VerifiedPostWorkInterrupted -> {
                job.put("currentPipelineStage", "PARTIAL")
                    .put("processStatus", "EXPORT_VERIFIED_POST_WORK_INTERRUPTED")
                    .put("userCanMoveDevice", true)
                    .put("exportError", outcome.currentError ?: JSONObject.NULL)
            }
            is RawFusionPublicExportOutcome.UncommittedFailure -> {
                job.put("currentPipelineStage", "FAILED")
                    .put("processStatus", "EXPORT_FAILED_KEEPING_CACHE")
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
    markMediaStoreExportJournalsTerminalPersisted(jobDir)
}

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
        jobDir?.let { owner ->
            inserted.journal?.let { abandonMediaStoreAttempt(context, owner, it, committedUri) }
        }
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
            verification = verification
        )
    }

    jobDir?.let { owner ->
        inserted.journal?.transition(owner, MediaStoreExportState.VERIFIED)
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
        verification = verification
    )
}

private fun abandonMediaStoreAttempt(
    context: Context,
    jobDir: File,
    journal: MediaStoreExportJournal,
    uri: Uri
): MediaStoreExportJournal {
    val abandoned = journal.transition(jobDir, MediaStoreExportState.CLEANUP_REQUIRED, uri.toString())
    val deleted = runCatching { context.contentResolver.delete(uri, null, null) == 1 }.getOrDefault(false)
    return if (deleted) abandoned.transition(jobDir, MediaStoreExportState.CLEANED) else abandoned
}

private data class InsertedPublicFile(
    val uri: Uri,
    val size: Long,
    val journal: MediaStoreExportJournal?
)

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
        val updateCount = resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        if (updateCount != 1) {
            journal?.let { journal = abandonMediaStoreAttempt(context, jobDir!!, it, uri!!) }
            return null
        }
        journal = journal?.transition(jobDir!!, MediaStoreExportState.PUBLIC_COMMITTED)
        InsertedPublicFile(uri, runCatching { queryMediaSize(context, uri) }.getOrDefault(0L), journal)
    } catch (ce: CancellationException) {
        if (insertReturned && uri != null && journal != null) {
            runCatching { journal = abandonMediaStoreAttempt(context, jobDir!!, journal!!, uri!!) }
        }
        throw ce
    } catch (error: Throwable) {
        if (insertReturned && uri != null && journal != null) {
            runCatching { journal = abandonMediaStoreAttempt(context, jobDir!!, journal!!, uri!!) }
        }
        if (error is Error) throw error
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
    } catch (error: Throwable) {
        if (error is Error) throw error
        Log.w(
            "KeplerGalleryExporter",
            "HEIF encode failed pixels=${bitmap.width.toLong() * bitmap.height} timeoutMs=$timeoutMs: ${error.message}",
            error
        )
        false
    } finally {
        runCatching { writer?.close() }
            .onFailure { Log.w("KeplerGalleryExporter", "Failed to close HEIF writer.", it) }
        if (tempFile.exists() && !tempFile.delete()) {
            Log.w("KeplerGalleryExporter", "Failed to delete temporary HEIF file: ${tempFile.absolutePath}")
        }
    }
}

/** Resolution-aware upper bound for the asynchronous HeifWriter encoder. */
internal fun heifStopTimeoutMs(width: Int, height: Int): Long {
    val pixels = width.toLong().coerceAtLeast(1L) * height.toLong().coerceAtLeast(1L)
    return (2_000L + pixels / 400L).coerceIn(3_000L, 30_000L)
}
