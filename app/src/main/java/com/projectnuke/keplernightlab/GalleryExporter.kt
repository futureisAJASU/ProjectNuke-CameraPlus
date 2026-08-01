package com.projectnuke.keplernightlab

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.heifwriter.HeifWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlin.math.abs

data class GalleryExportResult(
    val success: Boolean,
    val uriString: String?,
    val displayName: String?,
    val mimeType: String?,
    val fileSizeBytes: Long,
    val formatUsed: OutputFormat,
    val fallbackUsed: Boolean,
    val errorMessage: String?,
    val actualCommittedFormat: OutputFormat = formatUsed
)

data class RawSidecarExportResult(
    val success: Boolean,
    val exportedFiles: List<String>,
    val errorMessage: String?,
    val kind: RawSidecarOutcomeKind,
    val cancellationRequested: Boolean = false
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
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
): GalleryExportResult {
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
            cancellation = cancellation
        )
        if (!result.success) {
            cancellation.throwIfCancelled()
        }
        if (result.success) return result
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
        errorMessage = errors.joinToString("; ")
    )
}

fun verifyGalleryExport(
    context: Context,
    uriString: String,
    minSizeBytes: Long = 50_000L
): Boolean {
    if (uriString.isBlank()) return false
    val uri = Uri.parse(uriString)
    val maxRetries = 3
    for (attempt in 1..maxRetries) {
        val size = runCatching { queryMediaSize(context, uri) }.getOrDefault(0L)
        if (size >= minSizeBytes) {
            return runCatching {
                val detectedFormat = detectImageFormat(context, uri) ?: return@runCatching false
                val (w, h) = decodeBounds(context, uri)
                if (w <= 0 || h <= 0) return@runCatching false
                true
            }.getOrDefault(false)
        }
        if (attempt < maxRetries) Thread.sleep(100L * attempt)
    }
    return false
}

private class CandidateVerificationResult(
    val readable: Boolean,
    val detectedFormat: OutputFormat?,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val errorMessage: String?
)

private fun verifyExportCandidate(
    context: Context,
    uri: Uri,
    expectedFormat: OutputFormat,
    expectedWidth: Int? = null,
    expectedHeight: Int? = null
): CandidateVerificationResult {
    val detectedFormat = detectImageFormat(context, uri)
        ?: return CandidateVerificationResult(false, null, 0, 0, "Unreadable or unrecognized image format")
    if (detectedFormat != expectedFormat) {
        return CandidateVerificationResult(
            false, detectedFormat, 0, 0,
            "Format mismatch: expected ${expectedFormat.label}, detected ${detectedFormat.label}"
        )
    }
    val (w, h) = decodeBounds(context, uri)
    if (w <= 0 || h <= 0) {
        return CandidateVerificationResult(false, detectedFormat, w, h, "Invalid decoded bounds: ${w}x${h}")
    }
    if (expectedWidth != null && expectedHeight != null) {
        if (abs(w - expectedWidth) > 1 || abs(h - expectedHeight) > 1) {
            return CandidateVerificationResult(
                false, detectedFormat, w, h,
                "Dimension mismatch: expected ${expectedWidth}x${expectedHeight}, decoded ${w}x${h}"
            )
        }
    }
    return CandidateVerificationResult(true, detectedFormat, w, h, null)
}

private fun detectImageFormat(context: Context, uri: Uri): OutputFormat? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val header = ByteArray(12)
            var offset = 0
            while (offset < header.size) {
                val read = stream.read(header, offset, header.size - offset)
                if (read == -1) break
                offset += read
            }
            if (offset < 12) return@use null
            when {
                header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte() -> OutputFormat.HEIF
                header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> OutputFormat.JPEG
                header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() -> OutputFormat.PNG
                else -> null
            }
        }
    }.getOrDefault(null)
}

private fun decodeBounds(context: Context, uri: Uri): Pair<Int, Int> {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, options)
            options.outWidth to options.outHeight
        } ?: (0 to 0)
    }.getOrDefault(0 to 0)
}

