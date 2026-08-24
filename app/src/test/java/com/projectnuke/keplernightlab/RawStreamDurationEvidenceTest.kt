package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2: the RAW stream duration evidence must read the PUBLIC Camera2
 * surface (StreamConfigurationMap min-frame/stall durations) truthfully and
 * treat "0 ns" as not-advertised, so physical reports can compare observed
 * cameraAcquisitionMs against HAL pacing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RawStreamDurationEvidenceTest {

    @Test
    fun rawStreamDurations_missingMapYieldsNullEvidence() {
        val evidence = RawStreamDurationEvidence.fromMap(
            map = null, width = 4080, height = 3060, format = 0x20, sourceMap = null
        )
        assertNull(evidence.minFrameDurationNs)
        assertNull(evidence.stallDurationNs)
        assertNull(evidence.sourceMap)
    }

    @Test
    fun rawStreamDurations_mapKeyFollowsPixelMode() {
        // The key selector must route maximum-resolution pixel mode to the
        // MAXIMUM_RESOLUTION table (public Camera2 keys) and normal captures
        // to the standard table.
        val maxKey = RawStreamDurationEvidence.streamConfigurationMapKey(true)
        val stdKey = RawStreamDurationEvidence.streamConfigurationMapKey(false)
        assertTrue(maxKey !== stdKey)
        assertEquals(
            android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            stdKey
        )
    }

    @Test
    fun rawStreamDurations_zeroMeansNotAdvertised() {
        val evidence = RawStreamDurationEvidence(
            width = 4080,
            height = 3060,
            format = 0x20,
            minFrameDurationNs = 0L.takeIf { it > 0 },
            stallDurationNs = 33_000_000L,
            sourceMap = RawStreamDurationEvidence.MAP_STANDARD
        )
        assertNull(evidence.minFrameDurationNs)
        assertEquals(33_000_000L, evidence.stallDurationNs)
    }

    @Test
    fun rawStreamDurations_jsonExposesBoundedEvidence() {
        val evidence = RawStreamDurationEvidence(
            width = 4080,
            height = 3060,
            format = 0x20,
            minFrameDurationNs = 33_000_000L,
            stallDurationNs = null,
            sourceMap = RawStreamDurationEvidence.MAP_MAXIMUM_RESOLUTION
        )
        val json = evidence.toJson()
        assertEquals(4080, json.getInt("width"))
        assertEquals(3060, json.getInt("height"))
        assertEquals(33_000_000L, json.getLong("minFrameDurationNs"))
        assertEquals(true, json.isNull("stallDurationNs"))
        assertEquals(RawStreamDurationEvidence.MAP_MAXIMUM_RESOLUTION, json.getString("sourceMap"))
        // 1e9 / 33ms ~= 30.3 advertised fps ceiling.
        assertEquals(30.303, json.getDouble("advertisedMaxFps"), 0.01)

        val unset = RawStreamDurationEvidence(1, 1, 0x20, null, null, null).toJson()
        assertEquals(true, unset.isNull("advertisedMaxFps"))
        assertEquals(true, unset.isNull("sourceMap"))
    }
}
