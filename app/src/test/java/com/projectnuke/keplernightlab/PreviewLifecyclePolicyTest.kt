package com.projectnuke.keplernightlab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewLifecyclePolicyTest {
    private val idleCamera = PreviewLifecycleInput(true, true, true, true)

    @Test fun onStopDisablesIdlePreview() {
        assertFalse(previewMayRun(idleCamera.copy(lifecycleStarted = false)))
    }

    @Test fun settingsScreenDoesNotOwnCameraPreview() {
        assertFalse(previewMayRun(idleCamera.copy(cameraScreenVisible = false)))
    }

    @Test fun permissionDeniedIsTruthfullyUnavailable() {
        assertFalse(previewMayRun(idleCamera.copy(permissionGranted = false)))
    }

    @Test fun startRecoveryRequiresAllOwners() {
        assertTrue(previewMayRun(idleCamera))
    }
}
