package com.projectnuke.keplernightlab

/** Test-only migration of the former drain helper onto the coordinated claim API. */
internal fun YuvBufferedLifecycle.drainRetainedForTest(): List<YuvPngWorkItem> =
    claimRetainedForDrain().map { claim ->
        check(finishDrain(claim.item))
        claim.item
    }
