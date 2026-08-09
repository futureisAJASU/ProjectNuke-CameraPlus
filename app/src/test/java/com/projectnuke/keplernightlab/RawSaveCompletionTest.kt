package com.projectnuke.keplernightlab

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the immutable RAW save completion model.
 *
 * The save worker emits a structured [RawSaveCompletion] that the owner
 * adopts; the worker itself never mutates authoritative capture state. These
 * tests lock in:
 * - per-frame identity (frameIndex / timestampNs) is preserved on both branches,
 * - raw16 filename is mandatory on Success and absent on Failed (when no temp existed),
 * - DNG sidecar outcome is optional and structured,
 * - failure details (type / message / throwable) are preserved on Failed,
 * - a late completion (already after terminal) can be constructed deterministically.
 */
class RawSaveCompletionTest {

    @Test
    fun successCompletionCarriesRaw16FilenameAndDngSidecar() {
        val dng = RawDngSidecarOutcome.localSaved(frameIndex = 7, filename = "frame_07.dng")
        val completion = RawSaveCompletion.Success(
            frameIndex = 7,
            timestampNs = 12345L,
            raw16Filename = "frame_07.raw16",
            raw16Bytes = 12_582_912L,
            saveDurationMs = 84L,
            dngSidecar = dng
        )
        assertEquals(7, completion.frameIndex)
        assertEquals(12345L, completion.timestampNs)
        assertEquals("frame_07.raw16", completion.raw16Filename)
        assertEquals(12_582_912L, completion.raw16Bytes)
        assertEquals(84L, completion.saveDurationMs)
        assertTrue(completion.dngSidecar.isLocallySaved)
    }

    @Test
    fun successCompletionMayHaveNotRequestedDngSidecar() {
        val completion = RawSaveCompletion.Success(
            frameIndex = 0,
            timestampNs = 1000L,
            raw16Filename = "frame_00.raw16",
            raw16Bytes = 1024L,
            saveDurationMs = 12L,
            dngSidecar = RawDngSidecarOutcome.notRequested(frameIndex = 0)
        )
        assertEquals(RawDngSidecarStatus.NOT_REQUESTED, completion.dngSidecar.status)
        assertFalse(completion.dngSidecar.isLocallySaved)
    }

    @Test
    fun failedCompletionPreservesFailureMetadata() {
        val failure = IllegalStateException("raw16 encoder returned false")
        val completion = RawSaveCompletion.Failed(
            frameIndex = 3,
            timestampNs = 9999L,
            raw16TempFile = File("/tmp/frame_03.raw16.tmp"),
            failureType = "encode-failed",
            failureMessage = failure.message ?: "encode failed",
            throwable = failure
        )
        assertEquals(3, completion.frameIndex)
        assertEquals(9999L, completion.timestampNs)
        assertNotNull(completion.raw16TempFile)
        assertEquals("encode-failed", completion.failureType)
        assertEquals("raw16 encoder returned false", completion.failureMessage)
        assertEquals(failure, completion.throwable)
    }

    @Test
    fun failedCompletionWithoutTempFileIsRepresentable() {
        // The encoder may fail BEFORE creating any temp file: there is no
        // leftover candidate to clean up. The owner must still be able to
        // adopt the failure for accounting.
        val completion = RawSaveCompletion.Failed(
            frameIndex = 5,
            timestampNs = 5555L,
            raw16TempFile = null,
            failureType = "OutOfMemoryError",
            failureMessage = "Insufficient heap before file creation",
            throwable = null
        )
        assertNull(completion.raw16TempFile)
        assertEquals(null, completion.throwable)
    }

    @Test
    fun lateCompletionCanBeConstructedDeterministically() {
        // Late completion path: the worker finishes a frame after terminal
        // was already claimed. The owner publishes the orphan in
        // discardedLateCompletions; the completion itself is structurally
        // identical to a normal completion.
        val completion = RawSaveCompletion.Success(
            frameIndex = 11,
            timestampNs = 4242L,
            raw16Filename = "frame_11.raw16",
            raw16Bytes = 4096L,
            saveDurationMs = 50L,
            dngSidecar = RawDngSidecarOutcome.notRequested(11)
        )
        // Owner logic MUST be able to detect this is a late completion via
        // the terminal-state check; the completion type itself is unchanged.
        assertEquals(11, completion.frameIndex)
        assertEquals(4242L, completion.timestampNs)
    }

    @Test
    fun sealedInterfaceExhaustiveness() {
        // The sealed interface is intentionally closed: Success / Failed /
        // Abandoned exist, so a `when` over [RawSaveCompletion] is exhaustive
        // without an `else` branch.
        val values: List<RawSaveCompletion> = listOf(
            RawSaveCompletion.Success(0, 0L, "f.raw16", 0L, 0L, RawDngSidecarOutcome.notRequested(0)),
            RawSaveCompletion.Failed(0, 0L, null, "x", "x", null),
            RawSaveCompletion.Abandoned(0, 0L)
        )
        for (c in values) {
            val kind = when (c) {
                is RawSaveCompletion.Success -> "success"
                is RawSaveCompletion.Failed -> "failed"
                is RawSaveCompletion.Abandoned -> "abandoned"
            }
            assertTrue(kind in listOf("success", "failed", "abandoned"))
        }
    }
}
