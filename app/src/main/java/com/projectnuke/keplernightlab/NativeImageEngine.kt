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
        tileRows: Int = 64
    ): Bitmap? {
        if (!loaded || source.width <= 0 || source.height <= 0) return null
        val input = IntArray(source.width * source.height)
        val output = IntArray(input.size)
        source.getPixels(input, 0, source.width, 0, 0, source.width, source.height)
        nativeProcessArgb(
            input, output, source.width, source.height,
            denoise.ordinal, tone.ordinal,
            denoiseStrength.safeFinite().coerceIn(0f, 1f),
            sharpen.safeFinite().coerceIn(0f, 1f),
            localContrast.safeFinite().coerceIn(0f, 1f),
            tileRows.coerceIn(1, 512)
        )
        return Bitmap.createBitmap(output, source.width, source.height, Bitmap.Config.ARGB_8888)
    }

    internal fun processPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        denoise: DenoiseAlgorithm,
        strength: Float,
        tileRows: Int = 64
    ): Boolean {
        if (!loaded || width <= 0 || height <= 0 || pixels.size < width * height) return false
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