fun exportRawSidecarsToPublicStorage(
    context: Context,
    jobDir: File,
    displayNameBase: String,
    relativeRawPath: String = "Pictures/Kepler/RAW",
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
): RawSidecarExportResult {
    val dngFiles = jobDir.listFiles()
        ?.filter { it.isFile && it.extension.equals("dng", ignoreCase = true) }
        ?.sortedBy { it.name }
        .orEmpty()
    if (dngFiles.isEmpty()) {
        return RawSidecarExportResult.failed("No DNG sidecars found")
    }

    val exported = mutableListOf<String>()
    try {
        dngFiles.forEachIndexed { index, file ->
            cancellation.throwIfCancelled()
            val exportName = "${displayNameBase}_${index.toString().padStart(2, '0')}.dng"
            val result = insertPublicFile(
                context = context,
                displayName = exportName,
                mimeType = "image/x-adobe-dng",
                relativePath = relativeRawPath,
                collectionUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                cancellation = cancellation
            ) { output ->
                FileInputStream(file).use { input -> input.copyTo(output) }
            } ?: run {
                cancellation.throwIfCancelled()
                insertPublicFile(
                    context = context,
                    displayName = exportName,
                    mimeType = "image/x-adobe-dng",
                    relativePath = "Download/Kepler/RAW",
                    collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cancellation = cancellation
                ) { output ->
                    FileInputStream(file).use { input -> input.copyTo(output) }
                }
            }

            if (result == null) {
                return if (exported.isNotEmpty()) {
                    RawSidecarExportResult.partial(
                        exportedFiles = exported,
                        errorMessage = "Failed exporting ${file.name}"
                    )
                } else {
                    RawSidecarExportResult.failed("Failed exporting ${file.name}")
                }
            }
            exported += result.first.toString()
            val verifiedSize = result.second
            if (verifiedSize <= 0L || verifiedSize < file.length().coerceAtLeast(1L)) {
                return RawSidecarExportResult.partial(
                    exportedFiles = exported,
                    errorMessage = "Verification failed for ${file.name}: committed size=$verifiedSize sourceSize=${file.length()}"
                )
            }
        }
    } catch (ce: CancellationException) {
        if (exported.isNotEmpty()) {
            return RawSidecarExportResult.partial(
                exportedFiles = exported,
                errorMessage = "RAW sidecar export cancelled after partial commit"
            ).let { it.copy(cancellationRequested = true) }
        }
        return RawSidecarExportResult.cancelled()
    }

    return RawSidecarExportResult.complete(exportedFiles = exported)
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
            .put("rawSidecarError", when {
                rawSidecarIgnored -> "RAW sidecar unavailable for YUV pipeline."
                else -> rawSidecarResult?.errorMessage ?: JSONObject.NULL
            })
            .put("exportedAt", System.currentTimeMillis())
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
}

fun updateExportFailure(
    jobDir: File,
    error: String,
    finalOutputFormat: FinalOutputFormat,
    rawSidecarIgnored: Boolean = false,
    export: GalleryExportResult? = null
) {
    KeplerJobMetadata.update(jobDir) { job ->
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
            .put("rawSidecarError", sidecarError)
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
        val exportUri = outcome.export?.uriString
        if (exportUri != null) {
            job.put("galleryPublicExportLinkage", exportUri)
        } else {
            job.remove("galleryPublicExportLinkage")
        }
    }
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
    cancellation: KeplerPipelineCancellation
): GalleryExportResult {
    val inserted = insertPublicFile(
        context = context,
        displayName = displayName,
        mimeType = format.mimeType,
        relativePath = relativeAlbumPath,
        collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        cancellation = cancellation
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

    val committedUri = inserted.first
    val verification = verifyExportCandidate(
        context = context,
        uri = committedUri,
        expectedFormat = format,
        expectedWidth = bitmap.width,
        expectedHeight = bitmap.height
    )

    if (!verification.readable) {
        runCatching { context.contentResolver.delete(committedUri, null, null) }
        return GalleryExportResult(
            success = false,
            uriString = committedUri.toString(),
            displayName = displayName,
            mimeType = format.mimeType,
            fileSizeBytes = inserted.second,
            formatUsed = format,
            fallbackUsed = fallbackUsed,
            errorMessage = "Verification failed: ${verification.errorMessage}"
        )
    }

    return GalleryExportResult(
        success = true,
        uriString = committedUri.toString(),
        displayName = displayName,
        mimeType = format.mimeType,
        fileSizeBytes = inserted.second,
        formatUsed = format,
        fallbackUsed = fallbackUsed,
        errorMessage = null,
        actualCommittedFormat = verification.detectedFormat ?: format
    )
}

private fun insertPublicFile(
    context: Context,
    displayName: String,
    mimeType: String,
    relativePath: String,
    collectionUri: Uri,
    cancellation: KeplerPipelineCancellation,
    writer: (OutputStream) -> Unit
): Pair<Uri, Long>? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000L)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    var uri: Uri? = null
    return try {
        cancellation.throwIfCancelled()
        uri = resolver.insert(collectionUri, values) ?: return null
        cancellation.throwIfCancelled()
        resolver.openOutputStream(uri)?.use(writer) ?: error("openOutputStream returned null")
        cancellation.throwIfCancelled()
        val updateCount = resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        if (updateCount != 1) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        uri to runCatching { queryMediaSize(context, uri) }.getOrDefault(0L)
    } catch (ce: CancellationException) {
        uri?.let { runCatching { resolver.delete(it, null, null) } }
        throw ce
    } catch (_: Exception) {
        uri?.let { runCatching { resolver.delete(it, null, null) } }
        null
    }
}

private fun writeHeifViaTempFile(
    context: Context,
    bitmap: Bitmap,
    quality: Int,
    output: OutputStream
): Boolean {
    val tempFile = File.createTempFile("kepler_export_", ".heic", context.cacheDir)
    var writer: HeifWriter? = null
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
        createdWriter.stop(5_000)
        FileInputStream(tempFile).use { input -> input.copyTo(output) }
        true
    } finally {
        runCatching { writer?.close() }
            .onFailure { Log.w("KeplerGalleryExporter", "Failed to close HEIF writer.", it) }
        if (tempFile.exists() && !tempFile.delete()) {
            Log.w("KeplerGalleryExporter", "Failed to delete temporary HEIF file: ${tempFile.absolutePath}")
        }
    }
}
