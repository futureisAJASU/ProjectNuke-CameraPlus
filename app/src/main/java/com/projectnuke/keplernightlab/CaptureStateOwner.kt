package com.projectnuke.keplernightlab

internal interface CaptureOwnerEvent {
    fun execute()
    fun disposeWithoutMutation()
}

/**
 * Serial owner boundary. Camera callbacks and workers submit immutable
 * [CaptureOwnerEvent] values. The dispatcher serializes state mutation.
 *
 * Envelope state: PENDING -> RUNNING -> COMPLETED / DISPOSED.
 *
 * Contract:
 * - PENDING envelopes are drained to DISPOSED by close(); no body begins after.
 * - startGate() atomically PENDING->RUNNING iff owner is OPEN.
 * - If owner CLOSED when gate runs, the gate transitions PENDING->DISPOSED
 *   and the dispatch thread calls disposeWithoutMutation once.
 * - Close non-blocking: RUNNING envelopes are never drained by close().
 * - Completed and DISPOSED envelopes are removed from tracking.
 * - No event both executes and disposes.
 * - Exceptions still settle tracking in finally.
 */
internal class CaptureStateOwner(
    private val dispatch: (CaptureOwnerEvent) -> Boolean
) {

    internal enum class EventState { PENDING, RUNNING, COMPLETED, DISPOSED }
    internal enum class GateResult { STARTED, ALREADY_SETTLED, DISPOSED_BY_GATE }

    private class Envelope(
        val owner: CaptureStateOwner,
        val event: CaptureOwnerEvent
    ) : CaptureOwnerEvent {
        @Volatile var state: EventState = EventState.PENDING
        private val gate = Any()

        fun startGate(): GateResult = synchronized(gate) {
            when (state) {
                EventState.PENDING -> {
                    if (owner.isOpen()) {
                        state = EventState.RUNNING
                        GateResult.STARTED
                    } else {
                        // Draining raced past dispatch: the close drain finished
                        // before this envelope was queued. We perform the exactly-one
                        // disposal here because the drain never saw this envelope.
                        state = EventState.DISPOSED
                        owner.removeEnvelope(this)
                        GateResult.DISPOSED_BY_GATE
                    }
                }
                EventState.COMPLETED, EventState.DISPOSED, EventState.RUNNING ->
                    GateResult.ALREADY_SETTLED
            }
        }

        override fun execute() {
            when (startGate()) {
                GateResult.STARTED -> {
                    try { event.execute() } finally { complete() }
                }
                GateResult.DISPOSED_BY_GATE -> {
                    event.disposeWithoutMutation()
                }
                GateResult.ALREADY_SETTLED -> { /* noop */ }
            }
        }

        private fun complete() = synchronized(gate) {
            check(state == EventState.RUNNING) { "complete from $state" }
            state = EventState.COMPLETED
            owner.removeEnvelope(this)
        }

        override fun disposeWithoutMutation() {
            error("use owner drain; not called directly")
        }

        fun settleDisposed(): Boolean = synchronized(gate) {
            when (state) {
                EventState.COMPLETED, EventState.DISPOSED -> false
                else -> {
                    state = EventState.DISPOSED
                    owner.removeEnvelope(this)
                    true
                }
            }
        }
    }

    private val lock = Any()
    private val envelopes = arrayListOf<Envelope>()
    private var closed = false

    internal fun isOpen(): Boolean = synchronized(lock) { !closed }
    private fun removeEnvelope(env: Envelope) { synchronized(lock) { envelopes -= env } }

    fun post(event: CaptureOwnerEvent): Boolean {
        val env = synchronized(lock) {
            if (closed) return@synchronized null
            Envelope(this, event).also { envelopes += it }
        } ?: run { event.disposeWithoutMutation(); return false }

        return try {
            if (!dispatch(env)) {
                synchronized(lock) { envelopes -= env }
                if (env.settleDisposed()) event.disposeWithoutMutation()
                false
            } else {
                true
            }
        } catch (_: Throwable) {
            synchronized(lock) { envelopes -= env }
            if (env.settleDisposed()) event.disposeWithoutMutation()
            false
        }
    }

    fun close() {
        val drained = synchronized(lock) {
            if (closed) return
            closed = true
            val pending = envelopes.filter { it.state == EventState.PENDING }
            envelopes.clear()
            pending
        }
        drained.forEach { e ->
            if (e.settleDisposed()) e.event.disposeWithoutMutation()
        }
    }

    fun isClosed(): Boolean = synchronized(lock) { closed }
    internal fun pendingCount() = synchronized(lock) { envelopes.count { it.state == EventState.PENDING } }
    internal fun runningCount() = synchronized(lock) { envelopes.count { it.state == EventState.RUNNING } }
}