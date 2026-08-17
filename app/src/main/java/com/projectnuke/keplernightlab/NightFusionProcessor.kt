package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

data class LatestSceneEstimate(
    val meanLuma: Double?,
    val motionScore: Double?
)

private data class LoadedColorFrame(
    val bitmap: Bitmap,
    val timestampNs: Long?
)

private fun recycleLoadedColorFrames(frames: List<LoadedColorFrame>): Throwable? {
    var cleanupFailure: Throwable? = null
    frames.forEach { frame ->
        try {
            if (!frame.bitmap.isRecycled) {
                frame.bitmap.recycle()
            }
        } catch (failure: Throwable) {
            cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
        }
    }
    return cleanupFailure
}

private inline fun <T> withLoadedColorFrames(
    jobDir: File,
    job: JSONObject,
    block: (List<LoadedColorFrame>) -> T
): T {
    val frames = loadColorFrames(jobDir, job)
    var primaryFailure: Throwable? = null
    return try {
        block(frames)
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        val cleanupFailure = recycleLoadedColorFrames(frames)
        val combined = combineSettlementFailure(primaryFailure, cleanupFailure)
        if (combined !== primaryFailure) throw requireNotNull(combined)
    }
}

private data class GyroSampleForFusion(
    val timestampNs: Long,
    val magnitude: Double
)

private fun Throwable.shortMessage(): String {
    val message = message?.takeIf { it.isNotBlank() }
    return if (message == null) {
        javaClass.simpleName
    } else {
        "${javaClass.simpleName}: $message"
    }
}

