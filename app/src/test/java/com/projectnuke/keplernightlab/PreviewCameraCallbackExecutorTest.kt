package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.RejectedExecutionException

class PreviewCameraCallbackExecutorTest {
    @Test
    fun acceptedCallbackIsDispatched() {
        var ran = false
        var failure: Throwable? = null
        val executor = PreviewCameraCallbackExecutor(
            dispatch = { command -> command.run(); true },
            onDispatchFailure = { failure = it }
        )

        executor.execute(Runnable { ran = true })
        assertTrue(ran)
        assertEquals(null, failure)
    }

    @Test
    fun rejectedCallbackIsReportedAndThrown() {
        var failure: Throwable? = null
        val executor = PreviewCameraCallbackExecutor(
            dispatch = { false },
            onDispatchFailure = { failure = it }
        )

        var threw = false
        try {
            executor.execute(Runnable {})
        } catch (error: RejectedExecutionException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(failure is RejectedExecutionException)
    }

    @Test
    fun dispatchThrowIsReportedAndThrown() {
        var failure: Throwable? = null
        val executor = PreviewCameraCallbackExecutor(
            dispatch = { error("handler closed") },
            onDispatchFailure = { failure = it }
        )

        var threw = false
        try {
            executor.execute(Runnable {})
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse(failure == null)
    }
}
