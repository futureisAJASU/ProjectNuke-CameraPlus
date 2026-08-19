package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.concurrent.CancellationException
import java.util.Date
import java.util.Locale

/** Durably settles a capture handoff when outer YUV setup fails before a worker lease is used. */
internal fun persistYuvCaptureSetupFailure(
    jobDir: File,
    source: String,
    failure: Exception
) {
    val lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
        jobDir,
        JobRecoveryMutationIntent.PROCESSING_START,
        consumesProcessingHandoff = true
    )
    var operationId: String? = null
    var primaryFailure: Throwable? = null
    try {
operationId = KeplerJobMetadata.beginActiveOperation(
            jobDir,
            kind = KeplerActiveOperationKind.PROCESSING_YUV,
            ownerLease = lease,
            consumesProcessingHandoff = true
        )
        KeplerJobMetadata.update(jobDir) { job ->
            job.put("currentPipelineStage", "FAILED")
                .put("processStatus", "PIPELINE_FAILED")
                .put("pipelineFailed", true)
                .put("pipelineFailureSource", source)
                .put("pipelineFailureType", failure.javaClass.name)
                .put("pipelineFailureMessage", failure.message ?: failure.javaClass.simpleName)
                .put("userCanMoveDevice", true)
                .put(TERMINAL_OPERATION_ID, operationId)
        }
        if (!KeplerJobMetadata.clearActiveOperation(jobDir, operationId, lease)) {
            lease.markDurableSettlementPending(operationId)
        }
    } catch (terminalFailure: Throwable) {
        // Every secondary terminalization failure installs a retry reason BEFORE the scope
        // returns: an established durable operation becomes a pending terminal settlement; a
        // missing durable owner leaves the exact lease protecting the capture handoff.
        primaryFailure = KeplerJobMetadata.installWorkerSetupSettlementDebt(
            jobDir,
            lease,
            reason = failure.message ?: failure.javaClass.simpleName,
            primaryFailure = terminalFailure
        )
} finally {
        try {
            lease.releaseOrRetainForReconciliation()
        } catch (secondary: Throwable) {
            primaryFailure = combineSettlementFailure(primaryFailure, secondary)
        }
    }
    primaryFailure?.let { throw it }
}

