package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
            assertTrue(KeplerJobMetadata.read(outputDir).getString("processingAttemptId").isNotBlank())
        } finally {
            source.recycle()
            root.deleteRecursively()
        }
    }
}
