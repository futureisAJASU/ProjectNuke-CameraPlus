package com.projectnuke.keplernightlab

import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class KeplerRecoveryCoordinatorTest {
    private class ExactExportAccess(
        private val failingUri: String? = null
    ) : MediaStoreExportRecoveryAccess {
        override fun inspect(uri: Uri, journal: MediaStoreExportJournal): MediaStoreExportInspection {
            if (uri.toString() == failingUri) throw IllegalStateException("injected inspection failure")
            return MediaStoreExportInspection(exists = true, pending = false, verified = true)
        }
        override fun setPending(uri: Uri, pending: Boolean) = true
        override fun delete(uri: Uri) = true
    }

    @Test
    fun foreignDurableOwnerBlocksMutationBeforeStartupRecovery() {
        val job = Files.createTempDirectory("kepler-gate-owner-").toFile()
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, "old-export")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put("recoveryState", "STABLE"))
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_DEAD_OPERATION,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_DELETE)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    @Test
    fun currentHandoffOnlyAllowsItsExplicitProcessingConsumer() {
        val job = Files.createTempDirectory("kepler-gate-handoff-").toFile()
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, "capture-1")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.CAPTURE_YUV.name)
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "capture-1")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(
                    job,
                    JobRecoveryMutationIntent.PROCESSING_START,
                    consumesProcessingHandoff = true
                )
            )
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_DEAD_OPERATION,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_CLEANUP)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    @Test
    fun malformedProcessingNamespaceEntryBlocksEveryOrdinaryMutation() {
        val job = Files.createTempDirectory("kepler-gate-invalid-").toFile()
        try {
            KeplerJobMetadata.write(job, JSONObject().put("recoveryState", "STABLE"))
            File(job, ".processing_tx_invalid.json").mkdirs()
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_INVALID_PROCESSING_JOURNAL,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_CLEANUP)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    @Test
    fun currentRuntimeMarkerWithoutProcessLeaseIsReconciledByForcedRecovery() {
        val parent = Files.createTempDirectory("kepler-recovery-current-owner-").toFile()
        val root = File(parent, "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_ORPHAN").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, "orphan-current-operation")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name)
                .put("currentPipelineStage", "PROCESSING")
                .put("recoveryState", "STABLE"))

            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT, report.jobs.single().classification)
            assertTrue(!KeplerJobMetadata.read(job).has(ACTIVE_OPERATION_ID))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun activeExportJournalBlocksDestructiveMutationBeforeRecovery() {
        val job = Files.createTempDirectory("kepler-gate-export-").toFile()
        try {
            KeplerJobMetadata.write(job, JSONObject().put("recoveryState", "STABLE"))
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, "result.jpg",
                "Pictures/Kepler", "image/jpeg", Uri.parse("content://media/external/images/media"),
                ownerOperationId = "old-export"
            )
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_DEAD_OPERATION,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_DELETE)
            )
        } finally {
            job.deleteRecursively()
        }
    }

    @Test
    fun residualSettledProcessingJournalBlocksReplacementUntilItIsRemoved() {
        val job = Files.createTempDirectory("kepler-gate-settled-").toFile()
        try {
            KeplerJobMetadata.write(job, JSONObject().put("jobType", "YUV_NIGHT_FUSION"))
            val attempt = beginProcessingAttempt(job, "CLASSIC_YUV")
            val final = File(job, "final.bin")
            processingArtifactJournalDeleteFailureForTest = true
            try {
                commitProcessingArtifact(
                    finalFile = final,
                    writeTemp = { it.writeBytes(byteArrayOf(1, 2, 3)) },
                    verifyFinal = { check(it.readBytes().contentEquals(byteArrayOf(1, 2, 3))) },
                    processingAttemptId = attempt.id,
                    claimKey = "finalFile"
                )
                markProcessingArtifactClaim(job, attempt, "finalFile", final)
            } finally {
                attempt.releaseOwnedLease()
            }
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_SETTLED_JOURNAL,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.PROCESSING_START)
            )
            assertTrue(ProcessingArtifactJournal.list(job).isNotEmpty())
            processingArtifactJournalDeleteFailureForTest = false
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.PROCESSING_START)
            )
            assertTrue(ProcessingArtifactJournal.list(job).isEmpty())
        } finally {
            processingArtifactJournalDeleteFailureForTest = false
            job.deleteRecursively()
        }
    }

    @Test
    fun missingMetadataWithEvidenceIsNotAnEmptyInitializationTarget() {
        val job = Files.createTempDirectory("kepler-gate-orphan-").toFile()
        try {
            File(job, "frame_00.yuv").writeBytes(byteArrayOf(1))
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_ORPHANED_JOB_METADATA,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_DELETE)
            )
            val empty = Files.createTempDirectory("kepler-gate-empty-").toFile()
            try {
                assertEquals(
                    JobRecoveryMutationGateOutcome.ALLOWED,
                    KeplerJobMetadata.inspectRecoveryMutationGate(empty, JobRecoveryMutationIntent.PROCESSING_START)
                )
            } finally {
                empty.deleteRecursively()
            }
        } finally {
            job.deleteRecursively()
        }
    }
    @Test
    fun oneBrokenJobDoesNotAbortHealthyRootScan() {
        val root = File(Files.createTempDirectory("kepler-recovery-root-").toFile(), "KeplerRawFusion").apply { mkdirs() }
        try {
            val healthy = File(root, "KPL_RAW_FUSION_healthy").apply { mkdirs() }
            KeplerJobMetadata.write(healthy, JSONObject().put("status", "COMPLETE"))
            val corrupt = File(root, "KPL_RAW_FUSION_corrupt").apply { mkdirs() }
            File(corrupt, JOB_JSON_FILE_NAME).writeText("not-json")

            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))

            assertEquals(2, report.jobs.size)
            assertTrue(report.jobs.any { it.jobDir == healthy && it.classification == KeplerJobRecoveryClassification.RECOVERED })
            assertTrue(report.jobs.any { it.jobDir == corrupt && it.classification == KeplerJobRecoveryClassification.CORRUPT_JOB_METADATA })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun currentProcessLeaseIsNeverMutatedByRecovery() {
        val root = File(Files.createTempDirectory("kepler-recovery-active-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_active").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(job, JSONObject().put("status", "PROCESSING"))
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            try {
                val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
                assertEquals(KeplerJobRecoveryClassification.SKIP_ACTIVE_CURRENT_PROCESS, report.jobs.single().classification)
                assertEquals("PROCESSING", KeplerJobMetadata.read(job).getString("status"))
            } finally {
                lease.release()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptAndMissingMetadataProduceRecoverySummariesWithoutDroppingJobs() {
        val root = Files.createTempDirectory("kepler-gallery-isolation-").toFile()
        try {
            val corrupt = File(root, "KPL_RAW_FUSION_corrupt").apply { mkdirs() }
            File(corrupt, JOB_JSON_FILE_NAME).writeText("not-json")
            val orphan = File(root, "KPL_YUV_FUSION_orphan").apply { mkdirs() }
            File(orphan, "frame_00.yuv").writeBytes(byteArrayOf(1))

            val summaries = listOf(corrupt, orphan).map { directory ->
                val metadata = NoFollowFileSystem.resolveDirectChildResult(directory, JOB_JSON_FILE_NAME, requireFile = true)
                if (metadata is NoFollowInspection.Absent) {
                    recoveryGallerySummary(directory, KeplerJobMetadataMissing(directory))
                } else {
                    runCatching { readKeplerGalleryJob(directory) }
                        .getOrElse { recoveryGallerySummary(directory, it) }
                }
            }

            assertEquals(setOf("METADATA_CORRUPT", "ORPHANED_JOB_METADATA"), summaries.map { it.recoveryState }.toSet())
            assertTrue(summaries.all { !it.recoveryMessage.isNullOrBlank() })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unresolvedReprocessQuarantineStopsGenericRecovery() {
        val root = File(Files.createTempDirectory("kepler-recovery-quarantine-").toFile(), "KeplerRawFusion").apply { mkdirs() }
        val job = File(root, "KPL_RAW_FUSION_quarantine").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(job, JSONObject().put("status", "PROCESSING").put("activeOperationId", "old"))
            File(job, ".reprocess_unresolved").writeText("unresolved")
            val evidence = File(job, ".processing_tx_keep.json").apply { writeText("preserve") }
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(KeplerJobRecoveryClassification.REPROCESS_QUARANTINED, report.jobs.single().classification)
            assertTrue(evidence.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedExportJournalIsPreservedAsAmbiguousEvidence() {
        val root = File(Files.createTempDirectory("kepler-recovery-export-journal-").toFile(), "KeplerRawFusion").apply { mkdirs() }
        val job = File(root, "KPL_RAW_FUSION_bad_export").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(job, JSONObject().put("status", "PROCESSING"))
            File(job, ".export_tx_broken.json").writeText("not-json")
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED, report.jobs.single().classification)
            assertTrue(File(job, ".export_tx_broken.json").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun processingJournalOnlyInvalidEvidenceIsVisibleToStartupRecovery() {
        val root = File(Files.createTempDirectory("kepler-recovery-processing-journal-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_bad_processing").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(job, JSONObject().put("status", "COMPLETE").put("recoveryState", "STABLE"))
            File(job, ".processing_tx_broken.json").mkdirs()
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED, report.jobs.single().classification)
            assertEquals("AMBIGUOUS_RECOVERY_REQUIRED", KeplerJobMetadata.read(job).getString("recoveryState"))
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_INVALID_PROCESSING_JOURNAL,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_DELETE)
            )
            assertTrue(File(job, ".processing_tx_broken.json").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unconsumedProcessingHandoffIsInterruptedWithoutClaimingProcessingSuccess() {
        val root = File(Files.createTempDirectory("kepler-recovery-handoff-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_handoff").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("status", "PROCESSING")
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, "old-runtime")
                .put(PROCESSING_HANDOFF_OPERATION_ID, "handoff-1")
                .put(PROCESSING_HANDOFF_KIND, "PROCESSING_YUV"))
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT, report.jobs.single().classification)
            val recovered = KeplerJobMetadata.read(job)
            assertEquals(false, recovered.optBoolean("processingOutputCommitted", false))
            assertEquals("STABLE", recovered.getString("recoveryState"))
            assertEquals("INTERRUPTED_PRE_COMMIT", recovered.getString("lastRecoveryClassification"))
            assertEquals("", recovered.optString(PROCESSING_HANDOFF_OPERATION_ID))
            assertEquals("", recovered.optString(PROCESSING_HANDOFF_RUNTIME_SESSION_ID))
            val second = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertTrue(second.jobs.single().classification != KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun adoptedProcessingArtifactReconstructsMissingJobClaimBeforeClearingDeadOwner() {
        val root = File(Files.createTempDirectory("kepler-recovery-adopted-claim-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_adopted-claim").apply { mkdirs() }
        try {
            val attemptId = "processing-attempt-1"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "RAW_NIGHT_FUSION")
                .put("status", "PROCESSING")
                .put("processingAttemptId", attemptId)
                .put("processingOutputCommitted", false)
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, attemptId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PROCESSING_RAW.name))
            val final = File(job, "merged.raw")
            commitProcessingArtifact(
                finalFile = final,
                writeTemp = { it.writeBytes(byteArrayOf(1, 2, 3, 4)) },
                verifyFinal = { check(it.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4))) },
                processingAttemptId = attemptId,
                claimKey = "mergedRawFile"
            )

            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))

            assertEquals(KeplerJobRecoveryClassification.LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL, report.jobs.single().classification)
            val recovered = KeplerJobMetadata.read(job)
            assertTrue(recovered.getBoolean("processingOutputCommitted"))
            assertEquals(final.name, recovered.getString("mergedRawFile"))
            assertEquals(attemptId, recovered.getString("processingArtifactClaimAttemptId"))
            assertEquals("", recovered.optString(ACTIVE_OPERATION_ID))
            assertTrue(ProcessingArtifactJournal.list(job).isEmpty())
            assertTrue(final.exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun processingCleanupDebtBlocksNewAttemptUntilARecoveryRetrySettlesIt() {
        val root = File(Files.createTempDirectory("kepler-recovery-cleanup-debt-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_cleanup-debt").apply { mkdirs() }
        val priorBytes = byteArrayOf(9, 8, 7)
        val finalBytes = byteArrayOf(1, 2, 3)
        try {
            KeplerJobMetadata.write(job, JSONObject().put("jobType", "YUV_NIGHT_FUSION"))
            val attempt = beginProcessingAttempt(job, "CLASSIC_YUV")
            val final = File(job, "final.bin")
            final.writeBytes(priorBytes)
            processingArtifactDeleteFailureForTest = true
            try {
                commitProcessingArtifact(
                    finalFile = final,
                    writeTemp = { it.writeBytes(finalBytes) },
                    verifyFinal = { check(it.readBytes().contentEquals(finalBytes)) },
                    processingAttemptId = attempt.id,
                    claimKey = "finalFile"
                )
                markProcessingArtifactClaim(job, attempt, "finalFile", final)
            } finally {
                attempt.releaseOwnedLease()
            }

            val first = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(KeplerJobRecoveryClassification.PROCESSING_CLEANUP_REQUIRED, first.jobs.single().classification)
            assertEquals(PROCESSING_CLEANUP_REQUIRED, KeplerJobMetadata.read(job).getString("recoveryState"))
            assertEquals(finalBytes.toList(), final.readBytes().toList())
            org.junit.Assert.assertThrows(ProcessingCleanupRequiredException::class.java) {
                beginProcessingAttempt(job, "CLASSIC_YUV")
            }

            processingArtifactDeleteFailureForTest = false
            val second = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals("report=$second", KeplerJobRecoveryClassification.LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL, second.jobs.single().classification)
            assertEquals("STABLE", KeplerJobMetadata.read(job).getString("recoveryState"))
            assertTrue(ProcessingArtifactJournal.list(job).isEmpty())
            assertEquals(finalBytes.toList(), final.readBytes().toList())
        } finally {
            processingArtifactDeleteFailureForTest = false
            root.deleteRecursively()
        }
    }

    @Test
    fun failedInterruptedFinalizationIsNotReportedAsRecovered() {
        val root = File(Files.createTempDirectory("kepler-recovery-finalize-failure-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_finalize-failure").apply { mkdirs() }
        try {
            val operationId = "dead-processing-finalize-failure"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("status", "PROCESSING")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name))
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("injected metadata failure")
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(KeplerJobRecoveryClassification.RECOVERY_FAILED, report.jobs.single().classification)
            assertEquals(operationId, KeplerJobMetadata.read(job).optString(ACTIVE_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = null
            root.deleteRecursively()
        }
    }

    @Test
    fun fatalInterruptedFinalizationErrorEscapesCoordinatorAndPreservesOwner() {
        val root = File(Files.createTempDirectory("kepler-recovery-finalize-fatal-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_finalize-fatal").apply { mkdirs() }
        try {
            val operationId = "dead-processing-finalize-fatal"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("status", "PROCESSING")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name))
            KeplerJobMetadata.atomicWriteFailureForTest = AssertionError("fatal interrupted finalization")
            assertThrows(AssertionError::class.java) {
                KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            }
            val retained = KeplerJobMetadata.read(job)
            assertEquals(operationId, retained.optString(ACTIVE_OPERATION_ID))
            assertFalse(retained.optString("recoveryState") == "STABLE")
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = null
            root.deleteRecursively()
        }
    }

    @Test
    fun ordinaryTerminalFinalizationFailureRemainsRecoveryFailedAndPreservesOwner() {
        val root = File(Files.createTempDirectory("kepler-recovery-terminal-finalize-failure-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_terminal-finalize-failure").apply { mkdirs() }
        try {
            val operationId = "terminal-finalize-failure"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/external/images/media/101")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put(TERMINAL_OPERATION_ID, operationId))
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("ordinary terminal finalization")
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())
            assertEquals(KeplerJobRecoveryClassification.RECOVERY_FAILED, report.jobs.single().classification)
            assertEquals(operationId, KeplerJobMetadata.read(job).optString(ACTIVE_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = null
            root.deleteRecursively()
        }
    }

    @Test
    fun fatalTerminalFinalizationErrorEscapesCoordinatorAndPreservesOwner() {
        val root = File(Files.createTempDirectory("kepler-recovery-terminal-finalize-fatal-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_terminal-finalize-fatal").apply { mkdirs() }
        try {
            val operationId = "terminal-finalize-fatal"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/external/images/media/102")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put(TERMINAL_OPERATION_ID, operationId))
            KeplerJobMetadata.atomicWriteFailureForTest = AssertionError("fatal terminal finalization")
            assertThrows(AssertionError::class.java) {
                KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())
            }
            val retained = KeplerJobMetadata.read(job)
            assertEquals(operationId, retained.optString(ACTIVE_OPERATION_ID))
            assertFalse(retained.optString("recoveryState") == "STABLE")
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = null
            root.deleteRecursively()
        }
    }

    @Test
    fun verifiedMainCommitReconstructsJobTruthForOwningDeadExportOperation() {
        val root = File(Files.createTempDirectory("kepler-recovery-main-export-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_export").apply { mkdirs() }
        try {
            val operationId = "dead-export-operation"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("status", "EXPORTING")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
            MediaStoreExportJournal.create(
                jobDir = job,
                role = MediaStoreExportRole.MAIN_IMAGE,
                frameIndex = null,
                displayName = "result.jpg",
                relativePath = "Pictures/Kepler",
                mimeType = "image/jpeg",
                collectionUri = Uri.parse("content://media/external/images/media"),
                ownerOperationId = operationId
            ).transition(job, MediaStoreExportState.PUBLIC_COMMITTED, "content://media/external/images/media/77")
                .transition(job, MediaStoreExportState.VERIFIED)

            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())
            assertEquals(KeplerJobRecoveryClassification.PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL, report.jobs.single().classification)
            val recovered = KeplerJobMetadata.read(job)
            assertTrue(recovered.getBoolean("galleryExportCommitted"))
            assertTrue(recovered.getBoolean("exportVerified"))
            assertEquals("content://media/external/images/media/77", recovered.getString("exportUri"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun verifiedSidecarCannotSuppressCurrentMainInsertAmbiguity() {
        val root = File(Files.createTempDirectory("kepler-recovery-correlated-main-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_correlated-main").apply { mkdirs() }
        try {
            val operationId = "current-export"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("status", "EXPORTING")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.RAW_DNG_SIDECAR, 0, "frame.dng", "Pictures/Kepler",
                "image/x-adobe-dng", Uri.parse("content://media/external/images/media"),
                ownerOperationId = "historical-export"
            ).transition(job, MediaStoreExportState.VERIFIED, "content://media/external/images/media/90")
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, "result.jpg", "Pictures/Kepler",
                "image/jpeg", Uri.parse("content://media/external/images/media"), ownerOperationId = operationId)
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())
            assertEquals(KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED, report.jobs.single().classification)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun terminalOperationIdClearsDeadExportMarkerAfterRestart() {
        val root = File(Files.createTempDirectory("kepler-recovery-terminal-owner-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_terminal-owner").apply { mkdirs() }
        try {
            val operationId = "terminal-export"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/external/images/media/91")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put(TERMINAL_OPERATION_ID, operationId))
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())
            assertEquals(KeplerJobRecoveryClassification.RECOVERED, report.jobs.single().classification)
            assertEquals("", KeplerJobMetadata.read(job).optString(ACTIVE_OPERATION_ID))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun terminalExportJournalRecoveryAtomicallyBecomesStableAndRemainsStable() {
        val root = File(Files.createTempDirectory("kepler-recovery-terminal-cut-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_terminal-cut").apply { mkdirs() }
        try {
            val operationId = "terminal-cut-operation"
            val uri = "content://media/external/images/media/92"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", uri)
                .put("recoveryState", "PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put(TERMINAL_OPERATION_ID, operationId))
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, "result.jpg", "Pictures/Kepler",
                "image/jpeg", Uri.parse("content://media/external/images/media"), ownerOperationId = operationId
            ).transition(job, MediaStoreExportState.VERIFIED, uri)
            val first = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())
            assertEquals(KeplerJobRecoveryClassification.RECOVERED, first.jobs.single().classification)
            val stable = KeplerJobMetadata.read(job)
            assertEquals("STABLE", stable.getString("recoveryState"))
            assertEquals("", stable.optString(ACTIVE_OPERATION_ID))
            assertEquals(uri, stable.getString("exportUri"))
            val second = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())
            assertEquals(KeplerJobRecoveryClassification.RECOVERED, second.jobs.single().classification)
            assertEquals("STABLE", KeplerJobMetadata.read(job).getString("recoveryState"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun currentRecoveryPassAuthorizesHistoricalSidecarUri() {
        val root = File(Files.createTempDirectory("kepler-recovery-sidecar-pass-").toFile(), "KeplerRawFusion").apply { mkdirs() }
        val job = File(root, "KPL_RAW_FUSION_sidecar-pass").apply { mkdirs() }
        try {
            val dng = File(job, "frame_04.dng").apply { writeBytes(byteArrayOf(0x49, 0x49, 0x2a, 0x00, 5)) }
            val uri = "content://media/external/file/94"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "RAW_NIGHT_FUSION")
                .put("status", "EXPORTING")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, "current-export")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put("frames", JSONArray().put(JSONObject()
                    .put("frameIndex", 4)
                    .put("dngFile", dng.name)
                    .put("dngSidecarStatus", "LOCAL_SAVED"))))
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.RAW_DNG_SIDECAR, 4, dng.name, "Pictures/Kepler/RAW",
                "image/x-adobe-dng", Uri.parse("content://media/external/file"),
                expectedSizeBytes = dng.length(), expectedSha256 = NoFollowFileSystem.digestVerified(dng).sha256,
                ownerOperationId = "historical-export"
            ).transition(job, MediaStoreExportState.VERIFIED, uri)
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())
            assertTrue(report.jobs.single().classification != KeplerJobRecoveryClassification.RECOVERY_FAILED)
            assertEquals("PUBLIC_EXPORTED", KeplerJobMetadata.read(job).getJSONArray("frames").getJSONObject(0).getString("dngSidecarPublicStatus"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun sidecarInspectionFailureDoesNotReconstructHistoricalUri() {
        val root = File(Files.createTempDirectory("kepler-recovery-sidecar-failure-").toFile(), "KeplerRawFusion").apply { mkdirs() }
        val job = File(root, "KPL_RAW_FUSION_sidecar-failure").apply { mkdirs() }
        try {
            val dng = File(job, "frame_05.dng").apply { writeBytes(byteArrayOf(0x49, 0x49, 0x2a, 0x00, 6)) }
            val sidecarUri = "content://media/external/file/95"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "RAW_NIGHT_FUSION")
                .put("status", "EXPORTING")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, "current-export")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put("frames", JSONArray().put(JSONObject().put("frameIndex", 5).put("dngFile", dng.name).put("dngSidecarStatus", "LOCAL_SAVED"))))
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.RAW_DNG_SIDECAR, 5, dng.name, "Pictures/Kepler/RAW",
                "image/x-adobe-dng", Uri.parse("content://media/external/file"),
                expectedSizeBytes = dng.length(), expectedSha256 = NoFollowFileSystem.digestVerified(dng).sha256,
                ownerOperationId = "historical-export"
            ).transition(job, MediaStoreExportState.VERIFIED, sidecarUri)
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, "result.jpg", "Pictures/Kepler",
                "image/jpeg", Uri.parse("content://media/external/images/media"), ownerOperationId = "current-export"
            ).transition(job, MediaStoreExportState.VERIFIED, "content://media/external/images/media/97")
            KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess(sidecarUri))
            val frame = KeplerJobMetadata.read(job).getJSONArray("frames").getJSONObject(0)
            assertTrue(!frame.has("publicDngUri") || frame.optString("publicDngUri").isBlank())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun historicalMalformedExportDebtDoesNotBlockProvenStableResult() {
        val root = File(Files.createTempDirectory("kepler-recovery-historical-malformed-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_historical-malformed").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/external/images/media/96")
                .put("recoveryState", "STABLE"))
            File(job, ".export_tx_historical.json").writeText("not-json")
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())
            assertEquals(KeplerJobRecoveryClassification.RECOVERED, report.jobs.single().classification)
            assertEquals("STABLE", KeplerJobMetadata.read(job).getString("recoveryState"))
            assertTrue(File(job, ".export_tx_historical.json").exists())
            assertTrue(report.jobs.single().failures.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun historicalMalformedExportDoesNotBlockCurrentCorrelatedPublicExportSettlement() {
        val root = File(Files.createTempDirectory("kepler-recovery-historical-invalid-current-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_historical-invalid-current").apply { mkdirs() }
        try {
            val historical = File(job, ".export_tx_historical-corrupt.json").apply {
                writeText("not-json")
                setLastModified(1L)
            }
            val operationId = "current-public-export"
            val startedAt = System.currentTimeMillis()
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("status", "EXPORTING")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put(ACTIVE_OPERATION_STARTED_AT, startedAt))
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, "new.jpg", "Pictures/Kepler",
                "image/jpeg", Uri.parse("content://media/external/images/media"), ownerOperationId = operationId
            ).transition(job, MediaStoreExportState.VERIFIED, "content://media/external/images/media/new")

            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())

            assertEquals(KeplerJobRecoveryClassification.PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL, report.jobs.single().classification)
            val recovered = KeplerJobMetadata.read(job)
            assertEquals("STABLE", recovered.getString("recoveryState"))
            assertEquals("content://media/external/images/media/new", recovered.getString("exportUri"))
            assertTrue(historical.exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun historicalMalformedExportDoesNotPoisonDeadZeroJournalPreCommitOperation() {
        val root = File(Files.createTempDirectory("kepler-recovery-zero-journal-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_zero-journal").apply { mkdirs() }
        try {
            val startedAt = System.currentTimeMillis()
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "PROCESSING")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/old-uri")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, "new-public-export")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put(ACTIVE_OPERATION_STARTED_AT, startedAt))
            val historical = File(job, ".export_tx_historical-corrupt.json").apply {
                writeText("not-json")
                setLastModified(1L)
            }

            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())
            val result = report.jobs.single()
            val settled = KeplerJobMetadata.read(job)
            assertEquals(KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT, result.classification)
            assertEquals("STABLE", settled.getString("recoveryState"))
            assertEquals("content://media/old-uri", settled.getString("exportUri"))
            assertTrue(settled.getBoolean("galleryExportCommitted"))
            assertTrue(settled.getBoolean("exportVerified"))
            assertFalse(settled.has(ACTIVE_OPERATION_ID))
            assertTrue(historical.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun equalTimestampMalformedExportBlocksDeadCurrentPublicExportRecovery() {
        val root = File(Files.createTempDirectory("kepler-recovery-equal-timestamp-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_equal-timestamp").apply { mkdirs() }
        try {
            val startedAt = System.currentTimeMillis()
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "PROCESSING")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, "current-public-export")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put(ACTIVE_OPERATION_STARTED_AT, startedAt))
            val malformed = File(job, ".export_tx_equal-corrupt.json").apply {
                writeText("not-json")
                setLastModified(startedAt)
            }

            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())
            assertEquals(KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED, report.jobs.single().classification)
            assertEquals("AMBIGUOUS_RECOVERY_REQUIRED", KeplerJobMetadata.read(job).getString("recoveryState"))
            assertTrue(malformed.exists())
            assertEquals("current-public-export", KeplerJobMetadata.read(job).getString(ACTIVE_OPERATION_ID))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearedCurrentExportOperationReconstructsItsNewUriInsteadOfStaleMetadata() {
        val root = File(Files.createTempDirectory("kepler-recovery-cleared-export-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_cleared-export").apply { mkdirs() }
        try {
            val operationId = "cleared-current-export"
            val newUri = "content://media/external/images/media/new-after-clear"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "PARTIAL")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/external/images/media/old")
                .put("recoveryState", "STABLE")
                .put(TERMINAL_OPERATION_ID, operationId))
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, "new.jpg", "Pictures/Kepler",
                "image/jpeg", Uri.parse("content://media/external/images/media"), ownerOperationId = operationId
            ).transition(job, MediaStoreExportState.VERIFIED, newUri)

            KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())

            assertEquals(newUri, KeplerJobMetadata.read(job).getString("exportUri"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun oneMediaStoreRecoveryFailureDoesNotAbortLaterJobs() {
        val root = File(Files.createTempDirectory("kepler-recovery-isolation-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val broken = File(root, "KPL_YUV_FUSION_broken").apply { mkdirs() }
        val healthy = File(root, "KPL_YUV_FUSION_healthy").apply { mkdirs() }
        try {
            val brokenUri = "content://media/external/images/media/101"
            listOf(broken to brokenUri, healthy to "content://media/external/images/media/102").forEach { (job, uri) ->
                val operationId = "operation-${job.name}"
                KeplerJobMetadata.write(job, JSONObject()
                    .put("jobType", "YUV_NIGHT_FUSION")
                    .put("status", "EXPORTING")
                    .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                    .put(ACTIVE_OPERATION_ID, operationId))
                MediaStoreExportJournal.create(
                    jobDir = job,
                    role = MediaStoreExportRole.MAIN_IMAGE,
                    frameIndex = null,
                    displayName = "result.jpg",
                    relativePath = "Pictures/Kepler",
                    mimeType = "image/jpeg",
                    collectionUri = Uri.parse("content://media/external/images/media"),
                    ownerOperationId = operationId
                ).transition(job, MediaStoreExportState.PUBLIC_COMMITTED, uri)
            }
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess(brokenUri))
            assertEquals(2, report.jobs.size)
            assertEquals(KeplerJobRecoveryClassification.RECOVERY_FAILED, report.jobs.first { it.jobDir == broken }.classification)
            assertEquals(KeplerJobRecoveryClassification.PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL, report.jobs.first { it.jobDir == healthy }.classification)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun historicalAmbiguousExportDoesNotBlockDeadProcessingArtifactRecovery() {
        val root = File(Files.createTempDirectory("kepler-recovery-processing-authority-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_processing-authority").apply { mkdirs() }
        try {
            val processingOperation = "processing-operation"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("status", "PROCESSING")
                .put("processingOutputCommitted", true)
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, processingOperation)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name))
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, "old.jpg", "Pictures/Kepler",
                "image/jpeg", Uri.parse("content://media/external/images/media"), ownerOperationId = "old-export"
            )
            val prior = File(job, "result.bin").apply { writeBytes(byteArrayOf(4, 5, 6, 7)) }
            val journal = ProcessingArtifactJournal.create(
                jobDir = job,
                transactionId = java.util.UUID.randomUUID().toString(),
                processingAttemptId = null,
                artifactType = "BINARY",
                finalName = prior.name,
                tempName = ".result.tmp",
                priorName = ".result.prior"
            ).transition(job, ProcessingArtifactJournalState.PRIOR_BACKED_UP)
            val movedPrior = File(job, journal.priorName)
            prior.renameTo(movedPrior)
            val expected = NoFollowFileSystem.digestVerified(movedPrior)
            ProcessingArtifactJournal.read(ProcessingArtifactJournal.fileFor(job, journal.transactionId))
                .transition(job, ProcessingArtifactJournalState.PRIOR_BACKED_UP,
                    priorExpectedSizeBytesOverride = expected.size,
                    priorExpectedSha256Override = expected.sha256,
                    priorSemanticVerifiedOverride = true)
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())
            assertEquals(KeplerJobRecoveryClassification.LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL, report.jobs.single().classification)
            assertTrue(File(job, "result.bin").exists())
            assertEquals("", KeplerJobMetadata.read(job).optString(ACTIVE_OPERATION_ID))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun unknownInsertResultIsAmbiguousJobEvidence() {
        val root = File(Files.createTempDirectory("kepler-recovery-unknown-export-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_unknown").apply { mkdirs() }
        try {
            val operationId = "unknown-export"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId))
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, "result.jpg", "Pictures/Kepler",
                "image/jpeg", Uri.parse("content://media/external/images/media"), ownerOperationId = operationId
            )
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), ExactExportAccess())
            assertEquals(KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED, report.jobs.single().classification)
        } finally { root.deleteRecursively() }
    }
}
