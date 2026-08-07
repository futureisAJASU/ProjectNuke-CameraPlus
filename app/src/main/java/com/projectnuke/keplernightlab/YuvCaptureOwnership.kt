package com.projectnuke.keplernightlab

import android.media.Image
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// ── Serializable manifest entry ────────────────────────────────────
data class YuvFrameManifestEntry(
    val frameIndex: Int,
    val filename: String,
    val timestampNs: Long,
    val persisted: Boolean,
    val failure: String? = null
)

// ── Candidate ownership: file handle + exactly-once settlement ─────
/**
 * Atomic candidate ownership state machine:
 *
 *   UNSETTLED -> ADOPTING -> ADOPTED          (exclusive adoption claim)
 *   UNSETTLED -> DISCARDING -> DISCARDED      (settlement, delete succeeded/absent)
 *   UNSETTLED -> DISCARDING -> QUARANTINED    (settlement, delete failed + quarantine)
 *   ADOPTING  -> DISCARDING -> DISCARDED      (adoption abort)
 *   ADOPTING  -> DISCARDING -> QUARANTINED    (adoption abort)
 *
 * Adoption and discard compete for the SAME atomic ownership claim: only one
 * transition out of UNSETTLED ever wins, a DISCARDING candidate can never become
 * ADOPTED, an ADOPTING candidate can never be deleted/quarantined by a concurrent
 * settlement, intermediate states are observable, and no terminal state is ever
 * overwritten by another terminal state.
 */
internal enum class CandidateOwnership {
    UNSETTLED, ADOPTING, DISCARDING, ADOPTED, DISCARDED, QUARANTINED
}

/**
 * Result of a candidate file operation through the injectable [YuvCandidateFilesystem]
 * seam.  Every outcome is explicit so owner-side cleanup never depends on
 * runCatching/boolean delete conventions and deterministic JVM tests can simulate
 * every failure mode.  Thrown exceptions from injected implementations are CONTAINED
 * by the handle and converted into [DELETE_THREW] / [QUARANTINE_FAILED] results that
 * carry the original throwable.
 */
internal sealed class CandidateFileOperationResult {
    /** Contained throwable for a throwing implementation, or null. */
    abstract val failure: Throwable?

    data object FILE_ABSENT : CandidateFileOperationResult() {
        override val failure: Throwable? = null
    }

    data object DELETED : CandidateFileOperationResult() {
        override val failure: Throwable? = null
    }

    data object DELETE_RETURNED_FALSE : CandidateFileOperationResult() {
        override val failure: Throwable? = null
    }

    data class DELETE_THREW(override val failure: Throwable) : CandidateFileOperationResult()

    data object QUARANTINED : CandidateFileOperationResult() {
        override val failure: Throwable? = null
    }

    data class QUARANTINE_FAILED(override val failure: Throwable? = null) : CandidateFileOperationResult()

    /** Stable token for debt descriptions (independent of carried throwables). */
    fun describe(): String = when (this) {
        FILE_ABSENT -> "FILE_ABSENT"
        DELETED -> "DELETED"
        DELETE_RETURNED_FALSE -> "DELETE_RETURNED_FALSE"
        is DELETE_THREW -> "DELETE_THREW"
        QUARANTINED -> "QUARANTINED"
        is QUARANTINE_FAILED -> "QUARANTINE_FAILED"
    }
}

/**
 * Injectable filesystem operator for candidate cleanup.  Implementations are expected
 * to return explicit results, but even a THROWING implementation is contained by the
 * candidate handle: the throwable is captured into [CandidateFileOperationResult]
 * and the candidate still reaches a terminal cleanup-debt state.
 */
internal interface YuvCandidateFilesystem {
    fun delete(candidate: File): CandidateFileOperationResult
    fun quarantine(candidate: File): CandidateFileOperationResult
}

internal object RealYuvCandidateFilesystem : YuvCandidateFilesystem {
    override fun delete(candidate: File): CandidateFileOperationResult {
        if (!candidate.exists()) return CandidateFileOperationResult.FILE_ABSENT
        return try {
            if (candidate.delete()) CandidateFileOperationResult.DELETED
            else CandidateFileOperationResult.DELETE_RETURNED_FALSE
        } catch (t: Throwable) {
            CandidateFileOperationResult.DELETE_THREW(t)
        }
    }

    override fun quarantine(candidate: File): CandidateFileOperationResult {
        if (!candidate.exists()) return CandidateFileOperationResult.FILE_ABSENT
        return try {
            if (candidate.renameTo(File(candidate.path + ".quarantined"))) {
                CandidateFileOperationResult.QUARANTINED
            } else {
                CandidateFileOperationResult.QUARANTINE_FAILED()
            }
        } catch (t: Throwable) {
            CandidateFileOperationResult.QUARANTINE_FAILED(t)
        }
    }
}

/**
 * Injectable fail-closed candidate validation seam (owner side, before reservation).
 * The default production verifier requires an existing, regular, readable file.
 */
internal fun interface YuvCandidateVerifier {
    fun verify(candidate: File, frameIndex: Int): Boolean
}

internal object RealYuvCandidateVerifier : YuvCandidateVerifier {
    override fun verify(candidate: File, frameIndex: Int): Boolean =
        candidate.exists() && candidate.isFile && candidate.canRead()
}

/**
 * Injectable fail-closed final-file verifier used AFTER a successful commit.  The
 * default production verifier requires a readable regular file carrying the PNG
 * signature; [frameIndex] allows deterministic per-frame test failure injection.
 */
internal fun interface YuvFinalFileVerifier {
    fun verify(finalFile: File, frameIndex: Int): Boolean
}

internal object RealYuvFinalFileVerifier : YuvFinalFileVerifier {
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    override fun verify(finalFile: File, frameIndex: Int): Boolean {
        if (!finalFile.exists() || !finalFile.isFile || !finalFile.canRead()) return false
        return try {
            finalFile.inputStream().use { input ->
                val header = ByteArray(8)
                input.read(header) == 8 && header.contentEquals(PNG_SIGNATURE)
            }
        } catch (t: Throwable) {
            false
        }
    }
}

/**
 * Result of a [YuvCandidateHandle.completeAdoption] call.  Structured results
 * prevent the loser of a same-genuine-claim race from being classified as
 * impossible corruption (AdoptionInvariantException).
 */
internal enum class AdoptionResult {
    COMPLETED,
    LOST_RACE,
    STALE_OR_INVALID_CLAIM,
    ALREADY_TERMINAL
}

/**
 * Terminal candidate publication: after every settlement the final atomic state
 * together with the [CandidateDisposalOutcome] (or null for ADOPTED) is
 * published so a caller observing DISCARDING cannot mistake an in-flight
 * operation for a settled terminal state.  [isInProgressOrTerminal] is true
 * when another caller is currently performing the settlement and has not yet
 * published the outcome; in this case finalState is DISCARDING.
 */
internal data class CandidateTerminalRecord(
    val finalState: CandidateOwnership,
    val outcome: CandidateDisposalOutcome? = null,
    val isInProgressOrTerminal: Boolean = false
)

/**
 * Owner-side result of a candidate settlement.  [cleanupFailed] is true exactly when
 * the candidate could neither be deleted nor quarantined: the file remains and the
 * cleanup debt stays observable ([failureDescription]),
 * preserving frame identity, candidate path, failed operations, throwable
 * type/message and the final candidate state.
 */
internal class CandidateDisposalOutcome(
    val terminal: CandidateTerminalRecord,
    val deleteResult: CandidateFileOperationResult? = null,
    val quarantineResult: CandidateFileOperationResult? = null,
    val alreadySettled: Boolean = false
) {
    val finalState: CandidateOwnership get() = terminal.finalState

    val cleanupFailed: Boolean
        get() = !alreadySettled && finalState == CandidateOwnership.QUARANTINED &&
            quarantineResult is CandidateFileOperationResult.QUARANTINE_FAILED

    fun failureDescription(frameIndex: Int, file: File): String? {
        if (alreadySettled) return null
        val quarantine = quarantineResult
        if (quarantine !is CandidateFileOperationResult.QUARANTINE_FAILED) return null
        val delete = deleteResult
        val deleteDetail = if (delete != null) {
            " delete=${delete.describe()}" + throwableDetail("deleteThrowable", delete.failure)
        } else {
            ""
        }
        val quarantineDetail = " quarantine=${quarantine.describe()}" +
            throwableDetail("quarantineThrowable", quarantine.failure)
        return "candidate cleanup debt frame=$frameIndex file=$file" +
            deleteDetail + quarantineDetail + " state=${finalState}"
    }

    companion object {
        fun rejectedForInFlight(terminal: CandidateTerminalRecord): CandidateDisposalOutcome =
            CandidateDisposalOutcome(terminal, alreadySettled = true)

        private fun throwableDetail(label: String, failure: Throwable?): String =
            if (failure == null) ""
            else "; $label=${failure::class.java.simpleName}: ${failure.message}"
    }
}

