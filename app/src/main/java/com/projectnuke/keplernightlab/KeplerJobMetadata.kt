package com.projectnuke.keplernightlab

import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

private const val KEPLER_JOB_SCHEMA_VERSION = 1

/** Thrown when job metadata is missing or unreadable. */
sealed class KeplerJobMetadataException(message: String, cause: Throwable? = null) : Exception(message, cause)
class KeplerJobMetadataMissing(jobDir: File) : KeplerJobMetadataException("Job metadata missing in ${jobDir.absolutePath}")
class KeplerJobMetadataCorrupt(jobDir: File, cause: Throwable? = null) : KeplerJobMetadataException("Job metadata corrupt in ${jobDir.absolutePath}", cause)
internal class ProcessingCleanupRequiredException : IllegalStateException(
    "?�전 처리 ?�업???�일 ?�리가 ?�료?��? ?�아 지금�? ?�시 ?�성?????�습?�다."
)
internal const val PROCESSING_CLEANUP_REQUIRED = "PROCESSING_CLEANUP_REQUIRED"

internal enum class JobRecoveryMutationIntent {
    PROCESSING_START,
    REPROCESS,
    FRAME_SELECTION,
    METADATA_EDIT,
    JOB_CLEANUP,
    JOB_DELETE
}

/**
 * Explicit local destructive mutations operate on Kepler-owned job storage only.
 * They may proceed past public-export history debt because the public MediaStore
 * row is never touched by them; they must still respect every LIVE ownership and
 * evidence-uncertainty block (dead operation, handoff, quarantine, invalid or
 * ambiguous journals, processing cleanup debt, pre-commit export uncertainty).
 */
private val DESTRUCTIVE_LOCAL_STORAGE_INTENTS = setOf(
    JobRecoveryMutationIntent.JOB_DELETE,
    JobRecoveryMutationIntent.JOB_CLEANUP
)

/** Public-export HISTORY debts that must not block explicit local job deletion/cleanup. */
private val PUBLIC_EXPORT_HISTORY_DEBT_OUTCOMES = setOf(
    JobRecoveryMutationGateOutcome.BLOCKED_PUBLIC_COMMIT_MISSING,
    JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION
)

/**
 * Process-local retry input for a PUBLIC_EXPORT terminal settlement which
 * could not complete before its owning worker scope returned.  The durable
 * journals and job metadata remain the authority; this record only preserves
 * the exact operation/disposition needed to retry that ordered protocol while
 * the original lease is still registered.
 */
internal data class PendingPublicExportSettlement(
    val operationId: String,
    val failureMessage: String,
    val finalOutputFormat: FinalOutputFormat?,
    val disposition: PublicExportInterruptionDisposition
)

internal data class PendingTerminalSettlement(
    val operationId: String,
    val attemptStatus: String,
    val pipelineStage: String,
    val processStatus: String,
    val reason: String
)

internal enum class JobRecoveryMutationGateOutcome {
    ALLOWED,
    BLOCKED_DEAD_OPERATION,
    BLOCKED_HANDOFF,
    BLOCKED_ORPHANED_JOB_METADATA,
    BLOCKED_PROCESSING_CLEANUP,
    BLOCKED_AMBIGUOUS_RECOVERY,
    BLOCKED_PUBLIC_COMMIT_MISSING,
    BLOCKED_EXPORT_VERIFICATION,
    BLOCKED_EXPORT_SETTLEMENT,
    BLOCKED_INVALID_PROCESSING_JOURNAL,
    BLOCKED_INVALID_EXPORT_JOURNAL,
    BLOCKED_SETTLED_JOURNAL,
    BLOCKED_REPROCESS_QUARANTINE,
    INSPECTION_FAILED
}

internal class JobRecoveryMutationBlockedException(
    val outcome: JobRecoveryMutationGateOutcome
) : IllegalStateException(
    when (outcome) {
        JobRecoveryMutationGateOutcome.BLOCKED_DEAD_OPERATION -> "?�전 ?�행???�업 ?�유권이 ?�직 복구?��? ?�아 지금�? ?�업??변경할 ???�습?�다."
        JobRecoveryMutationGateOutcome.BLOCKED_HANDOFF -> "촬영 결과??처리 ?�계가 ?�직 ?�료?��? ?�아 지금�? ?�업??변경할 ???�습?�다."
        JobRecoveryMutationGateOutcome.BLOCKED_ORPHANED_JOB_METADATA -> "?�업 메�??�이?��? ?�어 복구 ?�인 ?�에???�업??변경할 ???�습?�다."
        JobRecoveryMutationGateOutcome.BLOCKED_PROCESSING_CLEANUP -> "?�전 처리 ?�업???�일 ?�리가 ?�료?��? ?�아 지금�? ?�업??변경할 ???�습?�다."
        JobRecoveryMutationGateOutcome.BLOCKED_AMBIGUOUS_RECOVERY -> "복구?��? ?��? ?�업 증거가 ?�어 지금�? ?�업??변경할 ???�습?�다."
        JobRecoveryMutationGateOutcome.BLOCKED_PUBLIC_COMMIT_MISSING -> "공개 결과??커밋 증거가 ?�어 지금�? ?�업??변경할 ???�습?�다."
        JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION -> "공개 결과�??�인?��? 못해 지금�? ?�업??변경할 ???�습?�다."
        JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_SETTLEMENT -> "공개 ?�보?�기???�업 ?�리 ?�인???�나지 ?�아 지금�? ?�업??변경할 ???�습?�다."
        JobRecoveryMutationGateOutcome.BLOCKED_INVALID_PROCESSING_JOURNAL -> "처리 복구 기록???�을 ???�어 지금�? ?�업??변경할 ???�습?�다."
        JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE -> "복구 중인 ?�업?� 지�?변경할 ???�습?�다."
        JobRecoveryMutationGateOutcome.INSPECTION_FAILED -> "?�업 복구 ?�태�??�인?��? 못해 지금�? ?�업??변경할 ???�습?�다."
        JobRecoveryMutationGateOutcome.BLOCKED_INVALID_EXPORT_JOURNAL -> "?�보?�기 복구 기록???�을 ???�어 지금�? ?�업??변경할 ???�습?�다."
        JobRecoveryMutationGateOutcome.BLOCKED_SETTLED_JOURNAL -> "?�료??처리 기록???�리가 ?�나지 ?�아 지금�? 결과 경로�?변경할 ???�습?�다."
        JobRecoveryMutationGateOutcome.ALLOWED -> ""
    }
)

/** Serializes each job's read-modify-write updates and never truncates a valid job.json. */
object KeplerJobMetadata {
    // Strongly reachable striped locks keep one stable lock choice per job
    // without a GC-sensitive weak table or an unbounded lock-map leak.
    private val _locks = Array(64) { Any() }
    private val operationLeases = ConcurrentHashMap<String, JobOperationLease>()
    private val autoOperationLeases = ConcurrentHashMap.newKeySet<String>()

    /** Narrow lease/metadata test seam: incremented each time a job metadata write is durably
     *  persisted. Tests must save the prior value and restore it in `finally`. Production never reads it. */
    @Volatile
    internal var atomicWriteCount: Int = 0
        private set

    @Volatile
    internal var atomicWriteFailureForTest: Throwable? = null

    /** Optional ordered test failures; null entries allow a write to pass. */
    internal var atomicWriteFailureSequenceForTest: MutableList<Throwable?>? = null

    /** Narrow lease/metadata test seam: incremented each time a lease is actually released (the
     *  idempotent guard has NOT skipped the release). Tests must save & restore in `finally`. */
    @Volatile
    public var leaseReleaseCount: Int = 0
        internal set

    /** Narrow test-only seam to reset [atomicWriteCount]. Tests must save/restore prior value. */
    public fun setAtomicWriteCountForTest(value: Int) { atomicWriteCount = value }
    /** Narrow test-only seam: inject an ordinary Exception on the first handoff inspection
     *  within [settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure]. Cleared after use. */
    public var settleInitialReadFailureForTest: Throwable? = null
        internal set

    /** Narrow test-only seam: inject an ordinary Exception during the PROCESSING_START
     *  settlement-owner acquisition within [settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure].
     *  Cleared after use. */
    public var settleRecoveryCheckFailureForTest: Throwable? = null
        internal set

    /** Narrow test-only seam: inject an ordinary Exception on the post-authority handoff
     *  reinspection within [settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure].
     *  Cleared after use. */
    public var settlePostAuthorityReadFailureForTest: Throwable? = null
        internal set

    /** Narrow test-only seam to reset [leaseReleaseCount]. Tests must save/restore prior value. */
    public fun setLeaseReleaseCountForTest(value: Int) { leaseReleaseCount = value }

    public fun getLeaseReleaseCountForTest(): Int = leaseReleaseCount

    /** Narrow test-only seam: inject an ordinary Exception on the post-handoff ACTIVE-state read
     *  within [reconcilePendingDurableSettlement]. Cleared after use. */
    public var reconcilePostHandoffReadFailureForTest: Throwable? = null
        internal set

    private fun lockFor(jobDir: File): Any = _locks[
        (jobDir.toPath().toAbsolutePath().normalize().toString().hashCode() and Int.MAX_VALUE) % _locks.size
    ]

    internal fun <T> withJobLock(jobDir: File, block: () -> T): T =
        synchronized(lockFor(jobDir), block)

