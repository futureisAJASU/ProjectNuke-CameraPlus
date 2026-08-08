internal data class YuvTerminalSnapshot(
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
    val terminalSettlementPhase: TerminalSettlementPhase,
    val terminalReason: String?,
    val discardedLateCompletions: List<Int>,
    val cleanupPhase: CleanupPhase,
    val diagnostics: List<YuvCaptureDiagnostic>,
    val metadataWriteOutcome: TerminalOperationOutcome,
    val motionSaveOutcome: TerminalOperationOutcome,
    val statusDispatchOutcome: TerminalOperationOutcome,
    val callbackDispatchOutcome: TerminalOperationOutcome,
    val callbackExecutionOutcome: TerminalOperationOutcome,
    val callbackState: CallbackState
) {
    val isTerminal: Boolean get() = terminalStatus != CaptureTerminalStatus.ACTIVE
    val isSettled: Boolean get() = terminalSettlementPhase == TerminalSettlementPhase.SETTLED
}