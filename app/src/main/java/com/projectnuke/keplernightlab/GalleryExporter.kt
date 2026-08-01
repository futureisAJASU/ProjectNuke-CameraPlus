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
    val publicFailures: List<String> = emptyList()
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
    val manifest = runCatching { loadRawSidecarManifest(jobDir) }.getOrElse {
        return RawSidecarExportResult.failed("Invalid RAW sidecar manifest: ${it.message}")
    }
    if (manifest.expected.isEmpty()) return RawSidecarExportResult.failed("No locally saved DNG sidecars in job.json")

    val exported = mutableListOf<String>()
    val publicFailures = mutableListOf<String>()
    try {
        manifest.expected.forEachIndexed { index, file ->
            cancellation.throwIfCancelled()
            val exportName = "${displayNameBase}_${index.toString().padStart(2, '0')}.dng"
            var sourceDigest: NoFollowFileSystem.StreamDigest? = null
            val result = insertPublicFile(
                context = context,
                displayName = exportName,
                mimeType = "image/x-adobe-dng",
                relativePath = relativeRawPath,
                collectionUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                cancellation = cancellation
            ) { output ->
                sourceDigest = NoFollowFileSystem.copyVerified(file, output)
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
                    sourceDigest = NoFollowFileSystem.copyVerified(file, output)
                }
            }

            if (result == null) {
                publicFailures += "${file.name}: MediaStore insert/write failed"
                return@forEachIndexed
            }
            val verificationError = verifyPublicDng(
                context = context,
                uri = result.first,
                expected = sourceDigest ?: return@forEachIndexed
            )
            if (verificationError != null) {
                runCatching { context.contentResolver.delete(result.first, null, null) }
                publicFailures += "${file.name}: $verificationError"
            } else {
                exported += result.first.toString()
            }
        }
    } catch (ce: CancellationException) {
        if (exported.isNotEmpty()) {
            return rawSidecarOutcome(manifest, exported, publicFailures + "Cancellation after partial public commit", true)
        }
        return RawSidecarExportResult.cancelled()
    }

    return rawSidecarOutcome(manifest, exported, publicFailures)
}

private data class RawSidecarManifest(
    val expected: List<File>,
    val localFailures: List<String>
)

private fun loadRawSidecarManifest(jobDir: File): RawSidecarManifest {
    val jobFile = NoFollowFileSystem.requireDirectChildFile(jobDir, "job.json")
    val job = JSONObject(NoFollowFileSystem.readTextVerified(jobFile))
    val frames = job.optJSONArray("frames") ?: error("job.json has no RAW frame manifest")
    val names = linkedSetOf<String>()
    val failures = mutableListOf<String>()
    for (index in 0 until frames.length()) {
        val frame = frames.optJSONObject(index) ?: error("Invalid frame manifest entry $index")
        val status = frame.optString("dngSidecarStatus")
        val name = frame.optString("dngFile").takeUnless { it.isBlank() || it == "null" }
        if (status == "LOCAL_SAVE_FAILED") failures += "frame $index: ${frame.optString("dngSidecarError")}"
        if (status == "LOCAL_SAVED") {
            require(name != null && name.lowercase().endsWith(".dng")) { "Invalid locally saved DNG reference at frame $index" }
            require(names.add(name)) { "Duplicate DNG reference: $name" }
        }
    }
    val expected = names.map { NoFollowFileSystem.requireDirectChildFile(jobDir, it) }
    val direct = NoFollowFileSystem.requireDirectDirectDngNames(jobDir)
    require(direct == names) { "Unexpected or missing DNG sidecar files" }
    expected.forEach { file -> require(isDngTiffHeader(NoFollowFileSystem.digestVerified(file).prefix)) { "Malformed local DNG: ${file.name}" } }
    return RawSidecarManifest(expected, failures)
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
    cancelled: Boolean = false
): RawSidecarExportResult {
    val complete = exported.size == manifest.expected.size && publicFailures.isEmpty() && manifest.localFailures.isEmpty()
    val kind = when {
        complete -> RawSidecarOutcomeKind.COMPLETE
        exported.isNotEmpty() -> RawSidecarOutcomeKind.PARTIAL
        else -> RawSidecarOutcomeKind.FAILED
    }
    return RawSidecarExportResult(
        success = kind != RawSidecarOutcomeKind.FAILED,
        exportedFiles = exported,
        errorMessage = (manifest.localFailures + publicFailures).takeIf { it.isNotEmpty() }?.joinToString("; "),
        kind = kind,
        cancellationRequested = cancelled,
        expectedCount = manifest.expected.size,
        locallySavedCount = manifest.expected.size,
        publicExportedCount = exported.size,
        missingFilenames = manifest.expected.map { it.name }.drop(exported.size),
        localFailures = manifest.localFailures,
        publicFailures = publicFailures
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
            .put("rawSidecarLocallySavedCount", rawSidecarResult?.locallySavedCount ?: 0)
            .put("rawSidecarPublicExportedCount", rawSidecarResult?.publicExportedCount ?: 0)
            .put("rawSidecarMissingFilenames", JSONArray(rawSidecarResult?.missingFilenames ?: emptyList<String>()))
            .put("rawSidecarLocalFailures", JSONArray(rawSidecarResult?.localFailures ?: emptyList<String>()))
            .put("rawSidecarPublicFailures", JSONArray(rawSidecarResult?.publicFailures ?: emptyList<String>()))
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
            .put("rawSidecarLocallySavedCount", sidecarResult?.locallySavedCount ?: 0)
            .put("rawSidecarPublicExportedCount", sidecarResult?.publicExportedCount ?: 0)
            .put("rawSidecarMissingFilenames", JSONArray(sidecarResult?.missingFilenames ?: emptyList<String>()))
            .put("rawSidecarLocalFailures", JSONArray(sidecarResult?.localFailures ?: emptyList<String>()))
            .put("rawSidecarPublicFailures", JSONArray(sidecarResult?.publicFailures ?: emptyList<String>()))
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
        runCatching { context.contentResolver.delete(committedUri, null, null) }
        return GalleryExportResult(
            success = false,
            uriString = committedUri.toString(),
            displayName = displayName,
            mimeType = format.mimeType,
            fileSizeBytes = inserted.second,
            formatUsed = format,
            fallbackUsed = fallbackUsed,
            errorMessage = "Verification failed: ${(verification as? GalleryExportVerification.RetryableFailure)?.reason ?: (verification as? GalleryExportVerification.PermanentFailure)?.reason}",
            attemptedFormats = listOf(format),
            candidateFailureReasons = listOf((verification as? GalleryExportVerification.RetryableFailure)?.reason ?: (verification as? GalleryExportVerification.PermanentFailure)?.reason.orEmpty()),
            verification = verification
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
        attemptedFormats = listOf(format),
        verification = verification
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
        val tempBytes = NoFollowFileSystem.readBytesVerified(tempFile)
        check(tempBytes.isNotEmpty()) { "HEIF writer produced an empty temporary file" }
        output.write(tempBytes)
        true
    } catch (error: Exception) {
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
