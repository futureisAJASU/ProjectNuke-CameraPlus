package com.projectnuke.keplernightlab

internal interface CaptureOwnerEvent {
    fun execute()
    fun disposeWithoutMutation()
}

internal enum class EnvelopeState { PENDING, RUNNING, COMPLETED, DISPOSED }
internal enum class GateResult { STARTED, ALREADY_SETTLED, DISPOSED_BY_GATE }

internal class CaptureStateOwner(
    private val dispatch: (CaptureOwnerEvent) -> Boolean,
    private val onEventFailure: (CaptureOwnerEvent, Throwable) -> Unit = { _, _ -> },
    private val onDisposalFailure: (CaptureOwnerEvent, Throwable) -> Unit = { _, _ -> }
) {
    internal class Envelope(
        val event: CaptureOwnerEvent,
        private val owner: CaptureStateOwner
    ) : CaptureOwnerEvent {
        @Volatile var state: EnvelopeState = EnvelopeState.PENDING

        override fun execute() {
            when (owner.startGate(this)) {
                GateResult.STARTED -> {
                    try { event.execute() } catch (t: Throwable) {
                        try { owner.onEventFailure(event, t) } catch (_: Throwable) {}
                    }
                    finally { owner.complete(this) }
                }
                GateResult.DISPOSED_BY_GATE -> {
                    owner.disposeEvent(event)
                }
                GateResult.ALREADY_SETTLED -> {}
            }
        }

        override fun disposeWithoutMutation() =
            error("disposeWithoutMutation called directly on Envelope")
    }

    // --------------- internal helpers --------------------------------

    private val lock = Any()
    private var closed = false
    private val tracking = linkedSetOf<Envelope>()

    internal fun startGate(env: Envelope): GateResult = synchronized(lock) {
        when (env.state) {
            EnvelopeState.PENDING -> {
                if (closed) {
                    env.state = EnvelopeState.DISPOSED
                    tracking -= env
                    GateResult.DISPOSED_BY_GATE
                } else {
                    env.state = EnvelopeState.RUNNING
                    GateResult.STARTED
                }
            }
            EnvelopeState.RUNNING, EnvelopeState.COMPLETED, EnvelopeState.DISPOSED ->
                GateResult.ALREADY_SETTLED
        }
    }

    internal fun complete(env: Envelope) = synchronized(lock) {
        check(env.state == EnvelopeState.RUNNING) { "complete from ${env.state}" }
        env.state = EnvelopeState.COMPLETED
        tracking -= env
    }

    // --------------------------------------------------------------
    //  post  —  returns true if event was / will be executed,
    //           false when it was rejected (disposed).
    // --------------------------------------------------------------

    fun post(event: CaptureOwnerEvent): Boolean {
        val env = synchronized(lock) {
            if (closed) return@synchronized null
            Envelope(event, this).also { tracking += it }
        } ?: run {
            disposeEvent(event)
            return false
        }

        val accepted = try { dispatch(env) } catch (_: Throwable) { false }

        // If the dispatcher already started (RUNNING) or even finished (COMPLETED)
        // the body, post reports accepted regardless of what dispatch returned.
        if (env.state != EnvelopeState.PENDING) return true

        if (accepted) return true

        // Dispatch rejected (false / threw) and the envelope is still PENDING.
        // Atomically decide the outcome under the lock.
        val shouldDispose = synchronized(lock) {
            when (env.state) {
                EnvelopeState.PENDING -> {
                    env.state = EnvelopeState.DISPOSED
                    tracking -= env
                    true
                }
                // Raced: some other thread started (RUNNING) or completed (COMPLETED)
                EnvelopeState.RUNNING, EnvelopeState.COMPLETED -> false
                EnvelopeState.DISPOSED -> false
            }
        }
        if (shouldDispose) {
            disposeEvent(event)
        }
        return !shouldDispose
    }

    // --------------------------------------------------------------
    //  close  —  drain all PENDING envelopes, leave RUNNING alone.
    // --------------------------------------------------------------

    fun close() {
        val drained = synchronized(lock) {
            if (closed) return@synchronized listOf<Envelope>()
            closed = true
            val list = mutableListOf<Envelope>()
            val iter = tracking.iterator()
            while (iter.hasNext()) {
                val env = iter.next()
                if (env.state == EnvelopeState.PENDING) {
                    env.state = EnvelopeState.DISPOSED
                    iter.remove()
                    list += env
                }
            }
            list
        }
        drained.forEach { env -> disposeEvent(env.event) }
    }

    // --------------------------------------------------------------
    //  helpers
    // --------------------------------------------------------------

    internal fun disposeEvent(event: CaptureOwnerEvent) {
        try { event.disposeWithoutMutation() } catch (t: Throwable) {
            onDisposalFailure(event, t)
        }
    }

    fun isClosed(): Boolean = synchronized(lock) { closed }
    fun pendingCount(): Int = synchronized(lock) { tracking.count { it.state == EnvelopeState.PENDING } }
    fun runningCount(): Int = synchronized(lock) { tracking.count { it.state == EnvelopeState.RUNNING } }
    fun trackingSize(): Int = synchronized(lock) { tracking.size }
}