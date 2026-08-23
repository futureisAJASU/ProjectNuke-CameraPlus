package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Flags-to-kind mapping for background terminal truth. The exact production
 * results (local commit, public commit, verification) decide the kind; a log
 * string is never parsed.
 */
internal fun backgroundTerminalKind(
    requiredOutputCommitted: Boolean,
    publicExportCommitted: Boolean,
    verified: Boolean
): CameraPipelineEvent.Terminal.Kind = when {
    verified && publicExportCommitted && requiredOutputCommitted ->
        CameraPipelineEvent.Terminal.Kind.COMPLETE
    requiredOutputCommitted || publicExportCommitted ->
        CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL
    else -> CameraPipelineEvent.Terminal.Kind.FAILED
}

/** One deferred exact terminal outcome, published after lease settlement. */
private data class BackgroundTerminalSpec(
    val kind: CameraPipelineEvent.Terminal.Kind,
    val requiredOutputCommitted: Boolean,
    val publicExportCommitted: Boolean,
    val verified: Boolean,
    val message: String?
)

/**
 * Process-scoped, stateless executor for [BackgroundProcessingRequest].
 * Uses [appContext] (applicationContext) only and reconstructs ALL processing
 * parameters from the exact job's durable metadata (job.json).
 * Must not capture Activity, Compose, or UI callbacks.
 *
 * Event surface: every accepted request publishes non-terminal stage events
 * plus EXACTLY ONE terminal event through [BackgroundPipelineEventHub], keyed
 * by the exact request job directory. The terminal is emitted only after the
 * durable job truth is settled AND the owning lease reached its release/
 * retain-for-reconciliation boundary - delivery is observational only.
 * A fatal Error is never downgraded to FAILED: it propagates (lane terminates)
 * while the lease retains reconciliation debt as today.
 */
internal object KeplerBackgroundExecutor : BackgroundProcessingExecutor {

    override fun execute(request: BackgroundProcessingRequest, appContext: Context) {
        val jobDir = request.exactJobDirectory
        val jobKind = request.jobKind
        val jobJson = try {
            KeplerJobMetadata.read(jobDir)
        } catch (e: Exception) {
            Log.e("KeplerBackgroundExecutor", "Failed to read job metadata for ${jobDir.name}", e)
            publishReadFailure(request, e)
            return
        }

        val captureMode = try {
            CaptureMode.valueOf(jobJson.optString("captureMode", CaptureMode.MULTI_FRAME.name))
        } catch (_: Exception) { CaptureMode.MULTI_FRAME }
        val finalOutputFormat = try {
            FinalOutputFormat.valueOf(jobJson.optString("finalOutputFormatSetting", FinalOutputFormat.JPEG.name))
        } catch (_: Exception) { FinalOutputFormat.JPEG }
        val displayRotation = jobJson.optInt("displayRotation", 0)

        val priority = android.os.Process.getThreadPriority(android.os.Process.myTid())
        if (priority != android.os.Process.THREAD_PRIORITY_BACKGROUND) {
            Log.w("KeplerBackgroundExecutor", "Heavy work not on background priority: $priority")
        }

        when (jobKind) {
            KeplerActiveOperationKind.PROCESSING_YUV ->
                if (jobJson.optString("backgroundWorkerKind") == "SUPER_RESOLUTION") {
                    executeSuperResolution(request, appContext, jobJson, finalOutputFormat)
                } else {
                    executeYuv(request, appContext, jobJson, finalOutputFormat)
                }
            KeplerActiveOperationKind.PROCESSING_RAW -> executeRaw(request, appContext, jobJson, finalOutputFormat)
            else -> executeSuperResolution(request, appContext, jobJson, finalOutputFormat)
        }
    }

