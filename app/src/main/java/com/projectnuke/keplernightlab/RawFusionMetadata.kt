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
    "rawFusionProcessedAt",
    "rawFusionProcessingTimeMs",
    "nativePostprocessStatus",
    "nativePostprocessRgbaFile",
    "nativePostprocessMetadataFile",
    "rawRenderDebugFile",
    "rawRenderInputMetadataFile",
    "nativeAlignMs",
    "nativeMergeMs",
    "nativeIspRenderMs"
)

/**
 * Current-run Classic RAW result and diagnostic keys produced by [runClassicRawFusionMerge]
 * and its debug-preview path. Reset at current-run initialization so a previous success cannot
 * leave Classic result or debug metadata after an early current failure. Persisted after a
 * successful Classic merge via the owned-key present-copy/absent-remove rule: an early NORMAL
 * failure removes absent previous-run Classic values; a failure after the current Classic merge
 * preserves values actually produced by the current run; reprocess does not own full Classic
 * result/artifact fields and never removes or replaces them.
 */
internal val RAW_CLASSIC_CURRENT_RUN_KEYS: Set<String> = setOf(
    "rawFusionEngine",
    "rawFusionVersion",
    "rawReferenceFrameIndex",
    "rawReferenceFrameReason",
    "usedFrameCount",
    "excludedFrameCount",
    "skippedFrameCount",
    "rawGhostSuppressionUsed",
    "rawNoiseModelVersion",
    "shotCoeff",
    "readNoiseCoeff",
    "rawOutlierRejectedRatio",
    "rawOutlierDownweightedRatio",
    "rawAlignmentSummary",
    "nativeAlignmentAvailable",
    "nativeAlignmentUsed",
    "alignmentVersion",
    "fallbackAlignmentCount",
    "lowConfidenceAlignmentCount",
    "rawFusionProcessedAt",
    "rawFusionProcessingTimeMs",
    "nativeAlignMs",
    "nativeMergeMs",
    "mergedRawFile",
    "rawFusionDebugFile",
    "alignmentFile",
    "alignmentStatus",
    "nativeRawMerge",
    "rawFusionNotes",
    "mergeWeightMapAvailable",
    "mergeWeightMapFile",
    "mergeRejectMapAvailable",
    "mergeRejectMapFile",
    "rawReferencePreviewFile",
    "rawFusedPreviewFile",
    "rawComparePreviewFile",
    "rawDebugArtifactStatus",
    "rawDebugArtifactError"
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
    "rawReferenceDebugFile",
    "rawMergedLinearDebugFile",
    "rawFinalRenderDebugFile",
    "rawRenderInputMetadataFile"
)

/**
 * Current-run processor-error fields owned by `processRawFusionJob`. `processError` and the
 * explicit RAW processor failure type/message fields are written by the run's failure metadata
 * but do not flip the terminal `processStatus` / `currentPipelineStage` values; they are
 * therefore safe to attribute to the current run even in `REPROCESS_PROGRESS_ONLY` failure,
 * where terminal status keys are intentionally NOT written.
 */
