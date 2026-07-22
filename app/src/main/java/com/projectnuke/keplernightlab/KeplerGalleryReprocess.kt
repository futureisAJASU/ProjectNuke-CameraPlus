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

/**
 * Result of classifying a transaction manifest's state for evidence inspection.
 * Only [Resolved] allows cleanup; all other states block job mutation/deletion.
 */
internal sealed class ManifestClassification {
    data object Unresolved : ManifestClassification()
    data class Resolved(val state: ReprocessTransactionState) : ManifestClassification()
}

/**
 * Classify a backup root's transaction manifest. Fails closed:
 * - Missing/corrupt/unreadable/incomplete manifests → Unresolved
 * - Legacy manifests missing only "state" key → Unresolved (ACTIVE)
 * - Only fully validated COMMITTED or ROLLED_BACK → Resolved
 * - Valid QUARANTINED/ACTIVE manifests → Unresolved
 */
internal fun classifyTransactionManifest(backupRoot: File): ManifestClassification {
    if (!backupRoot.isDirectory) return ManifestClassification.Unresolved
    val manifestFile = File(backupRoot, REPROCESS_TX_MANIFEST_FILE)
    if (!manifestFile.isFile) return ManifestClassification.Unresolved
    val json = runCatching { JSONObject(manifestFile.readText()) }.getOrNull() ?: return ManifestClassification.Unresolved
    if (!json.has("transactionId") || !json.has("createdAt")) return ManifestClassification.Unresolved
    val stateName = json.optString("state", null) ?: return ManifestClassification.Unresolved
    val state = runCatching { ReprocessTransactionState.valueOf(stateName) }.getOrNull()
        ?: return ManifestClassification.Unresolved
    return when (state) {
        ReprocessTransactionState.COMMITTED,
        ReprocessTransactionState.ROLLED_BACK -> ManifestClassification.Resolved(state)
        ReprocessTransactionState.ACTIVE,
        ReprocessTransactionState.QUARANTINED -> ManifestClassification.Unresolved
    }
}

/**
 * Validate monotonic state transitions. Throws on illegal transitions.
 * Allowed: ACTIVE → any, QUARANTINED → any, COMMITTED → COMMITTED only, ROLLED_BACK → ROLLED_BACK only.
 */
private fun validateStateTransition(current: ReprocessTransactionState, target: ReprocessTransactionState) {
    val allowed = when (current) {
        ReprocessTransactionState.ACTIVE -> setOf(
            ReprocessTransactionState.QUARANTINED, ReprocessTransactionState.COMMITTED, ReprocessTransactionState.ROLLED_BACK
        )
        ReprocessTransactionState.QUARANTINED -> setOf(
            ReprocessTransactionState.QUARANTINED, ReprocessTransactionState.COMMITTED, ReprocessTransactionState.ROLLED_BACK
        )
        ReprocessTransactionState.COMMITTED -> setOf(ReprocessTransactionState.COMMITTED)
        ReprocessTransactionState.ROLLED_BACK -> setOf(ReprocessTransactionState.ROLLED_BACK)
    }
    require(target in allowed) {
        "Illegal state transition: $current → $target"
    }
}

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
    var retainLease = false
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
    retainLease = true

    postProgress("프레임 선택 적용 중…")
    saveFrameSelectionInternal(
        jobDir = target,
        mode = selectionMode,
        frames = applyFrameSelectionToItems(reviewItems, resolvedSelection, selectionMode),
        operationLease = operationLease
    ).getOrElse {
        return@withContext settleAndFail(transaction, operationLease, it)
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
        val outcome = cleanup.getOrNull()
        if (outcome != null) {
            finalizeTransactionWithLease(
                transaction, operationLease,
                target, capability.jobKind, outputSettings, selectionMode,
                resolvedSelection, Result.success(outcome)
            )
        } else {
            val didNotExit = cleanup.exceptionOrNull() is ReprocessWorkerDidNotExitException
            if (didNotExit) writeQuarantineMarker(transaction)
            registerLateFinalization(worker, transaction, operationLease, target, capability, outputSettings, selectionMode, resolvedSelection)
        }
        retainLease = false
        throw callerCancellation
    }
    val terminalOutcome = if (pipelineOutcome == null) {
        cancelWorkerAndAwaitTerminal(worker)
    } else {
        Result.success(pipelineOutcome)
    }
    val finalization = finalizeTransactionWithLease(
        transaction, operationLease,
        target, capability.jobKind, outputSettings, selectionMode,
        resolvedSelection, terminalOutcome
    )
    retainLease = false
    if (finalization.state == ReprocessFinalizationState.QUARANTINED && terminalOutcome.isFailure) {
        registerLateFinalization(worker, transaction, operationLease, target, capability, outputSettings, selectionMode, resolvedSelection)
    }
    return@withContext finalization.result
    } finally {
        if (retainLease) operationLease.release()
    }
}

