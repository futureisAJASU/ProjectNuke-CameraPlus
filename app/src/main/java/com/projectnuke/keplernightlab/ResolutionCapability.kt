package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Size

enum class ResolutionAvailability {
    AVAILABLE,
    FUSION_AVAILABLE,
    FALLBACK_ONLY,
    UNAVAILABLE
}

const val HIGH_RES_RAW_INPUT_MIN_MP = 40.0
private const val PUBLIC_CAMERA2_24MP_NOTICE =
    "24MP Fusion uses public Camera2 RAW/YUV streams only. Samsung internal 24MP pipeline may not be exposed."

data class ResolutionModeAvailability(
    val mode: CaptureResolutionMode,
    val availability: ResolutionAvailability,
    val reason: String
)

data class CameraResolutionCapability(
    val cameraId: String,
    val lensSlot: LensSlot,
    val supportsUltraHighResolution: Boolean,
    val normalRawSizes: List<Size>,
    val normalYuvSizes: List<Size>,
    val normalJpegSizes: List<Size>,
    val maxRawSizes: List<Size>,
    val maxYuvSizes: List<Size>,
    val maxJpegSizes: List<Size>,
    val highResRawSizes: List<Size>,
    val highResYuvSizes: List<Size>,
    val highResJpegSizes: List<Size>,
    val native24RawAvailable: Boolean,
    val native24YuvAvailable: Boolean,
    val native24JpegAvailable: Boolean,
    val highResRawInputAvailable: Boolean,
    val maxAvailableRawMp: Double,
    val maxAvailableYuvMp: Double,
    val maxAvailableJpegMp: Double
)

data class CropOutputPlan(
    val zoomRatio: Float,
    val nativeCropWidth: Int,
    val nativeCropHeight: Int,
    val nativeCropMp: Double,
    val targetOutputMp: Double,
    val requiresUpscaleOrFusion: Boolean,
    val note: String
)

data class ResolutionCapturePlan(
    val requestedMode: CaptureResolutionMode,
    val actualInputMode: CaptureResolutionMode,
    val outputMode: CaptureResolutionMode,
    val cameraId: String,
    val inputSize: Size?,
    val outputWidth: Int?,
    val outputHeight: Int?,
    val usesMaximumResolution: Boolean,
    val usesHighResolutionSlowPath: Boolean,
    val isFusionOrUpscale: Boolean,
    val isAvailable: Boolean,
    val native24RawAvailable: Boolean = false,
    val native24YuvAvailable: Boolean = false,
    val native24JpegAvailable: Boolean = false,
    val highResRawInputAvailable: Boolean = false,
    val selected24MpStrategy: String? = null,
    val selected24MpReason: String? = null,
    val reason: String
)

enum class Fusion24Strategy {
    NATIVE_24MP_STREAM,
    TWELVE_PLUS_FIFTY_DETAIL,
    FIFTY_DETAIL_DOWNSAMPLE,
    UNAVAILABLE
}

fun megapixels(size: Size): Double = size.width.toDouble() * size.height.toDouble() / 1_000_000.0

fun queryCameraResolutionCapability(
    context: Context,
    cameraId: String,
    lensSlot: LensSlot
): CameraResolutionCapability = CameraCapabilityCache.getResolutionCapability(context, cameraId, lensSlot)