internal val RAW_FUSION_PROCESSOR_ERROR_KEYS: Set<String> = setOf(
    "processError",
    "rawProcessorFailureType",
    "rawProcessorFailureMessage"
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

/**
 * Current-run shared RAW processor progress keys split from NORMAL-only generic pipeline fields.
 * These are written by both NORMAL and reprocess paths and do not include terminal failure keys
 * or `userCanMoveDevice`.
 */
internal val RAW_FUSION_SHARED_PROCESSOR_KEYS: Set<String> = RAW_FUSION_PROGRESS_KEYS +
    RAW_FUSION_NATIVE_DIAGNOSTIC_KEYS +
    RAW_FUSION_PROCESSOR_ERROR_KEYS

/**
 * Diagnostic + progress keys owned by the current-run RAW export stage and shared between NORMAL
 * and `REPROCESS_PROGRESS_ONLY` policy. These are safe to attribute to the current run in either
 * policy because they do not flip a terminal stage/status, do not change `userCanMoveDevice`, and
 * do not touch gallery linkage, public-result, committed-export, or final-output ownership.
 * Includes only the processor/export progress diagnostics actually read by the shared finalizer or
 * that need run-scoped replacement (a previous native RGBA run's status/isp timing/debug-file
 * references must not survive a current run that did not produce them).
 *
 * Includes the explicit run identity scaler `rawFusionProcessingPolicy` so reprocess readers and
 * the shared finalizer can read which policy produced the diagnostic snapshot.
 */
internal val RAW_FUSION_EXPORT_SHARED_DIAGNOSTIC_KEYS: Set<String> = setOf(
    "rawFusionProcessingPolicy",
    "nativePostprocessStatus",
    "nativePostprocessRgbaFile",
    "nativePostprocessMetadataFile",
    "nativeIspRenderMs",
    "nativeMp24DebugPngRequested",
    "nativeMp24DebugPngWritten",
    "nativeMp24DebugPngSkipReason",
    "nativeRawIspUsed",
    "nativeRawIspFullBufferFallbackUsed",
    "fullSizeKotlinDemosaicUsed",
    "native24RawUsed",
    "highResRawInputUsed",
    "highResRawInputThresholdPixels",
    "highResRawInputThresholdMp",
    "nativePostprocessRequired",
    "nativePostprocessUsed",
    "rawRenderVersion",
    "rawRenderInputMetadataFile",
    "rawRenderDebugFile",
    "rawReferenceDebugFile",
    "rawMergedLinearDebugFile",
    "rawFinalRenderDebugFile",
    "rawRenderWarnings",
    "rawRenderColorTransform",
    "rawRenderCameraWbGains",
    "rawRenderDenoiseStrength",
    "rawRenderChromaDenoiseStrength",
    "rawRenderSharpenAmount",
    "rawRenderExposureGain",
    "rawRenderShadowLift",
    "rawRenderHighlightRollOff",
    "rawRenderWhiteBalance",
    "rawDebugPreviewSkipped",
    "rawDebugPreviewSkipReason",
    "demosaicMethod",
    "demosaicFallbackPixelCount",
    "mhcBoundaryFallbackUsed",
    "weightAwareDenoiseUsed",
    "adaptiveSharpenUsed",
    "sharpenSuppressionLowConfidenceUsed",
    "chromaArtifactSuppressionUsed",
    "finalOutputSource",
    "outputFallbackReason",
    "outputWidth",
    "outputHeight",
    "outputOrientation",
    "selected24MpStrategy",
    "actualInputResolutionMode",
    "outputResolutionMode",
    "nativeMergeVersion",
    "tileBasedMerge",
    "tileRows",
    "tileCount",
    "fullFrameAccumulatorsUsed",
    "fullFrameMergedBufferUsed",
    "estimatedTileMergeWorkingSetBytes",
    "estimatedTileMergeWorkingSetMb",
    "acceptedFrameCount",
    "rejectedFrameCount",
    "ghostSuppressionEnabled",
    "ghostRejectedSampleRatio",
    "referencePreservedPixelRatio",
    "mergeWarning",
    "nativeAlignMs",
    "nativeMergeMs",
    "mergeWeightMapAvailable",
    "mergeWeightMapFile",
    "mergeRejectMapAvailable",
    "mergeRejectMapFile",
    "rawReferencePreviewFile",
    "rawFusedPreviewFile",
    "rawComparePreviewFile",
    "rawDebugArtifactStatus",
    "rawDebugArtifactError",
    "referenceSinglePreviewFile",
    "referenceFrameIndex",
    "referenceFrameReason",
    "usedFrameCount",
    "excludedFrameCount",
    "skippedFrameCount",
    "requestedFrames",
    "savedFrames",
    "captureCompleteness",
    "partialCapture",
    "processingNotes",
    "rawFusionNotes",
    "blackLevelUsed",
    "whiteLevelUsed",
    "blackLevelSource",
    "blackLevelMode",
    "whiteLevelSource",
    "mergedRawFormat",
    "sensorOrientation",
    "processedAt"
)

/**
 * Owned-only-on-NORMAL keys covering the current export run's terminal stage/status, `userCanMoveDevice`,
 * generated output references (`finalFile`, `previewFile`, `mergedRawFile`, `mergedDngFile`),
 * gallery linkage, public-result fields, committed-export scalars, export verification, export timing,
 * and current export errors. These keys belong to the NORMAL export stage and NEVER to a
 * `REPROCESS_PROGRESS_ONLY` diagnostic run: reprocess must not directly write or clear terminal
 * stage/status, `userCanMoveDevice`, gallery linkage, public-result fields, committed-export
 * fields, generated output references, or final-output ownership. The shared finalizer owns reprocess
 * terminal metadata instead, so the reprocess export stage never writes these keys.
 */
internal val RAW_FUSION_EXPORT_NORMAL_ONLY_KEYS: Set<String> = setOf(
    "userCanMoveDevice",
    "currentPipelineStage",
    "processStatus",
    "finalFile",
    "previewFile",
    "mergedRawFile",
    "mergedDngFile",
    "isDebugPreviewUsedAsFinal",
    "alignmentFile",
    "alignmentStatus",
    "alignmentError",
    "nativeRawMerge",
    "galleryDisplayFile",
    "galleryThumbnailFile",
    "galleryDisplaySource",
    "finalOutputFormatSetting",
    "exportStatus",
    "exportVerified",
    "exportUri",
    "exportDisplayName",
    "exportMimeType",
    "exportFormatRequested",
    "exportFormatUsed",
    "exportFallbackUsed",
    "exportFileSizeBytes",
    "galleryExportCommitted",
    "postExportCancellationRequested",
    "postExportWorkSkipped",
    "rawSidecarRequested",
    "rawSidecarExportStatus",
    "rawSidecarExportedFiles",
    "rawSidecarError",
    "exportError",
    "cleanupStatus",
    "exportedAt",
    "totalPipelineMs"
)

/**
 * Single NORMAL export run's owned key set: shared processor/export diagnostics plus the
 * NORMAL-only terminal/gallery/public/committed-export/final-output/timing/error keys. A NORMAL
 * export run copies present current-run values for every key here and removes absent ones inside a
 * single [KeplerJobMetadata.update], so stale previous-run export result metadata never survives a
 * current NORMAL export, success or failure.
 */
internal val RAW_FUSION_EXPORT_NORMAL_OWNED_KEYS: Set<String> =
    RAW_FUSION_EXPORT_SHARED_DIAGNOSTIC_KEYS + RAW_FUSION_EXPORT_NORMAL_ONLY_KEYS

/**
 * `REPROCESS_PROGRESS_ONLY` export owned-key set: processor/export diagnostics only. NEVER
 * includes terminal stage/status, `userCanMoveDevice`, gallery linkage, public-result,
 * committed-export, final-output, or export-error terminal fields, in accordance with the progress
 * policy. The non-terminal processor/export-progress reads/writes flow through these keys so the
 * shared finalizer can inspect the current reprocess attempt without the reprocess stage writing
 * competing terminal metadata.
 */
internal val RAW_FUSION_EXPORT_REPROCESS_KEYS: Set<String> =
    RAW_FUSION_EXPORT_SHARED_DIAGNOSTIC_KEYS

/**
 * Persist current-run failure metadata in a single [KeplerJobMetadata.update] call.
 * Copies present current-run owned fields from [currentRunJob], removes absent owned fields,
 * and directly writes the failure scalar fields. Accepts a nullable [currentRunJob]; if null,
 * all owned fields are treated as absent and only removed from the locked metadata.
 *
 * Classic result keys are only removed when they appear in [ownedKeys] (NORMAL mode), so
 * reprocess failure never touches full Classic result fields.
 *
 * Does not allocate a separate failure [JSONObject]. Does not re-read or parse [job.json].
 */
internal fun persistRawFusionFailureMetadata(
    jobDir: File,
    metadataPolicy: ReprocessMetadataPolicy,
    processStatus: String,
    failureMessage: String,
    failureType: String,
    currentRunJob: JSONObject?,
    ownedKeys: Set<String>
) {
    runCatching {
        KeplerJobMetadata.update(jobDir) { current ->
            ownedKeys.forEach { key ->
                if (currentRunJob != null && currentRunJob.has(key)) {
                    current.put(key, currentRunJob.get(key))
                } else {
                    current.remove(key)
                }
            }
            if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
                current.put("currentPipelineStage", "FAILED")
                current.put("processStatus", processStatus)
                current.put("userCanMoveDevice", true)
                current.put("processedAt", System.currentTimeMillis())
            }
            current.put("rawProcessorFailureType", failureType)
            current.put("rawProcessorFailureMessage", failureMessage)
            current.put("processError", failureMessage)
            current.put("rawFusionProcessingPolicy", metadataPolicy.name)
        }
    }.onFailure { metadataWriteError ->
        Log.e(
            "KeplerRawPipeline",
            "Failed to persist RAW fusion failure metadata: ${metadataWriteError.message}",
            metadataWriteError
        )
    }
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
