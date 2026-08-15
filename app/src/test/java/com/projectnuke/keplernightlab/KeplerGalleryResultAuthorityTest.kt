package com.projectnuke.keplernightlab

import android.os.Environment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class KeplerGalleryResultAuthorityTest {
    @Test
    fun deleteGalleryJobReturnsOrdinaryFailureButPropagatesFatalError() {
        val (root, directory) = createGalleryJobDirectory("delete-contract")
        try {
            galleryDeleteFailureForTest = IOException("injected delete failure")
            assertTrue(deleteKeplerGalleryJob(RuntimeEnvironment.getApplication(), directory).isFailure)

            galleryDeleteFailureForTest = AssertionError("fatal delete failure")
            try {
                deleteKeplerGalleryJob(RuntimeEnvironment.getApplication(), directory)
                fail("fatal delete error must propagate")
            } catch (failure: AssertionError) {
                assertEquals("fatal delete failure", failure.message)
            }
        } finally {
            galleryDeleteFailureForTest = null
            directory.deleteRecursively()
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupGalleryJobReportsOrdinaryDeleteFailureAsPartial() {
        val (root, directory) = createGalleryJobDirectory("cleanup-partial")
        try {
            File(directory, "diagnostic.log").writeText("diagnostic")
            galleryCleanupDeleteReturnsFalseForTest = true

            val result = cleanupKeplerGalleryJob(
                RuntimeEnvironment.getApplication(),
                directory,
                KeplerJobCleanupType.DEBUG_ONLY
            ).getOrThrow()

            assertEquals(CleanupStatus.PARTIAL, result.cleanupStatus)
            assertTrue(result.failedPaths.any { it.endsWith("diagnostic.log") })
        } finally {
            galleryCleanupDeleteReturnsFalseForTest = false
            directory.deleteRecursively()
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupGalleryJobPropagatesFatalDeleteAndMetadataErrors() {
        val (root, directory) = createGalleryJobDirectory("cleanup-fatal")
        try {
            File(directory, "diagnostic.log").writeText("diagnostic")
            galleryCleanupFileDeleteFailureForTest = AssertionError("fatal file delete")
            try {
                cleanupKeplerGalleryJob(
                    RuntimeEnvironment.getApplication(),
                    directory,
                    KeplerJobCleanupType.DEBUG_ONLY
                )
                fail("fatal file-delete error must propagate")
            } catch (failure: AssertionError) {
                assertEquals("fatal file delete", failure.message)
            }

            galleryCleanupMetadataFailureForTest = AssertionError("fatal metadata write")
            try {
                cleanupKeplerGalleryJob(
                    RuntimeEnvironment.getApplication(),
                    directory,
                    KeplerJobCleanupType.DEBUG_ONLY
                )
                fail("fatal metadata error must propagate")
            } catch (failure: AssertionError) {
                assertEquals("fatal metadata write", failure.message)
            }
        } finally {
            galleryCleanupFileDeleteFailureForTest = null
            galleryCleanupMetadataFailureForTest = null
            directory.deleteRecursively()
            root.deleteRecursively()
        }
    }

    @Test
    fun exactClaimedSuperResolutionOutputBeatsNewestImageFallback() {
        val directory = Files.createTempDirectory("kepler-gallery-sr-claim-").toFile()
        try {
            val exact = File(directory, "super_resolution_24mp.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            File(directory, "unrelated-newest.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
                .setLastModified(System.currentTimeMillis() + 10_000L)
            KeplerJobMetadata.write(
                directory,
                JSONObject()
                    .put("jobType", "SUPER_RESOLUTION_FUSION")
                    .put("processingMode", "SUPER_RESOLUTION")
                    .put("processingAttemptId", "attempt-sr")
                    .put("processingArtifactClaimAttemptId", "attempt-sr")
                    .put("processingOutputCommitted", true)
                    .put("superResolutionOutputFile", exact.name)
                    .put("status", "PARTIAL")
            )

            val summary = readKeplerGalleryJob(directory)

            assertEquals(exact, summary.finalPreviewFile)
            assertTrue(finalFilesForCleanup(directory, summary.metadata).contains(exact))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun currentSingleFrameResultWithDiagnosticFailureIsNotFailedDeleteGarbage() {
        val directory = Files.createTempDirectory("kepler-gallery-single-frame-result-").toFile()
        try {
            val finalFile = File(directory, "single_frame_processed.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val job = summary(
                directory = directory,
                finalPreviewFile = finalFile,
                metadata = JSONObject()
                    .put("processStatus", "SINGLE_FRAME_PROCESSING_FAILED")
                    .put("finalOutputAvailable", true)
                    .put("galleryDisplayUnavailable", false)
            )

            assertTrue(job.finalPreviewFile?.isFile == true)
            assertTrue(selectFailedGalleryJobs(listOf(job)).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun localYuvResultWithExportFailureIsNotFailedDeleteGarbage() {
        val directory = Files.createTempDirectory("kepler-gallery-yuv-result-").toFile()
        try {
            val finalFile = File(directory, "final.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val job = summary(
                directory = directory,
                finalPreviewFile = finalFile,
                metadata = JSONObject()
                    .put("processStatus", "EXPORT_FAILED_KEEPING_CACHE")
                    .put("finalOutputAvailable", true)
                    .put("galleryDisplayUnavailable", false)
            )

            assertTrue(selectFailedGalleryJobs(listOf(job)).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun committedPublicResultWithoutLocalPreviewIsNotFailedDeleteGarbage() {
        val directory = Files.createTempDirectory("kepler-gallery-public-result-").toFile()
        try {
            val job = summary(
                directory = directory,
                finalPreviewFile = null,
                finalExportExists = true,
                metadata = JSONObject()
                    .put("processStatus", "EXPORT_FAILED_KEEPING_CACHE")
                    .put("galleryExportCommitted", true)
                    .put("exportVerified", true)
                    .put("exportUri", "content://media/current")
            )

            assertTrue(selectFailedGalleryJobs(listOf(job)).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun trueFailedStableJobRemainsAnEligibleFailedDeleteTarget() {
        val directory = Files.createTempDirectory("kepler-gallery-true-failure-").toFile()
        try {
            val job = summary(
                directory = directory,
                metadata = JSONObject()
                    .put("processStatus", "PROCESSING_FAILED")
                    .put("finalOutputAvailable", false)
                    .put("galleryDisplayUnavailable", true)
            )

            assertEquals(listOf(job), selectFailedGalleryJobs(listOf(job)))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ambiguousFailedJobIsNotAnOrdinaryFailedDeleteTarget() {
        val directory = Files.createTempDirectory("kepler-gallery-ambiguous-").toFile()
        try {
            val job = summary(
                directory = directory,
                recoveryState = "AMBIGUOUS_RECOVERY_REQUIRED",
                metadata = JSONObject().put("processStatus", "FAILED")
            )

            assertTrue(selectFailedGalleryJobs(listOf(job)).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun summary(
        directory: File,
        metadata: JSONObject,
        finalPreviewFile: File? = null,
        finalExportExists: Boolean = false,
        recoveryState: String = "STABLE"
    ): KeplerGalleryJobSummary = KeplerGalleryJobSummary(
        id = directory.absolutePath,
        jobType = "YUV_SINGLE_FRAME",
        directory = directory,
        createdAt = 1L,
        status = metadata.optString("status").ifBlank { "FAILED" },
        requestedFrames = 1,
        savedFrames = 1,
        width = null,
        height = null,
        folderSizeBytes = 0L,
        storage = KeplerJobStorageInfo(
            totalJobBytes = 0L,
            totalJobSizeText = "0 B",
            finalOutputBytes = 0L,
            finalOutputSizeText = "0 B",
            rawFramesBytes = 0L,
            intermediateFilesBytes = 0L,
            debugFilesBytes = 0L,
            previewFilesBytes = 0L,
            cacheFilesBytes = 0L,
            cleanableBytes = 0L,
            fileCount = 0
        ),
        finalPreviewFile = finalPreviewFile,
        finalExportExists = finalExportExists,
        frames = emptyList(),
        metadata = metadata,
        recoveryState = recoveryState
    )

    private fun createGalleryJobDirectory(label: String): Pair<File, File> {
        val pictures = requireNotNull(
            RuntimeEnvironment.getApplication().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        )
        val root = File(pictures, "KeplerYuvFusion").also { check(it.mkdirs() || it.isDirectory) }
        val directory = File(root, "KPL_YUV_FUSION_${label}_${UUID.randomUUID()}")
            .also { check(it.mkdirs()) }
        KeplerJobMetadata.write(
            directory,
            JSONObject()
                .put("jobType", "YUV_SINGLE_FRAME")
                .put("finalFile", "final.png")
                .put("status", "COMPLETE")
        )
        File(directory, "final.png").writeBytes(byteArrayOf(1, 2, 3))
        return root to directory
    }
}
