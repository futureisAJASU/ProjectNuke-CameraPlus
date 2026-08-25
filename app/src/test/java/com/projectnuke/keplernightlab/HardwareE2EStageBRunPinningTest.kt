package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage-B harness identity pinning: sequential captures that share one
 * pipeline (YUV->YUV, RAW->RAW) must be pinned at their evidenced handoffs,
 * never resolved through a mutable newest-first "latest matching pipeline"
 * scan. Also covers the deterministic mixed-pipeline configuration order.
 */
class HardwareE2EStageBRunPinningTest {

    private fun evidencedHandoffEvent(): HardwareE2EEventRecord = HardwareE2EEventRecord(
        checkpoint = HardwareE2EStageBRunPinning.CAPTURE_STAGE_COMPLETE_CHECKPOINT,
        eventType = "CaptureStageComplete",
        elapsedMs = 100L,
        wallClockTimestamp = 1L,
        generation = 1L,
        requestedFrames = 4,
        savedFrames = 4,
        receivedImages = 4,
        completedResults = 4,
        message = null,
        handoffEvidenceComplete = true
    )

    private fun legacyStageCompleteEvent(): HardwareE2EEventRecord =
        evidencedHandoffEvent().copy(handoffEvidenceComplete = false)

    @Suppress("SameParameterValue")
    private fun report(
        runId: String,
        startedAt: Long,
        pipeline: String = PipelineMode.YUV_NIGHT_FUSION.name,
        scenarioName: String = "production_main_camera_screen",
        events: List<HardwareE2EEventRecord> = listOf(evidencedHandoffEvent()),
        terminalKind: String? = null,
        finalJobPresent: Boolean = false,
        failure: String? = null
    ): HardwareE2ERunReport = HardwareE2ERunReport(
        schemaVersion = HARDWARE_E2E_SCHEMA_VERSION,
        runId = runId,
        runtimeSessionId = "runtime",
        processStartTimestamp = 1L,
        runStartWallClockTimestamp = startedAt,
        runEndWallClockTimestamp = if (terminalKind != null) startedAt + 500L else null,
        scenario = HardwareE2ERunScenario(
            requestedTestScenario = scenarioName,
            selectedPipelineMode = pipeline,
            captureMode = CaptureMode.MULTI_FRAME.name,
            requestedLensSlot = LensSlot.MAIN_1X.name,
            requestedResolution = CaptureResolutionMode.MP12.name,
            frameCountPolicy = FrameCountMode.MANUAL.name,
            effectiveRequestedFrames = 4,
            requestedZoom = 1.0f,
            requestedOutputFormat = FinalOutputFormat.JPEG.name
        ),
        appPackage = "pkg",
        appVersion = "1.0",
        debugBuild = true,
        androidSdk = 36,
        manufacturer = "m",
        deviceModel = "d",
        buildFingerprint = "f",
        eventHistory = events,
        progressCounts = emptyMap(),
        terminalEvent = terminalKind,
        terminalFlags = emptyMap(),
        latestJobDirectory = "/data/job-$runId",
        finalJob = if (finalJobPresent) {
            HardwareE2EJobSummary(
                jobDirectory = "/data/job-$runId",
                readable = true,
                jobType = "YUV_NIGHT_FUSION",
                captureMode = CaptureMode.MULTI_FRAME.name,
                createdAt = startedAt,
                status = "CAPTURE_COMPLETE",
                processStatus = "PIPELINE_COMPLETE",
                exportStatus = "COMPLETE",
                exportVerified = true,
                requiredOutputFilePresent = true,
                requestedFrames = 4,
                attemptedFrames = 4,
                savedFrames = 4,
                receivedImages = 4,
                completedResults = 4,
                failedCaptures = 0,
                partialCapture = false,
                cleanupType = "",
                cameraId = "0",
                physicalCameraId = "",
                requestedPhysicalCameraId = "",
                dngSidecarSaved = null,
                dngSidecarSkipReason = "",
                dngSidecarStatuses = emptyList(),
                frameManifestCount = 4,
                rawMetadata = emptyMap(),
                selectedRoute = "",
                actualRoute = "",
                processingTiming = emptyMap(),
                memoryFields = emptyMap(),
                activeOperationId = "",
                activeOperationKind = "",
                activeRuntimeSessionId = "",
                terminalOperationId = "",
                liveOperationRegistered = false,
                fileNames = emptyList(),
                error = null
            )
        } else {
            null
        },
        status = if (terminalKind != null) HardwareE2EClassification.PASS else HardwareE2EClassification.INCOMPLETE,
        failure = failure
    )

