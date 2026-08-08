package com.projectnuke.keplernightlab

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.ScheduledExecutorService
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
 * Synchronization domain: ONE coordinator lock guards the lifecycle phase and the
 * ownership references.  Attachment and the OPEN -> CLEANING claim share that lock,
 * so a resource can never be stored after cleanup has atomically claimed ownership:
 *
 * ```
 * under coordinator lock:
 *     OPEN:        attachment may be stored
 *     perform:     OPEN -> CLEANING, snapshot every owned resource, clear ownership refs
 *     CLEANING/CLOSED: attachment is NOT stored; a late-attachment settlement action is
 *                      selected (the release itself runs OUTSIDE the lock)
 *     after release actions complete: CLEANING -> CLOSED
 * ```
 *
 * External resource code (close/stop/abort/quit) NEVER runs while holding the
 * coordinator lock.  The lock only selects ownership/action; release executes
 * outside the lock.
 *
 * Every owned-resource release attempt produces a structured
 * [ProductionResourceReleaseRecord].  Late attachments after CLEANING/CLOSED produce
 * the same structured result.  There is no `catch (_: Exception) {}` for owned
 * production resources: every failure is recorded in the ledger and remains
 * observable in [ProductionCleanupSnapshot].
 *
 * Repeated [perform] semantics: the FIRST call claims ownership (OPEN -> CLEANING),
 * releases every owned resource, then publishes CLOSED.  Every later call is a
 * no-op that returns the CURRENT snapshot (CLOSED, performCount == 1).  A call made
 * while CLEANING also returns the current snapshot and never re-runs release.
 */
internal enum class CoordinatorLifecyclePhase { OPEN, CLEANING, CLOSED }

/** Structured outcome of one resource release attempt (owned or late attachment). */
internal data class ProductionResourceReleaseRecord(
    val resourceType: String,
    val action: String,
    val lateAttachment: Boolean = false,
    val attempted: Boolean = true,
    val succeeded: Boolean = false,
    val failure: Throwable? = null
)

/**
 * Immutable snapshot of the cleanup ledger.  Never stale: it is rebuilt from the
 * thread-safe ledger on every read, so a late attachment settled after CLOSED is
 * visible in every subsequent snapshot.
 */
internal data class ProductionCleanupSnapshot(
    val phase: CoordinatorLifecyclePhase,
    val performCount: Int,
    val initialResourceCount: Int,
    val releaseAttempts: Int,
    val releaseSuccesses: Int,
    val releaseFailures: Int,
    val lateAttachmentCount: Int,
    val lateAttachmentSettlementFailures: Int,
    val records: List<ProductionResourceReleaseRecord>
) {
    val isTerminal: Boolean get() = phase == CoordinatorLifecyclePhase.CLOSED
}

