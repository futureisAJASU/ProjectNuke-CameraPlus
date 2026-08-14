package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException

internal const val SINGLE_FRAME_OUTPUT_FILE_NAME = "single_frame_processed.png"
private const val SINGLE_FRAME_PIPELINE_VERSION = "single_yuv_isp_v1"

internal enum class SingleFrameCleanupResult {
    PREVIOUS_OUTPUT_RESTORED,
    COMMITTED_FINAL_RETAINED,
    NEW_OUTPUT_REMOVED,
    NO_PREVIOUS_OUTPUT,
    UNCOMMITTED_OUTPUT_REMAINS,
    CLEANUP_FAILED
}

internal fun processSingleFrameJobSync(
    jobDir: File,
    requestedParams: ClassicYuvFusionParams,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    metadataPolicy: ReprocessMetadataPolicy = ReprocessMetadataPolicy.NORMAL,
    operationLease: JobOperationLease? = null,
    onStatus: (String) -> Unit
): File {
    cancellation.throwIfCancelled()
    val params = requestedParams.clamped()
    val jobFile = when (val resolved = NoFollowFileSystem.resolveDirectChildResult(
        jobDir, JOB_JSON_FILE_NAME, requireFile = true
    )) {
        is NoFollowInspection.Present -> resolved.value
        NoFollowInspection.Absent -> error("Single-frame job metadata is absent")
        is NoFollowInspection.InspectionFailed -> throw resolved.exception
    }
    val job = JSONObject(NoFollowFileSystem.readTextVerified(jobFile))
    val processingStartedAt = System.currentTimeMillis()
    val processingAttempt = beginProcessingAttempt(
        jobDir,
        mode = "SINGLE_FRAME",
        additionalOwnedKeys = setOf(SINGLE_FRAME_OUTPUT_FILE_NAME),
        operationLease = operationLease
    )
    val processingAttemptId = processingAttempt.id

    persistSingleFrameProgress(
        jobDir = jobDir,
        processingStartedAt = processingStartedAt,
        params = params,
        metadataPolicy = metadataPolicy,
        stage = "PROCESSING",
        status = "SINGLE_FRAME_PROCESSING",
        attempt = processingAttempt
    )
    if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
        updateProcessingStage(jobDir, "PROCESSING", "SINGLE_FRAME_PROCESSING", mutate = { current ->
            current.put("processingAttemptId", processingAttemptId)
        }, attempt = processingAttempt)
    }

    var sourceForCleanup: Bitmap? = null
    var processedForCleanup: Bitmap? = null
    var outputFile: File? = null
    var committedFinalClaimed = false
    try {
        val frames = job.optJSONArray("frames")
            ?: error("Single-frame job has no frames array")
        val frame = (0 until frames.length())
            .asSequence()
            .mapNotNull(frames::optJSONObject)
            .firstOrNull {
                it.optBoolean("enabled", true) &&
                    !it.optBoolean("excludedByUser", false) &&
                    it.optString("file").isNotBlank()
            }
            ?: error("Single-frame job has no enabled source frame")
        val sourceFile = resolveSingleFrameSourceFile(jobDir, frame.getString("file"))

        onStatus("일반 사진 ISP 후처리 중입니다.")
        cancellation.throwIfCancelled()
        val sourceBitmap = NoFollowFileSystem.decodeBitmapVerified(
            sourceFile,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
        ) ?: error("Could not decode single-frame source: ${sourceFile.name}")
        sourceForCleanup = sourceBitmap
        cancellation.throwIfCancelled()

        val processedBitmap = applyClassicYuvPostProcessing(sourceBitmap, params, cancellation)
        processedForCleanup = processedBitmap
        cancellation.throwIfCancelled()

        outputFile = File(jobDir, SINGLE_FRAME_OUTPUT_FILE_NAME)
        commitProcessingArtifact(
            finalFile = outputFile,
            processingAttemptId = if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) processingAttempt.id else null,
            claimKey = if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) "finalFile" else null,
            cancellation = cancellation,
            writeTemp = { temp ->
                FileOutputStream(temp).use { stream ->
                    check(processedBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        "Could not encode ${outputFile.name}"
                    }
                    stream.fd.sync()
                }
            },
            verifyFinal = { candidate ->
                verifyPngArtifact(candidate, processedBitmap.width, processedBitmap.height)
            }
        )
        if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
            markProcessingArtifactClaim(jobDir, processingAttempt, "finalFile", outputFile)
            committedFinalClaimed = true
        } else {
            committedFinalClaimed = true
        }
        cancellation.throwIfCancelled()
        val finishedAt = System.currentTimeMillis()

        val completedOutput = requireNotNull(outputFile)
        persistSingleFrameSuccess(
            jobDir = jobDir,
            frame = frame,
            outputFile = completedOutput,
            outputWidth = processedBitmap.width,
            outputHeight = processedBitmap.height,
            params = params,
            processingStartedAt = processingStartedAt,
            finishedAt = finishedAt,
            metadataPolicy = metadataPolicy,
            attempt = processingAttempt
        )
        onStatus("일반 사진 후처리가 완료되었습니다.")
        return completedOutput
    } catch (ce: CancellationException) {
        if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
            val settlement = if (committedFinalClaimed) SingleFrameCleanupResult.COMMITTED_FINAL_RETAINED
            else SingleFrameCleanupResult.NO_PREVIOUS_OUTPUT
            persistSingleFrameCancellation(
                jobDir = jobDir,
                params = params,
                processingStartedAt = processingStartedAt,
                settlement = settlement,
                cancellation = ce,
                attempt = processingAttempt
            )
        }
        throw ce
    } catch (oom: OutOfMemoryError) {
        val settlement = if (committedFinalClaimed) {
            SingleFrameCleanupResult.COMMITTED_FINAL_RETAINED
        } else if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
            SingleFrameCleanupResult.NO_PREVIOUS_OUTPUT
        } else {
            SingleFrameCleanupResult.NO_PREVIOUS_OUTPUT
        }
        persistSingleFrameFailure(
            jobDir,
            params,
            processingStartedAt,
            metadataPolicy,
            oom,
            settlement,
            processingAttempt
        )
        throw oom
    } catch (e: Exception) {
        val settlement = if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
            SingleFrameCleanupResult.NO_PREVIOUS_OUTPUT
        } else {
            SingleFrameCleanupResult.NO_PREVIOUS_OUTPUT
        }
        persistSingleFrameFailure(
            jobDir,
            params,
            processingStartedAt,
            metadataPolicy,
            e,
            settlement,
            processingAttempt
        )
        throw e
    } finally {
        processedForCleanup?.takeIf { !it.isRecycled }?.recycle()
        sourceForCleanup
            ?.takeIf { it !== processedForCleanup && !it.isRecycled }
            ?.recycle()
        processingAttempt.releaseOwnedLease()
    }
}
private fun resolveSingleFrameSourceFile(jobDir: File, rawName: String): File {
    require(rawName == rawName.trim()) { "Single-frame source name has surrounding whitespace" }
    require(rawName.isNotEmpty() && rawName != "." && rawName != "..") {
        "Single-frame source name is empty or reserved"
    }
    require(!rawName.contains('/') && !rawName.contains('\\')) {
        "Single-frame source must be a direct-child file name"
    }

    val jobPath = jobDir.toPath()
    val jobAttributes = Files.readAttributes(
        jobPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
    )
    require(jobAttributes.isDirectory && !jobAttributes.isSymbolicLink()) {
        "Single-frame job directory must be a real directory"
    }
    val sourcePath = jobPath.resolve(rawName).normalize()
    require(sourcePath.parent == jobPath) { "Single-frame source escapes job directory" }
    val source = sourcePath.toFile()
    require(Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
        "Single-frame source is missing: $rawName"
    }
    require(!Files.isSymbolicLink(sourcePath)) {
        "Single-frame source must not be a symbolic link: $rawName"
    }
    require(Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
        "Single-frame source is not a regular file: $rawName"
    }
    require(Files.size(sourcePath) > 0L) { "Single-frame source is empty: $rawName" }
    return source
}

