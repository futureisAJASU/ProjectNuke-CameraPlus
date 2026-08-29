package com.projectnuke.keplernightlab

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CancellationException

internal enum class MediaStoreExportRecoveryClassification {
    CLEANED,
    PENDING_DELETED,
    PENDING_VERIFIED_AND_COMMITTED,
    PUBLIC_VERIFIED,
    PUBLIC_COMMITTED_UNVERIFIED,
    PUBLIC_COMMIT_MISSING,

    /**
     * The exact public row is absent, but durable evidence proves this exact
     * export previously reached a terminal-stable verified cut (state VERIFIED
     * with a persisted terminal metadata acknowledgement). This is historical
     * external removal by the user/provider — NOT recovery corruption — and
     * must never fail-closed block local mutations or reprocess. The journal
     * keeps its durable VERIFIED evidence; history is never downgraded.
     */
    PUBLIC_RESULT_REMOVED,
    INSERT_RESULT_UNKNOWN,
    DELETE_FAILED,
    AMBIGUOUS
}

internal data class MediaStoreExportRecoveryResult(
    val attemptId: String,
    val classification: MediaStoreExportRecoveryClassification,
    val message: String? = null
)

internal interface MediaStoreExportRecoveryAccess {
    fun inspect(uri: Uri, journal: MediaStoreExportJournal): MediaStoreExportInspection
    fun setPending(uri: Uri, pending: Boolean): Boolean
    fun delete(uri: Uri): Boolean
}

internal data class MediaStoreExportInspection(
    val exists: Boolean,
    val pending: Boolean,
    val verified: Boolean,
    val message: String? = null,
    val inspectionFailed: Boolean = false
)

internal fun recoverMediaStoreExportJournals(
    jobDir: File,
    access: MediaStoreExportRecoveryAccess
): List<MediaStoreExportRecoveryResult> = MediaStoreExportJournal.list(jobDir).map { journal ->
    recoverMediaStoreExportJournal(jobDir, journal, access)
}

/**
 * Cheap structural gate: is RAW DNG sidecar recovery semantically relevant to this job?
 *
 * Returns false only when the job provably has no RAW sidecar contract, so the surrounding
 * reconstruction pass (and its enclosing durable job-metadata rewrite) can be skipped. This is
 * intentionally independent of journal existence: a RAW job whose requested DNG sidecar has no
 * surviving RAW_DNG_SIDECAR journal must still run reconstruction to record truthful
 * PUBLIC_NOT_RECOVERED evidence. Conversely an ordinary YUV job whose frames carry no DNG-sidecar
 * field is structurally unrelated and must not be forced through reconstruction.
 */
internal fun rawSidecarRecoveryApplies(jobDir: File, job: org.json.JSONObject): Boolean {
    if (job.optString("jobType").uppercase() == "RAW_NIGHT_FUSION" || jobDir.name.startsWith("KPL_RAW_FUSION_")) {
        return true
    }
    val frames = job.optJSONArray("frames") ?: return false
    for (index in 0 until frames.length()) {
        val frame = frames.optJSONObject(index) ?: continue
        val dngFile = frame.optString("dngFile").takeUnless { it.isBlank() || it == "null" }
        if (dngFile != null) return true
        val status = frame.optString("dngSidecarStatus")
        if (status.isNotBlank() && status != "NOT_REQUESTED") return true
    }
    return false
}