/**
 * Shared settlement path for post-transaction failures (progress, frame-selection, worker-construction).
 * Routes all failures through the single transaction finalizer instead of separate rollback logic.
 * Never releases the lease — caller must propagate or throw after calling this.
 */
private fun settleAndFail(
    transaction: ReprocessTransaction,
    operationLease: JobOperationLease,
    error: Throwable
): Result<KeplerReprocessResult> {
    writeReprocessFailure(transaction.backupRoot.parentFile ?: return Result.failure(error),
        "${error.javaClass.simpleName}: ${error.message}")
    return Result.failure(error)
}

/** Register a late finalization callback on the worker's terminal for when it eventually completes.
 * Uses narrow exception boundary to avoid swallowing CancellationException, OOME, ThreadDeath, etc. */
private fun registerLateFinalization(
    worker: ReprocessWorkerRun,
    transaction: ReprocessTransaction,
    operationLease: JobOperationLease,
    target: File,
    capability: ReprocessCapability,
    outputSettings: FinalOutputFormat,
    selectionMode: FrameSelectionMode,
    resolvedSelection: Set<Int>
) {
    worker.terminal.invokeOnCompletion { _ ->
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outcome = worker.terminal.await()
                val late = finalizeTransactionWithLease(
                    transaction, operationLease,
                    target, capability.jobKind, outputSettings, selectionMode,
                    resolvedSelection, Result.success(outcome)
                )
                if (late.state == ReprocessFinalizationState.COMMITTED || late.state == ReprocessFinalizationState.ROLLED_BACK) {
                    operationLease.release()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: ThreadDeath) {
                throw e
            } catch (_: Exception) {
                // late finalization failure; retain quarantine and backups
            }
        }
    }
}

/**
 * Single authoritative transaction finalizer. All post-transaction outcomes (success, failure,
 * cancellation, timeout, late completion) route through here. Handles lease release only after
 * durable COMMITTED or ROLLED_BACK. QUARANTINED retains the lease.
 *
 * Order for COMMITTED:
 *   1. Terminal metadata/checkpoint
 *   2. Durable COMMITTED state
 *   3. Marker removal
 *   4. Immediate best-effort backup cleanup
 *   5. Lease release
 *
 * Order for ROLLED_BACK:
 *   1. Restore files
 *   2. Terminal failure/cancellation metadata
 *   3. Durable ROLLED_BACK state
 *   4. Marker removal
 *   5. Immediate best-effort backup cleanup
 *   6. Lease release
 *
 * Order for QUARANTINED:
 *   1. Attempt durable marker and QUARANTINED state
 *   2. Retain backups and lease
 *   3. Preserve original and persistence failures through cause/suppressed linkage
 */