    internal fun inspectRecoveryMutationGate(
        jobDir: File,
        intent: JobRecoveryMutationIntent,
        consumesProcessingHandoff: Boolean = false,
        ownerLease: JobOperationLease? = null
    ): JobRecoveryMutationGateOutcome = withJobLock(jobDir) {
        try {
            val key = jobDir.toPath().toAbsolutePath().normalize().toString()
            val exactCurrentOwner = ownerLease != null && operationLeases[key] === ownerLease
            val ownedActiveReprocess = exactCurrentOwner && ownerLease != null && isExactOwnedActiveReprocessTransaction(jobDir, ownerLease)
            if (isReprocessQuarantined(jobDir) && !ownedActiveReprocess) return@withJobLock JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE
            val processingScan = ProcessingArtifactJournal.scan(jobDir)
            if (processingScan.invalidFiles.isNotEmpty()) return@withJobLock JobRecoveryMutationGateOutcome.BLOCKED_INVALID_PROCESSING_JOURNAL
            val children = NoFollowFileSystem.requireDirectChildren(jobDir)
            val job = try {
                read(jobDir)
            } catch (_: KeplerJobMetadataMissing) {
                val empty = children.isEmpty()
                return@withJobLock if (intent == JobRecoveryMutationIntent.PROCESSING_START && empty) {
                    JobRecoveryMutationGateOutcome.ALLOWED
                } else {
                    JobRecoveryMutationGateOutcome.BLOCKED_ORPHANED_JOB_METADATA
                }
            }
            val activeId = job.optString(ACTIVE_OPERATION_ID)
            val activeRuntime = job.optString(ACTIVE_RUNTIME_SESSION_ID)
            val handoffId = job.optString(PROCESSING_HANDOFF_OPERATION_ID)
            val handoffRuntime = job.optString(PROCESSING_HANDOFF_RUNTIME_SESSION_ID)
            if (activeId.isNotBlank() && exactCurrentOwner) {
                // The exact current owner may reassert its durable phase.
            } else if (activeId.isNotBlank()) {
                val handoffConsumerAllowed = intent == JobRecoveryMutationIntent.PROCESSING_START &&
                    consumesProcessingHandoff &&
                    handoffId.isNotBlank() &&
                    handoffRuntime == KeplerRuntimeSession.id &&
                    activeRuntime == KeplerRuntimeSession.id &&
                    job.optString(ACTIVE_OPERATION_KIND) in setOf(
                        KeplerActiveOperationKind.CAPTURE_YUV.name,
                        KeplerActiveOperationKind.CAPTURE_RAW.name
                    )
                if (!handoffConsumerAllowed) return@withJobLock JobRecoveryMutationGateOutcome.BLOCKED_DEAD_OPERATION
            } else if (handoffId.isNotBlank()) {
                if (!(intent == JobRecoveryMutationIntent.PROCESSING_START &&
                        consumesProcessingHandoff && handoffRuntime == KeplerRuntimeSession.id)) {
                    return@withJobLock JobRecoveryMutationGateOutcome.BLOCKED_HANDOFF
                }
            }
            val exportInvalid = MediaStoreExportJournal.invalidFiles(jobDir)
            val terminalResultProven = activeId.isBlank() &&
                job.optString("currentPipelineStage") in setOf("COMPLETE", "PARTIAL", "FAILED", "CANCELLED") &&
                job.optBoolean("galleryExportCommitted", false) &&
                job.optBoolean("exportVerified", false) &&
                job.optString("exportUri").isNotBlank() &&
                job.optString("recoveryState").ifBlank { "STABLE" } == "STABLE"
            val nonDestructiveHistoricalExportInspection = intent in setOf(
                JobRecoveryMutationIntent.PROCESSING_START,
                JobRecoveryMutationIntent.REPROCESS,
                JobRecoveryMutationIntent.FRAME_SELECTION,
                JobRecoveryMutationIntent.METADATA_EDIT
            )
            if (exportInvalid.isNotEmpty() &&
                !(terminalResultProven && nonDestructiveHistoricalExportInspection)
            ) {
                return@withJobLock JobRecoveryMutationGateOutcome.BLOCKED_INVALID_EXPORT_JOURNAL
            }
            val settledAuthoritative = processingScan.validJournals.any {
                it.second.state == ProcessingArtifactJournalState.SETTLED &&
                    ((it.second.claimKey != null && it.second.processingAttemptId != null) ||
                        it.second.adoptedResult == "NO_OUTPUT")
            }
            if (settledAuthoritative && !reconcileSettledAuthoritativeProcessingJournals(jobDir, job)) {
                return@withJobLock JobRecoveryMutationGateOutcome.BLOCKED_SETTLED_JOURNAL
            }
            val exportBlocks = MediaStoreExportJournal.list(jobDir).any { it.isGateBlocking() }
            if (exportBlocks) return@withJobLock exportDebtGateOutcome(job)
            // Terminal settlement debt: a VERIFIED journal whose terminal ACK was never persisted
            // blocks mutations ONLY when that journal is owned by the durably recorded terminal
            // operation (a finalized but unacknowledged terminal).  Converged-verified debt without
            // operation correlation (e.g. an UNKNOWN record the shared engine settled) is not
            // settlement debt.
            val terminalOperationId = job.optString(TERMINAL_OPERATION_ID)
            if (terminalOperationId.isNotBlank() &&
                MediaStoreExportJournal.list(jobDir).any {
                    it.state == MediaStoreExportState.VERIFIED &&
                        !it.isTerminallyStable() &&
                        it.ownerOperationId == terminalOperationId
                }
            ) {
                return@withJobLock JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_SETTLEMENT
            }
            when (job.optString("recoveryState")) {
                PROCESSING_CLEANUP_REQUIRED -> JobRecoveryMutationGateOutcome.BLOCKED_PROCESSING_CLEANUP
                "AMBIGUOUS_RECOVERY_REQUIRED" -> JobRecoveryMutationGateOutcome.BLOCKED_AMBIGUOUS_RECOVERY
                "PUBLIC_COMMIT_MISSING" -> JobRecoveryMutationGateOutcome.BLOCKED_PUBLIC_COMMIT_MISSING
                "PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION" -> JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION
                else -> JobRecoveryMutationGateOutcome.ALLOWED
            }.let { outcome ->
                // Local destructive storage actions never require the public Gallery row to
                // exist: public-export history debt alone must not block them. Live ownership
                // and evidence-uncertainty blocks above are preserved for these intents.
                if (intent in DESTRUCTIVE_LOCAL_STORAGE_INTENTS &&
                    outcome in PUBLIC_EXPORT_HISTORY_DEBT_OUTCOMES
                ) {
                    JobRecoveryMutationGateOutcome.ALLOWED
                } else {
                    outcome
                }
            }
        } catch (_: Exception) {
            JobRecoveryMutationGateOutcome.INSPECTION_FAILED
        }
    }

    internal fun requireRecoveryMutationAllowed(
        jobDir: File,
        intent: JobRecoveryMutationIntent,
        consumesProcessingHandoff: Boolean = false
    ) {
        val outcome = inspectRecoveryMutationGate(jobDir, intent, consumesProcessingHandoff)
        if (outcome == JobRecoveryMutationGateOutcome.BLOCKED_PROCESSING_CLEANUP) {
            throw ProcessingCleanupRequiredException()
        }
        if (outcome != JobRecoveryMutationGateOutcome.ALLOWED) throw JobRecoveryMutationBlockedException(outcome)
    }

    /**
     * Maps export-debt blocking evidence to the actual durable reason instead of defaulting to
     * DEAD_OPERATION. A committed-unverified or UNKNOWN record is verification debt (the gate maps
     * the durable policy); a terminal-recorded job whose journal ACK is still pending is
     * settlement debt; genuine dead-owner journal debt without a recorded policy stays
     * DEAD_OPERATION.
     */
    private fun exportDebtGateOutcome(job: JSONObject): JobRecoveryMutationGateOutcome = when {
        job.optString("recoveryState") == "PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION" ->
            JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION
        job.optString("exportCommitState") == GalleryExportCommitState.UNKNOWN.name ->
            JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION
        job.optString("exportCommitState") == GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name ->
            JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION
        job.optString("recoveryState") == "AMBIGUOUS_RECOVERY_REQUIRED" ->
            JobRecoveryMutationGateOutcome.BLOCKED_AMBIGUOUS_RECOVERY
        job.optString("recoveryState") == "PUBLIC_COMMIT_MISSING" ->
            JobRecoveryMutationGateOutcome.BLOCKED_PUBLIC_COMMIT_MISSING
        job.optString("recoveryState") == PROCESSING_CLEANUP_REQUIRED ->
            JobRecoveryMutationGateOutcome.BLOCKED_PROCESSING_CLEANUP
        job.optString("recoveryState").ifBlank { "STABLE" } == "STABLE" &&
            job.optString(TERMINAL_OPERATION_ID).isNotBlank() ->
            JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_SETTLEMENT
        else -> JobRecoveryMutationGateOutcome.BLOCKED_DEAD_OPERATION
    }

    fun acquireOperation(jobDir: File): JobOperationLease? {
        return withJobLock(jobDir) {
            val key = jobDir.toPath().toAbsolutePath().normalize().toString()
            val existing = operationLeases[key]
            if (existing != null) {
                if (!reconcilePendingDurableSettlement(jobDir, existing)) return@withJobLock null
            }
            val lease = JobOperationLease(key)
            if (operationLeases.putIfAbsent(key, lease) == null) lease else null
        }
    }

