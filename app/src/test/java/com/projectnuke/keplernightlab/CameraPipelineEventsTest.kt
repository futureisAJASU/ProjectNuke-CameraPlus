package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPipelineEventsTest {
    @Test
    fun nativeEventRemainsAuthoritativeWhenDisplayTextChanges() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("start", 2) as CameraPipelineUiSession.StartResult.Accepted).operation
        val native = CameraPipelineEvent.CaptureProgress(
            generation = operation.generation,
            counts = CameraPipelineProgressCounts(requestedFrames = 2, savedFrames = 1),
            message = "PIPELINE_COMPLETE appears here as display-only diagnostics"
        )

        assertEquals(CameraPipelineUiSession.EventResult.ACCEPTED, session.accept(native))
        assertTrue(session.snapshot().isBusy)
        assertEquals(CameraPipelineUiSession.Phase.CAPTURING, session.snapshot().phase)
    }

    @Test
    fun nativeTerminalPreservesCommittedTruthEvenWhenFailureIsDisplayed() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("start", 1) as CameraPipelineUiSession.StartResult.Accepted).operation
        val native = CameraPipelineEvent.Terminal(
            generation = operation.generation,
            kind = CameraPipelineEvent.Terminal.Kind.FAILED,
            publicExportCommitted = true,
            verified = false,
            message = "PIPELINE_FAILED: commit checkpoint persistence failed; committed URI retained"
        )

        assertEquals(CameraPipelineUiSession.EventResult.ACCEPTED, session.accept(native))
        assertTrue(session.snapshot().terminal?.publicExportCommitted == true)
        assertFalse(session.snapshot().terminal?.verified == true)
    }

    @Test
    fun nativeCompleteDoesNotInventVerification() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("start", 1) as CameraPipelineUiSession.StartResult.Accepted).operation
        val native = CameraPipelineEvent.Terminal(
            generation = operation.generation,
            kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
            requiredOutputCommitted = true,
            publicExportCommitted = true,
            verified = false
        )

        session.accept(native)
        assertFalse(session.snapshot().terminal?.verified == true)
    }

    @Test
    fun terminalPublisherEmitsOnlyOneNativeTerminal() {
        val events = mutableListOf<CameraPipelineEvent>()
        val publisher = CameraPipelineTerminalPublisher(events::add)

        assertTrue(publisher.publish(CameraPipelineEvent.Terminal.Kind.FAILED, message = "first"))
        assertFalse(publisher.publish(CameraPipelineEvent.Terminal.Kind.COMPLETE, message = "late"))
        assertEquals(1, events.size)
        assertEquals(CameraPipelineEvent.Terminal.Kind.FAILED, (events.single() as CameraPipelineEvent.Terminal).kind)
    }

    @Test
    fun displayTextDoesNotChangeTypedTerminalMeaning() {
        val first = legacyCameraPipelineEvent(1L, "PIPELINE_COMPLETE: 저장 완료", CaptureProgressState())
        val second = legacyCameraPipelineEvent(1L, "PIPELINE_COMPLETE: changed display", CaptureProgressState())
        assertEquals((first as CameraPipelineEvent.Terminal).kind, (second as CameraPipelineEvent.Terminal).kind)
        assertEquals(first.requiredOutputCommitted, second.requiredOutputCommitted)
    }

    @Test
    fun markerInsideArbitraryDisplayTextIsNotTerminal() {
        val event = legacyCameraPipelineEvent(1L, "note: PIPELINE_COMPLETE appears in a diagnostic", CaptureProgressState())
        assertFalse(event is CameraPipelineEvent.Terminal)
    }

    @Test
    fun localizedProgressTextDoesNotBecomeTerminal() {
        val event = legacyCameraPipelineEvent(1L, "촬영 중 2 / 4", CaptureProgressState())
        assertTrue(event is CameraPipelineEvent.CaptureProgress)
    }

    @Test
    fun terminalKindIsStructural() {
        val event = CameraPipelineEvent.Terminal(
            generation = 4L,
            kind = CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            requiredOutputCommitted = true,
            publicExportCommitted = true,
            verified = true
        )
        assertEquals(CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL, event.kind)
        assertTrue(event.requiredOutputCommitted)
    }
}
