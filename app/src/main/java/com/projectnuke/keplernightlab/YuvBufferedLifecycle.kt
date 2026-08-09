package com.projectnuke.keplernightlab

import java.util.concurrent.atomic.AtomicReference

/**
 * Buffered-lifecycle state machine for YUV work items.
 *
 * States: RETAINED → ENCODING → SETTLING → (removed)  [encoding path]
 *          RETAINED → DRAINING → (removed)              [coordinated drain path]
 *
 * The coordinated drain API closes acceptance and atomically claims
 *   every RETAINED item as DRAINING, returning [YuvDrainClaim] tokens.  The item
 *   stays tracked (drainingCount / trackedCount stay truthful) until
 *   `claim.disposeAndFinish(accounting)` disposes the item and removes it from the
 *   registry in one exactly-once, disposal-aware settlement.
 *
 * Contract:
 * - Only ENCODING items may startSettling.
 * - startSettling reports the ACTUAL previous lifecycle state atomically
 *   ([SettlementStart.previousState]); an unknown/removed item reports `null`,
 *   never a synthesized RELEASED state.
 * - An item is removed from the active registry once its settlement or drain
 *   finishes.
 * - While an item is SETTLING or DRAINING its ownership remains visible via
 *   settlingCount / drainingCount / trackedCount so cleanup-coordinator
 *   snapshots are truthful.
 */
internal open class YuvBufferedLifecycle {

    enum class State { RETAINED, ENCODING, SETTLING, DRAINING, RELEASED }

    enum class SettlementResult { STARTED, ALREADY_SETTLING, ALREADY_RELEASED, INVALID_STATE, UNKNOWN }

