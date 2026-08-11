package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGenerationOwnerTest {
    @Test
    fun startWhileStoppingQueuesDesiredRestart() {
        val owner = PreviewGenerationOwner()
        val first = owner.start()
        assertNotNull(first)
        assertTrue(owner.markOpen(first!!))
        val stopGeneration = owner.stop()!!
        assertEquals(PreviewGenerationOwner.State.STOPPING, owner.snapshot().state)
        assertEquals(null, owner.start())
        assertTrue(owner.snapshot().desiredRunning)
        assertTrue(owner.finishStop(stopGeneration))
        val second = owner.start()
        assertNotNull(second)
        assertTrue(second!! > first)
    }

    @Test
    fun staleGenerationCannotBecomeOpen() {
        val owner = PreviewGenerationOwner()
        val first = owner.start()!!
        owner.stop()
        assertFalse(owner.markOpen(first))
        assertEquals(PreviewGenerationOwner.State.STOPPING, owner.snapshot().state)
    }

    @Test
    fun stopDuringStartingInvalidatesCallbacks() {
        val owner = PreviewGenerationOwner()
        val generation = owner.start()!!
        owner.stop()
        assertFalse(owner.accepts(generation))
    }

    @Test
    fun staleStopCompletionCannotStopReplacementGeneration() {
        val owner = PreviewGenerationOwner()
        val first = owner.start()!!
        val stopGeneration = owner.stop()!!
        assertTrue(owner.snapshot().desiredRunning.not())
        assertFalse(owner.finishStop(first))
        assertEquals(PreviewGenerationOwner.State.STOPPING, owner.snapshot().state)
        assertTrue(owner.finishStop(stopGeneration))
        assertEquals(PreviewGenerationOwner.State.STOPPED, owner.snapshot().state)
    }

    @Test
    fun failedGenerationCanBeStartedAgain() {
        val owner = PreviewGenerationOwner()
        val first = owner.start()!!
        assertTrue(owner.fail(first))
        assertFalse(owner.accepts(first))
        val second = owner.start()
        assertNotNull(second)
        assertTrue(second!! > first)
    }
}
