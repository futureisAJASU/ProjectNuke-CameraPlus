package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionMemoryPlanTest {
    @Test fun wideYuvReducesRowsOrUsesBatching() {
        val plan = planFusionMemory(FusionMemoryPlanRequest(4000, 256, 7, 256L * 1024 * 1024))
        assertTrue(plan.tileRows <= 256)
        assertTrue(plan.estimatedPeakBytes <= plan.budgetBytes)
    }

    @Test fun fifteenCandidatesRemainBounded() {
        val plan = planFusionMemory(FusionMemoryPlanRequest(4000, 256, 15, 256L * 1024 * 1024))
        assertTrue(plan.candidateBatchSize < 15 || plan.tileRows < 256)
        assertTrue(plan.estimatedPeakBytes <= plan.budgetBytes)
    }

    @Test fun highResolutionRawUsesStreamingWhenNecessary() {
        val plan = planFusionMemory(FusionMemoryPlanRequest(8000, 256, 15, 384L * 1024 * 1024))
        assertTrue(plan.estimatedPeakBytes <= plan.budgetBytes)
        assertTrue(plan.fallbackReason != null)
    }

    @Test(expected = IllegalStateException::class)
    fun overflowIsRejectedBeforeArrayConversion() {
        planFusionMemory(
            FusionMemoryPlanRequest(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE)
        )
    }

    @Test fun arithmeticUsesLongForNormalInputs() {
        val plan = planFusionMemory(FusionMemoryPlanRequest(4000, 256, 7, 512L * 1024 * 1024))
        assertEquals(512L * 1024 * 1024, plan.budgetBytes)
    }

    @Test fun fullOutputResidencyCanReturnCannotFitBeforeAllocation() {
        val plan = planFusionMemory(
            FusionMemoryPlanRequest(
                width = 4000,
                tileRows = 128,
                candidateFrames = 6,
                availableBytes = 96L * 1024 * 1024,
                fullOutputBitmapBytes = 64L * 1024 * 1024,
                postprocessOutputBitmapBytes = 64L * 1024 * 1024
            )
        )
        assertTrue(plan.cannotFit)
        assertTrue(plan.estimatedPeakBytes > plan.budgetBytes)
    }

    @Test fun candidatesAreStreamedOneAtATime() {
        val plan = planFusionMemory(FusionMemoryPlanRequest(4000, 128, 6, 512L * 1024 * 1024))
        assertEquals(1, plan.candidateBatchSize)
        assertTrue(plan.estimatedPeakBytes <= plan.budgetBytes)
    }
}
