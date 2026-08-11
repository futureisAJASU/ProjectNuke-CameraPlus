package com.projectnuke.keplernightlab

enum class PreviewResourceOperation {
    STOP_REPEATING,
    CAPTURE_SESSION_CLOSE,
    CAMERA_DEVICE_CLOSE,
    SURFACE_RELEASE,
    HANDLER_THREAD_QUIT,
    HANDLER_THREAD_TERMINATION
}

data class PreviewResourceSettlementRecord(
    val generation: Long,
    val operation: PreviewResourceOperation,
    val succeeded: Boolean,
    val failure: Throwable? = null
)

data class PreviewCleanupSnapshot(
    val generation: Long,
    val records: List<PreviewResourceSettlementRecord>
) {
    val failures: List<PreviewResourceSettlementRecord>
        get() = records.filterNot { it.succeeded }
}

data class PreviewCleanupDiagnostics(
    val lastCleanupSnapshot: PreviewCleanupSnapshot? = null,
    val lateResourceSettlements: List<PreviewCleanupSnapshot> = emptyList(),
    val threadTerminationOutcome: PreviewResourceSettlementRecord? = null,
    val callbackDispatchFailure: Throwable? = null,
    val cleanupDispatchFailure: Throwable? = null
)

/** Production recorder: one bounded cumulative snapshot per preview cleanup generation. */
internal class PreviewCleanupAccumulator {
    private var current: PreviewCleanupSnapshot? = null

    @Synchronized
    fun record(settlement: PreviewCleanupSnapshot, late: Boolean): PreviewCleanupSnapshot {
        if (late) return settlement
        val previous = current
        val next = if (previous?.generation == settlement.generation) {
            PreviewCleanupSnapshot(settlement.generation, previous.records + settlement.records)
        } else {
            settlement
        }
        current = next
        return next
    }
}

internal fun settlePreviewResources(
    generation: Long,
    operations: List<Pair<PreviewResourceOperation, () -> Unit>>
): PreviewCleanupSnapshot {
    val records = operations.map { (operation, release) ->
        try {
            release()
            PreviewResourceSettlementRecord(generation, operation, succeeded = true)
        } catch (failure: Throwable) {
            PreviewResourceSettlementRecord(generation, operation, succeeded = false, failure = failure)
        }
    }
    return PreviewCleanupSnapshot(generation, records)
}
