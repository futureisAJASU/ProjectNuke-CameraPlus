package com.projectnuke.keplernightlab

import org.json.JSONObject

internal fun runNativeRawMerge(request: NativeMergeRequest): String {
    if (!isNativeRawEngineAvailable()) return "ERROR: native library unavailable"
    return runCatching {
        NativeRawEngine.alignAndMergeRaw16(
            framePaths = request.frameInputs.map { it.file.absolutePath }.toTypedArray(),
            exposureScales = request.exposureScales,
            frameWeights = request.frameWeights,
            width = request.width,
            height = request.height,
            cfaPattern = request.cfa,
            blackLevel = request.blackLevel,
            whiteLevel = request.whiteLevel,
            referenceIndex = request.referenceIndex,
            downscale = 8,
            searchRadius = 24,
            outputMergedRawPath = request.mergedRawFile.absolutePath,
            outputAlignmentJsonPath = request.alignmentFile.absolutePath
        )
    }.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" }
}

internal fun resolveNativeAlignmentStatus(
    nativeMergedOk: Boolean,
    alignment: JSONObject?
): String {
    if (!nativeMergedOk) return "FAILED_FALLBACK_KOTLIN_NO_ALIGNMENT"
    val warning = alignment
        ?.optString("mergeWarning", "")
        .orEmpty()
        .takeIf { it.isNotBlank() && it != "null" }
    val version = alignment?.optString("nativeMergeVersion")
    return when {
        warning == "REFERENCE_ONLY_MERGE" &&
            version == "NATIVE_RAW_FUSION_V0_3_TILE_CONFIDENCE_GHOST" ->
            "NATIVE_TILE_REFERENCE_ONLY_MERGE_WARNING"
        warning == "REFERENCE_ONLY_MERGE" ->
            "NATIVE_REFERENCE_ONLY_MERGE_WARNING"
        version == "NATIVE_RAW_FUSION_V0_3_TILE_CONFIDENCE_GHOST" ->
            "NATIVE_TILE_GHOST_SUPPRESSION_COMPLETE"
        version == "NATIVE_RAW_FUSION_V0_2_CONFIDENCE_GHOST" ->
            "NATIVE_GLOBAL_SHIFT_GHOST_SUPPRESSION_COMPLETE"
        else -> "NATIVE_GLOBAL_SHIFT_COMPLETE"
    }
}

internal fun mergeRawFramesInKotlin(request: KotlinRawMergeRequest) {
    val acc = FloatArray(request.sensor.pixelCount)
    val weights = FloatArray(request.sensor.pixelCount)
    var observedMax = 0
    request.onStatus("Processing RAW fusion: merging Bayer...")
    request.frameInputs.forEachIndexed { frameIndex, input ->
        val raw = readRaw16(input.file, request.sensor.pixelCount)
        val exposureScale =
            request.mergePreparation.exposureScales[frameIndex].toDouble()
        val weight = request.mergePreparation.frameWeights[frameIndex]
        for (pixelIndex in 0 until request.sensor.pixelCount) {
            val rawValue = raw[pixelIndex].toInt() and 0xFFFF
            if (rawValue > observedMax) observedMax = rawValue
            val x = pixelIndex % request.sensor.width
            val y = pixelIndex / request.sensor.width
            val pixelBlack = blackLevelForPixel(
                x,
                y,
                request.sensor.cfa,
                request.blackLevelEstimate
            )
            val corrected = (rawValue - pixelBlack).coerceAtLeast(0)
            acc[pixelIndex] += (corrected * exposureScale).toFloat() * weight
            weights[pixelIndex] += weight
        }
        request.onStatus(
            "Processing RAW fusion: merged " +
                "${frameIndex + 1}/${request.frameInputs.size}"
        )
    }
    val fallbackWhite = estimateWhiteLevel(
        request.job,
        observedMax,
        request.sensor.blackLevel
    ).value
    val merged = ShortArray(request.sensor.pixelCount)
    for (pixelIndex in 0 until request.sensor.pixelCount) {
        val value = (
            acc[pixelIndex] / weights[pixelIndex].coerceAtLeast(0.001f)
            ).toInt().coerceIn(0, fallbackWhite - request.sensor.blackLevel)
        merged[pixelIndex] = value.toShort()
    }
    writeRaw16(merged, request.mergedRawFile)
}
