package com.projectnuke.keplernightlab

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Exactly-once idempotent owner of production Camera2/infrastructure resources.
 *
 * This coordinator owns the lifecycle of resources that are NOT internal YUV
 * capture-state resources (those are owned by [YuvCleanupCoordinator]):
 *
 *  - [CameraCaptureSession] (abort/stop/close)
 *  - [ImageReader] (listener detach + close)
 *  - [CameraDevice] (close)
 *  - [MotionLogger] (stop)
 *  - timeout scheduler (shutdown)
 *  - background [HandlerThread] (quit)
 *
 * Terminal settlement must call [perform] exactly once so that Camera2 resources
 * are always released regardless of whether the user callback, metadata write,
 * or status dispatch succeeded or failed.  The coordinator is idempotent: a
 * second call is a no-op.
 *
 * Internal YUV cleanup ([YuvCleanupCoordinator]) and production cleanup
 * ([perform]) must BOTH be initiated at terminal settlement.
 */
/** Coordinator lifecycle state machine: OPEN -> CLEANING -> CLOSED. */
internal enum class CoordinatorLifecyclePhase { OPEN, CLEANING, CLOSED }

/** Immutable cleanup result published by a single [perform]. */
internal data class ProductionCleanupResult(
    val phase: String,
    val performCount: Int,
    val resourceReleaseAttempts: Int,
    val resourceReleaseSuccesses: Int,
    val resourceReleaseFailures: Int,
    val lateAttachmentsImmediatelySettled: Int
)

internal class YuvProductionResourceCoordinator(
    private val timeoutScheduler: ScheduledExecutorService?,
    private val backgroundHandler: Handler?,
    private val backgroundThread: HandlerThread?
) {
    // Camera2 resources are created asynchronously AFTER the coordinator exists
    // (ImageReader/MotionLogger during setup, CameraDevice/CameraCaptureSession in
    // onOpened/onConfigured callbacks).  ColorFusion attaches each resource the moment
    // it becomes available so that perform() always releases the LATEST references.
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var cameraDevice: CameraDevice? = null
    @Volatile private var captureSession: CameraCaptureSession? = null
    @Volatile private var motionLogger: MotionLogger? = null

    private val lifecycle = AtomicReference(CoordinatorLifecyclePhase.OPEN)
    private val started = AtomicBoolean(false)
    private val performRuns = AtomicInteger(0)
    // Observable release record for diagnostics/tests.  Production ignores it.
    private val releasedTags = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val releaseSuccessCount = AtomicInteger(0)
    private val releaseFailureCount = AtomicInteger(0)
    private val lateAttachmentsSettled = AtomicInteger(0)
    private val cleanupResultRef = AtomicReference<ProductionCleanupResult?>(null)
    private val attachLock = Any()

    fun lifecyclePhase(): CoordinatorLifecyclePhase = lifecycle.get()

    fun performCount(): Int = performRuns.get()

    fun cleanupResult(): ProductionCleanupResult? = cleanupResultRef.get()

    /** Resource tags released by the single [perform]; empty until perform runs. */
    internal fun releasedResourceTags(): List<String> = releasedTags.toList()

    fun attachImageReader(reader: ImageReader?) {
        synchronized(attachLock) {
            val phase = lifecycle.get()
            if (phase == CoordinatorLifecyclePhase.CLEANING || phase == CoordinatorLifecyclePhase.CLOSED) {
                lateAttachmentsSettled.incrementAndGet()
                reader?.close()
                return
            }
            imageReader = reader
        }
    }

    fun attachCameraDevice(device: CameraDevice?) {
        synchronized(attachLock) {
            val phase = lifecycle.get()
            if (phase == CoordinatorLifecyclePhase.CLEANING || phase == CoordinatorLifecyclePhase.CLOSED) {
                lateAttachmentsSettled.incrementAndGet()
                device?.close()
                return
            }
            cameraDevice = device
        }
    }

    fun attachCaptureSession(session: CameraCaptureSession?) {
        synchronized(attachLock) {
            val phase = lifecycle.get()
            if (phase == CoordinatorLifecyclePhase.CLEANING || phase == CoordinatorLifecyclePhase.CLOSED) {
                lateAttachmentsSettled.incrementAndGet()
                try { session?.close() } catch (_: Exception) {}
                return
            }
            captureSession = session
        }
    }

    fun attachMotionLogger(logger: MotionLogger?) {
        synchronized(attachLock) {
            val phase = lifecycle.get()
            if (phase == CoordinatorLifecyclePhase.CLEANING || phase == CoordinatorLifecyclePhase.CLOSED) {
                lateAttachmentsSettled.incrementAndGet()
                try { logger?.stop() } catch (_: Exception) {}
                return
            }
            motionLogger = logger
        }
    }

    /**
     * Atomic lifecycle transition OPEN -> CLEANING -> CLOSED, then exactly-once
     * release of every currently-attached production resource.  Returns true only
     * for the first successful invocation; publishes an immutable [ProductionCleanupResult].
     */
    fun perform(): Boolean {
        if (!lifecycle.compareAndSet(CoordinatorLifecyclePhase.OPEN, CoordinatorLifecyclePhase.CLEANING)) {
            // If already CLOSED, return false; if CLEANING, also no-op for idempotency.
            return lifecycle.get() == CoordinatorLifecyclePhase.CLOSED && started.get()
        }
        started.set(true)
        performRuns.incrementAndGet()
        var attempts = 0
        var successes = 0
        var failures = 0

        fun <T> release(tag: String, resource: T?, block: () -> Unit) {
            attempts++
            if (resource == null) return
            releasedTags.add(tag)
            try {
                block()
                successes++
            } catch (t: Throwable) {
                failures++
                Log.w("KeplerYuvCleanup", "release $tag failed", t)
            }
        }

        // Detach Camera2 callbacks first.
        release("ImageReader.listener", imageReader) { imageReader?.setOnImageAvailableListener(null, null) }
        // Remove pending background callbacks.
        release("Background.handler", backgroundHandler) { backgroundHandler?.removeCallbacksAndMessages(null) }
        // Camera session teardown.
        release("CaptureSession.abort", captureSession) { captureSession?.abortCaptures() }
        release("CaptureSession.stop", captureSession) { captureSession?.stopRepeating() }
        release("CaptureSession.close", captureSession) { captureSession?.close() }
        // ImageReader and CameraDevice.
        release("ImageReader.close", imageReader) { imageReader?.close() }
        release("CameraDevice.close", cameraDevice) { cameraDevice?.close() }
        // Motion logger.
        release("MotionLogger.stop", motionLogger) { motionLogger?.stop() }
        // Timeout scheduler.
        release("TimeoutScheduler.shutdown", timeoutScheduler) { timeoutScheduler?.shutdownNow() }
        // Background thread ??do NOT block Main waiting for the encoder or thread.
        release("BackgroundThread.quit", backgroundThread) { backgroundThread?.quitSafely() }

        lifecycle.set(CoordinatorLifecyclePhase.CLOSED)
        val result = ProductionCleanupResult(
            phase = "CLOSED",
            performCount = performRuns.get(),
            resourceReleaseAttempts = attempts,
            resourceReleaseSuccesses = successes,
            resourceReleaseFailures = failures,
            lateAttachmentsImmediatelySettled = lateAttachmentsSettled.get()
        )
        cleanupResultRef.set(result)
        return true
    }

    fun isStarted(): Boolean = started.get()
    internal fun currentImageReader(): ImageReader? = imageReader
    internal fun currentCameraDevice(): CameraDevice? = cameraDevice
    internal fun currentCaptureSession(): CameraCaptureSession? = captureSession
    internal fun currentMotionLogger(): MotionLogger? = motionLogger
}

