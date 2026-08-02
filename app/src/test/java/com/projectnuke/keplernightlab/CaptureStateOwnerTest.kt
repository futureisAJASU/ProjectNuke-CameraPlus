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
        val owner = CaptureStateOwner(dispatch = { false }) { disposals.incrementAndGet() }

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
    // Phase 1B envelope semantics: every event settles exactly once.
    // -------------------------------------------------------------------------------------

    @Test
    fun postAfterCloseIsDisposedExactlyOnce() {
        val disposals = AtomicInteger(0)
        val mutations = AtomicInteger(0)
        val owner = CaptureStateOwner(dispatch = { true }) { disposals.incrementAndGet() }
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
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true }) { disposals.incrementAndGet() }

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
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true }) { disposals.incrementAndGet() }

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
        val owner = CaptureStateOwner(dispatch = { queue.offer(it); true }) { disposals.incrementAndGet() }

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
        val owner = CaptureStateOwner(dispatch = { true }) { disposals.incrementAndGet() }

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
        }) { disposals.incrementAndGet() }

        assertFalse(owner.post(Runnable { mutations.incrementAndGet() }))
        assertEquals(1, calls)
        assertEquals(0, mutations.get())
        assertEquals(1, disposals.get())
    }
}
