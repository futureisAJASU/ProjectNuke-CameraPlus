package com.projectnuke.keplernightlab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class KeplerJobStorageCanonicalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun packedStorage_sourceBytesCountsYuvpackNotConvertedPng() {
        val dir = tmp.newFolder()
        val frames = JSONArray().put(
            JSONObject()
                .put("frameIndex", 0)
                .put("file", "frame_00_color.yuvpack")
                .put("packedSourceFilename", "frame_00_color.yuvpack")
        )
        val job = JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put("yuvPersistenceStrategy", "PACKED_YUV_V1")
            .put("frames", frames)
        KeplerJobMetadata.write(dir, job)
        File(dir, "frame_00_color.yuvpack").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "frame_00_color.png").writeBytes(byteArrayOf(4, 5, 6))
        File(dir, "frame_01_color.png").writeBytes(byteArrayOf(7, 8, 9))

        val info = computeKeplerJobStorage(dir, KeplerJobMetadata.read(dir), null)
        assertEquals(3, info.rawFramesBytes)
    }

    @Test
    fun packedStorage_staleFramePngDoesNotBecomeCanonicalSource() {
        val dir = tmp.newFolder()
        val frames = JSONArray().put(
            JSONObject()
                .put("frameIndex", 0)
                .put("file", "frame_00_color.yuvpack")
                .put("packedSourceFilename", "frame_00_color.yuvpack")
        )
        val job = JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put("yuvPersistenceStrategy", "PACKED_YUV_V1")
            .put("frames", frames)
        KeplerJobMetadata.write(dir, job)
        File(dir, "frame_00_color.yuvpack").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "frame_00_color.png").writeBytes(byteArrayOf(4, 5, 6))

        val info = computeKeplerJobStorage(dir, KeplerJobMetadata.read(dir), null)
        assertEquals(3, info.rawFramesBytes)
        assertEquals(3, info.previewFilesBytes)
        assertEquals(6, info.cleanableBytes)
        assertTrue("totalJobBytes must include all files", info.totalJobBytes >= info.rawFramesBytes + info.previewFilesBytes)
    }

    @Test
    fun packedStorage_missingYuvpack_stalePng_sourceBytesZero() {
        val dir = tmp.newFolder()
        val frames = JSONArray().put(
            JSONObject()
                .put("frameIndex", 0)
                .put("file", "frame_00_color.yuvpack")
                .put("packedSourceFilename", "frame_00_color.yuvpack")
        )
        val job = JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put("yuvPersistenceStrategy", "PACKED_YUV_V1")
            .put("frames", frames)
        KeplerJobMetadata.write(dir, job)
        File(dir, "frame_00_color.png").writeBytes(byteArrayOf(4, 5, 6))

        val info = computeKeplerJobStorage(dir, KeplerJobMetadata.read(dir), null)
        assertEquals(0, info.rawFramesBytes)
        assertTrue("totalJobBytes must preserve stale file bytes", info.totalJobBytes > 0)
    }

    @Test
    fun pngStorage_declaredSourceMissing_otherFramePngDoesNotBecomeSource() {
        val dir = tmp.newFolder()
        val frames = JSONArray().put(
            JSONObject()
                .put("frameIndex", 0)
                .put("file", "frame_00_color.png")
        )
        val job = JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put("frames", frames)
        KeplerJobMetadata.write(dir, job)
        File(dir, "frame_01_color.png").writeBytes(byteArrayOf(7, 8, 9))

        val info = computeKeplerJobStorage(dir, KeplerJobMetadata.read(dir), null)
        assertEquals(0, info.rawFramesBytes)
        assertTrue("totalJobBytes must preserve non-source file bytes", info.totalJobBytes > 0)
    }

    @Test
    fun rawStorage_declaredRawMissing_staleFramePngDoesNotBecomeSource() {
        val dir = tmp.newFolder()
        val frames = JSONArray().put(
            JSONObject()
                .put("frameIndex", 0)
                .put("raw16File", "frame_00.raw16")
        )
        val job = JSONObject()
            .put("jobType", "RAW_NIGHT_FUSION")
            .put("frames", frames)
        KeplerJobMetadata.write(dir, job)
        File(dir, "frame_00.png").writeBytes(byteArrayOf(4, 5, 6))

        val info = computeKeplerJobStorage(dir, KeplerJobMetadata.read(dir), null)
        assertEquals(0, info.rawFramesBytes)
        assertTrue("totalJobBytes must preserve stale file bytes", info.totalJobBytes > 0)
    }

    @Test
    fun deleteSources_noCanonicalSources_canReprocessFalse() {
        val dir = tmp.newFolder()
        val frames = JSONArray().put(
            JSONObject()
                .put("frameIndex", 0)
                .put("file", "frame_00_color.png")
        )
        val job = JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put("frames", frames)
        KeplerJobMetadata.write(dir, job)

        assertEquals(0, canonicalSourceAvailability(dir, KeplerJobMetadata.read(dir), ReprocessJobKind.YUV_FUSION))
    }

    @Test
    fun partialCleanup_canonicalSourceDeleted_staleFrameDoesNotKeepCanReprocessTrue() {
        val dir = tmp.newFolder()
        val frames = JSONArray().put(
            JSONObject()
                .put("frameIndex", 0)
                .put("file", "frame_00_color.png")
        )
        val job = JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put("frames", frames)
        KeplerJobMetadata.write(dir, job)
        File(dir, "frame_00_color.png").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(dir, "frame_00_color.jpg").writeBytes(byteArrayOf(5, 6))

        val jobAfter = KeplerJobMetadata.read(dir)
        File(dir, "frame_00_color.png").delete()
        assertEquals(0, canonicalSourceAvailability(dir, jobAfter, ReprocessJobKind.YUV_FUSION))
        assertFalse(canReprocessFromCanonicalCounts(dir, jobAfter, ReprocessJobKind.YUV_FUSION))
    }

    @Test
    fun keepSourceOnly_canonicalSourcesRemain_canReprocessTrue() {
        val dir = tmp.newFolder()
        val frames = JSONArray().put(
            JSONObject()
                .put("frameIndex", 0)
                .put("file", "frame_00_color.png")
        )
        val job = JSONObject()
            .put("jobType", "YUV_SINGLE_FRAME")
            .put("frames", frames)
        KeplerJobMetadata.write(dir, job)
        File(dir, "frame_00_color.png").writeBytes(byteArrayOf(1, 2, 3, 4))

        val jobAfter = KeplerJobMetadata.read(dir)
        assertEquals(1, canonicalSourceAvailability(dir, jobAfter, ReprocessJobKind.YUV_FUSION))
        assertTrue(canReprocessFromCanonicalCounts(dir, jobAfter, ReprocessJobKind.YUV_FUSION))
    }

    @Test
    fun packedKeepSourceOnly_yuvpackExists_convertedPngAbsent_canReprocessTrue() {
        val dir = tmp.newFolder()
        val frames = JSONArray().put(
            JSONObject()
                .put("frameIndex", 0)
                .put("file", "frame_00_color.yuvpack")
                .put("packedSourceFilename", "frame_00_color.yuvpack")
        )
        val job = JSONObject()
            .put("jobType", "YUV_SINGLE_FRAME")
            .put("yuvPersistenceStrategy", "PACKED_YUV_V1")
            .put("frames", frames)
        KeplerJobMetadata.write(dir, job)
        File(dir, "frame_00_color.yuvpack").writeBytes(byteArrayOf(1, 2, 3))

        val jobAfter = KeplerJobMetadata.read(dir)
        assertEquals(1, canonicalSourceAvailability(dir, jobAfter, ReprocessJobKind.YUV_FUSION))
        assertTrue(canReprocessFromCanonicalCounts(dir, jobAfter, ReprocessJobKind.YUV_FUSION))
    }

    @Test
    fun countActualSourceFrames_declaredSourceMissing_stalePngDoesNotCount() {
        val dir = tmp.newFolder()
        val frames = JSONArray().put(
            JSONObject()
                .put("frameIndex", 0)
                .put("file", "frame_00_color.png")
        )
        val job = JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put("frames", frames)
        KeplerJobMetadata.write(dir, job)
        File(dir, "frame_01_color.png").writeBytes(byteArrayOf(5, 6))

        assertEquals(0, countActualSourceFrames(dir, job, ReprocessJobKind.YUV_FUSION))
    }
}
