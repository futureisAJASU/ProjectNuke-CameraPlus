package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Size

private fun List<Size>.describeSizes(): String =
    if (isEmpty()) {
        "none"
    } else {
        joinToString { "${it.width}x${it.height} ${"%.1f".format(megapixels(it))}MP" }
    }

private fun StreamConfigurationMap?.sizes(format: Int): List<Size> =
    this?.getOutputSizes(format)?.toList().orEmpty()

private fun describeMatchingSizes(sizes: List<Size>): String {
    val matching = sizes.filter { size ->
        val mp = megapixels(size)
        (size.width in 3800..4200 && size.height in 2100..3200) ||
            (size.width >= 7000 && size.height >= 5000) ||
            (mp >= 40.0 && kotlin.math.abs(size.width.toDouble() / size.height - 4.0 / 3.0) < 0.08)
    }
    return matching.describeSizes()
}

private fun describeCameraCapabilityForReport(
    manager: CameraManager,
    cameraId: String,
    capability: CameraResolutionCapability
): String {
    val characteristics = manager.getCameraCharacteristics(cameraId)
    val hardwareLevel = characteristics.get(
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
    )
    val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    val maxActiveArray = if (Build.VERSION.SDK_INT >= 31) {
        characteristics.get(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION
        )
    } else {
        null
    }
    val hasMaximumResolutionMap = Build.VERSION.SDK_INT >= 31 &&
        characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
        ) != null
    val normalMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val maximumMap = if (Build.VERSION.SDK_INT >= 31) {
        characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
        )
    } else {
        null
    }
    val pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    return buildString {
        appendLine("cameraId=$cameraId")
        appendLine("lensFacing=${characteristics.get(CameraCharacteristics.LENS_FACING) ?: "unknown"}")
        appendLine("hardwareLevel=${hardwareLevel ?: "unknown"}")
        appendLine(
            "focalLengths: " +
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.toList()
                    .orEmpty()
        )
        appendLine("capabilities: ${capability.capabilities}")
        appendLine("physicalCameraIds: ${capability.physicalCameraIds}")
        appendLine(
            "Ultra high resolution: " +
                if (capability.supportsUltraHighResolution) "YES" else "NO"
        )
        appendLine("activeArray: ${activeArray ?: "none"}")
        appendLine("pixelArray: ${pixelArray ?: "none"}")
        appendLine("maximumResolutionActiveArray: ${maxActiveArray ?: "none"}")
        appendLine("sensorOrientation=${characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: "unknown"}")
        appendLine(
            "SENSOR_PIXEL_MODE request key: " +
                if (capability.sensorPixelModeRequestKeySupported) "YES" else "NO"
        )
        appendLine(
            "maximum-resolution stream map: " +
                if (hasMaximumResolutionMap) "YES" else "NO"
        )
        appendLine(
            "SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION settable: " +
                if (capability.maximumResolutionPixelModeSettable) "YES" else "NO"
        )
        appendLine("normal RAW: ${capability.normalRawSizes.describeSizes()}")
        appendLine("normal YUV: ${capability.normalYuvSizes.describeSizes()}")
        appendLine("normal JPEG: ${capability.normalJpegSizes.describeSizes()}")
        appendLine("normal PRIVATE: ${normalMap.sizes(ImageFormat.PRIVATE).describeSizes()}")
        appendLine("maximum RAW: ${capability.maxRawSizes.describeSizes()}")
        appendLine("maximum YUV: ${capability.maxYuvSizes.describeSizes()}")
        appendLine("maximum JPEG: ${capability.maxJpegSizes.describeSizes()}")
        appendLine("maximum PRIVATE: ${maximumMap.sizes(ImageFormat.PRIVATE).describeSizes()}")
        appendLine("highRes RAW: ${capability.highResRawSizes.describeSizes()}")
        appendLine("highRes YUV: ${capability.highResYuvSizes.describeSizes()}")
        appendLine("highRes JPEG: ${capability.highResJpegSizes.describeSizes()}")
        appendLine(
            "max MP RAW/YUV/JPEG: ${"%.1f".format(capability.maxAvailableRawMp)} / " +
                "${"%.1f".format(capability.maxAvailableYuvMp)} / " +
                "${"%.1f".format(capability.maxAvailableJpegMp)}"
        )
        appendLine("raw50Available: ${capability.raw50Available}")
        appendLine("yuv50Available: ${capability.yuv50Available}")
        appendLine("jpeg50Available: ${capability.jpeg50Available}")
        appendLine("raw12Available: ${capability.raw12Available}")
        appendLine("yuv12Available: ${capability.yuv12Available}")
        appendLine("normalRaw50Available: ${capability.normalRaw50Available}")
        appendLine("normalYuv50Available: ${capability.normalYuv50Available}")
        appendLine("normalJpeg50Available: ${capability.normalJpeg50Available}")
        appendLine("maxResolutionRaw50Available: ${capability.maxResolutionRaw50Available}")
        appendLine("maxResolutionYuv50Available: ${capability.maxResolutionYuv50Available}")
        appendLine("maxResolutionJpeg50Available: ${capability.maxResolutionJpeg50Available}")
        appendLine("maxResolutionPixelModeRequired: ${capability.maxResolutionPixelModeRequired}")
        appendLine("raw50Reason: ${capability.raw50Reason}")
        appendLine("processed50Reason: ${capability.processed50Reason}")
        appendLine(
            "notable normal sizes: " +
                (capability.normalRawSizes + capability.normalYuvSizes + capability.normalJpegSizes)
                    .distinct()
                    .let(::describeMatchingSizes)
        )
        appendLine(
            "notable maximum sizes: " +
                (capability.maxRawSizes + capability.maxYuvSizes + capability.maxJpegSizes)
                    .distinct()
                    .let(::describeMatchingSizes)
        )
        appendLine(
            "yuv50Reason: " +
                if (capability.yuv50Available) {
                    "Public >=40MP YUV exists; diagnostic processed stream only."
                } else {
                    "No public >=40MP YUV stream."
                }
        )
        appendLine(
            "jpeg50Reason: " +
                if (capability.jpeg50Available) {
                    "Public >=40MP JPEG exists; diagnostic processed stream only."
                } else {
                    "No public >=40MP JPEG stream."
                }
        )
        if (Build.VERSION.SDK_INT >= 28) {
            capability.physicalCameraIds.forEach { physicalId ->
                appendLine(describePhysicalCamera(manager, physicalId))
            }
        }
    }.trimEnd()
}

