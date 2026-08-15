package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PostCaptureProcessingIntegrationTest {
    @Test
    fun superResolutionProductionSeamCommitsSyntheticJobOutput() {
        val root = Files.createTempDirectory("post-capture-sr").toFile()
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            val frameA = File(root, "frame_a.png")
            val frameB = File(root, "frame_b.png")
            frameA.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            frameB.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val outputDir = File(root, "job").also { check(it.mkdirs()) }
            val policy = SuperResolutionTargetPolicy(
                sourceMode = SuperResolutionSourceMode.BINNED_12MP_YUV,
                defaultTargetMegapixels = 0.000016,
                maxSafeTargetMegapixels = 0.000016,
                maxExperimentalTargetMegapixels = 0.000032,
                maxLinearScale = 2.0
            )
            val result = runSuperResolutionFusion(
                SuperResolutionFusionRequest(
                    context = RuntimeEnvironment.getApplication(),
                    inputFrameFiles = listOf(frameA, frameB),
                    outputDir = outputDir,
                    sourceMode = SuperResolutionSourceMode.BINNED_12MP_YUV,
                    targetPolicy = policy,
                    targetMegapixels = policy.defaultTargetMegapixels,
                    maxFrames = 2,
                    status = {}
                )
            )
            assertNotNull(result.outputFile)
            assertTrue(requireNotNull(result.outputFile).isFile)
            assertEquals("COMPLETE", KeplerJobMetadata.read(outputDir).getString("status"))
            val persisted = KeplerJobMetadata.read(outputDir)
            assertTrue(persisted.getString("processingAttemptId").isNotBlank())
            assertTrue(persisted.optInt("processingArtifactSettlementCount") >= 1)
        } finally {
            source.recycle()
            root.deleteRecursively()
        }
    }

    @Test
    fun superResolutionPostClaimOrdinaryFailurePreservesPartialClaim() {
        val root = Files.createTempDirectory("post-claim-sr-failure").toFile()
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            val frameA = File(root, "frame_a.png")
            val frameB = File(root, "frame_b.png")
            frameA.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            frameB.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val outputDir = File(root, "job").also { check(it.mkdirs()) }
            val policy = SuperResolutionTargetPolicy(
                sourceMode = SuperResolutionSourceMode.BINNED_12MP_YUV,
                defaultTargetMegapixels = 0.000016,
                maxSafeTargetMegapixels = 0.000016,
                maxExperimentalTargetMegapixels = 0.000032,
                maxLinearScale = 2.0
            )
            superResolutionJobWriteFailureForTest = IllegalStateException("post-claim write")
            val result = runSuperResolutionFusion(
                SuperResolutionFusionRequest(
                    context = RuntimeEnvironment.getApplication(),
                    inputFrameFiles = listOf(frameA, frameB),
                    outputDir = outputDir,
                    sourceMode = SuperResolutionSourceMode.BINNED_12MP_YUV,
                    targetPolicy = policy,
                    targetMegapixels = policy.defaultTargetMegapixels,
                    maxFrames = 2,
                    status = {}
                )
            )
            val persisted = KeplerJobMetadata.read(outputDir)
            assertFalse(result.outputFile?.isFile == true)
            assertEquals("PARTIAL", persisted.getString("status"))
            assertTrue(persisted.getBoolean("processingOutputCommitted"))
            assertEquals(
                persisted.getString("processingAttemptId"),
                persisted.getString("processingArtifactClaimAttemptId")
            )
        } finally {
            superResolutionJobWriteFailureForTest = null
            source.recycle()
            root.deleteRecursively()
        }
    }

    @Test
    fun superResolutionPostClaimFatalFailurePropagatesAndPreservesClaim() {
        val root = Files.createTempDirectory("post-claim-sr-fatal").toFile()
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            val frameA = File(root, "frame_a.png")
            val frameB = File(root, "frame_b.png")
            frameA.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            frameB.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val outputDir = File(root, "job").also { check(it.mkdirs()) }
            val policy = SuperResolutionTargetPolicy(
                sourceMode = SuperResolutionSourceMode.BINNED_12MP_YUV,
                defaultTargetMegapixels = 0.000016,
                maxSafeTargetMegapixels = 0.000016,
                maxExperimentalTargetMegapixels = 0.000032,
                maxLinearScale = 2.0
            )
            superResolutionJobWriteFailureForTest = AssertionError("fatal post-claim write")
            assertThrows(AssertionError::class.java) {
                runSuperResolutionFusion(
                    SuperResolutionFusionRequest(
                        context = RuntimeEnvironment.getApplication(),
                        inputFrameFiles = listOf(frameA, frameB),
                        outputDir = outputDir,
                        sourceMode = SuperResolutionSourceMode.BINNED_12MP_YUV,
                        targetPolicy = policy,
                        targetMegapixels = policy.defaultTargetMegapixels,
                        maxFrames = 2,
                        status = {}
                    )
                )
            }
            val persisted = KeplerJobMetadata.read(outputDir)
            assertTrue(persisted.getBoolean("processingOutputCommitted"))
            assertEquals(
                persisted.getString("processingAttemptId"),
                persisted.getString("processingArtifactClaimAttemptId")
            )
        } finally {
            superResolutionJobWriteFailureForTest = null
            source.recycle()
            root.deleteRecursively()
        }
    }
}
