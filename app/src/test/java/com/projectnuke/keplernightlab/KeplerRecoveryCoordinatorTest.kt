package com.projectnuke.keplernightlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class KeplerRecoveryCoordinatorTest {
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
}
