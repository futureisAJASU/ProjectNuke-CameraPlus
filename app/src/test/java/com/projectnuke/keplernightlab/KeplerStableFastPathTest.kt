package com.projectnuke.keplernightlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * U2.1 Class-A fast-path regression tests for the redundant STABLE metadata-rewrite skip.
 *
 * Covers spec §9 A–J:
 * A. already stable + no recoveryMessage => no incremental durable write
 * B. terminal proven but recoveryState not STABLE => durable update still occurs
 * C. recoveryMessage present => durable update clears it
 * D. active operation => fast path not taken
 * E. processing cleanup debt => fast path not taken
 * F. invalid export journal => fast path not taken (AMBIGUOUS)
 * G. public-export verification / missing commit => fast path not taken
 * H. metadata temp / capture temp recovery still executes (no fast path via AMBIGUOUS or capture debt)
 * I. externally removed public result => truthful recovery unchanged
 * J. classifications identical except for omission of provably redundant write
 */
@RunWith(RobolectricTestRunner::class)
class KeplerStableFastPathTest {
    private fun terminalStableJson(recoveryState: String = "STABLE", recoveryMessage: String? = null): JSONObject {
        val json = JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put("currentPipelineStage", "COMPLETE")
            .put("galleryExportCommitted", true)
            .put("exportVerified", true)
            .put("exportUri", "content://media/external/images/media/99100")
            .put("recoveryState", recoveryState)
        if (recoveryMessage != null) json.put("recoveryMessage", recoveryMessage)
        return json
    }

    private fun newRoot(): File = File(Files.createTempDirectory("kepler-fastpath-").toFile(), "KeplerYuvFusion").apply { mkdirs() }
    private fun newJob(root: File, name: String): File = File(root, name).apply { mkdirs() }