/**
 * Opaque, unforgeable adoption claim minted exactly once by
 * [YuvCandidateHandle.tryBeginAdoption].  The claim is validated SOLELY by
 * reference identity against the exact active claim object published in the
 * candidate's ADOPTING state record: completion and abort are accepted only for
 * the very instance that won the UNSETTLED -> ADOPTING transition.
 *
 * There is no copyable numeric credential and no factory that can construct
 * another claim which would satisfy the identity check.  The private constructor
 * is file-scoped to [YuvCaptureOwnership], so no module caller (including tests)
 * can mint a competing valid claim.  [invalidClaimForTest] is the only test seam
 * and it deliberately returns a distinct object instance that can never match the
 * active claim.
 */
internal class CandidateAdoptionClaim private constructor(
    val handle: YuvCandidateHandle,
    val frameIndex: Int
) {
    companion object {
        internal fun create(handle: YuvCandidateHandle, frameIndex: Int): CandidateAdoptionClaim =
            CandidateAdoptionClaim(handle, frameIndex)

        internal fun invalidForTest(handle: YuvCandidateHandle): CandidateAdoptionClaim =
            CandidateAdoptionClaim(handle, handle.frameIndex)
    }
}

/**
 * TEST-ONLY seam: mint a genuine-shaped but never-validating claim for [handle].
 * The active claim published by tryBeginAdoption is always a distinct object
 * instance, so reference-identity validation rejects this unconditionally.  Used
 * only to exercise rejection of copied/foreign credentials — never as a path to
 * a valid claim.
 */
internal fun invalidClaimForTest(handle: YuvCandidateHandle): CandidateAdoptionClaim =
    CandidateAdoptionClaim.invalidForTest(handle)

/**
 * Exactly-once candidate ownership handle backed by one atomic state machine.  A
 * candidate file is owned from creation until it settles exactly one way: ADOPTED
 * (owner committed it to a final PNG), DISCARDED (cleanup removed it, or it was
 * already absent), or QUARANTINED (cleanup could not remove it; the debt stays
 * observable).  Repeated/concurrent settlement attempts are idempotent and never
 * perform a second file operation; filesystem throws are contained with the
 * throwable preserved.
 */
internal class YuvCandidateHandle(
    val frameIndex: Int,
    val file: File
) {
    private data class StateRecord(
        val ownership: CandidateOwnership = CandidateOwnership.UNSETTLED,
        val activeClaim: CandidateAdoptionClaim? = null,
        val terminal: CandidateTerminalRecord? = null
    )

    private val record = AtomicReference(StateRecord())

    fun state(): CandidateOwnership = record.get().ownership

    /** The terminal record once settled, or null while in-flight or unsettled. */
    fun terminal(): CandidateTerminalRecord? = record.get().terminal

    /**
     * Exactly-once UNSETTLED -> ADOPTING.  The winner receives the exclusive
     * [CandidateAdoptionClaim] that becomes the active claim stored in the state
     * record; completion and abort are gated on reference identity with THAT EXACT
     * object, so a copied/foreign credential can never settle the candidate.
     * Returns null when the candidate is already claimed or settled.
     */
    fun tryBeginAdoption(): CandidateAdoptionClaim? {
        val old = record.get()
        if (old.ownership != CandidateOwnership.UNSETTLED) return null
        val claim = CandidateAdoptionClaim.create(this, frameIndex)
        val target = StateRecord(CandidateOwnership.ADOPTING, activeClaim = claim)
        return if (record.compareAndSet(old, target)) {
            claim
        } else {
            null
        }
    }

    /**
     * Exactly-once ADOPTING -> ADOPTED for the holder of [claim].  Only the genuine
     * active claim (reference-identical to the one published by tryBeginAdoption) may
     * complete: a copied/foreign/stale claim is rejected.  A same-genuine-claim race
     * with [abortAdoption] is a legitimate exactly-once race: exactly one wins, the
     * loser returns [AdoptionResult.LOST_RACE] without performing any filesystem or
     * accounting work.
     */
    fun completeAdoption(claim: CandidateAdoptionClaim): AdoptionResult {
        if (claim.handle !== this || claim.frameIndex != frameIndex) return AdoptionResult.STALE_OR_INVALID_CLAIM
        val rec = record.get()
        if (rec.ownership == CandidateOwnership.ADOPTED || rec.terminal != null) {
            return AdoptionResult.ALREADY_TERMINAL
        }
        if (rec.ownership != CandidateOwnership.ADOPTING || rec.activeClaim !== claim) {
            return AdoptionResult.STALE_OR_INVALID_CLAIM
        }
        val terminal = CandidateTerminalRecord(CandidateOwnership.ADOPTED)
        if (!record.compareAndSet(rec, StateRecord(CandidateOwnership.ADOPTED, terminal = terminal))) {
            // CAS failed: another thread transitioned the state between our read and
            // CAS.  If the exact active claim still matches and the state changed from
            // ADOPTING, this is a legitimate race (abortAdoption won), not corruption.
            val current = record.get()
            if (current.ownership != CandidateOwnership.ADOPTING ||
                current.activeClaim !== claim) {
                return AdoptionResult.LOST_RACE
            }
            // State is still ADOPTING with the same claim but CAS still failed:
            // impossible internal corruption.
            throw AdoptionInvariantException(
                "completeAdoption CAS failed despite exact active claim: " +
                    "frame=${frameIndex} file=${file} state=${rec.ownership}",
                operation = "completeAdoption",
                frameIndex = frameIndex
            )
        }
        return AdoptionResult.COMPLETED
    }

    /**
     * Exactly-once ADOPTING -> DISCARDING -> DISCARDED/QUARANTINED for the holder of
     * [claim]: only the exact active claim may abort, settling the candidate through
     * the normal settlement path (delete, quarantine on failure) and never leaving it
     * ADOPTING or UNSETTLED.  A copied/foreign/stale claim is rejected (in-flight,
     * no-op) without touching the file.  Filesystem throws are contained.
     */
    fun abortAdoption(
        claim: CandidateAdoptionClaim,
        filesystem: YuvCandidateFilesystem
    ): CandidateDisposalOutcome {
        if (claim.handle !== this || claim.frameIndex != frameIndex ||
            record.get().activeClaim !== claim) {
            val cur = record.get()
            val term = cur.terminal ?: CandidateTerminalRecord(cur.ownership, isInProgressOrTerminal = true)
            return CandidateDisposalOutcome.rejectedForInFlight(term)
        }
        val rec = record.get()
        if (rec.ownership != CandidateOwnership.ADOPTING || rec.activeClaim !== claim) {
            val term = rec.terminal ?: CandidateTerminalRecord(rec.ownership, isInProgressOrTerminal = true)
            return CandidateDisposalOutcome.rejectedForInFlight(term)
        }
        if (!record.compareAndSet(rec, StateRecord(CandidateOwnership.DISCARDING))) {
            val current = record.get()
            val term = current.terminal ?: CandidateTerminalRecord(current.ownership, isInProgressOrTerminal = true)
            return CandidateDisposalOutcome.rejectedForInFlight(term)
        }
        return settleFromDiscarding(filesystem)
    }

    /**
     * Exactly-once UNSETTLED -> DISCARDING -> DISCARDED/QUARANTINED
     * settlement: the first caller performs the file operations;
     * concurrent/repeated callers observe the settled (or in-flight) state
     * and never touch the file again.  While DISCARDING, a concurrent call
     * returns IN_PROGRESS (isInProgressOrTerminal=true), not a terminal
     * alreadySettled.
     */
    fun discardOrQuarantine(filesystem: YuvCandidateFilesystem): CandidateDisposalOutcome {
        var current = record.get()
        if (current.terminal != null) {
            return CandidateDisposalOutcome(current.terminal!!, alreadySettled = true)
        }
        if (current.ownership != CandidateOwnership.UNSETTLED) {
            return CandidateDisposalOutcome.rejectedForInFlight(
                CandidateTerminalRecord(current.ownership, isInProgressOrTerminal = true)
            )
        }
        if (!record.compareAndSet(current, StateRecord(CandidateOwnership.DISCARDING))) {
            val next = record.get()
            val term = next.terminal
            if (term != null) return CandidateDisposalOutcome(term, alreadySettled = true)
            return CandidateDisposalOutcome.rejectedForInFlight(
                CandidateTerminalRecord(next.ownership, isInProgressOrTerminal = true)
            )
        }
        return settleFromDiscarding(filesystem)
    }

    private fun settleFromDiscarding(filesystem: YuvCandidateFilesystem): CandidateDisposalOutcome {
        val deleteResult = containedDelete(filesystem)
        val quarantineResult: CandidateFileOperationResult?
        val finalState: CandidateOwnership
        when (deleteResult) {
            CandidateFileOperationResult.FILE_ABSENT,
            CandidateFileOperationResult.DELETED -> {
                quarantineResult = null
                finalState = CandidateOwnership.DISCARDED
            }
            else -> {
                quarantineResult = containedQuarantine(filesystem)
                finalState = CandidateOwnership.QUARANTINED
            }
        }
        val terminal = CandidateTerminalRecord(finalState)
        record.set(StateRecord(finalState, terminal = terminal))
        return CandidateDisposalOutcome(terminal, deleteResult, quarantineResult)
    }

    /** Contain a throwing delete implementation; the throwable is preserved. */
    private fun containedDelete(filesystem: YuvCandidateFilesystem): CandidateFileOperationResult =
        try {
            filesystem.delete(file)
        } catch (t: Throwable) {
            CandidateFileOperationResult.DELETE_THREW(t)
        }

    /** Contain a throwing quarantine implementation; the throwable is preserved. */
    private fun containedQuarantine(filesystem: YuvCandidateFilesystem): CandidateFileOperationResult =
        try {
            filesystem.quarantine(file)
        } catch (t: Throwable) {
            CandidateFileOperationResult.QUARANTINE_FAILED(t)
        }
}