private fun persistSingleFrameCancellation(
    jobDir: File,
    params: ClassicYuvFusionParams,
    processingStartedAt: Long,
    settlement: SingleFrameCleanupResult,
    cancellation: CancellationException,
    attempt: ProcessingAttempt
) {
    runCatching {
        updateForProcessingAttempt(jobDir, attempt) { current ->
            current.put("processingStartedAt", processingStartedAt)
                .put("processingTimeMs", System.currentTimeMillis() - processingStartedAt)
                .put("singleFrameProcessingPolicy", ReprocessMetadataPolicy.NORMAL.name)
                .put("fusionPresetName", params.presetName)
                .put("fusionParams", params.toJson())
                .put("currentPipelineStage", "CANCELLED")
                .put("processStatus", "PIPELINE_CANCELLED")
                .put("processingFailureType", cancellation.javaClass.simpleName)
                .put("processingFailureMessage", cancellation.message ?: "Single-frame processing cancelled")
                .put("singleFrameCleanupResult", settlement.name)
                .put("singleFrameCancelledOutputRetained", settlement == SingleFrameCleanupResult.PREVIOUS_OUTPUT_RESTORED)
                .put("userCanMoveDevice", true)
            if (settlement == SingleFrameCleanupResult.PREVIOUS_OUTPUT_RESTORED ||
                settlement == SingleFrameCleanupResult.COMMITTED_FINAL_RETAINED
            ) {
                current.put("galleryDisplayUnavailable", false)
                    .put("finalOutputAvailable", true)
                    .put("singleFrameCancelledPriorOutputValid", settlement == SingleFrameCleanupResult.PREVIOUS_OUTPUT_RESTORED)
            } else {
                current.put("galleryDisplayUnavailable", true)
                    .put("finalOutputAvailable", false)
                current.remove("finalNightFusionFile")
                current.remove("finalFile")
                current.remove("galleryDisplayFile")
                current.remove("galleryThumbnailFile")
                current.remove("galleryDisplaySource")
            }
        }
    }
}

