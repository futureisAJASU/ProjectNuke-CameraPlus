package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RawTerminalRequestTest {
    @Test
    fun successFailureAndCancellationAreTypedRequestsForOneOwnerTransaction() {
        val success = RawTerminalRequest(
            CaptureTerminalStatus.SUCCESS,
            "CAPTURE_COMPLETE",
            null,
            RawTerminalCompletionKind.SUCCESS,
            null,
            true
        )
        val failure = success.copy(
            status = CaptureTerminalStatus.FAILED,
            jobStatus = "CAPTURE_FAILED",
            completionKind = RawTerminalCompletionKind.ERROR,
            saveMotion = false
        )
        val cancelled = failure.copy(
            status = CaptureTerminalStatus.CANCELLED,
            jobStatus = "CAPTURE_CANCELLED",
            reason = "cancelled"
        )

        assertEquals(RawTerminalCompletionKind.SUCCESS, success.completionKind)
        assertEquals(RawTerminalCompletionKind.ERROR, failure.completionKind)
        assertEquals(RawTerminalCompletionKind.ERROR, cancelled.completionKind)
        assertNotEquals(success.status, failure.status)
        assertNotEquals(failure.status, cancelled.status)
        assertEquals(false, failure.saveMotion)
        assertEquals(false, cancelled.saveMotion)
    }
}
