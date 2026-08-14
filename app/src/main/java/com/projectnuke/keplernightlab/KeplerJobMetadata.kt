package com.projectnuke.keplernightlab

import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
    "이전 처리 작업의 파일 정리가 완료되지 않아 지금은 다시 합성할 수 없습니다."
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

internal enum class JobRecoveryMutationGateOutcome {
    ALLOWED,
    BLOCKED_DEAD_OPERATION,
    BLOCKED_HANDOFF,
    BLOCKED_ORPHANED_JOB_METADATA,
    BLOCKED_PROCESSING_CLEANUP,
    BLOCKED_AMBIGUOUS_RECOVERY,
    BLOCKED_PUBLIC_COMMIT_MISSING,
    BLOCKED_EXPORT_VERIFICATION,
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
        JobRecoveryMutationGateOutcome.BLOCKED_DEAD_OPERATION -> "이전 실행의 작업 소유권이 아직 복구되지 않아 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_HANDOFF -> "촬영 결과의 처리 인계가 아직 완료되지 않아 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_ORPHANED_JOB_METADATA -> "작업 메타데이터가 없어 복구 확인 전에는 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_PROCESSING_CLEANUP -> "이전 처리 작업의 파일 정리가 완료되지 않아 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_AMBIGUOUS_RECOVERY -> "복구되지 않은 작업 증거가 있어 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_PUBLIC_COMMIT_MISSING -> "공개 결과의 커밋 증거가 없어 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION -> "공개 결과를 확인하지 못해 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_INVALID_PROCESSING_JOURNAL -> "처리 복구 기록을 읽을 수 없어 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE -> "복구 중인 작업은 지금 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.INSPECTION_FAILED -> "작업 복구 상태를 확인하지 못해 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_INVALID_EXPORT_JOURNAL -> "내보내기 복구 기록을 읽을 수 없어 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_SETTLED_JOURNAL -> "완료된 처리 기록의 정리가 끝나지 않아 지금은 결과 경로를 변경할 수 없습니다."
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
    internal var leaseReleaseCount: Int = 0
        internal set

    /** Narrow test-only seam to reset [atomicWriteCount]. Tests must save/restore prior value. */
    internal fun setAtomicWriteCountForTest(value: Int) { atomicWriteCount = value }
    /** Narrow test-only seam to reset [leaseReleaseCount]. Tests must save/restore prior value. */
    internal fun setLeaseReleaseCountForTest(value: Int) { leaseReleaseCount = value }

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
            if (isReprocessQuarantined(jobDir)) return@withJobLock JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE
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
            val exactCurrentOwner = ownerLease != null &&
                operationLeases[jobDir.toPath().toAbsolutePath().normalize().toString()] === ownerLease
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
            val exportJournals = MediaStoreExportJournal.list(jobDir)
            val exportBlocks = exportJournals.any { journal ->
                journal.state in setOf(
                    MediaStoreExportState.PREPARED,
                    MediaStoreExportState.ROW_INSERTED,
                    MediaStoreExportState.CONTENT_WRITTEN,
                    MediaStoreExportState.PUBLIC_COMMITTED,
                    MediaStoreExportState.CLEANUP_REQUIRED
                )
            }
            if (exportBlocks) return@withJobLock JobRecoveryMutationGateOutcome.BLOCKED_DEAD_OPERATION
            when (job.optString("recoveryState")) {
                PROCESSING_CLEANUP_REQUIRED -> JobRecoveryMutationGateOutcome.BLOCKED_PROCESSING_CLEANUP
                "AMBIGUOUS_RECOVERY_REQUIRED" -> JobRecoveryMutationGateOutcome.BLOCKED_AMBIGUOUS_RECOVERY
                "PUBLIC_COMMIT_MISSING" -> JobRecoveryMutationGateOutcome.BLOCKED_PUBLIC_COMMIT_MISSING
                "PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION" -> JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION
                else -> JobRecoveryMutationGateOutcome.ALLOWED
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
     * Retries a terminal owner clear recorded by an operation scope that has already returned.
     * The retained lease is still the only process-local authority, so a successful retry can
     * release it immediately; a failed retry leaves the exact owner protected for the next
     * production mutation/recovery entry.
     */
    private fun reconcilePendingDurableSettlement(
        jobDir: File,
        lease: JobOperationLease
    ): Boolean {
        val pendingPublicExport = lease.pendingPublicExportSettlement()
        if (pendingPublicExport != null) {
            val settled = try {
                settleOwnedPublicExportInterruption(
                    jobDir = jobDir,
                    ownerLease = lease,
                    failureMessage = pendingPublicExport.failureMessage,
                    finalOutputFormat = pendingPublicExport.finalOutputFormat,
                    disposition = pendingPublicExport.disposition
                )
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                false
            }
            if (!settled) return false
            lease.completePublicExportSettlement(pendingPublicExport.operationId)
            lease.release()
            return true
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
            lease.completeProcessingHandoffSettlement()
            val current = try {
                read(jobDir)
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                return false
            }
            val activeId = current.optString(ACTIVE_OPERATION_ID)
            if (activeId.isBlank()) {
                lease.release()
                return true
            }
            lease.markDurableSettlementPending(activeId)
            return false
        }
        val pendingId = lease.pendingDurableSettlementId() ?: return false
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
            lease.release()
            return true
        }
        if (activeId != pendingId || activeRuntime != KeplerRuntimeSession.id) return false
        val cleared = clearActiveOperation(jobDir, pendingId, lease)
        if (!cleared && isCurrentActiveOperation(jobDir, pendingId)) return false
        lease.completeDurableSettlement(pendingId)
        lease.release()
        return true
    }

