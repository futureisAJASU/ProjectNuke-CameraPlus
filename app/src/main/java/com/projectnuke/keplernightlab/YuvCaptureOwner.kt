package com.projectnuke.keplernightlab

import android.util.Log
import java.io.File
import java.lang.Runnable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Immutable worker completion result.  The worker never mutates owner state; it only
 * encodes a temporary candidate and returns this result for owner-side adoption.
 *
 * Candidate ownership: [Success] ALWAYS carries a [YuvCandidateHandle]; [Failed]
 * carries one exactly when encoding created a partial candidate file (absent
 * otherwise).  [settleForOwnerRejection] is the single polymorphic emergency
 * settlement used when the owner event is rejected/disposed: it discards or
 * quarantines the candidate file exactly once through the filesystem operator.
 */
internal sealed interface YuvWorkerCompletion {
    val frameIndex: Int
    val timestampNs: Long

    data class Success(
        override val frameIndex: Int,
        override val timestampNs: Long,
        val candidateHandle: YuvCandidateHandle,
        val fileName: String,
        val encodeDurationMs: Long
    ) : YuvWorkerCompletion

    data class Failed(
        override val frameIndex: Int,
        override val timestampNs: Long,
        val candidateHandle: YuvCandidateHandle?,
        val cause: Throwable
    ) : YuvWorkerCompletion

    /** Emergency settlement for owner-event rejection/disposal: exactly-once cleanup. */
    fun settleForOwnerRejection(filesystem: YuvCandidateFilesystem): CandidateDisposalOutcome? {
        val handle = when (this) {
            is Success -> candidateHandle
            is Failed -> candidateHandle
        }
        return handle?.discardOrQuarantine(filesystem)
    }
}

/**
 * A disposable task for buffered YUV encoding that settles the lifecycle item in its
 * finally block and posts the completion back to the owner.  The owner schedules the
 * next buffered item after adopting this task's completion — the task itself never
 * schedules the next frame.
 *
 * Settlement-result consumption: the AtomicBoolean [settled] guard resolves the
 * run()/dispose() race so at most ONE settleEncoding attempt is made per task.  A
 * single-task settlement can only legitimately observe SETTLED, so every other outcome
 * is a genuine anomaly (e.g. the lifecycle was externally released or never started)
 * and is surfaced through [onSettlementIssue] exactly once.  In particular
 * ALREADY_SETTLING and ALREADY_RELEASED are NOT treated as accepted idempotent
 * outcomes — with the CAS guard they indicate the lifecycle state was already
 * inconsistent with this task's own settlement, and surfacing them is the only way
 * a lost release can be detected.
 */
