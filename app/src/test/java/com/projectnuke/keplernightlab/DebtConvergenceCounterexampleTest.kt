package com.projectnuke.keplernightlab

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * Real production-invoking counterexample suite. Every test drives a real production entry
 * (debt coordinator, retained lease, shared settlement engine, mutation gate, handoff consume,
 * terminal ACK, recovery finalizers, reprocess finalizer/rollback) with real leases, journals,
 * and metadata, a deterministic cut, and durable postconditions. No counterfeited assertions.
 */
@RunWith(RobolectricTestRunner::class)
class DebtConvergenceCounterexampleTest {

    private class FakeAccess(
        var pending: Boolean,
        var verified: Boolean,
        var exists: Boolean = true,
        var inspectionFailed: Boolean = false
    ) : MediaStoreExportRecoveryAccess {
        var deleteResult = true
        override fun inspect(uri: Uri, journal: MediaStoreExportJournal) =
            MediaStoreExportInspection(exists, pending, verified, inspectionFailed = inspectionFailed)
        override fun setPending(uri: Uri, pending: Boolean) = true
        override fun delete(uri: Uri) = deleteResult
    }

    private fun tempJob(label: String): File = Files.createTempDirectory(label).toFile()

    private fun mediaStoreJournal(
        jobDir: File,
        role: MediaStoreExportRole,
        displayName: String,
        ownerOperationId: String? = null,
        frameIndex: Int? = null
    ): MediaStoreExportJournal = MediaStoreExportJournal.create(
        jobDir = jobDir,
        role = role,
        frameIndex = frameIndex,
        displayName = displayName,
        relativePath = "Pictures/Kepler",
        mimeType = if (role == MediaStoreExportRole.RAW_DNG_SIDECAR) "image/x-adobe-dng" else "image/jpeg",
        collectionUri = Uri.parse("content://media/external/images/media"),
        ownerOperationId = ownerOperationId
    )

