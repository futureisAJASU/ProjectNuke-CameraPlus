package com.projectnuke.keplernightlab

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    val productionResourceCoordinator: YuvProductionResourceCoordinator,
    val terminalRequestHandoff: YuvTerminalRequestHandoff,
    internal val terminalMetadataWriter: YuvTerminalMetadataWriter,
    internal val verifiedFileReader: YuvVerifiedFileReader,
    internal val terminalFinalVerifier: YuvTerminalFinalVerifier,
    internal val onSessionTerminal: (YuvTerminalRequest) -> Unit,
    internal val onSessionSettlementFailure: (Throwable) -> Unit,
    private val startTerminalObserverOnCreate: Boolean
) : AutoCloseable, YuvSessionTerminalOperations {
    private val terminalObserverStarted = AtomicBoolean(false)
    private val terminalWatchdogStarted = AtomicBoolean(false)

    init {
        if (startTerminalObserverOnCreate) startTerminalObservation()
    }
    override fun close() {
        // Deterministically unblock any ColorFusion terminal gate FIRST, then
        // perform the coordinated resource cleanup.  A waiter that was unblocked
        // by closure must never synthesize a terminal result.
        terminalRequestHandoff.close()
        cleanupCoordinator.perform()
        productionResourceCoordinator.perform()
    }

    fun terminalSnapshot(): YuvTerminalSnapshot = owner.terminalSnapshotRef()

    /**
     * Initiate exactly-once terminal settlement for BOTH internal and production
     * resource cleanup.  Called by [YuvCaptureOwner.settleTerminalByRequest] and
     * the emergency settlement paths.
     */
    fun performTerminalCleanup() {
        cleanupCoordinator.perform()
        productionResourceCoordinator.perform()
    }

    /** Starts the unbounded sole terminal consumer. The optional bound arms only a diagnostic watchdog. */
    fun startTerminalObservation(settleBoundMillis: Long? = null): Boolean {
        val started = terminalObserverStarted.compareAndSet(false, true)
        if (started) {
            Thread({
                owner.consumeTerminalHandoff(terminalRequestHandoff.awaitResult())
            }, "KeplerYuvTerminalWait").apply {
                isDaemon = true
                start()
            }
        }
        if (settleBoundMillis != null && terminalWatchdogStarted.compareAndSet(false, true)) {
            Thread({
                if (terminalRequestHandoff.awaitResult(settleBoundMillis) is YuvTerminalHandoffResult.WatchdogTimeout) {
                    owner.recordTerminalWatchdogTimeout()
                }
            }, "KeplerYuvTerminalWatchdog").apply {
                isDaemon = true
                start()
            }
        }
        return started
    }

    // ------------------------------------------------------------------
    // YuvSessionTerminalOperations: the owner requests session terminal
    // operations; the session owns the callbacks/adapters.
    // ------------------------------------------------------------------

    override fun publishTerminal(request: YuvTerminalRequest): Boolean =
        terminalRequestHandoff.publish(request)

    override fun publishSettlementFailure(failure: Throwable): Boolean =
        terminalRequestHandoff.failSettlement(failure)

    override fun requestTerminalMetadataWrite(request: YuvTerminalMetadataRequest) =
        terminalMetadataWriter.write(request)

    override fun verifyTerminalFinalFile(file: File, frameIndex: Int): Boolean =
        terminalFinalVerifier.verify(file, frameIndex)

    override fun readVerifiedTerminalFile(file: File): ByteArray =
        verifiedFileReader.read(file)

    override fun observeTerminal(request: YuvTerminalRequest) =
        onSessionTerminal(request)

    override fun observeSettlementFailure(failure: Throwable) =
        onSessionSettlementFailure(failure)

    private fun onSessionSettlementFailure(failure: Throwable) {
        owner.recordTerminalSettlementFailure(failure)
        onSessionSettlementFailure.invoke(failure)
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
            postStatus: (String) -> Boolean = { true },
            dispatchCallback: CallbackDispatcher = CallbackDispatcher { runnable -> runnable.run(); true },
            writeJobJson: (
                status: String,
                savedFrames: Int,
                manifest: List<YuvFrameManifestEntry>,
                receivedFrames: Int,
                persistedFrames: Int,
                failedFrames: Int,
                droppedFrames: Int,
                completedResults: Int,
                firstWorkerFailureClass: String?,
                firstWorkerFailureMessage: String?,
                firstWorkerFailureFrameIndex: Int?,
                firstWorkerFailureRootCauseClass: String?,
                firstWorkerFailureRootCauseMessage: String?,
                firstWorkerFailureStage: String?,
                queuedWork: Int,
                inFlightWork: Int,
                yuvBufferedFrames: Int,
                yuvReservedAdoptionCount: Int
            ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
            saveMotionOnce: (File) -> Pair<String?, String?> = { _ -> null to null },
            onCaptureComplete: (File) -> Unit = {},
            onCaptureError: (message: String, cause: Throwable?) -> Unit = { _, _ -> },
            candidateFilesystem: YuvCandidateFilesystem = RealYuvCandidateFilesystem,
            candidateVerifier: YuvCandidateVerifier = RealYuvCandidateVerifier,
            finalFileVerifier: YuvFinalFileVerifier = RealYuvFinalFileVerifier,
            terminalMetadataWriter: YuvTerminalMetadataWriter =
                YuvTerminalMetadataWriter { request ->
                    writeJobJson(
                        request.jobStatus,
                        request.savedFrames,
                        request.manifest,
                        request.receivedFrames,
                        request.persistedFrames,
                        request.failedFrames,
                        request.droppedFrames,
                        request.completedResults,
                        request.firstWorkerFailureClass,
                        request.firstWorkerFailureMessage,
                        request.firstWorkerFailureFrameIndex,
                        request.firstWorkerFailureRootCauseClass,
                        request.firstWorkerFailureRootCauseMessage,
                        request.firstWorkerFailureStage,
                        request.queuedWork,
                        request.inFlightWork,
                        request.yuvBufferedFrames,
                        request.yuvReservedAdoptionCount
                    )
                },
            verifiedFileReader: YuvVerifiedFileReader =
                YuvVerifiedFileReader { file -> NoFollowFileSystem.readBytesVerified(file) },
            terminalFinalVerifier: YuvTerminalFinalVerifier =
                YuvTerminalFinalVerifier { file, frameIndex -> finalFileVerifier.verify(file, frameIndex) },
            onSessionTerminal: (YuvTerminalRequest) -> Unit = { request ->
                when (request.completionKind) {
                    TerminalCompletionKind.SUCCESS -> onCaptureComplete(outputDir)
                    TerminalCompletionKind.ERROR -> onCaptureError(request.reason ?: "", request.cause)
                }
            },
            onSessionSettlementFailure: (Throwable) -> Unit = { failure ->
                onCaptureError("CAPTURE_INTERNAL_ERROR: YUV terminal settlement failed", failure)
            },
            accounting: YuvCaptureAccounting? = null,
            productionResourceCoordinator: YuvProductionResourceCoordinator,
            finished: AtomicBoolean? = null,
            startTerminalObserverOnCreate: Boolean = true,
            schedulePersistenceDrainDeadline: ((Runnable) -> Unit)? = null,
            onAcquisitionUpdate: ((receivedImages: Int, completedResults: Int, persistedFrames: Int) -> Unit)? = null,
            timingHooks: YuvCaptureTimingHooks? = null
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
            val terminalRequestHandoff = YuvTerminalRequestHandoff()
            val sessionRef = AtomicReference<YuvCaptureSession?>()
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
                sessionTerminal = object : YuvSessionTerminalOperations {
                    override fun publishTerminal(request: YuvTerminalRequest): Boolean =
                        sessionRef.get()?.publishTerminal(request) ?: false

                    override fun publishSettlementFailure(failure: Throwable): Boolean =
                        sessionRef.get()?.publishSettlementFailure(failure) ?: false

                    override fun requestTerminalMetadataWrite(request: YuvTerminalMetadataRequest) {
                        sessionRef.get()?.requestTerminalMetadataWrite(request)
                    }

                    override fun verifyTerminalFinalFile(file: File, frameIndex: Int): Boolean =
                        sessionRef.get()?.verifyTerminalFinalFile(file, frameIndex) ?: false

                    override fun readVerifiedTerminalFile(file: File): ByteArray =
                        sessionRef.get()?.readVerifiedTerminalFile(file)
                            ?: error("session not initialized")

                    override fun observeTerminal(request: YuvTerminalRequest) {
                        sessionRef.get()?.observeTerminal(request)
                    }

                    override fun observeSettlementFailure(failure: Throwable) {
                        sessionRef.get()?.observeSettlementFailure(failure)
                    }
                },
                saveMotionOnce = saveMotionOnce,
                cleanupCoordinator = cleanupCoordinator,
                productionResourceCoordinator = productionResourceCoordinator,
                candidateFilesystem = candidateFilesystem,
                candidateVerifier = candidateVerifier,
                schedulePersistenceDrainDeadline = schedulePersistenceDrainDeadline,
                onAcquisitionUpdate = onAcquisitionUpdate,
                timingHooks = timingHooks
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
                productionResourceCoordinator,
                terminalRequestHandoff,
                terminalMetadataWriter,
                verifiedFileReader,
                terminalFinalVerifier,
                onSessionTerminal,
                onSessionSettlementFailure,
                startTerminalObserverOnCreate
            ).also { sessionRef.set(it) }
        }
    }
}
