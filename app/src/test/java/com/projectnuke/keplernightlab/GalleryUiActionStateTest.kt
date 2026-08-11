package com.projectnuke.keplernightlab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryUiActionStateTest {
    @Test
    fun conflictingActionCannotStartWhileJobActionIsActive() {
        val active = GalleryUiActionSession("job-a", GalleryUiAction.REPROCESSING, 1L)
        assertFalse(canStartGalleryUiAction(active, "job-a", GalleryUiAction.DELETING))
        assertFalse(canStartGalleryUiAction(active, "job-a", GalleryUiAction.CLEANING))
    }

    @Test
    fun oldJobCompletionCannotTouchNewActionSession() {
        val active = GalleryUiActionSession("job-b", GalleryUiAction.REPROCESSING, 2L)
        assertFalse(acceptsGalleryUiActionCompletion(active, "job-a", 1L))
        assertTrue(acceptsGalleryUiActionCompletion(active, "job-b", 2L))
    }
}
