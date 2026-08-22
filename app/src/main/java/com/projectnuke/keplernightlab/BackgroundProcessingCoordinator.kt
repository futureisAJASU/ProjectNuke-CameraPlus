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
            synchronized(this) { instance = null }
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

    fun enqueue(ref: ExactJobRef, work: HeavyProcessingWork): BackgroundEnqueueResult {
        val shouldStart = synchronized(lock) {
            val pathKey = ref.jobDirectory.absolutePath
            sequenceByPath[pathKey]?.let { return BackgroundEnqueueResult.Duplicate(it) }
            val sequence = ++nextSequence
            queue.addLast(ref)
            workByPath[pathKey] = work
            sequenceByPath[pathKey] = sequence
            ensureWorkerLocked()
            running == null && queue.size == 1
        }
        if (shouldStart) drain()
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

    private fun ensureWorkerLocked() {
        if (worker != null) return
        val thread = HandlerThread("KeplerBackgroundProcessing", Process.THREAD_PRIORITY_BACKGROUND)
        try {
            thread.start()
        } catch (failure: IllegalStateException) {
            // Thread.start can race VM teardown; leave the lane idle and let
            // recovery reconcile the durable handoffs instead of crashing.
            return
        }
        worker = thread
        Handler(thread.looper).post { drainLoop() }
    }

    private fun drain() {
        val handlerThread = synchronized(lock) { worker } ?: return
        Handler(handlerThread.looper).post { drainLoop() }
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
            try {
                work?.execute(item)
                    ?: error("Background work missing for ${item.jobDirectory.absolutePath}")
            } catch (failure: Exception) {
                Log.e(
                    "KeplerBackground",
                    "background processing failed for ${item.jobDirectory.name}; continuing lane",
                    failure
                )
                // One failed job must not poison the serialized lane. The exact
                // job keeps its durable failure/reconciliation evidence because
                // the worker owns that settlement; nothing is erased here.
            } finally {
                synchronized(lock) {
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
