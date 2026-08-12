package com.projectnuke.keplernightlab

import org.json.JSONException
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

    fun acquireOperation(jobDir: File): JobOperationLease? {
        val key = jobDir.toPath().toAbsolutePath().normalize().toString()
        val lease = JobOperationLease(key)
        return if (operationLeases.putIfAbsent(key, lease) == null) lease else null
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
        }
        return operationId
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

    fun atomicWrite(file: File, text: String) {
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
