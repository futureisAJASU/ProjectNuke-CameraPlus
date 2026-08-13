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

internal enum class JobRecoveryMutationGateOutcome {
    ALLOWED,
    BLOCKED_PROCESSING_CLEANUP,
    BLOCKED_AMBIGUOUS_RECOVERY,
    BLOCKED_PUBLIC_COMMIT_MISSING,
    BLOCKED_EXPORT_VERIFICATION,
    BLOCKED_INVALID_PROCESSING_JOURNAL,
    BLOCKED_REPROCESS_QUARANTINE,
    INSPECTION_FAILED
}

internal class JobRecoveryMutationBlockedException(
    val outcome: JobRecoveryMutationGateOutcome
) : IllegalStateException(
    when (outcome) {
        JobRecoveryMutationGateOutcome.BLOCKED_PROCESSING_CLEANUP -> "이전 처리 작업의 파일 정리가 완료되지 않아 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_AMBIGUOUS_RECOVERY -> "복구되지 않은 작업 증거가 있어 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_PUBLIC_COMMIT_MISSING -> "공개 결과의 커밋 증거가 없어 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION -> "공개 결과를 확인하지 못해 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_INVALID_PROCESSING_JOURNAL -> "처리 복구 기록을 읽을 수 없어 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE -> "복구 중인 작업은 지금 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.INSPECTION_FAILED -> "작업 복구 상태를 확인하지 못해 지금은 작업을 변경할 수 없습니다."
        JobRecoveryMutationGateOutcome.ALLOWED -> ""
    }
)

/** Serializes each job's read-modify-write updates and never truncates a valid job.json. */
object KeplerJobMetadata {
    // Strongly reachable striped locks keep one stable lock choice per job
    // without a GC-sensitive weak table or an unbounded lock-map leak.
    private val _locks = Array(64) { Any() }
    private val operationLeases = ConcurrentHashMap<String, JobOperationLease>()

    /** Narrow lease/metadata test seam: incremented each time a job metadata write is durably
     *  persisted. Tests must save the prior value and restore it in `finally`. Production never reads it. */
    @Volatile
    internal var atomicWriteCount: Int = 0
        private set

    @Volatile
    internal var atomicWriteFailureForTest: Throwable? = null

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

