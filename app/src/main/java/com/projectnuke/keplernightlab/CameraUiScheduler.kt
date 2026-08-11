package com.projectnuke.keplernightlab

import android.os.Handler

internal enum class CameraUiDispatchOutcome { ACCEPTED, REJECTED, DISPATCH_THREW }

internal fun cameraUiDispatchOutcome(dispatch: () -> Boolean): CameraUiDispatchOutcome = try {
    if (dispatch()) CameraUiDispatchOutcome.ACCEPTED else CameraUiDispatchOutcome.REJECTED
} catch (_: Throwable) {
    CameraUiDispatchOutcome.DISPATCH_THREW
}

internal interface CameraUiScheduler {
    fun post(delayMillis: Long = 0L, work: Runnable): CameraUiDispatchOutcome
    fun remove(work: Runnable): Boolean
}

internal class HandlerCameraUiScheduler(private val handler: Handler) : CameraUiScheduler {
    override fun post(delayMillis: Long, work: Runnable): CameraUiDispatchOutcome = cameraUiDispatchOutcome {
        if (delayMillis <= 0L) handler.post(work) else handler.postDelayed(work, delayMillis)
    }

    override fun remove(work: Runnable): Boolean {
        handler.removeCallbacks(work)
        return true
    }
}
