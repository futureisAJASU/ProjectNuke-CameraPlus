package com.projectnuke.keplernightlab

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Narrow deterministic seams for destructive Gallery API contract tests. */
internal var galleryDeleteFailureForTest: Throwable? = null
internal var galleryCleanupFileDeleteFailureForTest: Throwable? = null
internal var galleryCleanupDeleteFailurePathForTest: String? = null
internal var galleryCleanupDeleteReturnsFalseForTest: Boolean = false
internal var galleryCleanupMetadataFailureForTest: Throwable? = null
internal var galleryReadFailureForTest: Throwable? = null

data class KeplerGalleryJobSummary(
    val id: String,
    val jobType: String,
    val directory: File,
    val createdAt: Long,
    val status: String,
    val requestedFrames: Int,
    val savedFrames: Int,
    val width: Int?,
    val height: Int?,
    val folderSizeBytes: Long,
    val storage: KeplerJobStorageInfo,
    val finalPreviewFile: File?,
    val finalExportExists: Boolean,
    val frames: List<KeplerGalleryFrame>,
    val metadata: JSONObject?,
    val recoveryState: String = "STABLE",
    val recoveryMessage: String? = null,
    val lastRecoveryClassification: String? = null,
    val lastRecoveryMessage: String? = null,
    /** Local final/preview file actually exists on disk right now. */
    val localFinalAvailable: Boolean = false,
    /** Current public Gallery result is believed available (never inferred from history alone). */
    val publicResultAvailable: Boolean = false,
    /** Canonical source frames actually exist on disk right now. */
    val sourceFramesAvailable: Boolean = false,
    /** Reprocess capability derived from actual remaining sources. */
    val canReprocess: Boolean = false
) {
    /** Public result was proven removed outside Kepler by recovery. */
    val publicResultRemovedExternally: Boolean
        get() = metadata?.optString("exportStatus").orEmpty() == "REMOVED_EXTERNALLY"

    /** "있음" / "삭제됨" / "확인 필요" */
    val publicResultStateText: String
        get() = when {
            publicResultAvailable -> "있음"
            publicResultRemovedExternally -> "삭제됨"
            else -> "확인 필요"
        }
}

data class KeplerJobStorageInfo(
    val totalJobBytes: Long,
    val totalJobSizeText: String,
    val finalOutputBytes: Long,
    val finalOutputSizeText: String,
    val rawFramesBytes: Long,
    val intermediateFilesBytes: Long,
    val debugFilesBytes: Long,
    val previewFilesBytes: Long,
    val cacheFilesBytes: Long,
    val cleanableBytes: Long,
    val fileCount: Int
)

enum class KeplerJobCleanupType {
    DEBUG_ONLY,
    /** Legacy name for the modern DELETE_SOURCES action (durable metadata vocabulary). */
    SOURCE_FRAMES_ONLY,
    /** Legacy "KEEP final only": retains only final outputs; deletes sources as well. */
    FINAL_ONLY,
    /** Legacy name for the modern KEEP_SOURCE_ONLY action (durable metadata vocabulary). */
    SOURCE_ONLY,
    FAILED_JOB_DELETE,
    /** Modern recomputable intermediate/debug/cache purge; canonical originals and finals are kept. */
    DERIVED_CACHE_ONLY
}

enum class CleanupStatus { COMPLETE, PARTIAL, FAILED }

data class KeplerJobCleanupResult(
    val bytesFreed: Long,
    val failedPaths: List<String>,
    val metadataWarning: String?,
    val cleanupStatus: CleanupStatus = if (failedPaths.isEmpty()) CleanupStatus.COMPLETE else CleanupStatus.PARTIAL
)

data class KeplerGalleryFrame(
    val index: Int,
    val fileName: String,
    val timestampNs: Long?,
    val enabled: Boolean,
    val excludedByUser: Boolean,
    val excludeReason: String?,
    val file: File?,
    val sharpnessScore: Float?,
    val motionScore: Float?,
    val exposureScore: Float?,
    val brightnessMean: Float?,
    val brightnessStdDev: Float?,
    val clippedShadowRatio: Float?,
    val clippedHighlightRatio: Float?,
    val qualityScore: Float?,
    val qualityLabel: String?,
    val recommendedExclude: Boolean,
    val qualityReason: String?
) {
    val included: Boolean get() = enabled && !excludedByUser
}

fun loadJobJson(jobDir: File): JSONObject =
    KeplerJobMetadata.read(jobDir)

fun saveJobJson(context: Context, jobDir: File, job: JSONObject) {
    // Resolve provider-aware retained debt BEFORE acquiring mutation lease; the real gate
    // reports the durable blocking reason (verification/commit policy, retained owner,
    // unresolved journal) — never a synthetic DEAD_OPERATION.
    if (!settleMediaStoreExportDebt(context, jobDir)) {
        KeplerJobMetadata.requireRecoveryMutationAllowed(jobDir, JobRecoveryMutationIntent.METADATA_EDIT)
    }
    val lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
        jobDir,
        JobRecoveryMutationIntent.METADATA_EDIT
    )
    try {
        KeplerJobMetadata.write(jobDir, job)
    } finally {
        lease.release()
    }
}

fun setFrameExcluded(context: Context, jobDir: File, frameIndex: Int, excluded: Boolean) {
    // Resolve provider-aware retained debt BEFORE acquiring mutation lease; the real gate
    // reports the durable blocking reason (verification/commit policy, retained owner,
    // unresolved journal) — never a synthetic DEAD_OPERATION.
    if (!settleMediaStoreExportDebt(context, jobDir)) {
        KeplerJobMetadata.requireRecoveryMutationAllowed(jobDir, JobRecoveryMutationIntent.FRAME_SELECTION)
    }
    // External mutation: acquire own operation lease, reject unresolved transactions
    val lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
        jobDir,
        JobRecoveryMutationIntent.FRAME_SELECTION
    )
    try {
        require(!isReprocessQuarantined(jobDir)) { "Cannot modify frames of a quarantined or unresolved reprocess job." }
        KeplerJobMetadata.update(jobDir) { job ->
        val frames = job.getJSONArray("frames")
        var found = false
        repeat(frames.length()) { position ->
            val frame = frames.getJSONObject(position)
            if (frame.optInt("index", position) == frameIndex) {
                frame.put("enabled", !excluded)
                    .put("excludedByUser", excluded)
                    .put("excludeReason", if (excluded) "USER_EXCLUDED" else JSONObject.NULL)
                found = true
            }
        }
        require(found) { "Frame index $frameIndex not found." }
        job.put("updatedAt", System.currentTimeMillis())
        }
    } finally {
        lease.release()
    }
}

