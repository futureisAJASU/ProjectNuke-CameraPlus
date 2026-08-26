package com.projectnuke.keplernightlab

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 7E: HardwareE2E run identity under overlapping foreground captures.
 * A rejected/accepted second capture must not replace the first run, and a
 * background terminal must complete the run pinned to its exact job directory.
 */
@RunWith(RobolectricTestRunner::class)
class HardwareE2ERunRecorderConcurrencyTest {

    private val reportsDir = createTempDirectory("hw-e2e-reports").toFile()
    private val jobsRoot = createTempDirectory("hw-e2e-jobs").toFile()

    private fun environment() = HardwareE2EEnvironment(
        runtimeSessionId = "test-session",
        processStartTimestamp = 1L,
        appPackage = "com.projectnuke.keplernightlab",
        appVersion = "test",
        debugBuild = true,
        androidSdk = 36,
        manufacturer = "samsung",
        deviceModel = "SM-S921N",
        buildFingerprint = "test/fingerprint"
    )

    private fun newRecorder(): HardwareE2ERunRecorder =
        HardwareE2ERunRecorder.forTest(
            reportDirectory = reportsDir,
            environment = environment(),
            jobFinder = { jobsRoot.listFiles()?.toList() ?: emptyList() }
        )

    private fun scenario(name: String) = HardwareE2ERunScenario(
        requestedTestScenario = name,
        selectedPipelineMode = PipelineMode.YUV_NIGHT_FUSION.name,
        captureMode = CaptureMode.MULTI_FRAME.name,
        requestedLensSlot = LensSlot.MAIN_1X.name,
        requestedResolution = "12",
        frameCountPolicy = "MANUAL",
        effectiveRequestedFrames = 4,
        requestedZoom = 1.0f,
        requestedOutputFormat = "HEIF"
    )

    private fun newJobWithMetadata(prefix: String): File {
        val jobDir = jobsRoot.resolve("KPL_${prefix}_${System.nanoTime()}")
        jobDir.mkdirs()
        KeplerJobMetadata.write(
            jobDir,
            org.json.JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("captureMode", CaptureMode.MULTI_FRAME.name)
                .put("status", "COMPLETE")
                .put("processStatus", "EXPORT_VERIFIED")
                .put("exportStatus", "EXPORTED")
                .put("savedFrames", 4)
                .put("requestedFrames", 4)
                .put("requiredOutputCommitted", true)
        )
        return jobDir
    }

