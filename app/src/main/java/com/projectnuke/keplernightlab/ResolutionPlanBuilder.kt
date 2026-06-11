package com.projectnuke.keplernightlab

import android.util.Size

internal const val PUBLIC_CAMERA2_24MP_NOTICE =
    "24MP Fusion uses public Camera2 RAW/YUV streams only. " +
        "Samsung internal 24MP pipeline may not be exposed."

private data class ResolutionPlanInputs(
    val capability: CameraResolutionCapability,
    val pipelineMode: PipelineMode,
    val normal12: Size?,
    val native24Raw: Size?,
    val native24Yuv: Size?,
    val native24Jpeg: Size?,
    val raw50: Size?,
    val yuv50: Size?,
    val jpeg50: Size?
)

private class ResolutionPlanFactory(private val inputs: ResolutionPlanInputs) {
    private val capability = inputs.capability
    private val maximumSizes =
        capability.maxRawSizes + capability.maxYuvSizes + capability.maxJpegSizes
    private val highResolutionSizes =
        capability.highResRawSizes + capability.highResYuvSizes + capability.highResJpegSizes

    fun plan12(reason: String) = ResolutionCapturePlan(
        requestedMode = CaptureResolutionMode.MP12,
        actualInputMode = CaptureResolutionMode.MP12,
        outputMode = CaptureResolutionMode.MP12,
        cameraId = capability.cameraId,
        inputSize = inputs.normal12,
        outputWidth = null,
        outputHeight = null,
        usesMaximumResolution = false,
        usesHighResolutionSlowPath = false,
        isFusionOrUpscale = false,
        isAvailable = inputs.normal12 != null,
        reason = reason
    )

    fun plan50(isAvailable: Boolean, size: Size?, reason: String) = ResolutionCapturePlan(
        requestedMode = CaptureResolutionMode.MP50,
        actualInputMode = CaptureResolutionMode.MP50,
        outputMode = CaptureResolutionMode.MP50,
        cameraId = capability.cameraId,
        inputSize = size,
        outputWidth = size?.width,
        outputHeight = size?.height,
        usesMaximumResolution = size != null && size in maximumSizes,
        usesHighResolutionSlowPath = size != null && size in highResolutionSizes,
        isFusionOrUpscale = false,
        isAvailable = isAvailable,
        reason = reason
    )

    fun plan24(
        inputSize: Size?,
        strategy: String?,
        reason: String,
        isFusion: Boolean
    ) = ResolutionCapturePlan(
        requestedMode = CaptureResolutionMode.MP24_FUSION,
        actualInputMode = if (strategy == "50MP_DETAIL_TO_24MP_FUSION_V0") {
            CaptureResolutionMode.MP50
        } else {
            CaptureResolutionMode.MP24_FUSION
        },
        outputMode = CaptureResolutionMode.MP24_FUSION,
        cameraId = capability.cameraId,
        inputSize = inputSize,
        outputWidth = 5664,
        outputHeight = 4248,
        usesMaximumResolution = inputSize != null && inputSize in maximumSizes,
        usesHighResolutionSlowPath = inputSize != null && inputSize in highResolutionSizes,
        isFusionOrUpscale = isFusion,
        isAvailable = inputSize != null,
        native24RawAvailable = capability.native24RawAvailable,
        native24YuvAvailable = capability.native24YuvAvailable,
        native24JpegAvailable = capability.native24JpegAvailable,
        highResRawInputAvailable = capability.highResRawInputAvailable,
        selected24MpStrategy = strategy,
        selected24MpReason = reason,
        reason = reason
    )
}

