package com.projectnuke.keplernightlab

import android.content.Context
import android.os.Environment
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * PROCESS-SCOPED HardwareE2E recorder (pre-physical closure Phase 2).
 *
 * Lifecycle blocker this suite pins shut: the recorder previously lived in a
 * Composable remember + screen-owned close(), so the Stage-B mixed-pair
 * Activity recreation destroyed the exact jobA -> runIdA mapping and A's
 * background terminal was silently dropped (`runIdByJobDirectory[job] ?: return`).
 *
 * These tests simulate the REAL sequence - start run, evidenced handoff binds
 * jobA, screen disposal + recreation obtains the recorder AGAIN through
 * forContext, background terminal arrives through a NEW hub subscription -
 * and prove the process lifetime itself preserves exact routing. No test ever
 * rebinds jobA manually into a new recorder.
 */
@RunWith(RobolectricTestRunner::class)
class HardwareE2ERecorderProcessScopeTest {

    private val appContext: Context = RuntimeEnvironment.getApplication()

    @After
    fun cleanup() {
        HardwareE2ERecorderProcessScope.resetForTest()
        BackgroundPipelineEventHub.resetForTest()
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private fun scenario(name: String) = HardwareE2ERunScenario(
        requestedTestScenario = name,
        selectedPipelineMode = PipelineMode.YUV_NIGHT_FUSION.name,
        captureMode = CaptureMode.MULTI_FRAME.name,
        requestedLensSlot = LensSlot.MAIN_1X.name,
        requestedResolution = CaptureResolutionMode.MP12.name,
        frameCountPolicy = FrameCountMode.MANUAL.name,
        effectiveRequestedFrames = 4,
        requestedZoom = 1.0f,
        requestedOutputFormat = FinalOutputFormat.JPEG.name
    )

    /** REAL production-shaped job directory the process jobFinder discovers. */
    private fun createRealYuvJob(name: String): File {
        val pictures = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        val directory = File(File(pictures, "KeplerYuvFusion"), name).apply { mkdirs() }
        File(directory, "final.jpg").writeText("output")
        File(directory, JOB_JSON_FILE_NAME).writeText(
            JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("captureMode", CaptureMode.MULTI_FRAME.name)
                .put("requestedResolutionMode", CaptureResolutionMode.MP12.name)
                .put("createdAt", System.currentTimeMillis())
                .put("status", "PIPELINE_COMPLETE")
                .put("processStatus", "PIPELINE_COMPLETE")
                .put("exportStatus", "COMPLETE")
                .put("exportVerified", true)
                .put("requestedFrames", 4)
                .put("attemptedFrames", 4)
                .put("savedFrames", 4)
                .put("receivedImages", 4)
                .put("completedResults", 4)
                .put("finalFile", "final.jpg")
                .toString()
        )
        return directory
    }

    private fun captureStageComplete(job: File): CameraPipelineEvent.CaptureStageComplete =
        CameraPipelineEvent.CaptureStageComplete(
            generation = 0L,
            counts = CameraPipelineProgressCounts(),
            message = "evidenced handoff",
            jobDirectoryPath = job.absolutePath,
            captureResourcesSettled = true,
            processingHandoffDurable = true
        )

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
            message = "background terminal after recreation",
            jobDirectoryPath = job.absolutePath
        )
    )

    // ------------------------------------------------------------------
    // required regressions
    // ------------------------------------------------------------------

    @Test
    fun recorderProcessScope_sameInstanceAcrossScreenRecreation() {
        HardwareE2ERecorderProcessScope.resetForTest()
        // Screen composition #1 and (after recreation) composition #2 both ask
        // the production accessor; they MUST receive one shared instance.
        val first = HardwareE2ERunRecorder.forContext(appContext)
        val second = HardwareE2ERunRecorder.forContext(appContext)
        assertSame(first, second)
    }

    @Test
    fun captureAHandoff_thenScreenRecreated_thenTerminalA_stillRoutesToRunA() {
        HardwareE2ERecorderProcessScope.resetForTest()

        // --- screen #1: capture A starts, evidenced handoff binds jobA ---
        val screenRecorder = HardwareE2ERunRecorder.forContext(appContext)
        val runIdA = screenRecorder.start(scenario("runA"))!!
        val jobA = createRealYuvJob("scope-job-a")
        screenRecorder.recordEvent(CameraPipelineEvent.Started(1L))
        screenRecorder.recordEvent(captureStageComplete(jobA).copy(generation = 1L))

        // --- Activity recreation: old subscription disposed, new screen asks
        //     forContext again. The PROCESS lifetime itself must preserve the
        //     mapping (no manual rebinding anywhere in this test). ---
        val recreatedRecorder = HardwareE2ERunRecorder.forContext(appContext)
        assertSame(screenRecorder, recreatedRecorder)

        recreatedRecorder.recordBackgroundEvent(backgroundTerminal(jobA))
        assertTrue(recreatedRecorder.awaitIdle())

        val reportA = HardwareE2EReportStore.read(appContext, runIdA)!!
        assertEquals(CameraPipelineEvent.Terminal.Kind.COMPLETE.name, reportA.terminalEvent)
        assertEquals(HardwareE2EClassification.PASS, reportA.status)
        assertEquals(HardwareE2EClassificationReason.PASS_SUCCESS, reportA.classificationReason)
        assertEquals(HardwareE2EJobCorrelation.EXACT, reportA.jobCorrelation)
        assertNotNull(reportA.finalJob)
        assertEquals(jobA.absolutePath, reportA.finalJob?.jobDirectory)
    }

    @Test
    fun captureAHandoff_recreate_startB_terminalA_doesNotMutateRunB() {
        HardwareE2ERecorderProcessScope.resetForTest()

        val preRecreation = HardwareE2ERunRecorder.forContext(appContext)
        val runIdA = preRecreation.start(scenario("runA"))!!
        val jobA = createRealYuvJob("scope-job-a-b")
        preRecreation.recordEvent(CameraPipelineEvent.Started(1L))
        preRecreation.recordEvent(captureStageComplete(jobA).copy(generation = 1L))

        // Recreation happens BEFORE B starts (Stage-B mixed pair order).
        val postRecreation = HardwareE2ERunRecorder.forContext(appContext)
        assertSame(preRecreation, postRecreation)
        val runIdB = postRecreation.start(scenario("runB"))!!
        assertNotEquals(runIdA, runIdB)
        postRecreation.recordEvent(CameraPipelineEvent.Started(2L))

        // A's background terminal arrives AFTER B is current.
        postRecreation.recordBackgroundEvent(backgroundTerminal(jobA))
        assertTrue(postRecreation.awaitIdle())

        val reportA = HardwareE2EReportStore.read(appContext, runIdA)!!
        val reportB = HardwareE2EReportStore.read(appContext, runIdB)!!
        assertEquals(HardwareE2EClassification.PASS, reportA.status)
        assertEquals(HardwareE2EJobCorrelation.EXACT, reportA.jobCorrelation)
        assertNull("A's terminal must never terminate current run B", reportB.terminalEvent)
        assertEquals(HardwareE2EClassification.INCOMPLETE, reportB.status)
        assertEquals(0, reportB.progressCounts.getOrDefault("TERMINAL_COMPLETE", 0))
    }

    @Test
    fun mixedStageBRecreation_preservesAExactJobMapping() {
        HardwareE2ERecorderProcessScope.resetForTest()

        // YUV -> RAW style mixed pair with recreation between captures.
        val beforeRecreate = HardwareE2ERunRecorder.forContext(appContext)
        val runIdA = beforeRecreate.start(scenario("mixed-runA"))!!
        val jobA = createRealYuvJob("scope-mixed-job-a")
        beforeRecreate.recordEvent(CameraPipelineEvent.Started(1L))
        beforeRecreate.recordEvent(captureStageComplete(jobA).copy(generation = 1L))

        // configureSettings(pipelineB) recreates the Activity.
        val afterRecreate = HardwareE2ERunRecorder.forContext(appContext)
        assertSame(beforeRecreate, afterRecreate)
        val runIdB = afterRecreate.start(scenario("mixed-runB"))!!
        afterRecreate.recordEvent(CameraPipelineEvent.Started(2L))

        // Full A background stage sequence routes by EXACT job only.
        afterRecreate.recordBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = jobA,
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = CameraPipelineEvent.ProcessingStage(
                    generation = 0L,
                    stage = CaptureStage.PROCESSING,
                    counts = CameraPipelineProgressCounts(requestedFrames = 4, savedFrames = 4)
                )
            )
        )
        afterRecreate.recordBackgroundEvent(backgroundTerminal(jobA))
        assertTrue(afterRecreate.awaitIdle())

        val reportA = HardwareE2EReportStore.read(appContext, runIdA)!!
        val reportB = HardwareE2EReportStore.read(appContext, runIdB)!!
        assertTrue(reportA.progressCounts.containsKey("PROCESSING_STARTED"))
        assertTrue(reportA.progressCounts.containsKey("TERMINAL_COMPLETE"))
        assertEquals(HardwareE2EClassification.PASS, reportA.status)
        assertFalse(reportB.progressCounts.containsKey("PROCESSING_STARTED"))
        assertEquals(0, reportB.progressCounts.getOrDefault("TERMINAL_COMPLETE", 0))
        // latest.json still points at current foreground run B.
        val latest = HardwareE2EReportCodec.decode(
            File(HardwareE2EReportStore.directory(appContext), "latest.json").readText()
        )
        assertEquals(runIdB, latest.runId)
    }

    @Test
    fun oldSubscriptionDisposed_newSubscription_sameRecorderReceivesTerminal() {
        HardwareE2ERecorderProcessScope.resetForTest()

        val processRecorder = HardwareE2ERunRecorder.forContext(appContext)
        val runIdA = processRecorder.start(scenario("hub-runA"))!!
        val jobA = createRealYuvJob("scope-hub-job-a")
        processRecorder.recordEvent(CameraPipelineEvent.Started(1L))
        processRecorder.recordEvent(captureStageComplete(jobA).copy(generation = 1L))

        // Screen #1 subscription (captures THIS composition's recorder method ref).
        var deliveredToOld = 0
        val oldSubscription = BackgroundPipelineEventHub.subscribe { background ->
            deliveredToOld++
            processRecorder.recordBackgroundEvent(background)
        }
        oldSubscription.dispose() // screen #1 disposal

        // Screen #2 obtains the SAME process recorder and installs its own
        // subscription; the hub holds no reference to the disposed closure.
        val recreated = HardwareE2ERunRecorder.forContext(appContext)
        assertSame(processRecorder, recreated)
        var deliveredToNew = 0
        val newSubscription = BackgroundPipelineEventHub.subscribe { background ->
            deliveredToNew++
            recreated.recordBackgroundEvent(background)
        }
        try {
            BackgroundPipelineEventHub.publish(backgroundTerminal(jobA))
            assertTrue(processRecorder.awaitIdle())

            assertEquals(0, deliveredToOld)
            assertEquals(1, deliveredToNew)
            val reportA = HardwareE2EReportStore.read(appContext, runIdA)!!
            assertEquals(CameraPipelineEvent.Terminal.Kind.COMPLETE.name, reportA.terminalEvent)
            assertEquals(HardwareE2EClassification.PASS, reportA.status)
        } finally {
            newSubscription.dispose()
        }
    }

    @Test
    fun processRecorder_resetForTest_doesNotLeakWriterOrMappings() {
        HardwareE2ERecorderProcessScope.resetForTest()
        val first = HardwareE2ERunRecorder.forContext(appContext)
        first.start(scenario("reset-run"))!!
        assertEquals(1, first.snapshotsForTest().size)
        assertTrue(first.awaitIdle())

        // Reset terminates the writer AND discards mappings...
        HardwareE2ERecorderProcessScope.resetForTest()
        assertFalse("terminated writer must reject markers", first.awaitIdle())

        // ...and the next accessor call yields a FRESH isolated recorder.
        val second = HardwareE2ERunRecorder.forContext(appContext)
        assertNotEquals(System.identityHashCode(first), System.identityHashCode(second))
        assertEquals(0, second.snapshotsForTest().size)
        assertTrue(second.awaitIdle())

        // Repeated reset/rebuild cycles never wedge the scope or leak writers:
        // every fresh instance keeps accepting work.
        repeat(5) { cycle ->
            HardwareE2ERecorderProcessScope.resetForTest()
            val recorder = HardwareE2ERunRecorder.forContext(appContext)
            recorder.recordCheckpoint("RESET_CYCLE_$cycle", null, null)
            assertTrue(recorder.awaitIdle())
        }
        HardwareE2ERecorderProcessScope.resetForTest()
        HardwareE2ERecorderProcessScope.shutdownForTest() // alias is safe twice
    }
}
