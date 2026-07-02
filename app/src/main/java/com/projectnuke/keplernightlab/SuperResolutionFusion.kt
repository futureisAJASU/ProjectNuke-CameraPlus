package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val SUPER_RES_PIPELINE = "SUPER_RESOLUTION_FUSION"
private const val SUPER_RES_JOB_FILE = "job.json"
private const val ALIGNMENT_PROXY_MAX_WIDTH = 512
private const val ALIGNMENT_SEARCH_RADIUS = 24
private const val FUSION_TILE_WIDTH = 512
private const val FUSION_TILE_HEIGHT = 512
private const val BILINEAR_HALO_RADIUS = 2
private const val OUTLIER_LUMA_THRESHOLD = 35f
private const val ALIGNMENT_SCORE_LIMIT = 0.16f
private const val MIN_FUSION_FRAMES = 2
private const val JPEG_QUALITY = 95

enum class SuperResolutionSourceMode {
    BINNED_12MP_YUV,
    BINNED_12MP_RGB,
    FULLRES_50MP_RAW,
    FULLRES_50MP_RGB
}

data class SuperResolutionTargetPolicy(
    val sourceMode: SuperResolutionSourceMode,
    val defaultTargetMegapixels: Double,
    val maxSafeTargetMegapixels: Double,
    val maxExperimentalTargetMegapixels: Double,
    val maxLinearScale: Double
)

fun superResolutionTargetPolicy(
    sourceMode: SuperResolutionSourceMode
): SuperResolutionTargetPolicy = when (sourceMode) {
    SuperResolutionSourceMode.BINNED_12MP_YUV,
    SuperResolutionSourceMode.BINNED_12MP_RGB -> SuperResolutionTargetPolicy(
        sourceMode = sourceMode,
        defaultTargetMegapixels = 24.0,
        maxSafeTargetMegapixels = 24.0,
        maxExperimentalTargetMegapixels = 48.0,
        maxLinearScale = 2.0
    )

    SuperResolutionSourceMode.FULLRES_50MP_RAW,
    SuperResolutionSourceMode.FULLRES_50MP_RGB -> SuperResolutionTargetPolicy(
        sourceMode = sourceMode,
        defaultTargetMegapixels = 50.0,
        maxSafeTargetMegapixels = 75.0,
        maxExperimentalTargetMegapixels = 100.0,
        maxLinearScale = 1.45
    )
}

data class SuperResolutionFusionRequest(
    val context: Context,
    val inputFrameFiles: List<File>,
    val outputDir: File,
    val sourceMode: SuperResolutionSourceMode = SuperResolutionSourceMode.BINNED_12MP_YUV,
    val targetPolicy: SuperResolutionTargetPolicy = superResolutionTargetPolicy(sourceMode),
    val targetMegapixels: Double = targetPolicy.defaultTargetMegapixels,
    val maxFrames: Int = 6,
    val tileSinkFactory: ((File) -> SuperResolutionTileSink)? = null,
    val status: (String) -> Unit
)

data class SuperResolutionFusionResult(
    val outputFile: File?,
    val outputWidth: Int,
    val outputHeight: Int,
    val inputFrameCount: Int,
    val usedFrameCount: Int,
    val fallbackUsed: Boolean,
    val estimatedShifts: List<FrameShift>,
    val sourceMegapixels: Double,
    val targetMegapixels: Double,
    val actualOutputMegapixels: Double,
    val experimentalTarget: Boolean,
    val rawInputUsed: Boolean,
    val message: String
)

data class FrameShift(
    val index: Int,
    val dx: Float,
    val dy: Float,
    val score: Float,
    val accepted: Boolean
)

interface SuperResolutionTileSink {
    fun begin(width: Int, height: Int)
    fun writeTile(x: Int, y: Int, width: Int, height: Int, pixels: IntArray)
    fun finish(): File
}

