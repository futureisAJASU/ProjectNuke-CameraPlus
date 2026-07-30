package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Test

class RawCfaAlignmentTest {
    @Test fun oddPositiveAndNegativeEstimatesAreAppliedOnCfaGrid() {
        assertEquals(2, cfaSafeRawShift(1.49f, 3.1f).appliedDx)
        assertEquals(4, cfaSafeRawShift(1.49f, 3.1f).appliedDy)
        assertEquals(-2, cfaSafeRawShift(-1.49f, -3.1f).appliedDx)
        assertEquals(-4, cfaSafeRawShift(-1.49f, -3.1f).appliedDy)
    }

    @Test fun everyBayerLayoutPreservesBothCoordinateParities() {
        for (cfa in 0..3) {
            val shift = cfaSafeRawShift(-7.2f, 5.6f)
            assertEquals(0, shift.appliedDx and 1)
            assertEquals(0, shift.appliedDy and 1)
        }
    }
}