// ── Adoption token: stateful exactly-once reservation → commit/rollback ──
/**
 * Truthful token states: COMMITTED is published only AFTER the accounting commit
 * (manifest + persistedFrames) succeeded; ROLLED_BACK only after both reservations
 * were released; FAILED means neither success may be claimed and the exact failure
 * stays observable via [AdoptionToken.failure].
 */
internal enum class AdoptionTokenState {
    RESERVED, COMMITTING, COMMITTED,
    ROLLING_BACK, ROLLED_BACK,
    FAILED, RECOVERING, FAILED_RECOVERED, FAILED_WITH_RESERVATION_DEBT
}

/**
 * Token-specific reservation probe.  Answers the four questions a recovery or
 * invariant diagnostic must ask about THIS token's entry, never about the global
 * reservation population:
 *  - is THIS frame index reserved?
 *  - is THIS filename reserved?
 *  - does THIS frame index collide in the manifest?
 *  - does THIS filename collide in the manifest?
 */
internal data class AdoptionReservationStatus(
    val indexReservedForThisEntry: Boolean,
    val filenameReservedForThisEntry: Boolean,
    val manifestHasIndex: Boolean,
    val manifestHasFilename: Boolean
) {
    val symmetric: Boolean get() = indexReservedForThisEntry == filenameReservedForThisEntry
    val released: Boolean get() = !indexReservedForThisEntry && !filenameReservedForThisEntry
}

/**
 * Stateful adoption token created by [YuvCaptureAccounting.tryReserveAdoption].
 * Reservation alone never touches the manifest or persistedFrames; the frame index
 * and final filename become committed (manifest entry + persistedFrames++) via
 * exactly-one [commit], or are released via exactly-one [rollback].  While COMMITTING
 * / ROLLING_BACK the accounting mutation happens under the token's exclusive claim
 * and the terminal state is published only afterwards.  A concurrent commit/rollback
 * still has exactly one owner (single CAS out of RESERVED).
 *
 * After a commit/rollback failure the token is FAILED; [recoverRollbackAfterFailure]
 * drives a truthful recovery sub-machine (FAILED -> RECOVERING -> FAILED_RECOVERED |
 * FAILED_WITH_RESERVATION_DEBT) using ONLY this token's reservation status.
 */
internal class AdoptionToken internal constructor(
    val reservedEntry: YuvFrameManifestEntry,
    private val accounting: YuvCaptureAccounting
) {
    private val state = AtomicReference(AdoptionTokenState.RESERVED)

    @Volatile
    var failure: Throwable? = null
        private set

    fun state(): AdoptionTokenState = state.get()

    fun reservationStatus(): AdoptionReservationStatus = accounting.reservationStatus(reservedEntry)

    /**
     * Exactly-once RESERVED -> COMMITTING -> COMMITTED.  COMMITTED is only visible
     * after the manifest/persisted mutation completed; an accounting failure leaves
     * the token in [AdoptionTokenState.FAILED] with [failure] set to an
     * [AdoptionInvariantException] (for false returns) or the thrown exception.
     * False when the token was already settled.
     */
    fun commit(): Boolean {
        if (!state.compareAndSet(AdoptionTokenState.RESERVED, AdoptionTokenState.COMMITTING)) return false
        val ok = try {
            accounting.commitAdoption(this)
        } catch (t: Throwable) {
            failure = t
            false
        }
        if (!ok) {
            if (failure == null) {
                failure = commitFailDiagnostic()
            }
            state.set(AdoptionTokenState.FAILED)
            return false
        }
        state.set(AdoptionTokenState.COMMITTED)
        return true
    }

    /**
     * Exactly-once RESERVED -> ROLLING_BACK -> ROLLED_BACK.  ROLLED_BACK is only
     * visible after both reservations were released; an accounting failure leaves
     * the token in [AdoptionTokenState.FAILED] with [failure] set to an
     * [AdoptionInvariantException] (for false returns) or the thrown exception.
     * False when the token was already settled.
     */
    fun rollback(): Boolean {
        if (!state.compareAndSet(AdoptionTokenState.RESERVED, AdoptionTokenState.ROLLING_BACK)) return false
        val ok = try {
            accounting.rollbackAdoption(this)
        } catch (t: Throwable) {
            failure = t
            false
        }
        if (!ok) {
            if (failure == null) {
                failure = rollbackFailDiagnostic()
            }
            state.set(AdoptionTokenState.FAILED)
            return false
        }
        state.set(AdoptionTokenState.ROLLED_BACK)
        return true
    }

    /**
     * Recovery after a failed adoption: releases whatever reservations remain for
     * THIS token, reports whether they are still held, and publishes a truthful
     * terminal recovery state.  Uses ONLY this token's reservation status.
     *
     * A `rollbackAdoption` false return is an invariant anomaly even when the
     * reservations are already absent — the disappearance is itself recorded as
     * [recoveryFailure], never silently treated as success.  The forbidden outcome
     * (returned false, reservations absent, released=true, recoveryFailure=null) is
     * therefore structurally impossible from this method.
     */
    fun recoverRollbackAfterFailure(): AdoptionRecoveryResult {
        val prev = state.getAndUpdate { s ->
            if (s == AdoptionTokenState.FAILED) AdoptionTokenState.RECOVERING else s
        }
        if (prev != AdoptionTokenState.FAILED) {
            val status = reservationStatus()
            return AdoptionRecoveryResult(
                eligible = false,
                currentState = prev,
                rollbackAttempted = false,
                rollbackReturnedSuccess = false,
                indexReservationRemaining = status.indexReservedForThisEntry,
                filenameReservationRemaining = status.filenameReservedForThisEntry,
                asymmetric = !status.symmetric,
                recoveryFailure = null
            )
        }
        val entry = reservedEntry
        var returnedSuccess = false
        var recoveryFailure: Throwable? = null
        try {
            val ok = accounting.rollbackAdoption(this)
            if (!ok) {
                // False return == invariant event.  Record it regardless of whether
                // the reservations are already absent.
                recoveryFailure = AdoptionInvariantException(
                    "rollbackAdoption returned false during recovery: " +
                        "frame=${entry.frameIndex} file=${entry.filename}",
                    operation = "rollbackAdoption",
                    frameIndex = entry.frameIndex,
                    filename = entry.filename,
                    rollbackAttempted = true,
                    rollbackReturnedSuccess = false,
                    reservationStatus = accounting.reservationStatus(entry)
                )
            } else {
                returnedSuccess = true
            }
        } catch (t: Throwable) {
            recoveryFailure = t
        }
        val status = accounting.reservationStatus(entry)
        val newState = if (status.released && recoveryFailure == null)
            AdoptionTokenState.FAILED_RECOVERED
        else
            AdoptionTokenState.FAILED_WITH_RESERVATION_DEBT
        state.set(newState)
        return AdoptionRecoveryResult(
            eligible = true,
            currentState = AdoptionTokenState.FAILED,
            rollbackAttempted = true,
            rollbackReturnedSuccess = returnedSuccess,
            indexReservationRemaining = status.indexReservedForThisEntry,
            filenameReservationRemaining = status.filenameReservedForThisEntry,
            asymmetric = !status.symmetric,
            recoveryFailure = recoveryFailure
        )
    }

    data class AdoptionRecoveryResult(
        val eligible: Boolean,
        val currentState: AdoptionTokenState?,
        val rollbackAttempted: Boolean,
        val rollbackReturnedSuccess: Boolean,
        val indexReservationRemaining: Boolean,
        val filenameReservationRemaining: Boolean,
        val asymmetric: Boolean,
        val recoveryFailure: Throwable? = null
    ) {
        val released: Boolean get() = !indexReservationRemaining && !filenameReservationRemaining
    }

    private fun commitFailDiagnostic(): AdoptionInvariantException {
        val entry = reservedEntry
        return AdoptionInvariantException(
            "commitAdoption returned false: frame=${entry.frameIndex} file=${entry.filename}",
            operation = "commitAdoption",
            frameIndex = entry.frameIndex,
            filename = entry.filename,
            reservationStatus = accounting.reservationStatus(entry)
        )
    }

    private fun rollbackFailDiagnostic(): AdoptionInvariantException {
        val entry = reservedEntry
        return AdoptionInvariantException(
            "rollbackAdoption returned false: frame=${entry.frameIndex} file=${entry.filename}",
            operation = "rollbackAdoption",
            frameIndex = entry.frameIndex,
            filename = entry.filename,
            reservationStatus = accounting.reservationStatus(entry)
        )
    }
}

