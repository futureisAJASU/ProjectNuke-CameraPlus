package com.projectnuke.keplernightlab

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Camera2 callback executor that preserves Executor rejection semantics. */
internal class PreviewCameraCallbackExecutor(
    private val dispatch: (Runnable) -> Boolean,
    private val onDispatchFailure: (Throwable) -> Unit
) : Executor {
    override fun execute(command: Runnable) {
        try {
            if (!dispatch(command)) {
                val failure = RejectedExecutionException("preview callback handler rejected command")
                onDispatchFailure(failure)
                throw failure
            }
        } catch (failure: Throwable) {
            if (failure !is RejectedExecutionException) onDispatchFailure(failure)
            throw failure
        }
    }
}