internal fun finalizeTransactionWithLease(
    transaction: ReprocessTransaction,
    operationLease: JobOperationLease,
    jobDir: File,
    jobKind: ReprocessJobKind,
    outputSettings: FinalOutputFormat,
    selectionMode: FrameSelectionMode,
    includedFrameIndices: Set<Int>,
    terminal: Result<ReprocessWorkerOutcome>
): ReprocessFinalizationResult {
    val backups = transaction.backups
    val backupRoot = transaction.backupRoot

    // Validate transaction integrity
    if (!backupRoot.isDirectory) {
        return ReprocessFinalizationResult(
            ReprocessFinalizationState.QUARANTINED,
            Result.failure(IllegalStateException("Transaction backup root missing: $backupRoot"))
        )
    }
    val manifestFile = File(backupRoot, REPROCESS_TX_MANIFEST_FILE)
    if (!manifestFile.isFile) {
        return ReprocessFinalizationResult(
            ReprocessFinalizationState.QUARANTINED,
            Result.failure(IllegalStateException("Transaction manifest missing for finalization"))
        )
    }
    val currentManifest = runCatching { ReprocessTransactionManifest.fromJson(JSONObject(manifestFile.readText())) }.getOrNull()
        ?: return ReprocessFinalizationResult(
            ReprocessFinalizationState.QUARANTINED,
            Result.failure(IllegalStateException("Transaction manifest unreadable for finalization"))
        )
    if (currentManifest.transactionId != transaction.transactionId) {
        return ReprocessFinalizationResult(
            ReprocessFinalizationState.QUARANTINED,
            Result.failure(IllegalStateException("Transaction ID mismatch during finalization"))
        )
    }

    val outcome = terminal.getOrElse { terminalError ->
        // Terminal error = never received worker outcome. Quarantine and retain.
        writeQuarantineMarker(transaction)
        val stateError = runCatching { writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED) }
            .exceptionOrNull()
        if (stateError != null) {
            val combined = RuntimeException("Failed to persist QUARANTINED state after terminal error", terminalError).apply {
                addSuppressed(stateError)
            }
            return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(combined))
        }
        return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(terminalError))
    }

    // If already terminal, do not re-run finalization (idempotent)
    val currentState = currentManifest.state
    if (currentState == ReprocessTransactionState.COMMITTED || currentState == ReprocessTransactionState.ROLLED_BACK) {
        // Already resolved - return existing state without side effects
        val existingState = when (currentState) {
            ReprocessTransactionState.COMMITTED -> ReprocessFinalizationState.COMMITTED
            ReprocessTransactionState.ROLLED_BACK -> ReprocessFinalizationState.ROLLED_BACK
            else -> ReprocessFinalizationState.QUARANTINED
        }
        val terminalResult = terminal.fold(
            onSuccess = { Result.success(KeplerReprocessResult(jobDir, jobKind, null, null, 0L, listOf("Already finalized: ${currentState.name}"))) },
            onFailure = { Result.failure(it) }
        )
        return ReprocessFinalizationResult(existingState, terminalResult)
    }

    // Commit path: VERIFIED_SUCCESS, COMMITTED_PARTIAL, or publicExportCommitted
    if (outcome.disposition == ReprocessTerminalDisposition.VERIFIED_SUCCESS ||
        outcome.disposition == ReprocessTerminalDisposition.COMMITTED_PARTIAL ||
        outcome.publicExportCommitted
    ) {
        val committed = finalizeReprocessOutcome(
            jobDir, jobKind, outputSettings, selectionMode, includedFrameIndices, outcome, transaction
        )
        return if (committed.isSuccess) {
            // 1. Terminal metadata/checkpoint done
            // 2. Durable COMMITTED state
            try {
                writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
            } catch (e: Exception) {
                // COMMITTED state write failed; quarantine and retain backups.
                writeQuarantineMarker(transaction)
                return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(e))
            }
            // 3. Marker removal
            removeQuarantineMarker(transaction)
            // 4. Immediate best-effort backup cleanup
            deleteBackups(transaction)
            // 5. Lease release
            operationLease.release()
            ReprocessFinalizationResult(ReprocessFinalizationState.COMMITTED, committed)
        } else {
            // Terminal metadata write failed after MediaStore output already committed.
            // Public export cannot be rolled back, so job must remain quarantined with backups retained.
            writeQuarantineMarker(transaction)
            try {
                writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED)
            } catch (e: Exception) {
                return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(e))
            }
            // Lease retained for QUARANTINED
            ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, committed)
        }
    }

    // Rollback path: restore -> metadata -> ROLLED_BACK -> cleanup -> release
    val error = outcome.terminalError ?: IllegalStateException("Reprocess worker failed.")
    return restoreBackups(jobDir, transaction).fold(
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
                writeQuarantineMarker(transaction)
                try {
                    writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED)
                } catch (e: Exception) {
                    return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(e))
                }
                return@fold ReprocessFinalizationResult(
                    ReprocessFinalizationState.QUARANTINED,
                    Result.failure(metadataError)
                )
            }
            removeQuarantineMarker(transaction)
            try {
                writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
            } catch (e: Exception) {
                return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(e))
            }
            val cleanupSuccess = deleteBackups(transaction)
            if (!cleanupSuccess) {
                try {
                    KeplerJobMetadata.update(jobDir) {
                        it.put("reprocessWarning", "Reprocess backup cleanup failed after safe rollback.")
                    }
                } catch (_: Exception) { /* rollback already safe; warning best-effort */ }
            }
            // Lease release after durable ROLLED_BACK
            operationLease.release()
            ReprocessFinalizationResult(ReprocessFinalizationState.ROLLED_BACK, Result.failure(error))
        },
        onFailure = { rollbackError ->
            // Restore failed: job is incoherent. Quarantine and retain backups and lease.
            writeQuarantineMarker(transaction)
            try {
                writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED)
            } catch (e: Exception) {
                return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(e))
            }
            ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(rollbackError))
        }
    )
}

/**
 * Finalizes the reprocess outcome metadata. Does NOT handle transaction state or lease.
 * Returns success with KeplerReprocessResult or failure with the metadata error.
 */
