package com.projectnuke.keplernightlab

import android.util.Size
import org.json.JSONObject
import java.io.File

data class RawFusionProcessResult(
    val success: Boolean,
    val mergedRawFile: File?,
    val mergedDngFile: File?,
    val previewPngFile: File?,
    val finalPngFile: File?,
    val errorMessage: String?,
    val nativeRgbaFile: File? = null,
    val nativeRgbaWidth: Int = 0,
    val nativeRgbaHeight: Int = 0
)

data class RawSizeSelection(
    val size: Size,
    val source: String,
    val requiresMaximumResolutionPixelMode: Boolean,
    val isHighResolutionSlowPath: Boolean,
    val fallbackReason: String?
)

internal data class NativeMergeRequest(
    val frameInputs: List<RawFrameInput>,
    val exposureScales: FloatArray,
    val frameWeights: FloatArray,
    val width: Int,
    val height: Int,
    val cfa: Int,
    val blackLevel: Int,
    val whiteLevel: Int,
    val referenceIndex: Int,
    val mergedRawFile: File,
    val alignmentFile: File
)

internal data class RawFusionSensorData(
    val width: Int,
    val height: Int,
    val pixelCount: Int,
    val cfa: Int,
    val blackLevel: Int,
    val whiteLevel: Int
)

internal data class RawFusionFiles(
    val jobDir: File,
    val jobFile: File,
    val mergedRawFile: File,
    val alignmentFile: File
)

internal data class NativeMergeOutcome(
    val mergedOk: Boolean,
    val status: String,
    val alignmentMetadata: JSONObject?,
    val alignmentStatus: String
)

internal data class KotlinRawMergeRequest(
    val frameInputs: List<RawFrameInput>,
    val mergePreparation: RawMergePreparation,
    val sensor: RawFusionSensorData,
    val blackLevelEstimate: BlackLevelEstimate,
    val job: JSONObject,
    val mergedRawFile: File,
    val onStatus: (String) -> Unit
)

internal data class RawFusionExportContext(
    val files: RawFusionFiles,
    val job: JSONObject,
    val sensor: RawFusionSensorData,
    val frames: PreparedRawFusionFrames,
    val blackLevelEstimate: BlackLevelEstimate,
    val whiteLevelEstimate: WhiteLevelEstimate,
    val referenceSelection: RawReferenceSelection,
    val nativeMerge: NativeMergeOutcome,
    val outputMode: CaptureResolutionMode,
    val highResolutionRaw: Boolean,
    val saveNativeMp24DebugPng: Boolean,
    val onStatus: (String) -> Unit
)

internal data class BlackLevelEstimate(
    val value: Int,
    val source: String,
    val pattern: IntArray? = null,
    val mode: String = "scalar_fallback"
)

internal data class WhiteLevelEstimate(
    val value: Int,
    val source: String
)