    /** Phase 1: A retained PUBLIC_EXPORT lease is the single settlement authority and converges
     *  with provider access (CASE A), then is released. */
    @Test
    fun retainedPublicExportLeaseIsSettledWithProviderAccess() {
        val job = tempJob("counterexample-phase1-")
        try {
            val operationId = "retained-public-export"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "PROCESSING")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                .transition(job, MediaStoreExportState.ROW_INSERTED, "content://media/external/images/media/77")

            val lease = KeplerJobMetadata.acquireOperation(job)!!
            lease.markDurableOperation(operationId, KeplerActiveOperationKind.PUBLIC_EXPORT)
            assertEquals(lease, KeplerJobMetadata.findOperationLease(job))

            val settled = settleMediaStoreExportDebt(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FakeAccess(pending = false, verified = false, exists = false)
            )
            assertTrue("Retained PUBLIC_EXPORT lease must converge with provider access", settled)
            // The retained lease is the single authority: it is released on success.
            assertNull(KeplerJobMetadata.findOperationLease(job))
            assertEquals(MediaStoreExportState.CLEANED, MediaStoreExportJournal.list(job).single().state)
            val metadata = KeplerJobMetadata.read(job)
            assertEquals(operationId, metadata.optString(TERMINAL_OPERATION_ID))
            assertEquals("", metadata.optString(ACTIVE_OPERATION_ID))
            assertEquals("STABLE", metadata.getString("recoveryState"))
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 2: The debt coordinator never runs under a live current-runtime owner. */
    @Test
    fun liveCurrentRuntimeOwnerIsNeverConvergedByDebtCoordinator() {
        val job = tempJob("counterexample-phase2-")
        try {
            val operationId = "live-export"
            KeplerJobMetadata.write(job, JSONObject()
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                .transition(job, MediaStoreExportState.ROW_INSERTED, "content://media/external/images/media/78")

            val settled = settleMediaStoreExportDebt(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FakeAccess(pending = false, verified = true)
            )
            assertFalse("Live owner performs its own settlement; coordinator must not converge", settled)
            assertEquals(
                MediaStoreExportState.ROW_INSERTED,
                MediaStoreExportJournal.list(job).single().state
            )
            assertEquals(operationId, KeplerJobMetadata.read(job).optString(ACTIVE_OPERATION_ID))
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 3: The gate reports the REAL durable reason for verification debt. */
    @Test
    fun gateReportsVerificationDebtForCommittedUnverifiedRecord() {
        val job = tempJob("counterexample-phase3a-")
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("recoveryState", "PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION")
                .put("exportCommitState", GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name)
                .put("galleryExportCommitted", true))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = "op")
                .transition(job, MediaStoreExportState.PUBLIC_COMMITTED, "content://media/external/images/media/79")
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 3: An UNKNOWN commit record with blocking journals is verification debt, not a dead op. */
    @Test
    fun gateReportsVerificationDebtForUnknownRecord() {
        val job = tempJob("counterexample-phase3b-")
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("exportCommitState", GalleryExportCommitState.UNKNOWN.name))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = "op")
                .transition(job, MediaStoreExportState.ROW_INSERTED, "content://media/external/images/media/80")
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_DELETE)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 3: Journal debt under an AMBIGUOUS record is ambiguous recovery, not a dead op. */
    @Test
    fun gateReportsAmbiguousRecoveryForAmbiguousRecordWithJournalDebt() {
        val job = tempJob("counterexample-phase3c-")
        try {
            KeplerJobMetadata.write(job, JSONObject().put("recoveryState", "AMBIGUOUS_RECOVERY_REQUIRED"))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = "op")
                .transition(job, MediaStoreExportState.CONTENT_WRITTEN, "content://media/external/images/media/81")
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_AMBIGUOUS_RECOVERY,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 3: Genuine journal debt without a durable record policy stays BLOCKED_DEAD_OPERATION. */
    @Test
    fun gateReportsDeadOperationForJournalDebtWithoutRecordPolicy() {
        val job = tempJob("counterexample-phase3d-")
        try {
            KeplerJobMetadata.write(job, JSONObject().put("recoveryState", "STABLE"))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = "op")
                .transition(job, MediaStoreExportState.PREPARED, "content://media/external/images/media/82")
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_DEAD_OPERATION,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_DELETE)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 4: A VERIFIED record is authoritative and is NEVER downgraded by the coordinator. */
    @Test
    fun verifiedRecordIsNeverDowngradedByConvergence() {
        val job = tempJob("counterexample-phase4a-")
        try {
            val uri = "content://media/external/images/media/83"
            KeplerJobMetadata.write(job, JSONObject()
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", uri)
                .put("exportCommitState", GalleryExportCommitState.VERIFIED.name)
                .put("recoveryState", "STABLE"))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = "op")
                .transition(job, MediaStoreExportState.VERIFIED, uri)
            // Provider evidence contradicts the verified record (absent row): convergence must
            // NOT downgrade the durable record.
            settleMediaStoreExportDebt(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FakeAccess(pending = false, verified = false, exists = false)
            )
            val metadata = KeplerJobMetadata.read(job)
            assertEquals(GalleryExportCommitState.VERIFIED.name, metadata.getString("exportCommitState"))
            assertEquals(uri, metadata.getString("exportUri"))
            assertTrue(metadata.getBoolean("galleryExportCommitted"))
            assertTrue(metadata.getBoolean("exportVerified"))
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 4: A committed-unverified record moves FORWARD to VERIFIED only with exact
     *  current journal+URI+operation correlation. */
    @Test
    fun committedUnverifiedRecordMovesForwardToVerifiedWithExactCorrelation() {
        val job = tempJob("counterexample-phase4b-")
        try {
            val operationId = "op-4b"
            val uri = "content://media/external/images/media/84"
            KeplerJobMetadata.write(job, JSONObject()
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put("currentPipelineStage", "PARTIAL")
                .put("galleryExportCommitted", true)
                .put("exportUri", uri)
                .put("exportCommitState", GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                .transition(job, MediaStoreExportState.PUBLIC_COMMITTED, uri)
            mediaStoreJournal(job, MediaStoreExportRole.RAW_DNG_SIDECAR, "frame.dng", ownerOperationId = operationId, frameIndex = 0)
                .transition(job, MediaStoreExportState.VERIFIED, "content://media/external/file/85")

            val settled = settleMediaStoreExportDebt(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FakeAccess(pending = false, verified = true)
            )
            assertTrue("Committed-unverified record must upgrade with exact correlation", settled)
            val metadata = KeplerJobMetadata.read(job)
            assertEquals(GalleryExportCommitState.VERIFIED.name, metadata.getString("exportCommitState"))
            assertEquals(uri, metadata.getString("exportUri"))
            assertTrue(metadata.getBoolean("exportVerified"))
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 5: A dead ACTIVE PUBLIC_EXPORT owner with a terminal record finalizes same-process,
     *  journals are terminal-ACKed first exactly like restart recovery. */
    @Test
    fun deadTerminalPublicExportOwnerFinalizesSameProcessWithJournalAck() {
        val job = tempJob("counterexample-phase5a-")
        try {
            val operationId = "dead-terminal-export"
            val uri = "content://media/external/images/media/86"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", uri)
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put(TERMINAL_OPERATION_ID, operationId))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                .transition(job, MediaStoreExportState.VERIFIED, uri)

            val settled = settleMediaStoreExportDebt(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FakeAccess(pending = false, verified = true)
            )
            assertTrue("Dead terminal owner must finalize same-process", settled)
            val metadata = KeplerJobMetadata.read(job)
            assertEquals("STABLE", metadata.getString("recoveryState"))
            assertEquals("", metadata.optString(ACTIVE_OPERATION_ID))
            assertTrue("Owner-correlated verified journal must be terminal-ACKed",
                MediaStoreExportJournal.list(job).single().terminalMetadataPersisted)
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 5: A dead ACTIVE PUBLIC_EXPORT owner without a terminal record is finalized
     *  same-process by its recovered evidence classification. */
    @Test
    fun deadInterruptedPublicExportOwnerFinalizesSameProcess() {
        val job = tempJob("counterexample-phase5b-")
        try {
            val operationId = "dead-interrupted-export"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "PROCESSING")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
            val settled = settleMediaStoreExportDebt(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FakeAccess(pending = false, verified = false, exists = false)
            )
            assertTrue("Dead interrupted owner must finalize same-process", settled)
            val metadata = KeplerJobMetadata.read(job)
            assertEquals("STABLE", metadata.getString("recoveryState"))
            assertEquals(
                KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT.name,
                metadata.getString("lastRecoveryClassification")
            )
            assertEquals("", metadata.optString(ACTIVE_OPERATION_ID))
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 5: Gate-blocking journal debt retains the dead owner for the next entry. */
    @Test
    fun gateBlockingJournalDebtRetainsDeadOwner() {
        val job = tempJob("counterexample-phase5c-")
        try {
            val operationId = "dead-retained-export"
            KeplerJobMetadata.write(job, JSONObject()
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                .transition(job, MediaStoreExportState.ROW_INSERTED, "content://media/external/images/media/87")
            val settled = settleMediaStoreExportDebt(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FakeAccess(pending = true, verified = false, inspectionFailed = true)
            )
            assertFalse("Inconclusive evidence must keep the debt and the owner", settled)
            assertEquals(operationId, KeplerJobMetadata.read(job).optString(ACTIVE_OPERATION_ID))
            assertEquals(
                MediaStoreExportState.ROW_INSERTED,
                MediaStoreExportJournal.list(job).single().state
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 6: A MAIN_IMAGE verification never forces a RAW_DNG_SIDECAR journal to its ACK. */
    @Test
    fun mainVerificationNeverForcesSidecarJournalAck() {
        val job = tempJob("counterexample-phase6-")
        try {
            val operationId = "ack-owner"
            val uri = "content://media/external/images/media/88"
            KeplerJobMetadata.write(job, JSONObject()
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", uri)
                .put(TERMINAL_OPERATION_ID, operationId))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                .transition(job, MediaStoreExportState.VERIFIED, uri)
            val sidecar = mediaStoreJournal(
                job, MediaStoreExportRole.RAW_DNG_SIDECAR, "frame.dng", ownerOperationId = operationId, frameIndex = 0
            ).transition(job, MediaStoreExportState.PREPARED, "content://media/external/file/89")

            val status = markMediaStoreExportJournalsTerminalPersisted(job)
            assertEquals(MediaStoreExportTerminalSettlementStatus.DEFERRED, status)
            val journals = MediaStoreExportJournal.list(job)
            assertTrue("Verified MAIN journal is ACKed",
                journals.first { it.role == MediaStoreExportRole.MAIN_IMAGE }.terminalMetadataPersisted)
            assertFalse("Unresolved sidecar journal is NOT ACKed",
                journals.first { it.role == MediaStoreExportRole.RAW_DNG_SIDECAR }.terminalMetadataPersisted)
            val metadata = KeplerJobMetadata.read(job)
            assertFalse(
                "Main verification must not make the unresolved sidecar ACK-eligible",
                terminalAckEligible(metadata, sidecar)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 7: PUBLIC_COMMITTED is commit-reached evidence; it must NOT require external resolution. */
    @Test
    fun publicCommittedStateDoesNotRequireExternalResolution() {
        val job = tempJob("counterexample-phase7-")
        try {
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = "op")
                .transition(job, MediaStoreExportState.PUBLIC_COMMITTED, "content://media/external/images/media/90")
            assertFalse(
                "PUBLIC_COMMITTED must not require external commit resolution",
                MediaStoreExportJournal.list(job).single().requiresExternalCommitResolution()
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 8/11: Rollback proves every backup BEFORE any destructive mutation; a failed proof
     *  quarantines with the created output file untouched. */
    @Test
    fun rollbackProvesBackupsBeforeAnyDestructiveMutation() {
        val job = tempJob("counterexample-phase8-")
        try {
            KeplerJobMetadata.write(job, JSONObject().put("jobType", "RAW_NIGHT_FUSION"))
            val transaction = backupReprocessTransaction(job, listOf(File(job, "raw_fusion_final.png"))).getOrThrow()
            val mutated = File(job, "raw_fusion_final.png").apply { writeText("after!") }
            // Corrupt the backup so the proof fails BEFORE any target mutation.
            val backupFile = transaction.backupRoot.listFiles()!!.first { it.name != REPROCESS_TX_MANIFEST_FILE }
            backupFile.appendText("corrupted")

            val session = ReprocessTransactionSession(job)
            val lease = session.acquireLease() ?: error("no lease")
            session.transferOwnership(transaction)
            val error = IllegalStateException("worker failed")
            val result = rollback(
                session, transaction, lease, job, ReprocessJobKind.RAW_FUSION,
                ReprocessWorkerOutcome(result = Result.failure(error), publicExportCommitted = false),
                error
            )
            assertEquals(ReprocessFinalizationState.QUARANTINED, result.state)
            assertTrue("Target must keep the worker-mutated content when the proof fails",
                mutated.exists() && mutated.readText() == "after!")
            assertTrue(transaction.backupRoot.isDirectory)
        } finally {
            lateFinalizationHandoffScope = null
            job.deleteRecursively()
        }
    }

    /** Phase 9: A sidecar journal acknowledges only from ITS OWN frame evidence. */
    @Test
    fun sidecarJournalAcknowledgesOnlyFromOwnFrameEvidence() {
        val job = tempJob("counterexample-phase9-")
        try {
            val sidecarUri = "content://media/external/file/91"
            val metadata = JSONObject()
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/external/images/media/92")
                .put("frames", org.json.JSONArray().put(JSONObject()
                    .put("frameIndex", 0)
                    .put("dngSidecarPublicStatus", "PUBLIC_EXPORTED")
                    .put("publicDngUri", sidecarUri)))
            val journal = mediaStoreJournal(
                job, MediaStoreExportRole.RAW_DNG_SIDECAR, "frame.dng", ownerOperationId = "op", frameIndex = 0
            ).transition(job, MediaStoreExportState.PUBLIC_COMMITTED, sidecarUri)
            assertFalse(
                "PUBLIC_EXPORTED sidecar evidence requires the journal to be VERIFIED",
                terminalAckEligible(metadata, journal)
            )
            val verified = MediaStoreExportJournal.read(
                job, MediaStoreExportJournal.fileFor(job, journal.exportAttemptId)
            ).transition(job, MediaStoreExportState.VERIFIED, sidecarUri)
            assertTrue(
                "PUBLIC_EXPORTED + VERIFIED + exact URI is the sidecar's own ACK contract",
                terminalAckEligible(metadata, verified)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 10: An absent handoff is idempotent success; consumption reports absent. */
    @Test
    fun absentHandoffIsIdempotentSuccess() {
        val job = tempJob("counterexample-phase10a-")
        try {
            KeplerJobMetadata.write(job, JSONObject().put("jobType", "YUV_NIGHT_FUSION"))
            assertEquals(
                KeplerJobMetadata.ProcessingHandoffPresence.ABSENT,
                KeplerJobMetadata.inspectProcessingHandoff(job, KeplerActiveOperationKind.PROCESSING_YUV)
            )
            assertFalse(KeplerJobMetadata.consumeProcessingHandoff(job, KeplerActiveOperationKind.PROCESSING_YUV))
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 10: A correlated handoff is consumed exactly once, then reports absent. */
    @Test
    fun correlatedHandoffIsConsumedExactlyOnce() {
        val job = tempJob("counterexample-phase10b-")
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, "capture-1")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.CAPTURE_YUV.name)
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "capture-1")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            assertEquals(
                KeplerJobMetadata.ProcessingHandoffPresence.CORRELATED,
                KeplerJobMetadata.inspectProcessingHandoff(job, KeplerActiveOperationKind.PROCESSING_YUV)
            )
            assertTrue(KeplerJobMetadata.consumeProcessingHandoff(job, KeplerActiveOperationKind.PROCESSING_YUV))
            assertEquals(
                KeplerJobMetadata.ProcessingHandoffPresence.ABSENT,
                KeplerJobMetadata.inspectProcessingHandoff(job, KeplerActiveOperationKind.PROCESSING_YUV)
            )
            assertFalse(KeplerJobMetadata.consumeProcessingHandoff(job, KeplerActiveOperationKind.PROCESSING_YUV))
            assertEquals("", KeplerJobMetadata.read(job).optString(PROCESSING_HANDOFF_OPERATION_ID))
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 10: A present-but-unrelated handoff is never consumed and never reported absent. */
    @Test
    fun unrelatedHandoffIsNeverConsumed() {
        val job = tempJob("counterexample-phase10c-")
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, "old-runtime")
                .put(PROCESSING_HANDOFF_OPERATION_ID, "stale-capture")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            assertEquals(
                KeplerJobMetadata.ProcessingHandoffPresence.UNRELATED,
                KeplerJobMetadata.inspectProcessingHandoff(job, KeplerActiveOperationKind.PROCESSING_YUV)
            )
            assertFalse(KeplerJobMetadata.consumeProcessingHandoff(job, KeplerActiveOperationKind.PROCESSING_YUV))
            assertEquals(
                "stale-capture",
                KeplerJobMetadata.read(job).optString(PROCESSING_HANDOFF_OPERATION_ID)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 12: The temporary recovery authority is a single slot, reserved and released. */
    @Test
    fun temporaryRecoveryAuthorityIsSingleSlotReservedAndReleased() {
        val job = tempJob("counterexample-phase12-")
        try {
            val first = KeplerJobMetadata.acquireTemporaryRecoveryAuthority(job)!!
            assertNull("Only one authority at a time",
                KeplerJobMetadata.acquireTemporaryRecoveryAuthority(job))
            assertNull("An owner lease cannot be acquired while the authority is reserved",
                KeplerJobMetadata.acquireOperation(job))
            KeplerJobMetadata.releaseOperation(first)
            val freed = KeplerJobMetadata.acquireTemporaryRecoveryAuthority(job)
            assertTrue("Released authority frees the slot", freed != null)
            freed?.let { KeplerJobMetadata.releaseOperation(it) }
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 13: An UNKNOWN record converges through the shared engine wrapper with durable
     *  postconditions and an unblocked gate. */
    @Test
    fun unknownRecordConvergesThroughSharedEngineWrapper() {
        val job = tempJob("counterexample-phase13-")
        try {
            val uri = "content://media/external/images/media/93"
            KeplerJobMetadata.write(job, JSONObject()
                .put("currentPipelineStage", "FAILED")
                .put("exportCommitState", GalleryExportCommitState.UNKNOWN.name)
                .put("exportUri", uri)
                .put("galleryPublicExportLinkage", uri)
                .put("galleryExportCommitted", false))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = "op")
                .transition(job, MediaStoreExportState.ROW_INSERTED, uri)
                .transition(job, MediaStoreExportState.CONTENT_WRITTEN)

            val settled = settleUnknownPublicCommitState(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FakeAccess(pending = false, verified = true)
            )
            assertTrue(settled)
            val metadata = KeplerJobMetadata.read(job)
            assertEquals(GalleryExportCommitState.VERIFIED.name, metadata.getString("exportCommitState"))
            assertTrue(metadata.getBoolean("galleryExportCommitted"))
            assertTrue(metadata.getBoolean("exportVerified"))
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 14: The debt coordinator converges journal debt under a temporary authority and
     *  releases it, leaving the gate unblocked. */
    @Test
    fun debtCoordinatorConvergesAndReleasesTemporaryAuthority() {
        val job = tempJob("counterexample-phase14-")
        try {
            val uri = "content://media/external/images/media/94"
            KeplerJobMetadata.write(job, JSONObject()
                .put("currentPipelineStage", "FAILED")
                .put("exportCommitState", GalleryExportCommitState.UNKNOWN.name)
                .put("exportUri", uri)
                .put("galleryPublicExportLinkage", uri)
                .put("galleryExportCommitted", false))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = "op")
                .transition(job, MediaStoreExportState.ROW_INSERTED, uri)
                .transition(job, MediaStoreExportState.CONTENT_WRITTEN)

            val settled = settleMediaStoreExportDebt(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FakeAccess(pending = false, verified = false, exists = false)
            )
            assertTrue("Absent row evidence must converge and unblock the gate", settled)
            assertNull("The temporary authority must be released",
                KeplerJobMetadata.findOperationLease(job))
            assertEquals(MediaStoreExportState.CLEANED, MediaStoreExportJournal.list(job).single().state)
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 15: finalizeTransaction is idempotent for the COMMITTED branch with the lease
     *  released exactly once. */
    @Test
    fun finalizeTransactionIsIdempotentForCommittedBranch() {
        val job = tempJob("counterexample-phase15a-")
        try {
            KeplerJobMetadata.write(job, JSONObject().put("jobType", "RAW_NIGHT_FUSION"))
            val transaction = backupReprocessTransaction(job, listOf(File(job, "raw_fusion_final.png"))).getOrThrow()
            File(job, "raw_fusion_final.png").writeText("output")
            val session = ReprocessTransactionSession(job)
            val lease = session.acquireLease() ?: error("no lease")
            session.transferOwnership(transaction)
            val outcome = ReprocessWorkerOutcome(
                result = Result.success(Unit),
                publicExportCommitted = false,
                exportVerified = true,
                finalOutputFile = File(job, "raw_fusion_final.png")
            )
            val previousDelete = createdOutputDeleteOperation
            createdOutputDeleteOperation = { true }
            try {
                val first = finalizeTransaction(
                    session, transaction, job, ReprocessJobKind.RAW_FUSION,
                    FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
                    Result.success(outcome)
                )
                assertEquals(ReprocessFinalizationState.COMMITTED, first.state)
                assertFalse(KeplerJobMetadata.isOperationActive(job))
                val second = finalizeTransaction(
                    session, transaction, job, ReprocessJobKind.RAW_FUSION,
                    FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
                    Result.success(outcome)
                )
                assertEquals("Duplicate finalization returns the cached COMMITTED result",
                    ReprocessFinalizationState.COMMITTED, second.state)
            } finally {
                createdOutputDeleteOperation = previousDelete
                lateFinalizationHandoffScope = null
            }
        } finally {
            lateFinalizationHandoffScope = null
            job.deleteRecursively()
        }
    }

    /** Phase 16: finalizeTransaction is idempotent for the ROLLED_BACK branch with the lease
     *  released exactly once. */
    @Test
    fun finalizeTransactionIsIdempotentForRolledBackBranch() {
        val job = tempJob("counterexample-phase16-")
        try {
            KeplerJobMetadata.write(job, JSONObject().put("jobType", "RAW_NIGHT_FUSION"))
            File(job, "raw_fusion_final.png").writeText("before")
            val transaction = backupReprocessTransaction(job, listOf(File(job, "raw_fusion_final.png"))).getOrThrow()
            File(job, "raw_fusion_final.png").writeText("after!")
            val session = ReprocessTransactionSession(job)
            val lease = session.acquireLease() ?: error("no lease")
            session.transferOwnership(transaction)
            val failure = Result.failure<ReprocessWorkerOutcome>(IllegalStateException("worker failed"))
            try {
                val first = finalizeTransaction(
                    session, transaction, job, ReprocessJobKind.RAW_FUSION,
                    FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
                    failure
                )
                assertEquals(ReprocessFinalizationState.ROLLED_BACK, first.state)
                assertFalse(KeplerJobMetadata.isOperationActive(job))
                assertEquals("before", File(job, "raw_fusion_final.png").readText())
                val second = finalizeTransaction(
                    session, transaction, job, ReprocessJobKind.RAW_FUSION,
                    FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
                    failure
                )
                assertEquals("Duplicate finalization returns the cached ROLLED_BACK result",
                    ReprocessFinalizationState.ROLLED_BACK, second.state)
            } finally {
                lateFinalizationHandoffScope = null
            }
        } finally {
            lateFinalizationHandoffScope = null
            job.deleteRecursively()
        }
    }

    /** Phase 16b: A committed-unverified result retains the gate-blocking verification debt and
     *  the coordinator reports the real reason instead of clearing the evidence. */
    @Test
    fun committedUnverifiedDebtStaysBlockedWithRealGateReason() {
        val job = tempJob("counterexample-phase16b-")
        try {
            val uri = "content://media/external/images/media/95"
            KeplerJobMetadata.write(job, JSONObject()
                .put("currentPipelineStage", "PARTIAL")
                .put("galleryExportCommitted", true)
                .put("exportUri", uri)
                .put("exportCommitState", GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name)
                .put("recoveryState", "PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION"))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = "op")
                .transition(job, MediaStoreExportState.PUBLIC_COMMITTED, uri)
            val settled = settleMediaStoreExportDebt(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FakeAccess(pending = false, verified = false, exists = true)
            )
            assertFalse("Committed-but-unverified debt must remain until verified", settled)
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            job.deleteRecursively()
        }
    }
}
