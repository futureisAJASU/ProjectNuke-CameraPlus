package com.projectnuke.keplernightlab

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal data class ProcessingAttempt(
    val id: String,
    val startedAt: Long,
    val mode: String,
    internal val operationLease: JobOperationLease?,
    internal val ownsOperationLease: Boolean
) {
    private val released = AtomicBoolean(false)

    internal fun release() {
        if (!released.compareAndSet(false, true)) return
        operationLease?.releaseProcessingAttempt(id)
        if (ownsOperationLease) operationLease?.release()
    }
}

internal class ProcessingAlreadyActiveException(jobDir: File) :
    IllegalStateException("Processing is already active for ${jobDir.absolutePath}")

internal fun ProcessingAttempt.releaseOwnedLease() = release()

private val COMMON_PROCESSING_ATTEMPT_KEYS = setOf(
    "pipelineFailed",
    "pipelineFailureSource",
    "pipelineFailureType",
    "pipelineFailureMessage",
    "processingFinishedAt",
    "processingOutputCommitted",
    "postCommitCancellationRequested",
    "processingStageAttemptId"
)

internal fun beginProcessingAttempt(
    jobDir: File,
    mode: String,
    additionalOwnedKeys: Set<String> = emptySet(),
    operationLease: JobOperationLease? = null
): ProcessingAttempt {
    val ownsLease = operationLease == null
    val lease = operationLease ?: KeplerJobMetadata.acquireOperation(jobDir)
        ?: throw ProcessingAlreadyActiveException(jobDir)
    check(KeplerJobMetadata.isOperationOwner(jobDir, lease)) {
        "Processing operation lease is not owned by this attempt"
    }
    val attempt = ProcessingAttempt(
        id = UUID.randomUUID().toString(),
        startedAt = System.currentTimeMillis(),
        mode = mode,
        operationLease = lease,
        ownsOperationLease = ownsLease
    )
    if (!lease.claimProcessingAttempt(attempt.id)) {
        if (ownsLease) lease.release()
        throw ProcessingAlreadyActiveException(jobDir)
    }
    if (NoFollowFileSystem.resolveDirectChildResult(jobDir, JOB_JSON_FILE_NAME, requireFile = true) is NoFollowInspection.Absent) {
        try {
            KeplerJobMetadata.write(jobDir, org.json.JSONObject().put("jobType", mode).put("pipeline", mode))
        } catch (failure: Throwable) {
            attempt.releaseOwnedLease()
            throw failure
        }
    }
    try {
        KeplerJobMetadata.update(jobDir) { job ->
        check(KeplerJobMetadata.isOperationOwner(jobDir, lease)) {
            "Processing operation lease is no longer owned"
        }
        check(lease.isProcessingAttemptOwner(attempt.id)) {
            "Processing attempt is no longer authoritative"
        }
        (COMMON_PROCESSING_ATTEMPT_KEYS + additionalOwnedKeys).forEach(job::remove)
            job.put("processingAttemptId", attempt.id)
                .put("processingStartedAt", attempt.startedAt)
                .put("processingMode", attempt.mode)
        }
    } catch (failure: Throwable) {
        attempt.releaseOwnedLease()
        throw failure
    }
    return attempt
}

internal fun updateForProcessingAttempt(
    jobDir: File,
    attempt: ProcessingAttempt,
    mutate: (org.json.JSONObject) -> Unit
) {
    KeplerJobMetadata.update(jobDir) { job ->
        check(job.optString("processingAttemptId") == attempt.id) {
            "Processing attempt is no longer current"
        }
        check(attempt.operationLease?.let { KeplerJobMetadata.isOperationOwner(jobDir, it) } == true) {
            "Processing operation lease is no longer owned"
        }
        check(attempt.operationLease?.isProcessingAttemptOwner(attempt.id) == true) {
            "Processing attempt is no longer authoritative"
        }
        mutate(job)
    }
}

internal fun markProcessingArtifactClaim(
    jobDir: File,
    attempt: ProcessingAttempt,
    artifactKey: String,
    artifactFile: File
) {
    updateForProcessingAttempt(jobDir, attempt) { job ->
        job.put(artifactKey, artifactFile.name)
            .put("processingOutputCommitted", true)
        appendProcessingSettlement(job, artifactFile, "ADOPTED_FINAL", "ADOPTED", null)
    }
}

internal fun recordProcessingArtifactSettlements(
    jobDir: File,
    attempt: ProcessingAttempt,
    settlements: Iterable<ProcessingArtifactSettlementRecord>
) {
    updateForProcessingAttempt(jobDir, attempt) { job ->
        settlements.forEach { settlement ->
            appendProcessingSettlement(
                job,
                settlement.path,
                settlement.role.name,
                settlement.status.name,
                settlement.failure
            )
        }
    }
}

internal fun processingArtifactSettlementObserver(
    jobDir: File,
    attempt: ProcessingAttempt
): (ProcessingArtifactSettlementReport) -> Unit = { report ->
    recordProcessingArtifactSettlements(jobDir, attempt, report.settlements)
}

private fun appendProcessingSettlement(
    job: JSONObject,
    path: File,
    role: String,
    status: String,
    failure: Throwable?
) {
    val records = job.optJSONArray("processingArtifactSettlements") ?: JSONArray()
    if (records.length() >= 64) return
    records.put(
        JSONObject()
            .put("path", path.name)
            .put("role", role)
            .put("status", status)
            .put("failure", failure?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: JSONObject.NULL)
    )
    job.put("processingArtifactSettlements", records)
        .put("processingArtifactSettlementCount", records.length())
}

internal fun markProcessingPostCommitCancellation(
    jobDir: File,
    attempt: ProcessingAttempt,
    skippedOptionalWork: Boolean = true
) {
    updateForProcessingAttempt(jobDir, attempt) { job ->
        job.put("processingOutputCommitted", true)
            .put("postCommitCancellationRequested", true)
            .put("postCommitWorkSkipped", skippedOptionalWork)
    }
}