internal class BufferedEncodeTask(
    val item: YuvPngWorkItem,
    private val accounting: YuvCaptureAccounting,
    private val lifecycle: YuvBufferedLifecycle,
    private val candidateFilesystem: YuvCandidateFilesystem,
    private val encode: () -> YuvWorkerCompletion,
    private val postCompletion: (YuvWorkerCompletion) -> Unit,
    private val onSettlementIssue: ((YuvPngWorkItem, YuvBufferedLifecycle.EncodingSettlementOutcome) -> Unit)? = null
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
        val settledCleanly = outcome.status == YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED &&
            outcome.failure == null && outcome.lifecycleReleaseFailure == null
        if (settledCleanly) return
        onSettlementIssue?.let { hook ->
            try {
                hook(item, outcome)
            } catch (_: Throwable) {
                // The issue hook must never throw into worker cleanup.
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
    private val cleanupCoordinator: YuvCleanupCoordinator,
    private val candidateFilesystem: YuvCandidateFilesystem = RealYuvCandidateFilesystem,
    private val candidateVerifier: YuvCandidateVerifier = RealYuvCandidateVerifier,
    private val finalFileVerifier: YuvFinalFileVerifier = RealYuvFinalFileVerifier
) {

    private var completedResults = 0
    private var terminalReason: String? = null
    private val discardedLateCompletions = mutableListOf<Int>()
    private val callbackFired = AtomicBoolean(false)

    /**
     * Cleanup debt recorded for candidates/final files that could neither be deleted
     * nor quarantined (or whose removal failed).  Thread-safe because the emergency
     * settlement path (owner-event rejection) runs off the owner dispatcher.
     */
    private val candidateCleanupDebts = java.util.concurrent.CopyOnWriteArrayList<String>()

    internal fun candidateCleanupDebt(): List<String> = candidateCleanupDebts.toList()

    private fun recordCandidateDebt(settlement: CandidateDisposalOutcome, frameIndex: Int, file: File) {
        val description = settlement.failureDescription(frameIndex, file)
        if (description != null) {
            candidateCleanupDebts.add(description)
            Log.e("KeplerYuvOwner", description)
        }
    }

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
                                    YuvWorkerCompletion.Success(
                                        item.frameIndex, item.timestampNs,
                                        YuvCandidateHandle(item.frameIndex, candidate),
                                        fileName, 0L
                                    )
                                } catch (t: Throwable) {
                                    YuvWorkerCompletion.Failed(
                                        item.frameIndex, item.timestampNs,
                                        if (candidate.exists()) YuvCandidateHandle(item.frameIndex, candidate) else null,
                                        t
                                    )
                                }
                                try {
                                    captureStateOwner.post(object : CaptureOwnerEvent {
                                        override fun execute() { adoptCompletion(completion) }
                                        override fun disposeWithoutMutation() {
                                            completion.settleForOwnerRejection(candidateFilesystem)
                                        }
                                    })
                                } finally {
                                    item.dispose()
                                }
                            }
                            override fun dispose() { item.dispose() }
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
                candidateFilesystem = candidateFilesystem,
                encode = {
                try {
                    workProcessor.encode(frame, candidate, rotationDegrees)
                    YuvWorkerCompletion.Success(
                        frame.frameIndex, frame.timestampNs,
                        YuvCandidateHandle(frame.frameIndex, candidate),
                        fileName, 0L
                    )
                } catch (t: Throwable) {
                    YuvWorkerCompletion.Failed(
                        frame.frameIndex, frame.timestampNs,
                        if (candidate.exists()) YuvCandidateHandle(frame.frameIndex, candidate) else null,
                        t
                    )
                }
            },
            postCompletion = { completion ->
                captureStateOwner.post(object : CaptureOwnerEvent {
                    override fun execute() { adoptCompletion(completion) }
                    override fun disposeWithoutMutation() {
                        completion.settleForOwnerRejection(candidateFilesystem)
                    }
                })
            },
            onSettlementIssue = { issueItem, outcome ->
                val cause: Throwable? = outcome.failure ?: outcome.lifecycleReleaseFailure
                if (cause != null) {
                    Log.w("KeplerYuvOwner", "YUV settlement issue frame=${issueItem.frameIndex}: ${outcome.status}", cause)
                } else {
                    Log.w("KeplerYuvOwner", "YUV settlement issue frame=${issueItem.frameIndex}: ${outcome.status}")
                }
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
                // Partial candidates (encode failed after creating a file) are settled
                // through the same candidate path as Success candidates.
                completion.candidateHandle?.let { handle ->
                    recordCandidateDebt(handle.discardOrQuarantine(candidateFilesystem), handle.frameIndex, handle.file)
                }
                if (terminalState.status() == CaptureTerminalStatus.ACTIVE) accounting.failedFrame()
            }
        }
        checkTerminal()
        scheduleBufferedEncoding()
    }

    /**
     * Fail-closed adoption pipeline (owner event, serialized):
     * validate candidate -> reserve identity+filename -> collision policy (preserve
     * pre-existing final files) -> commit -> verify final -> commit manifest entry ->
     * settle candidate ADOPTED.  Every failure rolls back the reservation, preserves
     * pre-existing files, removes/quarantines only newly created invalid finals,
     * settles the candidate, and records the exact failure/cleanup debt.  An untracked
     * final PNG can never be left behind.
     */
    private fun adoptSuccess(completion: YuvWorkerCompletion.Success) {
        val handle = completion.candidateHandle
        if (terminalState.status() != CaptureTerminalStatus.ACTIVE) {
            // Late completion: settle the candidate (discard/quarantine), never adopt.
            discardedLateCompletions += completion.frameIndex
            recordCandidateDebt(handle.discardOrQuarantine(candidateFilesystem), handle.frameIndex, handle.file)
            return
        }
        val entry = YuvFrameManifestEntry(completion.frameIndex, completion.fileName, completion.timestampNs, true)

        // 1. Fail-closed candidate validation (injectable verifier seam)
        if (!candidateVerifier.verify(handle.file, completion.frameIndex)) {
            Log.e("KeplerYuvOwner", "Candidate validation failed for frame ${completion.frameIndex}: ${handle.file}")
            recordCandidateDebt(handle.discardOrQuarantine(candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            return
        }

        // 2. Reserve frame identity + final filename atomically (reservation alone
        //    never touches the manifest or persistedFrames)
        val token = accounting.tryReserveAdoption(entry) ?: run {
            // Duplicate/late completion: settle the candidate without touching any
            // adopted final file.
            Log.w("KeplerYuvOwner", "Duplicate adoption reservation rejected for frame ${completion.frameIndex}")
            recordCandidateDebt(handle.discardOrQuarantine(candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            return
        }
        val finalFile = File(outputDir, completion.fileName)

        // 3. Fail-closed collision policy: preserve ANY unexpected pre-existing final
        //    file (never delete it); roll back and discard/quarantine the candidate.
        if (finalFile.exists()) {
            Log.w("KeplerYuvOwner", "Unexpected pre-existing final file for frame ${completion.frameIndex}: ${finalFile.path}")
            token.rollback()
            recordCandidateDebt(handle.discardOrQuarantine(candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            return
        }

        // 4. Commit candidate -> final
        try {
            workProcessor.commit(handle.file, finalFile)
        } catch (t: Throwable) {
            Log.e("KeplerYuvOwner", "Commit failed for frame ${completion.frameIndex}", t)
            token.rollback()
            recordCandidateDebt(handle.discardOrQuarantine(candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            finishError("YUV commit failed for frame ${completion.frameIndex}", cause = t)
            return
        }

        // 5. Fail-closed verification of the newly created final file; only the newly
        //    created file may be removed/quarantined.
        if (!finalFileVerifier.verify(finalFile, completion.frameIndex)) {
            Log.w("KeplerYuvOwner", "Final file verification failed after commit for frame ${completion.frameIndex}")
            token.rollback()
            removeOrQuarantineCreatedFinal(finalFile)
            // The candidate was consumed by the commit; the handle settles as DISCARDED
            // (file absent) via the same polymorphic settlement path.
            recordCandidateDebt(handle.discardOrQuarantine(candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            return
        }

        // 6. Commit manifest entry + persistedFrames (exactly-once token commit)
        if (!token.commit()) {
            Log.w("KeplerYuvOwner", "Adoption commit failed for frame ${completion.frameIndex}")
            // The token is already settled; never delete an adopted/uncertain final.
            recordCandidateDebt(handle.discardOrQuarantine(candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            return
        }

        // 7. Settle the candidate ADOPTED (exactly-once UNSETTLED -> ADOPTED)
        if (!handle.adopt()) {
            Log.w("KeplerYuvOwner", "Candidate settle to ADOPTED failed for frame ${completion.frameIndex}")
        }
        val persistedFrames = accounting.snapshot().persistedFrames
        postStatus("YUV capture: saved $persistedFrames/$frameCount")
        writeJobJson("CAPTURING", persistedFrames, accounting.snapshot().manifest)
    }

    /**
     * Removes/quarantines ONLY the newly created invalid final file (never a
     * pre-existing one).  A failed removal records the cleanup debt explicitly.
     */
    private fun removeOrQuarantineCreatedFinal(file: File) {
        val deleteResult = candidateFilesystem.delete(file)
        if (deleteResult != CandidateFileOperationResult.DELETED &&
            deleteResult != CandidateFileOperationResult.FILE_ABSENT) {
            val quarantineResult = candidateFilesystem.quarantine(file)
            if (quarantineResult != CandidateFileOperationResult.QUARANTINED) {
                val description =
                    "final-file cleanup debt file=$file delete=$deleteResult quarantine=$quarantineResult"
                candidateCleanupDebts.add(description)
                Log.e("KeplerYuvOwner", description)
            }
        }
    }

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
