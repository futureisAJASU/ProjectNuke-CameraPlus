package com.projectnuke.keplernightlab

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single production lifecycle for buffered YUV work items retained after [createBufferedYuvWork].
 *
 * Replaces the prior pair of `[bufferedFrames]` list and `retainedBufferedWork` set used by the
 * YUV capture worker and terminal cleanup. The lifecycle is the only authoritative collection;
 * state transitions are atomic so that:
 *
 *  - an item registers before it can remain retained,
 *  - registration after closure fails and the caller must dispose the exact item,
 *  - cleanup can drain `RETAINED` items but must leave `ENCODING` items alone until the worker
 *    settles them in its `finally`,
 *  - the buffered-frame and retained-byte counters (in [YuvCaptureAccounting] and
 *    [YuvBufferReservations]) move in lockstep with state transitions, so they never reach zero
 *    while an encoder still owns the copied frame.
 */
internal class YuvBufferedLifecycle {

    enum class State { RETAINED, ENCODING, RELEASED }

    private data class Entry(val state: AtomicReference<State>)

    private val items = ConcurrentHashMap<YuvPngWorkItem, Entry>()
    private val closed = AtomicBoolean(false)

    fun isClosed(): Boolean = closed.get()

    fun retainedCount(): Int = items.size

    /**
     * Attempts to register [item] in `RETAINED` state. If the lifecycle is already closed (or
     * closes during registration) the function returns `false` and the caller MUST dispose the
     * exact item; no accounting side effect is left behind.
     */
    fun tryRegister(item: YuvPngWorkItem, accounting: YuvCaptureAccounting): Boolean {
        if (closed.get()) return false
        val entry = Entry(AtomicReference(State.RETAINED))
        val prior = items.putIfAbsent(item, entry)
        if (prior != null) {
            error("YUV work item already tracked by buffered lifecycle")
        }
        accounting.bufferedFrame()
        if (closed.get()) {
            // Close raced past the put. Remove atomically; either we win or closeAndDrainRetained did.
            if (items.remove(item) != null) {
                accounting.releasedBufferedFrame()
                return false
            }
            return false
        }
        return true
    }

    /**
     * Transitions the item from `RETAINED` to `ENCODING` so cleanup can observe ownership.
     * Returns `false` if the item is not tracked or has already been removed by cleanup.
     */
    fun beginEncoding(item: YuvPngWorkItem): Boolean {
        val entry = items[item] ?: return false
        return entry.state.compareAndSet(State.RETAINED, State.ENCODING)
    }

    /**
     * Called by the encoder in its `finally` block. Atomically removes the item from the
     * lifecycle and invokes [item].dispose, which releases the reservation and adjusts
     * accounting. Exactly one invocation per item has any effect; subsequent calls are no-ops.
     */
    fun settleEncoding(item: YuvPngWorkItem, accounting: YuvCaptureAccounting) {
        val entry = items.remove(item) ?: return
        entry.state.set(State.RELEASED)
        item.dispose(accounting)
    }

    /**
     * Terminal cleanup. Marks the lifecycle closed and atomically drains every `RETAINED` item.
     * `ENCODING` items are left alone: their encoders still own them and will dispose them
     * via [settleEncoding] when they return. The returned list contains exactly the items
     * cleanup must dispose itself; it is empty when no `RETAINED` items remained. The caller is
     * responsible for calling `item.dispose(accounting)` on every returned item.
     */
    fun closeAndDrainRetained(): List<YuvPngWorkItem> {
        closed.set(true)
        val drained = mutableListOf<YuvPngWorkItem>()
        val iter = items.entries.iterator()
        while (iter.hasNext()) {
            val (item, entry) = iter.next()
            if (entry.state.compareAndSet(State.RETAINED, State.RELEASED)) {
                iter.remove()
                drained.add(item)
            }
        }
        return drained
    }

    /**
     * Returns the currently `RETAINED` items sorted by `frameIndex` for ordered flush. Items
     * concurrently moved to `ENCODING` are not included. The list is a snapshot; the caller
     * must still respect per-item [beginEncoding] / [settleEncoding] state transitions.
     */
    fun snapshotRetainedByFrameIndex(): List<YuvPngWorkItem> {
        if (items.isEmpty()) return emptyList()
        return items.entries
            .asSequence()
            .filter { it.value.state.get() == State.RETAINED }
            .map { it.key }
            .sortedBy { it.frameIndex }
            .toList()
    }
}
