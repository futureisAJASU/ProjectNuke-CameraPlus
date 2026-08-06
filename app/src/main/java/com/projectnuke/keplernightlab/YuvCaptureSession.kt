package com.projectnuke.keplernightlab

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class YuvCaptureSession internal constructor(
    val captureStateOwner: CaptureStateOwner,
    val boundedWorker: BoundedCaptureWorker,
    val finished: AtomicBoolean,
    val terminalState: CaptureTerminalState,
    val accounting: YuvCaptureAccounting,
    val lifecycle: YuvBufferedLifecycle,
    val reservations: YuvBufferReservations,
    val identityOwner: CaptureFrameIdentityOwner,
    val owner: YuvCaptureOwner,
    val cleanupCoordinator: YuvCleanupCoordinator
) : AutoCloseable {
    override fun close() {
        cleanupCoordinator.perform()
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
            onCaptureError: (message: String, cause: Throwable?) -> Unit = { _, _ -> },
            candidateFilesystem: YuvCandidateFilesystem = RealYuvCandidateFilesystem,
            candidateVerifier: YuvCandidateVerifier = RealYuvCandidateVerifier,
            finalFileVerifier: YuvFinalFileVerifier = RealYuvFinalFileVerifier,
            accounting: YuvCaptureAccounting? = null
        ): YuvCaptureSession {
            val captureStateOwner = CaptureStateOwner(dispatch)
            val boundedWorker = BoundedCaptureWorker(workerName, workerCapacity)
            val finished = AtomicBoolean(false)
            val reservations = YuvBufferReservations(maxRetainedBytes)
            val accounting = accounting ?: YuvCaptureAccounting()
            val lifecycle = YuvBufferedLifecycle()
            val identityOwner = CaptureFrameIdentityOwner(frameCount)
            val terminalState = CaptureTerminalState()
            val cleanupCoordinator = YuvCleanupCoordinator(
                captureStateOwner, lifecycle, accounting, reservations, boundedWorker
            )
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
                onCaptureError = onCaptureError,
                cleanupCoordinator = cleanupCoordinator,
                candidateFilesystem = candidateFilesystem,
                candidateVerifier = candidateVerifier,
                finalFileVerifier = finalFileVerifier
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
                owner,
                cleanupCoordinator
            )
        }
    }
}