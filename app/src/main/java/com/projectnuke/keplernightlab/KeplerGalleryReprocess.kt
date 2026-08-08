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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
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

/** Test-only counters for transaction-critical idempotency verification. Each test must save the
 *  prior value and restore it in `finally`. Production never reads these. */
@Volatile
internal var restoreBackupsKickbackCount: Int = 0
@Volatile
internal var createdOutputKickbackCount: Int = 0
@Volatile
internal var fallbackWriteCount: Int = 0
/** Convenience accessor aligned with the [KeplerJobMetadata.leaseReleaseCount] storage. */
internal val leaseReleaseCount: Int
    get() = KeplerJobMetadata.leaseReleaseCount
/** Convenience accessor aligned with the [KeplerJobMetadata.atomicWriteCount] storage. */
internal val metadataWriteCount: Int
    get() = KeplerJobMetadata.atomicWriteCount

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
    val parsed: ReprocessTransactionManifest? = try {
        loadStrictManifest(manifestFile)
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (_: Exception) { null }
    if (parsed == null) return ManifestClassification.Unresolved
    return when (parsed.state) {
        ReprocessTransactionState.COMMITTED,
        ReprocessTransactionState.ROLLED_BACK -> ManifestClassification.Resolved(parsed.state)
        ReprocessTransactionState.ACTIVE,
        ReprocessTransactionState.QUARANTINED -> ManifestClassification.Unresolved
    }
}

private fun classifyTransactionManifest(jobDir: File, backupRoot: File): ManifestClassification {
    val base = classifyTransactionManifest(backupRoot)
    if (base is ManifestClassification.Unresolved) return base
    val manifest = try { loadStrictManifest(File(backupRoot, REPROCESS_TX_MANIFEST_FILE)) }
    catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (_: Exception) { return ManifestClassification.Unresolved }
    val job = try { jobDir.canonicalFile } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (_: Exception) { return ManifestClassification.Unresolved }
    val root = try { backupRoot.canonicalFile } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (_: Exception) { return ManifestClassification.Unresolved }
    try {
        if (root.parentFile?.canonicalFile != job || root.name != ".reprocess_backup_${manifest.transactionId}") {
            return ManifestClassification.Unresolved
        }
        if (manifest.backupEntries.values.any { entry ->
                val target = File(job, entry.relativePath).canonicalFile
                val backup = File(root, entry.backupName).canonicalFile
                target.parentFile?.canonicalFile != job || backup.parentFile?.canonicalFile != root ||
                    entry.relativePath == REPROCESS_TX_MANIFEST_FILE || entry.backupName == REPROCESS_TX_MANIFEST_FILE
            }) return ManifestClassification.Unresolved
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (_: Exception) { return ManifestClassification.Unresolved }
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
    /** Durable terminal result stored before cleanup, reused for idempotent duplicate finalization. */
    private val terminalResult = AtomicReference<ReprocessFinalizationResult?>(null)
    /** Bounded production retry counter: at most one retry is permitted from UNRESOLVED. */
    private val lateRetryCount = java.util.concurrent.atomic.AtomicInteger(0)
    /** Maximum production late-finalization retries from UNRESOLVED. */
    private val LATE_RETRY_BOUND = 1

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

    /** Store the durable terminal result for idempotent duplicate-finalization reads. */
    fun storeTerminalResult(result: ReprocessFinalizationResult) {
        terminalResult.compareAndSet(null, result)
    }

    /** Return the existing terminal result if already stored, else null. */
    fun existingTerminalResult(): ReprocessFinalizationResult? = terminalResult.get()
    /** True when a durable terminal result has already been recorded. */
    fun isTerminal(): Boolean = terminalResult.get() != null

    /** Try to acquire the once-guard for late-finalization registration. Returns true only the first time.
     *  Permits at most [LATE_RETRY_BOUND] bounded production retries from UNRESOLVED. */
    fun tryAcquireLateRegistration(): Boolean {
        while (true) {
            val current = lateState.get()
            if (current != LateState.IDLE && current != LateState.UNRESOLVED) return false
            val isRetry = current == LateState.UNRESOLVED
            if (isRetry && lateRetryCount.get() >= LATE_RETRY_BOUND) return false
            if (lateState.compareAndSet(current, LateState.LATE_REGISTERED)) {
                if (isRetry) lateRetryCount.incrementAndGet()
                return true
            }
        }
    }

    /** True when no more production retries are allowed (bound reached or terminal). */
    fun lateRetryExhausted(): Boolean = lateRetryCount.get() >= LATE_RETRY_BOUND

    /** Try to acquire the once-guard for late finalizer invocation. Returns true only the first time. */
    fun tryBeginFinalization(): Boolean = lateState.compareAndSet(LateState.LATE_REGISTERED, LateState.FINALIZING)

    /** A terminal finalization was already processed (prevents duplicate late-finalization). */
    fun markLateTerminal() { lateState.set(LateState.TERMINAL) }
    fun markLateUnresolved() { lateState.set(LateState.UNRESOLVED) }

    /** Narrow test accessor for the late-finalization state machine. */
    internal fun lateStateForTest(): LateState = lateState.get()

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
    for (s in suppressed) if (s != null && s !== primary) addSuppressedSafe(primary, s)
    return primary
}

/** Compact cause/suppressed helper used by rollback, cleanup, terminal metadata, quarantine, and late-finalization. */
private fun combineCauseWithMessage(primary: Throwable, message: String, suppressedFailure: Throwable?): Throwable {
    val wrapping = if (primary.message != message) RuntimeException(message, primary) else primary
    if (suppressedFailure != null && suppressedFailure !== primary) addSuppressedSafe(wrapping, suppressedFailure)
    return wrapping
}

/** Structured completed-terminal helper. Avoids [Deferred.getCompleted] experimental opt-in and
 *  ambiguous cancellation behavior. Returns the completed value, or throws if the Deferred completed
 *  exceptionally. CancellationException, fatal Errors propagate unchanged. */
/**
 *  Classify a completed worker terminal Deferred into a worker outcome.
 *
 *  Returns null if the terminal is null or not yet completed.
 *  Returns [Result.success] for a normal worker outcome.
 *  Returns [Result.failure] for a confirmed worker exit:
 *    - ordinary exception thrown by the Deferred
 *    - [CancellationException] while [currentCoroutineContext] is [isActive]
 *      (the worker Deferred was cancelled but this callback/retry coroutine is alive)
 *  Throws [CancellationException] when this coroutine is cancelled (callback/retry cancellation).
 *  Throws fatal [Error]s unchanged.
 */
private suspend fun resolveWorkerTerminal(
    terminal: Deferred<ReprocessWorkerOutcome>?
): Result<ReprocessWorkerOutcome>? {
    if (terminal == null || !terminal.isCompleted) return null
    return try {
        Result.success(terminal.await())
    } catch (ce: kotlinx.coroutines.CancellationException) {
        if (currentCoroutineContext().isActive) {
            // Worker Deferred was cancelled, but this coroutine is still active.
            // Confirmed worker failure, not callback cancellation.
            Result.failure(ce)
        } else {
            // This coroutine is cancelled → callback/retry cancellation, propagate.
            throw ce
        }
    } catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (e: Exception) { Result.failure(e) }
}

internal data class UnresolvedPersistenceResult(
    val rootMarkerPersisted: Boolean,
    val quarantinedStatePersisted: Boolean,
    val fallbackPersisted: Boolean,
    val durableEvidenceEstablished: Boolean,
    val markerError: Throwable?,
    val stateError: Throwable?,
    val fallbackError: Throwable?,
    val cancelError: Throwable?,
) {
    /** Compatibility shim for callers that need a single error. */
    val error: Throwable?
        get() = listOfNotNull(cancelError, markerError, stateError, fallbackError).firstOrNull()
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

    val target = try { requireReprocessSafeJobDirectory(context, jobDir) }
    catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (sf: Exception) { return@withContext Result.failure(sf) }
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
        } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
        catch (oom: OutOfMemoryError) { throw oom }
        catch (td: ThreadDeath) { throw td }
        catch (le: LinkageError) { throw le }
        catch (ie: InternalError) { throw ie }
        catch (e: Error) { throw e }
        catch (inspectionFailure: Exception) {
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
            if (it is kotlinx.coroutines.CancellationException) throw it
            writeReprocessFailure(target, "${it.javaClass.simpleName}: ${it.message}")
            return@withContext Result.failure(it)
        }
        resolvedSelection = resolveFrameSelection(target, kind, job, reviewItems, frameSelection).getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            writeReprocessFailure(target, "${it.javaClass.simpleName}: ${it.message}")
            return@withContext Result.failure(it)
        }
        if (resolvedSelection.size < requiredSelectedFrameCount(kind, job)) {
            val message = "선택한 원본 프레임이 부족합니다. 다시 합성할 수 없습니다."
            writeReprocessFailure(target, message)
            return@withContext Result.failure(IllegalStateException(message))
        }
selectionMode = resolveSelectionMode(job, frameSelection)
    val listing = target.listFiles()
    check(listing != null) { "Cannot read job directory contents." }
    transaction = backupReprocessTransaction(
        target,
        listing.filter { it.isFile && isReprocessWorkerWritable(it, kind) },
        job = job,
        jobKind = kind
    ).getOrElse { backupError ->
        writeReprocessFailure(target, "Required reprocess backup failed: ${backupError.message}")
        return@withContext Result.failure(backupError)
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
            val original = IllegalStateException("Reprocess worker did not exit before rollback timeout.")
            val combined = when {
                !persistence.durableEvidenceEstablished && persistence.fallbackError != null ->
                    combineCauseWithMessage(original, "Unresolved persistence failed", persistence.fallbackError)
                persistence.durableEvidenceEstablished -> original
                else -> combineCauseWithMessage(original, "Unresolved persistence failed", null)
            }
            ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(combined))
        }
        is WorkerTerminalResult.CallerCancelledWhileWorkerActive -> {
            val persistence = persistUnresolvedQuarantine(session, transaction, acquisition.cancelFailure)
            registerLateFinalization(session, worker, target, jobKind, outputSettings, selectionMode, resolvedSelection)
            val combined = if (!persistence.durableEvidenceEstablished && persistence.fallbackError != null)
                combineCause(acquisition.callerCancellation, persistence.fallbackError)
            else acquisition.callerCancellation
            ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(combined))
        }
    }
}

/**
 * Structured late-evidence persistence result: every ordinary outcome is preserved separately.
 * [durableEvidenceEstablished] is true when either the marker or state succeeded and verified via
 * [strictRootEvidence]. The caller may combine [markerError], [stateError], [fallbackError], or
 * [cancelError] with the original operation failure; the result itself only describes persistence.
 */
internal data class LateEvidencePersistenceResult(
    val markerPersisted: Boolean,
    val statePersisted: Boolean,
    val fallbackPersisted: Boolean,
    val durableEvidenceEstablished: Boolean,
    val markerError: Throwable?,
    val stateError: Throwable?,
    val fallbackError: Throwable?,
    val evidenceError: Throwable?
)

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
    val workerOutcomeSnapshot: ReprocessWorkerOutcome = noOutcomeSnapshot,
    /** Cumulative evidence-persistence error preserved across late attempts; nulled on terminal success. */
    var evidenceError: Throwable? = null
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

/** Narrow test seam: overrides the CoroutineScope used by the invokeOnCompletion callback in
 *  [registerLateFinalization]. When null, defaults to `CoroutineScope(Dispatchers.IO)`. Tests
 *  inject a controlled scope to verify real callback cancellation. Always reset in `finally`. */
