package com.projectnuke.keplernightlab

import android.graphics.Bitmap

enum class NativeToneAlgorithm { NATURAL, LOCAL_COMPRESSION, NIGHT }
enum class NativeFusionAlgorithm { ROBUST_REFERENCE, NOISE_AWARE, MOTION_SAFE }

object NativeImageEngine {
    private val loaded = runCatching { System.loadLibrary("kepler_raw_engine") }.isSuccess

    fun isAvailable(): Boolean = loaded

    fun process(
        source: Bitmap,
        denoise: DenoiseAlgorithm,
        tone: NativeToneAlgorithm,
        denoiseStrength: Float,
        sharpen: Float,
        localContrast: Float,
        tileRows: Int = 64,
        cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
    ): Bitmap? {
        if (!loaded || source.width <= 0 || source.height <= 0) return null
        val safeRows = tileRows.coerceIn(1, 512)
        val halo = 2
        val bufferHeight = (safeRows + halo * 2).coerceAtMost(source.height)
        val input = IntArray(source.width * bufferHeight)
        val output = IntArray(input.size)
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        var top = 0
        while (top < source.height) {
            cancellation.throwIfCancelled()
            val bottom = (top + safeRows).coerceAtMost(source.height)
            val sourceTop = (top - halo).coerceAtLeast(0)
            val sourceBottom = (bottom + halo).coerceAtMost(source.height)
            val sourceHeight = sourceBottom - sourceTop
            source.getPixels(input, 0, source.width, 0, sourceTop, source.width, sourceHeight)
            nativeProcessArgb(
                input, output, source.width, sourceHeight,
                denoise.ordinal, tone.ordinal,
                denoiseStrength.safeFinite().coerceIn(0f, 1f),
                sharpen.safeFinite().coerceIn(0f, 1f),
                localContrast.safeFinite().coerceIn(0f, 1f),
                safeRows
            )
            result.setPixels(
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
        if (!loaded || width <= 0 || height <= 0 || pixels.size < width * height) return false
        cancellation.throwIfCancelled()
        val output = IntArray(width * height)
        nativeProcessArgb(
            pixels, output, width, height, denoise.ordinal,
            NativeToneAlgorithm.NATURAL.ordinal, strength.safeFinite(), 0f, 0f,
            tileRows.coerceIn(1, 512)
        )
        output.copyInto(pixels)
        return true
    }

    private external fun nativeProcessArgb(
        source: IntArray, output: IntArray, width: Int, height: Int,
        denoise: Int, tone: Int, denoiseStrength: Float, sharpen: Float,
        localContrast: Float, tileRows: Int
    )
}

private fun Float.safeFinite(): Float = if (isFinite()) this else 0f
