package com.projectnuke.keplernightlab

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeplerRestartArtifactRecoveryTest {
    private fun job(dir: java.io.File, frameName: String = "frame_01.raw16"): JSONObject {
        val value = JSONObject().put("file", frameName).put("raw16File", frameName)
        return JSONObject().put("jobType", "RAW").put("frames", JSONArray().put(value))
    }

    @Test
    fun oneValidMetadataTempIsPromotedWhenJobJsonIsMissing() {
        val dir = Files.createTempDirectory("metadata-temp-promote-").toFile()
        try {
            java.io.File(dir, ".job.json.1.tmp").writeText(job(dir).toString())
            val result = reconcileJobMetadataWriteTemps(dir)
            assertEquals(KeplerMetadataTempClassification.PROMOTED_SINGLE_VALID, result.classification)
            assertTrue(java.io.File(dir, "job.json").isFile)
            assertFalse(java.io.File(dir, ".job.json.1.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun multipleMetadataTempsArePreservedAsAmbiguous() {
        val dir = Files.createTempDirectory("metadata-temp-ambiguous-").toFile()
        try {
            java.io.File(dir, ".job.json.1.tmp").writeText(job(dir).toString())
            java.io.File(dir, ".job.json.2.tmp").writeText(job(dir).put("status", "PROCESSING").toString())
            val result = reconcileJobMetadataWriteTemps(dir)
            assertEquals(KeplerMetadataTempClassification.AMBIGUOUS, result.classification)
            assertTrue(java.io.File(dir, ".job.json.1.tmp").exists())
            assertTrue(java.io.File(dir, ".job.json.2.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parseableButUnsupportedMetadataTempIsPreserved() {
        val dir = Files.createTempDirectory("metadata-temp-invalid-").toFile()
        try {
            java.io.File(dir, ".job.json.1.tmp").writeText(JSONObject()
                .put("schemaVersion", 99)
                .put("jobType", "UNKNOWN")
                .put("status", "PROCESSING")
                .toString())
            val result = reconcileJobMetadataWriteTemps(dir)
            assertEquals(KeplerMetadataTempClassification.AMBIGUOUS, result.classification)
            assertTrue(java.io.File(dir, ".job.json.1.tmp").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun ordinaryMetadataTempCandidateReadFailureRemainsAmbiguousAndPreserved() {
        val dir = Files.createTempDirectory("metadata-temp-read-failure-").toFile()
        val temp = java.io.File(dir, ".job.json.1.tmp").apply { writeText(job(dir).toString()) }
        try {
            metadataTempCandidateReadFailureForTest = IOException("ordinary candidate read")
            val result = reconcileJobMetadataWriteTemps(dir)
            assertEquals(KeplerMetadataTempClassification.AMBIGUOUS, result.classification)
            assertTrue(temp.exists())
        } finally {
            metadataTempCandidateReadFailureForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun fatalMetadataTempCandidateReadErrorPropagatesAndPreservesEvidence() {
        val dir = Files.createTempDirectory("metadata-temp-read-fatal-").toFile()
        val temp = java.io.File(dir, ".job.json.1.tmp").apply { writeText(job(dir).toString()) }
        try {
            metadataTempCandidateReadFailureForTest = AssertionError("fatal candidate read")
            assertThrows(AssertionError::class.java) { reconcileJobMetadataWriteTemps(dir) }
            assertTrue(temp.exists())
        } finally {
            metadataTempCandidateReadFailureForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun ordinaryMetadataTempPromotionFailureRemainsAmbiguousAndPreserved() {
        val dir = Files.createTempDirectory("metadata-temp-promote-failure-").toFile()
        val temp = java.io.File(dir, ".job.json.1.tmp").apply { writeText(job(dir).toString()) }
        try {
            metadataTempPromotionFailureForTest = IOException("ordinary promotion failure")
            val result = reconcileJobMetadataWriteTemps(dir)
            assertEquals(KeplerMetadataTempClassification.AMBIGUOUS, result.classification)
            assertTrue(temp.exists())
            assertFalse(java.io.File(dir, "job.json").exists())
        } finally {
            metadataTempPromotionFailureForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun fatalMetadataTempPromotionErrorPropagatesAndPreservesEvidence() {
        val dir = Files.createTempDirectory("metadata-temp-promote-fatal-").toFile()
        val temp = java.io.File(dir, ".job.json.1.tmp").apply { writeText(job(dir).toString()) }
        try {
            metadataTempPromotionFailureForTest = AssertionError("fatal promotion")
            assertThrows(AssertionError::class.java) { reconcileJobMetadataWriteTemps(dir) }
            assertTrue(temp.exists())
            assertFalse(java.io.File(dir, "job.json").exists())
        } finally {
            metadataTempPromotionFailureForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun oldActiveCaptureDeletesOnlyManifestOwnedTemp() {
        val dir = Files.createTempDirectory("capture-temp-recovery-").toFile()
        try {
            java.io.File(dir, ".frame_01.raw16.9.tmp").writeText("candidate")
            java.io.File(dir, ".other.raw16.9.tmp").writeText("preserve")
            val result = recoverCaptureOwnedTemps(dir, job(dir), oldActiveOperation = true)
            assertEquals(listOf(".frame_01.raw16.9.tmp"), result.deleted)
            assertTrue(java.io.File(dir, ".other.raw16.9.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cacheCleanupMatchesOnlyOwnedHeifPrefixAndSuffix() {
        val dir = Files.createTempDirectory("export-cache-").toFile()
        try {
            java.io.File(dir, "kepler_export_old.heic").writeText("old")
            java.io.File(dir, "other.heic").writeText("keep")
            java.io.File(dir, "kepler_export_old.tmp").writeText("keep")
            assertEquals(listOf("kepler_export_old.heic"), cleanStaleKeplerExportCacheFiles(dir))
            assertTrue(java.io.File(dir, "other.heic").exists())
            assertTrue(java.io.File(dir, "kepler_export_old.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ordinaryCacheDeleteExceptionRemainsACleanupFailure() {
        val dir = Files.createTempDirectory("export-cache-delete-failure-").toFile()
        val file = java.io.File(dir, "kepler_export_old.heic").apply { writeText("old") }
        try {
            cacheCleanupDeleteFailureForTest = IOException("ordinary cache delete")
            val result = cleanStaleKeplerExportCacheFilesDetailed(dir)
            assertTrue(result.failures.single().contains(file.name))
            assertTrue(file.exists())
        } finally {
            cacheCleanupDeleteFailureForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun fatalCacheDeleteErrorPropagatesAndPreservesEvidence() {
        val dir = Files.createTempDirectory("export-cache-delete-fatal-").toFile()
        val file = java.io.File(dir, "kepler_export_old.heic").apply { writeText("old") }
        try {
            cacheCleanupDeleteFailureForTest = AssertionError("fatal cache delete")
            assertThrows(AssertionError::class.java) { cleanStaleKeplerExportCacheFilesDetailed(dir) }
            assertTrue(file.exists())
        } finally {
            cacheCleanupDeleteFailureForTest = null
            dir.deleteRecursively()
        }
    }
}