internal var lateFinalizationCallbackScope: CoroutineScope? = null

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
    val callbackScope = lateFinalizationCallbackScope ?: CoroutineScope(Dispatchers.IO)
    worker?.terminal?.invokeOnCompletion { cause ->
      callbackScope.launch {
        try {
          runLateFinalization(handoff, cause)
          // Production retry path: after the first ordinary finalizer/evidence failure leaves the
          // session in UNRESOLVED, schedule exactly one bounded retry sharing the same session/lease.
          // Never retries after COMMITTED/ROLLED_BACK; the session once-guard prevents concurrency.
          if (handoff.session.lateStateForTest() == ReprocessTransactionSession.LateState.UNRESOLVED &&
              !handoff.session.lateRetryExhausted()) {
            scheduleUnresolvedRetry(handoff)
          }
          // After initial attempt and one bounded retry, if still UNRESOLVED with no durable
          // evidence, surface a production failure so the process does not silently leak.
          if (handoff.session.lateStateForTest() == ReprocessTransactionSession.LateState.UNRESOLVED &&
              handoff.session.lateRetryExhausted()) {
            val rootEvidence = strictRootEvidence(handoff.target, handoff.transaction)
            val fallbackEvidence = strictFallbackEvidence(handoff.target, handoff.transaction)
            if (rootEvidence !== RootEvidence.Trustworthy &&
                fallbackEvidence !== FallbackEvidence.Trustworthy) {
              // Collect all evidence errors: initial/retry errors, plus root/fallback inspection causes.
              var combined = handoff.evidenceError ?: IllegalStateException(
                "Late finalization failed without establishing durable evidence for ${handoff.target.name}"
              )
              val rootCause = (rootEvidence as? RootEvidence.InspectionFailed)?.cause
              val fallbackCause = (fallbackEvidence as? FallbackEvidence.InspectionFailed)?.cause
              if (rootCause != null && rootCause !== combined) {
                combined = combineCause(combined, rootCause)
              }
              if (fallbackCause != null && fallbackCause !== combined) {
                combined = combineCause(combined, fallbackCause)
              }
              val combinedError = combineCauseWithMessage(
                combined,
                "Late finalization exhausted retries with no durable quarantine evidence",
                null
              )
              val handler = lateFinalizationFailureHandler
              if (handler != null) {
                handler(combinedError, handoff.target)
              } else {
                // Production default: throw through the coroutine scope so supervisor/uncaught
                // handler observes it. The lease remains retained for manual recovery.
                throw combinedError
              }
            }
          }
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: OutOfMemoryError) { throw e
        } catch (e: ThreadDeath) { throw e
        } catch (e: LinkageError) { throw e
        } catch (e: InternalError) { throw e
        } catch (e: Error) { throw e
        } finally {
          lateFinalizationCompleteCallback?.invoke(handoff)
        }
      }
    }
    return handoff
}

/** Narrow test seam: called after the invokeOnCompletion callback completes (in its finally block). */
internal var lateFinalizationCompleteCallback: ((ReprocessLateFinalizationHandoff) -> Unit)? = null

/**
 * Persist durable evidence for a late failure and return a structured result. Used by
 * [runLateFinalization] when terminal retrieval, finalizer, or callback cancellation occurs
 * before a durable terminal state. Never silently swallows fallback failures: the caller
 * must inspect [LateEvidencePersistenceResult.evidenceError] and rethrow/aggregate as needed.
 *
 * Contract:
 *  - if strict root evidence is [RootEvidence.Trustworthy], do NOT create a fallback marker.
 *  - otherwise persist the job-level durable fallback marker and verify it.
 *  - preserve the evidence-persistence error that blocked durable evidence establishment.
 *
 * Fatal Errors and [CancellationException] propagate unchanged from marker/state inspection.
 */
