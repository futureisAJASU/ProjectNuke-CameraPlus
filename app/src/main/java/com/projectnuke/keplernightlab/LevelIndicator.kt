package com.projectnuke.keplernightlab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI

data class DeviceLevelState(
    val available: Boolean = false,
    val pitchDegrees: Float = 0f,
    val rollDegrees: Float = 0f,
    val accuracy: Int = 0
)

@Composable
fun rememberDeviceLevelState(
    enabled: Boolean
): DeviceLevelState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(DeviceLevelState()) }

    DisposableEffect(enabled) {
        if (!enabled) {
            state = DeviceLevelState()
            onDispose { }
        } else {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (sensor == null) {
                state = DeviceLevelState(available = false)
                onDispose { }
            } else {
                val rotationMatrix = FloatArray(9)
                val orientation = FloatArray(3)
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        val pitch = (orientation[1] * 180f / PI.toFloat())
                        val roll = (orientation[2] * 180f / PI.toFloat())
                        // TODO: tune pitch/roll sign for Samsung portrait orientation if needed.
                        state = DeviceLevelState(
                            available = true,
                            pitchDegrees = -pitch,
                            rollDegrees = roll,
                            accuracy = event.accuracy
                        )
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                        state = state.copy(accuracy = accuracy)
                    }
                }
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                onDispose { sensorManager.unregisterListener(listener) }
            }
        }
    }

    return state
}

@Composable
fun LevelIndicatorOverlay(
    levelState: DeviceLevelState,
    modifier: Modifier = Modifier
) {
    if (!levelState.available) {
        Text(
            text = "Level unavailable",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelMedium,
            modifier = modifier
        )
        return
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val lineColor = Color.White.copy(alpha = 0.65f)
        val accent = Color(0xFFFFD33D).copy(alpha = 0.75f)
        val pitchOffset = levelState.pitchDegrees.coerceIn(-20f, 20f) * 3f

        rotate(
            degrees = -levelState.rollDegrees,
            pivot = center
        ) {
            drawLine(
                color = lineColor,
                start = Offset(center.x - 120f, center.y + pitchOffset),
                end = Offset(center.x + 120f, center.y + pitchOffset),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = accent,
                start = Offset(center.x - 28f, center.y),
                end = Offset(center.x + 28f, center.y),
                strokeWidth = 4f,
                cap = StrokeCap.Round
            )
        }

        drawCircle(
            color = lineColor,
            radius = 74f,
            center = center,
            style = Stroke(width = 2f)
        )
        drawLine(
            color = lineColor,
            start = Offset(center.x, center.y - 84f),
            end = Offset(center.x, center.y - 64f),
            strokeWidth = 2f
        )
        drawLine(
            color = lineColor,
            start = Offset(center.x - 8f, center.y),
            end = Offset(center.x + 8f, center.y),
            strokeWidth = 2f
        )
        drawLine(
            color = lineColor,
            start = Offset(center.x, center.y - 8f),
            end = Offset(center.x, center.y + 8f),
            strokeWidth = 2f
        )

        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(170, 255, 255, 255)
                textSize = 28f
                isAntiAlias = true
            }
            drawText("ROLL ${levelState.rollDegrees.toInt()}°", 24f, 42f, paint)
            drawText("PITCH ${levelState.pitchDegrees.toInt()}°", 24f, 76f, paint)
        }
    }
}
