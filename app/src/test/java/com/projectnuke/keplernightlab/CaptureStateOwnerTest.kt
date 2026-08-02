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
    @Test
    fun rejectedOwnerEventUsesEmergencyDisposalWithoutRunningStateMutationOnProducer() {
        val mutations = AtomicInteger(0)
        val disposals = AtomicInteger(0)
        val owner = CaptureStateOwner(dispatch = { false }, emergencyDispose = { disposals.incrementAndGet() })

        assertFalse(owner.post(Runnable { mutations.incrementAndGet() }))
        assertEquals(0, mutations.get())
        assertEquals(1, disposals.get())
    }

    @Test
    fun closedOwnerRejectsLaterEvents() {
        val owner = CaptureStateOwner(dispatch = { true })
        owner.close()
        assertFalse(owner.post(Runnable { error("must not run") }))
    }

    @Test
    fun acceptedOwnerEventIsDispatchedExactlyOnce() {
        val events = mutableListOf<Runnable>()
        val owner = CaptureStateOwner(dispatch = { events += it; true })
        val mutations = AtomicInteger(0)
        assertTrue(owner.post(Runnable { mutations.incrementAndGet() }))
        assertEquals(1, events.size)
        events.single().run()
        assertEquals(1, mutations.get())
    }

    // -------------------------------------------------------------------------------------
    // Phase 2A hardened envelope semantics: every event settles exactly once with no
    // check-then-run window.
    // -------------------------------------------------------------------------------------

    @Test
    fun postAfterCloseIsDisposedExactlyOnce() {
        val disposals = AtomicInteger(0)
        val mutations = AtomicInteger(0)
        val owner = CaptureStateOwner(dispatch = { true }, emergencyDispose = { disposals.incrementAndGet() })
        owner.close()

        assertFalse(owner.post(Runnable { mutations.incrementAndGet() }))
        assertFalse(owner.post(Runnable { mutations.incrementAndGet() }))
        assertEquals(0, mutations.get())
        assertEquals(2, disposals.get())
    }

    @Test
    fun acceptedPendingEventCannotMutateStateAfterClose() {
        val disposals = AtomicInteger(0)
        val mutations = AtomicInteger(0)
        val queue = LinkedBlockingQueue<Runnable>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true }, emergencyDispose = { disposals.incrementAndGet() })

        assertTrue(owner.post(Runnable { mutations.incrementAndGet() }))
        assertEquals(1, queue.size)
        owner.close()
        // The handler finally runs the previously accepted event after closure.
        queue.poll()?.run()
        assertEquals(0, mutations.get())
        assertEquals(1, disposals.get())
    }

    @Test
    fun closeRacingPostYieldsExactlyOneSettlementPerEvent() {
        val disposals = AtomicInteger(0)
        val mutations = AtomicInteger(0)
        val queue = LinkedBlockingQueue<Runnable>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true }, emergencyDispose = { disposals.incrementAndGet() })

        val start = CountDownLatch(2)
        val done = CountDownLatch(2)
        val posts = 200
        val poster = Thread {
            start.countDown()
            start.await(5, TimeUnit.SECONDS)
            repeat(posts) { owner.post(Runnable { mutations.incrementAndGet() }) }
            done.countDown()
        }
        val closer = Thread {
            start.countDown()
            start.await(5, TimeUnit.SECONDS)
            owner.close()
            done.countDown()
        }
        poster.start()
        closer.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        poster.join(5_000)
        closer.join(5_000)

        // Drain whatever the handler never ran; no event may mutate after close.
        while (true) {
            val envelope = queue.poll() ?: break
            envelope.run()
        }

        assertEquals(posts, mutations.get() + disposals.get())
        assertTrue("mutations=$mutations must be <= posts=$posts", mutations.get() <= posts)
    }

    @Test
    fun eventNeverBothExecutesAndDisposes() {
        val disposals = AtomicInteger(0)
        val mutations = AtomicInteger(0)
        val queue = LinkedBlockingQueue<Runnable>()
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true }, emergencyDispose = { disposals.incrementAndGet() })

        assertTrue(owner.post(Runnable { mutations.incrementAndGet() }))
        queue.poll()?.run()
        assertEquals(1, mutations.get())
        assertEquals(0, disposals.get())

        owner.close()
        assertEquals(1, mutations.get())
        assertEquals(0, disposals.get())
    }

    @Test
    fun multipleCloseCallsRemainIdempotent() {
        val disposals = AtomicInteger(0)
        val mutations = AtomicInteger(0)
        val owner = CaptureStateOwner(dispatch = { true }, emergencyDispose = { disposals.incrementAndGet() })

        assertTrue(owner.post(Runnable { mutations.incrementAndGet() }))
        owner.close()
        owner.close()
        owner.close()
        assertEquals(0, mutations.get())
        assertEquals(1, disposals.get())
        assertFalse(owner.post(Runnable { mutations.incrementAndGet() }))
        assertEquals(2, disposals.get())
    }

    @Test
    fun handlerDispatchRejectionDisposesOnceWithoutStateMutation() {
        val disposals = AtomicInteger(0)
        val mutations = AtomicInteger(0)
        var calls = 0
        val owner = CaptureStateOwner(dispatch = {
            calls++
            false
        }, emergencyDispose = { disposals.incrementAndGet() })

        assertFalse(owner.post(Runnable { mutations.incrementAndGet() }))
        assertEquals(1, calls)
        assertEquals(0, mutations.get())
        assertEquals(1, disposals.get())
    }

    /**
     * Phase 2A: An event paused at the execution boundary must not begin mutation after
     * close() returns.  This test posts an event, runs it on a dispatcher thread (claiming
     * PENDING -> RUNNING), blocks inside onExecutionBoundary before the settle decision,
     * then calls close().  When the boundary hook releases, the envelope must emergency-
     * dispose rather than execute its mutation.
     */
    @Test
    fun eventPausedAtExecutionBoundaryCannotBeginMutationAfterCloseReturns() {
        val disposals = AtomicInteger(0)
        val mutations = AtomicInteger(0)
        val queue = LinkedBlockingQueue<Runnable>()
        val atBoundary = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = CaptureStateOwner(
            dispatch = { queue.offer(it); true },
            emergencyDispose = { disposals.incrementAndGet() },
            onExecutionBoundary = {
                atBoundary.countDown()
                // Block here: the envelope has claimed RUNNING but not yet settled.
                release.await(2, TimeUnit.SECONDS)
            }
        )

        assertTrue(owner.post(Runnable { mutations.incrementAndGet() }))
        // Start a dispatcher thread that will run the envelope (claiming PENDING -> RUNNING)
        // and block at the execution boundary inside onExecutionBoundary.
        val dispatcher = Thread {
            queue.poll()?.run()
        }
        dispatcher.start()
        assertTrue(atBoundary.await(2, TimeUnit.SECONDS))
        // The envelope is now in RUNNING state, paused in processReady before the canExecute check.
        owner.close()
        // close() has returned.  The event must not proceed to mutation.
        release.countDown()
        dispatcher.join(5_000)

        assertEquals(0, mutations.get())
        assertEquals(1, disposals.get())
    }

    /**
     * Phase 2A: Successfully executed events are removed from pending tracking without
     * waiting for close(); close() finds nothing to drain and does not dispose them.
     */
    @Test
    fun executedEventIsRemovedFromPendingTrackingWithoutWaitingForClose() {
        val disposals = AtomicInteger(0)
        val queue = LinkedBlockingQueue<Runnable>()
        val owner = CaptureStateOwner(
            dispatch = { queue.offer(it); true },
            emergencyDispose = { disposals.incrementAndGet() }
        )

        assertTrue(owner.post(Runnable { }))
        queue.poll()?.run()
        // After execution, the envelope is EXECUTED and already removed from pending.
        owner.close()
        // close() should drain zero pending envelopes (the event already settled itself),
        // so emergencyDispose is never called.
        assertEquals(0, disposals.get())
    }
}
