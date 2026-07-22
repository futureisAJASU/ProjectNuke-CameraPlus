package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

typealias OutputSettings = FinalOutputFormat

data class ReprocessCapability(
    val canReprocess: Boolean,
    val jobKind: ReprocessJobKind,
    val reason: String,
    val sourceFrameCount: Int,
    val finalOutputExists: Boolean,
    val sourceFramesAvailable: Boolean
)

enum class ReprocessJobKind {
    RAW_FUSION,
    YUV_FUSION,
    COLOR_BURST,
    UNSUPPORTED
}

data class KeplerReprocessResult(
    val jobDir: File,
    val jobKind: ReprocessJobKind,
    val finalOutputFile: File?,
    val previewFile: File?,
    val bytesWritten: Long,
    val warnings: List<String>
)

internal data class ReprocessWorkerRun(
    val terminal: Deferred<ReprocessWorkerOutcome>,
    val cancel: () -> Unit
)

internal enum class ReprocessTerminalDisposition {
    VERIFIED_SUCCESS,
    COMMITTED_PARTIAL,
    UNCOMMITTED_FAILURE,
    CANCELLED
}

internal data class ReprocessWorkerOutcome(
    val result: Result<Unit>,
    val publicExportCommitted: Boolean,
    val exportVerified: Boolean = false,
    val export: GalleryExportResult? = null,
    val finalOutputFile: File? = null,
    val previewFile: File? = null,
    val bytesWritten: Long = 0L,
    val disposition: ReprocessTerminalDisposition = when {
        publicExportCommitted && !exportVerified -> ReprocessTerminalDisposition.COMMITTED_PARTIAL
        result.isSuccess && exportVerified -> ReprocessTerminalDisposition.VERIFIED_SUCCESS
        else -> ReprocessTerminalDisposition.UNCOMMITTED_FAILURE
    },
    val terminalError: Throwable? = result.exceptionOrNull(),
    val sidecar: RawSidecarExportResult? = null,
    val postExportCancellationRequested: Boolean = false,
    val postExportWorkSkipped: Boolean = false,
    val currentLocalPreview: File? = null,
    val currentLocalOutput: File? = null,
    val publicOutcome: RawFusionPublicExportOutcome? = null
)

internal class ReprocessWorkerDidNotExitException(message: String) : IllegalStateException(message)

internal enum class ReprocessFinalizationState { COMMITTED, ROLLED_BACK, QUARANTINED }
internal data class ReprocessFinalizationResult(
    val state: ReprocessFinalizationState,
    val result: Result<KeplerReprocessResult>
)

private const val REPROCESS_TIMEOUT_MS = 10 * 60 * 1000L
private const val REPROCESS_WORKER_EXIT_TIMEOUT_MS = 30_000L
private const val REPROCESS_QUARANTINE_MARKER = ".reprocess_quarantine"
private const val REPROCESS_PREVIEW_PREFIX = "reprocess_preview_"
private const val REPROCESS_PREVIEW_MAX_DIMENSION = 1600
private const val REPROCESS_HISTORY_LIMIT = 32

suspend fun reprocessKeplerGalleryJob(
    context: Context,
    jobDir: File,
    outputSettings: OutputSettings,
    onProgress: (String) -> Unit
): Result<KeplerReprocessResult> = reprocessKeplerGalleryJob(
    context = context,
    jobDir = jobDir,
    outputSettings = outputSettings,
    frameSelection = null,
    onProgress = onProgress
)

