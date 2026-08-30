package com.projectnuke.keplernightlab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewFocusPointMappingTest {

    @Test
    fun displayPointIsIdentityInEveryLayout() {
        val size = Size(400f, 300f)
        val tap = Offset(100f, 90f)
        val point = normalizeDisplayPoint(tap, size)!!
        // The display-normalized point must stay exactly at the tap location,
        // independent of the layout mode. Rotation is applied only later for
        // sensor metering, never for the on-screen marker.
        assertEquals(100f / 400f, point.x, 0.001f)
        assertEquals(90f / 300f, point.y, 0.001f)
    }

    @Test
    fun nullForInvalidSize() {
        assertNull(normalizeDisplayPoint(Offset(10f, 10f), Size(0f, 100f)))
        assertNull(normalizeDisplayPoint(Offset(10f, 10f), Size(100f, 0f)))
        assertNull(normalizeDisplayPoint(Offset(10f, 10f), Size(-1f, 100f)))
    }

    @Test
    fun displayPointClampsToUnitRange() {
        val point = normalizeDisplayPoint(Offset(4_000f, -20f), Size(400f, 300f))!!
        assertEquals(1f, point.x, 0.001f)
        assertEquals(0f, point.y, 0.001f)
    }

    @Test
    fun centerMapsToCenterUnderEveryRotation() {
        val center = NormalizedPoint(0.5f, 0.5f)
        for (sensor in listOf(0, 90, 180, 270)) {
            for (display in listOf(
                android.view.Surface.ROTATION_0,
                android.view.Surface.ROTATION_90,
                android.view.Surface.ROTATION_180,
                android.view.Surface.ROTATION_270
            )) {
                val sensorPoint = transformDisplayPointToSensorPoint(center, sensor, display)
                assertEquals("sensor=$sensor display=$display", 0.5f, sensorPoint.x, 0.001f)
                assertEquals("sensor=$sensor display=$display", 0.5f, sensorPoint.y, 0.001f)
            }
        }
    }

    @Test
    fun sensorTransformIsSeparateFromDisplayPoint() {
        // The screen marker uses the raw display point; the sensor metering
        // point is produced by a distinct call with orientation inputs.
        val displayPoint = NormalizedPoint(0.25f, 0.75f)
        val sensorPoint = transformDisplayPointToSensorPoint(
            displayPoint = displayPoint,
            sensorOrientationDegrees = 90,
            displayRotation = android.view.Surface.ROTATION_90
        )
        // relativeRotation = (90 - 90) % 360 = 0 -> identity
        assertEquals(0.25f, sensorPoint.x, 0.001f)
        assertEquals(0.75f, sensorPoint.y, 0.001f)
        // And the display point itself is unchanged.
        assertEquals(0.25f, displayPoint.x, 0.001f)
        assertEquals(0.75f, displayPoint.y, 0.001f)
    }

    @Test
    fun relativeRotationZeroIsIdentity() {
        val point = NormalizedPoint(0.2f, 0.6f)
        val out = transformDisplayPointToSensorPoint(
            point, sensorOrientationDegrees = 90, displayRotation = android.view.Surface.ROTATION_90
        )
        assertEquals(0.2f, out.x, 0.001f)
        assertEquals(0.6f, out.y, 0.001f)
    }

    @Test
    fun relativeRotation90CounterRotates() {
        val point = NormalizedPoint(1f, 0f)
        val out = transformDisplayPointToSensorPoint(
            point, sensorOrientationDegrees = 90, displayRotation = android.view.Surface.ROTATION_0
        )
        // relativeRotation = 90 -> (1 - y, x) = (1, 1)
        assertEquals(1f, out.x, 0.001f)
        assertEquals(1f, out.y, 0.001f)
    }

    @Test
    fun relativeRotation180FlipsBothAxes() {
        val point = NormalizedPoint(0.1f, 0.9f)
        val out = transformDisplayPointToSensorPoint(
            point, sensorOrientationDegrees = 0, displayRotation = android.view.Surface.ROTATION_180
        )
        assertEquals(0.9f, out.x, 0.001f)
        assertEquals(0.1f, out.y, 0.001f)
    }

    @Test
    fun relativeRotation270CounterRotates() {
        val point = NormalizedPoint(0f, 1f)
        val out = transformDisplayPointToSensorPoint(
            point, sensorOrientationDegrees = 270, displayRotation = android.view.Surface.ROTATION_0
        )
        // relativeRotation = 270 -> (y, 1 - x) = (1, 1)
        assertEquals(1f, out.x, 0.001f)
        assertEquals(1f, out.y, 0.001f)
    }

    @Test
    fun mirrorFlipsHorizontalAxis() {
        val point = NormalizedPoint(0.25f, 0.5f)
        val out = transformDisplayPointToSensorPoint(
            point, sensorOrientationDegrees = 0, displayRotation = android.view.Surface.ROTATION_0,
            mirrored = true
        )
        assertEquals(0.75f, out.x, 0.001f)
        assertEquals(0.5f, out.y, 0.001f)
    }
}