/**
 * Entry-specific invariant anomaly.  Carries the exact reservation status of the
 * offender token's entry (never global counts) so diagnostics can answer
 * "is THIS frame reserved / does THIS filename collide in the manifest?".
 */
internal class AdoptionInvariantException(
    message: String,
    val operation: String,
    val frameIndex: Int? = null,
    val filename: String? = null,
    val rollbackAttempted: Boolean = false,
    val rollbackReturnedSuccess: Boolean = false,
    val reservationStatus: AdoptionReservationStatus? = null,
    val recoveryFailure: Throwable? = null
) : IllegalStateException(message) {
    val reservedIndexPresent: Boolean get() = reservationStatus?.indexReservedForThisEntry ?: false
    val reservedFilenamePresent: Boolean get() = reservationStatus?.filenameReservedForThisEntry ?: false
    val manifestCollisionIndex: Boolean get() = reservationStatus?.manifestHasIndex ?: false
    val manifestCollisionFilename: Boolean get() = reservationStatus?.manifestHasFilename ?: false
}

// ── Owned direct YUV source abstraction ────────────────────────────
internal interface OwnedDirectYuvSource {
    val timestampNs: Long
    fun encodeTo(encoder: YuvPngEncoder, candidate: File, rotationDegrees: Int)
    fun release()
}

internal class AndroidOwnedDirectYuvSource(
    val image: Image,
    override val timestampNs: Long
) : OwnedDirectYuvSource {
    private val released = AtomicBoolean(false)

    override fun encodeTo(encoder: YuvPngEncoder, candidate: File, rotationDegrees: Int) {
        encoder.encodeDirect(image, candidate, rotationDegrees)
    }

    override fun release() {
        if (released.compareAndSet(false, true)) {
            image.close()
        }
    }
}

// ── YuvCaptureAccounting ───────────────────────────────────────────
internal open class YuvCaptureAccounting {
    // Protected for deterministic invariant-failure injection in tests.
    protected val lock = Any()
    protected val reservedIndices = mutableSetOf<Int>()
    protected val reservedFilenames = mutableSetOf<String>()

    private var received = 0
    private var buffered = 0
    private var persisted = 0
    private var failed = 0
    private var dropped = 0
    private val manifest = linkedMapOf<Int, YuvFrameManifestEntry>()

    fun receivedFrame() = synchronized(lock) { received++ }
    open fun bufferedFrame() = synchronized(lock) { buffered++ }
    fun releasedBufferedFrame() = synchronized(lock) {
        check(buffered > 0) { "bufferedFrames released more than once" }
        buffered--
    }
    open fun failedFrame() = synchronized(lock) { failed++ }
    fun droppedFrame() = synchronized(lock) { dropped++ }

    // Legacy / ColorFusion compat — must reject any entry whose frame index or
    // filename is currently reserved by a pending adoption token, so legacy and
    // adoption paths can never race on the same identity.
    fun persistedFrame(entry: YuvFrameManifestEntry): Boolean = synchronized(lock) {
        if (!entry.persisted || manifest.containsKey(entry.frameIndex)
            || manifest.values.any { it.filename == entry.filename }
            || reservedIndices.contains(entry.frameIndex)
            || reservedFilenames.contains(entry.filename)) {
            return@synchronized false
        }
        manifest[entry.frameIndex] = entry
        persisted++
        true
    }

    fun tryReserveAdoption(entry: YuvFrameManifestEntry): AdoptionToken? = synchronized(lock) {
        if (manifest.containsKey(entry.frameIndex) ||
            manifest.values.any { it.filename == entry.filename } ||
            reservedIndices.contains(entry.frameIndex) ||
            reservedFilenames.contains(entry.filename)) {
            return@synchronized null
        }
        reservedIndices.add(entry.frameIndex)
        reservedFilenames.add(entry.filename)
        AdoptionToken(entry, this)
    }

    /**
     * Atomic reservation removal: BOTH reservations must exist, the manifest must
     * have neither the frame-index nor the filename, and persistedFrames ==
     * manifest.size BEFORE mutation.  Only after EVERY validation succeeds does
     * this operation remove both reservations, append the manifest entry and
     * increment persistedFrames.  On any invariant failure both sets are left
     * unchanged and false is returned.  Open for deterministic failure injection
     * in tests.
     */
    open fun commitAdoption(token: AdoptionToken): Boolean = synchronized(lock) {
        val entry = token.reservedEntry
        if (!reservedIndices.contains(entry.frameIndex) || !reservedFilenames.contains(entry.filename)) {
            return@synchronized false
        }
        if (manifest.containsKey(entry.frameIndex) ||
            manifest.values.any { it.filename == entry.filename }) {
            return@synchronized false
        }
        if (persisted != manifest.size) {
            return@synchronized false
        }
        reservedIndices.remove(entry.frameIndex)
        reservedFilenames.remove(entry.filename)
        manifest[entry.frameIndex] = entry
        persisted++
        check(persisted == manifest.size) { "persisted=$persisted manifest=${manifest.size}" }
        true
    }

    /**
     * Atomic reservation removal (rollback): BOTH reservations must exist; neither
     * set is mutated until both checks pass; both are removed inside this single
     * synchronized operation.  On any invariant failure both sets are left unchanged
     * and false is returned.  Open for deterministic failure injection in tests.
     */
    open fun rollbackAdoption(token: AdoptionToken): Boolean = synchronized(lock) {
        val entry = token.reservedEntry
        if (!reservedIndices.contains(entry.frameIndex) || !reservedFilenames.contains(entry.filename)) {
            return@synchronized false
        }
        reservedIndices.remove(entry.frameIndex)
        reservedFilenames.remove(entry.filename)
        true
    }

    /**
     * Token-specific reservation probe (Step 5): reports, for THIS entry only,
     * whether its frame index / filename are still reserved and whether either
     * collides with the committed manifest.  Recovery diagnostics must never infer
     * per-token state from the global reservation counts.
     */
    fun reservationStatus(entry: YuvFrameManifestEntry): AdoptionReservationStatus = synchronized(lock) {
        AdoptionReservationStatus(
            indexReservedForThisEntry = reservedIndices.contains(entry.frameIndex),
            filenameReservedForThisEntry = reservedFilenames.contains(entry.filename),
            manifestHasIndex = manifest.containsKey(entry.frameIndex),
            manifestHasFilename = manifest.values.any { it.filename == entry.filename }
        )
    }

    fun snapshot(): YuvCaptureAccountingSnapshot = synchronized(lock) {
        check(persisted == manifest.size) { "persisted=$persisted manifest=${manifest.size}" }
        YuvCaptureAccountingSnapshot(
            receivedFrames = received,
            bufferedFrames = buffered,
            persistedFrames = persisted,
            failedFrames = failed,
            droppedFrames = dropped,
            manifest = manifest.values.sortedBy { it.frameIndex },
            reservedCount = reservedIndices.size,
            reservedIndexCount = reservedIndices.size,
            reservedFilenameCount = reservedFilenames.size
        )
    }
}

data class YuvCaptureAccountingSnapshot(
    val receivedFrames: Int,
    val bufferedFrames: Int,
    val persistedFrames: Int,
    val failedFrames: Int,
    val droppedFrames: Int,
    val manifest: List<YuvFrameManifestEntry>,
    val reservedCount: Int = 0,
    val reservedIndexCount: Int = 0,
    val reservedFilenameCount: Int = 0
)

internal class YuvBufferReservations(private val limitBytes: Long) {
    private val retained = AtomicLong(0L)

