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

        fun runEntry(entry: Entry) {
            assertTrue(entries.remove(entry))
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

        assertFalse(orchestrator.start("capture") { _, _, _, _ -> starts++ })
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
        assertFalse(orchestrator.start("capture") { _, _, _, _ -> })
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
        val publisher = CameraPipelineTerminalPublisher(sink!!)
        assertTrue(publisher.publish(CameraPipelineEvent.Terminal.Kind.FAILED))
        assertFalse(publisher.publish(CameraPipelineEvent.Terminal.Kind.COMPLETE))

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

    @Test
    fun queuedProgressCannotReopenAnImmediatelySettledTerminal() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler()
        var sink: CameraPipelineEventSink? = null
        var terminalEffects = 0
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, { terminalEffects++ })
        )

        orchestrator.start("capture") { _, _, _, events -> sink = events }
        scheduler.run(250L)
        sink!!.invoke(
            CameraPipelineEvent.ProcessingStage(
                generation = 0L,
                stage = CaptureStage.PROCESSING,
                counts = CameraPipelineProgressCounts(),
                message = "processing one"
            )
        )
        sink!!.invoke(
            CameraPipelineEvent.ProcessingStage(
                generation = 0L,
                stage = CaptureStage.EXPORTING,
                counts = CameraPipelineProgressCounts(),
                message = "processing two"
            )
        )
        val queuedProgress = scheduler.entries.filter { it.delay == 0L }
        sink!!.invoke(
            CameraPipelineEvent.Terminal(
                generation = 0L,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                captureResourcesSettled = true,
                message = "complete"
            )
        )

        queuedProgress.forEach(scheduler::runEntry)
        assertEquals(CameraPipelineUiSession.Phase.TERMINAL, session.snapshot().phase)
        assertEquals(CameraPipelineEvent.Terminal.Kind.COMPLETE, session.snapshot().terminal?.kind)
        assertFalse(session.snapshot().isBusy)
        assertTrue(session.snapshot().previewAllowed)
        assertTrue(terminalEffects <= 1)
    }

    @Test
    fun queuedDisplayCannotOverwriteTerminalStatus() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler()
        val statuses = mutableListOf<String>()
        var sink: CameraPipelineEventSink? = null
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks(statuses::add, {}, {})
        )

        orchestrator.start("capture") { _, _, display, events ->
            display("Processing...")
            sink = events
        }
        scheduler.run(250L)
        val displayEntry = scheduler.entries.first { it.delay == 0L }
        sink!!.invoke(
            CameraPipelineEvent.Terminal(
                generation = 0L,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                message = "Complete"
            )
        )
        scheduler.runEntry(displayEntry)
        scheduler.run(0L)

        assertEquals("Complete", statuses.last())
        assertFalse(statuses.drop(1).contains("Processing..."))
        assertEquals(CameraPipelineUiSession.Phase.TERMINAL, session.snapshot().phase)
    }

    @Test
    fun oldGenerationTerminalNotificationCannotApplyToNewGeneration() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler()
        val statuses = mutableListOf<String>()
        var sink: CameraPipelineEventSink? = null
        var terminalEffects = 0
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks(statuses::add, {}, { terminalEffects++ })
        )

        orchestrator.start("A") { _, _, _, events -> sink = events }
        scheduler.run(250L)
        sink!!.invoke(
            CameraPipelineEvent.Terminal(
                generation = 0L,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                message = "A terminal"
            )
        )
        val oldNotification = scheduler.entries.first { it.delay == 0L }

        assertTrue(orchestrator.start("B") { _, _, _, _ -> })
        val statusCountBeforeOldNotification = statuses.size
        scheduler.runEntry(oldNotification)

        assertEquals(statusCountBeforeOldNotification, statuses.size)
        assertEquals(0, terminalEffects)
        assertEquals(2L, session.snapshot().generation)
        assertEquals(CameraPipelineUiSession.Phase.START_SCHEDULED, session.snapshot().phase)
    }

    @Test
    fun rejectedTerminalNotificationCannotReconcileAcrossGenerations() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler().also { it.rejectDelay = 0L }
        var sink: CameraPipelineEventSink? = null
        var terminalEffects = 0
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, { terminalEffects++ })
        )

        orchestrator.start("A") { _, _, _, events -> sink = events }
        scheduler.run(250L)
        sink!!.invoke(
            CameraPipelineEvent.Terminal(
                generation = 0L,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                message = "A terminal"
            )
        )
        assertEquals(TerminalUiDeliveryOutcome.REJECTED, orchestrator.terminalUiDeliveryOutcome())

        scheduler.rejectDelay = null
        assertTrue(orchestrator.start("B") { _, _, _, _ -> })
        assertEquals(CameraUiDispatchOutcome.REJECTED, orchestrator.reconcileTerminalUiDelivery())
        assertEquals(0, terminalEffects)
        assertEquals(2L, session.snapshot().generation)
    }

    @Test
    fun unexpectedJobExceptionWaitsForRealCompleteTerminalAndPreservesFacts() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler()
        var sink: CameraPipelineEventSink? = null
        var terminalEffects = 0
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, { terminalEffects++ })
        )

        orchestrator.start("capture") { _, _, _, events ->
            sink = events
            error("launcher failed")
        }
        scheduler.run(250L)

        assertTrue(sink != null)
        assertEquals(CameraPipelineUiSession.Phase.WAITING_FOR_TERMINAL, session.snapshot().phase)
        assertEquals(null, session.snapshot().terminal)
        assertFalse(session.hasTerminalClaimed(session.snapshot().generation))
        assertFalse(session.snapshot().captureResourcesSettled)
        assertTrue(session.snapshot().isBusy)
        assertFalse(session.snapshot().previewAllowed)
        assertEquals(0, terminalEffects)
        assertTrue(orchestrator.launcherFailure() != null)

        sink!!.invoke(
            CameraPipelineEvent.Terminal(
                generation = 0L,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                requiredOutputCommitted = true,
                publicExportCommitted = true,
                verified = true,
                captureResourcesSettled = true,
                message = "real complete"
            )
        )
        assertEquals(CameraPipelineUiSession.Phase.TERMINAL, session.snapshot().phase)
        assertEquals(CameraPipelineEvent.Terminal.Kind.COMPLETE, session.snapshot().terminal?.kind)
        assertTrue(session.snapshot().terminal?.requiredOutputCommitted == true)
        assertTrue(session.snapshot().terminal?.publicExportCommitted == true)
        assertTrue(session.snapshot().terminal?.verified == true)
        assertFalse(session.snapshot().isBusy)
        assertTrue(session.snapshot().previewAllowed)
        scheduler.run(0L)
        assertEquals(1, terminalEffects)
    }

    @Test
    fun unexpectedJobExceptionWaitsForRealFailedCancelledAndPartialTerminals() {
        listOf(
            CameraPipelineEvent.Terminal.Kind.FAILED,
            CameraPipelineEvent.Terminal.Kind.CANCELLED,
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL
        ).forEach { kind ->
            val session = CameraPipelineUiSession()
            val scheduler = ManualScheduler()
            var sink: CameraPipelineEventSink? = null
            val orchestrator = CameraPipelineUiOrchestrator(
                session,
                scheduler,
                CameraPipelineUiOrchestrator.Callbacks({}, {}, {})
            )
            assertTrue(orchestrator.start("capture") { _, _, _, events ->
                sink = events
                error("launcher failed")
            })
            scheduler.run(250L)
            assertEquals(CameraPipelineUiSession.Phase.WAITING_FOR_TERMINAL, session.snapshot().phase)

            sink!!.invoke(
                CameraPipelineEvent.Terminal(
                    generation = 0L,
                    kind = kind,
                    requiredOutputCommitted = kind == CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
                    publicExportCommitted = kind == CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
                    verified = kind == CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
                    captureResourcesSettled = true
                )
            )
            assertEquals(CameraPipelineUiSession.Phase.TERMINAL, session.snapshot().phase)
            assertEquals(kind, session.snapshot().terminal?.kind)
            assertFalse(session.snapshot().isBusy)
            assertTrue(session.snapshot().previewAllowed)
        }
    }

    @Test
    fun unexpectedJobExceptionFallsBackToUnresolvedWithoutFabricatingTerminal() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler()
        var sink: CameraPipelineEventSink? = null
        var terminalEffects = 0
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, { terminalEffects++ })
        )
        assertTrue(orchestrator.start("capture") { _, _, _, events ->
            sink = events
            error("launcher failed")
        })
        scheduler.run(250L)
        scheduler.run(15_000L)

        assertEquals(CameraPipelineUiSession.Phase.UNRESOLVED, session.snapshot().phase)
        assertEquals(null, session.snapshot().terminal)
        assertFalse(session.hasTerminalClaimed(session.snapshot().generation))
        assertFalse(session.snapshot().captureResourcesSettled)
        assertFalse(session.snapshot().previewAllowed)
        assertTrue(session.snapshot().isBusy)
        assertEquals(0, terminalEffects)

        sink!!.invoke(
            CameraPipelineEvent.Terminal(
                generation = 0L,
                kind = CameraPipelineEvent.Terminal.Kind.CANCELLED,
                captureResourcesSettled = true
            )
        )
        assertEquals(CameraPipelineUiSession.Phase.TERMINAL, session.snapshot().phase)
        assertEquals(CameraPipelineEvent.Terminal.Kind.CANCELLED, session.snapshot().terminal?.kind)
        assertFalse(session.snapshot().isBusy)
    }

    @Test
    fun launcherFailureFallbackRejectionPreservesUnresolvedAuthority() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler().also { it.rejectDelay = 15_000L }
        var sink: CameraPipelineEventSink? = null
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, {})
        )
        assertTrue(orchestrator.start("capture") { _, _, _, events ->
            sink = events
            error("launcher failed")
        })
        scheduler.run(250L)

        assertEquals(CameraPipelineUiSession.Phase.UNRESOLVED, session.snapshot().phase)
        assertEquals(null, session.snapshot().terminal)
        assertTrue(orchestrator.terminalFallbackDispatchFailure() != null)
        assertTrue(sink != null)
    }

    @Test
    fun realTerminalRemovesLauncherFallbackAndStaleFallbackCannotMutateNextGeneration() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler()
        var sink: CameraPipelineEventSink? = null
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, {})
        )
        assertTrue(orchestrator.start("A") { _, _, _, events ->
            sink = events
            error("launcher failed")
        })
        scheduler.run(250L)
        val staleFallback = scheduler.entries.single { it.delay == 15_000L }.work

        sink!!.invoke(
            CameraPipelineEvent.Terminal(
                generation = 0L,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                captureResourcesSettled = true
            )
        )
        assertTrue(scheduler.removed.contains(staleFallback))
        assertTrue(orchestrator.start("B") { _, _, _, _ -> })
        staleFallback.run()

        assertEquals(2L, session.snapshot().generation)
        assertEquals(CameraPipelineUiSession.Phase.START_SCHEDULED, session.snapshot().phase)
        assertFalse(session.snapshot().previewAllowed)
    }
}