fun allowedResolutionModesForLens(
    lensSlot: LensSlot,
    threeXSourceMode: ThreeXSourceMode,
    capability: CameraResolutionCapability
): List<ResolutionModeAvailability> {
    val has12 =
        (capability.normalRawSizes + capability.normalYuvSizes + capability.normalJpegSizes)
            .any { megapixels(it) in 8.0..14.5 } ||
            capability.maxAvailableRawMp > 0.0 ||
            capability.maxAvailableYuvMp > 0.0
    val has50 = capability.highResRawInputAvailable
    val mp12 = ResolutionModeAvailability(
        CaptureResolutionMode.MP12,
        if (has12) {
            ResolutionAvailability.AVAILABLE
        } else {
            ResolutionAvailability.FALLBACK_ONLY
        },
        if (has12) {
            "12M stream available."
        } else {
            "No explicit 12M stream; app can fall back to max available."
        }
    )

    return when (lensSlot) {
        LensSlot.ULTRAWIDE -> listOf(
            mp12,
            ResolutionModeAvailability(
                CaptureResolutionMode.MP24_FUSION,
                ResolutionAvailability.UNAVAILABLE,
                "Ultrawide is locked to 12M in this app."
            ),
            ResolutionModeAvailability(
                CaptureResolutionMode.MP50,
                ResolutionAvailability.UNAVAILABLE,
                "Ultrawide is locked to 12M in this app."
            )
        )
        LensSlot.MAIN_1X -> listOf(
            mp12,
            ResolutionModeAvailability(
                CaptureResolutionMode.MP24_FUSION,
                if (capability.native24RawAvailable || has50) {
                    ResolutionAvailability.FUSION_AVAILABLE
                } else {
                    ResolutionAvailability.UNAVAILABLE
                },
                when {
                    capability.native24RawAvailable ->
                        "Native 24MP RAW stream exposed. $PUBLIC_CAMERA2_24MP_NOTICE"
                    has50 ->
                        "24MP Fusion uses 50MP-class RAW detail input. " +
                            PUBLIC_CAMERA2_24MP_NOTICE
                    capability.native24YuvAvailable || capability.native24JpegAvailable ->
                        "Processed 24MP stream exists, but RAW Fusion 24MP is unavailable. " +
                            PUBLIC_CAMERA2_24MP_NOTICE
                    else ->
                        "24MP unavailable through public Camera2. " +
                            PUBLIC_CAMERA2_24MP_NOTICE
                }
            ),
            ResolutionModeAvailability(
                CaptureResolutionMode.MP50,
                if (has50) {
                    ResolutionAvailability.AVAILABLE
                } else {
                    ResolutionAvailability.UNAVAILABLE
                },
                if (has50) {
                    "50M RAW_SENSOR stream exposed through public Camera2."
                } else {
                    capability.raw50Reason
                }
            )
        )
        LensSlot.MAIN_2X -> listOf(
            mp12,
            ResolutionModeAvailability(
                CaptureResolutionMode.MP24_FUSION,
                if (has50) {
                    ResolutionAvailability.FUSION_AVAILABLE
                } else {
                    ResolutionAvailability.UNAVAILABLE
                },
                if (has50) {
                    "2x 24M uses 50M detail crop + fusion/SR; native 2x crop is about 12.5MP."
                } else {
                    "2x 24M Fusion needs 50M main stream."
                }
            ),
            ResolutionModeAvailability(
                CaptureResolutionMode.MP50,
                ResolutionAvailability.UNAVAILABLE,
                "50M is not shown as 2x output."
            )
        )
        LensSlot.THREE_X -> if (threeXSourceMode == ThreeXSourceMode.OPTICAL) {
            listOf(
                mp12,
                ResolutionModeAvailability(
                    CaptureResolutionMode.MP24_FUSION,
                    ResolutionAvailability.UNAVAILABLE,
                    "Optical tele is locked to 12M in this app."
                ),
                ResolutionModeAvailability(
                    CaptureResolutionMode.MP50,
                    ResolutionAvailability.UNAVAILABLE,
                    "Optical tele is locked to 12M in this app."
                )
            )
        } else {
            listOf(
                mp12,
                ResolutionModeAvailability(
                    CaptureResolutionMode.MP24_FUSION,
                    if (has50) {
                        ResolutionAvailability.FUSION_AVAILABLE
                    } else {
                        ResolutionAvailability.UNAVAILABLE
                    },
                    if (has50) {
                        "3x 24M uses main 50M crop + fusion/SR."
                    } else {
                        "3x crop 24M Fusion needs 50M main stream."
                    }
                ),
                ResolutionModeAvailability(
                    CaptureResolutionMode.MP50,
                    ResolutionAvailability.UNAVAILABLE,
                    "50M is not shown as 3x output."
                )
            )
        }
    }
}

private fun largestSize(sizes: List<Size>): Size? =
    sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }

private fun buildResolutionPlanInputs(
    capability: CameraResolutionCapability,
    pipelineMode: PipelineMode
): ResolutionPlanInputs {
    fun native24(sizes: List<Size>) =
        largestSize(sizes.filter { megapixels(it) in 20.0..30.0 })
    fun fifty(
        maximum: List<Size>,
        highResolution: List<Size>,
        normal: List<Size>
    ) = largestSize(maximum.filter { megapixels(it) >= HIGH_RES_RAW_INPUT_MIN_MP })
        ?: largestSize(
            highResolution.filter { megapixels(it) >= HIGH_RES_RAW_INPUT_MIN_MP }
        )
        ?: largestSize(normal.filter { megapixels(it) >= HIGH_RES_RAW_INPUT_MIN_MP })

    val raw12 =
        largestSize(capability.normalRawSizes.filter { megapixels(it) in 8.0..14.5 })
    val yuv12 =
        largestSize(capability.normalYuvSizes.filter { megapixels(it) in 8.0..14.5 })
    val jpeg12 =
        largestSize(capability.normalJpegSizes.filter { megapixels(it) in 8.0..14.5 })
    val normal12 = if (pipelineMode == PipelineMode.RAW_NIGHT_FUSION) {
        raw12 ?: largestSize(capability.normalRawSizes)
    } else {
        yuv12 ?: jpeg12
    }
    return ResolutionPlanInputs(
        capability = capability,
        pipelineMode = pipelineMode,
        normal12 = normal12,
        native24Raw = native24(
            capability.normalRawSizes + capability.maxRawSizes + capability.highResRawSizes
        ),
        native24Yuv = native24(
            capability.normalYuvSizes + capability.maxYuvSizes + capability.highResYuvSizes
        ),
        native24Jpeg = native24(
            capability.normalJpegSizes + capability.maxJpegSizes + capability.highResJpegSizes
        ),
        raw50 = fifty(
            capability.maxRawSizes,
            capability.highResRawSizes,
            capability.normalRawSizes
        ),
        yuv50 = fifty(
            capability.maxYuvSizes,
            capability.highResYuvSizes,
            capability.normalYuvSizes
        ),
        jpeg50 = fifty(
            capability.maxJpegSizes,
            capability.highResJpegSizes,
            capability.normalJpegSizes
        )
    )
}

private fun build24MpRawPlan(
    factory: ResolutionPlanFactory,
    inputs: ResolutionPlanInputs,
    detailReason: String,
    unavailableReason: String = "24MP RAW Fusion unavailable."
): ResolutionCapturePlan {
    return when {
        inputs.raw50 != null -> factory.plan24(
            inputs.raw50,
            "50MP_DETAIL_TO_24MP_FUSION_V0",
            "$detailReason $PUBLIC_CAMERA2_24MP_NOTICE",
            true
        )
        inputs.native24Raw != null -> factory.plan24(
            inputs.native24Raw,
            "native_24mp_raw_fallback",
            "No 50MP-class RAW stream is exposed; using native 20-30MP public " +
                "Camera2 RAW fallback. $PUBLIC_CAMERA2_24MP_NOTICE",
            false
        )
        else -> factory.plan24(
            null,
            null,
            "$unavailableReason $PUBLIC_CAMERA2_24MP_NOTICE",
            false
        )
    }
}

private fun buildMain1xPlans(
    factory: ResolutionPlanFactory,
    inputs: ResolutionPlanInputs
): List<ResolutionCapturePlan> {
    if (inputs.pipelineMode == PipelineMode.RAW_NIGHT_FUSION) {
        val raw50Available = inputs.raw50 != null
        return listOf(
            factory.plan50(
                raw50Available,
                inputs.raw50,
                if (raw50Available) {
                    "50M RAW input available."
                } else {
                    inputs.capability.raw50Reason
                }
            ),
            build24MpRawPlan(
                factory,
                inputs,
                "50MP-class RAW detail input selected for 24MP Fusion v0."
            )
        )
    }
    val yuv50Available = inputs.yuv50 != null
    val processed24 = inputs.native24Yuv ?: inputs.native24Jpeg
    return listOf(
        factory.plan50(
            yuv50Available,
            inputs.yuv50,
            when {
                yuv50Available -> "50M YUV input available."
                inputs.jpeg50 != null ->
                    "50M is available only as JPEG/high-res still, not YUV burst."
                else -> "50M unavailable: no >=40MP YUV stream exposed."
            }
        ),
        factory.plan24(
            processed24,
            when {
                inputs.native24Yuv != null -> "native_24mp_yuv_processed"
                inputs.native24Jpeg != null -> "native_24mp_jpeg_processed"
                else -> null
            },
            if (processed24 != null) {
                "Processed 24MP public Camera2 stream selected. " +
                    PUBLIC_CAMERA2_24MP_NOTICE
            } else {
                "Processed 24MP unavailable through public Camera2. " +
                    PUBLIC_CAMERA2_24MP_NOTICE
            },
            false
        )
    )
}

