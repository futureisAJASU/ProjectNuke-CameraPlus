package com.projectnuke.keplernightlab

import java.util.concurrent.CancellationException
import org.junit.Assert.assertSame
import org.junit.Test

class FailurePrecedenceTest {
    @Test
    fun ordinaryPrimaryAndFatalCleanupSelectCleanupAndSuppressPrimary() {
        val primary = IllegalStateException("primary")
        val cleanup = AssertionError("cleanup")

        val selected = combineSettlementFailure(primary, cleanup)

        assertSame(cleanup, selected)
        assertSame(primary, cleanup.suppressed.single())
    }

    @Test
    fun fatalPrimaryAndFatalCleanupKeepPrimaryAndSuppressCleanup() {
        val primary = AssertionError("primary")
        val cleanup = LinkageError("cleanup")

        val selected = combineSettlementFailure(primary, cleanup)

        assertSame(primary, selected)
        assertSame(cleanup, primary.suppressed.single())
    }

    @Test
    fun cancellationAndFatalCleanupSelectCleanupAndSuppressCancellation() {
        val primary = CancellationException("cancelled")
        val cleanup = InternalError("cleanup")

        val selected = combineSettlementFailure(primary, cleanup)

        assertSame(cleanup, selected)
        assertSame(primary, cleanup.suppressed.single())
    }
}