suspend fun reprocessKeplerGalleryJob(
    context: Context,
    jobDir: File,
    outputSettings: OutputSettings,
    frameSelection: Set<Int>?,
    onProgress: (String) -> Unit
): Result<KeplerReprocessResult> = withContext(Dispatchers.IO) {
    suspend fun postProgress(message: String) {
        withContext(Dispatchers.Main) { onProgress(message) }
    }

    val target = runCatching { requireReprocessSafeJobDirectory(context, jobDir) }
        .getOrElse { return@withContext Result.failure(it) }
    val operationLease = KeplerJobMetadata.acquireOperation(target) ?: run {
        return@withContext Result.failure(IllegalStateException("A job mutation is already in progress."))
    }
    var releaseOperationLease = false
    try {
    val capability = detectReprocessCapability(context, target)
    if (!capability.canReprocess) {
        writeReprocessFailure(target, capability.reason)
        return@withContext Result.failure(IllegalStateException(capability.reason))
    }

    postProgress("원본 프레임 확인 중…")
    val job = try { KeplerJobMetadata.read(target) } catch (metadataError: KeplerJobMetadataException) {
        writeReprocessFailure(target, "${metadataError.javaClass.simpleName}: ${metadataError.message}")
        return@withContext Result.failure(metadataError)
    }
    val kind = detectJobKind(target, job)
    val reviewItems = loadFrameReviewItems(context, target).getOrElse {
        writeReprocessFailure(target, "${it.javaClass.simpleName}: ${it.message}")
        return@withContext Result.failure(it)
    }
    val resolvedSelection = resolveFrameSelection(target, kind, reviewItems, frameSelection).getOrElse {
        writeReprocessFailure(target, "${it.javaClass.simpleName}: ${it.message}")
        return@withContext Result.failure(it)
    }
    if (resolvedSelection.size < requiredSelectedFrameCount(kind)) {
        val message = "선택한 원본 프레임이 부족합니다. 다시 합성할 수 없습니다."
        writeReprocessFailure(target, message)
        return@withContext Result.failure(IllegalStateException(message))
    }
    val selectionMode = resolveSelectionMode(job, frameSelection)
    val transaction = backupReprocessTransaction(
        target,
        target.listFiles()?.filter { it.isFile && isReprocessWorkerWritable(it) }.orEmpty()
    ).getOrElse {
        writeReprocessFailure(target, "Required reprocess backup failed: ${it.message}")
        return@withContext Result.failure(it)
    }
    postProgress("프레임 선택 적용 중…")
    saveFrameSelectionInternal(
        jobDir = target,
        mode = selectionMode,
        frames = applyFrameSelectionToItems(reviewItems, resolvedSelection, selectionMode),
        operationLease = operationLease
    ).getOrElse {
        val frameSelectionError = it
        val rollback = restoreBackups(target, transaction.backups)
        if (rollback.isFailure) {
            val rollbackError = rollback.exceptionOrNull() ?: IllegalStateException("Rollback failed")
            writeQuarantineMarker(transaction.backups)
            try {
                writeReprocessFailure(
                    target,
                    "${frameSelectionError.javaClass.simpleName}: ${frameSelectionError.message}; rollback failed: ${rollbackError.message}"
                )
            } catch (_: Exception) { /* quarantine already persisted */ }
            releaseOperationLease = false
            return@withContext Result.failure(rollbackError)
        }
        try {
            writeReprocessFailure(target, "${frameSelectionError.javaClass.simpleName}: ${frameSelectionError.message}")
        } catch (metadataFailure: Exception) {
            writeQuarantineMarker(transaction.backups)
            releaseOperationLease = false
            return@withContext Result.failure(metadataFailure)
        }
        removeQuarantineMarker(transaction.backups)
        if (!deleteBackups(transaction.backups)) {
            try {
                KeplerJobMetadata.update(target) {
                    it.put("reprocessWarning", "Frame-selection rollback backup cleanup failed.")
                }
            } catch (_: Exception) { /* rollback already safe; warning best-effort */ }
        }
        return@withContext Result.failure(frameSelectionError)
    }

    val progressScope = CoroutineScope(coroutineContext)
    val cancellation = KeplerPipelineCancellationToken()
    val worker = when (capability.jobKind) {
        ReprocessJobKind.RAW_FUSION ->
            reprocessRawJob(
                context,
                target,
                outputSettings,
                resolvedSelection,
                cancellation = cancellation,
                onStatus = { message -> progressScope.launch { postProgress(message) } }
            )
        ReprocessJobKind.YUV_FUSION ->
            reprocessYuvJob(
                context,
                target,
                outputSettings,
                resolvedSelection,
                cancellation = cancellation,
                onStatus = { message -> progressScope.launch { postProgress(message) } }
            )
        ReprocessJobKind.COLOR_BURST, ReprocessJobKind.UNSUPPORTED -> {
            ReprocessWorkerRun(
                terminal = CompletableDeferred<ReprocessWorkerOutcome>().apply {
                    complete(ReprocessWorkerOutcome(Result.failure(UnsupportedOperationException("Reprocess job is unsupported.")), false))
                },
                cancel = {}
            )
        }
    }

    val pipelineOutcome = try {
        withTimeoutOrNull(REPROCESS_TIMEOUT_MS) { worker.terminal.await() }
    } catch (callerCancellation: kotlinx.coroutines.CancellationException) {
        val cleanup = cancelWorkerAndAwaitTerminal(worker)
        val cancellationFinalization = cleanup.getOrNull()?.let { outcome ->
            finalizeTerminalOutcome(
                target, capability.jobKind, outputSettings, selectionMode,
                resolvedSelection, Result.success(outcome), transaction.backups
            )
        }
        releaseOperationLease = cancellationFinalization?.let { it.state == ReprocessFinalizationState.COMMITTED || it.state == ReprocessFinalizationState.ROLLED_BACK } ?: false
        if (cleanup.exceptionOrNull() is ReprocessWorkerDidNotExitException) {
            writeQuarantineMarker(transaction.backups)
            try {
                writeReprocessFailure(target, "Reprocess worker did not exit; job quarantined.")
            } catch (_: Exception) { /* quarantine is already persisted; metadata is best-effort */ }
            // Worker didn't exit — register late finalization for when it eventually does.
            registerLateFinalization(worker, target, capability, outputSettings, selectionMode, resolvedSelection, transaction.backups, operationLease)
        } else if (cancellationFinalization == null) {
            writeReprocessFailure(target, "Caller cancellation cleanup failed: ${cleanup.exceptionOrNull()?.message}")
            // Worker didn't exit cleanly — register late finalization.
            registerLateFinalization(worker, target, capability, outputSettings, selectionMode, resolvedSelection, transaction.backups, operationLease)
        }
        // If cancellationFinalization was produced (worker exited), do NOT register late finalization
        // even if it was QUARANTINED — the worker outcome was already processed.
        throw callerCancellation
    }
    val terminalOutcome = if (pipelineOutcome == null) {
        cancelWorkerAndAwaitTerminal(worker)
    } else {
        Result.success(pipelineOutcome)
    }
    val finalization = finalizeTerminalOutcome(
        target, capability.jobKind, outputSettings, selectionMode,
        resolvedSelection, terminalOutcome, transaction.backups
    )
    // Lease releases only after a safe commit or rollback. QUARANTINED keeps the lease held while a
    // late worker completion is awaited; that late pass releases iff it too reaches a safe state.
    releaseOperationLease = finalization.state == ReprocessFinalizationState.COMMITTED ||
        finalization.state == ReprocessFinalizationState.ROLLED_BACK
    if (finalization.state == ReprocessFinalizationState.QUARANTINED && terminalOutcome.isFailure) {
        // Only register late finalization if we genuinely never received a worker outcome.
        // If worker outcome was received but finalization failed (QUARANTINED), don't re-run.
        registerLateFinalization(worker, target, capability, outputSettings, selectionMode, resolvedSelection, transaction.backups, operationLease)
    }
    return@withContext finalization.result
    } finally {
        if (releaseOperationLease) operationLease.release()
    }
}

/** Register a late finalization callback on the worker's terminal for when it eventually completes. */
private fun registerLateFinalization(
    worker: ReprocessWorkerRun,
    target: File,
    capability: ReprocessCapability,
    outputSettings: FinalOutputFormat,
    selectionMode: FrameSelectionMode,
    resolvedSelection: Set<Int>,
    transactionBackups: List<ReprocessBackup>,
    operationLease: JobOperationLease
) {
    worker.terminal.invokeOnCompletion {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                worker.terminal.await().let { outcome ->
                    val late = finalizeTerminalOutcome(
                        target, capability.jobKind, outputSettings, selectionMode,
                        resolvedSelection, Result.success(outcome), transactionBackups
                    )
                    if (late.state == ReprocessFinalizationState.COMMITTED || late.state == ReprocessFinalizationState.ROLLED_BACK) {
                        operationLease.release()
                    }
                }
            }.onFailure { /* late finalization failure; retain quarantine and backups */ }
        }
    }
}

private fun finalizeTerminalOutcome(
    jobDir: File,
    jobKind: ReprocessJobKind,
    outputSettings: FinalOutputFormat,
    selectionMode: FrameSelectionMode,
    includedFrameIndices: Set<Int>,
    terminal: Result<ReprocessWorkerOutcome>,
    backups: List<ReprocessBackup>
): ReprocessFinalizationResult {
    val outcome = terminal.getOrElse {
        // A terminal error means we never received a worker outcome. Quarantine and retain backups.
        writeQuarantineMarker(backups)
        try {
            writeTransactionState(backups, ReprocessTransactionState.QUARANTINED)
        } catch (e: Exception) {
            // State write failed; transaction remains unresolved. Propagate both failures.
            throw RuntimeException("Failed to persist QUARANTINED state after terminal error", e).apply { addSuppressed(it) }
        }
        return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(it))
    }
    if (outcome.disposition == ReprocessTerminalDisposition.VERIFIED_SUCCESS ||
        outcome.disposition == ReprocessTerminalDisposition.COMMITTED_PARTIAL ||
        outcome.publicExportCommitted
    ) {
        val committed = finalizeReprocessOutcome(
            jobDir, jobKind, outputSettings, selectionMode, includedFrameIndices, outcome, backups
        )
return if (committed.isSuccess) {
            try {
                writeTransactionState(backups, ReprocessTransactionState.COMMITTED)
            } catch (e: Exception) {
                // COMMITTED state write failed; quarantine and retain backups.
                writeQuarantineMarker(backups)
                return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(e))
            }
            removeQuarantineMarker(backups)
            ReprocessFinalizationResult(ReprocessFinalizationState.COMMITTED, committed)
        } else {
            // Terminal metadata write failed after MediaStore output already committed. The public
            // export cannot be rolled back, so the job must remain quarantined with backups retained.
            writeQuarantineMarker(backups)
            try {
                writeTransactionState(backups, ReprocessTransactionState.QUARANTINED)
            } catch (e: Exception) {
                // State write failed; transaction remains unresolved.
                return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(e))
            }
            ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, committed)
        }
    }
    val error = outcome.terminalError ?: IllegalStateException("Reprocess worker failed.")
    // Rollback path. Order: restore files -> write failure/cancellation metadata -> delete backups.
    // Each metadata-write failure below converts a safe rollback into a quarantine rather than
    // releasing the lease with an incoherent job.
    return restoreBackups(jobDir, backups).fold(
        onSuccess = {
            val metadataError = try {
                if (outcome.disposition == ReprocessTerminalDisposition.CANCELLED) {
                    writeReprocessCancelled(jobDir, error.message)
                } else {
                    writeReprocessFailure(jobDir, "${error.javaClass.simpleName}: ${error.message}")
                }
                null
            } catch (metadataFailure: Exception) {
                metadataFailure
            }
            if (metadataError != null) {
                writeQuarantineMarker(backups)
                try {
                    writeTransactionState(backups, ReprocessTransactionState.QUARANTINED)
                } catch (e: Exception) {
                    // State write failed; transaction remains unresolved.
                    return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(e))
                }
                return@fold ReprocessFinalizationResult(
                    ReprocessFinalizationState.QUARANTINED,
                    Result.failure(metadataError)
                )
            }
            removeQuarantineMarker(backups)
            try {
                writeTransactionState(backups, ReprocessTransactionState.ROLLED_BACK)
            } catch (e: Exception) {
                // State write failed; transaction remains unresolved.
                return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(e))
            }
            val cleanupSuccess = deleteBackups(backups)
            if (!cleanupSuccess) {
                try {
                    KeplerJobMetadata.update(jobDir) {
                        it.put("reprocessWarning", "Reprocess backup cleanup failed after safe rollback.")
                    }
                } catch (_: Exception) { /* roll back already safe; warning best-effort */ }
            }
            ReprocessFinalizationResult(ReprocessFinalizationState.ROLLED_BACK, Result.failure(error))
        },
        onFailure = { rollbackError ->
            // Restoring the original files failed: job is incoherent. Quarantine and retain backups.
            writeQuarantineMarker(backups)
            try {
                writeTransactionState(backups, ReprocessTransactionState.QUARANTINED)
            } catch (e: Exception) {
                // State write failed; transaction remains unresolved.
                return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(e))
            }
            ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(rollbackError))
        }
    )
}