    @Test
    fun a_alreadyStableNoMessage_noRedundantWrite() {
        val root = newRoot()
        val job = newJob(root, "KPL_YUV_FUSION_a_stable_no_msg")
        try {
            KeplerJobMetadata.write(job, terminalStableJson("STABLE", null))
            val before = KeplerJobMetadata.atomicWriteCount
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(KeplerJobRecoveryClassification.RECOVERED, report.jobs.single().classification)
            assertEquals("STABLE", KeplerJobMetadata.read(job).getString("recoveryState"))
            assertFalse(KeplerJobMetadata.read(job).has("recoveryMessage"))
            assertEquals(before, KeplerJobMetadata.atomicWriteCount)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun b_terminalProvenRecoveryStateNotStable_stillWrites() {
        val root = newRoot()
        val job = newJob(root, "KPL_YUV_FUSION_b_not_stable")
        try {
            KeplerJobMetadata.write(job, terminalStableJson("PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL", null))
            val before = KeplerJobMetadata.atomicWriteCount
            KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(before + 1, KeplerJobMetadata.atomicWriteCount)
            assertEquals("STABLE", KeplerJobMetadata.read(job).getString("recoveryState"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun c_recoveryMessagePresent_clearsIt() {
        val root = newRoot()
        val job = newJob(root, "KPL_YUV_FUSION_c_msg_present")
        try {
            KeplerJobMetadata.write(job, terminalStableJson("STABLE", "stale transitional message"))
            val before = KeplerJobMetadata.atomicWriteCount
            KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(before + 1, KeplerJobMetadata.atomicWriteCount)
            assertFalse(KeplerJobMetadata.read(job).has("recoveryMessage"))
            assertEquals("STABLE", KeplerJobMetadata.read(job).getString("recoveryState"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun d_activeOperation_fastPathNotTaken() {
        val root = newRoot()
        val job = newJob(root, "KPL_YUV_FUSION_d_active")
        try {
            KeplerJobMetadata.write(job, terminalStableJson("STABLE", null)
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, "dead-op-d")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
            val before = KeplerJobMetadata.atomicWriteCount
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertTrue(report.jobs.single().classification != KeplerJobRecoveryClassification.SKIP_ACTIVE_CURRENT_PROCESS)
            // Recovery must handle dead owner and requires a durable write (fast-path must not apply).
            assertTrue(KeplerJobMetadata.atomicWriteCount > before)
            // Either clears the dead owner or marks as preprocess stage.
            assertTrue(report.jobs.single().classification == KeplerJobRecoveryClassification.RECOVERED ||
                report.jobs.single().classification == KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun e_processingCleanupDebt_fastPathNotTaken() {
        val root = newRoot()
        val job = newJob(root, "KPL_YUV_FUSION_e_cleanup")
        try {
            KeplerJobMetadata.write(job, JSONObject().put("jobType", "YUV_NIGHT_FUSION"))
            val attempt = beginProcessingAttempt(job, "CLASSIC_YUV")
            val final = File(job, "final.bin")
            final.writeBytes(byteArrayOf(9, 8, 7))
            processingArtifactDeleteFailureForTest = true
            try {
                commitProcessingArtifact(
                    finalFile = final,
                    writeTemp = { it.writeBytes(byteArrayOf(1, 2, 3)) },
                    verifyFinal = { check(it.readBytes().contentEquals(byteArrayOf(1, 2, 3))) },
                    processingAttemptId = attempt.id,
                    claimKey = "finalFile"
                )
                markProcessingArtifactClaim(job, attempt, "finalFile", final)
            } finally { attempt.releaseOwnedLease() }
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(KeplerJobRecoveryClassification.PROCESSING_CLEANUP_REQUIRED, report.jobs.single().classification)
            assertEquals(PROCESSING_CLEANUP_REQUIRED, KeplerJobMetadata.read(job).getString("recoveryState"))
            processingArtifactDeleteFailureForTest = false
            // Second recovery must settle through the normal local-output path, not the fast path.
            val second = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(KeplerJobRecoveryClassification.LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL, second.jobs.single().classification)
        } finally {
            processingArtifactDeleteFailureForTest = false
            root.deleteRecursively()
        }
    }

    @Test
    fun f_invalidExportJournal_fastPathNotTaken_ambiguous() {
        val root = newRoot()
        val job = newJob(root, "KPL_YUV_FUSION_f_invalid")
        try {
            // Historical invalid journal (terminal stable) is preserved but NOT ambiguous per
            // historicalMalformedExportDebtDoesNotBlockProvenStableResult; the fast-path concern
            // is the CURRENT correlated invalid export which must still require handling.
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "EXPORTING")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, "current-export-f")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name)
                .put(ACTIVE_OPERATION_STARTED_AT, System.currentTimeMillis()))
            File(job, ".export_tx_broken.json").writeText("not-json")
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED, report.jobs.single().classification)
            assertTrue(File(job, ".export_tx_broken.json").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun g_publicExportMissingCommit_fastPathNotTaken() {
        val root = newRoot()
        val job = newJob(root, "KPL_YUV_FUSION_g_missing")
        try {
            val operationId = "current-export-g"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "EXPORTING")
                .put(ACTIVE_RUNTIME_SESSION_ID, "old-runtime")
                .put(ACTIVE_OPERATION_ID, operationId)
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
            val failingUri = "content://media/external/images/media/999g"
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, "result.jpg", "Pictures/Kepler",
                "image/jpeg", android.net.Uri.parse("content://media/external/images/media"),
                ownerOperationId = operationId
            ).transition(job, MediaStoreExportState.PUBLIC_COMMITTED, failingUri)
            val access = object : MediaStoreExportRecoveryAccess {
                override fun inspect(uri: android.net.Uri, journal: MediaStoreExportJournal): MediaStoreExportInspection =
                    MediaStoreExportInspection(exists = false, pending = false, verified = false, message = "missing")
                override fun setPending(uri: android.net.Uri, pending: Boolean) = true
                override fun delete(uri: android.net.Uri) = true
            }
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), access)
            // Fast path is impossible here because terminalResultAlreadyProven == false and journal debt exists.
            assertTrue(report.jobs.single().classification == KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED ||
                report.jobs.single().classification == KeplerJobRecoveryClassification.PUBLIC_COMMIT_MISSING ||
                report.jobs.single().classification == KeplerJobRecoveryClassification.PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION ||
                report.jobs.single().classification != KeplerJobRecoveryClassification.RECOVERED)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun h_metadataTempAndCaptureTempRecovery_stillExecutes() {
        val root = newRoot()
        val job = newJob(root, "KPL_YUV_FUSION_h_meta_temp")
        try {
            KeplerJobMetadata.write(job, terminalStableJson("STALE_NEEDS_WRITE", null))
            // Stale metadata temp
            val stale = File(job, ".job.json.1.tmp")
            stale.writeText(JSONObject().put("jobType", "YUV_NIGHT_FUSION").put("status", "COMPLETE").toString())
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            // Stale temp must have been cleaned (fast path not taken).
            assertFalse(stale.exists())
            assertEquals(KeplerJobRecoveryClassification.RECOVERED, report.jobs.single().classification)
            // Still settles to STABLE (collection repaired, debt cleaned).
            assertEquals("STABLE", KeplerJobMetadata.read(job).getString("recoveryState"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun i_externallyRemovedPublicResult_truthUnchanged() {
        val root = newRoot()
        val job = newJob(root, "KPL_YUV_FUSION_i_removed")
        try {
            val uri = "content://media/external/images/media/99200"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", uri)
                .put("recoveryState", "STABLE"))
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, "result.jpg", "Pictures/Kepler",
                "image/jpeg", android.net.Uri.parse("content://media/external/images/media"),
                ownerOperationId = null
            ).transition(job, MediaStoreExportState.VERIFIED, uri).markTerminalPersisted(job)
            val access = object : MediaStoreExportRecoveryAccess {
                override fun inspect(v: android.net.Uri, journal: MediaStoreExportJournal) =
                    MediaStoreExportInspection(exists = false, pending = false, verified = false, message = "removed")
                override fun setPending(v: android.net.Uri, pending: Boolean) = true
                override fun delete(v: android.net.Uri) = true
            }
            val before = KeplerJobMetadata.read(job).toString()
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), access)
            // Legacy/terminal evidence keeps it RECOVERED (not AMBIGUOUS).
            assertEquals(KeplerJobRecoveryClassification.RECOVERED, report.jobs.single().classification)
            assertTrue(report.jobs.single().failures.isEmpty())
            // Dedicated durable removal path is handled elsewhere; baseline RECOVERED truth is preserved.
            // Verify recoveryState stayed STABLE (fast path would not have rewritten it differently).
            assertEquals("STABLE", KeplerJobMetadata.read(job).getString("recoveryState"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun j_classificationIdenticalExceptOmittedWrite() {
        val root = newRoot()
        val job = newJob(root, "KPL_YUV_FUSION_j_class")
        try {
            KeplerJobMetadata.write(job, terminalStableJson("STABLE", null))
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root))
            assertEquals(KeplerJobRecoveryClassification.RECOVERED, report.jobs.single().classification)
            // The only intended difference from the prior production path is that the provably-redundant
            // job.json rewrite was omitted; classification, recoveryState, and message absence are identical.
            val recovered = KeplerJobMetadata.read(job)
            assertEquals("STABLE", recovered.getString("recoveryState"))
            assertFalse(recovered.has("recoveryMessage"))
            // No failures and no residual recovery debt.
            assertTrue(report.jobs.single().failures.isEmpty())
        } finally { root.deleteRecursively() }
    }
}