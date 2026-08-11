package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureAttemptUiSnapshotTest {
    @Test
    fun laterUiEditsDoNotChangeCapturedAttemptValues() {
        val captured = CaptureAttemptUiSnapshot(
            lensSlot = LensSlot.MAIN_1X,
            resolution = CaptureResolutionMode.MP12,
            zoomRatio = 1.0f,
            focusAeState = FocusAeState(),
            processingSettings = ProcessingSettings.default(),
            outputFormat = FinalOutputFormat.JPEG
        )
        val later = captured.copy(
            lensSlot = LensSlot.THREE_X,
            resolution = CaptureResolutionMode.MP50,
            zoomRatio = 3.0f
        )
        assertEquals(LensSlot.MAIN_1X, captured.lensSlot)
        assertEquals(CaptureResolutionMode.MP12, captured.resolution)
        assertEquals(1.0f, captured.zoomRatio)
        assertEquals(LensSlot.THREE_X, later.lensSlot)
    }
}