fun processLatestNightFusionV02(
    context: Context,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    onStatus: (String) -> Unit,
    onPipelineEvent: CameraPipelineEventSink = {},
    workerPostOperation: ((Runnable) -> Boolean)? = null
) {
    val mainHandler = Handler(Looper.getMainLooper())
    val callbackLedger = ProcessingCallbackOutcomeLedger()
    val callbackDispatcher = ProcessingCallbackDispatcher(
        mainHandler,
        "KeplerYuvPipeline",
        executionObserver = callbackLedger::recordExecution,
        dispatchObserver = callbackLedger::recordDispatch
    )
    fun postStatus(message: String) {
        val result = callbackDispatcher.dispatch { onStatus(message) }
        if (result != ProcessingCallbackDispatchResult.ACCEPTED) {
            Log.w("KeplerYuvPipeline", "status dispatch $result snapshot=${callbackLedger.snapshot()}")
        }
    }
    val terminalPublished = AtomicBoolean(false)
    fun postTerminal(
        kind: CameraPipelineEvent.Terminal.Kind,
        message: String,
        requiredOutputCommitted: Boolean = false,
        publicExportCommitted: Boolean = false,
        verified: Boolean = false
    ) {
        if (terminalPublished.compareAndSet(false, true)) {
            postStatus(message)
            onPipelineEvent(
                CameraPipelineEvent.Terminal(
                    generation = 0L,
                    kind = kind,
                    requiredOutputCommitted = requiredOutputCommitted,
                    publicExportCommitted = publicExportCommitted,
                    verified = verified,
                    message = message
                )
            )
        } else {
            Log.w("KeplerYuvPipeline", "duplicate terminal notification suppressed: $message")
        }
    }

    fun persistStandaloneSetupFailure(failure: Exception) {
        val target = try {
            findLatestColorBurstJobDir(context)
        } catch (secondary: Error) {
            throw secondary
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return
        val lease = try {
            KeplerJobMetadata.acquireRecoveryCheckedOperation(
                target,
                JobRecoveryMutationIntent.PROCESSING_START,
                consumesProcessingHandoff = true
            )
        } catch (secondary: Error) {
            throw secondary
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        var primaryFailure: Throwable? = null
        try {
            val operationId = KeplerJobMetadata.beginActiveOperation(
                target,
                kind = KeplerActiveOperationKind.PROCESSING_YUV,
                ownerLease = lease,
                consumesProcessingHandoff = true
            )
            KeplerJobMetadata.update(target) { job ->
                job.put("currentPipelineStage", "FAILED")
                    .put("processStatus", "PIPELINE_FAILED")
                    .put("pipelineFailed", true)
                    .put("pipelineFailureSource", "processLatestNightFusionV02.setup")
                    .put("pipelineFailureType", failure.javaClass.name)
                    .put("pipelineFailureMessage", failure.message ?: failure.javaClass.simpleName)
                    .put(TERMINAL_OPERATION_ID, operationId)
                    .put("userCanMoveDevice", true)
            }
            KeplerJobMetadata.clearActiveOperation(target, operationId, lease)
        } catch (secondary: Error) {
            // A fatal terminalization failure still installs the deterministic retry reason on
            // the exact lease BEFORE the scope returns; the finally can never release a lease
            // that still protects a durable handoff or an unpersisted terminal.
            primaryFailure = KeplerJobMetadata.installWorkerSetupSettlementDebt(
                target,
                lease,
                reason = failure.message ?: failure.javaClass.simpleName,
                primaryFailure = secondary
            )
            throw secondary
        } catch (failure: Exception) {
            primaryFailure = KeplerJobMetadata.installWorkerSetupSettlementDebt(
                target,
                lease,
                reason = failure.message ?: failure.javaClass.simpleName,
                primaryFailure = failure
            )
        } finally {
            try {
                lease.releaseIfProcessingSettled()
            } catch (secondary: Throwable) {
                val combined = combineSettlementFailure(primaryFailure, secondary)
                if (combined !== primaryFailure) throw requireNotNull(combined)
            }
        }
        if (primaryFailure is Error || primaryFailure is CancellationException) {
            throw primaryFailure!!
        }
    }

    var startedThread: HandlerThread? = null
    val workerThread: HandlerThread
    val workerHandler: Handler
    try {
        val candidate = HandlerThread("KeplerNightFusionV02Thread")
        startedThread = candidate
        candidate.start()
        workerThread = candidate
        workerHandler = Handler(workerThread.looper)
    } catch (cancelled: CancellationException) {
        var cleanupFailure: Throwable? = null
        try { startedThread?.quitSafely() } catch (failure: Throwable) { cleanupFailure = failure }
        throw requireNotNull(combineSettlementFailure(cancelled, cleanupFailure))
    } catch (failure: Error) {
        var cleanupFailure: Throwable? = null
        try { startedThread?.quitSafely() } catch (secondary: Throwable) { cleanupFailure = secondary }
        throw requireNotNull(combineSettlementFailure(failure, cleanupFailure))
    } catch (failure: Exception) {
        try {
            startedThread?.quitSafely()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure is Error || cleanupFailure is CancellationException) {
                throw cleanupFailure
            }
        }
        persistStandaloneSetupFailure(failure)
        postTerminal(CameraPipelineEvent.Terminal.Kind.FAILED, "PIPELINE_FAILED: YUV Night Fusion worker setup failed.")
        return
    }

    val workerPosted = try {
        (workerPostOperation ?: workerHandler::post).invoke(Runnable {
        var jobDir: File? = null
        var operationLease: JobOperationLease? = null
        var requiredOutputCommitted = false
        var primaryFailure: Throwable? = null
        fun retainTerminalSettlement(stage: String, processStatus: String, reason: String) {
            val exactOperationId = operationLease?.currentDurableOperationId() ?: return
            operationLease?.markTerminalSettlementPending(
                PendingTerminalSettlement(
                    operationId = exactOperationId,
                    attemptStatus = when (stage) {
                        "CANCELLED" -> "CANCELLED"
                        else -> "FAILED"
                    },
                    pipelineStage = stage,
                    processStatus = processStatus,
                    reason = reason
                )
            )
        }
        try {
            cancellation.throwIfCancelled()
            jobDir = findLatestColorBurstJobDir(context)
                ?: run {
                    postTerminal(CameraPipelineEvent.Terminal.Kind.FAILED, "PIPELINE_FAILED: No YUV fusion job found.")
                    return@Runnable
                }
            operationLease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                jobDir,
                JobRecoveryMutationIntent.PROCESSING_START,
                consumesProcessingHandoff = true
            )
            val finalFile = processNightFusionJobV02Sync(
                jobDir,
                onStatus = { postStatus(it) },
                cancellation = cancellation,
                operationLease = operationLease
            )
            requiredOutputCommitted = requiredOutputCommittedAfterProcessing(jobDir!!, operationLease)
            cancellation.throwIfCancelled()
            val exactOperationId = operationLease.currentDurableOperationId()
            KeplerJobMetadata.update(jobDir!!) { job ->
                job.put("currentPipelineStage", "COMPLETE")
                    .put("processStatus", "PIPELINE_COMPLETE")
                    .put("userCanMoveDevice", true)
                exactOperationId?.takeIf { job.optString(ACTIVE_OPERATION_ID) == it }?.let {
                    job.put(TERMINAL_OPERATION_ID, it)
                }
            }
            postTerminal(
                CameraPipelineEvent.Terminal.Kind.COMPLETE,
                "PIPELINE_COMPLETE: YUV Night Fusion processing complete.",
                requiredOutputCommitted = requiredOutputCommitted
            )
        } catch (cancelled: CancellationException) {
            primaryFailure = cancelled
            try {
                requiredOutputCommitted = requiredOutputCommitted || jobDir?.let {
                    currentProcessingAttemptHasRequiredOutputClaimForLease(it, operationLease)
                } == true
            } catch (failure: Throwable) {
                primaryFailure = combineSettlementFailure(primaryFailure, failure)
                if (primaryFailure is Error) throw primaryFailure!!
            }
            val targetDir = jobDir
            if (targetDir != null) {
                val exactOperationId = operationLease?.currentDurableOperationId()
                try {
                    KeplerJobMetadata.update(targetDir) { job ->
                        job.put("currentPipelineStage", if (requiredOutputCommitted) "PARTIAL" else "CANCELLED")
                            .put(
                                "processStatus",
                                if (requiredOutputCommitted) {
                                    "PIPELINE_CANCELLED_KEEPING_CACHE"
                                } else {
                                    "PIPELINE_CANCELLED"
                                }
                            )
                            .put("processingCancellationType", CancellationException::class.java.name)
                            .put("processingCancellationMessage", "YUV Night Fusion processing cancelled")
                            .put("userCanMoveDevice", true)
                        exactOperationId?.takeIf { job.optString(ACTIVE_OPERATION_ID) == it }?.let {
                            job.put(TERMINAL_OPERATION_ID, it)
                        }
                    }
                } catch (failure: Throwable) {
                    primaryFailure = combineSettlementFailure(primaryFailure, failure)
                    try {
                        retainTerminalSettlement(
                            stage = if (requiredOutputCommitted) "PARTIAL" else "CANCELLED",
                            processStatus = if (requiredOutputCommitted) {
                                "PIPELINE_CANCELLED_KEEPING_CACHE"
                            } else {
                                "PIPELINE_CANCELLED"
                            },
                            reason = "YUV Night Fusion processing cancelled"
                        )
                    } catch (secondary: Throwable) {
                        primaryFailure = combineSettlementFailure(primaryFailure, secondary)
                    }
                    if (primaryFailure is Error || primaryFailure is CancellationException) {
                        throw primaryFailure!!
                    }
                    Log.e("KeplerYuvPipeline", "failed to persist processing cancellation metadata", failure)
                }
            }
            postTerminal(
                if (requiredOutputCommitted) {
                    CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL
                } else {
                    CameraPipelineEvent.Terminal.Kind.CANCELLED
                },
                "PIPELINE_CANCELLED: YUV Night Fusion processing cancelled; cache kept.",
                requiredOutputCommitted = requiredOutputCommitted
            )
        } catch (e: Exception) {
            primaryFailure = e
            if (e is ProcessingAlreadyActiveException ||
                e is ProcessingCleanupRequiredException ||
                e is JobRecoveryMutationBlockedException
            ) {
                // This worker never acquired the exact durable owner.  Do not write FAILED into
                // a job that is currently owned by another operation or blocked by recovery.
                postTerminal(
                    CameraPipelineEvent.Terminal.Kind.FAILED,
                    "PIPELINE_FAILED: YUV Night Fusion is blocked; existing operation kept."
                )
                return@Runnable
            }
            try {
                requiredOutputCommitted = requiredOutputCommitted || jobDir?.let {
                    currentProcessingAttemptHasRequiredOutputClaimForLease(it, operationLease)
                } == true
            } catch (failure: Throwable) {
                primaryFailure = combineSettlementFailure(primaryFailure, failure)
                if (primaryFailure is Error || primaryFailure is CancellationException) {
                    throw primaryFailure!!
                }
            }
            Log.e("KeplerYuvPipeline", "PIPELINE_FAILED in processLatestNightFusionV02", e)
            try {
                val targetDir = jobDir ?: findLatestColorBurstJobDir(context)
                if (targetDir != null) {
                    val exactOperationId = operationLease?.currentDurableOperationId()
                    KeplerJobMetadata.update(targetDir) { job ->
                        job.put("currentPipelineStage", if (requiredOutputCommitted) "PARTIAL" else "FAILED")
                            .put(
                                "processStatus",
                                if (requiredOutputCommitted) {
                                    "PIPELINE_FAILED_KEEPING_CACHE"
                                } else {
                                    "PIPELINE_FAILED"
                                }
                            )
                            .put("pipelineFailed", true)
                            .put("pipelineFailureSource", "processLatestNightFusionV02")
                            .put("pipelineFailureType", e.javaClass.name)
                            .put("pipelineFailureMessage", e.shortMessage())
                            .put("pipelineFailureStackTrace", e.stackTraceToString())
                            .put("updatedAt", System.currentTimeMillis())
                        exactOperationId?.takeIf { job.optString(ACTIVE_OPERATION_ID) == it }?.let {
                            job.put(TERMINAL_OPERATION_ID, it)
                        }
                    }
                }
            } catch (failure: Throwable) {
                primaryFailure = combineSettlementFailure(primaryFailure, failure)
                try {
                    retainTerminalSettlement(
                        stage = if (requiredOutputCommitted) "PARTIAL" else "FAILED",
                        processStatus = if (requiredOutputCommitted) {
                            "PIPELINE_FAILED_KEEPING_CACHE"
                        } else {
                            "PIPELINE_FAILED"
                        },
                        reason = e.shortMessage()
                    )
                } catch (secondary: Throwable) {
                    primaryFailure = combineSettlementFailure(primaryFailure, secondary)
                }
                if (primaryFailure is Error || primaryFailure is CancellationException) {
                    throw primaryFailure!!
                }
                Log.e("KeplerYuvPipeline", "failed to persist processing failure metadata", failure)
            }
            try {
                KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(
                    jobDir!!, operationLease, settleOnlyIfPresent = true
                )
            } catch (handoffFailure: Throwable) {
                primaryFailure = combineSettlementFailure(primaryFailure, handoffFailure)
            }
            postTerminal(
                if (requiredOutputCommitted) {
                    CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL
                } else {
                    CameraPipelineEvent.Terminal.Kind.FAILED
                },
                "PIPELINE_FAILED: YUV Night Fusion failed: ${e.shortMessage()}; cache kept. See logcat/job.json for details.",
                requiredOutputCommitted = requiredOutputCommitted
            )
        } catch (fatal: Error) {
            primaryFailure = fatal
            throw fatal
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                if (operationLease?.releaseIfProcessingSettled() == false) {
                    Log.e("KeplerYuvPipeline", "retaining processing lease after durable attempt settlement failure")
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
            if (combined !== primaryFailure) throw requireNotNull(combined)
        }
        })
    } catch (failure: Error) {
        var cleanupFailure: Throwable? = null
        try {
            val jobDir = findLatestColorBurstJobDir(context)
            if (jobDir != null) {
                KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(
                    jobDir, settleOnlyIfPresent = true
                )
            }
        } catch (handoffFailure: Throwable) {
            cleanupFailure = combineSettlementFailure(cleanupFailure, handoffFailure)
        }
        try {
            workerThread.quitSafely()
        } catch (secondary: Throwable) {
            cleanupFailure = combineSettlementFailure(cleanupFailure, secondary)
        }
        throw requireNotNull(combineSettlementFailure(failure, cleanupFailure))
    } catch (cancelled: CancellationException) {
        var cleanupFailure: Throwable? = null
        try {
            val jobDir = findLatestColorBurstJobDir(context)
            if (jobDir != null) {
                KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(
                    jobDir, settleOnlyIfPresent = true
                )
            }
        } catch (handoffFailure: Throwable) {
            cleanupFailure = combineSettlementFailure(cleanupFailure, handoffFailure)
        }
        try { workerThread.quitSafely() } catch (failure: Throwable) { cleanupFailure = failure }
        throw requireNotNull(combineSettlementFailure(cancelled, cleanupFailure))
    } catch (failure: Exception) {
        Log.e("KeplerYuvPipeline", "worker dispatch failed", failure)
        false
    }
    if (!workerPosted) {
        try {
            persistStandaloneSetupFailure(
                IllegalStateException("YUV Night Fusion worker could not start")
            )
        } catch (failure: Error) {
            throw failure
        } catch (failure: Exception) {
            Log.e("KeplerYuvPipeline", "worker dispatch terminal persistence failed", failure)
        }
        try {
            workerThread.quitSafely()
        } catch (failure: Error) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e("KeplerYuvPipeline", "worker shutdown after dispatch failure failed", failure)
        }
        postTerminal(CameraPipelineEvent.Terminal.Kind.FAILED, "PIPELINE_FAILED: YUV Night Fusion worker could not start; cache kept.")
    }
}

