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
 *
 * This set is DISJOINT from RAW_CLASSIC_CURRENT_RUN_KEYS.
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
    "outputWidth",
    "outputHeight",
    "outputOrientation",
    "selected24MpStrategy",
    "actualInputResolutionMode",
    "outputResolutionMode",
    "outputFallbackReason"
)

/**
 * NORMAL-only local-output keys covering the current export run's local candidate references
 * (`finalFile`, `previewFile`, `isDebugPreviewUsedAsFinal`). These keys belong to the NORMAL
 * export stage and NEVER to a `REPROCESS_PROGRESS_ONLY` diagnostic run: reprocess must not
 * directly write or clear local output references. The shared finalizer owns reprocess terminal
 * metadata instead, so the reprocess export stage never writes these keys.
 */
internal val RAW_FUSION_EXPORT_NORMAL_LOCAL_KEYS: Set<String> = setOf(
    "finalFile",
    "previewFile",
    "isDebugPreviewUsedAsFinal"
)

internal val RAW_FUSION_EXPORT_RENDERER_ERROR_KEYS: Set<String> = setOf(
    "rawLocalRenderFailureType",
    "rawLocalRenderFailureMessage"
)

/**
 * Keys initialized/cleared at local-render start: shared diagnostics + NORMAL local candidates
 * + renderer-error diagnostics. Does NOT include terminal fields, public-export fields,
 * or gallery linkage.
 */
internal val RAW_FUSION_EXPORT_NORMAL_INIT_KEYS: Set<String> =
    RAW_FUSION_EXPORT_SHARED_DIAGNOSTIC_KEYS +
    RAW_FUSION_EXPORT_NORMAL_LOCAL_KEYS +
    RAW_FUSION_EXPORT_RENDERER_ERROR_KEYS

/**
 * Keys persisted on NORMAL local-render success: shared diagnostics + NORMAL local candidates
 * + renderer-error diagnostics (absent to clear a previous current-run error).
 * Does NOT include terminal fields, public-export fields, or gallery linkage.
 */
internal val RAW_FUSION_EXPORT_NORMAL_SUCCESS_KEYS: Set<String> =
    RAW_FUSION_EXPORT_SHARED_DIAGNOSTIC_KEYS +
    RAW_FUSION_EXPORT_NORMAL_LOCAL_KEYS +
    RAW_FUSION_EXPORT_RENDERER_ERROR_KEYS

/**
 * `REPROCESS_PROGRESS_ONLY` export owned-key set: shared diagnostics + renderer-error
 * diagnostics only. NEVER includes NORMAL local candidate keys, terminal stage/status,
 * `userCanMoveDevice`, gallery linkage, public-result, committed-export, final-output,
 * or local-output fields, in accordance with the progress policy.
 *
 * DISJOINT from RAW_CLASSIC_CURRENT_RUN_KEYS.
 */
internal val RAW_FUSION_EXPORT_REPROCESS_KEYS: Set<String> =
    RAW_FUSION_EXPORT_SHARED_DIAGNOSTIC_KEYS + RAW_FUSION_EXPORT_RENDERER_ERROR_KEYS

/**
 * NORMAL RAW public-export owned keys: only the public/export fields produced by the NORMAL
 * public-export stage inside [captureProcessExportRawNightFusion]:
 *
 * - export URI / display name / MIME / requested & used format
 * - fallback and file-size metadata
 * - export committed and verified state
 * - export status and current export error
 * - exported timestamp
 * - post-export cancellation and post-export work-skipped flags
 * - RAW sidecar requested / status / exported files / error
 * - gallery/public-result linkage owned by this export (current public export URI linkage)
 *
 * Excludes Classic processing, local-render diagnostics, frame selection, capture, alignment,
 * merge, or unrelated finalizer fields.
 *
 * Disjoint from every other RAW owned set. Mutating these keys must stay inside a single
 * [KeplerJobMetadata.update] call.
 */