internal class YuvProductionResourceCoordinator(
    private val timeoutScheduler: ScheduledExecutorService?,
    private val backgroundHandler: Handler?,
    private val backgroundThread: HandlerThread?
) {
    // Camera2 resources are created asynchronously AFTER the coordinator exists
    // (ImageReader/MotionLogger during setup, CameraDevice/CameraCaptureSession in
    // onOpened/onConfigured callbacks).  ColorFusion attaches each resource the moment
    // it becomes available so that perform() always releases the LATEST references.
    // These references are ONLY read/written under [coordinatorLock].
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var motionLogger: MotionLogger? = null

    // The ONE synchronization domain: lifecycle phase + ownership + ledger.
    private val coordinatorLock = Any()
    private var phase = CoordinatorLifecyclePhase.OPEN
    private var performCount = 0
    private var releaseAttempts = 0
    private var releaseSuccesses = 0
    private var releaseFailures = 0
    private var lateAttachmentCount = 0
    private var lateAttachmentSettlementFailures = 0
    private val records = mutableListOf<ProductionResourceReleaseRecord>()
    private val releasedTags = mutableListOf<String>()

    fun lifecyclePhase(): CoordinatorLifecyclePhase = synchronized(coordinatorLock) { phase }

    fun performCount(): Int = synchronized(coordinatorLock) { performCount }

    /** Current immutable cleanup snapshot; always fresh, never stale. */
    fun snapshot(): ProductionCleanupSnapshot = buildSnapshot()

    /** Resource tags released by the single [perform]; empty until perform runs. */
    internal fun releasedResourceTags(): List<String> = synchronized(coordinatorLock) { releasedTags.toList() }

    // ------------------------------------------------------------------
    // Attachment: under the coordinator lock.  OPEN stores; CLEANING/CLOSED
    // selects an immediate-settlement action (release runs outside the lock).
    // ------------------------------------------------------------------

    fun attachImageReader(reader: ImageReader?) {
        val late = synchronized(coordinatorLock) {
            if (phase == CoordinatorLifecyclePhase.OPEN) {
                imageReader = reader
                false
            } else {
                lateAttachmentCount++
                true
            }
        }
        if (late) settleLateAttachment("ImageReader", "close") { reader?.close() }
    }

    fun attachCameraDevice(device: CameraDevice?) {
        val late = synchronized(coordinatorLock) {
            if (phase == CoordinatorLifecyclePhase.OPEN) {
                cameraDevice = device
                false
            } else {
                lateAttachmentCount++
                true
            }
        }
        if (late) settleLateAttachment("CameraDevice", "close") { device?.close() }
    }

    fun attachCaptureSession(session: CameraCaptureSession?) {
        val late = synchronized(coordinatorLock) {
            if (phase == CoordinatorLifecyclePhase.OPEN) {
                captureSession = session
                false
            } else {
                lateAttachmentCount++
                true
            }
        }
        if (late) settleLateAttachment("CaptureSession", "close") { session?.close() }
    }

    fun attachMotionLogger(logger: MotionLogger?) {
        val late = synchronized(coordinatorLock) {
            if (phase == CoordinatorLifecyclePhase.OPEN) {
                motionLogger = logger
                false
            } else {
                lateAttachmentCount++
                true
            }
        }
        if (late) settleLateAttachment("MotionLogger", "stop") { logger?.stop() }
    }

    // ------------------------------------------------------------------
    // perform: claim under the lock, release outside the lock, CLOSED under
    // the lock.  performCount stays exactly 1.
    // ------------------------------------------------------------------

    /**
     * Exactly-once cleanup.  Returns the CURRENT [ProductionCleanupSnapshot].
     * First call: claims ownership (OPEN -> CLEANING), releases every owned
     * resource outside the lock, then publishes CLOSED.  Later calls (including
     * concurrent ones) are no-ops returning the current snapshot.
     */
    fun perform(): ProductionCleanupSnapshot {
        // Claim under the lock: OPEN -> CLEANING, snapshot owned refs, clear them.
        // Returns true only when this call won the claim.
        var claim: ClaimedResources? = null
        synchronized(coordinatorLock) {
            if (phase == CoordinatorLifecyclePhase.OPEN) {
                phase = CoordinatorLifecyclePhase.CLEANING
                performCount = 1
                claim = ClaimedResources(
                    imageReader = imageReader,
                    cameraDevice = cameraDevice,
                    captureSession = captureSession,
                    motionLogger = motionLogger,
                    initialResourceCount = listOfNotNull(imageReader, cameraDevice, captureSession, motionLogger).size
                )
                imageReader = null
                cameraDevice = null
                captureSession = null
                motionLogger = null
            }
        }
        val owned = claim ?: return buildSnapshot()

        // Release every owned resource OUTSIDE the lock.
        val releaseRecords = mutableListOf<ProductionResourceReleaseRecord>()
        fun <T> release(resourceType: String, action: String, resource: T?, block: (T) -> Unit) {
            if (resource == null) return
            releaseRecords.add(recordRelease(resourceType, action) { block(resource) })
        }

        release("ImageReader", "listener", owned.imageReader) { it.setOnImageAvailableListener(null, null) }
        release("Background", "handler", backgroundHandler) { it.removeCallbacksAndMessages(null) }
        release("CaptureSession", "abort", owned.captureSession) { it.abortCaptures() }
        release("CaptureSession", "stop", owned.captureSession) { it.stopRepeating() }
        release("CaptureSession", "close", owned.captureSession) { it.close() }
        release("ImageReader", "close", owned.imageReader) { it.close() }
        release("CameraDevice", "close", owned.cameraDevice) { it.close() }
        release("MotionLogger", "stop", owned.motionLogger) { it.stop() }
        release("TimeoutScheduler", "shutdown", timeoutScheduler) { it.shutdownNow() }
        release("BackgroundThread", "quit", backgroundThread) { it.quitSafely() }

        // Publish CLOSED under the lock with the completed release ledger.
        synchronized(coordinatorLock) {
            phase = CoordinatorLifecyclePhase.CLOSED
            records.addAll(releaseRecords)
            releaseAttempts += releaseRecords.size
            releaseSuccesses += releaseRecords.count { it.succeeded }
            releaseFailures += releaseRecords.count { !it.succeeded }
            releaseRecords.forEach { record ->
                if (record.succeeded) {
                    releasedTags.add("${record.resourceType}.${record.action}")
                }
            }
        }
        return buildSnapshot()
    }

    private class ClaimedResources(
        val imageReader: ImageReader?,
        val cameraDevice: CameraDevice?,
        val captureSession: CameraCaptureSession?,
        val motionLogger: MotionLogger?,
        val initialResourceCount: Int
    )

    /** Runs the release action, records the structured result, then publishes. */
    private fun settleLateAttachment(resourceType: String, action: String, release: () -> Unit) {
        val record = recordRelease(resourceType, action, late = true) { release() }
        synchronized(coordinatorLock) {
            records.add(record)
            lateAttachmentSettlementFailures += if (record.succeeded) 0 else 1
        }
    }

    /** Executes [block]; the caller decides whether a null resource is recorded. */
    private fun recordRelease(
        resourceType: String,
        action: String,
        late: Boolean = false,
        block: () -> Unit
    ): ProductionResourceReleaseRecord {
        val failure: Throwable? = try {
            releaseInterceptor?.invoke(resourceType, action, block) ?: block()
            null
        } catch (t: Throwable) {
            Log.w("KeplerYuvCleanup", "release $resourceType.$action failed", t)
            t
        }
        return ProductionResourceReleaseRecord(
            resourceType = resourceType,
            action = action,
            lateAttachment = late,
            attempted = true,
            succeeded = failure == null,
            failure = failure
        )
    }

    private fun buildSnapshot(): ProductionCleanupSnapshot = synchronized(coordinatorLock) {
        ProductionCleanupSnapshot(
            phase = phase,
            performCount = performCount,
            initialResourceCount = records
                .asSequence()
                .filter { !it.lateAttachment }
                .map { it.resourceType }
                .distinct()
                .count(),
            releaseAttempts = releaseAttempts,
            releaseSuccesses = releaseSuccesses,
            releaseFailures = releaseFailures,
            lateAttachmentCount = lateAttachmentCount,
            lateAttachmentSettlementFailures = lateAttachmentSettlementFailures,
            records = records.toList()
        )
    }

    internal fun currentImageReader(): ImageReader? = synchronized(coordinatorLock) { imageReader }
    internal fun currentCameraDevice(): CameraDevice? = synchronized(coordinatorLock) { cameraDevice }
    internal fun currentCaptureSession(): CameraCaptureSession? = synchronized(coordinatorLock) { captureSession }
    internal fun currentMotionLogger(): MotionLogger? = synchronized(coordinatorLock) { motionLogger }

    /**
     * Internal test-only seam: intercepts a single resource release to simulate a
     * thrown failure or to deterministically pause perform() mid-release (CLEANING).
     * Production code NEVER sets this; when null the real release runs directly.
     * The interceptor MAY call [block] (real release) or skip it (simulated throw).
     */
    internal var releaseInterceptor: ((String, String, () -> Unit) -> Unit)? = null
}

/**
 * Production [CallbackDispatcher] that returns the ACTUAL [Handler.post] acceptance
 * result.  If Main dispatch rejects, the callback is NOT executed inline, and
 * [dispatch] returns false.  The caller (YuvCaptureOwner) records a diagnostic and
 * proceeds with cleanup — the terminal metadata stays valid and production cleanup
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
    private val terminalClaimed = java.util.concurrent.atomic.AtomicBoolean(false)

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
