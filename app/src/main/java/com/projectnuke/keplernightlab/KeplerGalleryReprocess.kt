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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
 * Strict manifest parser/classifier. Fails closed:
 * - Missing/corrupt/unreadable/incomplete manifests → Unresolved
 * - Legacy manifests missing only "state" key → Unresolved (ACTIVE)
 * - Only fully validated COMMITTED or ROLLED_BACK → Resolved
 * - Valid QUARANTINED/ACTIVE manifests → Unresolved
 *
 * Required fields: transactionId (non-blank), createdAt (>0), state (valid enum),
 * preExistingPaths, backedUpPaths arrays. Safe relative paths only (no traversal).
 */
internal fun classifyTransactionManifest(backupRoot: File): ManifestClassification {
    if (!backupRoot.isDirectory) return ManifestClassification.Unresolved
    val manifestFile = File(backupRoot, REPROCESS_TX_MANIFEST_FILE)
    if (!manifestFile.isFile) return ManifestClassification.Unresolved
    val json = runCatching { JSONObject(manifestFile.readText()) }.getOrNull()
        ?: return ManifestClassification.Unresolved
    val txId = json.optString("transactionId")
    if (txId.isNullOrBlank()) return ManifestClassification.Unresolved
    val createdAt = json.optLong("createdAt", 0L)
    if (createdAt <= 0L) return ManifestClassification.Unresolved
    val preExisting = json.optJSONArray("preExistingPaths")
    val backedUp = json.optJSONArray("backedUpPaths")
    if (preExisting == null || backedUp == null) return ManifestClassification.Unresolved
    // Validate safe relative paths
    if (!areManifestPathsSafe(preExisting) || !areManifestPathsSafe(backedUp)) return ManifestClassification.Unresolved
    val newlyCreated = json.optJSONArray("newlyCreatedPaths")
    if (newlyCreated != null && !areManifestPathsSafe(newlyCreated)) return ManifestClassification.Unresolved
    // State: missing state means legacy ACTIVE (unresolved)
    if (!json.has("state") || json.isNull("state")) return ManifestClassification.Unresolved
    val stateName = json.optString("state")
    val state = runCatching { ReprocessTransactionState.valueOf(stateName) }.getOrNull()
        ?: return ManifestClassification.Unresolved
    return when (state) {
        ReprocessTransactionState.COMMITTED,
        ReprocessTransactionState.ROLLED_BACK -> ManifestClassification.Resolved(state)
        ReprocessTransactionState.ACTIVE,
        ReprocessTransactionState.QUARANTINED -> ManifestClassification.Unresolved
    }
}

private fun areManifestPathsSafe(array: JSONArray): Boolean {
    repeat(array.length()) { i ->
        val path = array.optString(i)
        if (path.isNullOrBlank()) return false
        if (path.contains("..") || path.startsWith("/") || File(path).isAbsolute) return false
        val normalized = File(path).path.replace("\\", "/")
        if (normalized.contains("..")) return false
    }
    return true
}

/**
 * Strict monotonic state transition validation. Called by the authoritative state writer.
 * Allowed transitions:
 * - ACTIVE → QUARANTINED, COMMITTED, ROLLED_BACK
 * - QUARANTINED → QUARANTINED, COMMITTED, ROLLED_BACK
 * - COMMITTED → COMMITTED (idempotent)
 * - ROLLED_BACK → ROLLED_BACK (idempotent)
 * Rejects: COMMITTED → ROLLED_BACK, ROLLED_BACK → COMMITTED, terminal → ACTIVE, terminal → QUARANTINED
 */