fun captureProcessExportNightFusion(
    context: Context,
    cameraId: String,
    frameCount: Int,
    resolutionMode: CaptureResolutionMode,
    finalOutputFormat: FinalOutputFormat,
    zoomRatio: Float,
    requestedUiZoomRatio: Float,
    physicalCameraId: String? = null,
    zoomRoute: ThreeXSourceMode = ThreeXSourceMode.OPTICAL,
    previewRoute: String? = null,
    routeFallbackReason: String? = null,
    focusAeState: FocusAeState = FocusAeState(),
    cleanupPolicy: CacheCleanupPolicy = CacheCleanupPolicy.DELETE_SOURCE_FRAMES_AFTER_VERIFIED_EXPORT,
    frameCountMode: FrameCountMode = FrameCountMode.AUTO,
    autoMinFrames: Int = 4,
    autoMaxFrames: Int = 8,
    manualFrames: Int = 4,
    framePlanReason: String = "Default",
    captureMode: CaptureMode = CaptureMode.MULTI_FRAME,
    processingParams: ClassicYuvFusionParams = ClassicYuvFusionPreset.NATURAL.params,
    captureCancellationHandle: KeplerCaptureCancellationHandle = NoOpKeplerCaptureCancellationHandle,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    onStatus: (String) -> Unit,
    onPipelineEvent: CameraPipelineEventSink = {}
) {
    val mainHandler = Handler(Looper.getMainLooper())
    val callbackLedger = ProcessingCallbackOutcomeLedger()
    val callbackDispatcher = ProcessingCallbackDispatcher(
        mainHandler,
        "KeplerYuvPipeline",
        executionObserver = callbackLedger::recordExecution,
        dispatchObserver = callbackLedger::recordDispatch
    )
    fun post(message: String) {
        val result = callbackDispatcher.dispatch { onStatus(message) }
        if (result != ProcessingCallbackDispatchResult.ACCEPTED) {
            android.util.Log.w("KeplerYuvPipeline", "status dispatch $result")
        }
    }
    val terminal = CameraPipelineTerminalPublisher(onPipelineEvent)

    cancellation.throwIfCancelled()
    post("YUV capture: saved 0/$frameCount")
    captureYuvBurstColorWithMotion(
        context = context,
        cameraId = cameraId,
        frameCount = frameCount,
        resolutionMode = resolutionMode,
        zoomRatio = zoomRatio,
        requestedUiZoomRatio = requestedUiZoomRatio,
        physicalCameraId = physicalCameraId,
        zoomRoute = zoomRoute,
        previewRoute = previewRoute,
        routeFallbackReason = routeFallbackReason,
        focusAeState = focusAeState,
        frameCountMode = frameCountMode,
        autoMinFrames = autoMinFrames,
        autoMaxFrames = autoMaxFrames,
        manualFrames = manualFrames,
        framePlanReason = framePlanReason,
        captureMode = captureMode,
        processingParams = processingParams,
        captureCancellationHandle = captureCancellationHandle,
onComplete = { jobDir ->
            try {
                cancellation.throwIfCancelled()
            } catch (_: CancellationException) {
                try {
                    // The capture already published its processing handoff; no worker will
                    // consume it now, so settle it durably instead of blocking the job.
                    KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(jobDir)
                } catch (settledError: Error) {
                    throw settledError
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (settlementError: Exception) {
                    android.util.Log.e(
                        "KeplerYuvPipeline",
                        "Failed to settle YUV processing handoff after cancellation: ${settlementError.message}",
                        settlementError
                    )
                }
                post("PIPELINE_CANCELLED: Capture timed out; background processing stopped.")
                terminal.publish(CameraPipelineEvent.Terminal.Kind.CANCELLED, message = "Capture cancelled before processing started.")
                return@captureYuvBurstColorWithMotion
            }
            try {
                KeplerJobMetadata.update(jobDir) { current ->
                    current.put("captureMode", captureMode.name)
                        .put("processingPresetName", processingParams.presetName)
                        .put("processingParams", processingParams.clamped().toJson())
                    if (captureMode == CaptureMode.SINGLE_FRAME) {
                        current.put("jobType", "YUV_SINGLE_FRAME")
                            .put("requestedFrames", 1)
                            .put("savedFrames", 1)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Error) {
                throw failure
            } catch (failure: Exception) {
                var settlementFailure: Throwable? = null
                try {
                    persistYuvCaptureSetupFailure(
                        jobDir,
                        "captureProcessExportNightFusion.metadataInitialization",
                        failure
                    )
                } catch (secondary: Throwable) {
                    settlementFailure = secondary
                }
                if (settlementFailure is Error || settlementFailure is CancellationException) {
                    throw requireNotNull(combineSettlementFailure(failure, settlementFailure))
                }
                post("PIPELINE_FAILED: Capture metadata initialization failed; cache kept.")
                terminal.publish(
                    CameraPipelineEvent.Terminal.Kind.FAILED,
                    message = "Capture metadata initialization failed; cache kept."
                )
                return@captureYuvBurstColorWithMotion
            }
            val pipelineLease = try {
                KeplerJobMetadata.acquireRecoveryCheckedOperation(
                    jobDir,
                    JobRecoveryMutationIntent.PROCESSING_START,
                    consumesProcessingHandoff = true
                )
            } catch (failure: Error) {
                throw failure
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                var settlementFailure: Throwable? = null
                try {
                    persistYuvCaptureSetupFailure(
                        jobDir = jobDir,
                        source = "captureProcessExportNightFusion.processingOwnership",
                        failure = failure
                    )
                } catch (secondary: Throwable) {
                    settlementFailure = secondary
                }
                if (settlementFailure is Error || settlementFailure is CancellationException) {
                    throw requireNotNull(combineSettlementFailure(failure, settlementFailure))
                }
                post("PIPELINE_FAILED: Capture processing ownership could not be reserved; cache kept.")
                terminal.publish(
                    CameraPipelineEvent.Terminal.Kind.FAILED,
                    message = "Capture processing ownership could not be reserved."
                )
                return@captureYuvBurstColorWithMotion
            }
            var startedThread: HandlerThread? = null
            val workerThread: HandlerThread
            val workerHandler: Handler
            try {
                val candidate = HandlerThread("KeplerCaptureProcessExportThread")
                startedThread = candidate
                candidate.start()
                workerThread = candidate
                workerHandler = Handler(workerThread.looper)
            } catch (cancelled: CancellationException) {
                var cleanupFailure: Throwable? = null
                try { startedThread?.quitSafely() } catch (failure: Throwable) { cleanupFailure = failure }
                try {
                    KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(jobDir, pipelineLease)
                } catch (failure: Throwable) {
                    cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
                }
                throw requireNotNull(combineSettlementFailure(cancelled, cleanupFailure))
            } catch (failure: Error) {
                var cleanupFailure: Throwable? = null
                try { startedThread?.quitSafely() } catch (secondary: Throwable) { cleanupFailure = secondary }
                try {
                    KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(jobDir, pipelineLease)
                } catch (secondary: Throwable) {
                    cleanupFailure = combineSettlementFailure(cleanupFailure, secondary)
                }
                throw requireNotNull(combineSettlementFailure(failure, cleanupFailure))
            } catch (failure: Exception) {
                var terminalFailure: Throwable? = null
                try {
                    startedThread?.quitSafely()
                } catch (secondary: Throwable) {
                    terminalFailure = combineSettlementFailure(failure, secondary)
                }
                try {
val operationId = pipelineLease.currentDurableOperationId()
                        ?: KeplerJobMetadata.beginActiveOperation(
                            jobDir,
                            kind = KeplerActiveOperationKind.PROCESSING_YUV,
                            ownerLease = pipelineLease,
                            consumesProcessingHandoff = true
                        )
                    KeplerJobMetadata.update(jobDir) { job ->
                        job.put("currentPipelineStage", "FAILED")
                            .put("processStatus", "PIPELINE_FAILED")
                            .put("pipelineFailed", true)
                            .put("pipelineFailureSource", "captureProcessExportNightFusion.setup")
                            .put("pipelineFailureType", failure.javaClass.name)
                            .put("pipelineFailureMessage", failure.message ?: failure.javaClass.simpleName)
                            .put("userCanMoveDevice", true)
                            .put(TERMINAL_OPERATION_ID, operationId)
                    }
KeplerJobMetadata.clearActiveOperation(jobDir, operationId, pipelineLease)
                } catch (secondary: Throwable) {
                    // Every secondary terminalization failure installs a retry reason BEFORE
                    // leaving the scope: an established durable operation becomes a pending
                    // terminal settlement; a missing durable owner leaves the exact lease
                    // protecting the capture processing handoff.
                    terminalFailure = KeplerJobMetadata.installWorkerSetupSettlementDebt(
                        jobDir,
                        pipelineLease,
                        reason = failure.message ?: failure.javaClass.simpleName,
                        primaryFailure = combineSettlementFailure(failure, secondary)
                    )
                }
                try {
                    pipelineLease.releaseOrRetainForReconciliation()
                } catch (secondary: Throwable) {
                    terminalFailure = combineSettlementFailure(terminalFailure ?: failure, secondary)
                }
                if (terminalFailure is Error || terminalFailure is CancellationException) {
                    throw terminalFailure!!
                }
                post("PIPELINE_FAILED: YUV worker setup failed; cache kept.")
                terminal.publish(
                    CameraPipelineEvent.Terminal.Kind.FAILED,
                    message = "YUV worker setup failed; cache kept."
                )
                return@captureYuvBurstColorWithMotion
            }
            fun settleWorkerDispatchFailure(primary: Throwable, cancelled: Boolean): Throwable {
                var secondaryFailure: Throwable? = null
                val operationId = try {
                    pipelineLease.currentDurableOperationId()
                        ?: KeplerJobMetadata.read(jobDir).optString(ACTIVE_OPERATION_ID)
                            .takeIf { it.isNotBlank() }
                } catch (secondary: Throwable) {
                    secondaryFailure = combineSettlementFailure(secondaryFailure, secondary)
                    null
                }
                if (operationId != null) {
                    try {
                        KeplerJobMetadata.update(jobDir) { job ->
                            job.put("currentPipelineStage", if (cancelled) "CANCELLED" else "FAILED")
                                .put("processStatus", if (cancelled) "PIPELINE_CANCELLED" else "PIPELINE_FAILED")
                                .put("pipelineFailed", !cancelled)
                                .put("pipelineFailureSource", "captureProcessExportNightFusion.workerDispatch")
                                .put("pipelineFailureType", primary.javaClass.name)
                                .put("pipelineFailureMessage", primary.message ?: primary.javaClass.simpleName)
                                .put(TERMINAL_OPERATION_ID, operationId)
                                .put("userCanMoveDevice", true)
                        }
                        if (!KeplerJobMetadata.clearActiveOperation(jobDir, operationId, pipelineLease)) {
                            pipelineLease.markDurableSettlementPending(operationId)
                        }
                    } catch (secondary: Throwable) {
                        pipelineLease.markTerminalSettlementPending(
                            PendingTerminalSettlement(
                                operationId = operationId,
                                attemptStatus = if (cancelled) "CANCELLED" else "FAILED",
                                pipelineStage = if (cancelled) "CANCELLED" else "FAILED",
                                processStatus = if (cancelled) "PIPELINE_CANCELLED" else "PIPELINE_FAILED",
                                reason = primary.message ?: primary.javaClass.simpleName
                            )
                        )
                        secondaryFailure = combineSettlementFailure(secondaryFailure, secondary)
                    }
                }
try {
                    KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(
                        jobDir, pipelineLease
                    )
                } catch (secondary: Throwable) {
                    secondaryFailure = combineSettlementFailure(secondaryFailure, secondary)
                }
                try {
                    pipelineLease.releaseOrRetainForReconciliation()
                } catch (secondary: Throwable) {
                    secondaryFailure = combineSettlementFailure(secondaryFailure, secondary)
                }
                try {
                    workerThread.quitSafely()
                } catch (secondary: Throwable) {
                    secondaryFailure = combineSettlementFailure(secondaryFailure, secondary)
                }
                return requireNotNull(combineSettlementFailure(primary, secondaryFailure))
            }
            val workerPosted = try { workerHandler.post {
                var requiredOutputCommitted = false
                var publicExportCommitted = false
                var verified = false
                var exportSettlementAttempted = false
                var exportSettlementSucceeded = false
                var primaryFailure: Throwable? = null
                fun settleInterruptedExportForTerminal(
                    disposition: PublicExportInterruptionDisposition
                ): OwnedPublicExportEvidence? {
                    val evidence = try {
                        inspectOwnedPublicExportEvidence(jobDir, pipelineLease)
                    } catch (failure: Error) {
                        throw failure
                    } catch (_: Exception) {
                        null
                    }
                    try {
                        exportSettlementAttempted = true
                        val settled = settleOwnedPublicExportInterruption(
                            jobDir = jobDir,
                            ownerLease = pipelineLease,
                            failureMessage = "Night Fusion public export ended before terminal metadata was settled.",
                            finalOutputFormat = finalOutputFormat,
                            disposition = disposition
                        )
                        if (settled) exportSettlementSucceeded = true
                    } catch (failure: Error) {
                        throw failure
                    } catch (failure: Exception) {
                        android.util.Log.e("KeplerYuvPipeline", "public export owner settlement failed", failure)
                    }
                    return evidence
                }
                try {
                    cancellation.throwIfCancelled()
                    post(if (captureMode == CaptureMode.SINGLE_FRAME) {
                        "Processing single photo..."
                    } else {
                        "Processing Night Fusion..."
                    })
                    cancellation.throwIfCancelled()
                    val finalFile = if (captureMode == CaptureMode.SINGLE_FRAME) {
                        processSingleFrameJobSync(
                            jobDir = jobDir,
                            requestedParams = processingParams,
                            cancellation = cancellation,
                            operationLease = pipelineLease,
                            onStatus = { post(it) }
                        )
                    } else {
                        processNightFusionJobV02Sync(
                            jobDir,
                            onStatus = { post(it) },
                            requestedParams = processingParams,
                            cancellation = cancellation,
                            operationLease = pipelineLease
                        )
                    }
                    requiredOutputCommitted = requiredOutputCommittedAfterProcessing(jobDir, pipelineLease)
                    cancellation.throwIfCancelled()

                    val requestedOutputFormat = requestedOutputFormatForSetting(finalOutputFormat)
                    cancellation.throwIfCancelled()
                    post("Exporting ${requestedOutputFormat.label}...")
                    cancellation.throwIfCancelled()
                    val bitmap = NoFollowFileSystem.decodeBitmapVerified(finalFile)
                        ?: error("Could not decode final Night Fusion image.")
                    val displayNameBase = "Kepler_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
                    val export = withSettlementPrecedence(
                        block = {
                            cancellation.throwIfCancelled()
                            exportNightFusionBitmapToGallery(
                                context = context,
                                bitmap = bitmap,
                                displayNameBase = displayNameBase,
                                requestedFormat = requestedOutputFormat,
                                cancellation = cancellation,
                                jobDir = jobDir,
                                ownerLease = pipelineLease
                            )
                        },
                        cleanup = { bitmap.recycle() }
                    )

                    if (!export.publicCommitted || export.uriString.isNullOrBlank()) {
                        updateExportFailure(
                            jobDir = jobDir,
                            error = export.errorMessage ?: "Unknown export failure",
                            finalOutputFormat = finalOutputFormat,
                            rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar,
                            export = export,
                            requiredOutputCommitted = requiredOutputCommitted,
                            operationLease = pipelineLease
                        )
                        post("PIPELINE_FAILED: Export failed; keeping cache. ${export.errorMessage}")
                        val currentPublicCommit = export.publicCommitted
                        val currentRequiredOutputCommitted = requiredOutputCommitted ||
                            currentProcessingAttemptHasRequiredOutputClaimForLease(jobDir, pipelineLease)
                        terminal.publish(
                            exportOutcomeTerminalKind(
                                requiredOutputCommitted = currentRequiredOutputCommitted,
                                publicExportCommitted = currentPublicCommit,
                                verified = false
                            ),
                            requiredOutputCommitted = currentRequiredOutputCommitted,
                            publicExportCommitted = currentPublicCommit,
                            message = export.errorMessage
                        )
                        return@post
                    }

                    post("Verifying gallery output...")
                    verified = verifyCommittedGalleryExport(context, export) is GalleryExportVerification.Verified
                    publicExportCommitted = export.publicCommitted
                    requiredOutputCommitted = requiredOutputCommitted ||
                        currentProcessingAttemptHasRequiredOutputClaimForLease(jobDir, pipelineLease)
                    updateExportMetadata(
                        jobDir = jobDir,
                        export = export,
                        verified = verified,
                        finalOutputFormat = finalOutputFormat,
                        rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar
                        ,postExportCancellationRequested = cancellation.isCancelled,
                        postExportWorkSkipped = cancellation.isCancelled,
                        operationLease = pipelineLease
                    )

                    if (!verified) {
                        // updateExportMetadata already persisted the exact committed URI as a
                        // canonical PARTIAL result.  Do not run the pre-commit failure writer a
                        // second time or clear/reclassify the same PUBLIC_EXPORT owner.
                        post("PIPELINE_COMPLETE_PARTIAL: Export verification was not proven; keeping source frames.")
                        terminal.publish(
                            exportOutcomeTerminalKind(
                                requiredOutputCommitted = requiredOutputCommitted,
                                publicExportCommitted = publicExportCommitted,
                                verified = false
                            ),
                            requiredOutputCommitted = requiredOutputCommitted,
                            publicExportCommitted = publicExportCommitted,
                            verified = false,
                            message = "Export verification failed"
                        )
                        return@post
                    }

                    if (cancellation.isCancelled) {
                        updateExportMetadata(jobDir, export, true, finalOutputFormat,
                            rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar,
                            postExportCancellationRequested = true, postExportWorkSkipped = true,
                            operationLease = pipelineLease)
                        post("PIPELINE_COMPLETE_PARTIAL: Image was saved, but optional post-export work was cancelled. Cache was kept.")
                        terminal.publish(
                            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
                            requiredOutputCommitted = requiredOutputCommitted,
                            publicExportCommitted = publicExportCommitted,
                            verified = verified,
                            message = "Image was saved; optional post-export work was cancelled."
                        )
                        return@post
                    }
                    post("Cleanup...")
                    val cleanup = cleanupNightFusionJobAfterVerifiedExport(
                        jobDir = jobDir,
                        policy = cleanupPolicy,
                        cancellation = cancellation,
                        onStatus = { post(it) }
                    )
                    if (cancellation.isCancelled) {
                        updateExportMetadata(
                            jobDir = jobDir,
                            export = export,
                            verified = true,
                            finalOutputFormat = finalOutputFormat,
                            rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar,
                            postExportCancellationRequested = true,
                            postExportWorkSkipped = true,
                            operationLease = pipelineLease
                        )
                        post("PIPELINE_COMPLETE_PARTIAL: Image was saved, but optional post-export work was cancelled. Cache was kept.")
                        terminal.publish(
                            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
                            requiredOutputCommitted = requiredOutputCommitted,
                            publicExportCommitted = publicExportCommitted,
                            verified = verified,
                            message = "Image was saved; optional post-export work was cancelled."
                        )
                        return@post
                    }
                    val album = "Pictures/Kepler/${export.displayName}"
                    if (finalOutputFormat.shouldExportRawSidecar) {
                        post("RAW sidecar unavailable for YUV pipeline.")
                    }
                    if (export.fallbackUsed && requestedOutputFormat == OutputFormat.HEIF) {
                        post("PIPELINE_COMPLETE: HEIF failed, saved ${export.formatUsed.label} to Gallery: $album\nCleanup complete. Deleted ${cleanup.deletedFiles} files.")
                        terminal.publish(
                            CameraPipelineEvent.Terminal.Kind.COMPLETE,
                            requiredOutputCommitted = true,
                            publicExportCommitted = true,
                            verified = true,
                            message = "Night Fusion export complete."
                        )
                    } else {
                        post("PIPELINE_COMPLETE: Saved ${export.formatUsed.label} to Gallery: $album\nCleanup complete. Deleted ${cleanup.deletedFiles} files.")
                        terminal.publish(
                            CameraPipelineEvent.Terminal.Kind.COMPLETE,
                            requiredOutputCommitted = true,
                            publicExportCommitted = true,
                            verified = true,
                            message = "Night Fusion export complete."
                        )
                    }
                } catch (cancelled: CancellationException) {
                    primaryFailure = cancelled
                    try {
                        requiredOutputCommitted = requiredOutputCommitted ||
                            currentProcessingAttemptHasRequiredOutputClaimForLease(jobDir, pipelineLease)
                    } catch (failure: Throwable) {
                        primaryFailure = combineSettlementFailure(primaryFailure, failure)
                        if (primaryFailure is Error) throw primaryFailure!!
                    }
                    post("PIPELINE_CANCELLED: Capture timed out; background processing stopped.")
                    val evidence = try {
                        settleInterruptedExportForTerminal(PublicExportInterruptionDisposition.CANCELLED)
                    } catch (failure: Throwable) {
                        primaryFailure = combineSettlementFailure(primaryFailure, failure)
                        if (primaryFailure is Error) throw primaryFailure!!
                        null
                    }
                    terminal.publish(
                        publicExportInterruptionTerminalKind(
                            evidence,
                            cancellationRequested = true,
                            committedFallback = publicExportCommitted,
                            requiredOutputCommitted = requiredOutputCommitted
                        ),
                        requiredOutputCommitted = requiredOutputCommitted,
                        publicExportCommitted = evidence?.committed ?: publicExportCommitted,
                        verified = evidence?.verified ?: verified,
                        message = "Pipeline cancellation settled."
                    )
                } catch (e: Exception) {
                    primaryFailure = e
                    try {
                        requiredOutputCommitted = requiredOutputCommitted ||
                            currentProcessingAttemptHasRequiredOutputClaimForLease(jobDir, pipelineLease)
                    } catch (failure: Throwable) {
                        primaryFailure = combineSettlementFailure(primaryFailure, failure)
                        if (primaryFailure is Error || primaryFailure is CancellationException) {
                            throw primaryFailure!!
                        }
                    }
                    post("PIPELINE_FAILED: ${if (captureMode == CaptureMode.SINGLE_FRAME) "Single photo" else "Night Fusion"} pipeline failed; keeping cache.\n${e.stackTraceToString()}")
                    val evidence = try {
                        settleInterruptedExportForTerminal(PublicExportInterruptionDisposition.FAILED)
                    } catch (failure: Throwable) {
                        primaryFailure = combineSettlementFailure(primaryFailure, failure)
                        if (primaryFailure is Error || primaryFailure is CancellationException) {
                            throw primaryFailure!!
                        }
                        null
                    }
terminal.publish(
                        publicExportInterruptionTerminalKind(
                            evidence,
                            cancellationRequested = false,
                            committedFallback = publicExportCommitted,
                            requiredOutputCommitted = requiredOutputCommitted
                        ),
                        requiredOutputCommitted = requiredOutputCommitted,
                        publicExportCommitted = evidence?.committed ?: publicExportCommitted,
                        verified = evidence?.verified ?: verified,
                        message = e.message
                    )
                    try {
                        KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(
                            jobDir, pipelineLease, settleOnlyIfPresent = true
                        )
                    } catch (handoffFailure: Throwable) {
                        primaryFailure = combineSettlementFailure(primaryFailure, handoffFailure)
                    }
                } catch (fatal: Error) {
                    primaryFailure = fatal
                    throw fatal
                } finally {
                    var cleanupFailure: Throwable? = null
                    if (!exportSettlementAttempted) {
                        try {
                            exportSettlementAttempted = true
                            val settled = settleOwnedPublicExportInterruption(
                                jobDir = jobDir,
                            ownerLease = pipelineLease,
                            failureMessage = "Night Fusion public export ended before terminal metadata was settled.",
                            finalOutputFormat = finalOutputFormat,
                            disposition = PublicExportInterruptionDisposition.FAILED
                            )
                            if (settled) exportSettlementSucceeded = true
                        } catch (settlementFailure: Throwable) {
                            cleanupFailure = combineSettlementFailure(cleanupFailure, settlementFailure)
                            android.util.Log.e("KeplerYuvPipeline", "public export owner settlement failed", settlementFailure)
                        }
                    }
                    try {
                        if (exportSettlementSucceeded) {
                            if (!pipelineLease.releaseOrRetainForReconciliation()) {
                                android.util.Log.e(
                                    "KeplerYuvPipeline",
                                    "retaining processing lease for reconciliation after durable attempt settlement"
                                )
                            }
                        } else {
                            pipelineLease.releaseOrRetainForReconciliation()
                        }
                    } catch (failure: Throwable) {
                        cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
                    }
                    try {
                        workerThread.quitSafely()
                    } catch (failure: Throwable) {
                        cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
                    }
                    val combined = combineSettlementFailure(primaryFailure, cleanupFailure)
                    if (combined is Error && combined !== primaryFailure) {
                        throw combined
                    }
                }
            } } catch (cancelled: CancellationException) {
                throw settleWorkerDispatchFailure(cancelled, cancelled = true)
            } catch (failure: Error) {
                throw settleWorkerDispatchFailure(failure, cancelled = false)
            } catch (failure: Exception) {
                android.util.Log.e("KeplerYuvPipeline", "capture/process worker dispatch failed", failure)
                false
            }
            if (!workerPosted) {
                val operationId = try {
                    pipelineLease.currentDurableOperationId()
                        ?: KeplerJobMetadata.read(jobDir).optString(ACTIVE_OPERATION_ID)
                            .takeIf { it.isNotBlank() }
                } catch (failure: Error) {
                    throw failure
                } catch (failure: Exception) {
                    pipelineLease.markProcessingHandoffSettlementPending()
                    android.util.Log.e("KeplerYuvPipeline", "worker dispatch owner inspection failed", failure)
                    null
                }
                if (operationId != null) {
                    try {
                        KeplerJobMetadata.update(jobDir) { job ->
                            job.put("currentPipelineStage", "FAILED")
                                .put("processStatus", "PIPELINE_FAILED")
                                .put("pipelineFailed", true)
                                .put("pipelineFailureSource", "captureProcessExportNightFusion.workerDispatch")
                                .put("pipelineFailureType", IllegalStateException::class.java.name)
                                .put("pipelineFailureMessage", "YUV worker could not be posted")
                                .put(TERMINAL_OPERATION_ID, operationId)
                                .put("userCanMoveDevice", true)
                        }
if (!KeplerJobMetadata.clearActiveOperation(jobDir, operationId, pipelineLease)) {
                            pipelineLease.markDurableSettlementPending(operationId)
                        }
                    } catch (failure: Error) {
                        throw failure
                    } catch (failure: Exception) {
                        pipelineLease.markTerminalSettlementPending(
                            PendingTerminalSettlement(
                                operationId = operationId,
                                attemptStatus = "FAILED",
                                pipelineStage = "FAILED",
                                processStatus = "PIPELINE_FAILED",
                                reason = "YUV worker could not be posted"
                            )
                        )
                        android.util.Log.e("KeplerYuvPipeline", "worker dispatch terminal persistence failed", failure)
                    }
                }
try {
                    val handoffSettled = KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(
                        jobDir, pipelineLease
                    )
                    if (!handoffSettled) {
                        pipelineLease.markProcessingHandoffSettlementPending()
                    }
                } catch (failure: Error) {
                    throw failure
                } catch (failure: Exception) {
                    pipelineLease.markProcessingHandoffSettlementPending()
                }
                try {
                    workerThread.quitSafely()
                } catch (failure: Error) {
                    throw failure
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.e("KeplerYuvPipeline", "worker shutdown after dispatch failure failed", failure)
                }
                // Owner relinquish boundary: all cleanup complete, now release or retain for reconciliation
                try {
                    pipelineLease.releaseOrRetainForReconciliation()
                } catch (secondary: Throwable) {
                    android.util.Log.e("KeplerYuvPipeline", "owner relinquish boundary failed", secondary)
                }
                post("PIPELINE_FAILED: Capture processing worker could not start; cache kept.")
                terminal.publish(CameraPipelineEvent.Terminal.Kind.FAILED, message = "Capture processing worker could not start.")
            }
        },
        onError = { error ->
            post("PIPELINE_FAILED: Capture failed; keeping cache.\n$error")
            terminal.publish(CameraPipelineEvent.Terminal.Kind.FAILED, message = error)
        },
        onStatus = { message ->
            post(message)
        }
    )
}

internal fun reprocessYuvJob(
    context: Context,
    jobDir: File,
    finalOutputFormat: FinalOutputFormat,
    selectedFrameIndices: Set<Int>? = null,
    fusionParams: ClassicYuvFusionParams? = null,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    operationLease: JobOperationLease? = null,
    workerPostOperation: ((Runnable) -> Boolean)? = null,
    onStatus: (String) -> Unit
): ReprocessWorkerRun {
    val mainHandler = Handler(Looper.getMainLooper())
    val callbackLedger = ProcessingCallbackOutcomeLedger()
    val callbackDispatcher = ProcessingCallbackDispatcher(
        mainHandler,
        "KeplerYuvReprocess",
        executionObserver = callbackLedger::recordExecution,
        dispatchObserver = callbackLedger::recordDispatch
    )
    fun post(message: String): Boolean {
        val result = callbackDispatcher.dispatch { onStatus(message) }
        if (result != ProcessingCallbackDispatchResult.ACCEPTED) {
            android.util.Log.w("KeplerYuvReprocess", "status dispatch $result")
        }
        return result == ProcessingCallbackDispatchResult.ACCEPTED
    }
    val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
    var startedThread: HandlerThread? = null
    val workerThread: HandlerThread
    val workerHandler: Handler
    try {
        val candidate = HandlerThread("KeplerYuvReprocessThread")
        startedThread = candidate
        candidate.start()
        workerThread = candidate
        workerHandler = Handler(workerThread.looper)
    } catch (failure: Error) {
        var cleanupFailure: Throwable? = null
        try { startedThread?.quitSafely() } catch (secondary: Throwable) { cleanupFailure = secondary }
        throw requireNotNull(combineSettlementFailure(failure, cleanupFailure))
    } catch (failure: Exception) {
        try { startedThread?.quitSafely() } catch (secondary: Throwable) {
            if (secondary is Error || secondary is CancellationException) throw secondary
        }
        throw failure
    }
    val workerPosted = try {
        (workerPostOperation ?: workerHandler::post).invoke(Runnable {
        val jobFile = NoFollowFileSystem.requireDirectChildFile(jobDir, JOB_JSON_FILE_NAME)
        var totalFrames = 0
        var enabledFrames = 0
        var terminalResult: Result<Unit> = Result.failure(IllegalStateException("YUV reprocess did not reach a terminal state."))
        var fatalReprocessFailure: Error? = null
        var publicExportCommitted = false
        var exportVerified = false
        var committedExport: GalleryExportResult? = null
        var terminalDisposition = ReprocessTerminalDisposition.UNCOMMITTED_FAILURE
        var finalOutputFile: File? = null
        fun currentAttemptHasLocalResult(): Boolean {
            return try {
                if (operationLease != null) {
                    currentProcessingAttemptHasRequiredOutputClaimForLease(jobDir, operationLease)
                } else {
                    // A returned file belongs to this invocation. Without the outer lease there
                    // is no safe attempt identity to use for a post-throw metadata lookup, so an
                    // older pathname is never treated as a new reprocess result.
                    val file = finalOutputFile ?: return false
                    file.isFile && file.length() > 0L
                }
            } catch (failure: Throwable) {
                val combined = combineSettlementFailure(terminalResult.exceptionOrNull(), failure)
                if (combined is Error) {
                    fatalReprocessFailure = combined
                } else if (combined is CancellationException) {
                    terminalResult = Result.failure(combined)
                }
                false
            }
        }
        try {
            cancellation.throwIfCancelled()
            if (selectedFrameIndices != null) {
                applyExplicitYuvFrameSelection(jobDir, selectedFrameIndices)
            }
            val initialJob = JSONObject(NoFollowFileSystem.readTextVerified(jobFile))
            val singleFrame = isSingleFrameJob(initialJob)
            val requiredFrames = if (singleFrame) 1 else 2
            val frames = initialJob.optJSONArray("frames")
            totalFrames = frames?.length() ?: 0
            repeat(totalFrames) { index ->
                val frame = frames?.optJSONObject(index) ?: return@repeat
                val fileName = frame.optString("file")
                if (
                    frame.optBoolean("enabled", true) &&
                    !frame.optBoolean("excludedByUser", false) &&
                    fileName.isNotBlank() &&
                    NoFollowFileSystem.optionalDirectChildFile(jobDir, fileName) != null
                ) {
                    enabledFrames++
                }
            }
            if (enabledFrames < requiredFrames) {
                val message = "Not enough enabled YUV frames to reprocess: required=$requiredFrames actual=$enabledFrames"
                post(message)
                terminalResult = Result.failure(IllegalStateException(message))
                return@Runnable
            }

            post(if (singleFrame) {
                "Single photo reprocess: loading source frame..."
            } else {
                "YUV reprocess: loading enabled frames..."
            })
            post("${if (singleFrame) "Single photo" else "YUV reprocess"}: using $enabledFrames/$totalFrames frames...")
            val requestedProcessingParams = fusionParams ?: loadClassicYuvFusionParams(initialJob)
            val finalFile = if (singleFrame) {
                    processSingleFrameJobSync(
                    jobDir = jobDir,
                    requestedParams = requestedProcessingParams,
                    cancellation = cancellation,
                        metadataPolicy = ReprocessMetadataPolicy.REPROCESS_PROGRESS_ONLY,
                        operationLease = operationLease,
                        onStatus = { post(it) }
                )
            } else {
                processNightFusionJobV02Sync(
                    jobDir = jobDir,
                    onStatus = { post(it) },
                    requestedParams = requestedProcessingParams,
                    cancellation = cancellation,
                    metadataPolicy = ReprocessMetadataPolicy.REPROCESS_PROGRESS_ONLY,
                    operationLease = operationLease
                )
            }
            finalOutputFile = finalFile.takeIf { it.isFile && it.length() > 0L }
            post("YUV reprocess: exporting...")
            val bitmap = NoFollowFileSystem.decodeBitmapVerified(finalFile)
                ?: error("Could not decode reprocessed YUV image.")
            val requestedFormat = requestedOutputFormatForSetting(finalOutputFormat)
            val export = withSettlementPrecedence(
                block = {
                    exportNightFusionBitmapToGallery(
                        context = context,
                        bitmap = bitmap,
                        displayNameBase = "Kepler_YUV_REPROCESS_${
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        }",
                        requestedFormat = requestedFormat,
                        cancellation = cancellation,
                        jobDir = jobDir,
                        ownerLease = operationLease
                    )
                },
cleanup = { bitmap.recycle() }
            )
            val exportCommitState = export.publicCommitState
            val hasPublicCommitEvidence = exportCommitState != GalleryExportCommitState.NOT_COMMITTED
            if (hasPublicCommitEvidence) {
                // Preserve exact export evidence for all non-NOT_COMMITTED states (UNKNOWN,
                // PUBLIC_COMMITTED_UNVERIFIED, VERIFIED)
                committedExport = export
                publicExportCommitted = export.publicCommitted
                val verified = verifyCommittedGalleryExport(context, export) is GalleryExportVerification.Verified
                exportVerified = verified
                if (exportCommitState == GalleryExportCommitState.UNKNOWN) {
                    terminalDisposition = ReprocessTerminalDisposition.COMMITTED_PARTIAL
                    post("PIPELINE_COMPLETE_PARTIAL: YUV reprocess export commit state UNKNOWN; exact URI preserved.")
                } else if (!verified) {
                    terminalDisposition = ReprocessTerminalDisposition.COMMITTED_PARTIAL
                    post("PIPELINE_COMPLETE_PARTIAL: YUV reprocess export verification incomplete.")
                } else {
                    terminalDisposition = ReprocessTerminalDisposition.VERIFIED_SUCCESS
                    post(
                        "PIPELINE_COMPLETE: ${if (singleFrame) "Single photo" else "YUV reprocess"} " +
                            "saved ${export.formatUsed.label}; used $enabledFrames/$totalFrames frames; cache kept."
                    )
                }
                terminalResult = Result.success(Unit)
            } else {
                // NOT_COMMITTED: ordinary export failure
                error(export.errorMessage ?: "YUV export failed")
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            post("PIPELINE_CANCELLED: YUV reprocess cancelled; source frames kept.")
            terminalResult = Result.failure(ce)
            terminalDisposition = if (currentAttemptHasLocalResult()) {
                ReprocessTerminalDisposition.COMMITTED_PARTIAL
            } else {
                ReprocessTerminalDisposition.CANCELLED
            }
        } catch (oom: OutOfMemoryError) {
            post("PIPELINE_FAILED: YUV reprocess failed; cache kept. out of memory")
            fatalReprocessFailure = oom
            terminalResult = Result.success(Unit)
            if (currentAttemptHasLocalResult()) {
                terminalDisposition = ReprocessTerminalDisposition.COMMITTED_PARTIAL
            }
        } catch (e: Exception) {
            post("PIPELINE_FAILED: YUV reprocess failed; cache kept. ${e.message}")
            terminalResult = Result.failure(e)
            if (currentAttemptHasLocalResult()) {
                terminalDisposition = ReprocessTerminalDisposition.COMMITTED_PARTIAL
            }
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                workerThread.quitSafely()
            } catch (failure: Throwable) {
                cleanupFailure = failure
            }
            val primaryFailure = fatalReprocessFailure ?: terminalResult.exceptionOrNull()
            val combinedFailure = combineSettlementFailure(primaryFailure, cleanupFailure)
            if (combinedFailure is Error) {
                fatalReprocessFailure = combinedFailure
            }
            terminal.complete(
                ReprocessWorkerOutcome(
                    result = terminalResult,
                    publicExportCommitted = publicExportCommitted,
                    exportVerified = exportVerified,
                    export = committedExport,
                    finalOutputFile = finalOutputFile,
                    previewFile = finalOutputFile,
                    bytesWritten = finalOutputFile?.length() ?: 0L,
                    disposition = terminalDisposition,
                    terminalError = fatalReprocessFailure ?: terminalResult.exceptionOrNull()
                )
            )
        }
        fatalReprocessFailure?.let { throw it }
        })
    } catch (failure: Error) {
        var cleanupFailure: Throwable? = null
        try {
            workerThread.quitSafely()
        } catch (secondary: Throwable) {
            cleanupFailure = secondary
        }
        throw requireNotNull(combineSettlementFailure(failure, cleanupFailure))
    } catch (cancelled: CancellationException) {
        var cleanupFailure: Throwable? = null
        try {
            workerThread.quitSafely()
        } catch (secondary: Throwable) {
            cleanupFailure = secondary
        }
        throw requireNotNull(combineSettlementFailure(cancelled, cleanupFailure))
    } catch (_: Exception) {
        false
    }
    if (!workerPosted) {
        val failure = IllegalStateException("YUV reprocess worker could not start")
        var cleanupFailure: Throwable? = null
        try {
            workerThread.quitSafely()
        } catch (secondary: Throwable) {
            cleanupFailure = secondary
        }
        val terminalFailure = combineSettlementFailure(failure, cleanupFailure)
        if (terminalFailure is Error || terminalFailure is CancellationException) {
            throw terminalFailure
        }
        terminal.complete(
            ReprocessWorkerOutcome(
                result = Result.failure(terminalFailure ?: failure),
                publicExportCommitted = false,
                exportVerified = false,
                disposition = ReprocessTerminalDisposition.UNCOMMITTED_FAILURE,
                terminalError = terminalFailure ?: failure
            )
        )
    }
    return ReprocessWorkerRun(
        terminal = terminal,
        cancel = { (cancellation as? KeplerPipelineCancellationToken)?.cancel() }
    )
}

private fun applyExplicitYuvFrameSelection(jobDir: File, selectedFrameIndices: Set<Int>) {
    KeplerJobMetadata.update(jobDir) { job ->
    val frames = job.optJSONArray("frames") ?: return@update
    repeat(frames.length()) { position ->
        val frame = frames.optJSONObject(position) ?: return@repeat
        val index = frame.optInt("index", position)
        val included = index in selectedFrameIndices
        frame.put("enabled", included)
            .put("excludedByUser", !included)
            .put("excludeReason", if (included) JSONObject.NULL else "FRAME_SELECTION")
    }
    job.put("includedFrameIndices", org.json.JSONArray(selectedFrameIndices.sorted()))
        .put("frameSelectionUpdatedAt", System.currentTimeMillis())
    }
}

fun cleanupNightFusionJobAfterVerifiedExport(
    jobDir: File,
    policy: CacheCleanupPolicy,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    onStatus: (String) -> Unit
): CleanupResult {
    val jobFile = NoFollowFileSystem.optionalDirectChildFile(jobDir, JOB_JSON_FILE_NAME)
        ?: return CleanupResult(0, 0L, emptyList())

    val job = JSONObject(NoFollowFileSystem.readTextVerified(jobFile))
    if (!job.optBoolean("exportVerified", false)) {
        onStatus("Cleanup skipped: export not verified.")
        return CleanupResult(0, 0L, NoFollowFileSystem.requireDirectChildren(jobDir).map { it.name })
    }
    if (policy == CacheCleanupPolicy.KEEP_ALL) {
        updateCleanupMetadata(jobFile, policy, "KEPT_ALL", 0, 0L, false)
        return CleanupResult(0, 0L, NoFollowFileSystem.requireDirectChildren(jobDir).map { it.name })
    }

    val deleteNames = mutableSetOf<String>()
    if (
        policy == CacheCleanupPolicy.DELETE_SOURCE_FRAMES_AFTER_VERIFIED_EXPORT ||
        policy == CacheCleanupPolicy.DELETE_INTERMEDIATES_AFTER_VERIFIED_EXPORT ||
        policy == CacheCleanupPolicy.DELETE_ALL_CACHE_AFTER_VERIFIED_EXPORT_KEEP_JOB
    ) {
        NoFollowFileSystem.requireDirectChildren(jobDir)
            .filter { NoFollowFileSystem.isRealFile(it.toPath()) && it.name.matches(Regex("frame_\\d+_color\\.png")) }
            ?.forEach { deleteNames.add(it.name) }
    }
    if (
        policy == CacheCleanupPolicy.DELETE_INTERMEDIATES_AFTER_VERIFIED_EXPORT ||
        policy == CacheCleanupPolicy.DELETE_ALL_CACHE_AFTER_VERIFIED_EXPORT_KEEP_JOB
    ) {
        deleteNames.add("average_color_rotated.png")
        deleteNames.add("denoise_color.png")
    }
    if (policy == CacheCleanupPolicy.DELETE_ALL_CACHE_AFTER_VERIFIED_EXPORT_KEEP_JOB) {
        deleteNames.add("sharpened_night_fusion.png")
    }

    var deleted = 0
    var freed = 0L
    var cancelledDuringCleanup = cancellation.isCancelled
    deleteNames.forEach { name ->
        if (cancellation.isCancelled) {
            cancelledDuringCleanup = true
            return@forEach
        }
        val file = NoFollowFileSystem.optionalDirectChildFile(jobDir, name)
        if (file != null && file.name != JOB_JSON_FILE_NAME) {
            val size = java.nio.file.Files.size(file.toPath())
            if (java.nio.file.Files.deleteIfExists(file.toPath())) {
                deleted++
                freed += size
            }
        }
        if (cancellation.isCancelled) {
            cancelledDuringCleanup = true
        }
    }

    val sourceDeleted = NoFollowFileSystem.requireDirectChildren(jobDir)
        .none { it.name.matches(Regex("frame_\\d+_color\\.png")) }
    val status = when {
        cancelledDuringCleanup -> "PARTIAL_CLEANUP"
        sourceDeleted -> "SOURCE_FRAMES_DELETED"
        else -> "PARTIAL_CLEANUP"
    }
    updateCleanupMetadata(jobFile, policy, status, deleted, freed, sourceDeleted)

    return CleanupResult(
        deletedFiles = deleted,
        freedBytes = freed,
        keptFiles = NoFollowFileSystem.requireDirectChildren(jobDir).map { it.name }
    )
}

private fun updateCleanupMetadata(
    jobFile: File,
    policy: CacheCleanupPolicy,
    cleanupStatus: String,
    deletedFiles: Int,
    freedBytes: Long,
    sourceFramesDeleted: Boolean
) {
    val jobDir = jobFile.parentFile ?: error("Job directory missing")
    KeplerJobMetadata.update(jobDir) { job ->
        job.put("cleanupStatus", cleanupStatus)
            .put("cleanupDeletedFiles", deletedFiles)
            .put("cleanupFreedBytes", freedBytes)
            .put("cleanupPolicy", policy.name)
            .put("sourceFramesDeleted", sourceFramesDeleted)
            .put("cleanedAt", System.currentTimeMillis())
    }
}
