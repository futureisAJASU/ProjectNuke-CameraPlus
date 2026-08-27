package com.projectnuke.keplernightlab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
        assertEquals(0, info.intermediateFilesBytes)
    }

    @Test
    fun pngStrategy_storageCountsDeclaredSourcePng() {
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

        val info = computeKeplerJobStorage(dir, KeplerJobMetadata.read(dir), null)
        assertEquals(4, info.rawFramesBytes)
    }

    @Test
    fun rawStorage_countsDeclaredRaw16OrDngFallback() {
        val dir = tmp.newFolder()
        val frames = JSONArray().put(
            JSONObject()
                .put("frameIndex", 0)
                .put("raw16File", "frame_00.raw16")
                .put("dngFile", "frame_00.dng")
        )
        val job = JSONObject()
            .put("jobType", "RAW_NIGHT_FUSION")
            .put("frames", frames)
        KeplerJobMetadata.write(dir, job)
        File(dir, "frame_00.raw16").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "frame_00.dng").writeBytes(byteArrayOf(4, 5, 6, 7))

        val info = computeKeplerJobStorage(dir, KeplerJobMetadata.read(dir), null)
        assertEquals(3, info.rawFramesBytes)
    }
}
