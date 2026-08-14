package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
class SingleFrameProcessorTest {
    @Test
    fun processesOneCapturedFrameWithSharedIspAndPersistsResultMetadata() {
        val jobDir = Files.createTempDirectory("kepler-single-frame-").toFile()
        try {
            val sourceFile = File(jobDir, "frame_000.png")
            val source = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
            try {
                for (y in 0 until source.height) {
                    for (x in 0 until source.width) {
                        source.setPixel(
                            x,
                            y,
                            Color.rgb(24 + x * 12, 20 + y * 15, 36 + (x + y) * 8)
                        )
                    }
                }
                FileOutputStream(sourceFile).use { output ->
                    assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                source.recycle()
            }

            KeplerJobMetadata.write(
                jobDir,
                JSONObject()
                    .put("jobType", "YUV_SINGLE_FRAME")
                    .put("captureMode", CaptureMode.SINGLE_FRAME.name)
                    .put("requestedFrames", 1)
                    .put("savedFrames", 1)
                    .put(
                        "frames",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("file", sourceFile.name)
                                .put("enabled", true)
                        )
                    )
            )

            val params = ClassicYuvFusionPreset.CLEAN.params
            val output = processSingleFrameJobSync(
                jobDir = jobDir,
                requestedParams = params,
                onStatus = {}
            )

            assertEquals(SINGLE_FRAME_OUTPUT_FILE_NAME, output.name)
            assertTrue(output.isFile && output.length() > 0L)
            val decoded = requireNotNull(BitmapFactory.decodeFile(output.absolutePath))
            try {
                assertEquals(8, decoded.width)
                assertEquals(6, decoded.height)
            } finally {
                decoded.recycle()
            }

            val job = KeplerJobMetadata.read(jobDir)
            assertEquals("YUV_SINGLE_FRAME", job.getString("jobType"))
            assertEquals(CaptureMode.SINGLE_FRAME.name, job.getString("captureMode"))
            assertEquals("PIPELINE_COMPLETE", job.getString("processStatus"))
            assertEquals(SINGLE_FRAME_OUTPUT_FILE_NAME, job.getString("finalFile"))
            assertTrue(job.getBoolean("processingOutputCommitted"))
            assertEquals(job.getString("processingAttemptId"), job.getString("processingArtifactClaimAttemptId"))
            assertEquals(params.presetName, job.getString("fusionPresetName"))
            assertEquals(1, job.getInt("usedFrameCount"))
            assertTrue(Files.list(jobDir.toPath()).use { stream ->
                stream.noneMatch { it.fileName.toString().endsWith(".candidate") || it.fileName.toString().endsWith(".bak") }
            })
        } finally {
            jobDir.deleteRecursively()
        }
    }