fun getEnabledRawFrames(jobDir: File): List<JSONObject> {
    val job = loadJobJson(jobDir)
    val frames = job.optJSONArray("frames") ?: return emptyList()
    return buildList {
        repeat(frames.length()) { position ->
            val frame = frames.getJSONObject(position)
            val fileName = frame.optString("raw16File")
            if (
                frame.optBoolean("enabled", true) &&
                !frame.optBoolean("excludedByUser", false) &&
                fileName.isNotBlank() &&
                NoFollowFileSystem.resolveDirectChild(jobDir, fileName, requireFile = true) != null
            ) {
                add(frame)
            }
        }
    }
}

fun loadKeplerGalleryJobs(context: Context): List<KeplerGalleryJobSummary> {
    return keplerGalleryRoots(context).flatMap { root ->
        NoFollowFileSystem.requireDirectChildren(root)
            .filter { NoFollowFileSystem.isRealDirectory(it.toPath()) && matchesJobPrefix(root, it.name) }
    }.map { directory ->
        val metadata = NoFollowFileSystem.resolveDirectChildResult(directory, JOB_JSON_FILE_NAME, requireFile = true)
        if (metadata is NoFollowInspection.Absent) {
            recoveryGallerySummary(directory, KeplerJobMetadataMissing(directory))
        } else {
            try {
                readKeplerGalleryJob(directory)
            } catch (failure: Error) {
                throw failure
            } catch (failure: Exception) {
                recoveryGallerySummary(directory, failure)
            }
        }
    }.sortedByDescending { it.createdAt }
}

internal fun recoveryGallerySummary(directory: File, failure: Throwable): KeplerGalleryJobSummary {
    val metadataFile = NoFollowFileSystem.resolveDirectChild(directory, JOB_JSON_FILE_NAME, requireFile = true)
    val missing = metadataFile == null
    val recoveryState = if (missing) "ORPHANED_JOB_METADATA" else "METADATA_CORRUPT"
    val recoveryMessage = if (missing) {
        "이 작업의 메타데이터가 없어 복구가 필요합니다."
    } else {
        "이 작업의 메타데이터를 읽을 수 없어 복구가 필요합니다."
    }
    val frames = try {
        listFilesNoFollow(directory)
            .filter { NoFollowFileSystem.isRealFile(it.toPath()) && isSourceFrame(it) }
            .sortedBy { it.name }
            .mapIndexed { index, file ->
                KeplerGalleryFrame(
                    index, file.name, null, true, false, null, file,
                    null, null, null, null, null, null, null, null, null, false, null
                )
            }
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        emptyList()
    }
    val storage = try {
        computeKeplerJobStorage(directory, null, null)
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        KeplerJobStorageInfo(0L, "0 B", 0L, "0 B", 0L, 0L, 0L, 0L, 0L, 0L, 0)
    }
    return KeplerGalleryJobSummary(
        id = directory.absolutePath,
        jobType = when {
            directory.name.startsWith("KPL_RAW_FUSION_") -> "RAW_NIGHT_FUSION"
            directory.name.startsWith("KPL_YUV_FUSION_") -> "YUV_NIGHT_FUSION"
            directory.name.startsWith("KPL_SUPER_RES_") -> "SUPER_RESOLUTION"
            else -> "COLOR/YUV"
        },
        directory = directory,
        createdAt = directory.lastModified(),
        status = recoveryState,
        requestedFrames = frames.size,
        savedFrames = frames.size,
        width = null,
        height = null,
        folderSizeBytes = storage.totalJobBytes,
        storage = storage,
        finalPreviewFile = null,
        finalExportExists = false,
        frames = frames,
        metadata = null,
        recoveryState = recoveryState,
        recoveryMessage = recoveryMessage + " (${failure.javaClass.simpleName})",
        localFinalAvailable = false,
        publicResultAvailable = false,
        sourceFramesAvailable = frames.isNotEmpty(),
        canReprocess = frames.isNotEmpty()
    )
}

data class KeplerGalleryStorageSummary(
    val totalBytes: Long,
    val finalOutputBytes: Long,
    val sourceFrameBytes: Long,
    val intermediateBytes: Long,
    val debugDiagnosticBytes: Long,
    val cleanableBytes: Long,
    val rawBytes: Long,
    val yuvBytes: Long,
    val debugCacheBytes: Long,
    val jobCount: Int
)

fun summarizeKeplerGalleryStorage(jobs: List<KeplerGalleryJobSummary>): KeplerGalleryStorageSummary {
    return KeplerGalleryStorageSummary(
        totalBytes = jobs.sumOf { it.storage.totalJobBytes },
        finalOutputBytes = jobs.sumOf { it.storage.finalOutputBytes },
        sourceFrameBytes = jobs.sumOf { it.storage.rawFramesBytes },
        intermediateBytes = jobs.sumOf { it.storage.intermediateFilesBytes },
        debugDiagnosticBytes = jobs.sumOf { it.storage.debugFilesBytes + it.storage.previewFilesBytes },
        cleanableBytes = jobs.sumOf { it.storage.cleanableBytes },
        rawBytes = jobs.filter { it.jobType == "RAW_NIGHT_FUSION" }.sumOf { it.storage.totalJobBytes },
        yuvBytes = jobs.filter {
            it.jobType == "YUV_NIGHT_FUSION" || it.jobType == "YUV_SINGLE_FRAME"
        }.sumOf { it.storage.totalJobBytes },
        debugCacheBytes = jobs.sumOf { it.storage.debugFilesBytes + it.storage.cacheFilesBytes },
        jobCount = jobs.size
    )
}

fun deleteKeplerGalleryJob(context: Context, jobDirectory: File): Result<KeplerJobCleanupResult> {
    return try {
        val target = requireCleanupSafeJobDirectory(context, jobDirectory)
        val lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
            target,
            JobRecoveryMutationIntent.JOB_DELETE
        )
        try {
            galleryDeleteFailureForTest?.let { injected ->
                galleryDeleteFailureForTest = null
                throw injected
            }
            require(target.isDirectory) { "Job directory no longer exists." }
            val bytesBefore = try {
                folderSizeBytesNoFollow(target)
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                0L
            }
            val (status, failedPaths) = deleteRecursivelySafe(target)
            if (status == CleanupStatus.COMPLETE) KeplerJobMetadata.removeLockEntry(target)
            val bytesAfter = if (status == CleanupStatus.COMPLETE) {
                0L
            } else {
                try {
                    folderSizeBytesNoFollow(target)
                } catch (failure: Error) {
                    throw failure
                } catch (_: Exception) {
                    bytesBefore
                }
            }
            Result.success(KeplerJobCleanupResult(
                bytesFreed = (bytesBefore - bytesAfter).coerceAtLeast(0L),
                failedPaths = failedPaths,
                metadataWarning = failedPaths.takeIf { it.isNotEmpty() }
                    ?.let { "Cleanup ${status.name}: ${it.size} path(s) failed" },
                cleanupStatus = status
            ))
        } finally {
            lease.release()
        }
    } catch (failure: Error) {
        throw failure
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}

