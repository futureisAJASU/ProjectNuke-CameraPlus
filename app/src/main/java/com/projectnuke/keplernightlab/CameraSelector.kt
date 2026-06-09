package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import kotlin.math.abs
import kotlin.math.max

data class CameraCandidate(
    val cameraId: String,
    val lensFacing: Int?,
    val focalLengths: List<Float>,
    val maxYuvMegapixels: Double,
    val maxRawMegapixels: Double,
    val maxHighResMegapixels: Double,
    val supportsUltraHighResolution: Boolean,
    val supportsRaw: Boolean
)

data class CameraSelection(
    val cameraId: String,
    val effectiveZoomRatio: Float,
    val useCrop: Boolean,
    val note: String
)

fun findBackCameraCandidates(context: Context): List<CameraCandidate> {
    return CameraCapabilityCache.getBackCameraCandidates(context)
}

fun loadBackCameraCandidates(context: Context): List<CameraCandidate> {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    return cameraManager.cameraIdList.mapNotNull { cameraId ->
        runCatching {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

            if (lensFacing != CameraCharacteristics.LENS_FACING_BACK) {
                return@mapNotNull null
            }

            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()
            val supportsRaw = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val supportsUltraHighResolution = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
            )
            val capability = queryCameraResolutionCapability(context, cameraId, LensSlot.MAIN_1X)

            CameraCandidate(
                cameraId = cameraId,
                lensFacing = lensFacing,
                focalLengths = characteristics
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.toList()
                    .orEmpty(),
                maxYuvMegapixels = maxMegapixels(map?.getOutputSizes(ImageFormat.YUV_420_888)),
                maxRawMegapixels = maxMegapixels(map?.getOutputSizes(ImageFormat.RAW_SENSOR)),
                maxHighResMegapixels = max(
                    capability.maxAvailableRawMp,
                    max(capability.maxAvailableYuvMp, capability.maxAvailableJpegMp)
                ),
                supportsUltraHighResolution = supportsUltraHighResolution,
                supportsRaw = supportsRaw
            )
        }.getOrNull()
    }
}

fun selectCameraForOptions(
    context: Context,
    options: SelectedCaptureOptions
): CameraSelection {
    val candidates = findBackCameraCandidates(context)

    if (candidates.isEmpty()) {
        return CameraSelection(
            cameraId = "0",
            effectiveZoomRatio = 1.0f,
            useCrop = false,
            note = "No back camera candidate found; using cameraId=0 fallback."
        )
    }

    val main = selectMainCamera(candidates)

    return when (options.lensSlot) {
        LensSlot.ULTRAWIDE -> {
            val camera = candidates.minByOrNull { it.primaryFocalLength() } ?: main
            CameraSelection(
                cameraId = camera.cameraId,
                effectiveZoomRatio = 1.0f,
                useCrop = false,
                note = "Ultrawide candidate cameraId=${camera.cameraId}."
            )
        }

        LensSlot.MAIN_1X -> {
            CameraSelection(
                cameraId = main.cameraId,
                effectiveZoomRatio = 1.0f,
                useCrop = false,
                note = "Main selected by max high-res capability: cameraId=${main.cameraId}."
            )
        }

        LensSlot.MAIN_2X -> {
            // TODO: True 2x remosaic requires high-res main sensor access and custom processing.
            CameraSelection(
                cameraId = main.cameraId,
                effectiveZoomRatio = 2.0f,
                useCrop = true,
                note = "2x uses main high-res camera crop on cameraId=${main.cameraId}. 2x 24M is fusion/SR, not native crop."
            )
        }

        LensSlot.THREE_X -> {
            when (options.threeXSourceMode) {
                ThreeXSourceMode.OPTICAL -> {
                    // TODO: 3x optical selection is heuristic until physical camera mapping is verified.
                    val tele = selectTeleCamera(candidates, main)

                    if (tele != null) {
                        CameraSelection(
                            cameraId = tele.cameraId,
                            effectiveZoomRatio = 1.0f,
                            useCrop = false,
                            note = "Optical 3x candidate cameraId=${tele.cameraId}."
                        )
                    } else {
                        CameraSelection(
                            cameraId = main.cameraId,
                            effectiveZoomRatio = 3.0f,
                            useCrop = true,
                            note = "Tele camera not found; falling back to main 3x crop. cameraId=${main.cameraId}."
                        )
                    }
                }

                ThreeXSourceMode.MAIN_CROP -> {
                    CameraSelection(
                        cameraId = main.cameraId,
                        effectiveZoomRatio = 3.0f,
                        useCrop = true,
                        note = "3x uses main high-res camera crop on cameraId=${main.cameraId}."
                    )
                }
            }
        }
    }
}