    internal fun hasProcessingCleanupBlocker(jobDir: File): Boolean = runCatching {
        val job = read(jobDir)
        job.optString("recoveryState") == PROCESSING_CLEANUP_REQUIRED ||
            ProcessingArtifactJournal.scan(jobDir).let { scan ->
                scan.invalidFiles.isNotEmpty() || scan.validJournals.any {
                    isUnresolvedAuthoritativeProcessingJournal(it.second) ||
                        (it.second.state == ProcessingArtifactJournalState.SETTLED && it.second.adoptedResult == "NO_OUTPUT")
                }
            }
    }.getOrDefault(false)

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
                    job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id)
            ) return@update
            matched = true
            job.put("recoveryState", PROCESSING_CLEANUP_REQUIRED)
                .put("processingCleanupDebt", JSONArray(failures.distinct()))
                .put("lastRecoveryClassification", historicalClassification)
                .put("lastRecoveryMessage", "처리 결과는 보존되었지만 이전 작업의 파일 정리가 아직 완료되지 않았습니다.")
                .put("recoveredAt", System.currentTimeMillis())
                .put("recoveryMessage", "이전 처리 작업의 파일 정리가 완료되지 않아 지금은 다시 합성할 수 없습니다.")
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
        val job = try {
            JSONObject(NoFollowFileSystem.readTextVerified(file))
        } catch (parseFailure: JSONException) {
            throw KeplerJobMetadataCorrupt(jobDir, parseFailure)
        } catch (ioFailure: Exception) {
            throw KeplerJobMetadataCorrupt(jobDir, ioFailure)
        }
        mutate(job)
        job.put("schemaVersion", job.optInt("schemaVersion", KEPLER_JOB_SCHEMA_VERSION))
        atomicWrite(File(jobDir, JOB_JSON_FILE_NAME), job.toString(2))
        job
    }

    /** Persists restart-reconciliation ownership without replacing the live in-process lease. */
    internal fun beginActiveOperation(
        jobDir: File,
        operationId: String = UUID.randomUUID().toString(),
        kind: KeplerActiveOperationKind,
        startedAt: Long = System.currentTimeMillis(),
        ownerLease: JobOperationLease? = null
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
                JobRecoveryMutationIntent.METADATA_EDIT,
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
            if (matched) ownerLease?.clearDurableOperation(operationId)
            matched
        } catch (failure: Error) {
            ownerLease?.markDurableSettlementPending(operationId)
            throw failure
        } catch (_: Exception) {
            if (ownerLease != null && isOperationOwner(jobDir, ownerLease) &&
                isCurrentActiveOperation(jobDir, operationId)
            ) {
                ownerLease.markDurableSettlementPending(operationId)
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
    internal fun clearActiveOperationKind(jobDir: File, kind: KeplerActiveOperationKind): Boolean = runCatching {
        var matched = false
        update(jobDir) { job ->
            if (job.optString(ACTIVE_RUNTIME_SESSION_ID) != KeplerRuntimeSession.id ||
                job.optString(ACTIVE_OPERATION_KIND) != kind.name
            ) return@update
            matched = true
            job.remove(ACTIVE_RUNTIME_SESSION_ID)
            job.remove(ACTIVE_OPERATION_ID)
            job.remove(ACTIVE_OPERATION_KIND)
            job.remove(ACTIVE_OPERATION_STARTED_AT)
            job.remove(ACTIVE_OPERATION_UPDATED_AT)
        }
        if (matched) releaseAutoOperation(jobDir)
        matched
    }.getOrDefault(false)

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

    /** Atomically settles a dead terminal export owner and removes obsolete recovery gating. */
    internal fun finalizeRecoveredTerminalOperation(
        jobDir: File,
        operationId: String,
        recoveryLease: JobOperationLease? = null
    ): Boolean = runCatching {
        var matched = false
        update(jobDir) { job ->
            if (job.optString(ACTIVE_OPERATION_ID) != operationId ||
                (job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id &&
                    (recoveryLease == null || !isOperationOwner(jobDir, recoveryLease))) ||
                job.optString(TERMINAL_OPERATION_ID) != operationId ||
                job.optString("currentPipelineStage") !in setOf("COMPLETE", "PARTIAL", "FAILED", "CANCELLED")
            ) return@update
            matched = true
            job.put("recoveryState", "STABLE")
                .put("lastRecoveryClassification", "RECOVERED")
                .put("lastRecoveryMessage", "앱이 다시 시작된 후 완료된 내보내기 결과를 확인했습니다.")
                .put("recoveredAt", System.currentTimeMillis())
            job.remove("recoveryMessage")
            job.remove(ACTIVE_RUNTIME_SESSION_ID)
            job.remove(ACTIVE_OPERATION_ID)
            job.remove(ACTIVE_OPERATION_KIND)
            job.remove(ACTIVE_OPERATION_STARTED_AT)
            job.remove(ACTIVE_OPERATION_UPDATED_AT)
        }
        matched
    }.getOrDefault(false)

    /** Atomically records a successful recovery classification before releasing a dead owner. */
    internal fun finalizeRecoveredInterruptedOperation(
        jobDir: File,
        operationId: String,
        classification: KeplerJobRecoveryClassification,
        recoveryMessage: String,
        recoveryLease: JobOperationLease? = null
    ): Boolean = runCatching {
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
    }.getOrDefault(false)

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
                .put("lastRecoveryMessage", "처리가 시작되기 전 이전 실행이 종료되었지만 원본 프레임을 다시 사용할 수 있습니다.")
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
     * Settles a capture handoff when its processing worker could not be posted.
     * The exact lease is retained if the metadata settlement fails so a durable
     * handoff cannot become ownerless while the process is still alive.
     */
    internal fun settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(
        jobDir: File,
        ownerLease: JobOperationLease? = null
    ): Boolean {
        val handoffPresent = try {
            read(jobDir).optString(PROCESSING_HANDOFF_OPERATION_ID).isNotBlank()
        } catch (failure: Error) {
            ownerLease?.markProcessingHandoffSettlementPending()
            throw failure
        } catch (_: Exception) {
            ownerLease?.markProcessingHandoffSettlementPending()
            return false
        }
        if (!handoffPresent) {
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
                ownerLease.release()
                return true
            }
            ownerLease.markDurableSettlementPending(current.optString(ACTIVE_OPERATION_ID))
            return false
        }

        val lease = ownerLease ?: try {
            acquireRecoveryCheckedOperation(
                jobDir,
                JobRecoveryMutationIntent.PROCESSING_START,
                consumesProcessingHandoff = true
            )
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            return false
        }

        val settled = try {
            finalizeRecoveredProcessingHandoff(jobDir, lease)
        } catch (failure: Error) {
            if (ownerLease == null) {
                lease.release()
            } else {
                ownerLease.markProcessingHandoffSettlementPending()
            }
            throw failure
        } catch (_: Exception) {
            false
        }
        if (settled || ownerLease == null) {
            lease.release()
        } else {
            ownerLease.markProcessingHandoffSettlementPending()
        }
        return settled
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
    private val currentDurableOperationId =
        java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val currentDurableOperationKind =
        java.util.concurrent.atomic.AtomicReference<KeplerActiveOperationKind?>(null)

    internal fun claimProcessingAttempt(attemptId: String): Boolean {
        if (released.get() || !processingAttemptId.compareAndSet(null, attemptId)) return false
        lastProcessingAttemptId.set(attemptId)
        return true
    }

    internal fun releaseProcessingAttempt(attemptId: String): Boolean =
        processingAttemptId.compareAndSet(attemptId, null)

    internal fun isProcessingAttemptOwner(attemptId: String): Boolean =
        !released.get() && processingAttemptId.get() == attemptId

    internal fun lastProcessingAttemptId(): String? = lastProcessingAttemptId.get()

    internal fun markDurableSettlementPending(operationId: String) {
        pendingDurableSettlementId.compareAndSet(null, operationId)
    }

    internal fun markDurableOperation(operationId: String, kind: KeplerActiveOperationKind) {
        currentDurableOperationId.set(operationId)
        currentDurableOperationKind.set(kind)
    }

    internal fun clearDurableOperation(operationId: String) {
        if (currentDurableOperationId.compareAndSet(operationId, null)) {
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

    internal fun completeProcessingHandoffSettlement(): Boolean =
        pendingProcessingHandoffSettlement.compareAndSet(true, false)

    internal fun pendingDurableSettlementId(): String? = pendingDurableSettlementId.get()

    internal fun completeDurableSettlement(operationId: String): Boolean {
        if (!pendingDurableSettlementId.compareAndSet(operationId, null)) return false
        processingAttemptId.compareAndSet(operationId, null)
        return true
    }

    /**
     * Releases the top-level lease only when a borrowed processing sublease has
     * already settled its durable owner. A failed ProcessingAttempt clear keeps
     * the sublease attached so outer pipeline cleanup cannot create an ownerless
     * current-runtime marker.
     */
    internal fun releaseIfProcessingSettled(): Boolean {
        if (pendingPublicExportSettlement.get() != null) return false
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
