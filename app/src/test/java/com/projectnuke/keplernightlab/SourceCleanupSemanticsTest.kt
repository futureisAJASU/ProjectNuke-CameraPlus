package com.projectnuke.keplernightlab

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Source deletion and explicit storage-action semantics (Phases 5, 6, 11, 16):
 *  - SOURCE deletion never requires a final result (Gallery row deleted, local final deleted,
 *    export failed, or never existed);
 *  - KEEP_SOURCE_ONLY on PACKED_YUV_V1 removes the converted PNG fusion input but keeps the
 *    durable .yuvpack authority; reprocess capability survives;
 *  - metadata describes ACTUAL post-deletion filesystem state; metadata failure is never
 *    reported as full success.
 */
@RunWith(RobolectricTestRunner::class)
class SourceCleanupSemanticsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun yuvRoot(): File {
        val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        return File(pictures, "KeplerYuvFusion").apply { mkdirs() }
    }

    private fun bufferedFrame(index: Int): BufferedYuvFrame {
        val width = 8
        val height = 6
        return BufferedYuvFrame(
            index = index,
            timestampNs = 1000L * index,
            width = width,
            height = height,
            y = ByteArray(10 * height) { it.toByte() },
            u = ByteArray(5 * height / 2) { (it * 3).toByte() },
            v = ByteArray(5 * height / 2) { (it * 7).toByte() },
            yRowStride = 10,
            yPixelStride = 1,
            uRowStride = 5,
            uPixelStride = 1,
            vRowStride = 5,
            vPixelStride = 1
        )
    }

    /** Packed job fixture: two .yuvpack canonical sources + converted PNGs + final output. */
    private fun newPackedJob(root: File, name: String): File {
        val job = File(root, name).apply { mkdirs() }
        val frames = JSONArray()
        for (index in 1..2) {
            val packedName = yuvFrameFileName(index, YuvPersistenceStrategy.PACKED_YUV_V1)
            PackedYuvFrameStore.pack(bufferedFrame(index), rotationDegrees = 0, outFile = File(job, packedName))
            frames.put(
                JSONObject()
                    .put("index", index)
                    .put("frameIndex", index)
                    .put("file", yuvFrameFileName(index, YuvPersistenceStrategy.PNG))
                    .put("filename", yuvFrameFileName(index, YuvPersistenceStrategy.PNG))
                    .put("packedSourceFilename", packedName)
                    .put("enabled", true)
            )
        }
        // Simulate the background converter: converted PNG fusion inputs exist.
        KeplerJobMetadata.write(
            job,
            JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("status", "COMPLETE")
                .put("currentPipelineStage", "COMPLETE")
                .put("recoveryState", "STABLE")
                .put(YuvPersistenceStrategy.JOB_KEY, YuvPersistenceStrategy.PACKED_YUV_V1.name)
                .put("finalNightFusionFile", "night_fusion_final.png")
                .put("frames", frames)
        )
        File(job, yuvFrameFileName(1, YuvPersistenceStrategy.PNG)).writeBytes(byteArrayOf(1, 2, 3))
        File(job, yuvFrameFileName(2, YuvPersistenceStrategy.PNG)).writeBytes(byteArrayOf(4, 5, 6))
        File(job, "night_fusion_final.png").writeBytes(ByteArray(256))
        return job
    }

    /** Phase 5: explicit source purge must succeed even when no final output exists at all. */
    @Test
    fun sourceDeletion_withoutFinalOutput_succeedsAndDisablesReprocess() {
        val root = yuvRoot()
        val job = File(root, "KPL_YUV_FUSION_SRC_NO_FINAL").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(
                job,
                JSONObject()
                    .put("jobType", "YUV_NIGHT_FUSION")
                    .put("recoveryState", "STABLE")
                    .put("frames", JSONArray().put(
                        JSONObject().put("index", 1).put("file", "frame_01_color.png").put("enabled", true))
                    )
            )
            File(job, "frame_01_color.png").writeBytes(byteArrayOf(9, 9))
            // No final file, no Gallery claim — exactly the previously-refused shape.

            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.SOURCE_FRAMES_ONLY)
            assertTrue("SOURCE deletion must not require a final result", result.isSuccess)
            val cleanup = result.getOrThrow()
            assertEquals(CleanupStatus.COMPLETE, cleanup.cleanupStatus)
            assertFalse(File(job, "frame_01_color.png").exists())
            assertTrue(job.exists())

            val metadata = KeplerJobMetadata.read(job)
            assertFalse(metadata.optBoolean("sourceFramesAvailable", true))
            assertFalse(metadata.optBoolean("canReprocess", true))
            assertEquals(KeplerStorageAction.DELETE_SOURCES.name, metadata.optString("storageAction"))
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    /**
     * Phase 11: KEEP_SOURCE_ONLY on a packed job deletes converted frame PNGs (derived fusion
     * inputs), keeps the immutable .yuvpack authority, and reprocess stays possible.
     */
    @Test
    fun packedSourceOnlyCleanup_removesConvertedPngKeepsYuvpack_reprocessStaysAvailable() {
        val root = yuvRoot()
        val job = newPackedJob(root, "KPL_YUV_FUSION_PACKED_KEEP")
        try {
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.SOURCE_ONLY)
            assertTrue(result.isSuccess)
            assertEquals(CleanupStatus.COMPLETE, result.getOrThrow().cleanupStatus)

            assertFalse("Converted PNG is a derived artifact", File(job, yuvFrameFileName(1, YuvPersistenceStrategy.PNG)).exists())
            assertFalse(File(job, yuvFrameFileName(2, YuvPersistenceStrategy.PNG)).exists())
            assertFalse("Local final is purged by KEEP_SOURCE_ONLY", File(job, "night_fusion_final.png").exists())
            assertTrue(".yuvpack canonical authority survives",
                File(job, yuvFrameFileName(1, YuvPersistenceStrategy.PACKED_YUV_V1)).exists())
            assertTrue(File(job, yuvFrameFileName(2, YuvPersistenceStrategy.PACKED_YUV_V1)).exists())

            val metadata = KeplerJobMetadata.read(job)
            assertTrue(metadata.optBoolean("sourceFramesAvailable"))
            assertTrue(metadata.optBoolean("canReprocess"))

            // Canonical resolver truth agrees with the post-cleanup disk state.
            val reloaded = KeplerJobMetadata.read(job)
            assertEquals(2, CanonicalFrameSources.countAvailable(job, reloaded, ReprocessJobKind.YUV_FUSION))

            // detectReprocessCapability sees the source-only packed job as reprocessable.
            val capability = detectReprocessCapability(context, job)
            assertTrue("packedSourceOnlyJob_detectReprocessCapabilityTrue", capability.canReprocess)
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    /** Phase 11A: regeneration rebuilds the derived fusion input from the verified packed source. */
    @Test
    fun packedSourceOnlyJob_reprocessRegeneratesFusionInputs_fromVerifiedPackedSource() {
        val dir = tmp.newFolder()
        val packedName = yuvFrameFileName(1, YuvPersistenceStrategy.PACKED_YUV_V1)
        PackedYuvFrameStore.pack(bufferedFrame(1), rotationDegrees = 90, outFile = File(dir, packedName))
        val manifest = JSONObject()
            .put(YuvPersistenceStrategy.JOB_KEY, YuvPersistenceStrategy.PACKED_YUV_V1.name)
            .put("frames", JSONArray().put(
                JSONObject()
                    .put("index", 1)
                    .put("frameIndex", 1)
                    .put("file", yuvFrameFileName(1, YuvPersistenceStrategy.PNG))
                    .put("filename", yuvFrameFileName(1, YuvPersistenceStrategy.PNG))
                    .put("packedSourceFilename", packedName)
            ))
        // Fusion input absent (purged by KEEP_SOURCE_ONLY).
        assertFalse(File(dir, yuvFrameFileName(1, YuvPersistenceStrategy.PNG)).exists())

        val result = PackedYuvBackgroundConverter.convertJob(dir, manifest)
        assertEquals(1, result.convertedFrames)
        val regenerated = File(dir, yuvFrameFileName(1, YuvPersistenceStrategy.PNG))
        assertTrue(regenerated.exists() && regenerated.length() > 0L)
        // Manifest keeps the packed authority while pointing fusion at the derived input.
        assertEquals(packedName, manifest.optJSONArray("frames")!!.optJSONObject(0)!!.optString("packedSourceFilename"))
        // The immutable packed source is byte-identical after conversion.
        val digestBefore = NoFollowFileSystem.digestVerified(File(dir, packedName)).sha256
        PackedYuvBackgroundConverter.convertJob(dir, JSONObject(manifest.toString()))
        assertEquals(digestBefore, NoFollowFileSystem.digestVerified(File(dir, packedName)).sha256)
    }

    /** Phase 11A: corrupted packed digests fail closed before any conversion. */
    @Test
    fun packedDigestCorrupt_conversionFailsClosed() {
        val dir = tmp.newFolder()
        val packedName = yuvFrameFileName(1, YuvPersistenceStrategy.PACKED_YUV_V1)
        val packedFile = File(dir, packedName)
        PackedYuvFrameStore.pack(bufferedFrame(1), rotationDegrees = 0, outFile = packedFile)
        val bytes = packedFile.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        packedFile.writeBytes(bytes)

        val manifest = JSONObject()
            .put(YuvPersistenceStrategy.JOB_KEY, YuvPersistenceStrategy.PACKED_YUV_V1.name)
            .put("frames", JSONArray().put(
                JSONObject().put("index", 1).put("frameIndex", 1).put("packedSourceFilename", packedName)
            ))
        assertTrue(PackedYuvBackgroundConverter.isSelected(manifest))
        var threw = false
        try {
            PackedYuvBackgroundConverter.convertJob(dir, manifest)
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("Digest corruption must fail closed", threw)
        assertFalse("No derived artifact may be written from a corrupt source",
            File(dir, yuvFrameFileName(1, YuvPersistenceStrategy.PNG)).exists())
    }

    /** Phase 11: the packed authority is immutable during a reprocess transaction. */
    @Test
    fun packedSourceIsImmutableDuringReprocessTransaction() {
        val dir = tmp.newFolder()
        val packedName = yuvFrameFileName(1, YuvPersistenceStrategy.PACKED_YUV_V1)
        PackedYuvFrameStore.pack(bufferedFrame(1), rotationDegrees = 0, outFile = File(dir, packedName))
        File(dir, yuvFrameFileName(1, YuvPersistenceStrategy.PNG)).writeBytes(byteArrayOf(1))
        val jobJson = JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put(YuvPersistenceStrategy.JOB_KEY, YuvPersistenceStrategy.PACKED_YUV_V1.name)
            .put("frames", JSONArray().put(
                JSONObject()
                    .put("index", 1)
                    .put("frameIndex", 1)
                    .put("file", yuvFrameFileName(1, YuvPersistenceStrategy.PNG))
                    .put("packedSourceFilename", packedName)
            ))
        KeplerJobMetadata.write(dir, jobJson)

        // The derived PNG may be rewritten by the worker; the packed authority may never be.
        assertTrue(isReprocessWorkerWritable(File(dir, yuvFrameFileName(1, YuvPersistenceStrategy.PNG)), ReprocessJobKind.YUV_FUSION, jobJson))
        assertFalse(isReprocessWorkerWritable(File(dir, packedName), ReprocessJobKind.YUV_FUSION, jobJson))

        val transaction = backupReprocessTransaction(
            dir,
            dir.listFiles()!!.filter { it.isFile && isReprocessWorkerWritable(it, ReprocessJobKind.YUV_FUSION, jobJson) },
            job = jobJson,
            jobKind = ReprocessJobKind.YUV_FUSION
        ).getOrThrow()
        val backedUpNames = transaction.manifest.backedUpPaths
        assertTrue("Derived fusion input is transaction-protected as mutable pre-existing state",
            backedUpNames.contains(yuvFrameFileName(1, YuvPersistenceStrategy.PNG)))
        assertFalse("The packed canonical source must never be backed up or mutated",
            backedUpNames.contains(packedName))
    }

    /** Phase 6: DERIVED_CACHE_ONLY keeps canonical originals + finals, deletes recomputable artifacts. */
    @Test
    fun derivedCacheCleanup_keepsSourcesAndFinals_deletesDerivedArtifacts() {
        val root = yuvRoot()
        val job = newPackedJob(root, "KPL_YUV_FUSION_DERIVED_CACHE")
        File(job, "merged_yuv_intermediate.bin").writeBytes(ByteArray(32))
        try {
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.DERIVED_CACHE_ONLY)
            assertTrue(result.isSuccess)
            assertEquals(CleanupStatus.COMPLETE, result.getOrThrow().cleanupStatus)
            assertFalse(File(job, "merged_yuv_intermediate.bin").exists())
            assertTrue(File(job, "night_fusion_final.png").exists())
            assertTrue(File(job, yuvFrameFileName(1, YuvPersistenceStrategy.PACKED_YUV_V1)).exists())
            // Derived fusion inputs are recomputable and are purged by this action too.
            assertFalse(File(job, yuvFrameFileName(1, YuvPersistenceStrategy.PNG)).exists())

            val metadata = KeplerJobMetadata.read(job)
            assertTrue(metadata.optBoolean("sourceFramesAvailable"))
            assertTrue(metadata.optBoolean("canReprocess"))
            assertEquals(KeplerStorageAction.DELETE_DERIVED_CACHE.name, metadata.optString("storageAction"))
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    /** Phase 16: a metadata write failure after payload deletion is reported FAILED, never success. */
    @Test
    fun cleanupMetadataFailure_reportsFailedTruthWithWarning() {
        val root = yuvRoot()
        val job = newPackedJob(root, "KPL_YUV_FUSION_META_FAIL")
        val prior = galleryCleanupMetadataFailureForTest
        try {
            galleryCleanupMetadataFailureForTest = IllegalStateException("metadata write refused")
            val result = cleanupKeplerGalleryJob(context, job, KeplerJobCleanupType.SOURCE_ONLY)
            assertTrue(result.isSuccess)
            val cleanup = result.getOrThrow()
            assertEquals(CleanupStatus.FAILED, cleanup.cleanupStatus)
            assertNotNull(cleanup.metadataWarning)
            // Batch outcome model refuses to collapse this into Complete UX.
            val outcome = deleteKeplerGalleryJobsBatch(context, emptyList())
            assertEquals(0, outcome.entries.size)
        } finally {
            galleryCleanupMetadataFailureForTest = prior
            root.parentFile?.deleteRecursively()
        }
    }
}
