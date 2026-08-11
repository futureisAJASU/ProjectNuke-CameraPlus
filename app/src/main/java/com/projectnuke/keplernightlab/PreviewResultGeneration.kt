package com.projectnuke.keplernightlab

internal fun acceptsPreviewResultGeneration(
    resultGeneration: Int,
    currentGeneration: Int,
    coroutineActive: Boolean
): Boolean = coroutineActive && resultGeneration == currentGeneration

internal fun dispatchPreviewError(
    post: (Runnable) -> Boolean,
    work: Runnable
): CameraUiDispatchOutcome = try {
    if (post(work)) CameraUiDispatchOutcome.ACCEPTED else CameraUiDispatchOutcome.REJECTED
} catch (_: Throwable) {
    CameraUiDispatchOutcome.DISPATCH_THREW
}
