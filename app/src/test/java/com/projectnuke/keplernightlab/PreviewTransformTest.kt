package com.projectnuke.keplernightlab

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTransformTest {
    private val displays = listOf(
        Surface.ROTATION_0,
        Surface.ROTATION_90,
        Surface.ROTATION_180,
        Surface.ROTATION_270
    )

    @Test
    fun expectedRelativeRotationAndDimensionsCoverSensorDisplayMatrix() {
        val expectedBySensor = mapOf(
            90 to listOf(90, 0, 270, 180),
            270 to listOf(270, 180, 90, 0)
        )

        expectedBySensor.forEach { (sensor, expectedRotations) ->
            displays.forEachIndexed { index, display ->
                val geometry = calculatePreviewTransformGeometry(
                    bufferWidth = 1920,
                    bufferHeight = 1440,
                    viewportWidth = 2340f,
                    viewportHeight = 1000f,
                    sensorOrientationDegrees = sensor,
                    displayRotation = display
                )
                val rotation = expectedRotations[index]
                assertEquals("sensor=$sensor display=$display", rotation, geometry.relativeRotationDegrees)
                if (rotation == 90 || rotation == 270) {
                    assertEquals(1440f, geometry.logicalWidth, 0.01f)
                    assertEquals(1920f, geometry.logicalHeight, 0.01f)
                } else {
                    assertEquals(1920f, geometry.logicalWidth, 0.01f)
                    assertEquals(1440f, geometry.logicalHeight, 0.01f)
                }
            }
        }
    }

    @Test
    fun portraitAndLandscapeUseUniformAspectPreservingCenterCrop() {
        val viewports = listOf(
            1080f to 1440f,
            2340f to 1000f
        )

        listOf(90, 270).forEach { sensor ->
            displays.forEach { display ->
                viewports.forEach { (width, height) ->
                    val geometry = calculatePreviewTransformGeometry(
                        bufferWidth = 1920,
                        bufferHeight = 1440,
                        viewportWidth = width,
                        viewportHeight = height,
                        sensorOrientationDegrees = sensor,
                        displayRotation = display
                    )
                    assertTrue("width cover sensor=$sensor display=$display", geometry.scaledWidth >= width)
                    assertTrue("height cover sensor=$sensor display=$display", geometry.scaledHeight >= height)
                    assertEquals(
                        geometry.logicalWidth / geometry.logicalHeight,
                        if (geometry.relativeRotationDegrees == 90 || geometry.relativeRotationDegrees == 270) {
                            1440f / 1920f
                        } else {
                            1920f / 1440f
                        },
                        0.0001f
                    )
                    assertEquals(
                        "center sensor=$sensor display=$display",
                        width / 2f,
                        geometry.mapBufferPointToViewport(960f, 720f).x,
                        0.01f
                    )
                    assertEquals(
                        "center sensor=$sensor display=$display",
                        height / 2f,
                        geometry.mapBufferPointToViewport(960f, 720f).y,
                        0.01f
                    )
                    val values = buildPreviewTransformValues(geometry)
                    val rowScaleX = hypot(values[0], values[1])
                    val rowScaleY = hypot(values[3], values[4])
                    assertEquals("uniform x/y sensor=$sensor display=$display", rowScaleX, rowScaleY, 0.0001f)
                    assertEquals(geometry.uniformScale, rowScaleX, 0.0001f)
                }
            }
        }
    }

    @Test
    fun sensor90Display90IsIdentityWithNoAdditionalDisplayCorrection() {
        assertEquals(0, relativeRotationDegrees(90, Surface.ROTATION_90))
        val geometry = calculatePreviewTransformGeometry(
            bufferWidth = 1920,
            bufferHeight = 1440,
            viewportWidth = 2340f,
            viewportHeight = 1000f,
            sensorOrientationDegrees = 90,
            displayRotation = Surface.ROTATION_90
        )
        assertEquals(0, geometry.relativeRotationDegrees)
        assertEquals(1920f, geometry.logicalWidth, 0.01f)
        assertEquals(1440f, geometry.logicalHeight, 0.01f)
        val values = buildPreviewTransformValues(geometry)
        assertEquals(0f, values[1], 0.0001f)
        assertEquals(0f, values[3], 0.0001f)
        assertTrue(values[0] > 0f)
    }

    private fun hypot(x: Float, y: Float): Float = kotlin.math.sqrt(x * x + y * y)
}