    /**
     * Atomic settlement-start decision: the settlement [result] together with the
     * actual [previousState] observed under the lifecycle lock.  [previousState] is
     * `null` only for unknown/removed items.
     */
    data class SettlementStart(
        val result: SettlementResult,
        val previousState: State?
    )

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
     * [previousState] is the actual lifecycle state observed before settlement
     * (`null` for unknown/removed items — never a synthesized RELEASED).
     * [lifecycleReleased] is truthful: it reflects the actual result of the
     * [finishSettling] attempt (never hardcoded).  If resource disposal and lifecycle
     * release BOTH fail, both failures are preserved: [failure] carries the disposal
     * failure and [lifecycleReleaseFailure] carries the release failure.
     */
    data class EncodingSettlementOutcome(
        val status: EncodingSettlementStatus,
        val previousState: State?,
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

    /** Current state of a tracked item, or `null` when it is no longer tracked. */
    internal fun stateOf(item: YuvPngWorkItem): State? = synchronized(lock) { items[item]?.state }

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

    /**
     * Starts settlement of an ENCODING item (ENCODING → SETTLING) and reports the
     * actual previous lifecycle state atomically:
     * - ENCODING → STARTED, previousState=ENCODING
     * - SETTLING → ALREADY_SETTLING, previousState=SETTLING
     * - RETAINED → INVALID_STATE, previousState=RETAINED
     * - DRAINING → INVALID_STATE, previousState=DRAINING
     * - unknown/removed → UNKNOWN, previousState=null
     */
    fun startSettling(item: YuvPngWorkItem): SettlementStart = synchronized(lock) {
        val entry = items[item] ?: return@synchronized SettlementStart(SettlementResult.UNKNOWN, null)
        val prev = entry.state
        when (entry.state) {
            State.ENCODING -> {
                entry.state = State.SETTLING
                SettlementStart(SettlementResult.STARTED, prev)
            }
            State.SETTLING -> SettlementStart(SettlementResult.ALREADY_SETTLING, prev)
            State.RELEASED -> SettlementStart(SettlementResult.ALREADY_RELEASED, prev)
            State.RETAINED, State.DRAINING -> SettlementStart(SettlementResult.INVALID_STATE, prev)
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
        val start = startSettling(item)
        when (start.result) {
            SettlementResult.STARTED -> Unit
            SettlementResult.ALREADY_SETTLING -> return EncodingSettlementOutcome(
                status = EncodingSettlementStatus.ALREADY_SETTLING,
                previousState = start.previousState,
                itemDisposed = false,
                lifecycleReleased = false
            )
            SettlementResult.ALREADY_RELEASED -> return EncodingSettlementOutcome(
                status = EncodingSettlementStatus.ALREADY_RELEASED,
                previousState = start.previousState,
                itemDisposed = false,
                lifecycleReleased = true
            )
            SettlementResult.INVALID_STATE -> return EncodingSettlementOutcome(
                status = EncodingSettlementStatus.INVALID_STATE,
                previousState = start.previousState,
                itemDisposed = false,
                lifecycleReleased = false
            )
            SettlementResult.UNKNOWN -> return EncodingSettlementOutcome(
                status = EncodingSettlementStatus.UNKNOWN,
                previousState = start.previousState,
                itemDisposed = false,
                lifecycleReleased = false
            )
        }

        var failure: Throwable? = null
        var disposed = false
        // dispose() reports an explicit YuvWorkDisposalOutcome and never throws: a
        // sub-settlement failure (source/reservation/observer) is preserved in the
        // outcome while every other sub-settlement still runs.
        val disposal = item.dispose(accounting)
        disposed = disposal.isClean
        failure = disposal.failures().firstOrNull()

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
            previousState = start.previousState,
            itemDisposed = disposed,
            lifecycleReleased = released,
            failure = failure,
            lifecycleReleaseFailure = releaseFailure
        )
    }

    /**
     * COORDINATED DRAIN API (used only by YuvCleanupCoordinator):
     *
     * Closes lifecycle acceptance and atomically claims every RETAINED item as
     * DRAINING (never ENCODING or SETTLING items).  Each returned [YuvDrainClaim]
     * keeps its item tracked and exposes the exactly-once [YuvDrainClaim.finish]
     * settlement capability: finish success removes the item from the registry;
     * finish failure leaves it DRAINING and the cleanup debt stays observable via
     * drainingCount / trackedCount.  New registration is rejected after closure.
     *
     * Open for deterministic failure injection in tests.
     */
    internal open fun claimRetainedForDrain(): List<YuvDrainClaim> = synchronized(lock) {
        closed = true
        val claims = mutableListOf<YuvDrainClaim>()
        for ((item, entry) in items) {
            if (entry.state == State.RETAINED) {
                entry.state = State.DRAINING
                claims.add(YuvDrainClaim(item, this))
            }
        }
        claims
    }

    fun snapshotRetainedByFrameIndex(): List<YuvPngWorkItem> = synchronized(lock) {
        items.entries
            .filter { it.value.state == State.RETAINED }
            .map { it.key }
            .sortedBy { it.frameIndex }
    }

}

/**
 * Exactly-once coordinated drain settlement capability.
 *
 * Created by [YuvBufferedLifecycle.claimRetainedForDrain]: the claimed [item] is
 * DRAINING and remains tracked until [disposeAndFinish] succeeds.  Settlement is
 * disposal-aware:
 * - [disposeAndFinish] disposes the item first and only then attempts the lifecycle
 *   release; there is no way to finish a claim before disposal was attempted.
 * - Exactly-one settlement: a second [disposeAndFinish] returns ALREADY_SETTLED /
 *   ALREADY_FAILED / ALREADY_DISPOSING and performs nothing.
 * - On FAILED settlement the item remains DRAINING and the cleanup debt stays
 *   observable via the lifecycle draining/tracked counts; the outcome preserves the
 *   item-disposal [YuvWorkDisposalOutcome] and any lifecycle release failure.
 */
internal enum class DrainSettlementStatus {
    SETTLED, FAILED, ALREADY_SETTLED, ALREADY_FAILED, ALREADY_DISPOSING
}

internal class DrainSettlementOutcome(
    val status: DrainSettlementStatus,
    val disposal: YuvWorkDisposalOutcome,
    val lifecycleReleased: Boolean,
    val lifecycleReleaseFailure: Throwable? = null
)

internal class YuvDrainClaim(
    val item: YuvPngWorkItem,
    private val lifecycle: YuvBufferedLifecycle
) {
    enum class State { CLAIMED, DISPOSING, SETTLED, FAILED }

    val frameIndex: Int = item.frameIndex

    private val state = AtomicReference(State.CLAIMED)

    fun claimState(): State = state.get()

    /** Current lifecycle state of the claimed item, or `null` once finished/removed. */
    fun lifecycleState(): YuvBufferedLifecycle.State? = lifecycle.stateOf(item)

    /**
     * Disposal-aware exactly-once settlement:
     *
     * - A coordinated buffered claim REQUIRES non-null accounting: without it the
     *   reservation/accounting could never settle, so the claim fails BEFORE any
     *   disposal is started and the item remains DRAINING.
     * - An unclean item disposal NEVER calls finishDrain: the claim fails, the item
     *   remains DRAINING and the cleanup debt stays observable.
     * - Only a clean disposal proceeds to finishDrain: success -> SETTLED (item
     *   removed); finish failure -> FAILED (item remains DRAINING).
     * - Exactly-one settlement: concurrent/repeated callers observe the settled state
     *   and perform nothing; repeated outcomes preserve the original disposal
     *   diagnostics via [YuvWorkDisposalOutcome.originalOutcome].
     */
    fun disposeAndFinish(accounting: YuvCaptureAccounting?): DrainSettlementOutcome {
        if (!state.compareAndSet(State.CLAIMED, State.DISPOSING)) {
            val first = item.disposalOutcome()
            val disposal = if (first == null) YuvWorkDisposalOutcome.notAttempted()
            else YuvWorkDisposalOutcome.alreadySettled(first)
            return when (state.get()) {
                State.SETTLED -> DrainSettlementOutcome(
                    DrainSettlementStatus.ALREADY_SETTLED, disposal, lifecycleReleased = true
                )
                State.FAILED -> DrainSettlementOutcome(
                    DrainSettlementStatus.ALREADY_FAILED, disposal, lifecycleReleased = false
                )
                else -> DrainSettlementOutcome(
                    DrainSettlementStatus.ALREADY_DISPOSING, disposal, lifecycleReleased = false
                )
            }
        }
        if (accounting == null && item.isBuffered) {
            state.set(State.FAILED)
            return DrainSettlementOutcome(
                DrainSettlementStatus.FAILED,
                YuvWorkDisposalOutcome.notAttempted(),
                lifecycleReleased = false,
                lifecycleReleaseFailure = IllegalStateException(
                    "buffered coordinated drain requires accounting (frame $frameIndex)"
                )
            )
        }
        val disposal = item.dispose(accounting)
        if (!disposal.isClean) {
            // Unclean disposal: finishDrain must NOT run; the item remains DRAINING.
            state.set(State.FAILED)
            return DrainSettlementOutcome(
                DrainSettlementStatus.FAILED, disposal, lifecycleReleased = false
            )
        }
        val released = try {
            lifecycle.finishDrain(item)
        } catch (t: Throwable) {
            state.set(State.FAILED)
            return DrainSettlementOutcome(
                DrainSettlementStatus.FAILED, disposal, lifecycleReleased = false, lifecycleReleaseFailure = t
            )
        }
        if (!released) {
            state.set(State.FAILED)
            return DrainSettlementOutcome(
                DrainSettlementStatus.FAILED, disposal, lifecycleReleased = false,
                lifecycleReleaseFailure = IllegalStateException(
                    "finishDrain returned false for frame $frameIndex: item not in DRAINING state"
                )
            )
        }
        state.set(State.SETTLED)
        return DrainSettlementOutcome(
            DrainSettlementStatus.SETTLED, disposal, lifecycleReleased = true
        )
    }
}
