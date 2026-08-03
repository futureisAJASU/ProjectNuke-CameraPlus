package com.projectnuke.keplernightlab

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class CaptureTerminalStatus {
    ACTIVE, SUCCESS, PARTIAL_SUCCESS, FAILED, TIMED_OUT, CANCELLED
}

internal class CaptureTerminalState {
    private val state = AtomicReference(CaptureTerminalStatus.ACTIVE)

    fun status(): CaptureTerminalStatus = state.get()

    fun claim(next: CaptureTerminalStatus): Boolean =
        state.compareAndSet(CaptureTerminalStatus.ACTIVE, next)
}

internal class CaptureFrameIdentityOwner(private val requested: Int) {
    private var next = 0

    fun nextIdentity(): Int? {
        if (next >= requested) return null
        return next++
    }

    fun allocatedCount(): Int = next
}

internal interface DisposableCaptureTask : Runnable {
    fun dispose()
}

internal class BoundedCaptureWorker(
    name: String,
    capacity: Int,
    private val onRejected: (Runnable) -> Unit = {}
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<Runnable>(capacity.coerceAtLeast(1))
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        queue,
        { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun submit(task: Runnable): Boolean {
        if (closed.get()) {
            reject(task)
            return false
        }
        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            reject(task)
            false
        }
    }

    fun shutdownNow() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow().forEach(::reject)
    }

    private fun reject(task: Runnable) {
        (task as? DisposableCaptureTask)?.dispose()
        onRejected(task)
    }

    fun queuedCount(): Int = queue.size

    fun activeCount(): Int = executor.activeCount

    fun awaitTermination(timeoutMs: Long): Boolean = try {
        executor.awaitTermination(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    override fun close() = shutdownNow()
}