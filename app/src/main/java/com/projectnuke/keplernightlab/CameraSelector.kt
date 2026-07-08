package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class PhysicalCameraCandidate(
    val physicalCameraId: String,
    val lensFacing: Int?,
    val focalLengths: List<Float>,
    val maxYuvMegapixels: Double,
    val maxJpegMegapixels: Double,
    val maxRawMegapixels: Double
)

data class CameraCandidate(
    val cameraId: String,
    val lensFacing: Int?,
    val focalLengths: List<Float>,
    val maxYuvMegapixels: Double,
    val maxJpegMegapixels: Double,
    val maxRawMegapixels: Double,
    val maxHighResMegapixels: Double,
    val supportsUltraHighResolution: Boolean,
    val supportsRaw: Boolean,
    val isLogicalMultiCamera: Boolean,
    val capabilities: List<Int>,
    val physicalCameras: List<PhysicalCameraCandidate>
)

enum class ActualLensSource {
    MAIN_1X,
    ULTRAWIDE,
    MAIN_CROP_2X,
    MAIN_CROP_3X,
    OPTICAL_TELE_LOGICAL,
    OPTICAL_TELE_PHYSICAL,
    OPTICAL_TELE_UNAVAILABLE_FALLBACK_CROP
}

data class CameraSelection(
    val cameraId: String,
    val effectiveZoomRatio: Float,
    val useCrop: Boolean,
    val note: String,
    val requestedLensSlot: LensSlot,
    val requestedThreeXSourceMode: ThreeXSourceMode,
    val actualLensSource: ActualLensSource,
    val physicalCameraId: String? = null,
    val isOpticalTeleActuallyUsed: Boolean = false,
    val isFallback: Boolean = false,
    val diagnosticReason: String = note
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
            val isLogicalMultiCamera = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            )
            val supportsUltraHighResolution = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR
            )
            val capability = queryCameraResolutionCapability(context, cameraId, LensSlot.MAIN_1X)
            val physicalCameras = if (Build.VERSION.SDK_INT >= 28) {
                characteristics.physicalCameraIds.mapNotNull { physicalCameraId ->
                    runCatching {
                        val physical = cameraManager.getCameraCharacteristics(physicalCameraId)
                        val physicalMap = runCatching {
                            physical.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        }.getOrNull()
                        PhysicalCameraCandidate(
                            physicalCameraId = physicalCameraId,
                            lensFacing = runCatching {
                                physical.get(CameraCharacteristics.LENS_FACING)
                            }.getOrNull(),
                            focalLengths = runCatching {
                                physical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            }.getOrNull()
                                ?.toList()
                                .orEmpty(),
                            maxYuvMegapixels = safeMaxMegapixels(
                                physicalMap,
                                ImageFormat.YUV_420_888
                            ),
                            maxJpegMegapixels = safeMaxMegapixels(
                                physicalMap,
                                ImageFormat.JPEG
                            ),
                            maxRawMegapixels = safeMaxMegapixels(
                                physicalMap,
                                ImageFormat.RAW_SENSOR
                            )
                        )
                    }.onFailure { error ->
                        Log.w(
                            "KeplerPhysicalTele",
                            "physicalId=$physicalCameraId candidateLoad=failed " +
                                "exception=${error.javaClass.simpleName}:${error.message}"
                        )
                    }.getOrNull()
                }
            } else {
                emptyList()
            }

            CameraCandidate(
                cameraId = cameraId,
                lensFacing = lensFacing,
                focalLengths = characteristics
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.toList()
                    .orEmpty(),
                maxYuvMegapixels = maxMegapixels(map?.getOutputSizes(ImageFormat.YUV_420_888)),
                maxJpegMegapixels = maxMegapixels(map?.getOutputSizes(ImageFormat.JPEG)),
                maxRawMegapixels = maxMegapixels(map?.getOutputSizes(ImageFormat.RAW_SENSOR)),
                maxHighResMegapixels = max(
                    capability.maxAvailableRawMp,
                    max(capability.maxAvailableYuvMp, capability.maxAvailableJpegMp)
                ),
                supportsUltraHighResolution = supportsUltraHighResolution,
                supportsRaw = supportsRaw,
                isLogicalMultiCamera = isLogicalMultiCamera,
                capabilities = capabilities.toList(),
                physicalCameras = physicalCameras
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
            note = "No back camera candidate found; using cameraId=0 fallback.",
            requestedLensSlot = options.lensSlot,
            requestedThreeXSourceMode = options.threeXSourceMode,
            actualLensSource = ActualLensSource.MAIN_1X,
            isFallback = true
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
                note = "Ultrawide candidate cameraId=${camera.cameraId}.",
                requestedLensSlot = options.lensSlot,
                requestedThreeXSourceMode = options.threeXSourceMode,
                actualLensSource = ActualLensSource.ULTRAWIDE,
                isFallback = camera.cameraId == main.cameraId,
                diagnosticReason = if (camera.cameraId == main.cameraId) {
                    "Separate public ultrawide camera was not found; main logical camera selected."
                } else {
                    "Separate public ultrawide logical camera selected."
                }
            )
        }

        LensSlot.MAIN_1X -> {
            CameraSelection(
                cameraId = main.cameraId,
                effectiveZoomRatio = 1.0f,
                useCrop = false,
                note = "Main selected by max high-res capability: cameraId=${main.cameraId}.",
                requestedLensSlot = options.lensSlot,
                requestedThreeXSourceMode = options.threeXSourceMode,
                actualLensSource = ActualLensSource.MAIN_1X
            )
        }

        LensSlot.MAIN_2X -> {
            CameraSelection(
                cameraId = main.cameraId,
                effectiveZoomRatio = 2.0f,
                useCrop = true,
                note = "2x uses main high-res camera crop on cameraId=${main.cameraId}. 2x 24M is fusion/SR, not native crop.",
                requestedLensSlot = options.lensSlot,
                requestedThreeXSourceMode = options.threeXSourceMode,
                actualLensSource = ActualLensSource.MAIN_CROP_2X
            )
        }

        LensSlot.THREE_X -> {
            when (options.threeXSourceMode) {
                ThreeXSourceMode.OPTICAL -> {
                    val tele = selectTeleCamera(candidates, main)

                    if (tele != null) {
                        CameraSelection(
                            cameraId = tele.cameraId,
                            effectiveZoomRatio = 1.0f,
                            useCrop = false,
                            note = "Optical tele logical camera selected: cameraId=${tele.cameraId}.",
                            requestedLensSlot = options.lensSlot,
                            requestedThreeXSourceMode = options.threeXSourceMode,
                            actualLensSource = ActualLensSource.OPTICAL_TELE_LOGICAL,
                            isOpticalTeleActuallyUsed = true,
                            diagnosticReason = "Separate public logical tele camera selected."
                        )
                    } else {
                        val physicalTele = selectPhysicalTeleCamera(main)
                        val selection = CameraSelection(
                            cameraId = main.cameraId,
                            effectiveZoomRatio = if (physicalTele != null) 1.0f else 3.0f,
                            useCrop = physicalTele == null,
                            note = if (physicalTele != null) {
                                "Physical tele candidate ${physicalTele.physicalCameraId} selected for experimental preview and capture output routing."
                            } else {
                                "Optical tele unavailable; using main 3x crop."
                            },
                            requestedLensSlot = options.lensSlot,
                            requestedThreeXSourceMode = options.threeXSourceMode,
                            actualLensSource = if (physicalTele != null) {
                                ActualLensSource.OPTICAL_TELE_PHYSICAL
                            } else {
                                ActualLensSource.OPTICAL_TELE_UNAVAILABLE_FALLBACK_CROP
                            },
                            physicalCameraId = physicalTele?.physicalCameraId,
                            isOpticalTeleActuallyUsed = physicalTele != null,
                            isFallback = physicalTele == null,
                            diagnosticReason = if (physicalTele != null) {
                                "Physical tele candidate routed for preview and capture inside logical cameraId=${main.cameraId}; failed physical sessions fall back to main 3x crop."
                            } else {
                                "No separate public logical tele camera or usable physical tele metadata was exposed."
                            }
                        )
                        Log.d(
                            "Kepler3xSelection",
                            "3x optical selection cameraId=${selection.cameraId} physical=${selection.physicalCameraId} " +
                                "mainFocal=${main.primaryFocalLength()} physicalFocal=${physicalTele?.primaryFocalLength()} " +
                                "candidates=${candidates.joinToString { candidate -> candidate.cameraId + ':' + candidate.focalLengths }}"
                        )
                        selection
                    }
                }

                ThreeXSourceMode.MAIN_CROP -> {
                    CameraSelection(
                        cameraId = main.cameraId,
                        effectiveZoomRatio = 3.0f,
                        useCrop = true,
                        note = "3x uses main high-res camera crop on cameraId=${main.cameraId}.",
                        requestedLensSlot = options.lensSlot,
                        requestedThreeXSourceMode = options.threeXSourceMode,
                        actualLensSource = ActualLensSource.MAIN_CROP_3X
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
                    "primaryFocal=${camera.primaryFocalLength()}, " +
                    "teleFocal=${camera.teleFocalLength()}, " +
                    "maxYuvMp=${"%.1f".format(camera.maxYuvMegapixels)}, " +
                    "maxJpegMp=${"%.1f".format(camera.maxJpegMegapixels)}, " +
                    "maxRawMp=${"%.1f".format(camera.maxRawMegapixels)}, " +
                    "maxHighResMp=${"%.1f".format(camera.maxHighResMegapixels)}, " +
                    "ultraHighRes=${camera.supportsUltraHighResolution}, " +
                    "supportsRaw=${camera.supportsRaw}, " +
                    "logicalMultiCamera=${camera.isLogicalMultiCamera}, " +
                    "capabilities=${camera.capabilities}, " +
                    "physicalCameraIds=${camera.physicalCameras.map { it.physicalCameraId }}"
            )
            camera.physicalCameras.forEach { physical ->
                appendLine(
                    "  physicalId=${physical.physicalCameraId}, facing=${physical.lensFacing}, " +
                        "focalLengths=${physical.focalLengths}, " +
                        "primaryFocal=${physical.primaryFocalLength()}, " +
                        "maxRAW/YUV/JPEG=${"%.1f".format(physical.maxRawMegapixels)}/" +
                        "${"%.1f".format(physical.maxYuvMegapixels)}/" +
                        "${"%.1f".format(physical.maxJpegMegapixels)}MP"
                )
            }
        }
    }.trim()
}

