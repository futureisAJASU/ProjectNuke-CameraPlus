package com.projectnuke.keplernightlab

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.media.ImageReader
import android.os.HandlerThread
import java.util.concurrent.ScheduledExecutorService

internal enum class RawAttachmentDisposition { ACCEPTED, SETTLED_LATE, NO_RESOURCE }

internal data class RawProductionReleaseRecord(
    val tag: String,
    val attempted: Boolean,
    val succeeded: Boolean,
    val failure: Throwable? = null
)

internal data class RawProductionCleanupSnapshot(
    val performed: Boolean,
    val records: List<RawProductionReleaseRecord>,
    val lateAttachments: Int
)

/** Sole owner of RAW Camera2/infrastructure resources after creation. */
internal class RawProductionResourceCoordinator(
    private val timeoutScheduler: ScheduledExecutorService,
    private val saveWorker: BoundedCaptureWorker,
    private val backgroundThread: HandlerThread
) {
    private val lock = Any()
    private var performed = false
    private var lateAttachments = 0
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var motionLogger: MotionLogger? = null
    private val records = mutableListOf<RawProductionReleaseRecord>()

    fun attachImageReader(value: ImageReader?): RawAttachmentDisposition = attach(value, "ImageReader", { it.close() }) { imageReader = it }
    fun attachCameraDevice(value: CameraDevice?): RawAttachmentDisposition = attach(value, "CameraDevice", { it.close() }) { cameraDevice = it }
    fun attachCaptureSession(value: CameraCaptureSession?): RawAttachmentDisposition = attach(value, "CaptureSession", { it.close() }) { captureSession = it }
    fun attachMotionLogger(value: MotionLogger?): RawAttachmentDisposition = attach(value, "MotionLogger", { it.stop() }) { motionLogger = it }

    private fun <T : Any> attach(value: T?, tag: String, close: (T) -> Unit, store: (T) -> Unit): RawAttachmentDisposition {
        if (value == null) return RawAttachmentDisposition.NO_RESOURCE
        val accepted = synchronized(lock) {
            if (!performed && when (tag) {
                    "ImageReader" -> imageReader == null
                    "CameraDevice" -> cameraDevice == null
                    "CaptureSession" -> captureSession == null
                    else -> motionLogger == null
                }) { store(value); true } else { lateAttachments++; false }
        }
        if (accepted) return RawAttachmentDisposition.ACCEPTED
        settle(tag) { close(value) }
        return RawAttachmentDisposition.SETTLED_LATE
    }

    fun perform(): RawProductionCleanupSnapshot {
        val resources: List<Pair<String, () -> Unit>>
        synchronized(lock) {
            if (performed) return snapshotLocked()
            performed = true
            resources = listOfNotNull(
                imageReader?.let { "ImageReader" to { it.close() } },
                captureSession?.let { "CaptureSession" to { runCatching { it.abortCaptures() }.getOrThrow(); it.stopRepeating(); it.close() } },
                cameraDevice?.let { "CameraDevice" to { it.close() } },
                motionLogger?.let { "MotionLogger" to { it.stop() } }
            )
            imageReader = null; captureSession = null; cameraDevice = null; motionLogger = null
        }
        resources.forEach { (tag, close) -> settle(tag, close) }
        settle("TimeoutScheduler", { timeoutScheduler.shutdownNow() })
        settle("SaveWorker", { saveWorker.shutdownNow() })
        settle("HandlerThread", { backgroundThread.quitSafely() })
        return snapshot()
    }

    fun snapshot(): RawProductionCleanupSnapshot = synchronized(lock) { snapshotLocked() }
    private fun snapshotLocked() = RawProductionCleanupSnapshot(performed, records.toList(), lateAttachments)

    private fun settle(tag: String, close: () -> Unit) {
        try { close(); synchronized(lock) { records += RawProductionReleaseRecord(tag, true, true) } }
        catch (t: Throwable) { synchronized(lock) { records += RawProductionReleaseRecord(tag, true, false, t) } }
    }
}
