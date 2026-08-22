package com.projectnuke.keplernightlab

/**
 * Foreground capture ownership truth for the camera screen.
 *
 * This owner represents ONLY the foreground capture slot: the capture
 * generation, whether Camera2 capture resources are currently owned, and the
 * capture-side settlement boundary. It intentionally knows nothing about the
 * lifetime of heavy fusion/export work that continues after a durable
 * processing handoff; that lifetime belongs to the background processing
 * authority (durable job metadata + JobOperationLease + the serialized
 * background coordinator).
 *
 * Every mutation is generation-guarded: a stale callback carrying an older
 * generation can never settle, cancel, or otherwise mutate a newer capture
 * generation.
 */
internal class ForegroundCaptureSession {

    enum class CaptureOwnershipPhase {
        /** No foreground capture is owned; shutter admission is free. */
        IDLE,

        /** A capture start is scheduled but Camera2 resources are not owned yet. */
        SCHEDULED,

        /** An active burst owns Camera2/ImageReader capture resources. */
        CAPTURING,

        /**
         * Capture-stage ownership ended: capture resources are settled and the
         * durable processing handoff boundary was reached. Heavy processing
         * may still be running, but it is no longer foreground-owned.
         */
        HANDOFF_SETTLED
    }

    data class State(
        val generation: Long = 0L,
        val phase: CaptureOwnershipPhase = CaptureOwnershipPhase.IDLE,
        val cancellationRequested: Boolean = false
    ) {
        val isCaptureOwned: Boolean
            get() = phase == CaptureOwnershipPhase.SCHEDULED ||
                phase == CaptureOwnershipPhase.CAPTURING

        val isCaptureBusy: Boolean
            get() = isCaptureOwned
    }

    @Volatile
    private var state: State = State()

    @Synchronized
    fun state(): State = state

    @Synchronized
    fun beginScheduled(localGeneration: Long): Boolean {
        // A new capture may take the slot once prior capture ownership ended
        // (IDLE or superseded after handoff settlement); never while an older
        // generation still owns SCHEDULED/CAPTURING resources.
        if (state.isCaptureOwned) return false
        state = State(generation = localGeneration, phase = CaptureOwnershipPhase.SCHEDULED)
        return true
    }

    @Synchronized
    fun beginCapturing(localGeneration: Long): Boolean {
        if (state.generation != localGeneration) return false
        if (state.phase != CaptureOwnershipPhase.SCHEDULED) return false
        state = state.copy(phase = CaptureOwnershipPhase.CAPTURING)
        return true
    }

    /**
     * Records the durable capture-handoff boundary for the exact generation.
     * Returns false for any stale generation or a non-capturing phase, so an
     * older processing callback can never settle a newer foreground capture.
     */
    @Synchronized
    fun settleHandoff(localGeneration: Long): Boolean {
        if (state.generation != localGeneration) return false
        if (state.phase != CaptureOwnershipPhase.SCHEDULED &&
            state.phase != CaptureOwnershipPhase.CAPTURING
        ) {
            return false
        }
        state = state.copy(phase = CaptureOwnershipPhase.HANDOFF_SETTLED)
        return true
    }

    /** Releases foreground ownership without claiming resource settlement (unresolved paths). */
    @Synchronized
    fun abandon(localGeneration: Long): Boolean {
        if (state.generation != localGeneration) return false
        if (state.phase == CaptureOwnershipPhase.IDLE) return false
        state = State()
        return true
    }

    @Synchronized
    fun markCancellationRequested(localGeneration: Long): Boolean {
        if (state.generation != localGeneration) return false
        if (!state.isCaptureOwned) return false
        if (state.cancellationRequested) return false
        state = state.copy(cancellationRequested = true)
        return true
    }

    /**
     * True only while THIS generation still owns foreground capture resources.
     * After handoff settlement the answer is permanently false for capture
     * purposes regardless of any later processing callback.
     */
    @Synchronized
    fun isCaptureOwnedBy(localGeneration: Long): Boolean =
        state.generation == localGeneration && state.isCaptureOwned
}
