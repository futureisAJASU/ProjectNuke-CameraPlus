package com.projectnuke.keplernightlab

/** Explicit owner for one Compose-visible capture pipeline operation. */
internal class CameraPipelineUiSession {
    enum class Phase {
        IDLE,
        START_SCHEDULED,
        CAPTURING,
        POST_CAPTURE_PROCESSING,
        CANCELLATION_REQUESTED,
        WAITING_FOR_TERMINAL,
        UNRESOLVED,
        TERMINAL,
        DISPOSED
    }

    data class Snapshot(
        val generation: Long = 0L,
        val phase: Phase = Phase.IDLE,
        val cancellationRequested: Boolean = false,
        val terminal: CameraPipelineEvent.Terminal? = null,
        val captureProgress: CaptureProgressState = CaptureProgressState(),
        val previewAllowed: Boolean = true,
        val captureResourcesSettled: Boolean = true,
        val requiredOutputCommitted: Boolean = false
    ) {
        val isBusy: Boolean
            get() = phase != Phase.IDLE && phase != Phase.DISPOSED &&
                (phase != Phase.TERMINAL || !captureResourcesSettled)
        val isCapturing: Boolean
            get() = phase == Phase.START_SCHEDULED || phase == Phase.CAPTURING
    }

    data class Operation(
        val generation: Long,
        val cancellationToken: KeplerPipelineCancellationToken,
        val captureCancellation: KeplerCaptureCancellationHandle
    )

    sealed interface StartResult {
        data class Accepted(val operation: Operation) : StartResult
        data object Rejected : StartResult
    }

    enum class EventResult { ACCEPTED, STALE, DUPLICATE_TERMINAL, DISPOSED }

    private var nextGeneration = 0L
    private var operation: Operation? = null
    private var terminalClaimed = false
    private var disposed = false
    private var scheduledStart: Runnable? = null
    private var watchdog: Runnable? = null
    private var current = Snapshot()

    @Synchronized
    fun snapshot(): Snapshot = current

    @Synchronized
    fun currentOperation(): Operation? = operation?.takeIf { current.isBusy }

    @Synchronized
    fun start(startMessage: String, requestedFrames: Int): StartResult {
        if (disposed || current.isBusy) return StartResult.Rejected
        val newOperation = Operation(
            generation = ++nextGeneration,
            cancellationToken = KeplerPipelineCancellationToken(),
            captureCancellation = KeplerCaptureCancellationHandle()
        )
        operation = newOperation
        terminalClaimed = false
        current = Snapshot(
            generation = newOperation.generation,
            phase = Phase.START_SCHEDULED,
            captureProgress = CaptureProgressState(
                stage = CaptureStage.PREPARING,
                message = startMessage,
                requestedFrames = requestedFrames,
                progressPercent = 0.05f
            ),
            previewAllowed = false,
            captureResourcesSettled = false
        )
        return StartResult.Accepted(newOperation)
    }

    @Synchronized
    fun attachScheduledStart(localGeneration: Long, runnable: Runnable): Boolean {
        if (!isCurrentLocked(localGeneration)) return false
        scheduledStart = runnable
        return true
    }

    @Synchronized
    fun attachWatchdog(localGeneration: Long, runnable: Runnable): Boolean {
        if (!isCurrentLocked(localGeneration)) return false
        watchdog = runnable
        return true
    }

    @Synchronized
    fun clearScheduledStart(localGeneration: Long): Runnable? {
        if (!isCurrentLocked(localGeneration)) return null
        return scheduledStart.also { scheduledStart = null }
    }

    @Synchronized
    fun clearWatchdog(localGeneration: Long): Runnable? {
        if (!isCurrentLocked(localGeneration)) return null
        return watchdog.also { watchdog = null }
    }

    @Synchronized
    fun requestCancellation(localGeneration: Long, reason: String): Boolean {
        val active = operation.takeIf { isCurrentLocked(localGeneration) } ?: return false
        if (!current.cancellationRequested) {
            active.cancellationToken.cancel()
            active.captureCancellation.cancelCapture(reason)
            current = current.copy(
                phase = Phase.CANCELLATION_REQUESTED,
                cancellationRequested = true,
                previewAllowed = false
            )
        }
        return true
    }

    /** Settles a failure that happened before Camera2 capture acquired any resource. */
    @Synchronized
    fun settlePreStartFailure(localGeneration: Long, message: String): Boolean {
        if (!isCurrentLocked(localGeneration) || current.phase != Phase.START_SCHEDULED || terminalClaimed) {
            return false
        }
        val terminal = CameraPipelineEvent.Terminal(
            generation = localGeneration,
            kind = CameraPipelineEvent.Terminal.Kind.FAILED,
            captureResourcesSettled = true,
            message = message
        )
        terminalClaimed = true
        operation?.cancellationToken?.cancel()
        operation?.captureCancellation?.cancelCapture("camera pipeline failed before capture start")
        current = current.copy(
            phase = Phase.TERMINAL,
            terminal = terminal,
            captureProgress = current.captureProgress.copy(
                stage = CaptureStage.FAILED,
                message = message,
                progressPercent = 1f
            ),
            previewAllowed = true,
            captureResourcesSettled = true
        )
        scheduledStart = null
        watchdog = null
        return true
    }

