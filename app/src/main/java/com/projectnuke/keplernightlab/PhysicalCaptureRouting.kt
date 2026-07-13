package com.projectnuke.keplernightlab

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface

internal enum class PhysicalCaptureRoute {
    NORMAL,
    PHYSICAL,
    PHYSICAL_FAILED_CROP_FALLBACK
}

internal fun PhysicalCaptureRoute.finalRequestZoomRatio(normalZoomRatio: Float): Float = when (this) {
    PhysicalCaptureRoute.NORMAL -> normalZoomRatio
    PhysicalCaptureRoute.PHYSICAL -> 1.0f
    PhysicalCaptureRoute.PHYSICAL_FAILED_CROP_FALLBACK -> normalZoomRatio
}

internal fun createRoutedStillCaptureSession(
    camera: CameraDevice,
    surface: Surface,
    cameraId: String,
    physicalCameraId: String?,
    requestedUiZoomRatio: Float,
    requestedCaptureZoomRatio: Float,
    selectedRoute: ThreeXSourceMode,
    handler: Handler,
    pipelineName: String,
    isFinished: () -> Boolean,
    onConfigured: (CameraCaptureSession, PhysicalCaptureRoute) -> Unit,
    onFailed: (String) -> Unit
) {
    fun logLateCallback(callback: String) {
        Log.d(
            "KeplerCaptureCancel",
            "pipeline=$pipelineName callback=$callback late=true finished=${isFinished()}"
        )
    }

    fun createNormalOutput(reason: String, route: PhysicalCaptureRoute) {
        if (isFinished()) {
            logLateCallback("createNormalOutput")
            return
        }
        val path = when (route) {
            PhysicalCaptureRoute.NORMAL -> "normal"
            PhysicalCaptureRoute.PHYSICAL_FAILED_CROP_FALLBACK -> "cropFallback"
            PhysicalCaptureRoute.PHYSICAL -> "physical"
        }
        val finalRequestZoom = route.finalRequestZoomRatio(requestedCaptureZoomRatio)
        Log.i(
            "KeplerPhysicalRoute",
            "capture path=$path cameraId=$cameraId requestedPhysicalCameraId=$physicalCameraId " +
                "requestedUiZoomRatio=$requestedUiZoomRatio selectedRoute=$selectedRoute " +
                "actualRoute=$route finalRequestZoom=$finalRequestZoom reason=$reason"
        )
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (isFinished()) {
                            logLateCallback("normal.onConfigured")
                            session.close()
                            return
                        }
                        Log.i(
                            "KeplerPhysicalRoute",
                            "capture normal output configured path=$path cameraId=$cameraId " +
                                "requestedUiZoomRatio=$requestedUiZoomRatio selectedRoute=$selectedRoute " +
                                "actualRoute=$route finalRequestZoom=$finalRequestZoom"
                        )
                        onConfigured(session, route)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (isFinished()) {
                            logLateCallback("normal.onConfigureFailed")
                            return
                        }
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
        createNormalOutput("physical output not requested", PhysicalCaptureRoute.NORMAL)
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
                        if (isFinished()) {
                            logLateCallback("physical.onConfigured")
                            session.close()
                            return
                        }
                        Log.i(
                            "KeplerPhysicalRoute",
                            "capture physical output configured path=physical cameraId=$cameraId " +
                                "requestedPhysicalCameraId=$physicalCameraId " +
                                "requestedUiZoomRatio=$requestedUiZoomRatio selectedRoute=$selectedRoute " +
                                "actualRoute=${PhysicalCaptureRoute.PHYSICAL} finalRequestZoom=1.0"
                        )
                        onConfigured(session, PhysicalCaptureRoute.PHYSICAL)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (isFinished()) {
                            logLateCallback("physical.onConfigureFailed")
                            return
                        }
                        Log.e(
                            "KeplerPhysicalRoute",
                            "capture physical output failed cameraId=$cameraId " +
                                "requestedPhysicalCameraId=$physicalCameraId"
                        )
                        createNormalOutput(
                            "physical session configuration failed",
                            PhysicalCaptureRoute.PHYSICAL_FAILED_CROP_FALLBACK
                        )
                    }
                }
            )
        )
    } catch (error: Exception) {
        if (isFinished()) {
            logLateCallback("physical.create")
            return
        }
        Log.e(
            "KeplerPhysicalRoute",
            "capture physical output create failed cameraId=$cameraId " +
                "requestedPhysicalCameraId=$physicalCameraId " +
                "exception=${error.javaClass.simpleName}:${error.message}",
            error
        )
        createNormalOutput(
            "physical session creation exception",
            PhysicalCaptureRoute.PHYSICAL_FAILED_CROP_FALLBACK
        )
    }
}
