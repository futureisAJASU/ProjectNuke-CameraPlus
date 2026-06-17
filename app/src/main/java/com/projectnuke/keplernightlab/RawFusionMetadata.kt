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
    val jobFile = File(jobDir, JOB_JSON_FILE_NAME)
    val job = if (jobFile.exists()) JSONObject(jobFile.readText()) else JSONObject()
    job.put("exportBitmapSource", source)
        .put("nativeRgbaDirectExportUsed", nativeRgbaDirectExportUsed)
        .put("nativeRgbaBitmapLoadedForExport", nativeRgbaBitmapLoadedForExport)
        .put("finalPngDecodeSkippedForExport", finalPngDecodeSkippedForExport)
        .put("exportBitmapWidth", exportBitmapWidth)
        .put("exportBitmapHeight", exportBitmapHeight)
        .put("nativePreviewPrepareMs", nativePreviewPrepareMs)
    jobFile.writeText(job.toString(2))
    Log.i(
        "KeplerRawPipeline",
        "finalOutputSource=${job.optString("finalOutputSource")} exportBitmapSource=$source " +
            "nativePostprocessRgbaFile=${job.optString("nativePostprocessRgbaFile")} " +
            "rawRenderDebugFile=${job.optString("rawRenderDebugFile")}"
    )
}
