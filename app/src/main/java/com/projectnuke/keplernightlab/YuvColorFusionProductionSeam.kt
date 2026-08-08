package com.projectnuke.keplernightlab

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.ScheduledExecutorService

/**
 * The REAL ColorFusion YUV production seam: the single construction point for the
 * production Main-thread dispatchers, the pre-session terminal, the production
 * resource coordinator, and exactly-once production cleanup.
 *
 * `captureYuvBurstColorWithMotion` uses this seam instead of constructing the
 * dispatchers inline, so the seam can be exercised as a unit (Robolectric)
 * without camera hardware.  All dispatch is acceptance-reporting and never inline:
 * a rejected status/error dispatch is a diagnostic only, never an inline fallback.
 */
internal class YuvColorFusionProductionSeam(
    private val mainHandler: Handler,
    private val timeoutScheduler: ScheduledExecutorService,
    private val backgroundHandler: Handler,
    private val backgroundThread: HandlerThread,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    /** Exactly-once idempotent owner of production Camera2/infrastructure resources. */
    val productionResourceCoordinator = YuvProductionResourceCoordinator(
        timeoutScheduler = timeoutScheduler,
        backgroundHandler = backgroundHandler,
        backgroundThread = backgroundThread
    )

    /** Acceptance-reporting Main-thread status dispatcher (never inline). */
    val statusDispatcher = YuvStatusDispatcher(mainHandler) { onStatus(it) }

    /** Acceptance-reporting Main-thread callback dispatcher (never inline). */
    val callbackDispatcher = YuvProductionCallbackDispatcher(mainHandler)

    /**
     * Late-bound by ColorFusion after [YuvCaptureSession] creation; production
     * cleanup closes the session (internal cleanup) through this hook.  Pre-session
     * cleanup leaves it null and skips the session close.
     */
    var sessionClose: (() -> Unit)? = null

    fun postStatus(message: String) {
        statusDispatcher.dispatch(message)
    }

    /**
     * Pre-session terminal: owns exactly-once claim, status/error dispatch through
     * acceptance-reporting Main dispatchers (never inline), and production cleanup.
     * After [YuvCaptureSession] becomes authoritative this path is no longer used.
     */
    val preSessionTerminal = YuvPreSessionTerminal(
        dispatchStatus = { message -> statusDispatcher.dispatch(message) },
        dispatchError = { message -> callbackDispatcher.dispatch(Runnable { onError(message) }) },
        cleanup = { productionCleanup() }
    )

    /**
     * Production resource cleanup: exactly-once, idempotent.  Closes Camera2
     * resources, detaches callbacks, stops motion logger, shuts down scheduler and
     * background thread, and closes the session if one was created.
     */
    fun productionCleanup() {
        productionResourceCoordinator.perform()
        try { sessionClose?.invoke() } catch (_: Exception) {}
    }
}
