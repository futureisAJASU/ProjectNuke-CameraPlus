package com.projectnuke.keplernightlab

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewFocusPointMappingTest {

    @Test
    fun displayPointIsStoredUnrotatedForTheOnScreenMarker() {
        val size = Size(400f, 300f)
        val tap = Offset(100f, 90f)
        val point = normalizeDisplayPoint(tap, size)!!

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
    fun inverseMappingIncludesCenterCropOffset() {
        val geometry = geometry(Surface.ROTATION_90)

        val center = mapDisplayPointToPreviewBuffer(NormalizedPoint(0.5f, 0.5f), geometry)
        val upperLeft = mapDisplayPointToPreviewBuffer(NormalizedPoint(0f, 0f), geometry)
        val lowerRight = mapDisplayPointToPreviewBuffer(NormalizedPoint(1f, 1f), geometry)

        assertEquals(0.5f, center.x, 0.001f)
        assertEquals(0.5f, center.y, 0.001f)
        assertEquals(0f, upperLeft.x, 0.001f)
        assertEquals(309.7436f / 1440f, upperLeft.y, 0.001f)
        assertEquals(1f, lowerRight.x, 0.001f)
        assertEquals(1130.2563f / 1440f, lowerRight.y, 0.001f)

        // The top visible edge is inside the source buffer after the inverse
        // crop. It must not be treated as sensor-normalized y=0.
        assertTrue(upperLeft.y > 0.20f)
    }

    @Test
    fun bothLandscapeDirectionsInvertTheSameVisualTransform() {
        val left = geometry(Surface.ROTATION_90)
        val right = geometry(Surface.ROTATION_270)

        val leftUpper = mapDisplayPointToPreviewBuffer(NormalizedPoint(0f, 0f), left)
        val rightUpper = mapDisplayPointToPreviewBuffer(NormalizedPoint(0f, 0f), right)

        assertEquals(0f, leftUpper.x, 0.001f)
        assertEquals(309.7436f / 1440f, leftUpper.y, 0.001f)
        assertEquals(1f, rightUpper.x, 0.001f)
        assertEquals(1130.2563f / 1440f, rightUpper.y, 0.001f)
    }

    @Test
    fun zoomedActiveArrayCropReceivesCanonicalBufferPoint() {
        val geometry = geometry(Surface.ROTATION_90)
        val crop = Rect().apply {
            left = 100
            top = 200
            right = 1100
            bottom = 1000
        }
        val upperLeftVisible = mapDisplayPointToPreviewBuffer(
            NormalizedPoint(0f, 0f),
            geometry
        )
        val meteringPoint = mapNormalizedPreviewPointToCrop(upperLeftVisible, crop)

        assertEquals(100f, meteringPoint.x, 0.01f)
        assertEquals(200f + 800f * (309.7436f / 1440f), meteringPoint.y, 0.01f)

        val center = mapNormalizedPreviewPointToCrop(
            mapDisplayPointToPreviewBuffer(NormalizedPoint(0.5f, 0.5f), geometry),
            crop
        )
        assertEquals(600f, center.x, 0.01f)
        assertEquals(600f, center.y, 0.01f)
    }

    @Test
    fun geometryUsesExactBufferAndViewportSizesForPortraitToo() {
        val geometry = calculatePreviewTransformGeometry(
            bufferWidth = 1920,
            bufferHeight = 1440,
            viewportWidth = 1080f,
            viewportHeight = 1440f,
            sensorOrientationDegrees = 90,
            displayRotation = Surface.ROTATION_0,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK
        )
        val topLeft = mapDisplayPointToPreviewBuffer(NormalizedPoint(0f, 0f), geometry)
        assertEquals(0f, topLeft.x, 0.001f)
        assertEquals(1f, topLeft.y, 0.001f)
    }

    private fun geometry(displayRotation: Int): PreviewTransformGeometry =
        calculatePreviewTransformGeometry(
            bufferWidth = 1920,
            bufferHeight = 1440,
            viewportWidth = 2340f,
            viewportHeight = 1000f,
            sensorOrientationDegrees = 90,
            displayRotation = displayRotation,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK
        )
}