    fun tryReserve(bytes: Long): Boolean {
        if (bytes <= 0L || bytes > limitBytes) return false
        while (true) {
            val current = retained.get()
            if (current > limitBytes - bytes) return false
            if (retained.compareAndSet(current, current + bytes)) return true
        }
    }

    fun release(bytes: Long) {
        if (bytes <= 0L) return
        while (true) {
            val current = retained.get()
            check(current >= bytes) { "reservation released more than once" }
            if (retained.compareAndSet(current, current - bytes)) return
        }
    }

    fun currentBytes(): Long = retained.get()
}

internal interface YuvImageAccess {
    fun timestampNs(): Long
    fun allocationBytes(): Long
    fun copy(frameIndex: Int): BufferedYuvFrame
    fun release()
}

internal class Camera2YuvImageAccess(private val image: Image) : YuvImageAccess {
    override fun timestampNs(): Long = image.timestamp
    override fun allocationBytes(): Long = actualYuvPlaneBytes(image)
    override fun copy(frameIndex: Int): BufferedYuvFrame = copyYuvFrameToMemory(image, frameIndex)
    override fun release() = image.close()
}

internal interface DirectYuvImageAccess : YuvImageAccess {
    fun takeImage(): Image?
}

internal class Camera2DirectYuvImageAccess(private val image: Image) : DirectYuvImageAccess {
    private var taken = false
    override fun timestampNs(): Long = image.timestamp
    override fun allocationBytes(): Long = 0L
    override fun copy(frameIndex: Int): BufferedYuvFrame = error("direct work does not copy")
    override fun release() { if (!taken) image.close() }
    override fun takeImage(): Image? {
        check(!taken) { "DirectYuvImageAccess.takeImage() called twice" }
        taken = true
        return image
    }
}

internal sealed interface DirectYuvWorkCreation {
    data class Accepted(val item: YuvPngWorkItem) : DirectYuvWorkCreation

    /**
     * Creation failure with full cleanup diagnostics: [cause] is the primary failure
     * and [releaseFailure] (when non-null) is the ownership-release failure that
     * happened while cleaning up (e.g. access.release / Image.close / source.release
     * throwing).  Every failure path performs AT MOST one release attempt, and a
     * release failure is never silently discarded.
     */
    data class Failed(
        val cause: Throwable,
        val releaseFailure: Throwable? = null
    ) : DirectYuvWorkCreation
}

/**
 * Direct work creation with exactly-once ownership transfer:
 * [DirectYuvImageAccess] -> [OwnedDirectYuvSource] -> [YuvPngWorkItem] -> worker.
 *
 * Every failure path (timestamp access, takeImage throwing, takeImage returning null,
 * source-adapter construction, work-item construction) releases the Image exactly
 * once — and if that release itself throws, the failure is CONTAINED and reported in
 * [DirectYuvWorkCreation.Failed.releaseFailure] instead of being discarded.  The
 * adapter/item factories are injectable for deterministic JVM tests; the production
 * defaults wrap the real Camera2 Image in [AndroidOwnedDirectYuvSource].
 */
internal fun createDirectYuvWork(
    frameIndex: Int,
    access: DirectYuvImageAccess,
    account: YuvCaptureAccounting,
    onRelease: (() -> Unit)? = null,
    sourceFactory: (Image, Long) -> OwnedDirectYuvSource = { image, ts ->
        AndroidOwnedDirectYuvSource(image, ts)
    },
    itemFactory: (Int, Long, OwnedDirectYuvSource, (() -> Unit)?) -> YuvPngWorkItem = { i, ts, src, release ->
        YuvPngWorkItem.directOwned(i, ts, src, release)
    }
): DirectYuvWorkCreation {
    val timestampNs = try {
        access.timestampNs()
    } catch (t: Throwable) {
        val releaseFailure = containedRelease { access.release() }
        account.failedFrame()
        return DirectYuvWorkCreation.Failed(t, releaseFailure)
    }
    val image = try {
        access.takeImage()
    } catch (t: Throwable) {
        val releaseFailure = containedRelease { access.release() }
        account.failedFrame()
        return DirectYuvWorkCreation.Failed(t, releaseFailure)
    }
    if (image == null) {
        // takeImage() returning null is a failure: there is never a valid-null direct item.
        val releaseFailure = containedRelease { access.release() }
        account.failedFrame()
        return DirectYuvWorkCreation.Failed(
            NullPointerException("DirectYuvImageAccess.takeImage() returned null for frame $frameIndex"),
            releaseFailure
        )
    }
    val source = try {
        sourceFactory(image, timestampNs)
    } catch (t: Throwable) {
        // The access was consumed by takeImage: the raw Image is released exactly once.
        val releaseFailure = containedRelease { image.close() }
        account.failedFrame()
        return DirectYuvWorkCreation.Failed(t, releaseFailure)
    }
    val item = try {
        itemFactory(frameIndex, timestampNs, source, onRelease)
    } catch (t: Throwable) {
        val releaseFailure = containedRelease { source.release() }
        account.failedFrame()
        return DirectYuvWorkCreation.Failed(t, releaseFailure)
    }
    return DirectYuvWorkCreation.Accepted(item)
}

/** Runs [release] at most once; returns the throwable it threw, or null on success. */
private inline fun containedRelease(release: () -> Unit): Throwable? = try {
    release()
    null
} catch (t: Throwable) {
    t
}

internal sealed interface BufferedYuvWorkCreation {
    data class Accepted(val item: YuvPngWorkItem) : BufferedYuvWorkCreation
    data object Rejected : BufferedYuvWorkCreation
    data class Failed(val cause: Throwable) : BufferedYuvWorkCreation
}

internal class YuvImageReleaseGuard(private val access: YuvImageAccess) {
    private val consumed = AtomicBoolean(false)
    fun releaseSafely() {
        if (consumed.compareAndSet(false, true)) runCatching { access.release() }
    }
}

internal fun createBufferedYuvWork(
    frameIndex: Int,
    access: YuvImageAccess,
    reservations: YuvBufferReservations,
    accounting: YuvCaptureAccounting,
    onRelease: (() -> Unit)? = null
): BufferedYuvWorkCreation {
    val guard = YuvImageReleaseGuard(access)
    var timestampNs = 0L
    var bytes = 0L
    var reserved = false
    var itemOwner = false
    try {
        timestampNs = access.timestampNs()
        bytes = access.allocationBytes()
        if (!reservations.tryReserve(bytes)) {
            accounting.droppedFrame()
            return BufferedYuvWorkCreation.Rejected
        }
        reserved = true
        val frame = access.copy(frameIndex)
        val item = YuvPngWorkItem.buffered(frameIndex, timestampNs, frame, bytes, reservations, accounting, onRelease)
        itemOwner = true
        return BufferedYuvWorkCreation.Accepted(item)
    } catch (oom: OutOfMemoryError) {
        throw oom
    } catch (t: Throwable) {
        accounting.failedFrame()
        return BufferedYuvWorkCreation.Failed(t)
    } finally {
        guard.releaseSafely()
        if (reserved && !itemOwner) {
            reservations.release(bytes)
        }
    }
}

/**
 * Sealed owned-source model: exactly one source per work item.  Direct items own an
 * [OwnedDirectYuvSource] (production: [AndroidOwnedDirectYuvSource] wrapping the real
 * Camera2 Image); buffered items own the copied [BufferedYuvFrame].  Mixed or absent
 * sources are unrepresentable.
 */
internal sealed interface YuvOwnedSource {
    data class Direct(val source: OwnedDirectYuvSource) : YuvOwnedSource
    data class Buffered(val frame: BufferedYuvFrame) : YuvOwnedSource
}

/**
 * Explicit result of one [YuvPngWorkItem.dispose] attempt.  Every sub-settlement is
 * independent: a failure in one never skips the others.  Requirement fields
 * (sourceReleaseRequired, reservationReleaseRequired,
 * bufferedAccountingReleaseRequired, releaseObserverRequired) are truthful: a
 * settlement that was required but not attempted/completed makes [isClean] false —
 * for example `dispose(null)` on a buffered item can never be clean.  Repeated
 * dispose returns an already-settled mirror ([alreadySettled]) that preserves the
 * ORIGINAL outcome's failures ([originalOutcome]) instead of losing diagnostics.
 */
