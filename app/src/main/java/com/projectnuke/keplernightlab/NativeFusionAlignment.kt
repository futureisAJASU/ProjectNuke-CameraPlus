package com.projectnuke.keplernightlab

data class NativeAlignmentResult(
    val dx: Float,
    val dy: Float,
    val integerDx: Int,
    val integerDy: Int,
    val subpixelDx: Float,
    val subpixelDy: Float,
    val score: Float,
    val confidence: Float,
    val usedSubpixel: Boolean,
    val backend: String = "native_subpixel_v1"
)

object NativeFusionAlignment {
    private val loaded = runCatching {
        System.loadLibrary("kepler_raw_engine")
    }.isSuccess

    fun isAvailable(): Boolean = loaded

    fun alignLumaFrames(
        reference: ByteArray,
        candidate: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        searchRadius: Int
    ): NativeAlignmentResult? {
        if (!loaded) return null
        val result = runCatching {
            nativeAlignLumaFrames(reference, candidate, width, height, rowStride, searchRadius)
        }.getOrNull() ?: return null
        if (result.size != 9 || result.any { !it.isFinite() }) return null
        return NativeAlignmentResult(
            dx = result[0],
            dy = result[1],
            integerDx = result[2].toInt(),
            integerDy = result[3].toInt(),
            subpixelDx = result[4],
            subpixelDy = result[5],
            score = result[6],
            confidence = result[7].coerceIn(0f, 1f),
            usedSubpixel = result[8] >= 0.5f
        )
    }

    private external fun nativeAlignLumaFrames(
        reference: ByteArray,
        candidate: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        searchRadius: Int
    ): FloatArray?
}
