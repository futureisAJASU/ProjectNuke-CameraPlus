package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
import java.util.concurrent.atomic.AtomicReference
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
private const val REPROCESS_QUARANTINE_MARKER_CONTENT = "quarantined\n"
private const val REPROCESS_FALLBACK_QUARANTINE_MARKER = ".reprocess_unresolved"
private const val REPROCESS_PREVIEW_PREFIX = "reprocess_preview_"
private const val REPROCESS_PREVIEW_MAX_DIMENSION = 1600
private const val REPROCESS_HISTORY_LIMIT = 32

/** Test-only bounded-exit timeout override. Each test must restore it in `finally`. */
internal var reprocessWorkerExitTimeoutMsForTest: Long? = null
internal var reprocessTimeoutMsForTest: Long? = null

/**
 * Result of classifying a transaction manifest's state for evidence inspection.
 * Only [Resolved] allows cleanup; all other states block job mutation/deletion.
 */
internal sealed class ManifestClassification {
    data object Unresolved : ManifestClassification()
    data class Resolved(val state: ReprocessTransactionState) : ManifestClassification()
}

/**
 * Strict manifest parser/classifier. Fails closed by reusing [loadStrictManifest]:
 * - Missing/corrupt/unreadable/incomplete/unsafe manifests → Unresolved
 * - Legacy structurally-valid manifest missing only `state` → still ACTIVE (Unresolved)
 * - Only fully validated COMMITTED or ROLLED_BACK → Resolved
 * - Valid ACTIVE/QUARANTINED manifests → Unresolved
 *
 * A manifest missing backup arrays/entries is never treated as terminal-resolved, even if the
 * `state` field claims COMMITTED/ROLLED_BACK.
 */
internal fun classifyTransactionManifest(backupRoot: File): ManifestClassification {
    if (!backupRoot.isDirectory) return ManifestClassification.Unresolved
    val manifestFile = File(backupRoot, REPROCESS_TX_MANIFEST_FILE)
    if (!manifestFile.isFile) return ManifestClassification.Unresolved
    val manifest = runCatching { loadStrictManifest(manifestFile) }.getOrNull()
        ?: return ManifestClassification.Unresolved
    return when (manifest.state) {
        ReprocessTransactionState.COMMITTED,
        ReprocessTransactionState.ROLLED_BACK -> ManifestClassification.Resolved(manifest.state)
        ReprocessTransactionState.ACTIVE,
        ReprocessTransactionState.QUARANTINED -> ManifestClassification.Unresolved
    }
}

private fun classifyTransactionManifest(jobDir: File, backupRoot: File): ManifestClassification {
    val base = classifyTransactionManifest(backupRoot)
    if (base is ManifestClassification.Unresolved) return base
    val manifest = try { loadStrictManifest(File(backupRoot, REPROCESS_TX_MANIFEST_FILE)) }
    catch (_: Exception) { return ManifestClassification.Unresolved }
    val job = try { jobDir.canonicalFile } catch (_: Exception) { return ManifestClassification.Unresolved }
    val root = try { backupRoot.canonicalFile } catch (_: Exception) { return ManifestClassification.Unresolved }
    if (root.parentFile?.canonicalFile != job || root.name != ".reprocess_backup_${manifest.transactionId}") {
        return ManifestClassification.Unresolved
    }
    if (manifest.backupEntries.values.any { entry ->
            val target = File(job, entry.relativePath).canonicalFile
            val backup = File(root, entry.backupName).canonicalFile
            target.parentFile?.canonicalFile != job || backup.parentFile?.canonicalFile != root ||
                entry.relativePath == REPROCESS_TX_MANIFEST_FILE || entry.backupName == REPROCESS_TX_MANIFEST_FILE
        }) return ManifestClassification.Unresolved
    return base
}

