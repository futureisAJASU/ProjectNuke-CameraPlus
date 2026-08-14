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
    internal val jobDir: File,
    internal val operationLease: JobOperationLease?,
    internal val ownsOperationLease: Boolean
) {
    private val released = AtomicBoolean(false)

    internal fun release() {
        if (released.get()) return
        val cleared = KeplerJobMetadata.clearActiveOperation(jobDir, id)
        if (!cleared && KeplerJobMetadata.isCurrentActiveOperation(jobDir, id)) {
            // A failed metadata write leaves the durable owner in place. Keep both
            // the sublease and its top-level lease so another mutation cannot overlap it.
            return
        }
        if (!released.compareAndSet(false, true)) return
        operationLease?.releaseProcessingAttempt(id)
        if (ownsOperationLease) operationLease?.release()
    }
}

internal class ProcessingAlreadyActiveException(jobDir: File) :
    IllegalStateException("Processing is already active for ${jobDir.absolutePath}")

internal fun ProcessingAttempt.releaseOwnedLease() = release()

/**
 * Reads only the current processing attempt's durable required-output claim.
 * A previous final pathname is not sufficient evidence for this predicate.
 */
internal fun currentProcessingAttemptHasRequiredOutputClaim(
    jobDir: File,
    expectedAttemptId: String? = null
): Boolean {
    val job = try {
        KeplerJobMetadata.read(jobDir)
    } catch (failure: Error) {
        throw failure
    } catch (_: Exception) {
        return false
    }
    val attemptId = job.optString("processingAttemptId").takeIf { it.isNotBlank() }
        ?: return false
    if (expectedAttemptId != null && attemptId != expectedAttemptId) return false
    if (!job.optBoolean("processingOutputCommitted", false) ||
        job.optString("processingArtifactClaimAttemptId") != attemptId
    ) return false
    val mode = job.optString("processingMode").uppercase()
    val jobType = job.optString("jobType").uppercase()
    val claimKey = when {
        mode == "CLASSIC_RAW" || jobType == "RAW" || jobType == "RAW_NIGHT_FUSION" -> "mergedRawFile"
        mode == "SUPER_RESOLUTION" || jobType.contains("SUPER") -> "superResolutionOutputFile"
        mode == "CLASSIC_YUV" || mode == "SINGLE_FRAME" ||
            jobType == "YUV_NIGHT_FUSION" || jobType == "YUV_SINGLE_FRAME" -> "finalFile"
        else -> return false
    }
    val finalName = job.optString(claimKey).takeIf { it.isNotBlank() } ?: return false
    return NoFollowFileSystem.resolveDirectChildResult(jobDir, finalName, requireFile = true) is
        NoFollowInspection.Present
}

/**
 * Processing callers use the lease's last exact attempt identity because the
 * ProcessingAttempt sublease is released before an outer terminal callback sees
 * an exceptional return. This prevents an old durable result from being counted
 * when a new attempt failed before it published its own metadata.
 */
internal fun requiredOutputCommittedAfterProcessing(
    jobDir: File,
    operationLease: JobOperationLease? = null
): Boolean {
    if (operationLease == null) return currentProcessingAttemptHasRequiredOutputClaim(jobDir)
    val attemptId = operationLease.lastProcessingAttemptId() ?: return false
    return currentProcessingAttemptHasRequiredOutputClaim(jobDir, expectedAttemptId = attemptId)
}

internal fun currentProcessingAttemptHasRequiredOutputClaimForLease(
    jobDir: File,
    operationLease: JobOperationLease?
): Boolean = operationLease?.lastProcessingAttemptId()?.let { attemptId ->
    currentProcessingAttemptHasRequiredOutputClaim(jobDir, expectedAttemptId = attemptId)
} == true

private val COMMON_PROCESSING_ATTEMPT_KEYS = setOf(
    "pipelineFailed",
    "pipelineFailureSource",
    "pipelineFailureType",
    "pipelineFailureMessage",
    "processingFinishedAt",
    "processingOutputCommitted",
    "processingArtifactClaimAttemptId",
    "postCommitCancellationRequested",
    "processingStageAttemptId"
)

