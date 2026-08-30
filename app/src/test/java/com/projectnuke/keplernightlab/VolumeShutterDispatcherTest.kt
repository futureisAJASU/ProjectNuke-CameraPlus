package com.projectnuke.keplernightlab

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
