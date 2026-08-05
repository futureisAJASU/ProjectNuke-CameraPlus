package com.projectnuke.keplernightlab

import android.util.Log
import java.io.File
import java.lang.Runnable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Immutable worker completion result.  The worker never mutates owner state; it only
 * encodes a temporary candidate and returns this result for owner-side adoption.
 */
internal sealed interface YuvWorkerCompletion {
    val frameIndex: Int
    val timestampNs: Long

    data class Success(
        override val frameIndex: Int,
        override val timestampNs: Long,
        val candidate: File,
        val fileName: String,
        val encodeDurationMs: Long
    ) : YuvWorkerCompletion

    data class Failed(
        override val frameIndex: Int,
        override val timestampNs: Long,
        val candidate: File?,
        val cause: Throwable
    ) : YuvWorkerCompletion
}

/**
 * A disposable task for buffered YUV encoding that settles the lifecycle item in its
 * finally block and posts the completion back to the owner.  The owner schedules the
 * next buffered item after adopting this task's completion ??the task itself never
 * schedules the next frame.
 */
internal class BufferedEncodeTask(
    val item: YuvPngWorkItem,
    private val accounting: YuvCaptureAccounting,
    private val lifecycle: YuvBufferedLifecycle,
    private val encode: () -> YuvWorkerCompletion,
    private val postCompletion: (YuvWorkerCompletion) -> Unit,
    private val onSettlementFailure: ((YuvPngWorkItem, Throwable) -> Unit)? = null
) : DisposableCaptureTask {
    private val settled = AtomicBoolean(false)

    override fun run() {
        val completion = try {
            encode()
        } catch (t: Throwable) {
            YuvWorkerCompletion.Failed(item.frameIndex, item.timestampNs, null, t)
        }
        try {
            postCompletion(completion)
        } finally {
            if (settled.compareAndSet(false, true)) {
                settleItem()
            }
        }
    }

    override fun dispose() {
        if (settled.compareAndSet(false, true)) {
            settleItem()
        }
    }

    private fun settleItem() {
        val outcome = lifecycle.settleEncoding(item, accounting)
        if (outcome.failure != null) {
            onSettlementFailure?.let { hook ->
                try { hook(item, outcome.failure!!) } catch (_: Throwable) {}
            }
        }
    }
}

/**
 * YuvCaptureOwner is the single authoritative owner for the YUV capture pipeline state.
 *
 * Camera callbacks, the timeout scheduler, cancellation, and worker completions may only
 * post immutable events.  All mutable state transitions happen on the owner's serialized
 * dispatcher (the capture handler thread).  This ensures there is exactly one point of
 * truth for: terminal state, frame identity allocation, received/buffered/persisted/failed/
 * dropped counters, the persisted manifest, retained-byte registration, completed capture-
 * result count, and success/error callback decision.
 *
 * Terminal states (only the owner may transition out of ACTIVE):
 *  ACTIVE -> SUCCESS | PARTIAL_SUCCESS | FAILED | TIMED_OUT | CANCELLED
 */