internal fun validateStateTransition(current: ReprocessTransactionState, target: ReprocessTransactionState) {
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

/**
 * Transaction session: explicit ownership model for the operation lease.
 *
 * Before the ACTIVE transaction is durably written, the session releases the lease
 * on every return or exception. After ownership transfer (durably persisted ACTIVE
 * manifest), the outer finally must never release it — only the shared settlement
 * releases it after durable COMMITTED or ROLLED_BACK.
 */
private class ReprocessTransactionSession(val jobDir: File) {
    var lease: JobOperationLease? = null
        private set
    var transaction: ReprocessTransaction? = null
        private set
    private val ownershipTransferred = AtomicBoolean(false)

    fun acquireLease(): JobOperationLease? {
        val acquired = KeplerJobMetadata.acquireOperation(jobDir)
        lease = acquired
        return acquired
    }

    /** Transfer lease ownership to the transaction. After this, the outer finally must NOT release. */
    fun transferOwnership(tx: ReprocessTransaction) {
        transaction = tx
        ownershipTransferred.set(true)
    }

    /** True if ownership has been transferred to a durable ACTIVE transaction. */
    fun ownsTransaction(): Boolean = ownershipTransferred.get()

    /** Release the lease if not yet transferred. Called only in pre-transaction exit paths. */
    fun releaseIfUnowned() {
        if (!ownershipTransferred.get()) {
            lease?.release()
            lease = null
        }
    }
}

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
    val session = ReprocessTransactionSession(target)
    val operationLease = session.acquireLease() ?: run {
        return@withContext Result.failure(IllegalStateException("A job mutation is already in progress."))
    }

    // Pre-transaction phase: every path here must release the lease via session.releaseIfUnowned()
    // No transaction exists yet, so the session will release it.
    val capability = detectReprocessCapability(context, target)
    if (!capability.canReprocess) {
        writeReprocessFailure(target, capability.reason)
        session.releaseIfUnowned()
        return@withContext Result.failure(IllegalStateException(capability.reason))
    }

    postProgress("원본 프레임 확인 중…")
    val job = try {
        KeplerJobMetadata.read(target)
    } catch (metadataError: KeplerJobMetadataException) {
        writeReprocessFailure(target, "${metadataError.javaClass.simpleName}: ${metadataError.message}")
        session.releaseIfUnowned()
        return@withContext Result.failure(metadataError)
    }
    val kind = detectJobKind(target, job)
    val reviewItems = loadFrameReviewItems(context, target).getOrElse {
        writeReprocessFailure(target, "${it.javaClass.simpleName}: ${it.message}")
        session.releaseIfUnowned()
        return@withContext Result.failure(it)
    }
    val resolvedSelection = resolveFrameSelection(target, kind, reviewItems, frameSelection).getOrElse {
        writeReprocessFailure(target, "${it.javaClass.simpleName}: ${it.message}")
        session.releaseIfUnowned()
        return@withContext Result.failure(it)
    }
    if (resolvedSelection.size < requiredSelectedFrameCount(kind)) {
        val message = "선택한 원본 프레임이 부족합니다. 다시 합성할 수 없습니다."
        writeReprocessFailure(target, message)
        session.releaseIfUnowned()
        return@withContext Result.failure(IllegalStateException(message))
    }
    val selectionMode = resolveSelectionMode(job, frameSelection)
    val transaction = backupReprocessTransaction(
        target,
        target.listFiles()?.filter { it.isFile && isReprocessWorkerWritable(it) }.orEmpty()
    ).getOrElse {
        writeReprocessFailure(target, "Required reprocess backup failed: ${it.message}")
        session.releaseIfUnowned()
        return@withContext Result.failure(it)
    }

    // Ownership transfer: ACTIVE manifest is now durably persisted.
    // After this point, the outer finally must NOT release the lease.
    session.transferOwnership(transaction)

    // Post-transaction phase: ALL failures route through the shared finalizer.
    // The finalizer handles lease release after durable COMMITTED/ROLLED_BACK,
    // quarantine retention, and backup evidence preservation.
    try {
    postProgress("프레임 선택 적용 중…")
    saveFrameSelectionInternal(
        jobDir = target,
        mode = selectionMode,
        frames = applyFrameSelectionToItems(reviewItems, resolvedSelection, selectionMode),
        operationLease = operationLease
    ).getOrElse {
        return@withContext settlePostTransactionFailure(transaction, operationLease, target, it)
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
    if (finalization.state == ReprocessFinalizationState.QUARANTINED && terminalOutcome.isFailure) {
        registerLateFinalization(worker, transaction, operationLease, target, capability, outputSettings, selectionMode, resolvedSelection)
    }
    return@withContext finalization.result
    } catch (@Suppress("UNUSED_VARIABLE") unexpected: Exception) {
        // Unexpected post-transaction exception: route through shared settlement.
        // The finalizer quarantines and retains the lease if it cannot resolve.
        val tx = transaction
        return@withContext finalizeTransactionWithLease(
            tx, operationLease,
            target, capability.jobKind, outputSettings, selectionMode,
            resolvedSelection, Result.failure(unexpected)
        ).result
    }
}

/**
 * Shared settlement for post-transaction failures (progress, frame-selection, worker-construction).
 * Routes all failures through the single transaction finalizer as uncommitted terminal outcomes.
 * The finalizer handles rollback, quarantine, lease release per the settlement contract.
 */
private fun settlePostTransactionFailure(
    transaction: ReprocessTransaction,
    operationLease: JobOperationLease,
    jobDir: File,
    error: Throwable
): Result<KeplerReprocessResult> {
    val failedOutcome = ReprocessWorkerOutcome(
        result = Result.failure(error),
        publicExportCommitted = false,
        exportVerified = false
    )
    val finalization = finalizeTransactionWithLease(
        transaction = transaction,
        operationLease = operationLease,
        jobDir = jobDir,
        jobKind = ReprocessJobKind.UNSUPPORTED,
        outputSettings = FinalOutputFormat.PNG_DEBUG,
        selectionMode = FrameSelectionMode.AUTO_RULE_BASED,
        includedFrameIndices = emptySet(),
        terminal = Result.success(failedOutcome)
    )
    return finalization.result
}

/** Register a late finalization callback on the worker's terminal for when it eventually completes.
 * Uses narrow exception boundary to avoid swallowing CancellationException, OOME, ThreadDeath, etc.
 * The callback releases the real retained lease once after safe settlement (COMMITTED/ROLLED_BACK).
 * Quarantined late completion retains the lease.
 * Registration is idempotent: only registered when no terminal outcome was obtained. */
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
    val registered = AtomicBoolean(false)
    if (!registered.compareAndSet(false, true)) return
    worker.terminal.invokeOnCompletion { _ ->
        // Detached IO scope survives caller cancellation; ownership is the late-finalization callback.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outcome = worker.terminal.await()
                val late = finalizeTransactionWithLease(
                    transaction, operationLease,
                    target, capability.jobKind, outputSettings, selectionMode,
                    resolvedSelection, Result.success(outcome)
                )
                // Shared finalizer already released the lease for COMMITTED/ROLLED_BACK.
                // Only release here if the finalizer did not (QUARANTINED retains).
                // The finalizer handles release internally; do not double-release.
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: ThreadDeath) {
                throw e
            } catch (_: Exception) {
                // Late finalization failure; retain quarantine and backups.
                // The lease is retained for unresolved state.
            }
        }
    }
}

