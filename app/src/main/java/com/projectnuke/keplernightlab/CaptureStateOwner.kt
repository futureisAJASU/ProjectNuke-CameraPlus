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
                    try { event.execute() } catch (t: Throwable) { owner.onEventFailure(event, t) }
                    finally { owner.complete(this) }
                }
                GateResult.DISPOSED_BY_GATE -> {
                    try { event.disposeWithoutMutation() } catch (t: Throwable) {
                        owner.onDisposalFailure(event, t)
                    }
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
        } ?: run { event.disposeWithoutMutation(); return false }

        val accepted = try { dispatch(env) } catch (_: Throwable) { false }

        // If dispatch started the body synchronously (RUNNING or COMPLETED),
        // post must report accepted regardless of dispatch return value.
        if (env.state != EnvelopeState.PENDING) return true

        if (accepted) return true

        // Dispatch returned false or threw, and the envelope is still PENDING.
        // Atomically transition to DISPOSED.
        synchronized(lock) {
            if (env in tracking && env.state == EnvelopeState.PENDING) {
                env.state = EnvelopeState.DISPOSED
                tracking -= env
                event.disposeWithoutMutation()
            }
        }
        return false
    }

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
        drained.forEach { env ->
            try { env.event.disposeWithoutMutation() } catch (t: Throwable) {
                onDisposalFailure(env.event, t)
            }
        }
    }

    fun isClosed(): Boolean = synchronized(lock) { closed }
    fun pendingCount(): Int = synchronized(lock) { tracking.count { it.state == EnvelopeState.PENDING } }
    fun runningCount(): Int = synchronized(lock) { tracking.count { it.state == EnvelopeState.RUNNING } }
    fun trackingSize(): Int = synchronized(lock) { tracking.size }
}