fun estimateLatestColorBurstScene(context: Context): LatestSceneEstimate {
    val jobDir = findLatestColorBurstJobDir(context) ?: return LatestSceneEstimate(null, null)
    val jobFile = when (val resolved = NoFollowFileSystem.resolveDirectChildResult(
        jobDir, JOB_JSON_FILE_NAME, requireFile = true
    )) {
        is NoFollowInspection.Present -> resolved.value
        else -> return LatestSceneEstimate(null, null)
    }
    val firstFrame = runCatching {
        val job = JSONObject(NoFollowFileSystem.readTextVerified(jobFile))
        val frames = job.optJSONArray("frames")
        val fileName = frames?.optJSONObject(0)?.optString("file").orEmpty()
        val frameFile = when (val resolved = NoFollowFileSystem.resolveDirectChildResult(
            jobDir, fileName, requireFile = true
        )) {
            is NoFollowInspection.Present -> resolved.value
            else -> null
        }
        frameFile?.let { NoFollowFileSystem.decodeBitmapVerified(it) }
    }.getOrNull()
    val luma = firstFrame?.let { bitmap ->
        val value = computeMeanLuma(bitmap)
        bitmap.recycle()
        value
    }
    val gyroFile = when (val resolved = NoFollowFileSystem.resolveDirectChildResult(
        jobDir, "gyro.csv", requireFile = true
    )) {
        is NoFollowInspection.Present -> resolved.value
        else -> null
    }
    val gyro = gyroFile?.let { readGyroSamples(it) }?.map { it.magnitude }?.average()
        ?.takeIf { !it.isNaN() }
    return LatestSceneEstimate(luma, gyro)
}