    private fun publishReadFailure(request: BackgroundProcessingRequest, failure: Exception) {
        try {
            BackgroundPipelineEventHub.publish(
                BackgroundPipelineEvent(
                    exactJobDirectory = request.exactJobDirectory,
                    jobKind = request.jobKind,
                    event = CameraPipelineEvent.Terminal(
                        generation = 0L,
                        kind = CameraPipelineEvent.Terminal.Kind.FAILED,
                        captureResourcesSettled = true,
                        message = "Background job metadata unreadable: ${failure.message ?: failure.javaClass.simpleName}",
                        jobDirectoryPath = request.exactJobDirectory.absolutePath
                    )
                )
            )
        } catch (_: Throwable) {
        }
    }

    private fun eventCounts(jobJson: org.json.JSONObject): CameraPipelineProgressCounts =
        CameraPipelineProgressCounts(
            requestedFrames = jobJson.optInt("requestedFrames", 0),
            savedFrames = jobJson.optInt("savedFrames", 0)
        )

    private fun backgroundEventSink(request: BackgroundProcessingRequest): (CameraPipelineEvent) -> Unit = { event ->
        BackgroundPipelineEventHub.publish(
            BackgroundPipelineEvent(
                exactJobDirectory = request.exactJobDirectory,
                jobKind = request.jobKind,
                event = event
            )
        )
    }

    private fun statusLogger(jobDir: File): (String) -> Unit = { message ->
        Log.i("KeplerBackgroundExecutor", "${jobDir.name}: $message")
    }

    /**
     * Best-effort durable FAILED truth for an ordinary processing exception.
     * Only written when this attempt holds no committed-output claim, so a
     * committed/partial export is never contradicted by a late failure.
     */
    private fun markOrdinaryFailureTruth(
        jobDir: File,
        lease: JobOperationLease?,
        failure: Exception
    ) {
        try {
            val committedClaim = try {
                requiredOutputCommittedAfterProcessing(jobDir, lease) ||
                    currentProcessingAttemptHasRequiredOutputClaimForLease(jobDir, lease)
            } catch (_: Throwable) { false } || runCatching {
                KeplerJobMetadata.read(jobDir).optBoolean("galleryExportCommitted", false)
            }.getOrDefault(false)
            if (committedClaim) return
            KeplerJobMetadata.update(jobDir) { job ->
                if (job.optString("currentPipelineStage") == "COMPLETE" ||
                    job.optString("currentPipelineStage") == "PARTIAL"
                ) return@update
                job.put("currentPipelineStage", "FAILED")
                    .put("processStatus", "PIPELINE_FAILED")
                    .put("pipelineFailed", true)
                    .put("backgroundExecutionFailed", true)
                    .put("pipelineFailureSource", "BACKGROUND_EXECUTOR")
                    .put("pipelineFailureType", failure.javaClass.name)
                    .put("pipelineFailureMessage", failure.message ?: failure.javaClass.simpleName)
            }
        } catch (truthFailure: Throwable) {
            Log.e("KeplerBackgroundExecutor", "durable FAILED truth write failed ${jobDir.name}", truthFailure)
        }
    }

    /**
     * Publishes the exact deferred outcome once, after lease settlement. An
     * ordinary failure settles durable FAILED truth first, then publishes.
     */
    private fun settleTerminal(
        terminal: CameraPipelineTerminalPublisher,
        counts: CameraPipelineProgressCounts,
        jobDir: File,
        pendingOutcome: BackgroundTerminalSpec?,
        ordinaryFailure: Exception?,
        truthDir: File,
        lease: JobOperationLease?
    ) {
        val failure = ordinaryFailure
        if (failure != null && !terminal.isPublished()) {
            markOrdinaryFailureTruth(truthDir, lease, failure)
            terminal.publish(
                CameraPipelineEvent.Terminal.Kind.FAILED,
                requiredOutputCommitted = false,
                publicExportCommitted = false,
                verified = false,
                captureResourcesSettled = true,
                counts = counts,
                message = "Background processing failed: ${failure.message ?: failure.javaClass.simpleName}",
                jobDirectoryPath = jobDir.absolutePath
            )
            return
        }
        val spec = pendingOutcome ?: return
        terminal.publish(
            spec.kind,
            requiredOutputCommitted = spec.requiredOutputCommitted,
            publicExportCommitted = spec.publicExportCommitted,
            verified = spec.verified,
            captureResourcesSettled = true,
            counts = counts,
            message = spec.message,
            jobDirectoryPath = jobDir.absolutePath
        )
    }