private fun isStrictRelativePath(path: String): Boolean {
    if (path.isBlank()) return false
    if (path.contains("..")) return false
    if (path.startsWith("/")) return false
    if (path.startsWith("\\")) return false
    if (File(path).isAbsolute) return false
    val normalized = File(path).path.replace("\\", "/")
    if (normalized.contains("..")) return false
    if (normalized.startsWith("/")) return false
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
 * Transaction session: explicit ownership model for the operation lease. One authoritative
 * object stores the transaction, owning lease, and late-registration state. The lease is
 * released exactly by the path that owns it after durable COMMITTED/ROLLED_BACK, or never
 * (QUARANTINED).
 *
 * Before the ACTIVE transaction is durably written, [releaseIfUnowned] releases the lease for
 * every pre-transaction return, progress failure, exception, and cancellation in a single
 * outer `finally` of [reprocessKeplerGalleryJob]. After [transferOwnership] the outer finally
 * must never release the lease; only [finalizeTransaction] (shared settlement) does.
 *
 * The once-guard for late registration also lives here so every registration attempt — timeout,
 * cancellation, late callback — shares it.
 */
internal class ReprocessTransactionSession(val jobDir: File) {
    var lease: JobOperationLease? = null
        private set
    var transaction: ReprocessTransaction? = null
        private set
    var worker: ReprocessWorkerRun? = null
        private set
    private val ownershipTransferred = AtomicBoolean(false)
    internal enum class LateState { IDLE, LATE_REGISTERED, FINALIZING, TERMINAL, UNRESOLVED }
    private val lateState = AtomicReference(LateState.IDLE)

    fun acquireLease(): JobOperationLease? {
        val acquired = KeplerJobMetadata.acquireOperation(jobDir)
        lease = acquired
        return acquired
    }

    /** Transfer lease ownership to the durable ACTIVE transaction. After this the outer finally must NOT release. */
    fun transferOwnership(tx: ReprocessTransaction) {
        transaction = tx
        ownershipTransferred.set(true)
    }

    fun bindWorker(run: ReprocessWorkerRun) {
        worker = run
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

    /** Try to acquire the once-guard for late-finalization registration. Returns true only the first time. */
    fun tryAcquireLateRegistration(): Boolean {
        while (true) {
            val current = lateState.get()
            if (current != LateState.IDLE && current != LateState.UNRESOLVED) return false
            if (lateState.compareAndSet(current, LateState.LATE_REGISTERED)) return true
        }
    }

    /** Try to acquire the once-guard for late finalizer invocation. Returns true only the first time. */
    fun tryBeginFinalization(): Boolean = lateState.compareAndSet(LateState.LATE_REGISTERED, LateState.FINALIZING)

    /** Whether a finalization pass was already processed (prevents duplicate cross-terminal writes). */
    fun markLateTerminal() { lateState.set(LateState.TERMINAL) }
    fun markLateUnresolved() { lateState.set(LateState.UNRESOLVED) }

    /** Legacy/test seam: bind an existing transaction+lease to this session as if ownership had been
     *  transferred, so [finalizeTransactionWithLease] exercises the real strict finalizer path. */
    fun bindForLegacyFinalizer(tx: ReprocessTransaction, lease: JobOperationLease) {
        transaction = tx
        ownershipTransferred.set(true)
        this.lease = lease
    }
}

/**
 * Structured terminal acquisition result for a worker [Deferred]. Covers every confirmed terminal
 * state without beginning rollback before worker exit is confirmed. The caller must:
 * - feed [TerminalReceived] / [DeferredExceptionalCompletion] into shared settlement,
 * - object to rollback until either of those is returned
 *   alone, since a failed cancel callback does not prove worker exit),
 * - on [WorkerDidNotExitBeforeTimeout] persist unresolved/quarantine evidence and register late
 *   finalization; never begin rollback,
 * - on [CallerCancelledWhileWorkerExited] treat as terminal received and rethrow caller
 *   cancellation after settlement,
 * - on [CallerCancelledWhileWorkerActive] persist unresolved/quarantine evidence and register
 *   late finalization, then rethrow the caller cancellation.
 */
internal sealed class WorkerTerminalResult {
    /** Confirmed terminal outcome received from the worker. */
    data class TerminalReceived(val outcome: ReprocessWorkerOutcome) : WorkerTerminalResult()

    /** Deferred completed exceptionally — the worker exited with failure. Enters normal uncommitted settlement. */
    data class DeferredExceptionalCompletion(val cause: Throwable) : WorkerTerminalResult()

    /** Cancel callback invocation threw. Does NOT prove worker exit — do not begin rollback. */
    /** Worker exited after cancellation was requested. Holds the real outcome if any. */
    data class WorkerExitedAfterCancellation(
        val outcome: ReprocessWorkerOutcome?,
        val cancelFailure: Throwable?
    ) : WorkerTerminalResult()

    /** Worker did not exit before the rollback timeout. Rollback is unsafe. */
    data class WorkerDidNotExitBeforeTimeout(val cancelFailure: Throwable?) : WorkerTerminalResult()

    /** Caller cancellation arrived after the worker had already exited with a terminal outcome. */
    data class CallerCancelledWhileWorkerExited(
        val outcome: ReprocessWorkerOutcome,
        val callerCancellation: kotlinx.coroutines.CancellationException
    ) : WorkerTerminalResult()

    /** Caller cancellation arrived while the worker was still active. */
    data class CallerCancelledWhileWorkerActive(
        val callerCancellation: kotlinx.coroutines.CancellationException,
        val cancelFailure: Throwable?
    ) : WorkerTerminalResult()
}

private fun combineCause(primary: Throwable, vararg suppressed: Throwable?): Throwable {
    for (s in suppressed) if (s != null && s !== primary) {
        runCatching { primary.addSuppressed(s) }
    }
    return primary
}

/** Compact cause/suppressed helper used by rollback, cleanup, terminal metadata, quarantine, and late-finalization. */
private fun combineCauseWithMessage(primary: Throwable, message: String, suppressedFailure: Throwable?): Throwable {
    val wrapping = if (primary.message != message) RuntimeException(message, primary) else primary
    if (suppressedFailure != null && suppressedFailure !== primary) {
        runCatching { wrapping.addSuppressed(suppressedFailure) }
    }
    return wrapping
}

internal data class UnresolvedPersistenceResult(
    val rootMarkerPersisted: Boolean,
    val quarantinedStatePersisted: Boolean,
    val fallbackPersisted: Boolean,
    val error: Throwable?
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
    val session = ReprocessTransactionSession(target)
    val operationLease = session.acquireLease() ?: run {
        return@withContext Result.failure(IllegalStateException("A job mutation is already in progress."))
    }

    var capability: ReprocessCapability? = null
    var transaction: ReprocessTransaction? = null
    var kind: ReprocessJobKind = ReprocessJobKind.UNSUPPORTED
    var job: JSONObject? = null
    var reviewItems: List<KeplerFrameReviewItem> = emptyList()
    var resolvedSelection: Set<Int> = emptySet()
    var selectionMode: FrameSelectionMode = FrameSelectionMode.AUTO_RULE_BASED

    try {
        // Inspect while the single pre-ACTIVE lease boundary is still owned by this try/finally.
        // Inspection failure is unsafe and must not write ordinary failure metadata.
        val unresolved = try {
            isReprocessQuarantined(target)
        } catch (inspectionFailure: Exception) {
            return@withContext Result.failure(
                IllegalStateException("Unable to inspect existing reprocess transaction evidence.", inspectionFailure)
            )
        }
        // Pre-transaction phase: outer `finally` (below) releases the lease via the session for any
        // pre-transaction return, progress failure, ordinary exception, or cancellation. We do NOT
        // call releaseIfUnowned() inline — the single finally handles it.
        if (unresolved) {
            return@withContext Result.failure(
                IllegalStateException("다시 합성을 진행할 수 없습니다. 이 작업은 격리되었습니다.")
            )
        }
        capability = detectReprocessCapability(context, target)
        if (!capability.canReprocess) {
            // The job is purely unsupported (no unresolved evidence); safe to record terminal metadata.
            writeReprocessFailure(target, capability.reason)
            return@withContext Result.failure(IllegalStateException(capability.reason))
        }
        postProgress("원본 프레임 확인 중…")
        job = try {
            KeplerJobMetadata.read(target)
        } catch (metadataError: KeplerJobMetadataException) {
            writeReprocessFailure(target, "${metadataError.javaClass.simpleName}: ${metadataError.message}")
            return@withContext Result.failure(metadataError)
        }
        kind = detectJobKind(target, job)
        reviewItems = loadFrameReviewItems(context, target).getOrElse {
            writeReprocessFailure(target, "${it.javaClass.simpleName}: ${it.message}")
            return@withContext Result.failure(it)
        }
        resolvedSelection = resolveFrameSelection(target, kind, reviewItems, frameSelection).getOrElse {
            writeReprocessFailure(target, "${it.javaClass.simpleName}: ${it.message}")
            return@withContext Result.failure(it)
        }
        if (resolvedSelection.size < requiredSelectedFrameCount(kind)) {
            val message = "선택한 원본 프레임이 부족합니다. 다시 합성할 수 없습니다."
            writeReprocessFailure(target, message)
            return@withContext Result.failure(IllegalStateException(message))
        }
        selectionMode = resolveSelectionMode(job, frameSelection)
        transaction = backupReprocessTransaction(
            target,
            target.listFiles()?.filter { it.isFile && isReprocessWorkerWritable(it) }.orEmpty(),
            job = job,
            jobKind = kind
        ).getOrElse {
            writeReprocessFailure(target, "Required reprocess backup failed: ${it.message}")
            return@withContext Result.failure(it)
        }

        // Ownership transfer exactly once: ACTIVE manifest is now durably persisted and validated
        // against the in-memory transaction. Strict identity must hold before any post-ACTIVE work;
        // if it does not, the durable manifest is authoritative and we quarantine without mutation.
        session.transferOwnership(transaction)
        if (!validateTransactionIdentity(target, transaction)) {
            return@withContext quarantineWithPersistence(
                transaction,
                IllegalStateException("Transaction identity validation failed after ACTIVE creation")
            ).result
        }

        // After ownership transfer the outer `finally` must NEVER release the lease; only shared
        // settlement in [finalizeTransaction] releases it after durable COMMITTED/ROLLED_BACK.

        postProgress("프레임 선택 적용 중…")
        saveFrameSelectionInternal(
            jobDir = target,
            mode = selectionMode,
            frames = applyFrameSelectionToItems(reviewItems, resolvedSelection, selectionMode),
            operationLease = operationLease
        ).getOrElse {
            return@withContext finalizeTransaction(
                session, transaction, target, capability.jobKind, outputSettings, selectionMode,
                resolvedSelection, Result.failure(it)
            ).result
        }
        check(KeplerJobMetadata.isOperationOwner(target, operationLease)) {
            "Reprocess operation lease ownership lost before worker start."
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
        session.bindWorker(worker)

        // Structured terminal acquisition owns the initial timeout, cancellation callback, and
        // bounded terminal wait. Rollback is never started before it reports a confirmed exit.
        val terminalOutcome = acquireWorkerTerminal(worker, callerCancellation = null)
        val finalization = settleTerminalResult(
            session, transaction, target, capability.jobKind, outputSettings, selectionMode,
            resolvedSelection, worker, terminalOutcome
        )
        return@withContext finalization.result
    } catch (callerCancellation: kotlinx.coroutines.CancellationException) {
        // A caller cancellation has one owner: this branch. It either safely settles a confirmed
        // worker exit or durably quarantines and hands late completion to the session callback.
        val tx = transaction
        val worker = session.worker
        if (tx != null && worker != null) {
            val acquisition = acquireWorkerTerminal(worker, callerCancellation)
            val settled = settleTerminalResult(
                session, tx, target, kind, outputSettings, selectionMode, resolvedSelection,
                worker, acquisition
            )
            settled.result.exceptionOrNull()?.let { settlementFailure ->
                try { callerCancellation.addSuppressed(settlementFailure) } catch (_: Exception) { }
            }
        } else if (tx != null) {
            val settled = finalizeTransaction(
                session, tx, target, kind, outputSettings, selectionMode, resolvedSelection,
                Result.failure(callerCancellation)
            )
            settled.result.exceptionOrNull()?.let { settlementFailure ->
                try { callerCancellation.addSuppressed(settlementFailure) } catch (_: Exception) { }
            }
        }
        throw callerCancellation
    } catch (unexpected: Exception) {
        val tx = transaction ?: run {
            // Pre-ACTIVE unexpected: metadata is untouched beneath the lease; outer finally releases it.
            return@withContext Result.failure(unexpected)
        }
        return@withContext finalizeTransaction(
            session, tx, target, kind,
            outputSettings, selectionMode, resolvedSelection,
            Result.failure(unexpected)
        ).result
    } finally {
        // One authority for pre-transaction release; after ownership transfer the outer cleanup
        // NEVER releases the lease — only shared settlement does.
        session.releaseIfUnowned()
    }
}
/**
 * Structured worker terminal acquisition. Replaces ad-hoc `withTimeoutOrNull` + throwing cancel.
 * Does not begin rollback until worker exit is confirmed. Preserves cancel failure, terminal
 * failure, timeout, and caller cancellation through cause/suppressed linkage. Does not convert or
 * swallow unrelated fatal [Error]s (OOM, ThreadDeath).
 */
internal suspend fun acquireWorkerTerminal(
    worker: ReprocessWorkerRun,
    callerCancellation: kotlinx.coroutines.CancellationException?
): WorkerTerminalResult {
    val terminal = worker.terminal
    if (terminal.isCompleted) {
        return try {
            val outcome = terminal.await()
            if (callerCancellation != null) {
                WorkerTerminalResult.CallerCancelledWhileWorkerExited(outcome, callerCancellation)
            } else WorkerTerminalResult.TerminalReceived(outcome)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            WorkerTerminalResult.DeferredExceptionalCompletion(failure)
        } catch (failure: Exception) {
            WorkerTerminalResult.DeferredExceptionalCompletion(failure)
        }
    }
    // The ordinary operation gets its full reprocess timeout before cancellation is requested.
    // A caller cancellation, on the other hand, requests cancellation immediately.
    if (callerCancellation == null && !terminal.isCompleted) {
        try {
            val initial = withTimeoutOrNull(reprocessTimeoutMsForTest ?: REPROCESS_TIMEOUT_MS) {
                terminal.await()
            }
            if (initial != null) return WorkerTerminalResult.TerminalReceived(initial)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            return WorkerTerminalResult.DeferredExceptionalCompletion(cancelled)
        } catch (deferredFailure: Exception) {
            return WorkerTerminalResult.DeferredExceptionalCompletion(deferredFailure)
        }
    }
    return withContext(NonCancellable) {
    val cancelFailure: Throwable? = try {
        worker.cancel()
        null
    } catch (cancelError: Exception) {
        cancelError
    }
    val exitOutcome = try {
        withTimeoutOrNull(reprocessWorkerExitTimeoutMsForTest ?: REPROCESS_WORKER_EXIT_TIMEOUT_MS) {
            terminal.await()
        }
    } catch (deferredExceptional: Error) {
        throw deferredExceptional
    } catch (deferredExceptional: Exception) {
        // Deferred completed exceptionally ⇒ worker exited with failure.
        return@withContext WorkerTerminalResult.DeferredExceptionalCompletion(combineCause(deferredExceptional, cancelFailure))
    }
    if (exitOutcome != null) {
        return@withContext if (callerCancellation != null) {
            WorkerTerminalResult.CallerCancelledWhileWorkerExited(exitOutcome, callerCancellation)
        } else {
            WorkerTerminalResult.WorkerExitedAfterCancellation(exitOutcome, cancelFailure)
        }
    }
    // Worker did not exit before timeout. Rollback is unsafe.
    if (callerCancellation != null) {
        return@withContext WorkerTerminalResult.CallerCancelledWhileWorkerActive(
            callerCancellation, cancelFailure
        )
    }
    return@withContext WorkerTerminalResult.WorkerDidNotExitBeforeTimeout(cancelFailure)
    }
}

/**
 * Map a [WorkerTerminalResult] into shared settlement, registering late finalization when the
 * worker exit is uncertain. Quarantine + late-finalization paths never release the lease.
 */
private fun settleTerminalResult(
    session: ReprocessTransactionSession,
    transaction: ReprocessTransaction?,
    target: File,
    jobKind: ReprocessJobKind,
    outputSettings: FinalOutputFormat,
    selectionMode: FrameSelectionMode,
    resolvedSelection: Set<Int>,
    worker: ReprocessWorkerRun?,
    acquisition: WorkerTerminalResult
): ReprocessFinalizationResult {
    if (transaction == null) {
        return ReprocessFinalizationResult(
            ReprocessFinalizationState.QUARANTINED,
            Result.failure(IllegalStateException("No transaction available to settle terminal result."))
        )
    }
    return when (acquisition) {
        is WorkerTerminalResult.TerminalReceived -> finalizeTransaction(
            session, transaction, target, jobKind, outputSettings, selectionMode,
            resolvedSelection, Result.success(acquisition.outcome)
        )
        is WorkerTerminalResult.DeferredExceptionalCompletion -> finalizeTransaction(
            session, transaction, target, jobKind, outputSettings, selectionMode,
            resolvedSelection, Result.failure(acquisition.cause)
        )
        is WorkerTerminalResult.WorkerExitedAfterCancellation -> finalizeTransaction(
            session, transaction, target, jobKind, outputSettings, selectionMode,
            resolvedSelection, acquisition.outcome?.let { Result.success(it) }
                ?: Result.failure(
                    combineCause(
                        IllegalStateException("Worker exited after cancellation with no outcome."),
                        acquisition.cancelFailure
                    )
                )
        )
        is WorkerTerminalResult.CallerCancelledWhileWorkerExited -> finalizeTransaction(
            session, transaction, target, jobKind, outputSettings, selectionMode,
            resolvedSelection, Result.success(acquisition.outcome)
        )
        is WorkerTerminalResult.WorkerDidNotExitBeforeTimeout -> {
            val persistence = persistUnresolvedQuarantine(session, transaction, acquisition.cancelFailure)
            registerLateFinalization(session, worker, target, jobKind, outputSettings, selectionMode, resolvedSelection)
            ReprocessFinalizationResult(
                ReprocessFinalizationState.QUARANTINED,
                Result.failure(combineCauseWithMessage(
                    IllegalStateException("Reprocess worker did not exit before rollback timeout."),
                    "Unresolved persistence failed", persistence.error
                ))
            )
        }
        is WorkerTerminalResult.CallerCancelledWhileWorkerActive -> {
            val persistence = persistUnresolvedQuarantine(session, transaction, acquisition.cancelFailure)
            registerLateFinalization(session, worker, target, jobKind, outputSettings, selectionMode, resolvedSelection)
            ReprocessFinalizationResult(
                ReprocessFinalizationState.QUARANTINED,
                Result.failure(combineCause(acquisition.callerCancellation, persistence.error))
            )
        }
    }
}

/**
 * Holds the session, owning lease, and late-registration state for one late finalization pass.
 * Shared between timeout/cancellation registration and the eventual completion callback so every
 * attempt shares the same once-guard.
 */
internal data class ReprocessLateFinalizationHandoff(
    val session: ReprocessTransactionSession,
    val transaction: ReprocessTransaction,
    val lease: JobOperationLease,
    val target: File,
    val jobKind: ReprocessJobKind,
    val outputSettings: FinalOutputFormat,
    val selectionMode: FrameSelectionMode,
    val resolvedSelection: Set<Int>,
    val workerTerminal: Deferred<ReprocessWorkerOutcome>? = null,
    val workerOutcomeSnapshot: ReprocessWorkerOutcome = noOutcomeSnapshot
) {
    companion object
}

private val noOutcomeSnapshot: ReprocessWorkerOutcome = ReprocessWorkerOutcome(
    result = Result.failure(IllegalStateException("Late finalization invoked without a worker outcome.")),
    publicExportCommitted = false,
    exportVerified = false
)

/**
 * Narrow internal test seam: the latest late-finalization handoff registered for the current
 * session. Tests invoke [runLateFinalization] through this handoff (the real callback path); they
 * must reset it in `finally`. Production code does not read this outside [registerLateFinalization].
 */
internal var lateFinalizationHandoffScope: ReprocessLateFinalizationHandoff? = null

/**
 * Once-only late-finalization registration. Uses the session shared once-guard so duplicate
 * registration (timeout then cancellation, or racing completion) is ignored. The detached IO scope
 * survives caller cancellation; the late callback invokes shared settlement exactly once via
 * [runLateFinalization]. Late finalization failure retains backups and the lease (QUARANTINED).
 */
internal fun registerLateFinalization(
    session: ReprocessTransactionSession,
    worker: ReprocessWorkerRun?,
    target: File,
    jobKind: ReprocessJobKind,
    outputSettings: FinalOutputFormat,
    selectionMode: FrameSelectionMode,
    resolvedSelection: Set<Int>
): ReprocessLateFinalizationHandoff? {
    val tx = session.transaction ?: return null
    val lease = session.lease ?: return null
    if (!session.tryAcquireLateRegistration()) return null
    val handoff = ReprocessLateFinalizationHandoff(
        session, tx, lease, target, jobKind, outputSettings, selectionMode,
        resolvedSelection, workerTerminal = worker?.terminal
    )
    lateFinalizationHandoffScope = handoff
    worker?.terminal?.invokeOnCompletion { cause ->
        // Completion that races immediately before callback registration still finalizes once
        // because [runLateFinalization] re-checks the session once-guard. The detached IO scope
        // survives caller cancellation; the late callback owns settlement of the retained lease.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runLateFinalization(handoff, cause)
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: ThreadDeath) {
                throw e
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Detached late finalization was cancelled: retain quarantine and lease.
            } catch (_: Exception) {
                ensureDurableFallbackQuarantine(handoff.target, handoff.transaction)
            }
        }
    }
    return handoff
}

/**
 * Late-finalization entry point. Invokes shared settlement exactly once using the worker terminal
 * outcome (awaited from the deferred), the completion [cause], or a snapshot. Exceptional deferred
 * completion enters shared settlement as a worker failure. Never silently swallows callback exceptions:
 * ordinary callback failure leaves durable unresolved evidence and retains the lease.
 */
internal suspend fun runLateFinalization(handoff: ReprocessLateFinalizationHandoff, completionCause: Throwable?) {
    if (!handoff.session.tryBeginFinalization()) return
    val outcome: Result<ReprocessWorkerOutcome> = try {
        val terminal = handoff.workerTerminal
        if (terminal != null && terminal.isCompleted) {
            val awaited = try {
                Result.success(terminal.await())
            } catch (deferredCancellation: kotlinx.coroutines.CancellationException) {
                if (!terminal.isCancelled) throw deferredCancellation
                Result.failure<ReprocessWorkerOutcome>(combineCause(deferredCancellation, completionCause))
            } catch (fatal: Error) {
                throw fatal
            } catch (deferredFailure: Exception) {
                Result.failure<ReprocessWorkerOutcome>(combineCause(deferredFailure, completionCause))
            }
            awaited
            /* awaited result already carries explicit success/failure */
            /*
                onFailure = { deferredFailure ->
                    // Exceptional deferred completion ⇒ worker exited with failure.
                    Result.failure<ReprocessWorkerOutcome>(combineCause(deferredFailure, completionCause))
                }
            )
            */
        } else if (completionCause != null) {
            Result.failure<ReprocessWorkerOutcome>(
                combineCause(
                    IllegalStateException("Reprocess late finalization after completion cause."),
                    completionCause
                )
            )
        } else {
            Result.success(handoff.workerOutcomeSnapshot)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: OutOfMemoryError) {
        throw e
    } catch (e: ThreadDeath) {
        throw e
    } catch (e: Exception) {
        handoff.session.markLateUnresolved()
        ensureDurableFallbackQuarantine(handoff.target, handoff.transaction)
        return
    }
    val late = finalizeTransaction(
        handoff.session, handoff.transaction, handoff.target, handoff.jobKind,
        handoff.outputSettings, handoff.selectionMode, handoff.resolvedSelection, outcome
    )
    if (late.state == ReprocessFinalizationState.QUARANTINED) {
        handoff.session.markLateUnresolved()
        ensureDurableFallbackQuarantine(handoff.target, handoff.transaction)
    } else {
        handoff.session.markLateTerminal()
    }
}


/**
 * Single authoritative transaction finalizer. All post-transaction outcomes (success, failure,
 * cancellation, timeout, late completion) route through here. Enforces monotonically terminal
 * states and idempotency. Handles lease release exactly once after durable COMMITTED or ROLLED_BACK;
 * QUARANTINED retains the lease.
 *
 * A duplicate finalization of an already-terminal transaction performs no second restore, no second
 * terminal metadata write, no cross-terminal transition, does not acquire or retain a new lease, and
 * returns a result consistent with the existing terminal state.
 *
 * A committed public export never invokes restore, even when terminal metadata, checkpoint clearing,
 * sidecar, diagnostics, or cleanup fails.
 *
 * Identity is strictly verified before any state transition, rollback, or cleanup uses the durable
 * manifest; the durable manifest is authoritative and never falls back to the in-memory snapshot.
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
 *   2. Attempt durable marker persistence (including durable fallback)
 *   3. Attempt durable QUARANTINED state
 *   4. Retain backups and the lease
 */
internal fun finalizeTransaction(
    session: ReprocessTransactionSession,
    transaction: ReprocessTransaction,
    jobDir: File,
    jobKind: ReprocessJobKind,
    outputSettings: FinalOutputFormat,
    selectionMode: FrameSelectionMode,
    includedFrameIndices: Set<Int>,
    terminal: Result<ReprocessWorkerOutcome>
): ReprocessFinalizationResult {
    val operationLease = session.lease
    // Strict identity verification BEFORE any mutation. The durable manifest is authoritative.
    if (!validateTransactionIdentity(jobDir, transaction)) {
        // Backup-root evidence unavailable: durable fallback quarantine marker survives process death.
        ensureDurableFallbackQuarantine(jobDir, transaction)
        return ReprocessFinalizationResult(
            ReprocessFinalizationState.QUARANTINED,
            Result.failure(IllegalStateException("Transaction identity validation failed during finalization"))
        )
    }
    val currentManifest = loadStrictManifest(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE))
    val outcome = terminal.getOrElse { terminalError ->
        // Exceptional Deferred completion is a confirmed worker exit, not an unresolved timeout.
        // It therefore follows the ordinary uncommitted rollback settlement path.
        ReprocessWorkerOutcome(
            result = Result.failure(terminalError),
            publicExportCommitted = false,
            disposition = ReprocessTerminalDisposition.UNCOMMITTED_FAILURE,
            terminalError = terminalError
        )
    }
    // Idempotency: an already terminal transaction is never re-resolved.
    when (currentManifest.state) {
        ReprocessTransactionState.COMMITTED -> {
            if (operationLease != null && KeplerJobMetadata.isOperationOwner(jobDir, operationLease)) operationLease.release()
            return ReprocessFinalizationResult(
                ReprocessFinalizationState.COMMITTED, Result.success(
                    KeplerReprocessResult(jobDir, jobKind, null, null, 0L, listOf("Already finalized: COMMITTED"))
                )
            )
        }
        ReprocessTransactionState.ROLLED_BACK -> {
            if (operationLease != null && KeplerJobMetadata.isOperationOwner(jobDir, operationLease)) operationLease.release()
            return ReprocessFinalizationResult(
                ReprocessFinalizationState.ROLLED_BACK, Result.failure(IllegalStateException("Already finalized: ROLLED_BACK"))
            )
        }
        ReprocessTransactionState.ACTIVE, ReprocessTransactionState.QUARANTINED -> Unit
    }
    val ownedLease = operationLease ?: return ReprocessFinalizationResult(
        ReprocessFinalizationState.QUARANTINED,
        Result.failure(IllegalStateException("Operation lease missing for finalization"))
    )
    if (!KeplerJobMetadata.isOperationOwner(jobDir, ownedLease)) return ReprocessFinalizationResult(
        ReprocessFinalizationState.QUARANTINED,
        Result.failure(IllegalStateException("Operation lease owner mismatch during finalization"))
    )

    val hasUsableOutput = outcome.publicExportCommitted || outcome.exportVerified ||
        (outcome.finalOutputFile?.isFile == true && outcome.finalOutputFile.length() > 0L)
    val shouldCommit = (outcome.disposition == ReprocessTerminalDisposition.VERIFIED_SUCCESS ||
        outcome.disposition == ReprocessTerminalDisposition.COMMITTED_PARTIAL ||
        outcome.publicExportCommitted) && hasUsableOutput

    return if (shouldCommit) {
        val commitResult = runCatching {
            finalizeReprocessOutcome(
                jobDir, jobKind, outputSettings, selectionMode, includedFrameIndices, outcome, transaction
            )
        }
        commitResult.fold(
            onSuccess = { committed ->
                try {
                    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
                } catch (e: Exception) {
                    if (e is OutOfMemoryError || e is ThreadDeath) throw e
                    return@fold quarantineWithPersistence(transaction, e)
                }
                runCatching { removeQuarantineMarker(transaction) }
                if (!removeMatchingFallbackQuarantine(jobDir, transaction)) {
                    runCatching {
                        KeplerJobMetadata.update(jobDir) {
                            it.put("reprocessWarning", "Fallback quarantine marker deletion failed after commit. Root preserved for retry.")
                        }
                    }
                    try { ownedLease.release() } catch (_: Exception) {}
                    return@fold ReprocessFinalizationResult(ReprocessFinalizationState.COMMITTED, Result.success(committed))
                }
                runCatching { cleanupBackups(transaction) }
                try { ownedLease.release() } catch (_: Exception) {}
                ReprocessFinalizationResult(ReprocessFinalizationState.COMMITTED, Result.success(committed))
            },
            onFailure = { metadataError ->
                if (outcome.publicExportCommitted) {
                    // Public export committed but terminal metadata failed: quarantine, never rollback.
                    return@fold quarantineWithPersistence(transaction, metadataError)
                }
                rollback(transaction, ownedLease, jobDir, jobKind, outcome, metadataError)
            }
        )
    } else {
        rollback(transaction, ownedLease, jobDir, jobKind, outcome,
            outcome.terminalError ?: IllegalStateException("Reprocess worker failed."))
    }
}

/**
 * Legacy entry compatible with [ReprocessTransaction]-and-lease tests. Constructs a transient
 * session so the strict finalizer's lease/identity/idempotency checks are exercised for real.
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
    val session = ReprocessTransactionSession(jobDir)
    session.bindForLegacyFinalizer(transaction, operationLease)
    return finalizeTransaction(
        session, transaction, jobDir, jobKind, outputSettings, selectionMode,
        includedFrameIndices, terminal
    )
}


/** Quarantine with durable marker + state persistence. Retains lease. Preserves the original error
 *  and links marker/state failures through suppressed attachments. */
internal fun quarantineWithPersistence(
    transaction: ReprocessTransaction,
    originalError: Throwable
): ReprocessFinalizationResult {
    val canonicalError = originalError
    var markerError: Throwable? = null
    var stateError: Throwable? = null
    try {
        runCatching { writeQuarantineMarker(transaction) }
            .exceptionOrNull()?.let { markerError = it }
    } catch (e: Exception) {
        if (e is OutOfMemoryError || e is ThreadDeath) throw e
        markerError = e
    }
    try {
        writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED)
    } catch (e: Exception) {
        if (e is OutOfMemoryError || e is ThreadDeath) throw e
        stateError = e
    }
    val combined = if (markerError != null || stateError != null) {
        combineCauseWithMessage(canonicalError, "Quarantine persistence failed after processing error", markerError)
            .also { stateError?.let { se -> runCatching { it.addSuppressed(se) } } }
    } else canonicalError
    if (markerError != null && stateError != null) {
        try {
            ensureDurableFallbackQuarantine(transaction.backupRoot.parentFile ?: transaction.backupRoot, transaction)
        } catch (fallbackError: Exception) {
            return ReprocessFinalizationResult(
                ReprocessFinalizationState.QUARANTINED,
                Result.failure(combineCauseWithMessage(combined, "Fallback quarantine persistence failed", fallbackError))
            )
        }
    }
    return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(combined))
}

