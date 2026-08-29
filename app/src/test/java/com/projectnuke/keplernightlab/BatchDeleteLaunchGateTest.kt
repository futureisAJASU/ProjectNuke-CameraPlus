package com.projectnuke.keplernightlab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchDeleteLaunchGateTest {

    @Test
    fun tryStart_idle_succeeds() {
        val gate = BatchDeleteLaunchGate()
        assertTrue(gate.tryStart())
        assertTrue(gate.isBusy)
    }

    @Test
    fun tryStart_whileBusy_rejected() {
        val gate = BatchDeleteLaunchGate()
        gate.tryStart()
        assertFalse(gate.tryStart())
        assertTrue(gate.isBusy)
    }

    @Test
    fun finish_returnsToIdle() {
        val gate = BatchDeleteLaunchGate()
        gate.tryStart()
        gate.finish()
        assertFalse(gate.isBusy)
        assertTrue(gate.tryStart())
    }

    @Test
    fun finish_withoutStart_staysIdle() {
        val gate = BatchDeleteLaunchGate()
        gate.finish()
        assertFalse(gate.isBusy)
        assertTrue(gate.tryStart())
    }
}