    @Test
    fun runA_handoff_thenRunBStart_doesNotReplaceRunA() {
        val recorder = newRecorder()
        val runA = recorder.start(scenario("runA"))
        assertNotNull(runA)
        recorder.recordEvent(
            CameraPipelineEvent.Started(101L, "capturing A"),
        )
        recorder.recordEvent(
            CameraPipelineEvent.CaptureStageComplete(
                101L,
                CameraPipelineProgressCounts(),
                "handoff A",
                jobDirectoryPath = "/bound/by/handoff/A",
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )

        // A second capture starting while A processes must record its own run.
        val runB = recorder.start(scenario("runB"))
        assertNotNull(runB)
        assertTrue(runA != runB)

        val runs = recorder.snapshotsForTest()
        assertEquals(2, runs.size)
        assertEquals("runA", runs[0].scenario.requestedTestScenario)
        assertEquals("/bound/by/handoff/A", runs[0].latestJobDirectory)
        assertTrue(recorder.awaitIdle())
    }

    @Test
    fun backgroundTerminalA_completesRunAWhileRunBIsForeground() {
        val recorder = newRecorder()
        val runA = recorder.start(scenario("runA"), foregroundSessionId = 1L, generation = 101L)!!
        assertNotNull(runA)
        val jobA = newJobWithMetadata("joba")
        recorder.recordEvent(CameraPipelineEvent.Started(101L, "capturing A"))
        recorder.recordEvent(
            CameraPipelineEvent.CaptureStageComplete(
                101L, CameraPipelineProgressCounts(), "handoff",
                jobDirectoryPath = jobA.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )
        // Run B becomes the foreground run.
        val runB = recorder.start(scenario("runB"), foregroundSessionId = 2L, generation = 202L)
        assertNotNull(runB)
        recorder.recordEvent(CameraPipelineEvent.Started(202L, "capturing B"))

        // Late terminal for A arrives while B is foreground. Route through the
        // explicit composite binding so the old session's terminal still reaches
        // run A without mutating run B.
        recorder.recordForegroundEvent(
            foregroundSessionId = 1L,
            event = CameraPipelineEvent.Terminal(
                generation = 101L,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                requiredOutputCommitted = true,
                publicExportCommitted = true,
                verified = true,
                captureResourcesSettled = true,
                message = "processing A complete"
            )
        )
        assertTrue(recorder.awaitIdle())

        val runs = recorder.snapshotsForTest()
        val finalizedA = runs.first { it.runId == runA }
        val stillOpenB = runs.first { it.runId == runB }
        assertEquals(HardwareE2EClassification.PASS, finalizedA.status)
        assertEquals(jobA.absolutePath, finalizedA.finalJob?.jobDirectory)
        assertNull(stillOpenB.terminalEvent)
        assertEquals(HardwareE2EClassification.INCOMPLETE, stillOpenB.status)
    }

    @Test
    fun backgroundTerminalB_completesRunB() {
        val recorder = newRecorder()
        val runA = recorder.start(scenario("runA"))
        assertNotNull(runA)
        recorder.recordEvent(CameraPipelineEvent.Started(101L, "A"))
        val runB = recorder.start(scenario("runB"))
        assertNotNull(runB)
        val jobB = newJobWithMetadata("jobb")
        recorder.recordEvent(CameraPipelineEvent.Started(202L, "B"))
        recorder.recordEvent(
            CameraPipelineEvent.CaptureStageComplete(
                202L, CameraPipelineProgressCounts(), "handoff B",
                jobDirectoryPath = jobB.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )
        recorder.recordEvent(
            CameraPipelineEvent.Terminal(
                generation = 202L,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                requiredOutputCommitted = true,
                publicExportCommitted = true,
                verified = true,
                captureResourcesSettled = true
            )
        )
        assertTrue(recorder.awaitIdle())
        val finalizedB = recorder.snapshotsForTest().first { it.runId == runB }
        assertEquals(HardwareE2EClassification.PASS, finalizedB.status)
        assertEquals(jobB.absolutePath, finalizedB.finalJob?.jobDirectory)
        // Run A remains untouched and incomplete (its terminal never came).
        val untouchedA = recorder.snapshotsForTest().first { it.runId == runA }
        assertEquals(HardwareE2EClassification.INCOMPLETE, untouchedA.status)
    }

    @Test
    fun exactJobCorrelation_survivesOverlappingForegroundRuns() {
        val recorder = newRecorder()
        recorder.start(scenario("overlap"))
        val olderJob = newJobWithMetadata("older")
        val newerJob = newJobWithMetadata("newer")

        recorder.recordEvent(CameraPipelineEvent.Started(301L, "capture"))
        // Handoff pins the EXACT job even though a NEWER job exists on disk.
        recorder.recordEvent(
            CameraPipelineEvent.CaptureStageComplete(
                301L, CameraPipelineProgressCounts(), "handoff",
                jobDirectoryPath = olderJob.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )
        recorder.recordEvent(
            CameraPipelineEvent.Terminal(
                generation = 301L,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                requiredOutputCommitted = true,
                publicExportCommitted = true,
                verified = true,
                captureResourcesSettled = true
            )
        )
        assertTrue(recorder.awaitIdle())
        val report = recorder.snapshotsForTest().single()
        assertEquals(HardwareE2EJobCorrelation.EXACT, report.jobCorrelation)
        assertEquals(olderJob.absolutePath, report.finalJob?.jobDirectory)
    }

    @Test
    fun captureBusyGone_doesNotClassifyPipelineSuccess() {
        val recorder = newRecorder()
        recorder.start(scenario("noTerminal"))
        val jobDir = newJobWithMetadata("noterminal")
        recorder.recordEvent(CameraPipelineEvent.Started(401L, "capture"))
        // Capture admission freed at handoff - the shutter is available again.
        recorder.recordEvent(
            CameraPipelineEvent.CaptureStageComplete(
                401L, CameraPipelineProgressCounts(), "handoff",
                jobDirectoryPath = jobDir.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )
        assertTrue(recorder.awaitIdle())

        // Shutter availability is NOT pipeline success: without the exact
        // run's terminal the classification stays INCOMPLETE.
        val report = recorder.snapshotsForTest().single()
        assertEquals(HardwareE2EClassification.INCOMPLETE, report.status)
        assertEquals(HardwareE2EClassificationReason.INCOMPLETE_REPORT, report.classificationReason)
    }

    @Test
    fun strictPublicExportRequirement_remains() {
        val recorder = newRecorder()
        recorder.start(scenario("strict"))
        val jobDir = newJobWithMetadata("strict_job")
        recorder.recordEvent(CameraPipelineEvent.Started(501L, "capture"))
        recorder.recordEvent(
            CameraPipelineEvent.CaptureStageComplete(
                501L, CameraPipelineProgressCounts(), "handoff",
                jobDirectoryPath = jobDir.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )
        // A success claim without durable required output cannot classify PASS.
        recorder.recordEvent(
            CameraPipelineEvent.Terminal(
                generation = 501L,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                requiredOutputCommitted = false,
                publicExportCommitted = false,
                verified = false,
                captureResourcesSettled = true
            )
        )
        assertTrue(recorder.awaitIdle())
        val report = recorder.snapshotsForTest().single()
        assertEquals(HardwareE2EClassification.FAIL, report.status)
        assertEquals(HardwareE2EClassificationReason.FAIL_OUTPUT_NOT_COMMITTED, report.classificationReason)
    }

    @Test
    fun processRecorder_sameGenerationDifferentSession_routesToDifferentRuns() {
        val recorder = newRecorder()
        val runA = recorder.start(scenario("runA"), foregroundSessionId = 1L, generation = 1L)!!
        val runB = recorder.start(scenario("runB"), foregroundSessionId = 2L, generation = 1L)!!
        assertNotEquals(runA, runB)

        recorder.recordForegroundEvent(1L, CameraPipelineEvent.Started(1L, "A start"))
        recorder.recordForegroundEvent(2L, CameraPipelineEvent.Started(1L, "B start"))

        val runs = recorder.snapshotsForTest()
        val reportA = runs.first { it.runId == runA }
        val reportB = runs.first { it.runId == runB }
        assertEquals(1, reportA.progressCounts.getOrDefault("CAPTURE_STARTED", 0))
        assertEquals(1, reportB.progressCounts.getOrDefault("CAPTURE_STARTED", 0))
        assertEquals("A start", reportA.eventHistory.last { it.checkpoint == "CAPTURE_STARTED" }.message)
        assertEquals("B start", reportB.eventHistory.last { it.checkpoint == "CAPTURE_STARTED" }.message)
    }

    @Test
    fun stageA_yuvThenActivityRecreateRaw_generationOneReuseDoesNotStealRawEvents() {
        val recorder = newRecorder()
        // Session A: YUV run generation=1
        val runA = recorder.start(scenario("yuv"), foregroundSessionId = 1L, generation = 1L)!!
        val jobA = newJobWithMetadata("stageA-yuv")
        recorder.recordForegroundEvent(1L, CameraPipelineEvent.Started(1L))
        recorder.recordForegroundEvent(
            1L,
            CameraPipelineEvent.CaptureStageComplete(
                1L, CameraPipelineProgressCounts(), "handoff",
                jobDirectoryPath = jobA.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )

        // Session B (recreate): RAW run generation=1
        val runB = recorder.start(scenario("raw"), foregroundSessionId = 2L, generation = 1L)!!
        val jobB = newJobWithMetadata("stageA-raw")
        recorder.recordForegroundEvent(2L, CameraPipelineEvent.Started(1L))
        recorder.recordForegroundEvent(
            2L,
            CameraPipelineEvent.CaptureStageComplete(
                1L, CameraPipelineProgressCounts(), "handoff",
                jobDirectoryPath = jobB.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )
        recorder.recordBackgroundEvent(backgroundTerminal(jobB))

        assertTrue(recorder.awaitIdle())

        val reportA = HardwareE2EReportStore.read(reportsDir, runA)!!
        val reportB = HardwareE2EReportStore.read(reportsDir, runB)!!

        // RAW run receives RAW events
        assertEquals(HardwareE2EClassification.PASS, reportB.status)
        assertEquals(jobB.absolutePath, reportB.finalJob?.jobDirectory)
        // YUV run is unchanged (still incomplete since no terminal)
        assertEquals(HardwareE2EClassification.INCOMPLETE, reportA.status)
        assertNull(reportA.terminalEvent)
        // Exact job routing
        assertEquals(jobA.absolutePath, reportA.latestJobDirectory)
        assertEquals(jobB.absolutePath, reportB.latestJobDirectory)
    }

    @Test
    fun stageB_firstPairThenRecreate_nextPairGenerationReuseStillPinsNewHandoff() {
        val recorder = newRecorder()
        // First pair: session A gen1, session A gen2
        val run1 = recorder.start(scenario("pair1-A"), foregroundSessionId = 1L, generation = 1L)!!
        val job1 = newJobWithMetadata("stageB-job1")
        recorder.recordForegroundEvent(1L, CameraPipelineEvent.Started(1L))
        recorder.recordForegroundEvent(
            1L,
            CameraPipelineEvent.CaptureStageComplete(
                1L, CameraPipelineProgressCounts(), "handoff",
                jobDirectoryPath = job1.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )

        val run2 = recorder.start(scenario("pair1-B"), foregroundSessionId = 1L, generation = 2L)!!
        val job2 = newJobWithMetadata("stageB-job2")
        recorder.recordForegroundEvent(1L, CameraPipelineEvent.Started(2L))
        recorder.recordForegroundEvent(
            1L,
            CameraPipelineEvent.CaptureStageComplete(
                2L, CameraPipelineProgressCounts(), "handoff",
                jobDirectoryPath = job2.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )

        // New session B (recreate): gen1
        val run3 = recorder.start(scenario("pair2-A"), foregroundSessionId = 2L, generation = 1L)!!
        val job3 = newJobWithMetadata("stageB-job3")
        recorder.recordForegroundEvent(2L, CameraPipelineEvent.Started(1L))
        recorder.recordForegroundEvent(
            2L,
            CameraPipelineEvent.CaptureStageComplete(
                1L, CameraPipelineProgressCounts(), "handoff",
                jobDirectoryPath = job3.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )

        // Background terminal for run3 must reach run3
        recorder.recordBackgroundEvent(backgroundTerminal(job3))
        assertTrue(recorder.awaitIdle())

        val report3 = HardwareE2EReportStore.read(reportsDir, run3)!!
        assertEquals(HardwareE2EClassification.PASS, report3.status)
        assertEquals(job3.absolutePath, report3.finalJob?.jobDirectory)
    }

    @Test
    fun packedAb_pngThenRecreatePacked_generationReuseRoutesPackedRun() {
        val recorder = newRecorder()
        // PNG test: session A gen1
        val runPng = recorder.start(scenario("png"), foregroundSessionId = 1L, generation = 1L)!!
        val jobPng = newJobWithMetadata("packed-png")
        recorder.recordForegroundEvent(1L, CameraPipelineEvent.Started(1L))
        recorder.recordForegroundEvent(
            1L,
            CameraPipelineEvent.CaptureStageComplete(
                1L, CameraPipelineProgressCounts(), "handoff",
                jobDirectoryPath = jobPng.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )
        recorder.recordBackgroundEvent(backgroundTerminal(jobPng))

        // PACKED test: session B gen1 (recreate)
        val runPacked = recorder.start(scenario("packed"), foregroundSessionId = 2L, generation = 1L)!!
        val jobPacked = newJobWithMetadata("packed-yuv")
        recorder.recordForegroundEvent(2L, CameraPipelineEvent.Started(1L))
        recorder.recordForegroundEvent(
            2L,
            CameraPipelineEvent.CaptureStageComplete(
                1L, CameraPipelineProgressCounts(), "handoff",
                jobDirectoryPath = jobPacked.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )
        recorder.recordBackgroundEvent(backgroundTerminal(jobPacked))

        assertTrue(recorder.awaitIdle())

        val reportPng = HardwareE2EReportStore.read(reportsDir, runPng)!!
        val reportPacked = HardwareE2EReportStore.read(reportsDir, runPacked)!!

        assertEquals(HardwareE2EClassification.PASS, reportPng.status)
        assertEquals(HardwareE2EClassification.PASS, reportPacked.status)
        assertEquals(jobPng.absolutePath, reportPng.finalJob?.jobDirectory)
        assertEquals(jobPacked.absolutePath, reportPacked.finalJob?.jobDirectory)
    }

    @Test
    fun lateOldSessionEvent_doesNotMutateNewSessionRun() {
        val recorder = newRecorder()
        val runA = recorder.start(scenario("runA"), foregroundSessionId = 1L, generation = 1L)!!
        val runB = recorder.start(scenario("runB"), foregroundSessionId = 2L, generation = 1L)!!

        recorder.recordForegroundEvent(2L, CameraPipelineEvent.Started(1L))

        // Late event from old session A must still resolve to A and must not
        // affect B.
        recorder.recordForegroundEvent(1L, CameraPipelineEvent.Started(1L))

        val runs = recorder.snapshotsForTest()
        val reportA = runs.first { it.runId == runA }
        val reportB = runs.first { it.runId == runB }

        assertEquals(1, reportA.progressCounts.getOrDefault("CAPTURE_STARTED", 0))
        assertEquals(1, reportB.progressCounts.getOrDefault("CAPTURE_STARTED", 0))
    }

    @Test
    fun backgroundExactJobRouting_survivesForegroundSessionDisposal() {
        val recorder = newRecorder()
        val runA = recorder.start(scenario("runA"), foregroundSessionId = 1L, generation = 1L)!!
        val jobA = newJobWithMetadata("bg-routing")
        recorder.recordForegroundEvent(
            1L,
            CameraPipelineEvent.CaptureStageComplete(
                1L, CameraPipelineProgressCounts(), "handoff",
                jobDirectoryPath = jobA.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )

        // Start a new foreground run B (simulates screen recreation)
        val runB = recorder.start(scenario("runB"), foregroundSessionId = 2L, generation = 1L)!!
        recorder.recordForegroundEvent(2L, CameraPipelineEvent.Started(1L))

        // Background terminal for A arrives while B is current
        recorder.recordBackgroundEvent(backgroundTerminal(jobA))
        assertTrue(recorder.awaitIdle())

        val reportA = HardwareE2EReportStore.read(reportsDir, runA)!!
        val reportB = HardwareE2EReportStore.read(reportsDir, runB)!!

        assertEquals(HardwareE2EClassification.PASS, reportA.status)
        assertEquals(jobA.absolutePath, reportA.finalJob?.jobDirectory)
        assertNull(reportB.terminalEvent)
        assertEquals(HardwareE2EClassification.INCOMPLETE, reportB.status)
    }

    private fun backgroundTerminal(job: File) = BackgroundPipelineEvent(
        requestJobDirectory = job,
        jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
        event = CameraPipelineEvent.Terminal(
            generation = 0L,
            kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
            requiredOutputCommitted = true,
            publicExportCommitted = true,
            verified = true,
            captureResourcesSettled = true,
            message = "background terminal",
            jobDirectoryPath = job.absolutePath
        )
    )
}