internal fun reconstructRawSidecarJournalEvidence(
    jobDir: File,
    job: org.json.JSONObject,
    journals: List<MediaStoreExportJournal> = MediaStoreExportJournal.list(jobDir),
    verifiedAttemptIds: Set<String>? = null,
    classifications: Map<String, MediaStoreExportRecoveryClassification>? = null
): Int {
    val manifest = try {
        loadRawSidecarManifest(jobDir)
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        null
    }
        ?: return 0
    val classificationDriven = classifications != null
    val candidates = journals.filter {
        it.role == MediaStoreExportRole.RAW_DNG_SIDECAR &&
            it.frameIndex != null &&
            it.uri != null &&
            (classificationDriven && it.exportAttemptId in classifications!!.keys ||
                !classificationDriven && it.state == MediaStoreExportState.VERIFIED &&
                (verifiedAttemptIds == null || it.exportAttemptId in verifiedAttemptIds))
    }
    var count = 0
    val frames = job.optJSONArray("frames") ?: return 0
    for (index in 0 until frames.length()) {
        val frame = frames.optJSONObject(index) ?: continue
        val frameIndex = frame.optInt("frameIndex", frame.optInt("index", index))
        val manifestFrame = manifest.frames.firstOrNull { it.frameIndex == frameIndex }
        val localFile = manifestFrame?.localFile
        val digest = localFile?.let {
            try {
                NoFollowFileSystem.digestVerified(it)
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                null
            }
        }
        val journal = candidates.asSequence()
            .filter { candidate ->
                candidate.frameIndex == frameIndex &&
                    digest != null &&
                    candidate.expectedSizeBytes == digest.size &&
                    candidate.expectedSha256.equals(digest.sha256, ignoreCase = true)
            }
            .sortedWith(compareByDescending<MediaStoreExportJournal> { it.updatedAt }
                .thenByDescending { it.exportAttemptId })
            .firstOrNull()
        if (journal != null) {
            val classification = if (classificationDriven) {
                classifications?.get(journal.exportAttemptId)
            } else {
                null
            }
            when (classification) {
                MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED,
                MediaStoreExportRecoveryClassification.PENDING_VERIFIED_AND_COMMITTED -> {
                    frame.put("dngSidecarPublicStatus", "PUBLIC_EXPORTED")
                        .put("publicDngUri", journal.uri)
                        .remove("publicDngError")
                    count += 1
                }
                MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED -> {
                    frame.put("dngSidecarPublicStatus", "PUBLIC_COMMITTED_UNVERIFIED")
                        .put("publicDngUri", journal.uri)
                        .remove("publicDngError")
                }
                MediaStoreExportRecoveryClassification.PUBLIC_RESULT_REMOVED -> {
                    // Historical sidecar evidence whose exact public row was externally
                    // removed afterwards: report truthful absence, never a live export.
                    frame.put("dngSidecarPublicStatus", "PUBLIC_NOT_RECOVERED")
                        .remove("publicDngUri")
                }
                MediaStoreExportRecoveryClassification.AMBIGUOUS,
                MediaStoreExportRecoveryClassification.INSERT_RESULT_UNKNOWN,
                MediaStoreExportRecoveryClassification.DELETE_FAILED -> {
                    // Unresolved evidence is preserved: the exact URI stays and the frame is
                    // reported as commit-unknown, never as missing.
                    frame.put("dngSidecarPublicStatus", "PUBLIC_COMMIT_UNKNOWN")
                        .put("publicDngUri", journal.uri)
                        .remove("publicDngError")
                }
                null -> {
                    // Legacy verified-only reconstruction.
                    frame.put("dngSidecarPublicStatus", "PUBLIC_EXPORTED")
                        .put("publicDngUri", journal.uri)
                        .remove("publicDngError")
                    count += 1
                }
                else -> {
                    frame.put("dngSidecarPublicStatus", "PUBLIC_NOT_RECOVERED")
                        .remove("publicDngUri")
                }
            }
        } else if (manifestFrame?.requested == true) {
            frame.put("dngSidecarPublicStatus", "PUBLIC_NOT_RECOVERED")
                .remove("publicDngUri")
        }
    }
    val requestedCount = manifest.expected.size
    job.put("rawSidecarPublicExportedCount", count)
        .put("rawSidecarPublicFailedCount", (requestedCount - count).coerceAtLeast(0))
        .put("rawSidecarRecoveryEvidence", "DURABLE_EXPORT_JOURNAL_CURRENT_SOURCE")
    return count
}

internal fun reconstructMainExportEvidence(
    jobDir: File,
    job: org.json.JSONObject,
    activeOperationId: String,
    results: List<MediaStoreExportRecoveryResult>
): Boolean {
    if (activeOperationId.isBlank()) return false
    val resultByAttempt = results.associateBy { it.attemptId }
    val journal = MediaStoreExportJournal.list(jobDir)
        .asSequence()
        .filter { it.role == MediaStoreExportRole.MAIN_IMAGE }
        .filter { it.ownerOperationId == activeOperationId }
        .filter { it.uri != null }
        .mapNotNull { candidate ->
            val result = resultByAttempt[candidate.exportAttemptId]
            val verified = result?.classification == MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED ||
                result?.classification == MediaStoreExportRecoveryClassification.PENDING_VERIFIED_AND_COMMITTED
            val committed = verified || result?.classification == MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED
            if (!committed) null else candidate to verified
        }
        .sortedWith(compareByDescending<Pair<MediaStoreExportJournal, Boolean>> { it.first.updatedAt }
            .thenByDescending { it.first.exportAttemptId })
        .firstOrNull()
        ?: return false
    val (selected, verified) = journal
    job.put("galleryExportCommitted", true)
        .put("exportVerified", verified)
        .put("exportUri", selected.uri)
        .put("galleryPublicExportLinkage", selected.uri)
        .put("exportDisplayName", selected.displayName)
        .put("exportMimeType", selected.mimeType)
        .put("recoveryState", if (verified) "PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL" else "PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION")
        .put("recoveryMessage", if (verified) {
            "이전 실행이 종료된 후 공개 내보내기 결과를 확인했습니다."
        } else {
            "공개 내보내기 결과가 저장되었지만 확인이 필요합니다."
        })
    return true
}

