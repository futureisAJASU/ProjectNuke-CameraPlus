package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraUiSchedulerTest {
    @Test
    fun outcomesAreExplicit() {
        val scheduler = object : CameraUiScheduler {
            override fun post(delayMillis: Long, work: Runnable) = CameraUiDispatchOutcome.REJECTED
            override fun remove(work: Runnable) = false
        }
        assertEquals(CameraUiDispatchOutcome.REJECTED, scheduler.post(250L, Runnable {}))
    }

    @Test
    fun dispatchThrowIsDistinctFromRejection() {
        assertEquals(CameraUiDispatchOutcome.REJECTED, cameraUiDispatchOutcome { false })
        assertEquals(CameraUiDispatchOutcome.DISPATCH_THREW, cameraUiDispatchOutcome { error("closed") })
    }
}
