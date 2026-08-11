package com.projectnuke.keplernightlab

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the same production owner/coordinator used by CameraPreviewController. */
class ProductionPreviewLifecycleInvariantTest {
    @Test
    fun stopWhileStartingInvalidatesLateCameraAndSessionCallbacks() {
        val owner = PreviewGenerationOwner()
        val generation = owner.start()!!
        val stopGeneration = owner.stop()!!
        assertFalse(owner.accepts(generation))
        assertFalse(owner.markOpen(generation))
        assertTrue(owner.finishStop(stopGeneration))
        assertEquals(PreviewGenerationOwner.State.STOPPED, owner.snapshot().state)
    }

    @Test
    fun physicalFailureThenNormalSessionHasOneAuthoritativeOwner() {
        val slot = PreviewResourceSlot<Any>()
        val physical = Any()
        val normal = Any()
        var duplicateSettlements = 0
        assertEquals(PreviewResourceAttachment.ACCEPTED, slot.attach(9L, 9L, physical) { duplicateSettlements++ })
        assertTrue(slot.detach(9L, physical) === physical)
        assertEquals(PreviewResourceAttachment.ACCEPTED, slot.attach(9L, 9L, normal) { duplicateSettlements++ })
        assertEquals(0, duplicateSettlements)
        assertTrue(slot.detach(9L, normal) === normal)
    }

    @Test
    fun everyCleanupOperationIsAttemptedAfterFailures() {
        val attempts = AtomicInteger()
        val snapshot = settlePreviewResources(
            generation = 12L,
            operations = listOf(
                PreviewResourceOperation.STOP_REPEATING to {
                    attempts.incrementAndGet()
                    error("stopRepeating")
                },
                PreviewResourceOperation.CAPTURE_SESSION_CLOSE to {
                    attempts.incrementAndGet()
                    error("session close")
                },
                PreviewResourceOperation.CAMERA_DEVICE_CLOSE to { attempts.incrementAndGet() },
                PreviewResourceOperation.SURFACE_RELEASE to { attempts.incrementAndGet() },
                PreviewResourceOperation.HANDLER_THREAD_QUIT to { attempts.incrementAndGet() },
                PreviewResourceOperation.HANDLER_THREAD_TERMINATION to {
                    attempts.incrementAndGet()
                    error("THREAD_TERMINATION_TIMEOUT")
                }
            )
        )
        assertEquals(6, attempts.get())
        assertEquals(3, snapshot.failures.size)
        assertTrue(snapshot.failures.any { it.failure?.message == "THREAD_TERMINATION_TIMEOUT" })
    }

    @Test
    fun staleCommandAndCallbackRejectionHaveStructuredOutcomes() {
        assertFalse(
            acceptsPreviewCommand(
                currentGeneration = 4,
                active = true,
                command = PreviewCommand(3, PreviewCommandKind.ZOOM)
            )
        )
        val requested = PreviewCommandSnapshot(generation = 4, requestedZoomRatio = 3f)
        val rejected = requested.copy(lastOutcome = PreviewCommandApplyOutcome.DISPATCH_REJECTED)
        assertEquals(3f, rejected.requestedZoomRatio)
        assertEquals(null, rejected.appliedZoomRatio)
    }
}
