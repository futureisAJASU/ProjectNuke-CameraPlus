package com.projectnuke.keplernightlab

/**
 * Buffered-lifecycle state machine for YUV work items.
 *
 * States: RETAINED → ENCODING → SETTLING → (removed)  [encoding path]
 *          RETAINED → DRAINING → (removed)              [close-drain path]
 *
 * Contract:
 * - Only ENCODING items may startSettling.
 * - Only RETAINED items may startDraining.
 * - An item is removed from the active registry once it reaches RELEASED.
 * - startSettling on a previously removed (RELEASED) item returns UNKNOWN
 *   (the item is no longer in the active registry).
 * - While an item is SETTLING or DRAINING its ownership remains visible via
 *   settlingCount / drainingCount / trackedCount so cleanup-coordinator
 *   snapshots are truthful.
 */
internal open class YuvBufferedLifecycle {

    enum class State { RETAINED, ENCODING, SETTLING, DRAINING, RELEASED }

    enum class SettlementResult { STARTED, ALREADY_SETTLING, ALREADY_RELEASED, INVALID_STATE, UNKNOWN }

    enum class EncodingSettlementStatus {
        SETTLED,
        ALREADY_SETTLING,
        ALREADY_RELEASED,
        INVALID_STATE,
        UNKNOWN
    }

    /**
     * Result of one settlement attempt for a buffered work item.
     *
     * [lifecycleReleased] is truthful: it reflects the actual result of the
     * [finishSettling] attempt (never hardcoded).  If resource disposal and lifecycle
     * release BOTH fail, both failures are preserved: [failure] carries the disposal
     * failure and [lifecycleReleaseFailure] carries the release failure.
     */
    data class EncodingSettlementOutcome(
        val status: EncodingSettlementStatus,
        val previousState: State,
        val itemDisposed: Boolean,
        val lifecycleReleased: Boolean,
        val failure: Throwable? = null,
        val lifecycleReleaseFailure: Throwable? = null
    )

    private data class Entry(var state: State = State.RETAINED)

    private val lock = Any()
    private val items = linkedMapOf<YuvPngWorkItem, Entry>()
    private var closed = false

    fun isClosed(): Boolean = synchronized(lock) { closed }

    fun retainedCount(): Int = synchronized(lock) {
        items.count { it.value.state == State.RETAINED }
    }

    fun encodingCount(): Int = synchronized(lock) {
        items.count { it.value.state == State.ENCODING }
    }

    fun settlingCount(): Int = synchronized(lock) {
        items.count { it.value.state == State.SETTLING }
    }

    fun drainingCount(): Int = synchronized(lock) {
        items.count { it.value.state == State.DRAINING }
    }

    fun activeEncodingOwnershipCount(): Int = synchronized(lock) {
        items.count { it.value.state == State.ENCODING || it.value.state == State.SETTLING }
    }

    fun trackedCount(): Int = synchronized(lock) { items.size }

