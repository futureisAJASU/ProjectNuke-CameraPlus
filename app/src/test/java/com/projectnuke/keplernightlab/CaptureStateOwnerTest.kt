package com.projectnuke.keplernightlab

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
}
