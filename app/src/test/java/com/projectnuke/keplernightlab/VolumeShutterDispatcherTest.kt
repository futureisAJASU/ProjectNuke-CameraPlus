package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeShutterDispatcherTest {
    @Test
    fun dispatchReturnsTrueWhenRegistered() {
        val dispatcher = VolumeShutterDispatcher()
        dispatcher.register { true }
        assertTrue(dispatcher.dispatch())
        dispatcher.unregister()
        assertFalse(dispatcher.dispatch())
    }

    @Test
    fun dispatchReturnsFalseWhenNotRegistered() {
        val dispatcher = VolumeShutterDispatcher()
        assertFalse(dispatcher.dispatch())
    }

    @Test
    fun isRegisteredReflectsState() {
        val dispatcher = VolumeShutterDispatcher()
        assertFalse(dispatcher.isRegistered)
        dispatcher.register { false }
        assertTrue(dispatcher.isRegistered)
        dispatcher.unregister()
        assertFalse(dispatcher.isRegistered)
    }
}

class VolumeKeyEventPolicyTest {
    private fun resolve(
        keyCode: Int = android.view.KeyEvent.KEYCODE_VOLUME_UP,
        repeatCount: Int = 0,
        camerasOwnInput: Boolean = true,
        isKeyDown: Boolean = true
    ) = VolumeKeyEventPolicy.resolve(keyCode, repeatCount, camerasOwnInput, isKeyDown)

    @Test
    fun volumeUpRepeatZeroDispatches() {
        assertEquals(VolumeKeyAction.DISPATCH, resolve(repeatCount = 0))
    }

    @Test
    fun volumeUpRepeatZeroVolumeDownDispatches() {
        assertEquals(
            VolumeKeyAction.DISPATCH,
            resolve(keyCode = android.view.KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0)
        )
    }

    @Test
    fun volumeRepeatConsumedNotDispatched() {
        assertEquals(VolumeKeyAction.CONSUME_ONLY, resolve(repeatCount = 1))
        assertEquals(VolumeKeyAction.CONSUME_ONLY, resolve(repeatCount = 5))
    }

    @Test
    fun volumeDownRepeatConsumed() {
        assertEquals(
            VolumeKeyAction.CONSUME_ONLY,
            resolve(keyCode = android.view.KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 2)
        )
    }

    @Test
    fun keyUpNeverDispatches() {
        assertEquals(VolumeKeyAction.IGNORE, resolve(isKeyDown = false))
        assertEquals(VolumeKeyAction.IGNORE, resolve(isKeyDown = false, repeatCount = 0))
    }

    @Test
    fun notCameraContextIgnores() {
        assertEquals(VolumeKeyAction.IGNORE, resolve(camerasOwnInput = false))
        // repeat not-registered also ignored, system volume preserved
        assertEquals(VolumeKeyAction.IGNORE, resolve(camerasOwnInput = false, repeatCount = 1))
    }

    @Test
    fun nonVolumeKeyIgnored() {
        assertEquals(
            VolumeKeyAction.IGNORE,
            resolve(keyCode = android.view.KeyEvent.KEYCODE_POWER)
        )
    }
}