private fun describePhysicalCamera(manager: CameraManager, physicalId: String): String {
    val physical = runCatching {
        manager.getCameraCharacteristics(physicalId)
    }.getOrNull()
    val physicalMap = physical?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val raw = physicalMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
    val yuv = physicalMap?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
    val jpeg = physicalMap?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
    val maximumMap = if (Build.VERSION.SDK_INT >= 31) {
        physical?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
    } else {
        null
    }
    val capabilities = physical
        ?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        ?.toList()
        .orEmpty()
    val supportsUltraHighResolution = capabilities.contains(
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
    )
    val sensorPixelModeAvailable = physical
        ?.getAvailableCaptureRequestKeys()
        ?.contains(android.hardware.camera2.CaptureRequest.SENSOR_PIXEL_MODE) == true
    return buildString {
        appendLine("physicalCameraId=$physicalId")
        appendLine("  lensFacing=${physical?.get(CameraCharacteristics.LENS_FACING) ?: "unknown"}")
        appendLine("  focalLengths=${physical?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()}")
        appendLine("  activeArray=${physical?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: "none"}")
        appendLine("  pixelArray=${physical?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: "none"}")
        appendLine("  sensorOrientation=${physical?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: "unknown"}")
        appendLine("  capabilities=$capabilities")
        appendLine("  ultraHighResolutionSensor=$supportsUltraHighResolution")
        appendLine("  SENSOR_PIXEL_MODE request key=$sensorPixelModeAvailable")
        appendLine(
            "  SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION settable=" +
                (Build.VERSION.SDK_INT >= 31 &&
                    maximumMap != null &&
                    sensorPixelModeAvailable)
        )
        appendLine("  normal RAW=${raw.describeSizes()}")
        appendLine("  normal YUV=${yuv.describeSizes()}")
        appendLine("  normal JPEG=${jpeg.describeSizes()}")
        appendLine("  normal PRIVATE=${physicalMap.sizes(ImageFormat.PRIVATE).describeSizes()}")
        appendLine("  maximum RAW=${maximumMap.sizes(ImageFormat.RAW_SENSOR).describeSizes()}")
        appendLine("  maximum YUV=${maximumMap.sizes(ImageFormat.YUV_420_888).describeSizes()}")
        appendLine("  maximum JPEG=${maximumMap.sizes(ImageFormat.JPEG).describeSizes()}")
        append("  maximum PRIVATE=${maximumMap.sizes(ImageFormat.PRIVATE).describeSizes()}")
    }
}

fun buildResolutionCapabilityReport(context: Context): String {
    CameraCapabilityCache.clear()
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    return buildString {
        manager.cameraIdList.forEach { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            if (
                characteristics.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                return@forEach
            }
            val capability =
                queryCameraResolutionCapability(context, id, LensSlot.MAIN_1X)
            appendLine(describeCameraCapabilityForReport(manager, id, capability))
            appendLine()
        }
    }.trim()
}
