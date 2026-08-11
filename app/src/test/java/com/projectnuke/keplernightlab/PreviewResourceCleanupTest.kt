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
}
