package com.projectnuke.keplernightlab

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/** Small observable seam that accepts every valid display-rotation transition. */
internal class DisplayRotationState(initialRotation: Int) {
    var rotation by mutableIntStateOf(normalizeDisplayRotation(initialRotation))
        private set

    fun update(newRotation: Int) {
        if (displayRotationDegrees(newRotation) != null) {
            rotation = newRotation
        }
    }

    private fun normalizeDisplayRotation(rotation: Int): Int =
        if (displayRotationDegrees(rotation) != null) rotation else Display.DEFAULT_DISPLAY
}

/**
 * The single platform observer used by camera UI code. Display callbacks are
 * authoritative, including 90 -> 270 and 270 -> 0 transitions where the
 * window can remain landscape-shaped or otherwise keep the same size.
 */
internal class AuthoritativeDisplayRotationSource(
    context: Context,
    private val displayId: Int
) {
    private val displayManager =
        context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val state = DisplayRotationState(readRotation() ?: Display.DEFAULT_DISPLAY)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false

    val rotation: Int
        get() = state.rotation

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(changedDisplayId: Int) {
            if (changedDisplayId != displayId) return
            displayManager.getDisplay(changedDisplayId)?.rotation?.let { rotation ->
                state.update(rotation)
                Log.i(TAG, "authoritative display rotation displayId=$displayId rotation=$rotation")
            }
        }
    }

    fun start() {
        if (registered) return
        registered = true
        displayManager.registerDisplayListener(listener, mainHandler)
        readRotation()?.let { rotation ->
            state.update(rotation)
            Log.i(TAG, "authoritative display rotation initial displayId=$displayId rotation=$rotation")
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        displayManager.unregisterDisplayListener(listener)
    }

    private fun readRotation(): Int? = displayManager.getDisplay(displayId)?.rotation

    private companion object {
        const val TAG = "KeplerDisplayRotation"
    }
}

/** Returns the authoritative rotation for the display containing the app view. */
@Composable
internal fun rememberAuthoritativeDisplayRotation(): Int {
    val context = LocalContext.current
    val displayId = LocalView.current.display?.displayId ?: Display.DEFAULT_DISPLAY
    val source = remember(context.applicationContext, displayId) {
        AuthoritativeDisplayRotationSource(context, displayId)
    }
    DisposableEffect(source) {
        source.start()
        onDispose { source.stop() }
    }
    return source.rotation
}
