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
        if (VolumeKeyEventPolicy.isVolumeKey(keyCode) && volumeShutterDispatcher.isRegistered) {
            val action = VolumeKeyEventPolicy.resolve(
                keyCode = keyCode,
                repeatCount = event?.repeatCount ?: 0,
                camerasOwnInput = volumeShutterDispatcher.isRegistered,
                isKeyDown = true
            )
            when (action) {
                VolumeKeyAction.DISPATCH -> {
                    volumeShutterDispatcher.dispatch()
                    return true
                }
                VolumeKeyAction.CONSUME_ONLY -> return true
                VolumeKeyAction.IGNORE -> { }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        R3GalleryColdMeasurement.onProcessStart(applicationContext)
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