fun cleanupKeplerGalleryJob(
    context: Context,
    jobDirectory: File,
    cleanupType: KeplerJobCleanupType
): Result<KeplerJobCleanupResult> = cleanupKeplerGalleryJobInternal(context, jobDirectory, cleanupType)

/** Modern explicit storage action entry point (Phase 6). */
fun cleanupKeplerGalleryJob(
    context: Context,
    jobDirectory: File,
    action: KeplerStorageAction
): Result<KeplerJobCleanupResult> = when (action) {
    KeplerStorageAction.DELETE_SOURCES ->
        cleanupKeplerGalleryJobInternal(context, jobDirectory, KeplerJobCleanupType.SOURCE_FRAMES_ONLY)
    KeplerStorageAction.DELETE_DERIVED_CACHE ->
        cleanupKeplerGalleryJobInternal(context, jobDirectory, KeplerJobCleanupType.DERIVED_CACHE_ONLY)
    KeplerStorageAction.KEEP_SOURCE_ONLY ->
        cleanupKeplerGalleryJobInternal(context, jobDirectory, KeplerJobCleanupType.SOURCE_ONLY)
    KeplerStorageAction.DELETE_LOCAL_JOB ->
        deleteKeplerGalleryJob(context, jobDirectory)
    KeplerStorageAction.DEBUG_ONLY ->
        cleanupKeplerGalleryJobInternal(context, jobDirectory, KeplerJobCleanupType.DEBUG_ONLY)
}

