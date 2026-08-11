package com.projectnuke.keplernightlab

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewResourceCleanupTest {
    @Test
    fun oneFailureDoesNotSkipLaterReleases() {
        val attempted = AtomicInteger()
        val snapshot = settlePreviewResources(
            generation = 7L,
            operations = PreviewResourceOperation.entries.map { operation ->
                operation to {
                    attempted.incrementAndGet()
                    if (operation == PreviewResourceOperation.STOP_REPEATING) error("stop")
                }
            }
        )
        assertEquals(PreviewResourceOperation.entries.size, attempted.get())
        assertEquals(1, snapshot.failures.size)
        assertEquals(7L, snapshot.failures.single().generation)
    }

    @Test
    fun allReleaseOutcomesAreRecorded() {
        val snapshot = settlePreviewResources(
            generation = 9L,
            operations = listOf(
                PreviewResourceOperation.CAPTURE_SESSION_CLOSE to { error("session") },
                PreviewResourceOperation.CAMERA_DEVICE_CLOSE to { error("camera") },
                PreviewResourceOperation.SURFACE_RELEASE to {}
            )
        )
        assertEquals(3, snapshot.records.size)
        assertTrue(snapshot.records.any { it.operation == PreviewResourceOperation.SURFACE_RELEASE && it.succeeded })
        assertEquals(2, snapshot.failures.size)
    }

    @Test
    fun cleanupAccumulatorRetainsEarlyCameraFailuresAfterThreadSettlement() {
        val accumulator = PreviewCleanupAccumulator()
        val first = settlePreviewResources(
            generation = 12L,
            operations = listOf(
                PreviewResourceOperation.STOP_REPEATING to { error("stopRepeating") },
                PreviewResourceOperation.CAPTURE_SESSION_CLOSE to { error("session close") }
            )
        )
        accumulator.record(first, late = false)
        val final = accumulator.record(
            settlePreviewResources(
                generation = 12L,
                operations = listOf(
                    PreviewResourceOperation.CAMERA_DEVICE_CLOSE to {},
                    PreviewResourceOperation.HANDLER_THREAD_QUIT to {},
                    PreviewResourceOperation.HANDLER_THREAD_TERMINATION to {}
                )
            ),
            late = false
        )

        assertEquals(5, final.records.size)
        assertEquals(
            setOf(
                PreviewResourceOperation.STOP_REPEATING,
                PreviewResourceOperation.CAPTURE_SESSION_CLOSE
            ),
            final.failures.map { it.operation }.toSet()
        )
    }

    @Test
    fun lateSettlementDoesNotReplaceCurrentGenerationSnapshot() {
        val accumulator = PreviewCleanupAccumulator()
        val current = settlePreviewResources(
            generation = 4L,
            operations = listOf(PreviewResourceOperation.CAMERA_DEVICE_CLOSE to {})
        )
        accumulator.record(current, late = false)
        val late = settlePreviewResources(
            generation = 3L,
            operations = listOf(PreviewResourceOperation.CAPTURE_SESSION_CLOSE to { error("late") })
        )
        accumulator.record(late, late = true)
        assertEquals(listOf(PreviewResourceOperation.CAMERA_DEVICE_CLOSE), current.records.map { it.operation })
    }
}
