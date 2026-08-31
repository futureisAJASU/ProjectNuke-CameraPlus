package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Screen-independent R2 proof using the real MediaStore provider and the production recovery
 * coordinator. Only this test's exact rows and isolated job root are touched.
 */
@RunWith(AndroidJUnit4::class)
class RealMediaStoreTerminalVerifiedCohortTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pilotThreeJobs_areTerminalVerified_andRecoveryIsIdempotent() {
        runCohort("pilot", 3)
    }

    @Test
    fun fullFortySixJobs_areTerminalVerified_andRecoveryIsIdempotent() {
        runCohort("full", 46)
    }

    private fun runCohort(label: String, count: Int) {
        val root = File(
            requireNotNull(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)),
            "R2DeviceProof/$ROOT_NAME"
        )
        assertTrue("Unable to create isolated R2 root", root.mkdirs() || root.isDirectory)
        val jobs = mutableListOf<CohortJob>()
        val createdUris = linkedSetOf<Uri>()
        try {
            repeat(count) { index ->
                val job = createTerminalVerifiedJob(root, index)
                jobs += job
                job.uri?.let { createdUris += Uri.parse(it) }
            }

            assertEquals(count, jobs.size)
            val jpegCount = jobs.count { it.actualFormat == OutputFormat.JPEG }
            val heifCount = jobs.count { it.actualFormat == OutputFormat.HEIF }
            assertEquals(count / 2 + count % 2, jpegCount)
            assertEquals(count / 2, heifCount)
            assertEquals(count, jobs.count { it.verified && it.diagnostic == null })
            assertEquals(count, jobs.count { it.pending == false })
            assertEquals(count, jobs.count { it.terminalMetadataPersisted })
            assertEquals(count, jobs.count { it.journalState == MediaStoreExportState.VERIFIED })
            println("R2D_BASELINE label=$label count=$count jpeg=$jpegCount heif=$heifCount terminal=$count verified=$count pending=0 diagnosticNull=$count journalVerified=$count terminalMetadataPersisted=$count recoveryDebt=0")

            val baseline = jobs.associate { it.jobDir.name to snapshot(it.jobDir) }
            val firstAccess = CountingRecoveryAccess(ContextMediaStoreExportRecoveryAccess(context))
            val first = KeplerRecoveryCoordinator.recoverRoots(listOf(root), firstAccess)
            println("R2D_PASS1_DIAGNOSTIC=" + first.jobs.joinToString(";") { "${it.jobDir.name}:${it.classification}:${it.failures.joinToString(",")}" })
            assertEquals(count, first.jobs.size)
            assertEquals(count, first.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
            assertEquals(0, first.jobs.count { it.classification != KeplerJobRecoveryClassification.RECOVERED })
            assertEquals(count, firstAccess.inspections)
            assertStable(root, jobs, count)
            val afterFirst = jobs.associate { it.jobDir.name to snapshot(it.jobDir) }
            assertSameDurableContent(baseline, afterFirst)
            val firstMetadataRewrites = countMtimeChanges(baseline, afterFirst) { it.metadataModified }
            val firstJournalRewrites = countMtimeChanges(baseline, afterFirst) { it.journalModified }
            println("R2D_PASS1 label=$label count=$count terminal=$count verified=$count pending=0 diagnosticReasons=none classifications=RECOVERED durableWrites=metadata:$firstMetadataRewrites,journal:$firstJournalRewrites unexpectedMutations=0 inspections=${firstAccess.inspections} verifiedTrue=${firstAccess.verifiedTrue}")

            val secondAccess = CountingRecoveryAccess(ContextMediaStoreExportRecoveryAccess(context))
            val second = KeplerRecoveryCoordinator.recoverRoots(listOf(root), secondAccess)
            assertEquals(count, second.jobs.size)
            assertEquals(count, second.jobs.count { it.classification == KeplerJobRecoveryClassification.RECOVERED })
            assertEquals(0, second.jobs.count { it.classification != KeplerJobRecoveryClassification.RECOVERED })
            assertEquals(count, secondAccess.inspections)
            assertStable(root, jobs, count)
            val afterSecond = jobs.associate { it.jobDir.name to snapshot(it.jobDir) }
            assertSameDurableContent(baseline, afterSecond)
            val secondMetadataRewrites = countMtimeChanges(afterFirst, afterSecond) { it.metadataModified }
            val secondJournalRewrites = countMtimeChanges(afterFirst, afterSecond) { it.journalModified }
            println("R2D_PASS2 label=$label count=$count terminal=$count verified=$count pending=0 diagnosticReasons=none classifications=RECOVERED durableWrites=metadata:$secondMetadataRewrites,journal:$secondJournalRewrites unexpectedMutations=0 inspections=${secondAccess.inspections} verifiedTrue=${secondAccess.verifiedTrue} idempotent=true")
        } finally {
            jobs.flatMap { it.uris }.toSet().also { createdUris += it }
            createdUris.forEach { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
                val remains = runCatching {
                    context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)
                        ?.use { it.moveToFirst() } == true
                }.getOrDefault(false)
                assertFalse("R2 exact test row must be deleted: $uri", remains)
            }
            jobs.forEach { job ->
                assertTrue("R2 exact job directory must be removed", !job.jobDir.exists() || job.jobDir.deleteRecursively())
            }
            if (root.isDirectory) assertTrue("R2 isolated root cleanup failed", root.delete() || root.listFiles().isNullOrEmpty())
        }
    }

    private fun createTerminalVerifiedJob(root: File, index: Int): CohortJob {
        val jobDir = File(root, "KPL_YUV_FUSION_R2D_${UUID.randomUUID()}")
        assertTrue(jobDir.mkdirs())
        val requested = if (index % 2 == 0) OutputFormat.JPEG else OutputFormat.HEIF
        val finalFormat = if (requested == OutputFormat.JPEG) FinalOutputFormat.JPEG else FinalOutputFormat.HEIF
        KeplerJobMetadata.write(
            jobDir,
            JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("status", "PROCESSING")
                .put("processStatus", "PROCESSING")
                .put("currentPipelineStage", "PROCESSING")
                .put("recoveryState", "STABLE")
                .put("createdAt", System.currentTimeMillis())
        )
        val bitmap = deterministicBitmap(64, 64, index)
        return try {
            val export = exportNightFusionBitmapToGallery(
                context = context,
                bitmap = bitmap,
                displayNameBase = "r2d-${UUID.randomUUID()}",
                requestedFormat = requested,
                relativeAlbumPath = TEST_RELATIVE_PATH,
                quality = 92,
                cancellation = NoOpKeplerPipelineCancellation,
                jobDir = jobDir
            )
            assertTrue("Production export failed for cohort member $index: $export", export.success)
            assertEquals(GalleryExportCommitState.VERIFIED, export.publicCommitState)
            assertNotNull(export.uriString)
            assertNotNull(export.verification)
            assertTrue(export.verification is GalleryExportVerification.Verified)
            assertEquals(null, diagnosticReason(export.verification))
            updateExportMetadata(jobDir, export, verified = true, finalOutputFormat = finalFormat)
            // The capture terminal event owns the status scalar; mirror that production terminal
            // event after the export writer has persisted its authoritative export evidence.
            KeplerJobMetadata.update(jobDir) {
                it.put("status", "COMPLETE").put("processStatus", "PIPELINE_COMPLETE")
            }
            // The real pipeline releases its owner before restart recovery. The production writer
            // creates an auto lease when no caller lease is supplied; release only this exact
            // test-created lease before invoking recoverRoots.
            KeplerJobMetadata.findOperationLease(jobDir)?.let { KeplerJobMetadata.releaseOperation(it) }
            val metadata = KeplerJobMetadata.read(jobDir)
            val journal = MediaStoreExportJournal.list(jobDir).single { it.role == MediaStoreExportRole.MAIN_IMAGE }
            assertEquals("COMPLETE", metadata.optString("currentPipelineStage"))
            assertEquals("STABLE", metadata.optString("recoveryState"))
            assertTrue(metadata.optBoolean("galleryExportCommitted"))
            assertTrue(metadata.optBoolean("exportVerified"))
            assertEquals(export.uriString, metadata.optString("exportUri"))
            assertEquals("", metadata.optString(ACTIVE_OPERATION_ID))
            assertEquals(MediaStoreExportState.VERIFIED, journal.state)
            assertTrue(journal.terminalMetadataPersisted)
            val uri = Uri.parse(requireNotNull(export.uriString))
            val row = requireNotNull(context.contentResolver.query(
                uri,
                arrayOf(android.provider.MediaStore.MediaColumns.IS_PENDING),
                null,
                null,
                null
            )).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
            assertEquals(0, row)
            CohortJob(jobDir, export.uriString, export.formatUsed, true, diagnosticReason(export.verification), false, journal.state, journal.terminalMetadataPersisted, setOf(uri))
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertStable(root: File, jobs: List<CohortJob>, count: Int) {
        assertEquals(count, jobs.count { it.jobDir.isDirectory })
        jobs.forEach { item ->
            val metadata = KeplerJobMetadata.read(item.jobDir)
            val journal = MediaStoreExportJournal.list(item.jobDir).single { it.role == MediaStoreExportRole.MAIN_IMAGE }
            val uri = Uri.parse(requireNotNull(journal.uri))
            val inspection = ContextMediaStoreExportRecoveryAccess(context).inspect(uri, journal)
            assertEquals("COMPLETE", metadata.optString("currentPipelineStage"))
            assertEquals("STABLE", metadata.optString("recoveryState"))
            assertTrue(metadata.optBoolean("galleryExportCommitted"))
            assertTrue(metadata.optBoolean("exportVerified"))
            assertEquals("", metadata.optString(ACTIVE_OPERATION_ID))
            assertEquals(MediaStoreExportState.VERIFIED, journal.state)
            assertTrue(journal.terminalMetadataPersisted)
            assertTrue(inspection.exists)
            assertFalse(inspection.pending)
            assertTrue(inspection.verified)
            assertEquals(null, inspection.verificationDiagnosticReason)
        }
    }

    private fun snapshot(jobDir: File): DurableSnapshot {
        val metadata = File(jobDir, JOB_JSON_FILE_NAME)
        val journal = MediaStoreExportJournal.list(jobDir).single { it.role == MediaStoreExportRole.MAIN_IMAGE }
        val journalFile = MediaStoreExportJournal.fileFor(jobDir, journal.exportAttemptId)
        return DurableSnapshot(sha256(metadata), sha256(journalFile), metadata.lastModified(), journalFile.lastModified())
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun assertSameDurableContent(before: Map<String, DurableSnapshot>, after: Map<String, DurableSnapshot>) {
        assertEquals(before.mapValues { it.value.metadataHash }, after.mapValues { it.value.metadataHash })
        assertEquals(before.mapValues { it.value.journalHash }, after.mapValues { it.value.journalHash })
    }

    private fun countMtimeChanges(
        before: Map<String, DurableSnapshot>,
        after: Map<String, DurableSnapshot>,
        selector: (DurableSnapshot) -> Long
    ): Int = before.keys.count { key -> selector(before.getValue(key)) != selector(after.getValue(key)) }

    private fun diagnosticReason(result: GalleryExportVerification?): GalleryExportVerificationReason? = when (result) {
        is GalleryExportVerification.RetryableFailure -> result.diagnosticReason
        is GalleryExportVerification.PermanentFailure -> result.diagnosticReason
        is GalleryExportVerification.Verified, null -> null
    }

    private fun deterministicBitmap(width: Int, height: Int, seed: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until height) for (x in 0 until width) {
                bitmap.setPixel(x, y, android.graphics.Color.argb(255, (x * 4 + seed) % 256, (y * 4 + seed * 3) % 256, ((x + y) * 2 + seed * 5) % 256))
            }
        }

    private data class CohortJob(
        val jobDir: File,
        val uri: String?,
        val actualFormat: OutputFormat,
        val verified: Boolean,
        val diagnostic: GalleryExportVerificationReason?,
        val pending: Boolean,
        val journalState: MediaStoreExportState,
        val terminalMetadataPersisted: Boolean,
        val uris: Set<Uri>
    )

    private data class DurableSnapshot(val metadataHash: String, val journalHash: String, val metadataModified: Long, val journalModified: Long)

    private class CountingRecoveryAccess(private val delegate: MediaStoreExportRecoveryAccess) : MediaStoreExportRecoveryAccess {
        var inspections = 0
        var verifiedTrue = 0
        override fun inspect(uri: Uri, journal: MediaStoreExportJournal): MediaStoreExportInspection {
            inspections++
            return delegate.inspect(uri, journal).also { if (it.verified) verifiedTrue++ }
        }
        override fun setPending(uri: Uri, pending: Boolean): Boolean = delegate.setPending(uri, pending)
        override fun delete(uri: Uri): Boolean = delegate.delete(uri)
    }

    private companion object {
        const val ROOT_NAME = "KeplerYuvFusion"
        const val TEST_RELATIVE_PATH = "Pictures/KeplerR2DeviceProof"
    }
}
