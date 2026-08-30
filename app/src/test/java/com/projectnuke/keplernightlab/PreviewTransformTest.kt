package com.projectnuke.keplernightlab

import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import kotlin.math.sqrt
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
    fun officialRelativeRotationUsesLensFacingAwareCamera2Formula() {
        val expectedRear = listOf(90, 180, 270, 0)
        val expectedFront = listOf(90, 0, 270, 180)

        displays.forEachIndexed { index, display ->
            assertEquals(
                "rear display=$display",
                expectedRear[index],
                relativeRotationDegrees(
                    sensorOrientationDegrees = 90,
                    displayRotation = display,
                    lensFacing = CameraCharacteristics.LENS_FACING_BACK
                )
            )
            assertEquals(
                "front display=$display",
                expectedFront[index],
                relativeRotationDegrees(
                    sensorOrientationDegrees = 90,
                    displayRotation = display,
                    lensFacing = CameraCharacteristics.LENS_FACING_FRONT
                )
            )
        }
    }

    @Test
    fun sensor90And270CoverAllDisplaysWithOrientedDimensions() {
        listOf(90, 270).forEach { sensor ->
            displays.forEach { display ->
                val geometry = geometry(sensor, display, 1080f, 1440f)
                val expectedEffective = normalizeRightAngle(sensor - displayDegrees(display))!!
                assertEquals(expectedEffective, geometry.effectiveRotationDegrees)
                if (expectedEffective == 90 || expectedEffective == 270) {
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
    fun frontCameraKeepsItsLensFacingAwareDisplayDirectionExtensible() {
        val expectedEffective = listOf(270, 0, 90, 180)
        displays.forEachIndexed { index, display ->
            val geometry = calculatePreviewTransformGeometry(
                bufferWidth = 1920,
                bufferHeight = 1440,
                viewportWidth = 2340f,
                viewportHeight = 1000f,
                sensorOrientationDegrees = 270,
                displayRotation = display,
                lensFacing = CameraCharacteristics.LENS_FACING_FRONT
            )
            assertEquals(expectedEffective[index], geometry.effectiveRotationDegrees)
        }
    }

    @Test
    fun composedTextureViewPipelineIsUniformAndCenterCropped() {
        listOf(
            1080f to 1440f,
            2340f to 1000f
        ).forEach { (viewportWidth, viewportHeight) ->
            listOf(90, 270).forEach { sensor ->
                displays.forEach { display ->
                    val geometry = geometry(sensor, display, viewportWidth, viewportHeight)
                    val center = geometry.mapBufferPointToViewport(960f, 720f)
                    assertEquals(viewportWidth / 2f, center.x, 0.01f)
                    assertEquals(viewportHeight / 2f, center.y, 0.01f)
                    assertTrue(geometry.scaledWidth >= viewportWidth - 0.001f)
                    assertTrue(geometry.scaledHeight >= viewportHeight - 0.001f)
                    assertEquals(
                        (geometry.scaledWidth - viewportWidth).coerceAtLeast(0f) / 2f,
                        geometry.cropOffsetX,
                        0.001f
                    )
                    assertEquals(
                        (geometry.scaledHeight - viewportHeight).coerceAtLeast(0f) / 2f,
                        geometry.cropOffsetY,
                        0.001f
                    )

                    // The raw correction matrix is not the final transform. Its
                    // composed result must match the canonical uniform mapping.
                    val sourcePoints = listOf(
                        960f to 720f,
                        1160f to 720f,
                        960f to 920f
                    )
                    sourcePoints.forEach { (x, y) ->
                        val expected = geometry.mapBufferPointToViewport(x, y)
                        val actual = geometry.mapBufferPointThroughTextureView(x, y)
                        assertEquals(expected.x, actual.x, 0.01f)
                        assertEquals(expected.y, actual.y, 0.01f)
                    }
                }
            }
        }
    }

    @Test
    fun circleRemainsCircleAfterImplicitScaleCorrection() {
        val geometry = geometry(90, Surface.ROTATION_90, 2340f, 1000f)
        val center = geometry.mapBufferPointThroughTextureView(960f, 720f)
        val right = geometry.mapBufferPointThroughTextureView(1060f, 720f)
        val down = geometry.mapBufferPointThroughTextureView(960f, 820f)

        assertEquals(distance(center, right), distance(center, down), 0.01f)
        assertEquals(1170f, center.x, 0.01f)
        assertEquals(500f, center.y, 0.01f)
    }

    @Test
    fun landscapeDirectionsHaveNoSecondAccidentalRotation() {
        val left = geometry(90, Surface.ROTATION_90, 2340f, 1000f)
        val right = geometry(90, Surface.ROTATION_270, 2340f, 1000f)

        assertEquals(0, left.effectiveRotationDegrees)
        assertEquals(180, right.effectiveRotationDegrees)
        assertEquals(270, left.displayCompensationDegrees)
        assertEquals(90, right.displayCompensationDegrees)
        assertEquals(
            left.mapBufferPointToViewport(1160f, 720f).x,
            left.mapBufferPointThroughTextureView(1160f, 720f).x,
            0.01f
        )
        assertEquals(
            right.mapBufferPointToViewport(1160f, 720f).x,
            right.mapBufferPointThroughTextureView(1160f, 720f).x,
            0.01f
        )
    }

    private fun geometry(
        sensor: Int,
        display: Int,
        viewportWidth: Float,
        viewportHeight: Float
    ): PreviewTransformGeometry = calculatePreviewTransformGeometry(
        bufferWidth = 1920,
        bufferHeight = 1440,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        sensorOrientationDegrees = sensor,
        displayRotation = display,
        lensFacing = CameraCharacteristics.LENS_FACING_BACK
    )

    private fun displayDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> error("rotation=$rotation")
    }

    private fun distance(a: PreviewPoint, b: PreviewPoint): Float = sqrt(
        (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)
    )
}