private fun cleanupKeplerGalleryJobInternal(
    context: Context,
    jobDirectory: File,
    requestedType: KeplerJobCleanupType
): Result<KeplerJobCleanupResult> {
    return try {
        val target = requireCleanupSafeJobDirectory(context, jobDirectory)
        val lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
            target,
            JobRecoveryMutationIntent.JOB_CLEANUP
        )
        try {
    val before = folderSizeBytes(target)
    val job = when (val resolved = NoFollowFileSystem.resolveDirectChildResult(
        target, JOB_JSON_FILE_NAME, requireFile = true
    )) {
        is NoFollowInspection.Present -> try {
            JSONObject(NoFollowFileSystem.readTextVerified(resolved.value))
        } catch (failure: Error) {
            throw failure
        } catch (failure: Exception) {
            throw IllegalStateException("Cannot read job.json", failure)
        }
        NoFollowInspection.Absent -> JSONObject()
        is NoFollowInspection.InspectionFailed -> throw resolved.exception
    }
    val finalFiles = finalFilesForCleanup(target, job)
    // Phase 5: explicit SOURCE deletion must never require a final result. The user may purge
    // local sources even when the Gallery result was deleted, the local final is absent, export
    // failed, or no final ever existed — losing reprocess capability is the accepted contract.
    if (
        requestedType == KeplerJobCleanupType.FINAL_ONLY &&
        finalFiles.isEmpty()
    ) {
        throw IllegalStateException("Final output missing; cleanup refused.")
    }
    val kind = when {
        job.optString("jobType") == "RAW_NIGHT_FUSION" -> ReprocessJobKind.RAW_FUSION
        job.optString("jobType") == "YUV_NIGHT_FUSION" ||
            job.optString("jobType") == "YUV_NIGHT_FUSION_MULTI" -> ReprocessJobKind.YUV_FUSION
        job.optString("jobType") == "COLOR_BURST" -> ReprocessJobKind.COLOR_BURST
        else -> when {
            target.name.startsWith("KPL_RAW_FUSION_") -> ReprocessJobKind.RAW_FUSION
            target.name.startsWith("KPL_YUV_FUSION_") -> ReprocessJobKind.YUV_FUSION
            target.name.startsWith("KPL_COLOR_BURST_") -> ReprocessJobKind.COLOR_BURST
            else -> ReprocessJobKind.UNSUPPORTED
        }
    }
    val hasMetadataCanonicalAuthority = job.optJSONArray("frames")?.length()?.let { it > 0 } == true && kind != ReprocessJobKind.UNSUPPORTED
    val canonicalSourceFiles = if (hasMetadataCanonicalAuthority) {
        CanonicalFrameSources.resolve(target, job, kind).mapNotNull { it.sourceFile }.toSet()
    } else {
        listFilesNoFollow(target).filter { it.isFile && isSourceFrame(it) }.toSet()
    }
    val filesToDelete = listFilesNoFollow(target)
        .filter { NoFollowFileSystem.isRealFile(it.toPath()) }
        .filter { file ->
            when (requestedType) {
                KeplerJobCleanupType.DEBUG_ONLY -> isDeletableDebugFile(file, finalFiles)
                KeplerJobCleanupType.SOURCE_FRAMES_ONLY -> {
                    if (file.name == JOB_JSON_FILE_NAME || file in finalFiles) return@filter false
                    file in canonicalSourceFiles || isIntermediateFile(file, finalFiles.map { it.name }.toSet())
                }
                KeplerJobCleanupType.DERIVED_CACHE_ONLY -> {
                    if (file.name == JOB_JSON_FILE_NAME || isRequiredSourceOnlyMetadata(file) || file in finalFiles) return@filter false
                    if (file in canonicalSourceFiles) return@filter false
                    if (CanonicalFrameSources.isDerivedFusionInputFileName(file.name, job)) return@filter true
                    isDeletableDebugFile(file, finalFiles) ||
                        isIntermediateFile(file, finalFiles.map { it.name }.toSet()) ||
                        isCacheFile(file, finalFiles.map { it.name }.toSet()) ||
                        isPreviewFile(file, finalFiles.map { it.name }.toSet())
                }
                KeplerJobCleanupType.FINAL_ONLY -> file.name != JOB_JSON_FILE_NAME && file !in finalFiles
                KeplerJobCleanupType.SOURCE_ONLY -> {
                    if (file.name == JOB_JSON_FILE_NAME || isRequiredSourceOnlyMetadata(file)) return@filter false
                    if (CanonicalFrameSources.isDerivedFusionInputFileName(file.name, job)) return@filter true
                    if (file in canonicalSourceFiles) return@filter false
                    val name = file.name.lowercase()
                    file in finalFiles ||
                        isDeletableDebugFile(file, finalFiles) ||
                        isIntermediateFile(file, finalFiles.map { it.name }.toSet()) ||
                        isCacheFile(file, finalFiles.map { it.name }.toSet()) ||
                        isPreviewFile(file, finalFiles.map { it.name }.toSet()) ||
                        name.startsWith("final") ||
                        name.contains("thumbnail") ||
                        name.contains("gallery") ||
                        name.contains("temp") ||
                        name.contains("tmp")
                }
                KeplerJobCleanupType.FAILED_JOB_DELETE -> false
            }
        }
        .toList()
    val failed = mutableListOf<String>()
    filesToDelete.forEach { item ->
        val deleted = if (NoFollowFileSystem.isRealFile(item.toPath())) {
            galleryCleanupFileDeleteFailureForTest?.let { injected ->
                galleryCleanupFileDeleteFailureForTest = null
                throw injected
            }
            if (galleryCleanupDeleteFailurePathForTest == item.name) {
                galleryCleanupDeleteFailurePathForTest = null
                false
            } else if (galleryCleanupDeleteReturnsFalseForTest) {
                galleryCleanupDeleteReturnsFalseForTest = false
                false
            } else try {
                java.nio.file.Files.deleteIfExists(item.toPath())
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                false
            }
        } else {
            true
        }
        if (!deleted) {
            failed += item.absolutePath
        }
    }
    val after = folderSizeBytes(target)
    // Phase 16: metadata must describe ACTUAL remaining filesystem state, inspected after deletion.
    val remainingFiles = listFilesNoFollow(target).filter { NoFollowFileSystem.isRealFile(it.toPath()) }
    val sourceCount = canonicalSourceAvailability(target, job, kind)
    val sourceAvailable = sourceCount > 0
    val debugAvailable = remainingFiles.any { isDebugFile(it, finalFiles.map { f -> f.name }.toSet()) }
    val finalOutputAvailable = finalFiles.any { NoFollowFileSystem.isRealFile(it.toPath()) }
    val effectiveSourceOnly = requestedType == KeplerJobCleanupType.SOURCE_ONLY && !finalOutputAvailable
    val effectiveCleanupType = when {
        effectiveSourceOnly -> KeplerJobCleanupType.SOURCE_ONLY.name
        requestedType == KeplerJobCleanupType.SOURCE_ONLY -> "SOURCE_ONLY_PARTIAL"
        else -> requestedType.name
    }
    val metadataWarning = try {
        galleryCleanupMetadataFailureForTest?.let { injected ->
            galleryCleanupMetadataFailureForTest = null
            throw injected
        }
        KeplerJobMetadata.update(target) { j ->
            val updated = computeKeplerJobStorage(target, j, finalFiles.firstOrNull())
            j.put("cleanupApplied", failed.isEmpty())
                .put("cleanupType", effectiveCleanupType)
                .put("requestedCleanupType", requestedType.name)
                .put("storageAction", requestedType.toStorageAction()?.name ?: JSONObject.NULL)
                .put("cleanupAt", System.currentTimeMillis())
                .put("bytesFreed", (before - after).coerceAtLeast(0L))
                .put("remainingJobBytes", after)
                .put("sourceFramesAvailable", sourceAvailable)
                .put("finalOutputAvailable", finalOutputAvailable)
                .put("debugFilesAvailable", debugAvailable)
                .put("canReprocess", sourceAvailable && canReprocessFromCanonicalCounts(target, job, kind))
                .put("galleryDisplayUnavailable", effectiveSourceOnly)
                .put("galleryVisible", !effectiveSourceOnly)
            putStorageMetadata(j, updated)
        }
        null
    } catch (failure: Error) {
        throw failure
    } catch (failure: Exception) {
        "${failure.javaClass.simpleName}: ${failure.message}"
    }
    // Metadata truth failures are never reported as full success.
    val cleanupStatus = when {
        metadataWarning != null && failed.isEmpty() -> CleanupStatus.FAILED
        failed.isNotEmpty() || metadataWarning != null -> CleanupStatus.PARTIAL
        else -> CleanupStatus.COMPLETE
    }
    Result.success(KeplerJobCleanupResult(
        bytesFreed = (before - after).coerceAtLeast(0L),
        failedPaths = failed,
        metadataWarning = metadataWarning,
        cleanupStatus = cleanupStatus
    ))
        } finally {
            lease.release()
        }
    } catch (failure: Error) {
        throw failure
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}

fun keplerGalleryRoots(context: Context): List<File> {
    val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return emptyList()
    return listOf(
        File(pictures, "KeplerRawFusion"),
        File(pictures, "KeplerYuvFusion"),
        File(pictures, "KeplerColorBurst"),
        File(pictures, "KeplerSuperRes")
    )
}

internal fun cleanupSafeRoots(context: Context): List<File> {
    val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return emptyList()
    return listOf(
        File(pictures, "KeplerRawFusion"),
        File(pictures, "KeplerYuvFusion"),
        File(pictures, "KeplerColorBurst"),
        File(pictures, "KeplerSuperRes")
    )
}

internal fun requireCleanupSafeJobDirectory(context: Context, jobDirectory: File): File {
    val target = cleanupSafeRoots(context).firstNotNullOfOrNull { root ->
        if (!NoFollowFileSystem.isRealDirectory(root.toPath())) return@firstNotNullOfOrNull null
        if (!matchesJobPrefix(root, jobDirectory.name)) return@firstNotNullOfOrNull null
        NoFollowFileSystem.resolveDirectChild(root, jobDirectory.name)
    }
    require(target != null) { "Refusing to modify directory outside known Kepler job roots." }
    return target
}

internal fun matchesJobPrefix(root: File, name: String): Boolean = when (root.name) {
    "KeplerRawFusion" -> name.startsWith("KPL_RAW_FUSION_")
    "KeplerYuvFusion" -> name.startsWith("KPL_YUV_FUSION_")
    "KeplerColorBurst" -> name.startsWith("KPL_COLOR_BURST_")
    "KeplerSuperRes" -> name.startsWith("KPL_SUPER_RES_")
    else -> false
}

