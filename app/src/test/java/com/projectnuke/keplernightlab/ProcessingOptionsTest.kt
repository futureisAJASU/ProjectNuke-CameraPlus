package com.projectnuke.keplernightlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProcessingOptionsTest {
    @Test
    fun singleFrameModeForcesOneFrameWithoutChangingMultiFramePlan() {
        val estimated = FramePlan(framesToCapture = 7, maxFrames = 10, reason = "dark scene")

        assertEquals(
            FramePlan(framesToCapture = 1, maxFrames = 1, reason = "Single-frame capture"),
            effectiveFramePlan(CaptureMode.SINGLE_FRAME, estimated)
        )
        assertEquals(estimated, effectiveFramePlan(CaptureMode.MULTI_FRAME, estimated))
    }

    @Test
    fun processingSettingsNormalizeAndResolveUserOverrides() {
        val normalized = ProcessingSettings(
            presetName = "sharp",
            denoiseStrength = 2f,
            sharpenAmount = -1f,
            localContrastAmount = 1f
        ).normalized()

        assertEquals(ClassicYuvFusionPreset.SHARP.name, normalized.presetName)
        assertEquals(0.55f, normalized.denoiseStrength, 0.0001f)
        assertEquals(0f, normalized.sharpenAmount, 0.0001f)
        assertEquals(0.18f, normalized.localContrastAmount, 0.0001f)

        val resolved = normalized.resolvedParams()
        assertEquals(ClassicYuvFusionPreset.SHARP.name, resolved.presetName)
        assertEquals(normalized.denoiseStrength, resolved.denoiseStrength, 0.0001f)
        assertEquals(normalized.sharpenAmount, resolved.sharpenAmount, 0.0001f)
        assertEquals(normalized.localContrastAmount, resolved.localContrastAmount, 0.0001f)
        assertEquals(ClassicYuvFusionPreset.SHARP.params.saturationBoost, resolved.saturationBoost, 0.0001f)
    }

    @Test
    fun captureTimeProcessingParamsRemainLoadableBeforeFirstProcessingRun() {
        val expected = ClassicYuvFusionPreset.NIGHT_BRIGHT.params
        val job = JSONObject()
            .put("processingPresetName", expected.presetName)
            .put("processingParams", expected.toJson())

        val loaded = loadClassicYuvFusionParams(job)

        assertEquals(expected.presetName, loaded.presetName)
        assertEquals(expected.denoiseStrength, loaded.denoiseStrength, 0.0001f)
        assertEquals(expected.sharpenAmount, loaded.sharpenAmount, 0.0001f)
        assertEquals(expected.localContrastAmount, loaded.localContrastAmount, 0.0001f)
    }

    @Test
    fun singleFrameIdentityUsesExplicitCanonicalMetadata() {
        assertTrue(isSingleFrameJob(JSONObject().put("captureMode", "SINGLE_FRAME")))
        assertTrue(isSingleFrameJob(JSONObject().put("jobType", "YUV_SINGLE_FRAME")))
        assertTrue(
            isSingleFrameJob(
                JSONObject()
                    .put("requestedFrames", 1)
                    .put("savedFrames", 1)
                    .put("fusionEngine", "single_yuv_isp_v1")
            )
        )
        assertFalse(
            isSingleFrameJob(
                JSONObject()
                    .put("requestedFrames", 1)
                    .put("savedFrames", 1)
                    .put("fusionEngine", "classic_yuv_v1")
            )
        )
    }
}