private fun isLegacyTerminalStableVerifiedMainExportForRecovery(job: org.json.JSONObject, journal: MediaStoreExportJournal): Boolean {
    if (journal.role != MediaStoreExportRole.MAIN_IMAGE) return false
    if (journal.state != MediaStoreExportState.VERIFIED) return false
    if (journal.uri.isNullOrBlank()) return false
    val activeOperationId = job.optString(ACTIVE_OPERATION_ID)
    if (activeOperationId.isNotBlank()) return false
    val currentPipelineStage = job.optString("currentPipelineStage").uppercase()
    if (currentPipelineStage !in setOf("COMPLETE", "PARTIAL", "FAILED", "CANCELLED")) return false
    if (!job.optBoolean("galleryExportCommitted", false)) return false
    if (!job.optBoolean("exportVerified", false)) return false
    val currentUri = job.optString("galleryPublicExportLinkage").takeIf { it.isNotBlank() && it != "null" }
        ?: job.optString("exportUri")
    if (currentUri.isNullOrBlank() || currentUri != journal.uri) return false
    val terminalOperationId = job.optString(TERMINAL_OPERATION_ID).takeIf { it.isNotBlank() }
    if (terminalOperationId != null) {
        if (journal.ownerOperationId != terminalOperationId) return false
    }
    if (job.optString(PROCESSING_HANDOFF_OPERATION_ID).isNotBlank()) return false
    return true
}

