package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RawTerminalRequestTest {
    @Test
    fun successFailureAndCancellationAreTypedRequestsForOneOwnerTransaction() {
        val success = RawTerminalRequest(
            CaptureTerminalStatus.SUCCESS,
            "CAPTURE_COMPLETE",
            null,
            RawTerminalCompletionKind.SUCCESS,
            null,
            true
        )
        val failure = success.copy(
            status = CaptureTerminalStatus.FAILED,
            jobStatus = "CAPTURE_FAILED",
            completionKind = RawTerminalCompletionKind.ERROR,
            saveMotion = false
        )
        val cancelled = failure.copy(
            status = CaptureTerminalStatus.CANCELLED,
            jobStatus = "CAPTURE_CANCELLED",
            reason = "cancelled"
        )

        assertEquals(RawTerminalCompletionKind.SUCCESS, success.completionKind)
        assertEquals(RawTerminalCompletionKind.ERROR, failure.completionKind)
        assertEquals(RawTerminalCompletionKind.ERROR, cancelled.completionKind)
        assertNotEquals(success.status, failure.status)
        assertNotEquals(failure.status, cancelled.status)
        assertEquals(false, failure.saveMotion)
        assertEquals(false, cancelled.saveMotion)
    }

    @Test
    fun settledSnapshotCarriesCompletedOperationOutcomesAndCleanup() {
        val progress = RawCaptureProgressSnapshot(3, 3, 3, 3, 0, 0, 3)
        val snapshot = RawTerminalSnapshot(
            progress = progress,
            terminalStatus = CaptureTerminalStatus.SUCCESS,
            reason = null,
            phase = RawTerminalSettlementPhase.SETTLED,
            settlementFailure = null,
            motion = RawTerminalOperationOutcome.Succeeded,
            metadata = RawTerminalOperationOutcome.Succeeded,
            status = RawTerminalOperationOutcome.Succeeded,
            callback = RawTerminalOperationOutcome.Succeeded,
            callbackDispatch = RawTerminalOperationOutcome.Succeeded,
            callbackExecution = RawTerminalOperationOutcome.Succeeded,
            cleanup = null
        )
        assertEquals(RawTerminalSettlementPhase.SETTLED, snapshot.phase)
        assertEquals(RawTerminalOperationOutcome.Succeeded, snapshot.motion)
        assertEquals(RawTerminalOperationOutcome.Succeeded, snapshot.metadata)
        assertEquals(RawTerminalOperationOutcome.Succeeded, snapshot.status)
        assertEquals(RawTerminalOperationOutcome.Succeeded, snapshot.callback)
        assertEquals(RawTerminalOperationOutcome.Succeeded, snapshot.callbackDispatch)
        assertEquals(RawTerminalOperationOutcome.Succeeded, snapshot.callbackExecution)
        assertEquals(3, snapshot.progress.savedFrames)
    }

    @Test
    fun cleanupLedgerRetainsDistinctSettlementStatuses() {
        assertEquals(
            setOf(
                RawOutputCleanupStatus.NOT_ATTEMPTED,
                RawOutputCleanupStatus.ABSENT,
                RawOutputCleanupStatus.DELETED,
                RawOutputCleanupStatus.DELETE_RETURNED_FALSE,
                RawOutputCleanupStatus.DELETE_THREW,
                RawOutputCleanupStatus.QUARANTINED,
                RawOutputCleanupStatus.QUARANTINE_FAILED,
                RawOutputCleanupStatus.ADOPTED
            ),
            RawOutputCleanupStatus.entries.toSet()
        )
    }

    @Test
    fun cleanupLedgerUsesTemporaryAndUnadoptedRolesPrecisely() {
        val records = listOf(
            RawOutputCleanupRecord("frame.raw16.tmp", RawOutputResourceKind.RAW_TEMP, RawOutputOwnershipRole.TEMPORARY, RawOutputCleanupStatus.DELETED),
            RawOutputCleanupRecord("frame.dng.tmp", RawOutputResourceKind.DNG_TEMP, RawOutputOwnershipRole.TEMPORARY, RawOutputCleanupStatus.ABSENT),
            RawOutputCleanupRecord("frame.raw16", RawOutputResourceKind.RAW_FINAL, RawOutputOwnershipRole.UNADOPTED, RawOutputCleanupStatus.DELETE_THREW),
            RawOutputCleanupRecord("frame.dng", RawOutputResourceKind.DNG_FINAL, RawOutputOwnershipRole.ADOPTED, RawOutputCleanupStatus.ADOPTED)
        )
        assertEquals(RawOutputOwnershipRole.TEMPORARY, records[0].ownershipRole)
        assertEquals(RawOutputOwnershipRole.TEMPORARY, records[1].ownershipRole)
        assertEquals(RawOutputOwnershipRole.UNADOPTED, records[2].ownershipRole)
        assertEquals(RawOutputOwnershipRole.ADOPTED, records[3].ownershipRole)
    }
}
