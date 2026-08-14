package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.text.SimpleDateFormat
import java.util.concurrent.CancellationException
import java.util.Date
import java.util.Locale
import java.util.UUID
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
private const val FUSION_TILE_WIDTH = 384
private const val FUSION_TILE_HEIGHT = 384
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
    val processingParams: ClassicYuvFusionParams = ClassicYuvFusionPreset.NATURAL.params,
    val denoiseAlgorithm: DenoiseAlgorithm = DenoiseAlgorithm.GUIDED,
    val tileSinkFactory: ((File) -> SuperResolutionTileSink)? = null,
    val cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    val operationLease: JobOperationLease? = null,
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
    fun abort() {}
}

class BitmapTileSink(
    private val outputFile: File,
    private val quality: Int = JPEG_QUALITY
) : SuperResolutionTileSink {
    private var processingAttempt: ProcessingAttempt? = null

    internal fun bindProcessingAttempt(attempt: ProcessingAttempt) {
        processingAttempt = attempt
    }
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
            saveJpeg(output, outputFile, quality, processingAttempt = processingAttempt, claimKey = "superResolutionOutputFile")
            outputFile
        } finally {
            output.recycle()
            bitmap = null
        }
    }

    override fun abort() {
        bitmap?.recycle()
        bitmap = null
    }
}

