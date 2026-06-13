package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Size

internal data class CameraStreamSizes(
    val normalRaw: List<Size>,
    val normalYuv: List<Size>,
    val normalJpeg: List<Size>,
    val maximumRaw: List<Size>,
    val maximumYuv: List<Size>,
    val maximumJpeg: List<Size>,
    val highResolutionRaw: List<Size>,
    val highResolutionYuv: List<Size>,
    val highResolutionJpeg: List<Size>
) {
    val allRaw = normalRaw + maximumRaw + highResolutionRaw
    val allYuv = normalYuv + maximumYuv + highResolutionYuv
    val allJpeg = normalJpeg + maximumJpeg + highResolutionJpeg
}

private data class StreamSizeSet(
    val raw: List<Size>,
    val yuv: List<Size>,
    val jpeg: List<Size>
)

internal data class Raw50Status(
    val rawAvailable: Boolean,
    val yuvAvailable: Boolean,
    val jpegAvailable: Boolean,
    val maxRawMp: Double,
    val maxYuvMp: Double,
    val maxJpegMp: Double,
    val normalRawAvailable: Boolean,
    val normalYuvAvailable: Boolean,
    val normalJpegAvailable: Boolean,
    val maximumRawAvailable: Boolean,
    val maximumYuvAvailable: Boolean,
    val maximumJpegAvailable: Boolean,
    val maximumResolutionPixelModeRequired: Boolean,
    val reason: String
)

internal data class Native24Status(
    val rawAvailable: Boolean,
    val yuvAvailable: Boolean,
    val jpegAvailable: Boolean
)

fun megapixels(size: Size): Double =
    size.width.toDouble() * size.height.toDouble() / 1_000_000.0

private fun collectNormalStreamSizes(
    characteristics: CameraCharacteristics
): StreamSizeSet {
    val normalMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    fun sizes(format: Int) = normalMap?.getOutputSizes(format)?.toList().orEmpty()
    return StreamSizeSet(
        raw = sizes(ImageFormat.RAW_SENSOR),
        yuv = sizes(ImageFormat.YUV_420_888),
        jpeg = sizes(ImageFormat.JPEG)
    )
}

private fun collectMaximumResolutionStreamSizes(
    characteristics: CameraCharacteristics
): StreamSizeSet {
    val maximumMap = if (Build.VERSION.SDK_INT >= 31) {
        characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
        )
    } else {
        null
    }
    fun sizes(format: Int) = maximumMap?.getOutputSizes(format)?.toList().orEmpty()
    return StreamSizeSet(
        raw = sizes(ImageFormat.RAW_SENSOR),
        yuv = sizes(ImageFormat.YUV_420_888),
        jpeg = sizes(ImageFormat.JPEG)
    )
}

private fun collectHighResolutionStreamSizes(
    characteristics: CameraCharacteristics
): StreamSizeSet {
    val normalMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    fun sizes(format: Int) = runCatching {
        normalMap?.getHighResolutionOutputSizes(format)?.toList().orEmpty()
    }.getOrDefault(emptyList())
    return StreamSizeSet(
        raw = sizes(ImageFormat.RAW_SENSOR),
        yuv = sizes(ImageFormat.YUV_420_888),
        jpeg = sizes(ImageFormat.JPEG)
    )
}

private fun collectStreamSizes(characteristics: CameraCharacteristics): CameraStreamSizes {
    val normal = collectNormalStreamSizes(characteristics)
    val maximum = collectMaximumResolutionStreamSizes(characteristics)
    val highResolution = collectHighResolutionStreamSizes(characteristics)
    return CameraStreamSizes(
        normalRaw = normal.raw,
        normalYuv = normal.yuv,
        normalJpeg = normal.jpeg,
        maximumRaw = maximum.raw,
        maximumYuv = maximum.yuv,
        maximumJpeg = maximum.jpeg,
        highResolutionRaw = highResolution.raw,
        highResolutionYuv = highResolution.yuv,
        highResolutionJpeg = highResolution.jpeg
    )
}

