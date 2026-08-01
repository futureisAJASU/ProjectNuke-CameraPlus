package com.projectnuke.keplernightlab

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A single-owner, bounded queue for capture work. */
internal class BoundedCaptureWorker(
    name: String,
    capacity: Int,
    private val onRejected: (Runnable) -> Unit = {}
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(capacity.coerceAtLeast(1)),
        { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun submit(task: Runnable): Boolean {
        if (closed.get()) {
            onRejected(task)
            return false
        }
        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            onRejected(task)
            false
        }
    }

    fun shutdownNow() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow().forEach(onRejected)
    }

    override fun close() = shutdownNow()
}
