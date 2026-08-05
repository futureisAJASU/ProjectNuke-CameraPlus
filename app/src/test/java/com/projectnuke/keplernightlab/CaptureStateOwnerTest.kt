package com.projectnuke.keplernightlab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

    private class ThrowingTestEvent : CaptureOwnerEvent {
        val executions = AtomicInteger(0)
        override fun execute() { executions.incrementAndGet(); error("forced failure") }
        override fun disposeWithoutMutation() {}
    }

    // ------------------------------------------------------------------
    // Open / RUNNING vs close interleaving
    // ------------------------------------------------------------------

    @Test
    fun postWithSynchronousDispatchExecutesAndReportsAccepted() {
        val event = TestEvent()
        val owner = CaptureStateOwner(dispatch = { it.execute(); true })
        assertTrue(owner.post(event))
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
    }

    @Test
    fun postAfterCloseDisposesWithoutMutation() {
        val event = TestEvent()
        val owner = CaptureStateOwner(dispatch = { it.execute(); true })
        owner.close()
        assertFalse(owner.post(event))
        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
    }

    @Test
    fun closeDrainsPendingEventsButNotRunningOnes() {
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true })
        val e1 = TestEvent()
        assertTrue(owner.post(e1))
        assertEquals(1, queue.size)
        owner.close()

        // e1 was pending when close ran and should be disposed
        assertEquals(0, e1.executions.get())
        assertEquals(1, e1.disposals.get())

        // verify the deferred dispatch does NOTHING to already-drained evt
        queue.poll()!!.execute()
        assertEquals(0, e1.executions.get())
        assertEquals(1, e1.disposals.get())
    }

    @Test
    fun runningBeforeCloseMayFinish() {
        val started = CountDownLatch(1)
        val block = CountDownLatch(1)
        val executionDone = CountDownLatch(1)
        val event = BlockingTestEvent(started, block)
        val owner = CaptureStateOwner(dispatch = { envelope: CaptureOwnerEvent ->
            Thread {
                envelope.execute()
                executionDone.countDown()
            }.start()
            true
        })
        assertTrue(owner.post(event))
        assertTrue(started.await(2, TimeUnit.SECONDS))

        // Event is RUNNING now. Close must not touch it.
        owner.close()
        assertTrue(owner.isClosed())
        assertEquals(0, event.disposals.get())

        block.countDown()
        assertTrue(executionDone.await(5, TimeUnit.SECONDS))
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
    }

    @Test
    fun multipleCloseCallsAreIdempotent() {
        val event = TestEvent()
        val owner = CaptureStateOwner(dispatch = { true })
        assertTrue(owner.post(event))
        assertEquals(0, event.executions.get()) // async, not executed
        assertEquals(0, event.disposals.get())
        owner.close()
        owner.close()
        owner.close()
        val postCloseEvent = TestEvent()
        assertFalse(owner.post(postCloseEvent))
        assertEquals(1, postCloseEvent.disposals.get())
    }

    @Test
    fun closeReadyPostYieldsExactlyOneSettlementPerEvent() {
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val events = mutableListOf<TestEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true })
        val m = 200
        val startLatch = CountDownLatch(2)
        val doneLatch = CountDownLatch(2)

        val poster = Thread {
            startLatch.countDown(); startLatch.await(5, TimeUnit.SECONDS)
            repeat(m) { events += TestEvent().also(owner::post) }
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
        events.forEach { assertEquals(1, it.executions.get() + it.disposals.get()) }
        assertEquals(m, events.sumOf { it.executions.get() + it.disposals.get() })
    }

    @Test
    fun eventCannotBothExecuteAndDispose() {
        val events = mutableListOf<TestEvent>()
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true })
        repeat(100) { events += TestEvent(); owner.post(events.last()) }

        // Close some to race
        owner.close()
        // Execute remaining queue
        while (true) { (queue.poll() ?: break).execute() }
        events.forEach { event ->
            assertTrue("event settled exactly one way", event.executions.get() + event.disposals.get() == 1)
        }
    }

    @Test
    fun synchronousDispatcherPlusThrowingEventDoesNotMakePostReportRejection() {
        val failures = mutableListOf<Pair<CaptureOwnerEvent, Throwable>>()
        val owner = CaptureStateOwner(dispatch = { it.execute(); true },
            onEventFailure = { event, error -> failures += event to error })
        val event = ThrowingTestEvent()

        assertTrue(owner.post(event))
        assertEquals(1, event.executions.get())
        assertEquals(1, failures.size)
        assertEquals("forced failure", failures[0].second.message)
    }

    @Test
    fun eventFailureHookReceivesExceptionOnce() {
        val failures = AtomicInteger(0)
        val owner = CaptureStateOwner(dispatch = { it.execute(); true },
            onEventFailure = { _, _ -> failures.incrementAndGet() })

        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val asyncOwner = CaptureStateOwner(dispatch = { queue.offer(it); true },
            onEventFailure = { _, _ -> failures.incrementAndGet() })

        val event = ThrowingTestEvent()
        assertTrue(owner.post(event))
        assertEquals(1, failures.get())

        val event2 = ThrowingTestEvent()
        assertTrue(asyncOwner.post(event2))
        val env = queue.poll(2, TimeUnit.SECONDS)
        env.execute()
        assertEquals(2, failures.get())
    }

    @Test
    fun completedEventsDoNotAccumulate() {
        val owner = CaptureStateOwner(dispatch = { it.execute(); true })
        repeat(50) { assertTrue(owner.post(TestEvent())) }
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
        assertEquals(0, owner.trackingSize())
    }

    @Test
    fun dispatchedBeforeCloseExecutesNotDisposes() {
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true })
        val event = TestEvent()
        assertTrue(owner.post(event))
        val envelope = queue.poll(1, TimeUnit.SECONDS)!!
        envelope.execute()
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
        owner.close()
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
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

    @Test
    fun dispatcherThrowingBeforeDispatchDisposesOnce() {
        val event = TestEvent()
        val owner = CaptureStateOwner(dispatch = { error("dispatch threw") })
        val result = owner.post(event)
        assertFalse(result)
        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
    }
}