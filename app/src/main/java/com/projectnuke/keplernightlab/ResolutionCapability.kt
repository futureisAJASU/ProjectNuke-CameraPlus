package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size

enum class ResolutionAvailability {
    AVAILABLE,
    FUSION_AVAILABLE,
    FALLBACK_ONLY,
    UNAVAILABLE
}

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
    val has50 = listOf(
        capability.maxAvailableRawMp,
        capability.maxAvailableYuvMp,
        capability.maxAvailableJpegMp
    ).max() >= 40.0
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
                if (has12 && has50) ResolutionAvailability.FUSION_AVAILABLE else ResolutionAvailability.UNAVAILABLE,
                if (has12 && has50) "24M Fusion planned from 12M + high-resolution detail." else "24M Fusion needs high-resolution detail input; 50M not exposed."
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
    val raw50 = largest(capability.maxRawSizes.filter { megapixels(it) >= 40.0 })
        ?: largest(capability.highResRawSizes.filter { megapixels(it) >= 40.0 })
        ?: largest(capability.normalRawSizes.filter { megapixels(it) >= 40.0 })
    val yuv50 = largest(capability.maxYuvSizes.filter { megapixels(it) >= 40.0 })
        ?: largest(capability.highResYuvSizes.filter { megapixels(it) >= 40.0 })
        ?: largest(capability.normalYuvSizes.filter { megapixels(it) >= 40.0 })
    val jpeg50 = largest(capability.maxJpegSizes.filter { megapixels(it) >= 40.0 })
        ?: largest(capability.highResJpegSizes.filter { megapixels(it) >= 40.0 })
        ?: largest(capability.normalJpegSizes.filter { megapixels(it) >= 40.0 })
    val normal12 = largest((capability.normalRawSizes + capability.normalYuvSizes + capability.normalJpegSizes).filter { megapixels(it) in 8.0..14.5 })
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
    fun plan24(isAvailable: Boolean, reason: String) = target24().let { (w, h) ->
        ResolutionCapturePlan(
            requestedMode = CaptureResolutionMode.MP24_FUSION,
            actualInputMode = if (has50DetailForRaw) CaptureResolutionMode.MP50 else CaptureResolutionMode.MP12,
            outputMode = CaptureResolutionMode.MP24_FUSION,
            cameraId = capability.cameraId,
            inputSize = raw50,
            outputWidth = w,
            outputHeight = h,
            usesMaximumResolution = raw50 != null && raw50 in capability.maxRawSizes,
            usesHighResolutionSlowPath = raw50 != null && raw50 in capability.highResRawSizes,
            isFusionOrUpscale = true,
            isAvailable = isAvailable,
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
                plans += plan24(has50DetailForRaw, if (has50DetailForRaw) "24M Fusion v0: using 50MP detail input." else "24M Fusion unavailable: 50MP detail input is not exposed.")
            } else {
                plans += plan50(has50ForYuv, yuv50, if (has50ForYuv) "50M YUV input available." else if (jpeg50 != null) "50M is available only as JPEG/high-res still, not YUV burst." else "50M unavailable: no >=40MP YUV stream exposed.")
            }
        }
        LensSlot.MAIN_2X -> {
            if (pipelineMode == PipelineMode.RAW_NIGHT_FUSION) {
                plans += plan24(has50DetailForRaw, if (has50DetailForRaw) "2x 24M Fusion v0 uses 50MP crop + upscale/SR placeholder." else "24M Fusion unavailable: 50MP detail input is not exposed.")
            }
        }
        LensSlot.THREE_X -> if (threeXSourceMode == ThreeXSourceMode.MAIN_CROP && pipelineMode == PipelineMode.RAW_NIGHT_FUSION) {
            plans += plan24(has50DetailForRaw, if (has50DetailForRaw) "3x 24M Fusion v0 uses 50MP crop + upscale/SR placeholder." else "24M Fusion unavailable: 50MP detail input is not exposed.")
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
    val allSizes = capability.normalRawSizes + capability.maxRawSizes + capability.highResRawSizes +
        capability.normalYuvSizes + capability.maxYuvSizes + capability.highResYuvSizes
    if (allSizes.any { megapixels(it) in 20.0..30.0 }) return Fusion24Strategy.NATIVE_24MP_STREAM
    val has12 = allSizes.any { megapixels(it) in 8.0..14.5 }
    val has50 = allSizes.any { megapixels(it) >= 40.0 }
    return when {
        lensSlot == LensSlot.MAIN_1X && has12 && has50 -> Fusion24Strategy.TWELVE_PLUS_FIFTY_DETAIL
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
            appendLine("cameraId=$id")
            appendLine("Ultra high resolution: ${if (cap.supportsUltraHighResolution) "YES" else "NO"}")
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
