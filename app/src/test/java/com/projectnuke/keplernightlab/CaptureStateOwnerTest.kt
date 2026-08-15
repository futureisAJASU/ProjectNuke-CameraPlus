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

    private class FatalTestEvent : CaptureOwnerEvent {
        val executions = AtomicInteger(0)
        override fun execute() {
            executions.incrementAndGet()
            throw AssertionError("fatal event")
        }
        override fun disposeWithoutMutation() = Unit
    }

    private class FatalDisposeEvent : CaptureOwnerEvent {
        val disposals = AtomicInteger(0)
        override fun execute() = Unit
        override fun disposeWithoutMutation() {
            disposals.incrementAndGet()
            throw AssertionError("fatal disposal")
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
    fun fatalEventFailureEscapesAfterEnvelopeSettles() {
        val event = FatalTestEvent()
        val owner = CaptureStateOwner(dispatch = { it.execute(); true })
        var escaped: AssertionError? = null
        try {
            owner.post(event)
        } catch (failure: AssertionError) {
            escaped = failure
        }
        assertEquals("fatal event", escaped?.message)
        assertEquals(1, event.executions.get())
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
        assertEquals(0, owner.trackingSize())
    }

    @Test
    fun fatalDisposalFailureEscapesAndDoesNotBecomeRejectedPost() {
        val event = FatalDisposeEvent()
        val owner = CaptureStateOwner(dispatch = { false })
        var escaped: AssertionError? = null
        try {
            owner.post(event)
        } catch (failure: AssertionError) {
            escaped = failure
        }
        assertEquals("fatal disposal", escaped?.message)
        assertEquals(1, event.disposals.get())
        assertEquals(0, owner.trackingSize())
    }

    @Test
    fun fatalFailureHookEscapesInsteadOfReplacingEventFailureWithOrdinaryTruth() {
        val owner = CaptureStateOwner(
            dispatch = { it.execute(); true },
            onEventFailure = { _, _ -> throw AssertionError("fatal failure hook") }
        )
        var escaped: AssertionError? = null
        try {
            owner.post(ThrowingTestEvent())
        } catch (failure: AssertionError) {
            escaped = failure
        }
        assertEquals("fatal failure hook", escaped?.message)
        assertEquals(0, owner.trackingSize())
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

    // ---- DISPOSED acceptance defect tests (dispatcher closes owner + false/throw) ----

    @Test
    fun dispatcherClosesOwnerThenReturnsFalsePostReturnsFalse() {
        val event = TestEvent()
        var ownerRef: CaptureStateOwner? = null
        val owner = CaptureStateOwner(dispatch = { ownerRef!!.close(); false })
        ownerRef = owner
        assertFalse(owner.post(event))
        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.trackingSize())
    }

    @Test
    fun dispatcherClosesOwnerThenThrowsPostReturnsFalse() {
        val event = TestEvent()
        var ownerRef: CaptureStateOwner? = null
        val owner = CaptureStateOwner(dispatch = { ownerRef!!.close(); error("close-then-throw") })
        ownerRef = owner
        assertFalse(owner.post(event))
        assertEquals(0, event.executions.get())
        assertEquals(1, event.disposals.get())
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.trackingSize())
    }

    // ---- false/throw racing PENDING -> RUNNING (deterministic with latches + join) ----

    @Test
    fun falseReturnRacingPendingToRunningReportsAccepted() {
        val eventStarted = CountDownLatch(1)
        val allowEventToFinish = CountDownLatch(1)
        val executionFinished = CountDownLatch(1)
        val executionCount = AtomicInteger(0)
        val disposalCount = AtomicInteger(0)

        val event = object : CaptureOwnerEvent {
            override fun execute() {
                eventStarted.countDown()
                assertTrue(allowEventToFinish.await(2, TimeUnit.SECONDS))
                executionCount.incrementAndGet()
                executionFinished.countDown()
            }
            override fun disposeWithoutMutation() { disposalCount.incrementAndGet() }
        }

        val execThread = AtomicReference<Thread>()
        val owner = CaptureStateOwner(dispatch = { e ->
            val t = Thread { e.execute() }; execThread.set(t); t.start()
            assertTrue(eventStarted.await(2, TimeUnit.SECONDS))
            false
        })

        assertTrue(owner.post(event))
        assertEquals(0, disposalCount.get())
        allowEventToFinish.countDown()
        assertTrue(executionFinished.await(5, TimeUnit.SECONDS))
        execThread.get()?.join(2_000)
        assertEquals(1, executionCount.get())
        assertEquals(0, disposalCount.get())
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
        assertEquals(0, owner.trackingSize())
    }

    @Test
    fun throwRacingPendingToRunningReportsAccepted() {
        val eventStarted = CountDownLatch(1)
        val allowEventToFinish = CountDownLatch(1)
        val executionFinished = CountDownLatch(1)
        val executionCount = AtomicInteger(0)
        val disposalCount = AtomicInteger(0)

        val event = object : CaptureOwnerEvent {
            override fun execute() {
                eventStarted.countDown()
                assertTrue(allowEventToFinish.await(2, TimeUnit.SECONDS))
                executionCount.incrementAndGet()
                executionFinished.countDown()
            }
            override fun disposeWithoutMutation() { disposalCount.incrementAndGet() }
        }

        val eventThread = AtomicReference<Thread>()
        val owner = CaptureStateOwner(dispatch = { e ->
            val t = Thread { e.execute() }; eventThread.set(t); t.start()
            assertTrue(eventStarted.await(2, TimeUnit.SECONDS))
            error("dispatch threw late")
        })

        assertTrue(owner.post(event))
        assertEquals(0, disposalCount.get())
        allowEventToFinish.countDown()
        assertTrue(executionFinished.await(5, TimeUnit.SECONDS))
        eventThread.get()?.join(1_000)
        assertEquals(1, executionCount.get())
        assertEquals(0, disposalCount.get())
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
        assertEquals(0, owner.trackingSize())
    }

    @Test
    fun falseRacingPendingToCompletedReportsAccepted() {
        val event = TestEvent()
        val owner = CaptureStateOwner(dispatch = { it.execute(); false })
        assertTrue(owner.post(event))
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
        assertEquals(0, owner.trackingSize())
    }

    @Test
    fun throwRacingPendingToCompletedReportsAccepted() {
        val event = TestEvent()
        val owner = CaptureStateOwner(dispatch = { it.execute(); error("after") })
        assertTrue(owner.post(event))
        assertEquals(1, event.executions.get())
        assertEquals(0, event.disposals.get())
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
        assertEquals(0, owner.trackingSize())
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
        val executionDone = CountDownLatch(1)
        val event = BlockingTestEvent(started, block)
        val execThread = AtomicReference<Thread>()
        val owner = CaptureStateOwner(dispatch = { env ->
            val t = Thread { env.execute(); executionDone.countDown() }; execThread.set(t); t.start()
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
        assertTrue(executionDone.await(5, TimeUnit.SECONDS))
        execThread.get()?.join(2_000)
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

    // ======================== disposal-hook failure containment ========================

    // onDisposalFailure throws but close continues to drain remaining events
    @Test
    fun firstCloseDrainedEventThrowsDuringDisposalSecondStillDisposes() {
        val e1 = object : CaptureOwnerEvent {
            val disposals = AtomicInteger(0)
            override fun execute() {}
            override fun disposeWithoutMutation() { disposals.incrementAndGet(); error("disposal A failed") }
        }
        val e2 = object : CaptureOwnerEvent {
            val disposals = AtomicInteger(0)
            override fun execute() {}
            override fun disposeWithoutMutation() { disposals.incrementAndGet() }
        }
        val hookFailures = AtomicInteger(0)
        val internalFailures = AtomicInteger(0)
        val owner = CaptureStateOwner(
            dispatch = { true },
            onDisposalFailure = { _, _ -> hookFailures.incrementAndGet() },
            onOwnerInternalFailure = { _, _, _ -> internalFailures.incrementAndGet() }
        )
        assertTrue(owner.post(e1))
        assertTrue(owner.post(e2))
        owner.close()
        assertEquals(1, e1.disposals.get())
        assertEquals(1, e2.disposals.get())
        assertEquals(1, hookFailures.get())
        assertEquals(0, internalFailures.get())
        assertEquals(0, owner.trackingSize())
    }

    // onDisposalFailure throws and onOwnerInternalFailure catches it
    @Test
    fun onDisposalFailureThrowsInternalFailureReported() {
        val e = object : CaptureOwnerEvent {
            val disposals = AtomicInteger(0)
            override fun execute() {}
            override fun disposeWithoutMutation() { disposals.incrementAndGet(); error("X") }
        }
        val internalFailures = AtomicInteger(0)
        val owner = CaptureStateOwner(
            dispatch = { false },
            onDisposalFailure = { _, _ -> error("hook threw") },
            onOwnerInternalFailure = { _, _, _ -> internalFailures.incrementAndGet() }
        )
        assertFalse(owner.post(e))
        assertEquals(1, e.disposals.get())
        assertEquals(1, internalFailures.get())
        assertEquals(0, owner.trackingSize())
    }

    @Test
    fun postAfterCloseDisposerAndHookBothThrowPostReturnsFalse() {
        val e = object : CaptureOwnerEvent {
            val disposals = AtomicInteger(0)
            override fun execute() {}
            override fun disposeWithoutMutation() { disposals.incrementAndGet(); error("X") }
        }
        val internalFailures = AtomicInteger(0)
        val owner = CaptureStateOwner(
            dispatch = { true },
            onDisposalFailure = { _, _ -> error("hook threw") },
            onOwnerInternalFailure = { _, _, _ -> internalFailures.incrementAndGet() }
        )
        owner.close()
        assertFalse(owner.post(e))
        assertEquals(1, e.disposals.get())
        assertEquals(1, internalFailures.get())
    }

    // tracking empty after all disposal failure cases
    @Test
    fun trackingCleanAfterAllDisposalFailureVariants() {
        val internalFailures = AtomicInteger(0)
        val owner = CaptureStateOwner(
            dispatch = { true },
            onDisposalFailure = { _, _ -> error("hook threw") },
            onOwnerInternalFailure = { _, _, _ -> internalFailures.incrementAndGet() }
        )
        owner.close()
        assertFalse(owner.post(object : CaptureOwnerEvent {
            val disposals = AtomicInteger(0)
            override fun execute() {}
            override fun disposeWithoutMutation() { disposals.incrementAndGet(); error("X") }
        }))
        assertEquals(0, owner.pendingCount())
        assertEquals(0, owner.runningCount())
        assertEquals(0, owner.trackingSize())
        assertEquals(1, internalFailures.get())
    }

    }
