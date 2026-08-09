package com.projectnuke.keplernightlab

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProcessingCallbackDispatcherTest {
    @Test
    fun acceptedDispatchExecutesCallbackAndContainsCallbackFailure() {
        val count = AtomicInteger(0)
        val dispatcher = ProcessingCallbackDispatcher(
            Handler(Looper.getMainLooper()),
            "test",
            postOperation = { it.run(); true }
        )
        assertEquals(ProcessingCallbackDispatchResult.ACCEPTED, dispatcher.dispatch { count.incrementAndGet() })
        assertEquals(1, count.get())
        assertEquals(ProcessingCallbackDispatchResult.ACCEPTED, dispatcher.dispatch { error("callback failure") })
    }

    @Test
    fun rejectedDispatchDoesNotRunCallbackInline() {
        val count = AtomicInteger(0)
        val dispatcher = ProcessingCallbackDispatcher(
            Handler(Looper.getMainLooper()),
            "test",
            postOperation = { false }
        )
        assertEquals(ProcessingCallbackDispatchResult.REJECTED, dispatcher.dispatch { count.incrementAndGet() })
        assertEquals(0, count.get())
    }

    @Test
    fun throwingDispatcherIsReportedWithoutRunningCallback() {
        val count = AtomicInteger(0)
        val dispatcher = ProcessingCallbackDispatcher(
            Handler(Looper.getMainLooper()),
            "test",
            postOperation = { error("dispatcher failure") }
        )
        assertEquals(ProcessingCallbackDispatchResult.DISPATCH_THREW, dispatcher.dispatch { count.incrementAndGet() })
        assertEquals(0, count.get())
    }
}