internal class YuvWorkDisposalOutcome(
    val disposalAttempted: Boolean,
    val alreadySettled: Boolean = false,
    val alreadyDisposedByAnother: Boolean = false,
    val disposalInProgress: Boolean = false,
    val originalOutcome: YuvWorkDisposalOutcome? = null,
    val sourceReleaseRequired: Boolean,
    val sourceReleaseAttempted: Boolean,
    val sourceReleased: Boolean,
    val sourceReleaseFailure: Throwable? = null,
    val reservationReleaseRequired: Boolean,
    val reservationReleaseAttempted: Boolean,
    val reservationReleased: Boolean,
    val reservationReleaseFailure: Throwable? = null,
    val bufferedAccountingReleaseRequired: Boolean,
    val bufferedAccountingReleaseAttempted: Boolean,
    val bufferedAccountingReleased: Boolean,
    val bufferedAccountingFailure: Throwable? = null,
    val releaseObserverRequired: Boolean,
    val releaseObserverAttempted: Boolean,
    val releaseObserverCompleted: Boolean,
    val releaseObserverFailure: Throwable? = null
) {
    val failed: Boolean
        get() = failures().isNotEmpty()

    fun failures(): List<Throwable> = listOfNotNull(
        sourceReleaseFailure, reservationReleaseFailure, bufferedAccountingFailure, releaseObserverFailure
    )

    /**
     * True when every REQUIRED settlement was attempted AND completed by this call
     * (or, for an already-settled mirror, by the original settlement).  An
     * IN_PROGRESS outcome (disposalInProgress=true) is never clean.
     */
    val isClean: Boolean
        get() = if (disposalInProgress) false
        else originalOutcome?.isClean ?: (
            !failed &&
                (!sourceReleaseRequired || (sourceReleaseAttempted && sourceReleased)) &&
                (!reservationReleaseRequired || (reservationReleaseAttempted && reservationReleased)) &&
                (!bufferedAccountingReleaseRequired ||
                    (bufferedAccountingReleaseAttempted && bufferedAccountingReleased)) &&
                (!releaseObserverRequired || (releaseObserverAttempted && releaseObserverCompleted))
        )

    companion object {
        fun notAttempted(): YuvWorkDisposalOutcome = YuvWorkDisposalOutcome(
            disposalAttempted = false,
            sourceReleaseRequired = false,
            sourceReleaseAttempted = false,
            sourceReleased = false,
            reservationReleaseRequired = false,
            reservationReleaseAttempted = false,
            reservationReleased = false,
            bufferedAccountingReleaseRequired = false,
            bufferedAccountingReleaseAttempted = false,
            bufferedAccountingReleased = false,
            releaseObserverRequired = false,
            releaseObserverAttempted = false,
            releaseObserverCompleted = false
        )

        /**
         * IN_PROGRESS: another caller is actively disposing this item.  This outcome
         * is never clean and never reports a terminal settled state.
         */
        internal fun inProgress(): YuvWorkDisposalOutcome = YuvWorkDisposalOutcome(
            disposalAttempted = true,
            alreadyDisposedByAnother = true,
            disposalInProgress = true,
            sourceReleaseRequired = false,
            sourceReleaseAttempted = false,
            sourceReleased = false,
            reservationReleaseRequired = false,
            reservationReleaseAttempted = false,
            reservationReleased = false,
            bufferedAccountingReleaseRequired = false,
            bufferedAccountingReleaseAttempted = false,
            bufferedAccountingReleased = false,
            releaseObserverRequired = false,
            releaseObserverAttempted = false,
            releaseObserverCompleted = false
        )

        /**
         * Mirror of the FIRST settlement for repeated dispose: this call performed no
         * new attempt, but every original failure diagnostic is preserved so a lost
         * cleanup failure can never be hidden by an empty not-attempted result.
         */
        internal fun alreadySettled(first: YuvWorkDisposalOutcome): YuvWorkDisposalOutcome =
            YuvWorkDisposalOutcome(
                disposalAttempted = false,
                alreadySettled = true,
                alreadyDisposedByAnother = true,
                originalOutcome = first,
                sourceReleaseRequired = first.sourceReleaseRequired,
                sourceReleaseAttempted = false,
                sourceReleased = false,
                sourceReleaseFailure = first.sourceReleaseFailure,
                reservationReleaseRequired = first.reservationReleaseRequired,
                reservationReleaseAttempted = false,
                reservationReleased = false,
                reservationReleaseFailure = first.reservationReleaseFailure,
                bufferedAccountingReleaseRequired = first.bufferedAccountingReleaseRequired,
                bufferedAccountingReleaseAttempted = false,
                bufferedAccountingReleased = false,
                bufferedAccountingFailure = first.bufferedAccountingFailure,
                releaseObserverRequired = first.releaseObserverRequired,
                releaseObserverAttempted = false,
                releaseObserverCompleted = false,
                releaseObserverFailure = first.releaseObserverFailure
            )
    }
}

