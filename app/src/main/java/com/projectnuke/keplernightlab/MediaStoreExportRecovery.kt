package com.projectnuke.keplernightlab

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

internal enum class MediaStoreExportRecoveryClassification {
    CLEANED,
    PENDING_DELETED,
    PENDING_VERIFIED_AND_COMMITTED,
    PUBLIC_VERIFIED,
    PUBLIC_COMMITTED_UNVERIFIED,
    PUBLIC_COMMIT_MISSING,
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

internal fun reconstructRawSidecarJournalEvidence(
    jobDir: File,
    job: org.json.JSONObject,
    journals: List<MediaStoreExportJournal> = MediaStoreExportJournal.list(jobDir),
    verifiedAttemptIds: Set<String>? = null
): Int {
    val manifest = runCatching { loadRawSidecarManifest(jobDir) }.getOrNull()
        ?: return 0
    val candidates = journals.filter {
        it.role == MediaStoreExportRole.RAW_DNG_SIDECAR &&
            it.frameIndex != null &&
            it.uri != null &&
            it.state == MediaStoreExportState.VERIFIED &&
            (verifiedAttemptIds == null || it.exportAttemptId in verifiedAttemptIds)
    }
    var count = 0
    val frames = job.optJSONArray("frames") ?: return 0
    for (index in 0 until frames.length()) {
        val frame = frames.optJSONObject(index) ?: continue
        val frameIndex = frame.optInt("frameIndex", frame.optInt("index", index))
        val manifestFrame = manifest.frames.firstOrNull { it.frameIndex == frameIndex }
        val localFile = manifestFrame?.localFile
        val digest = localFile?.let { runCatching { NoFollowFileSystem.digestVerified(it) }.getOrNull() }
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
            frame.put("dngSidecarPublicStatus", "PUBLIC_EXPORTED")
                .put("publicDngUri", journal.uri)
                .remove("publicDngError")
            count += 1
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
        .filter { it.state == MediaStoreExportState.VERIFIED || it.state == MediaStoreExportState.PUBLIC_COMMITTED }
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
            "Public export was verified after the previous process ended."
        } else {
            "Public export was committed but requires verification."
        })
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
        val abandonedUri = journal.uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
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
        val deleted = runCatching { access.delete(abandonedUri) }.getOrDefault(false)
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
    val uri = runCatching { Uri.parse(uriString) }.getOrNull()
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
            val deleted = runCatching { access.delete(uri) }.getOrDefault(false)
            if (!deleted) {
                return MediaStoreExportRecoveryResult(
                    journal.exportAttemptId,
                    MediaStoreExportRecoveryClassification.DELETE_FAILED,
                    "Unverifiable pending MediaStore row could not be deleted."
                )
            }
            journal.transition(jobDir, MediaStoreExportState.CLEANUP_REQUIRED)
            return MediaStoreExportRecoveryResult(
                journal.exportAttemptId,
                MediaStoreExportRecoveryClassification.PENDING_DELETED,
                inspection.message ?: "Pending MediaStore content was not verifiable."
            )
        }
        val committed = runCatching { access.setPending(uri, false) }.getOrDefault(false)
        if (!committed) {
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
        if (journal.state == MediaStoreExportState.VERIFIED) {
            journal.transition(jobDir, MediaStoreExportState.PUBLIC_COMMITTED)
        }
        return MediaStoreExportRecoveryResult(
            journal.exportAttemptId,
            MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED,
            inspection.message ?: "Committed MediaStore content could not be verified."
        )
    }
    journal.transition(jobDir, MediaStoreExportState.VERIFIED)
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
