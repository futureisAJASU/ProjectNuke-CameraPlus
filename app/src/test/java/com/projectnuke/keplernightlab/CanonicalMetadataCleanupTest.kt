package com.projectnuke.keplernightlab

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CanonicalMetadataCleanupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun yuvRoot(): File {
        val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        return File(pictures, "KeplerYuvFusion").apply { mkdirs() }
    }

    private fun rawRoot(): File {
        val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        return File(pictures, "KeplerRawFusion").apply { mkdirs() }
    }

    private fun makeJob(root: File, name: String, jobType: String, frames: JSONArray, finalFile: String? = null): File {
        val job = File(root, name).apply { mkdirs() }
        val jobJson = JSONObject()
            .put("jobType", jobType)
            .put("status", "COMPLETE")
            .put("recoveryState", "STABLE")
            .put("frames", frames)
        finalFile?.let { jobJson.put("finalNightFusionFile", it) }
        KeplerJobMetadata.write(job, jobJson)
        return job
    }

    @Test
    fun deleteSources_nonFrameMetadataRaw_deletesDeclaredRaw() {
        val root = rawRoot()
        val job = File(root, "KPL_RAW_FUSION_NONFRAME_RAW").apply { mkdirs() }
        try {
            val frames = JSONArray().put(
                JSONObject().put("index", 0).put("raw16File", "source_001.raw16").put("enabled", true)
            )
            makeJob(root, "KPL_RAW_FUSION_NONFRAME_RAW", "RAW_NIGHT_FUSION", frames)
            File(job, "source_001.raw16").writeBytes(byteArrayOf(1,2,3))
            File(job, "stale_source_b.raw16").writeBytes(byteArrayOf(9))
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.SOURCE_FRAMES_ONLY)
            assertTrue(result.isSuccess)
            assertFalse(File(job, "source_001.raw16").exists())
            val metadata = KeplerJobMetadata.read(job)
            assertFalse(metadata.optBoolean("sourceFramesAvailable", true))
            assertFalse(metadata.optBoolean("canReprocess", true))
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun deleteSources_nonFrameMetadataPng_deletesDeclaredPng() {
        val root = yuvRoot()
        val job = File(root, "KPL_YUV_FUSION_NONFRAME_PNG").apply { mkdirs() }
        try {
            val frames = JSONArray().put(
                JSONObject().put("index", 0).put("file", "legacy_source_a.png").put("enabled", true)
            )
            makeJob(root, "KPL_YUV_FUSION_NONFRAME_PNG", "YUV_NIGHT_FUSION", frames)
            File(job, "legacy_source_a.png").writeBytes(byteArrayOf(1))
            File(job, "stale_source_b.png").writeBytes(byteArrayOf(2))
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.SOURCE_FRAMES_ONLY)
            assertTrue(result.isSuccess)
            assertFalse(File(job, "legacy_source_a.png").exists())
            assertTrue(File(job, "stale_source_b.png").exists())
            val metadata = KeplerJobMetadata.read(job)
            assertFalse(metadata.optBoolean("sourceFramesAvailable", true))
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun deleteSources_nonFrameMetadataPacked_deletesDeclaredYuvpack() {
        val root = yuvRoot()
        val job = File(root, "KPL_YUV_FUSION_NONFRAME_PACKED").apply { mkdirs() }
        try {
            val frames = JSONArray().put(
                JSONObject().put("index", 0).put("packedSourceFilename", "legacy_source_a.yuvpack").put("enabled", true)
            )
            val jobJson = JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(YuvPersistenceStrategy.JOB_KEY, YuvPersistenceStrategy.PACKED_YUV_V1.name)
                .put("status", "COMPLETE")
                .put("recoveryState", "STABLE")
                .put("frames", frames)
            KeplerJobMetadata.write(job, jobJson)
            File(job, "legacy_source_a.yuvpack").writeBytes(byteArrayOf(1))
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.SOURCE_FRAMES_ONLY)
            assertTrue(result.isSuccess)
            assertFalse(File(job, "legacy_source_a.yuvpack").exists())
            val metadata = KeplerJobMetadata.read(job)
            assertFalse(metadata.optBoolean("sourceFramesAvailable", true))
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun keepSourceOnly_nonFrameMetadataRaw_preservesSource() {
        val root = rawRoot()
        val job = File(root, "KPL_RAW_FUSION_KEEP_RAW").apply { mkdirs() }
        try {
            val frames = JSONArray()
                .put(JSONObject().put("index", 0).put("raw16File", "source_001.raw16").put("enabled", true))
                .put(JSONObject().put("index", 1).put("raw16File", "source_002.raw16").put("enabled", true))
            makeJob(root, "KPL_RAW_FUSION_KEEP_RAW", "RAW_NIGHT_FUSION", frames, "final.png")
            File(job, "source_001.raw16").writeBytes(byteArrayOf(1))
            File(job, "source_002.raw16").writeBytes(byteArrayOf(1))
            File(job, "final.png").writeBytes(byteArrayOf(2))
            File(job, "preview_debug.log").writeBytes(byteArrayOf(3))
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.SOURCE_ONLY)
            assertTrue(result.isSuccess)
            assertTrue(File(job, "source_001.raw16").exists())
            assertTrue(File(job, "source_002.raw16").exists())
            assertFalse(File(job, "preview_debug.log").exists())
            val metadata = KeplerJobMetadata.read(job)
            assertTrue(metadata.optBoolean("sourceFramesAvailable"))
            assertTrue(metadata.optBoolean("canReprocess"))
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun keepSourceOnly_nonFrameMetadataPng_preservesSource() {
        val root = yuvRoot()
        val job = File(root, "KPL_YUV_FUSION_KEEP_PNG").apply { mkdirs() }
        try {
            val frames = JSONArray().put(
                JSONObject().put("index", 0).put("file", "legacy_source_a.png").put("enabled", true)
            )
            makeJob(root, "KPL_YUV_FUSION_KEEP_PNG", "YUV_NIGHT_FUSION", frames, "final.png")
            File(job, "legacy_source_a.png").writeBytes(byteArrayOf(1))
            File(job, "final.png").writeBytes(byteArrayOf(2))
            File(job, "preview_image.png").writeBytes(byteArrayOf(3))
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.SOURCE_ONLY)
            assertTrue(result.isSuccess)
            assertTrue(File(job, "legacy_source_a.png").exists())
            assertFalse(File(job, "preview_image.png").exists())
            val metadata = KeplerJobMetadata.read(job)
            assertTrue(metadata.optBoolean("sourceFramesAvailable"))
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun keepSourceOnly_nonFrameMetadataYuv_preservesSource() {
        val root = yuvRoot()
        val job = File(root, "KPL_YUV_FUSION_KEEP_YUV").apply { mkdirs() }
        try {
            val frames = JSONArray().put(
                JSONObject().put("index", 0).put("file", "legacy_source_a.yuv").put("enabled", true)
            )
            makeJob(root, "KPL_YUV_FUSION_KEEP_YUV", "YUV_NIGHT_FUSION", frames, "final.png")
            File(job, "legacy_source_a.yuv").writeBytes(byteArrayOf(1))
            File(job, "intermediate.bin").writeBytes(byteArrayOf(2))
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.SOURCE_ONLY)
            assertTrue(result.isSuccess)
            assertTrue(File(job, "legacy_source_a.yuv").exists())
            assertFalse(File(job, "intermediate.bin").exists())
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun keepSourceOnly_nonFrameMetadataPacked_preservesYuvpack() {
        val root = yuvRoot()
        val job = File(root, "KPL_YUV_FUSION_KEEP_PACKED").apply { mkdirs() }
        try {
            val frames = JSONArray()
                .put(JSONObject().put("index", 0).put("packedSourceFilename", "legacy_source_a.yuvpack").put("file", "frame_00_color.png").put("enabled", true))
                .put(JSONObject().put("index", 1).put("packedSourceFilename", "legacy_source_b.yuvpack").put("file", "frame_01_color.png").put("enabled", true))
            val jobJson = JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(YuvPersistenceStrategy.JOB_KEY, YuvPersistenceStrategy.PACKED_YUV_V1.name)
                .put("status", "COMPLETE")
                .put("recoveryState", "STABLE")
                .put("frames", frames)
                .put("finalNightFusionFile", "final.png")
            KeplerJobMetadata.write(job, jobJson)
            File(job, "legacy_source_a.yuvpack").writeBytes(byteArrayOf(1))
            File(job, "legacy_source_b.yuvpack").writeBytes(byteArrayOf(1))
            File(job, "frame_00_color.png").writeBytes(byteArrayOf(2))
            File(job, "frame_01_color.png").writeBytes(byteArrayOf(2))
            File(job, "final.png").writeBytes(byteArrayOf(3))
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.SOURCE_ONLY)
            assertTrue(result.isSuccess)
            assertTrue(File(job, "legacy_source_a.yuvpack").exists())
            assertTrue(File(job, "legacy_source_b.yuvpack").exists())
            assertFalse(File(job, "frame_00_color.png").exists())
            assertFalse(File(job, "frame_01_color.png").exists())
            assertFalse(File(job, "final.png").exists())
            val metadata = KeplerJobMetadata.read(job)
            assertTrue(metadata.optBoolean("sourceFramesAvailable"))
            assertTrue(metadata.optBoolean("canReprocess"))
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun deleteDerivedCache_nonFrameMetadataPng_doesNotDeleteCanonicalSource() {
        val root = yuvRoot()
        val job = File(root, "KPL_YUV_FUSION_DERIVED_PNG").apply { mkdirs() }
        try {
            val frames = JSONArray().put(
                JSONObject().put("index", 0).put("file", "legacy_source_a.png").put("enabled", true)
            )
            makeJob(root, "KPL_YUV_FUSION_DERIVED_PNG", "YUV_NIGHT_FUSION", frames, "final.png")
            File(job, "legacy_source_a.png").writeBytes(byteArrayOf(1))
            File(job, "preview_image.png").writeBytes(byteArrayOf(2))
            File(job, "final.png").writeBytes(byteArrayOf(3))
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.DERIVED_CACHE_ONLY)
            assertTrue(result.isSuccess)
            assertTrue(File(job, "legacy_source_a.png").exists())
            assertFalse(File(job, "preview_image.png").exists())
            assertTrue(File(job, "final.png").exists())
            val metadata = KeplerJobMetadata.read(job)
            assertTrue(metadata.optBoolean("sourceFramesAvailable"))
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun deleteDerivedCache_nonFrameMetadataYuv_doesNotDeleteCanonicalSource() {
        val root = yuvRoot()
        val job = File(root, "KPL_YUV_FUSION_DERIVED_YUV").apply { mkdirs() }
        try {
            val frames = JSONArray().put(
                JSONObject().put("index", 0).put("file", "legacy_source_a.yuv").put("enabled", true)
            )
            makeJob(root, "KPL_YUV_FUSION_DERIVED_YUV", "YUV_NIGHT_FUSION", frames, "final.png")
            File(job, "legacy_source_a.yuv").writeBytes(byteArrayOf(1))
            File(job, "merged_yuv_intermediate.bin").writeBytes(byteArrayOf(2))
            File(job, "final.png").writeBytes(byteArrayOf(3))
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.DERIVED_CACHE_ONLY)
            assertTrue(result.isSuccess)
            assertTrue(File(job, "legacy_source_a.yuv").exists())
            assertFalse(File(job, "merged_yuv_intermediate.bin").exists())
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun deleteDerivedCache_nonFrameMetadataPacked_preservesYuvpack_deletesConvertedPng() {
        val root = yuvRoot()
        val job = File(root, "KPL_YUV_FUSION_DERIVED_PACKED").apply { mkdirs() }
        try {
            val frames = JSONArray().put(
                JSONObject().put("index", 0).put("packedSourceFilename", "legacy_source_a.yuvpack").put("file", "frame_00_color.png").put("enabled", true)
            )
            val jobJson = JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(YuvPersistenceStrategy.JOB_KEY, YuvPersistenceStrategy.PACKED_YUV_V1.name)
                .put("status", "COMPLETE")
                .put("recoveryState", "STABLE")
                .put("frames", frames)
                .put("finalNightFusionFile", "final.png")
            KeplerJobMetadata.write(job, jobJson)
            File(job, "legacy_source_a.yuvpack").writeBytes(byteArrayOf(1))
            File(job, "frame_00_color.png").writeBytes(byteArrayOf(2))
            File(job, "final.png").writeBytes(byteArrayOf(3))
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.DERIVED_CACHE_ONLY)
            assertTrue(result.isSuccess)
            assertTrue(File(job, "legacy_source_a.yuvpack").exists())
            assertFalse(File(job, "frame_00_color.png").exists())
            assertTrue(File(job, "final.png").exists())
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun deleteDerivedCache_staleUndeclaredPng_isNotProtectedAsCanonical() {
        val root = yuvRoot()
        val job = File(root, "KPL_YUV_FUSION_STALE_PNG").apply { mkdirs() }
        try {
            val frames = JSONArray().put(
                JSONObject().put("index", 0).put("file", "legacy_source_a.png").put("enabled", true)
            )
            makeJob(root, "KPL_YUV_FUSION_STALE_PNG", "YUV_NIGHT_FUSION", frames, "final.png")
            File(job, "legacy_source_a.png").writeBytes(byteArrayOf(1))
            File(job, "stale_source_b.png").writeBytes(byteArrayOf(2))
            File(job, "final.png").writeBytes(byteArrayOf(3))
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.DERIVED_CACHE_ONLY)
            assertTrue(result.isSuccess)
            assertTrue(File(job, "legacy_source_a.png").exists())
            assertFalse(File(job, "stale_source_b.png").exists())
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }
}
