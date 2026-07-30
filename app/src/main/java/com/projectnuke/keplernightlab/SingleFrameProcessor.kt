package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException

internal const val SINGLE_FRAME_OUTPUT_FILE_NAME = "single_frame_processed.png"
private const val SINGLE_FRAME_PIPELINE_VERSION = "single_yuv_isp_v1"

internal fun processSingleFrameJobSync(
    jobDir: File,
    requestedParams: ClassicYuvFusionParams,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    metadataPolicy: ReprocessMetadataPolicy = ReprocessMetadataPolicy.NORMAL,
    onStatus: (String) -> Unit
): File {
    cancellation.throwIfCancelled()
    val params = requestedParams.clamped()
    val jobFile = File(jobDir, JOB_JSON_FILE_NAME)
    val job = JSONObject(jobFile.readText())
    val processingStartedAt = System.currentTimeMillis()

    persistSingleFrameProgress(
        jobDir = jobDir,
        processingStartedAt = processingStartedAt,
        params = params,
        metadataPolicy = metadataPolicy,
        stage = "PROCESSING",
        status = "SINGLE_FRAME_PROCESSING"
    )

    var sourceForCleanup: Bitmap? = null
    var processedForCleanup: Bitmap? = null
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
        val sourceFile = File(jobDir, frame.getString("file"))
        require(sourceFile.isFile && sourceFile.length() > 0L) {
            "Single-frame source is missing: ${sourceFile.name}"
        }

        onStatus("일반 사진 ISP 후처리 중입니다.")
        cancellation.throwIfCancelled()
        val sourceBitmap = BitmapFactory.decodeFile(
            sourceFile.absolutePath,
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

        val outputFile = File(jobDir, SINGLE_FRAME_OUTPUT_FILE_NAME)
        writeBitmapPngAtomically(processedBitmap, outputFile)
        cancellation.throwIfCancelled()
        check(outputFile.isFile && outputFile.length() > 0L) {
            "Single-frame output verification failed"
        }
        val finishedAt = System.currentTimeMillis()

        persistSingleFrameSuccess(
            jobDir = jobDir,
            frame = frame,
            outputFile = outputFile,
            outputWidth = processedBitmap.width,
            outputHeight = processedBitmap.height,
            params = params,
            processingStartedAt = processingStartedAt,
            finishedAt = finishedAt,
            metadataPolicy = metadataPolicy
        )
        onStatus("일반 사진 후처리가 완료되었습니다.")
        return outputFile
    } catch (ce: CancellationException) {
        throw ce
    } catch (oom: OutOfMemoryError) {
        persistSingleFrameFailure(jobDir, params, processingStartedAt, metadataPolicy, oom)
        throw oom
    } catch (e: Exception) {
        persistSingleFrameFailure(jobDir, params, processingStartedAt, metadataPolicy, e)
        throw e
    } finally {
        processedForCleanup?.takeIf { !it.isRecycled }?.recycle()
        sourceForCleanup
            ?.takeIf { it !== processedForCleanup && !it.isRecycled }
            ?.recycle()
    }
}

private fun persistSingleFrameProgress(
    jobDir: File,
    processingStartedAt: Long,
    params: ClassicYuvFusionParams,
    metadataPolicy: ReprocessMetadataPolicy,
    stage: String,
    status: String
) {
    KeplerJobMetadata.update(jobDir) { current ->
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
    metadataPolicy: ReprocessMetadataPolicy
) {
    KeplerJobMetadata.update(jobDir) { current ->
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
        }
    }
}

private fun persistSingleFrameFailure(
    jobDir: File,
    params: ClassicYuvFusionParams,
    processingStartedAt: Long,
    metadataPolicy: ReprocessMetadataPolicy,
    failure: Throwable
) {
    runCatching {
        KeplerJobMetadata.update(jobDir) { current ->
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
                    .put("userCanMoveDevice", true)
            }
        }
    }
}

private fun writeBitmapPngAtomically(bitmap: Bitmap, outputFile: File) {
    val temp = File(outputFile.parentFile, ".${outputFile.name}.${System.nanoTime()}.tmp")
    try {
        FileOutputStream(temp).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Could not encode ${outputFile.name}"
            }
            stream.fd.sync()
        }
        KeplerJobMetadata.atomicReplace(temp, outputFile)
    } finally {
        if (temp.exists()) temp.delete()
    }
}