private const val ENABLE_YUV_FUSION_V2 = false
// Scoring-only V2 adds expensive work without producing output; keep it opt-in for debug builds.
private const val ENABLE_YUV_FUSION_V2_DRY_RUN = false

fun processNightFusionJobV02Sync(
    jobDir: File,
    onStatus: (String) -> Unit,
    requestedParams: ClassicYuvFusionParams? = null,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    metadataPolicy: ReprocessMetadataPolicy = ReprocessMetadataPolicy.NORMAL,
    operationLease: JobOperationLease? = null
): File {
    cancellation.throwIfCancelled()
    return when {
        ENABLE_YUV_FUSION_V2 -> try {
            cancellation.throwIfCancelled()
            processYuvFusionJobV2(
                jobDir = jobDir,
                onStatus = onStatus,
                requestedParams = requestedParams,
                dryRun = false,
                cancellation = cancellation,
                metadataPolicy = metadataPolicy
            )
        } catch (t: Exception) {
            cancellation.throwIfCancelled()
            onStatus("YUV Fusion V2 failed; falling back to classic V1: ${t.shortMessage()}")
            val finalFile = processClassicYuvFusionJob(
                jobDir,
                onStatus = onStatus,
                requestedParams = requestedParams,
                cancellation = cancellation,
                metadataPolicy = metadataPolicy,
                operationLease = operationLease
            )
            cancellation.throwIfCancelled()
            finalFile
        }
        ENABLE_YUV_FUSION_V2_DRY_RUN -> try {
            cancellation.throwIfCancelled()
            processYuvFusionJobV2(
                jobDir = jobDir,
                onStatus = onStatus,
                requestedParams = requestedParams,
                dryRun = true,
                cancellation = cancellation,
                metadataPolicy = metadataPolicy
            )
        } catch (t: YuvFusionV2DryRunClassicFusionFailedException) {
            throw t
        } catch (t: Exception) {
            cancellation.throwIfCancelled()
            onStatus("YUV Fusion V2 dry-run failed; falling back to classic V1: ${t.shortMessage()}")
            val finalFile = processClassicYuvFusionJob(
                jobDir,
                onStatus = onStatus,
                requestedParams = requestedParams,
                cancellation = cancellation,
                metadataPolicy = metadataPolicy,
                operationLease = operationLease
            )
            cancellation.throwIfCancelled()
            finalFile
        }
        else -> {
            cancellation.throwIfCancelled()
            val finalFile = processClassicYuvFusionJob(
                jobDir,
                onStatus = onStatus,
                requestedParams = requestedParams,
                cancellation = cancellation,
                metadataPolicy = metadataPolicy,
                operationLease = operationLease
            )
            cancellation.throwIfCancelled()
            finalFile
        }
    }
}