fun readKeplerGalleryJob(directory: File): KeplerGalleryJobSummary {
    galleryReadFailureForTest?.let { injected ->
        galleryReadFailureForTest = null
        throw injected
    }
    val job = when (val resolved = NoFollowFileSystem.resolveDirectChildResult(
        directory, JOB_JSON_FILE_NAME, requireFile = true
    )) {
        is NoFollowInspection.Present -> JSONObject(NoFollowFileSystem.readTextVerified(resolved.value))
        NoFollowInspection.Absent -> null
        is NoFollowInspection.InspectionFailed -> throw resolved.exception
    }
    val frames = job?.optJSONArray("frames").galleryFrames(directory)
        .orEmpty()
        .ifEmpty {
            listFilesNoFollow(directory)
                .filter { NoFollowFileSystem.isRealFile(it.toPath()) && isSourceFrame(it) }
                ?.sortedBy { it.name }
                ?.mapIndexed { index, file ->
                    KeplerGalleryFrame(
                        index, file.name, null, true, false, null, file,
                        null, null, null, null, null, null, null, null, null, false, null
                    )
                }
                .orEmpty()
        }
    val finalPreview = resolveFinalPreview(directory, job)
    val storage = computeKeplerJobStorage(directory, job, finalPreview)
    maybePersistStorageMetadata(directory, job, storage)
    val createdAt = job?.optLong("createdAt", 0L)
        ?.takeIf { it > 0L }
        ?: directory.lastModified()
    val width = firstPositive(job, "outputWidth", "rawWidth", "inputWidth", "width")
    val height = firstPositive(job, "outputHeight", "rawHeight", "inputHeight", "height")
    val rawType = job?.optString("jobType").orEmpty()
    val jobType = when {
        rawType == "RAW_NIGHT_FUSION" || directory.name.startsWith("KPL_RAW_FUSION_") ->
            "RAW_NIGHT_FUSION"
        rawType == "YUV_SINGLE_FRAME" ->
            "YUV_SINGLE_FRAME"
        rawType == "YUV_NIGHT_FUSION" || directory.name.startsWith("KPL_YUV_FUSION_") ->
            "YUV_NIGHT_FUSION"
        rawType == "SUPER_RESOLUTION" || rawType == "SUPER_RESOLUTION_FUSION" || directory.name.startsWith("KPL_SUPER_RES_") ->
            "SUPER_RESOLUTION"
        else -> "COLOR/YUV"
    }
    val exportExists = finalPreview != null ||
        (job?.optBoolean("exportVerified", false) == true && job.optString("exportUri").isNotBlank()) ||
        (job?.optBoolean("galleryExportCommitted", false) == true && job.optString("exportUri").isNotBlank()) ||
        MediaStoreExportJournal.list(directory).any { journal ->
            journal.role == MediaStoreExportRole.MAIN_IMAGE &&
                journal.state == MediaStoreExportState.VERIFIED &&
                !journal.uri.isNullOrBlank()
        }

    val kind = if (job != null) detectJobKind(directory, job) else ReprocessJobKind.UNSUPPORTED

    // Phase 13 — split availability truths. A historical VERIFIED journal is evidence that a
    // result EXISTED, never that it still exists today; current public availability comes only
    // from durable claims that recovery has reconciled against provider truth.
    val localFinalAvailable = finalPreview != null && NoFollowFileSystem.isRealFile(finalPreview.toPath())
    val exportStatusRemoved = job?.optString("exportStatus").orEmpty() == "REMOVED_EXTERNALLY"
    val currentPublicClaim = (
        job?.optBoolean("galleryExportCommitted", false) == true ||
            job?.optBoolean("exportVerified", false) == true
        ) && job.optString("exportUri").orEmpty().let { it.isNotBlank() && it != "null" }
    val publicResultAvailable = !exportStatusRemoved &&
        job?.optBoolean("publicResultAvailable", true) != false &&
        currentPublicClaim
    val hasMetadataFrames = job?.optJSONArray("frames")?.length()?.let { it > 0 } == true
    val sourceFramesAvailable = if (hasMetadataFrames && kind != ReprocessJobKind.UNSUPPORTED) {
        canonicalSourceAvailability(directory, job, kind) > 0
    } else {
        frames.any { it.file != null }
    }
    val actualCanonicalCanReprocess = canReprocessFromCanonicalCounts(directory, job, kind)
    val persistedPolicyAllows = job?.has("canReprocess") != true || job?.optBoolean("canReprocess", true) == true
    val canReprocess = job != null && persistedPolicyAllows && actualCanonicalCanReprocess

    return KeplerGalleryJobSummary(
        id = directory.absolutePath,
        jobType = jobType,
        directory = directory,
        createdAt = createdAt,
        status = job?.optString("status").orEmpty().ifBlank { "UNKNOWN" },
        requestedFrames = job?.optInt("requestedFrames", frames.size) ?: frames.size,
        savedFrames = job?.optInt("savedFrames", frames.size) ?: frames.size,
        width = width,
        height = height,
        folderSizeBytes = storage.totalJobBytes,
        storage = storage,
        finalPreviewFile = finalPreview,
        finalExportExists = exportExists,
        frames = frames,
        metadata = job,
        recoveryState = job?.optString("recoveryState").orEmpty().ifBlank { "STABLE" },
        recoveryMessage = job?.optString("recoveryMessage").orEmpty().ifBlank { null },
        lastRecoveryClassification = job?.optString("lastRecoveryClassification").orEmpty().ifBlank { null },
        lastRecoveryMessage = job?.optString("lastRecoveryMessage").orEmpty().ifBlank { null },
        localFinalAvailable = localFinalAvailable,
        publicResultAvailable = publicResultAvailable,
        sourceFramesAvailable = sourceFramesAvailable,
        canReprocess = canReprocess
    )
}

