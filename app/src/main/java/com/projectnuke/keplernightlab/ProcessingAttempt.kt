package com.projectnuke.keplernightlab

import java.io.File
import java.util.UUID

internal data class ProcessingAttempt(
    val id: String,
    val startedAt: Long,
    val mode: String,
    internal val operationLease: JobOperationLease?,
    internal val ownsOperationLease: Boolean
)

internal class ProcessingAlreadyActiveException(jobDir: File) :
    IllegalStateException("Processing is already active for ${jobDir.absolutePath}")

internal fun ProcessingAttempt.releaseOwnedLease() {
    if (ownsOperationLease) operationLease?.release()
}

private val COMMON_PROCESSING_ATTEMPT_KEYS = setOf(
    "pipelineFailed",
    "pipelineFailureSource",
    "pipelineFailureType",
    "pipelineFailureMessage",
    "processingFinishedAt",
    "processingOutputCommitted",
    "postCommitCancellationRequested"
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
    }
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
