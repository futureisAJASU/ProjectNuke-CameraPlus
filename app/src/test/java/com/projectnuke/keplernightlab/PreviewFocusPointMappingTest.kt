package com.projectnuke.keplernightlab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewFocusPointMappingTest {
    @Test
    fun portraitMapsDirectly() {
        val point = normalizePointFromPreviewContainer(
            offset = Offset(100f, 200f),
            containerSize = Size(300f, 400f),
            layoutMode = CameraUiLayoutMode.PORTRAIT
        )
        assertEquals(100f / 300f, point!!.x, 0.001f)
        assertEquals(200f / 400f, point.y, 0.001f)
    }

    @Test
    fun landscapeLeftMapsRotated() {
        val point = normalizePointFromPreviewContainer(
            offset = Offset(50f, 150f),
            containerSize = Size(400f, 300f),
            layoutMode = CameraUiLayoutMode.LANDSCAPE_LEFT
        )
        assertEquals(1f - (150f / 300f), point!!.x, 0.001f)
        assertEquals(50f / 400f, point.y, 0.001f)
    }

    @Test
    fun landscapeRightMapsRotated() {
        val point = normalizePointFromPreviewContainer(
            offset = Offset(50f, 150f),
            containerSize = Size(400f, 300f),
            layoutMode = CameraUiLayoutMode.LANDSCAPE_RIGHT
        )
        assertEquals(150f / 300f, point!!.x, 0.001f)
        assertEquals(1f - (50f / 400f), point.y, 0.001f)
    }

    @Test
    fun nullForInvalidSize() {
        val point = normalizePointFromPreviewContainer(
            offset = Offset(10f, 10f),
            containerSize = Size(0f, 100f),
            layoutMode = CameraUiLayoutMode.PORTRAIT
        )
        assertEquals(null, point)
    }
}