fun computeKeplerJobStorage(
    directory: File,
    job: JSONObject?,
    finalPreview: File?
): KeplerJobStorageInfo {
    var totalBytes = 0L
    var fileCount = 0
    val encodedFinalNames = setOfNotNull(
        finalPreview?.name,
        job?.optString("galleryDisplayFile").orEmpty().ifBlank { null },
        job?.optString("finalNightFusionFile").orEmpty().ifBlank { null },
        job?.optString("finalFile").orEmpty().ifBlank { null },
        job?.optString("outputFile").orEmpty().ifBlank { null }
    )
    val finalNames = if (encodedFinalNames.isNotEmpty()) encodedFinalNames else setOfNotNull(
        job?.optString("nativePostprocessRgbaFile").orEmpty().ifBlank { null }
    )
    var finalBytes = 0L
    var rawBytes = 0L
    var debugBytes = 0L
    var previewBytes = 0L
    var cacheBytes = 0L
    var intermediateBytes = 0L
    var cleanableBytes = 0L

    val kind = when {
        job == null -> ReprocessJobKind.UNSUPPORTED
        job.optString("jobType") == "RAW_NIGHT_FUSION" -> ReprocessJobKind.RAW_FUSION
        job.optString("jobType") == "YUV_NIGHT_FUSION" ||
            job.optString("jobType") == "YUV_NIGHT_FUSION_MULTI" -> ReprocessJobKind.YUV_FUSION
        job.optString("jobType") == "COLOR_BURST" -> ReprocessJobKind.COLOR_BURST
        else -> when {
            directory.name.startsWith("KPL_RAW_FUSION_") -> ReprocessJobKind.RAW_FUSION
            directory.name.startsWith("KPL_YUV_FUSION_") -> ReprocessJobKind.YUV_FUSION
            directory.name.startsWith("KPL_COLOR_BURST_") -> ReprocessJobKind.COLOR_BURST
            else -> ReprocessJobKind.UNSUPPORTED
        }
    }
    val hasMetadataCanonicalAuthority = job != null &&
        kind != ReprocessJobKind.UNSUPPORTED &&
        (job.optJSONArray("frames")?.length() ?: 0) > 0
    val canonicalSources = if (hasMetadataCanonicalAuthority) {
        CanonicalFrameSources.resolve(directory, job, kind).mapNotNull { it.sourceFile }.toSet()
    } else emptySet()

    listFilesNoFollow(directory).forEach { file ->
        if (!file.isFile) return@forEach
        val bytes = file.length()
        totalBytes += bytes
        fileCount++
        val isFinal = file.name in finalNames
        val source = if (hasMetadataCanonicalAuthority) {
            file in canonicalSources
        } else {
            isCanonicalSourceFileForJob(file, job)
        }
        val debug = isDebugFile(file, finalNames)
        val preview = isPreviewFile(file, finalNames)
        val cache = isCacheFile(file, finalNames)
        val intermediate = isIntermediateFile(file, finalNames)
        if (isFinal) finalBytes += bytes
        if (source) rawBytes += bytes
        if (debug) debugBytes += bytes
        if (preview) previewBytes += bytes
        if (cache) cacheBytes += bytes
        if (intermediate) intermediateBytes += bytes
        if (file.name != JOB_JSON_FILE_NAME && !isFinal && (source || debug || preview || cache || intermediate)) {
            cleanableBytes += bytes
        }
    }
    return KeplerJobStorageInfo(
        totalJobBytes = totalBytes,
        totalJobSizeText = formatBytes(totalBytes),
        finalOutputBytes = finalBytes,
        finalOutputSizeText = formatBytes(finalBytes),
        rawFramesBytes = rawBytes,
        intermediateFilesBytes = intermediateBytes,
        debugFilesBytes = debugBytes,
        previewFilesBytes = previewBytes,
        cacheFilesBytes = cacheBytes,
        cleanableBytes = cleanableBytes,
        fileCount = fileCount
    )
}

fun maybePersistStorageMetadata(
    directory: File,
    job: JSONObject?,
    storage: KeplerJobStorageInfo
) {
    if (job == null) return
    if (isReprocessQuarantined(directory)) return
    if (KeplerJobMetadata.inspectRecoveryMutationGate(
            directory,
            JobRecoveryMutationIntent.METADATA_EDIT
        ) != JobRecoveryMutationGateOutcome.ALLOWED
    ) return
    if (
        job.optLong("totalJobBytes", -1L) == storage.totalJobBytes &&
        job.optInt("fileCount", -1) == storage.fileCount
    ) return
    try {
        KeplerJobMetadata.update(directory) {
            putStorageMetadata(it, storage)
                .put("storageScannedAt", System.currentTimeMillis())
        }
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        // Storage summaries are non-authoritative and best effort.
    }
}

fun putStorageMetadata(job: JSONObject, storage: KeplerJobStorageInfo): JSONObject {
    return job.put("totalJobBytes", storage.totalJobBytes)
        .put("totalJobSizeText", storage.totalJobSizeText)
        .put("finalOutputBytes", storage.finalOutputBytes)
        .put("finalOutputSizeText", storage.finalOutputSizeText)
        .put("rawFramesBytes", storage.rawFramesBytes)
        .put("intermediateFilesBytes", storage.intermediateFilesBytes)
        .put("debugFilesBytes", storage.debugFilesBytes)
        .put("previewFilesBytes", storage.previewFilesBytes)
        .put("cacheFilesBytes", storage.cacheFilesBytes)
        .put("cleanableBytes", storage.cleanableBytes)
        .put("fileCount", storage.fileCount)
}

fun finalFilesForCleanup(directory: File, job: JSONObject?): Set<File> {
    val names = setOfNotNull(
        job?.optString("galleryDisplayFile").orEmpty().ifBlank { null },
        job?.optString("galleryThumbnailFile").orEmpty().ifBlank { null },
        job?.optString("finalNightFusionFile").orEmpty().ifBlank { null },
        job?.optString("finalFile").orEmpty().ifBlank { null },
        job?.optString("outputFile").orEmpty().ifBlank { null },
        claimedSuperResolutionOutput(directory, job)?.name,
        resolveFinalPreview(directory, job)?.name
    )
    return names.mapNotNull { name -> NoFollowFileSystem.resolveDirectChild(directory, name, requireFile = true) }.toSet()
}

fun isDeletableDebugFile(file: File, finalFiles: Set<File>): Boolean {
    if (file.name == JOB_JSON_FILE_NAME || file in finalFiles) return false
    val name = file.name.lowercase()
    if (name == "raw_render_debug.json" || name == "fusion_debug.json" || name == "yuv_debug.json") return false
    return isDebugFile(file, finalFiles.map { it.name }.toSet()) ||
        name.contains("diagnostic") ||
        name.contains("contact") ||
        name.endsWith(".log")
}

fun isDeletableSourceOrIntermediate(file: File, finalFiles: Set<File>): Boolean {
    if (file.name == JOB_JSON_FILE_NAME || file in finalFiles) return false
    return isSourceFrame(file) || isIntermediateFile(file, finalFiles.map { it.name }.toSet())
}

/** Modern DELETE_DERIVED_CACHE: recomputable artifacts only; canonical originals and finals kept. */
fun isDeletableDerivedCache(file: File, finalFiles: Set<File>, job: JSONObject?): Boolean {
    if (file.name == JOB_JSON_FILE_NAME || isRequiredSourceOnlyMetadata(file) || file in finalFiles) return false
    // A converted packed fusion input is always recomputable from its .yuvpack authority.
    if (CanonicalFrameSources.isDerivedFusionInputFileName(file.name, job)) return true
    if (isCanonicalSourceFileForJob(file, job)) return false
    return isDeletableDebugFile(file, finalFiles) ||
        isIntermediateFile(file, finalFiles.map { it.name }.toSet()) ||
        isCacheFile(file, finalFiles.map { it.name }.toSet()) ||
        isPreviewFile(file, finalFiles.map { it.name }.toSet())
}