fun findLatestColorBurstJobDir(context: Context): File? {
    val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
    return listOf(File(picturesDir, "KeplerYuvFusion"), File(picturesDir, "KeplerColorBurst"))
        .flatMap { root ->
            NoFollowFileSystem.requireDirectChildren(root).filter { child ->
                NoFollowFileSystem.isRealDirectory(child.toPath()) &&
                    NoFollowFileSystem.resolveDirectChildResult(
                        child, JOB_JSON_FILE_NAME, requireFile = true
                    ) is NoFollowInspection.Present
            }
        }
        .maxByOrNull { it.lastModified() }
}

private fun loadColorFrames(jobDir: File, job: JSONObject): List<LoadedColorFrame> {
    val framesArray = job.optJSONArray("frames") ?: return emptyList()
    val frames = mutableListOf<LoadedColorFrame>()
    var width: Int? = null
    var height: Int? = null

    for (i in 0 until framesArray.length()) {
        val frameObject = framesArray.optJSONObject(i) ?: continue
        if (
            !frameObject.optBoolean("enabled", true) ||
            frameObject.optBoolean("excludedByUser", false)
        ) {
            continue
        }
        val fileName = frameObject.optString("file")
        if (fileName.isBlank()) continue

        val frameFile = when (val resolved = NoFollowFileSystem.resolveDirectChildResult(
            jobDir, fileName, requireFile = true
        )) {
            is NoFollowInspection.Present -> resolved.value
            else -> continue
        }
        val bitmap = NoFollowFileSystem.decodeBitmapVerified(frameFile) ?: continue
        if (width == null || height == null) {
            width = bitmap.width
            height = bitmap.height
        }

        if (bitmap.width == width && bitmap.height == height) {
            frames.add(
                LoadedColorFrame(
                    bitmap = bitmap,
                    timestampNs = if (frameObject.has("timestampNs")) frameObject.optLong("timestampNs") else null
                )
            )
        } else {
            bitmap.recycle()
        }
    }

    return frames
}