internal fun buildNative24Status(streams: CameraStreamSizes): Native24Status {
    fun List<Size>.hasNative24() = any { megapixels(it) in 20.0..30.0 }
    return Native24Status(
        rawAvailable = streams.allRaw.hasNative24(),
        yuvAvailable = streams.allYuv.hasNative24(),
        jpegAvailable = streams.allJpeg.hasNative24()
    )
}

internal fun buildRaw50Status(streams: CameraStreamSizes): Raw50Status {
    fun maxMp(sizes: List<Size>) = sizes.maxOfOrNull(::megapixels) ?: 0.0
    val maxRawMp = maxMp(streams.allRaw)
    val maxYuvMp = maxMp(streams.allYuv)
    val maxJpegMp = maxMp(streams.allJpeg)
    val rawAvailable = streams.allRaw.any {
        megapixels(it) >= HIGH_RES_RAW_INPUT_MIN_MP
    }
    val yuvAvailable = streams.allYuv.any {
        megapixels(it) >= HIGH_RES_RAW_INPUT_MIN_MP
    }
    val jpegAvailable = streams.allJpeg.any {
        megapixels(it) >= HIGH_RES_RAW_INPUT_MIN_MP
    }
    val normalRawAvailable = streams.normalRaw.has50Mp()
    val normalYuvAvailable = streams.normalYuv.has50Mp()
    val normalJpegAvailable = streams.normalJpeg.has50Mp()
    val maximumRawAvailable = streams.maximumRaw.has50Mp()
    val maximumYuvAvailable = streams.maximumYuv.has50Mp()
    val maximumJpegAvailable = streams.maximumJpeg.has50Mp()
    val maximumResolutionPixelModeRequired =
        (maximumRawAvailable && !normalRawAvailable) ||
            (maximumYuvAvailable && !normalYuvAvailable) ||
            (maximumJpegAvailable && !normalJpegAvailable)
    val reason = if (rawAvailable) {
        "Public Camera2 exposes RAW_SENSOR >=40MP."
    } else {
        buildString {
            append("50M RAW unavailable: max public RAW is ${"%.1f".format(maxRawMp)} MP.")
            when {
                yuvAvailable && jpegAvailable ->
                    append(
                        " Public >=40MP YUV and JPEG exist, but they are processed " +
                            "streams, not RAW Fusion input."
                    )
                yuvAvailable ->
                    append(" Public >=40MP YUV exists, but it is processed, not RAW Fusion input.")
                jpegAvailable ->
                    append(" Public >=40MP JPEG exists, but it is processed, not RAW Fusion input.")
                else -> append(" No >=40MP public RAW/YUV/JPEG stream is exposed.")
            }
        }
    }
    return Raw50Status(
        rawAvailable,
        yuvAvailable,
        jpegAvailable,
        maxRawMp,
        maxYuvMp,
        maxJpegMp,
        normalRawAvailable,
        normalYuvAvailable,
        normalJpegAvailable,
        maximumRawAvailable,
        maximumYuvAvailable,
        maximumJpegAvailable,
        maximumResolutionPixelModeRequired,
        reason
    )
}

private fun List<Size>.has50Mp(): Boolean =
    any { megapixels(it) >= HIGH_RES_RAW_INPUT_MIN_MP }

private fun List<Size>.has12Mp(): Boolean =
    any { megapixels(it) in 8.0..14.5 }

fun queryCameraResolutionCapability(
    context: Context,
    cameraId: String,
    lensSlot: LensSlot
): CameraResolutionCapability =
    CameraCapabilityCache.getResolutionCapability(context, cameraId, lensSlot)