    fun tryRegister(item: YuvPngWorkItem): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        if (item in items) error("YUV work item already tracked by buffered lifecycle")
        items[item] = Entry()
        true
    }

    fun beginEncoding(item: YuvPngWorkItem): Boolean = synchronized(lock) {
        val entry = items[item] ?: return@synchronized false
        if (entry.state != State.RETAINED) return@synchronized false
        entry.state = State.ENCODING
        true
    }

    fun startSettling(item: YuvPngWorkItem): SettlementResult = synchronized(lock) {
        val entry = items[item] ?: return@synchronized SettlementResult.UNKNOWN
        when (entry.state) {
            State.ENCODING -> {
                entry.state = State.SETTLING
                SettlementResult.STARTED
            }
            State.SETTLING -> SettlementResult.ALREADY_SETTLING
            State.RELEASED -> SettlementResult.ALREADY_RELEASED
            State.RETAINED, State.DRAINING -> SettlementResult.INVALID_STATE
        }
    }

    /**
     * Removes a SETTLING item from the active registry.  Returns false (no throw) when
     * the item is not in SETTLING state, which is an invariant violation for the
     * normal settleEncoding flow.  Open for deterministic failure injection in tests.
     */
    internal open fun finishSettling(item: YuvPngWorkItem): Boolean = synchronized(lock) {
        val entry = items[item] ?: return@synchronized false
        if (entry.state != State.SETTLING) return@synchronized false
        items.remove(item)
        true
    }

    fun startDraining(item: YuvPngWorkItem): Boolean = synchronized(lock) {
        val entry = items[item] ?: return@synchronized false
        if (entry.state != State.RETAINED) return@synchronized false
        entry.state = State.DRAINING
        true
    }

    /**
     * Removes a DRAINING item from the active registry.  Returns false (no throw) when
     * the item is not in DRAINING state.  Open for deterministic failure injection in tests.
     */
    internal open fun finishDrain(item: YuvPngWorkItem): Boolean = synchronized(lock) {
        val entry = items[item] ?: return@synchronized false
        if (entry.state != State.DRAINING) return@synchronized false
        items.remove(item)
        true
    }

    fun settleEncoding(
        item: YuvPngWorkItem,
        accounting: YuvCaptureAccounting
    ): EncodingSettlementOutcome {
        val previous = startSettling(item)
        val prevState = when (previous) {
            SettlementResult.STARTED -> State.ENCODING
            SettlementResult.ALREADY_SETTLING -> State.SETTLING
            SettlementResult.ALREADY_RELEASED -> State.RELEASED
            SettlementResult.INVALID_STATE -> State.RETAINED
            SettlementResult.UNKNOWN -> State.RELEASED
        }
        when (previous) {
            SettlementResult.STARTED -> Unit
            SettlementResult.ALREADY_SETTLING -> return EncodingSettlementOutcome(
                status = EncodingSettlementStatus.ALREADY_SETTLING,
                previousState = prevState,
                itemDisposed = false,
                lifecycleReleased = false
            )
            SettlementResult.ALREADY_RELEASED -> return EncodingSettlementOutcome(
                status = EncodingSettlementStatus.ALREADY_RELEASED,
                previousState = prevState,
                itemDisposed = false,
                lifecycleReleased = true
            )
            SettlementResult.INVALID_STATE -> return EncodingSettlementOutcome(
                status = EncodingSettlementStatus.INVALID_STATE,
                previousState = prevState,
                itemDisposed = false,
                lifecycleReleased = false
            )
            SettlementResult.UNKNOWN -> return EncodingSettlementOutcome(
                status = EncodingSettlementStatus.UNKNOWN,
                previousState = prevState,
                itemDisposed = false,
                lifecycleReleased = false
            )
        }

        var failure: Throwable? = null
        var disposed = false
        try {
            item.dispose(accounting)
            disposed = true
        } catch (t: Throwable) {
            failure = t
        }

        var releaseFailure: Throwable? = null
        val released = try {
            if (finishSettling(item)) {
                true
            } else {
                releaseFailure = IllegalStateException(
                    "finishSettling returned false for frame ${item.frameIndex}: not in SETTLING state"
                )
                false
            }
        } catch (t: Throwable) {
            releaseFailure = t
            false
        }

        return EncodingSettlementOutcome(
            status = EncodingSettlementStatus.SETTLED,
            previousState = prevState,
            itemDisposed = disposed,
            lifecycleReleased = released,
            failure = failure,
            lifecycleReleaseFailure = releaseFailure
        )
    }

    /**
     * Claims RETAINED items as DRAINING (never ENCODING or SETTLING items) and marks
     * the lifecycle closed.  Open for deterministic failure injection in tests.
     */
    internal open fun closeAndDrainRetained(): List<YuvPngWorkItem> = synchronized(lock) {
        closed = true
        val drained = mutableListOf<YuvPngWorkItem>()
        for ((item, entry) in items) {
            if (entry.state == State.RETAINED) {
                entry.state = State.DRAINING
                drained.add(item)
            }
        }
        drained
    }

    fun snapshotRetainedByFrameIndex(): List<YuvPngWorkItem> = synchronized(lock) {
        items.entries
            .filter { it.value.state == State.RETAINED }
            .map { it.key }
            .sortedBy { it.frameIndex }
    }

}
