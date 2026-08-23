package com.projectnuke.keplernightlab

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.io.File
import java.util.ArrayDeque

/**
 * Identifies one exact durable job directory to process. The coordinator and
 * the queue carry ONLY this lightweight identity plus caller-supplied
 * metadata; frame pixels, Images, and decoded Bitmaps must never be queued.
 */
internal data class ExactJobRef(
    val jobDirectory: File,
    val jobKind: KeplerActiveOperationKind,
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * A unit of heavy background work bound to one exact job directory.
 * Implementations must resolve all processing parameters from the durable
 * job metadata (job.json) of [ref] - never from mutable UI state or from a
 * "latest job" lookup - so the item stays valid no matter how many newer
 * captures appear before execution.
 */
internal fun interface HeavyProcessingWork {
    fun execute(ref: ExactJobRef)
}

internal data class BackgroundProcessingSnapshot(
    val activeJobDirectory: String?,
    val activeJobKind: KeplerActiveOperationKind?,
    val activeSequence: Long?,
    val queuedCount: Int,
    val queuedJobDirectories: List<String>
) {
    val hasActiveWork: Boolean get() = activeJobDirectory != null
    val hasPendingWork: Boolean get() = hasActiveWork || queuedCount > 0
}

internal sealed interface BackgroundEnqueueResult {
    data object Accepted : BackgroundEnqueueResult
    data class Duplicate(val existingSequence: Long) : BackgroundEnqueueResult
    data object Unavailable : BackgroundEnqueueResult
    data object Shutdown : BackgroundEnqueueResult
}

/**
 * Application-scoped serialized lane for heavy fusion/export work.
 *
 * Ownership truth remains the EXISTING durable authority: the job directory's
 * processing handoff, JobOperationLease acquisitions inside each worker, and
 * RecoveryCoordinator reconciliation. This coordinator is only the scheduling
 * convenience on top:
 *  - at most ONE heavy job executes at a time (strict FIFO);
 *  - work items identify an exact job directory (never "latest");
 *  - duplicate scheduling of the same job is rejected in-process; cross-owner
 *    duplicates are still rejected authoritatively by JobOperationLease;
 *  - a failing job settles its own exact job and never poisons the lane;
 *  - the queue holds no pixels - only file identities.
 *
 * The coordinator holds applicationContext only. It is NOT scoped to any
 * Activity/Composable lifetime: disposing the camera screen must not cancel
 * jobs that were already durably handed off.
 */
internal class BackgroundProcessingCoordinator private constructor(
    internal val heldApplicationContext: Context
) {
    companion object {
        @Volatile
        private var instance: BackgroundProcessingCoordinator? = null

        fun of(context: Context): BackgroundProcessingCoordinator =
            instance ?: synchronized(this) {
                instance ?: BackgroundProcessingCoordinator(context.applicationContext).also {
                    instance = it
                }
            }

        internal fun resetForTest() {
            synchronized(this) {
                instance?.let { coordinator ->
                    synchronized(coordinator.lock) {
                        coordinator.worker?.quitSafely()
                        coordinator.worker = null
                        coordinator.testWorkerFactory = null
                        coordinator.queue.clear()
                        coordinator.workByPath.clear()
                        coordinator.sequenceByPath.clear()
                        coordinator.running = null
                        coordinator.runningSequence = null
                    }
                }
                instance = null
            }
        }
    }

    private val lock = Any()
    private val queue = ArrayDeque<ExactJobRef>()
    private val workByPath = HashMap<String, HeavyProcessingWork>()
    private val sequenceByPath = HashMap<String, Long>()
    private var running: ExactJobRef? = null
    private var runningSequence: Long? = null
    private var nextSequence = 0L
    private var worker: HandlerThread? = null

    @Volatile
    internal var testWorkerFactory: (() -> HandlerThread)? = null

    fun enqueue(ref: ExactJobRef, work: HeavyProcessingWork): BackgroundEnqueueResult {
        val shouldStart: Boolean
        val dispatchOk: Boolean
        synchronized(lock) {
            val pathKey = ref.jobDirectory.absolutePath
            sequenceByPath[pathKey]?.let { return BackgroundEnqueueResult.Duplicate(it) }
            val sequence = ++nextSequence
            queue.addLast(ref)
            workByPath[pathKey] = work
            sequenceByPath[pathKey] = sequence
            val workerReady = ensureWorkerLocked()
            if (!workerReady) {
                // Roll back in-memory registration; durable handoff remains for recovery.
                queue.removeLastOccurrence(ref)
                workByPath.remove(pathKey)
                sequenceByPath.remove(pathKey)
                return BackgroundEnqueueResult.Unavailable
            }
            shouldStart = running == null && queue.size == 1
            dispatchOk = if (shouldStart) {
                // Try to dispatch drain; if Handler.post fails, treat as unavailable.
                val ok = postDrainLocked()
                if (!ok) {
                    queue.remove(ref)
                    workByPath.remove(pathKey)
                    sequenceByPath.remove(pathKey)
                }
                ok
            } else {
                true
            }
        }
        if (shouldStart && dispatchOk) {
            // drainLoop will be invoked via Handler; if we already posted inside lock, no need to post again.
            // However we posted inside lock via postDrainLocked, so nothing to do.
        }
        if (!dispatchOk) return BackgroundEnqueueResult.Unavailable
        return BackgroundEnqueueResult.Accepted
    }

    fun snapshot(): BackgroundProcessingSnapshot = synchronized(lock) {
        BackgroundProcessingSnapshot(
            activeJobDirectory = running?.jobDirectory?.absolutePath,
            activeJobKind = running?.jobKind,
            activeSequence = runningSequence,
            queuedCount = queue.size,
            queuedJobDirectories = queue.map { it.jobDirectory.absolutePath }
        )
    }

    /** Diagnostic/testing hook: current FIFO order as exact job paths. */
    fun queuedOrder(): List<String> = snapshot().queuedJobDirectories

    private fun ensureWorkerLocked(): Boolean {
        val existing = worker
        if (existing != null && existing.isAlive && existing.looper != null) return true
        if (existing != null) {
            try { existing.quitSafely() } catch (_: Throwable) {}
            worker = null
        }
        val thread = try {
            testWorkerFactory?.invoke() ?: HandlerThread("KeplerBackgroundProcessing", Process.THREAD_PRIORITY_BACKGROUND)
        } catch (e: Throwable) {
            Log.e("KeplerBackground", "background worker factory failed", e)
            return false
        }
        try {
            thread.start()
        } catch (e: Throwable) {
            Log.e("KeplerBackground", "background worker start failed", e)
            return false
        }
        worker = thread
        // Initial dispatch for the first item will be done via postDrainLocked; we don't need to post here.
        return true
    }

    private fun postDrainLocked(): Boolean {
        val handlerThread = worker ?: return false
        if (!handlerThread.isAlive) return false
        val looper = handlerThread.looper ?: return false
        return try {
            Handler(looper).post { drainLoop() }
        } catch (e: Throwable) {
            Log.e("KeplerBackground", "background drain post failed", e)
            false
        }
    }

    private fun drain() {
        val ok = synchronized(lock) { postDrainLocked() }
        if (!ok) {
            Log.e("KeplerBackground", "drain post failed - lane may be stalled until next enqueue")
        }
    }

    private fun drainLoop() {
        while (true) {
            val next = synchronized(lock) {
                val head = queue.pollFirst() ?: run {
                    running = null
                    runningSequence = null
                    return
                }
                val pathKey = head.jobDirectory.absolutePath
                running = head
                runningSequence = sequenceByPath[pathKey]
                head to workByPath.remove(pathKey)
            }
            val (item, work) = next
            var executedOk = false
            try {
                work?.execute(item)
                    ?: error("Background work missing for ${item.jobDirectory.absolutePath}")
                executedOk = true
            } catch (cancelled: java.util.concurrent.CancellationException) {
                // Preserve cancellation semantics: treat as ordinary job termination, not fatal.
                Log.i(
                    "KeplerBackground",
                    "background job cancelled for ${item.jobDirectory.name}",
                    cancelled
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                Log.i(
                    "KeplerBackground",
                    "background job cancelled for ${item.jobDirectory.name}",
                    cancelled
                )
            } catch (failure: Exception) {
                Log.e(
                    "KeplerBackground",
                    "background processing failed for ${item.jobDirectory.name}; continuing lane",
                    failure
                )
                // One failed job must not poison the serialized lane. The exact
                // job keeps its durable failure/reconciliation evidence because
                // the worker owns that settlement; nothing is erased here.
            } catch (error: Error) {
                Log.e(
                    "KeplerBackground",
                    "fatal error in background job ${item.jobDirectory.name}; lane will terminate",
                    error
                )
                // Do required local bookkeeping in finally, then rethrow.
                // The finally below will clear running/sequence.
                synchronized(lock) {
                    sequenceByPath.remove(item.jobDirectory.absolutePath)
                    if (running?.jobDirectory?.absolutePath == item.jobDirectory.absolutePath) {
                        running = null
                        runningSequence = null
                    }
                }
                throw error
            } finally {
                synchronized(lock) {
                    // For normal completion (including Exception/Cancellation), clear.
                    // For Error we already cleared above, but clearing again is idempotent.
                    sequenceByPath.remove(item.jobDirectory.absolutePath)
                    if (running?.jobDirectory?.absolutePath == item.jobDirectory.absolutePath) {
                        running = null
                        runningSequence = null
                    }
                }
            }
        }
    }
}
