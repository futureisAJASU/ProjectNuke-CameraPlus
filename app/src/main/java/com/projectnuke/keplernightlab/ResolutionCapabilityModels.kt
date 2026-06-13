package com.projectnuke.keplernightlab

import android.util.Size

enum class ResolutionAvailability {
    AVAILABLE,
    FUSION_AVAILABLE,
    FALLBACK_ONLY,
    UNAVAILABLE
}

const val HIGH_RES_RAW_INPUT_MIN_MP = 40.0

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
    val capabilities: List<Int>,
    val sensorPixelModeRequestKeySupported: Boolean,
    val physicalCameraIds: List<String>,
    val native24RawAvailable: Boolean,
    val native24YuvAvailable: Boolean,
    val native24JpegAvailable: Boolean,
    val highResRawInputAvailable: Boolean,
    val raw50Available: Boolean,
    val yuv50Available: Boolean,
    val jpeg50Available: Boolean,
    val raw12Available: Boolean,
    val yuv12Available: Boolean,
    val normalRaw50Available: Boolean,
    val normalYuv50Available: Boolean,
    val normalJpeg50Available: Boolean,
    val maxResolutionRaw50Available: Boolean,
    val maxResolutionYuv50Available: Boolean,
    val maxResolutionJpeg50Available: Boolean,
    val maxResolutionPixelModeRequired: Boolean,
    val maximumResolutionPixelModeSettable: Boolean,
    val raw50Reason: String,
    val processed50Reason: String,
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
