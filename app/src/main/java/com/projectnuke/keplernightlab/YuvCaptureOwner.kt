package com.projectnuke.keplernightlab

import android.util.Log
import java.io.File
import java.lang.Runnable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
 * Settlement is guarded by a three-state machine [TaskSettlementState]: at most ONE
 * settleEncoding attempt is made per task via a NOT_STARTED → SETTLING CAS.  A
 * single-task settlement can only legitimately observe SETTLED, so every other outcome
 * is a genuine anomaly (e.g. the lifecycle was externally released or never started)
 * and is surfaced through [onSettlementIssue] exactly once.  In particular
 * ALREADY_SETTLING and ALREADY_RELEASED are NOT treated as accepted idempotent
 * outcomes — with the CAS guard they indicate the lifecycle state was already
 * inconsistent with this task's own settlement, and surfacing them is the only way
 * a lost release can be detected.
 *
 * Task publication state: [taskState] and [settledOutcome] expose the settlement
 * state machine and the published outcome so observers (cleanup coordinator, tests)
 * can check whether the task is NOT_STARTED / SETTLING / SETTLED without racing.
 */
/** BufferedEncodeTask */
internal class BufferedEncodeTask(
    val item: YuvPngWorkItem,
    private val accounting: YuvCaptureAccounting,
    private val lifecycle: YuvBufferedLifecycle,
    private val candidateFilesystem: YuvCandidateFilesystem,
    private val encode: () -> YuvWorkerCompletion,
    private val postCompletion: (YuvWorkerCompletion) -> Unit,
    private val onSettlementIssue: ((YuvPngWorkItem, YuvBufferedLifecycle.EncodingSettlementOutcome) -> Unit)? = null,
    private val onWorkDisposalDebt: ((YuvPngWorkItem, YuvWorkDisposalOutcome) -> Unit)? = null
) : OutcomeDisposableCaptureTask {

    internal enum class TaskSettlementState { NOT_STARTED, SETTLING, SETTLED }

    private val settlementState = AtomicReference(TaskSettlementState.NOT_STARTED)
    private val settledOutcome = AtomicReference<CaptureTaskDisposalOutcome?>(null)

    /** Publicly observable task publication state. */
    fun taskState(): TaskSettlementState = settlementState.get()

    /** The settled outcome (null until the task reaches SETTLED). */
    fun settledOutcome(): CaptureTaskDisposalOutcome? = settledOutcome.get()

    override fun run() {
        val completion = try {
            encode()
        } catch (t: Throwable) {
            YuvWorkerCompletion.Failed(item.frameIndex, item.timestampNs, null, t)
        }
        try {
            postCompletion(completion)
        } finally {
            attemptSettle()
        }
    }

    override fun dispose() { disposeWithOutcome() }

    override fun disposeWithOutcome(): CaptureTaskDisposalOutcome = attemptSettle()

    /**
     * Exactly-once settlement via NOT_STARTED → SETTLING CAS.  The winner performs
     * the lifecycle settlement and publishes SETTLED + the outcome; concurrent or
     * repeated callers observe SETTLING (in-progress) or SETTLED (published outcome).
     */
    private fun attemptSettle(): CaptureTaskDisposalOutcome {
        if (settlementState.compareAndSet(TaskSettlementState.NOT_STARTED, TaskSettlementState.SETTLING)) {
            return settleItemAndReport()
        }
        return when (val s = settlementState.get()) {
            TaskSettlementState.SETTLING -> settledOutcome.get()?.let { asMirrored(it) }
                ?: CaptureTaskDisposalOutcome.Unclean(null, "bufferedTaskDispose frame=${item.frameIndex}: settlement in progress")
            TaskSettlementState.SETTLED -> settledOutcome.get()?.let { asMirrored(it) }
                ?: CaptureTaskDisposalOutcome.Clean
            TaskSettlementState.NOT_STARTED -> CaptureTaskDisposalOutcome.Clean
        }
    }

    private fun settleItemAndReport(): CaptureTaskDisposalOutcome {
        val outcome = lifecycle.settleEncoding(item, accounting)
        val settledCleanly = outcome.status == YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED &&
            outcome.failure == null && outcome.lifecycleReleaseFailure == null
        val taskOutcome = if (settledCleanly) {
            CaptureTaskDisposalOutcome.Clean
        } else {
            // Record any work-item disposal debt so it stays observable
            val disposal = item.disposalOutcome()
            if (disposal != null && !disposal.isClean) {
                onWorkDisposalDebt?.invoke(item, disposal)
            }
            onSettlementIssue?.let { hook ->
                try {
                    hook(item, outcome)
                } catch (_: Throwable) {}
            }
            val detail = listOfNotNull(outcome.failure, outcome.lifecycleReleaseFailure)
                .joinToString("; ") { "${it::class.java.simpleName}: ${it.message}" }
            CaptureTaskDisposalOutcome.Unclean(disposal, "bufferedTaskDispose frame=${item.frameIndex}: ${outcome.status} $detail")
        }
        settledOutcome.set(taskOutcome)
        settlementState.set(TaskSettlementState.SETTLED)
        return taskOutcome
    }

    private fun asMirrored(taskOutcome: CaptureTaskDisposalOutcome): CaptureTaskDisposalOutcome {
        if (taskOutcome is CaptureTaskDisposalOutcome.Clean) return taskOutcome
        if (taskOutcome is CaptureTaskDisposalOutcome.Unclean) {
            val disposal = item.disposalOutcome()
            return CaptureTaskDisposalOutcome.Unclean(disposal, taskOutcome.description)
        }
        return taskOutcome
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

    /**
     * Records an adoption-rollback recovery failure as observable debt: a throwing
     * [YuvCaptureAccounting.rollbackAdoption], still-held reservations, or asymmetric
     * index/filename corruption must never be silently dropped.
     */
    private fun recordRollbackRecovery(
        entry: YuvFrameManifestEntry,
        result: AdoptionToken.AdoptionRecoveryResult
    ) {
        if (result.recoveryFailure == null && result.released && !result.asymmetric) return
        val failurePart = result.recoveryFailure?.let {
            " recoveryFailure=" + it::class.java.simpleName + ": " + it.message
        } ?: ""
        val description = "adoption rollback recovery frame=${entry.frameIndex} file=${entry.filename}" +
            " rollbackAttempted=${result.rollbackAttempted}" +
            " rollbackReturnedSuccess=${result.rollbackReturnedSuccess}" +
            " released=${result.released} remainingIndex=${result.indexReservationRemaining}" +
            " remainingFilename=${result.filenameReservationRemaining} asymmetric=${result.asymmetric}" +
            failurePart
        candidateCleanupDebts.add(description)
        Log.e("KeplerYuvOwner", description)
    }

    /**
     * Records every unclean work-item disposal as observable cleanup debt.
     * A non-clean outcome with an empty throwable list (e.g. missing requirements,
     * in-progress, or buffered account not provided) is still debt — never silently
     * passed.  Never throws.
     */
    private fun recordWorkDisposalDebt(item: YuvPngWorkItem, outcome: YuvWorkDisposalOutcome) {
        if (outcome.isClean) return
        val description = disposalDescription(outcome, item.frameIndex)
        candidateCleanupDebts.add(description)
        Log.e("KeplerYuvOwner", description)
    }

    private fun recordDisposalIfUnclean(item: YuvPngWorkItem, outcome: YuvWorkDisposalOutcome) {
        recordWorkDisposalDebt(item, outcome)
    }

    // ------------------------------------------------------------------
    // Typed Camera2 callback entry points (no unsafe casts)
    // ------------------------------------------------------------------

    fun acceptBuffered(access: YuvImageAccess) {
        val event = object : CaptureOwnerEvent {
            val guard = YuvImageReleaseGuard(access)
            override fun execute() {
                if (terminalState.status() != CaptureTerminalStatus.ACTIVE) { guard.releaseSafely(); return }
                // receivedFrames counts the acquired access as soon as owner processing
                // begins — even when no frame identity remains (then dropped++ too).
                accounting.receivedFrame()
                val frameIndex = identityOwner.nextIdentity()
                if (frameIndex == null) { accounting.droppedFrame(); guard.releaseSafely(); return }
                when (val c = createBufferedYuvWork(frameIndex, access, reservations, accounting)) {
                    is BufferedYuvWorkCreation.Accepted -> {
                        if (!lifecycle.tryRegister(c.item)) {
                            recordDisposalIfUnclean(c.item, c.item.dispose(accounting))
                            return
                        }
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
                // receivedFrames counts the acquired access as soon as owner processing
                // begins — even when no frame identity remains (then dropped++ too).
                accounting.receivedFrame()
                val frameIndex = identityOwner.nextIdentity()
                if (frameIndex == null) { accounting.droppedFrame(); guard.releaseSafely(); return }
                when (val creation = createDirectYuvWork(frameIndex, access, accounting)) {
                    is DirectYuvWorkCreation.Accepted -> {
                        val item = creation.item
                        val fileName = "frame_${item.frameIndex.toString().padStart(2, '0')}_color.png"
                        val candidate = File(outputDir, ".${fileName}.${System.nanoTime()}.tmp")
                        val task = object : OutcomeDisposableCaptureTask {
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
                                            val settlement = completion.settleForOwnerRejection(candidateFilesystem)
                                            if (settlement != null) {
                                                val handle = when (completion) {
                                                    is YuvWorkerCompletion.Success -> completion.candidateHandle
                                                    is YuvWorkerCompletion.Failed -> completion.candidateHandle
                                                }
                                                if (handle != null) recordCandidateDebt(settlement, handle.frameIndex, handle.file)
                                            }
                                        }
                                    })
                                } finally {
                                    disposeWithOutcome()
                                }
                            }
                            override fun dispose() { disposeWithOutcome() }
                            override fun disposeWithOutcome(): CaptureTaskDisposalOutcome {
                                val outcome = item.dispose()
                                return if (outcome.isClean) {
                                    CaptureTaskDisposalOutcome.Clean
                                } else {
                                    recordWorkDisposalDebt(item, outcome)
                                    CaptureTaskDisposalOutcome.Unclean(
                                        outcome, disposalDescription(outcome, item.frameIndex)
                                    )
                                }
                            }
                        }
                        if (!boundedWorker.submit(task)) {
                            // Worker already disposed the rejected task (disposeWithOutcome);
                            // the drop is recorded here exactly once.
                            accounting.droppedFrame()
                            postStatus("YUV direct backpressure: frame ${item.frameIndex + 1} dropped")
                        }
                    }
                    is DirectYuvWorkCreation.Failed -> {
                        if (creation.cause is Error) throw creation.cause
                        // Preserve the release failure as observable debt
                        if (creation.releaseFailure != null) {
                            val msg = "direct creation releaseFailure frame=$frameIndex " +
                                "cause=${creation.cause::class.java.simpleName}: ${creation.cause.message} " +
                                "releaseFailure=${creation.releaseFailure::class.java.simpleName}: ${creation.releaseFailure.message}"
                            candidateCleanupDebts.add(msg)
                            Log.e("KeplerYuvOwner", msg)
                        }
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
                        val settlement = completion.settleForOwnerRejection(candidateFilesystem)
                        if (settlement != null) {
                            val handle = when (completion) {
                                is YuvWorkerCompletion.Success -> completion.candidateHandle
                                is YuvWorkerCompletion.Failed -> completion.candidateHandle
                            }
                            if (handle != null) recordCandidateDebt(settlement, handle.frameIndex, handle.file)
                        }
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
                // Non-clean settlement is always observable debt — even when the
                // work-item disposal outcome is clean (e.g. INVALID_STATE, UNKNOWN,
                // ALREADY_SETTLING with failures).  Step 4: always record debt for
                // non-clean settlements.
                val settledCleanly = outcome.status == YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED &&
                    outcome.failure == null && outcome.lifecycleReleaseFailure == null
                if (!settledCleanly) {
                    val detail = listOfNotNull(outcome.failure, outcome.lifecycleReleaseFailure)
                        .joinToString("; ") { "${it::class.java.simpleName}: ${it.message}" }
                    candidateCleanupDebts.add(
                        "bufferedTaskSettlementIssue frame=${issueItem.frameIndex}: ${outcome.status} $detail"
                    )
                }
                val disposal = issueItem.disposalOutcome()
                if (disposal != null && !disposal.isClean) {
                    recordDisposalIfUnclean(issueItem, disposal)
                }
            },
            onWorkDisposalDebt = { workItem, outcome ->
                recordDisposalIfUnclean(workItem, outcome)
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
     * Fail-closed, transactional adoption pipeline (owner event, serialized):
     *
     *   candidate verifier -> exclusive adoption claim (ADOPTING) -> reservation
     *   -> collision policy (preserve pre-existing finals) -> commit -> final verifier
     *   -> token commit (manifest+persistedFrames) -> candidate ADOPTED.
     *
     * Every injected stage is treated as throwable and contained; every failure
     * rolls back the reservation, removes/quarantines ONLY newly created finals
     * ([destinationExistedBeforeAttempt] / [destinationCreatedByAttempt] tracking),
     * settles the candidate through the claim (never leaving it ADOPTING/UNSETTLED),
     * records failedFrame and the exact failure/cleanup debt.  An untracked final PNG
     * is never left behind and a pre-existing final is never deleted or quarantined.
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
        val finalFile = File(outputDir, completion.fileName)
        val destinationExistedBeforeAttempt = finalFile.exists()

        // 1. Fail-closed candidate validation (a THROWING verifier fails closed: the
        //    throwable is contained and reported in the debt ledger).
        var verifierThrowable: Throwable? = null
        val candidateValid = try {
            candidateVerifier.verify(handle.file, completion.frameIndex)
        } catch (t: Throwable) {
            verifierThrowable = t
            Log.e("KeplerYuvOwner", "Candidate verifier threw for frame ${completion.frameIndex}", t)
            false
        }
        if (!candidateValid) {
            val reason = verifierThrowable?.let { ": ${it::class.java.simpleName}: ${it.message}" } ?: ""
            Log.e("KeplerYuvOwner", "Candidate validation failed for frame ${completion.frameIndex}: ${handle.file}$reason")
            if (verifierThrowable != null) {
                candidateCleanupDebts.add(
                    "candidate verifier failed frame=${completion.frameIndex} file=${handle.file} " +
                        "${verifierThrowable::class.java.simpleName}: ${verifierThrowable.message}"
                )
            }
            recordCandidateDebt(handle.discardOrQuarantine(candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            return
        }

        // 2. Exclusive adoption claim: UNSETTLED -> ADOPTING.  The candidate is now
        //    immune to concurrent discard until the claim is completed or aborted.
        val claim = handle.tryBeginAdoption() ?: run {
            Log.e("KeplerYuvOwner", "Candidate adoption claim rejected for frame ${completion.frameIndex}")
            recordCandidateDebt(handle.discardOrQuarantine(candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            return
        }

        // 3. Reserve frame identity + final filename atomically (reservation alone
        //    never touches the manifest or persistedFrames)
        val token = accounting.tryReserveAdoption(entry) ?: run {
            Log.w("KeplerYuvOwner", "Duplicate adoption reservation rejected for frame ${completion.frameIndex}")
            recordCandidateDebt(handle.abortAdoption(claim, candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            return
        }

        // 4. Fail-closed collision policy: preserve ANY pre-existing final file
        //    (never delete it); roll back and settle the candidate through the claim.
        if (destinationExistedBeforeAttempt) {
            Log.w("KeplerYuvOwner", "Unexpected pre-existing final file for frame ${completion.frameIndex}: ${finalFile.path}")
            token.rollback()
            recordRollbackRecovery(entry, token.recoverRollbackAfterFailure())
            recordCandidateDebt(handle.abortAdoption(claim, candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            return
        }

        // 5. Commit candidate -> final (contained).  If the committer throws AFTER
        //    creating the final file (partial side effect), the newly created
        //    untracked final is removed/quarantined; the pre-existing-only rule is
        //    respected because destinationExistedBeforeAttempt is false here.
        val commitFailure = try {
            workProcessor.commit(handle.file, finalFile)
            null
        } catch (t: Throwable) {
            t
        }
        val destinationCreatedByAttempt = finalFile.exists()
        if (commitFailure != null) {
            Log.e("KeplerYuvOwner", "Commit failed for frame ${completion.frameIndex}", commitFailure)
            if (destinationCreatedByAttempt) removeOrQuarantineCreatedFinal(finalFile)
            token.rollback()
            recordRollbackRecovery(entry, token.recoverRollbackAfterFailure())
            recordCandidateDebt(handle.abortAdoption(claim, candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            finishError("YUV commit failed for frame ${completion.frameIndex}", cause = commitFailure)
            return
        }

        // 6. Fail-closed verification of the newly created final file (a THROWING
        //    final verifier is the same as verification failure).  Only the newly
        //    created file may be removed/quarantined.
        val finalVerified = try {
            finalFileVerifier.verify(finalFile, completion.frameIndex)
        } catch (t: Throwable) {
            val description = "final verifier threw frame=${completion.frameIndex} file=${finalFile}: " +
                "${t::class.java.simpleName}: ${t.message}"
            candidateCleanupDebts.add(description)
            Log.e("KeplerYuvOwner", description, t)
            false
        }
        if (!finalVerified) {
            Log.w("KeplerYuvOwner", "Final file verification failed after commit for frame ${completion.frameIndex}")
            token.rollback()
            recordRollbackRecovery(entry, token.recoverRollbackAfterFailure())
            if (destinationCreatedByAttempt) removeOrQuarantineCreatedFinal(finalFile)
            recordCandidateDebt(handle.abortAdoption(claim, candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            finishError("Final file verification failed for frame ${completion.frameIndex}")
            return
        }

        // 7. Commit manifest entry + persistedFrames.  COMMITTED is only visible after
        //    the accounting mutation completed.  If the token commit fails after final
        //    creation, the final is UNTRACKED: it is removed/quarantined, any remaining
        //    reservation is rolled back, and the candidate is settled.
        if (!token.commit()) {
            Log.e("KeplerYuvOwner", "Adoption commit failed for frame ${completion.frameIndex}", token.failure)
            if (destinationCreatedByAttempt) removeOrQuarantineCreatedFinal(finalFile)
            token.rollback()
            recordRollbackRecovery(entry, token.recoverRollbackAfterFailure())
            recordCandidateDebt(handle.abortAdoption(claim, candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            finishError("YUV adoption commit failed for frame ${completion.frameIndex}", cause = token.failure)
            return
        }

        // 8. Settle the candidate ADOPTED through the exclusive claim.  The claim is
        //    reference-identical to the ADOPTING record's active claim, so completion
        //    is structurally infallible for the exact claim; an AdoptionInvariantException
        //    here is impossible internal corruption and is recorded as observable debt.
        //    The final file is already committed so a corruption must also remove it.
        val claimCompleted = try {
            handle.completeAdoption(claim)
        } catch (t: AdoptionInvariantException) {
            val description = "candidate adoption completion invariant-failed frame=${completion.frameIndex} " +
                "file=${handle.file}: ${t.message}"
            candidateCleanupDebts.add(description)
            Log.e("KeplerYuvOwner", description, t)
            if (destinationCreatedByAttempt) removeOrQuarantineCreatedFinal(finalFile)
            accounting.failedFrame()
            return
        }
        if (!claimCompleted) {
            val description = "candidate adoption completion rejected frame=${completion.frameIndex} " +
                "file=${handle.file} state=${handle.state()}"
            candidateCleanupDebts.add(description)
            Log.e("KeplerYuvOwner", description)
            if (destinationCreatedByAttempt) removeOrQuarantineCreatedFinal(finalFile)
            accounting.failedFrame()
            return
        }
        val persistedFrames = accounting.snapshot().persistedFrames
        postStatus("YUV capture: saved $persistedFrames/$frameCount")
        writeJobJson("CAPTURING", persistedFrames, accounting.snapshot().manifest)
    }

    /**
     * Removes/quarantines ONLY the newly created invalid final file (never a
     * pre-existing one).  Both operations are contained independently: a throwing
     * filesystem implementation is converted into an explicit result and a failed
     * removal records the cleanup debt explicitly.
     */
    private fun removeOrQuarantineCreatedFinal(file: File) {
        val deleteResult = try {
            candidateFilesystem.delete(file)
        } catch (t: Throwable) {
            CandidateFileOperationResult.DELETE_THREW(t)
        }
        if (deleteResult != CandidateFileOperationResult.DELETED &&
            deleteResult != CandidateFileOperationResult.FILE_ABSENT) {
            val quarantineResult = try {
                candidateFilesystem.quarantine(file)
            } catch (t: Throwable) {
                CandidateFileOperationResult.QUARANTINE_FAILED(t)
            }
            if (quarantineResult !is CandidateFileOperationResult.QUARANTINE_FAILED) return
            val deleteFailure = deleteResult.failure
            val quarantineFailure = quarantineResult.failure
            val description = "final-file cleanup debt file=$file" +
                " delete=${deleteResult.describe()}" +
                " quarantine=${quarantineResult.describe()}" +
                (if (deleteFailure == null) "" else " deleteThrowable=" + deleteFailure::class.java.simpleName + ": " + deleteFailure.message) +
                (if (quarantineFailure == null) "" else " quarantineThrowable=" + quarantineFailure::class.java.simpleName + ": " + quarantineFailure.message)
            candidateCleanupDebts.add(description)
            Log.e("KeplerYuvOwner", description)
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
