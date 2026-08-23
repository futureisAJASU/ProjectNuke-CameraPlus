package com.projectnuke.keplernightlab

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase-A corrective audit, Phase 2: background ownership leftovers.
 *
 * 2A - Super Resolution dual identity: the request/correlation job is the
 * source YUV capture job; the result job is the newly created SR output
 * directory. Routing uses request identity, finalization/UI refresh use result
 * identity, and the relationship is persisted durably before terminal
 * publication.
 *
 * 2B - Main-thread UI delivery: background hub events record diagnostics on
 * the worker thread; ALL Compose mutation is dispatched onto the camera-owned
 * UI scope and re-queries CURRENT foreground truth from the synchronized
 * session snapshot at delivery time.
 *
 * 2C - Fatal settlement precedence: Error is never swallowed during lease
 * settlement (bookkeeping then rethrow); cancellation semantics are preserved;
 * an ordinary lease-release failure never emits an ownership-settled claim.
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundOwnershipPhase2Test {

    private val roots = mutableListOf<File>()

    @After
    fun cleanup() {
        BackgroundPipelineEventHub.resetForTest()
        BackgroundProcessingCoordinator.resetForTest()
        roots.forEach { it.deleteRecursively() }
    }

    private fun newRoot(name: String): File =
        createTempDirectory(name).toFile().also { roots.add(it) }

    // ------------------------------------------------------------------
    // 2A - Super Resolution dual identity
    // ------------------------------------------------------------------

    private fun writeSourceCaptureJob(dir: File): File {
        dir.mkdirs()
        KeplerJobMetadata.write(
            dir,
            JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("captureMode", CaptureMode.MULTI_FRAME.name)
                .put("createdAt", System.currentTimeMillis())
        )
        return dir
    }

    @Test
    fun sr_routesBySourceRequest() {
        val root = newRoot("sr-route")
        val recorder = HardwareE2ERunRecorder.forTest(root, overlapEnvironment()) {
            root.listFiles().orEmpty().filter { it.isDirectory }
        }
        val runId = recorder.start(overlapScenario())!!
        val sourceDir = writeSourceCaptureJob(File(root, "srcCapture"))
        val resultDir = File(root, "srOutput").apply { mkdirs() }

        // Bind run -> SOURCE capture job at durable handoff.
        recorder.recordEvent(
            CameraPipelineEvent.CaptureStageComplete(
                generation = 1L,
                counts = CameraPipelineProgressCounts(),
                jobDirectoryPath = sourceDir.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )
        // SR terminal envelope: request=source capture, result=SR output.
        recorder.recordBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = sourceDir,
                resultJobDirectory = resultDir,
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = CameraPipelineEvent.ProcessingStage(
                    generation = 0L,
                    stage = CaptureStage.PROCESSING,
                    counts = CameraPipelineProgressCounts()
                )
            )
        )
        assertTrue(recorder.awaitIdle())
        val report = recorder.snapshotsForTest().single { it.runId == runId }
        // The processing-stage checkpoint was attributed to THIS run via the
        // REQUEST identity even though a distinct RESULT identity exists.
        assertEquals(1, report.progressCounts["PROCESSING_STARTED"])
    }

    @Test
    fun sr_finalizesOutputResult() {
        val root = newRoot("sr-finalize")
        val recorder = HardwareE2ERunRecorder.forTest(root, overlapEnvironment()) {
            root.listFiles().orEmpty().filter { it.isDirectory }
        }
        val runId = recorder.start(overlapScenario())!!
        val sourceDir = writeSourceCaptureJob(File(root, "srcCapture"))
        val resultDir = File(root, "srOutput").apply { mkdirs() }
        File(resultDir, "super_resolution.png").writeText("output")
        File(resultDir, JOB_JSON_FILE_NAME).writeText(
            JSONObject()
                .put("jobType", "SUPER_RESOLUTION_FUSION")
                .put("pipeline", "SUPER_RESOLUTION_FUSION")
                .put("createdAt", System.currentTimeMillis())
                .put("status", "COMPLETE")
                .put("processStatus", "COMPLETE")
                .put("exportStatus", "COMPLETE")
                .put("exportVerified", true)
                .put("requestedFrames", 4)
                .put("savedFrames", 1)
                .put("usedFrameCount", 4)
                .put("outputWidth", 4032)
                .put("outputHeight", 3024)
                .put("finalFile", "super_resolution.png")
                .toString()
        )

        recorder.recordEvent(
            CameraPipelineEvent.CaptureStageComplete(
                generation = 1L,
                counts = CameraPipelineProgressCounts(),
                jobDirectoryPath = sourceDir.absolutePath,
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )
        recorder.recordBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = sourceDir,
                resultJobDirectory = resultDir,
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = CameraPipelineEvent.Terminal(
                    generation = 0L,
                    kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                    requiredOutputCommitted = true,
                    publicExportCommitted = true,
                    verified = true,
                    message = "24M complete",
                    jobDirectoryPath = sourceDir.absolutePath,
                    resultJobDirectoryPath = resultDir.absolutePath
                )
            )
        )
        assertTrue(recorder.awaitIdle())
        // Finalization must settle asynchronously after the terminal.
        var report = recorder.snapshotsForTest().single { it.runId == runId }
        var attempts = 0
        while ((report.finalJob == null || report.finalJob?.jobDirectory != resultDir.absolutePath) && attempts < 200) {
            Thread.sleep(25)
            report = recorder.snapshotsForTest().single { it.runId == runId }
            attempts++
        }
        assertNotNull(report.finalJob)
        // Finalization reads the RESULT identity's durable metadata.
        assertEquals(resultDir.absolutePath, report.resultJobDirectoryPath)
        assertEquals(resultDir.absolutePath, report.finalJob?.jobDirectory)
        assertEquals(HardwareE2EJobCorrelation.EXACT, report.jobCorrelation)
        assertEquals(HardwareE2EClassification.PASS, report.status)
        assertSame(runId, runId)
    }

    @Test
    fun sr_uiRefreshUsesResultDirectory() {
        val root = newRoot("sr-ui-refresh")
        val requestDir = File(root, "srcCapture").apply { mkdirs() }
        val resultDir = File(root, "srOutput").apply { mkdirs() }
        val refreshed = AtomicReference<File?>()
        val previewFlag = AtomicReference(false)
        val scheduler = QueuedUiScheduler()
        val dispatcher = BackgroundTerminalUiDispatcher(
            session = CameraPipelineUiSession(),
            scheduler = scheduler,
            recordDiagnostic = {},
            refreshResult = { showPreview, exactJobDir ->
                previewFlag.set(showPreview)
                refreshed.set(exactJobDir)
            }
        )
        dispatcher.onBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = requestDir,
                resultJobDirectory = resultDir,
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = completedTerminal(requestDir)
            )
        )
        scheduler.drainAll()
        // UI refresh used the RESULT directory (SR output), not the request dir.
        assertEquals(resultDir, refreshed.get())

        // YUV/RAW shape: result == request identity.
        dispatcher.onBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = requestDir,
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = completedTerminal(requestDir)
            )
        )
        scheduler.drainAll()
        assertEquals(requestDir, refreshed.get())

        // SR failure before any output existed: result falls back to request so
        // the failed capture still refreshes its own entry.
        dispatcher.onBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = requestDir,
                resultJobDirectory = File(root, "never-created"),
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = completedTerminal(requestDir)
            )
        )
        scheduler.drainAll()
        assertEquals(requestDir, refreshed.get())
        assertFalse(previewFlag.get() == null)
    }

    @Test
    fun sr_sourceResultRelationshipDurable() {
        val root = newRoot("sr-durable-link")
        val sourceDir = writeSourceCaptureJob(File(root, "srcCapture"))
        val resultDir = File(root, "srOutput").apply { mkdirs() }

        KeplerBackgroundExecutor.linkSuperResolutionIdentities(sourceDir, resultDir)

        // Result side carries the explicit source link (stub created).
        val resultJob = JSONObject(KeplerJobMetadata.read(resultDir).toString())
        assertEquals(sourceDir.absolutePath, resultJob.optString("superResolutionSourceJobDirectory"))

        // Source side carries the reverse link.
        val sourceJob = JSONObject(KeplerJobMetadata.read(sourceDir).toString())
        assertEquals(resultDir.absolutePath, sourceJob.optString("superResolutionResultJobDirectory"))

        // Re-linking with an EXISTING result job.json preserves and updates it.
        KeplerJobMetadata.update(resultDir) { job -> job.put("status", "PROCESSING") }
        KeplerBackgroundExecutor.linkSuperResolutionIdentities(sourceDir, resultDir)
        val updatedResult = JSONObject(KeplerJobMetadata.read(resultDir).toString())
        assertEquals("PROCESSING", updatedResult.optString("status"))
        assertEquals(sourceDir.absolutePath, updatedResult.optString("superResolutionSourceJobDirectory"))
    }

    // ------------------------------------------------------------------
    // 2B - Main-thread UI delivery
    // ------------------------------------------------------------------

    @Test
    fun backgroundEvent_uiMutationOccursOnUiScope() {
        val session = CameraPipelineUiSession()
        val scheduler = QueuedUiScheduler()
        val uiThreadName = AtomicReference<String?>(null)
        val workerThreadName = AtomicReference<String?>(null)
        val mutationCount = AtomicInteger(0)
        val eventRoot = newRoot("ui-scope")
        val jobDir = File(eventRoot, "jobA").apply { mkdirs() }
        val dispatcher = BackgroundTerminalUiDispatcher(
            session = session,
            scheduler = scheduler,
            recordDiagnostic = {},
            refreshResult = { _, _ ->
                mutationCount.incrementAndGet()
                uiThreadName.set(Thread.currentThread().name)
            }
        )
        val workerStarted = CountDownLatch(1)
        val worker = Thread({
            workerThreadName.set(Thread.currentThread().name)
            dispatcher.onBackgroundEvent(
                BackgroundPipelineEvent(
                    requestJobDirectory = jobDir,
                    jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                    event = completedTerminal(jobDir)
                )
            )
            workerStarted.countDown()
        }, "background-worker-thread")
        worker.start()
        assertTrue(workerStarted.await(5, TimeUnit.SECONDS))

        // The heavy worker callback must NOT mutate UI state inline.
        assertEquals(0, mutationCount.get())
        assertTrue(scheduler.hasPendingWork())

        // All Compose mutation happens only when the camera-owned scope drains.
        val uiScope = Thread({ scheduler.drainAll() }, "camera-owned-ui-scope")
        uiScope.start()
        uiScope.join(5_000)
        assertEquals(1, mutationCount.get())
        assertEquals("camera-owned-ui-scope", uiThreadName.get())
    }

    @Test
    fun terminalA_duringCaptureB_doesNotShowResultPreview() {
        val session = CameraPipelineUiSession()
        // Foreground capture B owns the session.
        val startB = session.start("capturing B", 2)
        assertTrue(startB is CameraPipelineUiSession.StartResult.Accepted)

        val root = newRoot("during-capture")
        val jobADir = File(root, "jobA").apply { mkdirs() }
        val scheduler = QueuedUiScheduler()
        var showPreviewSeen: Boolean? = null
        var refreshedDir: File? = null
        val dispatcher = BackgroundTerminalUiDispatcher(
            session = session,
            scheduler = scheduler,
            recordDiagnostic = {},
            refreshResult = { showPreview, dir ->
                showPreviewSeen = showPreview
                refreshedDir = dir
            }
        )
        // Terminal for OLD job A arrives while B captures.
        dispatcher.onBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = jobADir,
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = completedTerminal(jobADir)
            )
        )
        scheduler.drainAll()
        // Data refresh may occur, but NEVER a result preview over capture B:
        // the decision used the CURRENT snapshot truth, not a stale one.
        assertEquals(false, showPreviewSeen)
        assertEquals(jobADir, refreshedDir)
    }

    @Test
    fun terminalA_idle_canRefreshResult() {
        val session = CameraPipelineUiSession()
        val scheduler = QueuedUiScheduler()
        var showPreviewSeen: Boolean? = null
        var refreshedDir: File? = null
        val dispatcher = BackgroundTerminalUiDispatcher(
            session = session,
            scheduler = scheduler,
            recordDiagnostic = {},
            refreshResult = { showPreview, dir ->
                showPreviewSeen = showPreview
                refreshedDir = dir
            }
        )
        val idleRoot = newRoot("idle-refresh")
        val jobDir = File(idleRoot, "jobA").apply { mkdirs() }
        dispatcher.onBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = jobDir,
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = completedTerminal(jobDir)
            )
        )
        scheduler.drainAll()
        // Idle foreground: the previous job's success MAY cover the UI.
        assertEquals(true, showPreviewSeen)
        assertEquals(jobDir, refreshedDir)
    }

    @Test
    fun diagnosticRecorderDoesNotWaitForUiDispatch() {
        val session = CameraPipelineUiSession()
        val scheduler = QueuedUiScheduler()
        val diagnosticsSeen = AtomicInteger(0)
        val mutations = AtomicInteger(0)
        val root = newRoot("diag-immediate")
        val jobDir = File(root, "job").apply { mkdirs() }
        val dispatcher = BackgroundTerminalUiDispatcher(
            session = session,
            scheduler = scheduler,
            recordDiagnostic = { diagnosticsSeen.incrementAndGet() },
            refreshResult = { _, _ -> mutations.incrementAndGet() }
        )
        val nonTerminal = BackgroundPipelineEvent(
            requestJobDirectory = jobDir,
            jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
            event = CameraPipelineEvent.ExportStage(
                generation = 0L,
                stage = CaptureStage.EXPORTING,
                counts = CameraPipelineProgressCounts()
            )
        )
        dispatcher.onBackgroundEvent(nonTerminal)
        // Diagnostics recorded synchronously on the calling thread...
        assertEquals(1, diagnosticsSeen.get())
        // ...while NO ui work was scheduled for non-terminal events.
        assertEquals(0, mutations.get())
        assertFalse(scheduler.hasPendingWork())

        val terminal = BackgroundPipelineEvent(
            requestJobDirectory = jobDir,
            jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
            event = completedTerminal(jobDir)
        )
        dispatcher.onBackgroundEvent(terminal)
        assertEquals(2, diagnosticsSeen.get())
        assertEquals(0, mutations.get())
        assertTrue(scheduler.hasPendingWork())
        scheduler.drainAll()
        assertEquals(1, mutations.get())
    }

    // ------------------------------------------------------------------
    // 2C - Fatal settlement precedence
    // ------------------------------------------------------------------

    @Test
    fun leaseSettlementError_notSwallowed() {
        val published = mutableListOf<Boolean>()
        val inFlight = IllegalStateException("lane body failure")
        try {
            KeplerBackgroundExecutor.finalizeLaneAfterExecution(
                releaseLease = { throw InternalError("fatal lease release") },
                jobDirName = "err-lane",
                inFlight = inFlight
            ) { settled -> published.add(settled) }
            fail("fatal Error must propagate")
        } catch (propagated: InternalError) {
            // Bookkeeping ran, then the Error was RETHROWN - not swallowed.
            assertEquals(listOf(false), published)
            // Original in-flight failure preserved as suppressed.
            assertTrue(propagated.suppressed.any { it === inFlight })
        }
    }

    @Test
    fun leaseSettlementCancellation_preserved() {
        val published = mutableListOf<Boolean>()
        val cancellation = java.util.concurrent.CancellationException("release cancelled")
        try {
            KeplerBackgroundExecutor.finalizeLaneAfterExecution(
                releaseLease = { throw cancellation },
                jobDirName = "cancel-lane",
                inFlight = null
            ) { settled -> published.add(settled) }
            fail("cancellation must propagate")
        } catch (propagated: java.util.concurrent.CancellationException) {
            assertSame(cancellation, propagated)
            // Terminal publication still attempted exactly once, fail-closed.
            assertEquals(listOf(false), published)
        }

        // In-flight cancellation with a CLEAN release: boundary reached truthfully.
        // Mirrors the production lane shape: the lane records the cancellation,
        // runs the settlement helper from its finally block, then rethrows.
        val publishedClean = mutableListOf<Boolean>()
        val laneCancellation = java.util.concurrent.CancellationException("lane cancelled")
        try {
            try {
                throw laneCancellation
            } catch (recorded: java.util.concurrent.CancellationException) {
                KeplerBackgroundExecutor.finalizeLaneAfterExecution(
                    releaseLease = { true },
                    jobDirName = "cancel-clean-lane",
                    inFlight = recorded
                ) { settled -> publishedClean.add(settled) }
                throw recorded
            }
            @Suppress("UNREACHABLE_CODE")
            fail("in-flight cancellation must keep propagating")
        } catch (propagated: java.util.concurrent.CancellationException) {
            assertSame(laneCancellation, propagated)
            assertEquals(listOf(true), publishedClean)
        }
    }

    @Test
    fun leaseSettlementException_doesNotClaimSettled() {
        val published = mutableListOf<Boolean>()
        // Ordinary release failure: helper does NOT throw; the lane continues to
        // publish its terminal WITHOUT an ownership-settled claim.
        KeplerBackgroundExecutor.finalizeLaneAfterExecution(
            releaseLease = { throw IllegalStateException("release io failed") },
            jobDirName = "exception-lane",
            inFlight = null
        ) { settled -> published.add(settled) }
        assertEquals(listOf(false), published)
    }

    @Test
    fun successfulSettlement_terminalPublishedAfterSettlement() {
        val order = mutableListOf<String>()
        KeplerBackgroundExecutor.finalizeLaneAfterExecution(
            releaseLease = { order.add("leaseReleased"); true },
            jobDirName = "success-lane",
            inFlight = null
        ) { settled ->
            order.add("terminalPublished:settled=$settled")
        }
        // Settlement precedes publication; truthful settled claim.
        assertEquals(listOf("leaseReleased", "terminalPublished:settled=true"), order)

        // Retain-for-reconciliation IS the documented owner boundary.
        val retainedOrder = mutableListOf<String>()
        KeplerBackgroundExecutor.finalizeLaneAfterExecution(
            releaseLease = { retainedOrder.add("leaseRetained"); false },
            jobDirName = "retain-lane",
            inFlight = null
        ) { settled -> retainedOrder.add("terminalPublished:settled=$settled") }
        assertEquals(listOf("leaseRetained", "terminalPublished:settled=true"), retainedOrder)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun completedTerminal(jobDir: File?): CameraPipelineEvent.Terminal =
        CameraPipelineEvent.Terminal(
            generation = 0L,
            kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
            requiredOutputCommitted = true,
            publicExportCommitted = true,
            verified = true,
            message = "done",
            jobDirectoryPath = jobDir?.absolutePath
        )

    /** Deterministic single-threaded stand-in for the camera-owned main scope. */
    private class QueuedUiScheduler : CameraUiScheduler {
        private val queue = ArrayDeque<Runnable>()
        override fun post(delayMillis: Long, work: Runnable): CameraUiDispatchOutcome {
            queue.addLast(work)
            return CameraUiDispatchOutcome.ACCEPTED
        }

        override fun remove(work: Runnable): Boolean = false

        fun hasPendingWork(): Boolean = queue.isNotEmpty()

        fun drainAll() {
            while (queue.isNotEmpty()) {
                queue.removeFirst().run()
            }
        }
    }

    private fun overlapScenario() = HardwareE2ERunScenario(
        requestedTestScenario = "phase2-ownership",
        selectedPipelineMode = PipelineMode.YUV_NIGHT_FUSION.name,
        captureMode = CaptureMode.MULTI_FRAME.name,
        requestedLensSlot = LensSlot.MAIN_1X.name,
        requestedResolution = CaptureResolutionMode.MP12.name,
        frameCountPolicy = FrameCountMode.MANUAL.name,
        effectiveRequestedFrames = 4,
        requestedZoom = 1.0f,
        requestedOutputFormat = FinalOutputFormat.JPEG.name
    )

    private fun overlapEnvironment() = HardwareE2EEnvironment(
        runtimeSessionId = "runtime-phase2",
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