    private fun executeYuv(
        request: BackgroundProcessingRequest,
        appContext: Context,
        jobJson: org.json.JSONObject,
        finalOutputFormat: FinalOutputFormat
    ) {
        val jobDir = request.exactJobDirectory
        val post = statusLogger(jobDir)
        val emit = backgroundEventSink(request)
        val terminal = CameraPipelineTerminalPublisher(emit)
        val counts = eventCounts(jobJson)
        var lease: JobOperationLease? = null
        var ordinaryFailure: Exception? = null
        var pendingOutcome: BackgroundTerminalSpec? = null
        try {
            val cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                jobDir,
                JobRecoveryMutationIntent.PROCESSING_START,
                consumesProcessingHandoff = true
            )
            val singleFrame = isSingleFrameJob(jobJson)
            val requestedParams = loadClassicYuvFusionParams(jobJson)
            post(if (singleFrame) "Processing single photo..." else "Processing Night Fusion...")
            emit(
                CameraPipelineEvent.ProcessingStage(
                    generation = 0L,
                    stage = CaptureStage.PROCESSING,
                    counts = counts,
                    message = if (singleFrame) "Processing single photo..." else "Processing Night Fusion..."
                )
            )
            val finalFile = if (singleFrame) {
                processSingleFrameJobSync(
                    jobDir = jobDir,
                    requestedParams = requestedParams,
                    cancellation = cancellation,
                    operationLease = lease,
                    onStatus = post
                )
            } else {
                processNightFusionJobV02Sync(
                    jobDir = jobDir,
                    onStatus = post,
                    requestedParams = requestedParams,
                    cancellation = cancellation,
                    operationLease = lease
                )
            }
            var requiredOutputCommitted = requiredOutputCommittedAfterProcessing(jobDir, lease)
            emit(
                CameraPipelineEvent.ExportStage(
                    generation = 0L,
                    stage = CaptureStage.EXPORTING,
                    counts = counts,
                    message = "Saving to Gallery..."
                )
            )
            val bitmap = NoFollowFileSystem.decodeBitmapVerified(finalFile)
                ?: error("Could not decode final image.")
            val displayNameBase = "Kepler_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
            val export = withSettlementPrecedence(
                block = {
                    exportNightFusionBitmapToGallery(
                        context = appContext,
                        bitmap = bitmap,
                        displayNameBase = displayNameBase,
                        requestedFormat = requestedOutputFormatForSetting(finalOutputFormat),
                        cancellation = cancellation,
                        jobDir = jobDir,
                        ownerLease = lease
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
                    operationLease = lease
                )
                requiredOutputCommitted = requiredOutputCommitted ||
                    currentProcessingAttemptHasRequiredOutputClaimForLease(jobDir, lease)
                post(
                    if (requiredOutputCommitted) {
                        "PIPELINE_COMPLETE_PARTIAL: local result kept; Gallery export failed."
                    } else {
                        "PIPELINE_FAILED: Gallery export failed. ${export.errorMessage ?: ""}"
                    }
                )
                pendingOutcome = BackgroundTerminalSpec(
                    kind = backgroundTerminalKind(
                        requiredOutputCommitted = requiredOutputCommitted,
                        publicExportCommitted = false,
                        verified = false
                    ),
                    requiredOutputCommitted = requiredOutputCommitted,
                    publicExportCommitted = false,
                    verified = false,
                    message = export.errorMessage ?: "Gallery export failed"
                )
                return
            }
            val verified = verifyCommittedGalleryExport(appContext, export) is GalleryExportVerification.Verified
            requiredOutputCommitted = requiredOutputCommitted ||
                currentProcessingAttemptHasRequiredOutputClaimForLease(jobDir, lease)
            updateExportMetadata(
                jobDir = jobDir,
                export = export,
                verified = verified,
                finalOutputFormat = finalOutputFormat,
                rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar,
                operationLease = lease
            )
            post(if (verified) "PIPELINE_COMPLETE: Saved to Gallery." else "PIPELINE_COMPLETE_PARTIAL: verification not proven; cache kept.")
            pendingOutcome = BackgroundTerminalSpec(
                kind = backgroundTerminalKind(
                    requiredOutputCommitted = requiredOutputCommitted,
                    publicExportCommitted = true,
                    verified = verified
                ),
                requiredOutputCommitted = requiredOutputCommitted,
                publicExportCommitted = true,
                verified = verified,
                message = if (verified) {
                    "PIPELINE_COMPLETE: Saved to Gallery."
                } else {
                    "PIPELINE_COMPLETE_PARTIAL: verification not proven; cache kept."
                }
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            ordinaryFailure = null
            pendingOutcome = BackgroundTerminalSpec(
                kind = CameraPipelineEvent.Terminal.Kind.CANCELLED,
                requiredOutputCommitted = false,
                publicExportCommitted = false,
                verified = false,
                message = "Background YUV processing cancelled."
            )
            throw cancelled
        } catch (failure: Exception) {
            Log.e("KeplerBackgroundExecutor", "YUV background job failed ${jobDir.name}", failure)
            ordinaryFailure = failure
        } finally {
            lease?.let { lease ->
                try {
                    if (!lease.releaseOrRetainForReconciliation()) {
                        Log.w("KeplerBackgroundExecutor", "retaining lease for reconciliation ${jobDir.name}")
                    }
                } catch (releaseFailure: Throwable) {
                    Log.e("KeplerBackgroundExecutor", "lease release failed ${jobDir.name}", releaseFailure)
                }
            }
            settleTerminal(terminal, counts, jobDir, pendingOutcome, ordinaryFailure, jobDir, lease)
        }
    }

    private fun executeRaw(
        request: BackgroundProcessingRequest,
        appContext: Context,
        jobJson: org.json.JSONObject,
        finalOutputFormat: FinalOutputFormat
    ) {
        val jobDir = request.exactJobDirectory
        val post = statusLogger(jobDir)
        val emit = backgroundEventSink(request)
        val terminal = CameraPipelineTerminalPublisher(emit)
        val counts = eventCounts(jobJson)
        var lease: JobOperationLease? = null
        var ordinaryFailure: Exception? = null
        var pendingOutcome: BackgroundTerminalSpec? = null
        try {
            val cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                jobDir,
                JobRecoveryMutationIntent.PROCESSING_START,
                consumesProcessingHandoff = true
            )
            post("Processing RAW fusion...")
            emit(
                CameraPipelineEvent.ProcessingStage(
                    generation = 0L,
                    stage = CaptureStage.DEMOSAICING,
                    counts = counts,
                    message = "Processing RAW fusion..."
                )
            )
            val result = processRawFusionJob(
                context = appContext,
                jobDir = jobDir,
                cancellation = cancellation,
                metadataPolicy = ReprocessMetadataPolicy.NORMAL,
                operationLease = lease,
                onStatus = post
            )
            val source = result.finalPngFile ?: result.previewPngFile
            if (!result.success || source == null || !source.isFile) {
                val message = result.errorMessage ?: "RAW fusion failed"
                post("PIPELINE_FAILED: RAW fusion failed. $message")
                markOrdinaryFailureTruth(jobDir, lease, IllegalStateException(message))
                pendingOutcome = BackgroundTerminalSpec(
                    kind = backgroundTerminalKind(
                        requiredOutputCommitted = result.outputCommitted,
                        publicExportCommitted = false,
                        verified = false
                    ),
                    requiredOutputCommitted = result.outputCommitted,
                    publicExportCommitted = false,
                    verified = false,
                    message = message
                )
                return
            }
            emit(
                CameraPipelineEvent.ExportStage(
                    generation = 0L,
                    stage = CaptureStage.EXPORTING,
                    counts = counts,
                    message = "Saving RAW fusion to Gallery..."
                )
            )
            val bitmap = NoFollowFileSystem.decodeBitmapVerified(source)
                ?: error("Could not decode RAW fusion output.")
            val displayNameBase = "Kepler_RAW_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
            val export = withSettlementPrecedence(
                block = {
                    exportNightFusionBitmapToGallery(
                        context = appContext,
                        bitmap = bitmap,
                        displayNameBase = displayNameBase,
                        requestedFormat = requestedOutputFormatForSetting(finalOutputFormat),
                        cancellation = cancellation,
                        jobDir = jobDir,
                        ownerLease = lease
                    )
                },
                cleanup = { bitmap.recycle() }
            )
            if (!export.publicCommitted || export.uriString.isNullOrBlank()) {
                updateExportFailure(
                    jobDir = jobDir,
                    error = export.errorMessage ?: "Unknown RAW export failure",
                    finalOutputFormat = finalOutputFormat,
                    rawSidecarIgnored = false,
                    export = export,
                    requiredOutputCommitted = result.outputCommitted,
                    operationLease = lease
                )
                val localCommitted = result.outputCommitted ||
                    currentProcessingAttemptHasRequiredOutputClaimForLease(jobDir, lease)
                post(
                    if (localCommitted) {
                        "PIPELINE_COMPLETE_PARTIAL: RAW local result kept; Gallery export failed."
                    } else {
                        "PIPELINE_FAILED: RAW Gallery export failed. ${export.errorMessage ?: ""}"
                    }
                )
                pendingOutcome = BackgroundTerminalSpec(
                    kind = backgroundTerminalKind(
                        requiredOutputCommitted = localCommitted,
                        publicExportCommitted = false,
                        verified = false
                    ),
                    requiredOutputCommitted = localCommitted,
                    publicExportCommitted = false,
                    verified = false,
                    message = export.errorMessage ?: "RAW Gallery export failed"
                )
                return
            }
            val verified = verifyCommittedGalleryExport(appContext, export) is GalleryExportVerification.Verified
            updateExportMetadata(
                jobDir = jobDir,
                export = export,
                verified = verified,
                finalOutputFormat = finalOutputFormat,
                rawSidecarIgnored = false,
                operationLease = lease
            )
            post(if (verified) "PIPELINE_COMPLETE: RAW saved to Gallery." else "PIPELINE_COMPLETE_PARTIAL: RAW verification not proven; cache kept.")
            pendingOutcome = BackgroundTerminalSpec(
                kind = backgroundTerminalKind(
                    requiredOutputCommitted = true,
                    publicExportCommitted = true,
                    verified = verified
                ),
                requiredOutputCommitted = true,
                publicExportCommitted = true,
                verified = verified,
                message = if (verified) {
                    "PIPELINE_COMPLETE: RAW saved to Gallery."
                } else {
                    "PIPELINE_COMPLETE_PARTIAL: RAW verification not proven; cache kept."
                }
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            ordinaryFailure = null
            pendingOutcome = BackgroundTerminalSpec(
                kind = CameraPipelineEvent.Terminal.Kind.CANCELLED,
                requiredOutputCommitted = false,
                publicExportCommitted = false,
                verified = false,
                message = "Background RAW processing cancelled."
            )
            throw cancelled
        } catch (failure: Exception) {
            Log.e("KeplerBackgroundExecutor", "RAW background job failed ${jobDir.name}", failure)
            ordinaryFailure = failure
        } finally {
            lease?.let { lease ->
                try {
                    if (!lease.releaseOrRetainForReconciliation()) {
                        Log.w("KeplerBackgroundExecutor", "retaining lease for reconciliation ${jobDir.name}")
                    }
                } catch (releaseFailure: Throwable) {
                    Log.e("KeplerBackgroundExecutor", "lease release failed ${jobDir.name}", releaseFailure)
                }
            }
            settleTerminal(terminal, counts, jobDir, pendingOutcome, ordinaryFailure, jobDir, lease)
        }
    }

    private fun executeSuperResolution(
        request: BackgroundProcessingRequest,
        appContext: Context,
        jobJson: org.json.JSONObject,
        finalOutputFormat: FinalOutputFormat
    ) {
        val sourceJobDir = request.exactJobDirectory
        val post = statusLogger(sourceJobDir)
        val emit = backgroundEventSink(request)
        val terminal = CameraPipelineTerminalPublisher(emit)
        val counts = eventCounts(jobJson)
        var lease: JobOperationLease? = null
        var ordinaryFailure: Exception? = null
        var pendingOutcome: BackgroundTerminalSpec? = null
        var srOutputDir: File? = null
        try {
            val cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
            val sourceFrames = readColorBurstFrameFiles(sourceJobDir)
            if (sourceFrames.isEmpty()) {
                post("PIPELINE_FAILED: no source frames for Super Resolution; cache kept.")
                pendingOutcome = BackgroundTerminalSpec(
                    kind = CameraPipelineEvent.Terminal.Kind.FAILED,
                    requiredOutputCommitted = false,
                    publicExportCommitted = false,
                    verified = false,
                    message = "no source frames for Super Resolution"
                )
                return
            }
            val outputDir = createSuperResolutionJobDirectory(appContext)
            srOutputDir = outputDir
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                outputDir, JobRecoveryMutationIntent.PROCESSING_START
            )
            post("Processing 24M Fusion...")
            emit(
                CameraPipelineEvent.ProcessingStage(
                    generation = 0L,
                    stage = CaptureStage.PROCESSING,
                    counts = counts,
                    message = "Processing 24M Fusion..."
                )
            )
            val result = runSuperResolutionFusion(
                SuperResolutionFusionRequest(
                    context = appContext,
                    inputFrameFiles = sourceFrames,
                    outputDir = outputDir,
                    sourceMode = SuperResolutionSourceMode.BINNED_12MP_YUV,
                    maxFrames = jobJson.optInt("requestedFrames", sourceFrames.size),
                    processingParams = loadClassicYuvFusionParams(jobJson),
                    cancellation = cancellation,
                    operationLease = lease,
                    status = post
                )
            )
            val outputFile = result.outputFile
            if (outputFile == null || !outputFile.exists()) {
                val message = result.message.ifBlank { "24M Fusion failed" }
                post("PIPELINE_FAILED: 24M Fusion failed. $message")
                markOrdinaryFailureTruth(outputDir, lease, IllegalStateException(message))
                pendingOutcome = BackgroundTerminalSpec(
                    kind = CameraPipelineEvent.Terminal.Kind.FAILED,
                    requiredOutputCommitted = false,
                    publicExportCommitted = false,
                    verified = false,
                    message = message
                )
                return
            }
            // Consume the source handoff so the capture job converges.
            try {
                when (KeplerJobMetadata.inspectProcessingHandoff(sourceJobDir, KeplerActiveOperationKind.PROCESSING_YUV)) {
                    KeplerJobMetadata.ProcessingHandoffPresence.CORRELATED ->
                        KeplerJobMetadata.consumeProcessingHandoff(sourceJobDir, KeplerActiveOperationKind.PROCESSING_YUV)
                    else -> Unit
                }
            } catch (handoffFailure: Exception) {
                Log.e("KeplerBackgroundExecutor", "SR source handoff consume failed ${sourceJobDir.name}", handoffFailure)
            }
            emit(
                CameraPipelineEvent.ExportStage(
                    generation = 0L,
                    stage = CaptureStage.EXPORTING,
                    counts = counts,
                    message = "Saving 24M Fusion to Gallery..."
                )
            )
            val bitmap = NoFollowFileSystem.decodeBitmapVerified(outputFile)
                ?: error("Could not decode 24M Fusion output.")
            val displayName = "Kepler_SR_${megapixelLabel(result.targetMegapixels)}MP_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
            val export = withSettlementPrecedence(
                block = {
                    exportNightFusionBitmapToGallery(
                        context = appContext,
                        bitmap = bitmap,
                        displayNameBase = displayName,
                        requestedFormat = requestedOutputFormatForSetting(finalOutputFormat),
                        cancellation = cancellation,
                        jobDir = outputDir,
                        ownerLease = lease
                    )
                },
                cleanup = { bitmap.recycle() }
            )
            if (!export.publicCommitted || export.uriString.isNullOrBlank()) {
                updateExportFailure(
                    jobDir = outputDir,
                    error = export.errorMessage ?: "Unknown SR export failure",
                    finalOutputFormat = finalOutputFormat,
                    rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar,
                    export = export,
                    requiredOutputCommitted = requiredOutputCommittedAfterProcessing(outputDir, lease),
                    operationLease = lease
                )
                val localCommitted = requiredOutputCommittedAfterProcessing(outputDir, lease) ||
                    currentProcessingAttemptHasRequiredOutputClaimForLease(outputDir, lease) ||
                    outputFile.isFile
                post(
                    if (localCommitted) {
                        "PIPELINE_COMPLETE_PARTIAL: 24M Fusion result kept; Gallery export failed."
                    } else {
                        "PIPELINE_FAILED: SR Gallery export failed. ${export.errorMessage ?: ""}"
                    }
                )
                pendingOutcome = BackgroundTerminalSpec(
                    kind = backgroundTerminalKind(
                        requiredOutputCommitted = localCommitted,
                        publicExportCommitted = false,
                        verified = false
                    ),
                    requiredOutputCommitted = localCommitted,
                    publicExportCommitted = false,
                    verified = false,
                    message = export.errorMessage ?: "SR Gallery export failed"
                )
                return
            }
            val verified = verifyCommittedGalleryExport(appContext, export) is GalleryExportVerification.Verified
            updateExportMetadata(
                jobDir = outputDir,
                export = export,
                verified = verified,
                finalOutputFormat = finalOutputFormat,
                rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar,
                operationLease = lease
            )
            post(if (verified) "PIPELINE_COMPLETE: 24M Fusion saved to Gallery." else "PIPELINE_COMPLETE_PARTIAL: SR verification not proven; cache kept.")
            pendingOutcome = BackgroundTerminalSpec(
                kind = backgroundTerminalKind(
                    requiredOutputCommitted = true,
                    publicExportCommitted = true,
                    verified = verified
                ),
                requiredOutputCommitted = true,
                publicExportCommitted = true,
                verified = verified,
                message = if (verified) {
                    "PIPELINE_COMPLETE: 24M Fusion saved to Gallery."
                } else {
                    "PIPELINE_COMPLETE_PARTIAL: SR verification not proven; cache kept."
                }
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            ordinaryFailure = null
            pendingOutcome = BackgroundTerminalSpec(
                kind = CameraPipelineEvent.Terminal.Kind.CANCELLED,
                requiredOutputCommitted = false,
                publicExportCommitted = false,
                verified = false,
                message = "Background 24M Fusion cancelled."
            )
            throw cancelled
        } catch (failure: Exception) {
            Log.e("KeplerBackgroundExecutor", "SR background job failed ${sourceJobDir.name}", failure)
            ordinaryFailure = failure
        } finally {
            lease?.let { lease ->
                try {
                    if (!lease.releaseOrRetainForReconciliation()) {
                        Log.w("KeplerBackgroundExecutor", "retaining lease for reconciliation ${sourceJobDir.name}")
                    }
                } catch (releaseFailure: Throwable) {
                    Log.e("KeplerBackgroundExecutor", "lease release failed ${sourceJobDir.name}", releaseFailure)
                }
            }
            settleTerminal(
                terminal, counts, sourceJobDir, pendingOutcome, ordinaryFailure,
                srOutputDir ?: sourceJobDir, lease
            )
        }
    }
}