internal class YuvPngWorkItem private constructor(
    val frameIndex: Int,
    val timestampNs: Long,
    private val source: YuvOwnedSource,
    private val retainedBytes: Long,
    private val reservations: YuvBufferReservations?,
    private val onRelease: (() -> Unit)?
) {
    private enum class DisposalState { NOT_STARTED, DISPOSING, SETTLED }

    private data class DisposalRecord(
        val state: DisposalState = DisposalState.NOT_STARTED,
        val outcome: YuvWorkDisposalOutcome? = null
    )

    private val disposal = AtomicReference(DisposalRecord())
    private val bufferedReleased = AtomicBoolean(false)

    internal val isBuffered: Boolean
        get() = source is YuvOwnedSource.Buffered

    fun sourceForEncoding(): YuvOwnedSource = source

    /**
     * ColorFusion-compatible view: the underlying Image for a production direct item,
     * or null for buffered items and test-owned direct sources.  Never the mutable
     * ownership field it used to be — the sealed source is the single owner.
     */
    fun imageForEncoding(): Image? = (source as? YuvOwnedSource.Direct)
        ?.source
        ?.let { it as? AndroidOwnedDirectYuvSource }
        ?.image

    fun bufferedForEncoding(): BufferedYuvFrame? = (source as? YuvOwnedSource.Buffered)?.frame

    fun settleBufferedAccounting(accounting: YuvCaptureAccounting) {
        if (retainedBytes > 0L && bufferedReleased.compareAndSet(false, true)) {
            reservations?.release(retainedBytes)
            accounting.releasedBufferedFrame()
        }
    }

    /** The FIRST disposal outcome, or null when this item has never been disposed. */
    internal fun disposalOutcome(): YuvWorkDisposalOutcome? = disposal.get().outcome

    /**
     * Exactly-once disposal with an explicit [YuvWorkDisposalOutcome]: the owned source
     * (direct release / buffered reservation + accounting) and the release observer are
     * each settled independently so a failure never skips other cleanup.
     *
     * NOT_STARTED -> DISPOSING -> SETTLED(outcome) is a single atomic state
     * machine: the first caller transitions to DISPOSING and performs every
     * independent resource settlement before publishing SETTLED.  A second
     * caller during DISPOSING returns an IN_PROGRESS outcome (isClean=false)
     * and must not perform any resource settlement.  After SETTLED, repeated
     * calls return an already-settled mirror of the exact original outcome.
     */
    fun dispose(accounting: YuvCaptureAccounting? = null): YuvWorkDisposalOutcome {
        // AtomicReference.compareAndSet compares object IDENTITY, so the expected
        // value must be the exact instance read from the reference — a freshly
        // constructed equal record never matches.  getAndUpdate returns the
        // PREVIOUS value: only a previous NOT_STARTED record wins the DISPOSING
        // transition.
        val previous = disposal.getAndUpdate { cur ->
            if (cur.state == DisposalState.NOT_STARTED) DisposalRecord(DisposalState.DISPOSING) else cur
        }
        if (previous.state != DisposalState.NOT_STARTED) {
            val first = previous.outcome
            return if (first != null) {
                YuvWorkDisposalOutcome.alreadySettled(first)
            } else {
                // DISPOSING – another caller is in-flight.  Return IN_PROGRESS, never
                // marked clean.
                YuvWorkDisposalOutcome.inProgress()
            }
        }

        val isDirect = source is YuvOwnedSource.Direct
        val isBufferedItem = source is YuvOwnedSource.Buffered
        val bufferedSettlementRequired = isBufferedItem && retainedBytes > 0L

        var sourceReleaseAttempted = false
        var sourceReleased = false
        var sourceReleaseFailure: Throwable? = null
        if (isDirect) {
            sourceReleaseAttempted = true
            try {
                source.source.release()
                sourceReleased = true
            } catch (t: Throwable) {
                sourceReleaseFailure = t
            }
        }

        var reservationReleaseAttempted = false
        var reservationReleased = false
        var reservationReleaseFailure: Throwable? = null
        var bufferedAccountingReleaseAttempted = false
        var bufferedAccountingReleased = false
        var bufferedAccountingFailure: Throwable? = null
        // Buffered accounting settlement requires the accounting handle AND an
        // unreleased reservation; dispose(null) on a buffered item cannot settle the
        // reservation, so the outcome is (truthfully) not clean.
        if (bufferedSettlementRequired && accounting != null &&
            bufferedReleased.compareAndSet(false, true)) {
            reservationReleaseAttempted = true
            try {
                reservations?.release(retainedBytes)
                reservationReleased = true
            } catch (t: Throwable) {
                reservationReleaseFailure = t
            }
            bufferedAccountingReleaseAttempted = true
            try {
                accounting.releasedBufferedFrame()
                bufferedAccountingReleased = true
            } catch (t: Throwable) {
                bufferedAccountingFailure = t
            }
        }

        var releaseObserverAttempted = false
        var releaseObserverCompleted = false
        var releaseObserverFailure: Throwable? = null
        val observer = onRelease
        if (observer != null) {
            releaseObserverAttempted = true
            try {
                observer.invoke()
                releaseObserverCompleted = true
            } catch (t: Throwable) {
                releaseObserverFailure = t
            }
        }

        val outcome = YuvWorkDisposalOutcome(
            disposalAttempted = true,
            sourceReleaseRequired = isDirect,
            sourceReleaseAttempted = sourceReleaseAttempted,
            sourceReleased = sourceReleased,
            sourceReleaseFailure = sourceReleaseFailure,
            reservationReleaseRequired = bufferedSettlementRequired,
            reservationReleaseAttempted = reservationReleaseAttempted,
            reservationReleased = reservationReleased,
            reservationReleaseFailure = reservationReleaseFailure,
            bufferedAccountingReleaseRequired = bufferedSettlementRequired,
            bufferedAccountingReleaseAttempted = bufferedAccountingReleaseAttempted,
            bufferedAccountingReleased = bufferedAccountingReleased,
            bufferedAccountingFailure = bufferedAccountingFailure,
            releaseObserverRequired = observer != null,
            releaseObserverAttempted = releaseObserverAttempted,
            releaseObserverCompleted = releaseObserverCompleted,
            releaseObserverFailure = releaseObserverFailure
        )
        disposal.set(DisposalRecord(DisposalState.SETTLED, outcome))
        return outcome
    }

    companion object {
        /**
         * ColorFusion-compatible direct factory: wraps the (non-null) Camera2 Image in
         * an [AndroidOwnedDirectYuvSource] that is released exactly once by dispose().
         */
        fun direct(
            frameIndex: Int,
            timestampNs: Long,
            image: Image,
            onRelease: (() -> Unit)? = null
        ): YuvPngWorkItem = directOwned(
            frameIndex, timestampNs, AndroidOwnedDirectYuvSource(image, timestampNs), onRelease
        )

        fun directOwned(
            frameIndex: Int,
            timestampNs: Long,
            source: OwnedDirectYuvSource,
            onRelease: (() -> Unit)? = null
        ): YuvPngWorkItem =
            YuvPngWorkItem(frameIndex, timestampNs, YuvOwnedSource.Direct(source), 0L, null, onRelease)

        fun buffered(
            frameIndex: Int, timestampNs: Long, frame: BufferedYuvFrame,
            retainedBytes: Long, reservations: YuvBufferReservations,
            accounting: YuvCaptureAccounting, onRelease: (() -> Unit)? = null
        ): YuvPngWorkItem {
            val item = YuvPngWorkItem(
                frameIndex, timestampNs, YuvOwnedSource.Buffered(frame), retainedBytes, reservations, onRelease
            )
            accounting.bufferedFrame()
            return item
        }

        internal fun ownedForTest(onRelease: () -> Unit): YuvPngWorkItem =
            directOwned(-1, 0L, NoOpOwnedDirectYuvSource, onRelease)

        internal fun bufferedForTest(
            frameIndex: Int, timestampNs: Long, retainedBytes: Long,
            reservations: YuvBufferReservations, accounting: YuvCaptureAccounting,
            onRelease: (() -> Unit)? = null
        ): YuvPngWorkItem {
            val f = BufferedYuvFrame(
                frameIndex, timestampNs, 1, 1,
                ByteArray(0), ByteArray(0), ByteArray(0), 1, 1, 1, 1, 1, 1
            )
            val item = YuvPngWorkItem(
                frameIndex, timestampNs, YuvOwnedSource.Buffered(f), retainedBytes, reservations, onRelease
            )
            accounting.bufferedFrame()
            return item
        }
    }

    private object NoOpOwnedDirectYuvSource : OwnedDirectYuvSource {
        override val timestampNs: Long = 0L
        override fun encodeTo(encoder: YuvPngEncoder, candidate: File, rotationDegrees: Int) =
            error("NoOpOwnedDirectYuvSource cannot encode")
        override fun release() = Unit
    }
}

internal interface YuvPngEncoder {
    fun encodeDirect(image: Image, candidate: File, rotationDegrees: Int)
    fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int)
}

internal fun interface YuvCandidateCommitter {
    fun commit(candidate: File, finalFile: File)
}

internal class YuvPngWorkProcessor(
    private val encoder: YuvPngEncoder,
    private val committer: YuvCandidateCommitter
) {
    fun encode(item: YuvPngWorkItem, candidate: File, rotationDegrees: Int) {
        // Typed dispatch over the sealed owned source: the direct path enters the
        // encoder through the OwnedDirectYuvSource, never through nullable probing.
        when (val source = item.sourceForEncoding()) {
            is YuvOwnedSource.Direct -> source.source.encodeTo(encoder, candidate, rotationDegrees)
            is YuvOwnedSource.Buffered -> encoder.encodeBuffered(source.frame, candidate, rotationDegrees)
        }
    }

    fun commit(candidate: File, finalFile: File) = committer.commit(candidate, finalFile)
}



internal class DisposableYuvTask(
    val item: YuvPngWorkItem,
    private val accounting: YuvCaptureAccounting,
    private val body: () -> Unit
) : OutcomeDisposableCaptureTask {
    override fun run() = body()
    override fun dispose() { disposeWithOutcome() }

    override fun disposeWithOutcome(): CaptureTaskDisposalOutcome {
        val outcome = item.dispose(accounting)
        return if (outcome.isClean) {
            CaptureTaskDisposalOutcome.Clean
        } else {
            CaptureTaskDisposalOutcome.Unclean(
                outcome,
                disposalDescription(outcome, item.frameIndex)
            )
        }
    }
}

/** Stable description of an unclean item disposal for debt reporting. */
internal fun disposalDescription(outcome: YuvWorkDisposalOutcome, frameIndex: Int): String {
    val parts = mutableListOf<String>()
    parts.add("work-item disposal unclean frame=$frameIndex")

    if (outcome.disposalInProgress) parts.add("disposal=IN_PROGRESS")

    if (outcome.sourceReleaseRequired && !outcome.sourceReleaseAttempted)
        parts.add("sourceReleaseRequired=notAttempted")
    if (outcome.reservationReleaseRequired && !outcome.reservationReleaseAttempted)
        parts.add("reservationReleaseRequired=notAttempted")
    if (outcome.bufferedAccountingReleaseRequired && !outcome.bufferedAccountingReleaseAttempted)
        parts.add("bufferedAccountingReleaseRequired=notAttempted")
    if (outcome.releaseObserverRequired && !outcome.releaseObserverAttempted)
        parts.add("releaseObserverRequired=notAttempted")

    if (outcome.disposalInProgress) parts.add("anotherCaller=DISPOSING")

    val failures = outcome.failures()
    if (failures.isNotEmpty()) {
        parts.add(
            failures.joinToString("; ") {
                "${it::class.java.simpleName}: ${it.message}"
            }
        )
    }

    if (parts.size == 1) parts.add("noFailures")
    return parts.joinToString(": ")
}

// ═══ Cleanup coordinator ═══════════════════════════════════════════════

internal enum class CleanupPhase { NOT_STARTED, IN_PROGRESS, COMPLETED }

internal data class YuvCleanupResult(
    val phase: CleanupPhase,
    val cleanupStarted: Boolean,
    val cleanupInitiationCount: Int,
    val ownerCloseRequested: Boolean,
    val workerShutdownRequested: Boolean,
    val totalDrainClaims: Int,
    val totalDrainDisposalAttempts: Int,
    val totalDrainDisposalsSucceeded: Int,
    val totalDrainSettlementsSucceeded: Int,
    val totalDrainSettlementsFailed: Int,
    val totalQueuedTasksRemoved: Int,
    val totalQueuedDisposableDisposalAttempts: Int,
    val totalQueuedDisposableDisposalsSucceeded: Int,
    val totalQueuedNonDisposableTasksRemoved: Int,
    val activeWorkersAtCleanupStart: Int,
    val currentRetainedItems: Int,
    val currentEncodingItems: Int,
    val currentSettlingItems: Int,
    val currentDrainingItems: Int,
    val currentBufferedFrames: Int,
    val currentReservedBytes: Long,
    val cleanupFailures: List<String>,
    val workerTaskDisposalFailures: List<String>,
    val workerRejectionNotificationFailures: List<String>,
    val workerTaskFailures: List<BoundedCaptureWorker.WorkerFailure>
)

