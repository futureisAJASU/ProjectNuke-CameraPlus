package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPipelineUiOrchestratorTest {
    private class ManualScheduler : CameraUiScheduler {
        data class Entry(val delay: Long, val work: Runnable)
        val entries = mutableListOf<Entry>()
        val removed = mutableListOf<Runnable>()
        var rejectDelay: Long? = null
        var throwDelay: Long? = null

        override fun post(delayMillis: Long, work: Runnable): CameraUiDispatchOutcome {
            if (throwDelay == delayMillis) error("dispatch failed")
            if (rejectDelay == delayMillis) return CameraUiDispatchOutcome.REJECTED
            entries += Entry(delayMillis, work)
            return CameraUiDispatchOutcome.ACCEPTED
        }

        override fun remove(work: Runnable): Boolean {
            removed += work
            return entries.removeAll { it.work === work }
        }

        fun run(delay: Long) {
            val entry = entries.first { it.delay == delay }
            entries.remove(entry)
            entry.work.run()
        }
    }

    @Test
    fun nativeTerminalEffectsRunExactlyOnce() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler()
        var terminalEffects = 0
        var sink: CameraPipelineEventSink? = null
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, { terminalEffects++ })
        )

        orchestrator.start("capture") { _, _, _, events -> sink = events }
        scheduler.run(250L)
        sink!!.invoke(CameraPipelineEvent.Terminal(0L, CameraPipelineEvent.Terminal.Kind.COMPLETE))
        scheduler.run(0L)
        sink!!.invoke(CameraPipelineEvent.Terminal(0L, CameraPipelineEvent.Terminal.Kind.COMPLETE))

        assertEquals(1, terminalEffects)
        assertFalse(session.snapshot().isBusy)
    }

    @Test
    fun watchdogRejectionSettlesBeforeJobStarts() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler().also { it.rejectDelay = 120_000L }
        var starts = 0
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, {})
        )

        assertFalse(orchestrator.start("capture") { _, _, _, _ -> starts++ })
        assertEquals(0, starts)
        assertFalse(session.snapshot().isBusy)
        assertTrue(session.snapshot().previewAllowed)
    }

    @Test
    fun jobStartRejectionSettlesBeforeCapture() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler().also { it.rejectDelay = 250L }
        var starts = 0
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, {})
        )

        assertTrue(orchestrator.start("capture") { _, _, _, _ -> starts++ })
        assertEquals(0, starts)
        assertFalse(session.snapshot().isBusy)
        assertTrue(session.snapshot().previewAllowed)
    }

    @Test
    fun jobStartRejectionRemovesWatchdogAndStaleWatchdogCannotReopenFailure() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler().also { it.rejectDelay = 250L }
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, {})
        )
        assertTrue(orchestrator.start("capture") { _, _, _, _ -> })
        val staleWatchdog = scheduler.removed.first()

        assertEquals(CameraPipelineUiSession.Phase.TERMINAL, session.snapshot().phase)
        assertEquals(CameraPipelineEvent.Terminal.Kind.FAILED, session.snapshot().terminal?.kind)
        assertFalse(session.snapshot().isBusy)
        assertTrue(session.snapshot().previewAllowed)
        staleWatchdog.run()
        assertEquals(CameraPipelineUiSession.Phase.TERMINAL, session.snapshot().phase)
        assertFalse(session.snapshot().isBusy)
        assertTrue(session.snapshot().previewAllowed)
        assertEquals(CameraPipelineEvent.Terminal.Kind.FAILED, session.snapshot().terminal?.kind)
    }

    @Test
    fun staleWatchdogCannotRegressSettledCancelledOrCompleteTerminal() {
        listOf(
            CameraPipelineEvent.Terminal.Kind.CANCELLED,
            CameraPipelineEvent.Terminal.Kind.COMPLETE
        ).forEach { kind ->
            val session = CameraPipelineUiSession()
            val scheduler = ManualScheduler()
            var sink: CameraPipelineEventSink? = null
            val orchestrator = CameraPipelineUiOrchestrator(
                session,
                scheduler,
                CameraPipelineUiOrchestrator.Callbacks({}, {}, {})
            )
            orchestrator.start("capture") { _, _, _, events -> sink = events }
            val watchdog = scheduler.entries.single { it.delay == 120_000L }.work
            scheduler.run(250L)
            sink!!.invoke(CameraPipelineEvent.Terminal(0L, kind))
            watchdog.run()
            assertEquals(CameraPipelineUiSession.Phase.TERMINAL, session.snapshot().phase)
            assertEquals(kind, session.snapshot().terminal?.kind)
            assertFalse(session.snapshot().isBusy)
            assertTrue(session.snapshot().previewAllowed)
        }
    }

    @Test
    fun nativeTerminalAuthoritySurvivesRejectedUiNotification() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler().also { it.rejectDelay = 0L }
        var sink: CameraPipelineEventSink? = null
        var terminalEffects = 0
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, { terminalEffects++ })
        )
        orchestrator.start("capture") { _, _, _, events -> sink = events }
        scheduler.run(250L)
        sink!!.invoke(CameraPipelineEvent.Terminal(0L, CameraPipelineEvent.Terminal.Kind.FAILED))

        assertEquals(CameraPipelineUiSession.Phase.TERMINAL, session.snapshot().phase)
        assertFalse(session.snapshot().isBusy)
        assertEquals(CameraPipelineEvent.Terminal.Kind.FAILED, session.snapshot().terminal?.kind)
        assertEquals(TerminalUiDeliveryOutcome.REJECTED, orchestrator.terminalUiDeliveryOutcome())
        assertEquals(0, terminalEffects)

        scheduler.rejectDelay = null
        assertEquals(CameraUiDispatchOutcome.ACCEPTED, orchestrator.reconcileTerminalUiDelivery())
        scheduler.run(0L)
        assertEquals(1, terminalEffects)
    }

    @Test
    fun nativeTerminalAuthoritySurvivesThrownUiNotificationDispatch() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler().also { it.throwDelay = 0L }
        var sink: CameraPipelineEventSink? = null
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, {})
        )
        orchestrator.start("capture") { _, _, _, events -> sink = events }
        scheduler.run(250L)
        sink!!.invoke(CameraPipelineEvent.Terminal(0L, CameraPipelineEvent.Terminal.Kind.COMPLETE))
        assertEquals(CameraPipelineUiSession.Phase.TERMINAL, session.snapshot().phase)
        assertEquals(TerminalUiDeliveryOutcome.DISPATCH_THREW, orchestrator.terminalUiDeliveryOutcome())
    }

    @Test
    fun displayMarkerCannotTerminateNativeSession() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler()
        var sink: ((CameraPipelineEvent) -> Unit)? = null
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, {})
        )

        orchestrator.start("capture") { _, _, display, events ->
            display("PIPELINE_COMPLETE is display text only")
            sink = events
        }
        scheduler.run(250L)
        scheduler.run(0L)
        assertTrue(session.snapshot().isBusy)
        assertTrue(sink != null)
    }

    @Test
    fun disposeRejectsLateNativeEvent() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler()
        var sink: CameraPipelineEventSink? = null
        var effects = 0
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, { effects++ })
        )
        orchestrator.start("capture") { _, _, _, events -> sink = events }
        orchestrator.dispose()
        sink?.invoke(CameraPipelineEvent.Terminal(0L, CameraPipelineEvent.Terminal.Kind.COMPLETE))
        assertEquals(0, effects)
        assertEquals(CameraPipelineUiSession.Phase.DISPOSED, session.snapshot().phase)
    }
}