class BitmapTileSink(
    private val outputFile: File,
    private val quality: Int = JPEG_QUALITY
) : SuperResolutionTileSink {
    private var bitmap: Bitmap? = null

    override fun begin(width: Int, height: Int) {
        check(bitmap == null) { "Tile sink already started." }
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    override fun writeTile(x: Int, y: Int, width: Int, height: Int, pixels: IntArray) {
        require(pixels.size >= width * height) { "Tile pixel buffer is too small." }
        bitmap?.setPixels(pixels, 0, width, x, y, width, height)
            ?: error("Tile sink not started.")
    }

    override fun finish(): File {
        val output = bitmap ?: error("Tile sink not started.")
        return try {
            saveJpeg(output, outputFile, quality)
            outputFile
        } finally {
            output.recycle()
            bitmap = null
        }
    }

    internal fun abort() {
        bitmap?.recycle()
        bitmap = null
    }
}

private data class LumaFrame(
    val index: Int,
    val file: File,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val proxyWidth: Int,
    val proxyHeight: Int,
    val luma: ByteArray,
    val sharpness: Double
)

private data class AlignmentEstimate(
    val dx: Float,
    val dy: Float,
    val score: Float
)

private data class DecodedRegion(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val pixels: IntArray
)

fun runSuperResolutionFusion(
    request: SuperResolutionFusionRequest
): SuperResolutionFusionResult {
    require(request.targetPolicy.sourceMode == request.sourceMode) {
        "Target policy sourceMode must match request sourceMode."
    }
    val inputFiles = request.inputFrameFiles
        .asSequence()
        .filter { it.isFile && it.length() > 0L }
        .take(request.maxFrames.coerceAtLeast(1))
        .toList()
    request.outputDir.mkdirs()

    if (inputFiles.isEmpty()) {
        return failedSuperResolutionResult(
            request = request,
            inputFrameCount = 0,
            message = "No readable source frames."
        )
    }
    if (request.sourceMode == SuperResolutionSourceMode.FULLRES_50MP_RAW) {
        return failedSuperResolutionResult(
            request = request,
            inputFrameCount = inputFiles.size,
            message = "FULLRES_50MP_RAW decoder is not implemented yet."
        )
    }

    var shifts = emptyList<FrameShift>()
    return try {
        val statusLabel = superResolutionStatusLabel(request)
        request.status("$statusLabel: aligning frames...")
        val analyzedFrames = analyzeFrames(inputFiles)
        if (analyzedFrames.isEmpty()) {
            return failedSuperResolutionResult(
                request = request,
                inputFrameCount = inputFiles.size,
                message = "Could not decode source frames."
            )
        }

        val reference = chooseFirstSharpFrame(analyzedFrames)
        val sourceMegapixels = megapixels(reference.sourceWidth, reference.sourceHeight)
        val resolvedTargetMegapixels = resolveTargetMegapixels(request, sourceMegapixels)
        shifts = estimateFrameShifts(analyzedFrames, reference)
        val acceptedFrames = analyzedFrames.filter { frame ->
            shifts.firstOrNull { it.index == frame.index }?.accepted == true
        }
        val dimensions = calculateTargetDimensions(
            reference.sourceWidth,
            reference.sourceHeight,
            resolvedTargetMegapixels,
            request.targetPolicy
        )
        val usesBitmapSink = request.tileSinkFactory == null
        if (
            usesBitmapSink &&
            (
                resolvedTargetMegapixels > request.targetPolicy.maxSafeTargetMegapixels ||
                    !canAllocateOutputBitmap(dimensions.first, dimensions.second)
                )
        ) {
            return failedSuperResolutionResult(
                request = request,
                inputFrameCount = inputFiles.size,
                message = "Target requires a streaming tile sink; BitmapTileSink is limited to safe targets.",
                shifts = shifts
            )
        }

        if (acceptedFrames.size < MIN_FUSION_FRAMES) {
            return runSingleFrameFallback(
                request = request,
                reference = reference,
                targetWidth = dimensions.first,
                targetHeight = dimensions.second,
                shifts = shifts,
                sourceMegapixels = sourceMegapixels,
                targetMegapixels = resolvedTargetMegapixels,
                reason = "Fewer than two frames passed alignment."
            )
        }

        val requiredBytes = estimateFusionWorkingBytes(
            outputWidth = dimensions.first,
            outputHeight = dimensions.second,
            includesOutputBitmap = usesBitmapSink
        )
        if (availableHeapBytes() < requiredBytes) {
            return runSingleFrameFallback(
                request = request,
                reference = reference,
                targetWidth = dimensions.first,
                targetHeight = dimensions.second,
                shifts = shifts,
                sourceMegapixels = sourceMegapixels,
                targetMegapixels = resolvedTargetMegapixels,
                reason = "Memory guard selected single-frame fallback."
            )
        }

        request.status("$statusLabel: accumulating detail...")
        val outputFile = File(
            request.outputDir,
            superResolutionOutputFileName(resolvedTargetMegapixels)
        )
        val tileSink = request.tileSinkFactory?.invoke(outputFile) ?: BitmapTileSink(outputFile)
        request.status("$statusLabel: writing output...")
        val writtenFile = fuseFramesTiled(
            frames = acceptedFrames,
            shifts = shifts,
            reference = reference,
            outputWidth = dimensions.first,
            outputHeight = dimensions.second,
            sink = tileSink
        )
        val actualOutputMegapixels = megapixels(dimensions.first, dimensions.second)
        val result = SuperResolutionFusionResult(
            outputFile = writtenFile,
            outputWidth = dimensions.first,
            outputHeight = dimensions.second,
            inputFrameCount = inputFiles.size,
            usedFrameCount = acceptedFrames.size,
            fallbackUsed = false,
            estimatedShifts = shifts,
            sourceMegapixels = sourceMegapixels,
            targetMegapixels = resolvedTargetMegapixels,
            actualOutputMegapixels = actualOutputMegapixels,
            experimentalTarget =
                resolvedTargetMegapixels > request.targetPolicy.maxSafeTargetMegapixels,
            rawInputUsed = request.sourceMode == SuperResolutionSourceMode.FULLRES_50MP_RAW,
            message = "Multi-frame tiled super-resolution completed."
        )
        writeSuperResolutionJob(request, result, "COMPLETE", null)
        result
    } catch (oom: OutOfMemoryError) {
        failedSuperResolutionResult(
            request = request,
            inputFrameCount = inputFiles.size,
            message = "Out of memory; fusion stopped without attempting recovery.",
            shifts = shifts
        )
    } catch (error: Exception) {
        failedSuperResolutionResult(
            request = request,
            inputFrameCount = inputFiles.size,
            message = "${error.javaClass.simpleName}: ${error.message}",
            shifts = shifts
        )
    }
}

fun captureProcessExportSuperResolutionFusion(
    context: Context,
    cameraId: String,
    frameCount: Int,
    finalOutputFormat: FinalOutputFormat,
    zoomRatio: Float,
    requestedUiZoomRatio: Float,
    physicalCameraId: String? = null,
    focusAeState: FocusAeState,
    frameCountMode: FrameCountMode,
    autoMinFrames: Int,
    autoMaxFrames: Int,
    manualFrames: Int,
    framePlanReason: String,
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    fun post(message: String) = mainHandler.post { onStatus(message) }
    val captureFrames = frameCount.coerceIn(MIN_FUSION_FRAMES, 6)

    post("24M Fusion: capturing 12MP burst...")
    captureYuvBurstColorWithMotion(
        context = context,
        cameraId = cameraId,
        frameCount = captureFrames,
        resolutionMode = CaptureResolutionMode.MP12,
        zoomRatio = zoomRatio,
        requestedUiZoomRatio = requestedUiZoomRatio,
        physicalCameraId = physicalCameraId,
        focusAeState = focusAeState,
        frameCountMode = frameCountMode,
        autoMinFrames = autoMinFrames,
        autoMaxFrames = autoMaxFrames,
        manualFrames = manualFrames,
        framePlanReason = framePlanReason,
        onComplete = { sourceJobDir ->
            val workerThread = HandlerThread("KeplerSuperResolutionThread").apply { start() }
            Handler(workerThread.looper).post {
                try {
                    val sourceFrames = readColorBurstFrameFiles(sourceJobDir)
                    val outputDir = createSuperResolutionJobDirectory(context)
                    val result = runSuperResolutionFusion(
                        SuperResolutionFusionRequest(
                            context = context,
                            inputFrameFiles = sourceFrames,
                            outputDir = outputDir,
                            sourceMode = SuperResolutionSourceMode.BINNED_12MP_YUV,
                            maxFrames = captureFrames,
                            status = { post(it) }
                        )
                    )
                    val outputFile = result.outputFile
                    if (outputFile == null || !outputFile.exists()) {
                        post("PIPELINE_FAILED: 24M Fusion failed. ${result.message}")
                        return@post
                    }

                    val bitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
                        ?: error("Could not decode 24M Fusion output.")
                    val requestedFormat = requestedOutputFormatForSetting(finalOutputFormat)
                    val displayName = "Kepler_SR_${
                        megapixelLabel(result.targetMegapixels)
                    }MP_${
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    }"
                    val export = try {
                        exportNightFusionBitmapToGallery(
                            context = context,
                            bitmap = bitmap,
                            displayNameBase = displayName,
                            requestedFormat = requestedFormat
                        )
                    } finally {
                        bitmap.recycle()
                    }
                    if (!export.success || export.uriString.isNullOrBlank()) {
                        updateExportFailure(
                            jobDir = outputDir,
                            error = export.errorMessage ?: "Unknown export failure",
                            finalOutputFormat = finalOutputFormat,
                            rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar
                        )
                        post("PIPELINE_FAILED: 24M Fusion export failed. ${export.errorMessage}")
                        return@post
                    }

                    val verified = verifyGalleryExport(context, export.uriString)
                    updateExportMetadata(
                        jobDir = outputDir,
                        export = export,
                        verified = verified,
                        finalOutputFormat = finalOutputFormat,
                        rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar
                    )
                    if (!verified) {
                        updateExportFailure(
                            jobDir = outputDir,
                            error = "Export verification failed",
                            finalOutputFormat = finalOutputFormat,
                            rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar
                        )
                        post("PIPELINE_FAILED: 24M Fusion export verification failed.")
                        return@post
                    }

                    post(
                        "PIPELINE_COMPLETE: 24M Fusion complete " +
                            "${result.outputWidth}x${result.outputHeight}, " +
                            "used ${result.usedFrameCount}/${result.inputFrameCount} frames, " +
                            "fallback=${result.fallbackUsed}."
                    )
                } catch (error: Exception) {
                    post(
                        "PIPELINE_FAILED: 24M Fusion failed. " +
                            "${error.javaClass.simpleName}: ${error.message}"
                    )
                } finally {
                    workerThread.quitSafely()
                }
            }
        },
        onError = { error ->
            post("PIPELINE_FAILED: 24M Fusion capture failed. $error")
        },
        onStatus = { message -> post(message) }
    )
}