private fun finalizeReprocessOutcome(
    jobDir: File,
    jobKind: ReprocessJobKind,
    outputSettings: FinalOutputFormat,
    selectionMode: FrameSelectionMode,
    includedFrameIndices: Set<Int>,
    outcome: ReprocessWorkerOutcome,
    backups: List<ReprocessBackup>
): Result<KeplerReprocessResult> {
    val finalFile = outcome.finalOutputFile?.takeIf { it.isFile && it.length() > 0L }
    val previewFile = outcome.previewFile?.takeIf { it.isFile && it.length() > 0L } ?: finalFile
    if (outcome.result.isSuccess && !outcome.publicExportCommitted && finalFile?.isFile != true) {
        if (previewFile == null || previewFile == finalFile) {
            return try {
                restoreBackups(jobDir, backups).getOrThrow()
                Result.failure(IllegalStateException("Reprocess completed without a final output file."))
            } catch (rollbackError: Exception) {
                writeQuarantineMarker(backups)
                try {
                    writeTransactionState(backups, ReprocessTransactionState.QUARANTINED)
                } catch (e: Exception) {
                    return Result.failure(e)
                }
                Result.failure(rollbackError)
            }
        }
    }
    val bytes = outcome.bytesWritten.takeIf { it > 0L }
        ?: outcome.export?.fileSizeBytes?.takeIf { it > 0L }
        ?: finalFile?.length()
        ?: 0L
    val verifiedSuccess = outcome.result.isSuccess && outcome.exportVerified
    val publicOnlyWithoutPreview = verifiedSuccess && outcome.publicExportCommitted && finalFile == null && previewFile == null
    val displayFile = finalFile ?: previewFile
    val publicOutcome = outcome.publicOutcome
    val sidecarResult = publicOutcome?.sidecar ?: outcome.sidecar
    val postExportCancellation = outcome.postExportCancellationRequested
    val postExportWorkSkipped = outcome.postExportWorkSkipped
    val currentWarning = publicOutcome?.currentWarning
    try {
        if (verifiedSuccess && !publicOnlyWithoutPreview) {
            writeReprocessSuccess(
                jobDir, jobKind, includedFrameIndices.size, finalFile, previewFile,
                selectionMode, includedFrameIndices, outcome.export, outcome.exportVerified,
                outputSettings, sidecarResult, postExportCancellation, postExportWorkSkipped,
                currentWarning
            )
        } else if (publicOnlyWithoutPreview) {
            writeReprocessPartialPublicOnly(
                jobDir, jobKind, includedFrameIndices.size, outcome.export,
                outcome.exportVerified, outputSettings, outcome.terminalError?.message,
                sidecarResult, postExportCancellation, postExportWorkSkipped,
                currentWarning
            )
        } else {
            writeReprocessPartial(
                jobDir, jobKind, includedFrameIndices.size, finalFile, previewFile,
                selectionMode, includedFrameIndices,
                outcome.result.exceptionOrNull()?.message, outcome.export,
                outcome.exportVerified, outputSettings, sidecarResult,
                postExportCancellation, postExportWorkSkipped, currentWarning
            )
        }
    } catch (metadataFailure: Exception) {
        writeQuarantineMarker(backups)
        return Result.failure(metadataFailure)
    }
    try {
        clearReprocessCommitCheckpoint(jobDir)
    } catch (checkpointClearError: Exception) {
        writeQuarantineMarker(backups)
        return Result.failure(checkpointClearError)
    }
    // Transaction resolution and cleanup (remove marker, delete backups) are handled by
    // the shared finalizer [finalizeTerminalOutcome] after durable COMMITTED/ROLLED_BACK state is persisted.
    return Result.success(
        KeplerReprocessResult(jobDir, jobKind, displayFile, previewFile, bytes,
            listOfNotNull(
                "Reprocess backup cleanup deferred to finalizer",
                if (verifiedSuccess) null else "Public export committed; reprocess verification incomplete"
            ))
    )
}

internal suspend fun cancelWorkerAndAwaitTerminal(
    worker: ReprocessWorkerRun
): Result<ReprocessWorkerOutcome> = withContext(NonCancellable) {
    worker.cancel()
    val outcome = withTimeoutOrNull(REPROCESS_WORKER_EXIT_TIMEOUT_MS) { worker.terminal.await() }
    if (outcome == null) {
        Result.failure(ReprocessWorkerDidNotExitException("Reprocess worker did not exit before rollback timeout."))
    } else {
        Result.success(outcome)
    }
}

/** Writes a bounded preview for this reprocess operation. Never reuses an older preview. */
internal fun writeBoundedReprocessPreview(jobDir: File, source: Bitmap): File {
    val maxDimension = REPROCESS_PREVIEW_MAX_DIMENSION.coerceAtLeast(1)
    val scale = maxDimension.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1)
    val scaled = if (scale < 1f) {
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(source, width, height, true)
    } else source
    val preview = File(jobDir, "$REPROCESS_PREVIEW_PREFIX${System.currentTimeMillis()}.png")
    val temp = File(preview.parentFile, ".${preview.name}.${System.nanoTime()}.tmp")
    try {
        temp.write { output -> check(scaled.compress(Bitmap.CompressFormat.PNG, 92, output)) { "Reprocess preview compress failed." } }
        KeplerJobMetadata.atomicReplace(temp, preview)
    } catch (compressFailure: Exception) {
        if (temp.exists()) runCatching { temp.delete() }
        throw compressFailure
    } finally {
        if (temp.exists()) runCatching { temp.delete() }
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
    }
    return preview.takeIf { it.isFile && it.length() > 0L } ?: error("Reprocess preview write produced no file.")
}