private fun finalizeReprocessOutcome(
    jobDir: File,
    jobKind: ReprocessJobKind,
    outputSettings: FinalOutputFormat,
    selectionMode: FrameSelectionMode,
    includedFrameIndices: Set<Int>,
    outcome: ReprocessWorkerOutcome,
    transaction: ReprocessTransaction
): Result<KeplerReprocessResult> {
    val backups = transaction.backups
    val backupRoot = transaction.backupRoot
    val finalFile = outcome.finalOutputFile?.takeIf { it.isFile && it.length() > 0L }
    val previewFile = outcome.previewFile?.takeIf { it.isFile && it.length() > 0L } ?: finalFile
    if (outcome.result.isSuccess && !outcome.publicExportCommitted && finalFile?.isFile != true) {
        if (previewFile == null || previewFile == finalFile) {
            return try {
                restoreBackups(jobDir, transaction).getOrThrow()
                Result.failure(IllegalStateException("Reprocess completed without a final output file."))
            } catch (rollbackError: Exception) {
                writeQuarantineMarker(transaction)
                try {
                    writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED)
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
        writeQuarantineMarker(transaction)
        return Result.failure(metadataFailure)
    }
    try {
        clearReprocessCommitCheckpoint(jobDir)
    } catch (checkpointClearError: Exception) {
        writeQuarantineMarker(transaction)
        return Result.failure(checkpointClearError)
    }
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

private fun reprocessBackupRoot(transaction: ReprocessTransaction): File = transaction.backupRoot

/** Persist a quarantine marker in the transaction backup directory. Retains the backups.
 * Throws on failure — quarantine must be durable or the transaction is unresolved. */
internal fun writeQuarantineMarker(transaction: ReprocessTransaction) {
    val root = reprocessBackupRoot(transaction)
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
internal fun writeTransactionState(transaction: ReprocessTransaction, state: ReprocessTransactionState) {
    val root = reprocessBackupRoot(transaction)
    val manifestFile = File(root, REPROCESS_TX_MANIFEST_FILE)
    if (!manifestFile.isFile) {
        throw IllegalStateException("Transaction manifest missing for state write: $state")
    }
    val manifest = ReprocessTransactionManifest.fromJson(JSONObject(manifestFile.readText()))
        .copy(state = state)
    KeplerJobMetadata.atomicWrite(manifestFile, manifest.toJson().toString(2))
}

/** Remove the quarantine marker only after a safe commit or rollback. */
internal fun removeQuarantineMarker(transaction: ReprocessTransaction) {
    val root = reprocessBackupRoot(transaction)
    val marker = File(root, REPROCESS_QUARANTINE_MARKER)
    if (marker.exists()) marker.delete()
}

/**
 * True when the job is marked quarantined by an unresolved reprocess finalization. Cleanup, deletion,
 * and stale recovery must refuse quarantined jobs so a half-written state is never destroyed.
 * Uses [classifyTransactionManifest] for fail-closed evidence inspection.
 */
internal fun isReprocessQuarantined(jobDir: File): Boolean {
    jobDir.listFiles()?.forEach { child ->
        if (child.isDirectory && child.name.startsWith(".reprocess_backup_")) {
            when (classifyTransactionManifest(child)) {
                is ManifestClassification.Unresolved -> return true
                is ManifestClassification.Resolved -> return false
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
            val classification = classifyTransactionManifest(child)
            val remaining = child.listFiles()?.toList().orEmpty()
            val hasBackupFiles = remaining.any { it.isFile && it.name != REPROCESS_QUARANTINE_MARKER && it.name != REPROCESS_TX_MANIFEST_FILE }
            val isResolved = classification is ManifestClassification.Resolved
            if (!hasBackupFiles || isResolved) {
                // Remove all contents (backup files, manifest, marker) then the root directory
                child.listFiles()?.forEach { it.delete() }
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

internal const val REPROCESS_TX_MANIFEST_FILE = "manifest.json"

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

internal fun restoreBackups(jobDir: File, transaction: ReprocessTransaction): Result<Unit> = runCatching {
    val root = transaction.backupRoot
    if (!root.isDirectory) return@runCatching
    val manifestFile = File(root, REPROCESS_TX_MANIFEST_FILE)
    val manifest = if (manifestFile.isFile) {
        runCatching { ReprocessTransactionManifest.fromJson(JSONObject(manifestFile.readText())) }.getOrNull()
    } else null
    val backups = transaction.backups
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

internal fun deleteBackups(transaction: ReprocessTransaction): Boolean {
    val root = transaction.backupRoot
    if (!root.isDirectory) return true
    removeQuarantineMarker(transaction)
    transaction.backups.forEach { backup -> if (backup.backup.exists() && !backup.backup.delete()) return false }
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
