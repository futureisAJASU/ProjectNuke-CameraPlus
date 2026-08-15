package com.projectnuke.keplernightlab

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler

/**
 * The real ColorFusion ImageReader callback body.  Keeping this in one production
 * component makes image acquisition, late-image settlement, and ownership transfer
 * testable without a second mirror of the callback.
 */
internal class YuvProductionImageBridge(
    private val ownerProvider: () -> YuvCaptureOwner?,
    private val isTerminal: () -> Boolean,
    private val onAcquireFailure: (Throwable) -> Unit,
    private val onReleaseFailure: (Throwable) -> Unit
) {
    fun onImageAvailable(reader: ImageReader, useMemoryBuffer: Boolean) {
        val image = try {
            reader.acquireNextImage()
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (t: Exception) {
            onAcquireFailure(t)
            return
        } ?: return

        // We acquired exactly one Image for this callback.  Before a successful
        // accept* call this bridge owns it; after that call the immutable owner event
        // owns/rejects and settles it exactly once.
        if (isTerminal()) {
            closeLateImage(image)
            return
        }
        val owner = ownerProvider()
        if (owner == null) {
            closeLateImage(image)
            return
        }
        if (useMemoryBuffer) {
            owner.acceptBuffered(Camera2YuvImageAccess(image))
        } else {
            owner.acceptDirect(Camera2DirectYuvImageAccess(image))
        }
    }

    private fun closeLateImage(image: Image) {
        try {
            image.close()
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (t: Exception) {
            onReleaseFailure(t)
        }
    }
}

/** The real finite Camera2 callback bridge used by ColorFusion.captureBurst. */
internal class YuvProductionCameraCallbackBridge(
    private val ownerProvider: () -> YuvCaptureOwner?,
    private val isTerminal: () -> Boolean,
    private val onCompleted: (TotalCaptureResult) -> Unit,
    private val failureDetail: (CaptureFailure) -> String
) {
    fun callback(): CameraCaptureSession.CaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            if (isTerminal()) return
            onCompleted(result)
            ownerProvider()?.onCaptureCompletedResult()
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (isTerminal()) return
            ownerProvider()?.onCaptureFailed(
                RuntimeException(failureDetail(failure)),
                "Color Burst capture failed"
            )
        }
    }
}

/** Injectable boundary that preserves the one finite still captureBurst operation. */
internal fun interface YuvFiniteBurstSubmitter {
    fun submit(
        session: CameraCaptureSession,
        requests: List<CaptureRequest>,
        callback: CameraCaptureSession.CaptureCallback,
        handler: Handler
    )
}

internal object RealYuvFiniteBurstSubmitter : YuvFiniteBurstSubmitter {
    override fun submit(
        session: CameraCaptureSession,
        requests: List<CaptureRequest>,
        callback: CameraCaptureSession.CaptureCallback,
        handler: Handler
    ) {
        session.captureBurst(requests, callback, handler)
    }
}

internal class YuvFiniteBurstSubmission(
    private val submitter: YuvFiniteBurstSubmitter = RealYuvFiniteBurstSubmitter
) {
    fun submit(
        session: CameraCaptureSession,
        requests: List<CaptureRequest>,
        callback: CameraCaptureSession.CaptureCallback,
        handler: Handler,
        frameCount: Int
    ) {
        require(requests.size == frameCount) {
            "finite YUV burst request count ${requests.size} != frameCount $frameCount"
        }
        submitter.submit(session, requests, callback, handler)
    }
}