private fun File.write(block: (java.io.OutputStream) -> Unit) {
    java.io.FileOutputStream(this).use { block(it) }
}

private fun reprocessBackupRoot(backups: List<ReprocessBackup>): File? =
    backups.firstOrNull()?.backup?.parentFile

/** Persist a quarantine marker in the transaction backup directory. Retains the backups.
 * Throws on failure — quarantine must be durable or the transaction is unresolved. */
internal fun writeQuarantineMarker(backups: List<ReprocessBackup>) {
    val root = reprocessBackupRoot(backups) ?: return
    if (!root.isDirectory) return
    val marker = File(root, REPROCESS_QUARANTINE_MARKER)
    if (marker.exists()) return
    KeplerJobMetadata.atomicWrite(marker, "quarantined\n")
}

/**
 * Atomically update the transaction manifest state. This is the single authoritative state writer.
 * Throws on any failure — state persistence is mandatory and must not be silently ignored.
 * Failure here leaves the transaction unresolved; caller must not release the operation lease.
 */
private fun writeTransactionState(transactionBackups: List<ReprocessBackup>, state: ReprocessTransactionState) {
    val root = reprocessBackupRoot(transactionBackups)
        ?: throw IllegalStateException("Transaction backup root not found for state write")
    val manifestFile = File(root, REPROCESS_TX_MANIFEST_FILE)
    if (!manifestFile.isFile) {
        throw IllegalStateException("Transaction manifest missing for state write: $state")
    }
    val manifest = ReprocessTransactionManifest.fromJson(JSONObject(manifestFile.readText()))
        .copy(state = state)
    KeplerJobMetadata.atomicWrite(manifestFile, manifest.toJson().toString(2))
}

/** Remove the quarantine marker only after a safe commit or rollback. */
internal fun removeQuarantineMarker(backups: List<ReprocessBackup>) {
    val root = reprocessBackupRoot(backups) ?: return
    val marker = File(root, REPROCESS_QUARANTINE_MARKER)
    if (marker.exists()) marker.delete()
}

/**
 * True when the job is marked quarantined by an unresolved reprocess finalization. Cleanup, deletion,
 * and stale recovery must refuse quarantined jobs so a half-written state is never destroyed.
 * Checks both the quarantine marker and the transaction manifest state.
 * Conservative: missing/corrupt/unknown-state manifests → unresolved (ACTIVE).
 */
internal fun isReprocessQuarantined(jobDir: File): Boolean {
    jobDir.listFiles()?.forEach { child ->
        if (child.isDirectory && child.name.startsWith(".reprocess_backup_")) {
            val marker = File(child, REPROCESS_QUARANTINE_MARKER)
            if (marker.exists()) return true
            // Check manifest state for durability
            val manifestFile = File(child, REPROCESS_TX_MANIFEST_FILE)
            if (manifestFile.isFile) {
                runCatching {
                    val json = JSONObject(manifestFile.readText())
                    val stateName = json.optString("state", "ACTIVE")
                    ReprocessTransactionState.valueOf(stateName)
                }.getOrNull()?.let { state ->
                    if (state == ReprocessTransactionState.QUARANTINED || state == ReprocessTransactionState.ACTIVE) return true
                }
            } else {
                // No manifest = unresolved (treat as ACTIVE)
                return true
            }
        }
    }
    return false
}

/**
 * Safe process-restart recovery for validated quarantined transactions. Called from
 * [loadKeplerGalleryJobs] for every job directory. If the quarantine marker exists but the
 * backup directory is empty (all backups were already deleted or the transaction completed
 * safely before the crash), the stale marker and empty backup root are removed so the job is
 * no longer treated as quarantined. If the backup directory still contains backup files, the
 * quarantine is preserved — manual intervention or a new reprocess is required.
 * Also checks manifest state: if state is COMMITTED or ROLLED_BACK, clean up by removing
 * all backup contents and the backup root.
 */
internal fun recoverValidatedQuarantine(jobDir: File) {
    if (KeplerJobMetadata.isOperationActive(jobDir)) return
    jobDir.listFiles()?.forEach { child ->
        if (child.isDirectory && child.name.startsWith(".reprocess_backup_")) {
            val marker = File(child, REPROCESS_QUARANTINE_MARKER)
            val manifestFile = File(child, REPROCESS_TX_MANIFEST_FILE)
            var manifestState: ReprocessTransactionState? = null
            if (manifestFile.isFile) {
                manifestState = runCatching {
                    val json = JSONObject(manifestFile.readText())
                    ReprocessTransactionState.valueOf(json.optString("state", "ACTIVE"))
                }.getOrNull()
            }
            val remaining = child.listFiles()?.toList().orEmpty()
            val hasBackupFiles = remaining.any { it.isFile && it.name != REPROCESS_QUARANTINE_MARKER && it.name != REPROCESS_TX_MANIFEST_FILE }
            val isResolved = manifestState == ReprocessTransactionState.COMMITTED || manifestState == ReprocessTransactionState.ROLLED_BACK
            if (!hasBackupFiles || isResolved) {
                // Remove all contents (backup files, manifest, marker) then the root directory
                child.listFiles()?.forEach { it.delete() }
                marker.delete()
                if (child.exists()) child.delete()
            }
        }
    }
}

fun detectReprocessCapability(context: Context, jobDir: File): ReprocessCapability {
    val target = runCatching { requireReprocessSafeJobDirectory(context, jobDir) }.getOrNull()
        ?: return ReprocessCapability(
            canReprocess = false,
            jobKind = ReprocessJobKind.UNSUPPORTED,
            reason = "지원하지 않는 작업 유형입니다.",
            sourceFrameCount = 0,
            finalOutputExists = false,
            sourceFramesAvailable = false
        )
    if (isReprocessQuarantined(target)) {
        return ReprocessCapability(
            canReprocess = false,
            jobKind = ReprocessJobKind.UNSUPPORTED,
            reason = "다시 합성을 진행할 수 없습니다. 이 작업은 격리되었습니다.",
            sourceFrameCount = 0,
            finalOutputExists = false,
            sourceFramesAvailable = false
        )
    }
    val job = try {
        KeplerJobMetadata.read(target)
    } catch (metadataError: KeplerJobMetadataException) {
        return ReprocessCapability(
            canReprocess = false,
            jobKind = ReprocessJobKind.UNSUPPORTED,
            reason = when (metadataError) {
                is KeplerJobMetadataMissing -> "Job metadata is missing."
                is KeplerJobMetadataCorrupt -> "Job metadata is corrupt and cannot be read."
            },
            sourceFrameCount = 0,
            finalOutputExists = false,
            sourceFramesAvailable = false
        )
    }
    val kind = detectJobKind(target, job)
    val finalExists = finalOutputCandidates(target, job).any { it.isFile && it.length() > 0L }
    val sourceCount = countActualSourceFrames(target, job, kind)
    val sourceAvailable = sourceCount > 0
    return when (kind) {
        ReprocessJobKind.RAW_FUSION -> {
            val canRun = sourceCount >= MIN_RAW_FUSION_FRAMES
            ReprocessCapability(
                canReprocess = canRun,
                jobKind = kind,
                reason = if (canRun) {
                    "RAW 원본 프레임으로 다시 합성할 수 있습니다."
                } else {
                    "원본 프레임이 부족하여 다시 합성할 수 없습니다."
                },
                sourceFrameCount = sourceCount,
                finalOutputExists = finalExists,
                sourceFramesAvailable = sourceAvailable
            )
        }
        ReprocessJobKind.YUV_FUSION -> {
            val canRun = sourceCount >= 2
            ReprocessCapability(
                canReprocess = canRun,
                jobKind = kind,
                reason = if (canRun) {
                    "YUV 원본 프레임으로 다시 합성할 수 있습니다."
                } else {
                    "원본 프레임이 부족하여 다시 합성할 수 없습니다."
                },
                sourceFrameCount = sourceCount,
                finalOutputExists = finalExists,
                sourceFramesAvailable = sourceAvailable
            )
        }
        ReprocessJobKind.COLOR_BURST -> ReprocessCapability(
            canReprocess = false,
            jobKind = kind,
            reason = "ColorBurst 다시 합성은 아직 지원되지 않습니다.",
            sourceFrameCount = sourceCount,
            finalOutputExists = finalExists,
            sourceFramesAvailable = sourceAvailable
        )
        ReprocessJobKind.UNSUPPORTED -> ReprocessCapability(
            canReprocess = false,
            jobKind = kind,
            reason = "지원하지 않는 작업 유형입니다.",
            sourceFrameCount = sourceCount,
            finalOutputExists = finalExists,
            sourceFramesAvailable = sourceAvailable
        )
    }
}