    internal fun inspectRecoveryMutationGate(jobDir: File): JobRecoveryMutationGateOutcome =
        withJobLock(jobDir) {
            try {
                if (isReprocessQuarantined(jobDir)) return@withJobLock JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE
                val scan = ProcessingArtifactJournal.scan(jobDir)
                if (scan.invalidFiles.isNotEmpty()) return@withJobLock JobRecoveryMutationGateOutcome.BLOCKED_INVALID_PROCESSING_JOURNAL
                if (scan.validJournals.any { isUnresolvedAuthoritativeProcessingJournal(it.second) }) {
                    return@withJobLock JobRecoveryMutationGateOutcome.BLOCKED_PROCESSING_CLEANUP
                }
                val job = try {
                    read(jobDir)
                } catch (_: KeplerJobMetadataMissing) {
                    return@withJobLock JobRecoveryMutationGateOutcome.ALLOWED
                }
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

    internal fun requireRecoveryMutationAllowed(jobDir: File) {
        val outcome = inspectRecoveryMutationGate(jobDir)
        if (outcome == JobRecoveryMutationGateOutcome.BLOCKED_PROCESSING_CLEANUP) {
            throw ProcessingCleanupRequiredException()
        }
        if (outcome != JobRecoveryMutationGateOutcome.ALLOWED) throw JobRecoveryMutationBlockedException(outcome)
    }

    fun acquireOperation(jobDir: File): JobOperationLease? {
        val key = jobDir.toPath().toAbsolutePath().normalize().toString()
        val lease = JobOperationLease(key)
        return if (operationLeases.putIfAbsent(key, lease) == null) lease else null
    }

    internal fun hasProcessingCleanupBlocker(jobDir: File): Boolean = runCatching {
        val job = read(jobDir)
        job.optString("recoveryState") == PROCESSING_CLEANUP_REQUIRED ||
            ProcessingArtifactJournal.scan(jobDir).let { scan ->
                scan.invalidFiles.isNotEmpty() || scan.validJournals.any { isUnresolvedAuthoritativeProcessingJournal(it.second) }
            }
    }.getOrDefault(false)

    internal fun recordProcessingCleanupRequired(
        jobDir: File,
        operationId: String?,
        failures: List<String>
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
                .put("lastRecoveryClassification", "LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL")
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
        operationLeases.remove(lease.key, lease)
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
        startedAt: Long = System.currentTimeMillis()
    ): String {
        update(jobDir) { job ->
            job.put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, kind.name)
                .put(ACTIVE_OPERATION_STARTED_AT, startedAt)
                .put(ACTIVE_OPERATION_UPDATED_AT, startedAt)
            job.remove(TERMINAL_OPERATION_ID)
        }
        return operationId
    }

    /** Records the durable capture-to-processing handoff before capture ownership is released. */
    internal fun publishProcessingHandoff(
        jobDir: File,
        captureOperationId: String,
        kind: KeplerActiveOperationKind,
        createdAt: Long = System.currentTimeMillis()
    ): Boolean = runCatching {
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
    }.getOrDefault(false)

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
    internal fun clearActiveOperation(jobDir: File, operationId: String): Boolean = runCatching {
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
        matched
    }.getOrDefault(false)

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
    internal fun finalizeRecoveredTerminalOperation(jobDir: File, operationId: String): Boolean = runCatching {
        var matched = false
        update(jobDir) { job ->
            if (job.optString(ACTIVE_OPERATION_ID) != operationId ||
                job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id ||
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
        recoveryMessage: String
    ): Boolean = runCatching {
        var matched = false
        update(jobDir) { job ->
            if (job.optString(ACTIVE_OPERATION_ID) != operationId ||
                job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id
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

    internal fun finalizeRecoveredProcessingHandoff(jobDir: File): Boolean = runCatching {
        var matched = false
        update(jobDir) { job ->
            if (job.optString(PROCESSING_HANDOFF_RUNTIME_SESSION_ID).isBlank() ||
                job.optString(PROCESSING_HANDOFF_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id
            ) return@update
            matched = true
            job.put("recoveryState", "STABLE")
                .put("lastRecoveryClassification", KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT.name)
                .put("lastRecoveryMessage", "처리가 시작되기 전 이전 실행이 종료되었지만 원본 프레임을 다시 사용할 수 있습니다.")
                .put("recoveredAt", System.currentTimeMillis())
                .put(PROCESSING_HANDOFF_FINALIZED, true)
            job.remove("recoveryMessage")
            job.remove(PROCESSING_HANDOFF_RUNTIME_SESSION_ID)
            job.remove(PROCESSING_HANDOFF_OPERATION_ID)
            job.remove(PROCESSING_HANDOFF_KIND)
            job.remove(PROCESSING_HANDOFF_CREATED_AT)
        }
        matched
    }.getOrDefault(false)

    fun atomicWrite(file: File, text: String) {
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

    internal fun claimProcessingAttempt(attemptId: String): Boolean =
        !released.get() && processingAttemptId.compareAndSet(null, attemptId)

    internal fun releaseProcessingAttempt(attemptId: String): Boolean =
        processingAttemptId.compareAndSet(attemptId, null)

    internal fun isProcessingAttemptOwner(attemptId: String): Boolean =
        !released.get() && processingAttemptId.get() == attemptId

    fun release() {
        if (!released.compareAndSet(false, true)) return
        KeplerJobMetadata.releaseOperation(this)
        KeplerJobMetadata.leaseReleaseCount += 1
    }
}
