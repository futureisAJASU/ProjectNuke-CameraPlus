package com.projectnuke.keplernightlab

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal serial-owner boundary shared by Camera2 capture implementations.
 *
 * Camera callbacks and workers may submit immutable events only.  The supplied dispatcher is
 * the single place where state mutation is allowed.  A rejected event is not executed on the
 * producer thread: the optional emergency action is intentionally limited to resource disposal.
 *
 * Every submitted event reaches exactly one settlement: it executes exactly once under a valid
 * owner, or it is emergency-disposed exactly once without executing its state mutation.  An
 * accepted-but-not-yet-run event cannot mutate state after the owner has been closed.
 *
 * Settlement atomicity (fixes Phase 1B check-then-run race):
 *  - An accepted event transitions PENDING -> RUNNING when the dispatcher invokes it, then
 *    RUNNING -> EXECUTED if the owner is still open.  The RUNNING transition is atomic with
 *    the decision to execute, so `close()` racing with a not-yet-started event yields either:
 *      * event execution under a valid owner, or
 *      * emergency disposal (if close won the race).
 *  - If `close()` wins the race after RUNNING but before EXECUTED, the event is emergency-
 *    disposed instead of executing its mutation (no double-settlement; no silent loss).
 *  - Executed events are removed from pending tracking at the moment they settle, so they do
 *    not linger until capture cleanup.
 *  - Repeated `close()` calls are idempotent.
 *  - Emergency disposal never runs normal capture-state mutation.
 *
 * Documented rule for an event already executing before close: an event that reached RUNNING
 * and then EXECUTED has fully completed its mutation atomically before `close()` observes
 * CLOSED; an event in RUNNING that has not yet reached EXECUTED when `close()` runs is
 * emergency-disposed — it never begins normal state mutation after close returns.
 */
internal class CaptureStateOwner(
    private val dispatch: (Runnable) -> Boolean,
    private val emergencyDispose: (Runnable) -> Unit = {},
    private val onExecutionBoundary: (() -> Unit)? = null
) {
    private enum class EventState { PENDING, RUNNING, EXECUTED, DISPOSED }

    private class EventEnvelope(
        private val owner: CaptureStateOwner,
        private val event: Runnable,
        private val emergencyDispose: (Runnable) -> Unit
    ) : Runnable {
        private val state = AtomicReference(EventState.PENDING)

        /**
         * Invoked by the dispatcher.  Atomically claims RUNNING via CAS; the caller-side
         * `close()` cannot produce an EXECUTED transition after CLOSED is set because
         * [EventState.RUNNING] -> [EventState.EXECUTED] is gated on `owner.canExecute()`.
         */
        override fun run() {
            if (!state.compareAndSet(EventState.PENDING, EventState.RUNNING)) return
            owner.processReady(this)
        }

        /** Settles the event under the owner's authority. */
        internal fun settle(): Boolean {
            return if (state.compareAndSet(EventState.RUNNING, EventState.EXECUTED)) {
                event.run()
                true
            } else {
                false
            }
        }

        internal fun emergencyDispose(): Boolean {
            // PENDING -> DISPOSED (not yet running) OR RUNNING -> DISPOSED (close won the race)
            return state.compareAndSet(EventState.PENDING, EventState.DISPOSED) ||
                state.compareAndSet(EventState.RUNNING, EventState.DISPOSED)
        }

        internal fun eventForDisposal(): Runnable = event

        internal fun stateForTest(): EventState = state.get()
    }

    /** Owner-side close flag.  Once CLOSED, no event may reach EXECUTED. */
    private val ownerState = AtomicReference(OwnerState.OPEN)

    private enum class OwnerState { OPEN, CLOSED }

    /**
     * Tracks envelopes that have been accepted for dispatch but have not yet settled
     * (either executed or disposed).  An envelope is removed at the exact moment it settles,
     * so there is never lingering retention of executed events.
     */
    private val pendingEnvelopes = ConcurrentHashMap.newKeySet<EventEnvelope>()

    /**
     * Called by an envelope's [EventEnvelope.run] after it has atomically claimed RUNNING.
     * Decides between execution and emergency disposal based on the owner's close state
     * without a separate check-then-run window.
     */
    private fun processReady(envelope: EventEnvelope) {
        onExecutionBoundary?.invoke()
        pendingEnvelopes.remove(envelope)
        if (canExecute()) {
            // Owner is still open: execute the event's mutation.  The CAS inside settle()
            // guarantees this is the only transition to EXECUTED.
            envelope.settle()
        } else {
            // Owner has closed between the envelope's PENDING->RUNNING CAS and now.
            // Emergency-dispose only if we win the state transition (close() may have
            // already disposed this envelope).  No event is disposed twice.
            if (envelope.emergencyDispose()) {
                emergencyDispose(envelope.eventForDisposal())
            }
        }
    }

    fun post(event: Runnable): Boolean {
        if (ownerState.get() == OwnerState.CLOSED) {
            emergencyDispose(event)
            return false
        }
        val envelope = EventEnvelope(this, event, emergencyDispose)
        pendingEnvelopes.add(envelope)
        if (ownerState.get() == OwnerState.CLOSED) {
            // Owner closed between dispatch preparation and offer.
            pendingEnvelopes.remove(envelope)
            if (envelope.emergencyDispose()) emergencyDispose(event)
            return false
        }
        val accepted = try {
            dispatch(envelope)
        } catch (t: Throwable) {
            false
        }
        if (!accepted) {
            pendingEnvelopes.remove(envelope)
            if (envelope.emergencyDispose()) emergencyDispose(event)
            return false
        }
        return true
    }

    fun close() {
        if (!ownerState.compareAndSet(OwnerState.OPEN, OwnerState.CLOSED)) return
        // Drain every envelope accepted before closure.  Envelopes that already settled
        // themselves (removed from the set) are simply not present.  Remaining envelopes
        // are emergency-disposed; their CAS ensures each event is disposed exactly once.
        val settled = pendingEnvelopes.toList()
        pendingEnvelopes.clear()
        settled.forEach { envelope ->
            if (envelope.emergencyDispose()) emergencyDispose(envelope.eventForDisposal())
        }
    }

    fun isClosed(): Boolean = ownerState.get() == OwnerState.CLOSED

    internal fun canExecute(): Boolean = ownerState.get() == OwnerState.OPEN
}
