package com.projectnuke.keplernightlab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resource-aware ownership tests for [CaptureStateOwner].  Every event owns its
 * exact resources and settles exactly once: either [CaptureOwnerEvent.execute]
 * or [CaptureOwnerEvent.disposeWithoutMutation], never both.
 *
 * The tests do not duplicate the internal PENDING -> RUNNING -> COMPLETED
 * state machine; they assert only the externally observable execute-or-dispose
 * ownership contract.
 */
class CaptureStateOwnerTest {

    /**
     * Test event that records which path was taken.  [execute] increments
     * [executions]; [disposeWithoutMutation] increments [disposals].  Exactly
     * one must be 1 and the other 0 for every event that reaches settlement.
     */
    private class TestEvent : CaptureOwnerEvent {
        val executions = AtomicInteger(0)
        val disposals = AtomicInteger(0)
        override fun execute() { executions.incrementAndGet() }
        override fun disposeWithoutMutation() { disposals.incrementAndGet() }
    }

    /**
     * Synchronous dispatcher that runs the envelope on the calling thread.
     * Used in tests that want to drive the start-gate inline.
     */
    private fun synchronousDispatcher(): ((CaptureOwnerEvent) -> Boolean) =
        { event -> event.execute(); true }

    // ------------------------------------------------------------------
    // Rejection and closure
    // ------------------------------------------------------------------

    @Test
    fun rejectedDispatchDisposesEventWithoutMutation() {
        val event = TestEvent()
        val owner = CaptureStateOwner(dispatch = { false })

        assertFalse(owner.post(event))
        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
    }

    @Test
    fun postAfterCloseDisposesWithoutMutation() {
        val event1 = TestEvent()
        val event2 = TestEvent()
        val owner = CaptureStateOwner(dispatch = synchronousDispatcher())
        owner.close()

        assertFalse(owner.post(event1))
        assertFalse(owner.post(event2))
        assertEquals(0, event1.executions.get() + event2.executions.get())
        assertEquals(1, event1.disposals.get())
        assertEquals(1, event2.disposals.get())
    }

    @Test
    fun multipleCloseCallsAreIdempotent() {
        val event = TestEvent()
        val owner = CaptureStateOwner(dispatch = { true })
        assertTrue(owner.post(event))

        owner.close()
        owner.close()
        owner.close()

        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())

        val postClose = TestEvent()
        assertFalse(owner.post(postClose))
        assertEquals(0, postClose.executions.get())
        assertEquals(1, postClose.disposals.get())
    }

    // ------------------------------------------------------------------
    // Exactly-one execution
    // ------------------------------------------------------------------

    @Test
    fun acceptedEventExecutesExactlyOnce() {
        val event = TestEvent()
        val owner = CaptureStateOwner(dispatch = synchronousDispatcher())

        assertTrue(owner.post(event))
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
    }

    @Test
    fun pendingEventCannotExecuteAfterCloseDrains() {
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true })

        val event = TestEvent()
        assertTrue(owner.post(event))
        assertEquals(1, queue.size)

        owner.close()

        // The dispatcher still has the event but close() drained it.
        // Running it via the envelope would CAS-fail; the event must not
        // mutate state after close() returns.  Calling execute() directly
        // would mutate, which proves the contract: only the owner decides
        // when execute runs.
        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
    }

    @Test
    fun noEventBothExecutesAndDisposes() {
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true })

        val event = TestEvent()
        assertTrue(owner.post(event))

        // Run envelope synchronously: simulate the dispatcher picking it up.
        queue.poll()?.execute()
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())

        owner.close()
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
    }

    // ------------------------------------------------------------------
    // Close-vs-run race
    // ------------------------------------------------------------------

    @Test
    fun closeRacingPostYieldsExactlyOneSettlementPerEvent() {
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val events = mutableListOf<TestEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true })
        val posts = 200

        val start = CountDownLatch(2)
        val done = CountDownLatch(2)

        val poster = Thread {
            start.countDown(); start.await(5, TimeUnit.SECONDS)
            repeat(posts) { events += TestEvent().also(owner::post) }
            done.countDown()
        }
        val closer = Thread {
            start.countDown(); start.await(5, TimeUnit.SECONDS)
            owner.close()
            done.countDown()
        }
        poster.start(); closer.start()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        poster.join(5_000); closer.join(5_000)

        // Drain whatever the dispatcher never ran.
        while (true) {
            val envelope = queue.poll() ?: break
            envelope.execute()
        }

        var executions = 0
        var disposals = 0
        events.forEach {
            executions += it.executions.get()
            disposals += it.disposals.get()
            assertEquals(
                "event must settle exactly once",
                1, it.executions.get() + it.disposals.get()
            )
        }
        assertEquals(posts, executions + disposals)
        assertEquals(posts, disposals + (events.count { it.executions.get() == 1 }))
    }

    /**
     * Event paused at the execution boundary must not begin mutation after
     * close() returns.  The owner offers an onExecutionBoundary hook so a
     * test can pause the envelope between CAS RUNNING and the open check.
     */
    @Test
    fun eventPausedAtBoundaryCannotMutateAfterCloseReturns() {
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val atBoundary = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = CaptureStateOwner(
            dispatch = { queue.offer(it); true },
            onExecutionBoundary = {
                atBoundary.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        )

        val event = TestEvent()
        assertTrue(owner.post(event))

        val dispatcher = Thread { queue.poll()?.execute() }
        dispatcher.start()

        assertTrue(atBoundary.await(2, TimeUnit.SECONDS))
        // Envelope has CAS'd RUNNING and is paused inside processReady.
        owner.close()
        release.countDown()
        dispatcher.join(5_000)

        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
    }
}
