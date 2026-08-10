package com.projectnuke.keplernightlab

import android.os.Handler
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

internal enum class ProcessingCallbackDispatchResult {
    ACCEPTED,
    REJECTED,
    DISPATCH_THREW
}

internal enum class ProcessingCallbackExecutionResult {
    EXECUTED,
    EXECUTION_FAILED
}

internal data class ProcessingCallbackOutcomeSnapshot(
    val dispatch: ProcessingCallbackDispatchResult? = null,
    val execution: ProcessingCallbackExecutionResult? = null,
    val failure: Throwable? = null
)

internal class ProcessingCallbackOutcomeLedger {
    private val snapshot = AtomicReference(ProcessingCallbackOutcomeSnapshot())

    internal fun recordDispatch(result: ProcessingCallbackDispatchResult) {
        snapshot.updateAndGet { it.copy(dispatch = result) }
    }

    internal fun recordExecution(result: ProcessingCallbackExecutionResult, failure: Throwable?) {
        snapshot.updateAndGet { it.copy(execution = result, failure = failure) }
    }

    internal fun snapshot(): ProcessingCallbackOutcomeSnapshot = snapshot.get()
}

internal class ProcessingCallbackDispatcher(
    private val handler: Handler,
    private val tag: String,
    private val postOperation: ((Runnable) -> Boolean)? = null,
    private val executionObserver: (ProcessingCallbackExecutionResult, Throwable?) -> Unit = { _, _ -> },
    private val dispatchObserver: (ProcessingCallbackDispatchResult) -> Unit = {}
) {
    fun dispatch(callback: () -> Unit): ProcessingCallbackDispatchResult {
        return try {
            val post = postOperation ?: handler::post
            if (!post(Runnable {
                    try {
                        callback()
                        executionObserver(ProcessingCallbackExecutionResult.EXECUTED, null)
                    } catch (failure: Throwable) {
                        Log.e(tag, "processing callback execution failed", failure)
                        runCatching {
                            executionObserver(ProcessingCallbackExecutionResult.EXECUTION_FAILED, failure)
                        }
                    }
                })) {
                ProcessingCallbackDispatchResult.REJECTED.also(dispatchObserver)
            } else {
                ProcessingCallbackDispatchResult.ACCEPTED.also(dispatchObserver)
            }
        } catch (failure: Throwable) {
            Log.e(tag, "processing callback dispatch failed", failure)
            ProcessingCallbackDispatchResult.DISPATCH_THREW.also(dispatchObserver)
        }
    }
}
