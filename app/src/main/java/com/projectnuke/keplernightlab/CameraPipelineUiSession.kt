package com.projectnuke.keplernightlab

/** Explicit owner for one Compose-visible capture pipeline operation. */
internal class CameraPipelineUiSession(
    /**
     * Live background-processing occupancy probe. In this phase it only feeds
     * observability and the (still conservative) admission check; routing
     * production work through the coordinator happens in a later phase.
     */
    private val backgroundOccupancy: () -> Boolean = { false }
) {
    /** Foreground capture ownership truth, generation-guarded. */
    val foreground = ForegroundCaptureSession()
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
        val requiredOutputCommitted: Boolean = false,
        val captureOwnerPhase: ForegroundCaptureSession.CaptureOwnershipPhase =
            ForegroundCaptureSession.CaptureOwnershipPhase.IDLE,
        val backgroundProcessingActive: Boolean = false,
        val captureStatus: String? = null,
        val backgroundStatus: String? = null
    ) {
        val isBusy: Boolean
            get() = phase != Phase.IDLE && phase != Phase.DISPOSED &&
                (phase != Phase.TERMINAL || !captureResourcesSettled)
        val isCapturing: Boolean
            get() = phase == Phase.START_SCHEDULED || phase == Phase.CAPTURING

        /**
         * Foreground-only capture ownership truth. In this behavior-neutral
         * phase the shutter still gates on [isBusy]; this split becomes the
         * admission authority only when early release is enabled later.
         */
        val isCaptureBusy: Boolean
            get() = captureOwnerPhase == ForegroundCaptureSession.CaptureOwnershipPhase.SCHEDULED ||
                captureOwnerPhase == ForegroundCaptureSession.CaptureOwnershipPhase.CAPTURING

        val isBackgroundProcessingBusy: Boolean
            get() = backgroundProcessingActive
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

    enum class EventResult {
        ACCEPTED,
        STALE,
        DUPLICATE_TERMINAL,
        LATE_AFTER_TERMINAL,
        DISPOSED
    }

    private var nextGeneration = 0L
    private var operation: Operation? = null
    private var terminalClaimed = false
    private var disposed = false
    private var scheduledStart: Runnable? = null
    private var watchdog: Runnable? = null
    private var terminalFallback: Runnable? = null
    private var current = Snapshot()

    @Synchronized
    fun snapshot(): Snapshot = current.copy(backgroundProcessingActive = backgroundOccupancy())

    @Synchronized
    fun acceptsDisplayUpdate(localGeneration: Long): Boolean =
        isCurrentLocked(localGeneration) &&
            !terminalClaimed &&
            current.phase != Phase.UNRESOLVED &&
            current.phase != Phase.DISPOSED

    @Synchronized
    fun hasTerminalClaimed(localGeneration: Long): Boolean =
        isCurrentLocked(localGeneration) && terminalClaimed

    @Synchronized
    fun currentOperation(): Operation? = operation?.takeIf { current.isBusy }

    @Synchronized
    fun start(startMessage: String, requestedFrames: Int): StartResult {
        // Behavior-neutral phase: admission still rejects whenever the whole
        // pipeline (including post-capture processing) is occupied, and now
        // also when the background lane holds work. Early release comes later.
        if (disposed || current.isBusy || backgroundOccupancy()) return StartResult.Rejected
        val newOperation = Operation(
            generation = ++nextGeneration,
            cancellationToken = KeplerPipelineCancellationToken(),
            captureCancellation = KeplerCaptureCancellationHandle()
        )
        operation = newOperation
        terminalClaimed = false
        foreground.beginScheduled(newOperation.generation)
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
            captureResourcesSettled = false,
            captureOwnerPhase = ForegroundCaptureSession.CaptureOwnershipPhase.SCHEDULED,
            backgroundProcessingActive = backgroundOccupancy(),
            captureStatus = startMessage
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
    fun attachTerminalFallback(localGeneration: Long, runnable: Runnable): Boolean {
        if (!isCurrentLocked(localGeneration) || terminalClaimed ||
            current.phase == Phase.TERMINAL || current.phase == Phase.UNRESOLVED
        ) {
            return false
        }
        terminalFallback = runnable
        return true
    }

    @Synchronized
    fun clearTerminalFallback(localGeneration: Long): Runnable? {
        if (!isCurrentLocked(localGeneration)) return null
        return terminalFallback.also { terminalFallback = null }
    }

    @Synchronized
    fun requestCancellation(localGeneration: Long, reason: String): Boolean {
        val active = operation.takeIf { isCurrentLocked(localGeneration) } ?: return false
        if (terminalClaimed || current.phase == Phase.TERMINAL ||
            current.phase == Phase.UNRESOLVED || current.phase == Phase.DISPOSED
        ) {
            return false
        }
        if (!current.cancellationRequested) {
            active.cancellationToken.cancel()
            active.captureCancellation.cancelCapture(reason)
            foreground.markCancellationRequested(localGeneration)
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
        foreground.abandon(localGeneration)
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
        foreground.abandon(localGeneration)
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
        return true
    }

    /** Records an unknown launcher failure without claiming that capture resources settled. */
    @Synchronized
    fun markLauncherFailureAwaitingTerminal(localGeneration: Long, message: String): Boolean {
        val active = operation.takeIf { isCurrentLocked(localGeneration) } ?: return false
        if (terminalClaimed || current.phase == Phase.TERMINAL ||
            current.phase == Phase.WAITING_FOR_TERMINAL || current.phase == Phase.UNRESOLVED
        ) {
            return false
        }
        val shouldRequestCancellation = !current.cancellationRequested
        current = current.copy(
            phase = Phase.WAITING_FOR_TERMINAL,
            cancellationRequested = true,
            captureProgress = current.captureProgress.copy(message = message),
            previewAllowed = false,
            captureResourcesSettled = false
        )
        if (shouldRequestCancellation) {
            active.cancellationToken.cancel()
            active.captureCancellation.cancelCapture("camera pipeline launcher failed: $message")
        }
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
            // The whole pipeline operation ended: foreground capture ownership
            // is released regardless of which stage claimed the terminal.
            foreground.abandon(event.generation)
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
                requiredOutputCommitted = event.requiredOutputCommitted,
                captureOwnerPhase = ForegroundCaptureSession.CaptureOwnershipPhase.IDLE,
                backgroundProcessingActive = backgroundOccupancy()
            )
            return EventResult.ACCEPTED
        }
        if (terminalClaimed || current.phase == Phase.UNRESOLVED) {
            return EventResult.LATE_AFTER_TERMINAL
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
        // Foreground ownership transitions at the capture-stage boundary.
        var ownerPhase = current.captureOwnerPhase
        var nextCaptureStatus = current.captureStatus
        var nextBackgroundStatus = current.backgroundStatus
        when (event) {
            is CameraPipelineEvent.Started -> {
                foreground.beginCapturing(event.generation)
                ownerPhase = ForegroundCaptureSession.CaptureOwnershipPhase.CAPTURING
                nextCaptureStatus = event.message
            }
            is CameraPipelineEvent.CaptureProgress -> nextCaptureStatus = event.message
            is CameraPipelineEvent.CaptureStageComplete -> {
                foreground.settleHandoff(event.generation)
                ownerPhase = ForegroundCaptureSession.CaptureOwnershipPhase.HANDOFF_SETTLED
                nextCaptureStatus = event.message
            }
            is CameraPipelineEvent.ProcessingStage, is CameraPipelineEvent.ExportStage -> {
                // After handoff settlement these belong to the background lane.
                // The legacy `status` mirror below stays for compatibility;
                // explicit channels let later phases arbitrate without parsing.
                if (foreground.state().phase ==
                    ForegroundCaptureSession.CaptureOwnershipPhase.HANDOFF_SETTLED
                ) {
                    nextBackgroundStatus = event.message
                } else {
                    nextCaptureStatus = event.message
                }
            }
            is CameraPipelineEvent.Terminal -> error("handled above")
        }
        current = current.copy(
            phase = if (current.cancellationRequested) Phase.WAITING_FOR_TERMINAL else nextPhase,
            captureProgress = event.counts.toCaptureProgress(current.captureProgress, stage, event.message),
            previewAllowed = false,
            captureOwnerPhase = ownerPhase,
            captureStatus = nextCaptureStatus,
            backgroundStatus = nextBackgroundStatus
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
        foreground.abandon(current.generation)
        scheduledStart = null
        watchdog = null
        terminalFallback = null
        current = current.copy(phase = Phase.DISPOSED, previewAllowed = false)
        return true
    }

    private fun isCurrentLocked(localGeneration: Long): Boolean =
        !disposed && operation?.generation == localGeneration
}
