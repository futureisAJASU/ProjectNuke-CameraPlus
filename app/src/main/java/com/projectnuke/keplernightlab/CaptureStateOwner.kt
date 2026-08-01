package com.projectnuke.keplernightlab

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal serial-owner boundary shared by Camera2 capture implementations.
 *
 * Camera callbacks and workers may submit immutable events only.  The supplied dispatcher is
 * the single place where state mutation is allowed.  A rejected event is not executed on the
 * producer thread: the optional emergency action is intentionally limited to resource disposal.
 */
internal class CaptureStateOwner(
    private val dispatch: (Runnable) -> Boolean,
    private val emergencyDispose: (Runnable) -> Unit = {}
) {
    private val closed = AtomicBoolean(false)

    fun post(event: Runnable): Boolean {
        if (closed.get() || !dispatch(event)) {
            emergencyDispose(event)
            return false
        }
        return true
    }

    fun close() {
        closed.set(true)
    }
}
