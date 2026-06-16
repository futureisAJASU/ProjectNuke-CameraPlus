package com.projectnuke.keplernightlab

object NativeRawEngine {
    private val loaded = runCatching {
        System.loadLibrary("kepler_raw_engine")
    }.isSuccess

    fun isAvailable(): Boolean = loaded

    external fun alignAndMergeRaw16(
        framePaths: Array<String>,
        exposureScales: FloatArray,
        frameWeights: FloatArray,
        width: Int,
        height: Int,
        cfaPattern: Int,
        blackLevel: Int,
        whiteLevel: Int,
        referenceIndex: Int,
        downscale: Int,
        searchRadius: Int,
        outputMergedRawPath: String,
        outputAlignmentJsonPath: String
    ): String

    external fun processRaw16ToRgbOutput(
        mergedRawPath: String,
        width: Int,
        height: Int,
        cfaPattern: Int,
        blackLevel: Int,
        whiteLevel: Int,
        outputWidth: Int,
        outputHeight: Int,
        outputPath: String,
        outputMetadataJsonPath: String
    ): String

    external fun processRaw16ToRgbOutputV2(
        mergedRawPath: String,
        referenceRawPath: String?,
        width: Int,
        height: Int,
        cfaPattern: Int,
        blackLevel: Int,
        whiteLevel: Int,
        outputWidth: Int,
        outputHeight: Int,
        metadataJsonPath: String,
        outputRgbaPath: String,
        outputDebugJsonPath: String,
        outputReferenceDebugRgbaPath: String?,
        outputMergedLinearDebugRgbaPath: String?
    ): String
}

fun isNativeRawEngineAvailable(): Boolean = NativeRawEngine.isAvailable()