/**
 * Single authoritative transaction finalizer. All post-transaction outcomes (success, failure,
 * cancellation, timeout, late completion) route through here. Handles lease release only after
 * durable COMMITTED or ROLLED_BACK. QUARANTINED retains the lease.
 *
 * Commit path ordering:
 *   1. Write terminal metadata and clear the commit checkpoint
 *   2. Durably write COMMITTED
 *   3. Remove quarantine marker if present
 *   4. Best-effort cleanup of backup payloads/root
 *   5. Release the operation lease
 *
 * Rollback path ordering:
 *   1. Validate every backup before mutating any target
 *   2. Restore all backed-up files (exact rollback — content verified, not just length)
 *   3. Remove transaction-created mutable files
 *   4. Write terminal failure/cancellation metadata
 *   5. Durably write ROLLED_BACK
 *   6. Remove quarantine marker if present
 *   7. Best-effort cleanup
 *   8. Release the operation lease
 *
 * Quarantine path ordering:
 *   1. Preserve the original error
 *   2. Attempt durable marker persistence
 *   3. Attempt durable QUARANTINED state
 *   4. Retain backups
 *   5. Retain the lease
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
    val backupRoot = transaction.backupRoot

    // Validate transaction integrity — fail closed
    if (!backupRoot.isDirectory) {
        return quarantineNoMutation(operationLease, IllegalStateException("Transaction backup root missing: $backupRoot"))
    }
    val manifestFile = File(backupRoot, REPROCESS_TX_MANIFEST_FILE)
    if (!manifestFile.isFile) {
        return quarantineNoMutation(operationLease, IllegalStateException("Transaction manifest missing for finalization"))
    }
    val currentManifest = runCatching { ReprocessTransactionManifest.fromJson(JSONObject(manifestFile.readText())) }.getOrNull()
        ?: return quarantineNoMutation(operationLease, IllegalStateException("Transaction manifest unreadable for finalization"))
    if (currentManifest.transactionId != transaction.transactionId) {
        return quarantineNoMutation(operationLease, IllegalStateException("Transaction ID mismatch during finalization"))
    }

    val outcome = terminal.getOrElse { terminalError ->
        // Terminal error = never received worker outcome. Quarantine and retain.
        return quarantineWithPersistence(transaction, operationLease, terminalError)
    }

    // If already terminal, do not re-run finalization (idempotent)
    val currentState = currentManifest.state
    if (currentState == ReprocessTransactionState.COMMITTED || currentState == ReprocessTransactionState.ROLLED_BACK) {
        val existingState = when (currentState) {
            ReprocessTransactionState.COMMITTED -> ReprocessFinalizationState.COMMITTED
            ReprocessTransactionState.ROLLED_BACK -> ReprocessFinalizationState.ROLLED_BACK
            else -> ReprocessFinalizationState.QUARANTINED
        }
        val terminalResult = terminal.fold(
            onSuccess = { Result.success(KeplerReprocessResult(jobDir, jobKind, null, null, 0L, listOf("Already finalized: ${currentState.name}"))) },
            onFailure = { Result.failure(it) }
        )
        // Already resolved — lease was released by the first finalization pass.
        // Do NOT release again. The late callback must also not double-release.
        return ReprocessFinalizationResult(existingState, terminalResult)
    }

    // Commit path: VERIFIED_SUCCESS, COMMITTED_PARTIAL, or publicExportCommitted
    // An uncommitted apparent success with no usable local/public output must go to rollback.
    val hasUsableOutput = outcome.publicExportCommitted || outcome.exportVerified ||
        (outcome.finalOutputFile?.isFile == true && outcome.finalOutputFile.length() > 0L)
    val shouldCommit = (outcome.disposition == ReprocessTerminalDisposition.VERIFIED_SUCCESS ||
        outcome.disposition == ReprocessTerminalDisposition.COMMITTED_PARTIAL ||
        outcome.publicExportCommitted) && hasUsableOutput

    if (shouldCommit) {
        val commitResult = runCatching {
            // 1. Write terminal metadata and clear the commit checkpoint
            finalizeReprocessOutcome(
                jobDir, jobKind, outputSettings, selectionMode, includedFrameIndices, outcome, transaction
            )
        }
        return commitResult.fold(
            onSuccess = { committed ->
                // 2. Durably write COMMITTED
                try {
                    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
                } catch (e: Exception) {
                    // State write failed; quarantine and retain backups and lease.
                    return quarantineWithPersistence(transaction, operationLease, e)
                }
                // 3. Remove quarantine marker if present
                runCatching { removeQuarantineMarker(transaction) }
                // 4. Best-effort cleanup of backup payloads/root
                runCatching { cleanupBackups(transaction) }
                // 5. Release the operation lease
                operationLease.release()
                ReprocessFinalizationResult(ReprocessFinalizationState.COMMITTED, Result.success(committed))
            },
            onFailure = { metadataError ->
                // Terminal metadata write failed after MediaStore output already committed.
                // Public export cannot be rolled back → quarantine, retain backups and lease.
                if (outcome.publicExportCommitted) {
                    return quarantineWithPersistence(transaction, operationLease, metadataError)
                }
                // No public export committed — metadata failure before commit → rollback path
                // Fall through to rollback path below
                rollback(transaction, operationLease, jobDir, jobKind, outcome, metadataError)
            }
        )
    }

    // Rollback path
    return rollback(transaction, operationLease, jobDir, jobKind, outcome,
        outcome.terminalError ?: IllegalStateException("Reprocess worker failed."))
}

/** Quarantine without mutating job files (backup root/manifest invalid). Retains lease. */
private fun quarantineNoMutation(operationLease: JobOperationLease, error: Throwable): ReprocessFinalizationResult {
    // Cannot write quarantine marker or state — root/manifest is invalid.
    // Retain the lease; do NOT release it (unresolved → invariant 5).
    return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(error))
}