internal fun requireReprocessSafeJobDirectory(context: Context, jobDirectory: File): File {
    val target = jobDirectory.canonicalFile
    require(target.isDirectory) { "Job directory no longer exists." }
    val allowed = reprocessSafeRoots(context).any { root ->
        target.parentFile == root.canonicalFile && matchesReprocessJobPrefix(root, target.name)
    }
    require(allowed) { "Refusing to modify directory outside known Kepler job roots." }
    return target
}

private fun reprocessSafeRoots(context: Context): List<File> {
    val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return emptyList()
    return listOf(
        File(pictures, "KeplerRawFusion"),
        File(pictures, "KeplerYuvFusion"),
        File(pictures, "KeplerColorBurst"),
        File(pictures, "KeplerSuperRes")
    )
}

private fun matchesReprocessJobPrefix(root: File, name: String): Boolean = when (root.name) {
    "KeplerRawFusion" -> name.startsWith("KPL_RAW_FUSION_")
    "KeplerYuvFusion" -> name.startsWith("KPL_YUV_FUSION_")
    "KeplerColorBurst" -> name.startsWith("KPL_COLOR_BURST_")
    "KeplerSuperRes" -> name.startsWith("KPL_SUPER_RES_")
    else -> false
}

internal fun detectJobKind(jobDir: File, job: JSONObject): ReprocessJobKind {
    val rawType = job.optString("jobType").uppercase(Locale.US)
    return when {
        rawType == "RAW_NIGHT_FUSION" || jobDir.name.startsWith("KPL_RAW_FUSION_") -> ReprocessJobKind.RAW_FUSION
        rawType == "YUV_NIGHT_FUSION" || jobDir.name.startsWith("KPL_YUV_FUSION_") -> ReprocessJobKind.YUV_FUSION
        jobDir.name.startsWith("KPL_COLOR_BURST_") -> ReprocessJobKind.COLOR_BURST
        else -> ReprocessJobKind.UNSUPPORTED
    }
}

private fun countActualSourceFrames(jobDir: File, job: JSONObject, kind: ReprocessJobKind): Int {
    val frames = job.optJSONArray("frames")
    val fromMetadata = buildSet {
        if (frames != null) {
            repeat(frames.length()) { index ->
                val frame = frames.optJSONObject(index) ?: return@repeat
                val name = when (kind) {
                    ReprocessJobKind.RAW_FUSION -> frame.optString("raw16File")
                        .ifBlank { frame.optString("dngFile") }
                        .ifBlank { frame.optString("file") }
                    ReprocessJobKind.YUV_FUSION, ReprocessJobKind.COLOR_BURST -> frame.optString("file")
                        .ifBlank { frame.optString("yuvFile") }
                        .ifBlank { frame.optString("nv21File") }
                    ReprocessJobKind.UNSUPPORTED -> frame.optString("file")
                }
                if (
                    name.isNotBlank() &&
                    frame.optBoolean("enabled", true) &&
                    !frame.optBoolean("excludedByUser", false)
                ) {
                    File(jobDir, name).takeIf { it.isFile && isReprocessSourceFrame(it, kind) }?.let { add(it.canonicalFile) }
                }
            }
        }
    }
    if (fromMetadata.isNotEmpty()) return fromMetadata.size
    return jobDir.listFiles()
        ?.count { it.isFile && isReprocessSourceFrame(it, kind) }
        ?: 0
}

internal fun isReprocessSourceFrame(file: File, kind: ReprocessJobKind): Boolean {
    val name = file.name.lowercase(Locale.US)
    if (!name.startsWith("frame_")) return false
    return when (kind) {
        ReprocessJobKind.RAW_FUSION -> name.endsWith(".raw16") || name.endsWith(".dng")
        ReprocessJobKind.YUV_FUSION -> name.endsWith(".png") || name.endsWith(".yuv") ||
            name.endsWith(".nv21") || name.endsWith(".yuv420")
        ReprocessJobKind.COLOR_BURST -> name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
        ReprocessJobKind.UNSUPPORTED -> false
    }
}

internal fun loadJobJsonSafe(jobDir: File): JSONObject =
    File(jobDir, JOB_JSON_FILE_NAME).takeIf { it.isFile }?.let {
        runCatching { KeplerJobMetadata.read(jobDir) }.getOrNull()
    } ?: JSONObject()

private fun finalOutputCandidates(jobDir: File, job: JSONObject): List<File> {
    val names = listOf(
        job.optString("finalNightFusionFile"),
        job.optString("finalFile"),
        job.optString("outputFile"),
        job.optString("galleryDisplayFile"),
        "raw_fusion_final.png",
        "sharpened_night_fusion.png"
    ).filter { it.isNotBlank() && it != "null" }.distinct()
    return names.map { File(jobDir, it) }
        .filter { it.extension.lowercase(Locale.US) in setOf("png", "jpg", "jpeg", "heic", "webp") }
}

private fun resolveReprocessFinalOutput(jobDir: File, job: JSONObject): File? =
    finalOutputCandidates(jobDir, job)
        .firstOrNull { it.isFile && it.length() > 0L }

internal data class ReprocessBackup(
    val original: File,
    val backup: File,
    val existingNames: Set<String> = emptySet(),
    val originalLength: Long = backup.length()
)

private const val REPROCESS_TX_MANIFEST_FILE = "manifest.json"

/**
 * Transaction manifest recording the job state before the reprocess. Records which relative paths
 * existed before the transaction, which were backed up (mutable outputs/metadata only — never
 * immutable source frames), and which paths were newly created by the transaction.
 * Durable state (ACTIVE/QUARANTINED/COMMITTED/ROLLED_BACK) survives process restarts.
 */
internal enum class ReprocessTransactionState {
    ACTIVE,         // Transaction created, finalization not yet resolved
    QUARANTINED,    // Finalization failed, needs manual intervention
    COMMITTED,      // Successfully committed (verified or public export committed)
    ROLLED_BACK     // Safely rolled back
}

