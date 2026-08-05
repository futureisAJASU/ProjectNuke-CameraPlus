package com.projectnuke.keplernightlab

internal interface CaptureOwnerEvent {
    fun execute()
    fun disposeWithoutMutation()
}

internal enum class EnvelopeState { PENDING, RUNNING, COMPLETED, DISPOSED }
internal enum class GateResult { STARTED, ALREADY_SETTLED, DISPOSED_BY_GATE }

internal enum class PostDispatchOutcome {
    ACCEPTED_RUNNING,
    ACCEPTED_COMPLETED,
    ACCEPTED_PENDING,
    REJECTED_AND_DISPOSE,
    ALREADY_DISPOSED
}

internal class CaptureStateOwner(
    private val dispatch: (CaptureOwnerEvent) -> Boolean,
    private val onEventFailure: (CaptureOwnerEvent, Throwable) -> Unit = { _, _ -> },
    private val onDisposalFailure: (CaptureOwnerEvent, Throwable) -> Unit = { _, _ -> },
    private val onOwnerInternalFailure: (String, CaptureOwnerEvent?, Throwable) -> Unit = { _, _, _ -> }
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
                        ignore { owner.onEventFailure(event, t) }
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

    fun post(event: CaptureOwnerEvent): Boolean {
        val env = synchronized(lock) {
            if (closed) return@synchronized null
            Envelope(event, this).also { tracking += it }
        } ?: run {
            disposeEvent(event)
            return false
        }

        val accepted = try { dispatch(env) } catch (_: Throwable) { false }
        val outcome = settleDispatchOutcome(env, accepted)
        return finalizePost(outcome, event)
    }

    private fun settleDispatchOutcome(env: Envelope, accepted: Boolean): PostDispatchOutcome =
        synchronized(lock) {
            when (env.state) {
                EnvelopeState.PENDING -> {
                    if (accepted) {
                        PostDispatchOutcome.ACCEPTED_PENDING
                    } else {
                        env.state = EnvelopeState.DISPOSED
                        tracking -= env
                        PostDispatchOutcome.REJECTED_AND_DISPOSE
                    }
                }
                EnvelopeState.RUNNING -> PostDispatchOutcome.ACCEPTED_RUNNING
                EnvelopeState.COMPLETED -> PostDispatchOutcome.ACCEPTED_COMPLETED
                EnvelopeState.DISPOSED -> PostDispatchOutcome.ALREADY_DISPOSED
            }
        }

    private fun finalizePost(
        outcome: PostDispatchOutcome,
        event: CaptureOwnerEvent
    ): Boolean = when (outcome) {
        PostDispatchOutcome.REJECTED_AND_DISPOSE -> {
            disposeEvent(event)
            false
        }
        PostDispatchOutcome.ALREADY_DISPOSED -> false
        PostDispatchOutcome.ACCEPTED_RUNNING,
        PostDispatchOutcome.ACCEPTED_COMPLETED,
        PostDispatchOutcome.ACCEPTED_PENDING -> true
    }

    fun close() {
        val drained: List<Envelope> = synchronized(lock) {
            if (closed) return@synchronized listOf()
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
        for (env in drained) {
            disposeEvent(env.event)
        }
    }

    internal fun disposeEvent(event: CaptureOwnerEvent) {
        try {
            event.disposeWithoutMutation()
        } catch (disposalError: Throwable) {
            try {
                onDisposalFailure(event, disposalError)
            } catch (hookError: Throwable) {
                ignore { onOwnerInternalFailure("disposal", event, hookError) }
            }
        }
    }

    fun isClosed(): Boolean = synchronized(lock) { closed }
    fun pendingCount(): Int = synchronized(lock) { tracking.count { it.state == EnvelopeState.PENDING } }
    fun runningCount(): Int = synchronized(lock) { tracking.count { it.state == EnvelopeState.RUNNING } }
    fun trackingSize(): Int = synchronized(lock) { tracking.size }
}

internal inline fun ignore(block: () -> Unit) {
    try { block() } catch (_: Throwable) {}
}