private fun computeMeanLuma(bitmap: Bitmap): Double {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    var sum = 0.0
    pixels.forEach { color ->
        sum += 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
    }
    return sum / pixels.size.coerceAtLeast(1)
}

private fun weightedAverageFrames(
    frames: List<LoadedColorFrame>,
    frameLumas: List<Double>,
    frameWeights: List<Double>,
    referenceLuma: Double,
    width: Int,
    height: Int
): Bitmap {
    val pixelCount = width * height
    val accR = FloatArray(pixelCount)
    val accG = FloatArray(pixelCount)
    val accB = FloatArray(pixelCount)
    val accW = FloatArray(pixelCount)
    val pixels = IntArray(pixelCount)

    frames.forEachIndexed { index, frame ->
        frame.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gain = (referenceLuma / frameLumas[index].coerceAtLeast(1.0)).coerceIn(0.75, 1.35)
        val weight = frameWeights[index].toFloat()

        for (p in 0 until pixelCount) {
            val color = pixels[p]
            accR[p] += (Color.red(color) * gain).toFloat() * weight
            accG[p] += (Color.green(color) * gain).toFloat() * weight
            accB[p] += (Color.blue(color) * gain).toFloat() * weight
            accW[p] += weight
        }
    }

    val out = IntArray(pixelCount)
    for (p in 0 until pixelCount) {
        val weight = accW[p].coerceAtLeast(0.001f)
        out[p] = Color.rgb(
            clampToByte((accR[p] / weight).toInt()),
            clampToByte((accG[p] / weight).toInt()),
            clampToByte((accB[p] / weight).toInt())
        )
    }

    return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
}

