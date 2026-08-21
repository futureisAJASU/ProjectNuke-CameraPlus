package com.projectnuke.keplernightlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HardwareE2ETest {
    private fun scenario(
        pipeline: String = PipelineMode.YUV_NIGHT_FUSION.name,
        name: String = "test"
    ) = HardwareE2ERunScenario(
        requestedTestScenario = name,
        selectedPipelineMode = pipeline,
        captureMode = CaptureMode.MULTI_FRAME.name,
        requestedLensSlot = LensSlot.MAIN_1X.name,
        requestedResolution = CaptureResolutionMode.MP12.name,
        frameCountPolicy = FrameCountMode.MANUAL.name,
        effectiveRequestedFrames = 4,
        requestedZoom = 1.0f,
        requestedOutputFormat = FinalOutputFormat.JPEG.name
    )

    private fun environment(debugBuild: Boolean = true) = HardwareE2EEnvironment(
        runtimeSessionId = "runtime-test",
        processStartTimestamp = 10L,
        appPackage = "test.package",
        appVersion = "1.0-test",
        debugBuild = debugBuild,
        androidSdk = 36,
        manufacturer = "test",
        deviceModel = "model",
        buildFingerprint = "fingerprint"
    )

    @Test
    fun reportCodec_roundTripsOrderedEventsAndScenario() {
        val recorder = HardwareE2ERunRecorder.forTest(createTempDir(), environment())
        recorder.start(scenario())
        recorder.recordEvent(CameraPipelineEvent.Started(1L, counts = CameraPipelineProgressCounts(requestedFrames = 4)))
        recorder.recordEvent(
            CameraPipelineEvent.Terminal(
                generation = 1L,
                kind = CameraPipelineEvent.Terminal.Kind.FAILED,
                counts = CameraPipelineProgressCounts(requestedFrames = 4, savedFrames = 2),
                message = "test failure"
            )
        )
        assertTrue(recorder.awaitIdle())
        val report = recorder.snapshot()!!
        val decoded = HardwareE2EReportCodec.decode(HardwareE2EReportCodec.encode(report))

        assertEquals(report.runId, decoded.runId)
        assertEquals("test", decoded.scenario.requestedTestScenario)
        assertEquals(
            listOf("RUN_STARTED", "CAPTURE_STARTED", "TERMINAL_FAILED", "OWNER_SETTLED"),
            decoded.eventHistory.map { it.checkpoint }
        )
        assertEquals(HardwareE2EClassification.FAIL, decoded.status)
        assertEquals(HardwareE2EClassificationReason.FAIL_PIPELINE_TERMINAL, decoded.classificationReason)
        recorder.close()
    }

    @Test
    fun staleCompletedLatestReport_doesNotSatisfyNewHardwareRun() {
        val root = createTempDir()
        val first = recorderFor(root)
        val firstRunId = first.start(scenario())!!
        val firstJob = writeJob(root, "first", System.currentTimeMillis(), "YUV_NIGHT_FUSION")
        first.recordEvent(successEvent())
        assertTrue(first.awaitIdle())
        first.close()

        val previousRunId = firstRunId
        val invocationStart = System.currentTimeMillis()
        val second = recorderFor(root)
        val secondRunId = second.start(scenario())!!
        assertTrue(secondRunId != previousRunId)
        assertTrue(second.awaitIdle())

        val observed = HardwareE2EReportStore.findLatestAfter(
            root,
            previousRunId,
            invocationStart,
            "test",
            PipelineMode.YUV_NIGHT_FUSION.name
        )
        assertEquals(secondRunId, observed?.runId)
        assertNull(HardwareE2EReportStore.read(root, secondRunId)?.terminalEvent)
        assertEquals(firstJob.absolutePath, HardwareE2EReportStore.read(root, firstRunId)?.latestJobDirectory)
        second.close()
    }

    @Test
    fun exactRunLookup_ignoresLatestPointer() {
        val root = createTempDir()
        val recorder = recorderFor(root)
        val runId = recorder.start(scenario())!!
        assertTrue(recorder.awaitIdle())
        val report = HardwareE2EReportStore.read(root, runId)
        assertEquals(runId, report?.runId)
        assertEquals(runId, HardwareE2EReportStore.readReports(root).single().runId)
        recorder.close()
    }

    @Test
    fun sequentialRuns_trackDifferentRunIds() {
        val root = createTempDir()
        val recorder = recorderFor(root)
        val first = recorder.start(scenario(name = "yuv"))!!
        recorder.recordSkipped("test sequence boundary")
        assertTrue(recorder.awaitIdle())
        val second = recorder.start(scenario(pipeline = PipelineMode.RAW_NIGHT_FUSION.name, name = "raw"))!!
        recorder.recordSkipped("test sequence boundary")
        assertTrue(recorder.awaitIdle())
        assertTrue(first != second)
        assertEquals("yuv", HardwareE2EReportStore.read(root, first)?.scenario?.requestedTestScenario)
        assertEquals("raw", HardwareE2EReportStore.read(root, second)?.scenario?.requestedTestScenario)
        recorder.close()
    }

    @Test
    fun busyRejectedCapture_doesNotReplaceActiveHardwareRun() {
        val recorder = HardwareE2ERunRecorder.forTest(createTempDir(), environment())
        val first = recorder.start(scenario(name = "active"))!!
        val rejected = recorder.start(scenario(name = "busy-rejected"))
        assertNull(rejected)
        assertEquals(first, recorder.currentRunId())
        assertEquals("active", recorder.snapshot()?.scenario?.requestedTestScenario)
        recorder.close()
    }

    @Test
    fun unrelatedNewerJob_isIgnoredForExactCorrelation() {
        val root = createTempDir()
        val recorder = recorderFor(root)
        recorder.start(scenario())
        val expected = writeJob(root, "expected", System.currentTimeMillis(), "YUV_NIGHT_FUSION")
        writeJob(root, "unrelated-newer", System.currentTimeMillis() + 10, "RAW_NIGHT_FUSION")
        recorder.recordEvent(successEvent())
        assertTrue(recorder.awaitIdle())
        val report = recorder.snapshot()!!
        assertEquals(HardwareE2EClassification.PASS, report.status)
        assertEquals(HardwareE2EJobCorrelation.EXACT, report.jobCorrelation)
        assertEquals(expected.absolutePath, report.latestJobDirectory)
        recorder.close()
    }

    @Test
    fun preExistingJob_isNotMistakenForNewCapture() {
        val root = createTempDir()
        writeJob(root, "pre-existing", System.currentTimeMillis(), "YUV_NIGHT_FUSION")
        val recorder = recorderFor(root)
        recorder.start(scenario())
        recorder.recordEvent(successEvent())
        assertTrue(recorder.awaitIdle())
        assertEquals(HardwareE2EClassification.INCOMPLETE, recorder.snapshot()?.status)
        assertEquals(HardwareE2EJobCorrelation.NONE, recorder.snapshot()?.jobCorrelation)
        recorder.close()
    }

    @Test
    fun ambiguousJobCorrelation_isIncomplete() {
        val root = createTempDir()
        val recorder = recorderFor(root)
        recorder.start(scenario())
        writeJob(root, "candidate-a", System.currentTimeMillis(), "YUV_NIGHT_FUSION")
        writeJob(root, "candidate-b", System.currentTimeMillis() + 1, "YUV_NIGHT_FUSION")
        recorder.recordEvent(successEvent())
        assertTrue(recorder.awaitIdle())
        assertEquals(HardwareE2EClassification.INCOMPLETE, recorder.snapshot()?.status)
        assertEquals(HardwareE2EJobCorrelation.AMBIGUOUS, recorder.snapshot()?.jobCorrelation)
        recorder.close()
    }

    @Test
    fun terminalFailure_classifiesFail() {
        val recorder = HardwareE2ERunRecorder.forTest(createTempDir(), environment())
        recorder.start(scenario())
        recorder.recordEvent(
            CameraPipelineEvent.Terminal(
                generation = 1L,
                kind = CameraPipelineEvent.Terminal.Kind.FAILED,
                message = "camera failed"
            )
        )
        assertTrue(recorder.awaitIdle())
        assertEquals(HardwareE2EClassification.FAIL, recorder.snapshot()?.status)
        assertEquals(HardwareE2EClassificationReason.FAIL_PIPELINE_TERMINAL, recorder.snapshot()?.classificationReason)
        recorder.close()
    }

    @Test
    fun completedButOutputMissing_classifiesFail() {
        val root = createTempDir()
        val recorder = recorderFor(root)
        recorder.start(scenario())
        writeJob(root, "missing-output", System.currentTimeMillis(), "YUV_NIGHT_FUSION", output = false)
        recorder.recordEvent(successEvent())
        assertTrue(recorder.awaitIdle())
        assertEquals(HardwareE2EClassification.FAIL, recorder.snapshot()?.status)
        assertEquals(HardwareE2EClassificationReason.FAIL_OUTPUT_NOT_COMMITTED, recorder.snapshot()?.classificationReason)
        recorder.close()
    }

    @Test
    fun completedWithLiveOperation_classifiesFail() {
        val root = createTempDir()
        val recorder = recorderFor(root)
        recorder.start(scenario())
        val job = writeJob(root, "live", System.currentTimeMillis(), "YUV_NIGHT_FUSION", status = "PROCESSING")
        KeplerJobMetadata.beginActiveOperation(job, kind = KeplerActiveOperationKind.PROCESSING_YUV)
        recorder.recordEvent(successEvent())
        assertTrue(recorder.awaitIdle())
        assertEquals(HardwareE2EClassification.FAIL, recorder.snapshot()?.status)
        assertEquals(HardwareE2EClassificationReason.FAIL_LIVE_OPERATION_REMAINS, recorder.snapshot()?.classificationReason)
        KeplerJobMetadata.findOperationLease(job)?.release()
        recorder.close()
    }

    @Test
    fun validCompletedRun_classifiesPass() {
        val root = createTempDir()
        val recorder = recorderFor(root)
        recorder.start(scenario())
        val job = writeJob(root, "valid", System.currentTimeMillis(), "YUV_NIGHT_FUSION")
        recorder.recordEvent(successEvent())
        assertTrue(recorder.awaitIdle())
        val report = recorder.snapshot()!!
        assertEquals(HardwareE2EClassification.PASS, report.status)
        assertEquals(HardwareE2EClassificationReason.PASS_SUCCESS, report.classificationReason)
        assertEquals(job.absolutePath, report.latestJobDirectory)
        assertTrue(report.finalJob?.requiredOutputFilePresent == true)
        recorder.close()
    }

    @Test
    fun disabledRecorder_isNoOp() {
        val root = createTempDir()
        val recorder = HardwareE2ERunRecorder.forTest(root, environment(debugBuild = false))
        assertNull(recorder.start(scenario()))
        recorder.recordEvent(successEvent())
        assertTrue(recorder.awaitIdle())
        assertNull(recorder.snapshot())
        assertTrue(root.listFiles().orEmpty().isEmpty())
        recorder.close()
    }

    @Test
    fun reportRetention_keepsLatestPointerValid() {
        val root = createTempDir()
        val recorder = recorderFor(root)
        val runIds = (0 until 15).map {
            val runId = recorder.start(scenario(name = "run-$it"))!!
            recorder.recordSkipped("unsupported")
            runId
        }
        assertTrue(recorder.awaitIdle())
        val files = root.listFiles().orEmpty().filter { it.extension == "json" && it.name != "latest.json" }
        assertTrue(files.size <= 12)
        val latest = root.resolve("latest.json")
        assertTrue(latest.isFile)
        val latestReport = HardwareE2EReportCodec.decode(latest.readText())
        assertEquals(runIds.last(), latestReport.runId)
        assertEquals(latestReport.runId, HardwareE2EReportStore.read(root, latestReport.runId)?.runId)
        recorder.close()
    }

    @Test
    fun reportWithoutTerminalRemainsIncomplete() {
        val recorder = HardwareE2ERunRecorder.forTest(createTempDir(), environment())
        recorder.start(scenario())
        assertTrue(recorder.awaitIdle())
        assertEquals(HardwareE2EClassification.INCOMPLETE, recorder.snapshot()?.status)
        assertTrue(recorder.snapshot()?.terminalEvent == null)
        recorder.close()
    }

    @Test
    fun yuvSummary_doesNotInventMissingAccountingAsZero() {
        val root = createTempDir()
        val recorder = recorderFor(root)
        recorder.start(scenario())
        val yuvJob = File(root, "yuv-missing-accounting").apply { mkdirs() }
        File(yuvJob, JOB_JSON_FILE_NAME).writeText(
            JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("captureMode", CaptureMode.MULTI_FRAME.name)
                .put("createdAt", System.currentTimeMillis())
                .put("status", "CAPTURE_TIMEOUT")
                .put("processStatus", "CAPTURE_TIMEOUT")
                .put("exportStatus", "PENDING")
                .put("requestedFrames", 4)
                .put("savedFrames", 0)
                .toString()
        )
        recorder.recordEvent(
            CameraPipelineEvent.Terminal(
                generation = 1L,
                kind = CameraPipelineEvent.Terminal.Kind.FAILED,
                counts = CameraPipelineProgressCounts(requestedFrames = 4)
            )
        )
        assertTrue(recorder.awaitIdle())
        val report = recorder.snapshot()!!
        val summary = report.finalJob!!
        assertEquals(4, summary.requestedFrames)
        assertEquals(0, summary.savedFrames)
        assertNull(summary.attemptedFrames)
        assertNull(summary.receivedImages)
        assertNull(summary.completedResults)
        assertNull(summary.yuvReceivedFrames)
        assertNull(summary.yuvPersistedFrames)
        recorder.close()
    }

    @Test
    fun yuvSummary_usesAuthoritativeTerminalAccountingWhenAvailable() {
        val root = createTempDir()
        val recorder = recorderFor(root)
        recorder.start(scenario())
        val yuvJob = File(root, "yuv-with-accounting").apply { mkdirs() }
        File(yuvJob, JOB_JSON_FILE_NAME).writeText(
            JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("captureMode", CaptureMode.MULTI_FRAME.name)
                .put("createdAt", System.currentTimeMillis())
                .put("status", "CAPTURE_TIMEOUT")
                .put("processStatus", "CAPTURE_TIMEOUT")
                .put("exportStatus", "PENDING")
                .put("requestedFrames", 4)
                .put("savedFrames", 0)
                .put("yuvReceivedFrames", 4)
                .put("yuvPersistedFrames", 0)
                .put("yuvFailedFrames", 4)
                .put("yuvDroppedFrames", 0)
                .put("yuvCompletedResults", 0)
                .put("yuvFirstWorkerFailureClass", "SyncFailedException")
                .put("yuvFirstWorkerFailureMessage", "sync failed")
                .put("yuvFirstWorkerFailureFrameIndex", 1)
                .toString()
        )
        recorder.recordEvent(
            CameraPipelineEvent.Terminal(
                generation = 1L,
                kind = CameraPipelineEvent.Terminal.Kind.FAILED,
                counts = CameraPipelineProgressCounts(requestedFrames = 4)
            )
        )
        assertTrue(recorder.awaitIdle())
        val report = recorder.snapshot()!!
        val summary = report.finalJob!!
        assertEquals(4, summary.yuvReceivedFrames)
        assertEquals(0, summary.yuvPersistedFrames)
        assertEquals(4, summary.yuvFailedFrames)
        assertEquals(0, summary.yuvDroppedFrames)
        assertEquals(0, summary.yuvCompletedResults)
        assertEquals("SyncFailedException", summary.yuvFirstWorkerFailureClass)
        assertEquals("sync failed", summary.yuvFirstWorkerFailureMessage)
        assertEquals(1, summary.yuvFirstWorkerFailureFrameIndex)
        assertNull(summary.attemptedFrames)
        assertNull(summary.receivedImages)
        assertNull(summary.completedResults)
        recorder.close()
    }

    private fun successEvent() = CameraPipelineEvent.Terminal(
        generation = 1L,
        kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
        requiredOutputCommitted = true,
        publicExportCommitted = true,
        verified = true,
        captureResourcesSettled = true,
        counts = CameraPipelineProgressCounts(
            requestedFrames = 4,
            savedFrames = 4,
            receivedImages = 4,
            completedResults = 4
        )
    )

    private fun recorderFor(root: File): HardwareE2ERunRecorder =
        HardwareE2ERunRecorder.forTest(root, environment()) {
            root.listFiles().orEmpty().filter { it.isDirectory }
        }

    private fun writeJob(
        root: File,
        name: String,
        createdAt: Long,
        jobType: String,
        output: Boolean = true,
        status: String = "PIPELINE_COMPLETE"
    ): File {
        val directory = File(root, name).apply { mkdirs() }
        if (output) File(directory, "final.jpg").writeText("output")
        File(directory, JOB_JSON_FILE_NAME).writeText(
            JSONObject()
                .put("jobType", jobType)
                .put("captureMode", CaptureMode.MULTI_FRAME.name)
                .put("requestedResolutionMode", CaptureResolutionMode.MP12.name)
                .put("createdAt", createdAt)
                .put("status", status)
                .put("processStatus", status)
                .put("exportStatus", "COMPLETE")
                .put("exportVerified", true)
                .put("requestedFrames", 4)
                .put("attemptedFrames", 4)
                .put("savedFrames", 4)
                .put("receivedImages", 4)
                .put("completedResults", 4)
                .put("finalFile", if (output) "final.jpg" else "")
                .toString()
        )
        return directory
    }
}
