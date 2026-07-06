package com.projectnuke.keplernightlab

import android.util.Log

private const val LEGACY_YUV_CAPTURE_LOG_TAG = "KeplerYuvCapture"

/**
 * Compatibility shim for partially migrated ColorFusion.kt failure paths.
 * Some older YUV capture branches still call finishError(message) while the local
 * capture function now uses finishError(message, source, throwable, ...).
 */
@Suppress("unused")
fun finishError(message: String) {
    Log.e(
        LEGACY_YUV_CAPTURE_LOG_TAG,
        "YUV_CAPTURE_FAILED: legacy finishError(message) - $message",
        IllegalStateException(message)
    )
}