internal data class ReprocessTransactionManifest(
    val transactionId: String,
    val createdAt: Long,
    val preExistingPaths: Set<String>,
    val backedUpPaths: Set<String>,
    val newlyCreatedPaths: MutableSet<String> = mutableSetOf(),
    val state: ReprocessTransactionState = ReprocessTransactionState.ACTIVE
) {
    fun isPreExisting(relativePath: String): Boolean = relativePath in preExistingPaths
    fun isBackedUp(relativePath: String): Boolean = relativePath in backedUpPaths
    fun isNewlyCreated(relativePath: String): Boolean = relativePath in newlyCreatedPaths

    fun recordNewlyCreated(relativePath: String) {
        if (relativePath !in preExistingPaths && relativePath !in backedUpPaths) {
            newlyCreatedPaths.add(relativePath)
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("transactionId", transactionId)
        .put("createdAt", createdAt)
        .put("preExistingPaths", JSONArray(preExistingPaths.sorted()))
        .put("backedUpPaths", JSONArray(backedUpPaths.sorted()))
        .put("newlyCreatedPaths", JSONArray(newlyCreatedPaths.sorted()))
        .put("state", state.name)

    companion object {
        fun fromJson(json: JSONObject): ReprocessTransactionManifest = ReprocessTransactionManifest(
            transactionId = json.optString("transactionId"),
            createdAt = json.optLong("createdAt"),
            preExistingPaths = json.optJSONArray("preExistingPaths")?.toStringSet().orEmpty(),
            backedUpPaths = json.optJSONArray("backedUpPaths")?.toStringSet().orEmpty(),
            newlyCreatedPaths = (json.optJSONArray("newlyCreatedPaths")?.toStringSet().orEmpty()).toMutableSet(),
            state = ReprocessTransactionState.valueOf(json.optString("state", "ACTIVE"))
        )

        private fun JSONArray?.toStringSet(): Set<String> = buildSet {
            if (this@toStringSet == null) return@buildSet
            repeat(length()) { i -> add(optString(i)) }
        }
    }
}

internal data class ReprocessTransaction(
    val transactionId: String,
    val backupRoot: File,
    val manifest: ReprocessTransactionManifest,
    val backups: List<ReprocessBackup>
)

/** True if the file is an immutable source frame that must never be backed up by reprocess transactions. */
private fun isImmutableSourceFrame(file: File): Boolean {
    val name = file.name.lowercase(Locale.US)
    if (!name.startsWith("frame_")) return false
    return name.endsWith(".raw16") || name.endsWith(".dng") ||
        name.endsWith(".yuv") || name.endsWith(".nv21") || name.endsWith(".yuv420")
}

/** True if the file is a mutable output or metadata that the reprocess worker may overwrite. */
internal fun isReprocessWorkerWritable(file: File): Boolean = !isImmutableSourceFrame(file)

internal fun backupReprocessTransaction(jobDir: File, files: List<File>): Result<ReprocessTransaction> {
    val transactionId = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    val root = File(jobDir, ".reprocess_backup_$transactionId")
    return runCatching {
        check(root.mkdirs()) { "Could not create reprocess backup directory." }
        val metadata = File(jobDir, JOB_JSON_FILE_NAME)
        check(metadata.isFile) { "job.json is required for rollback." }
        val preExistingNames = jobDir.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet().orEmpty()
        val filesToBackup = (files + metadata)
            .asSequence()
            .filter { it.isFile }
            .map { it.canonicalFile }
            .distinctBy { it.path }
            .filter { isReprocessWorkerWritable(it) }
            .toList()
        val backups = filesToBackup.map { original ->
            val backup = File(root, original.name)
            original.copyTo(backup, overwrite = false)
            check(backup.isFile && backup.length() == original.length()) { "Backup verification failed for ${original.name}" }
            ReprocessBackup(original, backup, preExistingNames, original.length())
        }
        val manifest = ReprocessTransactionManifest(
            transactionId = transactionId,
            createdAt = System.currentTimeMillis(),
            preExistingPaths = preExistingNames,
            backedUpPaths = backups.map { it.original.name }.toSet()
        )
        KeplerJobMetadata.atomicWrite(File(root, REPROCESS_TX_MANIFEST_FILE), manifest.toJson().toString(2))
        ReprocessTransaction(transactionId, root, manifest, backups)
    }.onFailure {
        root.listFiles()?.forEach { it.delete() }
        root.delete()
    }
}

internal fun restoreBackups(jobDir: File, backups: List<ReprocessBackup>): Result<Unit> = runCatching {
    val root = backups.firstOrNull()?.backup?.parentFile
    if (root == null || !root.isDirectory) return@runCatching
    val manifestFile = File(root, REPROCESS_TX_MANIFEST_FILE)
    val manifest = if (manifestFile.isFile) {
        runCatching { ReprocessTransactionManifest.fromJson(JSONObject(manifestFile.readText())) }.getOrNull()
    } else null
    backups.forEach { backup ->
        check(backup.backup.isFile) { "Missing rollback backup: ${backup.original.name}" }
        check(backup.backup.length() == backup.originalLength) {
            "Invalid rollback backup: ${backup.original.name}"
        }
    }
    val preExisting = manifest?.preExistingPaths ?: backups.firstOrNull()?.existingNames.orEmpty()
    val backupNames = backups.map { it.original.name }.toSet()
    val backupRootName = root.name
    jobDir.listFiles()?.filter { it.isFile && it.name !in preExisting && it.name !in backupNames && it.name != backupRootName }?.forEach { runCatching { it.delete() } }
    backups.forEach { backup ->
        val target = backup.original
        if (target.isFile && target.length() == backup.backup.length()) {
            return@forEach
        }
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.restore")
        try {
            backup.backup.copyTo(temp, overwrite = false)
            check(temp.length() == backup.backup.length()) { "Rollback temp verification failed: ${target.name}" }
            KeplerJobMetadata.atomicReplace(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}

internal fun deleteBackups(backups: List<ReprocessBackup>): Boolean {
    val root = backups.firstOrNull()?.backup?.parentFile ?: return true
    removeQuarantineMarker(backups)
    backups.forEach { backup -> if (backup.backup.exists() && !backup.backup.delete()) return false }
    val manifest = File(root, REPROCESS_TX_MANIFEST_FILE)
    if (manifest.exists() && !manifest.delete()) return false
    return !root.exists() || root.delete()
}

private fun writeReprocessSuccess(
    jobDir: File,
    jobKind: ReprocessJobKind,
    sourceFrameCount: Int,
    finalOutputFile: File?,
    previewFile: File?,
    selectionMode: FrameSelectionMode,
    includedFrameIndices: Set<Int>,
    export: GalleryExportResult?,
    exportVerified: Boolean,
    outputSettings: FinalOutputFormat,
    sidecarResult: RawSidecarExportResult? = null,
    postExportCancellationRequested: Boolean = false,
    postExportWorkSkipped: Boolean = false,
    currentWarning: String? = null
) {
    KeplerJobMetadata.update(jobDir) { job ->
    job.put("status", "COMPLETE")
        .put("processStatus", "REPROCESS_COMPLETE")
        .put("currentPipelineStage", "COMPLETE")
        .put("reprocessStatus", "COMPLETE")
        .put("reprocessAt", nowIso8601())
        .put("reprocessEngine", when (jobKind) {
            ReprocessJobKind.RAW_FUSION -> "raw_fusion_reprocess_v1"
            ReprocessJobKind.YUV_FUSION -> "yuv_fusion_reprocess_v1"
            ReprocessJobKind.COLOR_BURST -> "color_burst_reprocess_v1"
            ReprocessJobKind.UNSUPPORTED -> "unsupported"
        })
        .put("frameSelectionMode", selectionMode.name)
        .put("includedFrameIndices", JSONArray(includedFrameIndices.sorted()))
        .put("reprocessSourceFrameCount", sourceFrameCount)
        .put("finalOutputAvailable", finalOutputFile?.isFile == true)
        .put("galleryVisible", true)
        .put("galleryDisplayUnavailable", false)
        .put("canReprocess", sourceFrameCount > 0)
        .put("finalOutputFormatSetting", outputSettings.name)
        .put("exportStatus", if (export == null) "NOT_EXPORTED" else "EXPORTED")
        .put("exportVerified", exportVerified)
        .put("galleryExportCommitted", export?.success == true && !export.uriString.isNullOrBlank())
        .put("exportUri", export?.uriString ?: JSONObject.NULL)
        .put("exportDisplayName", export?.displayName ?: JSONObject.NULL)
        .put("exportMimeType", export?.mimeType ?: JSONObject.NULL)
        .put("exportFileSizeBytes", export?.fileSizeBytes ?: 0L)
        .put("postExportCancellationRequested", postExportCancellationRequested)
        .put("postExportWorkSkipped", postExportWorkSkipped)
        .put("rawSidecarRequested", outputSettings.shouldExportRawSidecar)
        .put("rawSidecarExportStatus", when {
            sidecarResult == null && outputSettings.shouldExportRawSidecar -> "SKIPPED"
            sidecarResult == null -> "NOT_REQUESTED"
            else -> sidecarResult.status
        })
        .put("rawSidecarExportedFiles", JSONArray(sidecarResult?.exportedFiles ?: emptyList<String>()))
        .put("rawSidecarError", sidecarResult?.errorMessage ?: JSONObject.NULL)
        .put("reprocessError", JSONObject.NULL)
        .put("reprocessWarning", currentWarning ?: JSONObject.NULL)
    if (currentWarning != null) {
        val previousWarnings = job.optJSONArray("reprocessWarnings") ?: JSONArray().also { job.put("reprocessWarnings", it) }
        previousWarnings.put(currentWarning)
    }
    if (job.optString("cleanupType") == "SOURCE_ONLY") {
        job.put("cleanupType", "REPROCESSED_FROM_SOURCE_ONLY")
    }
    finalOutputFile?.let {
        job.put("galleryDisplayFile", it.name)
            .put("galleryThumbnailFile", previewFile?.name ?: it.name)
    } ?: run {
        val previewName = previewFile?.takeIf { it.isFile }?.name
        if (previewName != null) {
            job.put("galleryDisplayFile", previewName)
                .put("galleryThumbnailFile", previewName)
                .remove("galleryDisplaySource")
        } else {
            job.remove("galleryDisplayFile")
            job.remove("galleryThumbnailFile")
            job.remove("galleryDisplaySource")
        }
    }
    putReprocessAvailability(jobDir, job, sourceFrameCount, finalOutputFile)
    recordReprocessTerminalMetadata(job, "COMPLETE", null)
    }
}

private fun writeReprocessFailure(jobDir: File, error: String) {
    KeplerJobMetadata.update(jobDir) {
        it.put("reprocessStatus", "FAILED")
            .put("reprocessError", error)
            .put("reprocessAt", nowIso8601())
        recordReprocessTerminalMetadata(it, "FAILED", error)
    }
}

private fun writeReprocessCancelled(jobDir: File, error: String?) {
    val message = error ?: "Reprocess cancelled"
    KeplerJobMetadata.update(jobDir) {
        it.put("reprocessStatus", "CANCELLED")
            .put("reprocessError", message)
            .put("reprocessAt", nowIso8601())
        recordReprocessTerminalMetadata(it, "CANCELLED", message)
    }
}

private fun writeReprocessPartial(
    jobDir: File,
    jobKind: ReprocessJobKind,
    sourceFrameCount: Int,
    finalOutputFile: File?,
    previewFile: File?,
    selectionMode: FrameSelectionMode,
    includedFrameIndices: Set<Int>,
    error: String?,
    export: GalleryExportResult?,
    exportVerified: Boolean,
    outputSettings: FinalOutputFormat,
    sidecarResult: RawSidecarExportResult? = null,
    postExportCancellationRequested: Boolean = false,
    postExportWorkSkipped: Boolean = false,
    currentWarning: String? = null
) {
    KeplerJobMetadata.update(jobDir) { job ->
    job.put("processStatus", "REPROCESS_PARTIAL")
        .put("status", "PARTIAL")
        .put("currentPipelineStage", "PARTIAL")
        .put("reprocessStatus", "PARTIAL")
        .put("reprocessAt", nowIso8601())
        .put("reprocessEngine", jobKind.name)
        .put("frameSelectionMode", selectionMode.name)
        .put("includedFrameIndices", JSONArray(includedFrameIndices.sorted()))
        .put("reprocessSourceFrameCount", sourceFrameCount)
        .put("reprocessError", error ?: "Public export committed but worker verification failed")
        .put("finalOutputAvailable", finalOutputFile?.isFile == true)
        .put("galleryVisible", finalOutputFile?.isFile == true)
        .put("galleryDisplayUnavailable", finalOutputFile?.isFile != true)
        .put("finalOutputFormatSetting", outputSettings.name)
        .put("exportStatus", if (exportVerified) "EXPORTED" else "EXPORT_UNVERIFIED")
        .put("exportVerified", exportVerified)
        .put("galleryExportCommitted", export?.success == true && !export?.uriString.isNullOrBlank())
        .put("exportUri", export?.uriString ?: JSONObject.NULL)
        .put("exportDisplayName", export?.displayName ?: JSONObject.NULL)
        .put("exportMimeType", export?.mimeType ?: JSONObject.NULL)
        .put("exportFileSizeBytes", export?.fileSizeBytes ?: 0L)
        .put("postExportCancellationRequested", postExportCancellationRequested)
        .put("postExportWorkSkipped", postExportWorkSkipped)
        .put("rawSidecarRequested", outputSettings.shouldExportRawSidecar)
        .put("rawSidecarExportStatus", when {
            sidecarResult == null && outputSettings.shouldExportRawSidecar -> "SKIPPED"
            sidecarResult == null -> "NOT_REQUESTED"
            else -> sidecarResult.status
        })
        .put("rawSidecarExportedFiles", JSONArray(sidecarResult?.exportedFiles ?: emptyList<String>()))
        .put("rawSidecarError", sidecarResult?.errorMessage ?: JSONObject.NULL)
        .put("reprocessWarning", currentWarning ?: JSONObject.NULL)
    if (currentWarning != null) {
        val previousWarnings = job.optJSONArray("reprocessWarnings") ?: JSONArray().also { job.put("reprocessWarnings", it) }
        previousWarnings.put(currentWarning)
    }
    finalOutputFile?.let { job.put("galleryDisplayFile", it.name).put("galleryThumbnailFile", previewFile?.name ?: it.name) }
        ?: run {
            val previewName = previewFile?.takeIf { it.isFile }?.name
            if (previewName != null) {
                job.put("galleryDisplayFile", previewName)
                    .put("galleryThumbnailFile", previewName)
                    .put("galleryVisible", true)
                    .put("galleryDisplayUnavailable", false)
                    .remove("galleryDisplaySource")
            } else {
                job.remove("galleryDisplayFile")
                job.remove("galleryThumbnailFile")
                job.remove("galleryDisplaySource")
            }
        }
    putReprocessAvailability(jobDir, job, sourceFrameCount, finalOutputFile)
    recordReprocessTerminalMetadata(job, "PARTIAL", error ?: "Public export committed but worker verification failed")
    }
}

/** Previewless public-only commit: verified export, no current local preview. Not gallery-visible. */
private fun writeReprocessPartialPublicOnly(
    jobDir: File,
    jobKind: ReprocessJobKind,
    sourceFrameCount: Int,
    export: GalleryExportResult?,
    exportVerified: Boolean,
    outputSettings: FinalOutputFormat,
    error: String?,
    sidecarResult: RawSidecarExportResult? = null,
    postExportCancellationRequested: Boolean = false,
    postExportWorkSkipped: Boolean = false,
    currentWarning: String? = null
) {
    KeplerJobMetadata.update(jobDir) { job ->
        job.put("processStatus", "PARTIAL_PUBLIC_ONLY")
            .put("status", "PARTIAL")
            .put("currentPipelineStage", "PARTIAL")
            .put("reprocessStatus", "PARTIAL_PUBLIC_ONLY")
            .put("reprocessAt", nowIso8601())
            .put("reprocessEngine", jobKind.name)
            .put("reprocessSourceFrameCount", sourceFrameCount)
            .put("finalOutputAvailable", false)
            .put("galleryVisible", false)
            .put("galleryDisplayUnavailable", true)
            .put("finalOutputFormatSetting", outputSettings.name)
            .put("exportStatus", "EXPORTED")
            .put("exportVerified", exportVerified)
            .put("galleryExportCommitted", export?.success == true && !export.uriString.isNullOrBlank())
            .put("exportUri", export?.uriString ?: JSONObject.NULL)
            .put("exportDisplayName", export?.displayName ?: JSONObject.NULL)
            .put("exportMimeType", export?.mimeType ?: JSONObject.NULL)
            .put("exportFileSizeBytes", export?.fileSizeBytes ?: 0L)
            .put("postExportCancellationRequested", postExportCancellationRequested)
            .put("postExportWorkSkipped", postExportWorkSkipped)
            .put("rawSidecarRequested", outputSettings.shouldExportRawSidecar)
            .put("rawSidecarExportStatus", when {
                sidecarResult == null && outputSettings.shouldExportRawSidecar -> "SKIPPED"
                sidecarResult == null -> "NOT_REQUESTED"
                else -> sidecarResult.status
            })
            .put("rawSidecarExportedFiles", JSONArray(sidecarResult?.exportedFiles ?: emptyList<String>()))
            .put("rawSidecarError", sidecarResult?.errorMessage ?: JSONObject.NULL)
            .put("reprocessError", JSONObject.NULL)
            .put("reprocessWarning", currentWarning ?: JSONObject.NULL)
        if (currentWarning != null) {
            val previousWarnings = job.optJSONArray("reprocessWarnings") ?: JSONArray().also { job.put("reprocessWarnings", it) }
            previousWarnings.put(currentWarning)
        } else {
            job.remove("reprocessWarning")
        }
        job.remove("galleryDisplayFile")
        job.remove("galleryThumbnailFile")
        job.remove("galleryDisplaySource")
        putReprocessAvailability(jobDir, job, sourceFrameCount, null)
        recordReprocessTerminalMetadata(job, "PARTIAL_PUBLIC_ONLY", error)
    }
}

private fun putReprocessAvailability(
    jobDir: File,
    job: JSONObject,
    sourceFrameCount: Int,
    finalOutputFile: File?
) {
    val debugAvailable = jobDir.walkTopDown().any { file ->
        file.isFile && file.name != JOB_JSON_FILE_NAME && file.name.lowercase(Locale.US).let { name ->
            name.contains("debug") || name.contains("compare") || name.endsWith(".log")
        }
    }
    job.put("sourceFramesAvailable", sourceFrameCount > 0)
        .put("debugFilesAvailable", debugAvailable)
        .put("finalOutputAvailable", finalOutputFile?.isFile == true)
}

/**
 * Records a completed reprocess attempt: increments the reprocess count and appends a history entry.
 * On success, stale reprocess/export errors are cleared so the gallery does not surface old failures.
 * Called inside each terminal [KeplerJobMetadata.update]; timestamps use job"updatedAt" for ordering.
 */
private fun recordReprocessTerminalMetadata(
    job: JSONObject,
    reprocessStatus: String,
    error: String?
) {
    val succeeded = reprocessStatus == "COMPLETE"
    val previousCount = job.optInt("reprocessCount", 0)
    job.put("reprocessCount", previousCount + 1)
        .put("reprocessLastAt", nowIso8601())
    val history = job.optJSONArray("reprocessHistory") ?: JSONArray().also { job.put("reprocessHistory", it) }
    val entry = JSONObject()
        .put("at", nowIso8601())
        .put("status", reprocessStatus)
        .put("error", error ?: JSONObject.NULL)
    history.put(entry)
    while (history.length() > REPROCESS_HISTORY_LIMIT) history.remove(0)
    if (succeeded) {
        job.remove("reprocessError")
        job.remove("exportError")
        job.remove("staleReprocessError")
        job.remove("staleExportError")
    }
}

private fun nowIso8601(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())

private suspend fun resolveFrameSelection(
    jobDir: File,
    kind: ReprocessJobKind,
    frames: List<KeplerFrameReviewItem>,
    explicitSelection: Set<Int>?
): Result<Set<Int>> = runCatching {
    if (explicitSelection != null) {
        return@runCatching explicitSelection
            .filter { index -> frames.any { it.index == index && it.file.isFile && it.file.length() > 0L } }
            .toSet()
    }

    val job = loadJobJsonSafe(jobDir)
    val persisted = persistedIncludedFrameIndices(job)
        .filter { index -> frames.any { it.index == index && it.file.isFile && it.file.length() > 0L } }
        .toSet()
    if (persisted.isNotEmpty()) return@runCatching persisted

    val recommendation = RuleBasedFrameSelectionAdvisor().recommend(null, frames)
    val recommended = recommendation.includedFrameIndices
        .filter { index -> frames.any { it.index == index && it.file.isFile && it.file.length() > 0L } }
        .toSet()
    if (recommended.size >= requiredSelectedFrameCount(kind)) {
        recommended
    } else {
        frames.filter { it.file.isFile && it.file.length() > 0L }
            .sortedByDescending { it.quality?.overallScore ?: 0.5f }
            .take(requiredSelectedFrameCount(kind))
            .map { it.index }
            .toSet()
    }
}

private fun resolveSelectionMode(job: JSONObject, explicitSelection: Set<Int>?): FrameSelectionMode {
    return persistedFrameSelectionMode(job)
        ?: if (explicitSelection != null) FrameSelectionMode.MANUAL else FrameSelectionMode.AUTO_RULE_BASED
}

private fun requiredSelectedFrameCount(kind: ReprocessJobKind): Int = when (kind) {
    ReprocessJobKind.RAW_FUSION -> MIN_RAW_FUSION_FRAMES
    ReprocessJobKind.YUV_FUSION -> 2
    ReprocessJobKind.COLOR_BURST,
    ReprocessJobKind.UNSUPPORTED -> Int.MAX_VALUE
}
