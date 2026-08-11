package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreviewResultGenerationTest {
    @Test
    fun oldRefreshCannotAdoptIntoNewGeneration() {
        assertFalse(acceptsPreviewResultGeneration(1, 2, coroutineActive = true))
    }

    @Test
    fun rejectedErrorDispatchIsVisible() {
        assertEquals(
            CameraUiDispatchOutcome.REJECTED,
            dispatchPreviewError({ false }, Runnable {})
        )
        assertEquals(
            CameraUiDispatchOutcome.DISPATCH_THREW,
            dispatchPreviewError({ throw IllegalStateException("closed") }, Runnable {})
        )
    }
}
