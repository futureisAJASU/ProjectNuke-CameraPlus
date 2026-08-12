package com.projectnuke.keplernightlab

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

internal enum class MediaStoreExportRecoveryClassification {
    NO_PUBLIC_OWNERSHIP,
    PENDING_DELETED,
    PENDING_VERIFIED_AND_COMMITTED,
    PUBLIC_VERIFIED,
    PUBLIC_COMMITTED_UNVERIFIED,
    PUBLIC_COMMIT_MISSING,
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
    val message: String? = null
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
    journals: List<MediaStoreExportJournal> = MediaStoreExportJournal.list(jobDir)
): Int {
    val verified = journals.filter {
        it.role == MediaStoreExportRole.RAW_DNG_SIDECAR &&
            it.frameIndex != null &&
            it.uri != null &&
            (it.state == MediaStoreExportState.VERIFIED || it.state == MediaStoreExportState.TERMINAL_PERSISTED)
    }.associateBy { it.frameIndex }
    var count = 0
    val frames = job.optJSONArray("frames") ?: return 0
    for (index in 0 until frames.length()) {
        val frame = frames.optJSONObject(index) ?: continue
        val frameIndex = frame.optInt("frameIndex", frame.optInt("index", index))
        val journal = verified[frameIndex] ?: continue
        frame.put("dngSidecarPublicStatus", "PUBLIC_EXPORTED")
            .put("publicDngUri", journal.uri)
            .remove("publicDngError")
        count += 1
    }
    if (count > 0) {
        job.put("rawSidecarPublicExportedCount", count)
            .put("rawSidecarRecoveryEvidence", "DURABLE_EXPORT_JOURNAL")
    }
    return count
}

private fun recoverMediaStoreExportJournal(
    jobDir: File,
    journal: MediaStoreExportJournal,
    access: MediaStoreExportRecoveryAccess
): MediaStoreExportRecoveryResult {
    val uriString = journal.uri
    if (uriString.isNullOrBlank()) {
        journal.transition(jobDir, MediaStoreExportState.CLEANUP_REQUIRED)
        return MediaStoreExportRecoveryResult(
            journal.exportAttemptId,
            MediaStoreExportRecoveryClassification.NO_PUBLIC_OWNERSHIP,
            "Export was prepared before MediaStore returned an exact URI."
        )
    }
    val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        ?: return MediaStoreExportRecoveryResult(
            journal.exportAttemptId,
            MediaStoreExportRecoveryClassification.AMBIGUOUS,
            "Export journal contains an invalid URI."
        )
    val inspection = access.inspect(uri, journal)
    if (!inspection.exists) {
        journal.transition(jobDir, MediaStoreExportState.CLEANUP_REQUIRED)
        return MediaStoreExportRecoveryResult(
            journal.exportAttemptId,
            MediaStoreExportRecoveryClassification.PUBLIC_COMMIT_MISSING,
            inspection.message ?: "The exact committed MediaStore URI is missing."
        )
    }
    if (inspection.pending) {
        if (!inspection.verified) {
            access.delete(uri)
            journal.transition(jobDir, MediaStoreExportState.CLEANUP_REQUIRED)
            return MediaStoreExportRecoveryResult(
                journal.exportAttemptId,
                MediaStoreExportRecoveryClassification.PENDING_DELETED,
                inspection.message ?: "Pending MediaStore content was not verifiable."
            )
        }
        if (!access.setPending(uri, false)) {
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
        journal.transition(jobDir, MediaStoreExportState.CLEANUP_REQUIRED)
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
            val pending = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.IS_PENDING),
                null,
                null,
                null
            )?.use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 } ?: false
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
            MediaStoreExportInspection(true, false, false, "MediaStore inspection failed: ${failure.message}")
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
