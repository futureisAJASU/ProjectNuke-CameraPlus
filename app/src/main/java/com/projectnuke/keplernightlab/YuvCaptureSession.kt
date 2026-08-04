package com.projectnuke.keplernightlab

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production session seam for the YUV color-burst capture pipeline. Owns the
 * shared state of one capture (dispatch owner, bounded encoder worker, finished
 * flag, terminal state, accounting, lifecycle, reservation, identity owner,
 * and the authoritative [YuvCaptureOwner]) and provides a single close path.
 *
 * Construction does NOT start the camera session; the caller wires the
 * returned components into Camera2 (ImageReader listeners, capture callbacks)
 * and the timeout scheduler / cancellation handle, then calls
 * [YuvCaptureOwner.onDeadlineReached] or [YuvCaptureOwner.onCancellationRequested]
 * from those producers as the only legal way to settle the terminal state.
 *
 * Components exposed for the production caller:
 * - [captureStateOwner] : post deadline/cancellation through this.
 * - [boundedWorker] : encoder worker; managed by [close].
 * - [finished] : idempotent flag for late camera callbacks.
 * - [terminalState] : readable terminal status (claim is owner-only).
 * - [accounting], [lifecycle], [reservations], [identityOwner]
 * : shared inspection access (mutation is owner-only).
 * - [owner] : the only legal entry point for ImageReader callbacks
 * and capture-result/failure callbacks.
 */
internal class YuvCaptureSession internal constructor(
    val captureStateOwner: CaptureStateOwner,
    val boundedWorker: BoundedCaptureWorker,
    val finished: AtomicBoolean,
    val terminalState: CaptureTerminalState,
    val accounting: YuvCaptureAccounting,
    val lifecycle: YuvBufferedLifecycle,
    val reservations: YuvBufferReservations,
    val identityOwner: CaptureFrameIdentityOwner,
    val owner: YuvCaptureOwner
) : AutoCloseable {
    override fun close() {
        captureStateOwner.close()
        boundedWorker.shutdownNow()
    }

    fun terminalSnapshot(): YuvCaptureOwner.TerminalSnapshot = owner.terminalSnapshot()

    companion object {
        fun create(
            dispatch: (CaptureOwnerEvent) -> Boolean,
            outputDir: File,
            frameCount: Int,
            rotationDegrees: Int,
            workerCapacity: Int,
            maxRetainedBytes: Long,
            workerName: String = "YuvCapture-$frameCount",
            workProcessor: YuvPngWorkProcessor,
            postStatus: (String) -> Unit = {},
            postMainOrRun: (Runnable) -> Unit = { _ -> },
            writeJobJson: (status: String, savedFrames: Int, manifest: List<YuvFrameManifestEntry>) -> Unit = { _, _, _ -> },
            saveMotionOnce: (File) -> Pair<String?, String?> = { _ -> null to null },
            onCaptureComplete: (File) -> Unit = {},
            onCaptureError: (message: String, cause: Throwable?) -> Unit = { _, _ -> }
        ): YuvCaptureSession {
            val captureStateOwner = CaptureStateOwner(dispatch)
            val boundedWorker = BoundedCaptureWorker(workerName, workerCapacity)
            val finished = AtomicBoolean(false)
            val reservations = YuvBufferReservations(maxRetainedBytes)
            val accounting = YuvCaptureAccounting()
            val lifecycle = YuvBufferedLifecycle()
            val identityOwner = CaptureFrameIdentityOwner(frameCount)
            val terminalState = CaptureTerminalState()
            val owner = YuvCaptureOwner(
                captureStateOwner = captureStateOwner,
                outputDir = outputDir,
                rotationDegrees = rotationDegrees,
                frameCount = frameCount,
                workProcessor = workProcessor,
                reservations = reservations,
                accounting = accounting,
                lifecycle = lifecycle,
                identityOwner = identityOwner,
                terminalState = terminalState,
                boundedWorker = boundedWorker,
                finished = finished,
                postStatus = postStatus,
                postMainOrRun = postMainOrRun,
                writeJobJson = writeJobJson,
                saveMotionOnce = saveMotionOnce,
                onCaptureComplete = onCaptureComplete,
                onCaptureError = onCaptureError
            )
            return YuvCaptureSession(
                captureStateOwner,
                boundedWorker,
                finished,
                terminalState,
                accounting,
                lifecycle,
                reservations,
                identityOwner,
                owner
            )
        }
    }
}
