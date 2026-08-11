package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PreviewResourceSlotTest {
    private class Resource(val name: String)

    @Test
    fun sameInstanceIsAlreadyOwnedAndNotSettled() {
        val slot = PreviewResourceSlot<Resource>()
        val resource = Resource("authoritative")
        var settled = 0
        assertEquals(
            PreviewResourceAttachment.ACCEPTED,
            slot.attach(1L, 1L, resource) { settled++ }
        )
        assertEquals(
            PreviewResourceAttachment.ALREADY_OWNED,
            slot.attach(1L, 1L, resource) { settled++ }
        )
        assertEquals(0, settled)
        assertSame(resource, slot.detach(1L, resource))
    }

    @Test
    fun differentDuplicateIsSettledWithoutReplacingOwner() {
        val slot = PreviewResourceSlot<Resource>()
        val first = Resource("first")
        val duplicate = Resource("duplicate")
        var settled: Resource? = null
        slot.attach(2L, 2L, first) { }
        assertEquals(
            PreviewResourceAttachment.SETTLED_DUPLICATE,
            slot.attach(2L, 2L, duplicate) { settled = it }
        )
        assertSame(duplicate, settled)
        assertSame(first, slot.detach(2L, first))
    }

    @Test
    fun staleGenerationIsSettledAndCannotOccupySlot() {
        val slot = PreviewResourceSlot<Resource>()
        val stale = Resource("stale")
        var settled: Resource? = null
        assertEquals(
            PreviewResourceAttachment.SETTLED_STALE,
            slot.attach(1L, 2L, stale) { settled = it }
        )
        assertSame(stale, settled)
        assertEquals(null, slot.detach(2L, stale))
    }
}