/** Scanline PNG sink used when a full-resolution Bitmap would exceed the heap plan. */
internal class StreamingPngTileSink(
    private val outputFile: File,
    private val processingAttempt: ProcessingAttempt? = null
) : SuperResolutionTileSink {
    private enum class State { IDLE, WRITING, FINISHING, COMMITTED, ABORTING, ABORTED, FAILED }
    private var state = State.IDLE
    private var temporary: File? = null
    private var rawOutput: FileOutputStream? = null
    private var stream: BufferedOutputStream? = null
    private var deflater: Deflater? = null
    private var width = 0
    private var height = 0
    private var nextY = 0
    private val cleanupRecords = mutableListOf<ProcessingArtifactSettlementRecord>()
    private val resourceSettlementRecords = mutableListOf<ProcessingResourceSettlementRecord>()
    private val compressed = ByteArray(64 * 1024)
    private var row: ByteArray? = null

    override fun begin(width: Int, height: Int) {
        check(state == State.IDLE || state == State.ABORTED) { "PNG sink begin in state=$state" }
        require(width > 0 && height > 0)
        this.width = width
        this.height = height
        val parent = requireNotNull(outputFile.parentFile)
        parent.mkdirs()
        val temp = File(parent, ".${outputFile.name}.${System.nanoTime()}.tmp")
        temporary = temp
        state = State.WRITING
        val fileOutput = FileOutputStream(temp)
        rawOutput = fileOutput
        val out = BufferedOutputStream(fileOutput, 64 * 1024)
        stream = out
        deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        row = ByteArray(Math.addExact(1, Math.multiplyExact(width, 4)))
        out.write(byteArrayOf(
            137.toByte(), 80, 78, 71, 13, 10, 26, 10
        ))
        val header = ByteArray(13)
        writeInt(header, 0, width)
        writeInt(header, 4, height)
        header[8] = 8
        header[9] = 6
        writeChunk(out, "IHDR", header, 0, header.size)
    }

    override fun writeTile(x: Int, y: Int, width: Int, height: Int, pixels: IntArray) {
        check(state == State.WRITING) { "PNG sink write in state=$state" }
        check(x == 0 && width == this.width && y == nextY)
        require(height > 0 && pixels.size >= Math.multiplyExact(width, height))
        val out = requireNotNull(stream)
        val encoder = requireNotNull(deflater)
        val scanline = requireNotNull(row)
        for (rowIndex in 0 until height) {
            scanline[0] = 0
            var offset = 1
            val pixelOffset = rowIndex * width
            for (column in 0 until width) {
                val color = pixels[pixelOffset + column]
                scanline[offset++] = (color ushr 16).toByte()
                scanline[offset++] = (color ushr 8).toByte()
                scanline[offset++] = color.toByte()
                scanline[offset++] = 0xFF.toByte()
            }
            encoder.setInput(scanline)
            while (!encoder.needsInput()) {
                val count = encoder.deflate(compressed)
                if (count > 0) writeChunk(out, "IDAT", compressed, 0, count)
            }
        }
        nextY += height
    }

    override fun finish(): File {
        check(state == State.WRITING) { "PNG sink finish in state=$state" }
        check(nextY == height)
        state = State.FINISHING
        val out = requireNotNull(stream)
        val encoder = requireNotNull(deflater)
        return try {
            encoder.finish()
            while (!encoder.finished()) {
                val count = encoder.deflate(compressed)
                if (count > 0) writeChunk(out, "IDAT", compressed, 0, count)
            }
            writeChunk(out, "IEND", ByteArray(0), 0, 0)
            out.flush()
            rawOutput?.fd?.sync()
            out.close()
            resourceSettlementRecords += ProcessingResourceSettlementRecord("STREAM", "CLOSED")
            stream = null
            rawOutput = null
            encoder.end()
            resourceSettlementRecords += ProcessingResourceSettlementRecord("DEFLATER", "ENDED")
            deflater = null
            val sourceTemp = requireNotNull(temporary)
            val result = commitProcessingArtifact(
                finalFile = outputFile,
                writeTemp = { transactionTemp ->
                    try {
                        java.nio.file.Files.move(
                            sourceTemp.toPath(),
                            transactionTemp.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        java.nio.file.Files.move(sourceTemp.toPath(), transactionTemp.toPath())
                    }
                },
                verifyFinal = { committed -> verifyPngArtifact(committed, width, height) }
                ,processingAttemptId = processingAttempt?.id
                ,claimKey = processingAttempt?.let { "superResolutionOutputFile" }
            )
            cleanupRecords += result.settlements
            temporary = null
            state = State.COMMITTED
            outputFile
        } catch (failure: Throwable) {
            cleanupRecords += listOfNotNull(settleTemporary())
            settleStreams()
            runCatching { encoder.end() }
                .onSuccess { resourceSettlementRecords += ProcessingResourceSettlementRecord("DEFLATER", "ENDED") }
                .onFailure { resourceSettlementRecords += ProcessingResourceSettlementRecord("DEFLATER", "END_FAILED", it) }
            deflater = null
            temporary = null
            state = State.FAILED
            throw failure
        }
    }

    override fun abort() {
        if (state == State.COMMITTED || state == State.ABORTED) return
        check(state == State.WRITING || state == State.FAILED) { "PNG sink abort in state=$state" }
        state = State.ABORTING
        settleStreams()
        val deflaterFailure = runCatching { deflater?.end() }.exceptionOrNull()
        resourceSettlementRecords += if (deflaterFailure == null) {
            ProcessingResourceSettlementRecord("DEFLATER", "ENDED")
        } else {
            ProcessingResourceSettlementRecord("DEFLATER", "END_FAILED", deflaterFailure)
        }
        cleanupRecords += listOfNotNull(settleTemporary())
        stream = null
        rawOutput = null
        deflater = null
        temporary = null
        state = State.ABORTED
    }

    internal fun settlementRecords(): List<ProcessingArtifactSettlementRecord> = cleanupRecords.toList()
    internal fun resourceSettlementRecords(): List<ProcessingResourceSettlementRecord> = resourceSettlementRecords.toList()

    private fun settleTemporary(): ProcessingArtifactSettlementRecord? {
        return temporary?.let { settleProcessingArtifactPath(it) }
    }

    private fun settleStreams() {
        val currentStream = stream
        val streamFailure = runCatching { currentStream?.close() }.exceptionOrNull()
        resourceSettlementRecords += if (streamFailure == null) {
            ProcessingResourceSettlementRecord("STREAM", "CLOSED")
        } else {
            ProcessingResourceSettlementRecord("STREAM", "CLOSE_FAILED", streamFailure)
        }
        if (streamFailure != null) {
            val currentRaw = rawOutput
            if (currentRaw != null) {
                val rawFailure = runCatching { currentRaw.close() }.exceptionOrNull()
                resourceSettlementRecords += if (rawFailure == null) {
                    ProcessingResourceSettlementRecord("RAW_FD_FALLBACK", "CLOSED")
                } else {
                    ProcessingResourceSettlementRecord("RAW_FD_FALLBACK", "CLOSE_FAILED", rawFailure)
                }
            }
        } else if (currentStream != null) {
            resourceSettlementRecords += ProcessingResourceSettlementRecord("RAW_FD", "CLOSED")
        }
        stream = null
        rawOutput = null
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun writeChunk(
        out: BufferedOutputStream,
        type: String,
        data: ByteArray,
        offset: Int,
        length: Int
    ) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val lengthBytes = ByteArray(4)
        writeInt(lengthBytes, 0, length)
        out.write(lengthBytes)
        out.write(typeBytes)
        out.write(data, offset, length)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data, offset, length)
        writeInt(lengthBytes, 0, crc.value.toInt())
        out.write(lengthBytes)
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
    request.cancellation.throwIfCancelled()
    require(request.targetPolicy.sourceMode == request.sourceMode) {
        "Target policy sourceMode must match request sourceMode."
    }
    val processingAttempt = beginProcessingAttempt(
        request.outputDir,
        "SUPER_RESOLUTION",
        operationLease = request.operationLease
    )
    val inputFiles = request.inputFrameFiles
        .asSequence()
        .filter { it.isFile && it.length() > 0L }
        .take(request.maxFrames.coerceAtLeast(1))
        .toList()
    request.outputDir.mkdirs()

    if (inputFiles.isEmpty()) {
        val failure = failedSuperResolutionResult(
            request = request,
            inputFrameCount = 0,
            message = "No readable source frames.",
            processingAttempt = processingAttempt
        )
        processingAttempt.release()
        return failure
    }
    if (request.sourceMode == SuperResolutionSourceMode.FULLRES_50MP_RAW) {
        val failure = failedSuperResolutionResult(
            request = request,
            inputFrameCount = inputFiles.size,
            message = "FULLRES_50MP_RAW decoder is not implemented yet.",
            processingAttempt = processingAttempt
        )
        processingAttempt.release()
        return failure
    }

    var shifts = emptyList<FrameShift>()
    return try {
        val statusLabel = superResolutionStatusLabel(request)
        request.status("$statusLabel: aligning frames...")
        request.cancellation.throwIfCancelled()
        val analyzedFrames = analyzeFrames(inputFiles, request.cancellation)
        request.cancellation.throwIfCancelled()
        if (analyzedFrames.isEmpty()) {
            return failedSuperResolutionResult(
                request = request,
                inputFrameCount = inputFiles.size,
                message = "Could not decode source frames.",
                processingAttempt = processingAttempt
            )
        }

        val reference = chooseFirstSharpFrame(analyzedFrames)
        request.cancellation.throwIfCancelled()
        val sourceMegapixels = megapixels(reference.sourceWidth, reference.sourceHeight)
        val resolvedTargetMegapixels = resolveTargetMegapixels(request, sourceMegapixels)
        shifts = estimateFrameShifts(analyzedFrames, reference, request.cancellation)
        request.cancellation.throwIfCancelled()
        val acceptedFrames = analyzedFrames.filter { frame ->
            shifts.firstOrNull { it.index == frame.index }?.accepted == true
        }
        val dimensions = calculateTargetDimensions(
            reference.sourceWidth,
            reference.sourceHeight,
            resolvedTargetMegapixels,
            request.targetPolicy
        )
        val bitmapSinkAllowed = request.tileSinkFactory == null &&
            resolvedTargetMegapixels <= request.targetPolicy.maxSafeTargetMegapixels &&
            canAllocateOutputBitmap(dimensions.first, dimensions.second)

        if (acceptedFrames.size < MIN_FUSION_FRAMES) {
            return runSingleFrameFallback(
                request = request,
                reference = reference,
                targetWidth = dimensions.first,
                targetHeight = dimensions.second,
                shifts = shifts,
                sourceMegapixels = sourceMegapixels,
                targetMegapixels = resolvedTargetMegapixels,
                reason = "Fewer than two frames passed alignment.",
                processingAttempt = processingAttempt
            )
        }

        val requiredBytes = estimateFusionWorkingBytes(
            outputWidth = dimensions.first,
            outputHeight = dimensions.second,
            includesOutputBitmap = bitmapSinkAllowed
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
                reason = "Memory guard selected single-frame fallback.",
                processingAttempt = processingAttempt
            )
        }

        request.status("$statusLabel: accumulating detail...")
        val outputFile = File(
            request.outputDir,
            if (bitmapSinkAllowed || request.tileSinkFactory != null) {
                superResolutionOutputFileName(resolvedTargetMegapixels)
            } else {
                superResolutionOutputFileName(resolvedTargetMegapixels).removeSuffix(".jpg") + ".png"
            }
        )
        val tileSink = request.tileSinkFactory?.invoke(outputFile) ?:
            if (bitmapSinkAllowed) BitmapTileSink(outputFile).also { it.bindProcessingAttempt(processingAttempt) } else StreamingPngTileSink(outputFile, processingAttempt)
        request.status("$statusLabel: writing output...")
        val writtenFile = fuseFramesTiled(
            frames = acceptedFrames,
            shifts = shifts,
            reference = reference,
            outputWidth = dimensions.first,
            outputHeight = dimensions.second,
            sink = tileSink,
            processingParams = request.processingParams,
            denoiseAlgorithm = request.denoiseAlgorithm,
            cancellation = request.cancellation
        )
        if (tileSink is StreamingPngTileSink) {
            recordProcessingArtifactSettlements(
                request.outputDir,
                processingAttempt,
                tileSink.settlementRecords().filter {
                    it.status != ProcessingArtifactSettlementStatus.ADOPTED
                }
            )
        }
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
        markProcessingArtifactClaim(request.outputDir, processingAttempt, "superResolutionOutputFile", requireNotNull(result.outputFile))
        writeSuperResolutionJob(request, result, "COMPLETE", null, processingAttempt)
        if (request.cancellation.isCancelled) {
            markProcessingPostCommitCancellation(request.outputDir, processingAttempt)
        }
        result
    } catch (ce: CancellationException) {
        throw ce
    } catch (oom: OutOfMemoryError) {
        failedSuperResolutionResult(
            request = request,
            inputFrameCount = inputFiles.size,
            message = "Out of memory; fusion stopped without attempting recovery.",
            shifts = shifts,
            processingAttempt = processingAttempt
        )
    } catch (error: Exception) {
        failedSuperResolutionResult(
            request = request,
            inputFrameCount = inputFiles.size,
            message = "${error.javaClass.simpleName}: ${error.message}",
            shifts = shifts,
            processingAttempt = processingAttempt
        )
    } finally {
        processingAttempt.releaseOwnedLease()
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
    processingParams: ClassicYuvFusionParams = ClassicYuvFusionPreset.NATURAL.params,
    captureCancellationHandle: KeplerCaptureCancellationHandle = NoOpKeplerCaptureCancellationHandle,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    onStatus: (String) -> Unit,
    onPipelineEvent: CameraPipelineEventSink = {}
) {
    val mainHandler = Handler(Looper.getMainLooper())
    val callbackLedger = ProcessingCallbackOutcomeLedger()
    val callbackDispatcher = ProcessingCallbackDispatcher(
        mainHandler,
        "KeplerSuperResolution",
        executionObserver = callbackLedger::recordExecution,
        dispatchObserver = callbackLedger::recordDispatch
    )
    fun post(message: String): Boolean {
        val result = callbackDispatcher.dispatch { onStatus(message) }
        if (result != ProcessingCallbackDispatchResult.ACCEPTED) {
            Log.w("KeplerSuperResolution", "status dispatch $result")
        }
        return result == ProcessingCallbackDispatchResult.ACCEPTED
    }
    val terminal = CameraPipelineTerminalPublisher(onPipelineEvent)
    val captureFrames = frameCount.coerceIn(MIN_FUSION_FRAMES, 6)

    cancellation.throwIfCancelled()
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
        captureMode = CaptureMode.MULTI_FRAME,
        processingParams = processingParams,
        captureCancellationHandle = captureCancellationHandle,
        onComplete = { sourceJobDir ->
            try {
                cancellation.throwIfCancelled()
            } catch (_: CancellationException) {
                post("PIPELINE_CANCELLED: Capture timed out; background processing stopped.")
                terminal.publish(CameraPipelineEvent.Terminal.Kind.CANCELLED, message = "Capture cancelled before Super Resolution processing.")
                return@captureYuvBurstColorWithMotion
            }
            val workerThread = HandlerThread("KeplerSuperResolutionThread").apply { start() }
            val workerHandler = Handler(workerThread.looper)
            val workerPosted = try {
                workerHandler.post {
                var requiredOutputCommitted = false
                var publicExportCommitted = false
                var verified = false
                var outputLease: JobOperationLease? = null
                var outputDirForSettlement: File? = null
                var exportSettlementAttempted = false
                var exportSettlementSucceeded = false
                fun settleInterruptedExportForTerminal(jobDir: File, lease: JobOperationLease): OwnedPublicExportEvidence? {
                    val evidence = try {
                        inspectOwnedPublicExportEvidence(jobDir, lease)
                    } catch (failure: Error) {
                        throw failure
                    } catch (_: Exception) {
                        null
                    }
                    try {
                        exportSettlementAttempted = true
                        val settled = settleOwnedPublicExportInterruption(
                            jobDir = jobDir,
                            ownerLease = lease,
                            failureMessage = "Super Resolution public export ended before terminal metadata was settled.",
                            finalOutputFormat = finalOutputFormat
                        )
                        if (settled) exportSettlementSucceeded = true
                    } catch (failure: Error) {
                        throw failure
                    } catch (settlementFailure: Exception) {
                        Log.e("KeplerSuperResolution", "public export owner settlement failed", settlementFailure)
                    }
                    return evidence
                }
                try {
                    cancellation.throwIfCancelled()
                    val sourceFrames = readColorBurstFrameFiles(sourceJobDir)
                    cancellation.throwIfCancelled()
                    val outputDir = createSuperResolutionJobDirectory(context)
                    outputDirForSettlement = outputDir
                    outputLease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                        outputDir,
                        JobRecoveryMutationIntent.PROCESSING_START
                    )
                    cancellation.throwIfCancelled()
                    val result = runSuperResolutionFusion(
                        SuperResolutionFusionRequest(
                            context = context,
                            inputFrameFiles = sourceFrames,
                            outputDir = outputDir,
                            sourceMode = SuperResolutionSourceMode.BINNED_12MP_YUV,
                            maxFrames = captureFrames,
                            processingParams = processingParams,
                            cancellation = cancellation,
                            operationLease = outputLease,
                            status = { post(it) }
                        )
                    )
                    val outputFile = result.outputFile
                    if (outputFile == null || !outputFile.exists()) {
                        post("PIPELINE_FAILED: 24M Fusion failed. ${result.message}")
                        terminal.publish(CameraPipelineEvent.Terminal.Kind.FAILED, message = result.message)
                        return@post
                    }

                    cancellation.throwIfCancelled()
                    val bitmap = NoFollowFileSystem.decodeBitmapVerified(outputFile)
                        ?: error("Could not decode 24M Fusion output.")
                    val requestedFormat = requestedOutputFormatForSetting(finalOutputFormat)
                    val displayName = "Kepler_SR_${
                        megapixelLabel(result.targetMegapixels)
                    }MP_${
                        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    }"
                    val export = try {
                        cancellation.throwIfCancelled()
                        exportNightFusionBitmapToGallery(
                            context = context,
                            bitmap = bitmap,
                            displayNameBase = displayName,
                            requestedFormat = requestedFormat,
                            cancellation = cancellation,
                            jobDir = outputDir,
                            ownerLease = outputLease
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
                        terminal.publish(
                            CameraPipelineEvent.Terminal.Kind.FAILED,
                            requiredOutputCommitted = outputFile.isFile,
                            publicExportCommitted = export.success && !export.uriString.isNullOrBlank(),
                            message = export.errorMessage
                        )
                        return@post
                    }

                    verified = verifyCommittedGalleryExport(context, export) is GalleryExportVerification.Verified
                    requiredOutputCommitted = outputFile.isFile
                    publicExportCommitted = export.success && !export.uriString.isNullOrBlank()
                    updateExportMetadata(
                        jobDir = outputDir,
                        export = export,
                        verified = verified,
                        finalOutputFormat = finalOutputFormat,
                        rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar
                        ,postExportCancellationRequested = cancellation.isCancelled,
                        postExportWorkSkipped = cancellation.isCancelled
                    )
                    if (!verified) {
                        updateExportFailure(
                            jobDir = outputDir,
                            error = "Export verification failed",
                            finalOutputFormat = finalOutputFormat,
                            rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar
                            ,export = export
                        )
                        post("PIPELINE_FAILED: 24M Fusion export verification failed.")
                        terminal.publish(
                            CameraPipelineEvent.Terminal.Kind.FAILED,
                            requiredOutputCommitted = requiredOutputCommitted,
                            publicExportCommitted = publicExportCommitted,
                            message = "24M Fusion export verification failed."
                        )
                        return@post
                    }

                    if (cancellation.isCancelled) {
                        updateExportMetadata(outputDir, export, true, finalOutputFormat,
                            rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar,
                            postExportCancellationRequested = true, postExportWorkSkipped = true)
                        post("PIPELINE_COMPLETE_PARTIAL: Image was saved, but optional post-export work was cancelled. Cache was kept.")
                        terminal.publish(
                            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
                            requiredOutputCommitted = requiredOutputCommitted,
                            publicExportCommitted = publicExportCommitted,
                            verified = verified,
                            message = "24M Fusion export committed; optional work cancelled."
                        )
                        return@post
                    }
                    post(
                        "PIPELINE_COMPLETE: 24M Fusion complete " +
                            "${result.outputWidth}x${result.outputHeight}, " +
                            "used ${result.usedFrameCount}/${result.inputFrameCount} frames, " +
                            "fallback=${result.fallbackUsed}."
                    )
                    terminal.publish(
                        CameraPipelineEvent.Terminal.Kind.COMPLETE,
                        requiredOutputCommitted = true,
                        publicExportCommitted = true,
                        verified = true,
                        message = "24M Fusion export complete."
                    )
                } catch (_: CancellationException) {
                    post("PIPELINE_CANCELLED: Capture timed out; background processing stopped.")
                    val evidence = outputLease?.let { lease ->
                        outputDirForSettlement?.let { dir -> settleInterruptedExportForTerminal(dir, lease) }
                    }
                    terminal.publish(
                        publicExportInterruptionTerminalKind(
                            evidence,
                            cancellationRequested = true,
                            committedFallback = publicExportCommitted
                        ),
                        requiredOutputCommitted = requiredOutputCommitted,
                        publicExportCommitted = evidence?.committed ?: publicExportCommitted,
                        verified = evidence?.verified ?: verified,
                        message = "24M Fusion cancellation settled."
                    )
                } catch (error: Exception) {
                    post(
                        "PIPELINE_FAILED: 24M Fusion failed. " +
                            "${error.javaClass.simpleName}: ${error.message}"
                    )
                    val evidence = outputLease?.let { lease ->
                        outputDirForSettlement?.let { dir -> settleInterruptedExportForTerminal(dir, lease) }
                    }
                    terminal.publish(
                        publicExportInterruptionTerminalKind(
                            evidence,
                            cancellationRequested = false,
                            committedFallback = publicExportCommitted
                        ),
                        requiredOutputCommitted = requiredOutputCommitted,
                        publicExportCommitted = evidence?.committed ?: publicExportCommitted,
                        verified = evidence?.verified ?: verified,
                        message = error.message
                    )
                } finally {
                    outputLease?.let { lease ->
                        val settlementDir = outputDirForSettlement
                        if (settlementDir == null) return@let
                        if (!exportSettlementAttempted) {
                            try {
                                exportSettlementAttempted = true
                                val settled = settleOwnedPublicExportInterruption(
                                    jobDir = settlementDir,
                                    ownerLease = lease,
                                    failureMessage = "Super Resolution public export ended before terminal metadata was settled.",
                                    finalOutputFormat = finalOutputFormat
                                )
                                if (settled) exportSettlementSucceeded = true
                            } catch (failure: Error) {
                                throw failure
                            } catch (settlementFailure: Exception) {
                                Log.e("KeplerSuperResolution", "public export owner settlement failed", settlementFailure)
                            }
                        }
                        if (exportSettlementSucceeded) lease.release()
                        else Log.e("KeplerSuperResolution", "retaining public export lease after settlement failure")
                    }
                    workerThread.quitSafely()
                }
            } } catch (failure: Error) {
                throw failure
            } catch (failure: Exception) {
                Log.e("KeplerSuperResolution", "worker dispatch failed", failure)
                false
            }
            if (!workerPosted) {
                // The worker never reached its finally block.
                workerThread.quitSafely()
                post("PIPELINE_FAILED: 24M Fusion worker could not start.")
                terminal.publish(CameraPipelineEvent.Terminal.Kind.FAILED, message = "24M Fusion worker could not start.")
            }
        },
        onError = { error ->
            post("PIPELINE_FAILED: 24M Fusion capture failed. $error")
            terminal.publish(CameraPipelineEvent.Terminal.Kind.FAILED, message = error)
        },
        onStatus = { message -> post(message) }
    )
}

