package com.projectnuke.keplernightlab

import java.util.concurrent.CancellationException

/**
 * Combines an operation failure with a failure raised while settling its resources.
 *
 * A fatal cleanup failure is still authoritative, but it must not erase an already
 * fatal operation failure.  Cancellation is treated as a primary control-flow result
 * unless cleanup itself raises a fatal error.
 */
internal fun combineSettlementFailure(
    primary: Throwable?,
    cleanup: Throwable?
): Throwable? {
    if (primary == null) return cleanup
    if (cleanup == null) return primary

    val selected = when {
        primary is Error -> primary
        cleanup is Error -> cleanup
        primary is CancellationException -> primary
        cleanup is CancellationException -> cleanup
        else -> primary
    }
    val secondary = if (selected === primary) cleanup else primary
    if (secondary !== selected) selected.addSuppressed(secondary)
    return selected
}

internal inline fun <T> withSettlementPrecedence(
    block: () -> T,
    cleanup: () -> Unit
): T {
    var primaryFailure: Throwable? = null
    try {
        return block()
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        var cleanupFailure: Throwable? = null
        try {
            cleanup()
        } catch (failure: Throwable) {
            cleanupFailure = failure
        }
        // A best-effort ordinary cleanup failure after a successful operation remains cleanup
        // debt; it must not fabricate a new operation failure. Fatal cleanup and cancellation
        // still escape even when there is no primary operation failure.
        if (primaryFailure != null ||
            cleanupFailure == null ||
            cleanupFailure is Error ||
            cleanupFailure is CancellationException
        ) {
            val combined = combineSettlementFailure(primaryFailure, cleanupFailure)
            if (combined !== primaryFailure) throw requireNotNull(combined)
        }
    }
}
