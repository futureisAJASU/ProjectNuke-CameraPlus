package com.projectnuke.keplernightlab

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 11: production-shaped lifecycle and overlap regressions. Processing
 * must survive Activity/screen recreation, no background event may depend on a
 * dead Activity or mutate another capture generation, and multi-generation
 * chains (A terminal during B capture, B terminal after C starts) must keep
 * exact routing with zero "latest job" inference.
 */
class LifecycleOverlapRegressionTest {

    @Before
    fun resetHub() {
        BackgroundPipelineEventHub.resetForTest()
    }

    private fun handoffEvent(generation: Long, path: String) =
        CameraPipelineEvent.CaptureStageComplete(
            generation,
            counts = CameraPipelineProgressCounts(4, 4, 4, 4),
            jobDirectoryPath = path,
            captureResourcesSettled = true,
            processingHandoffDurable = true
        )

    private fun startCapture(session: CameraPipelineUiSession, message: String): Long {
        val started = session.start(message, 4) as CameraPipelineUiSession.StartResult.Accepted
        session.accept(CameraPipelineEvent.Started(started.operation.generation))
        return started.operation.generation
    }

    @Test
    fun activityRecreation_whileAProcessing_disposedScreenIgnoresLateTerminal() {
        // Screen 1 owns generation for capture A...
        val screen1 = CameraPipelineUiSession(backgroundOccupancy = { false })
        val genA = startCapture(screen1, "capture A")
        screen1.accept(handoffEvent(genA, "/data/jobA"))

        // ...then the Activity is recreated: the OLD screen disposes.
        assertTrue(screen1.dispose())

        // A late terminal for the DEAD screen is rejected as DISPOSED - never
        // resurrects UI state, never cancels handed-off work.
        assertEquals(
            CameraPipelineUiSession.EventResult.DISPOSED,
            screen1.accept(
                CameraPipelineEvent.Terminal(
                    generation = genA,
                    kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                    requiredOutputCommitted = true,
                    publicExportCommitted = true,
                    verified = true,
                    jobDirectoryPath = "/data/jobA"
                )
            )
        )

        // The NEW screen instance admits captures immediately.
        val screen2 = CameraPipelineUiSession()
        val genB = startCapture(screen2, "capture B")
        // Generations are per-screen-session truth; the fresh instance owns
        // its own capture and blocks re-admission while it runs.
        assertFalse(screen2.snapshot().canAdmitNewCapture)
        assertEquals("capture B", screen2.snapshot().captureProgress.message)
    }

    @Test
    fun aTerminal_afterBHandoff_doesNotMutateB() {
        val session = CameraPipelineUiSession()
        // A runs as an earlier generation and hands off.
        val genA = startCapture(session, "capture A")
        session.accept(handoffEvent(genA, "/data/jobA"))
        // B starts and reaches ITS handoff while A still processes.
        val genB = startCapture(session, "capture B")
        session.accept(handoffEvent(genB, "/data/jobB"))
        val snapshotAfterB = session.snapshot()

        // A's late terminal arrives with A's generation only.
        assertEquals(
            CameraPipelineUiSession.EventResult.STALE,
            session.accept(
                CameraPipelineEvent.Terminal(
                    generation = genA,
                    kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                    jobDirectoryPath = "/data/jobA"
                )
            )
        )
        // B's foreground truth is byte-for-byte unchanged.
        assertEquals(snapshotAfterB.captureProgress, session.snapshot().captureProgress)
        assertEquals(snapshotAfterB.phase, session.snapshot().phase)
    }

    @Test
    fun bTerminal_afterCStarted_keepsExactRouting() {
        val refreshedDirs = mutableListOf<Pair<Boolean, String?>>()
        val scheduler = object : CameraUiScheduler {
            override fun post(delayMillis: Long, work: Runnable): CameraUiDispatchOutcome {
                work.run(); return CameraUiDispatchOutcome.ACCEPTED
            }
            override fun remove(work: Runnable): Boolean = true
        }

        val sessionForB = CameraPipelineUiSession()
        val genB = startCapture(sessionForB, "capture B")
        val dispatcher = BackgroundTerminalUiDispatcher(
            session = sessionForB,
            scheduler = scheduler,
            recordDiagnostic = {},
            refreshResult = { showPreview, dir -> refreshedDirs.add(showPreview to dir?.name) }
        )

        // C starts on the CURRENT screen while B processes in background.
        val sessionForC = CameraPipelineUiSession()
        val genC = startCapture(sessionForC, "capture C")

        // B's terminal is delivered through the background dispatcher bound to
        // B's OWN session truth - C's live capture suppresses preview pops but
        // B's exact RESULT directory is still what gets refreshed.
        val resultDirB = File("build/bp-jobB").apply { mkdirs() }
        dispatcher.onBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = resultDirB,
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = CameraPipelineEvent.Terminal(
                    generation = genB,
                    kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                    requiredOutputCommitted = true,
                    publicExportCommitted = true,
                    verified = true,
                    jobDirectoryPath = resultDirB.absolutePath
                ),
                resultJobDirectory = resultDirB
            )
        )
        assertEquals(listOf(false to resultDirB.name), refreshedDirs)
        assertTrue(sessionForC.snapshot().isCapturing)
    }

    @Test
    fun foregroundGenerations_rollOverMonotonically_perScreen() {
        val session = CameraPipelineUiSession()
        val generations = mutableListOf<Long>()
        repeat(3) { index ->
            val started = session.start("gen $index", 1) as
                CameraPipelineUiSession.StartResult.Accepted
            generations.add(started.operation.generation)
            session.settlePreStartFailure(started.operation.generation, "synthetic end $index")
        }
        assertEquals(generations.sorted(), generations)
        assertEquals(generations.distinct().size, generations.size)
    }

    @Test
    fun backgroundEnvelope_dualIdentity_neverConflated() {
        val requestDir = File("build/sr-request").apply { mkdirs() }
        val resultDir = File("build/sr-result").apply { mkdirs() }
        val envelope = BackgroundPipelineEvent(
            requestJobDirectory = requestDir,
            jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
            event = CameraPipelineEvent.Terminal(
                generation = 0L,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                jobDirectoryPath = requestDir.absolutePath,
                resultJobDirectoryPath = resultDir.absolutePath
            ),
            resultJobDirectory = resultDir
        )
        assertTrue(envelope.hasDistinctResultIdentity)
        assertEquals(requestDir, envelope.requestJobDirectory)
        assertEquals(resultDir, envelope.resultJobDirectory)
    }
}