/**
 * Production [CallbackDispatcher] that returns the ACTUAL [Handler.post] acceptance
 * result.  If Main dispatch rejects, the callback is NOT executed inline, and
 * [dispatch] returns false.  The caller (YuvCaptureOwner) records a diagnostic and
 * proceeds with cleanup ??the terminal metadata stays valid and production cleanup
 * still executes.
 */
internal class YuvProductionCallbackDispatcher(
    private val mainHandler: Handler
) : CallbackDispatcher {
    override fun dispatch(runnable: Runnable): Boolean {
        return try {
            mainHandler.post(runnable)
        } catch (e: Exception) {
            Log.e("KeplerYuvCallback", "Handler.post threw during callback dispatch", e)
            false
        }
    }
}

/**
 * Production status dispatcher that posts [onStatus] to the Main thread and
 * reports whether the post was accepted.  A rejected status dispatch becomes a
 * diagnostic; it is NEVER executed inline on the caller (worker/timeout) thread.
 */
internal class YuvStatusDispatcher(
    private val mainHandler: Handler,
    private val onStatus: (String) -> Unit
) {
    fun dispatch(message: String): Boolean {
        return try {
            mainHandler.post { onStatus(message) }
        } catch (e: Exception) {
            Log.e("KeplerYuvStatus", "Status dispatch threw", e)
            false
        }
    }
}

/**
 * Production pre-session terminal path used by ColorFusion for failures and
 * cancellations that occur BEFORE [YuvCaptureSession] becomes authoritative (e.g.
 * missing StreamConfigurationMap, no YUV sizes, Pictures dir unavailable, directory
 * creation failure, ImageReader creation failure, pre-session cancellation).
 *
 * It owns the exactly-once terminal claim, publishes the status message, dispatches
 * onError through an acceptance-reporting Main dispatcher (NEVER inline), and then
 * initiates production cleanup.  After [YuvCaptureSession] is created, terminal
 * authority moves to [YuvCaptureOwner] and this path is no longer used.
 *
 * A rejected status/error dispatch is recorded as a diagnostic and never executed on
 * the caller thread; cleanup still runs.
 */
internal class YuvPreSessionTerminal(
    private val dispatchStatus: (String) -> Boolean,
    private val dispatchError: (String) -> Boolean,
    private val cleanup: () -> Unit
) {
    private val terminalClaimed = AtomicBoolean(false)

    /**
     * Marks the outer pipeline terminal exactly once.  Returns true only when this call
     * was the first terminal claim; subsequent calls are no-ops (returns false).
     */
    fun finish(message: String): Boolean {
        if (!terminalClaimed.compareAndSet(false, true)) return false
        if (!dispatchStatus(message)) {
            Log.e("KeplerYuvPreSession", "pre-session status dispatch rejected; diagnostic only")
        }
        if (!dispatchError(message)) {
            Log.e("KeplerYuvPreSession", "pre-session onError dispatch rejected; diagnostic only")
        }
        cleanup()
        return true
    }

    fun isTerminal(): Boolean = terminalClaimed.get()
}

