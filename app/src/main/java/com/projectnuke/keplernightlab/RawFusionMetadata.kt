package com.projectnuke.keplernightlab

import android.util.Log
import org.json.JSONObject
import java.io.File

internal fun applyRawFusionMemoryMetadata(
    job: JSONObject,
    estimate: RawFusionMemoryEstimate
) {
    job.put("estimatedRawFusionMemoryMb", (estimate.totalMb * 10.0).toInt() / 10.0)
        .put("estimatedArgbBytes", estimate.argbBytes)
        .put("estimatedShortBytes", estimate.shortBytes)
        .put("estimatedNativeFallbackBytes", estimate.nativeFallbackBytes)
        .put("memoryRiskLevel", estimate.riskLevel)
        .put("memoryEstimateUpdatedAt", System.currentTimeMillis())
}

/**
 * Keys owned by the current-run RAW fusion processing stage and written by `processRawFusionJob`.
 * These are progress + diagnostic + non-terminal failure fields. Adding terminal state keys
 * (`processStatus`, `currentPipelineStage` endings, `userCanMoveDevice`) to this set is a policy
 * decision made by [processRawFusionJob]'s NORMAL/reprocess helpers, not by this shared list.
 */
internal val RAW_FUSION_PROGRESS_KEYS: Set<String> = setOf(
    "processingStartedAt",
    "rawFusionProcessingPolicy",
    "estimatedRawFusionMemoryMb",
    "estimatedArgbBytes",
    "estimatedShortBytes",
    "estimatedNativeFallbackBytes",
    "memoryRiskLevel",
    "memoryEstimateUpdatedAt",
    "totalPipelineMs",
    "processedAt",
    "nativePostprocessStatus",
    "nativePostprocessRgbaFile",
    "rawRenderDebugFile",
    "rawFusionDebugFile"
)

/**
 * Current-run native RGBA / debug / postprocess file references that must not survive from a
 * previous run unless produced by the current one. These are diagnostic artifacts from the
 * native ISP and bitmap-fallback paths inside the export stage and tracked here so that
 * `processRawFusionJob` can own clearing them across runs. The native ISP run-scoped metadata
 * file reference is intentionally included so a stale `nativePostprocessMetadataFile` does not
 * survive when the current run does not emit one.
 */
internal val RAW_FUSION_NATIVE_DIAGNOSTIC_KEYS: Set<String> = setOf(
    "nativePostprocessStatus",
    "nativePostprocessRgbaFile",
    "nativePostprocessMetadataFile",
    "rawRenderDebugFile",
    "rawFusionDebugFile",
    "rawReferenceDebugFile",
    "rawMergedLinearDebugFile",
    "rawFinalRenderDebugFile"
)

/**
 * NON-terminal processor-error fields owned by `processRawFusionJob`. `processError` is written
 * by the run's failure metadata but does not flip the terminal `processStatus` /
 * `currentPipelineStage` values; it is therefore safe to attribute to the current run even in
 * `REPROCESS_PROGRESS_ONLY` failure, where terminal status keys are intentionally NOT written.
 */
internal val RAW_FUSION_PROCESSOR_ERROR_KEYS: Set<String> = setOf(
    "processError"
)

/**
 * Terminal NORMAL-failure keys that represent the processor's decision that this run failed:
 * `currentPipelineStage="FAILED"` and the failure `processStatus` set. Owned only on NORMAL
 * failure; never written or cleared during `REPROCESS_PROGRESS_ONLY`.
 */
internal val RAW_FUSION_NORMAL_FAILURE_TERMINAL_KEYS: Set<String> = setOf(
    "currentPipelineStage",
    "processStatus"
)

