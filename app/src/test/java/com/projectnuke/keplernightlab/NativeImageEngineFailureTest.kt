package com.projectnuke.keplernightlab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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

}