    private fun select(
        reportsNewestFirst: List<HardwareE2ERunReport>,
        excludedRunIds: Set<String>,
        invocationStart: Long,
        pipeline: String = PipelineMode.YUV_NIGHT_FUSION.name
    ): HardwareE2ERunReport? = HardwareE2EStageBRunPinning.selectNewHandoffRun(
        reports = reportsNewestFirst,
        excludedRunIds = excludedRunIds,
        invocationStartWallClock = invocationStart,
        expectedScenario = "production_main_camera_screen",
        expectedPipeline = pipeline
    )

    @Test
    fun samePipeline_AandBRunIdsRemainDistinct() {
        val runA = report("run-a", startedAt = 1_000L)
        val runB = report("run-b", startedAt = 60_000L)
        // Store ordering is NEWEST-first; B exists by the time B is pinned.
        val storeOrder = listOf(runB, runA)

        val pinnedA = select(storeOrder, excludedRunIds = emptySet(), invocationStart = 900L)
        assertNotNull(pinnedA)
        assertEquals("run-a", pinnedA!!.runId)

        val pinnedB = select(storeOrder, excludedRunIds = setOf("run-a"), invocationStart = 900L)
        assertNotNull(pinnedB)
        assertEquals("run-b", pinnedB!!.runId)

        assertNotEquals(pinnedA.runId, pinnedB.runId)
        assertNotEquals(pinnedA.latestJobDirectory, pinnedB.latestJobDirectory)
    }

    @Test
    fun samePipeline_newestB_cannotBeMisidentifiedAsA() {
        // Even when BOTH runs already exist and the source is sorted
        // newest-first (B first), selection must return A for the first pin:
        // earliest-started evidenced run wins, never the newest match.
        val runA = report("run-a", startedAt = 1_000L)
        val runB = report("run-b", startedAt = 60_000L)
        val newestFirst = listOf(runB, runA)

        val pinnedA = select(newestFirst, excludedRunIds = emptySet(), invocationStart = 900L)
        assertEquals("run-a", pinnedA?.runId)

        // A stale pre-invocation run is also never selected.
        val stale = report("stale-run", startedAt = 100L)
        val withStale = listOf(runB, runA, stale)
        assertEquals("run-a", select(withStale, emptySet(), invocationStart = 900L)?.runId)

        // A legacy (non-evidenced) stage-complete marker does not qualify.
        val legacyOnly = report(
            "legacy-run", startedAt = 2_000L,
            events = listOf(legacyStageCompleteEvent())
        )
        assertNull(select(listOf(runA, legacyOnly), setOf("run-a"), invocationStart = 900L)?.runId)
    }