private fun analyzeFrames(
    files: List<File>,
    cancellation: KeplerPipelineCancellation
): List<LumaFrame> {
    val bounds = files.mapIndexedNotNull { index, file ->
        cancellation.throwIfCancelled()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        NoFollowFileSystem.decodeBitmapVerified(file, options)
        cancellation.throwIfCancelled()
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
        cancellation.throwIfCancelled()
        if (width != first.second || height != first.third) return@mapNotNull null
        decodeLumaFrame(
            index = index,
            file = files[index],
            sourceWidth = width,
            sourceHeight = height,
            proxyWidth = proxyWidth,
            proxyHeight = proxyHeight,
            cancellation = cancellation
        )
    }
}

private fun decodeLumaFrame(
    index: Int,
    file: File,
    sourceWidth: Int,
    sourceHeight: Int,
    proxyWidth: Int,
    proxyHeight: Int,
    cancellation: KeplerPipelineCancellation
): LumaFrame? {
    var sampleSize = 1
    while (sourceWidth / (sampleSize * 2) >= proxyWidth) sampleSize *= 2
    cancellation.throwIfCancelled()
    val decoded = NoFollowFileSystem.decodeBitmapVerified(
        file,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
    ) ?: return null
    var proxy: Bitmap? = null
    return try {
        cancellation.throwIfCancelled()
        proxy = if (decoded.width == proxyWidth && decoded.height == proxyHeight) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, proxyWidth, proxyHeight, true).also {
                decoded.recycle()
            }
        }
        val activeProxy = proxy ?: error("Proxy bitmap was not created.")
        val pixels = IntArray(proxyWidth * proxyHeight)
        activeProxy.getPixels(pixels, 0, proxyWidth, 0, 0, proxyWidth, proxyHeight)
        val luma = ByteArray(pixels.size)
        pixels.forEachIndexed { pixelIndex, color ->
            if ((pixelIndex and 4095) == 0) cancellation.throwIfCancelled()
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
            sharpness = run {
                cancellation.throwIfCancelled()
                calculateSharpness(luma, proxyWidth, proxyHeight)
            }
        )
    } finally {
        proxy?.takeUnless { it.isRecycled }?.recycle()
        if (proxy !== decoded) {
            decoded.takeUnless { it.isRecycled }?.recycle()
        }
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
    reference: LumaFrame,
    cancellation: KeplerPipelineCancellation
): List<FrameShift> {
    val proxyToSourceX = reference.sourceWidth.toFloat() / reference.proxyWidth
    val proxyToSourceY = reference.sourceHeight.toFloat() / reference.proxyHeight
    return frames.map { frame ->
        cancellation.throwIfCancelled()
        if (frame.index == reference.index) {
            FrameShift(frame.index, 0f, 0f, 0f, true)
        } else {
            val estimate = estimateTranslation(reference, frame, cancellation)
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

private fun estimateTranslation(
    reference: LumaFrame,
    frame: LumaFrame,
    cancellation: KeplerPipelineCancellation
): AlignmentEstimate {
    var bestDx = 0
    var bestDy = 0
    var bestScore = Float.MAX_VALUE
    for (dy in -ALIGNMENT_SEARCH_RADIUS..ALIGNMENT_SEARCH_RADIUS step 2) {
        cancellation.throwIfCancelled()
        for (dx in -ALIGNMENT_SEARCH_RADIUS..ALIGNMENT_SEARCH_RADIUS step 2) {
            val score = alignmentSad(reference, frame, dx, dy, 4, cancellation)
            if (score < bestScore) {
                bestScore = score
                bestDx = dx
                bestDy = dy
            }
        }
    }
    for (dy in bestDy - 2..bestDy + 2) {
        cancellation.throwIfCancelled()
        for (dx in bestDx - 2..bestDx + 2) {
            val score = alignmentSad(reference, frame, dx, dy, 2, cancellation)
            if (score < bestScore) {
                bestScore = score
                bestDx = dx
                bestDy = dy
            }
        }
    }

    val center = alignmentSad(reference, frame, bestDx, bestDy, 2, cancellation)
    val left = alignmentSad(reference, frame, bestDx - 1, bestDy, 2, cancellation)
    val right = alignmentSad(reference, frame, bestDx + 1, bestDy, 2, cancellation)
    val up = alignmentSad(reference, frame, bestDx, bestDy - 1, 2, cancellation)
    val down = alignmentSad(reference, frame, bestDx, bestDy + 1, 2, cancellation)
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
    stride: Int,
    cancellation: KeplerPipelineCancellation
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
    var processedRows = 0
    for (y in startY until endY step stride) {
        if ((processedRows++ and 15) == 0) cancellation.throwIfCancelled()
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

@Suppress("DEPRECATION")
private fun fuseFramesTiled(
    frames: List<LumaFrame>,
    shifts: List<FrameShift>,
    reference: LumaFrame,
    outputWidth: Int,
    outputHeight: Int,
    sink: SuperResolutionTileSink,
    processingParams: ClassicYuvFusionParams,
    denoiseAlgorithm: DenoiseAlgorithm,
    cancellation: KeplerPipelineCancellation
): File {
    val scaleX = outputWidth.toFloat() / reference.sourceWidth
    val scaleY = outputHeight.toFloat() / reference.sourceHeight
    val normalizedProcessingParams = processingParams.clamped()
    val shiftByIndex = shifts.associateBy { it.index }
    val maximumAcceptedShift = shifts
        .asSequence()
        .filter { it.accepted }
        .maxOfOrNull { max(abs(it.dx), abs(it.dy)) }
        ?: 0f
    val algorithmRadius = when (denoiseAlgorithm) {
        DenoiseAlgorithm.GUIDED -> 1
        DenoiseAlgorithm.WAVELET, DenoiseAlgorithm.BILATERAL -> 2
    }
    val outputHalo = maxOf(BILINEAR_HALO_RADIUS, algorithmRadius)
    val sourceHalo = ceil(maximumAcceptedShift).toInt() + outputHalo
    val decoders = linkedMapOf<Int, BitmapRegionDecoder>()
    var finished = false
    try {
        frames.forEach { frame ->
            cancellation.throwIfCancelled()
            decoders[frame.index] = BitmapRegionDecoder.newInstance(frame.file.absolutePath, false)
        }
        cancellation.throwIfCancelled()
        sink.begin(outputWidth, outputHeight)
        val orderedFrames = frames.sortedBy { if (it.index == reference.index) 0 else 1 }
        var tileY = 0
        while (tileY < outputHeight) {
            cancellation.throwIfCancelled()
            val tileHeight = minOf(FUSION_TILE_HEIGHT, outputHeight - tileY)
            var tileX = 0
            while (tileX < outputWidth) {
                cancellation.throwIfCancelled()
                val tileWidth = if (sink is StreamingPngTileSink) {
                    outputWidth
                } else {
                    minOf(FUSION_TILE_WIDTH, outputWidth - tileX)
                }
                val expandedX = maxOf(0, tileX - outputHalo)
                val expandedY = maxOf(0, tileY - outputHalo)
                val expandedRight = minOf(outputWidth, tileX + tileWidth + outputHalo)
                val expandedBottom = minOf(outputHeight, tileY + tileHeight + outputHalo)
                val expandedWidth = expandedRight - expandedX
                val expandedHeight = expandedBottom - expandedY
                val pixelCount = Math.multiplyExact(expandedWidth, expandedHeight)
                val accumR = FloatArray(pixelCount)
                val accumG = FloatArray(pixelCount)
                val accumB = FloatArray(pixelCount)
                val weights = FloatArray(pixelCount)
                val referenceLuma = FloatArray(pixelCount)

                orderedFrames.forEach { frame ->
                    cancellation.throwIfCancelled()
                    val shift = shiftByIndex.getValue(frame.index)
                    val region = decodeTileRegion(
                        decoder = decoders.getValue(frame.index),
                        sourceWidth = reference.sourceWidth,
                        sourceHeight = reference.sourceHeight,
                        outputTileX = expandedX,
                        outputTileY = expandedY,
                        outputTileWidth = expandedWidth,
                        outputTileHeight = expandedHeight,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        shift = shift,
                        sourceHalo = sourceHalo,
                        cancellation = cancellation
                    )
                    accumulateTile(
                        region = region,
                        tileX = expandedX,
                        tileY = expandedY,
                        tileWidth = expandedWidth,
                        tileHeight = expandedHeight,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        shift = shift,
                        isReference = frame.index == reference.index,
                        referenceLuma = referenceLuma,
                        accumR = accumR,
                        accumG = accumG,
                        accumB = accumB,
                        weights = weights,
                        processingParams = normalizedProcessingParams,
                        cancellation = cancellation
                    )
                }

                val outputPixels = normalizeTile(
                    accumR = accumR,
                    accumG = accumG,
                    accumB = accumB,
                    weights = weights,
                    tileWidth = expandedWidth,
                    tileHeight = expandedHeight,
                    processingParams = normalizedProcessingParams,
                    denoiseAlgorithm = denoiseAlgorithm,
                    cancellation = cancellation
                )
                cancellation.throwIfCancelled()
                val corePixels = IntArray(Math.multiplyExact(tileWidth, tileHeight))
                for (coreY in 0 until tileHeight) {
                    outputPixels.copyInto(
                        destination = corePixels,
                        destinationOffset = coreY * tileWidth,
                        startIndex = (tileY - expandedY + coreY) * expandedWidth + (tileX - expandedX),
                        endIndex = (tileY - expandedY + coreY) * expandedWidth + (tileX - expandedX) + tileWidth
                    )
                }
                sink.writeTile(tileX, tileY, tileWidth, tileHeight, corePixels)
                tileX += tileWidth
            }
            tileY += tileHeight
        }
        cancellation.throwIfCancelled()
        return sink.finish().also { finished = true }
    } finally {
        decoders.values.forEach { decoder -> runCatching { decoder.recycle() } }
        if (!finished) sink.abort()
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
    sourceHalo: Int,
    cancellation: KeplerPipelineCancellation
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
    cancellation.throwIfCancelled()
    val bitmap = decoder.decodeRegion(
        Rect(left, top, right, bottom),
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
    ) ?: error("Could not decode source strip.")
    return try {
        cancellation.throwIfCancelled()
        val pixels = IntArray(bitmap.width * bitmap.height)
        cancellation.throwIfCancelled()
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        cancellation.throwIfCancelled()
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
    weights: FloatArray,
    processingParams: ClassicYuvFusionParams,
    cancellation: KeplerPipelineCancellation
) {
    val alignmentWeight = if (isReference) {
        1f
    } else {
        ((1f - shift.score * 3f).coerceIn(0.35f, 1f) *
            (1f + processingParams.denoiseStrength * 1.25f))
            .coerceAtMost(1.65f)
    }
    for (localY in 0 until tileHeight) {
        if ((localY and 15) == 0) cancellation.throwIfCancelled()
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
                val robustWeight = when {
                    difference > OUTLIER_LUMA_THRESHOLD -> 0f
                    difference > 20f ->
                        alignmentWeight *
                            ((OUTLIER_LUMA_THRESHOLD - difference) / 15f)
                    else -> alignmentWeight
                }
                when (processingParams.fusionAlgorithm) {
                    FusionAlgorithm.ROBUST_REFERENCE -> robustWeight
                    FusionAlgorithm.NOISE_AWARE -> {
                        val signal = referenceLuma[index].coerceIn(0f, 255f)
                        val noiseVariance = (4f + 0.025f * signal).coerceAtLeast(1f)
                        robustWeight *
                            (1f / (1f + difference * difference / noiseVariance))
                    }
                    FusionAlgorithm.MOTION_SAFE -> {
                        if (difference > OUTLIER_LUMA_THRESHOLD * 0.65f) {
                            robustWeight * 0.08f
                        } else {
                            robustWeight * 0.72f
                        }
                    }
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
    weights: FloatArray,
    tileWidth: Int,
    tileHeight: Int,
    processingParams: ClassicYuvFusionParams,
    denoiseAlgorithm: DenoiseAlgorithm,
    cancellation: KeplerPipelineCancellation
): IntArray {
    val output = IntArray(weights.size)
    for (index in weights.indices) {
        if ((index and 4095) == 0) cancellation.throwIfCancelled()
        val weight = weights[index]
            output[index] = if (weight > 0f) {
                val red = (accumR[index] / weight).roundToInt().coerceIn(0, 255)
                val green = (accumG[index] / weight).roundToInt().coerceIn(0, 255)
                val blue = (accumB[index] / weight).roundToInt().coerceIn(0, 255)
                0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
        } else {
            0xff000000.toInt()
        }
    }
    applySuperResolutionDenoiseInPlace(
        pixels = output,
        width = tileWidth,
        height = tileHeight,
        strength = processingParams.denoiseStrength,
        algorithm = denoiseAlgorithm,
        cancellation = cancellation
    )
    for (index in output.indices) {
        if ((index and 4095) == 0) cancellation.throwIfCancelled()
        val color = output[index]
        output[index] = applySuperResolutionTone(
            red = color shr 16 and 0xff,
            green = color shr 8 and 0xff,
            blue = color and 0xff,
            params = processingParams
        )
    }
    applySuperResolutionDetailInPlace(
        pixels = output,
        width = tileWidth,
        height = tileHeight,
        params = processingParams,
        cancellation = cancellation
    )
    return output
}

internal fun applySuperResolutionDenoiseForTest(
    pixels: IntArray,
    width: Int,
    height: Int,
    strength: Float,
    algorithm: DenoiseAlgorithm
): IntArray {
    val copy = pixels.copyOf()
    applySuperResolutionDenoiseInPlace(copy, width, height, strength, algorithm, NoOpKeplerPipelineCancellation)
    return copy
}

private fun applySuperResolutionDenoiseInPlace(
    pixels: IntArray,
    width: Int,
    height: Int,
    strength: Float,
    algorithm: DenoiseAlgorithm,
    cancellation: KeplerPipelineCancellation
) {
    if (strength <= 0f || width < 5 || height < 5) return
    if (NativeImageEngine.processPixels(
            pixels, width, height, algorithm, strength, FUSION_TILE_HEIGHT, cancellation
        )) {
        return
    }
    val radius = 2
    val coreX0 = radius
    val coreY0 = radius
    val coreX1 = width - radius
    val coreY1 = height - radius
    if (coreX1 <= coreX0 || coreY1 <= coreY0) return
    val source = pixels.copyOf()
    val amount = strength.coerceIn(0f, 1f)
    for (y in coreY0 until coreY1) {
        if ((y and 15) == 0) cancellation.throwIfCancelled()
        for (x in coreX0 until coreX1) {
            val index = y * width + x
            val center = source[index]
            val cr = Color.red(center); val cg = Color.green(center); val cb = Color.blue(center)
            var sumR = 0; var sumG = 0; var sumB = 0; var count = 0
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val n = source[(y + dy) * width + (x + dx)]
                    sumR += Color.red(n); sumG += Color.green(n); sumB += Color.blue(n)
                    count++
                }
            }
            if (count == 0) continue
            val meanR = sumR.toFloat() / count
            val meanG = sumG.toFloat() / count
            val meanB = sumB.toFloat() / count
            val target = when (algorithm) {
                DenoiseAlgorithm.GUIDED -> {
                    val epsilon = 900f
                    var vR = 0f; var vG = 0f; var vB = 0f
                    for (dy2 in -radius..radius) {
                        for (dx2 in -radius..radius) {
                            val n2 = source[(y + dy2) * width + (x + dx2)]
                            val dR = Color.red(n2).toFloat() - meanR
                            val dG = Color.green(n2).toFloat() - meanG
                            val dB = Color.blue(n2).toFloat() - meanB
                            vR += dR * dR; vG += dG * dG; vB += dB * dB
                        }
                    }
                    val aR = vR / (vR + epsilon); val aG = vG / (vG + epsilon); val aB = vB / (vB + epsilon)
                    val bR = meanR - aR * meanR; val bG = meanG - aG * meanG; val bB = meanB - aB * meanB
                    Color.rgb(
                        (aR * cr + bR).roundToInt().coerceIn(0, 255),
                        (aG * cg + bG).roundToInt().coerceIn(0, 255),
                        (aB * cb + bB).roundToInt().coerceIn(0, 255)
                    )
                }
                DenoiseAlgorithm.WAVELET -> {
                    val threshold = 45f
                    val dR = kotlin.math.abs(cr.toFloat() - meanR)
                    val dG = kotlin.math.abs(cg.toFloat() - meanG)
                    val dB = kotlin.math.abs(cb.toFloat() - meanB)
                    Color.rgb(
                        (if (dR < threshold) meanR else cr.toFloat()).roundToInt().coerceIn(0, 255),
                        (if (dG < threshold) meanG else cg.toFloat()).roundToInt().coerceIn(0, 255),
                        (if (dB < threshold) meanB else cb.toFloat()).roundToInt().coerceIn(0, 255)
                    )
                }
                DenoiseAlgorithm.BILATERAL -> {
                    val sS2 = 1.5f; val rS2 = 750f
                    var sumRw = 0f; var sumGw = 0f; var sumBw = 0f; var totalW = 0f
                    for (dy2 in -radius..radius) {
                        for (dx2 in -radius..radius) {
                            val n2 = source[(y + dy2) * width + (x + dx2)]
                            val ndr = Color.red(n2).toFloat() - cr.toFloat()
                            val ndg = Color.green(n2).toFloat() - cg.toFloat()
                            val ndb = Color.blue(n2).toFloat() - cb.toFloat()
                            val dist2 = (dx2 * dx2 + dy2 * dy2).toFloat() / sS2 +
                                (2f * ndr * ndr + ndg * ndg + ndb * ndb) / rS2
                            val w = 1f / (1f + dist2)
                            sumRw += Color.red(n2).toFloat() * w
                            sumGw += Color.green(n2).toFloat() * w
                            sumBw += Color.blue(n2).toFloat() * w
                            totalW += w
                        }
                    }
                    if (totalW <= 0f) center else Color.rgb(
                        (sumRw / totalW).roundToInt().coerceIn(0, 255),
                        (sumGw / totalW).roundToInt().coerceIn(0, 255),
                        (sumBw / totalW).roundToInt().coerceIn(0, 255)
                    )
                }
            }
            pixels[index] = blendColorInt(center, target, amount)
        }
    }
}

private fun blendColorInt(a: Int, b: Int, amount: Float): Int {
    val clamped = amount.coerceIn(0f, 1f)
    val inv = 1f - clamped
    return Color.rgb(
        (Color.red(a) * inv + Color.red(b) * clamped).roundToInt().coerceIn(0, 255),
        (Color.green(a) * inv + Color.green(b) * clamped).roundToInt().coerceIn(0, 255),
        (Color.blue(a) * inv + Color.blue(b) * clamped).roundToInt().coerceIn(0, 255)
    )
}

private fun applySuperResolutionDetailInPlace(
    pixels: IntArray,
    width: Int,
    height: Int,
    params: ClassicYuvFusionParams,
    cancellation: KeplerPipelineCancellation
) {
    if (width < 3 || height < 3) return
    val amount = (params.sharpenAmount * 0.42f + params.localContrastAmount * 0.58f)
        .coerceIn(0f, 0.28f)
    if (amount <= 0f) return
    val source = pixels.copyOf()
    for (y in 1 until height - 1) {
        if ((y and 31) == 0) cancellation.throwIfCancelled()
        val row = y * width
        for (x in 1 until width - 1) {
            val index = row + x
            pixels[index] = sharpenPixel(
                center = source[index],
                left = source[index - 1],
                right = source[index + 1],
                up = source[index - width],
                down = source[index + width],
                amount = amount
            )
        }
    }
}

private fun applySuperResolutionTone(
    red: Int,
    green: Int,
    blue: Int,
    params: ClassicYuvFusionParams
): Int {
    val luma = rgbLuma(red, green, blue)
    val normalized = (luma / 255f).coerceIn(0f, 1f)
    val toned = when (params.toneAlgorithm) {
        NativeToneAlgorithm.NATURAL ->
            normalized * (0.92f + 0.08f * normalized) + 0.015f * (1f - normalized)
        NativeToneAlgorithm.LOCAL_COMPRESSION ->
            (normalized * (1f + 0.35f * (1f - normalized))) /
                (1f + 0.35f * normalized)
        NativeToneAlgorithm.NIGHT -> {
            val retained = (normalized - 0.012f).coerceAtLeast(0f) / 0.988f
            (retained / (retained + 0.22f * (1f - retained))).coerceAtMost(0.985f)
        }
    }.coerceIn(0f, 1f)
    val shadowLift = params.shadowLift * (1f - toned) * 255f
    val highlightStart = 190f
    val highlightCompression = if (luma > highlightStart) {
        (luma - highlightStart) * params.highlightRollOff
    } else {
        0f
    }
    val adjustedLuma = (toned * 255f + shadowLift - highlightCompression).coerceIn(0f, 255f)
    val saturation = params.saturationBoost

    fun channel(value: Int): Int {
        val saturated = adjustedLuma + (value - luma) * saturation
        return saturated.roundToInt().coerceIn(0, 255)
    }

    return 0xff000000.toInt() or
        (channel(red) shl 16) or
        (channel(green) shl 8) or
        channel(blue)
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

private fun applyMildUnsharpInPlace(
    bitmap: Bitmap,
    processingParams: ClassicYuvFusionParams,
    cancellation: KeplerPipelineCancellation
) {
    if (bitmap.width < 1 || bitmap.height < 1) return
    val params = processingParams.clamped()
    val width = bitmap.width
    val amount = (params.sharpenAmount * 0.55f + params.localContrastAmount * 0.45f)
        .coerceIn(0f, 0.28f)

    fun readTonedRow(y: Int): IntArray = IntArray(width).also { row ->
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        for (x in row.indices) {
            val color = row[x]
            row[x] = applySuperResolutionTone(
                red = color shr 16 and 0xff,
                green = color shr 8 and 0xff,
                blue = color and 0xff,
                params = params
            )
        }
    }

    var previous = IntArray(width)
    var current = readTonedRow(0)
    var next = if (bitmap.height > 1) readTonedRow(1) else current

    for (y in 0 until bitmap.height) {
        if ((y and 31) == 0) cancellation.throwIfCancelled()
        val following = if (y + 1 < bitmap.height) next else current
        val output = current.copyOf()
        if (amount > 0f && y in 1 until bitmap.height - 1) {
            for (x in 1 until width - 1) {
                output[x] = sharpenPixel(
                    center = current[x],
                    left = current[x - 1],
                    right = current[x + 1],
                    up = previous[x],
                    down = following[x],
                    amount = amount
                )
            }
        }
        bitmap.setPixels(output, 0, width, 0, y, width, 1)
        previous = current
        current = next
        next = if (y + 2 < bitmap.height) readTonedRow(y + 2) else current
    }
}

private fun sharpenPixel(
    center: Int,
    left: Int,
    right: Int,
    up: Int,
    down: Int,
    amount: Float
): Int {
    fun channel(shift: Int): Int {
        val centerValue = center shr shift and 0xff
        val neighbors = ((left shr shift and 0xff) + (right shr shift and 0xff) +
            (up shr shift and 0xff) + (down shr shift and 0xff)) / 4f
        return (centerValue + amount * (centerValue - neighbors))
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
    reason: String,
    processingAttempt: ProcessingAttempt
): SuperResolutionFusionResult {
    if (targetMegapixels > request.targetPolicy.maxSafeTargetMegapixels) {
        return failedSuperResolutionResult(
            request = request,
            inputFrameCount = request.inputFrameFiles.size,
            message = "$reason Streaming single-frame fallback is not implemented.",
            shifts = shifts,
            processingAttempt = processingAttempt
        )
    }
    request.status("${superResolutionStatusLabel(request)}: writing output...")
    request.cancellation.throwIfCancelled()
    val source = NoFollowFileSystem.decodeBitmapVerified(
        reference.file,
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
    ) ?: return failedSuperResolutionResult(
            request,
            request.inputFrameFiles.size,
            "Fallback reference decode failed.",
            shifts,
            processingAttempt
        )
    var output: Bitmap? = null
    return try {
        request.cancellation.throwIfCancelled()
        output = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        request.cancellation.throwIfCancelled()
        applyMildUnsharpInPlace(
            output!!,
            request.processingParams,
            request.cancellation
        )
        val outputFile = File(
            request.outputDir,
            superResolutionOutputFileName(targetMegapixels)
        )
        request.cancellation.throwIfCancelled()
        val artifact = saveJpeg(
            output!!,
            outputFile,
            cancellation = request.cancellation,
            onSettlement = processingArtifactSettlementObserver(request.outputDir, processingAttempt),
            processingAttempt = processingAttempt,
            claimKey = "superResolutionOutputFile"
        )
        val actualOutputMegapixels = megapixels(output!!.width, output!!.height)
        val result = SuperResolutionFusionResult(
            outputFile = outputFile,
            outputWidth = output!!.width,
            outputHeight = output!!.height,
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
        markProcessingArtifactClaim(request.outputDir, processingAttempt, "superResolutionOutputFile", requireNotNull(result.outputFile))
        val postCommitCancellation = request.cancellation.isCancelled
        writeSuperResolutionJob(request, result, "COMPLETE", reason, processingAttempt)
        if (postCommitCancellation) {
            markProcessingPostCommitCancellation(request.outputDir, processingAttempt)
        }
        result
    } finally {
        output?.takeUnless { it.isRecycled }?.recycle()
        if (output !== source) source.takeUnless { it.isRecycled }?.recycle()
    }
}

private fun failedSuperResolutionResult(
    request: SuperResolutionFusionRequest,
    inputFrameCount: Int,
    message: String,
    shifts: List<FrameShift> = emptyList(),
    processingAttempt: ProcessingAttempt? = null
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
    runCatching { writeSuperResolutionJob(request, result, "FAILED", message, processingAttempt) }
    return result
}

private fun writeSuperResolutionJob(
    request: SuperResolutionFusionRequest,
    result: SuperResolutionFusionResult,
    status: String,
    reason: String?,
    processingAttempt: ProcessingAttempt? = null
) {
    val priorAttempt = runCatching {
        NoFollowFileSystem.resolveDirectChildResult(request.outputDir, SUPER_RES_JOB_FILE, requireFile = true)
            .let { result ->
                if (result is NoFollowInspection.Present) {
                    JSONObject(NoFollowFileSystem.readTextVerified(result.value))
                } else null
            }
    }.getOrNull()
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
        .put("captureMode", CaptureMode.MULTI_FRAME.name)
        .put("processingPresetName", request.processingParams.clamped().presetName)
        .put("processingParams", request.processingParams.clamped().toJson())
        .put("fusionPresetName", request.processingParams.clamped().presetName)
        .put("fusionParams", request.processingParams.clamped().toJson())
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
    priorAttempt?.optString("processingAttemptId")?.takeIf { it.isNotBlank() }?.let {
        job.put("processingAttemptId", it)
            .put("processingStartedAt", priorAttempt.optLong("processingStartedAt"))
            .put("processingMode", priorAttempt.optString("processingMode", "SUPER_RESOLUTION"))
    }
    if (processingAttempt == null) {
        KeplerJobMetadata.write(request.outputDir, job)
    } else {
        updateForProcessingAttempt(request.outputDir, processingAttempt) { current ->
            val keys = job.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                current.put(key, job.get(key))
            }
        }
    }
}

private fun readColorBurstFrameFiles(jobDir: File): List<File> {
    val jobFile = NoFollowFileSystem.requireDirectChildFile(jobDir, SUPER_RES_JOB_FILE)
        val frames = JSONObject(NoFollowFileSystem.readTextVerified(jobFile)).optJSONArray("frames") ?: JSONArray()
    return buildList {
        for (index in 0 until frames.length()) {
            val name = frames.optJSONObject(index)?.optString("file").orEmpty()
            if (name.isNotBlank()) {
                NoFollowFileSystem.optionalDirectChildFile(jobDir, name)?.let(::add)
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
    return File(root, "KPL_SUPER_RES_${timestamp}_${UUID.randomUUID().toString().take(8)}").also {
        check(it.exists() || it.mkdirs()) { "Could not create SuperRes job directory." }
    }
}

private fun saveJpeg(
    bitmap: Bitmap,
    outputFile: File,
    quality: Int = JPEG_QUALITY,
    cancellation: KeplerPipelineCancellation? = null,
    onSettlement: ((ProcessingArtifactSettlementReport) -> Unit)? = null
    ,processingAttempt: ProcessingAttempt? = null
    ,claimKey: String? = null
): ProcessingArtifactResult {
    return commitProcessingArtifact(
        finalFile = outputFile,
        cancellation = cancellation,
        onSettlement = onSettlement,
        processingAttemptId = processingAttempt?.id,
        claimKey = claimKey,
        writeTemp = { temporary ->
            FileOutputStream(temporary).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    "JPEG encode failed."
                }
                output.fd.sync()
            }
        },
        verifyFinal = { committed ->
            verifyJpegArtifact(committed)
        }
    )
}

private fun estimateFusionWorkingBytes(
    outputWidth: Int,
    outputHeight: Int,
    includesOutputBitmap: Boolean
): Long {
    return runCatching {
        val outputBytes = if (includesOutputBitmap) {
            checkedBitmapBytes(outputWidth, outputHeight)
        } else 0L
        val tilePixels = Math.multiplyExact(
            minOf(FUSION_TILE_WIDTH, outputWidth),
            minOf(FUSION_TILE_HEIGHT, outputHeight)
        ).toLong()
        val tileBytes = Math.multiplyExact(tilePixels, 29L)
        Math.addExact(Math.addExact(outputBytes, tileBytes), 64L * 1024L * 1024L)
    }.getOrElse { Long.MAX_VALUE }
}

private fun availableHeapBytes(): Long {
    return currentAvailableJavaHeapBytes()
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
    NoFollowFileSystem.decodeBitmapVerified(file, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        megapixels(bounds.outWidth, bounds.outHeight)
    } else {
        0.0
    }
}

private fun canAllocateOutputBitmap(outputWidth: Int, outputHeight: Int): Boolean {
    val outputBytes = runCatching { checkedBitmapBytes(outputWidth, outputHeight) }
        .getOrElse { return false }
    val required = runCatching { Math.addExact(outputBytes, 64L * 1024L * 1024L) }
        .getOrElse { return false }
    return availableHeapBytes() >= required
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
