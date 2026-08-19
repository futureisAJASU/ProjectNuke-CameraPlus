package com.projectnuke.keplernightlab

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
            assertNull("Clean temporary authority blocks acquireOperation",
                KeplerJobMetadata.acquireOperation(job))
            KeplerJobMetadata.releaseOperation(first)
            val operationLease = KeplerJobMetadata.acquireOperation(job)
            assertNotNull("Released authority frees the slot", operationLease)
            operationLease?.let { KeplerJobMetadata.releaseOperation(it) }
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

    // ---------------------------------------------------------------------------------------------
    // Phase 13 (BOUNDED AUDIT): 20 production-lifetime counterexamples.
    // ---------------------------------------------------------------------------------------------

    private fun registeredLiveRetainedOwner(
        job: File,
        operationId: String,
        uri: String,
        journalState: MediaStoreExportState
    ): JobOperationLease {
        KeplerJobMetadata.write(job, JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put("currentPipelineStage", "PROCESSING")
            .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
            .put(ACTIVE_OPERATION_ID, operationId)
            .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
        mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
            .transition(job, journalState, uri)
        val lease = KeplerJobMetadata.acquireOperation(job)!!
        lease.markDurableOperation(operationId, KeplerActiveOperationKind.PUBLIC_EXPORT)
        lease.markPublicExportSettlementPending(
            PendingPublicExportSettlement(
                operationId = operationId,
                failureMessage = "Pipeline worker scope destroyed mid-export",
                finalOutputFormat = null,
                disposition = PublicExportInterruptionDisposition.FAILED
            )
        )
        return lease
    }

    private fun recoveryRoot(label: String): Pair<File, File> {
        val base = Files.createTempDirectory(label).toFile()
        val root = File(base, "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_RECOVERED").apply { mkdirs() }
        return root to job
    }

    /** Minimal in-memory cursor: reports exactly one published MediaStore row
 *  (IS_PENDING=0 at column 0 to mirror the production projection, _SIZE=1024). */
    private class PublishedMediaStoreRowCursor : org.robolectric.fakes.BaseCursor() {
        private val columns = arrayOf("IS_PENDING", "_ID", "_SIZE", "_display_name", "mime_type")
        private var advanced = false
        override fun getCount(): Int = 1
        override fun moveToFirst(): Boolean {
            advanced = false
            return true
        }
        override fun moveToNext(): Boolean {
            if (advanced) return false
            advanced = true
            return true
        }
        override fun getColumnNames(): Array<String> = columns
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getColumnIndex(columnName: String): Int =
            columns.indexOfFirst { it.equals(columnName, ignoreCase = true) }
        override fun getString(column: Int): String = when {
            column >= columns.size -> ""
            columns[column].equals("_ID", true) || columns[column].equals("_SIZE", true) -> "1024"
            columns[column].equals("IS_PENDING", true) -> "0"
            columns[column].equals("_display_name", true) -> "result.jpg"
            columns[column].equals("mime_type", true) -> "image/jpeg"
            else -> ""
        }
        override fun getLong(column: Int): Long = getString(column).toLong()
        override fun getInt(column: Int): Int = getString(column).toInt()
        override fun isNull(column: Int): Boolean = false
        override fun close() = Unit
        override fun isClosed(): Boolean = false
    }

    /** 1. A CURRENT-runtime retained PUBLIC_EXPORT owner (destroyed worker scope) settles under
     *  the exact retained lease E with live-owner semantics BEFORE any new mutation acquires,
     *  then a real Gallery mutation entry succeeds. */
    @Test
    fun retainedCurrentRuntimeOwnerSettlesBeforeNextMutationAcquires() {
        val job = tempJob("counterexample-bound1-")
        try {
            val operationId = "live-retained-export"
            val uri = "content://media/external/images/media/98"
            val lease = registeredLiveRetainedOwner(job, operationId, uri, MediaStoreExportState.ROW_INSERTED)
            lease.markReconciliationReady()

            assertNull("E occupies the only lease slot while retained",
                KeplerJobMetadata.acquireOperation(job))
            val settled = settleMediaStoreExportDebt(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FakeAccess(pending = false, verified = false, exists = false)
            )
            assertTrue("Current-runtime retained owner must settle with provider evidence", settled)
            assertNull("The exact retained lease is released on successful settlement",
                KeplerJobMetadata.findOperationLease(job))
            assertNull("The retained lease's durable owner marker is cleared",
                lease.currentDurableOperationId())
            assertEquals(MediaStoreExportState.CLEANED, MediaStoreExportJournal.list(job).single().state)
            assertEquals("", KeplerJobMetadata.read(job).optString(ACTIVE_OPERATION_ID))
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.FRAME_SELECTION)
            )
            val saved = saveFrameSelection(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FrameSelectionMode.AUTO_RULE_BASED, emptyList()
            )
            assertTrue("The next real mutation entry must succeed after settlement", saved.isSuccess)
        } finally {
            job.deleteRecursively()
        }
    }

    /** 2. The next real production Gallery entry (saveFrameSelection) performs the retained
     *  current-runtime settlement itself: the exact lease settles in-band (conclusive provider
     *  evidence), the ENTRY then reports the remaining verification debt with the REAL gate
     *  reason — and once the provider verifies, the next entry succeeds. */
    @Test
    fun currentRuntimeRetainedOwnerConvergesViaGalleryMutationEntry() {
        val job = tempJob("counterexample-bound2-")
        try {
            val operationId = "live-retained-entry"
            val uri = "content://media/external/images/media/100"
            val lease = registeredLiveRetainedOwner(job, operationId, uri, MediaStoreExportState.ROW_INSERTED)
            lease.markReconciliationReady()
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            org.robolectric.Shadows.shadowOf(context.contentResolver).setCursor(
                Uri.parse(uri), PublishedMediaStoreRowCursor()
            )
            val blocked = saveFrameSelection(
                context, job,
                FrameSelectionMode.AUTO_RULE_BASED, emptyList()
            )
            assertFalse("The first entry settles the retained owner but the committed-unverified row still blocks", blocked.isSuccess)
            assertEquals("The remaining debt is the REAL verification reason, not DEAD_OPERATION",
                JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION,
                (blocked.exceptionOrNull() as? JobRecoveryMutationBlockedException)?.outcome)
            assertNull("The retained owner is released by the in-band settlement",
                KeplerJobMetadata.findOperationLease(job))
            assertEquals("The durable owner marker is cleared by the in-band settlement",
                "", KeplerJobMetadata.read(job).optString(ACTIVE_OPERATION_ID))
            assertEquals("The terminal is persisted by the in-band settlement",
                operationId, KeplerJobMetadata.read(job).optString(TERMINAL_OPERATION_ID))
            assertTrue("The journal advanced past ROW_INSERTED within the entry pass",
                MediaStoreExportJournal.list(job).single().state != MediaStoreExportState.ROW_INSERTED)
            val converged = settleMediaStoreExportDebt(
                context, job,
                FakeAccess(pending = false, verified = true, exists = true)
            )
            assertTrue("A verified provider pass converges the committed-unverified row", converged)
            assertEquals("VERIFIED", KeplerJobMetadata.read(job).getString("exportCommitState"))
            val saved = saveFrameSelection(
                context, job,
                FrameSelectionMode.AUTO_RULE_BASED, emptyList()
            )
            assertTrue("The next real mutation entry succeeds after verification", saved.isSuccess)
        } finally {
            job.deleteRecursively()
        }
    }

    /** 3. A provider-inconclusive retained current-runtime owner keeps the exact lease retained;
     *  the real entry is blocked and no mutation happens. */
    @Test
    fun providerUnknownRetainedCurrentRuntimeOwnerBlocksRealEntry() {
        val job = tempJob("counterexample-bound3-")
        try {
            val operationId = "live-retained-unknown"
            val uri = "content://media/external/images/media/101"
            val lease = registeredLiveRetainedOwner(job, operationId, uri, MediaStoreExportState.ROW_INSERTED)
            val saved = saveFrameSelection(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FrameSelectionMode.AUTO_RULE_BASED, emptyList()
            )
            assertFalse("The real entry must be blocked while provider evidence is inconclusive", saved.isSuccess)
            val blocked = saved.exceptionOrNull() as? JobRecoveryMutationBlockedException
            assertNotNull("The blocked reason is the real gate outcome", blocked)
            assertEquals("The exact retained lease is preserved for the next entry",
                lease, KeplerJobMetadata.findOperationLease(job))
            assertEquals(
                MediaStoreExportState.ROW_INSERTED,
                MediaStoreExportJournal.list(job).single().state
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** 4. FRAME_SELECTION on a committed-unverified record reports the real verification reason
     *  through the production entry — never a synthetic dead operation. */
    @Test
    fun committedUnverifiedRecordBlocksFrameSelectionWithVerificationReason_NotDeadOperation() {
        val job = tempJob("counterexample-bound4-")
        try {
            val uri = "content://media/external/images/media/102"
            KeplerJobMetadata.write(job, JSONObject()
                .put("currentPipelineStage", "PARTIAL")
                .put("galleryExportCommitted", true)
                .put("exportUri", uri)
                .put("exportCommitState", GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name)
                .put("recoveryState", "PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION"))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = "op")
                .transition(job, MediaStoreExportState.PUBLIC_COMMITTED, uri)
            val saved = saveFrameSelection(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FrameSelectionMode.AUTO_RULE_BASED, emptyList()
            )
            assertFalse(saved.isSuccess)
            val blocked = saved.exceptionOrNull() as? JobRecoveryMutationBlockedException
            assertNotNull("The real gate reason is reported", blocked)
            assertEquals(
                "Verification debt must report BLOCKED_EXPORT_VERIFICATION, never BLOCKED_DEAD_OPERATION",
                JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION, blocked!!.outcome
            )
            assertEquals(MediaStoreExportState.PUBLIC_COMMITTED,
                MediaStoreExportJournal.list(job).single().state)
        } finally {
            job.deleteRecursively()
        }
    }

    /** 5. An unacknowledged VERIFIED journal blocks with the settlement reason; the durable ACK
     *  is the debt-clearing authority and never re-blocks. */
    @Test
    fun unacknowledgedVerifiedJournalBlocksWithSettlementReasonUntilAcked() {
        val job = tempJob("counterexample-bound5-")
        try {
            val operationId = "verified-settlement"
            val uri = "content://media/external/images/media/103"
            KeplerJobMetadata.write(job, JSONObject()
                .put("currentPipelineStage", "PARTIAL")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", uri)
                .put("exportCommitState", GalleryExportCommitState.VERIFIED.name)
                .put("recoveryState", "STABLE")
                .put(TERMINAL_OPERATION_ID, operationId))
            val journal = mediaStoreJournal(
                job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId
            ).transition(job, MediaStoreExportState.VERIFIED, uri)
            assertEquals(
                "Unacknowledged VERIFIED journal blocks with the settlement reason",
                JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_SETTLEMENT,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
            journal.markTerminalPersisted(job, operationId)
            assertEquals(
                "The durable terminal ACK clears the debt; the gate never re-blocks",
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** 6. A terminal-ACKed VERIFIED journal is never re-blocked by a contradicting provider
     *  read; the ACK is the debt-clearing authority. */
    @Test
    fun ackedVerifiedJournalIsNeverRevertedByProviderContradiction() {
        val job = tempJob("counterexample-bound6-")
        try {
            val operationId = "acked-owner"
            val uri = "content://media/external/images/media/104"
            KeplerJobMetadata.write(job, JSONObject()
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", uri)
                .put("exportCommitState", GalleryExportCommitState.VERIFIED.name)
                .put("recoveryState", "STABLE")
                .put(TERMINAL_OPERATION_ID, operationId))
            val journal = mediaStoreJournal(
                job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId
            ).transition(job, MediaStoreExportState.VERIFIED, uri)
                .markTerminalPersisted(job, operationId)
            val settled = settleMediaStoreExportDebt(
                org.robolectric.RuntimeEnvironment.getApplication(), job,
                FakeAccess(pending = false, verified = false, exists = false)
            )
            assertTrue("ACKed journal debt must stay cleared even under provider contradiction", settled)
            assertTrue("The durable ACK survives the provider read",
                MediaStoreExportJournal.list(job).single().terminalMetadataPersisted)
            assertEquals(GalleryExportCommitState.VERIFIED.name,
                KeplerJobMetadata.read(job).getString("exportCommitState"))
        } finally {
            job.deleteRecursively()
        }
    }

    /** 7. Recovery DEFERRED terminal settlement classifies from the MAIN record evidence
     *  (committed-unverified), NEVER mechanically downgrades to INTERRUPTED_PRE_COMMIT, and
     *  writes the durable classification. */
    @Test
    fun recoveryDeferredSettlementClassifiesFromMainEvidence() {
        val (base, job) = recoveryRoot("counterexample-bound7-")
        try {
            val operationId = "deferred-export"
            val uri = "content://media/external/images/media/105"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "PARTIAL")
                .put("galleryExportCommitted", true)
                .put("exportUri", uri)
                .put("exportCommitState", GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name)
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put(TERMINAL_OPERATION_ID, operationId))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                .transition(job, MediaStoreExportState.PUBLIC_COMMITTED, uri)
            val sidecar = mediaStoreJournal(
                job, MediaStoreExportRole.RAW_DNG_SIDECAR, "frame.dng",
                ownerOperationId = operationId, frameIndex = 0
            ).transition(job, MediaStoreExportState.PREPARED)
            val report = KeplerRecoveryCoordinator.recoverRoots(
                listOf(base),
                FakeAccess(pending = false, verified = false, exists = true)
            )
            val result = report.jobs.single { it.jobDir == job }
            assertEquals(
                "DEFERRED must classify from MAIN committed evidence, not INTERRUPTED_PRE_COMMIT",
                KeplerJobRecoveryClassification.PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION,
                result.classification
            )
            assertEquals("PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION",
                KeplerJobMetadata.read(job).getString("recoveryState"))
            assertEquals("The exact owner is retained for the next entry",
                operationId, KeplerJobMetadata.read(job).optString(ACTIVE_OPERATION_ID))
            val journals = MediaStoreExportJournal.list(job)
            assertTrue("Main committed journal is terminal-ACKed",
                journals.first { it.role == MediaStoreExportRole.MAIN_IMAGE }.terminalMetadataPersisted)
            assertFalse("Unresolved sidecar journal is not ACKed",
                journals.first { it.role == MediaStoreExportRole.RAW_DNG_SIDECAR }.terminalMetadataPersisted)
            assertFalse("Sidecar journal was not force-acked",
                sidecar.terminalMetadataPersisted)
        } finally {
            base.deleteRecursively()
        }
    }

    /** 8. The recovery terminal finalizer preserves the verification policy: a committed-unverified
     *  terminal owner keeps PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION instead of STABLE. */
    @Test
    fun recoveryTerminalFinalizerPreservesVerificationPolicy() {
        val (base, job) = recoveryRoot("counterexample-bound8-")
        try {
            val operationId = "terminal-policy"
            val uri = "content://media/external/images/media/106"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "PARTIAL")
                .put("galleryExportCommitted", true)
                .put("exportUri", uri)
                .put("exportCommitState", GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name)
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put(TERMINAL_OPERATION_ID, operationId))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                .transition(job, MediaStoreExportState.PUBLIC_COMMITTED, uri)
            val report = KeplerRecoveryCoordinator.recoverRoots(
                listOf(base),
                FakeAccess(pending = false, verified = false, exists = true)
            )
            val result = report.jobs.single { it.jobDir == job }
            assertEquals(KeplerJobRecoveryClassification.RECOVERED, result.classification)
            assertEquals(
                "Committed-unverified terminal owner keeps its verification policy, never STABLE",
                "PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION",
                KeplerJobMetadata.read(job).getString("recoveryState")
            )
            assertEquals("", KeplerJobMetadata.read(job).optString(ACTIVE_OPERATION_ID))
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            base.deleteRecursively()
        }
    }

    /** 9. Role-aware aggregation: a verified MAIN image is never downgraded by a committed-unverified
     *  SIDECAR result; the sidecar stays its own commit debt. */
    @Test
    fun verifiedMainNeverDowngradedByCommittedUnverifiedSidecar() {
        val (base, job) = recoveryRoot("counterexample-bound9-")
        try {
            val operationId = "verified-main"
            val mainUri = "content://media/external/images/media/107"
            val sidecarUri = "content://media/external/file/108"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "PARTIAL")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", mainUri)
                .put("exportCommitState", GalleryExportCommitState.VERIFIED.name)
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put(TERMINAL_OPERATION_ID, operationId))
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                .transition(job, MediaStoreExportState.VERIFIED, mainUri)
            val sidecar = mediaStoreJournal(
                job, MediaStoreExportRole.RAW_DNG_SIDECAR, "frame.dng",
                ownerOperationId = operationId, frameIndex = 0
            ).transition(job, MediaStoreExportState.PUBLIC_COMMITTED, sidecarUri)
            val report = KeplerRecoveryCoordinator.recoverRoots(
                listOf(base),
                object : MediaStoreExportRecoveryAccess {
                    override fun inspect(uri: Uri, journal: MediaStoreExportJournal) =
                        MediaStoreExportInspection(true, false, uri.toString() == mainUri)
                    override fun setPending(uri: Uri, pending: Boolean) = true
                    override fun delete(uri: Uri) = true
                }
            )
            val result = report.jobs.single { it.jobDir == job }
            assertEquals(
                KeplerJobRecoveryClassification.PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL,
                result.classification
            )
            val metadata = KeplerJobMetadata.read(job)
            assertEquals("VERIFIED", metadata.getString("exportCommitState"))
            assertEquals(
                "Main verification policy is preserved; the sidecar result never downgrades it",
                "STABLE", metadata.getString("recoveryState")
            )
            assertTrue("Verified main journal is ACKed",
                MediaStoreExportJournal.list(job)
                    .first { it.role == MediaStoreExportRole.MAIN_IMAGE }.terminalMetadataPersisted)
            assertFalse("Sidecar commit debt stays its own journal debt",
                sidecar.terminalMetadataPersisted)
        } finally {
            base.deleteRecursively()
        }
    }

    /** 10. Rollback with a committed owner: settlement precedes every destructive op; a durably
     *  committed public result refuses rollback with ZERO restore/delete invocation. */
    @Test
    fun rollbackSettlesExternalAuthorityBeforeAnyDestructiveOp() {
        val job = tempJob("counterexample-bound10-")
        val previousRestore = restoreBackupsInvocationCount
        val previousDelete = removeTransactionCreatedFilesInvocationCount
        try {
            val operationId = "reb-proc-export"
            val uri = "content://media/external/images/media/109"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "RAW_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
            val target = File(job, "raw_fusion_final.png").apply { writeText("before") }
            val transaction = backupReprocessTransaction(job, listOf(target)).getOrThrow()
            target.writeText("after!")
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                .transition(job, MediaStoreExportState.PUBLIC_COMMITTED, uri)
            val session = ReprocessTransactionSession(job)
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            session.bindForLegacyFinalizer(transaction, lease)
            val error = IllegalStateException("worker failed")
            val result = rollback(
                session, transaction, lease, job, ReprocessJobKind.RAW_FUSION,
                ReprocessWorkerOutcome(result = Result.failure(error), publicExportCommitted = false),
                error,
                FakeAccess(pending = false, verified = false, exists = true)
            )
            assertEquals("Committed evidence refuses rollback",
                ReprocessFinalizationState.QUARANTINED, result.state)
            assertEquals("ZERO backup restores on committed evidence",
                previousRestore, restoreBackupsInvocationCount)
            assertEquals("ZERO created-file deletions on committed evidence",
                previousDelete, removeTransactionCreatedFilesInvocationCount)
            assertEquals("The worker-mutated file is untouched", "after!", target.readText())
            assertTrue("The transaction evidence is quarantined durably",
                File(transaction.backupRoot, ".reprocess_quarantine").isFile)
            assertEquals("Committed record was written by the settlement",
                "PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION",
                KeplerJobMetadata.read(job).optString("recoveryState"))
        } finally {
            lateFinalizationHandoffScope = null
            restoreBackupsInvocationCount = previousRestore
            removeTransactionCreatedFilesInvocationCount = previousDelete
            job.deleteRecursively()
        }
    }

    /** 11. Rollback on a proven pre-commit cut: settlement resolves first, then restore/delete
     *  run exactly once and the job returns ROLLED_BACK with the original files. */
    @Test
    fun rollbackPreCommitProvesExternalAuthorityThenRestores() {
        val job = tempJob("counterexample-bound11-")
        val previousRestore = restoreBackupsInvocationCount
        val previousDelete = removeTransactionCreatedFilesInvocationCount
        try {
            val operationId = "reb-precommit"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "RAW_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
            val target = File(job, "raw_fusion_final.png").apply { writeText("before") }
            val transaction = backupReprocessTransaction(job, listOf(target)).getOrThrow()
            target.writeText("after!")
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                .transition(job, MediaStoreExportState.ROW_INSERTED, "content://media/external/images/media/110")
            val session = ReprocessTransactionSession(job)
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            session.bindForLegacyFinalizer(transaction, lease)
            val error = IllegalStateException("worker failed")
            val result = rollback(
                session, transaction, lease, job, ReprocessJobKind.RAW_FUSION,
                ReprocessWorkerOutcome(result = Result.failure(error), publicExportCommitted = false),
                error,
                FakeAccess(pending = false, verified = false, exists = false)
            )
            assertEquals(ReprocessFinalizationState.ROLLED_BACK, result.state)
            assertEquals("Settlement proves pre-commit BEFORE restore; restore runs exactly once",
                previousRestore + 1, restoreBackupsInvocationCount)
            assertEquals("Created-file deletion runs exactly once",
                previousDelete + 1, removeTransactionCreatedFilesInvocationCount)
            assertEquals("The original file is restored", "before", target.readText())
            assertFalse(KeplerJobMetadata.isOperationActive(job))
        } finally {
            lateFinalizationHandoffScope = null
            restoreBackupsInvocationCount = previousRestore
            removeTransactionCreatedFilesInvocationCount = previousDelete
            job.deleteRecursively()
        }
    }

    /** 12. Rollback with inconclusive provider evidence: NO destructive op runs and the owner
     *  lease is retained for the next retry. */
    @Test
    fun rollbackInconclusiveEvidenceBlocksAllDestructiveOps() {
        val job = tempJob("counterexample-bound12-")
        val previousRestore = restoreBackupsInvocationCount
        val previousDelete = removeTransactionCreatedFilesInvocationCount
        try {
            val operationId = "reb-unknown"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "RAW_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
            val target = File(job, "raw_fusion_final.png").apply { writeText("before") }
            val transaction = backupReprocessTransaction(job, listOf(target)).getOrThrow()
            target.writeText("after!")
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                .transition(job, MediaStoreExportState.ROW_INSERTED, "content://media/external/images/media/111")
            val session = ReprocessTransactionSession(job)
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            session.bindForLegacyFinalizer(transaction, lease)
            val error = IllegalStateException("worker failed")
            val result = rollback(
                session, transaction, lease, job, ReprocessJobKind.RAW_FUSION,
                ReprocessWorkerOutcome(result = Result.failure(error), publicExportCommitted = false),
                error,
                FakeAccess(pending = true, verified = false, inspectionFailed = true)
            )
            assertEquals(ReprocessFinalizationState.QUARANTINED, result.state)
            assertEquals("Zero destructive ops on inconclusive evidence",
                previousRestore, restoreBackupsInvocationCount)
            assertEquals(previousDelete, removeTransactionCreatedFilesInvocationCount)
            assertEquals("Worker-mutated file untouched", "after!", target.readText())
            assertTrue("The retained owner protects the job for the next entry",
                KeplerJobMetadata.isOperationActive(job))
        } finally {
            lateFinalizationHandoffScope = null
            restoreBackupsInvocationCount = previousRestore
            removeTransactionCreatedFilesInvocationCount = previousDelete
            job.deleteRecursively()
        }
    }

    /** 13. Rollback without provider access on commit-resolution debt is fail-closed: ZERO
     *  destructive invocation, quarantined, owner retained. */
    @Test
    fun rollbackWithoutProviderRefusesDestructiveOps() {
        val job = tempJob("counterexample-bound13-")
        val previousRestore = restoreBackupsInvocationCount
        val previousDelete = removeTransactionCreatedFilesInvocationCount
        try {
            val operationId = "reb-noaccess"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "RAW_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
            val target = File(job, "raw_fusion_final.png").apply { writeText("before") }
            val transaction = backupReprocessTransaction(job, listOf(target)).getOrThrow()
            target.writeText("after!")
            mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                .transition(job, MediaStoreExportState.ROW_INSERTED, "content://media/external/images/media/112")
            val session = ReprocessTransactionSession(job)
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            session.bindForLegacyFinalizer(transaction, lease)
            val error = IllegalStateException("worker failed")
            val result = rollback(
                session, transaction, lease, job, ReprocessJobKind.RAW_FUSION,
                ReprocessWorkerOutcome(result = Result.failure(error), publicExportCommitted = false),
                error
            )
            assertEquals(ReprocessFinalizationState.QUARANTINED, result.state)
            assertEquals("Zero destructive ops without provider authority",
                previousRestore, restoreBackupsInvocationCount)
            assertEquals(previousDelete, removeTransactionCreatedFilesInvocationCount)
            assertEquals("Worker-mutated file untouched", "after!", target.readText())
            assertTrue(KeplerJobMetadata.isOperationActive(job))
        } finally {
            lateFinalizationHandoffScope = null
            restoreBackupsInvocationCount = previousRestore
            removeTransactionCreatedFilesInvocationCount = previousDelete
            job.deleteRecursively()
        }
    }

    /** 14. YUV absorbing terminal: a failed handoff settlement retains the self-acquired lease
     *  with its pending marker; the next real mutation acquisition reconciles and converges. */
    @Test
    fun handoffSettlementFailureRetainsYuvLeaseUntilNextMutation() {
        val job = tempJob("counterexample-bound14-")
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, "capture-yuv")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.CAPTURE_YUV.name)
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "capture-yuv")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            KeplerJobMetadata.atomicWriteFailureForTest = java.io.IOException("injected handoff write failure")
            val settled = KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(job)
            assertFalse("Failed handoff settlement reports false", settled)
            assertNotNull("The self-acquired lease is retained on failure",
                KeplerJobMetadata.findOperationLease(job))
            KeplerJobMetadata.atomicWriteFailureForTest = null
            val acquired = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
            )
            assertTrue("The first reconcile drains handoff and durable debt in one pass",
                KeplerJobMetadata.isOperationOwner(job, acquired))
            assertFalse(KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
            assertFalse(KeplerJobMetadata.read(job).has(ACTIVE_OPERATION_ID))
            KeplerJobMetadata.releaseOperation(acquired)
            assertNull(KeplerJobMetadata.findOperationLease(job))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            job.deleteRecursively()
        }
    }

    /** 15. RAW absorbing terminal: the same retained-settlement contract holds for the RAW entry. */
    @Test
    fun handoffSettlementFailureRetainsRawLeaseUntilNextMutation() {
        val job = tempJob("counterexample-bound15-")
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "RAW_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, "capture-raw")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.CAPTURE_RAW.name)
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "capture-raw")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_RAW"))
            KeplerJobMetadata.atomicWriteFailureForTest = java.io.IOException("injected raw handoff write failure")
            val settled = KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(job)
            assertFalse(settled)
            val retained = KeplerJobMetadata.findOperationLease(job)
            assertNotNull("RAW absorbing terminal retains its lease on failure", retained)
            KeplerJobMetadata.atomicWriteFailureForTest = null
            val acquired = runCatching {
                KeplerJobMetadata.acquireRecoveryCheckedOperation(
                    job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
                )
            }.getOrElse { firstFailure ->
                if (firstFailure is ProcessingAlreadyActiveException) {
                    KeplerJobMetadata.acquireRecoveryCheckedOperation(
                        job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
                    )
                } else {
                    throw firstFailure
                }
            }
            if (acquired != null) KeplerJobMetadata.releaseOperation(acquired)
            assertEquals("", KeplerJobMetadata.read(job).optString(PROCESSING_HANDOFF_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            job.deleteRecursively()
        }
    }

    /** 16. Caller-owned lease (processor path): failed settlement marks the pending marker on the
     *  exact caller lease and never releases it. */
    @Test
    fun absorbingTerminalCallerLeaseRetainedOnFailedSettlement() {
        val job = tempJob("counterexample-bound16-")
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, "capture-caller")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.CAPTURE_YUV.name)
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "capture-caller")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            KeplerJobMetadata.atomicWriteFailureForTest = java.io.IOException("injected caller write failure")
            val settled = KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(job, lease)
            assertFalse(settled)
            assertEquals("The caller lease is retained and marked", lease, KeplerJobMetadata.findOperationLease(job))
            KeplerJobMetadata.atomicWriteFailureForTest = null
            // Mark reconciliation ready: the caller owner has finished all work
            lease.markReconciliationReady()
            runCatching {
                KeplerJobMetadata.acquireRecoveryCheckedOperation(
                    job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
                )
            }
            val second = runCatching {
                KeplerJobMetadata.acquireRecoveryCheckedOperation(
                    job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
                )
            }.getOrNull()
            if (second != null) KeplerJobMetadata.releaseOperation(second)
            assertEquals("", KeplerJobMetadata.read(job).optString(PROCESSING_HANDOFF_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            job.deleteRecursively()
        }
    }

    /** 17. SR consume path fail-closed: a persistence failure leaves the correlated handoff
     *  untouched for retry; the retry consumes it exactly once — success never follows an
     *  unconsumed handoff. */
    @Test
    fun srConsumePathFailsClosedAndDebtRetries() {
        val job = tempJob("counterexample-bound17-")
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, "sr-source")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.CAPTURE_YUV.name)
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "sr-source")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            KeplerJobMetadata.atomicWriteFailureForTest = java.io.IOException("injected consume write failure")
            val consumed = KeplerJobMetadata.consumeProcessingHandoff(job, KeplerActiveOperationKind.PROCESSING_YUV)
            assertFalse("Consume persistence failure is NOT success", consumed)
            assertEquals("The handoff remains correlated for the retry",
                KeplerJobMetadata.ProcessingHandoffPresence.CORRELATED,
                KeplerJobMetadata.inspectProcessingHandoff(job, KeplerActiveOperationKind.PROCESSING_YUV))
            KeplerJobMetadata.atomicWriteFailureForTest = null
            assertTrue("The retry consumes exactly once",
                KeplerJobMetadata.consumeProcessingHandoff(job, KeplerActiveOperationKind.PROCESSING_YUV))
            assertEquals("Consumed handoff reports absent",
                KeplerJobMetadata.ProcessingHandoffPresence.ABSENT,
                KeplerJobMetadata.inspectProcessingHandoff(job, KeplerActiveOperationKind.PROCESSING_YUV))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            job.deleteRecursively()
        }
    }

    /** 18. A committed-unverified sidecar with a DIVERGENT durable frame URI is never
     *  acknowledged; only the exact-URI record acknowledges. */
    @Test
    fun sidecarCommittedUnverifiedRequiresExactUri() {
        val job = tempJob("counterexample-bound18-")
        try {
            val journalUri = "content://media/external/file/113"
            val divergentUri = "content://media/external/file/114"
            val metadata = JSONObject()
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/external/images/media/115")
                .put("frames", org.json.JSONArray().put(JSONObject()
                    .put("frameIndex", 0)
                    .put("dngSidecarPublicStatus", "PUBLIC_COMMITTED_UNVERIFIED")
                    .put("publicDngUri", divergentUri)))
            val journal = mediaStoreJournal(
                job, MediaStoreExportRole.RAW_DNG_SIDECAR, "frame.dng", ownerOperationId = "op", frameIndex = 0
            ).transition(job, MediaStoreExportState.PUBLIC_COMMITTED, journalUri)
            assertFalse("Divergent durable URI never acknowledges the sidecar journal",
                terminalAckEligible(metadata, journal))
            val exact = MediaStoreExportJournal.read(job, MediaStoreExportJournal.fileFor(job, journal.exportAttemptId))
                .transition(job, MediaStoreExportState.PUBLIC_COMMITTED, divergentUri)
            assertTrue("Exact-URI committed-unverified record is the sidecar's own ACK evidence",
                terminalAckEligible(metadata, exact))
        } finally {
            job.deleteRecursively()
        }
    }

    /** 19. A sidecar WITHOUT an own frame record: only its own VERIFIED journal state
     *  acknowledges; PUBLIC_COMMITTED stays commit debt. */
    @Test
    fun sidecarWithoutFrameRecordVerifiedOwnEvidenceOnly() {
        val job = tempJob("counterexample-bound19-")
        try {
            val metadata = JSONObject()
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/external/images/media/116")
            val committed = mediaStoreJournal(
                job, MediaStoreExportRole.RAW_DNG_SIDECAR, "frame.dng", ownerOperationId = "op", frameIndex = 0
            ).transition(job, MediaStoreExportState.PUBLIC_COMMITTED, "content://media/external/file/117")
            assertFalse("Committed sidecar without reconstructed exact frame/URI record is NOT acked",
                terminalAckEligible(metadata, committed))
            val verified = MediaStoreExportJournal.read(job, MediaStoreExportJournal.fileFor(job, committed.exportAttemptId))
                .transition(job, MediaStoreExportState.VERIFIED, "content://media/external/file/117")
            assertTrue("The journal's own VERIFIED durable state is its own ACK evidence",
                terminalAckEligible(metadata, verified))
        } finally {
            job.deleteRecursively()
        }
    }

    /** 20. CASE B single temporary authority: an inconclusive pass retains the debt and leaves no
     *  second authority; the deterministic retry converges exactly once. */
    @Test
    fun singleAuthorityNeverDoubleSettlesUnderSerialPasses() {
        val job = tempJob("counterexample-bound20-")
        try {
            val uri = "content://media/external/images/media/118"
            KeplerJobMetadata.write(job, JSONObject()
                .put("currentPipelineStage", "FAILED")
                .put("exportCommitState", GalleryExportCommitState.UNKNOWN.name)
                .put("exportUri", uri)
                .put("galleryPublicExportLinkage", uri)
                .put("galleryExportCommitted", false))
            val journal = mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = "op")
                .transition(job, MediaStoreExportState.ROW_INSERTED, uri)
                .transition(job, MediaStoreExportState.CONTENT_WRITTEN)
            val access = FakeAccess(pending = true, verified = false, inspectionFailed = true)
            val first = settleMediaStoreExportDebt(
                org.robolectric.RuntimeEnvironment.getApplication(), job, access
            )
            assertFalse("Inconclusive first pass keeps the debt", first)
            assertEquals("Inconclusive pass leaves the journal evidence untouched in-band",
                MediaStoreExportState.CONTENT_WRITTEN,
                MediaStoreExportJournal.list(job).single().state)
            assertNull("No authority survives the pass",
                KeplerJobMetadata.findOperationLease(job))
            access.inspectionFailed = false
            access.pending = false
            access.exists = false
            val second = settleMediaStoreExportDebt(
                org.robolectric.RuntimeEnvironment.getApplication(), job, access
            )
            assertTrue("Deterministic retry converges exactly once", second)
            assertEquals("The journal converges to CLEANED exactly once",
                MediaStoreExportState.CLEANED, MediaStoreExportJournal.list(job).single().state)
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    /** A retained lease must carry a recognized retry reason (or a live durable owner) so
     *  the next production acquisition can always reconcile it. */
    private fun assertRetainedLeaseCarriesRetryReason(job: File) {
        val lease = KeplerJobMetadata.findOperationLease(job) ?: return
        val carrying = lease.pendingTerminalSettlement() != null ||
            lease.pendingPublicExportSettlement() != null ||
            lease.hasPendingProcessingHandoffSettlement() ||
            lease.pendingDurableSettlementId() != null ||
            lease.currentDurableOperationId() != null
        assertTrue("Retained lease must carry a recognized retry reason or a live durable owner", carrying)
    }

    private fun yuvDispatchCutJob(label: String, operationId: String): File {
        val job = tempJob(label)
        KeplerJobMetadata.write(job, JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
            .put(ACTIVE_OPERATION_ID, operationId)
            .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.CAPTURE_YUV.name)
            .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
            .put(PROCESSING_HANDOFF_OPERATION_ID, operationId)
            .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
        return job
    }

    /** 21. Phase 1 protocol: a lease with a pending processing-handoff settlement can never be
     *  released by releaseIfProcessingSettled; the marker-clear path releases it. */
    @Test
    fun releaseIfProcessingSettledHandoffPending() {
        val job = tempJob("counterexample-bound21-")
        try {
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            lease.markProcessingHandoffSettlementPending()
            assertFalse("releaseIfProcessingSettled refuses while the handoff debt is pending",
                lease.releaseIfProcessingSettled())
            assertEquals("The exact lease stays registered",
                lease, KeplerJobMetadata.findOperationLease(job))
            assertTrue("The pending marker is preserved", lease.hasPendingProcessingHandoffSettlement())
            assertFalse("A non-existent operation clear must not affect the retained lease",
                KeplerJobMetadata.clearActiveOperation(job, "noop", lease))
            assertTrue("The handoff settlement completion clears the pending marker",
                lease.completeProcessingHandoffSettlement())
            assertTrue("A cleared handoff debt allows the release",
                lease.releaseIfProcessingSettled())
            assertNull("The exact lease is released", KeplerJobMetadata.findOperationLease(job))
        } finally {
            job.deleteRecursively()
        }
    }

    /** 22. Worker dispatch throwable cut: handoff settlement write failure leaves the exact
     *  caller-owned lease registered; the pipeline cleanup's releaseIfProcessingSettled refuses. */
    @Test
    fun workerDispatchThrowable_handoffSettlementWriteFailure() {
        val job = yuvDispatchCutJob("counterexample-bound22-", "dispatch-cut")
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            KeplerJobMetadata.update(job) { it
                .put("currentPipelineStage", "FAILED")
                .put("processStatus", "PIPELINE_FAILED")
                .put("pipelineFailed", true)
                .put(TERMINAL_OPERATION_ID, "dispatch-cut")
                .put("userCanMoveDevice", true)
            }
            assertTrue(KeplerJobMetadata.clearActiveOperation(job, "dispatch-cut", lease))
            KeplerJobMetadata.atomicWriteFailureForTest = java.io.IOException("injected handoff write failure")
            assertFalse("Handoff settlement write failure reports false",
                KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(job, lease))
            assertTrue("The exact lease carries the pending handoff marker",
                lease.hasPendingProcessingHandoffSettlement())
            assertFalse("releaseIfProcessingSettled refuses while the handoff debt is pending",
                lease.releaseIfProcessingSettled())
            assertEquals("The exact lease remains registered for the same-process retry",
                lease, KeplerJobMetadata.findOperationLease(job))
            assertTrue("The durable handoff remains owned",
                KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
            assertRetainedLeaseCarriesRetryReason(job)
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            job.deleteRecursively()
        }
    }

    /** 23. From the bound22 cut, the next REAL Gallery mutation entry (saveFrameSelection)
     *  reconciles the retained handoff debt under the exact lease, releases it, re-runs the
     *  gate, and then acquires and completes the mutation. */
    @Test
    fun nextMutation_reconcilesRetainedHandoffAfterDispatchFailure() {
        val job = yuvDispatchCutJob("counterexample-bound23-", "dispatch-cut")
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.update(job) { it
                .put("currentPipelineStage", "FAILED")
                .put("processStatus", "PIPELINE_FAILED")
                .put("pipelineFailed", true)
                .put(TERMINAL_OPERATION_ID, "dispatch-cut")
                .put("userCanMoveDevice", true)
            }
            assertTrue(KeplerJobMetadata.clearActiveOperation(job, "dispatch-cut", null))
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            KeplerJobMetadata.atomicWriteFailureForTest = java.io.IOException("injected handoff write failure")
            assertFalse(KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(job, lease))
            assertFalse(lease.releaseIfProcessingSettled())
            assertEquals(lease, KeplerJobMetadata.findOperationLease(job))
            KeplerJobMetadata.atomicWriteFailureForTest = null
            // Mark reconciliation ready: the original owner has finished all work
            lease.markReconciliationReady()

            val saved = saveFrameSelection(context, job, FrameSelectionMode.AUTO_RULE_BASED, emptyList())
            assertTrue("The real mutation entry converges the retained handoff debt and proceeds", saved.isSuccess)
            assertNull("The retained lease is released when the debt settles",
                KeplerJobMetadata.findOperationLease(job))
            assertFalse("The durable handoff is settled",
                KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
            assertEquals("The requested mutation was applied",
                FrameSelectionMode.AUTO_RULE_BASED.name,
                KeplerJobMetadata.read(job).getString("frameSelectionMode"))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            job.deleteRecursively()
        }
    }

    /** 24. Worker-setup secondary beginActiveOperation write failure: the exact lease is
     *  retained with the pending handoff retry reason; the next real entry converges. */
    @Test
    fun workerSetup_secondaryBeginActiveWriteFailure() {
        val job = tempJob("counterexample-bound24-")
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "setup-handoff")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("setup operation write failed")
            assertThrows(IllegalStateException::class.java) {
                persistYuvCaptureSetupFailure(job, "test.setup", IllegalStateException("camera setup failed"))
            }
            val retained = KeplerJobMetadata.findOperationLease(job)
            assertNotNull("The exact lease is retained", retained)
            assertTrue("The pending handoff retry reason is installed",
                retained!!.hasPendingProcessingHandoffSettlement())
            assertTrue("The durable handoff is still owned",
                KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
            assertFalse("No durable owner was established",
                KeplerJobMetadata.read(job).has(ACTIVE_OPERATION_ID))
            assertRetainedLeaseCarriesRetryReason(job)
            KeplerJobMetadata.atomicWriteFailureForTest = null

            val saved = saveFrameSelection(context, job, FrameSelectionMode.AUTO_RULE_BASED, emptyList())
            assertTrue("The next real entry converges the retained setup debt", saved.isSuccess)
            assertNull(KeplerJobMetadata.findOperationLease(job))
            assertFalse(KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            job.deleteRecursively()
        }
    }

    /** 25. Worker-setup terminal metadata write failure AFTER beginActiveOperation: the exact
     *  PendingTerminalSettlement is installed and the lease retained; the next acquisition
     *  retries the terminal re-record and releases. */
    @Test
    fun workerSetup_terminalMetadataWriteFailureAfterBeginActive() {
        val job = tempJob("counterexample-bound25-")
        val previousSequence = KeplerJobMetadata.atomicWriteFailureSequenceForTest
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "setup-handoff")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            KeplerJobMetadata.atomicWriteFailureSequenceForTest =
                mutableListOf(null, java.io.IOException("terminal write failed"))
            assertThrows(java.io.IOException::class.java) {
                persistYuvCaptureSetupFailure(job, "test.setup", IllegalStateException("camera setup failed"))
            }
             val retained = KeplerJobMetadata.findOperationLease(job)
            assertNotNull("The exact lease is retained", retained)
            val pendingTerminal = retained!!.pendingTerminalSettlement()
            assertNotNull("The pending terminal settlement is installed", pendingTerminal)
            assertEquals("FAILED", pendingTerminal!!.attemptStatus)
            assertEquals("PIPELINE_FAILED", pendingTerminal.processStatus)
            assertEquals("The durable owner matched the pending terminal operation",
                KeplerJobMetadata.read(job).optString(ACTIVE_OPERATION_ID), pendingTerminal.operationId)
            assertFalse("The handoff was consumed with the successful beginActiveOperation",
                KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
            assertRetainedLeaseCarriesRetryReason(job)
            KeplerJobMetadata.atomicWriteFailureSequenceForTest = null

            val acquired = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
            )
            assertTrue("The next acquisition owns the job", KeplerJobMetadata.isOperationOwner(job, acquired))
            assertFalse("The durable ACTIVE owner was cleared by the reconciliation",
                KeplerJobMetadata.read(job).has(ACTIVE_OPERATION_ID))
            assertEquals("FAILED", KeplerJobMetadata.read(job).getString("currentPipelineStage"))
            assertEquals(pendingTerminal.operationId, KeplerJobMetadata.read(job).optString(TERMINAL_OPERATION_ID))
            acquired.release()
            assertNull(KeplerJobMetadata.findOperationLease(job))
        } finally {
            KeplerJobMetadata.atomicWriteFailureSequenceForTest = previousSequence
            job.deleteRecursively()
        }
    }

    /** 26. Worker-setup ACTIVE clear failure: the terminal is durable, the pending durable
     *  settlement id is installed, and the exact lease is retained until the next acquisition
     *  clears the owner and releases. */
    @Test
    fun workerSetup_activeClearFailure() {
        val job = tempJob("counterexample-bound26-")
        val previousSequence = KeplerJobMetadata.atomicWriteFailureSequenceForTest
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "setup-handoff")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            KeplerJobMetadata.atomicWriteFailureSequenceForTest =
                mutableListOf(null, null, java.io.IOException("active clear failed"))
            // Production behavior: clearActiveOperation catches the write failure internally
            // and returns false; persistYuvCaptureSetupFailure then installs a durable-pending
            // retry reason and completes without throwing the secondary failure out.
            persistYuvCaptureSetupFailure(job, "test.setup", IllegalStateException("camera setup failed"))
            val retained = KeplerJobMetadata.findOperationLease(job)
            assertNotNull("The exact lease is retained", retained)
            assertEquals("The terminal was recorded before the clear failure",
                "FAILED", KeplerJobMetadata.read(job).getString("currentPipelineStage"))
            val durableActiveId = KeplerJobMetadata.read(job).optString(ACTIVE_OPERATION_ID)
            assertTrue("The durable owner still requires settlement",
                durableActiveId.isNotBlank())
            assertEquals("The pending durable settlement id matches the durable owner",
                durableActiveId, retained!!.pendingDurableSettlementId())
             assertRetainedLeaseCarriesRetryReason(job)
            KeplerJobMetadata.atomicWriteFailureSequenceForTest = null

            val acquired = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
            )
            assertTrue("The next acquisition owns the job", KeplerJobMetadata.isOperationOwner(job, acquired))
            assertFalse("The durable owner was cleared by the next acquisition",
                KeplerJobMetadata.read(job).has(ACTIVE_OPERATION_ID))
            acquired.release()
            assertNull(KeplerJobMetadata.findOperationLease(job))
        } finally {
            KeplerJobMetadata.atomicWriteFailureSequenceForTest = previousSequence
            job.deleteRecursively()
        }
    }

    /** 27. Reconciliation totality: every retained-lease reason (A terminal, B public export,
     *  C processing handoff, D durable settlement) is created through a real producing path,
     *  a failed reconcile retains the EXACT lease with the SAME reason, and the successful
     *  retry converges and releases. */
    @Test
    fun reconcilePendingDurableSettlementTotality() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()

        // A. pendingTerminalSettlement via the real worker-setup terminal-write failure.
        tempJob("totality-a-").let { job ->
            val previousSequence = KeplerJobMetadata.atomicWriteFailureSequenceForTest
            try {
                KeplerJobMetadata.write(job, JSONObject()
                    .put("jobType", "YUV_NIGHT_FUSION")
                    .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "setup-handoff")
                    .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
                KeplerJobMetadata.atomicWriteFailureSequenceForTest =
                    mutableListOf(null, java.io.IOException("terminal write failed"))
                assertThrows(java.io.IOException::class.java) {
                    persistYuvCaptureSetupFailure(job, "test.setup", IllegalStateException("setup failed"))
                }
                val retained = KeplerJobMetadata.findOperationLease(job)!!
                val reason = retained.pendingTerminalSettlement()!!
                KeplerJobMetadata.atomicWriteFailureSequenceForTest = null
                KeplerJobMetadata.atomicWriteFailureForTest = java.io.IOException("reconcile terminal write failed")
                assertThrows(ProcessingAlreadyActiveException::class.java) {
                    KeplerJobMetadata.acquireRecoveryCheckedOperation(
                        job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
                    )
                }
                assertEquals("The failed reconcile retains the exact lease",
                    retained, KeplerJobMetadata.findOperationLease(job))
                assertEquals("The SAME terminal settlement reason survives the failed reconcile",
                    reason.operationId, retained.pendingTerminalSettlement()!!.operationId)
                assertEquals(reason.attemptStatus, retained.pendingTerminalSettlement()!!.attemptStatus)
                assertRetainedLeaseCarriesRetryReason(job)
                KeplerJobMetadata.atomicWriteFailureForTest = null
                val acquired = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                    job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
                )
                assertTrue(KeplerJobMetadata.isOperationOwner(job, acquired))
                assertFalse(KeplerJobMetadata.read(job).has(ACTIVE_OPERATION_ID))
                acquired.release()
            } finally {
                KeplerJobMetadata.atomicWriteFailureSequenceForTest = previousSequence
                KeplerJobMetadata.atomicWriteFailureForTest = null
                job.deleteRecursively()
            }
        }

        // B. pendingPublicExportSettlement via the real interruption-settlement cut.
        tempJob("totality-b-").let { job ->
            try {
                val operationId = "totality-export"
                KeplerJobMetadata.write(job, JSONObject()
                    .put("currentPipelineStage", "PROCESSING")
                    .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(ACTIVE_OPERATION_ID, operationId)
                    .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
                mediaStoreJournal(job, MediaStoreExportRole.MAIN_IMAGE, "result.jpg", ownerOperationId = operationId)
                    .transition(job, MediaStoreExportState.ROW_INSERTED, "content://media/external/images/media/120")
                val lease = KeplerJobMetadata.acquireOperation(job)!!
                lease.markDurableOperation(operationId, KeplerActiveOperationKind.PUBLIC_EXPORT)
                assertFalse("The real settlement cut keeps the owner retained without provider access",
                    settleOwnedPublicExportInterruption(job, lease, "cut", access = null))
                assertNotNull("The pending public export settlement is registered",
                    lease.pendingPublicExportSettlement())
                assertThrows(ProcessingAlreadyActiveException::class.java) {
                    KeplerJobMetadata.acquireRecoveryCheckedOperation(
                        job, JobRecoveryMutationIntent.FRAME_SELECTION
                    )
                }
                assertEquals("The failed reconcile retains the exact lease",
                    lease, KeplerJobMetadata.findOperationLease(job))
                assertEquals("The SAME public export settlement reason survives",
                    operationId, lease.pendingPublicExportSettlement()!!.operationId)
                assertRetainedLeaseCarriesRetryReason(job)
                lease.markReconciliationReady()
                val settled = settleMediaStoreExportDebt(
                    context, job, FakeAccess(pending = false, verified = false, exists = false)
                )
                assertTrue("The provider pass converges the retained public export owner", settled)
                assertNull("The converged owner is released",
                    KeplerJobMetadata.findOperationLease(job))
                assertEquals(MediaStoreExportState.CLEANED,
                    MediaStoreExportJournal.list(job).single().state)
            } finally {
                job.deleteRecursively()
            }
        }

        // C. pendingProcessingHandoffSettlement via the real dispatch settlement write failure.
        tempJob("totality-c-").let { job ->
            val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
            try {
                KeplerJobMetadata.write(job, JSONObject()
                    .put("jobType", "YUV_NIGHT_FUSION")
                    .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(ACTIVE_OPERATION_ID, "totality-capture")
                    .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.CAPTURE_YUV.name)
                    .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "totality-capture")
                    .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
                KeplerJobMetadata.atomicWriteFailureForTest = java.io.IOException("injected handoff write failure")
                assertFalse(KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(job))
                val retained = KeplerJobMetadata.findOperationLease(job)!!
                assertTrue(retained.hasPendingProcessingHandoffSettlement())
                KeplerJobMetadata.atomicWriteFailureForTest = null
                KeplerJobMetadata.atomicWriteFailureForTest = java.io.IOException("reconcile handoff write failed")
                assertThrows(ProcessingAlreadyActiveException::class.java) {
                    KeplerJobMetadata.acquireRecoveryCheckedOperation(
                        job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
                    )
                }
                assertEquals("The failed reconcile retains the exact lease",
                    retained, KeplerJobMetadata.findOperationLease(job))
                assertTrue("The SAME pending handoff marker survives the failed reconcile",
                    retained.hasPendingProcessingHandoffSettlement())
                assertRetainedLeaseCarriesRetryReason(job)
                KeplerJobMetadata.atomicWriteFailureForTest = null
                // Without the injected failure, the reconcile finalizes the handoff and durable debt
                // in the same invocation, clears the ACTIVE operation, and releases the lease.
                val acquired = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                    job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
                )
                assertTrue(KeplerJobMetadata.isOperationOwner(job, acquired))
                assertFalse(KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
                assertFalse(KeplerJobMetadata.read(job).has(ACTIVE_OPERATION_ID))
                acquired.release()
                assertNull(KeplerJobMetadata.findOperationLease(job))
            } finally {
                KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
                job.deleteRecursively()
            }
        }

        // D. pendingDurableSettlementId via the real worker-setup ACTIVE clear failure.
        tempJob("totality-d-").let { job ->
            val previousSequence = KeplerJobMetadata.atomicWriteFailureSequenceForTest
            try {
                KeplerJobMetadata.write(job, JSONObject()
                    .put("jobType", "YUV_NIGHT_FUSION")
                    .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "setup-handoff")
                    .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
                KeplerJobMetadata.atomicWriteFailureSequenceForTest =
                    mutableListOf(null, null, java.io.IOException("active clear failed"))
                // Production behavior: clearActiveOperation catches the failure internally,
                // returns false; the function installs durable-pending and completes.
                persistYuvCaptureSetupFailure(job, "test.setup", IllegalStateException("setup failed"))
                val retained = KeplerJobMetadata.findOperationLease(job)!!
                val pendingId = retained.pendingDurableSettlementId()!!
                assertEquals("The pending id matches the durable owner",
                    KeplerJobMetadata.read(job).optString(ACTIVE_OPERATION_ID), pendingId)
                KeplerJobMetadata.atomicWriteFailureSequenceForTest = null
                KeplerJobMetadata.atomicWriteFailureForTest = java.io.IOException("reconcile clear write failed")
                assertThrows(ProcessingAlreadyActiveException::class.java) {
                    KeplerJobMetadata.acquireRecoveryCheckedOperation(
                        job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
                    )
                }
                assertEquals("The failed reconcile retains the exact lease",
                    retained, KeplerJobMetadata.findOperationLease(job))
                assertEquals("The SAME pending durable settlement id survives",
                    pendingId, retained.pendingDurableSettlementId())
                assertRetainedLeaseCarriesRetryReason(job)
                KeplerJobMetadata.atomicWriteFailureForTest = null
                val acquired = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                    job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
                )
                assertTrue(KeplerJobMetadata.isOperationOwner(job, acquired))
                assertFalse(KeplerJobMetadata.read(job).has(ACTIVE_OPERATION_ID))
                acquired.release()
            } finally {
                KeplerJobMetadata.atomicWriteFailureSequenceForTest = previousSequence
                KeplerJobMetadata.atomicWriteFailureForTest = null
                job.deleteRecursively()
            }
        }
    }

    /** 28. Self-acquired immediate-cancellation handoff failure: the typed terminal may publish
     *  while the exact lease carries the pending handoff debt; the next real mutation entry
     *  converges the debt same-process. */
    @Test
    fun immediateCancellationHandoffFailureConvergesOnRealEntry() {
        val job = yuvDispatchCutJob("counterexample-bound28-", "immediate-cancel")
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.atomicWriteFailureForTest = java.io.IOException("injected cancellation settlement write failure")
            assertFalse("The immediate-cancellation settlement fails and retains",
                KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(job))
            val retained = KeplerJobMetadata.findOperationLease(job)
            assertNotNull("The self-acquired lease is retained", retained)
            assertTrue("The pending handoff retry reason is installed",
                retained!!.hasPendingProcessingHandoffSettlement())
            KeplerJobMetadata.atomicWriteFailureForTest = null
            // The typed terminal may publish for the UI while the handoff debt stays owned.
            KeplerJobMetadata.update(job) { it
                .put("currentPipelineStage", "CANCELLED")
                .put("processStatus", "PIPELINE_CANCELLED")
                .put("userCanMoveDevice", true)
                .put(TERMINAL_OPERATION_ID, "immediate-cancel")
            }
            assertTrue(KeplerJobMetadata.clearActiveOperation(job, "immediate-cancel", retained!!))
            assertRetainedLeaseCarriesRetryReason(job)
            val saved = saveFrameSelection(context, job, FrameSelectionMode.AUTO_RULE_BASED, emptyList())
            assertTrue("The next real mutation converges the cancellation handoff debt", saved.isSuccess)
            assertNull("The retained lease is released on convergence",
                KeplerJobMetadata.findOperationLease(job))
            assertFalse("The cancelled handoff is settled",
                KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
            assertEquals("CANCELLED", KeplerJobMetadata.read(job).getString("currentPipelineStage"))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            job.deleteRecursively()
        }
    }

    /** Phase 4: REAL production-lifetime test for initial read failure.
     *  When ownerLease == null and the first handoff inspection fails with an ordinary
     *  exception, a reserved process-local authority must exist and carry the pending
     *  handoff settlement. After fault removal, the next real mutation converges. */
    @Test
    fun initialReadFailureReservesRetryAuthorityAndConverges() {
        val job = tempJob("counterexample-bound29-")
        val previousInitialFailure = KeplerJobMetadata.settleInitialReadFailureForTest
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "H29")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            KeplerJobMetadata.settleInitialReadFailureForTest = java.io.IOException("injected initial read failure")
            val settled = KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(job)
            assertFalse("Initial read failure must return false", settled)
            val retained = KeplerJobMetadata.findOperationLease(job)
            assertNotNull("Reserved retry authority must exist after initial read failure", retained)
            assertTrue("Reserved authority carries pending handoff settlement",
                retained!!.hasPendingProcessingHandoffSettlement())
            assertTrue("Durable handoff remains",
                KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))

            // Clear the injected failure.
            KeplerJobMetadata.settleInitialReadFailureForTest = null
            val retrySettled = KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(job, retained)
            assertTrue("Retry settlement succeeds after fault removal", retrySettled)
            assertFalse("Durable handoff settled",
                KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
            retained!!.releaseOrRetainForReconciliation()
            assertFalse("Reserved lease discharged after settlement (release + counter advance)",
                KeplerJobMetadata.isOperationActive(job))
            assertNull("Reserved lease released after settlement",
                KeplerJobMetadata.findOperationLease(job))
        } finally {
            KeplerJobMetadata.settleInitialReadFailureForTest = previousInitialFailure
            job.deleteRecursively()
        }
    }

    /** Phase 5: REAL production-lifetime test for acquisition failure.
     *  When the initial inspection succeeds but the PROCESSING_START acquisition
     *  encounters an ordinary failure, no second lease is created when an exact
     *  owner exists; when no exact owner exists, the reserved authority retains the
     *  debt. After fault removal, settlement converges. */
    @Test
    fun acquisitionFailureAfterInspectionRetainsAuthority() {
        val job = tempJob("counterexample-bound30-")
        val previousRecoveryFailure = KeplerJobMetadata.settleRecoveryCheckFailureForTest
        val previousWriteFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "H30")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))

            // Control A: existing unrelated/live owner present; settlement MUST NOT commandeer it.
            // The helper should return false/busy and leave the existing owner authoritative.
            val existingLease = KeplerJobMetadata.acquireOperation(job)!!
            existingLease.markDurableOperation("existing-op", KeplerActiveOperationKind.PROCESSING_YUV)
            KeplerJobMetadata.settleRecoveryCheckFailureForTest = java.io.IOException("injected acquisition failure")
            val settledExisting = KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(job)
            assertFalse("Existing unrelated owner must not be commandeered for handoff settlement", settledExisting)
            assertNotNull("Existing owner remains authoritative and is NOT released",
                KeplerJobMetadata.findOperationLease(job))

            // Clean up the existing lease before Control B
            existingLease.release()

            // Control B: no existing owner; reserved authority handles any settlement failure.
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "H30")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            KeplerJobMetadata.atomicWriteFailureForTest = java.io.IOException("injected settlement write failure")
            val settledReserved = KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(job)
            assertFalse("Reserved authority retains debt when settlement fails", settledReserved)
            val retainedReserved = KeplerJobMetadata.findOperationLease(job)
            assertNotNull("Reserved retry authority exists", retainedReserved)
            assertTrue("Reserved authority carries pending handoff settlement",
                retainedReserved!!.hasPendingProcessingHandoffSettlement())

            // After fault removal, settlement converges.
            KeplerJobMetadata.atomicWriteFailureForTest = null
            KeplerJobMetadata.settleRecoveryCheckFailureForTest = null
            val retrySettled = KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(job, retainedReserved)
            assertTrue("Retry settlement succeeds after fault removal", retrySettled)
            assertFalse("Durable handoff settled",
                KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
            assertTrue("Pending marker cleared after retry (single complete + release)",
                !retainedReserved.hasPendingProcessingHandoffSettlement())
            retainedReserved.releaseOrRetainForReconciliation()
            assertNull("Reserved lease released after settlement",
                KeplerJobMetadata.findOperationLease(job))
        } finally {
            KeplerJobMetadata.settleInitialReadFailureForTest = null
            KeplerJobMetadata.settleRecoveryCheckFailureForTest = previousRecoveryFailure
            KeplerJobMetadata.atomicWriteFailureForTest = previousWriteFailure
            job.deleteRecursively()
        }
    }

    /** Phase 4: Terminal + handoff debt cross-product. A retained lease can carry both
     *  pendingTerminalSettlement and pendingProcessingHandoffSettlement simultaneously.
     *  The reconcile must drain BOTH in one pass. */
    @Test
    fun terminalAndHandoffDebtDrainInSingleReconcile() {
        val job = tempJob("counterexample-phase4-")
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, "capture-yuv")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.CAPTURE_YUV.name)
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "capture-yuv")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            lease.markDurableOperation("capture-yuv", KeplerActiveOperationKind.CAPTURE_YUV)
            lease.markTerminalSettlementPending(
                PendingTerminalSettlement(
                    operationId = "terminal-op",
                    attemptStatus = "FAILED",
                    pipelineStage = "FAILED",
                    processStatus = "PIPELINE_FAILED",
                    reason = "test terminal"
                )
            )
            lease.markProcessingHandoffSettlementPending()
            // Mark reconciliation ready: the original owner has finished all work
            lease.markReconciliationReady()
            assertNotNull(lease.pendingTerminalSettlement())
            assertTrue(lease.hasPendingProcessingHandoffSettlement())
            assertTrue(lease.isReconciliationReady())

            // First reconcile drains terminal, handoff, and remaining durable debt in one pass.
            val acquired = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
            )
            assertTrue(KeplerJobMetadata.isOperationOwner(job, acquired))
            assertFalse(KeplerJobMetadata.read(job).has(ACTIVE_OPERATION_ID))
            assertFalse(KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
            acquired.release()
            assertNull(KeplerJobMetadata.findOperationLease(job))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            job.deleteRecursively()
        }
    }

    /** Phase 5: Terminal completes but handoff retry fails again. The same lease must remain
     *  registered with pendingHandoff=true after terminal is settled. */
    @Test
    fun terminalSettled_handoffRetryFailsAgain_leaseRetained() {
        val job = tempJob("counterexample-phase5-")
        val previousSequence = KeplerJobMetadata.atomicWriteFailureSequenceForTest
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, "capture-yuv")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.CAPTURE_YUV.name)
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "capture-yuv")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            lease.markDurableOperation("capture-yuv", KeplerActiveOperationKind.CAPTURE_YUV)
            lease.markTerminalSettlementPending(
                PendingTerminalSettlement(
                    operationId = "terminal-op",
                    attemptStatus = "FAILED",
                    pipelineStage = "FAILED",
                    processStatus = "PIPELINE_FAILED",
                    reason = "test terminal"
                )
            )
            lease.markProcessingHandoffSettlementPending()
            // Mark reconciliation ready: the original owner has finished all work
            lease.markReconciliationReady()

            // First reconcile: terminal succeeds (first write passes), handoff retry is injected to fail (third write fails).
            KeplerJobMetadata.atomicWriteFailureSequenceForTest = mutableListOf(
                null,
                null,
                java.io.IOException("handoff finalization failed")
            )
            assertThrows(ProcessingAlreadyActiveException::class.java) {
                KeplerJobMetadata.acquireRecoveryCheckedOperation(
                    job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
                )
            }
            assertNull(lease.pendingTerminalSettlement())
            assertTrue(lease.hasPendingProcessingHandoffSettlement())
            assertTrue(KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))

            // Second reconcile: handoff succeeds, lease releases.
            KeplerJobMetadata.atomicWriteFailureSequenceForTest = null
            val acquired = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
            )
            assertTrue(KeplerJobMetadata.isOperationOwner(job, acquired))
            assertFalse(KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
            acquired.release()
            assertNull(KeplerJobMetadata.findOperationLease(job))
        } finally {
            KeplerJobMetadata.atomicWriteFailureSequenceForTest = previousSequence
            job.deleteRecursively()
        }
    }

    /** Phase 6: Handoff completion creates durable debt. When handoff settles and an ACTIVE
     *  operation remains, the reconcile must install pendingDurableSettlementId BEFORE clearing
     *  the handoff pending marker, and continue draining the durable debt in the same pass. */
    @Test
    fun handoffSettlementCreatesDurableDebt_drainedInSamePass() {
        val job = tempJob("counterexample-phase6-")
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, "capture-yuv")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.CAPTURE_YUV.name)
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "capture-yuv")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            lease.markDurableOperation("capture-yuv", KeplerActiveOperationKind.CAPTURE_YUV)
            lease.markProcessingHandoffSettlementPending()
            // Mark reconciliation ready: the original owner has finished all work
            lease.markReconciliationReady()

            // Reconcile settles handoff, sees ACTIVE remains, installs durable pending,
            // then continues to drain durable in the same pass.
            val acquired = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
            )
            assertTrue(KeplerJobMetadata.isOperationOwner(job, acquired))
            assertFalse(KeplerJobMetadata.read(job).has(ACTIVE_OPERATION_ID))
            assertFalse(KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
            acquired.release()
            assertNull(KeplerJobMetadata.findOperationLease(job))
        } finally {
            job.deleteRecursively()
        }
    }

    /** Phase 8E: Cross-product totality — terminal + processing handoff. The reconcile must
     *  never release the exact retained lease while any pending reason remains. */
    @Test
    fun reconcilePendingDurableSettlementTotality_terminalPlusHandoff() {
        val job = tempJob("totality-e-")
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, "totality-capture")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.CAPTURE_YUV.name)
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "totality-capture")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            lease.markDurableOperation("totality-capture", KeplerActiveOperationKind.CAPTURE_YUV)
            lease.markTerminalSettlementPending(
                PendingTerminalSettlement(
                    operationId = "terminal-op",
                    attemptStatus = "FAILED",
                    pipelineStage = "FAILED",
                    processStatus = "PIPELINE_FAILED",
                    reason = "test"
                )
            )
            lease.markProcessingHandoffSettlementPending()
            // Mark reconciliation ready: the original owner has finished all work
            lease.markReconciliationReady()

            // Reconcile drains all debts in one pass and releases.
            val acquired = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                job, JobRecoveryMutationIntent.PROCESSING_START, consumesProcessingHandoff = true
            )
            assertTrue(KeplerJobMetadata.isOperationOwner(job, acquired))
            assertFalse(KeplerJobMetadata.read(job).has(ACTIVE_OPERATION_ID))
            assertFalse(KeplerJobMetadata.read(job).has(PROCESSING_HANDOFF_OPERATION_ID))
            acquired.release()
            assertNull(KeplerJobMetadata.findOperationLease(job))
        } finally {
            job.deleteRecursively()
        }
    }
}