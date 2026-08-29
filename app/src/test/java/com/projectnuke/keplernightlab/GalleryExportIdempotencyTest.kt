package com.projectnuke.keplernightlab

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class GalleryExportIdempotencyTest {

    private fun recoveryRoot(label: String): Pair<File, File> {
        val parent = Files.createTempDirectory("gallery-idem-$label").toFile()
        val root = File(parent, "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_idem").apply { mkdirs() }
        return root to job
    }

    private fun mainJournal(
        jobDir: File,
        uri: String,
        createdAt: Long = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis(),
        terminalPersisted: Boolean = true
    ): MediaStoreExportJournal {
        val j = MediaStoreExportJournal.create(
            jobDir = jobDir,
            role = MediaStoreExportRole.MAIN_IMAGE,
            frameIndex = null,
            displayName = "result.jpg",
            relativePath = "Pictures/Kepler",
            mimeType = "image/jpeg",
            collectionUri = Uri.parse("content://media/external/images/media")
        )
        var result = j.transition(jobDir, MediaStoreExportState.VERIFIED, uri)
        result = result.copy(createdAt = createdAt, updatedAt = updatedAt).writeTo(jobDir)
        return if (terminalPersisted) result.markTerminalPersisted(jobDir, null) else result
    }

    private fun terminalStableJob(job: File, exportUri: String) {
        KeplerJobMetadata.write(job, JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put("currentPipelineStage", "COMPLETE")
            .put("galleryExportCommitted", true)
            .put("exportVerified", true)
            .put("exportUri", exportUri)
            .put("recoveryState", "STABLE"))
    }

    @Test
    fun multipleVerifiedMainJournals_omitSameStateRewrite_doesNotReorderEvidence() {
        val (root, jobDir) = recoveryRoot("multi-main-")
        try {
            val oldUri = "content://media/external/images/media/1"
            val newUri = "content://media/external/images/media/2"
            mainJournal(jobDir, oldUri, createdAt = 1_000L, updatedAt = 1_000L, terminalPersisted = true)
            mainJournal(jobDir, newUri, createdAt = 2_000L, updatedAt = 2_000L, terminalPersisted = true)
            terminalStableJob(jobDir, newUri)

            val beforeWrites = KeplerJobMetadata.atomicWriteCount
            val beforeUpdated = MediaStoreExportJournal.list(jobDir)
                .associate { it.exportAttemptId to it.updatedAt }

            val access = object : MediaStoreExportRecoveryAccess {
                override fun inspect(uri: android.net.Uri, journal: MediaStoreExportJournal) =
                    MediaStoreExportInspection(exists = true, pending = false, verified = true)
                override fun setPending(uri: android.net.Uri, pending: Boolean) = true
                override fun delete(uri: android.net.Uri) = true
            }
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), access)

            assertEquals(KeplerJobRecoveryClassification.RECOVERED, report.jobs.single().classification)
            val afterWrites = KeplerJobMetadata.atomicWriteCount
            val afterUpdated = MediaStoreExportJournal.list(jobDir).associate { it.exportAttemptId to it.updatedAt }
            assertEquals("Same-state VERIFIED rewrite should not touch updatedAt",
                beforeUpdated, afterUpdated)
            assertEquals("No redundant durable writes for already-settled terminal-stable cohort",
                beforeWrites, afterWrites)
            assertEquals("STABLE", KeplerJobMetadata.read(jobDir).getString("recoveryState"))
            assertEquals(newUri, KeplerJobMetadata.read(jobDir).getString("exportUri"))
            assertTrue("Raw sidecar reconstruction must be skipped for YUV contracts", !KeplerJobMetadata.read(jobDir).has("rawSidecarPublicExportedCount"))
        } finally { root.parentFile?.deleteRecursively() }
    }

    @Test
    fun yuvTerminalStableWithoutFrames_omitsNoOpWriteAndPreservesState() {
        val (root, jobDir) = recoveryRoot("yuv-no-frames-")
        try {
            val uri = "content://media/external/images/media/42"
            terminalStableJob(jobDir, uri)
            mainJournal(jobDir, uri, createdAt = 5_000L, updatedAt = 5_000L, terminalPersisted = true)
            val beforeWrites = KeplerJobMetadata.atomicWriteCount
            val beforeState = KeplerJobMetadata.read(jobDir).toString()

            val access = object : MediaStoreExportRecoveryAccess {
                override fun inspect(uri: android.net.Uri, journal: MediaStoreExportJournal) =
                    MediaStoreExportInspection(exists = true, pending = false, verified = true)
                override fun setPending(uri: android.net.Uri, pending: Boolean) = true
                override fun delete(uri: android.net.Uri) = true
            }
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), access)

            assertEquals(KeplerJobRecoveryClassification.RECOVERED, report.jobs.single().classification)
            assertEquals("STABLE", KeplerJobMetadata.read(jobDir).getString("recoveryState"))
            assertEquals(beforeWrites, KeplerJobMetadata.atomicWriteCount)
        } finally { root.parentFile?.deleteRecursively() }
    }

    @Test
    fun rawSidecarRecoveryApplies_toRawManifestWithFailedLocalSidecar() {
        val (root, jobDir) = recoveryRoot("raw-sidecar-failed-")
        try {
            val dng = File(jobDir, ".dng_placeholder.tmp").apply { writeBytes(byteArrayOf(1)) }
            KeplerJobMetadata.write(jobDir, JSONObject()
                .put("jobType", "RAW_NIGHT_FUSION")
                .put("status", "COMPLETE")
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/external/images/media/99")
                .put("recoveryState", "STABLE")
                .put("frames", JSONArray().put(JSONObject()
                    .put("frameIndex", 0)
                    .put("dngSidecarStatus", "LOCAL_SAVE_FAILED"))))

            val applies = rawSidecarRecoveryApplies(jobDir, KeplerJobMetadata.read(jobDir))
            assertTrue("Failed local RAW DNG sidecar contract must apply recovery", applies)
        } finally { root.parentFile?.deleteRecursively() }
    }

    @Test
    fun rawSidecarRecoveryApplies_toYuvWithoutDngFields_isStructurallyUnrelated() {
        val (root, jobDir) = recoveryRoot("yuv-no-dng-fields-")
        try {
            KeplerJobMetadata.write(jobDir, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("frames", JSONArray().put(JSONObject()
                    .put("frameIndex", 0)
                    .put("file", "frame_00.yuv"))))
            val applies = rawSidecarRecoveryApplies(jobDir, KeplerJobMetadata.read(jobDir))
            assertFalse("YUV job with no DNG-sidecar fields is structurally unrelated", applies)
        } finally { root.parentFile?.deleteRecursively() }
    }
}
