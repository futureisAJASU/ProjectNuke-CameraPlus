package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size

private fun List<Size>.describeSizes(): String =
    if (isEmpty()) {
        "none"
    } else {
        joinToString { "${it.width}x${it.height} ${"%.1f".format(megapixels(it))}MP" }
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
    return buildString {
        appendLine("cameraId=$cameraId")
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
        appendLine("maximumResolutionActiveArray: ${maxActiveArray ?: "none"}")
        appendLine(
            "SENSOR_PIXEL_MODE request key: " +
                if (capability.sensorPixelModeRequestKeySupported) "YES" else "NO"
        )
        appendLine(
            "maximum-resolution stream map: " +
                if (hasMaximumResolutionMap) "YES" else "NO"
        )
        appendLine("normal RAW: ${capability.normalRawSizes.describeSizes()}")
        appendLine("normal YUV: ${capability.normalYuvSizes.describeSizes()}")
        appendLine("normal JPEG: ${capability.normalJpegSizes.describeSizes()}")
        appendLine("maximum RAW: ${capability.maxRawSizes.describeSizes()}")
        appendLine("maximum YUV: ${capability.maxYuvSizes.describeSizes()}")
        appendLine("maximum JPEG: ${capability.maxJpegSizes.describeSizes()}")
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
        appendLine("raw50Reason: ${capability.raw50Reason}")
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
    return "physicalCameraId=$physicalId focalLengths=" +
        "${physical?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()} " +
        "lensFacing=${physical?.get(CameraCharacteristics.LENS_FACING) ?: "unknown"} " +
        "maxRAW/YUV/JPEG=" +
        "${"%.1f".format(raw.maxOfOrNull(::megapixels) ?: 0.0)}/" +
        "${"%.1f".format(yuv.maxOfOrNull(::megapixels) ?: 0.0)}/" +
        "${"%.1f".format(jpeg.maxOfOrNull(::megapixels) ?: 0.0)}MP"
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