fun buildFullCameraDumpReport(context: Context): String {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    return buildString {
        cameraManager.cameraIdList.forEach { cameraId ->
            runCatching {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val capabilities = characteristics
                    .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.toList()
                    .orEmpty()
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val physicalIds = if (Build.VERSION.SDK_INT >= 28) {
                    characteristics.physicalCameraIds.toList()
                } else {
                    emptyList()
                }
                val zoomRatioRange = if (Build.VERSION.SDK_INT >= 30) {
                    characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                } else {
                    null
                }

                val logicalLine = "cameraId=$cameraId " +
                    "lensFacing=${characteristics.get(CameraCharacteristics.LENS_FACING)} " +
                    "focalLengths=${characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()} " +
                    "capabilities=$capabilities " +
                    "isLogicalMultiCamera=${capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)} " +
                    "physicalCameraIds=$physicalIds " +
                    "maxYUV=${maxSize(map?.getOutputSizes(ImageFormat.YUV_420_888))} " +
                    "maxJPEG=${maxSize(map?.getOutputSizes(ImageFormat.JPEG))} " +
                    "maxRAW=${maxSize(map?.getOutputSizes(ImageFormat.RAW_SENSOR))} " +
                    "zoomRatioRange=$zoomRatioRange"
                appendLine(logicalLine)
                physicalIds.forEach { physicalId ->
                    appendLine(describePhysicalCamera(cameraManager, physicalId))
                }
            }.onFailure { error ->
                appendLine(
                    "cameraId=$cameraId skipped=true " +
                        "failure=${error.javaClass.simpleName}:${error.message}"
                )
            }
        }
    }.trim()
}

