package com.projectnuke.keplernightlab

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.media.ImageReader
import android.os.HandlerThread
import java.util.concurrent.ScheduledExecutorService

internal enum class RawCoordinatorPhase { OPEN, CLEANING, CLOSED }
internal enum class RawAttachmentDisposition {
    ACCEPTED, ALREADY_OWNED, SETTLED_DUPLICATE, SETTLED_LATE, NO_RESOURCE
}

internal data class RawProductionReleaseRecord(
    val tag: String,
    val attempted: Boolean,
    val succeeded: Boolean,
    val failure: Throwable? = null
)

internal data class RawProductionCleanupSnapshot(
    val phase: RawCoordinatorPhase,
    val records: List<RawProductionReleaseRecord>,
    val lateAttachments: Int,
    val duplicateAttachments: Int,
    val workerCleanupReport: BoundedCaptureWorker.CleanupReport? = null
) {
    val performed: Boolean get() = phase != RawCoordinatorPhase.OPEN
}

/** Sole owner of RAW Camera2/infrastructure resources after creation. */
internal class RawProductionResourceCoordinator(
    private val timeoutScheduler: ScheduledExecutorService,
    private val saveWorker: BoundedCaptureWorker,
    private val backgroundThread: HandlerThread
) {
    private val lock = Any()
    private var phase = RawCoordinatorPhase.OPEN
    private var lateAttachments = 0
    private var duplicateAttachments = 0
    private var workerCleanupReport: BoundedCaptureWorker.CleanupReport? = null
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var motionLogger: MotionLogger? = null
    private val records = mutableListOf<RawProductionReleaseRecord>()

    fun attachImageReader(value: ImageReader?): RawAttachmentDisposition =
        attach(value, "ImageReader", imageReader, { imageReader = it }, { it.setOnImageAvailableListener(null, null); it.close() })
    fun attachCameraDevice(value: CameraDevice?): RawAttachmentDisposition =
        attach(value, "CameraDevice", cameraDevice, { cameraDevice = it }, { it.close() })
    fun attachCaptureSession(value: CameraCaptureSession?): RawAttachmentDisposition =
        attach(value, "CaptureSession", captureSession, { captureSession = it }, { releaseSession(it) })
    fun attachMotionLogger(value: MotionLogger?): RawAttachmentDisposition =
        attach(value, "MotionLogger", motionLogger, { motionLogger = it }, { it.stop() })

    private fun releaseSession(session: CameraCaptureSession) {
        var first: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (t: Throwable) { if (first == null) first = t }
        }
        attempt { session.abortCaptures() }
        attempt { session.stopRepeating() }
        attempt { session.close() }
        first?.let { throw it }
    }

    private fun <T : Any> attach(
        value: T?,
        tag: String,
        current: T?,
        store: (T) -> Unit,
        settle: (T) -> Unit
    ): RawAttachmentDisposition {
        if (value == null) return RawAttachmentDisposition.NO_RESOURCE
        synchronized(lock) {
            if (phase == RawCoordinatorPhase.OPEN) {
                if (current === value) {
                    duplicateAttachments++
                    return RawAttachmentDisposition.ALREADY_OWNED
                }
                if (current == null) {
                    store(value)
                    return RawAttachmentDisposition.ACCEPTED
                }
                duplicateAttachments++
            } else {
                lateAttachments++
            }
        }
        settleOutsideLock(tag, value, settle)
        return synchronized(lock) {
            if (phase == RawCoordinatorPhase.OPEN) RawAttachmentDisposition.SETTLED_DUPLICATE
            else RawAttachmentDisposition.SETTLED_LATE
        }
    }

    fun perform(): RawProductionCleanupSnapshot {
        val resources: List<Pair<String, () -> Unit>>
        synchronized(lock) {
            when (phase) {
                RawCoordinatorPhase.CLOSED, RawCoordinatorPhase.CLEANING -> return snapshotLocked()
                RawCoordinatorPhase.OPEN -> phase = RawCoordinatorPhase.CLEANING
            }
            resources = listOfNotNull(
                imageReader?.let { "ImageReader" to { it.setOnImageAvailableListener(null, null); it.close() } },
                captureSession?.let { "CaptureSession" to { releaseSession(it) } },
                cameraDevice?.let { "CameraDevice" to { it.close() } },
                motionLogger?.let { "MotionLogger" to { it.stop() } }
            )
            imageReader = null
            captureSession = null
            cameraDevice = null
            motionLogger = null
        }
        resources.forEach { (tag, release) -> settle(tag, release) }
        settle("TimeoutScheduler", { timeoutScheduler.shutdownNow() })
        val report = saveWorker.shutdownNow()
        synchronized(lock) { workerCleanupReport = report }
        settle("HandlerThread", { backgroundThread.quitSafely() })
        synchronized(lock) { phase = RawCoordinatorPhase.CLOSED }
        return snapshot()
    }

    fun snapshot(): RawProductionCleanupSnapshot = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked() = RawProductionCleanupSnapshot(
        phase = phase,
        records = records.toList(),
        lateAttachments = lateAttachments,
        duplicateAttachments = duplicateAttachments,
        workerCleanupReport = workerCleanupReport
    )

    private fun <T : Any> settleOutsideLock(tag: String, value: T, release: (T) -> Unit) =
        settle(tag) { release(value) }

    private fun settle(tag: String, release: () -> Unit) {
        try {
            release()
            synchronized(lock) { records += RawProductionReleaseRecord(tag, true, true) }
        } catch (t: Throwable) {
            synchronized(lock) { records += RawProductionReleaseRecord(tag, true, false, t) }
        }
    }
}