/**
 * Exact rollback path. Returns ROLLED_BACK (lease released) or QUARANTINED (lease retained).
 * Does not begin target mutation until every backup and the durable manifest have been validated.
 * Preserves the original error and links restore/deletion/state failures through suppressed attachments.
 */
internal fun rollback(
    transaction: ReprocessTransaction,
    operationLease: JobOperationLease,
    jobDir: File,
    jobKind: ReprocessJobKind,
    outcome: ReprocessWorkerOutcome,
    error: Throwable
): ReprocessFinalizationResult {
    val restore = restoreBackups(jobDir, transaction)
    if (restore.isFailure) {
        val restoreError = restore.exceptionOrNull()
            ?: IllegalStateException("Rollback restore failed without a cause")
        val linked = combineCauseWithMessage(
            error, "Rollback restore failed: ${restoreError.message}", restoreError
        )
        return quarantineWithPersistence(transaction, linked)
    }
    removeTransactionCreatedFiles(jobDir, transaction, error)
        .exceptionOrNull()?.let { deleteError ->
            return quarantineWithPersistence(
                transaction,
                combineCauseWithMessage(error, "Created-file deletion failed during rollback", deleteError)
            )
        }
    val metadataError = try {
        if (outcome.disposition == ReprocessTerminalDisposition.CANCELLED) {
            writeReprocessCancelled(jobDir, error.message)
        } else {
            writeReprocessFailure(jobDir, "${error.javaClass.simpleName}: ${error.message}")
        }
        null
    } catch (metadataFailure: Exception) {
        if (metadataFailure is OutOfMemoryError || metadataFailure is ThreadDeath) throw metadataFailure
        metadataFailure
    }
    if (metadataError != null) {
        return quarantineWithPersistence(
            transaction, combineCauseWithMessage(error, "Terminal metadata failure during rollback", metadataError)
        )
    }
    try {
        writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    } catch (stateFailure: Exception) {
        if (stateFailure is OutOfMemoryError || stateFailure is ThreadDeath) throw stateFailure
        return quarantineWithPersistence(
            transaction, combineCauseWithMessage(error, "State persistence failure during rollback", stateFailure)
        )
    }
    if (!removeMatchingFallbackQuarantine(jobDir, transaction)) {
        return quarantineWithPersistence(
            transaction,
            combineCauseWithMessage(error, "Fallback marker deletion failed during rollback, quarantining", null)
        )
    }
    runCatching { removeQuarantineMarker(transaction) }
    val cleanupSuccess = runCatching { cleanupBackups(transaction) }.getOrDefault(false)
    if (!cleanupSuccess) {
        runCatching {
            KeplerJobMetadata.update(jobDir) {
                it.put("reprocessWarning", "Reprocess backup cleanup failed after safe rollback.")
            }
        }
    }
    // Lease release only after durable ROLLED_BACK; QUARANTINED retains it.
    try { operationLease.release() } catch (_: Throwable) {}
    return ReprocessFinalizationResult(ReprocessFinalizationState.ROLLED_BACK, Result.failure(error))
}

