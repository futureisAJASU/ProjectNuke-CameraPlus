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

@RunWith(RobolectricTestRunner::class)
class ExportFailureTruthTest {
    @Test
    fun committedPublicVerificationFailureKeepsCurrentUriAndPartialTruth() {
        val directory = Files.createTempDirectory("export-verification-failure-").toFile()
        try {
            File(directory, "final.png").writeBytes(byteArrayOf(1))
            KeplerJobMetadata.write(directory, JSONObject()
                .put("processingAttemptId", "attempt-current")
                .put("processingMode", "CLASSIC_YUV")
                .put("processingOutputCommitted", true)
                .put("processingArtifactClaimAttemptId", "attempt-current")
                .put("finalFile", "final.png")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/old")
                .put("currentPipelineStage", "PROCESSING"))

            updateExportFailure(
                jobDir = directory,
                error = "verification failed",
                finalOutputFormat = FinalOutputFormat.JPEG,
                export = GalleryExportResult(
                    success = true,
                    uriString = "content://media/new",
                    displayName = "new.jpg",
                    mimeType = "image/jpeg",
                    fileSizeBytes = 4L,
                    formatUsed = OutputFormat.JPEG,
                    fallbackUsed = false,
                    errorMessage = "verification failed"
                ),
                requiredOutputCommitted = true
            )

            val job = KeplerJobMetadata.read(directory)
            assertEquals("PARTIAL", job.getString("currentPipelineStage"))
            assertEquals("EXPORT_COMMITTED_UNVERIFIED", job.getString("processStatus"))
            assertEquals("COMMITTED_UNVERIFIED", job.getString("exportStatus"))
            assertTrue(job.getBoolean("galleryExportCommitted"))
            assertFalse(job.getBoolean("exportVerified"))
            assertEquals("content://media/new", job.getString("exportUri"))
            assertEquals("verification failed", job.getString("exportError"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun localCurrentCommitKeepsPartialTruthAndPreviousPublicLinkage() {
        val directory = Files.createTempDirectory("export-local-partial-").toFile()
        try {
            File(directory, "final.png").writeBytes(byteArrayOf(1))
            KeplerJobMetadata.write(directory, JSONObject()
                .put("processingAttemptId", "attempt-current")
                .put("processingMode", "CLASSIC_YUV")
                .put("processingOutputCommitted", true)
                .put("processingArtifactClaimAttemptId", "attempt-current")
                .put("finalFile", "final.png")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/old"))

            updateExportFailure(
                jobDir = directory,
                error = "insert failed",
                finalOutputFormat = FinalOutputFormat.JPEG,
                export = GalleryExportResult(
                    success = false,
                    uriString = null,
                    displayName = null,
                    mimeType = null,
                    fileSizeBytes = 0L,
                    formatUsed = OutputFormat.JPEG,
                    fallbackUsed = false,
                    errorMessage = "insert failed"
                ),
                requiredOutputCommitted = true
            )

            val job = KeplerJobMetadata.read(directory)
            assertEquals("PARTIAL", job.getString("currentPipelineStage"))
            assertEquals("EXPORT_FAILED_KEEPING_CACHE", job.getString("processStatus"))
            assertEquals("FAILED", job.getString("exportStatus"))
            assertTrue(job.getBoolean("processingOutputCommitted"))
            assertEquals("content://media/old", job.getString("exportUri"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun previousResultOnlyDoesNotBecomeCurrentPartialSuccess() {
        val directory = Files.createTempDirectory("export-old-result-").toFile()
        try {
            File(directory, "old.png").writeBytes(byteArrayOf(1))
            KeplerJobMetadata.write(directory, JSONObject()
                .put("processingAttemptId", "attempt-new")
                .put("processingMode", "CLASSIC_YUV")
                .put("processingOutputCommitted", false)
                .put("processingArtifactClaimAttemptId", JSONObject.NULL)
                .put("finalFile", "old.png")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/old"))

            updateExportFailure(
                jobDir = directory,
                error = "processing failed before output",
                finalOutputFormat = FinalOutputFormat.JPEG,
                export = GalleryExportResult(
                    success = false,
                    uriString = null,
                    displayName = null,
                    mimeType = null,
                    fileSizeBytes = 0L,
                    formatUsed = OutputFormat.JPEG,
                    fallbackUsed = false,
                    errorMessage = "processing failed"
                ),
                requiredOutputCommitted = false
            )

            val job = KeplerJobMetadata.read(directory)
            assertEquals("FAILED", job.getString("currentPipelineStage"))
            assertEquals("content://media/old", job.getString("exportUri"))
            assertFalse(job.getBoolean("processingOutputCommitted"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
