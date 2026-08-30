package com.projectnuke.keplernightlab

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val volumeShutterDispatcher = VolumeShutterDispatcher()

    fun registerVolumeShutter(callback: () -> Boolean) = volumeShutterDispatcher.register(callback)
    fun unregisterVolumeShutter() = volumeShutterDispatcher.unregister()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumeShutterDispatcher.dispatch()) {
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        lifecycleScope.launch(Dispatchers.IO) {
            KeplerRecoveryCoordinator.requestStartup(applicationContext).get()
        }
        setContent {
            KeplerAppRoot()
        }
    }
}
