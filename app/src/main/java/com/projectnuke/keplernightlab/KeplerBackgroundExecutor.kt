package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-scoped, stateless executor for [BackgroundProcessingRequest].
 * Uses [appContext] (applicationContext) only and reconstructs ALL processing
 * parameters from the exact job's durable metadata (job.json).
 * Must not capture Activity, Compose, or UI callbacks.
 */
internal object KeplerBackgroundExecutor : BackgroundProcessingExecutor {

    override fun execute(request: BackgroundProcessingRequest, appContext: Context) {
        val jobDir = request.exactJobDirectory
        val jobKind = request.jobKind
        val jobJson = try {
            KeplerJobMetadata.read(jobDir)
        } catch (e: Exception) {
            Log.e("KeplerBackgroundExecutor", "Failed to read job metadata for ${jobDir.name}", e)
            return
        }

        // Reconstruct all required params from durable job.json (not from closures)
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
            KeplerActiveOperationKind.PROCESSING_YUV -> executeYuv(request, appContext, jobJson, finalOutputFormat)
            KeplerActiveOperationKind.PROCESSING_RAW -> executeRaw(request, appContext, jobJson, finalOutputFormat)
            else -> executeSuperResolution(request, appContext, jobJson, finalOutputFormat)
        }
    }

    private fun statusLogger(jobDir: File): (String) -> Unit = { message ->
        Log.i("KeplerBackgroundExecutor", "${jobDir.name}: $message")
    }

    private fun executeYuv(
        request: BackgroundProcessingRequest,
        appContext: Context,
        jobJson: org.json.JSONObject,
        finalOutputFormat: FinalOutputFormat
    ) {
        val jobDir = request.exactJobDirectory
        val post = statusLogger(jobDir)
        var lease: JobOperationLease? = null
        try {
            val cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                jobDir, JobRecoveryMutationIntent.PROCESSING_START
            )
            val singleFrame = isSingleFrameJob(jobJson)
            val requestedParams = loadClassicYuvFusionParams(jobJson)
            post(if (singleFrame) "Processing single photo..." else "Processing Night Fusion...")
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
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e("KeplerBackgroundExecutor", "YUV background job failed ${jobDir.name}", failure)
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
        var lease: JobOperationLease? = null
        try {
            val cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                jobDir, JobRecoveryMutationIntent.PROCESSING_START
            )
            post("Processing RAW fusion...")
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
                post("PIPELINE_FAILED: RAW fusion failed. ${result.errorMessage}")
                return
            }
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
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e("KeplerBackgroundExecutor", "RAW background job failed ${jobDir.name}", failure)
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
        }
    }

    private fun executeSuperResolution(
        request: BackgroundProcessingRequest,
        appContext: Context,
        jobJson: org.json.JSONObject,
        finalOutputFormat: FinalOutputFormat
    ) {
        val jobDir = request.exactJobDirectory
        val post = statusLogger(jobDir)
        var lease: JobOperationLease? = null
        try {
            val cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
            val sourceFrames = readColorBurstFrameFiles(jobDir)
            if (sourceFrames.isEmpty()) {
                post("PIPELINE_FAILED: no source frames for Super Resolution; cache kept.")
                return
            }
            val outputDir = createSuperResolutionJobDirectory(appContext)
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                outputDir, JobRecoveryMutationIntent.PROCESSING_START
            )
            post("Processing 24M Fusion...")
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
                post("PIPELINE_FAILED: 24M Fusion failed. ${result.message}")
                return
            }
            // Consume the source handoff so the capture job converges.
            try {
                when (KeplerJobMetadata.inspectProcessingHandoff(jobDir, KeplerActiveOperationKind.PROCESSING_YUV)) {
                    KeplerJobMetadata.ProcessingHandoffPresence.CORRELATED ->
                        KeplerJobMetadata.consumeProcessingHandoff(jobDir, KeplerActiveOperationKind.PROCESSING_YUV)
                    else -> Unit
                }
            } catch (handoffFailure: Exception) {
                Log.e("KeplerBackgroundExecutor", "SR source handoff consume failed ${jobDir.name}", handoffFailure)
            }
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
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e("KeplerBackgroundExecutor", "SR background job failed ${request.exactJobDirectory.name}", failure)
        } finally {
            lease?.let { lease ->
                try {
                    if (!lease.releaseOrRetainForReconciliation()) {
                        Log.w("KeplerBackgroundExecutor", "retaining lease for reconciliation ${request.exactJobDirectory.name}")
                    }
                } catch (releaseFailure: Throwable) {
                    Log.e("KeplerBackgroundExecutor", "lease release failed ${request.exactJobDirectory.name}", releaseFailure)
                }
            }
        }
    }
}