private fun buildMain2xPlans(
    factory: ResolutionPlanFactory,
    inputs: ResolutionPlanInputs
): List<ResolutionCapturePlan> {
    if (inputs.pipelineMode != PipelineMode.RAW_NIGHT_FUSION) return emptyList()
    val plan = if (inputs.raw50 != null) {
        factory.plan24(
            inputs.raw50,
            "50MP_DETAIL_TO_24MP_FUSION_V0",
            "2x 24MP Fusion uses 50MP-class public Camera2 RAW detail input. " +
                PUBLIC_CAMERA2_24MP_NOTICE,
            true
        )
    } else {
        build24MpRawPlan(
            factory,
            inputs,
            "2x 24MP Fusion uses 50MP-class public Camera2 RAW detail input.",
            "24MP unavailable through public Camera2."
        )
    }
    return listOf(plan)
}

private fun buildThreeXPlans(
    factory: ResolutionPlanFactory,
    inputs: ResolutionPlanInputs,
    threeXSourceMode: ThreeXSourceMode
): List<ResolutionCapturePlan> {
    if (
        threeXSourceMode != ThreeXSourceMode.MAIN_CROP ||
        inputs.pipelineMode != PipelineMode.RAW_NIGHT_FUSION
    ) {
        return emptyList()
    }
    val plan = if (inputs.raw50 != null) {
        factory.plan24(
            inputs.raw50,
            "50MP_DETAIL_TO_24MP_FUSION_V0",
            "3x 24MP Fusion uses 50MP-class public Camera2 RAW detail input. " +
                PUBLIC_CAMERA2_24MP_NOTICE,
            true
        )
    } else {
        build24MpRawPlan(
            factory,
            inputs,
            "3x 24MP Fusion uses 50MP-class public Camera2 RAW detail input.",
            "24MP unavailable through public Camera2."
        )
    }
    return listOf(plan)
}

fun buildResolutionCapturePlans(
    lensSlot: LensSlot,
    threeXSourceMode: ThreeXSourceMode,
    pipelineMode: PipelineMode,
    capability: CameraResolutionCapability
): List<ResolutionCapturePlan> {
    val inputs = buildResolutionPlanInputs(capability, pipelineMode)
    val factory = ResolutionPlanFactory(inputs)
    val only12Reason = when {
        lensSlot == LensSlot.ULTRAWIDE ->
            "Ultrawide is locked to 12M in this app."
        lensSlot == LensSlot.THREE_X &&
            threeXSourceMode == ThreeXSourceMode.OPTICAL ->
            "Optical tele is locked to 12M in this app."
        else -> "12M burst capture available."
    }
    val additionalPlans = when (lensSlot) {
        LensSlot.ULTRAWIDE -> emptyList()
        LensSlot.MAIN_1X -> buildMain1xPlans(factory, inputs)
        LensSlot.MAIN_2X -> buildMain2xPlans(factory, inputs)
        LensSlot.THREE_X -> buildThreeXPlans(factory, inputs, threeXSourceMode)
    }
    return listOf(factory.plan12(only12Reason)) + additionalPlans
}

fun planCropOutput(
    inputSize: Size,
    zoomRatio: Float,
    targetMode: CaptureResolutionMode
): CropOutputPlan {
    val cropWidth = (inputSize.width / zoomRatio).toInt().coerceAtLeast(1)
    val cropHeight = (inputSize.height / zoomRatio).toInt().coerceAtLeast(1)
    val nativeMp = cropWidth.toDouble() * cropHeight.toDouble() / 1_000_000.0
    val targetMp = when (targetMode) {
        CaptureResolutionMode.MP12 -> 12.0
        CaptureResolutionMode.MP24_FUSION -> 24.0
        CaptureResolutionMode.MP50 -> 50.0
    }
    val needsFusion =
        targetMode == CaptureResolutionMode.MP24_FUSION && nativeMp < 20.0
    return CropOutputPlan(
        zoomRatio = zoomRatio,
        nativeCropWidth = cropWidth,
        nativeCropHeight = cropHeight,
        nativeCropMp = nativeMp,
        targetOutputMp = targetMp,
        requiresUpscaleOrFusion = needsFusion,
        note = if (needsFusion) {
            "2x 24M is fusion/SR, not native crop."
        } else {
            "Native crop can satisfy target."
        }
    )
}

fun choose24MpFusionStrategy(
    lensSlot: LensSlot,
    zoomRatio: Float,
    capability: CameraResolutionCapability
): Fusion24Strategy {
    val has50 = capability.highResRawInputAvailable
    return when {
        lensSlot == LensSlot.MAIN_1X && has50 ->
            Fusion24Strategy.TWELVE_PLUS_FIFTY_DETAIL
        zoomRatio >= 1.9f && has50 ->
            Fusion24Strategy.FIFTY_DETAIL_DOWNSAMPLE
        capability.native24RawAvailable ->
            Fusion24Strategy.NATIVE_24MP_STREAM
        else -> Fusion24Strategy.UNAVAILABLE
    }
}