private fun describePhysicalCamera(
    cameraManager: CameraManager,
    physicalId: String
): String {
    return runCatching {
        val characteristics = cameraManager.getCameraCharacteristics(physicalId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val capabilities = characteristics
            .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toList()
        val zoomRatioRange = if (Build.VERSION.SDK_INT >= 30) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }

        "  physicalId=$physicalId getCharacteristics=success " +
            "lensFacing=${characteristics.get(CameraCharacteristics.LENS_FACING)} " +
            "focalLengths=${characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()} " +
            "activeArray=${characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)} " +
            "pixelArray=${characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)} " +
            "maxYUV=${maxSize(map?.getOutputSizes(ImageFormat.YUV_420_888))} " +
            "maxJPEG=${maxSize(map?.getOutputSizes(ImageFormat.JPEG))} " +
            "maxRAW=${maxSize(map?.getOutputSizes(ImageFormat.RAW_SENSOR))} " +
            "capabilities=${capabilities ?: "unavailable"} " +
            "zoomRatioRange=$zoomRatioRange streamConfigurationMapExists=${map != null}"
    }.getOrElse { error ->
        "  physicalId=$physicalId getCharacteristics=failed " +
            "exception=${error.javaClass.simpleName}:${error.message}"
    }
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

    val cropWidth = (activeArray.width() / zoomRatio).roundToInt().coerceAtLeast(1)
    val cropHeight = (activeArray.height() / zoomRatio).roundToInt().coerceAtLeast(1)
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

private fun selectPhysicalTeleCamera(main: CameraCandidate): PhysicalCameraCandidate? {
    val mainFocal = main.primaryFocalLength()
    val threshold = mainFocal * 1.25f
    Log.i(
        "KeplerPhysicalTele",
        "mainCameraId=${main.cameraId} physicalCandidates=" +
            main.physicalCameras.map {
                "${it.physicalCameraId}:${it.focalLengths}:" +
                    "YUV=${it.maxYuvMegapixels},JPEG=${it.maxJpegMegapixels},RAW=${it.maxRawMegapixels}"
            } +
            " mainFocal=$mainFocal threshold=$threshold"
    )
    val accepted = main.physicalCameras.filter { physical ->
        val physicalFocal = physical.primaryFocalLength()
        val isAccepted = physicalFocal >= threshold
        Log.i(
            "KeplerPhysicalTele",
            "mainCameraId=${main.cameraId} mainFocal=$mainFocal threshold=$threshold " +
                "physicalId=${physical.physicalCameraId} focalLengths=${physical.focalLengths} " +
                "comparison=$physicalFocal>=$threshold accepted=$isAccepted " +
                "reason=${if (isAccepted) "focal meets tele threshold" else "focal below tele threshold"} " +
                "maxYuvMp=${physical.maxYuvMegapixels} " +
                "maxJpegMp=${physical.maxJpegMegapixels} maxRawMp=${physical.maxRawMegapixels}"
        )
        isAccepted
    }
    val selected = accepted.maxByOrNull { it.primaryFocalLength() }
    Log.i(
        "KeplerPhysicalTele",
        "mainCameraId=${main.cameraId} threshold=$threshold candidates=${main.physicalCameras.size} " +
            "accepted=${accepted.map { it.physicalCameraId }} selected=${selected?.physicalCameraId}"
    )
    return selected
}

private fun CameraCandidate.primaryFocalLength(): Float {
    return focalLengths.filter { it > 0f }.minOrNull() ?: 0f
}

private fun CameraCandidate.teleFocalLength(): Float {
    return focalLengths.filter { it > 0f }.maxOrNull() ?: 0f
}

private fun PhysicalCameraCandidate.primaryFocalLength(): Float {
    return focalLengths.filter { it > 0f }.maxOrNull() ?: 0f
}

private fun maxMegapixels(sizes: Array<Size>?): Double {
    return sizes
        ?.maxOfOrNull { size -> size.width.toDouble() * size.height.toDouble() / 1_000_000.0 }
        ?: 0.0
}

private fun safeMaxMegapixels(
    map: android.hardware.camera2.params.StreamConfigurationMap?,
    format: Int
): Double {
    return runCatching { maxMegapixels(map?.getOutputSizes(format)) }.getOrDefault(0.0)
}

private fun maxSize(sizes: Array<Size>?): String {
    val size = sizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        ?: return "unavailable"
    val megapixels = size.width.toDouble() * size.height.toDouble() / 1_000_000.0
    return "${size.width}x${size.height}(${"%.1f".format(megapixels)}MP)"
}

fun CameraSelection.finalZoomRouteName(): String = when (actualLensSource) {
    ActualLensSource.OPTICAL_TELE_LOGICAL,
    ActualLensSource.OPTICAL_TELE_PHYSICAL -> "OPTICAL"
    ActualLensSource.MAIN_CROP_3X,
    ActualLensSource.OPTICAL_TELE_UNAVAILABLE_FALLBACK_CROP -> "MAIN_CROP"
    else -> requestedLensSlot.name
}

fun CameraSelection.routeFallbackReason(): String? =
    if (actualLensSource == ActualLensSource.OPTICAL_TELE_UNAVAILABLE_FALLBACK_CROP) {
        diagnosticReason
    } else {
        null
    }
