package com.projectnuke.keplernightlab

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyCaptureDiagnosticsGateTest {
    @Test
    fun nonDebuggableApplicationCannotOpenLegacyCaptureDiagnostics() {
        val info = ApplicationInfo()
        info.flags = 0

        assertFalse(isLegacyCaptureDiagnosticsEnabled(info))
    }

    @Test
    fun debuggableApplicationUsesBuildDebugGate() {
        val info = ApplicationInfo()
        info.flags = ApplicationInfo.FLAG_DEBUGGABLE

        assertEquals(BuildConfig.DEBUG, isLegacyCaptureDiagnosticsEnabled(info))
    }
}