/**
 * Remove files created by this transaction that are safe, mutable, and proven not to have existed
 * before the transaction. Immutable source frames and unrelated pre-existing files are untouched.
 * Deletion failure means rollback is not safely complete and must quarantine.
 */
private fun removeTransactionCreatedFiles(
    jobDir: File,
    transaction: ReprocessTransaction,
    originalError: Throwable
): Result<Unit> = runCatching {
    val manifest = loadStrictManifest(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE))
    require(manifest.hasSameImmutableIdentity(transaction.manifest)) {
        "Durable manifest identity changed before created-file rollback"
    }
    val preExisting = manifest.preExistingPaths
    val backedUp = manifest.backedUpPaths
    val backupRootName = transaction.backupRoot.name
    val backupRootCanonical = transaction.backupRoot.canonicalFile
    jobDir.listFiles()?.filter { it.isFile }?.forEach { file ->
        val name = file.name
        if (name == JOB_JSON_FILE_NAME) return@forEach
        if (name == backupRootName) return@forEach
        if (name in preExisting) return@forEach
        if (name in backedUp) return@forEach
        if (!isReprocessCreatedOutputFile(name)) return@forEach
        // Defensive: never delete anything inside or matching the backup root.
        if (file.canonicalFile == backupRootCanonical) return@forEach
        if (!runCatching { file.delete() }.getOrDefault(false) && file.exists()) {
            throw combineCauseWithMessage(
                originalError,
                "Created-file deletion failed: $name",
                IllegalStateException("Failed to delete created file: $name")
            )
        }
    }
}

