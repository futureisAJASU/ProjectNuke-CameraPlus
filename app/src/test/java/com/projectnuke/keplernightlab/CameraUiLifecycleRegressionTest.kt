package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiLifecycleRegressionTest {
    @Test
    fun schedulerRejectionDoesNotBecomeAcceptedWork() {
        val scheduler = object : CameraUiScheduler {
            override fun post(delayMillis: Long, work: Runnable) = CameraUiDispatchOutcome.REJECTED
            override fun remove(work: Runnable) = false
        }
        assertEquals(CameraUiDispatchOutcome.REJECTED, scheduler.post(120_000L, Runnable {}))
    }

    @Test
    fun lifecyclePolicyRequiresPreviewAndPipelineOwnersTogether() {
        val base = PreviewLifecycleInput(true, true, true, true)
        assertTrue(previewMayRun(base))
        assertFalse(previewMayRun(base.copy(lifecycleStarted = false)))
        assertFalse(previewMayRun(base.copy(pipelineAllowsPreview = false)))
    }

    @Test
    fun actionCancellationCanReturnToIdleWithoutAllowingStaleCompletion() {
        val active = GalleryUiActionSession("job-a", GalleryUiAction.CLEANING, 3L)
        assertFalse(canStartGalleryUiAction(active, "job-a", GalleryUiAction.REPROCESSING))
        assertFalse(acceptsGalleryUiActionCompletion(active, "job-a", 2L))
        assertTrue(canStartGalleryUiAction(null, "job-a", GalleryUiAction.REPROCESSING))
    }
}
