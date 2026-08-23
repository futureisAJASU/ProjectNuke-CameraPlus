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
            // Durable handoff ordering invariant: immediately after an
            // evidenced CaptureStageComplete, process death may occur, so the
            // exact job must already contain every post-handoff processing
            // parameter. Metadata persistence therefore happens BEFORE the
            // evidenced event; a persistence failure never emits handoff
            // evidence, never unlocks the shutter via handoff, and never
            // enqueues an incomplete background request.
            val metadataDurable = try {
                KeplerJobMetadata.update(jobDir) { current ->
                    current.put("captureMode", captureMode.name)
                        .put("processingPresetName", processingParams.presetName)
                        .put("processingParams", processingParams.clamped().toJson())
                        .put("finalOutputFormatSetting", finalOutputFormat.name)
                    if (captureMode == CaptureMode.SINGLE_FRAME) {
                        current.put("jobType", "YUV_SINGLE_FRAME")
                            .put("requestedFrames", 1)
                            .put("savedFrames", 1)
                    }
                }
                true
            } catch (metadataFailure: Exception) {
                android.util.Log.e(
                    "KeplerYuvPipeline",
                    "Failed to persist YUV handoff metadata: ${metadataFailure.message}",
                    metadataFailure
                )
                false
            }
            if (!metadataDurable) {
                try {
                    KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(jobDir)
                } catch (settledError: Error) {
                    throw settledError
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (settlementError: Exception) {
                    android.util.Log.e(
                        "KeplerYuvPipeline",
                        "Failed to settle YUV processing handoff after metadata persistence failure: ${settlementError.message}",
                        settlementError
                    )
                }
                post("처리 요청 정보를 저장하지 못했습니다. 캐시를 보존했습니다. 나중에 다시 처리할 수 있습니다.")
                terminal.publish(
                    CameraPipelineEvent.Terminal.Kind.FAILED,
                    message = "Handoff metadata persistence failed; cache kept for recovery."
                )
                return@captureYuvBurstColorWithMotion
            }
            // Phase 6 boundary: at this point every frame is persisted and
            // verified, ALL post-handoff processing parameters are durable,
            // the durable processing handoff is published, capture resources
            // are settled, and the capture lease is released. The foreground
            // slot ends here; fusion/export continue on the serialized
            // background lane bound to this EXACT job directory.
            onPipelineEvent(
                CameraPipelineEvent.CaptureStageComplete(
                    generation = 0L,
                    counts = CameraPipelineProgressCounts(),
                    message = "촬영이 완료되었습니다. 결과를 처리하고 있습니다.",
                    jobDirectoryPath = jobDir.absolutePath,
                    captureResourcesSettled = true,
                    processingHandoffDurable = true
                )
            )
            val request = BackgroundProcessingRequest(exactJobDirectory = jobDir, jobKind = KeplerActiveOperationKind.PROCESSING_YUV)
            val laneAccepted = BackgroundProcessingCoordinator.of(context.applicationContext).enqueue(request)
            if (laneAccepted !is BackgroundEnqueueResult.Accepted) {
                // Phase 5F: scheduling failed AFTER the durable handoff. The
                // job is never lost - retain handoff/reconciliation evidence so
                // startup recovery can reprocess it, and surface a non-blocking
                // failure without claiming processing success.
                try {
                    KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(jobDir)
                } catch (settledError: Error) {
                    throw settledError
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (settlementError: Exception) {
                    android.util.Log.e(
                        "KeplerYuvPipeline",
                        "Failed to settle YUV processing handoff after lane scheduling failure: ${settlementError.message}",
                        settlementError
                    )
                }
                post("백그라운드 처리 등록에 실패했습니다. 캐시를 보존했습니다. 나중에 다시 처리할 수 있습니다.")
                terminal.publish(
                    CameraPipelineEvent.Terminal.Kind.FAILED,
                    message = "Background processing scheduling failed; cache kept for recovery."
                )
            }
        },
        onError = { error ->
            post("PIPELINE_FAILED: Capture failed; keeping cache.\n$error")
            terminal.publish(CameraPipelineEvent.Terminal.Kind.FAILED, message = error)
        },
        onStatus = { message ->
            post(message)
        },
        onTypedCaptureProgress = onPipelineEvent
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
        val candidate = HandlerThread("KeplerYuvReprocessThread", android.os.Process.THREAD_PRIORITY_BACKGROUND)
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