/** Quarantine with durable marker + state persistence. Retains lease. Combines errors. */
private fun quarantineWithPersistence(
    transaction: ReprocessTransaction,
    operationLease: JobOperationLease,
    originalError: Throwable
): ReprocessFinalizationResult {
    var combinedError = originalError
    var markerError: Throwable? = null
    var stateError: Throwable? = null
    try {
        writeQuarantineMarker(transaction)
    } catch (e: Exception) {
        markerError = e
    }
    try {
        writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED)
    } catch (e: Exception) {
        stateError = e
    }
    if (markerError != null || stateError != null) {
        combinedError = RuntimeException("Quarantine persistence failed after processing error", originalError)
        markerError?.let { combinedError.addSuppressed(it) }
        stateError?.let { combinedError.addSuppressed(it) }
    }
    return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(combinedError))
}

/** Exact rollback path. Returns ROLLED_BACK (leases released) or QUARANTINED (lease retained). */
private fun rollback(
    transaction: ReprocessTransaction,
    operationLease: JobOperationLease,
    jobDir: File,
    jobKind: ReprocessJobKind,
    outcome: ReprocessWorkerOutcome,
    error: Throwable
): ReprocessFinalizationResult {
    // 1. Validate every backup before mutating any target
    // 2. Restore all backed-up files (exact rollback)
    val restoreSuccess = restoreBackups(jobDir, transaction).isSuccess
    if (!restoreSuccess) {
        return quarantineWithPersistence(transaction, operationLease,
            IllegalStateException("Rollback restore failed: ${error.message}", error))
    }
    // 3. Remove transaction-created mutable files
    removeTransactionCreatedFiles(jobDir, transaction)
    // 4. Write terminal failure/cancellation metadata
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
        return quarantineWithPersistence(transaction, operationLease, metadataError)
    }
    // 5. Durably write ROLLED_BACK
    try {
        writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    } catch (e: Exception) {
        return quarantineWithPersistence(transaction, operationLease, e)
    }
    // 6. Remove quarantine marker if present
    runCatching { removeQuarantineMarker(transaction) }
    // 7. Best-effort cleanup
    val cleanupSuccess = runCatching { cleanupBackups(transaction) }.getOrDefault(false)
    if (!cleanupSuccess) {
        runCatching {
            KeplerJobMetadata.update(jobDir) {
                it.put("reprocessWarning", "Reprocess backup cleanup failed after safe rollback.")
            }
        }
    }
    // 8. Release the operation lease
    operationLease.release()
    return ReprocessFinalizationResult(ReprocessFinalizationState.ROLLED_BACK, Result.failure(error))
}

