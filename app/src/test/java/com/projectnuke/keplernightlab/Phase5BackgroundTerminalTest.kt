package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 5: background terminal delivery through the REAL production stack -
 * BackgroundProcessingCoordinator lane, KeplerBackgroundExecutor, durable
 * KeplerJobMetadata truth, JobOperationLease settlement, and the process-
 * scoped BackgroundPipelineEventHub. The hub is never tested in isolation:
 * every terminal assertion observes events delivered while the real executor
 * runs the exact durable request.
 */
@RunWith(RobolectricTestRunner::class)
class Phase5BackgroundTerminalTest {

    private val root = createTempDirectory("phase5-bg-terminal").toFile()

    @Before
    fun resetProcessState() {
        BackgroundProcessingCoordinator.resetForTest()
        BackgroundPipelineEventHub.resetForTest()
    }

    @After
    fun cleanup() {
        BackgroundPipelineEventHub.resetForTest()
        BackgroundProcessingCoordinator.resetForTest()
        root.deleteRecursively()
    }

    private fun newJobDir(prefix: String): File =
        root.resolve("${prefix}_${System.nanoTime()}").apply { mkdirs() }

    private fun tinyPng(jobDir: File, name: String): File {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val file = File(jobDir, name)
        try {
            file.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun identityParamsJson(): JSONObject = JSONObject().apply {
        put("presetName", "NATURAL")
        put("referenceWeight", 1f)
        put("ghostThreshold", 1f)
        put("ghostWeight", 0f)
        put("alignmentRejectThreshold", 1f)
        put("denoiseStrength", 0f)
        put("sharpenAmount", 0f)
        put("localContrastAmount", 0f)
        put("saturationBoost", 1f)
        put("shadowLift", 0f)
        put("highlightRollOff", 0f)
    }

    private fun writeJobJson(jobDir: File, build: JSONObject.() -> Unit) {
        File(jobDir, JOB_JSON_FILE_NAME).writeText(JSONObject().apply(build).toString())
    }

    /** Collects every envelope; records lease/metadata state AT DELIVERY TIME. */
    private class Collector : BackgroundPipelineEventSubscriber {
        val envelopes = CopyOnWriteArrayList<BackgroundPipelineEvent>()
        val latch = CountDownLatch(1)
        @Volatile var leasePresentAtTerminalDelivery: Boolean? = null
        @Volatile var pipelineFailedAtTerminalDelivery: Boolean? = null

        override fun onBackgroundEvent(event: BackgroundPipelineEvent) {
            envelopes.add(event)
            if (event.event is CameraPipelineEvent.Terminal) {
                val dir = event.exactJobDirectory
                leasePresentAtTerminalDelivery = runCatching {
                    KeplerJobMetadata.findOperationLease(dir) != null
                }.getOrDefault(true)
                pipelineFailedAtTerminalDelivery = runCatching {
                    KeplerJobMetadata.read(dir).optBoolean("pipelineFailed", false)
                }.getOrDefault(false)
                println(
                    "TERMINAL_SEEN thread=${Thread.currentThread().name} kind=" +
                        (event.event as CameraPipelineEvent.Terminal).kind +
                        " msg=" + event.event.message +
                        " dir=" + dir.name
                )
                latch.countDown()
            }
        }

        fun awaitTerminal(timeoutSeconds: Long = 30): Boolean = latch.await(timeoutSeconds, TimeUnit.SECONDS)

        fun terminals(): List<CameraPipelineEvent.Terminal> =
            envelopes.mapNotNull { it.event as? CameraPipelineEvent.Terminal }

        fun stages(): List<CameraPipelineEvent> =
            envelopes.map { it.event }.filterNot { it is CameraPipelineEvent.Terminal }
    }

    private fun enqueueAndAwait(jobDir: File, kind: KeplerActiveOperationKind, collector: Collector) {
        val result = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
            .enqueue(BackgroundProcessingRequest(exactJobDirectory = jobDir, jobKind = kind))
        assertTrue("request must be accepted", result is BackgroundEnqueueResult.Accepted)
        assertTrue("terminal must arrive", collector.awaitTerminal())
        assertTrue(
            "lane must drain after terminal",
            awaitLaneIdle(BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication()))
        )
    }

    private fun awaitLaneIdle(coordinator: BackgroundProcessingCoordinator, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!coordinator.snapshot().hasPendingWork) return true
            Thread.sleep(20)
        }
        return !coordinator.snapshot().hasPendingWork
    }

    private fun singleFrameYuvJob(prefix: String): File {
        val jobDir = newJobDir(prefix)
        tinyPng(jobDir, "frame_00_color.png")
        writeJobJson(jobDir) {
            put("jobType", "YUV_SINGLE_FRAME")
            put("captureMode", CaptureMode.SINGLE_FRAME.name)
            put("createdAt", System.currentTimeMillis())
            put("requestedFrames", 1)
            put("savedFrames", 1)
            put("processingParams", identityParamsJson())
            put("frames", JSONArray().put(JSONObject().put("file", "frame_00_color.png").put("enabled", true)))
        }
        return jobDir
    }

    @Test
    fun yuvAcceptedBackgroundRequest_emitsProcessingThenExactTerminal() {
        val collector = Collector()
        BackgroundPipelineEventHub.subscribe(collector)
        val jobDir = singleFrameYuvJob("yuv")

        enqueueAndAwait(jobDir, KeplerActiveOperationKind.PROCESSING_YUV, collector)

        val stageKinds = collector.stages().map { it.javaClass.simpleName }
        assertTrue(stageKinds.contains("ProcessingStage"))
        assertTrue(stageKinds.contains("ExportStage"))
        val processingIndex = collector.envelopes.indexOfFirst { it.event is CameraPipelineEvent.ProcessingStage }
        val exportIndex = collector.envelopes.indexOfFirst { it.event is CameraPipelineEvent.ExportStage }
        val terminalIndex = collector.envelopes.indexOfFirst { it.event is CameraPipelineEvent.Terminal }
        assertTrue(processingIndex in 0 until exportIndex)
        assertTrue(exportIndex < terminalIndex)

        val terminals = collector.terminals()
        assertEquals(1, terminals.size)
        val terminal = terminals.single()
        assertEquals(CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL, terminal.kind)
        assertEquals(jobDir.absolutePath, terminal.jobDirectoryPath)
        assertTrue(collector.envelopes.all { it.exactJobDirectory.absolutePath == jobDir.absolutePath })
        assertEquals(KeplerActiveOperationKind.PROCESSING_YUV, collector.envelopes.first().jobKind)
    }

    @Test
    fun rawAcceptedBackgroundRequest_emitsProcessingThenExactTerminal() {
        val collector = Collector()
        BackgroundPipelineEventHub.subscribe(collector)
        val jobDir = newJobDir("raw")
        writeJobJson(jobDir) {
            put("jobType", "RAW_NIGHT_FUSION")
            put("captureMode", CaptureMode.MULTI_FRAME.name)
            put("createdAt", System.currentTimeMillis())
            put("requestedFrames", 4)
            put("savedFrames", 4)
            put("frames", JSONArray())
        }

        enqueueAndAwait(jobDir, KeplerActiveOperationKind.PROCESSING_RAW, collector)

        val processingIndex = collector.envelopes.indexOfFirst { it.event is CameraPipelineEvent.ProcessingStage }
        val terminalIndex = collector.envelopes.indexOfFirst { it.event is CameraPipelineEvent.Terminal }
        assertTrue(processingIndex >= 0 && processingIndex < terminalIndex)

        val terminals = collector.terminals()
        assertEquals(1, terminals.size)
        assertEquals(CameraPipelineEvent.Terminal.Kind.FAILED, terminals.single().kind)
        assertEquals(jobDir.absolutePath, terminals.single().jobDirectoryPath)
        assertTrue(collector.envelopes.all { it.exactJobDirectory.absolutePath == jobDir.absolutePath })
    }

    @Test
    fun srAcceptedBackgroundRequest_emitsExactTerminal() {
        val collector = Collector()
        BackgroundPipelineEventHub.subscribe(collector)
        val sourceJobDir = newJobDir("srSource")
        writeJobJson(sourceJobDir) {
            put("jobType", "YUV_NIGHT_FUSION")
            put("captureMode", CaptureMode.MULTI_FRAME.name)
            put("createdAt", System.currentTimeMillis())
            put("backgroundWorkerKind", "SUPER_RESOLUTION")
            put("frames", JSONArray())
        }

        enqueueAndAwait(sourceJobDir, KeplerActiveOperationKind.PROCESSING_YUV, collector)

        val terminals = collector.terminals()
        assertEquals(1, terminals.size)
        assertEquals(CameraPipelineEvent.Terminal.Kind.FAILED, terminals.single().kind)
        assertEquals(sourceJobDir.absolutePath, terminals.single().jobDirectoryPath)
        assertTrue(terminals.single().message.orEmpty().contains("no source frames"))
        assertTrue(collector.envelopes.all { it.exactJobDirectory.absolutePath == sourceJobDir.absolutePath })
    }

    @Test
    fun backgroundOrdinaryFailure_emitsFailedTerminalWithExactJob() {
        val collector = Collector()
        BackgroundPipelineEventHub.subscribe(collector)
        val jobDir = newJobDir("yuvFail")
        writeJobJson(jobDir) {
            put("jobType", "YUV_NIGHT_FUSION")
            put("captureMode", CaptureMode.MULTI_FRAME.name)
            put("createdAt", System.currentTimeMillis())
            put("requestedFrames", 4)
            put("savedFrames", 4)
            put("processingParams", identityParamsJson())
            put(
                "frames",
                JSONArray().put(JSONObject().put("file", "missing_frame.png").put("enabled", true))
            )
        }

        enqueueAndAwait(jobDir, KeplerActiveOperationKind.PROCESSING_YUV, collector)

        val terminal = collector.terminals().single()
        assertEquals(CameraPipelineEvent.Terminal.Kind.FAILED, terminal.kind)
        assertEquals(jobDir.absolutePath, terminal.jobDirectoryPath)

        val durableTruth = KeplerJobMetadata.read(jobDir)
        assertTrue(durableTruth.optBoolean("pipelineFailed"))
        assertEquals("PIPELINE_FAILED", durableTruth.optString("processStatus"))
        assertNull(KeplerJobMetadata.findOperationLease(jobDir))
    }

    @Test
    fun backgroundPartialExport_emitsCompletePartialWithExactFlags() {
        val collector = Collector()
        BackgroundPipelineEventHub.subscribe(collector)
        val jobDir = singleFrameYuvJob("yuvPartial")

        enqueueAndAwait(jobDir, KeplerActiveOperationKind.PROCESSING_YUV, collector)

        val terminal = collector.terminals().single()
        assertEquals(CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL, terminal.kind)
        assertTrue(terminal.requiredOutputCommitted)
        assertFalse(terminal.verified)
        assertTrue(terminal.captureResourcesSettled)

        val durableTruth = KeplerJobMetadata.read(jobDir)
        assertTrue(durableTruth.optString("finalFile").isNotBlank())
        assertNull(KeplerJobMetadata.findOperationLease(jobDir))
    }

    @Test
    fun backgroundVerifiedExport_emitsCompleteWithExactFlags() {
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE,
            backgroundTerminalKind(requiredOutputCommitted = true, publicExportCommitted = true, verified = true)
        )
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            backgroundTerminalKind(requiredOutputCommitted = true, publicExportCommitted = true, verified = false)
        )
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            backgroundTerminalKind(requiredOutputCommitted = true, publicExportCommitted = false, verified = false)
        )
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.FAILED,
            backgroundTerminalKind(requiredOutputCommitted = false, publicExportCommitted = false, verified = false)
        )
    }

    @Test
    fun terminalEmissionOccursAfterLeaseSettlementBoundary() {
        val collector = Collector()
        BackgroundPipelineEventHub.subscribe(collector)
        val jobDir = newJobDir("yuvSettle")
        writeJobJson(jobDir) {
            put("jobType", "YUV_NIGHT_FUSION")
            put("captureMode", CaptureMode.MULTI_FRAME.name)
            put("createdAt", System.currentTimeMillis())
            put("requestedFrames", 4)
            put("savedFrames", 4)
            put("processingParams", identityParamsJson())
            put(
                "frames",
                JSONArray().put(JSONObject().put("file", "absent.png").put("enabled", true))
            )
        }

        enqueueAndAwait(jobDir, KeplerActiveOperationKind.PROCESSING_YUV, collector)

        assertEquals(CameraPipelineEvent.Terminal.Kind.FAILED, collector.terminals().single().kind)
        assertNotNull(collector.pipelineFailedAtTerminalDelivery)
        assertTrue("durable FAILED truth must precede terminal emission", collector.pipelineFailedAtTerminalDelivery == true)
        assertFalse(
            "lease must be settled before terminal emission",
            collector.leasePresentAtTerminalDelivery == true
        )
        assertNull(KeplerJobMetadata.findOperationLease(jobDir))
    }

    @Test
    fun eventObserverFailure_doesNotAlterProductionTruth() {
        val throwing = BackgroundPipelineEventSubscriber {
            throw IllegalStateException("observer failure must be observational only")
        }
        BackgroundPipelineEventHub.subscribe(throwing)
        val collector = Collector()
        BackgroundPipelineEventHub.subscribe(collector)
        val jobDir = singleFrameYuvJob("yuvObserver")

        enqueueAndAwait(jobDir, KeplerActiveOperationKind.PROCESSING_YUV, collector)

        val terminal = collector.terminals().single()
        assertEquals(CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL, terminal.kind)
        assertTrue(terminal.requiredOutputCommitted)
        assertNull(KeplerJobMetadata.findOperationLease(jobDir))
    }

    @Test
    fun disposedUiSubscriber_doesNotPreventBackgroundTerminal() {
        val disposedCollector = Collector()
        val disposedSubscription = BackgroundPipelineEventHub.subscribe(disposedCollector)
        disposedSubscription.dispose()
        val activeCollector = Collector()
        BackgroundPipelineEventHub.subscribe(activeCollector)

        val jobDir = newJobDir("srDisposed")
        writeJobJson(jobDir) {
            put("jobType", "YUV_NIGHT_FUSION")
            put("createdAt", System.currentTimeMillis())
            put("backgroundWorkerKind", "SUPER_RESOLUTION")
            put("frames", JSONArray())
        }
        enqueueAndAwait(jobDir, KeplerActiveOperationKind.PROCESSING_YUV, activeCollector)

        assertEquals(0, disposedCollector.envelopes.size)
        assertEquals(1, activeCollector.terminals().size)
        assertEquals(CameraPipelineEvent.Terminal.Kind.FAILED, activeCollector.terminals().single().kind)
    }

    @Test
    fun disposedUiSubscriber_isNotRetained() {
        val first = Collector()
        val second = Collector()
        val s1 = BackgroundPipelineEventHub.subscribe(first)
        BackgroundPipelineEventHub.subscribe(second)
        assertEquals(2, BackgroundPipelineEventHub.subscriberCountForTest())

        s1.dispose()
        assertEquals(1, BackgroundPipelineEventHub.subscriberCountForTest())
        s1.dispose()
        assertEquals(1, BackgroundPipelineEventHub.subscriberCountForTest())

        BackgroundPipelineEventHub.publish(
            BackgroundPipelineEvent(
                exactJobDirectory = newJobDir("probe"),
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = CameraPipelineEvent.ProcessingStage(
                    generation = 0L,
                    stage = CaptureStage.PROCESSING,
                    counts = CameraPipelineProgressCounts()
                )
            )
        )
        assertTrue(first.envelopes.isEmpty())
        assertEquals(1, second.envelopes.size)
    }
}
