package com.projectnuke.keplernightlab

/** Exact attachment result for one resource slot in one preview generation. */
internal enum class PreviewResourceAttachment {
    ACCEPTED,
    ALREADY_OWNED,
    SETTLED_DUPLICATE,
    SETTLED_STALE
}

/**
 * Production slot fence. The slot never replaces an accepted instance and never asks a caller
 * to settle the accepted instance through a late-resource path.
 */
internal class PreviewResourceSlot<T> {
    private var generation: Long? = null
    private var resource: T? = null

    @Synchronized
    fun attach(
        expectedGeneration: Long,
        currentGeneration: Long,
        supplied: T,
        settleSupplied: (T) -> Unit
    ): PreviewResourceAttachment {
        if (expectedGeneration != currentGeneration) {
            settleSupplied(supplied)
            return PreviewResourceAttachment.SETTLED_STALE
        }
        val owned = resource
        if (owned != null) {
            if (owned === supplied) return PreviewResourceAttachment.ALREADY_OWNED
            settleSupplied(supplied)
            return PreviewResourceAttachment.SETTLED_DUPLICATE
        }
        generation = expectedGeneration
        resource = supplied
        return PreviewResourceAttachment.ACCEPTED
    }

    @Synchronized
    fun detach(expectedGeneration: Long, expected: T): T? {
        if (generation != expectedGeneration || resource !== expected) return null
        generation = null
        resource = null
        return expected
    }

    @Synchronized
    fun clear(expectedGeneration: Long): T? {
        if (generation != expectedGeneration) return null
        val owned = resource
        generation = null
        resource = null
        return owned
    }

    @Synchronized
    fun peek(expectedGeneration: Long): T? =
        if (generation == expectedGeneration) resource else null
}