fun loadCameraResolutionCapability(
    context: Context,
    cameraId: String,
    lensSlot: LensSlot
): CameraResolutionCapability {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val characteristics = manager.getCameraCharacteristics(cameraId)
    val capabilities =
        characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
    val supportsUltraHighResolution = capabilities.contains(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
    )
    val streams = collectStreamSizes(characteristics)
    val raw50Status = buildRaw50Status(streams)
    val native24Status = buildNative24Status(streams)
    val pixelModeKeySupported = characteristics
        .getAvailableCaptureRequestKeys()
        ?.contains(CaptureRequest.SENSOR_PIXEL_MODE) == true
    val maximumResolutionPixelModeSettable =
        Build.VERSION.SDK_INT >= 31 &&
            supportsUltraHighResolution &&
            pixelModeKeySupported
    val processed50Reason = when {
        !raw50Status.rawAvailable && raw50Status.yuvAvailable && raw50Status.jpegAvailable ->
            "50MP RAW unavailable, 50MP YUV/JPEG available."
        !raw50Status.rawAvailable && raw50Status.yuvAvailable ->
            "50MP RAW unavailable, 50MP YUV available, 50MP JPEG unavailable."
        !raw50Status.rawAvailable && raw50Status.jpegAvailable ->
            "50MP RAW unavailable, 50MP JPEG available, 50MP YUV unavailable."
        raw50Status.yuvAvailable && raw50Status.jpegAvailable ->
            "50MP RAW and 50MP YUV/JPEG available."
        raw50Status.yuvAvailable ->
            "50MP RAW and 50MP YUV available; 50MP JPEG unavailable."
        raw50Status.jpegAvailable ->
            "50MP RAW and 50MP JPEG available; 50MP YUV unavailable."
        else -> "No >=40MP public YUV/JPEG stream is exposed."
    }

    return CameraResolutionCapability(
        cameraId = cameraId,
        lensSlot = lensSlot,
        supportsUltraHighResolution = supportsUltraHighResolution,
        normalRawSizes = streams.normalRaw,
        normalYuvSizes = streams.normalYuv,
        normalJpegSizes = streams.normalJpeg,
        maxRawSizes = streams.maximumRaw,
        maxYuvSizes = streams.maximumYuv,
        maxJpegSizes = streams.maximumJpeg,
        highResRawSizes = streams.highResolutionRaw,
        highResYuvSizes = streams.highResolutionYuv,
        highResJpegSizes = streams.highResolutionJpeg,
        capabilities = capabilities.toList(),
        sensorPixelModeRequestKeySupported = pixelModeKeySupported,
        physicalCameraIds = if (Build.VERSION.SDK_INT >= 28) {
            characteristics.physicalCameraIds.toList()
        } else {
            emptyList()
        },
        native24RawAvailable = native24Status.rawAvailable,
        native24YuvAvailable = native24Status.yuvAvailable,
        native24JpegAvailable = native24Status.jpegAvailable,
        highResRawInputAvailable = raw50Status.rawAvailable,
        raw50Available = raw50Status.rawAvailable,
        yuv50Available = raw50Status.yuvAvailable,
        jpeg50Available = raw50Status.jpegAvailable,
        raw12Available = streams.normalRaw.has12Mp(),
        yuv12Available = streams.normalYuv.has12Mp(),
        normalRaw50Available = raw50Status.normalRawAvailable,
        normalYuv50Available = raw50Status.normalYuvAvailable,
        normalJpeg50Available = raw50Status.normalJpegAvailable,
        maxResolutionRaw50Available = raw50Status.maximumRawAvailable,
        maxResolutionYuv50Available = raw50Status.maximumYuvAvailable,
        maxResolutionJpeg50Available = raw50Status.maximumJpegAvailable,
        maxResolutionPixelModeRequired = raw50Status.maximumResolutionPixelModeRequired,
        maximumResolutionPixelModeSettable = maximumResolutionPixelModeSettable,
        raw50Reason = raw50Status.reason,
        processed50Reason = processed50Reason,
        maxAvailableRawMp = raw50Status.maxRawMp,
        maxAvailableYuvMp = raw50Status.maxYuvMp,
        maxAvailableJpegMp = raw50Status.maxJpegMp
    )
}
