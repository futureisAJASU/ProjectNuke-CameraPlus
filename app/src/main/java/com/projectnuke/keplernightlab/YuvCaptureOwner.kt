package com.projectnuke.keplernightlab

import android.util.Log
import java.io.File
import java.lang.Runnable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private inline fun ignoreErrors(label: String, block: () -> Unit) {
    try { block() } catch (_: Exception) { }
}

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
 *  Terminal settlement is transactional: a single [settleTerminalByRequest] path handles
 *  every terminal outcome.  The settlement phase machine is ACTIVE -> CLAIMED -> SETTLING ->
 *  SETTLED.  [finished] becomes true at CLAIMED (no new capture work may be accepted);
 *  the published [YuvTerminalSnapshot] separately reports whether metadata/callback/cleanup
 *  settlement has completed (SETTLED) or is still in flight.
 *
 *  Terminal publication: the session-owned [YuvTerminalRequestHandoff] is the SOLE
 *  publication.  Exactly one [YuvTerminalRequest] is published via
 *  [YuvSessionTerminalOperations.publishTerminal]; ColorFusion observes it (gate +
 *  session terminal observer) and never infers the terminal result from counters or
 *  elapsed time.  The owner never invokes the production metadata writer or verifier
 *  directly — it requests session terminal operations.
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
    private val postStatus: (String) -> Boolean,
    private val dispatchCallback: CallbackDispatcher,
    private val sessionTerminal: YuvSessionTerminalOperations,
    private val saveMotionOnce: (File) -> Pair<String?, String?>,
    private val cleanupCoordinator: YuvCleanupCoordinator,
    private val productionResourceCoordinator: YuvProductionResourceCoordinator,
    private val candidateFilesystem: YuvCandidateFilesystem = RealYuvCandidateFilesystem,
    private val candidateVerifier: YuvCandidateVerifier = RealYuvCandidateVerifier
) {

    private var completedResults = 0
    private val discardedLateCompletions = mutableListOf<Int>()

    private val terminalSettlementPhaseRef = AtomicReference(TerminalSettlementPhase.ACTIVE)
    private val callbackStateRef = AtomicReference(CallbackState.NOT_REQUESTED)
    private val terminalReasonRef = AtomicReference<String?>(null)
    private val diagnostics = java.util.concurrent.CopyOnWriteArrayList<YuvCaptureDiagnostic>()

    // Terminal operation outcomes — never hardcoded null after the operation has run.
    private val metadataWriteOutcomeRef = AtomicReference<TerminalOperationOutcome>(TerminalOperationOutcome.NotRequested)
    private val motionSaveOutcomeRef = AtomicReference<TerminalOperationOutcome>(TerminalOperationOutcome.NotRequested)
    private val statusDispatchOutcomeRef = AtomicReference<TerminalOperationOutcome>(TerminalOperationOutcome.NotRequested)
    private val callbackDispatchOutcomeRef = AtomicReference<TerminalOperationOutcome>(TerminalOperationOutcome.NotRequested)
    private val callbackExecutionOutcomeRef = AtomicReference<TerminalOperationOutcome>(TerminalOperationOutcome.NotRequested)

    /**
     * Only the serialized owner builds this publication from its mutable counters and
     * collections.  Callback/terminal-observer threads only combine this immutable
     * value with their atomic operation outcomes; they never read owner collections.
     */
    private val ownerPublishedStateRef = AtomicReference(buildOwnerPublishedState())
    private val terminalSnapshotRef = AtomicReference(buildSnapshot(ownerPublishedStateRef.get()))
    private val terminalObservationClaimed = AtomicBoolean(false)

    internal fun candidateCleanupDebt(): List<String> =
        diagnostics.map { "${it.stage}: ${it.message}" }

    private fun recordDiagnostic(
        stage: DiagnosticStage,
        severity: DiagnosticSeverity,
        frameIndex: Int? = null,
        path: String? = null,
        message: String,
        throwable: Throwable? = null
    ) {
        diagnostics.add(YuvCaptureDiagnostic(stage, severity, frameIndex, path, message, throwable))
        when (severity) {
            DiagnosticSeverity.ERROR -> Log.e("KeplerYuvOwner", message, throwable)
            DiagnosticSeverity.WARN -> Log.w("KeplerYuvOwner", message, throwable)
            DiagnosticSeverity.INFO -> Unit
        }
    }

    private fun recordCandidateDebt(settlement: CandidateDisposalOutcome, frameIndex: Int, file: File) {
        val description = settlement.failureDescription(frameIndex, file)
        if (description != null) {
            recordDiagnostic(DiagnosticStage.CANDIDATE_CLEANUP, DiagnosticSeverity.ERROR, frameIndex, file.path, description)
        }
    }

    private fun recordRollbackRecovery(
        entry: YuvFrameManifestEntry,
        result: AdoptionToken.AdoptionRecoveryResult
    ) {
        if (result.recoveryFailure == null && result.released && !result.asymmetric) return
        val message = "adoption rollback recovery frame=${entry.frameIndex} file=${entry.filename}" +
            " eligible=${result.eligible}" +
            " rollbackAttempted=${result.rollbackAttempted}" +
            " rollbackReturnedSuccess=${result.rollbackReturnedSuccess}" +
            " released=${result.released} remainingIndex=${result.indexReservationRemaining}" +
            " remainingFilename=${result.filenameReservationRemaining} asymmetric=${result.asymmetric}"
        recordDiagnostic(DiagnosticStage.ADOPTION_ROLLBACK, DiagnosticSeverity.ERROR, entry.frameIndex, entry.filename, message, result.recoveryFailure)
    }

    private fun rollbackAdoptionAndRecord(entry: YuvFrameManifestEntry, token: AdoptionToken) {
        val preRollbackState = token.state()
        val rollbackSuccess = token.rollback()
        if (rollbackSuccess) {
            return
        }
        if (preRollbackState == AdoptionTokenState.RESERVED) {
            token.failure?.let { failure ->
                recordDiagnostic(DiagnosticStage.ADOPTION_ROLLBACK, DiagnosticSeverity.ERROR, entry.frameIndex, entry.filename,
                    "adoption rollback failed frame=${entry.frameIndex} file=${entry.filename} " +
                        "cause=${failure::class.java.simpleName}: ${failure.message}", failure)
            }
        }
        val recovery = token.recoverRollbackAfterFailure()
        recordRollbackRecovery(entry, recovery)
    }

    /**
     * Records every unclean work-item disposal as observable cleanup debt.
     * A non-clean outcome with an empty throwable list (e.g. missing requirements,
     * in-progress, or buffered account not provided) is still debt — never silently
     * passed.  Never throws.
     */
    private fun recordWorkDisposalDebt(item: YuvPngWorkItem, outcome: YuvWorkDisposalOutcome) {
        if (outcome.isClean) return
        recordDiagnostic(DiagnosticStage.WORK_DISPOSAL, DiagnosticSeverity.ERROR, item.frameIndex,
            null, disposalDescription(outcome, item.frameIndex))
    }

    private fun recordDisposalIfUnclean(item: YuvPngWorkItem, outcome: YuvWorkDisposalOutcome) {
        recordWorkDisposalDebt(item, outcome)
    }

    // ------------------------------------------------------------------
    // Typed Camera2 callback entry points (no unsafe casts)
    // ------------------------------------------------------------------

    fun acceptBuffered(access: YuvImageAccess) {
        val event = object : CaptureOwnerEvent {
            val guard = YuvImageReleaseGuard(access) { outcome ->
                outcome.failure?.let { failure ->
                    recordDiagnostic(DiagnosticStage.WORK_DISPOSAL, DiagnosticSeverity.ERROR, null, null,
                        "buffered YUV source release failed", failure)
                }
            }
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
                        ignoreErrors("buffered status dispatch") { postStatus("YUV buffered frame ${accounting.snapshot().bufferedFrames}/$frameCount") }
                        scheduleBufferedEncoding()
                    }
                    BufferedYuvWorkCreation.Rejected ->
                        ignoreErrors("dropped status dispatch") { postStatus("YUV memory buffer dropped frame ${frameIndex + 1}/$frameCount: retained=${reservations.currentBytes()} bytes") }
                    is BufferedYuvWorkCreation.Failed -> {
                        if (c.cause is Error) throw c.cause
                        finishError("YUV memory buffer copy failed", cause = c.cause)
                    }
                }
            }
            override fun disposeWithoutMutation() {
                guard.releaseSafely()
            }
        }
        // post() either accepted (event settled by owner) or rejected (event
        // already disposed via disposeWithoutMutation).  NEVER release access
        // after a rejected post — the envelope already disposed it.
        captureStateOwner.post(event)
    }

    fun acceptDirect(access: DirectYuvImageAccess) {
        val event = object : CaptureOwnerEvent {
            val guard = YuvImageReleaseGuard(access) { outcome ->
                outcome.failure?.let { failure ->
                    recordDiagnostic(DiagnosticStage.WORK_DISPOSAL, DiagnosticSeverity.ERROR, null, null,
                        "direct YUV source release failed", failure)
                }
            }
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
                            ignoreErrors("direct backpressure status dispatch") { postStatus("YUV direct backpressure: frame ${item.frameIndex + 1} dropped") }
                        }
                    }
                    is DirectYuvWorkCreation.Failed -> {
                        if (creation.cause is Error) throw creation.cause
                        // Preserve the release failure as observable debt
                        if (creation.releaseFailure != null) {
                            recordDiagnostic(DiagnosticStage.DIRECT_CREATION_RELEASE, DiagnosticSeverity.ERROR, frameIndex,
                                null, "direct creation releaseFailure frame=$frameIndex " +
                                    "cause=${creation.cause::class.java.simpleName}: ${creation.cause.message} " +
                                    "releaseFailure=${creation.releaseFailure::class.java.simpleName}: ${creation.releaseFailure.message}",
                                creation.releaseFailure)
                        }
                        finishError("YUV direct creation failed", cause = creation.cause)
                    }
                }
            }
            override fun disposeWithoutMutation() {
                guard.releaseSafely()
            }
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
            override fun execute() {
                // Camera2 capture failure must increment the authoritative failed-capture
                // accounting exactly once before terminal settlement.
                if (terminalState.status() == CaptureTerminalStatus.ACTIVE) {
                    accounting.failedFrame()
                }
                finishError("$detail: ${cause.message ?: cause.javaClass.simpleName}", cause = cause)
            }
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
                    recordDiagnostic(DiagnosticStage.LIFECYCLE_SETTLEMENT, DiagnosticSeverity.WARN, issueItem.frameIndex,
                        null, "bufferedTaskSettlementIssue frame=${issueItem.frameIndex}: ${outcome.status} $detail",
                        outcome.failure ?: outcome.lifecycleReleaseFailure)
                }
                // Item 7: DO NOT re-record disposal debt here — that is
                // onWorkDisposalDebt's exclusive responsibility.
            },
            onWorkDisposalDebt = { workItem, outcome ->
                recordDisposalIfUnclean(workItem, outcome)
            }
        )
        if (!boundedWorker.submit(task)) {
            // Worker already called task.dispose() which calls lifecycle.settleEncoding.
            // Do NOT double-settle or double-dispose the item.
            accounting.droppedFrame()
            ignoreErrors("buffered backpressure status dispatch") { postStatus("YUV backpressure: buffered frame ${frame.frameIndex + 1} dropped") }
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
                recordDiagnostic(DiagnosticStage.CANDIDATE_VERIFY, DiagnosticSeverity.ERROR, completion.frameIndex, handle.file.path,
                    "candidate verifier failed frame=${completion.frameIndex} file=${handle.file} " +
                        "${verifierThrowable::class.java.simpleName}: ${verifierThrowable.message}", verifierThrowable)
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
            rollbackAdoptionAndRecord(entry, token)
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
            rollbackAdoptionAndRecord(entry, token)
            recordCandidateDebt(handle.abortAdoption(claim, candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            finishError("YUV commit failed for frame ${completion.frameIndex}", cause = commitFailure)
            return
        }

        // 6. Fail-closed verification of the newly created final file (a THROWING
        //    final verifier is the same as verification failure).  Only the newly
        //    created file may be removed/quarantined.  Verification is a session
        //    terminal operation; the owner requests it through the session gateway.
        val finalVerified = try {
            sessionTerminal.verifyTerminalFinalFile(finalFile, completion.frameIndex)
        } catch (t: Throwable) {
            recordDiagnostic(DiagnosticStage.FINAL_VERIFY, DiagnosticSeverity.ERROR, completion.frameIndex, finalFile.path,
                "final verifier threw frame=${completion.frameIndex} file=${finalFile}: " +
                    "${t::class.java.simpleName}: ${t.message}", t)
            false
        }
        if (!finalVerified) {
            Log.w("KeplerYuvOwner", "Final file verification failed after commit for frame ${completion.frameIndex}")
            rollbackAdoptionAndRecord(entry, token)
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
            rollbackAdoptionAndRecord(entry, token)
            recordCandidateDebt(handle.abortAdoption(claim, candidateFilesystem), handle.frameIndex, handle.file)
            accounting.failedFrame()
            finishError("YUV adoption commit failed for frame ${completion.frameIndex}", cause = token.failure)
            return
        }

        // 8. Settle the candidate ADOPTED through the exclusive claim.  The claim is
        //    reference-identical to the ADOPTING record's active claim, so completion
        //    is structurally infallible for the exact claim.  An invariant failure or
        //    lost race here is impossible corruption — but the token is already COMMITTED,
        //    so the manifest entry and final file must NOT be deleted (Item 1).  The
        //    candidate-state anomaly is recorded as observable debt.
        val claimCompleted = try {
            handle.completeAdoption(claim)
        } catch (t: AdoptionInvariantException) {
            recordDiagnostic(DiagnosticStage.ADOPTION_COMMIT, DiagnosticSeverity.ERROR, completion.frameIndex, handle.file.path,
                "candidate adoption completion invariant-failed frame=${completion.frameIndex} " +
                    "file=${handle.file}: ${t.message}", t)
            return
        }
        if (claimCompleted != AdoptionResult.COMPLETED) {
            recordDiagnostic(DiagnosticStage.ADOPTION_COMMIT, DiagnosticSeverity.ERROR, completion.frameIndex, handle.file.path,
                "candidate adoption completion $claimCompleted frame=${completion.frameIndex} " +
                    "file=${handle.file} state=${handle.state()}")
            return
        }
        val persistedFrames = accounting.snapshot().persistedFrames
        ignoreErrors("capturing status dispatch") { postStatus("YUV capture: saved $persistedFrames/$frameCount") }
        ignoreErrors("capturing metadata write") {
            sessionTerminal.requestTerminalMetadataWrite(
                YuvTerminalMetadataRequest("CAPTURING", persistedFrames, accounting.snapshot().manifest)
            )
        }
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
            recordDiagnostic(DiagnosticStage.FINAL_CLEANUP, DiagnosticSeverity.ERROR, null, file.path,
                "final-file cleanup debt file=$file" +
                    " delete=${deleteResult.describe()}" +
                    " quarantine=${quarantineResult.describe()}" +
                    (if (deleteFailure == null) "" else " deleteThrowable=" + deleteFailure::class.java.simpleName + ": " + deleteFailure.message) +
                    (if (quarantineFailure == null) "" else " quarantineThrowable=" + quarantineFailure::class.java.simpleName + ": " + quarantineFailure.message),
                quarantineFailure)
        }
    }

    // ------------------------------------------------------------------
    // Terminal state and deadline settlement
    // ------------------------------------------------------------------

    private fun checkTerminal() {
        val snap = accounting.snapshot()
        if (snap.persistedFrames >= frameCount && terminalState.claim(CaptureTerminalStatus.SUCCESS)) {
            settleTerminalByRequest(YuvTerminalRequest(
                status = CaptureTerminalStatus.SUCCESS,
                jobStatus = "CAPTURE_COMPLETE",
                reason = "All $frameCount frames persisted",
                completionKind = TerminalCompletionKind.SUCCESS,
                cause = null,
                saveMotion = true
            ))
        }
    }

    fun onDeadlineReached() {
        val event = object : CaptureOwnerEvent {
            override fun execute() {
                val snap = accounting.snapshot()
                when {
                    snap.persistedFrames >= frameCount -> {
                        if (terminalState.claim(CaptureTerminalStatus.SUCCESS)) {
                            settleTerminalByRequest(YuvTerminalRequest(
                                status = CaptureTerminalStatus.SUCCESS,
                                jobStatus = "CAPTURE_COMPLETE",
                                reason = "All $frameCount frames persisted",
                                completionKind = TerminalCompletionKind.SUCCESS,
                                cause = null,
                                saveMotion = true
                            ))
                        }
                    }
                    snap.persistedFrames > 0 -> {
                        if (terminalState.claim(CaptureTerminalStatus.PARTIAL_SUCCESS)) {
                            settleTerminalByRequest(YuvTerminalRequest(
                                status = CaptureTerminalStatus.PARTIAL_SUCCESS,
                                jobStatus = "CAPTURE_PARTIAL",
                                reason = "Partial success: ${snap.persistedFrames}/$frameCount persisted",
                                completionKind = TerminalCompletionKind.SUCCESS,
                                cause = null,
                                saveMotion = true
                            ))
                        }
                    }
                    else -> {
                        if (terminalState.claim(CaptureTerminalStatus.TIMED_OUT)) {
                            settleTerminalByRequest(YuvTerminalRequest(
                                status = CaptureTerminalStatus.TIMED_OUT,
                                jobStatus = "CAPTURE_TIMEOUT",
                                reason = "YUV timeout: saved=${snap.persistedFrames}/$frameCount",
                                completionKind = TerminalCompletionKind.ERROR,
                                cause = null,
                                saveMotion = false
                            ))
                        }
                    }
                }
            }
            override fun disposeWithoutMutation() {}
        }
        if (!captureStateOwner.post(event)) {
            emergencySettleDeadline()
        }
    }

    fun onCancellationRequested() {
        val event = object : CaptureOwnerEvent {
            override fun execute() {
                if (terminalState.claim(CaptureTerminalStatus.CANCELLED)) {
                    settleTerminalByRequest(YuvTerminalRequest(
                        status = CaptureTerminalStatus.CANCELLED,
                        jobStatus = "CAPTURE_CANCELLED",
                        reason = "Cancelled",
                        completionKind = TerminalCompletionKind.ERROR,
                        cause = null,
                        saveMotion = false
                    ))
                }
            }
            override fun disposeWithoutMutation() {}
        }
        if (!captureStateOwner.post(event)) {
            emergencySettleCancellation()
        }
    }

    private fun finishError(
        message: String,
        cause: Throwable? = null
    ) {
        if (!terminalState.claim(CaptureTerminalStatus.FAILED)) return
        settleTerminalByRequest(YuvTerminalRequest(
            status = CaptureTerminalStatus.FAILED,
            jobStatus = "CAPTURE_FAILED",
            reason = message,
            completionKind = TerminalCompletionKind.ERROR,
            cause = cause,
            saveMotion = false
        ))
    }

    // ------------------------------------------------------------------
    // Emergency settlement for rejected owner events (runs off-dispatcher) --
    // every emergency path still goes through the SAME terminal transaction
    // (settleTerminalByRequest) so the terminal request is published and the
    // ColorFusion terminal gate always unparks.
    // ------------------------------------------------------------------

    private fun emergencySettleDeadline() {
        val snap = accounting.snapshot()
        val status: CaptureTerminalStatus
        val completionKind: TerminalCompletionKind
        val reason: String
        val saveMotion: Boolean
        when {
            snap.persistedFrames >= frameCount -> {
                status = CaptureTerminalStatus.SUCCESS
                completionKind = TerminalCompletionKind.SUCCESS
                reason = "All $frameCount frames persisted"
                saveMotion = true
            }
            snap.persistedFrames > 0 -> {
                status = CaptureTerminalStatus.PARTIAL_SUCCESS
                completionKind = TerminalCompletionKind.SUCCESS
                reason = "Partial success: ${snap.persistedFrames}/$frameCount persisted"
                saveMotion = true
            }
            else -> {
                status = CaptureTerminalStatus.TIMED_OUT
                completionKind = TerminalCompletionKind.ERROR
                reason = "YUV timeout: saved=${snap.persistedFrames}/$frameCount"
                saveMotion = false
            }
        }
        if (terminalState.claim(status)) {
            recordDiagnostic(DiagnosticStage.OWNER_EVENT_REJECTION, DiagnosticSeverity.WARN, null, null,
                "deadline event dispatch rejected; emergency settlement path taken")
            settleTerminalByRequest(
                YuvTerminalRequest(
                    status = status,
                    jobStatus = when (status) {
                        CaptureTerminalStatus.SUCCESS -> "CAPTURE_COMPLETE"
                        CaptureTerminalStatus.PARTIAL_SUCCESS -> "CAPTURE_PARTIAL"
                        else -> "CAPTURE_TIMEOUT"
                    },
                    reason = reason,
                    completionKind = completionKind,
                    cause = null,
                    saveMotion = saveMotion
                ),
                emergency = true
            )
        }
    }

    private fun emergencySettleCancellation() {
        if (terminalState.claim(CaptureTerminalStatus.CANCELLED)) {
            recordDiagnostic(DiagnosticStage.OWNER_EVENT_REJECTION, DiagnosticSeverity.WARN, null, null,
                "cancellation event dispatch rejected; emergency settlement path taken")
            settleTerminalByRequest(
                YuvTerminalRequest(
                    status = CaptureTerminalStatus.CANCELLED,
                    jobStatus = "CAPTURE_CANCELLED",
                    reason = "Cancelled",
                    completionKind = TerminalCompletionKind.ERROR,
                    cause = null,
                    saveMotion = false
                ),
                emergency = true
            )
        }
    }

    // ------------------------------------------------------------------
    // One terminal settlement path
    // ------------------------------------------------------------------

    /**
     * The single terminal transaction.  Exactly one [YuvTerminalRequest] is
     * published through the session-owned handoff (sole publication) before the
     * terminal observer is dispatched.  [emergency] selects the owner-event
     * rejection path: the four terminal operations (motion/metadata/status/
     * callback-dispatch outcomes) are NotRequested, but publication and cleanup
     * still follow the same transaction so the ColorFusion gate always unparks.
     */
    private fun settleTerminalByRequest(request: YuvTerminalRequest, emergency: Boolean = false) {
        try {
            settleTerminalByRequestInternal(request, emergency)
        } catch (failure: Throwable) {
            recordDiagnostic(
                DiagnosticStage.TERMINAL_PUBLICATION,
                DiagnosticSeverity.ERROR,
                null,
                null,
                "unexpected YUV terminal transaction failure",
                failure
            )
            try { cleanupCoordinator.perform() } catch (cleanupFailure: Throwable) {
                recordDiagnostic(DiagnosticStage.CLEANUP, DiagnosticSeverity.ERROR, null, null,
                    "internal cleanup after terminal transaction failure failed", cleanupFailure)
            }
            try { productionResourceCoordinator.perform() } catch (cleanupFailure: Throwable) {
                recordDiagnostic(DiagnosticStage.CLEANUP, DiagnosticSeverity.ERROR, null, null,
                    "production cleanup after terminal transaction failure failed", cleanupFailure)
            }
            sessionTerminal.publishSettlementFailure(failure)
        }
    }

    private fun settleTerminalByRequestInternal(request: YuvTerminalRequest, emergency: Boolean = false) {
        if (terminalSettlementPhaseRef.get() != TerminalSettlementPhase.ACTIVE) return
        finished.set(true)
        terminalReasonRef.set(request.reason)
        terminalSettlementPhaseRef.set(TerminalSettlementPhase.CLAIMED)
        publishOwnerSnapshot(emergency, request)

        terminalSettlementPhaseRef.set(TerminalSettlementPhase.SETTLING)

        run {
            // These operations are deliberately expressed through session-owned,
            // thread-safe seams.  Owner-event rejection may force this transaction
            // off the capture handler, but it must still attempt the public terminal
            // contract rather than falsely reporting NotRequested.
            val motionOutcome: TerminalOperationOutcome = if (request.saveMotion) {
                runCatching { saveMotionOnce(outputDir) }
                    .fold(
                        onSuccess = { TerminalOperationOutcome.Succeeded },
                        onFailure = { t ->
                            recordDiagnostic(DiagnosticStage.TERMINAL_MOTION, DiagnosticSeverity.ERROR, null, outputDir.path,
                                "terminal motion save failed", t)
                            TerminalOperationOutcome.Failed(t)
                        }
                    )
            } else {
                TerminalOperationOutcome.NotRequested
            }
            motionSaveOutcomeRef.set(motionOutcome)

            val snap = accounting.snapshot()
            val metadataOutcome = runCatching {
                sessionTerminal.requestTerminalMetadataWrite(
                    YuvTerminalMetadataRequest(request.jobStatus, snap.persistedFrames, snap.manifest)
                )
            }
                .fold(
                    onSuccess = { TerminalOperationOutcome.Succeeded },
                    onFailure = { t ->
                        recordDiagnostic(DiagnosticStage.TERMINAL_METADATA, DiagnosticSeverity.ERROR, null, null,
                            "terminal metadata write failed", t)
                        TerminalOperationOutcome.Failed(t)
                    }
                )
            metadataWriteOutcomeRef.set(metadataOutcome)

            val statusMessage = when (request.status) {
                CaptureTerminalStatus.SUCCESS -> "CAPTURE_COMPLETE: 캡처가 완료되었습니다."
                CaptureTerminalStatus.PARTIAL_SUCCESS -> "일부 프레임만 캡처되었습니다."
                else -> request.reason ?: request.jobStatus
            }
            // Acceptance-reporting terminal status dispatch: a false post result is a
            // REJECTED terminal dispatch (diagnostic retained), never an inline fallback.
            val statusOutcome = try {
                if (postStatus(statusMessage)) {
                    TerminalOperationOutcome.Succeeded
                } else {
                    recordDiagnostic(DiagnosticStage.TERMINAL_STATUS_DISPATCH, DiagnosticSeverity.ERROR, null, null,
                        "terminal status dispatch rejected by handler")
                    TerminalOperationOutcome.Failed(IllegalStateException("terminal status dispatch rejected"))
                }
            } catch (t: Throwable) {
                recordDiagnostic(DiagnosticStage.TERMINAL_STATUS_DISPATCH, DiagnosticSeverity.ERROR, null, null,
                    "terminal status dispatch failed", t)
                TerminalOperationOutcome.Failed(t)
            }
            statusDispatchOutcomeRef.set(statusOutcome)
        }

        try {
            // The terminal observer consumes the typed handoff result.  In particular,
            // an unpublished local request never reaches a normal user callback.
            // ColorFusion starts that observer after finite captureBurst submission.
        } finally {
            // Production cleanup: both internal (YuvCleanupCoordinator) AND
            // production (Camera2/HandlerThread/scheduler) resource settlement
            // must run exactly once, regardless of callback dispatch outcome.
            try {
                cleanupCoordinator.perform()
            } catch (t: Throwable) {
                recordDiagnostic(DiagnosticStage.CLEANUP, DiagnosticSeverity.ERROR, null, null,
                    "internal terminal cleanup failed", t)
            }
            try {
                productionResourceCoordinator.perform()
            } catch (t: Throwable) {
                recordDiagnostic(DiagnosticStage.CLEANUP, DiagnosticSeverity.ERROR, null, null,
                    "production terminal cleanup failed", t)
            }
        }

        terminalSettlementPhaseRef.set(TerminalSettlementPhase.SETTLED)
        publishOwnerSnapshot(emergency, request)

        // Publication is the release barrier: every required settlement attempt
        // and the final immutable snapshot are visible before the sole consumer
        // can choose the user callback.
        val published = try {
            sessionTerminal.publishTerminal(request)
        } catch (t: Throwable) {
            recordDiagnostic(DiagnosticStage.TERMINAL_PUBLICATION, DiagnosticSeverity.ERROR, null, null,
                "terminal request publication threw; publishing settlement failure", t)
            sessionTerminal.publishSettlementFailure(t)
            false
        }
        if (!published) {
            recordDiagnostic(DiagnosticStage.TERMINAL_PUBLICATION, DiagnosticSeverity.WARN, null, null,
                "terminal request publication rejected (handoff closed or already published)")
        }
    }

    /** Called by the terminal-handoff consumer, never by local terminal settlement. */
    fun consumeTerminalHandoff(result: YuvTerminalHandoffResult) {
        if (!terminalObservationClaimed.compareAndSet(false, true)) return
        when (result) {
            is YuvTerminalHandoffResult.Published -> dispatchTerminalCallback {
                sessionTerminal.observeTerminal(result.request)
            }
            is YuvTerminalHandoffResult.SettlementFailed -> {
                recordDiagnostic(DiagnosticStage.TERMINAL_PUBLICATION, DiagnosticSeverity.ERROR, null, null,
                    "YUV terminal handoff reported settlement failure", result.failure)
                dispatchTerminalCallback { sessionTerminal.observeSettlementFailure(result.failure) }
            }
            YuvTerminalHandoffResult.Closed -> {
                // Explicit lifecycle closure is not a locally invented capture result.
                recordDiagnostic(DiagnosticStage.TERMINAL_PUBLICATION, DiagnosticSeverity.WARN, null, null,
                    "YUV terminal handoff closed before publication; no normal callback dispatched")
            }
            YuvTerminalHandoffResult.WatchdogTimeout -> {
                recordDiagnostic(DiagnosticStage.TERMINAL_PUBLICATION, DiagnosticSeverity.ERROR, null, null,
                    "YUV terminal handoff watchdog expired; no capture result synthesized")
            }
        }
    }

    internal fun recordTerminalWatchdogTimeout() {
        recordDiagnostic(
            DiagnosticStage.TERMINAL_PUBLICATION,
            DiagnosticSeverity.ERROR,
            null,
            null,
            "YUV terminal handoff watchdog expired; unbounded consumer remains active"
        )
    }

    internal fun recordTerminalSettlementFailure(failure: Throwable) {
        recordDiagnostic(
            DiagnosticStage.TERMINAL_PUBLICATION,
            DiagnosticSeverity.ERROR,
            null,
            null,
            "YUV terminal transaction failed before publication",
            failure
        )
    }

    private fun dispatchTerminalCallback(callback: () -> Unit) {
        // The dispatched observer is the SESSION terminal observer: ColorFusion
        // consumes the published request and dispatches exactly one onComplete/
        // onError from it.  The owner never invokes the production callbacks
        // directly.
        // The wrapped runnable is the ACTUAL user callback invocation.  CallbackState
        // tracks this runnable's lifecycle, not an intermediate wrapper.  The dispatch
        // returns the real Handler.post acceptance result: if Main rejects, the callback
        // is NOT executed inline — a diagnostic is recorded and cleanup still runs.
        val wrapped = Runnable {
            try {
                // A dispatcher is allowed to run synchronously before dispatch()
                // returns.  Claim either PENDING (sync) or ACCEPTED (async) without
                // ever regressing an already-executed callback back to accepted.
                callbackStateRef.compareAndSet(CallbackState.DISPATCH_PENDING, CallbackState.EXECUTING)
                callbackStateRef.compareAndSet(CallbackState.DISPATCH_ACCEPTED, CallbackState.EXECUTING)
                callbackDispatchOutcomeRef.compareAndSet(
                    TerminalOperationOutcome.Pending,
                    TerminalOperationOutcome.Succeeded
                )
                callback.invoke()
                callbackStateRef.set(CallbackState.EXECUTED)
                callbackExecutionOutcomeRef.set(TerminalOperationOutcome.Succeeded)
            } catch (t: Throwable) {
                callbackStateRef.set(CallbackState.EXECUTION_FAILED)
                callbackExecutionOutcomeRef.set(TerminalOperationOutcome.Failed(t))
                recordDiagnostic(DiagnosticStage.TERMINAL_CALLBACK_EXECUTION, DiagnosticSeverity.ERROR, null, null,
                    "terminal callback execution failed", t)
            } finally {
                publishSnapshot()
            }
        }
        callbackStateRef.set(CallbackState.DISPATCH_PENDING)
        callbackDispatchOutcomeRef.set(TerminalOperationOutcome.Pending)
        publishSnapshot()
        val dispatched = try {
            dispatchCallback.dispatch(wrapped)
        } catch (t: Throwable) {
            callbackStateRef.set(CallbackState.DISPATCH_REJECTED)
            callbackDispatchOutcomeRef.set(TerminalOperationOutcome.Failed(t))
            recordDiagnostic(DiagnosticStage.TERMINAL_CALLBACK_DISPATCH, DiagnosticSeverity.ERROR, null, null,
                "terminal callback dispatch threw", t)
            publishSnapshot()
            return
        }
        // If synchronous dispatch already executed the callback, the state is now
        // EXECUTED or EXECUTION_FAILED — do NOT regress to DISPATCH_ACCEPTED.
        val stateAfterDispatch = callbackStateRef.get()
        if (stateAfterDispatch == CallbackState.DISPATCH_PENDING) {
            if (!dispatched) {
                callbackStateRef.set(CallbackState.DISPATCH_REJECTED)
                callbackDispatchOutcomeRef.set(TerminalOperationOutcome.Failed(IllegalStateException("dispatch returned false")))
                recordDiagnostic(DiagnosticStage.TERMINAL_CALLBACK_DISPATCH, DiagnosticSeverity.ERROR, null, null,
                    "terminal callback dispatcher returned false")
                publishSnapshot()
                return
            }
            callbackStateRef.set(CallbackState.DISPATCH_ACCEPTED)
            callbackDispatchOutcomeRef.set(TerminalOperationOutcome.Succeeded)
            publishSnapshot()
        }
    }

    // ------------------------------------------------------------------
    // Immutable terminal snapshot publication
    // ------------------------------------------------------------------

    fun completedResultsCount(): Int = ownerPublishedStateRef.get().completedResults

    fun terminalState(): CaptureTerminalState = terminalState

    fun terminalSettlementPhase(): TerminalSettlementPhase = terminalSettlementPhaseRef.get()

    fun callbackState(): CallbackState = callbackStateRef.get()

    fun terminalSnapshotRef(): YuvTerminalSnapshot = terminalSnapshotRef.get()

    /** Safe from callback/terminal-observer threads: reads immutable/atomic values only. */
    private fun publishSnapshot() {
        terminalSnapshotRef.set(buildSnapshot(ownerPublishedStateRef.get()))
    }

    /** Must be called only by the serialized owner, except the explicit emergency copy path. */
    private fun buildOwnerPublishedState(): OwnerPublishedState {
        val snap = accounting.snapshot()
        return OwnerPublishedState(
            accounting = snap,
            completedResults = completedResults,
            queuedWork = boundedWorker.queuedCount(),
            inFlightWork = boundedWorker.activeCount(),
            terminalStatus = terminalState.status(),
            terminalSettlementPhase = terminalSettlementPhaseRef.get(),
            terminalReason = terminalReasonRef.get(),
            discardedLateCompletions = discardedLateCompletions.toList(),
            cleanupPhase = cleanupCoordinator.snapshot().phase,
            productionCleanup = productionResourceCoordinator.snapshot()
        )
    }

    private fun publishOwnerSnapshot(emergency: Boolean, request: YuvTerminalRequest) {
        if (emergency) {
            // Emergency settlement is allowed to use only the last immutable owner
            // publication.  It must not inspect owner-confined collections off-owner.
            ownerPublishedStateRef.set(ownerPublishedStateRef.get().copy(
                terminalStatus = request.status,
                terminalSettlementPhase = terminalSettlementPhaseRef.get(),
                terminalReason = request.reason,
                cleanupPhase = cleanupCoordinator.snapshot().phase,
                productionCleanup = productionResourceCoordinator.snapshot()
            ))
        } else {
            ownerPublishedStateRef.set(buildOwnerPublishedState())
        }
        publishSnapshot()
    }

    private fun buildSnapshot(owner: OwnerPublishedState): YuvTerminalSnapshot {
        val snap = owner.accounting
        return YuvTerminalSnapshot(
            receivedFrames = snap.receivedFrames,
            bufferedFrames = snap.bufferedFrames,
            persistedFrames = snap.persistedFrames,
            failedFrames = snap.failedFrames,
            droppedFrames = snap.droppedFrames,
            manifest = snap.manifest.toList(),
            completedResults = owner.completedResults,
            queuedWork = owner.queuedWork,
            inFlightWork = owner.inFlightWork,
            terminalStatus = owner.terminalStatus,
            terminalSettlementPhase = owner.terminalSettlementPhase,
            terminalReason = owner.terminalReason,
            discardedLateCompletions = owner.discardedLateCompletions,
            cleanupPhase = owner.cleanupPhase,
            diagnostics = diagnostics.toList(),
            metadataWriteOutcome = metadataWriteOutcomeRef.get(),
            motionSaveOutcome = motionSaveOutcomeRef.get(),
            statusDispatchOutcome = statusDispatchOutcomeRef.get(),
            callbackDispatchOutcome = callbackDispatchOutcomeRef.get(),
            callbackExecutionOutcome = callbackExecutionOutcomeRef.get(),
            callbackState = callbackStateRef.get(),
            productionCleanup = owner.productionCleanup
        )
    }

    private data class OwnerPublishedState(
        val accounting: YuvCaptureAccountingSnapshot,
        val completedResults: Int,
        val queuedWork: Int,
        val inFlightWork: Int,
        val terminalStatus: CaptureTerminalStatus,
        val terminalSettlementPhase: TerminalSettlementPhase,
        val terminalReason: String?,
        val discardedLateCompletions: List<Int>,
        val cleanupPhase: CleanupPhase,
        val productionCleanup: ProductionCleanupSnapshot?
    )

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
