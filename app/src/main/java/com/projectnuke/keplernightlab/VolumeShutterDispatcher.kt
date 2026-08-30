package com.projectnuke.keplernightlab

import android.view.KeyEvent

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

enum class VolumeKeyAction { DISPATCH, CONSUME_ONLY, IGNORE }

/**
 * Pure key-event policy for volume shutter.
 *
 * Required semantics:
 *  - first physical key-down (repeatCount == 0) when camera owns volume shutter -> DISPATCH + consume
 *  - repeat key-down while held -> CONSUME_ONLY (do NOT dispatch, do NOT let volume change)
 *  - key-up -> IGNORE (never dispatch, key-up never captures)
 *  - non-volume keys or when camera does not own input -> IGNORE (normal system volume)
 */
object VolumeKeyEventPolicy {
    fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    fun resolve(
        keyCode: Int,
        repeatCount: Int,
        camerasOwnInput: Boolean,
        isKeyDown: Boolean
    ): VolumeKeyAction {
        if (!isKeyDown) return VolumeKeyAction.IGNORE
        if (!isVolumeKey(keyCode)) return VolumeKeyAction.IGNORE
        if (!camerasOwnInput) return VolumeKeyAction.IGNORE
        return if (repeatCount == 0) VolumeKeyAction.DISPATCH else VolumeKeyAction.CONSUME_ONLY
    }
}