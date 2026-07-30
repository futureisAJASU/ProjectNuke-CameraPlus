package com.projectnuke.keplernightlab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFastPathTest {
    @Test fun zeroStrengthSkipsAllRequestedRestorationStages() {
        val base = ClassicYuvFusionPreset.NATURAL.params.copy(
            denoiseStrength = 0f,
            sharpenAmount = 0f,
            localContrastAmount = 0f,
            shadowLift = 0f,
            highlightRollOff = 0f,
            saturationBoost = 1f
        )
        assertTrue(isIdentityProcessing(base))
        assertFalse(isIdentityProcessing(base.copy(sharpenAmount = 0.1f)))
    }
}