internal fun persistLateEvidence(
    handoff: ReprocessLateFinalizationHandoff
): LateEvidencePersistenceResult {
    val rootEvidence = strictRootEvidence(handoff.target, handoff.transaction)
    val durableEvidence = rootEvidence === RootEvidence.Trustworthy
    if (durableEvidence) return LateEvidencePersistenceResult(
        markerPersisted = false,
        statePersisted = false,
        fallbackPersisted = false,
        durableEvidenceEstablished = true,
        markerError = null, stateError = null, fallbackError = null, evidenceError = null
    )
    // Root evidence is not trustworthy — establish a durable fallback marker.
    var fallbackError: Throwable? = (rootEvidence as? RootEvidence.InspectionFailed)?.cause
    var fallbackPersisted = false
    try {
        ensureDurableFallbackQuarantine(handoff.target, handoff.transaction)
        fallbackPersisted = true
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (fb: Exception) {
        if (fallbackError == null) fallbackError = fb
        else fallbackError = combineCause(fallbackError, fb)
    }
    return LateEvidencePersistenceResult(
        markerPersisted = false,
        statePersisted = false,
        fallbackPersisted = fallbackPersisted,
        durableEvidenceEstablished = fallbackPersisted,
        markerError = null,
        stateError = null,
        fallbackError = fallbackError,
        // If the fallback failed and the root was merely Untrustworthy (no inspection error), preserve fallbackError.
        evidenceError = if (!fallbackPersisted) fallbackError else null
    )
}

/**
 * Late-finalization entry point. Invokes shared settlement exactly once using the worker terminal
 * outcome (awaited from the deferred), the completion [cause], or a snapshot. Exceptional deferred
 * completion enters shared settlement as a worker failure. Never silently swallows callback exceptions:
 * ordinary callback failure leaves durable unresolved evidence and retains the lease.
 *
 * Exception policy per the late callback contract:
 *  - ordinary terminal-retrieval/finalizer failure before terminal state → FINALIZING → UNRESOLVED,
 *    retain lease, establish or verify durable evidence, preserve complete failure context.
 *  - callback cancellation → transition to UNRESOLVED, retain lease, preserve durable evidence,
 *    rethrow original [CancellationException].
 *  - fatal [Error] → propagate unchanged; never convert into worker failure or "no evidence".
 *  - COMMITTED/ROLLED_BACK → terminal result already cached; mark TERMINAL; no fallback; no extra
 *    lease release outside the shared terminal cleanup path.
 *  - QUARANTINED → mark UNRESOLVED, retain lease; do not create fallback if strict root evidence exists.
 */
internal suspend fun runLateFinalization(handoff: ReprocessLateFinalizationHandoff, completionCause: Throwable?) {
    if (!handoff.session.tryBeginFinalization()) return
    val outcome: Result<ReprocessWorkerOutcome>
    try {
        val resolved = resolveWorkerTerminal(handoff.workerTerminal)
        outcome = if (resolved != null) {
            resolved
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
        handoff.session.markLateUnresolved()
        val evidence = persistLateEvidence(handoff)
        handoff.evidenceError = evidence.evidenceError
        throw e
    } catch (e: OutOfMemoryError) { handoff.session.markLateUnresolved(); throw e
    } catch (e: ThreadDeath) { handoff.session.markLateUnresolved(); throw e
    } catch (e: LinkageError) { handoff.session.markLateUnresolved(); throw e
    } catch (e: InternalError) { handoff.session.markLateUnresolved(); throw e
    } catch (e: Error) { handoff.session.markLateUnresolved(); throw e
    }
    val late: ReprocessFinalizationResult
    try {
        val injectedFailure = finalizerFailureSeam?.invoke()
        if (injectedFailure != null) throw injectedFailure
        late = finalizeTransaction(
            handoff.session, handoff.transaction, handoff.target, handoff.jobKind,
            handoff.outputSettings, handoff.selectionMode, handoff.resolvedSelection, outcome
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        handoff.session.markLateUnresolved()
        val evidence = persistLateEvidence(handoff)
        handoff.evidenceError = evidence.evidenceError
        throw e
    } catch (e: OutOfMemoryError) { handoff.session.markLateUnresolved(); throw e
    } catch (e: ThreadDeath) { handoff.session.markLateUnresolved(); throw e
    } catch (e: LinkageError) { handoff.session.markLateUnresolved(); throw e
    } catch (e: InternalError) { handoff.session.markLateUnresolved(); throw e
    } catch (e: Error) { handoff.session.markLateUnresolved(); throw e
    } catch (ordinaryFinalizerFailure: Exception) {
        handoff.session.markLateUnresolved()
        val evidence = persistLateEvidence(handoff)
        handoff.evidenceError = when {
            evidence.evidenceError != null -> combineCause(ordinaryFinalizerFailure, evidence.evidenceError)
            else -> ordinaryFinalizerFailure
        }
        return
    }
    when (late.state) {
        ReprocessFinalizationState.QUARANTINED -> {
            handoff.session.markLateUnresolved()
            val rootEvidence = strictRootEvidence(handoff.target, handoff.transaction)
            if (rootEvidence !== RootEvidence.Trustworthy) {
                try {
                    ensureDurableFallbackQuarantine(handoff.target, handoff.transaction)
                } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
                catch (oom: OutOfMemoryError) { throw oom }
                catch (td: ThreadDeath) { throw td }
                catch (le: LinkageError) { throw le }
                catch (ie: InternalError) { throw ie }
                catch (e: Error) { throw e }
                catch (fb: Exception) {
                    handoff.evidenceError = combineCause(
                        late.result.exceptionOrNull() ?: IllegalStateException("Quarantine without durable evidence."),
                        fb
                    )
                }
            }
        }
        ReprocessFinalizationState.COMMITTED, ReprocessFinalizationState.ROLLED_BACK -> {
            handoff.session.markLateTerminal()
            handoff.evidenceError = null
        }
    }
}

/**
 * Production recovery entry for an UNRESOLVED late-finalization session. Schedules at most one
 * bounded late-finalization retry after the first ordinary finalizer/evidence failure. Shares the
 * existing session, transaction, and lease, and never registers an unbounded callback loop.
 *
 * Contract:
 *  - only retries from [ReprocessTransactionSession.LateState.UNRESOLVED]
 *  - never retries after durable COMMITTED/ROLLED_BACK (terminal result is already cached)
 *  - the session holds the once-guard so two finalizers never run concurrently
 *  - retry failure remains UNRESOLVED with durable evidence and lease retained
 *  - returns the durable terminal result if the retry reached terminal, or `null` if no retry
 *    was scheduled (already terminal, bound exceeded, or pre-condition not met)
 *
 * Worker terminal integration: the retry uses the existing [ReprocessLateFinalizationHandoff.workerTerminal]
 * cached on the latest registered handoff (in [lateFinalizationHandoffScope]); if absent, it falls
 * back to the cached terminal result or a snapshot of the original failure recorded in [evidenceError].
 */
internal suspend fun scheduleUnresolvedRetry(
    originalHandoff: ReprocessLateFinalizationHandoff?
): ReprocessFinalizationResult? {
    val handoff = originalHandoff ?: lateFinalizationHandoffScope ?: return null
    val session = handoff.session
    if (session.isTerminal()) return session.existingTerminalResult()
    if (session.lateStateForTest() != ReprocessTransactionSession.LateState.UNRESOLVED) return null
    if (!session.tryAcquireLateRegistration()) return null
    // One bounded retry: reuse the shared session/transaction/lease (no new lease).
    // Extract the completed outcome before retry; do NOT re-await a failed deferred.
    val snapshot: ReprocessWorkerOutcome
    try {
        val resolved = resolveWorkerTerminal(handoff.workerTerminal)
        snapshot = if (resolved != null) {
            resolved.fold(
                onSuccess = { it },
                onFailure = { error ->
                    ReprocessWorkerOutcome(
                        result = Result.failure(error),
                        publicExportCommitted = false
                    )
                }
            )
        } else {
            handoff.workerOutcomeSnapshot
        }
    } catch (ce: kotlinx.coroutines.CancellationException) {
        // Retry coroutine cancellation → revert to UNRESOLVED, rethrow.
        session.markLateUnresolved(); throw ce
    } catch (oom: OutOfMemoryError) { session.markLateUnresolved(); throw oom }
    catch (td: ThreadDeath) { session.markLateUnresolved(); throw td }
    catch (le: LinkageError) { session.markLateUnresolved(); throw le }
    catch (ie: InternalError) { session.markLateUnresolved(); throw ie }
    catch (e: Error) { session.markLateUnresolved(); throw e }
    val retryHandoff = handoff.copy(
        workerTerminal = null,
        workerOutcomeSnapshot = snapshot
    )
    runLateFinalization(retryHandoff, completionCause = null)
    // Merge retry evidence errors back into the original handoff so the
    // exhaustion check in registerLateFinalization observes the full context.
    if (session.lateStateForTest() == ReprocessTransactionSession.LateState.TERMINAL) {
        handoff.evidenceError = null
    } else {
        val retryError = retryHandoff.evidenceError
        if (retryError != null) {
            handoff.evidenceError = handoff.evidenceError?.let { combineCause(it, retryError) } ?: retryError
        }
    }
    return when (session.lateStateForTest()) {
        ReprocessTransactionSession.LateState.TERMINAL -> session.existingTerminalResult()
        else -> null
    }
}

/**
 * Structured root-evidence inspection result.
 *
 *  - [Trustworthy]: at least one independent mechanism (marker or manifest) verified.
 *  - [Untrustworthy]: no mechanism verified; noinspection-fatal reason was ordinary.
 *  - [InspectionFailed]: an ordinary inspection error blocked evaluation. The marker and
 *    manifest are evaluated independently so a corrupt marker cannot mask a valid manifest
 *    and vice versa.
 *
 * Fatal [Error]s (OOM, ThreadDeath, LinkageError, InternalError) and [CancellationException]
 * propagate unchanged — they never convert into [Untrustworthy] or [InspectionFailed].
 */
internal sealed class RootEvidence {
    data object Trustworthy : RootEvidence()
    data object Untrustworthy : RootEvidence()
    data class InspectionFailed(val cause: Throwable) : RootEvidence()
}

/** Boolean shim retained by production paths that branch on trustworthiness. */
internal fun hasTrustworthyRootEvidence(jobDir: File, transaction: ReprocessTransaction): Boolean =
    strictRootEvidence(jobDir, transaction) === RootEvidence.Trustworthy

/**
 * Strict root-evidence inspection shared by late retrieval failure, late finalizer failure,
 * unresolved persistence, and fallback-creation decisions. Trusts root evidence when either
 * (a) the quarantine marker is a regular file with exact canonical content, or (b) a strict
 * manifest matches the transaction ID, createdAt, immutable identity, canonical job/root
 * relationship, and state ACTIVE or QUARANTINED. The marker and manifest are evaluated
 * independently so a corrupt/marker directory cannot mask a valid manifest. Treats as
 * untrusted: missing/corrupt/unreadable marker, marker directory, malformed manifest,
 * wrong ID/root/createdAt, terminal state with identity mismatch, and canonicalization or
 * security failures. Uses explicit exception handling — never broad `runCatching`.
 *
 * Exception policy:
 *  - ordinary parsing/IO/security/corrupt-evidence failure → [Untrustworthy] or [InspectionFailed]
 *  - [CancellationException] → propagate unchanged
 *  - [OutOfMemoryError], [ThreadDeath], [LinkageError], [InternalError], other fatal [Error] → propagate unchanged
 */
internal fun strictRootEvidence(jobDir: File, transaction: ReprocessTransaction): RootEvidence {
    val backupRoot = transaction.backupRoot
    if (!backupRoot.isDirectory) return RootEvidence.Untrustworthy

    // Root identity validation must pass before trusting any marker or manifest.
    // Use canonicalization seam when non-null for test injection.
    fun resolveCanonical(file: File): File {
        val seam = strictRootEvidenceCanonicalizationSeam
        if (seam != null) return seam(file)
        return file.canonicalFile
    }
    val canonicalJob = try { resolveCanonical(jobDir) } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (ex: Exception) { return RootEvidence.InspectionFailed(ex) }
    val canonicalRoot = try { resolveCanonical(backupRoot) } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (ex: Exception) { return RootEvidence.InspectionFailed(ex) }
    val parentCanonical = try { resolveCanonical(canonicalRoot.parentFile ?: return RootEvidence.Untrustworthy) } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (ex: Exception) { return RootEvidence.InspectionFailed(ex) }
    if (parentCanonical != canonicalJob) return RootEvidence.Untrustworthy
    if (canonicalRoot.name != ".reprocess_backup_${transaction.transactionId}") return RootEvidence.Untrustworthy

    var markerTrustworthy = false
    var markerInspectionError: Throwable? = null
    // (a) Identity-bound quarantine marker with matching transaction identity, evaluated independently.
    //     Legacy fixed-content marker is NOT independently trustworthy — falls through to manifest check.
    val marker = File(backupRoot, REPROCESS_QUARANTINE_MARKER)
    when (val classification = classifyMarkerPath(marker, backupRoot)) {
        is MarkerPathClassification.Valid -> {
            try {
                val identity = readQuarantineMarkerIdentity(marker)
                if (identity != null &&
                    identity.first == transaction.transactionId &&
                    identity.second == canonicalRoot.name &&
                    identity.third == transaction.manifest.createdAt
                ) {
                    markerTrustworthy = true
                }
            } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
            catch (oom: OutOfMemoryError) { throw oom }
            catch (td: ThreadDeath) { throw td }
            catch (le: LinkageError) { throw le }
            catch (ie: InternalError) { throw ie }
            catch (e: Error) { throw e }
            catch (readFailure: Exception) { markerInspectionError = readFailure }
        }
        is MarkerPathClassification.InspectionError -> {
            markerInspectionError = classification.cause
        }
        // Absent, Symlink, NotRegularFile, NotDirectChild → not trustworthy
        else -> {}
    }
    if (markerTrustworthy) return RootEvidence.Trustworthy

    // (b) Strict ACTIVE/QUARANTINED manifest matching transaction identity, evaluated independently
    // with root identity already validated above.
    val manifestFile = File(backupRoot, REPROCESS_TX_MANIFEST_FILE)
    if (!manifestFile.isFile) {
        return if (markerInspectionError != null) RootEvidence.InspectionFailed(markerInspectionError)
        else RootEvidence.Untrustworthy
    }
    val durable = try {
        loadStrictManifest(manifestFile)
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (manifestFailure: Exception) {
        return if (markerInspectionError != null) RootEvidence.InspectionFailed(markerInspectionError)
        else RootEvidence.InspectionFailed(manifestFailure)
    }
    if (durable.transactionId != transaction.transactionId) return RootEvidence.Untrustworthy
    if (durable.createdAt != transaction.manifest.createdAt) return RootEvidence.Untrustworthy
    if (!durable.hasSameImmutableIdentity(transaction.manifest)) return RootEvidence.Untrustworthy
    if (durable.state != ReprocessTransactionState.ACTIVE &&
        durable.state != ReprocessTransactionState.QUARANTINED) return RootEvidence.Untrustworthy
    return RootEvidence.Trustworthy
}

/**
 *  Strict fallback evidence classification.
 *
 *  Identical semantics to [RootEvidence] but for the job-directory-level fallback quarantine
 *  marker (`.reprocess_unresolved`). Used by retry-exhaustion reporting and restart gating.
 */
internal sealed class FallbackEvidence {
    data object Trustworthy : FallbackEvidence()
    data object Untrustworthy : FallbackEvidence()
    data class InspectionFailed(val cause: Throwable) : FallbackEvidence()
}

/**
 *  Strict fallback-evidence inspection for the job-directory fallback marker.
 *
 *  Verifies:
 *   - marker is a regular file
 *   - exact transaction ID, backup-root name, createdAt
 *   - canonical job relationship
 *   - no malformed or duplicate fields recognized as valid
 *
 *  A missing, corrupt, or mismatched marker → [Untrustworthy] or [InspectionFailed].
 *  Legacy fixed-content marker is NOT independently trustworthy.
 *
 *  Exception policy identical to [strictRootEvidence]:
 *   - ordinary parsing/IO failure → [Untrustworthy] or [InspectionFailed]
 *   - [CancellationException] → propagate
 *   - fatal [Error] → propagate
 */
internal fun strictFallbackEvidence(jobDir: File, transaction: ReprocessTransaction): FallbackEvidence {
    val marker = File(jobDir, REPROCESS_FALLBACK_QUARANTINE_MARKER)
    return when (val classification = classifyMarkerPath(marker, jobDir)) {
        is MarkerPathClassification.Valid -> {
            val identity = try { readFallbackIdentity(marker) }
            catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
            catch (oom: OutOfMemoryError) { throw oom }
            catch (td: ThreadDeath) { throw td }
            catch (le: LinkageError) { throw le }
            catch (ie: InternalError) { throw ie }
            catch (e: Error) { throw e }
            catch (readFailure: Exception) { return FallbackEvidence.InspectionFailed(readFailure) }
            if (identity == null) return FallbackEvidence.Untrustworthy
            if (identity.first != transaction.transactionId) return FallbackEvidence.Untrustworthy
            if (identity.second != transaction.backupRoot.name) return FallbackEvidence.Untrustworthy
            if (identity.third != transaction.manifest.createdAt) return FallbackEvidence.Untrustworthy
            FallbackEvidence.Trustworthy
        }
        is MarkerPathClassification.InspectionError -> FallbackEvidence.InspectionFailed(classification.cause)
        is MarkerPathClassification.Absent,
        is MarkerPathClassification.Symlink,
        is MarkerPathClassification.NotRegularFile,
        is MarkerPathClassification.NotDirectChild -> FallbackEvidence.Untrustworthy
    }
}

/** Boolean shim for fallback evidence. */
internal fun hasTrustworthyFallbackEvidence(jobDir: File, transaction: ReprocessTransaction): Boolean =
    strictFallbackEvidence(jobDir, transaction) === FallbackEvidence.Trustworthy

/**
 * Marker path classification using NIO [LinkOption.NOFOLLOW_LINKS].
 * [Valid] only when the marker is a regular file, not a symlink, and a direct child
 * of the expected parent directory. IO/security failures produce [InspectionError].
 */
internal sealed class MarkerPathClassification {
    data class Valid(val file: File) : MarkerPathClassification()
    data object Absent : MarkerPathClassification()
    data object NotRegularFile : MarkerPathClassification()
    data object Symlink : MarkerPathClassification()
    data object NotDirectChild : MarkerPathClassification()
    data class InspectionError(val cause: Throwable) : MarkerPathClassification()
}

/**
 * Classify [marker] relative to its expected [parentDir] using NIO [LinkOption.NOFOLLOW_LINKS].
 * A symlink marker (live or dangling) is never [Valid]. A non-existent marker is [Absent].
 * A directory or special file is [NotRegularFile]. IO/security exceptions propagate as [InspectionError].
 */
internal fun classifyMarkerPath(marker: File, parentDir: File): MarkerPathClassification {
    return try {
        val path = marker.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return MarkerPathClassification.Absent
        if (Files.isSymbolicLink(path)) return MarkerPathClassification.Symlink
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return MarkerPathClassification.NotRegularFile
        val canonicalParent = parentDir.canonicalFile
        val markerParent = marker.parentFile?.canonicalFile
        if (markerParent != canonicalParent) return MarkerPathClassification.NotDirectChild
        MarkerPathClassification.Valid(marker)
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (ex: Exception) { MarkerPathClassification.InspectionError(ex) }
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
    // Idempotency: duplicate finalization of the same terminal transaction returns the exact cached
    // result — no second metadata write, no fallback creation, no second cleanup, no second lease
    // release (the lease was already released by the shared terminal cleanup path on the first call).
    session.existingTerminalResult()?.let { stored ->
        return stored
    }
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
    // Idempotency: manifest already terminal, never re-resolve.
    when (currentManifest.state) {
        ReprocessTransactionState.COMMITTED -> {
            if (operationLease != null && KeplerJobMetadata.isOperationOwner(jobDir, operationLease)) {
                try { operationLease.release() } catch (_: Exception) {}
            }
            val saved = ReprocessFinalizationResult(
                ReprocessFinalizationState.COMMITTED, Result.success(
                    KeplerReprocessResult(jobDir, jobKind, null, null, 0L, listOf("Already finalized: COMMITTED"))
                )
            )
            session.storeTerminalResult(saved)
            return saved
        }
        ReprocessTransactionState.ROLLED_BACK -> {
            if (operationLease != null && KeplerJobMetadata.isOperationOwner(jobDir, operationLease)) {
                try { operationLease.release() } catch (_: Exception) {}
            }
            val saved = ReprocessFinalizationResult(
                ReprocessFinalizationState.ROLLED_BACK, Result.failure(IllegalStateException("Already finalized: ROLLED_BACK"))
            )
            session.storeTerminalResult(saved)
            return saved
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
        val finalOutcome: Result<KeplerReprocessResult> = try {
            Result.success(finalizeReprocessOutcome(jobDir, jobKind, outputSettings, selectionMode, includedFrameIndices, outcome, transaction))
        } catch (e: OutOfMemoryError) { throw e
        } catch (e: ThreadDeath) { throw e
        } catch (e: LinkageError) { throw e
        } catch (e: InternalError) { throw e
        } catch (e: Error) { throw e
        } catch (e: Exception) {
            Result.failure<KeplerReprocessResult>(e)
        }
        finalOutcome.fold(
    onSuccess = { committed ->
      try {
        writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
      } catch (e: OutOfMemoryError) { throw e
      } catch (e: ThreadDeath) { throw e
      } catch (e: LinkageError) { throw e
      } catch (e: InternalError) { throw e
      } catch (e: Error) { throw e
      } catch (e: Exception) {
        return@fold quarantineWithPersistence(transaction, e)
      }
      val terminalResult = ReprocessFinalizationResult(ReprocessFinalizationState.COMMITTED, Result.success(committed))
      session.storeTerminalResult(terminalResult)
      performTerminalCleanupDebt(transaction, jobDir, ReprocessTransactionState.COMMITTED, ownedLease)
      terminalResult
    },
            onFailure = { metadataError ->
                if (outcome.publicExportCommitted) {
                    // Public export committed but terminal metadata failed: quarantine, never rollback.
                    return@fold quarantineWithPersistence(transaction, metadataError)
                }
                rollback(session, transaction, ownedLease, jobDir, jobKind, outcome, metadataError)
            }
        )
    } else {
        rollback(session, transaction, ownedLease, jobDir, jobKind, outcome,
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
    // Preserve the original processing/rollback error as primary context.
    var markerError: Throwable? = null
    var stateError: Throwable? = null
    try {
        writeQuarantineMarker(transaction)
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (mf: Exception) { markerError = mf }
    try {
        writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED)
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (sf: Exception) { stateError = sf }

    // Inner try used addSuppressed for linked failures; replace with narrow helper that ignores
    // only self-suppression or IllegalArgumentException for invalid suppression.
    val combined: Throwable = if (markerError != null || stateError != null) {
        val wrapping = combineCauseWithMessage(originalError, "Quarantine persistence failed after processing error", markerError)
        if (stateError != null) addSuppressedSafe(wrapping, stateError)
        wrapping
    } else originalError

    // Fallback only when both root mechanisms failed. Use one narrow suppressed-error helper and
    // compact fixed messages.
    if (markerError != null && stateError != null) {
        try {
            ensureDurableFallbackQuarantine(transaction.backupRoot.parentFile ?: transaction.backupRoot, transaction)
            return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(combined))
        } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
        catch (oom: OutOfMemoryError) { throw oom }
        catch (td: ThreadDeath) { throw td }
        catch (le: LinkageError) { throw le }
        catch (ie: InternalError) { throw ie }
        catch (e: Error) { throw e }
        catch (fallbackError: Exception) {
            val finalError = combineCauseWithMessage(combined, "Fallback quarantine persistence failed", fallbackError)
            return ReprocessFinalizationResult(ReprocessFinalizationState.QUARANTINED, Result.failure(finalError))
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
    session: ReprocessTransactionSession,
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
    } catch (e: OutOfMemoryError) { throw e
    } catch (e: ThreadDeath) { throw e
    } catch (e: LinkageError) { throw e
    } catch (e: InternalError) { throw e
    } catch (e: Error) { throw e
    } catch (metadataFailure: Exception) {
        metadataFailure
    }
    if (metadataError != null) {
        return quarantineWithPersistence(
            transaction, combineCauseWithMessage(error, "Terminal metadata failure during rollback", metadataError)
        )
    }
    try {
        writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    } catch (e: OutOfMemoryError) { throw e
    } catch (e: ThreadDeath) { throw e
    } catch (e: LinkageError) { throw e
    } catch (e: InternalError) { throw e
    } catch (e: Error) { throw e
    } catch (stateFailure: Exception) {
        return quarantineWithPersistence(
            transaction, combineCauseWithMessage(error, "State persistence failure during rollback", stateFailure)
        )
    }
    val rolledBack = ReprocessFinalizationResult(ReprocessFinalizationState.ROLLED_BACK, Result.failure(error))
    session.storeTerminalResult(rolledBack)
    performTerminalCleanupDebt(transaction, jobDir, ReprocessTransactionState.ROLLED_BACK, operationLease)
    return rolledBack
}

/**
 * Shared terminal-cleanup-debt helper. Called after durable COMMITTED or ROLLED_BACK.
 * Fallback removal, quarantine-marker removal, warning metadata, and backup cleanup are cleanup debt only.
 * Always releases the operation lease in the outermost `finally`. Never downgrades terminal result.
 * If march-failure fallback deletion fails the terminal root/manifest is preserved and cleanup skips the root.
 *
 * Warning metadata failures (KeplerJobMetadataMissing, KeplerJobMetadataCorrupt, IllegalStateException,
 * IO/Security, injected seam failures) are recorded as warnings — they must not escape after durable
 * terminal state. Cancellation, OOM, ThreadDeath, LinkageError, InternalError, and other fatal Errors
 * are not caught.
 */
private fun performTerminalCleanupDebt(
    transaction: ReprocessTransaction,
    jobDir: File,
    state: ReprocessTransactionState,
    lease: JobOperationLease
): List<String> {
    val warnings = mutableListOf<String>()
    // Outmost finally: lease release always runs, even if warning metadata throws a fatal Error
    // or CancellationException. Ordinary cleanup and warning errors remain cleanup debt and do
    // not escape or downgrade COMMITTED/ROLLED_BACK. Cancellation and fatal Errors may propagate
    // only after lease release.
    try {
        try {
            val fallbackRemoved = runTerminalFallbackRemoval(jobDir, transaction, warnings)
            if (fallbackRemoved) {
                val cleanupOk = try {
                    cleanupBackups(transaction)
                } catch (ce: CleanupEvidenceException) {
                    warnings += ce.toWarningDetail()
                    false
                } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
                catch (oom: OutOfMemoryError) { throw oom }
                catch (td: ThreadDeath) { throw td }
                catch (le: LinkageError) { throw le }
                catch (ie: InternalError) { throw ie }
                catch (e: Error) { throw e }
                catch (e: Exception) { false }
                if (!cleanupOk) {
                    warnings += "Reprocess backup cleanup failed after durable $state."
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
        catch (oom: OutOfMemoryError) { throw oom }
        catch (td: ThreadDeath) { throw td }
        catch (le: LinkageError) { throw le }
        catch (ie: InternalError) { throw ie }
        catch (e: Error) { throw e }
        catch (_: Exception) {
            warnings += "Cleanup debt threw after durable $state."
        }
        // Warning metadata: ordinary failures are ignored. Cancellation/fatal Errors propagate
        // to the outer finally (lease release) and then onward.
        if (warnings.isNotEmpty()) {
            try {
                KeplerJobMetadata.update(jobDir) { job ->
                    val existing = job.optJSONArray("reprocessWarnings")
                        ?: JSONArray().also { job.put("reprocessWarnings", it) }
                    warnings.forEach { existing.put(it) }
                    job.put("reprocessWarning", warnings.first())
                }
            } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
            catch (oom: OutOfMemoryError) { throw oom }
            catch (td: ThreadDeath) { throw td }
            catch (le: LinkageError) { throw le }
            catch (ie: InternalError) { throw ie }
            catch (e: Error) { throw e }
            catch (_: KeplerJobMetadataMissing) {
            } catch (_: KeplerJobMetadataCorrupt) {
            } catch (_: IllegalStateException) {
            } catch (_: IOException) {
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
    } finally {
        try { lease.release() }
        catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
        catch (oom: OutOfMemoryError) { throw oom }
        catch (td: ThreadDeath) { throw td }
        catch (le: LinkageError) { throw le }
        catch (ie: InternalError) { throw ie }
        catch (e: Error) { throw e }
        catch (_: Exception) {}
    }
    return warnings
}

/**
 * Attempt matching fallback removal as part of terminal cleanup debt.
 * Returns true when the fallback was absent or successfully removed.
 * Returns false when the fallback remains after attempted removal (root preserved).
 * Never throws ordinary exceptions — records warnings.
 */
private fun runTerminalFallbackRemoval(
    jobDir: File,
    transaction: ReprocessTransaction,
    warnings: MutableList<String>
): Boolean {
    try {
        val removed = removeMatchingFallbackQuarantine(jobDir, transaction)
        if (!removed) {
            warnings += "Fallback quarantine marker deletion failed after terminal state. Root preserved for retry."
            return false
        }
        return true
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (e: Exception) {
        warnings += "Fallback quarantine removal threw during terminal cleanup."
        return false
    }
}

/** Narrow injectable IO seam for created-output deletion during rollback. Tests can override and must reset in `finally`. */
internal var createdOutputDeleteOperation: ((File) -> Boolean)? = null

/**
 * Remove files created by this transaction that are safe, mutable, and proven not to have existed
 * before the transaction. Immutable source frames and unrelated pre-existing files are untouched.
 * Deletion failure means rollback is not safely complete and must quarantine.
 * Uses durable preExistingPaths from the manifest and an explicit production-owned output list/policy.
 * Only direct-child files are candidates; metadata-referenced source files are never deleted.
 */
private fun removeTransactionCreatedFiles(
    jobDir: File,
    transaction: ReprocessTransaction,
    originalError: Throwable
): Result<Unit> {
    return try {
        val manifest = loadStrictManifest(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE))
        require(manifest.hasSameImmutableIdentity(transaction.manifest)) {
            "Durable manifest identity changed before created-file rollback"
        }
        val preExisting = manifest.preExistingPaths
        val backedUp = manifest.backedUpPaths
        val backupRootName = transaction.backupRoot.name
        val backupRootCanonical = transaction.backupRoot.canonicalFile
        val children = jobDir.listFiles()
            ?: throw IllegalStateException("Cannot list job directory during created-file rollback")
        val deleteOp = createdOutputDeleteOperation
        children.filter { it.isFile }.forEach { file ->
            val name = file.name
            if (name == JOB_JSON_FILE_NAME) return@forEach
            if (name == backupRootName) return@forEach
            if (name in preExisting) return@forEach
            if (name in backedUp) return@forEach
            if (!isReprocessCreatedOutputFile(name)) return@forEach
            if (file.canonicalFile == backupRootCanonical) return@forEach
            val deleted = if (deleteOp != null) deleteOp(file) else file.delete()
            if (!deleted || file.exists()) {
                return Result.failure(combineCauseWithMessage(
                    originalError,
                    "Created-file deletion failed: $name",
                    IllegalStateException("Failed to delete created file: $name")
                ))
            }
        }
        Result.success(Unit)
    } catch (e: OutOfMemoryError) { throw e
    } catch (e: ThreadDeath) { throw e
    } catch (e: LinkageError) { throw e
    } catch (e: InternalError) { throw e
    } catch (e: Error) { throw e
    } catch (e: Exception) {
        Result.failure(e)
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
        "sharpened_night_fusion.png", SINGLE_FRAME_OUTPUT_FILE_NAME,
        "average_color_rotated.png", "denoise_color.png",
        "fused_classic_yuv_v1.png", "reference_frame.png", "raw_fusion_final.png",
        "yuv_compare_reference_vs_fused.png", "compare_reference_vs_fused.png",
        "raw_reference_preview.png", "raw_fused_classic_v1_preview.png",
        "raw_compare_reference_vs_fused.png",
        "yuv_reference_preview.png", "yuv_fused_preview.png",
        "yuv_fused_before_denoise_preview.png",
        "yuv_fused_after_denoise_no_sharpen_preview.png",
        "yuv_final_preview.png", "yuv_compare_reference_vs_final.png",
        "fused_before_denoise_preview.png",
        "fused_after_denoise_no_sharpen_preview.png",
        "final_preview.png", "reference_single_preview.png",
        "compare_reference_vs_final.png",
        "fusion_debug.json", "yuv_debug.json", "raw_fusion_debug.json",
        "raw_render_debug.json", "raw_render_input_metadata.json",
        "native_postprocess.json"
    )
    if (lower in explicit) return true
    if (lower.startsWith("fused_classic_yuv_v1_")) return true
    if (name.startsWith(REPROCESS_PREVIEW_PREFIX)) return true
    if (lower.endsWith(".rgba") || lower.endsWith(".rgb") || lower.endsWith(".bin")) return true
    if (lower.endsWith(".tmp") || lower.endsWith(".restore")) return true
    if (lower.startsWith("merged_raw")) return true
    if (lower.contains("merged_yuv")) return true
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
    val expectedContent = quarantineMarkerContent(transaction)
    when (val existing = classifyMarkerPath(marker, root)) {
        is MarkerPathClassification.Valid -> {
            val identity = readQuarantineMarkerIdentity(marker)
            if (identity != null &&
                identity.first == transaction.transactionId &&
                identity.second == transaction.backupRoot.name &&
                identity.third == transaction.manifest.createdAt
            ) {
                return // Identity-bound marker with matching identity already exists
            }
        }
        is MarkerPathClassification.Absent -> { /* write below */ }
        is MarkerPathClassification.Symlink -> error("Quarantine marker is a symlink, not a direct child: $marker")
        is MarkerPathClassification.NotRegularFile -> error("Quarantine marker path exists but is not a regular file: $marker")
        is MarkerPathClassification.NotDirectChild -> error("Quarantine marker parent is not the backup root: $marker")
        is MarkerPathClassification.InspectionError -> error("Quarantine marker inspection failed: $marker")
    }
    val writeOp = quarantineMarkerWriteOperation
    if (writeOp != null) {
        writeOp(marker, expectedContent)
    } else {
        KeplerJobMetadata.atomicWrite(marker, expectedContent)
    }
    check(classifyMarkerPath(marker, root) is MarkerPathClassification.Valid) { "Quarantine marker write produced no valid file: $marker" }
    val writtenIdentity = readQuarantineMarkerIdentity(marker)
    check(writtenIdentity != null &&
          writtenIdentity.first == transaction.transactionId &&
          writtenIdentity.second == transaction.backupRoot.name &&
          writtenIdentity.third == transaction.manifest.createdAt) {
        "Quarantine marker content verification failed after write: $marker"
    }
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
 * Narrow suppressed-error helper. Adds [suppressed] as a suppressed exception of the [primary]
 * unless both refer to the same instance (self-suppression) or the JVM refuses the suppression
 * ([IllegalArgumentException] for an invalid suppression). Never broad `runCatching`. Cancellation
 * and fatal Errors propagate unchanged.
 */
internal fun addSuppressedSafe(primary: Throwable, suppressed: Throwable) {
    if (suppressed === primary) return
    try {
        primary.addSuppressed(suppressed)
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (_: IllegalArgumentException) { /* invalid suppression — ignore */ }
}

/**
 * Persist durable unresolved/quarantine evidence when a worker exit is uncertain (timeout, cancel
 * callback failure, caller cancellation while active). Attempts marker and state persistence
 * independently; root evidence succeeds when either operation succeeds and verifies via
 * [strictRootEvidence]. The fallback is created only when both root mechanisms fail.
 *
 * The result preserves the failure of each mechanism separately. The caller may combine them but
 * the persistence result itself never reports the worker cancel failure as unresolved persistence
 * failure — the [cancelError] is preserved separately for caller-side rethrow/aggregation.
 *
 * Fatal Errors and [CancellationException] propagate unchanged from marker/state inspection.
 */
internal fun persistUnresolvedQuarantine(
    session: ReprocessTransactionSession,
    transaction: ReprocessTransaction,
    cancelFailure: Throwable?
): UnresolvedPersistenceResult {
    var markerFailure: Throwable? = null
    var stateFailure: Throwable? = null
    try {
        writeQuarantineMarker(transaction)
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (mf: Exception) { markerFailure = mf }

    try {
        writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED)
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (sf: Exception) { stateFailure = sf }

    val markerPersisted = markerFailure == null
    val statePersisted = stateFailure == null
    // Root evidence succeeds when either mechanism succeeded AND the strict inspection verifies.
    val jobDir = transaction.backupRoot.parentFile
    val durableEvidenceEstablished = jobDir != null && (markerPersisted || statePersisted) &&
        strictRootEvidence(jobDir, transaction) === RootEvidence.Trustworthy

    var fallbackFailure: Throwable? = null
    var fallbackPersisted = false
    if (!durableEvidenceEstablished) {
        try {
            ensureDurableFallbackQuarantine(transaction.backupRoot.parentFile ?: transaction.backupRoot, transaction)
            fallbackPersisted = true
        } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
        catch (oom: OutOfMemoryError) { throw oom }
        catch (td: ThreadDeath) { throw td }
        catch (le: LinkageError) { throw le }
        catch (ie: InternalError) { throw ie }
        catch (e: Error) { throw e }
        catch (fb: Exception) { fallbackFailure = fb }
    }
    return UnresolvedPersistenceResult(
        rootMarkerPersisted = markerPersisted,
        quarantinedStatePersisted = statePersisted,
        fallbackPersisted = fallbackPersisted,
        durableEvidenceEstablished = durableEvidenceEstablished || fallbackPersisted,
        markerError = markerFailure,
        stateError = stateFailure,
        fallbackError = fallbackFailure,
        cancelError = cancelFailure
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
    when (val existing = classifyMarkerPath(marker, jobDir)) {
        is MarkerPathClassification.Valid -> {
            check(readFallbackIdentity(marker) == fallbackIdentity(transaction)) {
                "Existing fallback quarantine marker belongs to another or corrupt transaction"
            }
            return
        }
        is MarkerPathClassification.Absent -> { /* write below */ }
        is MarkerPathClassification.Symlink ->
            error("Fallback quarantine marker is a symlink, not a direct child")
        is MarkerPathClassification.NotRegularFile ->
            error("Fallback quarantine marker exists but is not a regular file")
        is MarkerPathClassification.NotDirectChild ->
            error("Fallback quarantine marker parent is not the job directory")
        is MarkerPathClassification.InspectionError ->
            error("Fallback quarantine marker inspection failed")
    }
    val payload = buildString {
        append("transactionId="); append(transaction.transactionId); append('\n')
        append("backupRoot="); append(transaction.backupRoot.name); append('\n')
        append("createdAt="); append(transaction.manifest.createdAt); append('\n')
    }
    val writeOp = fallbackWriteOperation
    if (writeOp != null) writeOp(jobDir, marker, payload)
    else KeplerJobMetadata.atomicWrite(marker, payload)
    check(classifyMarkerPath(marker, jobDir) is MarkerPathClassification.Valid) {
        "Fallback quarantine marker write produced no valid file: $marker"
    }
    val writtenIdentity = readFallbackIdentity(marker)
    check(writtenIdentity == fallbackIdentity(transaction)) {
        "Fallback quarantine marker content verification failed: expected ${fallbackIdentity(transaction)}, got $writtenIdentity"
    }
}

private fun fallbackIdentity(transaction: ReprocessTransaction): Triple<String, String, Long> = Triple(
    transaction.transactionId, transaction.backupRoot.name, transaction.manifest.createdAt
)

/** Strict identity parser shared by fallback and quarantine markers. Expects exactly 3
 *  physical lines in `key=value` format with keys: transactionId, backupRoot, createdAt.
 *  Rejects blank lines (leading, trailing, or intermediate), duplicate keys, unknown keys,
 *  blank values, whitespace padding, extra `=` in value, non-positive createdAt,
 *  backupRoot that does not start with `.reprocess_backup_`, transactionId with whitespace,
 *  `/`, `\`, `.` or `..`, and backupRoot that is not exactly `.reprocess_backup_` + transactionId.
 *  Does NOT catch exceptions — IO/security failures propagate to the caller so the evidence
 *  layer can distinguish InspectionFailed from Untrustworthy. */
private fun readMarkerIdentity(marker: File): Triple<String, String, Long>? {
    val lines = marker.readLines()
    if (lines.size != 3) return null
    if (lines.any { it.isBlank() }) return null
    val parsed = linkedMapOf<String, String>()
    for (line in lines) {
        val split = line.indexOf('=')
        if (split <= 0 || split == line.length - 1) return null
        val key = line.substring(0, split)
        if (parsed.containsKey(key)) return null
        val value = line.substring(split + 1)
        if (value.contains('=')) return null
        parsed[key] = value
    }
    if (parsed.keys != setOf("transactionId", "backupRoot", "createdAt")) return null
    if (parsed.keys.toList() != listOf("transactionId", "backupRoot", "createdAt")) return null
    val txId = parsed.getValue("transactionId")
    if (txId.isBlank()) return null
    if (txId.any { it.isWhitespace() }) return null
    if (txId.any { it == '/' || it == '\\' }) return null
    if (txId == "." || txId == "..") return null
    val rootName = parsed.getValue("backupRoot")
    if (rootName.isBlank() || !rootName.startsWith(".reprocess_backup_")) return null
    if (rootName != ".reprocess_backup_$txId") return null
    val createdAtStr = parsed.getValue("createdAt")
    val createdAt = createdAtStr.toLongOrNull() ?: return null
    if (createdAt <= 0L) return null
    if (createdAtStr != createdAt.toString()) return null
    return Triple(txId, rootName, createdAt)
}

/** Read fallback quarantine marker identity using the shared strict parser.
 *  Returns null for malformed content. IO/security exceptions propagate. */
private fun readFallbackIdentity(marker: File): Triple<String, String, Long>? {
    return readMarkerIdentity(marker)
}

/** Identity-bound content for the quarantine marker inside the backup root. */
internal fun quarantineMarkerContent(transaction: ReprocessTransaction): String = buildString {
    append("transactionId="); append(transaction.transactionId); append('\n')
    append("backupRoot="); append(transaction.backupRoot.name); append('\n')
    append("createdAt="); append(transaction.manifest.createdAt); append('\n')
}

/** Parse an identity-bound quarantine marker using the shared strict parser, then check for the
 *  legacy fixed-content format ("quarantined") which returns null. IO/security exceptions
 *  propagate to the caller so the evidence layer can distinguish InspectionFailed from
 *  Untrustworthy. */
internal fun readQuarantineMarkerIdentity(marker: File): Triple<String, String, Long>? {
    val lines = marker.readLines()
    if (lines.size == 1 && lines[0].trim() == "quarantined") return null
    return readMarkerIdentity(marker)
}

/**
 * Narrow injectable IO seam for fallback marker deletion. Tests can override and must reset in `finally`.
 * Receiving the marker [File] to delete; must return true when the file is gone after the attempt.
 */
internal var fallbackDeleteOperation: ((File) -> Boolean)? = null

/** Narrow injectable IO seam for fallback marker creation during late finalization. Tests can
 *  override to inject failure of `ensureDurableFallbackQuarantine`. Receives (jobDir, marker);
 *  the real call writes via `KeplerJobMetadata.atomicWrite`. Reset in `finally`. */
internal var fallbackWriteOperation: ((File, File, String) -> Unit)? = null

/** Narrow injectable seam: when non-null, the production late-finalization failure reporter
 *  is replaced with this callback. Receives the combined error with original worker/finalizer/
 *  callback error plus root-inspection and fallback-persistence failures. The lease remains held.
 *  Tests may inject a handler; production has a real default reporting path.
 *  Always reset in `finally`. */
internal var lateFinalizationFailureHandler: ((Throwable, File) -> Unit)? = null

/** Narrow injectable seam around the finalizer invocation for tests to force ordinary
 *  finalizer failure while terminal retrieval succeeds. When non-null and returns non-null,
 *  the returned exception is thrown inside [finalizeTransaction] as if the finalizer itself
 *  failed. Always reset in `finally`. */
internal var finalizerFailureSeam: (() -> Throwable?)? = null

/**
 * Removes only a verified matching fallback marker after a durable terminal transition.
 * Returns true only when the marker was absent or is confirmed gone after deletion.
 * Returns false when the identity does not match or deletion leaves the file present.
 */
internal fun removeMatchingFallbackQuarantine(jobDir: File, transaction: ReprocessTransaction): Boolean {
    val marker = File(jobDir, REPROCESS_FALLBACK_QUARANTINE_MARKER)
    return when (val classification = classifyMarkerPath(marker, jobDir)) {
        is MarkerPathClassification.Absent -> true
        is MarkerPathClassification.Valid -> {
            if (readFallbackIdentity(marker) != fallbackIdentity(transaction)) return false
            val deleteOp = fallbackDeleteOperation
            val deleted = if (deleteOp != null) deleteOp(marker) else marker.delete()
            deleted && classifyMarkerPath(marker, jobDir) is MarkerPathClassification.Absent
        }
        else -> false
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
    when (classifyMarkerPath(fallbackMarker, jobDir)) {
        is MarkerPathClassification.Absent -> { /* no fallback, continue */ }
        else -> return true
    }
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
  val jobDirListing = try { jobDir.listFiles() } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
  catch (oom: OutOfMemoryError) { throw oom }
  catch (td: ThreadDeath) { throw td }
  catch (le: LinkageError) { throw le }
  catch (ie: InternalError) { throw ie }
  catch (e: Error) { throw e }
  catch (_: Exception) { null }
  if (jobDirListing == null) {
    if (jobDir.exists() && jobDir.isDirectory) {
      return
    }
    return
  }
val children = jobDirListing
  val fallbackBlocked = when (val fc = classifyMarkerPath(fallbackMarker, jobDir)) {
    is MarkerPathClassification.Absent -> false
    is MarkerPathClassification.Valid -> {
      val fallbackId = try { readFallbackIdentity(fallbackMarker) }
        catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
        catch (oom: OutOfMemoryError) { throw oom }
        catch (td: ThreadDeath) { throw td }
        catch (le: LinkageError) { throw le }
        catch (ie: InternalError) { throw ie }
        catch (e: Error) { throw e }
        catch (_: Exception) { null }
      if (fallbackId == null) {
        true
      } else {
        val matchingRoot = children.firstOrNull { it.isDirectory && it.name == fallbackId.second }
        if (matchingRoot == null) {
          true
        } else {
          val classification = classifyTransactionManifest(jobDir, matchingRoot)
          if (classification !is ManifestClassification.Resolved) {
            true
          } else {
            val manifest = try { loadStrictManifest(File(matchingRoot, REPROCESS_TX_MANIFEST_FILE)) }
            catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
            catch (oom: OutOfMemoryError) { throw oom }
            catch (td: ThreadDeath) { throw td }
            catch (le: LinkageError) { throw le }
            catch (ie: InternalError) { throw ie }
            catch (e: Error) { throw e }
            catch (_: Exception) { null }
            if (manifest == null ||
              manifest.transactionId != fallbackId.first ||
              matchingRoot.name != fallbackId.second ||
              manifest.createdAt != fallbackId.third
            ) {
              true
            } else {
              val deleted = try {
                val deleteOp = fallbackDeleteOperation
                val result = if (deleteOp != null) deleteOp(fallbackMarker) else fallbackMarker.delete()
                result && classifyMarkerPath(fallbackMarker, jobDir) is MarkerPathClassification.Absent
              } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
              catch (oom: OutOfMemoryError) { throw oom }
              catch (td: ThreadDeath) { throw td }
              catch (le: LinkageError) { throw le }
              catch (ie: InternalError) { throw ie }
              catch (e: Error) { throw e }
              catch (_: Exception) { false }
              !deleted
            }
          }
        }
      }
    }
    // Symlink, NotRegularFile, NotDirectChild, InspectionError → blocked
    else -> true
  }

  if (!fallbackBlocked) {
    children.forEach { child ->
      if (child.isDirectory && child.name.startsWith(".reprocess_backup_")) {
        val classification = classifyTransactionManifest(jobDir, child)
        when (classification) {
          is ManifestClassification.Unresolved -> {
            if (isRootEvidenceFree(child)) {
              try { child.delete() } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
              catch (oom: OutOfMemoryError) { throw oom }
              catch (td: ThreadDeath) { throw td }
              catch (le: LinkageError) { throw le }
              catch (ie: InternalError) { throw ie }
              catch (e: Error) { throw e }
              catch (_: Exception) { }
            }
          }
          is ManifestClassification.Resolved -> {
            cleanupTerminalRoot(child)
          }
        }
      }
    }
  }
}

/** True when a root has no marker, no manifest, no backup payload, and no temp evidence. */
private fun isRootEvidenceFree(root: File): Boolean {
    val children = root.listFiles() ?: return false
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
    val durable = try {
        loadStrictManifest(manifestFile)
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (_: Exception) {
        // Restart recovery must not throw ordinary injected IO failures into gallery loading.
        return
    }
    val state = durable.state
    if (state != ReprocessTransactionState.COMMITTED && state != ReprocessTransactionState.ROLLED_BACK) return
    val dummyTx = ReprocessTransaction(durable.transactionId, root, durable, emptyList())
    try {
        cleanupBackups(dummyTx)
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (_: Exception) { /* swallow ordinary cleanup failure during restart recovery */ }
}

fun detectReprocessCapability(context: Context, jobDir: File): ReprocessCapability {
    val target = try { requireReprocessSafeJobDirectory(context, jobDir) }
    catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (_: Exception) { null }
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
    val framesValidation = validateMetadataSourceFrames(target, job)
    if (framesValidation is MetadataSourceValidation.Malformed) {
        return ReprocessCapability(
            canReprocess = false,
            jobKind = ReprocessJobKind.UNSUPPORTED,
            reason = framesValidation.reason,
            sourceFrameCount = 0,
            finalOutputExists = false,
            sourceFramesAvailable = false
        )
    }
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
            val singleFrame = isSingleFrameJob(job)
            val requiredFrames = if (singleFrame) 1 else 2
            val canRun = sourceCount >= requiredFrames
            ReprocessCapability(
                canReprocess = canRun,
                jobKind = kind,
                reason = if (canRun) {
                    if (singleFrame) {
                        "단일 원본 프레임에 ISP 후처리를 다시 적용할 수 있습니다."
                    } else {
                        "YUV 원본 프레임으로 다시 합성할 수 있습니다."
                    }
                } else {
                    "원본 프레임이 부족하여 다시 처리할 수 없습니다."
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
        rawType == "YUV_NIGHT_FUSION" || rawType == "YUV_SINGLE_FRAME" ||
            jobDir.name.startsWith("KPL_YUV_FUSION_") -> ReprocessJobKind.YUV_FUSION
        jobDir.name.startsWith("KPL_COLOR_BURST_") -> ReprocessJobKind.COLOR_BURST
        else -> ReprocessJobKind.UNSUPPORTED
    }
}

internal fun countActualSourceFrames(jobDir: File, job: JSONObject, kind: ReprocessJobKind): Int {
    val frames = job.optJSONArray("frames")
    if (frames != null) {
        var count = 0
        repeat(frames.length()) { index ->
            val value = frames.opt(index)
            if (value !is JSONObject) return 0
            val frame = value as JSONObject
            val candidates = when (kind) {
                ReprocessJobKind.RAW_FUSION -> listOfNotNull(
                    frame.optString("raw16File"),
                    frame.optString("dngFile"),
                    frame.optString("file")
                )
                ReprocessJobKind.YUV_FUSION, ReprocessJobKind.COLOR_BURST -> listOfNotNull(
                    frame.optString("file"),
                    frame.optString("yuvFile"),
                    frame.optString("nv21File")
                )
                ReprocessJobKind.UNSUPPORTED -> emptyList()
            }
            for (name in candidates) {
                if (name.isNotBlank() && name != "null" && isStrictRelativePath(name)) {
                    val source = when (val result = NoFollowFileSystem.resolveDirectChildResult(
                        jobDir, name, requireFile = true
                    )) {
                        is NoFollowInspection.Present -> result.value
                        NoFollowInspection.Absent, is NoFollowInspection.InspectionFailed -> null
                    }
                    if (source != null && isReprocessMetadataSourceFrame(name, kind)) {
                        count++
                        return@repeat
                    }
                }
            }
        }
        return count
    }
    return jobDir.listFiles()
        ?.count { it.isFile && isReprocessSourceFrame(it, kind) }
        ?: 0
}

/** Shared strict source-path validation used by counting and backup creation.
 * Requires canonical parent == canonical job dir, existing regular file, and no symlink escapes. */
internal fun isValidMetadataSourceFile(source: File, canonicalJobDir: File): Boolean {
    val resolved = when (val result = NoFollowFileSystem.resolveDirectChildResult(
        canonicalJobDir, source.name, requireFile = true
    )) {
        is NoFollowInspection.Present -> result.value
        NoFollowInspection.Absent, is NoFollowInspection.InspectionFailed -> return false
    }
    return resolved.toPath().toAbsolutePath().normalize() ==
        source.toPath().toAbsolutePath().normalize()
}

/** Result of validating the source frames metadata key for capability detection. */
internal sealed class MetadataSourceValidation {
    data object Valid : MetadataSourceValidation()
    data class Malformed(val reason: String) : MetadataSourceValidation()
}

/**
 * Validate the `frames` metadata array for structural correctness before capability detection.
 * Rejects non-object entries, empty frames, and entries missing all source references.
 * Returns [MetadataSourceValidation.Malformed] with a user-visible reason on failure.
 */
internal fun validateMetadataSourceFrames(jobDir: File, job: JSONObject): MetadataSourceValidation {
    val frames = job.optJSONArray("frames") ?: return MetadataSourceValidation.Valid
    if (frames.length() == 0) return MetadataSourceValidation.Malformed(
        "No source frames declared in metadata."
    )
    repeat(frames.length()) { index ->
        val value = frames.opt(index)
        if (value !is JSONObject) {
            return MetadataSourceValidation.Malformed(
                "Frame entry at index $index is not a JSON object."
            )
        }
        val frame = value as JSONObject
        val candidates = listOfNotNull(
            frame.optString("raw16File"),
            frame.optString("dngFile"),
            frame.optString("file"),
            frame.optString("yuvFile"),
            frame.optString("nv21File")
        ).filter { it.isNotBlank() && it != "null" }
        if (candidates.isEmpty()) {
            return MetadataSourceValidation.Malformed(
                "Frame entry at index $index has no valid source references."
            )
        }
        var hasValidSource = false
        for (ref in candidates) {
            if (!isStrictRelativePath(ref)) {
                return MetadataSourceValidation.Malformed(
                    "Unsafe source reference: $ref"
                )
            }
            when (val result = NoFollowFileSystem.resolveDirectChildResult(jobDir, ref, requireFile = true)) {
                is NoFollowInspection.Present -> hasValidSource = true
                NoFollowInspection.Absent -> Unit
                is NoFollowInspection.InspectionFailed -> return MetadataSourceValidation.Malformed(
                    "Unsafe or unreadable source reference: $ref"
                )
            }
        }
        if (!hasValidSource) {
            return MetadataSourceValidation.Malformed(
                "Frame entry at index $index references sources that do not exist or are not regular files."
            )
        }
    }
    return MetadataSourceValidation.Valid
}

internal fun isReprocessSourceFrame(file: File, kind: ReprocessJobKind): Boolean {
    val name = file.name.lowercase(Locale.US)
    if (!name.startsWith("frame_")) return false
    return isReprocessSourceFrameFromMetadata(name, kind)
}

internal fun isReprocessMetadataSourceFrame(name: String, kind: ReprocessJobKind): Boolean {
    val lower = name.lowercase(Locale.US)
    return isReprocessSourceFrameFromMetadata(lower, kind)
}

private fun isReprocessSourceFrameFromMetadata(lower: String, kind: ReprocessJobKind): Boolean {
    return when (kind) {
        ReprocessJobKind.RAW_FUSION -> lower.endsWith(".raw16") || lower.endsWith(".dng")
        ReprocessJobKind.YUV_FUSION -> lower.endsWith(".png") || lower.endsWith(".yuv") ||
            lower.endsWith(".nv21") || lower.endsWith(".yuv420")
        ReprocessJobKind.COLOR_BURST -> lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
        ReprocessJobKind.UNSUPPORTED -> false
    }
}

internal fun loadJobJsonSafe(jobDir: File): JSONObject =
    File(jobDir, JOB_JSON_FILE_NAME).takeIf { it.isFile }?.let {
        try { KeplerJobMetadata.read(jobDir) }
        catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
        catch (oom: OutOfMemoryError) { throw oom }
        catch (td: ThreadDeath) { throw td }
        catch (le: LinkageError) { throw le }
        catch (ie: InternalError) { throw ie }
        catch (e: Error) { throw e }
        catch (_: Exception) { null }
    } ?: JSONObject()

private fun finalOutputCandidates(jobDir: File, job: JSONObject): List<File> {
    val names = listOf(
        job.optString("finalNightFusionFile"),
        job.optString("finalFile"),
        job.optString("outputFile"),
        job.optString("galleryDisplayFile"),
        "raw_fusion_final.png",
        "sharpened_night_fusion.png",
        SINGLE_FRAME_OUTPUT_FILE_NAME
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
    val path = manifestFile.toPath()
    val attrs = when (val inspection = NoFollowFileSystem.inspect(path)) {
        NoFollowInspection.Absent -> error("Manifest file missing: ${manifestFile.absolutePath}")
        is NoFollowInspection.InspectionFailed -> throw inspection.exception
        is NoFollowInspection.Present -> inspection.value
    }
    require(attrs.isRegularFile && !attrs.isSymbolicLink()) {
        "Manifest file must be a regular non-symlink file: ${manifestFile.absolutePath}"
    }
    fun sameManifestIdentity(): Boolean = when (val inspection = NoFollowFileSystem.inspect(path)) {
        is NoFollowInspection.Present -> {
            val current = inspection.value
            current.isRegularFile && !current.isSymbolicLink() &&
                if (attrs.fileKey() != null && current.fileKey() != null) {
                    attrs.fileKey() == current.fileKey()
                } else {
                    attrs.size() == current.size() &&
                        attrs.lastModifiedTime() == current.lastModifiedTime()
                }
        }
        else -> false
    }
    require(sameManifestIdentity()) {
        "Manifest file changed before open: ${manifestFile.absolutePath}"
    }
    val rawBytes = java.nio.file.Files.newByteChannel(
        path,
        java.nio.file.StandardOpenOption.READ,
        java.nio.file.LinkOption.NOFOLLOW_LINKS
    ).use { channel ->
        require(attrs.size() <= Int.MAX_VALUE) { "Manifest file is too large" }
        val bytes = ByteArray(attrs.size().toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = channel.read(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            if (read <= 0) break
            offset += read
        }
        require(offset == bytes.size) { "Manifest file was truncated while reading" }
        bytes
    }
    require(sameManifestIdentity()) {
        "Manifest file changed while reading: ${manifestFile.absolutePath}"
    }
    val json = JSONObject(String(rawBytes, Charsets.UTF_8))
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
    val durable = try { loadStrictManifest(manifestFile) }
    catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (_: Exception) { return false }
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
private fun isImmutableSourceFrame(file: File, jobKind: ReprocessJobKind): Boolean {
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
        ReprocessJobKind.UNSUPPORTED -> false
    }
}

/** True if the file is a mutable output or metadata that the reprocess worker may overwrite. */
internal fun isReprocessWorkerWritable(file: File, kind: ReprocessJobKind): Boolean = !isImmutableSourceFrame(file, kind)

/** Compute SHA-256 digest of a file by streaming — never loads whole large files into memory. */
private fun computeSha256(file: File): String =
    NoFollowFileSystem.digestVerified(file).sha256

internal fun backupReprocessTransaction(
    jobDir: File,
    files: List<File>,
    job: JSONObject? = null,
    jobKind: ReprocessJobKind? = null
): Result<ReprocessTransaction> {
    val transactionId = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    val root = File(jobDir, ".reprocess_backup_$transactionId")
    return try {
        check(root.mkdirs()) { "Could not create reprocess backup directory." }
        val metadata = File(jobDir, JOB_JSON_FILE_NAME)
        check(metadata.isFile) { "job.json is required for rollback." }
        val validatedJob = job ?: KeplerJobMetadata.read(jobDir)
        val resolvedKind = jobKind ?: detectJobKind(jobDir, validatedJob)
        check(resolvedKind != ReprocessJobKind.UNSUPPORTED) { "Unsupported job kind." }
        val canonicalJobDir = jobDir.canonicalFile
        val jobDirListing = jobDir.listFiles()
        check(jobDirListing != null) { "Cannot read job directory contents." }
        val immutableSourceFiles = mutableSetOf<File>()
        val frames = validatedJob.optJSONArray("frames")
        if (frames != null) {
            val validation = validateMetadataSourceFrames(jobDir, validatedJob)
            if (validation is MetadataSourceValidation.Malformed) {
                throw IllegalStateException(validation.reason)
            }
            repeat(frames.length()) { index ->
                val value = frames.opt(index)
                check(value is JSONObject) { "Frame entry at index $index is not a JSON object." }
                val frame = value as JSONObject
                val candidates = listOfNotNull(
                    frame.optString("raw16File"),
                    frame.optString("dngFile"),
                    frame.optString("file"),
                    frame.optString("yuvFile"),
                    frame.optString("nv21File")
                ).filter { it.isNotBlank() && it != "null" }
                require(candidates.isNotEmpty()) { "Frame entry at index $index has no valid source references." }
                var hasValidSource = false
                for (ref in candidates) {
                    require(isStrictRelativePath(ref)) {
                        "Unsafe source reference in frames metadata: $ref"
                    }
                    val source = File(canonicalJobDir, ref)
                    if (source.parentFile?.canonicalFile != canonicalJobDir) {
                        require(false) { "Source reference escapes job directory: $ref" }
                    }
                    // Reject direct-child symlinks that resolve outside the job directory.
                    if (source.isFile && source.canonicalPath != source.absolutePath) {
                        val canonicalTarget = source.canonicalFile
                        if (canonicalTarget.parentFile != canonicalJobDir) {
                            require(false) { "Symlink source reference escapes job directory: $ref" }
                        }
                    }
                    // Must be a regular file that is a direct child of the canonical job directory.
                    if (isValidMetadataSourceFile(source, canonicalJobDir) &&
                        isReprocessMetadataSourceFrame(ref, resolvedKind)) {
                        hasValidSource = true
                        immutableSourceFiles += source.canonicalFile
                    }
                }
                require(hasValidSource) { "Frame entry at index $index has no valid existing source file." }
            }
        } else {
            // Legacy job without frames metadata: use filename-based detection as fallback.
            jobDirListing.forEach { child ->
                if (child.isFile && isImmutableSourceFrame(child, resolvedKind)) {
                    immutableSourceFiles += child.canonicalFile
                }
            }
        }
        val preExistingNames = jobDirListing.filter { it.isFile }.map { it.name }.toSet()
        // Record every pre-existing mutable file (job.json + worker-writable non-source files).
        val filesToBackup = (files + metadata)
            .asSequence()
            .filter { it.isFile }
            .map { it.canonicalFile }
            .distinctBy { it.path }
            .filter { it.parentFile?.canonicalFile == jobDir.canonicalFile }
            .filter { it !in immutableSourceFiles && isReprocessWorkerWritable(it, resolvedKind) }
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
        Result.success(ReprocessTransaction(transactionId, root, manifest, backups))
    } catch (e: kotlinx.coroutines.CancellationException) {
        // Pre-ACTIVE caller cancellation: do not write ordinary failure metadata, do not create
        // an ACTIVE transaction after cancellation, do not convert cancellation into rollback.
        // Partial backup artifacts are cleaned best-effort under a narrow non-fatal boundary.
        try {
            root.listFiles()?.forEach { it.delete() }
            if (root.exists()) root.delete()
        } catch (oom: OutOfMemoryError) { throw oom }
        catch (td: ThreadDeath) { throw td }
        catch (le: LinkageError) { throw le }
        catch (ie: InternalError) { throw ie }
        catch (fatal: Error) { throw fatal }
        catch (_: Exception) { /* best-effort partial cleanup; cancellation is the caller's error */ }
        throw e
    } catch (e: OutOfMemoryError) { throw e
    } catch (e: ThreadDeath) { throw e
    } catch (e: LinkageError) { throw e
    } catch (e: InternalError) { throw e
    } catch (e: Error) { throw e
    } catch (e: Exception) {
        root.listFiles()?.forEach { it.delete() }
        if (root.exists()) root.delete()
        Result.failure(e)
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
internal fun restoreBackups(jobDir: File, transaction: ReprocessTransaction): Result<Unit> {
    return try {
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
        // Clean up any remaining temp files best-effort. Cancellation/fatal Errors propagate unchanged.
        staged.forEach {
            if (it.temp.exists()) {
                try { it.temp.delete() }
                catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
                catch (oom: OutOfMemoryError) { throw oom }
                catch (td: ThreadDeath) { throw td }
                catch (le: LinkageError) { throw le }
                catch (ie: InternalError) { throw ie }
                catch (e: Error) { throw e }
                catch (_: Exception) { /* best-effort temp cleanup; rollback already validated */ }
            }
        }
    }
    Result.success(Unit)
    } catch (e: OutOfMemoryError) { throw e
    } catch (e: ThreadDeath) { throw e
    } catch (e: LinkageError) { throw e
    } catch (e: InternalError) { throw e
    } catch (e: Error) { throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Structured cleanup-evidence exception thrown by [cleanupBackups] when post-manifest-delete
 * recovery fails (manifest restoration AND fallback both fail). [performTerminalCleanupDebt]
 * catches this and records the structured causes as warning debt. Never downgrades terminal
 * COMMITTED/ROLLED_BACK — cleanup debt is observable only via warnings metadata.
 */
internal class CleanupEvidenceException(
    message: String,
    val triggerDescription: String,
    val triggerException: Throwable?,
    val manifestError: Throwable?,
    val fallbackError: Throwable?
) : IllegalStateException(message) {
    /** Combine all non-null causes into a single descriptive string for warning metadata. */
    fun toWarningDetail(): String = buildString {
        append("Cleanup evidence recovery failed: $triggerDescription")
        if (triggerException != null) append("; trigger: ${triggerException.message}")
        if (manifestError != null) append("; manifest: ${manifestError.message}")
        if (fallbackError != null) append("; fallback: ${fallbackError.message}")
    }
}

/** Narrow injectable IO seam for file deletion in cleanup. Tests can override and must reset in `finally`. */
internal var cleanupDeleteOperation: (File) -> Boolean = { it.delete() }

/** Narrow injectable IO seam for directory listing in cleanup. Tests can override to inject null returns or
 *  to simulate directory listing errors. Receives the directory [File]; the real call is `root.listFiles()`.
 *  Reset in `finally` after use. */
internal var cleanupListOperation: ((File) -> Array<File>?)? = null

/** Narrow injectable IO seam for the root directory deletion at the end of cleanup. The real call is
 *  [File.delete]. Tests inject false to simulate actual root deletion failure while payload removal
 *  succeeded. Reset in `finally`. Never uses recursive deletion — only removes an already-empty root. */
internal var cleanupRootDeleteOperation: (File) -> Boolean = { it.delete() }

/** Narrow injectable seam: called after the terminal manifest is deleted and before the
 *  post-deletion listing. Tests simulate a new file appearing during (window) to verify
 *  cleanup refuses it. The unknown file must survive and must never be recursively removed.
 *  Reset in `finally`. */
internal var afterManifestDeleteOperation: ((File) -> Unit)? = null

/** Narrow injectable seam: overrides canonical file resolution inside [strictRootEvidence].
 *  When non-null, replaces the `file.canonicalFile` call and may throw a known IOException
 *  or SecurityException to verify InspectionFailed preserves the exact exception. Reset in
 *  `finally`. */
internal var strictRootEvidenceCanonicalizationSeam: ((File) -> File)? = null

/** Narrow injectable seam: overrides only the manifest write step inside [cleanupBackups].
 *  The write seam receives the manifest file and content; after the seam (or the real atomic
 *  write), common production verification runs. A seam that writes nothing, writes malformed
 *  content, writes the wrong state/identity, or installs a symlink is classified as restoration
 *  failure. Reset in `finally`. */
internal var manifestRestoreOperation: ((File, String) -> Unit)? = null

/**
 * Outcome of a manifest recovery attempt inside [cleanupBackups]. Preserves the structured
 * causes so [performTerminalCleanupDebt] can record them as warning debt.
 */
internal data class ManifestRecoveryOutcome(
    val manifestRestored: Boolean,
    val fallbackEstablished: Boolean,
    val manifestError: Throwable?,
    val fallbackError: Throwable?,
    val recoveryWarnings: List<String> = emptyList()
)

/**
 * Safe backup cleanup, shared by immediate finalization and process-restart recovery.
 *
 * - Cleanup is allowed only for a strictly validated COMMITTED or ROLLED_BACK terminal manifest.
 * - Delete known payload/temp files first; inspect both files and directories.
 * - Never delete the terminal manifest while any known or unknown content failed to delete.
 * - Delete the terminal manifest only after terminal state is durable and every other entry is gone.
 * - Delete the root only when actually empty.
 * - A successful root deletion reports `true` (null listing from a non-existent root is success).
 * - Cleanup failure leaves a valid terminal manifest and must not block the job; it may add a warning
 *   but cannot downgrade COMMITTED/ROLLED_BACK to QUARANTINED.
 * - Recovery does not delete terminal evidence after a failed payload deletion.
 * - Unresolved roots are never cleaned merely because their payload is absent.
 */
internal fun cleanupBackups(transaction: ReprocessTransaction): Boolean {
    val root = transaction.backupRoot
    if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        // Root absent — a resolved transaction with no root is fully cleaned.
        return true
    }
    if (!root.isDirectory) {
        // Root exists but is not a directory — cannot safely clean.
        return false
    }
    // Cleanup is allowed only for strictly validated terminal manifests.
    // Use the same direct-child / no-follow path contract as marker classification.
    val manifestFile = File(root, REPROCESS_TX_MANIFEST_FILE)
    if (classifyMarkerPath(manifestFile, root) !is MarkerPathClassification.Valid) return false
    val durable = try {
        loadStrictManifest(manifestFile)
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
    catch (oom: OutOfMemoryError) { throw oom }
    catch (td: ThreadDeath) { throw td }
    catch (le: LinkageError) { throw le }
    catch (ie: InternalError) { throw ie }
    catch (e: Error) { throw e }
    catch (_: Exception) {
        // Ordinary manifest/parser failure: must return false (terminal manifest preserved).
        return false
    }
    if (durable.transactionId != transaction.transactionId) return false
    if (!durable.hasSameImmutableIdentity(transaction.manifest)) return false
    val state = durable.state
    if (state != ReprocessTransactionState.COMMITTED && state != ReprocessTransactionState.ROLLED_BACK) {
        return false
    }
    val cleanupDelete = cleanupDeleteOperation
    val cleanupList = cleanupListOperation

    /** Shared post-manifest-delete evidence recovery. Attempts manifest restoration with strict
     *  verification; if that fails, calls [ensureDurableFallbackQuarantine]. Preserves structured
     *  context (trigger description, original trigger exception, manifest failure, fallback
     *  failure). Returns [ManifestRecoveryOutcome] with recovery warnings when manifest fails
     *  but fallback succeeds. Throws [CleanupEvidenceException] when recovery is completely
     *  unsuccessful so the structured causes are inspectable in production tests. */
    fun recoverManifestAndFallback(trigger: String, triggerException: Throwable? = null): ManifestRecoveryOutcome {
        var manifestError: Throwable? = null
        val manifestRestored = try {
            val content = durable.toJson().toString(2)
            val writeOp = manifestRestoreOperation
            if (writeOp != null) writeOp(manifestFile, content)
            else KeplerJobMetadata.atomicWrite(manifestFile, content)
            when (classifyMarkerPath(manifestFile, root)) {
                is MarkerPathClassification.Valid -> {
                    val restored = try { loadStrictManifest(manifestFile) }
                    catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
                    catch (oom: OutOfMemoryError) { throw oom }
                    catch (td: ThreadDeath) { throw td }
                    catch (le: LinkageError) { throw le }
                    catch (ie: InternalError) { throw ie }
                    catch (e: Error) { throw e }
                    catch (ex: Exception) { manifestError = ex; null }
                    if (restored == null) false
                    else restored.transactionId == durable.transactionId &&
                        restored.createdAt == durable.createdAt &&
                        restored.hasSameImmutableIdentity(durable) &&
                        restored.state == durable.state
                }
                else -> { manifestError = IllegalStateException("Manifest not classified as valid after write"); false }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
        catch (oom: OutOfMemoryError) { throw oom }
        catch (td: ThreadDeath) { throw td }
        catch (le: LinkageError) { throw le }
        catch (ie: InternalError) { throw ie }
        catch (e: Error) { throw e }
        catch (ex: Exception) { manifestError = ex; false }
        if (manifestRestored) return ManifestRecoveryOutcome(true, false, null, null)
        var fallbackError: Throwable? = null
        var fallbackEstablished = false
        val jobDir = root.parentFile
        if (jobDir != null) {
            try {
                ensureDurableFallbackQuarantine(jobDir, transaction)
                fallbackEstablished = true
            } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
            catch (oom: OutOfMemoryError) { throw oom }
            catch (td: ThreadDeath) { throw td }
            catch (le: LinkageError) { throw le }
            catch (ie: InternalError) { throw ie }
            catch (e: Error) { throw e }
            catch (fb: Exception) { fallbackError = fb }
        }
        val warnings = mutableListOf<String>()
        if (!manifestRestored) {
            warnings += "Manifest restoration failed after $trigger" +
                if (manifestError != null) ": ${manifestError.message}" else ""
        }
        val outcome = ManifestRecoveryOutcome(
            manifestRestored, fallbackEstablished, manifestError, fallbackError, warnings.toList()
        )
        if (!manifestRestored && !fallbackEstablished) {
            throw CleanupEvidenceException(
                "Manifest restoration and fallback both failed after $trigger",
                trigger, triggerException, manifestError, fallbackError
            )
        }
        return outcome
    }
    fun listRoot(rootDir: File): Array<File>? {
        val seam = cleanupList
        return if (seam != null) seam(rootDir) else rootDir.listFiles()
    }
    val backupNames = durable.backupEntries.values.map { it.backupName }.toSet()
    val knownNames = backupNames + setOf(REPROCESS_QUARANTINE_MARKER, REPROCESS_TX_MANIFEST_FILE)
  // 1. Delete known payload files and temp artifacts first; inspect files and directories.
  val entries = listRoot(root) ?: return false
  entries.sortedBy { it.isDirectory }.forEach { entry ->
    if (!entry.isDirectory) {
      if (entry.name == REPROCESS_TX_MANIFEST_FILE) return@forEach
      if (entry.name in backupNames || entry.name.endsWith(".tmp") || entry.name.endsWith(".restore")) {
        try {
          cleanupDelete(entry)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: OutOfMemoryError) { throw e
        } catch (e: ThreadDeath) { throw e
        } catch (e: LinkageError) { throw e
        } catch (e: InternalError) { throw e
        } catch (e: Error) { throw e
        } catch (_: Exception) {
          return false
        }
        if (entry.exists()) return false
      }
    } else {
      // No transaction format owns directories inside the backup root. Preserve terminal
      // evidence rather than deleting even an empty unknown directory.
      return false
    }
  }
  // 2. Verify no unknown files or directories remain.
  val afterPayloadDelete = listRoot(root) ?: return false
  val unknownContents = afterPayloadDelete.filter { it.name !in knownNames }
  if (unknownContents.isNotEmpty()) {
    return false
  }
  // 3. Remove quarantine marker using fail-closed path classification.
  //    Absent → continue. Valid → delete, require post-delete Absent.
  //    Symlink, NotRegularFile, NotDirectChild, InspectionError → return false (fail closed).
  val markerFile = File(root, REPROCESS_QUARANTINE_MARKER)
  when (classifyMarkerPath(markerFile, root)) {
    is MarkerPathClassification.Valid -> {
      try {
        cleanupDelete(markerFile)
      } catch (e: kotlinx.coroutines.CancellationException) { throw e
      } catch (e: OutOfMemoryError) { throw e
      } catch (e: ThreadDeath) { throw e
      } catch (e: LinkageError) { throw e
      } catch (e: InternalError) { throw e
      } catch (e: Error) { throw e
      } catch (_: Exception) {
        return false
      }
      if (classifyMarkerPath(markerFile, root) !is MarkerPathClassification.Absent) {
        return false
      }
    }
    is MarkerPathClassification.Absent -> { /* no marker to remove */ }
    is MarkerPathClassification.Symlink,
    is MarkerPathClassification.NotRegularFile,
    is MarkerPathClassification.NotDirectChild,
    is MarkerPathClassification.InspectionError -> return false
  }
  // 4. Verify only the terminal manifest remains before deleting it as the final file operation.
  val preManifestDelete = listRoot(root) ?: return false
  val preRemaining = preManifestDelete.filter { it.name != REPROCESS_TX_MANIFEST_FILE }
  if (preRemaining.isNotEmpty()) return false
  // 5. Delete the terminal manifest as the final file operation. After this, the root should be empty.
  try {
    cleanupDelete(manifestFile)
  } catch (e: kotlinx.coroutines.CancellationException) { throw e
  } catch (e: OutOfMemoryError) { throw e
  } catch (e: ThreadDeath) { throw e
  } catch (e: LinkageError) { throw e
  } catch (e: InternalError) { throw e
  } catch (e: Error) { throw e
  } catch (ex: Exception) {
    recoverManifestAndFallback("manifest deletion threw exception", ex)
    return false
  }
  when (val postDelete = classifyMarkerPath(manifestFile, root)) {
    is MarkerPathClassification.Absent -> { /* manifest successfully removed */ }
    is MarkerPathClassification.Symlink -> {
      recoverManifestAndFallback("symlink after manifest delete", null)
      return false
    }
    else -> {
      recoverManifestAndFallback("unexpected file after manifest delete: ${postDelete.javaClass.simpleName}", null)
      return false
    }
  }
  // Seam: simulate a new file appearing after manifest deletion.
  afterManifestDeleteOperation?.invoke(root)
  // 6. Check root existence and re-list. After manifest deletion, the root may be gone.
  //  If root still exists but listing returns null, that is an IO failure — fail closed.
  //  A new/unknown file that appeared after manifest deletion is blocking.
  if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return true // root already removed
  val afterManifestDelete = listRoot(root) ?: run {
    // Root still exists but listing returned null — IO failure, fail closed.
    recoverManifestAndFallback("null listing after manifest delete")
    return false
  }
  if (afterManifestDelete.isNotEmpty()) {
    // An unknown file appeared after manifest deletion. Restore terminal evidence.
    // Never recursively remove a file that appeared after the final listing.
    recoverManifestAndFallback("unknown content after manifest delete")
    return false
  }
  // 7. Delete the now-empty root using non-recursive File.delete(). If root deletion fails,
  //    restore the terminal manifest and verify it matches the durable identity.
  try {
    val rootDeleted = cleanupRootDeleteOperation(root)
    if (!rootDeleted) {
      recoverManifestAndFallback("root deletion returned false")
      return false
    }
  } catch (ce: kotlinx.coroutines.CancellationException) { throw ce }
  catch (oom: OutOfMemoryError) { throw oom }
  catch (td: ThreadDeath) { throw td }
  catch (le: LinkageError) { throw le }
  catch (ie: InternalError) { throw ie }
  catch (e: Error) { throw e }
  catch (_: Exception) {
    // Root deletion threw: restore and verify terminal manifest.
    recoverManifestAndFallback("root deletion threw exception")
    return false
  }
  // Verify root absence. If root persists after successful delete, restore evidence.
  if (Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
    recoverManifestAndFallback("root still exists after successful delete")
    return false
  }
  return true
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
    val debugAvailable = listFilesNoFollow(jobDir).any { file ->
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
    job: JSONObject,
    frames: List<KeplerFrameReviewItem>,
    explicitSelection: Set<Int>?
): Result<Set<Int>> = runCatching {
    if (explicitSelection != null) {
        return@runCatching explicitSelection
            .filter { index -> frames.any { it.index == index && it.file.isFile && it.file.length() > 0L } }
            .toSet()
    }

    val persisted = persistedIncludedFrameIndices(job)
        .filter { index -> frames.any { it.index == index && it.file.isFile && it.file.length() > 0L } }
        .toSet()
    if (persisted.isNotEmpty()) return@runCatching persisted

    val recommendation = RuleBasedFrameSelectionAdvisor().recommend(null, frames)
    val recommended = recommendation.includedFrameIndices
        .filter { index -> frames.any { it.index == index && it.file.isFile && it.file.length() > 0L } }
        .toSet()
    if (recommended.size >= requiredSelectedFrameCount(kind, job)) {
        recommended
    } else {
        frames.filter { it.file.isFile && it.file.length() > 0L }
            .sortedByDescending { it.quality?.overallScore ?: 0.5f }
            .take(requiredSelectedFrameCount(kind, job))
            .map { it.index }
            .toSet()
    }
}

private fun resolveSelectionMode(job: JSONObject, explicitSelection: Set<Int>?): FrameSelectionMode {
    return persistedFrameSelectionMode(job)
        ?: if (explicitSelection != null) FrameSelectionMode.MANUAL else FrameSelectionMode.AUTO_RULE_BASED
}

private fun requiredSelectedFrameCount(kind: ReprocessJobKind, job: JSONObject): Int = when (kind) {
    ReprocessJobKind.RAW_FUSION -> MIN_RAW_FUSION_FRAMES
    ReprocessJobKind.YUV_FUSION -> if (isSingleFrameJob(job)) 1 else 2
    ReprocessJobKind.COLOR_BURST,
    ReprocessJobKind.UNSUPPORTED -> Int.MAX_VALUE
}
