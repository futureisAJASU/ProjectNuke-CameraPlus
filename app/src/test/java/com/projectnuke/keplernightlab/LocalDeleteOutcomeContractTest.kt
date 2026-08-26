package com.projectnuke.keplernightlab

import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Local deletion contract (Phases 3, 4, 14): whole-job deletion is independent of the public
 * MediaStore row; partial filesystem failure is NEVER success UX; batch settlement is per-job
 * with truthful byte accounting and live-ownership refusal.
 */
@RunWith(RobolectricTestRunner::class)
class LocalDeleteOutcomeContractTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun yuvRoot(): File {
        val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        return File(pictures, "KeplerYuvFusion").apply { mkdirs() }
    }

    private fun newJob(root: File, name: String, payloadBytes: Int): File {
        val job = File(root, name).apply { mkdirs() }
        KeplerJobMetadata.write(
            job,
            JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("status", "COMPLETE")
                .put("currentPipelineStage", "COMPLETE")
                .put("recoveryState", "STABLE")
        )
        File(job, "frame_01_color.png").writeBytes(ByteArray(payloadBytes))
        File(job, "fusion_debug.json").writeText("{}")
        return job
    }

    @Test
    fun wholeJobDelete_reportsTruthfulBytesFreed() {
        val root = yuvRoot()
        val job = newJob(root, "KPL_YUV_FUSION_BYTES", 2048)
        try {
            val expectedBytes = folderSizeBytesNoFollow(job)
            assertTrue(expectedBytes > 0L)
            val result = deleteKeplerGalleryJob(context, job)
            assertTrue(result.isSuccess)
            val cleanup = result.getOrThrow()
            assertEquals(CleanupStatus.COMPLETE, cleanup.cleanupStatus)
            assertEquals("Whole-job bytesFreed must reflect actual directory size", expectedBytes, cleanup.bytesFreed)
            assertFalse(job.exists())
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    /** Phase 4: PARTIAL filesystem deletion stays a structured PARTIAL result, not success UX. */
    @Test
    fun partialFilesystemFailure_reportsPartialNotSuccess() {
        val root = yuvRoot()
        val job = newJob(root, "KPL_YUV_FUSION_PARTIAL", 512)
        val priorOverride = deleteRecursivelySafeOverrideForTest
        try {
            deleteRecursivelySafeOverrideForTest = { Pair(CleanupStatus.PARTIAL, listOf("/kept/path")) }
            val result = deleteKeplerGalleryJob(context, job)
            // The action returns structured success, but the outcome model exposes partial truth.
            assertTrue(result.isSuccess)
            val cleanup = result.getOrThrow()
            assertEquals(CleanupStatus.PARTIAL, cleanup.cleanupStatus)
            assertEquals(listOf("/kept/path"), cleanup.failedPaths)

            val outcome = deleteKeplerGalleryJobsBatch(context, listOf(job)).entries.single().outcome
            assertTrue("Batch outcome must be Partial", outcome is LocalDeleteOutcome.Partial)
            outcome as LocalDeleteOutcome.Partial
            assertEquals(listOf("/kept/path"), outcome.failedPaths)
        } finally {
            deleteRecursivelySafeOverrideForTest = priorOverride
            root.parentFile?.deleteRecursively()
        }
    }

    /** Phase 14: one blocked job never aborts unrelated safe jobs; unresolved stay identifiable. */
    @Test
    fun batchDelete_threeSafeOneBlocked_deletesThreeKeepsBlockedSelected() {
        val root = yuvRoot()
        val safe1 = newJob(root, "KPL_YUV_FUSION_BATCH_A", 100)
        val safe2 = newJob(root, "KPL_YUV_FUSION_BATCH_B", 100)
        val safe3 = newJob(root, "KPL_YUV_FUSION_BATCH_C", 100)
        val busy = newJob(root, "KPL_YUV_FUSION_BATCH_BUSY", 100)
        val lease = KeplerJobMetadata.acquireOperation(busy)!!
        try {
            val result = deleteKeplerGalleryJobsBatch(
                context,
                listOf(safe1, busy, safe2, safe3)
            )
            assertEquals(4, result.entries.size)
            assertEquals("Exactly the three safe jobs are deleted", 3, result.deletedEntries.size)
            assertFalse(safe1.exists())
            assertFalse(safe2.exists())
            assertFalse(safe3.exists())
            assertTrue("The live-owned job must survive", busy.exists())
            val busyEntry = result.unresolvedEntries.single()
            assertEquals(busy.absolutePath, busyEntry.jobId)
            val blocked = busyEntry.outcome as LocalDeleteOutcome.Blocked
            assertTrue(blocked.reason.isNotBlank())
            // UI keeps unresolved IDs selected.
            val keptSelection = setOf(busy.absolutePath)
            assertEquals(setOf(busy.absolutePath), keptSelection.intersect(result.unresolvedEntries.map { it.jobId }.toSet()))
            assertNotNull(keplerBatchDeleteSummaryText(result))
        } finally {
            lease.release()
            root.parentFile?.deleteRecursively()
        }
    }

    /** Phases 3+12: external public-result removal (terminal-stable verified evidence, row
     *  absent) settles STABLE and local deletion succeeds without the Gallery row. */
    @Test
    fun batchDelete_externalPublicMissing_stillDeletesLocalJob() {
        val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        val recoveryRoot = File(pictures, "KeplerYuvFusion").apply { mkdirs() }
        val job = File(recoveryRoot, "KPL_YUV_FUSION_REMOVED_DELETE").apply { mkdirs() }
        try {
            val uri = "content://media/external/images/media/99"
            KeplerJobMetadata.write(
                job,
                JSONObject()
                    .put("jobType", "YUV_NIGHT_FUSION")
                    .put("status", "COMPLETE")
                    .put("currentPipelineStage", "COMPLETE")
                    .put("recoveryState", "STABLE")
                    .put("galleryExportCommitted", true)
                    .put("exportVerified", true)
                    .put("exportUri", uri)
                    .put("galleryPublicExportLinkage", uri)
            )
            MediaStoreExportJournal.create(
                jobDir = job,
                role = MediaStoreExportRole.MAIN_IMAGE,
                frameIndex = null,
                displayName = "result.jpg",
                relativePath = "Pictures/Kepler",
                mimeType = "image/jpeg",
                collectionUri = Uri.parse("content://media/external/images/media")
            ).transition(job, MediaStoreExportState.VERIFIED, uri)
                .copy(updatedAt = 1L, createdAt = 1L).writeTo(job)
                .markTerminalPersisted(job, null)
            KeplerRecoveryCoordinator.recoverRoots(
                listOf(recoveryRoot),
                object : MediaStoreExportRecoveryAccess {
                    override fun inspect(uri: Uri, journal: MediaStoreExportJournal) =
                        MediaStoreExportInspection(exists = false, pending = false, verified = false)

                    override fun setPending(uri: Uri, pending: Boolean) = true
                    override fun delete(uri: Uri) = true
                }
            )
            assertEquals("STABLE", KeplerJobMetadata.read(job).optString("recoveryState"))

            val entry = deleteKeplerGalleryJobsBatch(context, listOf(job)).entries.single()
            assertTrue("External public absence must not block local deletion", entry.outcome is LocalDeleteOutcome.Complete)
            assertFalse(job.exists())
        } finally {
            pictures.deleteRecursively()
        }
    }
}
