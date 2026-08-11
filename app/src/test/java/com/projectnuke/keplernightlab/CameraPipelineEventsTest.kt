package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPipelineEventsTest {
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
