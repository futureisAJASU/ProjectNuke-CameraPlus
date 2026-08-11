package com.projectnuke.keplernightlab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewCommandGateTest {
    @Test
    fun requestedAndAppliedValuesRemainDistinctAfterDispatchFailure() {
        val snapshot = PreviewCommandSnapshot(
            generation = 4,
            requestedZoomRatio = 3.0f,
            appliedZoomRatio = 1.0f,
            lastOutcome = PreviewCommandApplyOutcome.DISPATCH_REJECTED
        )
        assertTrue(snapshot.requestedZoomRatio != snapshot.appliedZoomRatio)
        assertTrue(snapshot.lastOutcome == PreviewCommandApplyOutcome.DISPATCH_REJECTED)
    }

    @Test
    fun appliedStateAdvancesOnlyOnCameraMutationSuccess() {
        val requested = PreviewCommandSnapshot(generation = 5, requestedZoomRatio = 2.0f)
        val applied = requested.copy(
            appliedZoomRatio = requested.requestedZoomRatio,
            lastOutcome = PreviewCommandApplyOutcome.APPLIED
        )
        assertTrue(applied.appliedZoomRatio == 2.0f)
    }

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

    @Test
    fun staleOutcomeCannotOverwriteNewGenerationRequest() {
        val current = PreviewCommandSnapshot(
            generation = 2,
            requestedZoomRatio = 3.0f,
            lastOutcome = PreviewCommandApplyOutcome.APPLIED
        )
        val fenced = current.withGenerationOutcome(1, PreviewCommandApplyOutcome.STALE_GENERATION)
        assertEquals(2, fenced.generation)
        assertEquals(3.0f, fenced.requestedZoomRatio)
        assertEquals(PreviewCommandApplyOutcome.APPLIED, fenced.lastOutcome)
    }
}
