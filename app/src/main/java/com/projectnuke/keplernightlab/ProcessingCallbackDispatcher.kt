package com.projectnuke.keplernightlab

import android.os.Handler
import android.util.Log

internal enum class ProcessingCallbackDispatchResult {
    ACCEPTED,
    REJECTED,
    DISPATCH_THREW
}

internal class ProcessingCallbackDispatcher(
    private val handler: Handler,
    private val tag: String,
    private val postOperation: ((Runnable) -> Boolean)? = null
) {
    fun dispatch(callback: () -> Unit): ProcessingCallbackDispatchResult {
        return try {
            val post = postOperation ?: handler::post
            if (!post(Runnable {
                    try {
                        callback()
                    } catch (failure: Throwable) {
                        Log.e(tag, "processing callback execution failed", failure)
                    }
                })) {
                ProcessingCallbackDispatchResult.REJECTED
            } else {
                ProcessingCallbackDispatchResult.ACCEPTED
            }
        } catch (failure: Throwable) {
            Log.e(tag, "processing callback dispatch failed", failure)
            ProcessingCallbackDispatchResult.DISPATCH_THREW
        }
    }
}