/** Remove files that were created by this transaction and are not pre-existing or backed-up. */
private fun removeTransactionCreatedFiles(jobDir: File, transaction: ReprocessTransaction) {
    val manifest = transaction.manifest
    val preExisting = manifest.preExistingPaths
    val backedUp = manifest.backedUpPaths
    val backupRootName = transaction.backupRoot.name
    jobDir.listFiles()?.filter { it.isFile }?.forEach { file ->
        val name = file.name
        if (name !in preExisting && name !in backedUp && name != backupRootName && name != JOB_JSON_FILE_NAME) {
            if (manifest.isNewlyCreated(name)) {
                runCatching { file.delete() }
            }
        }
    }
}

/**
 * Finalizes the reprocess outcome metadata and checkpoints only.
 * Does NOT: restore backups, write quarantine markers, write transaction state,
 * perform backup cleanup, or release the lease. Transaction resolution belongs
 * exclusively to [finalizeTransactionWithLease].
 *
 * If the result is a failure (metadata write or checkpoint clear), the caller
 * (finalizer) routes it through the rollback or quarantine path.
 *
 * An uncommitted apparent success with no usable local/public output throws
 * rather than returning success, so the call site enters the rollback path.
 */
private fun finalizeReprocessOutcome(
    jobDir: File,
    jobKind: ReprocessJobKind,
    outputSettings: FinalOutputFormat,
    selectionMode: FrameSelectionMode,
    includedFrameIndices: Set<Int>,
    outcome: ReprocessWorkerOutcome,
    transaction: ReprocessTransaction
): KeplerReprocessResult {
    val finalFile = outcome.finalOutputFile?.takeIf { it.isFile && it.length() > 0L }
    val previewFile = outcome.previewFile?.takeIf { it.isFile && it.length() > 0L } ?: finalFile
    val uncommittedNoOutput = outcome.result.isSuccess && !outcome.publicExportCommitted && finalFile?.isFile != true
    if (uncommittedNoOutput && (previewFile == null || previewFile == finalFile)) {
        throw IllegalStateException("Reprocess completed without a final output file.")
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
    clearReprocessCommitCheckpoint(jobDir)
    return KeplerReprocessResult(jobDir, jobKind, displayFile, previewFile, bytes,
        listOfNotNull(if (verifiedSuccess) null else "Public export committed; reprocess verification incomplete")
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
    FileOutputStream(this).use { block(it) }
}
/**
 * Persist a quarantine marker in the transaction backup directory. Retains the backups.
 * Throws on failure — quarantine must be durable or the transaction is unresolved.
 * Fails if the backup root is missing, not a directory, or marker write/sync fails.
 */
internal fun writeQuarantineMarker(transaction: ReprocessTransaction) {
    val root = transaction.backupRoot
    check(root.isDirectory) { "Quarantine marker write failed: backup root missing or not a directory: $root" }
    val marker = File(root, REPROCESS_QUARANTINE_MARKER)
    if (marker.exists()) return
    KeplerJobMetadata.atomicWrite(marker, "quarantined\n")
}

/**
 * Single authoritative atomic state writer. Validates monotonic state transitions.
 * Throws on any failure — state persistence is mandatory and must not be silently ignored.
 * Failure here leaves the transaction unresolved; caller must not release the operation lease.
 * Rejects: terminal → ACTIVE, terminal → QUARANTINED, COMMITTED → ROLLED_BACK,
 * ROLLED_BACK → COMMITTED, transaction-ID mismatch, invalid/incomplete manifest.
 * Same-terminal writes are idempotent.
 */
internal fun writeTransactionState(transaction: ReprocessTransaction, state: ReprocessTransactionState) {
    val root = transaction.backupRoot
    val manifestFile = File(root, REPROCESS_TX_MANIFEST_FILE)
    check(manifestFile.isFile) { "Transaction manifest missing for state write: $state" }
    val json = JSONObject(manifestFile.readText())
    val currentManifest = ReprocessTransactionManifest.fromJson(json)
    check(currentManifest.transactionId == transaction.transactionId) {
        "Transaction ID mismatch: manifest=${currentManifest.transactionId}, expected=${transaction.transactionId}"
    }
    val currentState = currentManifest.state
    // Idempotent for same-terminal writes
    if (currentState == state) return
    validateStateTransition(currentState, state)
    val updatedManifest = currentManifest.copy(state = state)
    KeplerJobMetadata.atomicWrite(manifestFile, updatedManifest.toJson().toString(2))
}

/** Remove the quarantine marker only after a safe commit or rollback.
 * Throwing on marker removal failure is not required — the terminal state is already durable.
 * Caller wraps this in runCatching for the commit/rollback paths. */
internal fun removeQuarantineMarker(transaction: ReprocessTransaction) {
    val root = transaction.backupRoot
    val marker = File(root, REPROCESS_QUARANTINE_MARKER)
    if (marker.exists() && !marker.delete()) {
        throw IllegalStateException("Quarantine marker deletion failed: ${marker.absolutePath}")
    }
}

/**
 * True when any reprocess backup root has an unresolved (ACTIVE, QUARANTINED, missing,
 * corrupt, or incomplete) transaction. Resolved roots never mask unresolved roots.
 * No roots means no transaction block.
 * A stale marker cannot make an invalid/nonterminal transaction safe.
 * A valid terminal manifest remains authoritative if cleanup artifacts remain.
 */
internal fun isReprocessQuarantined(jobDir: File): Boolean {
    var anyRoot = false
    jobDir.listFiles()?.forEach { child ->
        if (child.isDirectory && child.name.startsWith(".reprocess_backup_")) {
            anyRoot = true
            when (classifyTransactionManifest(child)) {
                is ManifestClassification.Unresolved -> return true
                is ManifestClassification.Resolved -> { /* resolved roots do not block */ }
            }
        }
    }
    return false // no unresolved root found; resolved roots or no roots are fine
}

/**
 * Safe process-restart recovery for validated quarantine transactions. Called from
 * [loadKeplerGalleryJobs] for every job directory.
 *
 * - ACTIVE, QUARANTINED, missing-manifest, corrupt-manifest, incomplete-manifest roots are PRESERVED.
 * - Unresolved evidence is never deleted merely because payload files are missing.
 * - A root may be considered empty/abandoned only when it has no marker, no manifest,
 *   no backup payload, and no transaction-related temporary evidence.
 * - Valid COMMITTED/ROLLED_BACK roots may be cleaned best-effort.
 * - Process-local active operations are never recovered concurrently.
 * - Multiple roots are handled independently; the aggregate job remains blocked if any unresolved root remains.
 */
internal fun recoverValidatedQuarantine(jobDir: File) {
    if (KeplerJobMetadata.isOperationActive(jobDir)) return
    jobDir.listFiles()?.forEach { child ->
        if (child.isDirectory && child.name.startsWith(".reprocess_backup_")) {
            val classification = classifyTransactionManifest(child)
            when (classification) {
                is ManifestClassification.Unresolved -> {
                    // Preserve unresolved evidence — do NOT delete.
                    // Only remove a truly empty/abandoned root (no evidence at all).
                    if (isRootEvidenceFree(child)) {
                        child.delete()
                    }
                }
                is ManifestClassification.Resolved -> {
                    // Valid terminal root: best-effort cleanup of backup payloads/root.
                    // Leaves terminal manifest until cleanup is safe.
                    cleanupTerminalRoot(child)
                }
            }
        }
    }
}

/** True when a root has no marker, no manifest, no backup payload, and no temp evidence. */
private fun isRootEvidenceFree(root: File): Boolean {
    val children = root.listFiles() ?: emptyArray()
    return children.none { file ->
        file.name == REPROCESS_QUARANTINE_MARKER ||
            file.name == REPROCESS_TX_MANIFEST_FILE ||
            file.isFile ||
            file.name.endsWith(".tmp") ||
            file.name.endsWith(".restore")
    }
}

/** Best-effort cleanup of a valid terminal root. Deletes payloads first, manifest last, root only if empty. */
private fun cleanupTerminalRoot(root: File) {
    val children = root.listFiles() ?: return
    // Delete backup payloads and temp artifacts first
    children.filter { it.isFile && it.name != REPROCESS_TX_MANIFEST_FILE && it.name != REPROCESS_QUARANTINE_MARKER }
        .forEach { file -> runCatching { file.delete() } }
    // Delete quarantine marker
    children.firstOrNull { it.name == REPROCESS_QUARANTINE_MARKER }?.let { runCatching { it.delete() } }
    // Delete manifest last
    children.firstOrNull { it.name == REPROCESS_TX_MANIFEST_FILE }?.let { runCatching { it.delete() } }
    // Remove root only if empty
    if (root.listFiles()?.isEmpty() == true) root.delete()
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
    val originalLength: Long = backup.length(),
    val sha256: String = ""
)

internal const val REPROCESS_TX_MANIFEST_FILE = "manifest.json"
private const val BACKUP_ENTRY_SUFFIX = ".backup"

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
    val backupEntries: Map<String, BackupEntry>,
    val newlyCreatedPaths: Set<String> = emptySet(),
    val state: ReprocessTransactionState = ReprocessTransactionState.ACTIVE
) {
    fun isPreExisting(relativePath: String): Boolean = relativePath in preExistingPaths
    fun isBackedUp(relativePath: String): Boolean = relativePath in backedUpPaths
    fun isNewlyCreated(relativePath: String): Boolean = relativePath in newlyCreatedPaths

    fun toJson(): JSONObject {
        val entriesJson = JSONObject()
        backupEntries.forEach { (name, entry) -> entriesJson.put(name, entry.toJson()) }
        return JSONObject()
            .put("transactionId", transactionId)
            .put("createdAt", createdAt)
            .put("preExistingPaths", JSONArray(preExistingPaths.sorted()))
            .put("backedUpPaths", JSONArray(backedUpPaths.sorted()))
            .put("backupEntries", entriesJson)
            .put("newlyCreatedPaths", JSONArray(newlyCreatedPaths.sorted()))
            .put("state", state.name)
    }

    companion object {
        fun fromJson(json: JSONObject): ReprocessTransactionManifest {
            val txId = json.optString("transactionId")
            require(txId.isNotBlank()) { "Transaction ID is blank" }
            val createdAt = json.optLong("createdAt")
            require(createdAt > 0L) { "Transaction createdAt is invalid: $createdAt" }
            val preExisting = json.optJSONArray("preExistingPaths")?.toStringSet().orEmpty()
            val backedUp = json.optJSONArray("backedUpPaths")?.toStringSet().orEmpty()
            val entriesJson = json.optJSONObject("backupEntries") ?: JSONObject()
            val backupEntries = mutableMapOf<String, BackupEntry>()
            entriesJson.keys().forEach { name ->
                val entryJson = entriesJson.optJSONObject(name) ?: return@forEach
                backupEntries[name] = BackupEntry.fromJson(entryJson)
            }
            val newlyCreated = json.optJSONArray("newlyCreatedPaths")?.toStringSet().orEmpty()
            val state = if (json.has("state") && !json.isNull("state")) {
                ReprocessTransactionState.valueOf(json.optString("state"))
            } else {
                ReprocessTransactionState.ACTIVE
            }
            return ReprocessTransactionManifest(
                transactionId = txId,
                createdAt = createdAt,
                preExistingPaths = preExisting,
                backedUpPaths = backedUp,
                backupEntries = backupEntries,
                newlyCreatedPaths = newlyCreated,
                state = state
            )
        }

        private fun JSONArray?.toStringSet(): Set<String> = buildSet {
            if (this@toStringSet == null) return@buildSet
            repeat(length()) { i -> add(optString(i)) }
        }
    }
}

internal data class BackupEntry(
    val backupName: String,         // backup file name inside backup root
    val relativePath: String,       // safe relative target path inside job dir
    val originalLength: Long,       // original byte length
    val sha256: String              // streaming SHA-256 hex digest
) {
    fun toJson(): JSONObject = JSONObject()
        .put("backupName", backupName)
        .put("relativePath", relativePath)
        .put("originalLength", originalLength)
        .put("sha256", sha256)
    companion object {
        fun fromJson(json: JSONObject): BackupEntry {
            val backupName = json.optString("backupName")
            val relativePath = json.optString("relativePath")
            val originalLength = json.optLong("originalLength")
            val sha256 = json.optString("sha256")
            require(backupName.isNotBlank()) { "backupName is blank" }
            require(relativePath.isNotBlank()) { "relativePath is blank" }
            require(!relativePath.contains("..") && !File(relativePath).isAbsolute) { "Unsafe relative path: $relativePath" }
            require(originalLength >= 0L) { "Invalid originalLength: $originalLength" }
            require(sha256.length == 64) { "Invalid SHA-256 digest length: ${sha256.length}" }
            return BackupEntry(backupName, relativePath, originalLength, sha256)
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

/** Compute SHA-256 digest of a file by streaming — never loads whole large files into memory. */
private fun computeSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

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
            val backup = File(root, "${original.name}$BACKUP_ENTRY_SUFFIX")
            original.copyTo(backup, overwrite = false)
            check(backup.isFile && backup.length() == original.length()) { "Backup verification failed for ${original.name}" }
            val sha = computeSha256(backup)
            // Verify backup digest matches original digest
            val originalSha = computeSha256(original)
            check(sha == originalSha) { "Backup digest mismatch for ${original.name}" }
            ReprocessBackup(
                original = original,
                backup = backup,
                existingNames = preExistingNames,
                originalLength = original.length(),
                sha256 = sha
            )
        }
        val backupEntries = backups.associate { it.original.name to
            BackupEntry(
                backupName = it.backup.name,
                relativePath = it.original.name,
                originalLength = it.originalLength,
                sha256 = it.sha256
            )
        }
        val manifest = ReprocessTransactionManifest(
            transactionId = transactionId,
            createdAt = System.currentTimeMillis(),
            preExistingPaths = preExistingNames,
            backedUpPaths = backups.map { it.original.name }.toSet(),
            backupEntries = backupEntries
        )
        KeplerJobMetadata.atomicWrite(File(root, REPROCESS_TX_MANIFEST_FILE), manifest.toJson().toString(2))
        ReprocessTransaction(transactionId, root, manifest, backups)
    }.onFailure {
        root.listFiles()?.forEach { it.delete() }
        root.delete()
    }
}

/**
 * Exact rollback restore. Validates every backup (length + SHA-256) before mutating any target.
 * Stages and verifies all replacement temp files before replacing any destination.
 * Never skips replacement merely because lengths match — a worker can overwrite with same-size different data.
 * Restores job.json last.
 * Returns failure before any target mutation if any backup is corrupt/missing.
 */
internal fun restoreBackups(jobDir: File, transaction: ReprocessTransaction): Result<Unit> = runCatching {
    val root = transaction.backupRoot
    if (!root.isDirectory) throw IllegalStateException("Backup root missing for rollback")
    val manifestFile = File(root, REPROCESS_TX_MANIFEST_FILE)
    val manifest = if (manifestFile.isFile) {
        runCatching { ReprocessTransactionManifest.fromJson(JSONObject(manifestFile.readText())) }.getOrNull()
    } else null
    // Strictly read and validate the durable manifest/entries
    val currentManifest = manifest ?: transaction.manifest
    if (currentManifest.transactionId != transaction.transactionId) {
        throw IllegalStateException("Transaction ID mismatch during rollback")
    }
    val backups = transaction.backups
    // 1. Validate every required backup exists, and verify length and digest
    backups.forEach { backup ->
        check(backup.backup.isFile) { "Missing rollback backup: ${backup.original.name}" }
        check(backup.backup.length() == backup.originalLength) {
            "Invalid rollback backup length: ${backup.original.name} (was ${backup.backup.length()}, expected ${backup.originalLength})"
        }
        val backupSha = computeSha256(backup.backup)
        check(backupSha == backup.sha256) {
            "Rollback backup digest mismatch: ${backup.original.name}"
        }
    }
    // 2. Stage and verify all replacement temp files before replacing any destination
    data class StagedRestore(val backup: ReprocessBackup, val temp: File)
    val staged = mutableListOf<StagedRestore>()
    try {
        backups.forEach { backup ->
            val target = backup.original
            // Never skip replacement merely because lengths match
            val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.restore")
            backup.backup.copyTo(temp, overwrite = true)
            check(temp.length() == backup.backup.length()) { "Rollback temp verification failed: ${target.name}" }
            val tempSha = computeSha256(temp)
            check(tempSha == backup.sha256) { "Rollback temp digest failed: ${target.name}" }
            staged.add(StagedRestore(backup, temp))
        }
        // 3. Atomically replace restored non-metadata files
        // Restore job.json last
        val (jobJsonFirst, jobJsonLast) = staged.partition { it.backup.original.name != JOB_JSON_FILE_NAME }
        jobJsonFirst.forEach { stagedRestore ->
            KeplerJobMetadata.atomicReplace(stagedRestore.temp, stagedRestore.backup.original)
        }
        jobJsonLast.forEach { stagedRestore ->
            KeplerJobMetadata.atomicReplace(stagedRestore.temp, stagedRestore.backup.original)
        }
    } finally {
        // Clean up any remaining temp files
        staged.forEach { if (it.temp.exists()) runCatching { it.temp.delete() } }
    }
}

/** Narrow injectable IO seam for file deletion in cleanup. Tests can override. */
internal var cleanupDeleteOperation: (File) -> Boolean = { it.delete() }

/**
 * Safe backup cleanup that preserves terminal evidence on partial failure.
 * Cleanup is allowed only for a validated COMMITTED or ROLLED_BACK transaction.
 * - Delete known backup payloads and temporary artifacts best-effort
 * - Do NOT delete the terminal manifest while unknown/non-removable contents remain
 * - Delete the manifest last, immediately before removing an otherwise empty root
 * - An empty root left after manifest deletion is safe for recovery to remove
 * - If cleanup fails, leave a valid terminal manifest whenever possible
 * - Cleanup failure may add a warning but cannot change COMMITTED/ROLLED_BACK to QUARANTINED
 */
internal fun cleanupBackups(transaction: ReprocessTransaction): Boolean {
    val root = transaction.backupRoot
    if (!root.isDirectory) return true
    val children = root.listFiles() ?: return false
    val cleanupDelete = cleanupDeleteOperation
    // Delete known backup payloads and temp artifacts first
    val backupNames = transaction.backups.map { it.backup.name }.toSet()
    var allPayloadsDeleted = true
    children.filter { it.isFile && (it.name in backupNames || it.name.endsWith(".tmp") || it.name.endsWith(".restore")) }
        .forEach { file ->
            if (!cleanupDelete(file) && file.exists()) allPayloadsDeleted = false
        }
    // Delete quarantine marker best-effort
    children.firstOrNull { it.name == REPROCESS_QUARANTINE_MARKER }?.let { marker ->
        if (!cleanupDelete(marker) && marker.exists()) { /* best-effort; terminal state is already durable */ }
    }
    // Check if unknown/non-removable contents remain
    val remaining = root.listFiles()?.filter { it.isFile }.orEmpty()
    val manifestFile = remaining.firstOrNull { it.name == REPROCESS_TX_MANIFEST_FILE }
    val unknownContents = remaining.filter { it != manifestFile && it.name !in backupNames }
    // Delete manifest last, only when no unknown/non-removable contents remain
    if (unknownContents.isEmpty() && manifestFile != null) {
        if (!cleanupDelete(manifestFile) && manifestFile.exists()) return false
    }
    // Remove an otherwise empty root
    if (root.listFiles()?.isEmpty() == true) {
        root.delete()
    }
    return allPayloadsDeleted && (root.listFiles()?.isEmpty() == true)
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