internal fun applyNativeMergeMetadata(
    target: JSONObject,
    alignment: JSONObject?
): JSONObject {
    if (alignment == null || !alignment.has("nativeMergeVersion")) return target
    return target
        .put("nativeMergeVersion", alignment.optString("nativeMergeVersion"))
        .put("tileBasedMerge", alignment.optBoolean("tileBasedMerge", false))
        .put("tileRows", alignment.optInt("tileRows", 0))
        .put("tileCount", alignment.optInt("tileCount", 0))
        .put(
            "fullFrameAccumulatorsUsed",
            alignment.optBoolean("fullFrameAccumulatorsUsed", true)
        )
        .put(
            "fullFrameMergedBufferUsed",
            alignment.optBoolean("fullFrameMergedBufferUsed", true)
        )
        .put(
            "estimatedTileMergeWorkingSetBytes",
            alignment.optLong("estimatedTileMergeWorkingSetBytes", 0L)
        )
        .put(
            "estimatedTileMergeWorkingSetMb",
            alignment.optDouble("estimatedTileMergeWorkingSetMb", 0.0)
        )
        .put("acceptedFrameCount", alignment.optInt("acceptedFrameCount", 0))
        .put("rejectedFrameCount", alignment.optInt("rejectedFrameCount", 0))
        .put("ghostSuppressionEnabled", alignment.optBoolean("ghostSuppressionEnabled", false))
        .put("ghostRejectedSampleRatio", alignment.optDouble("ghostRejectedSampleRatio", 0.0))
        .put(
            "referencePreservedPixelRatio",
            alignment.optDouble("referencePreservedPixelRatio", 0.0)
        )
        .put(
            "mergeWarning",
            alignment.opt("mergeWarning")
                ?.takeUnless { it == JSONObject.NULL }
                ?: JSONObject.NULL
        )
        .put("nativeAlignMs", alignment.optLong("nativeAlignMs", 0L))
        .put("nativeMergeMs", alignment.optLong("nativeMergeMs", 0L))
        .put("mergeWeightMapAvailable", alignment.optBoolean("mergeWeightMapAvailable", false))
        .put("mergeWeightMapFile", alignment.opt("mergeWeightMapFile") ?: JSONObject.NULL)
        .put("mergeRejectMapAvailable", alignment.optBoolean("mergeRejectMapAvailable", false))
        .put("mergeRejectMapFile", alignment.opt("mergeRejectMapFile") ?: JSONObject.NULL)
}

internal fun applyNativePostprocessMetadata(
    target: JSONObject,
    metadata: JSONObject?
): JSONObject {
    if (metadata == null || !metadata.has("nativePostprocessVersion")) return target
    listOf(
        "nativePostprocessVersion",
        "demosaic",
        "wbMode",
        "wbGainR",
        "wbGainG",
        "wbGainB",
        "wbSampleCount",
        "toneMap",
        "blackLift",
        "gamma",
        "shoulderStrength",
        "exposureBias",
        "chromaDenoise",
        "chromaDenoiseStrength",
        "sharpen",
        "sharpenStrength",
        "darkSharpenSuppression",
        "highlightSharpenSuppression",
        "outputFormat"
    ).forEach { key ->
        metadata.opt(key)
            ?.takeUnless { it == JSONObject.NULL }
            ?.let { target.put(key, it) }
    }
    return target
}

internal fun updateRawExportBitmapMetadata(
    jobDir: File,
    source: String,
    nativeRgbaDirectExportUsed: Boolean,
    nativeRgbaBitmapLoadedForExport: Boolean,
    finalPngDecodeSkippedForExport: Boolean,
    exportBitmapWidth: Int,
    exportBitmapHeight: Int,
    nativePreviewPrepareMs: Long = 0L
) {
    val values = mapOf<String, Any?>(
        "exportBitmapSource" to source,
        "nativeRgbaDirectExportUsed" to nativeRgbaDirectExportUsed,
        "nativeRgbaBitmapLoadedForExport" to nativeRgbaBitmapLoadedForExport,
        "finalPngDecodeSkippedForExport" to finalPngDecodeSkippedForExport,
        "exportBitmapWidth" to exportBitmapWidth,
        "exportBitmapHeight" to exportBitmapHeight,
        "nativePreviewPrepareMs" to nativePreviewPrepareMs
    )
    KeplerJobMetadata.update(jobDir) { job -> values.forEach { (key, value) -> job.put(key, value) } }
    Log.i(
        "KeplerRawPipeline",
        "exportBitmapSource=$source exportBitmapWidth=$exportBitmapWidth exportBitmapHeight=$exportBitmapHeight"
    )
}