    /** Checks durable recovery authority and reserves the process-local owner under one lock. */
    internal fun acquireRecoveryCheckedOperation(
        jobDir: File,
        intent: JobRecoveryMutationIntent,
        consumesProcessingHandoff: Boolean = false
    ): JobOperationLease = withJobLock(jobDir) {
        val key = jobDir.toPath().toAbsolutePath().normalize().toString()
        val existing = operationLeases[key]
        if (existing != null && !reconcilePendingDurableSettlement(jobDir, existing)) {
            throw ProcessingAlreadyActiveException(jobDir)
        }
        val outcome = inspectRecoveryMutationGate(
            jobDir,
            intent,
            consumesProcessingHandoff = consumesProcessingHandoff
        )
        if (outcome == JobRecoveryMutationGateOutcome.BLOCKED_PROCESSING_CLEANUP) {
            throw ProcessingCleanupRequiredException()
        }
        if (outcome != JobRecoveryMutationGateOutcome.ALLOWED) {
            throw JobRecoveryMutationBlockedException(outcome)
        }
        val lease = JobOperationLease(key)
        if (operationLeases.putIfAbsent(key, lease) != null) {
            throw ProcessingAlreadyActiveException(jobDir)
        }
        lease
    }

    /**
     * Reserves the single process-local recovery-authority slot for [jobDir] when no other lease
     * is currently held.  Used by the same-process debt coordinator (CASE B) so the
     * provider/journal/metadata convergence can never race a concurrent mutation acquisition.
     * Returns null while any lease is held: that live/retained owner performs its own settlement,
     * and the coordinator refuses to run as a second authority.  Released via [releaseOperation].
     */
    internal fun acquireTemporaryRecoveryAuthority(jobDir: File): JobOperationLease? =
        withJobLock(jobDir) {
            val key = jobDir.toPath().toAbsolutePath().normalize().toString()
            if (operationLeases.containsKey(key)) return@withJobLock null
            val lease = JobOperationLease(key)
            if (operationLeases.putIfAbsent(key, lease) == null) lease else null
        }

