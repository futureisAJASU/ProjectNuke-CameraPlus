package com.projectnuke.keplernightlab

internal interface CaptureOwnerEvent {
    fun execute()
    fun disposeWithoutMutation()
}

internal enum class GateResult { STARTED, ALREADY_SETTLED, DISPOSED_BY_GATE }

/**
 * Serial owner boundary. All state (open/closed, envelope registration,
 * PENDING->RUNNING, PENDING->DISPOSED, RUNNING->COMPLETED, tracking list)
 * is coordinated under ONE lock. Body execution is outside that lock.
 *
 * Invariants:
 * - A PENDING event either starts or disposes, never both.
 * - A RUNNING event never transitions to DISPOSED.
 * - close() never disposes a RUNNING event.
 * - close() does not erase RUNNING from tracking.
 * - No event body begins after close returns unless RUNNING before close.
 * - COMPLETED flows from RUNNING after body returns (including exception).
 * - COMPLETED and DISPOSED events are removed from tracking.
 * - Repeated close is idempotent.
 * - Event body exception is not a dispatcher rejection; post reports true
 *   and the failure is sent to injectable onEventFailure.
 */
internal class CaptureStateOwner(
    private val dispatch: (CaptureOwnerEvent) -> Boolean,
    private val onEventFailure: (CaptureOwnerEvent, Throwable) -> Unit = { _, _ -> }
) {

    internal class Envelope(
        val event: CaptureOwnerEvent,
        private val owner: CaptureStateOwner
    ) : CaptureOwnerEvent {

        @Volatile var started = false

        override fun execute() {
            // execute() is called by the dispatcher on a serialized thread.
            val gate = owner.startGate(this)
            when (gate) {
                GateResult.STARTED -> {
                    try { event.execute() } catch (t: Throwable) {
                        owner.onEventFailure(event, t)
                    } finally {
                        owner.complete(this)
                    }
                }
                GateResult.DISPOSED_BY_GATE -> event.disposeWithoutMutation()
                GateResult.ALREADY_SETTLED -> { /* noop */ }
            }
        }

        override fun disposeWithoutMutation() {
            error("disposeWithoutMutation called directly on Envelope")
        }

        fun disposeEvent() = event.disposeWithoutMutation()
    }

    // ── State under lock ──────────────────────────────────────────
    private val lock = Any()
    private var closed = false
    private val tracking = linkedSetOf<Envelope>()

    /**
     * Atomically transition a PENDING envelope to RUNNING if the owner
     * is OPEN, otherwise to DISPOSED.  Returns the gate result.
     */
    internal fun startGate(env: Envelope): GateResult = synchronized(lock) {
        if (env.started) return@synchronized GateResult.ALREADY_SETTLED
        env.started = true
        if (closed) {
            tracking -= env
            GateResult.DISPOSED_BY_GATE
        } else {
            GateResult.STARTED
        }
    }

    /**
     * Mark a RUNNING event as COMPLETED and remove it from tracking.
     */
    internal fun complete(env: Envelope) = synchronized(lock) {
        tracking -= env
    }

    fun post(event: CaptureOwnerEvent): Boolean {
        val env = synchronized(lock) {
            if (closed) return@synchronized null
            Envelope(event, this).also { tracking += it }
        } ?: run { event.disposeWithoutMutation(); return false }

        val dispatchAccepted = try {
            dispatch(env)
        } catch (_: Throwable) {
            false
        }

        if (!dispatchAccepted) {
            // The dispatcher did NOT queue/execute the envelope.
            // If the envelope is still PENDING (not started via a sync dispatch),
            // remove it from tracking and dispose the event.
            val notStarted = synchronized(lock) {
                if (env in tracking && !env.started) {
                    tracking -= env
                    true
                } else false
            }
            if (notStarted) event.disposeWithoutMutation()
        }
        return dispatchAccepted
    }

    fun close() {
        val drained = synchronized(lock) {
            if (closed) return@synchronized listOf<Envelope>()
            closed = true
            val list = mutableListOf<Envelope>()
            val iter = tracking.iterator()
            while (iter.hasNext()) {
                val env = iter.next()
                if (!env.started) {
                    env.started = true // prevent re-disposal if execute called later
                    iter.remove()
                    list += env
                }
            }
            list
        }
        for (env in drained) env.disposeEvent()
    }

    fun isClosed(): Boolean = synchronized(lock) { closed }

    fun pendingCount(): Int = synchronized(lock) {
        tracking.count { !it.started }
    }

    fun runningCount(): Int = synchronized(lock) {
        tracking.count { it.started }
    }

    fun trackingSize(): Int = synchronized(lock) { tracking.size }
}