/** Narrow test seam for the real created-file removal path. */
internal fun removeTransactionCreatedFilesForTest(
    jobDir: File,
    transaction: ReprocessTransaction
): Result<Unit> = removeTransactionCreatedFiles(
    jobDir,
    transaction,
    IllegalStateException("Protocol test created-file cleanup")
)

private fun isReprocessCreatedOutputFile(name: String): Boolean {
    val lower = name.lowercase(Locale.US)
    val explicit = setOf(
        "sharpened_night_fusion.png", "average_color_rotated.png", "denoise_color.png",
        "fused_classic_yuv_v1.png", "reference_frame.png", "raw_fusion_final.png"
    )
    if (lower in explicit || lower.startsWith("fused_classic_yuv_v1_") ||
        lower.startsWith("raw_yuv_comparison") || lower.startsWith("yuv_raw_comparison") ||
        lower.startsWith("raw_native_") || lower.startsWith("raw_intermediate_") ||
        lower.startsWith("current_diagnostic_")) return true
    if (name.startsWith(REPROCESS_PREVIEW_PREFIX)) return true
    if (lower.contains("final") || lower.contains("preview") || lower.contains("diagnostic")) return true
    if (lower.endsWith(".tmp") || lower.endsWith(".restore")) return true
    if (lower.startsWith("merged_raw") || lower.contains("merged_yuv") || lower.contains("intermediate")) return true
    if (lower.endsWith(".rgba") || lower.endsWith(".rgb") || lower.endsWith(".bin")) return true
    return false
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

/**
 * Tests-only helper that cancels a worker and awaits its terminal outcome. Production code uses
 * [acquireWorkerTerminal] which preserves cancel-failure, timeout, and caller-cancellation linkage.
 */
internal suspend fun cancelWorkerAndAwaitTerminal(
    worker: ReprocessWorkerRun
): Result<ReprocessWorkerOutcome> = withContext(NonCancellable) {
    val result = acquireWorkerTerminal(worker, callerCancellation = null)
    when (result) {
        is WorkerTerminalResult.TerminalReceived -> Result.success(result.outcome)
        is WorkerTerminalResult.WorkerExitedAfterCancellation ->
            result.outcome?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Worker exited with no outcome."))
        is WorkerTerminalResult.DeferredExceptionalCompletion -> Result.failure(result.cause)
        is WorkerTerminalResult.WorkerDidNotExitBeforeTimeout ->
            Result.failure(ReprocessWorkerDidNotExitException("Reprocess worker did not exit before rollback timeout."))
        is WorkerTerminalResult.CallerCancelledWhileWorkerExited -> Result.success(result.outcome)
        is WorkerTerminalResult.CallerCancelledWhileWorkerActive ->
            Result.failure(result.callerCancellation)
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
/** Narrow test seam for quarantine marker file write. Tests can override and must reset in `finally`. */
internal var quarantineMarkerWriteOperation: ((File, String) -> Unit)? = null

/**
 * Persist a quarantine marker in the transaction backup directory. Retains the backups.
 * Throws on failure — quarantine must be durable or the transaction is unresolved.
 * Fails if the backup root is missing, not a directory, or marker write/sync fails.
 * An existing path that is a directory, unreadable, corrupt, empty, or non-canonical is
 * treated as a persistence failure — not silently accepted as success.
 * After writing, the content is reread and verified to be exactly canonical.
 */
internal fun writeQuarantineMarker(transaction: ReprocessTransaction) {
    val root = transaction.backupRoot
    check(root.isDirectory) { "Quarantine marker write failed: backup root missing or not a directory: $root" }
    val marker = File(root, REPROCESS_QUARANTINE_MARKER)
    if (marker.exists()) {
        check(marker.isFile) { "Quarantine marker path exists but is not a regular file: $marker" }
        val content = runCatching { marker.readText() }.getOrNull()
        check(content == REPROCESS_QUARANTINE_MARKER_CONTENT) { "Quarantine marker exists but content is not canonical: $marker" }
        return
    }
    val writeOp = quarantineMarkerWriteOperation
    if (writeOp != null) {
        writeOp(marker, REPROCESS_QUARANTINE_MARKER_CONTENT)
    } else {
        KeplerJobMetadata.atomicWrite(marker, REPROCESS_QUARANTINE_MARKER_CONTENT)
    }
    check(marker.isFile) { "Quarantine marker write produced no file: $marker" }
    val writtenContent = runCatching { marker.readText() }.getOrNull()
    check(writtenContent == REPROCESS_QUARANTINE_MARKER_CONTENT) { "Quarantine marker content verification failed after write: $marker" }
}

/**
 * Single authoritative atomic state writer. Validates monotonic state transitions and durable
 * transaction identity using the strict manifest parser. Throws on any failure — state persistence
 * is mandatory and must not be silently ignored. Failure leaves the transaction unresolved and the
 * caller must not release the operation lease. Rejects terminal → ACTIVE/QUARANTINED and any
 * cross-terminal transition; same-terminal writes are idempotent.
 */
internal fun writeTransactionState(transaction: ReprocessTransaction, state: ReprocessTransactionState) {
    val root = transaction.backupRoot
    val manifestFile = File(root, REPROCESS_TX_MANIFEST_FILE)
    require(manifestFile.isFile) { "Transaction manifest missing for state write: $state" }
    val currentManifest = loadStrictManifest(manifestFile)
    require(currentManifest.transactionId == transaction.transactionId) {
        "Transaction ID mismatch: manifest=${currentManifest.transactionId}, expected=${transaction.transactionId}"
    }
    require(currentManifest.createdAt == transaction.manifest.createdAt) {
        "Transaction createdAt mismatch during state write"
    }
    require(currentManifest.hasSameImmutableIdentity(transaction.manifest)) {
        "Transaction manifest drift during state write"
    }
    val currentState = currentManifest.state
    // Idempotent for same-terminal writes
    if (currentState == state) return
    validateStateTransition(currentState, state)
    val updatedManifest = currentManifest.copy(state = state)
    KeplerJobMetadata.atomicWrite(manifestFile, updatedManifest.toJson().toString(2))
}

/**
 * Persist durable unresolved/quarantine evidence when a worker exit is uncertain (timeout, cancel
 * callback failure, caller cancellation while active). Tries the in-backup-root quarantine marker
 * first, then the job-level durable fallback marker. Never throws — callers rethrow caller
 * cancellation. Always registers late finalization afterwards through [settleTerminalResult].
 */
internal fun persistUnresolvedQuarantine(
    session: ReprocessTransactionSession,
    transaction: ReprocessTransaction,
    cancelFailure: Throwable?
): UnresolvedPersistenceResult {
    var markerFailure: Throwable? = null
    var stateFailure: Throwable? = null
    try { writeQuarantineMarker(transaction) } catch (e: Exception) { markerFailure = e }
    try { writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED) }
    catch (e: Exception) { stateFailure = e }
    val rootEvidence = markerFailure == null || stateFailure == null
    var fallbackFailure: Throwable? = null
    var fallbackPersisted = false
    if (!rootEvidence) {
        try {
            ensureDurableFallbackQuarantine(transaction.backupRoot.parentFile ?: transaction.backupRoot, transaction)
            fallbackPersisted = true
        } catch (e: Exception) { fallbackFailure = e }
    }
    val combined = listOfNotNull(cancelFailure, markerFailure, stateFailure, fallbackFailure)
        .firstOrNull()?.also { primary -> combineCause(primary, *listOfNotNull(markerFailure, stateFailure, fallbackFailure).toTypedArray()) }
    return UnresolvedPersistenceResult(
        rootMarkerPersisted = markerFailure == null,
        quarantinedStatePersisted = stateFailure == null,
        fallbackPersisted = fallbackPersisted,
        error = if (rootEvidence || fallbackPersisted) combined else (combined ?: IllegalStateException("Unresolved persistence failed"))
    )
}

/**
 * Job-level durable fallback quarantine marker. Survives process death when the backup-root marker
 * or in-backup-root QUARANTINED state cannot be persisted. Inspected by [isReprocessQuarantined]
 * and [recoverValidatedQuarantine] so process restart never treats a missing backup root as safe.
 */
internal fun ensureDurableFallbackQuarantine(jobDir: File, transaction: ReprocessTransaction) {
    check(jobDir.isDirectory) { "Job directory unavailable for fallback quarantine" }
    val marker = File(jobDir, REPROCESS_FALLBACK_QUARANTINE_MARKER)
    if (marker.exists()) {
        check(readFallbackIdentity(marker) == fallbackIdentity(transaction)) {
            "Existing fallback quarantine marker belongs to another or corrupt transaction"
        }
        return
    }
    val payload = buildString {
        append("transactionId="); append(transaction.transactionId); append('\n')
        append("backupRoot="); append(transaction.backupRoot.name); append('\n')
        append("createdAt="); append(transaction.manifest.createdAt); append('\n')
    }
    KeplerJobMetadata.atomicWrite(marker, payload)
    check(readFallbackIdentity(marker) == fallbackIdentity(transaction)) {
        "Fallback quarantine marker verification failed"
    }
}

private fun fallbackIdentity(transaction: ReprocessTransaction): Triple<String, String, Long> = Triple(
    transaction.transactionId, transaction.backupRoot.name, transaction.manifest.createdAt
)

private fun readFallbackIdentity(marker: File): Triple<String, String, Long>? = try {
    val values = marker.readLines().associate { line ->
        val split = line.indexOf('=')
        require(split > 0) { "Malformed fallback marker" }
        line.substring(0, split) to line.substring(split + 1)
    }
    if (values.keys != setOf("transactionId", "backupRoot", "createdAt")) null
    else Triple(values.getValue("transactionId"), values.getValue("backupRoot"), values.getValue("createdAt").toLong())
} catch (_: Exception) { null }

/**
 * Narrow injectable IO seam for fallback marker deletion. Tests can override and must reset in `finally`.
 * Receiving the marker [File] to delete; must return true when the file is gone after the attempt.
 */
internal var fallbackDeleteOperation: ((File) -> Boolean)? = null

/**
 * Removes only a verified matching fallback marker after a durable terminal transition.
 * Returns true only when the marker was absent or is confirmed gone after deletion.
 * Returns false when the identity does not match or deletion leaves the file present.
 */
internal fun removeMatchingFallbackQuarantine(jobDir: File, transaction: ReprocessTransaction): Boolean {
    val marker = File(jobDir, REPROCESS_FALLBACK_QUARANTINE_MARKER)
    if (!marker.exists()) return true
    if (readFallbackIdentity(marker) != fallbackIdentity(transaction)) return false
    val deleteOp = fallbackDeleteOperation
    val deleted = if (deleteOp != null) deleteOp(marker) else marker.delete()
    return deleted && !marker.exists()
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
    val fallbackMarker = File(jobDir, REPROCESS_FALLBACK_QUARANTINE_MARKER)
    if (fallbackMarker.exists()) return true
    val children = jobDir.listFiles() ?: return true
    children.forEach { child ->
        if (child.isDirectory && child.name.startsWith(".reprocess_backup_")) {
            when (classifyTransactionManifest(jobDir, child)) {
                is ManifestClassification.Unresolved -> return true
                is ManifestClassification.Resolved -> { /* resolved roots do not block */ }
            }
        }
    }
    return false
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
 * - Multiple roots are handled independently; the aggregate job remains blocked if any unresolved
 *   root remains, or if the durable job-level fallback marker survives.
 */
internal fun recoverValidatedQuarantine(jobDir: File) {
    if (KeplerJobMetadata.isOperationActive(jobDir)) return
    val fallbackMarker = File(jobDir, REPROCESS_FALLBACK_QUARANTINE_MARKER)
    val children = jobDir.listFiles() ?: return
    val rootsToSkip = mutableSetOf<String>()
    if (fallbackMarker.exists()) {
        val fallbackId = readFallbackIdentity(fallbackMarker)
        if (fallbackId != null) {
            val matchingRoot = children.firstOrNull { it.isDirectory && it.name == fallbackId.second }
            val matchingClassification = matchingRoot?.let { classifyTransactionManifest(jobDir, it) }
            if (matchingClassification is ManifestClassification.Resolved && matchingRoot != null) {
                val manifest = runCatching { loadStrictManifest(File(matchingRoot, REPROCESS_TX_MANIFEST_FILE)) }.getOrNull()
                if (manifest != null &&
                    manifest.transactionId == fallbackId.first &&
                    matchingRoot.name == fallbackId.second &&
                    manifest.createdAt == fallbackId.third
                ) {
                    val deleteOp = fallbackDeleteOperation
                    val deleted = if (deleteOp != null) deleteOp(fallbackMarker) else fallbackMarker.delete()
                    if (!deleted || fallbackMarker.exists()) {
                        rootsToSkip.add(matchingRoot.name)
                    }
                }
            }
        }
    }
    children.forEach { child ->
        if (child.isDirectory && child.name.startsWith(".reprocess_backup_")) {
            if (child.name in rootsToSkip) return@forEach
            val classification = classifyTransactionManifest(jobDir, child)
            when (classification) {
                is ManifestClassification.Unresolved -> {
                    if (isRootEvidenceFree(child)) {
                        child.delete()
                    }
                }
                is ManifestClassification.Resolved -> {
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

/**
 * Best-effort cleanup of a strictly validated terminal root used by process-restart recovery.
 * Shares the cleanup contract with [cleanupBackups]: never delete the terminal manifest while any
 * unknown content remains; delete the manifest last; remove the root only when empty; never delete
 * terminal evidence after a failed payload deletion.
 */
private fun cleanupTerminalRoot(root: File) {
    val children = root.listFiles() ?: return
    val manifestFile = children.firstOrNull { it.name == REPROCESS_TX_MANIFEST_FILE } ?: return
    val durable = runCatching { loadStrictManifest(manifestFile) }.getOrNull() ?: return
    val state = durable.state
    if (state != ReprocessTransactionState.COMMITTED && state != ReprocessTransactionState.ROLLED_BACK) return
    val dummyTx = ReprocessTransaction(durable.transactionId, root, durable, emptyList())
    cleanupBackups(dummyTx)
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
        /**
         * Single strict parser used by classifier, rollback, cleanup, recovery, and state writer.
         * Throws on any structural violation so the caller can degrade closed (Unresolved).
         * A structurally valid legacy manifest missing only `state` remains ACTIVE.
         */
        fun fromJson(json: JSONObject): ReprocessTransactionManifest {
            val txId = json.optString("transactionId").orEmpty()
            require(txId.isNotBlank()) { "Transaction ID is blank" }
            val createdAt = json.optLong("createdAt", 0L)
            require(createdAt > 0L) { "Transaction createdAt is invalid: $createdAt" }
            val preExistingArray = json.optJSONArray("preExistingPaths")
                ?: throw IllegalArgumentException("preExistingPaths missing")
            val backedUpArray = json.optJSONArray("backedUpPaths")
                ?: throw IllegalArgumentException("backedUpPaths missing")
            val preExisting = preExistingArray.toStringSetStrict()
            val backedUp = backedUpArray.toStringSetStrict()
            // Backup entries are required; every backed-up path must have an entry, and vice versa.
            val entriesJson = json.optJSONObject("backupEntries")
                ?: throw IllegalArgumentException("backupEntries missing")
            val backupEntries = mutableMapOf<String, BackupEntry>()
            entriesJson.keys().forEach { name ->
                require(isStrictRelativePath(name)) { "Unsafe backup entry key: $name" }
                val entryJson = entriesJson.optJSONObject(name)
                    ?: throw IllegalArgumentException("Malformed backup entry: $name")
                val entry = BackupEntry.fromJson(entryJson)
                require(name == entry.relativePath) { "Backup entry key/target mismatch: $name" }
                require(backupEntries.values.none { it.backupName == entry.backupName }) {
                    "Duplicate backup file name: ${entry.backupName}"
                }
                backupEntries[name] = entry
            }
            require(backupEntries.isNotEmpty() || backedUp.isEmpty()) {
                "backupEntries absent on non-empty backedUpPaths"
            }
            require(backupEntries.keys.toSet() == backedUp) {
                "backupEntries/backedUpPaths mismatch: entries=${backupEntries.keys}, backedUp=$backedUp"
            }
            for (entry in backupEntries.values) {
                require(entry.relativePath in backedUp) {
                    "backupEntry ${entry.backupName} target ${entry.relativePath} not in backedUpPaths"
                }
            }
            require(preExisting.containsAll(backedUp)) {
                "backed-up paths must be a subset of pre-existing paths"
            }
            val newlyCreated = json.optJSONArray("newlyCreatedPaths")?.toStringSetStrict().orEmpty()
            val state = if (json.has("state") && !json.isNull("state")) {
                val name = json.optString("state")
                runCatching { ReprocessTransactionState.valueOf(name) }.getOrNull()
                    ?: throw IllegalArgumentException("Unknown transaction state: $name")
            } else {
                // Legacy structurally valid manifest missing only `state` remains ACTIVE.
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

        private fun JSONArray.toStringSetStrict(): Set<String> {
            val out = LinkedHashSet<String>(length())
            repeat(length()) { i ->
                val raw = optString(i).orEmpty()
                require(isStrictRelativePath(raw)) { "Unsafe or invalid manifest path: $raw" }
                require(raw !in out) { "Duplicate manifest path: $raw" }
                out += raw
            }
            return out
        }
    }
}

/**
 * Strict durable manifest loader. Reads and fully validates [manifestFile] using the single
 * authoritative parser. Returns null on any I/O, JSON, structural, unsafe, or unknown-state
 * failure so callers can fail closed. Never falls back to an in-memory snapshot.
 */
internal fun loadStrictManifest(manifestFile: File): ReprocessTransactionManifest {
    require(manifestFile.isFile) { "Manifest file missing: ${manifestFile.absolutePath}" }
    val json = JSONObject(manifestFile.readText())
    return ReprocessTransactionManifest.fromJson(json)
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
        private val HEX256 = Regex("^[0-9a-f]{64}$")
        fun fromJson(json: JSONObject): BackupEntry {
            val backupName = json.optString("backupName").orEmpty()
            val relativePath = json.optString("relativePath").orEmpty()
            val originalLength = json.optLong("originalLength", -1L)
            val sha256 = json.optString("sha256").orEmpty()
            require(backupName.isNotBlank()) { "backupName is blank" }
            require(isStrictRelativePath(relativePath)) { "Unsafe relative path: $relativePath" }
            require(isStrictRelativePath(backupName)) { "Unsafe backup name: $backupName" }
            require(originalLength >= 0L) { "Invalid originalLength: $originalLength" }
            require(HEX256.matches(sha256)) { "Invalid SHA-256 digest: $sha256" }
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

/** Immutable transaction identity; durable state is deliberately excluded. */
private fun ReprocessTransactionManifest.hasSameImmutableIdentity(
    other: ReprocessTransactionManifest
): Boolean = transactionId == other.transactionId &&
    createdAt == other.createdAt &&
    preExistingPaths == other.preExistingPaths &&
    backedUpPaths == other.backedUpPaths &&
    backupEntries == other.backupEntries

/**
 * Validate that [transaction] is internally consistent and that every backed-up path maps to a
 * file that lives strictly inside [jobDir]. Returns true only when the durable manifest and the
 * in-memory manifest agree on identity and every entry has safe relative target and backup paths
 * that live inside the expected job and backup roots.
 */
internal fun validateTransactionIdentity(
    jobDir: File,
    transaction: ReprocessTransaction
): Boolean {
    val canonicalJob = jobDir.canonicalFile
    val canonicalBackup = transaction.backupRoot.canonicalFile
    val expectedParent = canonicalJob
    if (canonicalBackup.parentFile?.canonicalFile != expectedParent) return false
    if (canonicalBackup.name != ".reprocess_backup_${transaction.transactionId}") return false
    val manifestFile = File(canonicalBackup, REPROCESS_TX_MANIFEST_FILE)
    if (!manifestFile.isFile) return false
    val durable = runCatching { loadStrictManifest(manifestFile) }.getOrNull() ?: return false
    if (durable.transactionId != transaction.transactionId) return false
    if (durable.createdAt != transaction.manifest.createdAt) return false
    if (durable.preExistingPaths != transaction.manifest.preExistingPaths) return false
    if (durable.backedUpPaths != transaction.manifest.backedUpPaths) return false
    if (durable.backupEntries != transaction.manifest.backupEntries) return false
    if (!durable.hasSameImmutableIdentity(transaction.manifest)) return false
    for (entry in durable.backupEntries.values) {
        if (!isStrictRelativePath(entry.relativePath)) return false
        val target = File(canonicalJob, entry.relativePath).canonicalFile
        if (target.parentFile?.canonicalFile != canonicalJob) return false
        val backup = File(canonicalBackup, entry.backupName).canonicalFile
        if (backup.parentFile?.canonicalFile != canonicalBackup) return false
        if (backup.name == REPROCESS_TX_MANIFEST_FILE) return false
    }
    return true
}

/**
 * True if the file is an immutable source frame for [jobKind] that must never be backed up, deleted,
 * or replaced by a reprocess transaction. Identifies source frames from actual job metadata + kind:
 * RAW/YUV/Color PNG/JPEG/RAW/YUV source frames are all immutable.
 */
private fun isImmutableSourceFrame(file: File, jobKind: ReprocessJobKind = detectImmutableSourceKind(file)): Boolean {
    val name = file.name.lowercase(Locale.US)
    if (!name.startsWith("frame_")) return false
    return when (jobKind) {
        ReprocessJobKind.RAW_FUSION ->
            name.endsWith(".raw16") || name.endsWith(".dng") || name.endsWith(".png") ||
                name.endsWith(".jpg") || name.endsWith(".jpeg")
        ReprocessJobKind.YUV_FUSION ->
            name.endsWith(".yuv") || name.endsWith(".nv21") || name.endsWith(".yuv420") ||
                name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
        ReprocessJobKind.COLOR_BURST ->
            name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
        ReprocessJobKind.UNSUPPORTED -> true
    }
}

/** Best-effort detection used when no job kind is available; keeps RAW/YUV source frames immutable. */
private fun detectImmutableSourceKind(file: File): ReprocessJobKind = ReprocessJobKind.RAW_FUSION

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

internal fun backupReprocessTransaction(
    jobDir: File,
    files: List<File>,
    job: JSONObject? = null,
    jobKind: ReprocessJobKind? = null
): Result<ReprocessTransaction> {
    val transactionId = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    val root = File(jobDir, ".reprocess_backup_$transactionId")
    return runCatching {
        check(root.mkdirs()) { "Could not create reprocess backup directory." }
        val metadata = File(jobDir, JOB_JSON_FILE_NAME)
        check(metadata.isFile) { "job.json is required for rollback." }
        // Identify immutable source frames from actual job metadata and job kind. RAW/YUV/ColorBurst
        // source PNG/JPEG/RAW/YUV frames must NEVER be backed up, deleted, or replaced.
        val validatedJob = job ?: KeplerJobMetadata.read(jobDir)
        val resolvedKind = jobKind ?: detectJobKind(jobDir, validatedJob)
        val immutableSourceFiles = mutableSetOf<File>()
        jobDir.listFiles()?.forEach { child ->
            if (child.isFile && isImmutableSourceFrame(child, resolvedKind)) {
                immutableSourceFiles += child.canonicalFile
            }
        }
        val preExistingNames = jobDir.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet().orEmpty()
        // Record every pre-existing mutable file (job.json + worker-writable non-source files).
        val filesToBackup = (files + metadata)
            .asSequence()
            .filter { it.isFile }
            .map { it.canonicalFile }
            .distinctBy { it.path }
            .filter { it.parentFile?.canonicalFile == jobDir.canonicalFile }
            .filter { !immutableSourceFiles.contains(it.canonicalFile) && isReprocessWorkerWritable(it) }
            .toList()
        val backups = filesToBackup.map { original ->
            val backup = File(root, "${original.name}$BACKUP_ENTRY_SUFFIX")
            original.copyTo(backup, overwrite = false)
            check(backup.isFile && backup.length() == original.length()) { "Backup verification failed for ${original.name}" }
            val sha = computeSha256(backup)
            // Verify backup digest matches original digest without whole-file allocation.
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
 * The durable manifest is authoritative; corrupt/missing evidence never falls back to the in-memory snapshot.
 */
internal fun restoreBackups(jobDir: File, transaction: ReprocessTransaction): Result<Unit> = runCatching {
    val root = transaction.backupRoot.canonicalFile
    if (!root.isDirectory) throw IllegalStateException("Backup root missing for rollback")
    val canonicalJobDir = jobDir.canonicalFile
    if (root.parentFile?.canonicalFile != canonicalJobDir) {
        throw IllegalStateException("Backup root is not a direct child of the job directory")
    }
    if (root.name != ".reprocess_backup_${transaction.transactionId}") {
        throw IllegalStateException("Backup root name is not a valid transaction-root name")
    }
    val manifestFile = File(root, REPROCESS_TX_MANIFEST_FILE)
    if (!manifestFile.isFile) throw IllegalStateException("Transaction manifest missing for rollback")
    val durable = loadStrictManifest(manifestFile)
    if (durable.transactionId != transaction.transactionId) {
        throw IllegalStateException("Transaction ID mismatch during rollback")
    }
    if (!durable.hasSameImmutableIdentity(transaction.manifest)) {
        throw IllegalStateException("Durable manifest drift relative to in-memory transaction")
    }
    data class DurableRestore(val target: File, val backup: File, val entry: BackupEntry)
    // Restore authority is exclusively the strict durable manifest. In-memory backup objects are
    // deliberately ignored so an injected extra entry cannot mutate an unrelated file.
    val durableRestores = durable.backupEntries.values.map { entry ->
        val target = File(canonicalJobDir, entry.relativePath).canonicalFile
        val backup = File(root, entry.backupName).canonicalFile
        if (target.parentFile?.canonicalFile != canonicalJobDir) {
            throw IllegalStateException("Unsafe backup target path: ${entry.relativePath}")
        }
        if (backup.parentFile?.canonicalFile != root) {
            throw IllegalStateException("Backup file lives outside backup root: ${entry.backupName}")
        }
        if (entry.relativePath == REPROCESS_TX_MANIFEST_FILE) {
            throw IllegalStateException("Refusing to overwrite durable manifest file as target")
        }
        check(backup.isFile) { "Missing rollback backup: ${entry.relativePath}" }
        check(backup.length() == entry.originalLength) {
            "Invalid rollback backup length: ${entry.relativePath} (was ${backup.length()}, expected ${entry.originalLength})"
        }
        check(computeSha256(backup) == entry.sha256) {
            "Rollback backup digest mismatch: ${entry.relativePath}"
        }
        DurableRestore(target, backup, entry)
    }
    // 2. Stage and verify all replacement temp files before replacing any destination.
    data class StagedRestore(val restore: DurableRestore, val temp: File)
    val staged = mutableListOf<StagedRestore>()
    try {
        durableRestores.forEach { restore ->
            val target = restore.target
            val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.restore")
            restore.backup.copyTo(temp, overwrite = true)
            check(temp.length() == restore.entry.originalLength) { "Rollback temp verification failed: ${target.name}" }
            val tempSha = computeSha256(temp)
            check(tempSha == restore.entry.sha256) { "Rollback temp digest failed: ${target.name}" }
            staged.add(StagedRestore(restore, temp))
        }
        // 3. Atomically replace restored non-metadata files. Restore job.json last.
        val (jobJsonFirst, jobJsonLast) = staged.partition { it.restore.target.name != JOB_JSON_FILE_NAME }
        jobJsonFirst.forEach { stagedRestore ->
            KeplerJobMetadata.atomicReplace(stagedRestore.temp, stagedRestore.restore.target)
        }
        jobJsonLast.forEach { stagedRestore ->
            KeplerJobMetadata.atomicReplace(stagedRestore.temp, stagedRestore.restore.target)
        }
    } finally {
        // Clean up any remaining temp files.
        staged.forEach { if (it.temp.exists()) runCatching { it.temp.delete() } }
    }
}

/** Narrow injectable IO seam for file deletion in cleanup. Tests can override and must reset in `finally`. */
internal var cleanupDeleteOperation: (File) -> Boolean = { it.delete() }

/**
 * Safe backup cleanup, shared by immediate finalization and process-restart recovery.
 *
 * - Cleanup is allowed only for a strictly validated COMMITTED or ROLLED_BACK terminal manifest.
 * - Delete known payload/temp files first; inspect both files and directories.
 * - Never delete the terminal manifest while any known or unknown content failed to delete.
 * - Delete the terminal manifest only after terminal state is durable and every other entry is gone.
 * - Delete the root only when actually empty.
 * - A successful root deletion reports `true` (not a spurious failure because `listFiles()` is null).
 * - Cleanup failure leaves a valid terminal manifest and must not block the job; it may add a warning
 *   but cannot downgrade COMMITTED/ROLLED_BACK to QUARANTINED.
 * - Recovery does not delete terminal evidence after a failed payload deletion.
 * - Unresolved roots are never cleaned merely because their payload is absent.
 */
internal fun cleanupBackups(transaction: ReprocessTransaction): Boolean {
    val root = transaction.backupRoot
    if (!root.isDirectory) {
        // Already gone — a resolved transaction with no root is fully cleaned.
        return true
    }
    // Cleanup is allowed only for strictly validated terminal manifests.
    val manifestFile = File(root, REPROCESS_TX_MANIFEST_FILE)
    if (!manifestFile.isFile) return false
    val durable = runCatching { loadStrictManifest(manifestFile) }.getOrNull() ?: return false
    if (durable.transactionId != transaction.transactionId) return false
    if (!durable.hasSameImmutableIdentity(transaction.manifest)) return false
    val state = durable.state
    if (state != ReprocessTransactionState.COMMITTED && state != ReprocessTransactionState.ROLLED_BACK) {
        return false
    }
    val cleanupDelete = cleanupDeleteOperation
    val backupNames = durable.backupEntries.values.map { it.backupName }.toSet()
    val knownNames = backupNames + setOf(REPROCESS_QUARANTINE_MARKER, REPROCESS_TX_MANIFEST_FILE)
    val allKnownDeletedRemoved = mutableSetOf<File>()
    // 1. Delete known payload files and temp artifacts first; inspect files and directories.
    root.listFiles()?.sortedBy { it.isDirectory }?.forEach { entry ->
        if (!entry.isDirectory) {
            if (entry.name == REPROCESS_TX_MANIFEST_FILE) return@forEach
            if (entry.name in backupNames || entry.name.endsWith(".tmp") || entry.name.endsWith(".restore")) {
                if (!cleanupDelete(entry)) {
                    if (entry.exists()) return false
                }
                allKnownDeletedRemoved += entry
            }
        } else {
            // No transaction format owns directories inside the backup root. Preserve terminal
            // evidence rather than deleting even an empty unknown directory.
            return false
        }
    }
    // 2. Delete quarantine marker once known payloads are gone.
    val markerFile = File(root, REPROCESS_QUARANTINE_MARKER)
    if (markerFile.exists()) {
        val deleted = cleanupDelete(markerFile)
        if (!deleted || markerFile.exists()) {
            // Terminal state is already durable, but its manifest must remain while any known
            // payload/marker could not be removed.
            return false
        }
    }
    check(!markerFile.exists()) { "Quarantine marker must be deleted before manifest removal" }
    // 3. Check if unknown/non-removable contents remain.
    val remaining = root.listFiles()?.toList().orEmpty()
    val unknownContents = remaining.filter { it.name !in knownNames }
    val manifestRemaining = remaining.firstOrNull { it.name == REPROCESS_TX_MANIFEST_FILE }
    if (unknownContents.isNotEmpty()) {
        // Do NOT delete the terminal manifest while unknown content remains.
        return false
    }
    // 4. Delete the terminal manifest last after terminal state is durable.
    if (manifestRemaining != null) {
        if (!cleanupDelete(manifestRemaining) && manifestRemaining.exists()) return false
    }
    // 5. Remove an empty root; a successful root deletion reports success, not failure.
    val finalContents = root.listFiles()
    if (finalContents == null || finalContents.isEmpty()) {
        if (!root.delete() && root.exists()) return false
        return true
    }
    return false
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
