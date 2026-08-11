package com.projectnuke.keplernightlab

import android.util.Log
import java.util.concurrent.CancellationException

internal enum class TerminalUiDeliveryOutcome { ACCEPTED, REJECTED, DISPATCH_THREW }

internal typealias CameraPipelineUiJob = (
    KeplerPipelineCancellationToken,
    KeplerCaptureCancellationHandle,
    (String) -> Unit,
    CameraPipelineEventSink
) -> Unit

/** Production, JVM-testable orchestration seam used by MainCameraScreen. */
internal class CameraPipelineUiOrchestrator(
    private val session: CameraPipelineUiSession,
    private val scheduler: CameraUiScheduler,
    private var callbacks: Callbacks
) {
    internal data class Callbacks(
        val onStatus: (String) -> Unit,
        val onStateChanged: () -> Unit,
        val onTerminal: (CameraPipelineEvent.Terminal) -> Unit
    )

    private var pendingTerminal: CameraPipelineEvent.Terminal? = null
    private var terminalUiNotifiedGeneration: Long? = null
    private var terminalUiDeliveryOutcomeValue: TerminalUiDeliveryOutcome? = null

    fun updateCallbacks(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    internal fun terminalUiDeliveryOutcome(): TerminalUiDeliveryOutcome? =
        synchronized(this) { terminalUiDeliveryOutcomeValue }

    /** Retries only the UI notification; it never asks the native pipeline to republish. */
    internal fun reconcileTerminalUiDelivery(): CameraUiDispatchOutcome {
        val terminal = synchronized(this) {
            if (terminalUiDeliveryOutcomeValue == TerminalUiDeliveryOutcome.ACCEPTED) return CameraUiDispatchOutcome.ACCEPTED
            pendingTerminal
        } ?: return CameraUiDispatchOutcome.REJECTED
        return dispatchTerminalNotification(terminal)
    }

    fun start(
        startMessage: String,
        requestedFrames: Int = 0,
        timeoutMillis: Long = 120_000L,
        job: CameraPipelineUiJob
    ): Boolean {
        val started = session.start(startMessage, requestedFrames)
        if (started is CameraPipelineUiSession.StartResult.Rejected) {
            callbacks.onStatus("Pipeline busy: current fusion/export is still running.")
            return false
        }
        val operation = (started as CameraPipelineUiSession.StartResult.Accepted).operation
        val generation = operation.generation
        val token = operation.cancellationToken
        val captureCancellation = operation.captureCancellation
        callbacks.onStatus(startMessage)
        callbacks.onStateChanged()

        lateinit var watchdog: Runnable
        watchdog = Runnable {
            if (!session.requestCancellation(generation, "watchdog timeout")) return@Runnable
            callbacks.onStatus("CAPTURE_TIMEOUT: Cancellation requested; waiting for terminal settlement.")
            callbacks.onStateChanged()
            val fallback = Runnable {
                if (session.markTerminalDeliveryFailed(
                        generation,
                        "CAPTURE_TIMEOUT: Terminal delivery unresolved; capture remains blocked."
                    )
                ) {
                    callbacks.onStatus("CAPTURE_TIMEOUT: Terminal delivery unresolved; capture remains blocked.")
                    callbacks.onStateChanged()
                }
            }
            when (scheduler.post(15_000L, fallback)) {
                CameraUiDispatchOutcome.ACCEPTED -> Unit
                CameraUiDispatchOutcome.REJECTED,
                CameraUiDispatchOutcome.DISPATCH_THREW ->
                    Log.e(TAG, "terminal fallback dispatch failed generation=$generation")
            }
        }
        session.attachWatchdog(generation, watchdog)
        when (scheduler.post(timeoutMillis, watchdog)) {
            CameraUiDispatchOutcome.ACCEPTED -> Unit
            CameraUiDispatchOutcome.REJECTED,
            CameraUiDispatchOutcome.DISPATCH_THREW -> {
                clearScheduledWork(generation)
                if (session.settlePreStartFailure(
                        generation,
                        "PIPELINE_FAILED: Timeout guard could not be scheduled before capture start."
                    )
                ) {
                    callbacks.onStatus("PIPELINE_FAILED: Timeout guard could not be scheduled before capture start.")
                    callbacks.onStateChanged()
                }
                return false
            }
        }

        fun notifyNonTerminal(event: CameraPipelineEvent) {
            event.message?.let(callbacks.onStatus)
            callbacks.onStateChanged()
        }

        fun acceptEvent(event: CameraPipelineEvent) {
            when (session.accept(event)) {
                CameraPipelineUiSession.EventResult.ACCEPTED -> {
                    val terminal = event as? CameraPipelineEvent.Terminal ?: return
                    synchronized(this@CameraPipelineUiOrchestrator) {
                        pendingTerminal = terminal
                    }
                    session.clearWatchdog(generation)?.let(scheduler::remove)
                    dispatchTerminalNotification(terminal)
                }
                CameraPipelineUiSession.EventResult.DUPLICATE_TERMINAL -> Unit
                CameraPipelineUiSession.EventResult.STALE,
                CameraPipelineUiSession.EventResult.DISPOSED -> Log.i(TAG, "stale pipeline event ignored generation=$generation")
            }
        }

        val jobStart = Runnable {
            if (session.snapshot().generation != generation) return@Runnable
            if (token.isCancelled) {
                clearScheduledWork(generation)
                if (session.settleScheduledStartCancellation(
                        generation,
                        "PIPELINE_CANCELLED: Capture start was cancelled before Camera2 acquisition."
                    )
                ) {
                    callbacks.onStatus("PIPELINE_CANCELLED: Capture start was cancelled before Camera2 acquisition.")
                    callbacks.onStateChanged()
                }
                return@Runnable
            }
            acceptEvent(CameraPipelineEvent.Started(generation, startMessage))
            try {
                job(
                    token,
                    captureCancellation,
                    { display ->
                        when (scheduler.post(0L, Runnable {
                            if (session.snapshot().generation == generation) {
                                callbacks.onStatus(display)
                                callbacks.onStateChanged()
                            }
                        })) {
                            CameraUiDispatchOutcome.ACCEPTED -> Unit
                            CameraUiDispatchOutcome.REJECTED,
                            CameraUiDispatchOutcome.DISPATCH_THREW ->
                                Log.e(TAG, "pipeline display dispatch failed generation=$generation")
                        }
                    },
                    { native ->
                        val event = native.withGeneration(generation)
                        if (event is CameraPipelineEvent.Terminal) {
                            // Terminal evidence is authoritative before any UI dispatch attempt.
                            acceptEvent(event)
                        } else {
                            when (scheduler.post(0L, Runnable {
                                if (session.snapshot().generation == generation) {
                                    when (session.accept(event)) {
                                        CameraPipelineUiSession.EventResult.ACCEPTED -> notifyNonTerminal(event)
                                        CameraPipelineUiSession.EventResult.DUPLICATE_TERMINAL -> Unit
                                        CameraPipelineUiSession.EventResult.STALE,
                                        CameraPipelineUiSession.EventResult.DISPOSED -> Unit
                                    }
                                }
                            })) {
                                CameraUiDispatchOutcome.ACCEPTED -> Unit
                                CameraUiDispatchOutcome.REJECTED,
                                CameraUiDispatchOutcome.DISPATCH_THREW ->
                                    Log.e(TAG, "pipeline event dispatch failed generation=$generation")
                            }
                        }
                    }
                )
            } catch (_: CancellationException) {
                // The pipeline must still publish terminal evidence.
            } catch (failure: Exception) {
                acceptEvent(
                    CameraPipelineEvent.Terminal(
                        generation = generation,
                        kind = CameraPipelineEvent.Terminal.Kind.FAILED,
                        message = "PIPELINE_FAILED: ${failure.javaClass.simpleName}"
                    )
                )
            }
        }
        session.attachScheduledStart(generation, jobStart)
        when (scheduler.post(250L, jobStart)) {
            CameraUiDispatchOutcome.ACCEPTED -> Unit
            CameraUiDispatchOutcome.REJECTED,
            CameraUiDispatchOutcome.DISPATCH_THREW -> {
                clearScheduledWork(generation)
                if (session.settlePreStartFailure(
                        generation,
                        "PIPELINE_FAILED: Capture could not be scheduled before Camera2 acquisition."
                    )
                ) {
                    callbacks.onStatus("PIPELINE_FAILED: Capture could not be scheduled before Camera2 acquisition.")
                    callbacks.onStateChanged()
                }
            }
        }
        return true
    }

    fun dispose() {
        session.currentOperation()?.generation?.let(::clearScheduledWork)
        session.dispose()
        callbacks.onStateChanged()
    }

    private fun clearScheduledWork(generation: Long) {
        session.clearWatchdog(generation)?.let(scheduler::remove)
        session.clearScheduledStart(generation)?.let(scheduler::remove)
    }

    private fun dispatchTerminalNotification(
        terminal: CameraPipelineEvent.Terminal
    ): CameraUiDispatchOutcome {
        synchronized(this) {
            if (terminalUiNotifiedGeneration == terminal.generation) {
                terminalUiDeliveryOutcomeValue = TerminalUiDeliveryOutcome.ACCEPTED
                return CameraUiDispatchOutcome.ACCEPTED
            }
        }
        val outcome = try {
            scheduler.post(0L, Runnable {
                synchronized(this@CameraPipelineUiOrchestrator) {
                    if (terminalUiNotifiedGeneration == terminal.generation) return@Runnable
                    terminalUiNotifiedGeneration = terminal.generation
                }
                if (session.snapshot().phase == CameraPipelineUiSession.Phase.DISPOSED) return@Runnable
                terminal.message?.let(callbacks.onStatus)
                callbacks.onStateChanged()
                callbacks.onTerminal(terminal)
            })
        } catch (_: Throwable) {
            CameraUiDispatchOutcome.DISPATCH_THREW
        }
        synchronized(this) {
            terminalUiDeliveryOutcomeValue = when (outcome) {
                CameraUiDispatchOutcome.ACCEPTED -> TerminalUiDeliveryOutcome.ACCEPTED
                CameraUiDispatchOutcome.REJECTED -> TerminalUiDeliveryOutcome.REJECTED
                CameraUiDispatchOutcome.DISPATCH_THREW -> TerminalUiDeliveryOutcome.DISPATCH_THREW
            }
        }
        return outcome
    }

    private companion object {
        const val TAG = "KeplerPipelineState"
    }
}