    /** Correlation + removal shared by [consumeProcessingHandoff] and handoff-consuming
 *  acquisitions, so every consumption path enforces the exact same authority:
 *  current runtime session, exact job metadata, and exact handoff kind. */
private fun correlateAndRemoveProcessingHandoff(job: JSONObject, kind: KeplerActiveOperationKind): Boolean {
    if (job.optString(PROCESSING_HANDOFF_RUNTIME_SESSION_ID) != KeplerRuntimeSession.id ||
        job.optString(PROCESSING_HANDOFF_KIND) != kind.name
    ) return false
    job.remove(PROCESSING_HANDOFF_RUNTIME_SESSION_ID)
    job.remove(PROCESSING_HANDOFF_OPERATION_ID)
    job.remove(PROCESSING_HANDOFF_KIND)
    job.remove(PROCESSING_HANDOFF_CREATED_AT)
    return true
}

/**
 * Atomically clears the current-runtime processing handoff for [kind] on [jobDir].
 * Idempotent: returns false when no correlated handoff was present, so the same
 * worker can re-assert consumption before a terminal publish without side effects.
 */
internal fun consumeProcessingHandoff(
    jobDir: File,
    kind: KeplerActiveOperationKind
): Boolean = try {
    var matched = false
    update(jobDir) { job ->
        matched = correlateAndRemoveProcessingHandoff(job, kind)
    }
    matched
} catch (failure: Error) {
    throw failure
} catch (_: Exception) {
    false
}

/** Whether a processing handoff is absent, exactly correlated to [kind], or present-but-unrelated. */
internal enum class ProcessingHandoffPresence { ABSENT, CORRELATED, UNRELATED }

/**
 * Inspects the processing handoff on [jobDir] for [kind] WITHOUT consuming it, so callers can
 * distinguish "absent" (idempotent success) from "present but not consumable" (must not proceed
 * to a success terminal).  Fail-closed: an unreadable job reports [ProcessingHandoffPresence.UNRELATED].
 */
internal fun inspectProcessingHandoff(
    jobDir: File,
    kind: KeplerActiveOperationKind
): ProcessingHandoffPresence = try {
    val job = read(jobDir)
    when {
        job.optString(PROCESSING_HANDOFF_OPERATION_ID).isBlank() -> ProcessingHandoffPresence.ABSENT
        job.optString(PROCESSING_HANDOFF_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id &&
            job.optString(PROCESSING_HANDOFF_KIND) == kind.name -> ProcessingHandoffPresence.CORRELATED
        else -> ProcessingHandoffPresence.UNRELATED
    }
} catch (failure: Error) {
    throw failure
} catch (_: Exception) {
    ProcessingHandoffPresence.UNRELATED
}

/**
 * Retries a terminal owner clear recorded by an operation scope that has already returned.
     * The retained lease is still the only process-local authority, so a successful retry can
     * release it immediately; a failed retry leaves the exact owner protected for the next
     * production mutation/recovery entry.
     */
    private fun reconcilePendingDurableSettlement(
        jobDir: File,
        lease: JobOperationLease,
        access: MediaStoreExportRecoveryAccess? = null
    ): Boolean {
        if (!lease.hasPendingReconciliationDebt()) return false
        if (!lease.isReconciliationReady()) return false
        val pendingTerminal = lease.pendingTerminalSettlement()
        if (pendingTerminal != null) {
            val persisted = try {
                recordNormalPreCommitTerminal(
                    jobDir = jobDir,
                    attemptStatus = pendingTerminal.attemptStatus,
                    pipelineStage = pendingTerminal.pipelineStage,
                    processStatus = pendingTerminal.processStatus,
                    reason = pendingTerminal.reason,
                    operationId = pendingTerminal.operationId,
                    operationLease = lease
                )
                true
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                false
            }
            if (!persisted) return false
            lease.completeTerminalSettlement(pendingTerminal.operationId)
            val activeOperationId = lease.currentDurableOperationId() ?: pendingTerminal.operationId
            val cleared = clearActiveOperation(jobDir, activeOperationId, lease)
            if (!cleared && isCurrentActiveOperation(jobDir, activeOperationId)) return false
            lease.clearDurableOperation(activeOperationId)
            lease.completeDurableSettlement(pendingTerminal.operationId)
        }
        val pendingPublicExport = lease.pendingPublicExportSettlement()
        if (pendingPublicExport != null) {
            val settled = try {
                settleOwnedPublicExportInterruption(
                    jobDir = jobDir,
                    ownerLease = lease,
                    failureMessage = pendingPublicExport.failureMessage,
                    finalOutputFormat = pendingPublicExport.finalOutputFormat,
                    disposition = pendingPublicExport.disposition,
                    access = access
                )
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                false
            }
            if (!settled) return false
            lease.completePublicExportSettlement(pendingPublicExport.operationId)
        }
        if (lease.hasPendingProcessingHandoffSettlement()) {
            val handoffPresent = try {
                read(jobDir).optString(PROCESSING_HANDOFF_OPERATION_ID).isNotBlank()
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                return false
            }
            if (handoffPresent) {
                val settled = try {
                    finalizeRecoveredProcessingHandoff(jobDir, lease)
                } catch (failure: Error) {
                    throw failure
                } catch (_: Exception) {
                    false
                }
                if (!settled) return false
            }
            val current = try {
                reconcilePostHandoffReadFailureForTest?.let { throw it }
                read(jobDir)
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                return false
            }
            val activeId = current.optString(ACTIVE_OPERATION_ID)
            if (activeId.isNotBlank()) {
                lease.markDurableSettlementPending(activeId)
            }
            lease.completeProcessingHandoffSettlement()
        }
        val pendingId = lease.pendingDurableSettlementId() ?: return lease.releaseIfProcessingSettled()
        val job = try {
            read(jobDir)
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            return false
        }
        val activeId = job.optString(ACTIVE_OPERATION_ID)
        val activeRuntime = job.optString(ACTIVE_RUNTIME_SESSION_ID)
        if (activeId.isBlank()) {
            lease.completeDurableSettlement(pendingId)
        } else if (activeId == pendingId && activeRuntime == KeplerRuntimeSession.id) {
            val cleared = clearActiveOperation(jobDir, pendingId, lease)
            if (!cleared && isCurrentActiveOperation(jobDir, pendingId)) return false
            lease.completeDurableSettlement(pendingId)
        } else {
            return false
        }
        return lease.releaseIfProcessingSettled()
    }

    internal fun hasProcessingCleanupBlocker(jobDir: File): Boolean = try {
        val job = read(jobDir)
        job.optString("recoveryState") == PROCESSING_CLEANUP_REQUIRED ||
            ProcessingArtifactJournal.scan(jobDir).let { scan ->
                scan.invalidFiles.isNotEmpty() || scan.validJournals.any {
                    isUnresolvedAuthoritativeProcessingJournal(it.second) ||
                        (it.second.state == ProcessingArtifactJournalState.SETTLED && it.second.adoptedResult == "NO_OUTPUT")
                }
            }
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        false
    }

    internal fun recordProcessingCleanupRequired(
        jobDir: File,
        operationId: String?,
        failures: List<String>,
        historicalClassification: String = "LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL"
    ): Boolean {
        var matched = false
        update(jobDir) { job ->
            if (operationId != null &&
                (job.optString(ACTIVE_OPERATION_ID) != operationId ||
                    job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id ||
                    job.optString(ACTIVE_OPERATION_KIND) == KeplerActiveOperationKind.PUBLIC_EXPORT.name)
            ) return@update
            matched = true
            job.put("recoveryState", PROCESSING_CLEANUP_REQUIRED)
                .put("processingCleanupDebt", JSONArray(failures.distinct()))
                .put("lastRecoveryClassification", historicalClassification)
                .put("lastRecoveryMessage", "처리 결과??보존?�었지�??�전 ?�업???�일 ?�리가 ?�직 ?�료?��? ?�았?�니??")
                .put("recoveredAt", System.currentTimeMillis())
                .put("recoveryMessage", "?�전 처리 ?�업???�일 ?�리가 ?�료?��? ?�아 지금�? ?�시 ?�성?????�습?�다.")
            if (operationId != null) {
                job.remove(ACTIVE_RUNTIME_SESSION_ID)
                job.remove(ACTIVE_OPERATION_ID)
                job.remove(ACTIVE_OPERATION_KIND)
                job.remove(ACTIVE_OPERATION_STARTED_AT)
                job.remove(ACTIVE_OPERATION_UPDATED_AT)
            }
        }
        return matched
    }

    internal fun clearProcessingCleanupRequired(jobDir: File): Boolean {
        var changed = false
        update(jobDir) { job ->
            if (job.optString("recoveryState") != PROCESSING_CLEANUP_REQUIRED) return@update
            changed = true
            job.put("recoveryState", "STABLE")
                .put("recoveredAt", System.currentTimeMillis())
            job.remove("processingCleanupDebt")
            job.remove("recoveryMessage")
        }
        return changed
    }

    fun isOperationActive(jobDir: File): Boolean = operationLeases.containsKey(
        jobDir.toPath().toAbsolutePath().normalize().toString()
    )

    /** True if the given lease is the actual job operation owner. Public for production checks. */
    fun isOperationOwner(jobDir: File, lease: JobOperationLease): Boolean =
        operationLeases[jobDir.toPath().toAbsolutePath().normalize().toString()] === lease

    fun findOperationLease(jobDir: File): JobOperationLease? =
        operationLeases[jobDir.toPath().toAbsolutePath().normalize().toString()]

    internal fun releaseOperation(lease: JobOperationLease) {
        autoOperationLeases.remove(lease.key)
        operationLeases.remove(lease.key, lease)
    }

    private fun releaseAutoOperation(jobDir: File) {
        val key = jobDir.toPath().toAbsolutePath().normalize().toString()
        if (autoOperationLeases.remove(key)) operationLeases.remove(key)
    }

    /** Removes the lock entry for a permanently deleted job directory. Safe to call after successful deletion. */
    fun removeLockEntry(jobDir: File) {
        // Stripes are process-owned and intentionally retained.
    }

    /** Reads job metadata. Throws [KeplerJobMetadataMissing] if the file does not exist, [KeplerJobMetadataCorrupt] if parse fails. */
    fun read(jobDir: File): JSONObject = synchronized(lockFor(jobDir)) {
        requireRealJobDirectory(jobDir)
        val file = when (val result = NoFollowFileSystem.resolveDirectChildResult(
            jobDir, JOB_JSON_FILE_NAME, requireFile = true
        )) {
            NoFollowInspection.Absent -> throw KeplerJobMetadataMissing(jobDir)
            is NoFollowInspection.InspectionFailed -> throw KeplerJobMetadataCorrupt(jobDir, result.exception)
            is NoFollowInspection.Present -> result.value
        }
        try {
            JSONObject(NoFollowFileSystem.readTextVerified(file))
        } catch (parseFailure: JSONException) {
            throw KeplerJobMetadataCorrupt(jobDir, parseFailure)
        } catch (ioFailure: Exception) {
            throw KeplerJobMetadataCorrupt(jobDir, ioFailure)
        }
    }

    /**
     * Full replacement write. Use only for initial creation or intentional full replacement
     * of the entire metadata object. For partial updates use [update].
     */
    fun write(jobDir: File, job: JSONObject): JSONObject = synchronized(lockFor(jobDir)) {
        val replacement = JSONObject(job.toString())
        replacement.put("schemaVersion", replacement.optInt("schemaVersion", KEPLER_JOB_SCHEMA_VERSION))
        atomicWrite(File(jobDir, JOB_JSON_FILE_NAME), replacement.toString(2))
        replacement
    }

    /**
     * Narrow locked read-modify-write. The [mutate] lambda receives the current metadata and may
     * modify it in place. Only the modified keys are saved back; unrelated concurrent keys are
     * preserved. Use [removeKey] inside the lambda to remove keys.
     */
    fun update(jobDir: File, mutate: (JSONObject) -> Unit): JSONObject = synchronized(lockFor(jobDir)) {
        requireRealJobDirectory(jobDir)
        val file = when (val result = NoFollowFileSystem.resolveDirectChildResult(
            jobDir, JOB_JSON_FILE_NAME, requireFile = true
        )) {
            NoFollowInspection.Absent -> throw KeplerJobMetadataMissing(jobDir)
            is NoFollowInspection.InspectionFailed -> throw KeplerJobMetadataCorrupt(jobDir, result.exception)
            is NoFollowInspection.Present -> result.value
        }
        val originalText = try {
            NoFollowFileSystem.readTextVerified(file)
        } catch (ioFailure: Exception) {
            throw KeplerJobMetadataCorrupt(jobDir, ioFailure)
        }
        val job = try {
            JSONObject(originalText)
        } catch (parseFailure: JSONException) {
            throw KeplerJobMetadataCorrupt(jobDir, parseFailure)
        }
        mutate(job)
        job.put("schemaVersion", job.optInt("schemaVersion", KEPLER_JOB_SCHEMA_VERSION))
        val serialized = job.toString(2)
        R3GalleryColdMeasurement.measureMetadataWrite(originalText == serialized) {
            atomicWrite(File(jobDir, JOB_JSON_FILE_NAME), serialized)
        }
        job
    }

    /**
     * Persists restart-reconciliation ownership without replacing the live in-process lease.
     * When [consumesProcessingHandoff] is set, a current-runtime capture handoff whose kind
     * matches [kind] is consumed in the same atomic write as the operation start, so a
     * published handoff can never be observed as consumed without the owning operation being
     * durably active (and vice versa).
     */
    internal fun beginActiveOperation(
        jobDir: File,
        operationId: String = UUID.randomUUID().toString(),
        kind: KeplerActiveOperationKind,
        startedAt: Long = System.currentTimeMillis(),
        ownerLease: JobOperationLease? = null,
        consumesProcessingHandoff: Boolean = false
    ): String = withJobLock(jobDir) {
        val key = jobDir.toPath().toAbsolutePath().normalize().toString()
        val lease = if (ownerLease != null) {
            check(operationLeases[key] === ownerLease) {
                "Durable operation reassertion requires the exact job owner lease"
            }
            ownerLease
        } else {
            check(operationLeases[key] == null) {
                "A process-local job owner already exists"
            }
            JobOperationLease(key).also {
                check(operationLeases.putIfAbsent(key, it) == null)
                autoOperationLeases += key
            }
        }
        try {
            val outcome = inspectRecoveryMutationGate(
                jobDir,
                if (consumesProcessingHandoff) {
                    JobRecoveryMutationIntent.PROCESSING_START
                } else {
                    JobRecoveryMutationIntent.METADATA_EDIT
                },
                consumesProcessingHandoff = consumesProcessingHandoff,
                ownerLease = lease
            )
            if (outcome == JobRecoveryMutationGateOutcome.BLOCKED_PROCESSING_CLEANUP) {
                throw ProcessingCleanupRequiredException()
            }
            if (outcome != JobRecoveryMutationGateOutcome.ALLOWED) {
                throw JobRecoveryMutationBlockedException(outcome)
            }
            update(jobDir) { job ->
                job.put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(ACTIVE_OPERATION_ID, operationId)
                    .put(ACTIVE_OPERATION_KIND, kind.name)
                    .put(ACTIVE_OPERATION_STARTED_AT, startedAt)
                    .put(ACTIVE_OPERATION_UPDATED_AT, startedAt)
                if (consumesProcessingHandoff) {
                    // A mismatched or foreign handoff is left untouched for its own consumer.
                    correlateAndRemoveProcessingHandoff(job, kind)
                }
                job.remove(TERMINAL_OPERATION_ID)
            }
            lease.markDurableOperation(operationId, kind)
            operationId
        } catch (failure: Throwable) {
            if (ownerLease == null) {
                autoOperationLeases.remove(key)
                operationLeases.remove(key, lease)
            }
            throw failure
        }
    }

    /** Records the durable capture-to-processing handoff before capture ownership is released. */
    internal fun publishProcessingHandoff(
        jobDir: File,
        captureOperationId: String,
        kind: KeplerActiveOperationKind,
        createdAt: Long = System.currentTimeMillis()
    ): Boolean {
        return try {
            var matched = false
            update(jobDir) { job ->
                if (job.optString(ACTIVE_RUNTIME_SESSION_ID) != KeplerRuntimeSession.id ||
                    job.optString(ACTIVE_OPERATION_ID) != captureOperationId
                ) return@update
                matched = true
                job.put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, UUID.randomUUID().toString())
                    .put(PROCESSING_HANDOFF_KIND, kind.name)
                    .put(PROCESSING_HANDOFF_CREATED_AT, createdAt)
            }
            matched
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Clears a capture owner only when its handoff could not be published and
     * the exact lease still owns the durable marker. A failed metadata write
     * leaves the lease retained so the marker cannot become ownerless.
     */
    internal fun settleCaptureOwnerAfterHandoffFailure(
        jobDir: File,
        captureOperationId: String,
        ownerLease: JobOperationLease
    ): Boolean {
        check(isOperationOwner(jobDir, ownerLease)) {
            "Capture handoff settlement requires the exact owning lease"
        }
        val job = try {
            read(jobDir)
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            return false
        }
        val activeId = job.optString(ACTIVE_OPERATION_ID)
        if (activeId.isBlank()) return true
        if (activeId != captureOperationId ||
            job.optString(ACTIVE_RUNTIME_SESSION_ID) != KeplerRuntimeSession.id
        ) return false
        return clearActiveOperation(jobDir, captureOperationId, ownerLease)
    }

    /** Updates only the heartbeat; it is diagnostic/recovery evidence, not a runtime lock. */
    internal fun touchActiveOperation(jobDir: File, operationId: String): Boolean = runCatching {
        var matched = false
        update(jobDir) { job ->
            if (job.optString(ACTIVE_RUNTIME_SESSION_ID) != KeplerRuntimeSession.id ||
                job.optString(ACTIVE_OPERATION_ID) != operationId
            ) return@update
            matched = true
            job.put(ACTIVE_OPERATION_UPDATED_AT, System.currentTimeMillis())
        }
        matched
    }.getOrDefault(false)

    /** Clears only the marker owned by this runtime and operation. */
    internal fun clearActiveOperation(
        jobDir: File,
        operationId: String,
        ownerLease: JobOperationLease? = null
    ): Boolean {
        return try {
            var matched = false
            update(jobDir) { job ->
                if (job.optString(ACTIVE_RUNTIME_SESSION_ID) != KeplerRuntimeSession.id ||
                    job.optString(ACTIVE_OPERATION_ID) != operationId
                ) return@update
                matched = true
                job.remove(ACTIVE_RUNTIME_SESSION_ID)
                job.remove(ACTIVE_OPERATION_ID)
                job.remove(ACTIVE_OPERATION_KIND)
                job.remove(ACTIVE_OPERATION_STARTED_AT)
                job.remove(ACTIVE_OPERATION_UPDATED_AT)
            }
            if (matched) releaseAutoOperation(jobDir)
            if (matched) {
                ownerLease?.clearDurableOperation(operationId)
                ownerLease?.completeDurableSettlement(operationId)
            }
            matched
        } catch (failure: Error) {
            var cleanupFailure: Throwable? = null
            try {
                ownerLease?.markDurableSettlementPending(operationId)
            } catch (secondary: Throwable) {
                cleanupFailure = secondary
            }
            throw requireNotNull(combineSettlementFailure(failure, cleanupFailure))
        } catch (failure: Exception) {
            var cleanupFailure: Throwable? = null
            try {
                if (ownerLease != null && isOperationOwner(jobDir, ownerLease) &&
                    isCurrentActiveOperation(jobDir, operationId)
                ) {
                    ownerLease.markDurableSettlementPending(operationId)
                }
            } catch (secondary: Throwable) {
                cleanupFailure = secondary
            }
            val combined = combineSettlementFailure(failure, cleanupFailure)
            if (combined is Error || combined is java.util.concurrent.CancellationException) {
                throw combined
            }
            false
        }
    }

    /**
     * Conservative post-clear inspection for exact local-owner release. A read
     * failure is treated as still owned so a transient metadata fault cannot
     * release a lease behind an uncleared durable marker.
     */
    internal fun isCurrentActiveOperation(jobDir: File, operationId: String): Boolean {
        return try {
            val job = read(jobDir)
            job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id &&
                job.optString(ACTIVE_OPERATION_ID) == operationId
        } catch (failure: Error) {
            throw failure
        } catch (_: KeplerJobMetadataMissing) {
            false
        } catch (_: Exception) {
            true
        }
    }

    /** Clears a current-process marker when terminal metadata has already settled its owner. */
    internal fun clearActiveOperationKind(
        jobDir: File,
        kind: KeplerActiveOperationKind,
        ownerLease: JobOperationLease? = null
    ): Boolean = try {
        var matched = false
        var matchedOperationId: String? = null
        update(jobDir) { job ->
            if (job.optString(ACTIVE_RUNTIME_SESSION_ID) != KeplerRuntimeSession.id ||
                job.optString(ACTIVE_OPERATION_KIND) != kind.name
            ) return@update
            matched = true
            matchedOperationId = job.optString(ACTIVE_OPERATION_ID).takeIf { it.isNotBlank() }
            job.remove(ACTIVE_RUNTIME_SESSION_ID)
            job.remove(ACTIVE_OPERATION_ID)
            job.remove(ACTIVE_OPERATION_KIND)
            job.remove(ACTIVE_OPERATION_STARTED_AT)
            job.remove(ACTIVE_OPERATION_UPDATED_AT)
        }
        if (matched) releaseAutoOperation(jobDir)
        val exactLease = ownerLease ?: operationLeases[jobDir.toPath().toAbsolutePath().normalize().toString()]
        matchedOperationId?.let {
            exactLease?.clearDurableOperation(it)
            exactLease?.completeDurableSettlement(it)
        }
        matched
    } catch (failure: Error) {
        ownerLease?.currentDurableOperationId()?.let { ownerLease.markDurableSettlementPending(it) }
        throw failure
    } catch (_: Exception) {
        // This helper is used after terminal metadata/journal persistence. Keep the exact
        // operation reachable for the next production acquisition instead of leaving a lease
        // with no retry marker after an ordinary atomic-write failure.
        ownerLease?.currentDurableOperationId()?.let { ownerLease.markDurableSettlementPending(it) }
        false
    }

    /** Clears a dead-process marker only after recovery has matched its exact operation. */
    internal fun clearRecoveredActiveOperation(jobDir: File, operationId: String): Boolean = runCatching {
        var matched = false
        update(jobDir) { job ->
            if (job.optString(ACTIVE_OPERATION_ID) != operationId ||
                job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id
            ) return@update
            matched = true
            job.remove(ACTIVE_RUNTIME_SESSION_ID)
            job.remove(ACTIVE_OPERATION_ID)
            job.remove(ACTIVE_OPERATION_KIND)
            job.remove(ACTIVE_OPERATION_STARTED_AT)
            job.remove(ACTIVE_OPERATION_UPDATED_AT)
        }
        matched
    }.getOrDefault(false)

    /** Atomically settles a dead terminal export owner and removes obsolete recovery gating.
     *  The verification policy is preserved: a committed-unverified terminal owner stays
     *  PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION (the gate keeps reporting verification debt);
     *  only a verified terminal owner becomes STABLE. */
    internal fun finalizeRecoveredTerminalOperation(
        jobDir: File,
        operationId: String,
        recoveryLease: JobOperationLease? = null
    ): Boolean = try {
        var matched = false
        update(jobDir) { job ->
            if (job.optString(ACTIVE_OPERATION_ID) != operationId ||
                (job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id &&
                    (recoveryLease == null || !isOperationOwner(jobDir, recoveryLease))) ||
                job.optString(TERMINAL_OPERATION_ID) != operationId ||
                job.optString("currentPipelineStage") !in setOf("COMPLETE", "PARTIAL", "FAILED", "CANCELLED")
            ) return@update
            matched = true
            val commitState = job.optString("exportCommitState")
            val verified = job.optBoolean("exportVerified", false) ||
                commitState == GalleryExportCommitState.VERIFIED.name
            val committedUnverified = !verified &&
                (job.optBoolean("galleryExportCommitted", false) ||
                    commitState == GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name)
            if (committedUnverified) {
                job.put("recoveryState", "PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION")
                    .put("lastRecoveryClassification",
                        KeplerJobRecoveryClassification.PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION.name)
                    .put("lastRecoveryMessage",
                        "?�이 ?�시 ?�작?????�료???�보?�기???�인?��? ?�아 추�? ?�인???�요?�니??")
                    .put("recoveryMessage",
                        "공개 ?�보?�기 결과???�인???�료?��? ?�아 추�? ?�인???�요?�니??")
            } else {
                job.put("recoveryState", "STABLE")
                    .put("lastRecoveryClassification", "RECOVERED")
                    .put("lastRecoveryMessage", "?�이 ?�시 ?�작?????�료???�보?�기 결과�??�인?�습?�다.")
                job.remove("recoveryMessage")
            }
            job.put("recoveredAt", System.currentTimeMillis())
            job.remove(ACTIVE_RUNTIME_SESSION_ID)
            job.remove(ACTIVE_OPERATION_ID)
            job.remove(ACTIVE_OPERATION_KIND)
            job.remove(ACTIVE_OPERATION_STARTED_AT)
            job.remove(ACTIVE_OPERATION_UPDATED_AT)
        }
        matched
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        false
    }

    /** Atomically records a successful recovery classification before releasing a dead owner. */
    internal fun finalizeRecoveredInterruptedOperation(
        jobDir: File,
        operationId: String,
        classification: KeplerJobRecoveryClassification,
        recoveryMessage: String,
        recoveryLease: JobOperationLease? = null
    ): Boolean = try {
        var matched = false
        update(jobDir) { job ->
            if (job.optString(ACTIVE_OPERATION_ID) != operationId ||
                (job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id &&
                    (recoveryLease == null || !isOperationOwner(jobDir, recoveryLease)))
            ) return@update
            matched = true
            val blocksCurrentActions = classification in setOf(
                KeplerJobRecoveryClassification.PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION,
                KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED,
                KeplerJobRecoveryClassification.PUBLIC_COMMIT_MISSING,
                KeplerJobRecoveryClassification.PROCESSING_CLEANUP_REQUIRED
            )
            job.put("recoveryState", if (blocksCurrentActions) classification.name else "STABLE")
                .put("lastRecoveryClassification", classification.name)
                .put("lastRecoveryMessage", recoveryMessage)
                .put("recoveredAt", System.currentTimeMillis())
            if (blocksCurrentActions) job.put("recoveryMessage", recoveryMessage) else job.remove("recoveryMessage")
            job.remove(ACTIVE_RUNTIME_SESSION_ID)
            job.remove(ACTIVE_OPERATION_ID)
            job.remove(ACTIVE_OPERATION_KIND)
            job.remove(ACTIVE_OPERATION_STARTED_AT)
            job.remove(ACTIVE_OPERATION_UPDATED_AT)
        }
        matched
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        false
    }

    internal fun finalizeRecoveredProcessingHandoff(
        jobDir: File,
        recoveryLease: JobOperationLease? = null
    ): Boolean {
        return try {
        var matched = false
        update(jobDir) { job ->
            if (job.optString(PROCESSING_HANDOFF_RUNTIME_SESSION_ID).isBlank() ||
                (job.optString(PROCESSING_HANDOFF_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id &&
                    (recoveryLease == null || !isOperationOwner(jobDir, recoveryLease)))
            ) return@update
            matched = true
            job.put("recoveryState", "STABLE")
                .put("lastRecoveryClassification", KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT.name)
                .put("lastRecoveryMessage", "처리가 ?�작?�기 ???�전 ?�행??종료?�었지�??�본 ?�레?�을 ?�시 ?�용?????�습?�다.")
                .put("recoveredAt", System.currentTimeMillis())
            job.remove("recoveryMessage")
            job.remove(PROCESSING_HANDOFF_RUNTIME_SESSION_ID)
            job.remove(PROCESSING_HANDOFF_OPERATION_ID)
            job.remove(PROCESSING_HANDOFF_KIND)
            job.remove(PROCESSING_HANDOFF_CREATED_AT)
        }
        matched
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Installs the deterministic retry reason for a secondary worker-setup/terminalization
     * failure BEFORE an operation scope returns, so a returned scope can never leave the exact
     * lease registered without a reason [reconcilePendingDurableSettlement] understands:
     *  - a durable ACTIVE operation with durable terminal evidence ??pending durable settlement;
     *  - a durable ACTIVE operation without durable terminal evidence ??pending terminal settlement;
     *  - a missing durable owner leaves the exact lease protecting the capture processing
     *    handoff (pending processing-handoff settlement).
     * Returns [primaryFailure] combined with any marking failure. Never throws.
     */
    internal fun installWorkerSetupSettlementDebt(
        jobDir: File,
        lease: JobOperationLease,
        reason: String,
        primaryFailure: Throwable? = null
    ): Throwable? {
        return try {
            val durableId = lease.currentDurableOperationId()
            if (durableId != null) {
                val durableTerminalEvidence = try {
                    val job = read(jobDir)
                    job.optString(TERMINAL_OPERATION_ID).isNotBlank()
                } catch (_: Exception) {
                    false
                }
                if (durableTerminalEvidence) {
                    lease.markDurableSettlementPending(durableId)
                } else {
                    lease.markTerminalSettlementPending(
                        PendingTerminalSettlement(
                            operationId = durableId,
                            attemptStatus = "FAILED",
                            pipelineStage = "FAILED",
                            processStatus = "PIPELINE_FAILED",
                            reason = reason
                        )
                    )
                }
            } else {
                lease.markProcessingHandoffSettlementPending()
            }
            primaryFailure
        } catch (secondary: Throwable) {
            lease.currentDurableOperationId()?.let { lease.markDurableSettlementPending(it) }
            combineSettlementFailure(primaryFailure, secondary)
        }
    }

    /** Regression-test seam: injected once into the reinspection read. */

    /**
     * Settles a capture handoff when its processing worker could not be posted.
     * The exact lease is retained if the metadata settlement fails so a durable
     * handoff cannot become ownerless while the process is still alive.
     *
     * Boolean contract:
     * - TRUE: the durable processing handoff is settled (absent or durably finalized) AND
     *        no process-local retry ownership remains.
     * - FALSE: the durable processing handoff remains unresolved.
     *
     * Regression 1 (blank-check leak): when `settleOnlyIfPresent=true` and the initial
     * inspection proves the handoff absent, the temporarily reserved process-local
     * authority must be released before returning TRUE ??a dangling lease leaves the
     * next mutation acquisition blocked forever.
     *
     * Regression 2 (reinspection IOException ??false): a reinspection read failure must
     * NOT be interpreted as "handoff absent".  It is indistinguishably a transient read
     * fault; the helper must mark pending and return FALSE so the caller retries, never
     * returning TRUE while a durable handoff may still exist.
     *
     * For FALSE the helper guarantees a reachable process-local retry owner: either the
     * caller-owned [ownerLease], a reserved temporary authority, or the existing exact
     * process-local owner ([findOperationLease] != null) remains authoritative.  A fatal
     * [Error] during reinspection propagates after the pending marker is set; the caller
     * must not interpret the absence of a lease as "no handoff debt".
     */
    internal fun settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(
        jobDir: File,
        ownerLease: JobOperationLease? = null,
        settleOnlyIfPresent: Boolean = false
    ): Boolean {
        // Phase 1: reserve a process-local settlement authority BEFORE fallible inspection
        // so an initial-read IOException can mark THIS authority pending (regression 2 fix).
        val reservedAuthority: JobOperationLease? = if (ownerLease == null) {
            try {
                acquireTemporaryRecoveryAuthority(jobDir)
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                null
            }
        } else null

        val existingAuthority = if (ownerLease == null && reservedAuthority == null) {
            findOperationLease(jobDir)
        } else null

        // Phase 4: Explicit classification of authority types
        val authorityType = when {
            ownerLease != null -> AuthorityType.CALLER_OWNED
            reservedAuthority != null -> AuthorityType.SELF_RESERVED
            existingAuthority != null && existingAuthority.hasPendingProcessingHandoffSettlement() &&
                existingAuthority.isReconciliationReady() -> AuthorityType.EXISTING_PENDING_HANDOFF_RETRY
            existingAuthority != null -> AuthorityType.EXISTING_LIVE_OR_UNRELATED
            else -> AuthorityType.NONE_AVAILABLE
        }

        // The lease object that "owns" this settlement: any of the three sources above,
        // in preference order.  Preserving `lease` here lets the reinspection-catch block
        // mark the correct object pending on any read failure.
        val lease = ownerLease ?: reservedAuthority ?: existingAuthority

        // Injected initial-read failure (regression test seam ??only applies to first read).
        val initialReadFailure = settleInitialReadFailureForTest?.also {
            settleInitialReadFailureForTest = null
        }

        val handoffPresent = try {
            if (initialReadFailure != null) {
                throw initialReadFailure
            }
            read(jobDir).optString(PROCESSING_HANDOFF_OPERATION_ID).isNotBlank()
        } catch (failure: Error) {
            when (authorityType) {
                AuthorityType.CALLER_OWNED -> ownerLease?.markProcessingHandoffSettlementPending()
                AuthorityType.SELF_RESERVED -> {
                    reservedAuthority?.markProcessingHandoffSettlementPending()
                    reservedAuthority?.releaseOrRetainForReconciliation()
                }
                AuthorityType.EXISTING_PENDING_HANDOFF_RETRY -> existingAuthority?.markProcessingHandoffSettlementPending()
                AuthorityType.EXISTING_LIVE_OR_UNRELATED -> {}
                AuthorityType.NONE_AVAILABLE -> {
                    val fallback = reservedAuthority ?: run {
                        try { acquireTemporaryRecoveryAuthority(jobDir) } catch (_: Exception) { null }
                    }
                    fallback?.markProcessingHandoffSettlementPending()
                    fallback?.releaseOrRetainForReconciliation()
                }
            }
            throw failure
        } catch (_: Exception) {
            when (authorityType) {
                AuthorityType.CALLER_OWNED -> ownerLease?.markProcessingHandoffSettlementPending()
                AuthorityType.SELF_RESERVED -> {
                    reservedAuthority?.markProcessingHandoffSettlementPending()
                    reservedAuthority?.releaseOrRetainForReconciliation()
                }
                AuthorityType.EXISTING_PENDING_HANDOFF_RETRY -> existingAuthority?.markProcessingHandoffSettlementPending()
                AuthorityType.EXISTING_LIVE_OR_UNRELATED -> {}
                AuthorityType.NONE_AVAILABLE -> {
                    val fallback = reservedAuthority ?: run {
                        try { acquireTemporaryRecoveryAuthority(jobDir) } catch (_: Exception) { null }
                    }
                    fallback?.markProcessingHandoffSettlementPending()
                    fallback?.releaseOrRetainForReconciliation()
                }
            }
            return false
        }

        if (!handoffPresent) {
            when (authorityType) {
                AuthorityType.CALLER_OWNED -> {
                    if (ownerLease == null) return true
                    val current = try {
                        read(jobDir)
                    } catch (failure: Error) {
                        ownerLease.markProcessingHandoffSettlementPending()
                        throw failure
                    } catch (_: Exception) {
                        ownerLease.markProcessingHandoffSettlementPending()
                        return false
                    }
                    if (current.optString(ACTIVE_OPERATION_ID).isBlank()) {
                        ownerLease.completeProcessingHandoffSettlement()
                        return true
                    }
                    ownerLease.markDurableSettlementPending(current.optString(ACTIVE_OPERATION_ID))
                    return false
                }
                AuthorityType.SELF_RESERVED -> {
                    reservedAuthority?.releaseIfProcessingSettled()
                    return true
                }
                AuthorityType.EXISTING_PENDING_HANDOFF_RETRY -> {
                    existingAuthority?.completeProcessingHandoffSettlement()
                    existingAuthority?.releaseIfProcessingSettled()
                    return true
                }
                AuthorityType.EXISTING_LIVE_OR_UNRELATED -> {
                    return true
                }
                AuthorityType.NONE_AVAILABLE -> {
                    return true
                }
            }
        }

        // Handoff present: resolve the authoritative lease based on classification.
        val resolvedLease = when (authorityType) {
            AuthorityType.CALLER_OWNED -> ownerLease
            AuthorityType.SELF_RESERVED -> reservedAuthority
            AuthorityType.EXISTING_PENDING_HANDOFF_RETRY -> existingAuthority
            AuthorityType.EXISTING_LIVE_OR_UNRELATED -> return false  // Cannot hijack unrelated authority
            AuthorityType.NONE_AVAILABLE -> {
                try {
                    val recoveryFailure = settleRecoveryCheckFailureForTest?.also {
                        settleRecoveryCheckFailureForTest = null
                    }
                    if (recoveryFailure != null) throw recoveryFailure
                    acquireRecoveryCheckedOperation(
                        jobDir,
                        JobRecoveryMutationIntent.PROCESSING_START,
                        consumesProcessingHandoff = true
                    )
                } catch (failure: Error) {
                    reservedAuthority?.markProcessingHandoffSettlementPending()
                    reservedAuthority?.releaseOrRetainForReconciliation()
                    throw failure
                } catch (_: Exception) {
                    val fallbackAuthority = reservedAuthority ?: run {
                        try { acquireTemporaryRecoveryAuthority(jobDir) } catch (_: Exception) { null }
                    }
                    fallbackAuthority?.markProcessingHandoffSettlementPending()
                    fallbackAuthority?.releaseOrRetainForReconciliation()
                    return false
                }
            }
        }

        // Phase 2: Fix the real production metadata exception handling
        // Catch KeplerJobMetadataException instead of raw IOException for production reads
        val reinspectionState = try {
            settlePostAuthorityReadFailureForTest?.let { throw it }
            read(jobDir).optString(PROCESSING_HANDOFF_OPERATION_ID)
        } catch (failure: Error) {
            when (authorityType) {
                AuthorityType.CALLER_OWNED -> ownerLease?.markProcessingHandoffSettlementPending()
                AuthorityType.SELF_RESERVED -> {
                    reservedAuthority?.markProcessingHandoffSettlementPending()
                    reservedAuthority?.releaseOrRetainForReconciliation()
                }
                AuthorityType.EXISTING_PENDING_HANDOFF_RETRY -> existingAuthority?.markProcessingHandoffSettlementPending()
                AuthorityType.EXISTING_LIVE_OR_UNRELATED -> {}
                AuthorityType.NONE_AVAILABLE -> resolvedLease?.markProcessingHandoffSettlementPending()
            }
            throw failure
        } catch (_: KeplerJobMetadataException) {
            when (authorityType) {
                AuthorityType.CALLER_OWNED -> ownerLease?.markProcessingHandoffSettlementPending()
                AuthorityType.SELF_RESERVED -> {
                    reservedAuthority?.markProcessingHandoffSettlementPending()
                    reservedAuthority?.releaseOrRetainForReconciliation()
                }
                AuthorityType.EXISTING_PENDING_HANDOFF_RETRY -> existingAuthority?.markProcessingHandoffSettlementPending()
                AuthorityType.EXISTING_LIVE_OR_UNRELATED -> {}
                AuthorityType.NONE_AVAILABLE -> resolvedLease?.markProcessingHandoffSettlementPending()
            }
            return false
        } catch (_: IOException) {
            when (authorityType) {
                AuthorityType.CALLER_OWNED -> ownerLease?.markProcessingHandoffSettlementPending()
                AuthorityType.SELF_RESERVED -> {
                    reservedAuthority?.markProcessingHandoffSettlementPending()
                    reservedAuthority?.releaseOrRetainForReconciliation()
                }
                AuthorityType.EXISTING_PENDING_HANDOFF_RETRY -> existingAuthority?.markProcessingHandoffSettlementPending()
                AuthorityType.EXISTING_LIVE_OR_UNRELATED -> {}
                AuthorityType.NONE_AVAILABLE -> resolvedLease?.markProcessingHandoffSettlementPending()
            }
            return false
        }

        if (reinspectionState.isBlank()) {
            if (resolvedLease != null) {
                resolvedLease.completeProcessingHandoffSettlement()
                when (authorityType) {
                    AuthorityType.CALLER_OWNED -> {}
                    AuthorityType.SELF_RESERVED -> resolvedLease.releaseIfProcessingSettled()
                    AuthorityType.EXISTING_PENDING_HANDOFF_RETRY -> resolvedLease.releaseIfProcessingSettled()
                    AuthorityType.EXISTING_LIVE_OR_UNRELATED -> { /* unreachable */ }
                    AuthorityType.NONE_AVAILABLE -> resolvedLease.releaseIfProcessingSettled()
                }
            }
            return true
        }

        val settled = try {
            finalizeRecoveredProcessingHandoff(jobDir, resolvedLease)
        } catch (failure: Error) {
            when (authorityType) {
                AuthorityType.CALLER_OWNED -> ownerLease?.markProcessingHandoffSettlementPending()
                AuthorityType.SELF_RESERVED -> {
                    reservedAuthority?.markProcessingHandoffSettlementPending()
                    reservedAuthority?.releaseOrRetainForReconciliation()
                }
                AuthorityType.EXISTING_PENDING_HANDOFF_RETRY -> existingAuthority?.markProcessingHandoffSettlementPending()
                AuthorityType.EXISTING_LIVE_OR_UNRELATED -> {}  // Do NOT mark existing unrelated authority
                AuthorityType.NONE_AVAILABLE -> reservedAuthority?.markProcessingHandoffSettlementPending()
            }
            throw failure
        } catch (_: Exception) {
            if (authorityType != AuthorityType.EXISTING_LIVE_OR_UNRELATED) {
                resolvedLease?.markProcessingHandoffSettlementPending()
                if (authorityType == AuthorityType.SELF_RESERVED) {
                    resolvedLease?.releaseOrRetainForReconciliation()
                }
            }
            return false
        }

        if (settled) {
            resolvedLease?.completeProcessingHandoffSettlement()
            when (authorityType) {
                AuthorityType.CALLER_OWNED -> {}
                AuthorityType.SELF_RESERVED -> resolvedLease?.releaseIfProcessingSettled()
                AuthorityType.EXISTING_PENDING_HANDOFF_RETRY -> resolvedLease?.releaseIfProcessingSettled()
                AuthorityType.EXISTING_LIVE_OR_UNRELATED -> { /* unreachable */ }
                AuthorityType.NONE_AVAILABLE -> resolvedLease?.releaseIfProcessingSettled()
            }
        } else {
            when (authorityType) {
                AuthorityType.CALLER_OWNED -> ownerLease?.markProcessingHandoffSettlementPending()
                AuthorityType.SELF_RESERVED -> {
                    reservedAuthority?.markProcessingHandoffSettlementPending()
                    reservedAuthority?.releaseOrRetainForReconciliation()
                }
                AuthorityType.EXISTING_PENDING_HANDOFF_RETRY -> existingAuthority?.markProcessingHandoffSettlementPending()
                AuthorityType.EXISTING_LIVE_OR_UNRELATED -> {}
                AuthorityType.NONE_AVAILABLE -> resolvedLease?.markProcessingHandoffSettlementPending()
            }
            return false
        }
        return settled
    }

    private enum class AuthorityType {
        CALLER_OWNED,
        SELF_RESERVED,
        EXISTING_PENDING_HANDOFF_RETRY,
        EXISTING_LIVE_OR_UNRELATED,
        NONE_AVAILABLE
    }

    fun atomicWrite(file: File, text: String) {
        atomicWriteFailureSequenceForTest?.let { failures ->
            if (failures.isNotEmpty()) {
                failures.removeAt(0)?.let { throw it }
            }
        }
        atomicWriteFailureForTest?.let { failure ->
            atomicWriteFailureForTest = null
            throw failure
        }
        val parent = file.parentFile ?: error("job metadata parent missing")
        requireRealJobDirectory(parent)
        check(!Files.isSymbolicLink(file.toPath())) { "Metadata destination must not be a symbolic link" }
        check(parent.exists() || parent.mkdirs()) { "Could not create ${parent.absolutePath}" }
        val temp = File(parent, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            atomicReplace(temp, file)
            atomicWriteCount += 1
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun atomicReplace(temp: File, destination: File) {
        check(!Files.isSymbolicLink(destination.toPath())) {
            "Atomic destination must not be a symbolic link"
        }
        try {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun requireRealJobDirectory(jobDir: File) {
        check(NoFollowFileSystem.isRealDirectory(jobDir.toPath())) {
            "Job directory must be a real directory"
        }
    }
}

class JobOperationLease internal constructor(internal val key: String) {
    private val released = AtomicBoolean(false)
    private val processingAttemptId = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val lastProcessingAttemptId = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val pendingDurableSettlementId = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val pendingProcessingHandoffSettlement = AtomicBoolean(false)
    private val pendingPublicExportSettlement =
        java.util.concurrent.atomic.AtomicReference<PendingPublicExportSettlement?>(null)
    private val pendingTerminalSettlement =
        java.util.concurrent.atomic.AtomicReference<PendingTerminalSettlement?>(null)
    private val currentDurableOperationId =
        java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val currentDurableOperationKind =
        java.util.concurrent.atomic.AtomicReference<KeplerActiveOperationKind?>(null)
    private val reconciliationReady = AtomicBoolean(false)
    private val ownedReprocessTransactionId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    internal fun bindOwnedReprocessTransaction(transactionId: String) {
        if (released.get()) throw IllegalStateException("Lease already released")
        val current = ownedReprocessTransactionId.get()
        if (current != null && current != transactionId) {
            throw IllegalStateException("Lease already bound to different reprocess transaction $current")
        }
        ownedReprocessTransactionId.compareAndSet(current, transactionId)
    }

    internal fun clearOwnedReprocessTransaction(transactionId: String) {
        val current = ownedReprocessTransactionId.get()
        if (current == null || current != transactionId) {
            throw IllegalStateException("Lease not bound to transaction $transactionId")
        }
        ownedReprocessTransactionId.compareAndSet(current, null)
    }

    internal fun ownedReprocessTransactionId(): String? = ownedReprocessTransactionId.get()

    internal fun claimProcessingAttempt(attemptId: String): Boolean {
        if (released.get() || !processingAttemptId.compareAndSet(null, attemptId)) return false
        lastProcessingAttemptId.set(attemptId)
        return true
    }

    internal fun releaseProcessingAttempt(attemptId: String): Boolean =
        compareAndClear(processingAttemptId, attemptId)

    internal fun isProcessingAttemptOwner(attemptId: String): Boolean =
        !released.get() && processingAttemptId.get() == attemptId

    internal fun lastProcessingAttemptId(): String? = lastProcessingAttemptId.get()

    internal fun markDurableSettlementPending(operationId: String) {
        pendingDurableSettlementId.compareAndSet(null, operationId)
    }

    internal fun markTerminalSettlementPending(settlement: PendingTerminalSettlement) {
        val existing = pendingTerminalSettlement.get()
        if (existing != null) {
            check(existing.operationId == settlement.operationId) {
                "A different terminal settlement already owns this lease"
            }
            return
        }
        pendingTerminalSettlement.compareAndSet(null, settlement)
        markDurableSettlementPending(settlement.operationId)
    }

    internal fun pendingTerminalSettlement(): PendingTerminalSettlement? = pendingTerminalSettlement.get()

    internal fun completeTerminalSettlement(operationId: String): Boolean =
        pendingTerminalSettlement.get()?.let { pending ->
            if (pending.operationId != operationId) return false
            pendingTerminalSettlement.compareAndSet(pending, null)
        } ?: true

    internal fun markDurableOperation(operationId: String, kind: KeplerActiveOperationKind) {
        currentDurableOperationId.set(operationId)
        currentDurableOperationKind.set(kind)
    }

    internal fun clearDurableOperation(operationId: String) {
        if (compareAndClear(currentDurableOperationId, operationId)) {
            currentDurableOperationKind.set(null)
        }
    }

    internal fun currentDurableOperationId(): String? = currentDurableOperationId.get()

    internal fun currentDurableOperationKind(): KeplerActiveOperationKind? =
        currentDurableOperationKind.get()

    internal fun markPublicExportSettlementPending(
        settlement: PendingPublicExportSettlement
    ): Boolean {
        val existing = pendingPublicExportSettlement.get()
        if (existing != null) {
            check(existing.operationId == settlement.operationId) {
                "A different PUBLIC_EXPORT settlement already owns this lease"
            }
            return false
        }
        return pendingPublicExportSettlement.compareAndSet(null, settlement)
    }

    internal fun pendingPublicExportSettlement(): PendingPublicExportSettlement? =
        pendingPublicExportSettlement.get()

    internal fun completePublicExportSettlement(operationId: String): Boolean =
        pendingPublicExportSettlement.get()?.let { pending ->
            if (pending.operationId != operationId) return false
            pendingPublicExportSettlement.compareAndSet(pending, null)
        } ?: true

    internal fun markProcessingSettlementPending(attemptId: String) {
        lastProcessingAttemptId.compareAndSet(null, attemptId)
        markDurableSettlementPending(attemptId)
    }

    internal fun markProcessingHandoffSettlementPending() {
        pendingProcessingHandoffSettlement.set(true)
    }

    internal fun hasPendingProcessingHandoffSettlement(): Boolean =
        pendingProcessingHandoffSettlement.get()

    internal fun hasPendingReconciliationDebt(): Boolean =
        pendingTerminalSettlement.get() != null ||
            pendingPublicExportSettlement.get() != null ||
            pendingProcessingHandoffSettlement.get() ||
            pendingDurableSettlementId.get() != null

    /** True only when the original owner has explicitly marked this lease as ready for
     *  reconciliation by a competing acquisition. Pending debt markers alone do NOT imply
     *  reconciliation readiness; the owner must finish its own cleanup before calling
     *  [markReconciliationReady]. */
    internal fun isReconciliationReady(): Boolean = reconciliationReady.get()

    /** Called ONLY by the original owner scope at its exact relinquish boundary.
     *  Transitions the lease from LIVE/LIVE_WITH_PENDING_DEBT to RETAINED_READY_FOR_RECONCILIATION.
     *  After this point, the original owner must NOT perform any further lease/job authority work. */
    internal fun markReconciliationReady() {
        reconciliationReady.set(true)
    }

    internal fun completeProcessingHandoffSettlement(): Boolean =
        pendingProcessingHandoffSettlement.compareAndSet(true, false)

    internal fun pendingDurableSettlementId(): String? = pendingDurableSettlementId.get()

    internal fun completeDurableSettlement(operationId: String): Boolean {
        if (!compareAndClear(pendingDurableSettlementId, operationId)) return false
        compareAndClear(processingAttemptId, operationId)
        return true
    }

    /** AtomicReference CAS compares object identity; operation IDs are durable value identities. */
    private fun compareAndClear(reference: java.util.concurrent.atomic.AtomicReference<String?>, expected: String): Boolean {
        while (true) {
            val current = reference.get() ?: return false
            if (current != expected) return false
            if (reference.compareAndSet(current, null)) return true
        }
    }

    /**
     * Owner-relinquish boundary: called by the original owner scope when it has finished
     * all lease/job ownership work and is ready to either release or retain for reconciliation.
     * If explicit pending reconciliation debt remains, marks the lease reconciliation-ready
     * and retains it for a competing acquisition to reconcile. Otherwise, releases normally.
     * Returns true if the lease was released, false if retained for reconciliation.
     */
    internal fun releaseOrRetainForReconciliation(): Boolean {
        if (hasPendingReconciliationDebt()) {
            markReconciliationReady()
            return false
        }
        val released = releaseIfProcessingSettled()
        if (released) return true
        if (hasPendingReconciliationDebt()) {
            markReconciliationReady()
            return false
        }
        return false
    }

    /**
     * Releases the top-level lease only when a borrowed processing sublease has
     * already settled its durable owner. A failed ProcessingAttempt clear keeps
     * the sublease attached so outer pipeline cleanup cannot create an ownerless
     * current-runtime marker.
     */
    internal fun releaseIfProcessingSettled(): Boolean {
        if (pendingPublicExportSettlement.get() != null) return false
        if (pendingTerminalSettlement.get() != null || pendingDurableSettlementId.get() != null) return false
        // A pending processing-handoff settlement means the exact lease is the same-process
        // retry owner of a durable handoff debt: releasing E here would leave the handoff
        // ownerless while the process is alive.
        if (pendingProcessingHandoffSettlement.get()) return false
        // A nested PUBLIC_EXPORT (or any newer durable operation) is still owned by this lease.
        // The wrapper must settle/clear that exact operation before releasing the top-level lease.
        currentDurableOperationId.get()?.let {
            // A terminal writer which could not install a more specific retry record must still
            // leave a reachable debt marker.  Otherwise the retained lease would block the next
            // production acquisition forever with no convergence path.
            markDurableSettlementPending(it)
            return false
        }
        val attemptId = lastProcessingAttemptId.get()
        if (attemptId != null && isProcessingAttemptOwner(attemptId)) return false
        release()
        return true
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        KeplerJobMetadata.releaseOperation(this)
        KeplerJobMetadata.leaseReleaseCount += 1
    }
}