fun isDeletableForSourceOnly(
    file: File,
    finalFiles: Set<File>,
    job: JSONObject? = null
): Boolean {
    if (file.name == JOB_JSON_FILE_NAME || isRequiredSourceOnlyMetadata(file)) return false
    // PACKED_YUV_V1: the converted frame_NN_color.png is a derived fusion input, never a
    // canonical original; KEEP_SOURCE_ONLY deletes it while the .yuvpack authority survives.
    if (CanonicalFrameSources.isDerivedFusionInputFileName(file.name, job)) return true
    if (isCanonicalSourceFileForJob(file, job)) return false
    val name = file.name.lowercase()
    return file in finalFiles ||
        isDeletableDebugFile(file, finalFiles) ||
        isIntermediateFile(file, finalFiles.map { it.name }.toSet()) ||
        isCacheFile(file, finalFiles.map { it.name }.toSet()) ||
        isPreviewFile(file, finalFiles.map { it.name }.toSet()) ||
        name.startsWith("final") ||
        name.contains("thumbnail") ||
        name.contains("gallery") ||
        name.contains("temp") ||
        name.contains("tmp")
}

/**
 * Job-aware canonical source truth (Phase 10): packed-derived fusion inputs are
 * excluded and durable .yuvpack authorities are included.
 */
internal fun isCanonicalSourceFileForJob(file: File, job: JSONObject?): Boolean {
    val name = file.name
    if (CanonicalFrameSources.isDerivedFusionInputFileName(name, job)) return false
    if (isSourceFrame(file)) return true
    return CanonicalFrameSources.isCanonicalSourceFileName(name, job)
}

private fun isRequiredSourceOnlyMetadata(file: File): Boolean {
    val name = file.name.lowercase()
    return name == "raw_render_input_metadata.json" ||
        name == "gyro.csv" ||
        name == "rotation_vector.csv" ||
        name == "alignment.json" ||
        name == "capture_metadata.json"
}

/** Canonical source availability for cleanup and reprocess gating. */
internal fun canonicalSourceAvailability(target: File, job: JSONObject?, kind: ReprocessJobKind): Int {
    if (job == null || kind == ReprocessJobKind.UNSUPPORTED) {
        return listFilesNoFollow(target).count { it.isFile && isSourceFrame(it) }
    }
    val frames = job.optJSONArray("frames")
    if (frames == null || frames.length() == 0) {
        return listFilesNoFollow(target).count { it.isFile && isSourceFrame(it) }
    }
    return CanonicalFrameSources.resolve(target, job, kind).count { it.sourceFile != null }
}

internal fun canReprocessFromCanonicalCounts(target: File, job: JSONObject?, kind: ReprocessJobKind): Boolean {
    val count = canonicalSourceAvailability(target, job, kind)
    return when (kind) {
        ReprocessJobKind.RAW_FUSION -> count >= MIN_RAW_FUSION_FRAMES
        ReprocessJobKind.YUV_FUSION -> {
            val singleFrame = job != null && isSingleFrameJob(job)
            val required = if (singleFrame) 1 else 2
            count >= required
        }
        ReprocessJobKind.COLOR_BURST -> false
        ReprocessJobKind.UNSUPPORTED -> false
    }
}

private fun isDebugFile(file: File, finalNames: Set<String>): Boolean {
    val name = file.name.lowercase()
    if (file.name in finalNames) return false
    return name in setOf("raw_render_debug.json", "fusion_debug.json", "yuv_debug.json", "alignment_debug.json") ||
        name.contains("debug") ||
        name.contains("compare") ||
        name.endsWith(".log")
}

private fun isPreviewFile(file: File, finalNames: Set<String>): Boolean {
    val name = file.name.lowercase()
    if (file.name in finalNames) return false
    return name.contains("preview") ||
        name.contains("reference") ||
        name.endsWith(".jpg") ||
        name.endsWith(".jpeg") ||
        name.endsWith(".png")
}

private fun isCacheFile(file: File, finalNames: Set<String>): Boolean {
    val name = file.name.lowercase()
    if (file.name in finalNames) return false
    return name.endsWith(".rgba") || name.endsWith(".rgb") || name.endsWith(".bin")
}

private fun isIntermediateFile(file: File, finalNames: Set<String>): Boolean {
    val name = file.name.lowercase()
    if (file.name in finalNames) return false
    return name.startsWith("merged_raw") ||
        name.contains("merged_yuv") ||
        name.contains("intermediate") ||
        name.contains("linear") ||
        name.endsWith(".yuv")
}

private fun JSONArray?.galleryFrames(directory: File): List<KeplerGalleryFrame> {
    if (this == null) return emptyList()
    return buildList {
        repeat(length()) { position ->
            val frame = optJSONObject(position) ?: return@repeat
            val fileName = frame.optString("raw16File")
                .ifBlank { frame.optString("packedSourceFilename") }
                .ifBlank { frame.optString("file") }
                .ifBlank { frame.optString("dngFile") }
            val file = fileName.takeIf { it.isNotBlank() }?.let {
                when (val resolved = NoFollowFileSystem.resolveDirectChildResult(
                    directory, it, requireFile = true
                )) {
                    is NoFollowInspection.Present -> resolved.value
                    NoFollowInspection.Absent -> null
                    is NoFollowInspection.InspectionFailed -> throw resolved.exception
                }
            }
            add(
                KeplerGalleryFrame(
                    index = frame.optInt("index", position),
                    fileName = fileName.ifBlank { "frame_$position" },
                    timestampNs = frame.optLong("timestampNs", 0L).takeIf { it > 0L },
                    enabled = frame.optBoolean("enabled", true),
                    excludedByUser = frame.optBoolean("excludedByUser", false),
                    excludeReason = frame.optString("excludeReason")
                        .takeIf { it.isNotBlank() && it != "null" },
                    file = file,
                    sharpnessScore = frame.optionalFloat("sharpnessScore"),
                    motionScore = frame.optionalFloat("motionScore"),
                    exposureScore = frame.optionalFloat("exposureScore"),
                    brightnessMean = frame.optionalFloat("brightnessMean"),
                    brightnessStdDev = frame.optionalFloat("brightnessStdDev"),
                    clippedShadowRatio = frame.optionalFloat("clippedShadowRatio"),
                    clippedHighlightRatio = frame.optionalFloat("clippedHighlightRatio"),
                    qualityScore = frame.optionalFloat("qualityScore"),
                    qualityLabel = frame.optString("qualityLabel")
                        .takeIf { it.isNotBlank() && it != "null" },
                    recommendedExclude = frame.optBoolean("recommendedExclude", false),
                    qualityReason = frame.optString("qualityReason")
                        .takeIf { it.isNotBlank() && it != "null" }
                )
            )
        }
    }
}