private fun analyzeFrames(files: List<File>): List<LumaFrame> {
    val bounds = files.mapIndexedNotNull { index, file ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            Triple(index, options.outWidth, options.outHeight)
        } else {
            null
        }
    }
    val first = bounds.firstOrNull() ?: return emptyList()
    val proxyWidth = minOf(ALIGNMENT_PROXY_MAX_WIDTH, first.second)
    val proxyHeight = max(1, (first.third * (proxyWidth.toDouble() / first.second)).roundToInt())

    return bounds.mapNotNull { (index, width, height) ->
        if (width != first.second || height != first.third) return@mapNotNull null
        decodeLumaFrame(
            index = index,
            file = files[index],
            sourceWidth = width,
            sourceHeight = height,
            proxyWidth = proxyWidth,
            proxyHeight = proxyHeight
        )
    }
}

private fun decodeLumaFrame(
    index: Int,
    file: File,
    sourceWidth: Int,
    sourceHeight: Int,
    proxyWidth: Int,
    proxyHeight: Int
): LumaFrame? {
    var sampleSize = 1
    while (sourceWidth / (sampleSize * 2) >= proxyWidth) sampleSize *= 2
    val decoded = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    ) ?: return null
    val proxy = if (decoded.width == proxyWidth && decoded.height == proxyHeight) {
        decoded
    } else {
        Bitmap.createScaledBitmap(decoded, proxyWidth, proxyHeight, true).also {
            decoded.recycle()
        }
    }
    return try {
        val pixels = IntArray(proxyWidth * proxyHeight)
        proxy.getPixels(pixels, 0, proxyWidth, 0, 0, proxyWidth, proxyHeight)
        val luma = ByteArray(pixels.size)
        pixels.forEachIndexed { pixelIndex, color ->
            luma[pixelIndex] = rgbLuma(
                color shr 16 and 0xff,
                color shr 8 and 0xff,
                color and 0xff
            ).roundToInt().coerceIn(0, 255).toByte()
        }
        LumaFrame(
            index = index,
            file = file,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            proxyWidth = proxyWidth,
            proxyHeight = proxyHeight,
            luma = luma,
            sharpness = calculateSharpness(luma, proxyWidth, proxyHeight)
        )
    } finally {
        proxy.recycle()
    }
}

