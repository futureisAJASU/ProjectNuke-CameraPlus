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

/**
 * A disposable task whose disposal reports an explicit [CaptureTaskDisposalOutcome]
 * instead of silently returning Unit: an unclean work-item disposal can never be
 * counted as a successful task disposal.  Generic Unit-disposal tasks keep working
 * through [DisposableCaptureTask].
 */
internal interface OutcomeDisposableCaptureTask : DisposableCaptureTask {
    fun disposeWithOutcome(): CaptureTaskDisposalOutcome
}

internal sealed interface CaptureTaskDisposalOutcome {
    /** The task's work item was disposed cleanly (all required settlements done). */
    data object Clean : CaptureTaskDisposalOutcome

    /** Disposal ran but returned an unclean work-item outcome (cleanup debt). */
    data class Unclean(
        val workDisposal: YuvWorkDisposalOutcome?,
        val description: String
    ) : CaptureTaskDisposalOutcome

    /** The disposal attempt itself threw; [failure] is the contained throwable. */
    data class Failed(val failure: Throwable) : CaptureTaskDisposalOutcome
}

internal open class BoundedCaptureWorker(
    name: String,
    capacity: Int,
    private val onTaskDisposalFailure: (Runnable, Throwable) -> Unit = { _, _ -> },
    private val onRejectionNotificationFailure: (Runnable, Throwable) -> Unit = { _, _ -> },
    onRejected: (Runnable) -> Unit = {}
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<Runnable>(capacity.coerceAtLeast(1))
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        queue,
        { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val notificationHook = onRejected

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

    data class CleanupReport(
        val queuedTasksRemoved: Int,
        val queuedDisposableTasksDisposalAttempted: Int,
        val queuedDisposableTasksDisposedSuccessfully: Int,
        val queuedNonDisposableTasksRemoved: Int,
        val activeWorkersAtStart: Int,
        val taskDisposalFailures: List<String>,
        val rejectionNotificationFailures: List<String>,
        val shutdownAlreadyRequested: Boolean
    )

    /**
     * Drains and disposes queued tasks and shuts the executor down.  Returns a
     * [CleanupReport] with exact attempt/success counts.  An outcome-disposable task
     * is counted as disposed successfully ONLY when its disposal is clean; an unclean
     * or failed disposal is reported through the task-disposal failure list (and the
     * [onTaskDisposalFailure] hook).  Open for deterministic failure injection in tests.
     */
    internal open fun shutdownNow(): CleanupReport {
        val activeBeforeDrain = executor.activeCount
        if (!closed.compareAndSet(false, true)) {
            return CleanupReport(0, 0, 0, 0, activeBeforeDrain, emptyList(), emptyList(), true)
        }
        val drained = executor.shutdownNow()
        val taskFailures = mutableListOf<String>()
        var disposableAttempted = 0
        var disposableSucceeded = 0
        var nonDisposable = 0
        for (task in drained) {
            when (task) {
                is OutcomeDisposableCaptureTask -> {
                    disposableAttempted++
                    try {
                        when (val outcome = task.disposeWithOutcome()) {
                            CaptureTaskDisposalOutcome.Clean -> disposableSucceeded++
                            is CaptureTaskDisposalOutcome.Unclean -> {
                                val description = "taskDisposeUnclean: ${outcome.description}"
                                taskFailures.add(description)
                                ignore { onTaskDisposalFailure(task, IllegalStateException(description)) }
                            }
                            is CaptureTaskDisposalOutcome.Failed -> {
                                taskFailures.add("taskDispose: ${outcome.failure.message}")
                                ignore { onTaskDisposalFailure(task, outcome.failure) }
                            }
                        }
                    } catch (t: Throwable) {
                        taskFailures.add("taskDispose: ${t.message}")
                        ignore { onTaskDisposalFailure(task, t) }
                    }
                }
                is DisposableCaptureTask -> {
                    disposableAttempted++
                    try {
                        task.dispose()
                        disposableSucceeded++
                    } catch (t: Throwable) {
                        taskFailures.add("taskDispose: ${t.message}")
                        ignore { onTaskDisposalFailure(task, t) }
                    }
                }
                else -> nonDisposable++
            }
        }
        val rejectionFailures = mutableListOf<String>()
        for (task in drained) {
            try {
                notificationHook(task)
            } catch (t: Throwable) {
                rejectionFailures.add("rejectionNotification: ${t.message}")
                ignore { onRejectionNotificationFailure(task, t) }
            }
        }
        return CleanupReport(
            queuedTasksRemoved = drained.size,
            queuedDisposableTasksDisposalAttempted = disposableAttempted,
            queuedDisposableTasksDisposedSuccessfully = disposableSucceeded,
            queuedNonDisposableTasksRemoved = nonDisposable,
            activeWorkersAtStart = activeBeforeDrain,
            taskDisposalFailures = taskFailures,
            rejectionNotificationFailures = rejectionFailures,
            shutdownAlreadyRequested = false
        )
    }

    private fun reject(task: Runnable) {
        when (task) {
            is OutcomeDisposableCaptureTask -> {
                try {
                    when (val outcome = task.disposeWithOutcome()) {
                        CaptureTaskDisposalOutcome.Clean -> Unit
                        is CaptureTaskDisposalOutcome.Unclean -> {
                            val description = "taskDisposeUnclean: ${outcome.description}"
                            ignore { onTaskDisposalFailure(task, IllegalStateException(description)) }
                        }
                        is CaptureTaskDisposalOutcome.Failed ->
                            ignore { onTaskDisposalFailure(task, outcome.failure) }
                    }
                } catch (t: Throwable) {
                    ignore { onTaskDisposalFailure(task, t) }
                }
            }
            is DisposableCaptureTask -> {
                try { task.dispose() } catch (t: Throwable) {
                    ignore { onTaskDisposalFailure(task, t) }
                }
            }
            else -> Unit
        }
        try {
            notificationHook(task)
        } catch (t: Throwable) {
            ignore { onRejectionNotificationFailure(task, t) }
        }
    }

    fun queuedCount(): Int = queue.size

    fun activeCount(): Int = executor.activeCount

    fun awaitTermination(timeoutMs: Long): Boolean = try {
        executor.awaitTermination(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    override fun close() { shutdownNow() }
}