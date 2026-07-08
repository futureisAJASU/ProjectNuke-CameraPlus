package com.projectnuke.keplernightlab

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

interface KeplerPipelineCancellation {
    val isCancelled: Boolean
    fun throwIfCancelled()
}

object NoOpKeplerPipelineCancellation : KeplerPipelineCancellation {
    override val isCancelled: Boolean
        get() = false

    override fun throwIfCancelled() = Unit
}

class KeplerPipelineCancellationToken : KeplerPipelineCancellation {
    private val cancelled = AtomicBoolean(false)

    override val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        cancelled.set(true)
    }

    override fun throwIfCancelled() {
        if (cancelled.get()) {
            throw CancellationException("Kepler pipeline cancelled")
        }
    }
}