private fun recoverMediaStoreExportJournal(
    jobDir: File,
    journal: MediaStoreExportJournal,
    access: MediaStoreExportRecoveryAccess
): MediaStoreExportRecoveryResult {
    if (journal.state == MediaStoreExportState.CLEANED) {
        return MediaStoreExportRecoveryResult(
            journal.exportAttemptId,
            MediaStoreExportRecoveryClassification.CLEANED,
            "Abandoned MediaStore cleanup was already settled."
        )
    }
    if (journal.state == MediaStoreExportState.INSERT_FAILED_NO_ROW) {
        journal.transition(jobDir, MediaStoreExportState.CLEANED)
        return MediaStoreExportRecoveryResult(
            journal.exportAttemptId,
            MediaStoreExportRecoveryClassification.CLEANED,
            "MediaStore insert was known to return no row."
        )
    }
    if (journal.state == MediaStoreExportState.CLEANUP_REQUIRED) {
        val abandonedUri = journal.uri?.let {
            try {
                Uri.parse(it)
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                null
            }
        }
            ?: run {
                journal.transition(jobDir, MediaStoreExportState.CLEANED)
                return MediaStoreExportRecoveryResult(
                    journal.exportAttemptId,
                    MediaStoreExportRecoveryClassification.CLEANED,
                    "Abandoned MediaStore attempt had no owned URI."
                )
            }
        val inspection = access.inspect(abandonedUri, journal)
        if (inspection.inspectionFailed) {
            return MediaStoreExportRecoveryResult(
                journal.exportAttemptId,
                MediaStoreExportRecoveryClassification.DELETE_FAILED,
                inspection.message ?: "Abandoned MediaStore row could not be inspected."
            )
        }
        if (!inspection.exists) {
            journal.transition(jobDir, MediaStoreExportState.CLEANED)
            return MediaStoreExportRecoveryResult(
                journal.exportAttemptId,
                MediaStoreExportRecoveryClassification.CLEANED,
                "Abandoned MediaStore row was already absent."
            )
        }
        val deleted = try {
            access.delete(abandonedUri)
        } catch (failure: Error) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!deleted) {
            return MediaStoreExportRecoveryResult(
                journal.exportAttemptId,
                MediaStoreExportRecoveryClassification.DELETE_FAILED,
                "Abandoned MediaStore row could not be deleted."
            )
        }
        journal.transition(jobDir, MediaStoreExportState.CLEANED)
        return MediaStoreExportRecoveryResult(
            journal.exportAttemptId,
            MediaStoreExportRecoveryClassification.CLEANED,
            "Abandoned MediaStore row was deleted."
        )
    }
    val uriString = journal.uri
    if (uriString.isNullOrBlank()) {
        return MediaStoreExportRecoveryResult(
            journal.exportAttemptId,
            MediaStoreExportRecoveryClassification.INSERT_RESULT_UNKNOWN,
            "MediaStore insert result is unknown because no exact URI was durably recorded."
        )
    }
    val uri = try {
        Uri.parse(uriString)
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        null
    }
        ?: return MediaStoreExportRecoveryResult(
            journal.exportAttemptId,
            MediaStoreExportRecoveryClassification.AMBIGUOUS,
            "Export journal contains an invalid URI."
        )
    val inspection = access.inspect(uri, journal)
    if (inspection.inspectionFailed) {
        return MediaStoreExportRecoveryResult(
            journal.exportAttemptId,
            MediaStoreExportRecoveryClassification.AMBIGUOUS,
            inspection.message ?: "MediaStore row inspection failed."
        )
    }
    if (!inspection.exists) {
        // Category B (external removal): a terminal-stable VERIFIED cut proves this exact row
        // existed and was correct before. Its absence today is user/provider deletion, not
        // commit uncertainty. Preserve the durable VERIFIED evidence — never downgrade it to
        // PUBLIC_COMMITTED merely because the row later disappeared.
        if (journal.state == MediaStoreExportState.VERIFIED && journal.terminalMetadataPersisted) {
            return MediaStoreExportRecoveryResult(
                journal.exportAttemptId,
                MediaStoreExportRecoveryClassification.PUBLIC_RESULT_REMOVED,
                inspection.message ?: "A previously verified public result was removed outside Kepler."
            )
        }
        // Legacy terminal-stable verification: older jobs may lack terminalMetadataPersisted
        // but have durable terminal evidence in job metadata.
        if (journal.state == MediaStoreExportState.VERIFIED && journal.role == MediaStoreExportRole.MAIN_IMAGE) {
            try {
                val job = KeplerJobMetadata.read(jobDir)
                val legacy = isLegacyTerminalStableVerifiedMainExportForRecovery(job, journal)
                if (legacy) {
                    return MediaStoreExportRecoveryResult(
                        journal.exportAttemptId,
                        MediaStoreExportRecoveryClassification.PUBLIC_RESULT_REMOVED,
                        inspection.message ?: "A previously verified public result was removed outside Kepler (legacy evidence)."
                    )
                }
            } catch (_: Exception) {
                // If metadata read fails, fall back to fail-closed policy.
            }
        }
        // Category A (commit uncertainty): an unacknowledged in-flight export keeps the
        // existing fail-closed policy.
        if (journal.state == MediaStoreExportState.VERIFIED) {
            journal.transition(jobDir, MediaStoreExportState.PUBLIC_COMMITTED)
        }
        return MediaStoreExportRecoveryResult(
            journal.exportAttemptId,
            MediaStoreExportRecoveryClassification.PUBLIC_COMMIT_MISSING,
            inspection.message ?: "The exact committed MediaStore URI is missing."
        )
    }
    if (inspection.pending) {
        if (!inspection.verified) {
            val deleted = try {
                access.delete(uri)
            } catch (failure: Error) {
                throw failure
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!deleted) {
                return MediaStoreExportRecoveryResult(
                    journal.exportAttemptId,
                    MediaStoreExportRecoveryClassification.DELETE_FAILED,
                    "Unverifiable pending MediaStore row could not be deleted."
                )
            }
            journal.transition(jobDir, MediaStoreExportState.CLEANED)
            return MediaStoreExportRecoveryResult(
                journal.exportAttemptId,
                MediaStoreExportRecoveryClassification.PENDING_DELETED,
                inspection.message ?: "Pending MediaStore content was not verifiable."
            )
        }
        fun reconcileActualCommitAfterFailure(): MediaStoreExportRecoveryResult? {
            val afterFailure = try {
                access.inspect(uri, journal)
            } catch (failure: Error) {
                throw failure
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
            }
            if (afterFailure.inspectionFailed || !afterFailure.exists || afterFailure.pending) {
                return null
            }
            if (!afterFailure.verified) {
                journal.transition(jobDir, MediaStoreExportState.PUBLIC_COMMITTED)
                return MediaStoreExportRecoveryResult(
                    journal.exportAttemptId,
                    MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED,
                    afterFailure.message ?: "MediaStore commit succeeded but verification remains unresolved."
                )
            }
            journal.transition(jobDir, MediaStoreExportState.PUBLIC_COMMITTED)
            journal.transition(jobDir, MediaStoreExportState.VERIFIED)
            return MediaStoreExportRecoveryResult(
                journal.exportAttemptId,
                MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED,
                afterFailure.message ?: "MediaStore commit and verification were observed after the commit call failed."
            )
        }

        val committed = try {
            access.setPending(uri, false)
        } catch (failure: Error) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!committed) {
            reconcileActualCommitAfterFailure()?.let { return it }
            return MediaStoreExportRecoveryResult(
                journal.exportAttemptId,
                MediaStoreExportRecoveryClassification.AMBIGUOUS,
                "Verified pending MediaStore content could not be committed."
            )
        }
        journal.transition(jobDir, MediaStoreExportState.PUBLIC_COMMITTED)
        journal.transition(jobDir, MediaStoreExportState.VERIFIED)
        return MediaStoreExportRecoveryResult(
            journal.exportAttemptId,
            MediaStoreExportRecoveryClassification.PENDING_VERIFIED_AND_COMMITTED
        )
    }
    if (!inspection.verified) {
        if (journal.state != MediaStoreExportState.PUBLIC_COMMITTED) {
            journal.transition(jobDir, MediaStoreExportState.PUBLIC_COMMITTED)
        }
        return MediaStoreExportRecoveryResult(
            journal.exportAttemptId,
            MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED,
            inspection.message ?: "Committed MediaStore content could not be verified."
        )
    }
    // Same-state idempotency: a journal already durably VERIFIED that inspection freshly
    // proves verified again must not be rewritten. A redundant transition would mutate
    // `updatedAt` and therefore reorder historical export evidence for no semantic gain.
    if (journal.state != MediaStoreExportState.VERIFIED) {
        journal.transition(jobDir, MediaStoreExportState.VERIFIED)
    }
    return MediaStoreExportRecoveryResult(
        journal.exportAttemptId,
        MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED
    )
}