fun loadCameraResolutionCapability(
    context: Context,
    cameraId: String,
    lensSlot: LensSlot
): CameraResolutionCapability {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val c = manager.getCameraCharacteristics(cameraId)
    val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
    val supportsUltraHighResolution = capabilities.contains(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
    )
    val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val maxMap = if (Build.VERSION.SDK_INT >= 31) {
        c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
    } else {
        null
    }

    fun normal(format: Int) = map?.getOutputSizes(format)?.toList().orEmpty()
    fun maxRes(format: Int) = maxMap?.getOutputSizes(format)?.toList().orEmpty()
    fun highRes(format: Int) = runCatching {
        map?.getHighResolutionOutputSizes(format)?.toList().orEmpty()
    }.getOrDefault(emptyList())
    fun maxMp(sizes: List<Size>) = sizes.maxOfOrNull(::megapixels) ?: 0.0

    val normalRaw = normal(ImageFormat.RAW_SENSOR)
    val normalYuv = normal(ImageFormat.YUV_420_888)
    val normalJpeg = normal(ImageFormat.JPEG)
    val maxRaw = maxRes(ImageFormat.RAW_SENSOR)
    val maxYuv = maxRes(ImageFormat.YUV_420_888)
    val maxJpeg = maxRes(ImageFormat.JPEG)
    val highRaw = highRes(ImageFormat.RAW_SENSOR)
    val highYuv = highRes(ImageFormat.YUV_420_888)
    val highJpeg = highRes(ImageFormat.JPEG)

    return CameraResolutionCapability(
        cameraId = cameraId,
        lensSlot = lensSlot,
        supportsUltraHighResolution = supportsUltraHighResolution,
        normalRawSizes = normalRaw,
        normalYuvSizes = normalYuv,
        normalJpegSizes = normalJpeg,
        maxRawSizes = maxRaw,
        maxYuvSizes = maxYuv,
        maxJpegSizes = maxJpeg,
        highResRawSizes = highRaw,
        highResYuvSizes = highYuv,
        highResJpegSizes = highJpeg,
        native24RawAvailable = (normalRaw + maxRaw + highRaw).any { megapixels(it) in 20.0..30.0 },
        native24YuvAvailable = (normalYuv + maxYuv + highYuv).any { megapixels(it) in 20.0..30.0 },
        native24JpegAvailable = (normalJpeg + maxJpeg + highJpeg).any { megapixels(it) in 20.0..30.0 },
        highResRawInputAvailable = (normalRaw + maxRaw + highRaw).any { megapixels(it) >= HIGH_RES_RAW_INPUT_MIN_MP },
        maxAvailableRawMp = maxMp(normalRaw + maxRaw + highRaw),
        maxAvailableYuvMp = maxMp(normalYuv + maxYuv + highYuv),
        maxAvailableJpegMp = maxMp(normalJpeg + maxJpeg + highJpeg)
    )
}

fun allowedResolutionModesForLens(
    lensSlot: LensSlot,
    threeXSourceMode: ThreeXSourceMode,
    capability: CameraResolutionCapability
): List<ResolutionModeAvailability> {
    val has12 = (capability.normalRawSizes + capability.normalYuvSizes + capability.normalJpegSizes)
        .any { megapixels(it) in 8.0..14.5 } || capability.maxAvailableRawMp > 0.0 || capability.maxAvailableYuvMp > 0.0
    val has50 = capability.highResRawInputAvailable
    val mp12 = ResolutionModeAvailability(
        CaptureResolutionMode.MP12,
        if (has12) ResolutionAvailability.AVAILABLE else ResolutionAvailability.FALLBACK_ONLY,
        if (has12) "12M stream available." else "No explicit 12M stream; app can fall back to max available."
    )

    return when (lensSlot) {
        LensSlot.ULTRAWIDE -> listOf(
            mp12,
            ResolutionModeAvailability(CaptureResolutionMode.MP24_FUSION, ResolutionAvailability.UNAVAILABLE, "Ultrawide is locked to 12M in this app."),
            ResolutionModeAvailability(CaptureResolutionMode.MP50, ResolutionAvailability.UNAVAILABLE, "Ultrawide is locked to 12M in this app.")
        )
        LensSlot.MAIN_1X -> listOf(
            mp12,
            ResolutionModeAvailability(
                CaptureResolutionMode.MP24_FUSION,
                if (capability.native24RawAvailable || has50) ResolutionAvailability.FUSION_AVAILABLE else ResolutionAvailability.UNAVAILABLE,
                when {
                    capability.native24RawAvailable -> "Native 24MP RAW stream exposed. $PUBLIC_CAMERA2_24MP_NOTICE"
                    has50 -> "24MP Fusion uses 50MP-class RAW detail input. $PUBLIC_CAMERA2_24MP_NOTICE"
                    capability.native24YuvAvailable || capability.native24JpegAvailable ->
                        "Processed 24MP stream exists, but RAW Fusion 24MP is unavailable. $PUBLIC_CAMERA2_24MP_NOTICE"
                    else -> "24MP unavailable through public Camera2. $PUBLIC_CAMERA2_24MP_NOTICE"
                }
            ),
            ResolutionModeAvailability(
                CaptureResolutionMode.MP50,
                if (has50) ResolutionAvailability.AVAILABLE else ResolutionAvailability.UNAVAILABLE,
                if (has50) "50M high-resolution stream exposed." else "50M not exposed through public Camera2."
            )
        )
        LensSlot.MAIN_2X -> listOf(
            mp12,
            ResolutionModeAvailability(
                CaptureResolutionMode.MP24_FUSION,
                if (has50) ResolutionAvailability.FUSION_AVAILABLE else ResolutionAvailability.UNAVAILABLE,
                if (has50) "2x 24M uses 50M detail crop + fusion/SR; native 2x crop is about 12.5MP." else "2x 24M Fusion needs 50M main stream."
            ),
            ResolutionModeAvailability(CaptureResolutionMode.MP50, ResolutionAvailability.UNAVAILABLE, "50M is not shown as 2x output.")
        )
        LensSlot.THREE_X -> if (threeXSourceMode == ThreeXSourceMode.OPTICAL) {
            listOf(
                mp12,
                ResolutionModeAvailability(CaptureResolutionMode.MP24_FUSION, ResolutionAvailability.UNAVAILABLE, "Optical tele is locked to 12M in this app."),
                ResolutionModeAvailability(CaptureResolutionMode.MP50, ResolutionAvailability.UNAVAILABLE, "Optical tele is locked to 12M in this app.")
            )
        } else {
            listOf(
                mp12,
                ResolutionModeAvailability(
                    CaptureResolutionMode.MP24_FUSION,
                    if (has50) ResolutionAvailability.FUSION_AVAILABLE else ResolutionAvailability.UNAVAILABLE,
                    if (has50) "3x 24M uses main 50M crop + fusion/SR." else "3x crop 24M Fusion needs 50M main stream."
                ),
                ResolutionModeAvailability(CaptureResolutionMode.MP50, ResolutionAvailability.UNAVAILABLE, "50M is not shown as 3x output.")
            )
        }
    }
}

