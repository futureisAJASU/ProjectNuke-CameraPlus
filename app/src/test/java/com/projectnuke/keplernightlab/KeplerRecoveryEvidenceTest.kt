package com.projectnuke.keplernightlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class KeplerRecoveryEvidenceTest {
    @Test
    fun activeMarkerWithLocalCommitIsNotDowngradedToGenericInterrupted() {
        val root = File(Files.createTempDirectory("kepler-recovery-evidence-").toFile(), "KeplerRawFusion").apply { mkdirs() }
        try {
            val job = File(root, "KPL_RAW_FUSION_evidence").apply { mkdirs() }
            KeplerJobMetadata.write(
                job,
                JSONObject()
                    .put("status", "PROCESSING")
                    .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                    .put(ACTIVE_OPERATION_ID, "old-operation")
                    .put("processingOutputCommitted", true)
            )
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(KeplerJobRecoveryClassification.LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL, report.jobs.single().classification)
            assertEquals("STABLE", KeplerJobMetadata.read(job).getString("recoveryState"))
            assertEquals("LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL", KeplerJobMetadata.read(job).getString("lastRecoveryClassification"))
        } finally {
            root.deleteRecursively()
        }
    }
}