private fun JSONObject.optionalFloat(key: String): Float? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key, Double.NaN)
        .takeIf { it.isFinite() }
        ?.toFloat()
        ?.takeIf { it.isFinite() }
}

private fun resolveFinalPreview(directory: File, job: JSONObject?): File? {
    claimedSuperResolutionOutput(directory, job)?.let { return it }
    val currentNames = listOf(
        job?.optString("galleryDisplayFile").orEmpty(),
        job?.optString("galleryThumbnailFile").orEmpty(),
        job?.optString("previewFile").orEmpty()
    )
    currentNames.asSequence()
        .filter { it.isNotBlank() && it != "null" }
        .mapNotNull { name ->
            when (val resolved = NoFollowFileSystem.resolveDirectChildResult(
                directory, name, requireFile = true
            )) {
                is NoFollowInspection.Present -> resolved.value
                NoFollowInspection.Absent -> null
                is NoFollowInspection.InspectionFailed -> throw resolved.exception
            }
        }
        .firstOrNull { isDisplayImageFile(it) && !isDebugPreviewFinalBlocked(it.name) }
        ?.let { return it }
    if (job?.optBoolean("galleryDisplayUnavailable", false) == true ||
        (job?.optBoolean("galleryExportCommitted", false) == true &&
            job.optBoolean("finalOutputAvailable", false).not())
) return null
    // Prevent fallback scanning for reprocessed jobs
    if (job?.has("reprocessStatus") == true || job?.has("reprocessCount") == true || job?.has("reprocessAt") == true) {
        return null
    }
    val keys = listOf(
        "finalNightFusionFile",
        "finalFile",
        "outputFile"
    )
    keys.forEach { key ->
        val name = job?.optString(key).orEmpty()
        NoFollowFileSystem.resolveDirectChild(directory, name, requireFile = true)?.takeIf {
            name.isNotBlank() &&
                isDisplayImageFile(it) &&
                !isDebugPreviewFinalBlocked(it.name) &&
                (it.extension.equals("png", true) || it.extension.equals("jpg", true) || it.extension.equals("jpeg", true) || it.extension.equals("heic", true) || it.extension.equals("heif", true) || it.extension.equals("webp", true))
        }
            ?.let { return it }
    }
    return NoFollowFileSystem.requireDirectChildren(directory)
        .filter {
                NoFollowFileSystem.isRealFile(it.toPath()) &&
                isDisplayImageFile(it) &&
                !isSourceFrame(it) &&
                !isDebugPreviewFinalBlocked(it.name)
        }
        ?.maxByOrNull { it.lastModified() }
}

private fun isDisplayImageFile(file: File): Boolean =
    file.extension.lowercase() in setOf("png", "jpg", "jpeg", "heic", "heif", "webp")

private fun isDebugPreviewFinalBlocked(name: String): Boolean {
    val lower = name.lowercase()
    return lower in setOf(
        "raw_reference_preview.png",
        "raw_fused_classic_v1_preview.png",
        "raw_compare_reference_vs_fused.png",
        "reference_frame.png",
        "fused_classic_yuv_v1.png",
        "compare_reference_vs_fused.png",
        "yuv_reference_preview.png",
        "yuv_fused_preview.png",
        "yuv_compare_reference_vs_fused.png"
    )
}

private fun firstPositive(job: JSONObject?, vararg keys: String): Int? {
    return keys.firstNotNullOfOrNull { key -> job?.optInt(key, 0)?.takeIf { it > 0 } }
}

fun isSourceFrame(file: File): Boolean {
    val name = file.name.lowercase()
    return name.startsWith("frame_") &&
        (name.endsWith(".png") || name.endsWith(".raw16") || name.endsWith(".dng") ||
            name.endsWith(".yuv") || name.endsWith(".nv21") || name.endsWith(".yuv420") ||
            name.endsWith(PackedYuvFrameStore.FILE_EXTENSION))
}

internal fun isSafeRelativeFilename(name: String): Boolean {
    if (name.isBlank()) return false
    if (name.contains("..")) return false
    if (name.startsWith("/")) return false
    if (name.startsWith("\\")) return false
    if (File(name).isAbsolute) return false
    val normalized = File(name).path.replace("\\", "/")
    if (normalized.contains("..")) return false
    if (normalized.startsWith("/")) return false
    return true
}

/** Narrow partial-failure seam for whole-job deletion contract tests. */
internal var deleteRecursivelySafeOverrideForTest: ((root: File) -> Pair<CleanupStatus, List<String>>)? = null

internal fun deleteRecursivelySafe(root: File): Pair<CleanupStatus, List<String>> {
    deleteRecursivelySafeOverrideForTest?.let { return it(root) }
    return NoFollowFileSystem.deleteTree(root)
}

/** Exact current-attempt Super Resolution output authority; arbitrary image files are fallback only. */
private fun claimedSuperResolutionOutput(directory: File, job: JSONObject?): File? {
    if (job == null) return null
    val mode = job.optString("processingMode").uppercase()
    val jobType = job.optString("jobType").uppercase()
    if (mode != "SUPER_RESOLUTION" && !jobType.contains("SUPER")) return null
    val attemptId = job.optString("processingAttemptId").takeIf { it.isNotBlank() } ?: return null
    if (!job.optBoolean("processingOutputCommitted", false) ||
        job.optString("processingArtifactClaimAttemptId") != attemptId
    ) return null
    val name = job.optString("superResolutionOutputFile")
        .takeIf { it.isNotBlank() && it != "null" } ?: return null
    return NoFollowFileSystem.optionalDirectChildFile(directory, name)
        ?.takeIf { it.isFile && isDisplayImageFile(it) }
}

internal fun listFilesNoFollow(root: File): List<File> {
    return NoFollowFileSystem.listResult(root).let { result ->
        when (result) {
            NoFollowInspection.Absent -> emptyList()
            is NoFollowInspection.Present -> result.value
            is NoFollowInspection.InspectionFailed -> throw result.exception
        }
    }
}

internal fun folderSizeBytesNoFollow(file: File): Long {
    return NoFollowFileSystem.requireSize(file)
}
