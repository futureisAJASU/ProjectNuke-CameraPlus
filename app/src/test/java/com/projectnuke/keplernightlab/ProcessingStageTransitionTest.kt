package com.projectnuke.keplernightlab

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProcessingStageTransitionTest {
    @Test
    fun stageAndStatusArePublishedTogether() {
        val dir = Files.createTempDirectory("processing-stage").toFile()
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("currentPipelineStage", "CAPTURE_COMPLETE"))
            updateProcessingStage(dir, "PROCESSING", "SINGLE_FRAME_PROCESSING")
            val job = KeplerJobMetadata.read(dir)
            assertEquals("PROCESSING", job.getString("currentPipelineStage"))
            assertEquals("SINGLE_FRAME_PROCESSING", job.getString("processStatus"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun terminalTransitionCannotRegressToProcessing() {
        val dir = Files.createTempDirectory("processing-stage-terminal").toFile()
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("currentPipelineStage", "PIPELINE_COMPLETE"))
            var rejected = false
            try {
                updateProcessingStage(dir, "PROCESSING", "PROCESSING")
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue(rejected)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unknownTransitionFailsClosed() {
        val dir = Files.createTempDirectory("processing-stage-unknown").toFile()
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("currentPipelineStage", "PROCESSING"))
            var rejected = false
            try {
                updateProcessingStage(dir, "MADE_UP_STAGE", "STATUS")
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue(rejected)
        } finally {
            dir.deleteRecursively()
        }
    }
}
