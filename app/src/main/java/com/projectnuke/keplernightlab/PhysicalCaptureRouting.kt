package com.projectnuke.keplernightlab

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface

internal fun createRoutedStillCaptureSession(
    camera: CameraDevice,
    surface: Surface,
    cameraId: String,
    physicalCameraId: String?,
    handler: Handler,
    onConfigured: (CameraCaptureSession, Boolean) -> Unit,
    onFailed: (String) -> Unit
) {
    fun createNormalOutput(reason: String, cropFallback: Boolean) {
        val path = if (cropFallback) "cropFallback" else "normal"
        val zoom = if (cropFallback) "3.0" else "unchanged"
        Log.i(
            "KeplerPhysicalRoute",
            "capture path=$path cameraId=$cameraId requestedPhysicalCameraId=$physicalCameraId " +
                "reason=$reason zoomRatio=$zoom"
        )
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(
                            "KeplerPhysicalRoute",
                            "capture normal output configured path=$path cameraId=$cameraId zoomRatio=$zoom"
                        )
                        onConfigured(session, false)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        onFailed("Normal capture fallback session configuration failed")
                    }
                },
                handler
            )
        } catch (error: Exception) {
            onFailed(
                "Normal capture fallback creation failed: " +
                    "${error.javaClass.simpleName}:${error.message}"
            )
        }
    }

    if (physicalCameraId == null || Build.VERSION.SDK_INT < 28) {
        createNormalOutput("physical output not requested", cropFallback = false)
        return
    }

    try {
        val output = OutputConfiguration(surface).apply {
            setPhysicalCameraId(physicalCameraId)
        }
        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(output),
                { command -> handler.post(command) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i(
                            "KeplerPhysicalRoute",
                            "capture physical output configured path=physical cameraId=$cameraId " +
                                "requestedPhysicalCameraId=$physicalCameraId zoomRatio=1.0"
                        )
                        onConfigured(session, true)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        Log.e(
                            "KeplerPhysicalRoute",
                            "capture physical output failed cameraId=$cameraId " +
                                "requestedPhysicalCameraId=$physicalCameraId"
                        )
                        createNormalOutput(
                            "physical session configuration failed",
                            cropFallback = true
                        )
                    }
                }
            )
        )
    } catch (error: Exception) {
        Log.e(
            "KeplerPhysicalRoute",
            "capture physical output create failed cameraId=$cameraId " +
                "requestedPhysicalCameraId=$physicalCameraId " +
                "exception=${error.javaClass.simpleName}:${error.message}",
            error
        )
        createNormalOutput("physical session creation exception", cropFallback = true)
    }
}
