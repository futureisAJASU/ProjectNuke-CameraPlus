package com.projectnuke.keplernightlab

import android.graphics.Bitmap

enum class NativeToneAlgorithm { NATURAL, LOCAL_COMPRESSION, NIGHT }
enum class FusionAlgorithm { ROBUST_REFERENCE, NOISE_AWARE, MOTION_SAFE }

internal enum class NativeProcessStatus {
    SUCCESS,
    INVALID_ARGUMENT,
    ARRAY_LENGTH_MISMATCH,
    ARRAY_ACQUIRE_FAILED,
    PROCESSING_FAILED
}

internal data class NativeProcessInvocation(
    val source: IntArray,
    val output: IntArray,
    val width: Int,
    val height: Int,
    val denoise: Int,
    val tone: Int,
    val denoiseStrength: Float,
    val sharpen: Float,
    val localContrast: Float,
    val shadowLift: Float,
    val highlightRollOff: Float,
    val saturation: Float,
    val tileRows: Int
)

object NativeImageEngine {
    @Volatile
    internal var nativeProcessOverride: ((NativeProcessInvocation) -> Int)? = null

    @Volatile
    internal var lastProcessStatus: NativeProcessStatus = NativeProcessStatus.SUCCESS

    private val loaded = try {
        System.loadLibrary("kepler_raw_engine")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun isAvailable(): Boolean = loaded

    fun process(
        source: Bitmap,
        denoise: DenoiseAlgorithm,
        tone: NativeToneAlgorithm,
        denoiseStrength: Float,
        sharpen: Float,
        localContrast: Float,
        shadowLift: Float = 0f,
        highlightRollOff: Float = 0f,
        saturation: Float = 1f,
        tileRows: Int = 64,
        cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
    ): Bitmap? {
        if ((!loaded && nativeProcessOverride == null) || source.width <= 0 || source.height <= 0) return null
        if (checkedPixelCount(source.width, source.height) == null) return null
        val safeRows = tileRows.coerceIn(1, 512)
        val halo = 2
        val bufferHeight = (safeRows + halo * 2).coerceAtMost(source.height)
        val bufferPixels = checkedPixelCount(source.width, bufferHeight) ?: return null
        val input = IntArray(bufferPixels)
        val output = IntArray(input.size)
        var result: Bitmap? = null
        try {
            val outputBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            result = outputBitmap
            var top = 0
            while (top < source.height) {
                cancellation.throwIfCancelled()
                val bottom = (top + safeRows).coerceAtMost(source.height)
                val sourceTop = (top - halo).coerceAtLeast(0)
                val sourceBottom = (bottom + halo).coerceAtMost(source.height)
                val sourceHeight = sourceBottom - sourceTop
                source.getPixels(input, 0, source.width, 0, sourceTop, source.width, sourceHeight)
                val status = invokeNative(
                    NativeProcessInvocation(
                        source = input,
                        output = output,
                        width = source.width,
                        height = sourceHeight,
                        denoise = denoise.ordinal,
                        tone = tone.ordinal,
                        denoiseStrength = denoiseStrength.safeFinite().coerceIn(0f, 1f),
                        sharpen = sharpen.safeFinite().coerceIn(0f, 1f),
                        localContrast = localContrast.safeFinite().coerceIn(0f, 1f),
                        shadowLift = shadowLift.safeFinite().coerceIn(0f, 1f),
                        highlightRollOff = highlightRollOff.safeFinite().coerceIn(0f, 1f),
                        saturation = saturation.safeFinite().coerceIn(0f, 2f),
                        tileRows = safeRows
                    )
                )
                if (status != NativeProcessStatus.SUCCESS) {
                    outputBitmap.recycle()
                    result = null
                    return null
                }
                outputBitmap.setPixels(
                    output,
                    (top - sourceTop) * source.width,
                    source.width,
                    0,
                    top,
                    source.width,
                    bottom - top
                )
                top = bottom
            }
            return result
        } catch (t: Throwable) {
            result?.recycle()
            throw t
        }
    }

    internal fun processPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        denoise: DenoiseAlgorithm,
        strength: Float,
        tileRows: Int = 64,
        cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
    ): Boolean {
        val pixelCount = checkedPixelCount(width, height) ?: return false
        if ((!loaded && nativeProcessOverride == null) || pixels.size < pixelCount) return false
        cancellation.throwIfCancelled()
        val output = IntArray(pixelCount)
        val status = invokeNative(
            NativeProcessInvocation(
                source = pixels,
                output = output,
                width = width,
                height = height,
                denoise = denoise.ordinal,
                tone = -1,
                denoiseStrength = strength.safeFinite(),
                sharpen = 0f,
                localContrast = 0f,
                shadowLift = 0f,
                highlightRollOff = 0f,
                saturation = 1f,
                tileRows = tileRows.coerceIn(1, 512)
            )
        )
        if (status != NativeProcessStatus.SUCCESS) return false
        output.copyInto(pixels)
        return true
    }

    private fun invokeNative(invocation: NativeProcessInvocation): NativeProcessStatus {
        val raw = nativeProcessOverride?.invoke(invocation) ?: nativeProcessArgb(
            invocation.source,
            invocation.output,
            invocation.width,
            invocation.height,
            invocation.denoise,
            invocation.tone,
            invocation.denoiseStrength,
            invocation.sharpen,
            invocation.localContrast,
            invocation.shadowLift,
            invocation.highlightRollOff,
            invocation.saturation,
            invocation.tileRows
        )
        val status = when (raw) {
            0 -> NativeProcessStatus.SUCCESS
            1 -> NativeProcessStatus.INVALID_ARGUMENT
            2 -> NativeProcessStatus.ARRAY_LENGTH_MISMATCH
            3 -> NativeProcessStatus.ARRAY_ACQUIRE_FAILED
            else -> NativeProcessStatus.PROCESSING_FAILED
        }
        lastProcessStatus = status
        return status
    }

    private external fun nativeProcessArgb(
        source: IntArray, output: IntArray, width: Int, height: Int,
        denoise: Int, tone: Int, denoiseStrength: Float, sharpen: Float,
        localContrast: Float, shadowLift: Float, highlightRollOff: Float,
        saturation: Float, tileRows: Int
    ): Int
}

private fun Float.safeFinite(): Float = if (isFinite()) this else 0f

private fun checkedPixelCount(width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0) return null
    return try {
        Math.multiplyExact(width, height)
    } catch (_: ArithmeticException) {
        null
    }
}
