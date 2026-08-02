package com.projectnuke.keplernightlab

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal serial-owner boundary shared by Camera2 capture implementations.
 *
 * Camera callbacks and workers may submit immutable events only.  The supplied dispatcher is
 * the single place where state mutation is allowed.  A rejected event is not executed on the
 * producer thread: the optional emergency action is intentionally limited to resource disposal.
 *
 * Phase 1B introduces an [EventEnvelope] wrapper so every submitted event reaches exactly one
 * settlement: it executes exactly once before closure, or it is emergency-disposed exactly once
 * without executing its state mutation.  The wrapper guarantees that an accepted but not-yet-run
 * event cannot mutate state after the owner has been closed.
 *
 * This class is only the serial dispatch primitive in Phase 1B; it is not yet the authoritative
 * capture-state owner for any specific pipeline.
 */
internal class CaptureStateOwner(
    private val dispatch: (Runnable) -> Boolean,
    private val emergencyDispose: (Runnable) -> Unit = {}
) {
    private enum class EventState { PENDING, EXECUTED, DISPOSED }

    private class EventEnvelope(
        private val owner: CaptureStateOwner,
        private val event: Runnable,
        private val emergencyDispose: (Runnable) -> Unit
    ) : Runnable {
        private val state = AtomicReference(EventState.PENDING)

        override fun run() {
            if (state.compareAndSet(EventState.PENDING, EventState.EXECUTED)) {
                // An accepted but not-yet-run event must not mutate state after owner closure.
                if (owner.canExecute()) {
                    event.run()
                } else {
                    // Closure arrived before execution; fall back to disposal so nothing is lost.
                    emergencyDispose(event)
                }
            }
        }

        fun emergencyDispose() {
            if (state.compareAndSet(EventState.PENDING, EventState.DISPOSED)) {
                emergencyDispose(event)
            }
        }
    }

    private val closed = AtomicBoolean(false)
    private val closedForExecution = AtomicBoolean(false)
    private val pendingEnvelopes = ConcurrentHashMap.newKeySet<EventEnvelope>()

    fun post(event: Runnable): Boolean {
        if (closed.get()) {
            emergencyDispose(event)
            return false
        }
        val envelope = EventEnvelope(this, event, emergencyDispose)
        pendingEnvelopes.add(envelope)
        val accepted = try {
            dispatch(envelope)
        } catch (t: Throwable) {
            false
        }
        if (!accepted) {
            pendingEnvelopes.remove(envelope)
            envelope.emergencyDispose()
            return false
        }
        if (closed.get()) {
            // Close raced; the envelope may still be sitting in the handler queue.
            // We settle it here; if it has already executed, the envelope state ensures
            // its disposal path becomes a no-op and vice versa.
            pendingEnvelopes.remove(envelope)
            envelope.emergencyDispose()
            return false
        }
        return true
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        closedForExecution.set(true)
        // Drain every envelope accepted before closure. The envelope's CAS ensures each event
        // is either executed once or disposed once, never both, and never silently lost.
        val settled = pendingEnvelopes.toList()
        pendingEnvelopes.clear()
        settled.forEach { it.emergencyDispose() }
    }

    fun isClosed(): Boolean = closed.get()

    internal fun canExecute(): Boolean = !closedForExecution.get()
}