internal fun beginProcessingAttempt(
    jobDir: File,
    mode: String,
    additionalOwnedKeys: Set<String> = emptySet(),
    operationLease: JobOperationLease? = null
): ProcessingAttempt {
    // An existing process-local owner is the explicit internal concurrency proof. Let the
    // lease/sublease checks below return the established ProcessingAlreadyActive outcome;
    // a durable owner with no local proof still goes through the restart mutation gate.
    val ownsLease = operationLease == null
    val lease = operationLease ?: if (KeplerJobMetadata.isOperationActive(jobDir)) {
        KeplerJobMetadata.acquireOperation(jobDir)
    } else {
        KeplerJobMetadata.acquireRecoveryCheckedOperation(
            jobDir,
            JobRecoveryMutationIntent.PROCESSING_START,
            consumesProcessingHandoff = true
        )
    }
        ?: throw ProcessingAlreadyActiveException(jobDir)
    check(KeplerJobMetadata.isOperationOwner(jobDir, lease)) {
        "Processing operation lease is not owned by this attempt"
    }
    val attempt = ProcessingAttempt(
        id = UUID.randomUUID().toString(),
        startedAt = System.currentTimeMillis(),
        mode = mode,
        jobDir = jobDir,
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
            job.remove(PROCESSING_HANDOFF_RUNTIME_SESSION_ID)
            job.remove(PROCESSING_HANDOFF_OPERATION_ID)
            job.remove(PROCESSING_HANDOFF_KIND)
            job.remove(PROCESSING_HANDOFF_CREATED_AT)
            job.put("processingAttemptId", attempt.id)
                .put("processingStartedAt", attempt.startedAt)
                .put("processingMode", attempt.mode)
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, attempt.id)
                .put(ACTIVE_OPERATION_KIND, processingOperationKind(attempt.mode).name)
                .put(ACTIVE_OPERATION_STARTED_AT, attempt.startedAt)
                .put(ACTIVE_OPERATION_UPDATED_AT, attempt.startedAt)
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
    KeplerJobMetadata.withJobLock(jobDir) {
        val scan = ProcessingArtifactJournal.scan(jobDir)
        check(scan.invalidFiles.isEmpty()) {
            "Invalid processing journal evidence prevents claim acknowledgement"
        }
        val authoritativeJournals = scan.validJournals.asSequence()
            .map { it.second }
        .filter {
            isUnresolvedAuthoritativeProcessingJournal(it) &&
                it.processingAttemptId == attempt.id && it.claimKey == artifactKey
        }
        .toList()
    check(authoritativeJournals.size == 1) {
        throw ProcessingArtifactClaimConflictException(
            "Expected exactly one unresolved processing artifact journal for claim=$artifactKey, found=${authoritativeJournals.size}"
        )
    }
    val journal = authoritativeJournals.single()
    check(journal.adoptedResult == "NEW_FINAL" && journal.state == ProcessingArtifactJournalState.ADOPTED) {
        throw ProcessingArtifactClaimConflictException(
            "Processing artifact claim is not ready for acknowledgement: ${journal.state}"
        )
    }
    check(journal.finalName == artifactFile.name) {
        throw ProcessingArtifactClaimConflictException(
            "Processing artifact claim final mismatch for claim=$artifactKey: expected=${journal.finalName}, actual=${artifactFile.name}"
        )
    }
        updateForProcessingAttempt(jobDir, attempt) { job ->
            job.put(artifactKey, artifactFile.name)
                .put("processingOutputCommitted", true)
                .put("processingArtifactClaimAttemptId", attempt.id)
            appendProcessingSettlement(job, artifactFile, "ADOPTED_FINAL", "ADOPTED", null)
        }
        journal.transition(jobDir, ProcessingArtifactJournalState.JOB_CLAIM_PERSISTED)
        val temp = NoFollowFileSystem.resolveDirectChild(jobDir, journal.tempName, requireFile = true)
        val prior = NoFollowFileSystem.resolveDirectChild(jobDir, journal.priorName, requireFile = true)
        if (temp == null && prior == null) {
            journal.transition(jobDir, ProcessingArtifactJournalState.SETTLED, adoptedResultOverride = "NEW_FINAL")
                .deleteIfOwned(jobDir)
        }
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
