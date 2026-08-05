package com.projectnuke.keplernightlab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStateOwnerTest {

    private class TestEvent : CaptureOwnerEvent {
        val executions = AtomicInteger(0)
        val disposals = AtomicInteger(0)
        override fun execute() { executions.incrementAndGet() }
        override fun disposeWithoutMutation() { disposals.incrementAndGet() }
    }

    private class BlockingTestEvent(
        private val started: CountDownLatch,
        private val block: CountDownLatch
    ) : CaptureOwnerEvent {
        val executions = AtomicInteger(0)
        val disposals = AtomicInteger(0)
        override fun execute() {
            started.countDown()
            block.await(5, TimeUnit.SECONDS)
            executions.incrementAndGet()
        }
        override fun disposeWithoutMutation() { disposals.incrementAndGet() }
    }

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
        val e1 = TestEvent(); val e2 = TestEvent()
        val owner = CaptureStateOwner(dispatch = { it.execute(); true })
        owner.close()
        assertFalse(owner.post(e1))
        assertFalse(owner.post(e2))
        assertEquals(0, e1.executions.get() + e2.executions.get())
        assertEquals(1, e1.disposals.get())
        assertEquals(1, e2.disposals.get())
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
        val owner = CaptureStateOwner(dispatch = { it.execute(); true })
        assertTrue(owner.post(event))
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
    }

    @Test
    fun closeBeforeDispatchDrainsPendingEvent() {
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true })
        val event = TestEvent()
        assertTrue(owner.post(event))
        assertEquals(1, queue.size)
        owner.close()
        // close drained the pending event; dispatch thread never started it
        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
    }

    @Test
    fun dispatchedBeforeCloseExecutesNotDisposes() {
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true })
        val event = TestEvent()
        assertTrue(owner.post(event))
        val envelope = queue.poll()!!
        envelope.execute() // runs before close
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
        owner.close()
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
    }

    // ------------------------------------------------------------------
    // Close-vs-start race
    // ------------------------------------------------------------------

    @Test
    fun closeReadyPostYieldsExactlyOneSettlementPerEvent() {
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val events = mutableListOf<TestEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true })
        val posts = 200
        val startLatch = CountDownLatch(2)
        val doneLatch = CountDownLatch(2)

        val poster = Thread {
            startLatch.countDown(); startLatch.await(5, TimeUnit.SECONDS)
            repeat(posts) { events += TestEvent().also(owner::post) }
            doneLatch.countDown()
        }
        val closer = Thread {
            startLatch.countDown(); startLatch.await(5, TimeUnit.SECONDS)
            owner.close()
            doneLatch.countDown()
        }
        poster.start(); closer.start()
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS))
        poster.join(5_000); closer.join(5_000)

        while (true) { (queue.poll() ?: break).execute() }
        events.forEach {
            assertEquals("event must settle exactly once", 1, it.executions.get() + it.disposals.get())
        }
        assertEquals(posts, events.sumOf { it.executions.get() + it.disposals.get() })
    }

    @Test
    fun dispatchWhileOwnerClosedDoesNotExecuteBody() {
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true })
        val event = TestEvent()
        assertTrue(owner.post(event))
        assertEquals(1, queue.size)
        owner.close()
        // Now dispatch the deferred event. Gate result is ALREADY_SETTLED
        // because close drained it.
        queue.poll()!!.execute()
        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
    }

    @Test
    fun eventRunningBeforeCloseMayFinish() {
        val started = CountDownLatch(1)
        val block = CountDownLatch(1)
        val executionDone = CountDownLatch(1)
        val event = BlockingTestEvent(started, block)
        val owner = CaptureStateOwner(dispatch = { e ->
            Thread {
                e.execute(); executionDone.countDown()
            }.start()
            true
        })
        assertTrue(owner.post(event))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        // The event is RUNNING now on its thread.
        owner.close()
        // Close must not block RUNNING events.
        block.countDown()
        assertTrue(executionDone.await(5, TimeUnit.SECONDS))
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
    }

    @Test
    fun throwingEventSettles() {
        val event = object : CaptureOwnerEvent {
            override fun execute() { error("forced") }
            override fun disposeWithoutMutation() {}
        }
        val owner = CaptureStateOwner(dispatch = { it.execute(); true })
        try { owner.post(event) } catch (_: Exception) {}
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
    }

    @Test
    fun completedEventsDoNotAccumulate() {
        val owner = CaptureStateOwner(dispatch = { it.execute(); true })
        repeat(50) { assertTrue(owner.post(TestEvent())) }
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
    }

    @Test
    fun postRejectionDisposesOnce() {
        val event = TestEvent()
        val owner = CaptureStateOwner(dispatch = { false })
        assertFalse(owner.post(event))
        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
        assertEquals(0, owner.pendingCount())
    }
}