private fun persistSingleFrameProgress(
    jobDir: File,
    processingStartedAt: Long,
    params: ClassicYuvFusionParams,
    metadataPolicy: ReprocessMetadataPolicy,
    stage: String,
    status: String,
    attempt: ProcessingAttempt
) {
    updateForProcessingAttempt(jobDir, attempt) { current ->
        current.put("processingStartedAt", processingStartedAt)
            .put("singleFrameProcessingPolicy", metadataPolicy.name)
            .put("fusionEngine", SINGLE_FRAME_PIPELINE_VERSION)
            .put("fusionVersion", SINGLE_FRAME_PIPELINE_VERSION)
            .put("fusionParamsVersion", CLASSIC_YUV_FUSION_PARAMS_VERSION)
            .put("fusionPresetName", params.presetName)
            .put("fusionParams", params.toJson())
        if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
            current.put("jobType", "YUV_SINGLE_FRAME")
                .put("captureMode", CaptureMode.SINGLE_FRAME.name)
                .put("currentPipelineStage", stage)
                .put("processStatus", status)
                .put("userCanMoveDevice", true)
        }
    }
}

private fun persistSingleFrameSuccess(
    jobDir: File,
    frame: JSONObject,
    outputFile: File,
    outputWidth: Int,
    outputHeight: Int,
    params: ClassicYuvFusionParams,
    processingStartedAt: Long,
    finishedAt: Long,
    metadataPolicy: ReprocessMetadataPolicy,
    attempt: ProcessingAttempt
) {
    updateForProcessingAttempt(jobDir, attempt) { current ->
        current.put("processingStartedAt", processingStartedAt)
            .put("processingTimeMs", finishedAt - processingStartedAt)
            .put("singleFrameProcessingPolicy", metadataPolicy.name)
        if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
            current.put("jobType", "YUV_SINGLE_FRAME")
                .put("captureMode", CaptureMode.SINGLE_FRAME.name)
                .put("currentPipelineStage", "PIPELINE_COMPLETE")
                .put("processStatus", "PIPELINE_COMPLETE")
                .put("fusionEngine", SINGLE_FRAME_PIPELINE_VERSION)
                .put("fusionVersion", SINGLE_FRAME_PIPELINE_VERSION)
                .put("fusionParamsVersion", CLASSIC_YUV_FUSION_PARAMS_VERSION)
                .put("fusionPresetName", params.presetName)
                .put("fusionParams", params.toJson())
                .put("referenceFrameIndex", frame.optInt("index", 0))
                .put("usedFrameCount", 1)
                .put("acceptedFrameCount", 1)
                .put("rejectedFrameCount", 0)
                .put("frameCount", 1)
                .put("requestedFrames", 1)
                .put("savedFrames", 1)
                .put("finalNightFusionFile", outputFile.name)
                .put("finalFile", outputFile.name)
                .put("finalOutputSource", "single_yuv_isp")
                .put("galleryDisplayFile", outputFile.name)
                .put("galleryThumbnailFile", outputFile.name)
                .put("galleryDisplaySource", "single_frame_processed")
                .put("galleryDisplayUnavailable", false)
                .put("finalOutputAvailable", true)
                .put("outputWidth", outputWidth)
                .put("outputHeight", outputHeight)
                .put("lumaDenoiseStrength", params.denoiseStrength.toDouble())
                .put("chromaDenoiseStrength", params.denoiseStrength.toDouble())
                .put("sharpenAmount", params.sharpenAmount.toDouble())
                .put("localContrastAmount", params.localContrastAmount.toDouble())
                .put("processedAt", finishedAt)
                .put("userCanMoveDevice", true)
            current.remove("processingFailureType")
            current.remove("processingFailureMessage")
            current.remove("singleFrameCancelledOutputRetained")
            current.remove("singleFrameFailedOutputRetained")
        }
    }
}

