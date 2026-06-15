package com.projectnuke.keplernightlab

data class FrameQualityNativeMetrics(
    val sharpnessRaw: Float,
    val brightnessMean: Float,
    val brightnessStdDev: Float,
    val clippedShadowRatio: Float,
    val clippedHighlightRatio: Float
)

object NativeFrameQuality {
    private val loaded = runCatching {
        System.loadLibrary("kepler_raw_engine")
    }.isSuccess

    fun isAvailable(): Boolean = loaded

    fun scoreLumaFrame(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int
    ): FrameQualityNativeMetrics? {
        if (!loaded) return null
        val result = runCatching {
            nativeScoreLumaFrame(luma, width, height, rowStride)
        }.getOrNull() ?: return null
        if (result.size != 5 || result.any { !it.isFinite() }) return null
        return FrameQualityNativeMetrics(
            sharpnessRaw = result[0],
            brightnessMean = result[1],
            brightnessStdDev = result[2],
            clippedShadowRatio = result[3],
            clippedHighlightRatio = result[4]
        )
    }

    private external fun nativeScoreLumaFrame(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int
    ): FloatArray?
}