fun buildResolutionCapturePlans(
    lensSlot: LensSlot,
    threeXSourceMode: ThreeXSourceMode,
    pipelineMode: PipelineMode,
    capability: CameraResolutionCapability
): List<ResolutionCapturePlan> {
    fun largest(sizes: List<Size>) = sizes.maxByOrNull { it.width * it.height }
    val allRawSizes = capability.normalRawSizes + capability.maxRawSizes + capability.highResRawSizes
    val allYuvSizes = capability.normalYuvSizes + capability.maxYuvSizes + capability.highResYuvSizes
    val allJpegSizes = capability.normalJpegSizes + capability.maxJpegSizes + capability.highResJpegSizes
    val native24Raw = largest(allRawSizes.filter { megapixels(it) in 20.0..30.0 })
    val native24Yuv = largest(allYuvSizes.filter { megapixels(it) in 20.0..30.0 })
    val native24Jpeg = largest(allJpegSizes.filter { megapixels(it) in 20.0..30.0 })
    val raw50 = largest(capability.maxRawSizes.filter { megapixels(it) >= HIGH_RES_RAW_INPUT_MIN_MP })
        ?: largest(capability.highResRawSizes.filter { megapixels(it) >= HIGH_RES_RAW_INPUT_MIN_MP })
        ?: largest(capability.normalRawSizes.filter { megapixels(it) >= HIGH_RES_RAW_INPUT_MIN_MP })
    val yuv50 = largest(capability.maxYuvSizes.filter { megapixels(it) >= 40.0 })
        ?: largest(capability.highResYuvSizes.filter { megapixels(it) >= 40.0 })
        ?: largest(capability.normalYuvSizes.filter { megapixels(it) >= 40.0 })
    val jpeg50 = largest(capability.maxJpegSizes.filter { megapixels(it) >= 40.0 })
        ?: largest(capability.highResJpegSizes.filter { megapixels(it) >= 40.0 })
        ?: largest(capability.normalJpegSizes.filter { megapixels(it) >= 40.0 })
    val raw12 = largest(capability.normalRawSizes.filter { megapixels(it) in 8.0..14.5 })
    val yuv12 = largest(capability.normalYuvSizes.filter { megapixels(it) in 8.0..14.5 })
    val jpeg12 = largest(capability.normalJpegSizes.filter { megapixels(it) in 8.0..14.5 })
    val normal12 = if (pipelineMode == PipelineMode.RAW_NIGHT_FUSION) {
        raw12
    } else {
        yuv12 ?: jpeg12
    } ?: raw12 ?: yuv12 ?: jpeg12
    val has50DetailForRaw = raw50 != null
    val has50ForYuv = yuv50 != null

    fun target24() = 5664 to 4248
    fun plan12(reason: String) = ResolutionCapturePlan(
        requestedMode = CaptureResolutionMode.MP12,
        actualInputMode = CaptureResolutionMode.MP12,
        outputMode = CaptureResolutionMode.MP12,
        cameraId = capability.cameraId,
        inputSize = normal12,
        outputWidth = null,
        outputHeight = null,
        usesMaximumResolution = false,
        usesHighResolutionSlowPath = false,
        isFusionOrUpscale = false,
        isAvailable = true,
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
        usesMaximumResolution = size != null && size in capability.maxRawSizes + capability.maxYuvSizes + capability.maxJpegSizes,
        usesHighResolutionSlowPath = size != null && size in capability.highResRawSizes + capability.highResYuvSizes + capability.highResJpegSizes,
        isFusionOrUpscale = false,
        isAvailable = isAvailable,
        reason = reason
    )
    fun plan24(inputSize: Size?, strategy: String?, reason: String, isFusion: Boolean) = target24().let { (w, h) ->
        ResolutionCapturePlan(
            requestedMode = CaptureResolutionMode.MP24_FUSION,
            actualInputMode = if (strategy == "50MP_DETAIL_TO_24MP_FUSION_V0") CaptureResolutionMode.MP50 else CaptureResolutionMode.MP24_FUSION,
            outputMode = CaptureResolutionMode.MP24_FUSION,
            cameraId = capability.cameraId,
            inputSize = inputSize,
            outputWidth = w,
            outputHeight = h,
            usesMaximumResolution = inputSize != null && inputSize in capability.maxRawSizes + capability.maxYuvSizes + capability.maxJpegSizes,
            usesHighResolutionSlowPath = inputSize != null && inputSize in capability.highResRawSizes + capability.highResYuvSizes + capability.highResJpegSizes,
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

    val only12Reason = when {
        lensSlot == LensSlot.ULTRAWIDE -> "Ultrawide is locked to 12M in this app."
        lensSlot == LensSlot.THREE_X && threeXSourceMode == ThreeXSourceMode.OPTICAL -> "Optical tele is locked to 12M in this app."
        else -> "12M burst capture available."
    }
    val plans = mutableListOf(plan12(only12Reason))
    when (lensSlot) {
        LensSlot.ULTRAWIDE -> Unit
        LensSlot.MAIN_1X -> {
            if (pipelineMode == PipelineMode.RAW_NIGHT_FUSION) {
                plans += plan50(has50DetailForRaw, raw50, if (has50DetailForRaw) "50M RAW input available." else "50M unavailable: no >=40MP RAW stream exposed through public Camera2 for selected main camera.")
                plans += when {
                    native24Raw != null -> plan24(
                        native24Raw,
                        "native_24mp_raw",
                        "Native 24MP RAW stream selected. $PUBLIC_CAMERA2_24MP_NOTICE",
                        false
                    )
                    raw50 != null -> plan24(
                        raw50,
                        "50MP_DETAIL_TO_24MP_FUSION_V0",
                        "50MP-class RAW detail input selected for 24MP Fusion v0. $PUBLIC_CAMERA2_24MP_NOTICE",
                        true
                    )
                    else -> plan24(
                        null,
                        null,
                        "24MP RAW Fusion unavailable. $PUBLIC_CAMERA2_24MP_NOTICE",
                        false
                    )
                }
            } else {
                plans += plan50(has50ForYuv, yuv50, if (has50ForYuv) "50M YUV input available." else if (jpeg50 != null) "50M is available only as JPEG/high-res still, not YUV burst." else "50M unavailable: no >=40MP YUV stream exposed.")
                val processed24 = native24Yuv ?: native24Jpeg
                plans += plan24(
                    processed24,
                    if (native24Yuv != null) "native_24mp_yuv_processed" else if (native24Jpeg != null) "native_24mp_jpeg_processed" else null,
                    if (processed24 != null) {
                        "Processed 24MP public Camera2 stream selected. $PUBLIC_CAMERA2_24MP_NOTICE"
                    } else {
                        "Processed 24MP unavailable through public Camera2. $PUBLIC_CAMERA2_24MP_NOTICE"
                    },
                    false
                )
            }
        }
        LensSlot.MAIN_2X -> {
            if (pipelineMode == PipelineMode.RAW_NIGHT_FUSION) {
                plans += plan24(raw50, if (raw50 != null) "50MP_DETAIL_TO_24MP_FUSION_V0" else null, if (raw50 != null) "2x 24MP Fusion uses 50MP-class public Camera2 RAW detail input. $PUBLIC_CAMERA2_24MP_NOTICE" else "24MP unavailable through public Camera2. $PUBLIC_CAMERA2_24MP_NOTICE", true)
            }
        }
        LensSlot.THREE_X -> if (threeXSourceMode == ThreeXSourceMode.MAIN_CROP && pipelineMode == PipelineMode.RAW_NIGHT_FUSION) {
            plans += plan24(raw50, if (raw50 != null) "50MP_DETAIL_TO_24MP_FUSION_V0" else null, if (raw50 != null) "3x 24MP Fusion uses 50MP-class public Camera2 RAW detail input. $PUBLIC_CAMERA2_24MP_NOTICE" else "24MP unavailable through public Camera2. $PUBLIC_CAMERA2_24MP_NOTICE", true)
        }
    }
    return plans
}

fun planCropOutput(inputSize: Size, zoomRatio: Float, targetMode: CaptureResolutionMode): CropOutputPlan {
    val cropWidth = (inputSize.width / zoomRatio).toInt().coerceAtLeast(1)
    val cropHeight = (inputSize.height / zoomRatio).toInt().coerceAtLeast(1)
    val nativeMp = cropWidth.toDouble() * cropHeight.toDouble() / 1_000_000.0
    val targetMp = when (targetMode) {
        CaptureResolutionMode.MP12 -> 12.0
        CaptureResolutionMode.MP24_FUSION -> 24.0
        CaptureResolutionMode.MP50 -> 50.0
    }
    val needsFusion = targetMode == CaptureResolutionMode.MP24_FUSION && nativeMp < 20.0
    return CropOutputPlan(
        zoomRatio = zoomRatio,
        nativeCropWidth = cropWidth,
        nativeCropHeight = cropHeight,
        nativeCropMp = nativeMp,
        targetOutputMp = targetMp,
        requiresUpscaleOrFusion = needsFusion,
        note = if (needsFusion) "2x 24M is fusion/SR, not native crop." else "Native crop can satisfy target."
    )
}

fun choose24MpFusionStrategy(
    lensSlot: LensSlot,
    zoomRatio: Float,
    capability: CameraResolutionCapability
): Fusion24Strategy {
    if (capability.native24RawAvailable) return Fusion24Strategy.NATIVE_24MP_STREAM
    val has50 = capability.highResRawInputAvailable
    return when {
        lensSlot == LensSlot.MAIN_1X && has50 -> Fusion24Strategy.TWELVE_PLUS_FIFTY_DETAIL
        zoomRatio >= 1.9f && has50 -> Fusion24Strategy.FIFTY_DETAIL_DOWNSAMPLE
        else -> Fusion24Strategy.UNAVAILABLE
    }
}

fun buildResolutionCapabilityReport(context: Context): String {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    fun List<Size>.describe() = if (isEmpty()) "none" else joinToString { "${it.width}x${it.height} ${"%.1f".format(megapixels(it))}MP" }
    return buildString {
        manager.cameraIdList.forEach { id ->
            val lensSlot = LensSlot.MAIN_1X
            val cap = queryCameraResolutionCapability(context, id, lensSlot)
            val characteristics = manager.getCameraCharacteristics(id)
            val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val maxActiveArray = if (Build.VERSION.SDK_INT >= 31) {
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)
            } else {
                null
            }
            val hasSensorPixelModeRequestKey = characteristics
                .getAvailableCaptureRequestKeys()
                ?.contains(CaptureRequest.SENSOR_PIXEL_MODE) == true
            val hasMaximumResolutionMap = if (Build.VERSION.SDK_INT >= 31) {
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) != null
            } else {
                false
            }
            appendLine("cameraId=$id")
            appendLine("Ultra high resolution: ${if (cap.supportsUltraHighResolution) "YES" else "NO"}")
            appendLine("activeArray: ${activeArray ?: "none"}")
            appendLine("maximumResolutionActiveArray: ${maxActiveArray ?: "none"}")
            appendLine("SENSOR_PIXEL_MODE request key: ${if (hasSensorPixelModeRequestKey) "YES" else "NO"}")
            appendLine("maximum-resolution stream map: ${if (hasMaximumResolutionMap) "YES" else "NO"}")
            appendLine("normal RAW: ${cap.normalRawSizes.describe()}")
            appendLine("normal YUV: ${cap.normalYuvSizes.describe()}")
            appendLine("normal JPEG: ${cap.normalJpegSizes.describe()}")
            appendLine("maximum RAW: ${cap.maxRawSizes.describe()}")
            appendLine("maximum YUV: ${cap.maxYuvSizes.describe()}")
            appendLine("maximum JPEG: ${cap.maxJpegSizes.describe()}")
            appendLine("highRes RAW: ${cap.highResRawSizes.describe()}")
            appendLine("highRes YUV: ${cap.highResYuvSizes.describe()}")
            appendLine("highRes JPEG: ${cap.highResJpegSizes.describe()}")
            appendLine("max MP RAW/YUV/JPEG: ${"%.1f".format(cap.maxAvailableRawMp)} / ${"%.1f".format(cap.maxAvailableYuvMp)} / ${"%.1f".format(cap.maxAvailableJpegMp)}")
            appendLine()
        }
    }.trim()
}
