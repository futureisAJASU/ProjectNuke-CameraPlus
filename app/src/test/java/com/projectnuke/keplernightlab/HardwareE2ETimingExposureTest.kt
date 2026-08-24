package com.projectnuke.keplernightlab

import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase-A corrective audit, Phase 4: the physical E2E report exposes the newly
 * persisted nested captureTiming / backgroundStageTimings evidence.  Every new
 * field has a NON-DEFAULT round-trip test, and parsing is a PURE snapshot
 * operation - fromJson() never re-queries mutable filesystem state.
 */
@RunWith(RobolectricTestRunner::class)
class HardwareE2ETimingExposureTest {

    private val root = createTempDirectory("e2e-timing").toFile()

    @After
    fun cleanup() {
        BackgroundPipelineEventHub.resetForTest()
        BackgroundProcessingCoordinator.resetForTest()
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Pure derivation from persisted captureTiming instants
    // ------------------------------------------------------------------

    private fun captureTimingJson(): JSONObject {
        fun frame(index: Int, workerStart: Long, conversion: Long, fsync: Long, encodeDone: Long, written: Long, verified: Long) =
            JSONObject()
                .put("frameIndex", index)
                .put("workerStartedAt", workerStart)
                .put("conversionCompletedAt", conversion)
                .put("fsyncFinishedAt", fsync)
                .put("encodeFinishedAt", encodeDone)
                .put("writeFinishedAt", written)
                .put("verifiedAt", verified)
        return JSONObject()
            .put("requestedFrames", 2)
            .put("cameraAcquisitionMs", 120L)
            .put("persistenceDrainMs", 340L)
            .put("handoffSettlementMs", 56L)
            .put("captureStageTotalMs", 516L)
            .put(
                "frames",
                JSONArray()
                    .put(frame(0, 1_000_000_000L, 1_050_000_000L, 1_200_000_000L, 1_400_000_000L, 1_410_000_000L, 1_450_000_000L))
                    .put(frame(1, 2_000_000_000L, 2_100_000_000L, 2_250_000_000L, 2_600_000_000L, 2_620_000_000L, 2_660_000_000L))
            )
    }

    @Test
    fun captureTimingDerivation_computesRealSegmentsFromInstants() {
        val timing = HardwareE2ECaptureTiming.fromCaptureTimingJson(captureTimingJson())
        assertEquals(2, timing.requestedFrames)
        assertEquals(120L, timing.cameraAcquisitionMs)
        assertEquals(340L, timing.persistenceDrainMs)
        assertEquals(56L, timing.handoffSettlementMs)
        assertEquals(516L, timing.captureStageTotalMs)

        val f0 = timing.frames.first { it.frameIndex == 0 }
        // conversion = 1050-1000; encode span = 1400-1050; fsync segment = 1200-1050;
        // verify = 1450-1410.
        assertEquals(50L, f0.conversionMs)
        assertEquals(350L, f0.encodeMs)
        assertEquals(150L, f0.fsyncMs)
        assertEquals(40L, f0.verifyMs)

        val f1 = timing.frames.first { it.frameIndex == 1 }
        assertEquals(100L, f1.conversionMs)
        assertEquals(500L, f1.encodeMs)
        assertEquals(150L, f1.fsyncMs)
        assertEquals(40L, f1.verifyMs)

        assertEquals(850L, timing.aggregateEncodeMs)
        assertEquals(300L, timing.aggregateFsyncMs)
        assertEquals(80L, timing.aggregateVerifyMs)
        assertEquals(500L, timing.maxFrameEncodeMs)
    }

    @Test
    fun captureTimingDerivation_unsetEndpointsStayNull() {
        val json = JSONObject()
            .put("requestedFrames", 1)
            .put("frames", JSONArray().put(JSONObject().put("frameIndex", 0)))
        val timing = HardwareE2ECaptureTiming.fromCaptureTimingJson(json)
        val frame = timing.frames.single()
        assertNull(frame.conversionMs)
        assertNull(frame.encodeMs)
        assertNull(frame.fsyncMs)
        assertNull(frame.verifyMs)
        assertEquals(0L, timing.aggregateEncodeMs)
        assertEquals(0L, timing.maxFrameEncodeMs)
    }

    @Test
    fun captureTiming_roundTripsEveryFieldNonDefault() {
        val original = HardwareE2ECaptureTiming(
            requestedFrames = 3,
            cameraAcquisitionMs = 111L,
            persistenceDrainMs = 222L,
            handoffSettlementMs = 33L,
            captureStageTotalMs = 366L,
            aggregateEncodeMs = 900L,
            aggregateFsyncMs = 210L,
            aggregateVerifyMs = 99L,
            maxFrameEncodeMs = 400L,
            frames = listOf(
                HardwareE2EFrameTiming(0, conversionMs = 10L, encodeMs = 300L, fsyncMs = 70L, verifyMs = 30L),
                HardwareE2EFrameTiming(1, conversionMs = null, encodeMs = 400L, fsyncMs = null, verifyMs = 44L),
                HardwareE2EFrameTiming(2, conversionMs = 12L, encodeMs = 200L, fsyncMs = 70L, verifyMs = 25L)
            )
        )
        val decoded = HardwareE2ECaptureTiming.fromJson(original.toJson())
        assertEquals(original, decoded)
    }

    @Test
    fun jobSummary_roundTripsCaptureTimingAndBackgroundStageTimings() {
        // Built through the REAL summary JSON surface: a full report whose
        // finalJob carries both nested payloads, encoded then decoded purely.
        val summary = HardwareE2EJobSummary(
            jobDirectory = "/data/job",
            readable = true,
            jobType = "YUV_NIGHT_FUSION",
            captureMode = "MULTI_FRAME",
            createdAt = 42L,
            status = "PIPELINE_COMPLETE",
            processStatus = "COMPLETE",
            exportStatus = "COMPLETE",
            exportVerified = true,
            requiredOutputFilePresent = true,
            requestedFrames = 2,
            attemptedFrames = 2,
            savedFrames = 2,
            receivedImages = 2,
            completedResults = 2,
            failedCaptures = 0,
            partialCapture = false,
            cleanupType = "NORMAL",
            cameraId = "0",
            physicalCameraId = "0",
            requestedPhysicalCameraId = "0",
            dngSidecarSaved = null,
            dngSidecarSkipReason = "",
            dngSidecarStatuses = emptyList(),
            frameManifestCount = 2,
            rawMetadata = emptyMap(),
            selectedRoute = "MAIN_1X",
            actualRoute = "MAIN_1X",
            processingTiming = mapOf("processingDurationMs" to 1234L),
            memoryFields = emptyMap(),
            activeOperationId = "",
            activeOperationKind = "",
            activeRuntimeSessionId = "",
            terminalOperationId = "",
            liveOperationRegistered = false,
            fileNames = listOf("final.jpg"),
            error = null,
            captureTiming = HardwareE2ECaptureTiming(
                requestedFrames = 2,
                cameraAcquisitionMs = 77L,
                persistenceDrainMs = 88L,
                handoffSettlementMs = 9L,
                captureStageTotalMs = 174L,
                aggregateEncodeMs = 600L,
                aggregateFsyncMs = 140L,
                aggregateVerifyMs = 66L,
                maxFrameEncodeMs = 310L,
                frames = listOf(HardwareE2EFrameTiming(0, conversionMs = 40L, encodeMs = 310L, fsyncMs = 70L, verifyMs = 33L))
            ),
            backgroundStageTimings = mapOf("processingMs" to 4321L, "exportMs" to 890L)
        )
        val report = minimalReport(finalJob = summary)
        val decoded = HardwareE2ERunReport.fromJson(report.toJson())

        val decodedSummary = requireNotNull(decoded.finalJob)
        val timing = requireNotNull(decodedSummary.captureTiming)
        assertEquals(summary.captureTiming, timing)
        assertEquals(mapOf("processingMs" to 4321L, "exportMs" to 890L), decodedSummary.backgroundStageTimings)
    }

    @Test
    fun runReport_roundTripsFlattenedPhysicalTimingsNonDefault() {
        val report = minimalReport(finalJob = null)
            .copy(
                resultJobDirectoryPath = "/data/sr-output",
                cameraAcquisitionMs = 101L,
                persistenceDrainMs = 202L,
                handoffSettlementMs = 30L,
                captureStageTotalMs = 333L,
                backgroundProcessingMs = 4444L,
                backgroundExportMs = 555L
            )
        val decoded = HardwareE2ERunReport.fromJson(report.toJson())
        assertEquals("/data/sr-output", decoded.resultJobDirectoryPath)
        assertEquals(101L, decoded.cameraAcquisitionMs)
        assertEquals(202L, decoded.persistenceDrainMs)
        assertEquals(30L, decoded.handoffSettlementMs)
        assertEquals(333L, decoded.captureStageTotalMs)
        assertEquals(4444L, decoded.backgroundProcessingMs)
        assertEquals(555L, decoded.backgroundExportMs)
    }

    @Test
    fun finalizedRun_promotesJobTimingEvidenceToFlattenedFields() {
        val recorder = HardwareE2ERunRecorder.forTest(root, environment()) {
            root.listFiles().orEmpty().filter { it.isDirectory }
        }
        val runId = recorder.start(scenario())!!
        val jobDir = File(root, "timing-job").apply { mkdirs() }
        File(jobDir, "final.jpg").writeText("out")
        File(jobDir, JOB_JSON_FILE_NAME).writeText(
            JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("captureMode", CaptureMode.MULTI_FRAME.name)
                .put("createdAt", System.currentTimeMillis())
                .put("status", "PIPELINE_COMPLETE")
                .put("processStatus", "PIPELINE_COMPLETE")
                .put("exportStatus", "COMPLETE")
                .put("exportVerified", true)
                .put("requestedFrames", 2)
                .put("savedFrames", 2)
                .put("finalFile", "final.jpg")
                .put("captureTiming", captureTimingJson())
                .put(
                    "backgroundStageTimings",
                    JSONObject().put("processingMs", 1234L).put("exportMs", 567L)
                )
                .toString()
        )
        recorder.recordEvent(
            CameraPipelineEvent.CaptureStageComplete(
                generation = 1L,
                counts = CameraPipelineProgressCounts(),
                jobDirectoryPath = jobDir.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )
        recorder.recordBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = jobDir,
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = CameraPipelineEvent.Terminal(
                    generation = 0L,
                    kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                    requiredOutputCommitted = true,
                    publicExportCommitted = true,
                    verified = true,
                    message = "done",
                    jobDirectoryPath = jobDir.absolutePath
                )
            )
        )
        assertTrue(recorder.awaitIdle())
        var report = recorder.snapshotsForTest().single { it.runId == runId }
        var attempts = 0
        while (report.cameraAcquisitionMs == null && attempts < 200) {
            Thread.sleep(25)
            report = recorder.snapshotsForTest().single { it.runId == runId }
            attempts++
        }
        // Flattened physical timings promoted from the finalized job's durable
        // evidence:
        assertEquals(120L, report.cameraAcquisitionMs)
        assertEquals(340L, report.persistenceDrainMs)
        assertEquals(56L, report.handoffSettlementMs)
        assertEquals(516L, report.captureStageTotalMs)
        assertEquals(1234L, report.backgroundProcessingMs)
        assertEquals(567L, report.backgroundExportMs)
        assertNotNull(report.finalJob?.captureTiming)
        assertEquals(850L, report.finalJob?.captureTiming?.aggregateEncodeMs)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun minimalReport(finalJob: HardwareE2EJobSummary?): HardwareE2ERunReport =
        HardwareE2ERunReport(
            schemaVersion = 1,
            runId = "run-timing",
            runtimeSessionId = "runtime",
            processStartTimestamp = 1L,
            runStartWallClockTimestamp = 2L,
            runEndWallClockTimestamp = 3L,
            scenario = scenario(),
            appPackage = "pkg",
            appVersion = "1.0",
            debugBuild = true,
            androidSdk = 36,
            manufacturer = "m",
            deviceModel = "d",
            buildFingerprint = "f",
            eventHistory = emptyList(),
            progressCounts = emptyMap(),
            terminalEvent = null,
            terminalFlags = emptyMap(),
            latestJobDirectory = "/data/job",
            finalJob = finalJob,
            status = HardwareE2EClassification.PASS
        )

    private fun scenario() = HardwareE2ERunScenario(
        requestedTestScenario = "phase4-timing",
        selectedPipelineMode = PipelineMode.YUV_NIGHT_FUSION.name,
        captureMode = CaptureMode.MULTI_FRAME.name,
        requestedLensSlot = LensSlot.MAIN_1X.name,
        requestedResolution = CaptureResolutionMode.MP12.name,
        frameCountPolicy = FrameCountMode.MANUAL.name,
        effectiveRequestedFrames = 2,
        requestedZoom = 1.0f,
        requestedOutputFormat = FinalOutputFormat.JPEG.name
    )

    private fun environment() = HardwareE2EEnvironment(
        runtimeSessionId = "runtime-phase4",
        processStartTimestamp = 10L,
        appPackage = "test.package",
        appVersion = "1.0-test",
        debugBuild = true,
        androidSdk = 36,
        manufacturer = "test",
        deviceModel = "model",
        buildFingerprint = "fingerprint"
    )
}
