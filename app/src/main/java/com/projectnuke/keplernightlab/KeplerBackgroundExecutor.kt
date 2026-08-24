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

/**
 * The RAW background failure gate.  Delegates source truth ENTIRELY to the
 * established export-source abstraction ([RawFusionProcessResult.hasExportableBitmapSource]):
 * a successful standard RAW run returns a native-RGBA-only result, which is the
 * exportable success shape — never a fusion failure.
 */
internal fun rawBackgroundFusionFailed(result: RawFusionProcessResult): Boolean =
    !result.success || !result.hasExportableBitmapSource()

/** One deferred exact terminal outcome, published after lease settlement. */
internal data class BackgroundTerminalSpec(
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

    /**
     * Observational FAILED terminal for an unreadable job. Publication failure
     * on this path is diagnostic only (ordinary Exception); a fatal Error
     * propagates unchanged — it is never converted into silence.
     */
    private fun publishReadFailure(request: BackgroundProcessingRequest, failure: Exception) {
        try {
            BackgroundPipelineEventHub.publish(
                BackgroundPipelineEvent(
                    requestJobDirectory = request.exactJobDirectory,
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
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
        }
    }

    private fun eventCounts(jobJson: org.json.JSONObject): CameraPipelineProgressCounts =
        CameraPipelineProgressCounts(
            requestedFrames = jobJson.optInt("requestedFrames", 0),
            savedFrames = jobJson.optInt("savedFrames", 0)
        )

    /**
     * Observational event sink carrying BOTH durable identities. [request] is
     * the routing identity; [resultIdentity] supplies the result identity at
     * publish time (for Super Resolution this switches to the newly created
     * output directory as soon as it exists; before that it equals the request
     * identity, since no result directory exists yet).
     */
    private fun backgroundEventSink(
        request: BackgroundProcessingRequest,
        resultIdentity: () -> File = { request.exactJobDirectory }
    ): (CameraPipelineEvent) -> Unit = { event ->
        BackgroundPipelineEventHub.publish(
            BackgroundPipelineEvent(
                requestJobDirectory = request.exactJobDirectory,
                resultJobDirectory = resultIdentity(),
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
     *
     * Fatal precedence (durable-truth mutation helper): Error and
     * CancellationException are NEVER swallowed or converted — both propagate
     * unchanged. Only ordinary [Exception]s degrade to the documented fallback
     * (log + leave durable truth untouched).
     */
    private fun markOrdinaryFailureTruth(
        jobDir: File,
        lease: JobOperationLease?,
        failure: Exception
    ) {
        try {
            if (hasCommittedOutputClaim(jobDir, lease)) return
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
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (truthFailure: Exception) {
            Log.e(
                "KeplerBackgroundExecutor",
                "durable FAILED truth write failed ${jobDir.name}",
                truthFailure
            )
        }
    }

    /**
     * Read-only committed-output claim probe for [markOrdinaryFailureTruth].
     * Each probe is isolated independently: an ordinary failure in one probe
     * falls back to "no claim from THIS probe" without hiding the other probes.
     * A fatal Error or CancellationException from any probe propagates unchanged.
     */
    private fun hasCommittedOutputClaim(jobDir: File, lease: JobOperationLease?): Boolean {
        val leaseClaim = try {
            requiredOutputCommittedAfterProcessing(jobDir, lease) ||
                currentProcessingAttemptHasRequiredOutputClaimForLease(jobDir, lease)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            false
        }
        if (leaseClaim) return true
        return try {
            KeplerJobMetadata.read(jobDir).optBoolean("galleryExportCommitted", false)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Publishes the exact deferred outcome once, after lease settlement. An
     * ordinary failure settles durable FAILED truth first, then publishes.
     *
     * [leaseBoundaryReached] is the truthful release/retain-for-reconciliation
     * boundary observation: only a boundary that was actually reached supports
     * an ownership-settled claim ([captureResourcesSettled] = true). A lease
     * settlement that failed ordinarily publishes settled=false — never a claim
     * unsupported by reality.
     */
    private fun settleTerminal(
        terminal: CameraPipelineTerminalPublisher,
        counts: CameraPipelineProgressCounts,
        jobDir: File,
        pendingOutcome: BackgroundTerminalSpec?,
        ordinaryFailure: Exception?,
        truthDir: File,
        lease: JobOperationLease?,
        leaseBoundaryReached: Boolean = true,
        resultJobDirectoryPath: String? = null
    ) {
        val failure = ordinaryFailure
        if (failure != null && !terminal.isPublished()) {
            markOrdinaryFailureTruth(truthDir, lease, failure)
            terminal.publish(
                CameraPipelineEvent.Terminal.Kind.FAILED,
                requiredOutputCommitted = false,
                publicExportCommitted = false,
                verified = false,
                captureResourcesSettled = leaseBoundaryReached,
                counts = counts,
                message = "Background processing failed: ${failure.message ?: failure.javaClass.simpleName}",
                jobDirectoryPath = jobDir.absolutePath,
                resultJobDirectoryPath = resultJobDirectoryPath
            )
            return
        }
        val spec = pendingOutcome ?: return
        terminal.publish(
            spec.kind,
            requiredOutputCommitted = spec.requiredOutputCommitted,
            publicExportCommitted = spec.publicExportCommitted,
            verified = spec.verified,
            captureResourcesSettled = leaseBoundaryReached,
            counts = counts,
            message = spec.message,
            jobDirectoryPath = jobDir.absolutePath,
            resultJobDirectoryPath = resultJobDirectoryPath
        )
    }

    /**
     * Fatal-settlement precedence for the lane finally block (shared by the
     * YUV/RAW/SR lanes):
     *
     *  1. [Error]: required local bookkeeping (log + boundary marked unreached),
     *     then RETHROW — never swallowed as an ordinary Throwable. The original
     *     in-flight throwable, if any, is attached as suppressed first.
     *  2. CancellationException: cancellation semantics preserved (rethrown).
     *  3. Exception: the lease retains reconciliation debt explicitly; the
     *     boundary is reported UNREACHED and the terminal publisher still runs
     *     with captureResourcesSettled=false (no ownership-settled claim
     *     unsupported by reality).
     *
     * The terminal publication runs in a nested finally so even a fatal Error /
     * cancellation during lease release still attempts the exactly-once
     * publication before propagating.
     */
    internal fun finalizeLaneAfterExecution(
        releaseLease: (() -> Boolean)?,
        jobDirName: String,
        inFlight: Throwable?,
        publishTerminal: (captureResourcesSettled: Boolean) -> Unit
    ) {
        var boundaryReached = releaseLease == null
        try {
            val release = releaseLease ?: return
            try {
                if (release()) {
                    boundaryReached = true
                } else {
                    // Explicit retain-for-reconciliation IS the documented owner
                    // boundary: debt stays observable and reconciliation-ready.
                    Log.w("KeplerBackgroundExecutor", "retaining lease for reconciliation $jobDirName")
                    boundaryReached = true
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                Log.e("KeplerBackgroundExecutor", "lease release cancelled $jobDirName", cancelled)
                throw cancelled
            } catch (fatal: Error) {
                Log.e("KeplerBackgroundExecutor", "lease release failed fatally $jobDirName", fatal)
                throw fatal
            } catch (releaseFailure: Exception) {
                Log.e("KeplerBackgroundExecutor", "lease release failed $jobDirName", releaseFailure)
                boundaryReached = false
            }
        } catch (propagated: Throwable) {
            inFlight?.let { if (it !== propagated) propagated.addSuppressed(it) }
            throw propagated
        } finally {
            publishTerminal(boundaryReached)
        }
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
        var inFlight: Throwable? = null
        try {
            val cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                jobDir,
                JobRecoveryMutationIntent.PROCESSING_START,
                consumesProcessingHandoff = true
            )
            val singleFrame = isSingleFrameJob(jobJson)
            val requestedParams = loadClassicYuvFusionParams(jobJson)
            post("Processing Night Fusion...")
            emit(
                CameraPipelineEvent.ProcessingStage(
                    generation = 0L,
                    stage = CaptureStage.PROCESSING,
                    counts = counts,
                    message = if (singleFrame) "Processing single photo..." else "Processing Night Fusion..."
                )
            )
            val yuvProcessingStartedAt = android.os.SystemClock.elapsedRealtime()
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
            persistBackgroundStageTiming(
                jobDir,
                org.json.JSONObject().put(
                    "processingMs",
                    android.os.SystemClock.elapsedRealtime() - yuvProcessingStartedAt
                )
            )
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
            inFlight = cancelled
            pendingOutcome = BackgroundTerminalSpec(
                kind = CameraPipelineEvent.Terminal.Kind.CANCELLED,
                requiredOutputCommitted = false,
                publicExportCommitted = false,
                verified = false,
                message = "Background YUV processing cancelled."
            )
            throw cancelled
        } catch (fatal: Error) {
            // Fatal settlement precedence: record, never swallow — rethrow after
            // the finally bookkeeping. No terminal is synthesized for a fatal Error.
            inFlight = fatal
            throw fatal
        } catch (failure: Exception) {
            Log.e("KeplerBackgroundExecutor", "YUV background job failed ${jobDir.name}", failure)
            ordinaryFailure = failure
        } finally {
            finalizeLaneAfterExecution(
                releaseLease = lease?.let { l -> ({ l.releaseOrRetainForReconciliation() }) },
                jobDirName = jobDir.name,
                inFlight = inFlight
            ) { leaseBoundaryReached ->
                settleTerminal(
                    terminal, counts, jobDir, pendingOutcome, ordinaryFailure,
                    jobDir, lease, leaseBoundaryReached
                )
            }
        }
    }

    /**
     * Phase 7 stage instrumentation: persists bounded background-lane stage
     * durations into job.json under "backgroundStageTimings".  Best-effort:
     * diagnostics never fail the lane.
     */
    private fun persistBackgroundStageTiming(jobDir: File, stage: org.json.JSONObject) {
        try {
            KeplerJobMetadata.update(jobDir) { job ->
                val timings = job.optJSONObject("backgroundStageTimings") ?: org.json.JSONObject()
                stage.keys().forEach { key -> timings.put(key, stage.get(key)) }
                job.put("backgroundStageTimings", timings)
            }
        } catch (failure: Error) {
            throw failure
        } catch (_: Exception) {
            Log.w("KeplerBackgroundExecutor", "background stage timing persistence failed ${jobDir.name}")
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
        var inFlight: Throwable? = null
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
            val rawProcessingStartedAt = android.os.SystemClock.elapsedRealtime()
            val result = processRawFusionJob(
                context = appContext,
                jobDir = jobDir,
                cancellation = cancellation,
                metadataPolicy = ReprocessMetadataPolicy.NORMAL,
                operationLease = lease,
                onStatus = post
            )
            persistBackgroundStageTiming(
                jobDir,
                org.json.JSONObject().put(
                    "processingMs",
                    android.os.SystemClock.elapsedRealtime() - rawProcessingStartedAt
                )
            )
            // The established export-source abstraction decides failure truth:
            // a successful standard RAW run returns a native-RGBA-only result
            // (finalPngFile == null), which IS exportable — never "RAW fusion failed".
            if (rawBackgroundFusionFailed(result)) {
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
            pendingOutcome = runRawBackgroundExportStage(
                appContext = appContext,
                jobDir = jobDir,
                lease = lease,
                finalOutputFormat = finalOutputFormat,
                result = result,
                counts = counts,
                emit = emit,
                post = post
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            ordinaryFailure = null
            inFlight = cancelled
            pendingOutcome = BackgroundTerminalSpec(
                kind = CameraPipelineEvent.Terminal.Kind.CANCELLED,
                requiredOutputCommitted = false,
                publicExportCommitted = false,
                verified = false,
                message = "Background RAW processing cancelled."
            )
            throw cancelled
        } catch (fatal: Error) {
            inFlight = fatal
            throw fatal
        } catch (failure: Exception) {
            Log.e("KeplerBackgroundExecutor", "RAW background job failed ${jobDir.name}", failure)
            ordinaryFailure = failure
        } finally {
            finalizeLaneAfterExecution(
                releaseLease = lease?.let { l -> ({ l.releaseOrRetainForReconciliation() }) },
                jobDirName = jobDir.name,
                inFlight = inFlight
            ) { leaseBoundaryReached ->
                settleTerminal(
                    terminal, counts, jobDir, pendingOutcome, ordinaryFailure,
                    jobDir, lease, leaseBoundaryReached
                )
            }
        }
    }

    /**
     * The RAW background export stage.  Uses the SAME established export-source
     * semantics as the mature RAW reprocess/export path:
     * [RawFusionProcessResult.hasExportableBitmapSource] /
     * [RawFusionProcessResult.loadExportBitmap] understand native RGBA direct
     * loading, final-PNG fallback, rotation metadata, and recycle precedence.
     * The caller owns lease settlement and terminal publication.
     */
    internal fun runRawBackgroundExportStage(
        appContext: Context,
        jobDir: File,
        lease: JobOperationLease?,
        finalOutputFormat: FinalOutputFormat,
        result: RawFusionProcessResult,
        counts: CameraPipelineProgressCounts,
        emit: (CameraPipelineEvent) -> Unit,
        post: (String) -> Unit
    ): BackgroundTerminalSpec {
        val exportStartedAt = android.os.SystemClock.elapsedRealtime()
        emit(
            CameraPipelineEvent.ExportStage(
                generation = 0L,
                stage = CaptureStage.EXPORTING,
                counts = counts,
                message = "Saving RAW fusion to Gallery..."
            )
        )
        val loaded = result.loadExportBitmap(jobDir)
        val displayNameBase = "Kepler_RAW_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        val export = withSettlementPrecedence(
            block = {
                exportNightFusionBitmapToGallery(
                    context = appContext,
                    bitmap = loaded.bitmap,
                    displayNameBase = displayNameBase,
                    requestedFormat = requestedOutputFormatForSetting(finalOutputFormat),
                    cancellation = NoOpKeplerPipelineCancellation,
                    jobDir = jobDir,
                    ownerLease = lease
                )
            },
            cleanup = { loaded.bitmap.recycle() }
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
            return BackgroundTerminalSpec(
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
        persistBackgroundStageTiming(
            jobDir,
            org.json.JSONObject().put(
                "exportMs",
                android.os.SystemClock.elapsedRealtime() - exportStartedAt
            )
        )
        post(if (verified) "PIPELINE_COMPLETE: RAW saved to Gallery." else "PIPELINE_COMPLETE_PARTIAL: RAW verification not proven; cache kept.")
        return BackgroundTerminalSpec(
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
    }

    private fun executeSuperResolution(
        request: BackgroundProcessingRequest,
        appContext: Context,
        jobJson: org.json.JSONObject,
        finalOutputFormat: FinalOutputFormat
    ) {
        val sourceJobDir = request.exactJobDirectory
        val post = statusLogger(sourceJobDir)
        // Dual identity: routing stays on the SOURCE capture job; as soon as the
        // SR output directory exists it becomes the RESULT identity for every
        // subsequently published event (including the terminal).
        var resultIdentity: File = sourceJobDir
        val emit = backgroundEventSink(request) { resultIdentity }
        val terminal = CameraPipelineTerminalPublisher(emit)
        val counts = eventCounts(jobJson)
        var lease: JobOperationLease? = null
        var ordinaryFailure: Exception? = null
        var pendingOutcome: BackgroundTerminalSpec? = null
        var srOutputDir: File? = null
        var inFlight: Throwable? = null
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
            resultIdentity = outputDir
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                outputDir, JobRecoveryMutationIntent.PROCESSING_START
            )
            // Persist the source/result relationship durably BEFORE any terminal
            // can be published: result side first (stub if fusion metadata does
            // not exist yet), then the reverse link on the source capture job.
            // FAIL-CLOSED: an unproven durable relationship must never reach SR
            // terminal truth — an ordinary link-write failure settles this lane
            // as an ordinary FAILED outcome (cache kept, nothing committed).
            try {
                linkSuperResolutionIdentities(sourceJobDir, outputDir)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (linkFailure: Exception) {
                post("PIPELINE_FAILED: Super Resolution identity link failed; cache kept.")
                markOrdinaryFailureTruth(
                    outputDir,
                    lease,
                    IllegalStateException(
                        linkFailure.message ?: linkFailure.javaClass.simpleName,
                        linkFailure
                    )
                )
                pendingOutcome = BackgroundTerminalSpec(
                    kind = CameraPipelineEvent.Terminal.Kind.FAILED,
                    requiredOutputCommitted = false,
                    publicExportCommitted = false,
                    verified = false,
                    message = "Super Resolution identity link failed"
                )
                return
            }
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
                    sourceJobDirectory = sourceJobDir,
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
            inFlight = cancelled
            pendingOutcome = BackgroundTerminalSpec(
                kind = CameraPipelineEvent.Terminal.Kind.CANCELLED,
                requiredOutputCommitted = false,
                publicExportCommitted = false,
                verified = false,
                message = "Background 24M Fusion cancelled."
            )
            throw cancelled
        } catch (fatal: Error) {
            inFlight = fatal
            throw fatal
        } catch (failure: Exception) {
            Log.e("KeplerBackgroundExecutor", "SR background job failed ${sourceJobDir.name}", failure)
            ordinaryFailure = failure
        } finally {
            finalizeLaneAfterExecution(
                releaseLease = lease?.let { l -> ({ l.releaseOrRetainForReconciliation() }) },
                jobDirName = sourceJobDir.name,
                inFlight = inFlight
            ) { leaseBoundaryReached ->
                settleTerminal(
                    terminal, counts, sourceJobDir, pendingOutcome, ordinaryFailure,
                    srOutputDir ?: sourceJobDir, lease, leaseBoundaryReached,
                    resultJobDirectoryPath = srOutputDir?.absolutePath
                )
            }
        }
    }

    /**
     * Dual-identity durability for Super Resolution. Writes the explicit
     * request/result relationship into BOTH job directories BEFORE any terminal
     * publication:
     *  - RESULT side (SR output dir): "superResolutionSourceJobDirectory".
     *    If fusion metadata does not exist yet, a minimal stub job.json is
     *    created carrying the link; the later full SR metadata write merges
     *    over it and re-asserts the key.
     *  - REQUEST side (source capture dir): "superResolutionResultJobDirectory".
     *
     * FAIL-CLOSED contract: both sides are required. Each side is attempted
     * independently (so a failure on one side never hides the other side's
     * diagnostics), and if EITHER durable write fails this function throws —
     * the caller must treat the source↔result relationship as UNPROVEN and
     * must not publish an SR terminal that implies it. A partially applied link
     * (one side written) is asymmetric residue only; because this function
     * threw, no dual-identity relationship may be claimed from it.
     *
     * Fatal precedence: Error and CancellationException are never converted —
     * both propagate unchanged. The executor-level event envelope keeps its
     * observational dual identity for every published event regardless; only
     * the DURABLE claim is gated by this function's outcome.
     */
    internal fun linkSuperResolutionIdentities(sourceJobDir: File, resultJobDir: File) {
        var resultSideLinked = false
        var resultSideFailure: Exception? = null
        try {
            val existingResultJob = try {
                KeplerJobMetadata.read(resultJobDir)
            } catch (_: Exception) {
                // Absent/unreadable result metadata falls back to the stub write,
                // which is itself a REQUIRED operation below.
                null
            }
            if (existingResultJob != null) {
                KeplerJobMetadata.update(resultJobDir) { job ->
                    job.put(SUPER_RESOLUTION_SOURCE_JOB_KEY, sourceJobDir.absolutePath)
                }
            } else {
                KeplerJobMetadata.write(
                    resultJobDir,
                    org.json.JSONObject()
                        .put("jobType", "SUPER_RESOLUTION_FUSION")
                        .put("status", "PROCESSING")
                        .put("createdAt", System.currentTimeMillis())
                        .put(SUPER_RESOLUTION_SOURCE_JOB_KEY, sourceJobDir.absolutePath)
                )
            }
            resultSideLinked = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (linkFailure: Exception) {
            Log.e(
                "KeplerBackgroundExecutor",
                "SR result-side identity link failed ${resultJobDir.name}",
                linkFailure
            )
            resultSideFailure = linkFailure
        }
        try {
            KeplerJobMetadata.update(sourceJobDir) { job ->
                job.put(SUPER_RESOLUTION_RESULT_JOB_KEY, resultJobDir.absolutePath)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (linkFailure: Exception) {
            Log.e(
                "KeplerBackgroundExecutor",
                "SR source-side identity link failed ${sourceJobDir.name}",
                linkFailure
            )
            throw IllegalStateException(
                "Super Resolution identity link failed: source-side write failed for ${sourceJobDir.name}",
                linkFailure
            ).apply {
                resultSideFailure?.let { addSuppressed(it) }
            }
        }
        if (!resultSideLinked) {
            throw IllegalStateException(
                "Super Resolution identity link failed: result-side write failed for ${resultJobDir.name}",
                resultSideFailure
            )
        }
    }

    // Durable dual-identity keys (see [linkSuperResolutionIdentities]).
    private const val SUPER_RESOLUTION_SOURCE_JOB_KEY = "superResolutionSourceJobDirectory"
    private const val SUPER_RESOLUTION_RESULT_JOB_KEY = "superResolutionResultJobDirectory"
}
