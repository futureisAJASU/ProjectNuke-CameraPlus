package com.projectnuke.keplernightlab

import java.util.concurrent.atomic.AtomicLong

/** Explicit owner for one Compose-visible capture pipeline operation. */
internal class CameraPipelineUiSession(
    /**
     * Live background-processing occupancy probe. In this phase it only feeds
     * observability and the (still conservative) admission check; routing
     * production work through the coordinator happens in a later phase.
     */
    private val backgroundOccupancy: () -> Boolean = { false }
) {
    companion object {
        private val sessionIdAllocator = AtomicLong(1L)
    }

    /** Process-unique diagnostic routing identity for this session's lifetime. */
    val diagnosticSessionId: Long = sessionIdAllocator.getAndIncrement()
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

        /**
         * Phase 5 safe shutter-release boundary: a new capture may be admitted
         * exactly when no foreground generation owns capture resources and the
         * previous operation left the camera in an admittable state. Active or
         * queued BACKGROUND processing never appears here - fusion/export must
         * not keep the shutter locked after durable handoff.
         */
        val canAdmitNewCapture: Boolean
            get() = !isCaptureBusy &&
                phase != Phase.UNRESOLVED &&
                phase != Phase.DISPOSED &&
                (phase != Phase.TERMINAL || captureResourcesSettled)
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
        // Phase 5 admission: the shutter is gated only by foreground capture
        // ownership and unresolved camera states. Durable-handoff completion
        // frees admission even while heavy background processing continues.
        if (disposed || !current.canAdmitNewCapture) return StartResult.Rejected
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
        // Cancellation split (Phase 5): after the durable handoff boundary the
        // foreground owns nothing cancellable. Cancelling here would kill
        // background processing that no longer gates the shutter.
        val ownerState = foreground.state()
        if (ownerState.generation == localGeneration &&
            ownerState.phase == ForegroundCaptureSession.CaptureOwnershipPhase.HANDOFF_SETTLED
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
            captureResourcesSettled = true,
            captureOwnerPhase = ForegroundCaptureSession.CaptureOwnershipPhase.IDLE
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
            captureResourcesSettled = true,
            captureOwnerPhase = ForegroundCaptureSession.CaptureOwnershipPhase.IDLE
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
            // Typed acquisition progress means real camera capture has begun:
            // leave the conservative preparing state for CAPTURING.
            is CameraPipelineEvent.CaptureProgress ->
                if (current.captureProgress.stage == CaptureStage.IDLE ||
                    current.captureProgress.stage == CaptureStage.PREPARING
                ) {
                    CaptureStage.CAPTURING
                } else {
                    current.captureProgress.stage
                }
            is CameraPipelineEvent.CaptureStageComplete,
            is CameraPipelineEvent.ProcessingStage -> if (event is CameraPipelineEvent.ProcessingStage) event.stage else CaptureStage.PROCESSING
            is CameraPipelineEvent.ExportStage -> event.stage
            is CameraPipelineEvent.Terminal -> error("handled above")
        }
        // Foreground ownership transitions at the capture-stage boundary.
        var ownerPhase = current.captureOwnerPhase
        var nextCaptureStatus = current.captureStatus
        var nextBackgroundStatus = current.backgroundStatus
        var handoffSettled = false
        var backgroundStageEvent = false
        var routedToBackgroundSurface = false
        when (event) {
            is CameraPipelineEvent.Started -> {
                foreground.beginCapturing(event.generation)
                ownerPhase = ForegroundCaptureSession.CaptureOwnershipPhase.CAPTURING
                nextCaptureStatus = event.message
            }
            is CameraPipelineEvent.CaptureProgress -> nextCaptureStatus = event.message
            is CameraPipelineEvent.CaptureStageComplete -> {
                // Only complete authoritative evidence releases the foreground
                // slot. A bare stage marker keeps capture ownership until
                // terminal so an unproven handoff can never unlock the shutter.
                if (event.handoffEvidenceComplete &&
                    foreground.settleHandoff(event.generation)
                ) {
                    ownerPhase = ForegroundCaptureSession.CaptureOwnershipPhase.HANDOFF_SETTLED
                    handoffSettled = true
                }
                nextCaptureStatus = event.message
            }
            is CameraPipelineEvent.ProcessingStage, is CameraPipelineEvent.ExportStage -> {
                backgroundStageEvent = true
                // After handoff settlement these belong to the background lane;
                // they must not re-suppress preview, re-lock the shutter, OR
                // reuse/mutate the capture progress surface.
                if (foreground.state().phase ==
                    ForegroundCaptureSession.CaptureOwnershipPhase.HANDOFF_SETTLED
                ) {
                    nextBackgroundStatus = event.message
                    routedToBackgroundSurface = true
                } else {
                    nextCaptureStatus = event.message
                }
            }
            is CameraPipelineEvent.Terminal -> error("handled above")
        }
        val nextPreviewAllowed = when {
            handoffSettled -> true
            backgroundStageEvent -> current.previewAllowed
            else -> false
        }
        val nextCaptureProgress = if (routedToBackgroundSurface) {
            // The capture bar represents camera acquisition only; background
            // fusion/export stages have their own non-blocking status surface.
            current.captureProgress
        } else {
            event.counts.toCaptureProgress(current.captureProgress, stage, event.message)
        }
        current = current.copy(
            phase = if (current.cancellationRequested) Phase.WAITING_FOR_TERMINAL else nextPhase,
            captureProgress = nextCaptureProgress,
            previewAllowed = nextPreviewAllowed,
            captureResourcesSettled = current.captureResourcesSettled || handoffSettled,
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
        val handedOff = current.captureOwnerPhase ==
            ForegroundCaptureSession.CaptureOwnershipPhase.HANDOFF_SETTLED
        operation?.let {
            if (!handedOff) {
                // Disposal may cancel an ACTIVE foreground capture per the
                // existing policy, but must never cancel work that was already
                // durably handed off to background processing.
                it.cancellationToken.cancel()
                it.captureCancellation.cancelCapture("camera screen disposed")
            }
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

    /**
     * Phase D: foreground-only staleness check. An old background terminal may be
     * STALE_FOR_FOREGROUND but still VALID_FOR_BACKGROUND_JOB. Callers must NOT
     * use [accept] to decide if the background job exists - route diagnostics
     * by generation/runId first, then independently decide foreground mutation.
     */
    @Synchronized
    internal fun isCurrentGeneration(localGeneration: Long): Boolean = isCurrentLocked(localGeneration)
}