fun buildCameraSelectionDebugReport(context: Context): String {
    val candidates = findBackCameraCandidates(context)

    if (candidates.isEmpty()) {
        return "Back camera candidates: none"
    }

    return buildString {
        appendLine("Back camera candidates:")
        candidates.forEach { camera ->
            appendLine(
                "cameraId=${camera.cameraId}, " +
                    "focalLengths=${camera.focalLengths}, " +
                    "maxYuvMp=${"%.1f".format(camera.maxYuvMegapixels)}, " +
                    "maxRawMp=${"%.1f".format(camera.maxRawMegapixels)}, " +
                    "maxHighResMp=${"%.1f".format(camera.maxHighResMegapixels)}, " +
                    "ultraHighRes=${camera.supportsUltraHighResolution}, " +
                    "supportsRaw=${camera.supportsRaw}"
            )
        }
    }.trim()
}

fun buildCenterCropRegion(
    characteristics: CameraCharacteristics,
    zoomRatio: Float
): Rect? {
    return buildCenterCropRegionForPixelMode(
        characteristics = characteristics,
        zoomRatio = zoomRatio,
        useMaximumResolutionActiveArray = false
    ).region
}

data class CropRegionSelection(
    val region: Rect?,
    val activeArraySource: String
)

fun buildCenterCropRegionForPixelMode(
    characteristics: CameraCharacteristics,
    zoomRatio: Float,
    useMaximumResolutionActiveArray: Boolean
): CropRegionSelection {
    if (zoomRatio <= 1f) return CropRegionSelection(null, "NORMAL")

    val maxActiveArray = if (useMaximumResolutionActiveArray && Build.VERSION.SDK_INT >= 31) {
        characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)
    } else {
        null
    }
    val activeArray = maxActiveArray
        ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        ?: return CropRegionSelection(null, if (maxActiveArray != null) "MAXIMUM_RESOLUTION" else "NORMAL")

    val cropWidth = (activeArray.width() / zoomRatio).toInt().coerceAtLeast(1)
    val cropHeight = (activeArray.height() / zoomRatio).toInt().coerceAtLeast(1)
    val left = activeArray.left + (activeArray.width() - cropWidth) / 2
    val top = activeArray.top + (activeArray.height() - cropHeight) / 2

    return CropRegionSelection(
        region = Rect(left, top, left + cropWidth, top + cropHeight),
        activeArraySource = if (maxActiveArray != null) "MAXIMUM_RESOLUTION" else "NORMAL"
    )
}

private fun selectMainCamera(candidates: List<CameraCandidate>): CameraCandidate {
    val sortedFocalLengths = candidates
        .map { it.primaryFocalLength() }
        .sorted()
    val middleFocal = sortedFocalLengths.getOrNull(sortedFocalLengths.size / 2) ?: 0f

    return candidates.maxWithOrNull(
        compareBy<CameraCandidate> { if (it.supportsUltraHighResolution) 1 else 0 }
            .thenBy { it.maxHighResMegapixels }
            .thenBy { max(it.maxYuvMegapixels, it.maxRawMegapixels) }
            .thenBy { -abs(it.primaryFocalLength() - middleFocal) }
    ) ?: candidates.first()
}

private fun selectTeleCamera(
    candidates: List<CameraCandidate>,
    main: CameraCandidate
): CameraCandidate? {
    val mainFocal = main.primaryFocalLength()
    val longest = candidates.maxByOrNull { it.primaryFocalLength() } ?: return null

    return if (longest.cameraId != main.cameraId && longest.primaryFocalLength() >= mainFocal * 1.4f) {
        longest
    } else {
        null
    }
}

private fun CameraCandidate.primaryFocalLength(): Float {
    return focalLengths.minOrNull() ?: 0f
}

private fun maxMegapixels(sizes: Array<Size>?): Double {
    return sizes
        ?.maxOfOrNull { size -> size.width.toDouble() * size.height.toDouble() / 1_000_000.0 }
        ?: 0.0
}