    @Test
    fun terminalWait_usesPinnedRunId() {
        // A is pinned at its handoff and NOT yet terminal; B already reached a
        // terminal. Waiting on the PINNED id must resolve exactly A's report -
        // and must keep waiting (never adopt B) while A has no terminal.
        val runA = report("run-a", startedAt = 1_000L)
        val runBTerminal = report(
            "run-b", startedAt = 60_000L,
            terminalKind = CameraPipelineEvent.Terminal.Kind.COMPLETE.name,
            finalJobPresent = true
        )
        val storeOrder = listOf(runBTerminal, runA)

        val pinnedA = requireNotNull(select(storeOrder, emptySet(), invocationStart = 900L))
        assertEquals("run-a", pinnedA.runId)
        assertFalse(HardwareE2EStageBRunPinning.isTerminalReady(pinnedA))

        // Terminal-by-id reads ONLY the exact pinned run: simulate the exact
        // lookup the harness performs after releasing the lane.
        val reportsByRunId = storeOrder.associateBy { it.runId }
        val terminalizedA = report(
            "run-a", startedAt = 1_000L,
            terminalKind = CameraPipelineEvent.Terminal.Kind.COMPLETE.name,
            finalJobPresent = true
        )
        val updatedStore = reportsByRunId + ("run-a" to terminalizedA)
        assertTrue(HardwareE2EStageBRunPinning.isTerminalReady(updatedStore.getValue("run-a")))
        assertEquals("run-a", updatedStore.getValue("run-a").runId)
        assertNotEquals(
            updatedStore.getValue("run-b").latestJobDirectory,
            updatedStore.getValue("run-a").latestJobDirectory
        )
        // And a failed pinned terminal still satisfies readiness via `failure`.
        val failedA = report(
            "run-a", startedAt = 1_000L,
            terminalKind = CameraPipelineEvent.Terminal.Kind.FAILED.name,
            failure = "FAIL_PIPELINE_TERMINAL: x"
        )
        assertTrue(HardwareE2EStageBRunPinning.isTerminalReady(failedA))
    }

    @Test
    fun mixedPipelineConfiguration_isProvenBeforeSecondClick_allFourPairs() {
        val yuv = PipelineMode.YUV_NIGHT_FUSION.name
        val raw = PipelineMode.RAW_NIGHT_FUSION.name

        fun plan(a: String, b: String): List<HardwareE2EStageBRunPinning.Step> =
            HardwareE2EStageBRunPinning.stageBPlan(a, b)

        fun indexOf(steps: List<HardwareE2EStageBRunPinning.Step>, step: HardwareE2EStageBRunPinning.Step): Int =
            steps.indexOf(step)

        for ((pipelineA, pipelineB) in listOf(yuv to raw, raw to yuv, yuv to yuv, raw to raw)) {
            val steps = plan(pipelineA, pipelineB)
            val configureB = indexOf(steps, HardwareE2EStageBRunPinning.Step.CONFIGURE_PIPELINE_B)
            val pinA = indexOf(steps, HardwareE2EStageBRunPinning.Step.AWAIT_AND_PIN_HANDOFF_A)
            val clickB = indexOf(steps, HardwareE2EStageBRunPinning.Step.CLICK_CAPTURE_B)
            val pinB = indexOf(steps, HardwareE2EStageBRunPinning.Step.AWAIT_AND_PIN_HANDOFF_B)
            val release = indexOf(steps, HardwareE2EStageBRunPinning.Step.RELEASE_HEAVY_LANE)
            val terminalA = indexOf(steps, HardwareE2EStageBRunPinning.Step.AWAIT_TERMINAL_A_BY_PINNED_ID)
            val terminalB = indexOf(steps, HardwareE2EStageBRunPinning.Step.AWAIT_TERMINAL_B_BY_PINNED_ID)

            // Configuration of B (when mixed) happens strictly between A's pin
            // and B's capture attempt - NEVER after the click.
            assertEquals("$pipelineA->$pipelineB", pipelineA != pipelineB, configureB >= 0)
            if (configureB >= 0) {
                assertTrue(pinA < configureB)
                assertTrue(configureB < clickB)
            }
            // Identity pinning order and lane release ordering are invariant.
            assertTrue(0 <= pinA && pinA < clickB && clickB < pinB && pinB < release)
            assertTrue(release < terminalA && terminalA <= terminalB)
        }
    }
}
