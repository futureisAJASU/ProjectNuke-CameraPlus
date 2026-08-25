package com.projectnuke.keplernightlab

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 7: PACKED_YUV_V1 A/B integration, still NON-DEFAULT. The production
 * path stays PNG; packed selection is durable job metadata stamped at capture
 * creation, the background lane converts packed sources into the exact PNG
 * inputs fusion already consumes, and any digest failure fails closed.
 */
@RunWith(RobolectricTestRunner::class)
class PackedYuvStrategyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun frame(index: Int, width: Int = 8, height: Int = 6): BufferedYuvFrame {
        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)
        return BufferedYuvFrame(
            index = index,
            timestampNs = 1000L + index,
            width = width,
            height = height,
            y = ByteArray(ySize) { it.toByte() },
            u = ByteArray(uvSize) { (it + index).toByte() },
            v = ByteArray(uvSize) { (it * 3 + index).toByte() },
            yRowStride = width,
            yPixelStride = 1,
            uRowStride = width / 2,
            uPixelStride = 1,
            vRowStride = width / 2,
            vPixelStride = 1
        )
    }

    private fun packJob(frameCount: Int): Pair<File, JSONObject> {
        val jobDir = tmp.newFolder("packed-job")
        val frames = JSONArray()
        repeat(frameCount) { index ->
            val name = yuvFrameFileName(index, YuvPersistenceStrategy.PACKED_YUV_V1)
            PackedYuvFrameStore.pack(frame(index), rotationDegrees = 90, outFile = File(jobDir, name))
            frames.put(
                JSONObject()
                    .put("frameIndex", index)
                    .put("filename", name)
            )
        }
        val job = JSONObject()
            .put(YuvPersistenceStrategy.JOB_KEY, YuvPersistenceStrategy.PACKED_YUV_V1.name)
            .put("frames", frames)
        KeplerJobMetadata.write(jobDir, job)
        return jobDir to job
    }

    @Test
    fun pngStrategy_existingBehaviorUnchanged() {
        // Default parsing keeps legacy PNG semantics with NO metadata present.
        assertEquals(YuvPersistenceStrategy.PNG, YuvPersistenceStrategy.fromNameOrDefault(null))
        assertEquals(YuvPersistenceStrategy.PNG, YuvPersistenceStrategy.fromNameOrDefault(""))
        assertEquals(YuvPersistenceStrategy.PNG, YuvPersistenceStrategy.fromNameOrDefault("GARBAGE"))
        // Default naming is byte-for-byte the historical layout.
        assertEquals("frame_00_color.png", yuvFrameFileName(0, YuvPersistenceStrategy.PNG))
        assertEquals("frame_03_color.png", yuvFrameFileName(3, YuvPersistenceStrategy.PNG))
        // And a PNG-selected manifest is not treated as a conversion target.
        val job = JSONObject().put(YuvPersistenceStrategy.JOB_KEY, "PNG")
        assertFalse(PackedYuvBackgroundConverter.isSelected(job))
    }

    @Test
    fun strategyPersistedInJobMetadata() {
        val (_, job) = packJob(1)
        assertEquals(
            "PACKED_YUV_V1",
            job.getString(YuvPersistenceStrategy.JOB_KEY)
        )
        // Durable round-trip through the real job.json store.
        val jobDir = tmp.newFolder("stamp-job")
        KeplerJobMetadata.write(jobDir, JSONObject())
        KeplerJobMetadata.update(jobDir) {
            it.put(YuvPersistenceStrategy.JOB_KEY, YuvPersistenceStrategy.PACKED_YUV_V1.name)
        }
        assertTrue(PackedYuvBackgroundConverter.isSelected(KeplerJobMetadata.read(jobDir)))
    }

    @Test
    fun packedNaming_usesHonestDurableExtension() {
        assertEquals(
            "frame_02_color.yuvpack",
            yuvFrameFileName(2, YuvPersistenceStrategy.PACKED_YUV_V1)
        )
    }

    @Test
    fun packedStrategy_fourFramesDurableBeforeHandoff() {
        // Four fully durable packed sources exist BEFORE any handoff concept:
        // every frame verifies structurally AND by full payload digest.
        val (jobDir, _) = packJob(4)
        repeat(4) { index ->
            val name = yuvFrameFileName(index, YuvPersistenceStrategy.PACKED_YUV_V1)
            val decoded = PackedYuvFrameStore.verifyFull(File(jobDir, name))
            assertEquals(index, decoded.frameIndex)
        }
    }

    @Test
    fun packedStrategy_digestFailureFailsClosed() {
        val (jobDir, job) = packJob(2)
        val victimName = job.getJSONArray("frames").getJSONObject(1).getString("filename")
        val victim = File(jobDir, victimName)
        val bytes = victim.readBytes()
        // Flip one payload byte AFTER the header region.
        val flipAt = bytes.size - 8
        bytes[flipAt] = (bytes[flipAt].toInt() xor 0x01).toByte()
        victim.writeBytes(bytes)

        assertThrows(Exception::class.java) {
            PackedYuvBackgroundConverter.convertJob(jobDir, JSONObject(job.toString()))
        }
        // No converted artifact may appear for the corrupted frame.
        assertFalse(File(jobDir, "frame_01_color.png").exists())
    }

    @Test
    fun packedStrategy_backgroundConversionProducesFusionInputs() {
        val (jobDir, original) = packJob(3)

        val result = PackedYuvBackgroundConverter.convertJob(jobDir, JSONObject(original.toString()))

        assertEquals(3, result.convertedFrames)
        assertTrue(result.durationMs >= 0)
        val persistedJob = KeplerJobMetadata.read(jobDir)
        assertTrue(persistedJob.getBoolean("packedSourcesConverted"))
        val frames = persistedJob.getJSONArray("frames")
        repeat(3) { index ->
            val entry = frames.getJSONObject(index)
            // Manifest authority now points fusion at the converted PNG while
            // keeping the exact packed source identity for auditability.
            assertEquals("frame_0${index}_color.png", entry.getString("filename"))
            assertEquals(
                "frame_0${index}_color.yuvpack",
                entry.getString("packedSourceFilename")
            )
            assertTrue(File(jobDir, entry.getString("filename")).isFile)
        }
        // Idempotent recovery pass: everything already verified/converted.
        val secondPass = PackedYuvBackgroundConverter.convertJob(jobDir, persistedJob)
        assertEquals(0, secondPass.convertedFrames)
    }

    @Test
    fun packedStrategy_productionManifestUsesFileKey_andIsConverted() {
        // PRODUCTION SHAPE: writeColorJobJson stamps packed sources under the
        // "file" key. Conversion must find them, convert them, and repoint the
        // exact fusion-consumed identity while retaining the packed source.
        val jobDir = tmp.newFolder("packed-production-shape")
        val frames = JSONArray()
        repeat(2) { index ->
            val name = yuvFrameFileName(index, YuvPersistenceStrategy.PACKED_YUV_V1)
            PackedYuvFrameStore.pack(frame(index), rotationDegrees = 0, outFile = File(jobDir, name))
            frames.put(
                JSONObject()
                    .put("index", index)
                    .put("frameIndex", index)
                    .put("file", name)
                    .put("timestampNs", 1000L + index)
                    .put("persisted", true)
            )
        }
        val job = JSONObject()
            .put(YuvPersistenceStrategy.JOB_KEY, YuvPersistenceStrategy.PACKED_YUV_V1.name)
            .put("frames", frames)
        KeplerJobMetadata.write(jobDir, job)

        val result = PackedYuvBackgroundConverter.convertJob(jobDir, KeplerJobMetadata.read(jobDir))

        assertEquals(2, result.convertedFrames)
        val persistedFrames = KeplerJobMetadata.read(jobDir).getJSONArray("frames")
        repeat(2) { index ->
            val entry = persistedFrames.getJSONObject(index)
            assertEquals("frame_0${index}_color.png", entry.getString("file"))
            assertEquals("frame_0${index}_color.yuvpack", entry.getString("packedSourceFilename"))
            assertTrue(File(jobDir, entry.getString("file")).isFile)
        }
    }

    @Test
    fun strategyStamp_survivesFullMetadataRewrites() {
        val jobDir = tmp.newFolder("stamp-survival")
        KeplerJobMetadata.write(jobDir, JSONObject())
        KeplerJobMetadata.update(jobDir) {
            it.put(YuvPersistenceStrategy.JOB_KEY, YuvPersistenceStrategy.PACKED_YUV_V1.name)
        }
        val jobFile = File(jobDir, JOB_JSON_FILE_NAME)

        // Rewrite WITH the capture-creation value (production metadata writer).
        writeColorJobJson(
            jobFile = jobFile,
            status = "CAPTURING",
            cameraId = "0",
            width = 8,
            height = 8,
            outputWidth = 8,
            outputHeight = 8,
            rotationDegrees = 0,
            requestedFrames = 2,
            savedFrames = 0,
            frameManifest = emptyList(),
            gyroFile = null,
            rotationVectorFile = null,
            gyroSampleCount = 0,
            rotationVectorSampleCount = 0,
            motionInfo = "test",
            yuvPersistenceStrategyName = YuvPersistenceStrategy.PACKED_YUV_V1.name
        )
        assertTrue(PackedYuvBackgroundConverter.isSelected(KeplerJobMetadata.read(jobDir)))

        // Rewrite WITHOUT the parameter: the previous durable key must carry
        // forward so recovery passes never lose the strategy.
        writeColorJobJson(
            jobFile = jobFile,
            status = "CAPTURE_COMPLETE",
            cameraId = "0",
            width = 8,
            height = 8,
            outputWidth = 8,
            outputHeight = 8,
            rotationDegrees = 0,
            requestedFrames = 2,
            savedFrames = 2,
            frameManifest = emptyList(),
            gyroFile = null,
            rotationVectorFile = null,
            gyroSampleCount = 0,
            rotationVectorSampleCount = 0,
            motionInfo = "test"
        )
        assertTrue(PackedYuvBackgroundConverter.isSelected(KeplerJobMetadata.read(jobDir)))
    }

    @Test
    fun mixedHistoricalPngJob_reprocessesWithoutConversion() {
        // A legacy job has NO strategy key and plain PNG sources: it must be
        // reprocessable exactly as before, with zero conversion work.
        val jobDir = tmp.newFolder("legacy-png-job")
        File(jobDir, "frame_00_color.png").writeBytes(byteArrayOf(1, 2, 3))
        val legacy = JSONObject().put(
            "frames",
            JSONArray().put(JSONObject().put("frameIndex", 0).put("filename", "frame_00_color.png"))
        )
        KeplerJobMetadata.write(jobDir, legacy)

        assertFalse(PackedYuvBackgroundConverter.isSelected(KeplerJobMetadata.read(jobDir)))
        val result = PackedYuvBackgroundConverter.convertJob(
            jobDir,
            JSONObject(KeplerJobMetadata.read(jobDir).toString())
        )
        assertEquals(0, result.convertedFrames)
        // Legacy manifest untouched.
        assertEquals(
            "frame_00_color.png",
            KeplerJobMetadata.read(jobDir).getJSONArray("frames").getJSONObject(0).getString("filename")
        )
    }
}
