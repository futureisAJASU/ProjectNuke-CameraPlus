package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingShutterStateTest {
    @Test
    fun initialStateIsDocked() {
        val controller = FloatingShutterController()
        assertEquals(FloatingShutterState.DOCKED, controller.state.value)
    }

    @Test
    fun activateFloatingChangesState() {
        val controller = FloatingShutterController()
        controller.activateFloating()
        assertEquals(FloatingShutterState.FLOATING_IDLE, controller.state.value)
    }
}
