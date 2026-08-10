package com.projectnuke.keplernightlab

import java.io.File
import java.util.UUID

internal data class ProcessingAttempt(
    val id: String,
    val startedAt: Long,
    val mode: String
)

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
    additionalOwnedKeys: Set<String> = emptySet()
): ProcessingAttempt {
    val attempt = ProcessingAttempt(UUID.randomUUID().toString(), System.currentTimeMillis(), mode)
    if (NoFollowFileSystem.resolveDirectChildResult(jobDir, JOB_JSON_FILE_NAME, requireFile = true) is NoFollowInspection.Absent) {
        KeplerJobMetadata.write(
            jobDir,
            org.json.JSONObject().put("jobType", mode).put("pipeline", mode)
        )
    }
    KeplerJobMetadata.update(jobDir) { job ->
        (COMMON_PROCESSING_ATTEMPT_KEYS + additionalOwnedKeys).forEach(job::remove)
        job.put("processingAttemptId", attempt.id)
            .put("processingStartedAt", attempt.startedAt)
            .put("processingMode", attempt.mode)
    }
    return attempt
}

internal fun markProcessingArtifactClaim(
    jobDir: File,
    attempt: ProcessingAttempt,
    artifactKey: String,
    artifactFile: File
) {
    KeplerJobMetadata.update(jobDir) { job ->
        check(job.optString("processingAttemptId") == attempt.id) {
            "Processing attempt changed while claiming ${artifactFile.name}"
        }
        job.put(artifactKey, artifactFile.name)
            .put("processingOutputCommitted", true)
    }
}