internal class YuvCleanupCoordinator(
    private val captureStateOwner: CaptureStateOwner,
    private val lifecycle: YuvBufferedLifecycle,
    private val accounting: YuvCaptureAccounting,
    private val reservations: YuvBufferReservations,
    private val boundedWorker: BoundedCaptureWorker
) {
    private data class CleanupState(
        val phase: CleanupPhase = CleanupPhase.NOT_STARTED,
        val ownerCloseRequested: Boolean = false,
        val workerShutdownRequested: Boolean = false,
        val drainClaims: Int = 0,
        val drainDisposalAttempts: Int = 0,
        val drainDisposalsSucceeded: Int = 0,
        val drainSettlementsSucceeded: Int = 0,
        val drainSettlementsFailed: Int = 0,
        val queuedTasksRemoved: Int = 0,
        val queuedDisposableDisposalAttempted: Int = 0,
        val queuedDisposableDisposalsSucceeded: Int = 0,
        val queuedNonDisposableTasksRemoved: Int = 0,
        val activeWorkersAtStart: Int = 0,
        val workerTaskDisposalFailures: List<String> = emptyList(),
        val workerRejectionNotificationFailures: List<String> = emptyList(),
        val failures: List<String> = emptyList()
    )

    private val stateRef = AtomicReference(CleanupState())

    private fun buildSnapshot(): YuvCleanupResult {
        val s = stateRef.get()
        val snap = accounting.snapshot()
        return YuvCleanupResult(
            phase = s.phase,
            cleanupStarted = s.phase != CleanupPhase.NOT_STARTED,
            cleanupInitiationCount = if (s.phase != CleanupPhase.NOT_STARTED) 1 else 0,
            ownerCloseRequested = s.ownerCloseRequested,
            workerShutdownRequested = s.workerShutdownRequested,
            totalDrainClaims = s.drainClaims,
            totalDrainDisposalAttempts = s.drainDisposalAttempts,
            totalDrainDisposalsSucceeded = s.drainDisposalsSucceeded,
            totalDrainSettlementsSucceeded = s.drainSettlementsSucceeded,
            totalDrainSettlementsFailed = s.drainSettlementsFailed,
            totalQueuedTasksRemoved = s.queuedTasksRemoved,
            totalQueuedDisposableDisposalAttempts = s.queuedDisposableDisposalAttempted,
            totalQueuedDisposableDisposalsSucceeded = s.queuedDisposableDisposalsSucceeded,
            totalQueuedNonDisposableTasksRemoved = s.queuedNonDisposableTasksRemoved,
            activeWorkersAtCleanupStart = s.activeWorkersAtStart,
            currentRetainedItems = lifecycle.retainedCount(),
            currentEncodingItems = lifecycle.encodingCount(),
            currentSettlingItems = lifecycle.settlingCount(),
            currentDrainingItems = lifecycle.drainingCount(),
            currentBufferedFrames = snap.bufferedFrames,
            currentReservedBytes = reservations.currentBytes(),
            // Copy failure collections: the published snapshot is immutable.
            cleanupFailures = s.failures.toList(),
            workerTaskDisposalFailures = s.workerTaskDisposalFailures.toList(),
            workerRejectionNotificationFailures = s.workerRejectionNotificationFailures.toList(),
            workerTaskFailures = boundedWorker.disposalsFailureLedger.toList()
        )
    }

    /**
     * Runs the cleanup sequence exactly once.  Every safety stage has its own failure
     * boundary so one stage's failure never skips a later stage:
     *
     * 1. close CaptureStateOwner
     * 2. claim retained lifecycle items for coordinated drain (claimRetainedForDrain)
     * 3. dispose each claim's item outside the lifecycle lock, then finish each claim
     *    independently (one failure never skips later claims)
     * 4. request BoundedCaptureWorker shutdown
     * 5. merge worker cleanup failures
     * 6. publish COMPLETED state
     *
     * Every failure is recorded with its stage and item/frame identity where available.
     * A concurrent call observes IN_PROGRESS (cleanup not completed); a repeated call
     * after completion returns the same historical totals.
     */
    fun perform(): YuvCleanupResult {
        val mutableFailures = mutableListOf<String>()

        val prev = stateRef.getAndUpdate { current ->
            if (current.phase == CleanupPhase.NOT_STARTED) {
                current.copy(phase = CleanupPhase.IN_PROGRESS)
            } else {
                current
            }
        }
        if (prev.phase != CleanupPhase.NOT_STARTED) {
            return buildSnapshot()
        }

        val activeBeforeDrain = boundedWorker.activeCount()
        stateRef.getAndUpdate { it.copy(activeWorkersAtStart = activeBeforeDrain) }

        // Step 1: close owner (independent failure boundary)
        try {
            captureStateOwner.close()
            stateRef.getAndUpdate { it.copy(ownerCloseRequested = true) }
        } catch (t: Throwable) {
            mutableFailures.add("ownerClose: ${t.message}")
        }

        // Step 2: claim retained lifecycle items for coordinated drain (independent
        // failure boundary).  A failure here must NOT skip worker shutdown or final
        // state publication.
        val claims = try {
            lifecycle.claimRetainedForDrain()
        } catch (t: Throwable) {
            mutableFailures.add("claimRetainedForDrain: ${t.message}")
            emptyList()
        }

        // Step 3: settle each claim with the disposal-aware disposeAndFinish, OUTSIDE
        // the lifecycle lock.  Each claim settles independently: one failure never skips
        // later claims; disposal failures and lifecycle-release failures are recorded
        // separately with frame identity, and every counter stays truthful.
        for (claim in claims) {
            val outcome = try {
                claim.disposeAndFinish(accounting)
            } catch (t: Throwable) {
                mutableFailures.add("drainDisposeAndFinish[${claim.frameIndex}]: ${t.message}")
                continue
            }
            stateRef.getAndUpdate { current ->
                current.copy(
                    drainDisposalAttempts = current.drainDisposalAttempts + 1,
                    drainDisposalsSucceeded = current.drainDisposalsSucceeded +
                        if (outcome.disposal.isClean) 1 else 0,
                    drainSettlementsSucceeded = current.drainSettlementsSucceeded +
                        if (outcome.status == DrainSettlementStatus.SETTLED) 1 else 0,
                    drainSettlementsFailed = current.drainSettlementsFailed +
                        if (outcome.status == DrainSettlementStatus.FAILED) 1 else 0
                )
            }
            if (outcome.status == DrainSettlementStatus.FAILED) {
                val reason = outcome.lifecycleReleaseFailure?.message?.let { "lifecycle release failed: $it" }
                    ?: "item disposal unclean"
                mutableFailures.add(
                    "drainSettle[${claim.frameIndex}]: $reason; item remains DRAINING"
                )
            }
            outcome.disposal.failures().forEach {
                mutableFailures.add("drainDispose[${claim.frameIndex}]: ${it.message}")
            }
        }

        // Step 4: request worker shutdown (independent failure boundary)
        var workerReport: BoundedCaptureWorker.CleanupReport? = null
        try {
            workerReport = boundedWorker.shutdownNow()
        } catch (t: Throwable) {
            mutableFailures.add("workerShutdown: ${t.message}")
        }

        // Step 5: merge worker cleanup failures
        val report = workerReport
        if (report != null) {
            mutableFailures.addAll(report.taskDisposalFailures)
            mutableFailures.addAll(report.rejectionNotificationFailures)
        }

        // Step 6: publish COMPLETED state in one immutable snapshot
        stateRef.getAndUpdate { current ->
            current.copy(
                workerShutdownRequested = true,
                drainClaims = claims.size,
                queuedTasksRemoved = report?.queuedTasksRemoved ?: 0,
                queuedDisposableDisposalAttempted = report?.queuedDisposableTasksDisposalAttempted ?: 0,
                queuedDisposableDisposalsSucceeded = report?.queuedDisposableTasksDisposedSuccessfully ?: 0,
                queuedNonDisposableTasksRemoved = report?.queuedNonDisposableTasksRemoved ?: 0,
                workerTaskDisposalFailures = report?.taskDisposalFailures ?: emptyList(),
                workerRejectionNotificationFailures = report?.rejectionNotificationFailures ?: emptyList(),
                failures = mutableFailures.toList()
            )
        }
        stateRef.getAndUpdate { it.copy(phase = CleanupPhase.COMPLETED) }
        return buildSnapshot()
    }

    fun snapshot(): YuvCleanupResult = buildSnapshot()
}
