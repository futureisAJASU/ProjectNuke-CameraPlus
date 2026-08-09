package com.projectnuke.keplernightlab

import java.util.concurrent.atomic.AtomicReference

internal enum class RawTerminalCompletionKind { SUCCESS, ERROR }
internal enum class RawTerminalSettlementPhase { ACTIVE, CLAIMED, SETTLING, SETTLED, SETTLEMENT_FAILED }

internal data class RawTerminalRequest(
    val status: CaptureTerminalStatus,
    val jobStatus: String,
    val reason: String?,
    val completionKind: RawTerminalCompletionKind,
    val cause: Throwable?,
    val saveMotion: Boolean
)

internal sealed interface RawTerminalOperationOutcome {
    data object NotRequested : RawTerminalOperationOutcome
    data object Succeeded : RawTerminalOperationOutcome
    data class Failed(val failure: Throwable) : RawTerminalOperationOutcome
}

internal data class RawTerminalSnapshot(
    val progress: RawCaptureProgressSnapshot,
    val terminalStatus: CaptureTerminalStatus,
    val reason: String?,
    val phase: RawTerminalSettlementPhase,
    val settlementFailure: Throwable?,
    val motion: RawTerminalOperationOutcome,
    val metadata: RawTerminalOperationOutcome,
    val status: RawTerminalOperationOutcome,
    val callback: RawTerminalOperationOutcome,
    val cleanup: RawProductionCleanupSnapshot?
)

internal class RawTerminalSnapshotStore(initial: RawTerminalSnapshot) {
    private val ref = AtomicReference(initial)
    fun get(): RawTerminalSnapshot = ref.get()
    fun publish(value: RawTerminalSnapshot) { ref.set(value) }
}