internal class ContextMediaStoreExportRecoveryAccess(
    private val context: Context
) : MediaStoreExportRecoveryAccess {
    override fun inspect(uri: Uri, journal: MediaStoreExportJournal): MediaStoreExportInspection {
        return try {
            var pending = false
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.IS_PENDING),
                null,
                null,
                null
            )
            if (cursor == null) return MediaStoreExportInspection(
                exists = false,
                pending = false,
                verified = false,
                message = "MediaStore row inspection returned no cursor.",
                inspectionFailed = true
            )
            val exists = cursor.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                pending = cursor.getInt(0) != 0
                true
            }
            if (!exists) return MediaStoreExportInspection(false, false, false, "The exact MediaStore row is missing.")
            val verified = when (journal.role) {
                MediaStoreExportRole.MAIN_IMAGE -> {
                    val format = when (journal.mimeType) {
                        "image/png" -> OutputFormat.PNG
                        "image/heif" -> OutputFormat.HEIF
                        else -> OutputFormat.JPEG
                    }
                    verifyGalleryExportResult(
                        context,
                        uri.toString(),
                        GalleryExportExpectation(format, journal.expectedWidth, journal.expectedHeight)
                    ) is GalleryExportVerification.Verified
                }
                MediaStoreExportRole.RAW_DNG_SIDECAR -> {
                    journal.expectedSha256 != null && verifyDngJournalContent(context, uri, journal)
                }
            }
            MediaStoreExportInspection(true, pending, verified)
        } catch (failure: Error) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            MediaStoreExportInspection(false, false, false, "MediaStore inspection failed: ${failure.message}", inspectionFailed = true)
        }
    }

    override fun setPending(uri: Uri, pending: Boolean): Boolean =
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0) },
            null,
            null
        ) == 1

    override fun delete(uri: Uri): Boolean = context.contentResolver.delete(uri, null, null) == 1
}

private fun verifyDngJournalContent(context: Context, uri: Uri, journal: MediaStoreExportJournal): Boolean {
    val expectedSize = journal.expectedSizeBytes ?: return false
    val expectedSha = journal.expectedSha256 ?: return false
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    var size = 0L
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            size += read
        }
    } ?: return false
    return size == expectedSize && digest.digest().joinToString("") { "%02x".format(it) } == expectedSha
}
