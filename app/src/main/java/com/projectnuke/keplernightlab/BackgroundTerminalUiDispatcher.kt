package com.projectnuke.keplernightlab

import java.io.File

/**
 * Seam between the heavy background worker thread and the camera-owned Compose
 * UI scope for observational [BackgroundPipelineEventHub] events.
 *
 * Contract:
 *  - DIAGNOSTICS are immediate and thread-safe: the recorder callback runs on
 *    the calling (worker) thread and never waits for UI dispatch.
 *  - ALL Compose/UI mutation is DISPATCHED onto the camera-owned main
 *    [CameraUiScheduler]; this worker callback never touches Compose state.
 *  - FOREGROUND TRUTH is re-queried INSIDE the dispatched block from the
 *    synchronized session authority ([CameraPipelineUiSession.snapshot]). A
 *    captured foreground snapshot from event time is never used to decide
 *    whether a previous job's result may cover the CURRENT capture UI: a
 *    terminal for job A arriving while capture B owns the foreground refreshes
 *    data only — it never pops a result preview over B, and an idle foreground
 *    may show it.
 *  - RESULT identity drives the refreshed directory (SR output directory),
 *    falling back to the request identity when no result directory exists.
 */
internal class BackgroundTerminalUiDispatcher(
    private val session: CameraPipelineUiSession,
    private val scheduler: CameraUiScheduler,
    private val recordDiagnostic: (BackgroundPipelineEvent) -> Unit,
    private val refreshResult: (showPreview: Boolean, exactJobDir: File?) -> Unit,
    private val onDispatchFailure: ((Throwable) -> Unit)? = null
) {
    fun onBackgroundEvent(background: BackgroundPipelineEvent) {
        // 1. Immediate diagnostics on the worker thread (never waits for UI).
        recordDiagnostic(background)
        val terminal = background.event as? CameraPipelineEvent.Terminal ?: return

        // 2. Result identity decides which directory is refreshed.
        val resultDir = background.resultJobDirectory.takeIf { it.isDirectory }
            ?: background.requestJobDirectory.takeIf { it.isDirectory }
            ?: return

        val wantsPreview = (terminal.kind == CameraPipelineEvent.Terminal.Kind.COMPLETE ||
            terminal.kind == CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL) &&
            terminal.requiredOutputCommitted

        // 3. Dispatch ALL UI work onto the camera-owned scope. The foreground
        //    truth is re-read inside the dispatched block — not here.
        val outcome = try {
            scheduler.post(0L) { dispatchUi(wantsPreview, resultDir) }
        } catch (failure: Throwable) {
            onDispatchFailure?.invoke(failure)
            null
        } ?: return
        if (outcome != CameraUiDispatchOutcome.ACCEPTED) {
            // A rejected/thrown dispatch must never silently lose the refresh:
            // report through the failure hook; state stays untouched.
            onDispatchFailure?.invoke(IllegalStateException("background UI dispatch $outcome"))
        }
    }

    private fun dispatchUi(wantsPreview: Boolean, resultDir: File) {
        // Current foreground authority at delivery time — never the stale
        // event-time snapshot.
        val snapshot = session.snapshot()
        val foregroundIdle = !snapshot.isCapturing && snapshot.canAdmitNewCapture
        refreshResult(
            wantsPreview && foregroundIdle,
            resultDir
        )
    }
}
