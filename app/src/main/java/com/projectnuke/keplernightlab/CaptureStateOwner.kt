package com.projectnuke.keplernightlab

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Resource-aware event envelope.  Every accepted event owns its exact resources and commits
 * to exactly one settlement path: [execute] (owner-open path) or [disposeWithoutMutation]
 * (emergency / close path).  No event both executes and disposes.
 */
internal interface CaptureOwnerEvent {
    fun execute()
    fun disposeWithoutMutation()
}

/**
 * Single serial-owner boundary shared by Camera2 capture implementations.
 *
 * Camera callbacks and workers submit immutable [CaptureOwnerEvent] values only.
 * The supplied dispatcher is the single place where state mutation is allowed.
 *
 * Every submitted event reaches exactly one settlement:
 *  - PENDING events are transitioned to DISPOSED by [close] and their
 *    [CaptureOwnerEvent.disposeWithoutMutation] is called (no state mutation).
 *  - An event that transitions PENDING -> RUNNING in [EventEnvelope.execute]
 *    either runs [CaptureOwnerEvent.execute] (owner still OPEN) or calls
 *    [CaptureOwnerEvent.disposeWithoutMutation] (owner CLOSED by the time the
 *    dispatcher picks it up).  No event both executes and disposes.
 *
 * Envelope state machine:
 *
 *     post ──> PENDING ──(close)──> DISPOSED   (disposed)
 *                ||                        |
 *                || (execute)              |
 *                \/                        |
 *             RUNNING --(close race)--> DISPOSED
 *                ||    (dispatch runs
 *                ||     after CLOSED)
 *                ↓
 *             COMPLETED
 *             (execute ran)
 *
 * The PENDING -> RUNNING transition is atomic via CAS inside [EventEnvelope.execute].
 * close() only disposes PENDING envelope; RUNNING envelopes already committed to
 * `processReady` continue but will observe a closed order and dispose without mutation.
 *
 * Requirements satisfied:
 *  - Not-yet-started event may not begin after close() returns.
 *  - close() racing event start yields exactly one result:
 *    event was marked RUNNING before close → may finish normal execute path
 *    event was not RUNNING → disposed and never starts.
 *  - Running events are gated inside processReady; close does NOT block for them.
 *  - Completed envelopes are removed from tracking.
 *  - No event both executes and disposes (CAS guarantee).
 *  - No event is silently lost.
 *  - Repeated close is idempotent.
 *  - Resource-aware: every accepted event owns its exact resources; dispose
 *    disposes exactly those resources and never runs state mutation.
 */
internal class CaptureStateOwner(
    private val dispatch: (CaptureOwnerEvent) -> Boolean,
    private val onExecutionBoundary: (() -> Unit)? = null
) {
    private enum class EventState { PENDING, RUNNING, COMPLETED, DISPOSED }
    private enum class OwnerState { OPEN, CLOSED }

    private class EventEnvelope(
        private val owner: CaptureStateOwner,
        private val event: CaptureOwnerEvent
    ) : CaptureOwnerEvent {
        private val state = AtomicReference(EventState.PENDING)

        /**
         * Called by the dispatcher thread to start the envelope.  Atomically claims
         * RUNNING; the CAS itself acts as the start gate — if close() already drained
         * this envelope, the CAS fails and the execute() body is never entered.
         */
        override fun execute() {
            if (state.compareAndSet(EventState.PENDING, EventState.RUNNING)) {
                owner.processReady(this)
            }
        }

        override fun disposeWithoutMutation() {
            // close() drains PENDING envelopes; the boundary hook drains RUNNING
            // envelopes that paused before the owner-open check.
            if (state.compareAndSet(EventState.PENDING, EventState.DISPOSED) ||
                state.compareAndSet(EventState.RUNNING, EventState.DISPOSED)) {
                owner.removeEnvelope(this)
                event.disposeWithoutMutation()
            }
        }

        /**
         * Settles the envelope under a valid open owner.  Must only be called
         * after processReady confirmed the owner is still OPEN.
         */
        internal fun settle() {
            if (state.compareAndSet(EventState.RUNNING, EventState.COMPLETED)) {
                owner.removeEnvelope(this)
                event.execute()
            }
        }

        internal fun stateForTest(): EventState = state.get()
    }

    private val ownerState = AtomicReference(OwnerState.OPEN)
    private val pendingEnvelopes = ConcurrentHashMap.newKeySet<EventEnvelope>()

    private fun removeEnvelope(envelope: EventEnvelope) {
        pendingEnvelopes.remove(envelope)
    }

    /**
     * Called by [EventEnvelope.execute] after it has atomically claimed RUNNING.
     * The onExecutionBoundary hook is invoked for test determinism; the owner-open
     * check occurs after the boundary returns so a paused hook can still race with
     * close().
     */
    private fun processReady(envelope: EventEnvelope) {
        onExecutionBoundary?.invoke()
        if (ownerState.get() == OwnerState.OPEN) {
            envelope.settle()
        } else {
            envelope.disposeWithoutMutation()
        }
    }

    fun post(event: CaptureOwnerEvent): Boolean {
        if (ownerState.get() == OwnerState.CLOSED) {
            event.disposeWithoutMutation()
            return false
        }
        val envelope = EventEnvelope(this, event)
        pendingEnvelopes.add(envelope)
        if (ownerState.get() == OwnerState.CLOSED) {
            pendingEnvelopes.remove(envelope)
            envelope.disposeWithoutMutation()
            return false
        }
        val accepted = try {
            dispatch(envelope)
        } catch (_: Throwable) {
            false
        }
        if (!accepted) {
            pendingEnvelopes.remove(envelope)
            envelope.disposeWithoutMutation()
            return false
        }
        return true
    }

    fun close() {
        if (!ownerState.compareAndSet(OwnerState.OPEN, OwnerState.CLOSED)) return
        val drained = pendingEnvelopes.toList()
        pendingEnvelopes.clear()
        drained.forEach { envelope ->
            envelope.disposeWithoutMutation()
        }
    }

    fun isClosed(): Boolean = ownerState.get() == OwnerState.CLOSED

    internal fun canExecute(): Boolean = ownerState.get() == OwnerState.OPEN
}