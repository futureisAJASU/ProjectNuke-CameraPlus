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
        val onTerminal: (CameraPipelineEvent.Terminal) -> Unit,
        val onDiagnosticEvent: ((CameraPipelineEvent) -> Unit)? = null,
        val onBackgroundTerminal: ((CameraPipelineEvent.Terminal) -> Unit)? = null
    )

    private var pendingTerminal: CameraPipelineEvent.Terminal? = null
    private var terminalUiNotifiedGeneration: Long? = null
    private var terminalUiDeliveryOutcomeValue: TerminalUiDeliveryOutcome? = null
    private val staleTerminalUiNotifications = ArrayDeque<Long>()
    private var launcherFailureValue: Throwable? = null
    private var terminalFallbackDispatchFailureValue: Throwable? = null
    private val jobDirectoryByGeneration = HashMap<Long, String>()

    private fun notifyDiagnosticEvent(event: CameraPipelineEvent) {
        try {
            callbacks.onDiagnosticEvent?.invoke(event)
        } catch (error: Throwable) {
            // Diagnostics are strictly passive. A recorder failure must not alter the
            // production session, terminal truth, or the original pipeline failure.
            runCatching {
                Log.w(TAG, "passive pipeline diagnostic observer failed", error)
            }
        }
    }

    private fun rememberHandoffJobDirectory(event: CameraPipelineEvent) {
        if (event is CameraPipelineEvent.CaptureStageComplete && event.handoffEvidenceComplete) {
            event.jobDirectoryPath?.let { path ->
                synchronized(this) { jobDirectoryByGeneration[event.generation] = path }
            }
        }
    }

    private fun enrichTerminalWithJobDirectory(terminal: CameraPipelineEvent.Terminal): CameraPipelineEvent.Terminal {
        if (terminal.jobDirectoryPath != null) return terminal
        val remembered = synchronized(this) { jobDirectoryByGeneration[terminal.generation] }
        return if (remembered != null) terminal.copy(jobDirectoryPath = remembered) else terminal
    }

    fun updateCallbacks(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    internal fun terminalUiDeliveryOutcome(): TerminalUiDeliveryOutcome? =
        synchronized(this) { terminalUiDeliveryOutcomeValue }

    internal fun launcherFailure(): Throwable? = synchronized(this) { launcherFailureValue }

    internal fun terminalFallbackDispatchFailure(): Throwable? =
        synchronized(this) { terminalFallbackDispatchFailureValue }

    /** Retries only the UI notification; it never asks the native pipeline to republish. */
    internal fun reconcileTerminalUiDelivery(): CameraUiDispatchOutcome {
        val terminal = synchronized(this) {
            if (terminalUiDeliveryOutcomeValue == TerminalUiDeliveryOutcome.ACCEPTED) return CameraUiDispatchOutcome.ACCEPTED
            pendingTerminal
        } ?: return CameraUiDispatchOutcome.REJECTED
        if (session.snapshot().generation != terminal.generation) {
            synchronized(this) {
                if (pendingTerminal?.generation == terminal.generation) {
                    pendingTerminal = null
                    terminalUiDeliveryOutcomeValue = null
                }
                recordStaleTerminalUiNotificationLocked(terminal.generation)
            }
            return CameraUiDispatchOutcome.REJECTED
        }
        return dispatchTerminalNotification(terminal)
    }

    /**
     * Returns true only when the asynchronous job launch was scheduled. A busy/rejected request or
     * a proven pre-resource scheduling failure returns false; job-body failures settle separately.
     */
    fun start(
        startMessage: String,
        requestedFrames: Int = 0,
        timeoutMillis: Long = 120_000L,
        job: CameraPipelineUiJob
    ): Boolean {
        val started = session.start(startMessage, requestedFrames)
        if (started is CameraPipelineUiSession.StartResult.Rejected) {
            callbacks.onStatus("촬영 리소스가 사용 중입니다. 현재 처리 중인 작업이 완료된 후 다시 시도해 주세요.")
            return false
        }
        val operation = (started as CameraPipelineUiSession.StartResult.Accepted).operation
        val generation = operation.generation
        synchronized(this) {
            pendingTerminal = null
            terminalUiNotifiedGeneration = null
            terminalUiDeliveryOutcomeValue = null
            launcherFailureValue = null
            terminalFallbackDispatchFailureValue = null
        }
        val token = operation.cancellationToken
        val captureCancellation = operation.captureCancellation
        callbacks.onStatus(startMessage)
        callbacks.onStateChanged()

        lateinit var watchdog: Runnable
        watchdog = Runnable {
            session.clearWatchdog(generation)?.let(scheduler::remove)
            if (!session.requestCancellation(generation, "watchdog timeout")) return@Runnable
            callbacks.onStatus("CAPTURE_TIMEOUT: Cancellation requested; waiting for terminal settlement.")
            callbacks.onStateChanged()
            val fallback = Runnable {
                session.clearTerminalFallback(generation)
                if (session.markTerminalDeliveryFailed(
                        generation,
                        "CAPTURE_TIMEOUT: Terminal delivery unresolved; capture remains blocked."
                    )
                ) {
                    callbacks.onStatus("CAPTURE_TIMEOUT: Terminal delivery unresolved; capture remains blocked.")
                    callbacks.onStateChanged()
                }
            }
            if (!session.attachTerminalFallback(generation, fallback)) return@Runnable
            when (postSafely(15_000L, fallback)) {
                CameraUiDispatchOutcome.ACCEPTED -> Unit
                CameraUiDispatchOutcome.REJECTED,
                CameraUiDispatchOutcome.DISPATCH_THREW -> {
                    session.clearTerminalFallback(generation)
                    recordTerminalFallbackDispatchFailure(IllegalStateException("terminal fallback dispatch failed"))
                    if (session.markTerminalDeliveryFailed(
                            generation,
                            "CAPTURE_TIMEOUT: Terminal delivery fallback could not be scheduled; capture remains blocked."
                        )
                    ) {
                        callbacks.onStatus("CAPTURE_TIMEOUT: Terminal delivery fallback could not be scheduled; capture remains blocked.")
                        callbacks.onStateChanged()
                    }
                }
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
            // Phase D: split concerns - route diagnostics/background identity always,
            // then independently decide if event mutates CURRENT foreground UI session.
            // An old background terminal may be STALE_FOR_FOREGROUND but VALID_FOR_BACKGROUND_JOB_A.
            notifyDiagnosticEvent(event)
            rememberHandoffJobDirectory(event)
            when (session.accept(event)) {
                CameraPipelineUiSession.EventResult.ACCEPTED -> {
                    // The capture watchdog ends at durable capture settlement:
                    // once handoff evidence is accepted, a slow background
                    // fusion/export must not be cancelled by capture timing.
                    if (event is CameraPipelineEvent.CaptureStageComplete &&
                        event.handoffEvidenceComplete
                    ) {
                        session.clearWatchdog(generation)?.let(scheduler::remove)
                        session.clearScheduledStart(generation)?.let(scheduler::remove)
                    }
                    val terminal = event as? CameraPipelineEvent.Terminal ?: return
                    val enriched = enrichTerminalWithJobDirectory(terminal)
                    synchronized(this@CameraPipelineUiOrchestrator) {
                        pendingTerminal = enriched
                    }
                    clearScheduledWork(generation)
                    dispatchTerminalNotification(enriched)
                }
                CameraPipelineUiSession.EventResult.DUPLICATE_TERMINAL -> Unit
                CameraPipelineUiSession.EventResult.LATE_AFTER_TERMINAL -> Unit
                CameraPipelineUiSession.EventResult.STALE,
                CameraPipelineUiSession.EventResult.DISPOSED -> {
                    Log.i(TAG, "stale pipeline event ignored for foreground generation=$generation background generation=${event.generation}")
                    val terminal = event as? CameraPipelineEvent.Terminal
                    if (terminal != null) {
                        val enriched = enrichTerminalWithJobDirectory(terminal)
                        try {
                            callbacks.onBackgroundTerminal?.invoke(enriched)
                        } catch (error: Throwable) {
                            runCatching { Log.w(TAG, "background terminal callback failed", error) }
                        }
                    }
                }
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
                        when (postSafely(0L, Runnable {
                            if (session.acceptsDisplayUpdate(generation)) {
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
                            // Phase D: route diagnostics/background identity to correct run/job always,
                            // independently of whether it mutates CURRENT foreground UI session.
                            // Do NOT gate diagnostic on session.accept or generation check.
                            notifyDiagnosticEvent(event)
                            rememberHandoffJobDirectory(event)
                            when (postSafely(0L, Runnable {
                                if (session.snapshot().generation != generation) {
                                    // STALE_FOR_FOREGROUND but VALID_FOR_BACKGROUND - diagnostic already routed.
                                    return@Runnable
                                }
                                when (session.accept(event)) {
                                    CameraPipelineUiSession.EventResult.ACCEPTED -> {
                                        // The capture watchdog ends at durable
                                        // capture settlement (shared rule with
                                        // the synchronous acceptEvent path).
                                        if (event is CameraPipelineEvent.CaptureStageComplete &&
                                            event.handoffEvidenceComplete
                                        ) {
                                            session.clearWatchdog(generation)?.let(scheduler::remove)
                                            session.clearScheduledStart(generation)?.let(scheduler::remove)
                                        }
                                        notifyNonTerminal(event)
                                    }
                                    CameraPipelineUiSession.EventResult.DUPLICATE_TERMINAL,
                                    CameraPipelineUiSession.EventResult.LATE_AFTER_TERMINAL -> Unit
                                    CameraPipelineUiSession.EventResult.STALE,
                                    CameraPipelineUiSession.EventResult.DISPOSED -> Unit
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
            } catch (interruption: CancellationException) {
                recordLauncherInterruption(
                    generation = generation,
                    failure = interruption,
                    status = "취소 요청을 처리하고 있습니다."
                )
            } catch (failure: Exception) {
                recordLauncherInterruption(
                    generation = generation,
                    failure = failure,
                    status = "카메라 파이프라인 오류를 처리하고 있습니다."
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
                return false
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
        session.clearTerminalFallback(generation)?.let(scheduler::remove)
    }

    private fun recordLauncherInterruption(
        generation: Long,
        failure: Throwable,
        status: String
    ) {
        synchronized(this) {
            launcherFailureValue = failure
        }
        val message = "PIPELINE_FAILED: ${failure.javaClass.simpleName}"
        if (!session.markLauncherFailureAwaitingTerminal(generation, message)) return
        session.clearWatchdog(generation)?.let(scheduler::remove)
        session.clearScheduledStart(generation)?.let(scheduler::remove)
        if (session.hasTerminalClaimed(generation)) return
        callbacks.onStatus(status)
        callbacks.onStateChanged()
        val fallback = Runnable {
            session.clearTerminalFallback(generation)
            if (session.markTerminalDeliveryFailed(
                    generation,
                    "PIPELINE_FAILED: Terminal delivery unresolved after launcher failure; capture remains blocked."
                )
            ) {
                callbacks.onStatus("PIPELINE_FAILED: Terminal delivery unresolved after launcher failure; capture remains blocked.")
                callbacks.onStateChanged()
            }
        }
        if (!session.attachTerminalFallback(generation, fallback)) return
        when (postSafely(15_000L, fallback)) {
            CameraUiDispatchOutcome.ACCEPTED -> Unit
            CameraUiDispatchOutcome.REJECTED,
            CameraUiDispatchOutcome.DISPATCH_THREW -> {
                session.clearTerminalFallback(generation)
                recordTerminalFallbackDispatchFailure(IllegalStateException("launcher failure terminal fallback dispatch failed"))
                if (session.markTerminalDeliveryFailed(
                        generation,
                        "PIPELINE_FAILED: Terminal delivery fallback could not be scheduled; capture remains blocked."
                    )
                ) {
                    callbacks.onStatus("PIPELINE_FAILED: Terminal delivery fallback could not be scheduled; capture remains blocked.")
                    callbacks.onStateChanged()
                }
            }
        }
    }

    private fun recordTerminalFallbackDispatchFailure(failure: Throwable) {
        synchronized(this) {
            terminalFallbackDispatchFailureValue = failure
        }
    }

    private fun postSafely(delayMillis: Long, work: Runnable): CameraUiDispatchOutcome = try {
        scheduler.post(delayMillis, work)
    } catch (_: Throwable) {
        CameraUiDispatchOutcome.DISPATCH_THREW
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
                val snapshot = session.snapshot()
                if (snapshot.generation != terminal.generation ||
                    snapshot.phase == CameraPipelineUiSession.Phase.DISPOSED
                ) {
                    synchronized(this@CameraPipelineUiOrchestrator) {
                        recordStaleTerminalUiNotificationLocked(terminal.generation)
                    }
                    return@Runnable
                }
                synchronized(this@CameraPipelineUiOrchestrator) {
                    if (terminalUiNotifiedGeneration == terminal.generation) return@Runnable
                    terminalUiNotifiedGeneration = terminal.generation
                }
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

    private fun recordStaleTerminalUiNotificationLocked(generation: Long) {
        if (staleTerminalUiNotifications.size >= MAX_STALE_TERMINAL_UI_NOTIFICATIONS) {
            staleTerminalUiNotifications.removeFirst()
        }
        staleTerminalUiNotifications.addLast(generation)
    }

    private companion object {
        const val TAG = "KeplerPipelineState"
        const val MAX_STALE_TERMINAL_UI_NOTIFICATIONS = 8
    }
}