private fun calculateSharpness(luma: ByteArray, width: Int, height: Int): Double {
    var total = 0L
    var samples = 0
    for (y in 1 until height - 1 step 2) {
        val row = y * width
        for (x in 1 until width - 1 step 2) {
            val horizontal = abs(unsigned(luma[row + x + 1]) - unsigned(luma[row + x - 1]))
            val vertical = abs(unsigned(luma[row + width + x]) - unsigned(luma[row - width + x]))
            total += horizontal + vertical
            samples++
        }
    }
    return if (samples == 0) 0.0 else total.toDouble() / samples
}

private fun chooseFirstSharpFrame(frames: List<LumaFrame>): LumaFrame {
    val threshold = (frames.maxOfOrNull { it.sharpness } ?: 0.0) * 0.8
    return frames.firstOrNull { it.sharpness >= threshold } ?: frames.first()
}

private fun estimateFrameShifts(
    frames: List<LumaFrame>,
    reference: LumaFrame
): List<FrameShift> {
    val proxyToSourceX = reference.sourceWidth.toFloat() / reference.proxyWidth
    val proxyToSourceY = reference.sourceHeight.toFloat() / reference.proxyHeight
    return frames.map { frame ->
        if (frame.index == reference.index) {
            FrameShift(frame.index, 0f, 0f, 0f, true)
        } else {
            val estimate = estimateTranslation(reference, frame)
            val dx = estimate.dx * proxyToSourceX
            val dy = estimate.dy * proxyToSourceY
            val maxSourceShift = max(reference.sourceWidth, reference.sourceHeight) * 0.07f
            FrameShift(
                index = frame.index,
                dx = dx,
                dy = dy,
                score = estimate.score,
                accepted = estimate.score <= ALIGNMENT_SCORE_LIMIT &&
                    abs(dx) <= maxSourceShift &&
                    abs(dy) <= maxSourceShift
            )
        }
    }
}

private fun estimateTranslation(reference: LumaFrame, frame: LumaFrame): AlignmentEstimate {
    var bestDx = 0
    var bestDy = 0
    var bestScore = Float.MAX_VALUE
    for (dy in -ALIGNMENT_SEARCH_RADIUS..ALIGNMENT_SEARCH_RADIUS step 2) {
        for (dx in -ALIGNMENT_SEARCH_RADIUS..ALIGNMENT_SEARCH_RADIUS step 2) {
            val score = alignmentSad(reference, frame, dx, dy, 4)
            if (score < bestScore) {
                bestScore = score
                bestDx = dx
                bestDy = dy
            }
        }
    }
    for (dy in bestDy - 2..bestDy + 2) {
        for (dx in bestDx - 2..bestDx + 2) {
            val score = alignmentSad(reference, frame, dx, dy, 2)
            if (score < bestScore) {
                bestScore = score
                bestDx = dx
                bestDy = dy
            }
        }
    }

    val center = alignmentSad(reference, frame, bestDx, bestDy, 2)
    val left = alignmentSad(reference, frame, bestDx - 1, bestDy, 2)
    val right = alignmentSad(reference, frame, bestDx + 1, bestDy, 2)
    val up = alignmentSad(reference, frame, bestDx, bestDy - 1, 2)
    val down = alignmentSad(reference, frame, bestDx, bestDy + 1, 2)
    return AlignmentEstimate(
        dx = bestDx + parabolicOffset(left, center, right),
        dy = bestDy + parabolicOffset(up, center, down),
        score = center
    )
}

private fun alignmentSad(
    reference: LumaFrame,
    frame: LumaFrame,
    dx: Int,
    dy: Int,
    stride: Int
): Float {
    val width = reference.proxyWidth
    val height = reference.proxyHeight
    val marginX = width / 5
    val marginY = height / 5
    val startX = max(marginX, marginX - dx)
    val endX = minOf(width - marginX, width - marginX - dx)
    val startY = max(marginY, marginY - dy)
    val endY = minOf(height - marginY, height - marginY - dy)
    if (startX >= endX || startY >= endY) return Float.MAX_VALUE

    var sum = 0L
    var count = 0
    for (y in startY until endY step stride) {
        val referenceRow = y * width
        val frameRow = (y + dy) * width
        for (x in startX until endX step stride) {
            sum += abs(
                unsigned(reference.luma[referenceRow + x]) -
                    unsigned(frame.luma[frameRow + x + dx])
            )
            count++
        }
    }
    return if (count == 0) Float.MAX_VALUE else sum.toFloat() / (count * 255f)
}

private fun parabolicOffset(negative: Float, center: Float, positive: Float): Float {
    val denominator = negative - 2f * center + positive
    if (!denominator.isFinite() || abs(denominator) < 0.000001f) return 0f
    return (0.5f * (negative - positive) / denominator).coerceIn(-0.5f, 0.5f)
}

