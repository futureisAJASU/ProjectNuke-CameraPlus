package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPipelineUiSessionTest {
    @Test
    fun secondStartWhileActiveIsRejected() {
        val session = CameraPipelineUiSession()
        assertTrue(session.start("one", 4) is CameraPipelineUiSession.StartResult.Accepted)
        assertTrue(session.start("two", 4) is CameraPipelineUiSession.StartResult.Rejected)
    }

    @Test
    fun duplicateTerminalIsAcceptedOnlyOnce() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("one", 1) as CameraPipelineUiSession.StartResult.Accepted).operation
        val terminal = CameraPipelineEvent.Terminal(operation.generation, CameraPipelineEvent.Terminal.Kind.COMPLETE)
        assertEquals(CameraPipelineUiSession.EventResult.ACCEPTED, session.accept(terminal))
        assertEquals(CameraPipelineUiSession.EventResult.DUPLICATE_TERMINAL, session.accept(terminal))
        assertEquals(CameraPipelineUiSession.Phase.TERMINAL, session.snapshot().phase)
    }

    @Test
    fun staleGenerationCannotMutateNewSession() {
        val session = CameraPipelineUiSession()
        val first = (session.start("one", 1) as CameraPipelineUiSession.StartResult.Accepted).operation
        session.accept(CameraPipelineEvent.Terminal(first.generation, CameraPipelineEvent.Terminal.Kind.COMPLETE))
        val second = (session.start("two", 1) as CameraPipelineUiSession.StartResult.Accepted).operation
        assertEquals(CameraPipelineUiSession.EventResult.STALE, session.accept(CameraPipelineEvent.Terminal(first.generation, CameraPipelineEvent.Terminal.Kind.FAILED)))
        assertEquals(second.generation, session.snapshot().generation)
        assertFalse(session.snapshot().terminal?.kind == CameraPipelineEvent.Terminal.Kind.FAILED)
    }

    @Test
    fun disposalIgnoresLateTerminal() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("one", 1) as CameraPipelineUiSession.StartResult.Accepted).operation
        session.dispose()
        assertEquals(CameraPipelineUiSession.EventResult.DISPOSED, session.accept(CameraPipelineEvent.Terminal(operation.generation, CameraPipelineEvent.Terminal.Kind.COMPLETE)))
        assertEquals(CameraPipelineUiSession.Phase.DISPOSED, session.snapshot().phase)
    }

    @Test
    fun cancellationKeepsBusyUntilTerminalAndPreservesLateCommit() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("one", 1) as CameraPipelineUiSession.StartResult.Accepted).operation
        assertTrue(session.requestCancellation(operation.generation, "watchdog"))
        assertTrue(session.snapshot().isBusy)
        val late = CameraPipelineEvent.Terminal(
            generation = operation.generation,
            kind = CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            requiredOutputCommitted = true,
            publicExportCommitted = true,
            verified = true
        )
        assertEquals(CameraPipelineUiSession.EventResult.ACCEPTED, session.accept(late))
        assertFalse(session.snapshot().isBusy)
        assertTrue(session.snapshot().requiredOutputCommitted)
    }

    @Test
    fun missingTerminalBecomesUnresolvedAndDoesNotPretendIdle() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("one", 1) as CameraPipelineUiSession.StartResult.Accepted).operation
        session.requestCancellation(operation.generation, "watchdog")
        assertTrue(session.markTerminalDeliveryFailed(operation.generation, "unresolved"))
        assertEquals(CameraPipelineUiSession.Phase.UNRESOLVED, session.snapshot().phase)
        assertTrue(session.snapshot().isBusy)
        assertFalse(session.snapshot().previewAllowed)
    }

    @Test
    fun preStartFailureSettlesWithNoCaptureResources() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("start", 1) as CameraPipelineUiSession.StartResult.Accepted).operation

        assertTrue(session.settlePreStartFailure(operation.generation, "watchdog rejected"))
        assertFalse(session.snapshot().isBusy)
        assertTrue(session.snapshot().previewAllowed)
        assertTrue(session.snapshot().captureResourcesSettled)
        assertEquals(CameraPipelineEvent.Terminal.Kind.FAILED, session.snapshot().terminal?.kind)
        assertTrue(session.start("next", 1) is CameraPipelineUiSession.StartResult.Accepted)
    }

    @Test
    fun scheduledCancellationSettlesWithoutStartingCapture() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("start", 1) as CameraPipelineUiSession.StartResult.Accepted).operation
        assertTrue(session.requestCancellation(operation.generation, "cancel before runnable"))

        assertTrue(
            session.settleScheduledStartCancellation(
                operation.generation,
                "cancelled before Camera2 acquisition"
            )
        )
        assertFalse(session.snapshot().isBusy)
        assertTrue(session.snapshot().previewAllowed)
        assertEquals(CameraPipelineEvent.Terminal.Kind.CANCELLED, session.snapshot().terminal?.kind)
    }

    @Test
    fun preStartFailureIsExactlyOnce() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("start", 1) as CameraPipelineUiSession.StartResult.Accepted).operation
        assertTrue(session.settlePreStartFailure(operation.generation, "first"))
        assertFalse(session.settlePreStartFailure(operation.generation, "late"))
        assertEquals("first", session.snapshot().terminal?.message)
    }
}