    /** Settles a scheduled start cancelled before its runnable acquired capture resources. */
    @Synchronized
    fun settleScheduledStartCancellation(localGeneration: Long, message: String): Boolean {
        if (!isCurrentLocked(localGeneration) || current.phase != Phase.CANCELLATION_REQUESTED || terminalClaimed) {
            return false
        }
        val terminal = CameraPipelineEvent.Terminal(
            generation = localGeneration,
            kind = CameraPipelineEvent.Terminal.Kind.CANCELLED,
            captureResourcesSettled = true,
            message = message
        )
        terminalClaimed = true
        current = current.copy(
            phase = Phase.TERMINAL,
            terminal = terminal,
            captureProgress = current.captureProgress.copy(
                stage = CaptureStage.CANCELLED,
                message = message,
                progressPercent = 1f
            ),
            previewAllowed = true,
            captureResourcesSettled = true
        )
        scheduledStart = null
        watchdog = null
        return true
    }

    @Synchronized
    fun accept(event: CameraPipelineEvent): EventResult {
        if (disposed) return EventResult.DISPOSED
        if (!isCurrentLocked(event.generation)) return EventResult.STALE
        if (event is CameraPipelineEvent.Terminal) {
            if (terminalClaimed) {
                if (event.captureResourcesSettled && !current.captureResourcesSettled) {
                    current = current.copy(
                        previewAllowed = true,
                        captureResourcesSettled = true
                    )
                }
                return EventResult.DUPLICATE_TERMINAL
            }
            terminalClaimed = true
            current = current.copy(
                phase = Phase.TERMINAL,
                terminal = event,
                captureProgress = event.counts.toCaptureProgress(
                    current.captureProgress,
                    when (event.kind) {
                        CameraPipelineEvent.Terminal.Kind.COMPLETE,
                        CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL -> CaptureStage.COMPLETE
                        CameraPipelineEvent.Terminal.Kind.FAILED -> CaptureStage.FAILED
                        CameraPipelineEvent.Terminal.Kind.CANCELLED -> CaptureStage.CANCELLED
                    },
                    event.message
                ),
                previewAllowed = event.captureResourcesSettled,
                captureResourcesSettled = event.captureResourcesSettled,
                requiredOutputCommitted = event.requiredOutputCommitted
            )
            return EventResult.ACCEPTED
        }
        val nextPhase = when (event) {
            is CameraPipelineEvent.Started -> Phase.CAPTURING
            is CameraPipelineEvent.CaptureProgress -> Phase.CAPTURING
            is CameraPipelineEvent.CaptureStageComplete -> Phase.POST_CAPTURE_PROCESSING
            is CameraPipelineEvent.ProcessingStage,
            is CameraPipelineEvent.ExportStage -> Phase.POST_CAPTURE_PROCESSING
            is CameraPipelineEvent.Terminal -> error("handled above")
        }
        val stage = when (event) {
            is CameraPipelineEvent.Started -> CaptureStage.PREPARING
            is CameraPipelineEvent.CaptureProgress -> current.captureProgress.stage
            is CameraPipelineEvent.CaptureStageComplete,
            is CameraPipelineEvent.ProcessingStage -> if (event is CameraPipelineEvent.ProcessingStage) event.stage else CaptureStage.PROCESSING
            is CameraPipelineEvent.ExportStage -> event.stage
            is CameraPipelineEvent.Terminal -> error("handled above")
        }
        current = current.copy(
            phase = if (current.cancellationRequested) Phase.WAITING_FOR_TERMINAL else nextPhase,
            captureProgress = event.counts.toCaptureProgress(current.captureProgress, stage, event.message),
            previewAllowed = false
        )
        return EventResult.ACCEPTED
    }

    @Synchronized
    fun markTerminalDeliveryFailed(localGeneration: Long, message: String): Boolean {
        if (!isCurrentLocked(localGeneration) || terminalClaimed) return false
        current = current.copy(
            phase = Phase.UNRESOLVED,
            cancellationRequested = true,
            captureProgress = current.captureProgress.copy(
                stage = CaptureStage.TIMEOUT,
                message = message,
                progressPercent = 1f
            ),
            previewAllowed = false,
            captureResourcesSettled = false
        )
        return true
    }

    @Synchronized
    fun dispose(): Boolean {
        if (disposed) return false
        disposed = true
        operation?.let {
            it.cancellationToken.cancel()
            it.captureCancellation.cancelCapture("camera screen disposed")
        }
        scheduledStart = null
        watchdog = null
        current = current.copy(phase = Phase.DISPOSED, previewAllowed = false)
        return true
    }

    private fun isCurrentLocked(localGeneration: Long): Boolean =
        !disposed && operation?.generation == localGeneration
}
