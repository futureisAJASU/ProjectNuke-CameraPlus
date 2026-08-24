package com.projectnuke.keplernightlab

import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 6 contention policy: process-scoped truth about whether FOREGROUND
 * capture persistence is currently executing. The single background heavy lane
 * MAY voluntarily yield at its own safe stage boundaries while this signal is
 * active. Deterministic by construction:
 *  - the signal carries NO locks, NO sleeps and NO queues;
 *  - it can never cancel, delay beyond one yield, or reorder background jobs;
 *  - background work remains max-concurrency-1 FIFO regardless of state.
 */
internal object ForegroundCaptureActivitySignal {
    private val activePersistenceTasks = AtomicInteger(0)

    /** Called on the foreground persistence worker around each task. */
    fun beginPersistence() {
        activePersistenceTasks.incrementAndGet()
    }

    /** Must run in finally on every exit path of the foreground task. */
    fun endPersistence() {
        activePersistenceTasks.decrementAndGet()
    }

    fun isForegroundCaptureActive(): Boolean = activePersistenceTasks.get() > 0

    /**
     * Safe-boundary hook for the background heavy lane: at most one
     * [Thread.yield] when foreground persistence is active. Never blocks,
     * never holds anything, and is a no-op when foreground is idle.
     */
    fun cooperativeYieldAtStageBoundary() {
        if (activePersistenceTasks.get() > 0) {
            Thread.yield()
        }
    }
}
