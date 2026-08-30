package com.projectnuke.keplernightlab

/**
 * Narrow dispatcher for volume-shutter behavior.
 * The camera screen registers its capture callback here;
 * MainActivity forwards volume key events when eligible.
 */
class VolumeShutterDispatcher {
    @Volatile
    private var shutterCallback: (() -> Boolean)? = null

    fun register(callback: () -> Boolean) {
        shutterCallback = callback
    }

    fun unregister() {
        shutterCallback = null
    }

    fun dispatch(): Boolean {
        val cb = shutterCallback
        return if (cb != null) {
            cb()
        } else {
            false
        }
    }

    val isRegistered: Boolean
        get() = shutterCallback != null
}