internal class YuvCaptureOwner(
    private val captureStateOwner: CaptureStateOwner,
    private val outputDir: File,
    private val rotationDegrees: Int,
    private val frameCount: Int,
    private val workProcessor: YuvPngWorkProcessor,
    private val reservations: YuvBufferReservations,
    private val accounting: YuvCaptureAccounting,
    private val lifecycle: YuvBufferedLifecycle,
    private val identityOwner: CaptureFrameIdentityOwner,
    private val terminalState: CaptureTerminalState,
    private val boundedWorker: BoundedCaptureWorker,
    private val finished: AtomicBoolean,
    private val postStatus: (String) -> Unit,
    private val postMainOrRun: (Runnable) -> Unit,
    private val writeJobJson: (status: String, savedFrames: Int, manifest: List<YuvFrameManifestEntry>) -> Unit,
    private val saveMotionOnce: (File) -> Pair<String?, String?>,
    private val onCaptureComplete: (File) -> Unit,
    private val onCaptureError: (message: String, cause: Throwable?) -> Unit,
    private val cleanupCoordinator: YuvCleanupCoordinator
) {

    private var completedResults = 0
    private var terminalReason: String? = null
    private val discardedLateCompletions = mutableListOf<Int>()
    private val callbackFired = AtomicBoolean(false)

    // ------------------------------------------------------------------
    // Typed Camera2 callback entry points (no unsafe casts)
    // ------------------------------------------------------------------

    fun acceptBuffered(access: YuvImageAccess) {
        val event = object : CaptureOwnerEvent {
            val guard = YuvImageReleaseGuard(access)
            override fun execute() {
                if (terminalState.status() != CaptureTerminalStatus.ACTIVE) { guard.releaseSafely(); return }
                val frameIndex = identityOwner.nextIdentity()
                if (frameIndex == null) { accounting.droppedFrame(); guard.releaseSafely(); return }
                accounting.receivedFrame()
                when (val c = createBufferedYuvWork(frameIndex, access, reservations, accounting)) {
                    is BufferedYuvWorkCreation.Accepted -> {
                        if (!lifecycle.tryRegister(c.item)) { c.item.dispose(accounting); return }
                        postStatus("YUV buffered frame ${accounting.snapshot().bufferedFrames}/$frameCount")
                        scheduleBufferedEncoding()
                    }
                    BufferedYuvWorkCreation.Rejected ->
                        postStatus("YUV memory buffer dropped frame ${frameIndex + 1}/$frameCount: retained=${reservations.currentBytes()} bytes")
                    is BufferedYuvWorkCreation.Failed -> {
                        if (c.cause is Error) throw c.cause
                        finishError("YUV memory buffer copy failed", cause = c.cause)
                    }
                }
            }
            override fun disposeWithoutMutation() = guard.releaseSafely()
        }
        // post() either accepted (event settled by owner) or rejected (event
        // already disposed via disposeWithoutMutation).  NEVER release access
        // after a rejected post — the envelope already disposed it.
        captureStateOwner.post(event)
    }

    fun acceptDirect(access: DirectYuvImageAccess) {
        val event = object : CaptureOwnerEvent {
            val guard = YuvImageReleaseGuard(access)
            override fun execute() {
                if (terminalState.status() != CaptureTerminalStatus.ACTIVE) { guard.releaseSafely(); return }
                val frameIndex = identityOwner.nextIdentity()
                if (frameIndex == null) { accounting.droppedFrame(); guard.releaseSafely(); return }
                accounting.receivedFrame()
                when (val creation = createDirectYuvWork(frameIndex, access, accounting)) {
                    is DirectYuvWorkCreation.Accepted -> {
                        val item = creation.item
                        val fileName = "frame_${item.frameIndex.toString().padStart(2, '0')}_color.png"
                        val candidate = File(outputDir, ".${fileName}.${System.nanoTime()}.tmp")
                        val task = object : DisposableCaptureTask {
                            override fun run() {
                                val completion = try {
                                    workProcessor.encode(item, candidate, rotationDegrees)
                                    YuvWorkerCompletion.Success(item.frameIndex, item.timestampNs, candidate, fileName, 0L)
                                } catch (t: Throwable) {
                                    YuvWorkerCompletion.Failed(item.frameIndex, item.timestampNs, candidate, t)
                                }
                                try {
                                    captureStateOwner.post(object : CaptureOwnerEvent {
                                        override fun execute() { adoptCompletion(completion) }
                                        override fun disposeWithoutMutation() {
                                            if (completion is YuvWorkerCompletion.Success) runCatching { completion.candidate.delete() }
                                        }
                                    })
                                } finally {
                                    item.dispose()
                                }
                            }
                            override fun dispose() = item.dispose()
                        }
                        if (!boundedWorker.submit(task)) {
                            // Worker already called task.dispose() which calls item.dispose().
                            // Do NOT double-dispose.
                            accounting.droppedFrame()
                            postStatus("YUV direct backpressure: frame ${item.frameIndex + 1} dropped")
                        }
                    }
                    is DirectYuvWorkCreation.Failed -> {
                        if (creation.cause is Error) throw creation.cause
                        finishError("YUV direct creation failed", cause = creation.cause)
                    }
                }
            }
            override fun disposeWithoutMutation() = guard.releaseSafely()
        }
        captureStateOwner.post(event)
    }

    fun onCaptureCompletedResult() {
        captureStateOwner.post(object : CaptureOwnerEvent {
            override fun execute() { completedResults++ }
            override fun disposeWithoutMutation() {}
        })
    }

    fun onCaptureFailed(cause: Throwable, detail: String) {
        captureStateOwner.post(object : CaptureOwnerEvent {
            override fun execute() { finishError("Color Burst 罹≪쿂 ?ㅽ뙣: $detail", cause = cause) }
            override fun disposeWithoutMutation() {}
        })
    }

    // ------------------------------------------------------------------
    // Buffered encoding scheduling (owner-controlled)
    // ------------------------------------------------------------------

    private fun scheduleBufferedEncoding() {
        val frame = lifecycle.snapshotRetainedByFrameIndex().firstOrNull() ?: return
        if (!lifecycle.beginEncoding(frame)) return

        val fileName = "frame_${frame.frameIndex.toString().padStart(2, '0')}_color.png"
        val candidate = File(outputDir, ".${fileName}.${System.nanoTime()}.tmp")
            val task = BufferedEncodeTask(
                item = frame,
                accounting = accounting,
                lifecycle = lifecycle,
                encode = {
                try {
                    workProcessor.encode(frame, candidate, rotationDegrees)
                    YuvWorkerCompletion.Success(frame.frameIndex, frame.timestampNs, candidate, fileName, 0L)
                } catch (t: Throwable) {
                    YuvWorkerCompletion.Failed(frame.frameIndex, frame.timestampNs, candidate, t)
                }
            },
            postCompletion = { completion ->
                captureStateOwner.post(object : CaptureOwnerEvent {
                    override fun execute() { adoptCompletion(completion) }
                    override fun disposeWithoutMutation() {
                        if (completion is YuvWorkerCompletion.Success) runCatching { completion.candidate.delete() }
                    }
                })
            }
        )
        if (!boundedWorker.submit(task)) {
            // Worker already called task.dispose() which calls lifecycle.settleEncoding.
            // Do NOT double-settle or double-dispose the item.
            accounting.droppedFrame()
            postStatus("YUV backpressure: buffered frame ${frame.frameIndex + 1} dropped")
        }
    }

    // ------------------------------------------------------------------
    // Owner-side atomic adoption
    // ------------------------------------------------------------------

    private fun adoptCompletion(completion: YuvWorkerCompletion) {
        when (completion) {
            is YuvWorkerCompletion.Success -> adoptSuccess(completion)
            is YuvWorkerCompletion.Failed -> {
                Log.e("KeplerYuvOwner", "YUV worker failed for frame ${completion.frameIndex}", completion.cause)
                completion.candidate?.let { runCatching { it.delete() } }
                if (terminalState.status() == CaptureTerminalStatus.ACTIVE) accounting.failedFrame()
            }
        }
        checkTerminal()
        scheduleBufferedEncoding()
    }

    private fun adoptSuccess(completion: YuvWorkerCompletion.Success) {
        if (terminalState.status() != CaptureTerminalStatus.ACTIVE) {
            discardedLateCompletions += completion.frameIndex
            runCatching { completion.candidate.delete() }
            return
        }
        val entry = YuvFrameManifestEntry(completion.frameIndex, completion.fileName, completion.timestampNs, true)
        val token = accounting.tryReserveAdoption(entry) ?: run {
            runCatching { completion.candidate.delete() }
            accounting.failedFrame()
            return
        }
        val finalFile = File(outputDir, completion.fileName)

        // Fail-closed filesystem validation: reject unexpected pre-existing final file
        if (finalFile.exists() && !finalFile.canRead()) {
            Log.w("KeplerYuvOwner", "Pre-existing unreadable final file for ${completion.frameIndex}")
            // Remove the bad file so we can retry, or quarantine it.
            runCatching { finalFile.delete() }
        }
        if (finalFile.exists()) {
            Log.w("KeplerYuvOwner", "Unexpected pre-existing final file for ${completion.frameIndex}")
            accounting.rollbackAdoption(token)
            runCatching { completion.candidate.delete() }
            accounting.failedFrame()
            return
        }

        try {
            workProcessor.commit(completion.candidate, finalFile)
        } catch (t: Throwable) {
            Log.e("KeplerYuvOwner", "Commit failed for frame ${completion.frameIndex}", t)
            accounting.rollbackAdoption(token)
            runCatching { completion.candidate.delete() }
            accounting.failedFrame()
            finishError("YUV commit failed for frame ${completion.frameIndex}", cause = t)
            return
        }
        if (!finalFile.exists() || !finalFile.canRead()) {
            Log.w("KeplerYuvOwner", "Final file not readable after commit ${completion.frameIndex}")
            accounting.rollbackAdoption(token)
            removeCreatedFinal(finalFile)
            accounting.failedFrame()
            return
        }
        if (!accounting.commitAdoption(token)) {
            Log.w("KeplerYuvOwner", "Adoption commit failed for frame ${completion.frameIndex}")
            runCatching { completion.candidate.delete() }
            removeCreatedFinal(finalFile)
            accounting.failedFrame()
            return
        }
        val persistedFrames = accounting.snapshot().persistedFrames
        postStatus("YUV capture: saved $persistedFrames/$frameCount")
        writeJobJson("CAPTURING", persistedFrames, accounting.snapshot().manifest)
    }

    private fun removeCreatedFinal(file: File) { runCatching { if (file.exists()) file.delete() } }

    // ------------------------------------------------------------------
    // Terminal state and deadline settlement
    // ------------------------------------------------------------------

    private fun checkTerminal() {
        val snap = accounting.snapshot()
        if (snap.persistedFrames >= frameCount && terminalState.claim(CaptureTerminalStatus.SUCCESS)) {
            completeSuccess()
        }
    }

    fun onDeadlineReached() {
        val event = object : CaptureOwnerEvent {
            override fun execute() {
                // Compute outcome from ONE snapshot, then claim the exact terminal state.
                val snap = accounting.snapshot()
                when {
                    snap.persistedFrames >= frameCount -> {
                        if (terminalState.claim(CaptureTerminalStatus.SUCCESS)) completeSuccess()
                    }
                    snap.persistedFrames > 0 -> {
                        if (terminalState.claim(CaptureTerminalStatus.PARTIAL_SUCCESS)) completePartial(snap)
                    }
                    else -> {
                        if (terminalState.claim(CaptureTerminalStatus.TIMED_OUT)) completeTimeout(snap)
                    }
                }
            }
            override fun disposeWithoutMutation() {}
        }
        if (!captureStateOwner.post(event)) {
            Log.e("KeplerYuvOwner", "Deadline settlement rejected")
            finished.set(true)
            cleanupCoordinator.perform()
        }
    }

    fun onCancellationRequested() {
        val event = object : CaptureOwnerEvent {
            override fun execute() {
                if (terminalState.claim(CaptureTerminalStatus.CANCELLED)) completeCancel()
            }
            override fun disposeWithoutMutation() {}
        }
        if (!captureStateOwner.post(event)) {
            Log.e("KeplerYuvOwner", "Cancellation rejected")
            finished.set(true)
            cleanupCoordinator.perform()
        }
    }

    // ------------------------------------------------------------------
    // Terminal settlement ??only the owner writes metadata and decides callbacks
    // ------------------------------------------------------------------

    private fun completeSuccess() {
        terminalReason = "All $frameCount frames persisted"
        if (!callbackFired.compareAndSet(false, true)) return
        finished.set(true)
        val snap = accounting.snapshot()
        saveMotionOnce(outputDir)
        writeJobJson("CAPTURE_COMPLETE", snap.persistedFrames, snap.manifest)
        postStatus("CAPTURE_COMPLETE: 罹≪쿂媛 ?꾨즺?섏뿀?듬땲??")
        cleanup()
        postMainOrRun { onCaptureComplete(outputDir) }
    }

    private fun completePartial(snap: YuvCaptureAccountingSnapshot) {
        terminalReason = "Partial success: ${snap.persistedFrames}/$frameCount persisted"
        if (!callbackFired.compareAndSet(false, true)) return
        finished.set(true)
        saveMotionOnce(outputDir)
        writeJobJson("CAPTURE_PARTIAL", snap.persistedFrames, snap.manifest)
        postStatus("Captured partial success")
        cleanup()
        postMainOrRun { onCaptureComplete(outputDir) }
    }

    private fun completeTimeout(snap: YuvCaptureAccountingSnapshot) {
        terminalReason = "YUV timeout: saved=${snap.persistedFrames}/$frameCount"
        if (!callbackFired.compareAndSet(false, true)) return
        finished.set(true)
        Log.e("KeplerYuvOwner", terminalReason ?: "YUV timeout")
        writeJobJson("CAPTURE_TIMEOUT", snap.persistedFrames, snap.manifest)
        postStatus(terminalReason!!)
        cleanup()
        postMainOrRun { onCaptureError(terminalReason!!, null) }
    }

    private fun finishError(
        message: String,
        cause: Throwable? = null,
        terminalAlreadyClaimed: Boolean = false
    ) {
        if (!terminalAlreadyClaimed && !terminalState.claim(CaptureTerminalStatus.FAILED)) return
        terminalReason = message
        if (!callbackFired.compareAndSet(false, true)) return
        finished.set(true)
        Log.e("KeplerYuvOwner", message, cause)
        val snap = accounting.snapshot()
        writeJobJson("CAPTURE_FAILED", snap.persistedFrames, snap.manifest)
        postStatus(message)
        cleanup()
        postMainOrRun { onCaptureError(message, cause) }
    }

    private fun completeCancel() {
        terminalReason = "Cancelled"
        if (!callbackFired.compareAndSet(false, true)) return
        finished.set(true)
        val snap = accounting.snapshot()
        writeJobJson("CAPTURE_CANCELLED", snap.persistedFrames, snap.manifest)
        postStatus("CAPTURE_CANCELLED: YUV capture cancelled")
        cleanup()
        postMainOrRun { onCaptureError(terminalReason!!, null) }
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    private fun cleanup() {
        cleanupCoordinator.perform()
    }

    // ------------------------------------------------------------------
    // Snapshot
    // ------------------------------------------------------------------

    /**
     * Number of [CameraCaptureSession.CaptureCallback.onCaptureCompleted] events received
     * so far.  Visible to the production caller for failure-snapshot observability; the
     * counter itself is mutated only on the owner's serialized dispatcher.
     */
    fun completedResultsCount(): Int = completedResults

    fun terminalState(): CaptureTerminalState = terminalState

    internal fun terminalSnapshot(): TerminalSnapshot {
        val snap = accounting.snapshot()
        return TerminalSnapshot(
            receivedFrames = snap.receivedFrames,
            bufferedFrames = snap.bufferedFrames,
            persistedFrames = snap.persistedFrames,
            failedFrames = snap.failedFrames,
            droppedFrames = snap.droppedFrames,
            manifest = snap.manifest,
            completedResults = completedResults,
            queuedWork = boundedWorker.queuedCount(),
            inFlightWork = boundedWorker.activeCount(),
            terminalStatus = terminalState.status(),
            terminalReason = terminalReason,
            discardedLateCompletions = discardedLateCompletions.toList()
        )
    }

    internal data class TerminalSnapshot(
        val receivedFrames: Int,
        val bufferedFrames: Int,
        val persistedFrames: Int,
        val failedFrames: Int,
        val droppedFrames: Int,
        val manifest: List<YuvFrameManifestEntry>,
        val completedResults: Int,
        val queuedWork: Int,
        val inFlightWork: Int,
        val terminalStatus: CaptureTerminalStatus,
        val terminalReason: String?,
        val discardedLateCompletions: List<Int>
    )
}