private fun calculateTargetDimensions(
    inputWidth: Int,
    inputHeight: Int,
    targetMegapixels: Double,
    targetPolicy: SuperResolutionTargetPolicy
): Pair<Int, Int> {
    val inputPixels = inputWidth.toDouble() * inputHeight
    val targetPixels = targetMegapixels * 1_000_000.0
    val scale = sqrt(targetPixels / inputPixels)
        .coerceIn(1.0, targetPolicy.maxLinearScale)
    val width = ((inputWidth * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
    val height = ((inputHeight * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
    return width to height
}

private fun fuseFramesTiled(
    frames: List<LumaFrame>,
    shifts: List<FrameShift>,
    reference: LumaFrame,
    outputWidth: Int,
    outputHeight: Int,
    sink: SuperResolutionTileSink
): File {
    val scaleX = outputWidth.toFloat() / reference.sourceWidth
    val scaleY = outputHeight.toFloat() / reference.sourceHeight
    val shiftByIndex = shifts.associateBy { it.index }
    val maximumAcceptedShift = shifts
        .asSequence()
        .filter { it.accepted }
        .maxOfOrNull { max(abs(it.dx), abs(it.dy)) }
        ?: 0f
    val sourceHalo = ceil(maximumAcceptedShift).toInt() + BILINEAR_HALO_RADIUS
    val decoders = frames.associate { frame ->
        frame.index to BitmapRegionDecoder.newInstance(frame.file.absolutePath, false)
    }

    var finished = false
    sink.begin(outputWidth, outputHeight)
    try {
        val orderedFrames = frames.sortedBy { if (it.index == reference.index) 0 else 1 }
        var tileY = 0
        while (tileY < outputHeight) {
            val tileHeight = minOf(FUSION_TILE_HEIGHT, outputHeight - tileY)
            var tileX = 0
            while (tileX < outputWidth) {
                val tileWidth = minOf(FUSION_TILE_WIDTH, outputWidth - tileX)
                val pixelCount = tileWidth * tileHeight
                val accumR = FloatArray(pixelCount)
                val accumG = FloatArray(pixelCount)
                val accumB = FloatArray(pixelCount)
                val weights = FloatArray(pixelCount)
                val referenceLuma = FloatArray(pixelCount)

                orderedFrames.forEach { frame ->
                    val shift = shiftByIndex.getValue(frame.index)
                    val region = decodeTileRegion(
                        decoder = decoders.getValue(frame.index),
                        sourceWidth = reference.sourceWidth,
                        sourceHeight = reference.sourceHeight,
                        outputTileX = tileX,
                        outputTileY = tileY,
                        outputTileWidth = tileWidth,
                        outputTileHeight = tileHeight,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        shift = shift,
                        sourceHalo = sourceHalo
                    )
                    accumulateTile(
                        region = region,
                        tileX = tileX,
                        tileY = tileY,
                        tileWidth = tileWidth,
                        tileHeight = tileHeight,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        shift = shift,
                        isReference = frame.index == reference.index,
                        referenceLuma = referenceLuma,
                        accumR = accumR,
                        accumG = accumG,
                        accumB = accumB,
                        weights = weights
                    )
                }

                val outputPixels = normalizeTile(
                    accumR = accumR,
                    accumG = accumG,
                    accumB = accumB,
                    weights = weights
                )
                sink.writeTile(tileX, tileY, tileWidth, tileHeight, outputPixels)
                tileX += tileWidth
            }
            tileY += tileHeight
        }
        return sink.finish().also { finished = true }
    } finally {
        decoders.values.forEach { decoder -> runCatching { decoder.recycle() } }
        if (!finished && sink is BitmapTileSink) sink.abort()
    }
}

private fun decodeTileRegion(
    decoder: BitmapRegionDecoder,
    sourceWidth: Int,
    sourceHeight: Int,
    outputTileX: Int,
    outputTileY: Int,
    outputTileWidth: Int,
    outputTileHeight: Int,
    scaleX: Float,
    scaleY: Float,
    shift: FrameShift,
    sourceHalo: Int
): DecodedRegion {
    val firstSourceX = (outputTileX + 0.5f) / scaleX - 0.5f + shift.dx
    val lastSourceX =
        (outputTileX + outputTileWidth - 0.5f) / scaleX - 0.5f + shift.dx
    val firstSourceY = (outputTileY + 0.5f) / scaleY - 0.5f + shift.dy
    val lastSourceY =
        (outputTileY + outputTileHeight - 0.5f) / scaleY - 0.5f + shift.dy
    val left = floor(minOf(firstSourceX, lastSourceX)).toInt().minus(sourceHalo)
        .coerceIn(0, sourceWidth - 1)
    val right = ceil(max(firstSourceX, lastSourceX)).toInt().plus(sourceHalo + 1)
        .coerceIn(left + 1, sourceWidth)
    val top = floor(minOf(firstSourceY, lastSourceY)).toInt().minus(sourceHalo)
        .coerceIn(0, sourceHeight - 1)
    val bottom = ceil(max(firstSourceY, lastSourceY)).toInt().plus(sourceHalo + 1)
        .coerceIn(top + 1, sourceHeight)
    val bitmap = decoder.decodeRegion(
        Rect(left, top, right, bottom),
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    ) ?: error("Could not decode source strip.")
    return try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        DecodedRegion(left, top, bitmap.width, bitmap.height, pixels)
    } finally {
        bitmap.recycle()
    }
}

private fun accumulateTile(
    region: DecodedRegion,
    tileX: Int,
    tileY: Int,
    tileWidth: Int,
    tileHeight: Int,
    scaleX: Float,
    scaleY: Float,
    shift: FrameShift,
    isReference: Boolean,
    referenceLuma: FloatArray,
    accumR: FloatArray,
    accumG: FloatArray,
    accumB: FloatArray,
    weights: FloatArray
) {
    val alignmentWeight = if (isReference) {
        1f
    } else {
        (1f - shift.score * 3f).coerceIn(0.35f, 1f)
    }
    for (localY in 0 until tileHeight) {
        val outputY = tileY + localY
        val sourceY = (outputY + 0.5f) / scaleY - 0.5f + shift.dy
        if (sourceY < region.top || sourceY > region.top + region.height - 1) continue
        val rowOffset = localY * tileWidth
        for (localX in 0 until tileWidth) {
            val outputX = tileX + localX
            val sourceX = (outputX + 0.5f) / scaleX - 0.5f + shift.dx
            if (sourceX < region.left || sourceX > region.left + region.width - 1) continue
            val color = bilinearArgb(region, sourceX, sourceY)
            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            val luma = rgbLuma(red, green, blue)
            val index = rowOffset + localX
            val weight = if (isReference) {
                referenceLuma[index] = luma
                1f
            } else {
                val difference = abs(luma - referenceLuma[index])
                when {
                    difference > OUTLIER_LUMA_THRESHOLD -> 0f
                    difference > 20f ->
                        alignmentWeight *
                            ((OUTLIER_LUMA_THRESHOLD - difference) / 15f)
                    else -> alignmentWeight
                }
            }
            if (weight <= 0f) continue
            accumR[index] += red * weight
            accumG[index] += green * weight
            accumB[index] += blue * weight
            weights[index] += weight
        }
    }
}

private fun normalizeTile(
    accumR: FloatArray,
    accumG: FloatArray,
    accumB: FloatArray,
    weights: FloatArray
): IntArray = IntArray(weights.size) { index ->
    val weight = weights[index]
    if (weight > 0f) {
        val red = (accumR[index] / weight).roundToInt().coerceIn(0, 255)
        val green = (accumG[index] / weight).roundToInt().coerceIn(0, 255)
        val blue = (accumB[index] / weight).roundToInt().coerceIn(0, 255)
        0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
    } else {
        0xff000000.toInt()
    }
}

private fun bilinearArgb(region: DecodedRegion, sourceX: Float, sourceY: Float): Int {
    val localX = sourceX - region.left
    val localY = sourceY - region.top
    val x0 = floor(localX).toInt().coerceIn(0, region.width - 1)
    val x1 = minOf(x0 + 1, region.width - 1)
    val y0 = floor(localY).toInt().coerceIn(0, region.height - 1)
    val y1 = minOf(y0 + 1, region.height - 1)
    val fx = (localX - x0).coerceIn(0f, 1f)
    val fy = (localY - y0).coerceIn(0f, 1f)
    val c00 = region.pixels[y0 * region.width + x0]
    val c10 = region.pixels[y0 * region.width + x1]
    val c01 = region.pixels[y1 * region.width + x0]
    val c11 = region.pixels[y1 * region.width + x1]
    val red = bilinearChannel(c00, c10, c01, c11, 16, fx, fy)
    val green = bilinearChannel(c00, c10, c01, c11, 8, fx, fy)
    val blue = bilinearChannel(c00, c10, c01, c11, 0, fx, fy)
    return 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
}

private fun bilinearChannel(
    c00: Int,
    c10: Int,
    c01: Int,
    c11: Int,
    shift: Int,
    fx: Float,
    fy: Float
): Int {
    val top = ((c00 shr shift) and 0xff) * (1f - fx) + ((c10 shr shift) and 0xff) * fx
    val bottom = ((c01 shr shift) and 0xff) * (1f - fx) + ((c11 shr shift) and 0xff) * fx
    return (top * (1f - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
}

private fun applyMildUnsharpInPlace(bitmap: Bitmap) {
    if (bitmap.width < 3 || bitmap.height < 3) return
    val width = bitmap.width
    var previous = IntArray(width)
    var current = IntArray(width)
    var next = IntArray(width)
    bitmap.getPixels(current, 0, width, 0, 0, width, 1)
    bitmap.getPixels(next, 0, width, 0, 1, width, 1)

    for (y in 0 until bitmap.height) {
        val following = if (y + 1 < bitmap.height) next else current
        val output = current.copyOf()
        if (y in 1 until bitmap.height - 1) {
            for (x in 1 until width - 1) {
                output[x] = sharpenPixel(
                    center = current[x],
                    left = current[x - 1],
                    right = current[x + 1],
                    up = previous[x],
                    down = following[x]
                )
            }
        }
        bitmap.setPixels(output, 0, width, 0, y, width, 1)
        previous = current
        current = next
        next = if (y + 2 < bitmap.height) {
            IntArray(width).also {
                bitmap.getPixels(it, 0, width, 0, y + 2, width, 1)
            }
        } else {
            current
        }
    }
}

private fun sharpenPixel(center: Int, left: Int, right: Int, up: Int, down: Int): Int {
    fun channel(shift: Int): Int {
        val centerValue = center shr shift and 0xff
        val neighbors = ((left shr shift and 0xff) + (right shr shift and 0xff) +
            (up shr shift and 0xff) + (down shr shift and 0xff)) / 4f
        return (centerValue + 0.16f * (centerValue - neighbors))
            .roundToInt()
            .coerceIn(0, 255)
    }
    return 0xff000000.toInt() or
        (channel(16) shl 16) or
        (channel(8) shl 8) or
        channel(0)
}

private fun runSingleFrameFallback(
    request: SuperResolutionFusionRequest,
    reference: LumaFrame,
    targetWidth: Int,
    targetHeight: Int,
    shifts: List<FrameShift>,
    sourceMegapixels: Double,
    targetMegapixels: Double,
    reason: String
): SuperResolutionFusionResult {
    if (targetMegapixels > request.targetPolicy.maxSafeTargetMegapixels) {
        return failedSuperResolutionResult(
            request = request,
            inputFrameCount = request.inputFrameFiles.size,
            message = "$reason Streaming single-frame fallback is not implemented.",
            shifts = shifts
        )
    }
    request.status("${superResolutionStatusLabel(request)}: writing output...")
    val source = BitmapFactory.decodeFile(reference.file.absolutePath)
        ?: return failedSuperResolutionResult(
            request,
            request.inputFrameFiles.size,
            "Fallback reference decode failed.",
            shifts
        )
    val output = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    if (output !== source) source.recycle()
    return try {
        applyMildUnsharpInPlace(output)
        val outputFile = File(
            request.outputDir,
            superResolutionOutputFileName(targetMegapixels)
        )
        saveJpeg(output, outputFile)
        val actualOutputMegapixels = megapixels(output.width, output.height)
        val result = SuperResolutionFusionResult(
            outputFile = outputFile,
            outputWidth = output.width,
            outputHeight = output.height,
            inputFrameCount = request.inputFrameFiles.size,
            usedFrameCount = 1,
            fallbackUsed = true,
            estimatedShifts = shifts,
            sourceMegapixels = sourceMegapixels,
            targetMegapixels = targetMegapixels,
            actualOutputMegapixels = actualOutputMegapixels,
            experimentalTarget =
                targetMegapixels > request.targetPolicy.maxSafeTargetMegapixels,
            rawInputUsed = request.sourceMode == SuperResolutionSourceMode.FULLRES_50MP_RAW,
            message = reason
        )
        writeSuperResolutionJob(request, result, "COMPLETE", reason)
        result
    } finally {
        output.recycle()
    }
}

private fun failedSuperResolutionResult(
    request: SuperResolutionFusionRequest,
    inputFrameCount: Int,
    message: String,
    shifts: List<FrameShift> = emptyList()
): SuperResolutionFusionResult {
    val sourceMegapixels = detectSourceMegapixels(request.inputFrameFiles.firstOrNull())
    val targetMegapixels = if (sourceMegapixels > 0.0) {
        resolveTargetMegapixels(request, sourceMegapixels)
    } else {
        request.targetMegapixels.coerceAtMost(
            request.targetPolicy.maxExperimentalTargetMegapixels
        )
    }
    val result = SuperResolutionFusionResult(
        outputFile = null,
        outputWidth = 0,
        outputHeight = 0,
        inputFrameCount = inputFrameCount,
        usedFrameCount = 0,
        fallbackUsed = false,
        estimatedShifts = shifts,
        sourceMegapixels = sourceMegapixels,
        targetMegapixels = targetMegapixels,
        actualOutputMegapixels = 0.0,
        experimentalTarget =
            targetMegapixels > request.targetPolicy.maxSafeTargetMegapixels,
        rawInputUsed = request.sourceMode == SuperResolutionSourceMode.FULLRES_50MP_RAW,
        message = message
    )
    runCatching { writeSuperResolutionJob(request, result, "FAILED", message) }
    return result
}

private fun writeSuperResolutionJob(
    request: SuperResolutionFusionRequest,
    result: SuperResolutionFusionResult,
    status: String,
    reason: String?
) {
    val shiftArray = JSONArray()
    result.estimatedShifts.forEach { shift ->
        shiftArray.put(
            JSONObject()
                .put("index", shift.index)
                .put("dx", shift.dx.toDouble())
                .put("dy", shift.dy.toDouble())
                .put("score", shift.score.toDouble())
                .put("accepted", shift.accepted)
        )
    }
    val sources = JSONArray()
    request.inputFrameFiles.forEach { sources.put(it.absolutePath) }
    val policy = request.targetPolicy
    val policyJson = JSONObject()
        .put("sourceMode", policy.sourceMode.name)
        .put("defaultTargetMegapixels", policy.defaultTargetMegapixels)
        .put("maxSafeTargetMegapixels", policy.maxSafeTargetMegapixels)
        .put("maxExperimentalTargetMegapixels", policy.maxExperimentalTargetMegapixels)
        .put("maxLinearScale", policy.maxLinearScale)
    val job = JSONObject()
        .put("jobType", SUPER_RES_PIPELINE)
        .put("pipeline", SUPER_RES_PIPELINE)
        .put("status", status)
        .put("processStatus", status)
        .put("requestedResolutionMode", resolutionModeLabelForTarget(result.targetMegapixels))
        .put("outputResolutionMode", resolutionModeLabelForTarget(result.targetMegapixels))
        .put("sourceResolutionMode", resolutionModeLabelForSource(request.sourceMode))
        .put("sourceMode", request.sourceMode.name)
        .put("sourceMegapixels", result.sourceMegapixels)
        .put("targetMegapixels", result.targetMegapixels)
        .put("actualOutputMegapixels", result.actualOutputMegapixels)
        .put("targetPolicy", policyJson)
        .put("experimentalTarget", result.experimentalTarget)
        .put("rawInputUsed", result.rawInputUsed)
        .put("inputFrameCount", result.inputFrameCount)
        .put("requestedFrames", minOf(request.maxFrames, request.inputFrameFiles.size))
        .put("savedFrames", result.inputFrameCount)
        .put("usedFrameCount", result.usedFrameCount)
        .put("outputWidth", result.outputWidth)
        .put("outputHeight", result.outputHeight)
        .put("fallbackUsed", result.fallbackUsed)
        .put("estimatedShifts", shiftArray)
        .put("createdAt", System.currentTimeMillis())
        .put("sourceFrameFiles", sources)
        .put("finalFile", result.outputFile?.name ?: JSONObject.NULL)
        .put("reason", reason ?: JSONObject.NULL)
        .put("failureMessage", if (status == "FAILED") result.message else JSONObject.NULL)
        .put("message", result.message)
    File(request.outputDir, SUPER_RES_JOB_FILE).writeText(job.toString(2))
}

private fun readColorBurstFrameFiles(jobDir: File): List<File> {
    val jobFile = File(jobDir, SUPER_RES_JOB_FILE)
    val frames = JSONObject(jobFile.readText()).optJSONArray("frames") ?: JSONArray()
    return buildList {
        for (index in 0 until frames.length()) {
            val name = frames.optJSONObject(index)?.optString("file").orEmpty()
            if (name.isNotBlank()) {
                File(jobDir, name).takeIf { it.isFile }?.let(::add)
            }
        }
    }
}

private fun createSuperResolutionJobDirectory(context: Context): File {
    val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        ?: error("Pictures directory unavailable.")
    val root = File(picturesDir, "KeplerSuperRes")
    check(root.exists() || root.mkdirs()) { "Could not create KeplerSuperRes directory." }
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    return File(root, "KPL_SUPER_RES_$timestamp").also {
        check(it.exists() || it.mkdirs()) { "Could not create SuperRes job directory." }
    }
}

private fun saveJpeg(bitmap: Bitmap, outputFile: File, quality: Int = JPEG_QUALITY) {
    FileOutputStream(outputFile).use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            "JPEG encode failed."
        }
    }
}

private fun estimateFusionWorkingBytes(
    outputWidth: Int,
    outputHeight: Int,
    includesOutputBitmap: Boolean
): Long {
    val outputBytes = if (includesOutputBitmap) {
        outputWidth.toLong() * outputHeight * 4L
    } else {
        0L
    }
    val tilePixels =
        minOf(FUSION_TILE_WIDTH, outputWidth).toLong() *
            minOf(FUSION_TILE_HEIGHT, outputHeight)
    val tileBytes = tilePixels * 29L
    return outputBytes + tileBytes + 64L * 1024L * 1024L
}

private fun availableHeapBytes(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
}

private fun rgbLuma(red: Int, green: Int, blue: Int): Float =
    0.299f * red + 0.587f * green + 0.114f * blue

private fun unsigned(value: Byte): Int = value.toInt() and 0xff

private fun resolveTargetMegapixels(
    request: SuperResolutionFusionRequest,
    sourceMegapixels: Double
): Double {
    val requested = request.targetMegapixels
        .coerceAtMost(request.targetPolicy.maxExperimentalTargetMegapixels)
    val linearScaleLimit =
        sourceMegapixels * request.targetPolicy.maxLinearScale * request.targetPolicy.maxLinearScale
    return requested.coerceAtMost(linearScaleLimit).coerceAtLeast(sourceMegapixels)
}

private fun superResolutionOutputFileName(targetMegapixels: Double): String {
    return "super_resolution_${megapixelLabel(targetMegapixels).replace('.', '_')}mp.jpg"
}

private fun megapixels(width: Int, height: Int): Double =
    width.toDouble() * height.toDouble() / 1_000_000.0

private fun detectSourceMegapixels(file: File?): Double {
    if (file == null) return 0.0
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        megapixels(bounds.outWidth, bounds.outHeight)
    } else {
        0.0
    }
}

private fun canAllocateOutputBitmap(outputWidth: Int, outputHeight: Int): Boolean {
    val outputBytes = outputWidth.toLong() * outputHeight * 4L
    return availableHeapBytes() >= outputBytes + 64L * 1024L * 1024L
}

private fun megapixelLabel(megapixels: Double): String =
    if (megapixels % 1.0 == 0.0) {
        megapixels.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", megapixels)
    }

private fun resolutionModeLabelForSource(sourceMode: SuperResolutionSourceMode): String =
    when (sourceMode) {
        SuperResolutionSourceMode.BINNED_12MP_YUV,
        SuperResolutionSourceMode.BINNED_12MP_RGB -> CaptureResolutionMode.MP12.name
        SuperResolutionSourceMode.FULLRES_50MP_RAW,
        SuperResolutionSourceMode.FULLRES_50MP_RGB -> CaptureResolutionMode.MP50.name
    }

private fun resolutionModeLabelForTarget(targetMegapixels: Double): String =
    if (targetMegapixels <= 30.0) {
        CaptureResolutionMode.MP24_FUSION.name
    } else {
        "SUPER_RES_${megapixelLabel(targetMegapixels).replace('.', '_')}MP"
    }

private fun superResolutionStatusLabel(request: SuperResolutionFusionRequest): String {
    val target = megapixelLabel(request.targetMegapixels)
    return if (request.targetMegapixels <= 30.0) {
        "${target}M Fusion"
    } else {
        "${target}M SuperRes"
    }
}
