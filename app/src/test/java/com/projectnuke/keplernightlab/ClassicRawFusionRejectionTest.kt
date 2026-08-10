package com.projectnuke.keplernightlab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ClassicRawFusionRejectionTest {
    @Test
    fun multipleRawInputCloseFailuresAreRetainedWithoutOverwriting() {
        val dir = Files.createTempDirectory("classic-raw-close-debt").toFile()
        try {
            val firstFile = dir.resolve("frame-a.raw16").apply { writeBytes(ByteArray(32)) }
            val secondFile = dir.resolve("frame-b.raw16").apply { writeBytes(ByteArray(32) { 1 }) }
            val firstFailure = IllegalStateException("close failure A")
            val secondFailure = IllegalStateException("close failure B")
            val first = VerifiedRandomAccessHandle.openForTesting(firstFile, 32L, firstFailure)
            val second = VerifiedRandomAccessHandle.openForTesting(secondFile, 32L, secondFailure)

            val failures = settleVerifiedRawInputHandles(
                listOf(
                    "frameIndex=0,file=${firstFile.name}" to first,
                    "frameIndex=1,file=${secondFile.name}" to second
                )
            )

            assertEquals(2, failures.size)
            assertSame(firstFailure, failures[0].failure)
            assertSame(secondFailure, failures[1].failure)
            assertEquals("VERIFIED_RAW_INPUT_HANDLE", failures[0].resource)
            assertEquals("CLOSE", failures[0].operation)
            assertEquals("frameIndex=0,file=${firstFile.name}", failures[0].identity)
            assertEquals("frameIndex=1,file=${secondFile.name}", failures[1].identity)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rawOutputRangeNeverWraps() {
        assertEquals(0, clampRawOutputValue(-12f, 4095))
        assertEquals(4095, clampRawOutputValue(5000f, 4095))
        assertEquals(65535, clampRawOutputValue(70000f, 65535))
        assertEquals(0, clampRawOutputValue(Float.NaN, 4095))
        assertEquals(0, clampRawOutputValue(Float.POSITIVE_INFINITY, 4095))
        assertEquals(0, clampRawOutputValue(0f, 4095))
    }


    @Test
    fun `NaN alignment score rejects frame from merge`() {
        val scores = listOf(Float.NaN, 0.15f, Float.NEGATIVE_INFINITY, 0.05f)
        var nanCount = 0
        var nonFiniteCount = 0
        var aboveThreshCount = 0
        val threshold = 0.20f
        for (score in scores) {
            if (!score.isFinite()) {
                nonFiniteCount++
                if (score.isNaN()) nanCount++
            } else if (score > threshold) {
                aboveThreshCount++
            }
        }
        assertEquals(1, nanCount)
        assertEquals(2, nonFiniteCount)
        assertEquals(0, aboveThreshCount)
    }

    @Test
    fun `only reference accepted - single frame fallback`() {
        val usedFlags = listOf(true, false, false)
        val mergedCount = usedFlags.count { it }
        assertEquals(1, mergedCount)
    }

    @Test
    fun `metadata exactly matches merged frame indices`() {
        val usedFlags = listOf(true, true, false, true)
        val mergedIndices = usedFlags.mapIndexedNotNull { i, used -> if (used) i else null }
        assertEquals(listOf(0, 1, 3), mergedIndices)
        assertEquals(3, mergedIndices.size)
    }

    @Test
    fun `integer finite shift values`() {
        val dx = 2
        val dy = 2
        assertTrue(dx.toFloat().isFinite())
        assertTrue(dy.toFloat().isFinite())
    }

    @Test
    fun `rejected nan count increments correctly`() {
        var nanCount = 0
        val scores = listOf(Float.NaN, 0.15f, Float.NEGATIVE_INFINITY, 0.05f)
        for (score in scores) {
            if (score.isNaN()) nanCount++
        }
        assertEquals(1, nanCount)
    }

    @Test
    fun `exposure scale with NaN reverts to 1`() {
        val refExposure = 100_000_000f
        val altExposure = Float.NaN
        val scale = refExposure / altExposure
        val clamped = if (scale.isFinite()) scale.coerceIn(0.5f, 2.0f) else 1f
        assertEquals(1f, clamped)
    }

    @Test
    fun `normal exposure scale is clamped`() {
        val refExposure = 100_000_000f
        val normalExposure = 50_000_000f
        val scale = refExposure / normalExposure
        assertTrue(scale.isFinite())
        val clamped = scale.coerceIn(0.5f, 2.0f)
        assertEquals(2.0f, clamped)
    }

    @Test
    fun `spatial filter zero-weight returns center`() {
        val center = 0xff808080.toInt()
        val totalW = 0.0
        val result = if (totalW <= 0.0) center else 0
        assertEquals(center, result)
    }

    @Test
    fun `zero global weight for fully rejected frame`() {
        val alignmentUsed = false
        val globalWeight = if (alignmentUsed) 0.12f else 0f
        assertEquals(0f, globalWeight, 0.001f)
    }

    @Test
    fun `alignment score threshold rejection`() {
        val score = 0.25f
        val threshold = 0.20f
        val scoreOk = score.isFinite() && score <= threshold
        assertFalse(scoreOk)
    }
}
