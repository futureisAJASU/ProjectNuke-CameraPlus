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

    // ============================ test events ================================

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
        val disposals = AtomicInteger(0)
        override fun execute() { executions.incrementAndGet(); error("forced failure") }
        override fun disposeWithoutMutation() {}
    }

    private class ThrowingDisposeEvent : CaptureOwnerEvent {
        val executions = AtomicInteger(0)
        val disposals = AtomicInteger(0)
        override fun execute() { executions.incrementAndGet() }
        override fun disposeWithoutMutation() {
            disposals.incrementAndGet(); error("disposal failure")
        }
    }

    // ============================ existing tests ============================

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
        assertEquals(0, e1.executions.get())
        assertEquals(1, e1.disposals.get())
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
        val owner = CaptureStateOwner(dispatch = {
            Thread { it.execute(); executionDone.countDown() }.start()
            true
        })
        assertTrue(owner.post(event))
        assertTrue(started.await(2, TimeUnit.SECONDS))
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
        assertEquals(0, event.executions.get())
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
        owner.close()
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

    // ============================ new deterministic tests ============================

    // ---- dispatcher false/throw race: sync execution already happened ----

    @Test
    fun synchronousExecutionThenDispatcherFalseReportsAccepted() {
        val event = TestEvent()
        val owner = CaptureStateOwner(dispatch = { it.execute(); false })
        assertTrue(owner.post(event))
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
    }

    @Test
    fun synchronousExecutionThenDispatcherThrowReportsAccepted() {
        val event = TestEvent()
        val owner = CaptureStateOwner(dispatch = { it.execute(); error("post-dispatch") })
        assertTrue(owner.post(event))
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
    }

    // ---- queued-then-false -> disposed once, later execute is no-op ----

    @Test
    fun queuedThenFalseDisposesOnceAndLaterExecuteIsNoop() {
        val event = TestEvent()
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); false })
        assertFalse(owner.post(event))
        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
        val env = queue.poll(1, TimeUnit.SECONDS)!!
        env.execute()
        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
    }

    @Test
    fun queuedThenThrowDisposesOnceAndLaterExecuteIsNoop() {
        val event = TestEvent()
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val owner = CaptureStateOwner(dispatch = {
            queue.offer(it)
            error("dispatch threw after queueing")
        })
        assertFalse(owner.post(event))
        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
        val env = queue.poll(1, TimeUnit.SECONDS)!!
        env.execute()
        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
    }

    // ---- false-return racing PENDING -> RUNNING ----

    @Test
    fun falseReturnRacingPendingToRunningReportsAccepted() {
        val eventStarted = CountDownLatch(1)
        val allowEventToFinish = CountDownLatch(1)
        val executionCount = AtomicInteger(0)
        val disposalCount = AtomicInteger(0)

        val event = object : CaptureOwnerEvent {
            override fun execute() {
                eventStarted.countDown()
                assertTrue(allowEventToFinish.await(2, TimeUnit.SECONDS))
                executionCount.incrementAndGet()
            }
            override fun disposeWithoutMutation() { disposalCount.incrementAndGet() }
        }

        val owner = CaptureStateOwner(dispatch = { e ->
            Thread { e.execute() }.start()
            assertTrue(eventStarted.await(2, TimeUnit.SECONDS))
            false
        })

        assertTrue(owner.post(event))
        assertEquals(0, disposalCount.get())
        allowEventToFinish.countDown()

        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
        while (System.currentTimeMillis() < deadline && executionCount.get() == 0) {
            Thread.yield()
        }
        assertEquals(1, executionCount.get())
        assertEquals(0, disposalCount.get())
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
    }

    @Test
    fun throwRacingPendingToRunningReportsAccepted() {
        val eventStarted = CountDownLatch(1)
        val allowEventToFinish = CountDownLatch(1)
        val executionCount = AtomicInteger(0)
        val disposalCount = AtomicInteger(0)

        val event = object : CaptureOwnerEvent {
            override fun execute() {
                eventStarted.countDown()
                try {
                    allowEventToFinish.await(2, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    return
                }
                executionCount.incrementAndGet()
            }
            override fun disposeWithoutMutation() { disposalCount.incrementAndGet() }
        }

        val owner = CaptureStateOwner(dispatch = { e ->
            Thread { e.execute() }.start()
            assertTrue(eventStarted.await(2, TimeUnit.SECONDS))
            error("dispatch threw late")
        })

        assertTrue(owner.post(event))
        assertEquals(0, disposalCount.get())
        allowEventToFinish.countDown()

        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
        while (System.currentTimeMillis() < deadline && executionCount.get() == 0) {
            Thread.yield()
        }
        assertEquals(1, executionCount.get())
        assertEquals(0, disposalCount.get())
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
    }

    // ---- queued-then-throw -> disposed exactly once and later execute is no-op ----
    // (This was already covered above; adding one more for clarity)

    @Test
    fun queuedThenThrowReportsRejectedAndLaterExecuteIsNoop() {
        val executionCount = AtomicInteger(0)
        val disposalCount = AtomicInteger(0)
        val infos = mutableListOf<CaptureOwnerEvent>()

        val event = object : CaptureOwnerEvent {
            override fun execute() { executionCount.incrementAndGet() }
            override fun disposeWithoutMutation() { disposalCount.incrementAndGet() }
        }

        val owner = CaptureStateOwner(dispatch = { e ->
            infos.add(e)
            error("dispatch threw after queueing")
        })

        assertFalse(owner.post(event))
        assertEquals(0, executionCount.get())
        assertEquals(1, disposalCount.get())
        assertEquals(0, owner.pendingCount())

        // Later, execute is a no-op (already disposed)
        infos.first().execute()
        assertEquals(0, executionCount.get())
        assertEquals(1, disposalCount.get())
    }

    // ---- blocking disposer does not hold owner lock ----

    @Test
    fun blockingDisposerDoesNotHoldOwnerLock() {
        val disposeStarted = CountDownLatch(1)
        val disposeBlock = CountDownLatch(1)
        val event = object : CaptureOwnerEvent {
            override fun execute() {}
            override fun disposeWithoutMutation() {
                disposeStarted.countDown()
                disposeBlock.await(5, TimeUnit.SECONDS)
            }
        }
        val owner = CaptureStateOwner(dispatch = { false })
        // Post on separate thread so disposal blocks without holding lock
        val postDone = CountDownLatch(1)
        Thread {
            owner.post(event)
            postDone.countDown()
        }.start()
        assertTrue(disposeStarted.await(5, TimeUnit.SECONDS))
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
        disposeBlock.countDown()
        assertTrue(postDone.await(5, TimeUnit.SECONDS))
    }

    // ---- disposer re-entering owner does not deadlock ----

    @Test
    fun disposerReEnteringOwnerDoesNotDeadlock() {
        val events = mutableListOf<TestEvent>()
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true })
        val event = TestEvent()
        assertTrue(owner.post(event))
        owner.close()
        // Verify close() drained the pending event; remaining ol one queue to execute
        // Deliberately execute past close disposer. execute should be ALREADY_SETTLED.
        val env = queue.poll(1, TimeUnit.SECONDS)!!
        env.execute()
        assertEquals(1, event.disposals.get())
        assertEquals(0, event.executions.get())
    }

    // ---- disposal exception reaches onDisposalFailure once ----

    @Test
    fun disposalExceptionReachesOnDisposalFailureOnce() {
        val disposalFailures = AtomicInteger(0)
        val event = ThrowingDisposeEvent()
        val owner = CaptureStateOwner(dispatch = { false },
            onDisposalFailure = { _, _ -> disposalFailures.incrementAndGet() })
        assertFalse(owner.post(event))
        assertEquals(1, event.disposals.get())
        assertEquals(1, disposalFailures.get())
    }

    // ---- post-after-close disposal exception reaches hook ----

    @Test
    fun postAfterCloseDisposalExceptionReachesHook() {
        val disposalFailures = AtomicInteger(0)
        val event = ThrowingDisposeEvent()
        val owner = CaptureStateOwner(dispatch = { true },
            onDisposalFailure = { _, _ -> disposalFailures.incrementAndGet() })
        owner.close()
        assertFalse(owner.post(event))
        assertEquals(1, event.disposals.get())
        assertEquals(1, disposalFailures.get())
    }

    // ---- event failure hook throwing still leaves envelope COMPLETED and untracked ----

    @Test
    fun eventFailureHookThrowingStillLeavesEnvelopeCompletedAndUntracked() {
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true },
            onEventFailure = { _, _ -> error("hook threw") })
        val event = ThrowingTestEvent()
        assertTrue(owner.post(event))
        val env = queue.poll(2, TimeUnit.SECONDS)!!
        env.execute()
        assertEquals(1, event.executions.get())
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
        assertEquals(0, owner.trackingSize())
    }

    // ---- RUNNING event remains tracked after close and disappears only after body return ----

    @Test
    fun runningEventRemainsTrackedAfterClose() {
        val started = CountDownLatch(1)
        val block = CountDownLatch(1)
        val event = BlockingTestEvent(started, block)
        val owner = CaptureStateOwner(dispatch = { env ->
            Thread { env.execute() }.start()
            true
        })
        assertTrue(owner.post(event))
        assertTrue(started.await(5, TimeUnit.SECONDS))

        assertEquals(1, owner.runningCount())
        owner.close()
        assertTrue(owner.isClosed())
        assertEquals(1, owner.runningCount())
        assertEquals(0, event.disposals.get())

        block.countDown()
        // Spin flush until body exits
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
        while (System.currentTimeMillis() < deadline && owner.runningCount() > 0) {
            Thread.yield()
        }
        assertEquals(0, owner.runningCount())
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
    }

    // ---- close-disposed events: disposal outside lock, failure reports ----

    @Test
    fun closeDrainsPendingWithFailureHook() {
        val disposalFailures = AtomicInteger(0)
        val event = ThrowingDisposeEvent()
        val owner = CaptureStateOwner(dispatch = { true },
            onDisposalFailure = { _, _ -> disposalFailures.incrementAndGet() })
        val queue = LinkedBlockingQueue<CaptureOwnerEvent>()
        val async = CaptureStateOwner(dispatch = { queue.offer(it); true },
            onDisposalFailure = { _, _ -> disposalFailures.incrementAndGet() })
        assertTrue(async.post(event))
        async.close()
        assertEquals(1, event.disposals.get())
        assertEquals(1, disposalFailures.get())
    }

    // ---- Repeated post-after-close does not double-invoke hook ----
    @Test
    fun repeatedPostAfterCloseDisposesEachEventOnce() {
        val owner = CaptureStateOwner(dispatch = { true })
        owner.close()
        val e1 = TestEvent()
        val e2 = TestEvent()
        assertFalse(owner.post(e1))
        assertFalse(owner.post(e2))
        assertEquals(1, e1.disposals.get())
        assertEquals(1, e2.disposals.get())
        assertEquals(0, e1.executions.get())
        assertEquals(0, e2.executions.get())
    }
}