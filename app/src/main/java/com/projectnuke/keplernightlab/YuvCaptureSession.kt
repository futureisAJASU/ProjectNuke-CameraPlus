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
    val cleanupCoordinator: YuvCleanupCoordinator,
    val productionResourceCoordinator: YuvProductionResourceCoordinator
) : AutoCloseable {
    override fun close() {
        cleanupCoordinator.perform()
        productionResourceCoordinator.perform()
    }

    fun terminalSnapshot(): YuvTerminalSnapshot = owner.terminalSnapshotRef()

    /**
     * Initiate exactly-once terminal settlement for BOTH internal and production
     * resource cleanup.  Called by [YuvCaptureOwner.settleTerminal] and the
     * emergency settlement paths.
     */
    fun performTerminalCleanup() {
        cleanupCoordinator.perform()
        productionResourceCoordinator.perform()
    }

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
            dispatchCallback: CallbackDispatcher = CallbackDispatcher { runnable -> runnable.run(); true },
            writeJobJson: (status: String, savedFrames: Int, manifest: List<YuvFrameManifestEntry>) -> Unit = { _, _, _ -> },
            saveMotionOnce: (File) -> Pair<String?, String?> = { _ -> null to null },
            onCaptureComplete: (File) -> Unit = {},
            onCaptureError: (message: String, cause: Throwable?) -> Unit = { _, _ -> },
            candidateFilesystem: YuvCandidateFilesystem = RealYuvCandidateFilesystem,
            candidateVerifier: YuvCandidateVerifier = RealYuvCandidateVerifier,
            finalFileVerifier: YuvFinalFileVerifier = RealYuvFinalFileVerifier,
            accounting: YuvCaptureAccounting? = null,
            productionResourceCoordinator: YuvProductionResourceCoordinator,
            finished: AtomicBoolean? = null
        ): YuvCaptureSession {
            val captureStateOwner = CaptureStateOwner(dispatch)
            val boundedWorker = BoundedCaptureWorker(workerName, workerCapacity)
            val finishedState = finished ?: AtomicBoolean(false)
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
                finished = finishedState,
                postStatus = postStatus,
                dispatchCallback = dispatchCallback,
                writeJobJson = writeJobJson,
                saveMotionOnce = saveMotionOnce,
                onCaptureComplete = onCaptureComplete,
                onCaptureError = onCaptureError,
                cleanupCoordinator = cleanupCoordinator,
                productionResourceCoordinator = productionResourceCoordinator,
                candidateFilesystem = candidateFilesystem,
                candidateVerifier = candidateVerifier,
                finalFileVerifier = finalFileVerifier
            )
            return YuvCaptureSession(
                captureStateOwner,
                boundedWorker,
                finishedState,
                terminalState,
                accounting,
                lifecycle,
                reservations,
                identityOwner,
                owner,
                cleanupCoordinator,
                productionResourceCoordinator
            )
        }
    }
}