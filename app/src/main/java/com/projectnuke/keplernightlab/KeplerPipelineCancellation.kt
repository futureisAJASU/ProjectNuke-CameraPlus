package com.projectnuke.keplernightlab

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
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

open class KeplerCaptureCancellationHandle {
    private val cancelled = AtomicBoolean(false)
    private val cleanupAction = AtomicReference<(() -> Unit)?>(null)
    private val cleanupRan = AtomicBoolean(false)

    open val isCancelled: Boolean
        get() = cancelled.get()

    open fun registerCleanupAction(action: () -> Unit) {
        if (!cleanupAction.compareAndSet(null, action)) return
        if (cancelled.get()) {
            runCleanup()
        }
    }

    open fun cancelCapture(reason: String? = null) {
        cancelled.set(true)
        if (reason != null) {
            // Intentionally quiet: the watchdog owns the visible timeout message.
        }
        runCleanup()
    }

    private fun runCleanup() {
        val action = cleanupAction.getAndSet(null) ?: return
        if (!cleanupRan.compareAndSet(false, true)) return
        action.invoke()
    }
}

object NoOpKeplerCaptureCancellationHandle : KeplerCaptureCancellationHandle() {
    override val isCancelled: Boolean = false

    override fun registerCleanupAction(action: () -> Unit) = Unit

    override fun cancelCapture(reason: String?) = Unit
}
