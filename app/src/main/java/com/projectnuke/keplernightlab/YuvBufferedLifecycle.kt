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
 *  - registration after closure fails and the caller must dispose the exact item (the item's
 *    own accounting token remains unsettled until that caller-side dispose, so no double-count),
 *  - cleanup can drain `RETAINED` items but must leave `ENCODING` items alone until the worker
 *    settles them in its `finally`,
 *  - a single owner settles each item exactly once: either [settleEncoding] (worker path) or
 *    [closeAndDrainRetained] + caller dispose (cleanup path), never both.
 *
 * Accounting ownership model (fixes Phase 1B double-settlement):
 *  - [createBufferedYuvWork] increments `bufferedFrames` via the item's constructor token at creation.
 *  - That token is settled by exactly one path:
 *      * ENCODING path: [settleEncoding] calls [YuvPngWorkItem.settleBufferedAccounting].
 *      * Cleanup path: [closeAndDrainRetained] drains RETAINED items; the caller then calls
 *        `item.dispose(accounting)` which settles the token for items the lifecycle never
 *        moved to ENCODING.
 *  - A close-raced [tryRegister] returns false without touching accounting; the caller-side
 *    `item.dispose(accounting)` settles the single token.
 */
internal class YuvBufferedLifecycle {

    enum class State { RETAINED, ENCODING, RELEASED }

    private data class Entry(val state: AtomicReference<State>)

    private val items = ConcurrentHashMap<YuvPngWorkItem, Entry>()
    private val closed = AtomicBoolean(false)

    fun isClosed(): Boolean = closed.get()

    fun retainedCount(): Int = items.size

    /**
     * Attempts to register [item] in `RETAINED` state.  The caller has already created the
     * work item (which owns its accounting token).  Registration is purely about lifecycle
     * tracking — it does NOT bump accounting again.  If the lifecycle is already closed (or
     * closes during registration) the function returns `false` and the caller MUST dispose
     * the exact item, which settles the single accounting token.
     */
    fun tryRegister(item: YuvPngWorkItem): Boolean {
        if (closed.get()) return false
        val entry = Entry(AtomicReference(State.RETAINED))
        val prior = items.putIfAbsent(item, entry)
        if (prior != null) {
            error("YUV work item already tracked by buffered lifecycle")
        }
        if (closed.get()) {
            // Close raced past the put. Remove atomically; either we win or closeAndDrainRetained did.
            if (items.remove(item) != null) {
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
     * lifecycle and settles its accounting token (reservation + buffered-frame counter)
     * exactly once.  [YuvPngWorkItem.settleBufferedAccounting] and [YuvPngWorkItem.dispose]
     * are each idempotent; this method is also safe to call multiple times.
     */
    fun settleEncoding(item: YuvPngWorkItem, accounting: YuvCaptureAccounting) {
        val entry = items.remove(item) ?: return
        entry.state.set(State.RELEASED)
        item.settleBufferedAccounting(accounting)
        item.dispose(accounting)
    }

    /**
     * Terminal cleanup. Marks the lifecycle closed and atomically drains every `RETAINED`
     * item.  `ENCODING` items are left alone: their encoders still own them and will settle
     * them via [settleEncoding] when they return.  The returned list contains exactly the
     * items cleanup must dispose itself; it is empty when no `RETAINED` items remained.
     * The caller is responsible for calling `item.dispose(accounting)` on every returned item
     * to settle their accounting tokens.
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
