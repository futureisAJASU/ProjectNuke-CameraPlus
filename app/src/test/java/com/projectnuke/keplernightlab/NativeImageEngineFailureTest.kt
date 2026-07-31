package com.projectnuke.keplernightlab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeImageEngineFailureTest {
    @Test
    fun failedNativeStatusDoesNotCopyZeroInitializedPixels() {
        val original = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt())
        val before = original.copyOf()
        val previous = NativeImageEngine.nativeProcessOverride
        try {
            NativeImageEngine.nativeProcessOverride = { NativeProcessStatus.ARRAY_ACQUIRE_FAILED.ordinal }
            assertFalse(
                NativeImageEngine.processPixels(
                    original,
                    width = 2,
                    height = 1,
                    denoise = DenoiseAlgorithm.GUIDED,
                    strength = 0.5f
                )
            )
            assertArrayEquals(before, original)
            assertEquals(NativeProcessStatus.ARRAY_ACQUIRE_FAILED, NativeImageEngine.lastProcessStatus)
        } finally {
            NativeImageEngine.nativeProcessOverride = previous
        }
    }

    @Test
    fun bitmapPathUsesReusableOversizedArraysForShortTiles() {
        val source = android.graphics.Bitmap.createBitmap(5, 131, android.graphics.Bitmap.Config.ARGB_8888)
        val heights = mutableListOf<Int>()
        val previous = NativeImageEngine.nativeProcessOverride
        try {
            source.eraseColor(0xFF223344.toInt())
            NativeImageEngine.nativeProcessOverride = { invocation ->
                heights += invocation.height
                invocation.source.copyInto(invocation.output, 0, 0, invocation.width * invocation.height)
                NativeProcessStatus.SUCCESS.ordinal
            }
            val result = NativeImageEngine.process(
                source = source,
                denoise = DenoiseAlgorithm.GUIDED,
                tone = NativeToneAlgorithm.NATURAL,
                denoiseStrength = 0.2f,
                sharpen = 0f,
                localContrast = 0f,
                tileRows = 64
            )
            assertEquals(listOf(66, 68, 5), heights)
            assertEquals(source.width, result?.width)
            assertEquals(source.height, result?.height)
            result?.recycle()
        } finally {
            NativeImageEngine.nativeProcessOverride = previous
            source.recycle()
        }
    }

}