    @Test
    fun reprocessProgressPolicyDoesNotOverwriteExistingTerminalFields() {
        val jobDir = Files.createTempDirectory("kepler-single-reprocess-").toFile()
        try {
            val sourceFile = File(jobDir, "frame_000.png")
            val source = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(Color.rgb(64, 72, 80))
                FileOutputStream(sourceFile).use { output ->
                    assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                source.recycle()
            }
            KeplerJobMetadata.write(
                jobDir,
                JSONObject()
                    .put("jobType", "YUV_SINGLE_FRAME")
                    .put("processStatus", "TERMINAL_SENTINEL")
                    .put("finalFile", "existing_terminal.png")
                    .put(
                        "frames",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("file", sourceFile.name)
                                .put("enabled", true)
                        )
                    )
            )

            processSingleFrameJobSync(
                jobDir = jobDir,
                requestedParams = ClassicYuvFusionPreset.SHARP.params,
                metadataPolicy = ReprocessMetadataPolicy.REPROCESS_PROGRESS_ONLY,
                onStatus = {}
            )

            val job = KeplerJobMetadata.read(jobDir)
            assertEquals("TERMINAL_SENTINEL", job.getString("processStatus"))
            assertEquals("existing_terminal.png", job.getString("finalFile"))
            assertEquals(ClassicYuvFusionPreset.SHARP.name, job.getString("fusionPresetName"))
            assertFalse(job.optBoolean("processingOutputCommitted", false))
            assertTrue(ProcessingArtifactJournal.list(jobDir).isEmpty())
        } finally {
            jobDir.deleteRecursively()
        }
    }
    @Test
    fun cancellationAfterVerifiedOutputRetainsCommittedResult() {
        val jobDir = Files.createTempDirectory("kepler-single-cancel-").toFile()
        try {
            val sourceFile = File(jobDir, "frame_000.png")
            val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(Color.rgb(72, 80, 96))
                FileOutputStream(sourceFile).use { output ->
                    assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                source.recycle()
            }
            KeplerJobMetadata.write(
                jobDir,
                JSONObject()
                    .put("jobType", "YUV_SINGLE_FRAME")
                    .put("captureMode", CaptureMode.SINGLE_FRAME.name)
                    .put(
                        "frames",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("file", sourceFile.name)
                                .put("enabled", true)
                        )
                    )
            )
            val outputFile = File(jobDir, SINGLE_FRAME_OUTPUT_FILE_NAME)
            val cancellation = object : KeplerPipelineCancellation {
                override val isCancelled: Boolean
                    get() = outputFile.exists()

                override fun throwIfCancelled() {
                    if (isCancelled) throw CancellationException("cancel after output publish")
                }
            }

            var cancelled = false
            try {
                processSingleFrameJobSync(
                    jobDir = jobDir,
                    requestedParams = ClassicYuvFusionPreset.NATURAL.params,
                    cancellation = cancellation,
                    onStatus = {}
                )
            } catch (_: CancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
            assertTrue(outputFile.exists())
            val job = KeplerJobMetadata.read(jobDir)
            assertEquals("PIPELINE_CANCELLED", job.getString("processStatus"))
            assertFalse(job.getBoolean("galleryDisplayUnavailable"))
            assertTrue(job.getBoolean("finalOutputAvailable"))
            assertTrue(job.getBoolean("processingOutputCommitted"))
            assertEquals(
                job.getString("processingAttemptId"),
                job.getString("processingArtifactClaimAttemptId")
            )
            assertEquals(SINGLE_FRAME_OUTPUT_FILE_NAME, job.getString("finalFile"))
        } finally {
            jobDir.deleteRecursively()
        }
    }

    @Test
    fun postClaimOrdinaryFailureRetainsCurrentSingleFrameClaimAndDisplay() {
        val jobDir = createSingleFrameTestJob("kepler-single-post-claim-failure-")
        val previousFailure = singleFrameSuccessFailureForTest
        try {
            singleFrameSuccessFailureForTest = IllegalStateException("post-claim success metadata failed")
            assertThrows(IllegalStateException::class.java) {
                processSingleFrameJobSync(
                    jobDir = jobDir,
                    requestedParams = ClassicYuvFusionPreset.NATURAL.params,
                    onStatus = {}
                )
            }
            val job = KeplerJobMetadata.read(jobDir)
            assertTrue(File(jobDir, SINGLE_FRAME_OUTPUT_FILE_NAME).isFile)
            assertEquals(SINGLE_FRAME_OUTPUT_FILE_NAME, job.getString("finalFile"))
            assertTrue(job.getBoolean("finalOutputAvailable"))
            assertFalse(job.getBoolean("galleryDisplayUnavailable"))
            assertTrue(job.getBoolean("processingOutputCommitted"))
            assertEquals(
                job.getString("processingAttemptId"),
                job.getString("processingArtifactClaimAttemptId")
            )
            assertEquals(SingleFrameCleanupResult.COMMITTED_FINAL_RETAINED.name, job.getString("singleFrameCleanupResult"))
        } finally {
            singleFrameSuccessFailureForTest = previousFailure
            jobDir.deleteRecursively()
        }
    }

    @Test
    fun postClaimFatalFailureRetainsCurrentSingleFrameClaimAndPropagates() {
        val jobDir = createSingleFrameTestJob("kepler-single-post-claim-fatal-")
        val previousFailure = singleFrameSuccessFailureForTest
        try {
            singleFrameSuccessFailureForTest = AssertionError("post-claim fatal failure")
            assertThrows(AssertionError::class.java) {
                processSingleFrameJobSync(
                    jobDir = jobDir,
                    requestedParams = ClassicYuvFusionPreset.SHARP.params,
                    onStatus = {}
                )
            }
            val job = KeplerJobMetadata.read(jobDir)
            assertTrue(File(jobDir, SINGLE_FRAME_OUTPUT_FILE_NAME).isFile)
            assertEquals(SINGLE_FRAME_OUTPUT_FILE_NAME, job.getString("finalFile"))
            assertTrue(job.getBoolean("finalOutputAvailable"))
            assertFalse(job.getBoolean("galleryDisplayUnavailable"))
            assertTrue(job.getBoolean("processingOutputCommitted"))
            assertEquals(
                job.getString("processingAttemptId"),
                job.getString("processingArtifactClaimAttemptId")
            )
            assertEquals("AssertionError", job.getString("processingFailureType"))
        } finally {
            singleFrameSuccessFailureForTest = previousFailure
            jobDir.deleteRecursively()
        }
    }

    @Test
    fun fatalFailureMetadataPersistenceIsNotSwallowedAfterOrdinaryPostClaimFailure() {
        val jobDir = createSingleFrameTestJob("kepler-single-failure-metadata-fatal-")
        val previousSuccessFailure = singleFrameSuccessFailureForTest
        val previousMetadataFailure = singleFrameFailureMetadataFailureForTest
        try {
            singleFrameSuccessFailureForTest = IllegalStateException("ordinary post-claim failure")
            singleFrameFailureMetadataFailureForTest = AssertionError("fatal failure metadata write")
            assertThrows(AssertionError::class.java) {
                processSingleFrameJobSync(
                    jobDir = jobDir,
                    requestedParams = ClassicYuvFusionPreset.CLEAN.params,
                    onStatus = {}
                )
            }
            val job = KeplerJobMetadata.read(jobDir)
            assertTrue(File(jobDir, SINGLE_FRAME_OUTPUT_FILE_NAME).isFile)
            assertEquals(SINGLE_FRAME_OUTPUT_FILE_NAME, job.getString("finalFile"))
            assertTrue(job.getBoolean("processingOutputCommitted"))
            assertEquals(
                job.getString("processingAttemptId"),
                job.getString("processingArtifactClaimAttemptId")
            )
        } finally {
            singleFrameSuccessFailureForTest = previousSuccessFailure
            singleFrameFailureMetadataFailureForTest = previousMetadataFailure
            jobDir.deleteRecursively()
        }
    }

    @Test
    fun preClaimCancellationDoesNotAttributePreviousSingleFrameToNewAttempt() {
        val jobDir = createSingleFrameTestJob("kepler-single-previous-result-")
        try {
            val previous = File(jobDir, SINGLE_FRAME_OUTPUT_FILE_NAME)
            previous.writeBytes(byteArrayOf(1, 2, 3))
            KeplerJobMetadata.update(jobDir) { job ->
                job.put("finalFile", previous.name)
                    .put("processingOutputCommitted", true)
                    .put("processingAttemptId", "old-attempt")
                    .put("processingArtifactClaimAttemptId", "old-attempt")
                    .put("finalOutputAvailable", true)
            }
            var cancellationChecks = 0
            val cancellation = object : KeplerPipelineCancellation {
                override val isCancelled: Boolean
                    get() = cancellationChecks > 0

                override fun throwIfCancelled() {
                    if (cancellationChecks++ > 0) throw CancellationException("cancel before new commit")
                }
            }

            assertThrows(CancellationException::class.java) {
                processSingleFrameJobSync(
                    jobDir = jobDir,
                    requestedParams = ClassicYuvFusionPreset.CLEAN.params,
                    cancellation = cancellation,
                    onStatus = {}
                )
            }
            val job = KeplerJobMetadata.read(jobDir)
            assertFalse(job.optBoolean("processingOutputCommitted", false))
            assertFalse(job.has("processingArtifactClaimAttemptId"))
            assertFalse(job.has("finalFile"))
            assertTrue(previous.isFile)
        } finally {
            jobDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsSingleFrameSourceOutsideJobDirectory() {
        val parent = Files.createTempDirectory("kepler-single-unsafe-").toFile()
        val jobDir = File(parent, "job").apply { mkdirs() }
        try {
            val outside = File(parent, "outside.png")
            val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(Color.GRAY)
                FileOutputStream(outside).use { output ->
                    assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                source.recycle()
            }
            KeplerJobMetadata.write(
                jobDir,
                JSONObject()
                    .put("jobType", "YUV_SINGLE_FRAME")
                    .put(
                        "frames",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("file", "../outside.png")
                                .put("enabled", true)
                        )
                    )
            )

            var rejected = false
            try {
                processSingleFrameJobSync(
                    jobDir = jobDir,
                    requestedParams = ClassicYuvFusionPreset.NATURAL.params,
                    onStatus = {}
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }

            assertTrue(rejected)
            assertFalse(File(jobDir, SINGLE_FRAME_OUTPUT_FILE_NAME).exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun createSingleFrameTestJob(prefix: String): File {
        val jobDir = Files.createTempDirectory(prefix).toFile()
        val sourceFile = File(jobDir, "frame_000.png")
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.rgb(72, 80, 96))
            FileOutputStream(sourceFile).use { output ->
                check(source.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            source.recycle()
        }
        KeplerJobMetadata.write(
            jobDir,
            JSONObject()
                .put("jobType", "YUV_SINGLE_FRAME")
                .put("captureMode", CaptureMode.SINGLE_FRAME.name)
                .put(
                    "frames",
                    JSONArray().put(
                        JSONObject()
                            .put("index", 0)
                            .put("file", sourceFile.name)
                            .put("enabled", true)
                    )
                )
        )
        return jobDir
    }

}
