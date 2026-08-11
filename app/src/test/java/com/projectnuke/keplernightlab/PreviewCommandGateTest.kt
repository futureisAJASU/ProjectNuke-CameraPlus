package com.projectnuke.keplernightlab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewCommandGateTest {
    @Test
    fun staleCommandCannotReachNewGeneration() {
        assertFalse(
            acceptsPreviewCommand(
                currentGeneration = 2,
                active = true,
                command = PreviewCommand(1, PreviewCommandKind.ZOOM)
            )
        )
    }

    @Test
    fun currentCommandIsAcceptedOnlyWhilePreviewIsActive() {
        val command = PreviewCommand(4, PreviewCommandKind.FOCUS_AE)
        assertTrue(acceptsPreviewCommand(4, active = true, command = command))
        assertFalse(acceptsPreviewCommand(4, active = false, command = command))
    }
}
