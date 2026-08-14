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
class KeplerGalleryResultAuthorityTest {
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
}