private fun chromaDenoise3x3(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    val out = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    fun index(x: Int, y: Int) = y * width + x

    for (y in 0 until height) {
        for (x in 0 until width) {
            val center = pixels[index(x, y)]
            val base = (Color.red(center) + Color.green(center) + Color.blue(center)) / 3.0
            var cR = 0.0
            var cG = 0.0
            var cB = 0.0
            var count = 0

            for (dy in -1..1) {
                for (dx in -1..1) {
                    val sx = (x + dx).coerceIn(0, width - 1)
                    val sy = (y + dy).coerceIn(0, height - 1)
                    val color = pixels[index(sx, sy)]
                    val neighborBase = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3.0
                    cR += Color.red(color) - neighborBase
                    cG += Color.green(color) - neighborBase
                    cB += Color.blue(color) - neighborBase
                    count++
                }
            }

            out[index(x, y)] = Color.rgb(
                clampToByte((base + cR / count).toInt()),
                clampToByte((base + cG / count).toInt()),
                clampToByte((base + cB / count).toInt())
            )
        }
    }

    return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
}

private fun sharpenAndToneMap(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    val blurred = boxBlur3x3(source)
    val blurredPixels = IntArray(width * height)
    val out = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    blurred.getPixels(blurredPixels, 0, width, 0, 0, width, height)
    blurred.recycle()

    fun curve(value: Int): Int {
        val x = (value / 255.0).coerceIn(0.0, 1.0)
        val gamma = x.pow(0.92)
        val contrast = ((gamma - 0.5) * 1.04 + 0.5).coerceIn(0.0, 1.0)
        val lifted = (contrast + 0.018 * (1.0 - contrast)).coerceIn(0.0, 1.0)
        return (lifted * 255.0).toInt().coerceIn(0, 255)
    }

    for (i in pixels.indices) {
        val color = pixels[i]
        val blur = blurredPixels[i]
        val amount = 0.42
        out[i] = Color.rgb(
            curve(clampToByte((Color.red(color) + amount * (Color.red(color) - Color.red(blur))).toInt())),
            curve(clampToByte((Color.green(color) + amount * (Color.green(color) - Color.green(blur))).toInt())),
            curve(clampToByte((Color.blue(color) + amount * (Color.blue(color) - Color.blue(blur))).toInt()))
        )
    }

    return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
}

private fun boxBlur3x3(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    val out = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    fun index(x: Int, y: Int) = y * width + x

    for (y in 0 until height) {
        for (x in 0 until width) {
            var r = 0
            var g = 0
            var b = 0
            var count = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val color = pixels[index((x + dx).coerceIn(0, width - 1), (y + dy).coerceIn(0, height - 1))]
                    r += Color.red(color)
                    g += Color.green(color)
                    b += Color.blue(color)
                    count++
                }
            }
            out[index(x, y)] = Color.rgb(r / count, g / count, b / count)
        }
    }

    return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
}

private fun readGyroSamples(file: File): List<GyroSampleForFusion> {
    if (!file.exists()) return emptyList()

    return NoFollowFileSystem.readLinesVerified(file)
        .drop(1)
        .mapNotNull { line ->
            val parts = line.split(',')
            val timestamp = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val x = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val y = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            val z = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
            GyroSampleForFusion(timestamp, sqrt(x * x + y * y + z * z))
        }
}

private fun motionScoreNear(
    gyroSamples: List<GyroSampleForFusion>,
    frameTimestampNs: Long
): Double {
    if (gyroSamples.isEmpty()) return 0.0

    val windowNs = 80_000_000L
    val nearby = gyroSamples.filter { abs(it.timestampNs - frameTimestampNs) <= windowNs }
    if (nearby.isEmpty()) return 0.0
    return nearby.map { it.magnitude }.average()
}