internal val RAW_PUBLIC_EXPORT_KEYS: Set<String> = setOf(
    "exportUri",
    "exportDisplayName",
    "exportMimeType",
    "exportFormatRequested",
    "exportFormatUsed",
    "exportFallbackUsed",
    "exportFileSizeBytes",
    "galleryExportCommitted",
    "exportVerified",
    "exportStatus",
    "exportError",
    "currentWarning",
    "exportedAt",
    "postExportCancellationRequested",
    "postExportWorkSkipped",
    "rawSidecarRequested",
    "rawSidecarExportStatus",
    "rawSidecarExportedFiles",
    "rawSidecarError",
    "galleryPublicExportLinkage"
)

/**
 * Current-attempt bitmap preparation / native quality diagnostic keys owned by the RAW export
 * stage. Reset at the start of each new public-export attempt so previous-attempt diagnostics
 * do not survive when the current attempt does not produce them. Reprocess mode may update these
 * keys but never writes terminal or committed public metadata from this set.
 *
 * Disjoint from [RAW_PUBLIC_EXPORT_KEYS] and from [RAW_FUSION_EXPORT_SHARED_DIAGNOSTIC_KEYS].
 */
internal val RAW_PUBLIC_EXPORT_CURRENT_ATTEMPT_KEYS: Set<String> = setOf(
    "exportBitmapSource",
    "nativeRgbaDirectExportUsed",
    "nativeRgbaBitmapLoadedForExport",
    "finalPngDecodeSkippedForExport",
    "exportBitmapWidth",
    "exportBitmapHeight",
    "nativePreviewPrepareMs",
    "referenceSinglePreviewFile",
    "fusedBeforeDenoisePreviewFile",
    "fusedAfterDenoiseNoSharpenPreviewFile",
    "finalPreviewFile",
    "compareReferenceVsFinalFile",
    "qualityDiagnosticNativeLimited",
    "qualityDiagnosticNativeLimitedReason",
    "rawPublicExportAttemptStatus",
    "rawPublicExportAttemptError",
    "rawPublicExportAttemptAt"
)

/**
 * Keys persisted on NORMAL local-render failure: shared diagnostics + NORMAL local candidates
 * + renderer-error diagnostics. Terminal stage/status and `userCanMoveDevice` are written as a
 * direct overlay inside the locked update (not in the present-copy/absent-remove owned set) so
 * a NORMAL failure cannot return without current failure metadata.
 */
internal val RAW_FUSION_EXPORT_NORMAL_FAILURE_PERSIST_KEYS: Set<String> =
    RAW_FUSION_EXPORT_SHARED_DIAGNOSTIC_KEYS +
    RAW_FUSION_EXPORT_NORMAL_LOCAL_KEYS +
    RAW_FUSION_EXPORT_RENDERER_ERROR_KEYS

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

/**
 * Reset RAW export current-attempt diagnostic fields at the start of a new public-export attempt
 * so previous-attempt values cannot survive. Removes each key in
 * [RAW_PUBLIC_EXPORT_CURRENT_ATTEMPT_KEYS] from job metadata and persists the cleared state
 * inside a single [KeplerJobMetadata.update].
 *
 * NORMAL callers invoke this immediately before they capture/load/prepare the bitmap for the
 * upcoming public MediaStore commit. Reprocess treats the same action as run-scoped diagnostic
 * clearing only — terminal stage, status, `userCanMoveDevice`, and committed public metadata are
 * intentionally left untouched here.
 */
internal fun resetRawExportAttemptDiagnostics(jobDir: File) {
    runCatching {
        KeplerJobMetadata.update(jobDir) { job ->
            RAW_PUBLIC_EXPORT_CURRENT_ATTEMPT_KEYS.forEach { key -> job.remove(key) }
        }
    }.onFailure { metadataError ->
        Log.e(
            "KeplerRawPipeline",
            "Failed to reset RAW export attempt diagnostics: ${metadataError.message}",
            metadataError
        )
    }
}