private fun persistSingleFrameFailure(
    jobDir: File,
    params: ClassicYuvFusionParams,
    processingStartedAt: Long,
    metadataPolicy: ReprocessMetadataPolicy,
    failure: Throwable,
    settlement: SingleFrameCleanupResult = SingleFrameCleanupResult.NO_PREVIOUS_OUTPUT,
    attempt: ProcessingAttempt
) {
    runCatching {
        updateForProcessingAttempt(jobDir, attempt) { current ->
            current.put("processingStartedAt", processingStartedAt)
                .put("processingTimeMs", System.currentTimeMillis() - processingStartedAt)
                .put("singleFrameProcessingPolicy", metadataPolicy.name)
                .put("fusionPresetName", params.presetName)
                .put("fusionParams", params.toJson())
            if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
                current.put("currentPipelineStage", "FAILED")
                    .put("processStatus", "SINGLE_FRAME_PROCESSING_FAILED")
                    .put("processingFailureType", failure.javaClass.simpleName)
                    .put(
                        "processingFailureMessage",
                        failure.message ?: "Single-frame processing failed"
                    )
                    .put("singleFrameCleanupResult", settlement.name)
                    .put("singleFrameFailedOutputRetained", settlement == SingleFrameCleanupResult.PREVIOUS_OUTPUT_RESTORED)
                    .put("userCanMoveDevice", true)
                if (settlement == SingleFrameCleanupResult.PREVIOUS_OUTPUT_RESTORED) {
                    current.put("galleryDisplayUnavailable", false)
                        .put("finalOutputAvailable", true)
                } else {
                    current.put("galleryDisplayUnavailable", true)
                        .put("finalOutputAvailable", false)
                    current.remove("finalNightFusionFile")
                    current.remove("finalFile")
                    current.remove("galleryDisplayFile")
                    current.remove("galleryThumbnailFile")
                    current.remove("galleryDisplaySource")
                }
            }
        }
    }
}
