package com.projectnuke.keplernightlab

import android.media.FakeImage
import android.media.Image
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2A-P2 fail-closed adoption through the production session seam
 * ([YuvCaptureSession.create] with injectable candidate filesystem / candidate
 * verifier / final-file verifier).
 *
 * Every rejected adoption (missing / non-regular / unreadable candidate, duplicate
 * reservation, pre-existing final collision, commit failure, invalid final file)
 * must: never create an untracked final PNG, never overwrite a pre-existing final,
 * roll back the reservation, settle the candidate, record failedFrame, and leave
 * cleanup debt observable when file removal fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21])
class YuvAdoptionFilesystemTest {

    private class FakeDirectAccess : DirectYuvImageAccess {
        private var taken = false
        override fun timestampNs(): Long = 4321L
        override fun allocationBytes(): Long = 0L
        override fun copy(frameIndex: Int): BufferedYuvFrame = error("direct work does not copy")
        override fun release() {
            // Mirrors Camera2DirectYuvImageAccess: release after takeImage is a no-op.
            if (taken) return
            error("release before take")
        }
        override fun takeImage(): Image? {
            if (taken) error("takeImage called twice")
            taken = true
            return FakeImage()
        }
    }

    /**
     * Filesystem whose delete/quarantine both fail on existing files (mirroring
     * [RealYuvCandidateFilesystem]'s FILE_ABSENT handling for missing files):
     * cleanup debt stays observable.
     */
    private class FailingFilesystem : YuvCandidateFilesystem {
        override fun delete(candidate: File) =
            if (!candidate.exists()) CandidateFileOperationResult.FILE_ABSENT
            else CandidateFileOperationResult.DELETE_RETURNED_FALSE
        override fun quarantine(candidate: File) =
            if (!candidate.exists()) CandidateFileOperationResult.FILE_ABSENT
            else CandidateFileOperationResult.QUARANTINE_FAILED()
    }

    /**
     * Deterministic encode gating: the worker task signals start, blocks until
     * [release], then signals completion.  The test never sleeps.
     */
    private class EncodeGate {
        private val startLatch = CountDownLatch(1)
        private val releaseLatch = CountDownLatch(1)
        private val doneLatch = CountDownLatch(1)
        @Volatile private var started = false

        fun signalStartAndBlock() {
            if (!started) { started = true; startLatch.countDown() }
            releaseLatch.await(10, TimeUnit.SECONDS)
        }

        fun awaitStart(timeoutSec: Long = 5) {
            assertTrue("encode never started", startLatch.await(timeoutSec, TimeUnit.SECONDS))
        }

        fun release() { releaseLatch.countDown() }

        fun awaitDone(timeoutSec: Long = 5) {
            assertTrue("encode never finished", doneLatch.await(timeoutSec, TimeUnit.SECONDS))
        }

        fun signalDone() { doneLatch.countDown() }
    }

    private class Harness(
        val frameCount: Int = 1,
        private val filesystem: YuvCandidateFilesystem = RealYuvCandidateFilesystem,
        private val candidateVerifier: YuvCandidateVerifier = RealYuvCandidateVerifier,
        private val finalFileVerifier: YuvFinalFileVerifier = RealYuvFinalFileVerifier,
        private val committerFailure: Throwable? = null,
        private val committerOverride: YuvCandidateCommitter? = null,
        private val accounting: YuvCaptureAccounting = YuvCaptureAccounting(),
        private val encodeBody: (File) -> Unit = { candidate -> Files.write(candidate.toPath(), PNG_1X1) }
    ) {
        val dir: File = Files.createTempDirectory("yuv-adopt-test").toFile()
        val handlerThread = android.os.HandlerThread("yuv-adopt-test").apply { start() }
        val handler = android.os.Handler(handlerThread.looper)
        val gate = EncodeGate()
        private var posted = 0

        /**
         * When true, the SECOND owner dispatch (the completion post from the worker)
         * is rejected through disposeWithoutMutation, exercising the owner-event
         * emergency settlement path.  Set on the instance before acceptDirectAndSettle.
         *
         * Kept as a mutable field rather than a constructor parameter so that Gradle's
         * --tests wildcard matches Harness only by name; since Harness has no @Test
         * methods of its own and (with all-default constructor parameters) a synthetic
         * no-arg constructor, JUnit's discovery skips it as a non-test class.  Promoting
         * it to a constructor parameter caused Gradle to additionally select
         * YuvAdoptionFilesystemTest$Harness and surface JUnit's
         * "Test class can only have one constructor" initializationError.
         */
        var rejectCompletionDispatch = false

        val terminalLatch = CountDownLatch(1)
        val onCaptureErrorCount = AtomicInteger(0)
        val errorMessage = AtomicReference<String?>()

        val session: YuvCaptureSession = YuvCaptureSession.create(
            // Synchronous dispatch: with a single direct frame, the owner event and
            // the completion event both run inside the worker task, so the adoption
            // result is fully deterministic once the worker terminates.  With
            // rejectCompletionDispatch the FIRST event runs and the SECOND (the
            // completion post) is rejected through disposeWithoutMutation, exercising
            // the owner-event emergency settlement path.
            dispatch = { event ->
                if (rejectCompletionDispatch && ++posted == 2) {
                    event.disposeWithoutMutation()
                    false
                } else {
                    event.execute()
                    true
                }
            },
            outputDir = dir,
            frameCount = frameCount,
            rotationDegrees = 0,
            workerCapacity = 4,
            maxRetainedBytes = 16L * 1024 * 1024,
            workProcessor = YuvPngWorkProcessor(
                encoder = object : YuvPngEncoder {
                    override fun encodeDirect(image: Image, candidate: File, rotationDegrees: Int) {
                        gate.signalStartAndBlock()
                        encodeBody(candidate)
                        gate.signalDone()
                    }
                    override fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int) {
                        gate.signalStartAndBlock()
                        encodeBody(candidate)
                        gate.signalDone()
                    }
                },
                committer = committerOverride ?: YuvCandidateCommitter { candidate, final ->
                    if (committerFailure != null) throw committerFailure
                    Files.move(candidate.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
            ),
            dispatchCallback = CallbackDispatcher { runnable -> if (!handler.post(runnable)) runnable.run(); true },
            writeJobJson = { status, _, _, _, _, _, _, _, _, _, _ ->
                if (status in TERMINAL_JOB_STATUSES) terminalLatch.countDown()
            },
            onCaptureError = { msg, _ ->
                onCaptureErrorCount.incrementAndGet()
                errorMessage.set(msg)
            },
            candidateFilesystem = filesystem,
            candidateVerifier = candidateVerifier,
            finalFileVerifier = finalFileVerifier,
            accounting = accounting,
            productionResourceCoordinator = YuvProductionResourceCoordinator(
                timeoutScheduler = null,
                backgroundHandler = null,
                backgroundThread = null
            )
        )

        fun finalFile(frame: Int = 0): File = File(dir, "frame_%02d_color.png".format(frame))

        /**
         * Accept a direct frame and deterministically wait until the worker fully
         * finished.  The synchronous dispatch runs the adoption (including any
         * terminal settlement and its shutdownNow) inside the worker task, so
         * close() + awaitTermination is the drain-safe completion barrier; the
         * final flushHandler processes any postMainOrRun callbacks.
         */
        fun acceptDirectAndSettle(timeoutSec: Long = 5) {
            session.owner.acceptDirect(FakeDirectAccess())
            gate.awaitStart(timeoutSec)
            gate.release()
            gate.awaitDone(timeoutSec)
            session.boundedWorker.close()
            assertTrue(session.boundedWorker.awaitTermination(5_000L))
            flushHandler()
        }

        fun awaitTerminal(timeoutSec: Long = 10): CaptureTerminalStatus {
            assertTrue("terminal not reached", terminalLatch.await(timeoutSec, TimeUnit.SECONDS))
            flushHandler()
            return session.terminalState.status()
        }

        fun flushHandler() {
            val latch = CountDownLatch(1)
            assertTrue("handler flush did not complete", handler.post { latch.countDown() })
            assertTrue(latch.await(2, TimeUnit.SECONDS))
        }

        fun leftoverTmpFiles(): List<String> =
            dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.map { it.name } ?: emptyList()

        fun shutdown() {
            session.close()
            handlerThread.quitSafely()
        }

        companion object {
            private val TERMINAL_JOB_STATUSES = setOf(
                "CAPTURE_COMPLETE", "CAPTURE_PARTIAL", "CAPTURE_FAILED", "CAPTURE_TIMEOUT", "CAPTURE_CANCELLED"
            )
        }
    }

    // ------------------------------------------------------------------
    // Item 1: post-COMMITTED manifest/final consistency
    // ------------------------------------------------------------------

    @Test
    fun postCommittedManifestFinalConsistency() {
        val harness = Harness()
        try {
            harness.acceptDirectAndSettle()
            val snap = harness.session.accounting.snapshot()
            // Item 1: persistedFrames == manifest.size invariant
            assertEquals(snap.persistedFrames, snap.manifest.size)
            // Item 1: every manifest entry has a readable final file
            for (entry in snap.manifest) {
                val finalFile = harness.finalFile(entry.frameIndex)
                assertTrue("final file missing for frame ${entry.frameIndex}", finalFile.exists())
                assertTrue("final file not readable for frame ${entry.frameIndex}", finalFile.canRead())
            }
            // Item 1: no committed manifest references a deleted final
            assertEquals(1, snap.persistedFrames)
            assertEquals(1, snap.manifest.size)
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Fail-closed candidate validation
    // ------------------------------------------------------------------

    @Test
    fun missingCandidateIsRejectedFailClosed() {
        // The encoder never creates the candidate file: a Success completion still
        // carries a handle, and the real verifier rejects the absent file.
        val harness = Harness(encodeBody = { _ -> })
        try {
            harness.acceptDirectAndSettle()
            val snap = harness.session.accounting.snapshot()
            assertEquals(1, snap.failedFrames)
            assertEquals(0, snap.persistedFrames)
            assertTrue(snap.manifest.isEmpty())
            assertFalse(harness.finalFile().exists())
            assertTrue(harness.leftoverTmpFiles().isEmpty())
            assertEquals(CaptureTerminalStatus.ACTIVE, harness.session.terminalState.status())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun nonRegularCandidateIsRejectedFailClosed() {
        // The encoder creates a DIRECTORY at the candidate path: the real verifier
        // requires a regular file and rejects it.
        val harness = Harness(encodeBody = { candidate -> Files.createDirectory(candidate.toPath()) })
        try {
            harness.acceptDirectAndSettle()
            val snap = harness.session.accounting.snapshot()
            assertEquals(1, snap.failedFrames)
            assertEquals(0, snap.persistedFrames)
            assertTrue(snap.manifest.isEmpty())
            assertFalse(harness.finalFile().exists())
            assertTrue(harness.leftoverTmpFiles().isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun unreadableCandidateIsRejectedFailClosed() {
        // The verifier seam deterministically simulates the unreadable state (JVM
        // canRead is not reliably false-able on Windows).
        val harness = Harness(candidateVerifier = YuvCandidateVerifier { _, _ -> false })
        try {
            harness.acceptDirectAndSettle()
            val snap = harness.session.accounting.snapshot()
            assertEquals(1, snap.failedFrames)
            assertEquals(0, snap.persistedFrames)
            assertTrue(snap.manifest.isEmpty())
            assertFalse(harness.finalFile().exists())
            assertTrue(harness.leftoverTmpFiles().isEmpty())
            assertTrue(harness.session.owner.candidateCleanupDebt().isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Collision, commit, and final-verification fail-closed paths
    // ------------------------------------------------------------------

    @Test
    fun collisionPreservesPreExistingFinalFile() {
        val harness = Harness()
        try {
            val existing = harness.finalFile()
            Files.write(existing.toPath(), "pre-existing".toByteArray())

            harness.acceptDirectAndSettle()

            val snap = harness.session.accounting.snapshot()
            assertEquals(1, snap.failedFrames)
            assertEquals(0, snap.persistedFrames)
            assertTrue(snap.manifest.isEmpty())
            // The pre-existing final file is preserved byte-for-byte, never overwritten.
            assertEquals("pre-existing", Files.readAllBytes(existing.toPath()).toString(Charsets.UTF_8))
            assertTrue(harness.leftoverTmpFiles().isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun commitExceptionRollsBackAndFailsCapture() {
        val harness = Harness(committerFailure = IllegalStateException("commit boom"))
        try {
            harness.acceptDirectAndSettle()
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.FAILED, status)
            val snap = harness.session.accounting.snapshot()
            assertEquals(1, snap.failedFrames)
            assertEquals(0, snap.persistedFrames)
            assertTrue(snap.manifest.isEmpty())
            assertFalse(harness.finalFile().exists())
            assertTrue(harness.leftoverTmpFiles().isEmpty())
            assertEquals(1, harness.onCaptureErrorCount.get())
            assertNotNull(harness.errorMessage.get())
            assertTrue(harness.errorMessage.get()!!.contains("YUV commit failed for frame 0"))
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun committerMovedFileThenThrewLeavesNoUntrackedFinal() {
        // The committer moves the candidate to the final path and THEN throws: the
        // new final file is untracked (never added to the manifest) and must be
        // removed, the reservation rolled back, and the candidate settled.
        val harness = Harness(
            committerOverride = YuvCandidateCommitter { candidate, final ->
                Files.move(candidate.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
                throw IllegalStateException("commit boom after move")
            }
        )
        try {
            harness.acceptDirectAndSettle()
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.FAILED, status)
            val snap = harness.session.accounting.snapshot()
            assertEquals(1, snap.failedFrames)
            assertEquals(0, snap.persistedFrames)
            assertTrue(snap.manifest.isEmpty())
            assertEquals(0, snap.reservedIndexCount)
            assertEquals(0, snap.reservedFilenameCount)
            // The untracked final must have been removed; no candidate remains either.
            assertFalse(harness.finalFile().exists())
            assertTrue(harness.leftoverTmpFiles().isEmpty())
            assertTrue(harness.session.owner.candidateCleanupDebt().isEmpty())
            assertNotNull(harness.errorMessage.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun tokenCommitFailureRemovesUntrackedFinalAndReleasesReservation() {
        // The accounting commit (manifest mutation) throws after the file was
        // committed: the final is untracked, so the owner must remove it, release
        // the reservation, settle the candidate, and record failedFrame.
        val boom = IllegalStateException("manifest commit boom")
        val failingAccounting = object : YuvCaptureAccounting() {
            override fun commitAdoption(token: AdoptionToken): Boolean = throw boom
        }
        val harness = Harness(accounting = failingAccounting)
        try {
            harness.acceptDirectAndSettle()
            val snap = harness.session.accounting.snapshot()
            assertEquals(1, snap.failedFrames)
            assertEquals(0, snap.persistedFrames)
            assertTrue(snap.manifest.isEmpty())
            assertEquals(0, snap.reservedIndexCount)
            assertEquals(0, snap.reservedFilenameCount)
            assertFalse(harness.finalFile().exists())
            assertTrue(harness.leftoverTmpFiles().isEmpty())
            assertTrue(harness.session.owner.candidateCleanupDebt().isEmpty())
            assertEquals(1, harness.onCaptureErrorCount.get())
            assertNotNull(harness.errorMessage.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun invalidFinalVerificationRemovesOnlyNewlyCreatedFile() {
        val harness = Harness(finalFileVerifier = YuvFinalFileVerifier { _, _ -> false })
        try {
            harness.acceptDirectAndSettle()
            val snap = harness.session.accounting.snapshot()
            assertEquals(1, snap.failedFrames)
            assertEquals(0, snap.persistedFrames)
            assertTrue(snap.manifest.isEmpty())
            // Only the newly created invalid final is removed; nothing is left behind.
            assertFalse(harness.finalFile().exists())
            assertTrue(harness.leftoverTmpFiles().isEmpty())
            assertTrue(harness.session.owner.candidateCleanupDebt().isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun duplicateCompletionCannotOverwriteAdoptedFinal() {
        // frameCount=2: the seeded adopted entry (persistedFrames=1) must NOT trip the
        // terminal — the duplicate completion for frame 0 is then rejected as late.
        val harness = Harness(frameCount = 2)
        try {
            // Seed an already-adopted final + manifest entry for frame 0 BEFORE the
            // duplicate completion arrives.
            val adopted = harness.finalFile()
            Files.write(adopted.toPath(), "adopted".toByteArray())
            harness.session.accounting.persistedFrame(
                YuvFrameManifestEntry(0, "frame_00_color.png", 4321L, true)
            )

            harness.acceptDirectAndSettle()

            val snap = harness.session.accounting.snapshot()
            assertEquals(1, snap.failedFrames)
            assertEquals(1, snap.persistedFrames)
            assertEquals(1, snap.manifest.size)
            // The adopted final file is byte-for-byte untouched.
            assertEquals("adopted", Files.readAllBytes(adopted.toPath()).toString(Charsets.UTF_8))
            assertTrue(harness.leftoverTmpFiles().isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Cleanup debt observability
    // ------------------------------------------------------------------

    @Test
    fun candidateCleanupFailureRecordsObservableDebt() {
        val harness = Harness(
            filesystem = FailingFilesystem(),
            candidateVerifier = YuvCandidateVerifier { _, _ -> false }
        )
        try {
            harness.acceptDirectAndSettle()
            val debts = harness.session.owner.candidateCleanupDebt()
            assertEquals(1, debts.size)
            assertTrue(debts[0].contains("candidate cleanup debt"))
            assertTrue(debts[0].contains("frame=0"))
            assertTrue(debts[0].contains("quarantine=QUARANTINE_FAILED"))
            // The file removal failed: the candidate file remains on disk (the debt).
            assertTrue(harness.leftoverTmpFiles().isNotEmpty())
            val snap = harness.session.accounting.snapshot()
            assertEquals(1, snap.failedFrames)
            assertFalse(harness.finalFile().exists())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun finalCleanupFailureRecordsObservableDebt() {
        val harness = Harness(
            filesystem = FailingFilesystem(),
            finalFileVerifier = YuvFinalFileVerifier { _, _ -> false }
        )
        try {
            harness.acceptDirectAndSettle()
            val debts = harness.session.owner.candidateCleanupDebt()
            assertEquals(1, debts.size)
            assertTrue(debts[0].contains("final-file cleanup debt"))
            assertTrue(debts[0].contains("delete=DELETE_RETURNED_FALSE"))
            assertTrue(debts[0].contains("quarantine=QUARANTINE_FAILED"))
            // The invalid final file could not be removed: it remains on disk.
            assertTrue(harness.finalFile().exists())
            val snap = harness.session.accounting.snapshot()
            assertEquals(1, snap.failedFrames)
            assertEquals(0, snap.persistedFrames)
            assertTrue(snap.manifest.isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Owner-event rejection: every rejected completion records cleanup debt
    // ------------------------------------------------------------------

    @Test
    fun ownerRejectedSuccessCompletionRecordsCleanupDebtForPartialCandidate() {
        // The completion post is rejected: the emergency settlement path must settle
        // the partial candidate and keep the cleanup failure observable.
        val harness = Harness(filesystem = FailingFilesystem())
        try {
            harness.rejectCompletionDispatch = true
            harness.acceptDirectAndSettle()
            val debts = harness.session.owner.candidateCleanupDebt()
            assertEquals(1, debts.size)
            assertTrue(debts[0].contains("candidate cleanup debt"))
            assertTrue(debts[0].contains("frame=0"))
            assertTrue(debts[0].contains("quarantine=QUARANTINE_FAILED"))
            // The candidate file could not be removed: the debt stays observable.
            assertTrue(harness.leftoverTmpFiles().isNotEmpty())
            assertEquals(0, harness.session.accounting.snapshot().persistedFrames)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun ownerRejectedFailedCompletionRecordsCleanupDebtForPartialCandidate() {
        // The completion post carries a Failed status (the committer threw) while
        // the candidate file was already written by a successful encode.  Rejecting
        // the completion post exercises the emergency settlement path on a Failed
        // event: the partial candidate cannot be removed (FailingFilesystem) so the
        // cleanup debt stays observable.
        val harness = Harness(
            filesystem = FailingFilesystem(),
            committerFailure = IllegalStateException("commit boom")
        )
        try {
            harness.rejectCompletionDispatch = true
            harness.acceptDirectAndSettle()
            val debts = harness.session.owner.candidateCleanupDebt()
            assertEquals(1, debts.size)
            assertTrue(debts[0].contains("candidate cleanup debt"))
            assertTrue(debts[0].contains("frame=0"))
            assertTrue(harness.leftoverTmpFiles().isNotEmpty())
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Owner rollback recovery: every rollback failure is recorded
    // ------------------------------------------------------------------

    /** Accounting whose rollback always throws: the owner's recovery must not drop it. */
    private fun throwingRollbackAccounting(): YuvCaptureAccounting = object : YuvCaptureAccounting() {
        override fun rollbackAdoption(token: AdoptionToken): Boolean =
            throw IllegalStateException("rollback boom")
    }

    private fun assertRollbackRecoveryDebt(debts: List<String>) {
        assertTrue("rollback recovery debt missing: $debts", debts.any { it.contains("adoption rollback recovery frame=0") })
        assertTrue(
            "recovery failure must be preserved: $debts",
            debts.any { it.contains("rollbackReturnedSuccess=false") }
        )
        assertTrue("still-held reservations must be reported: $debts", debts.any { it.contains("released=false") })
        assertTrue("remaining reservations must be visible: $debts", debts.any { it.contains("remainingIndex=true") })
    }

    @Test
    fun preExistingFinalCollisionRollbackFailureIsRecorded() {
        val harness = Harness(accounting = throwingRollbackAccounting())
        try {
            val existing = harness.finalFile()
            Files.write(existing.toPath(), "pre-existing".toByteArray())

            harness.acceptDirectAndSettle()

            assertRollbackRecoveryDebt(harness.session.owner.candidateCleanupDebt())
            // The pre-existing final file is still preserved byte-for-byte.
            assertEquals("pre-existing", Files.readAllBytes(existing.toPath()).toString(Charsets.UTF_8))
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun committerFailureRollbackFailureIsRecorded() {
        val harness = Harness(
            accounting = throwingRollbackAccounting(),
            committerFailure = IllegalStateException("commit boom")
        )
        try {
            harness.acceptDirectAndSettle()
            assertRollbackRecoveryDebt(harness.session.owner.candidateCleanupDebt())
            assertFalse(harness.finalFile().exists())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun finalVerifierFailureRollbackFailureIsRecorded() {
        val harness = Harness(
            accounting = throwingRollbackAccounting(),
            finalFileVerifier = YuvFinalFileVerifier { _, _ -> false }
        )
        try {
            harness.acceptDirectAndSettle()
            assertRollbackRecoveryDebt(harness.session.owner.candidateCleanupDebt())
            assertFalse(harness.finalFile().exists())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun tokenCommitFailureRollbackFailureIsRecorded() {
        // The accounting commit returns FALSE (no throwable): the token publishes a
        // diagnostic, and the owner's recovery still records the throwing rollback.
        val harness = Harness(
            accounting = object : YuvCaptureAccounting() {
                override fun commitAdoption(token: AdoptionToken): Boolean = false
                override fun rollbackAdoption(token: AdoptionToken): Boolean =
                    throw IllegalStateException("rollback boom")
            }
        )
        try {
            harness.acceptDirectAndSettle()
            assertRollbackRecoveryDebt(harness.session.owner.candidateCleanupDebt())
            assertFalse(harness.finalFile().exists())
            assertTrue(harness.leftoverTmpFiles().isEmpty())
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Item 1: post-COMMITTED consistency — token COMMITTED => manifest entry
    // exists => persistedFrames includes entry => the committed manifest entry
    // survives a subsequent candidate-settlement failure (the candidate handle
    // is settled independently; the manifest is owned by the accounting).
    // ------------------------------------------------------------------

    @Test
    fun tokenCommittedThenCandidateSettlementFailsPreservesManifest() {
        val accounting = YuvCaptureAccounting()
        val entry = YuvFrameManifestEntry(0, "frame_00_color.png", 4321L, true)
        val token = accounting.tryReserveAdoption(entry)!!

        assertTrue(token.commit())
        assertEquals(AdoptionTokenState.COMMITTED, token.state())

        val snap1 = accounting.snapshot()
        assertEquals(1, snap1.persistedFrames)
        assertEquals(1, snap1.manifest.size)
        assertEquals(0, snap1.failedFrames)

        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        val claim = handle.tryBeginAdoption()!!
        handle.abortAdoption(claim, RealYuvCandidateFilesystem)

        val snap2 = accounting.snapshot()
        assertEquals(1, snap2.persistedFrames)
        assertEquals(1, snap2.manifest.size)
        assertEquals(0, snap2.failedFrames)
    }

    companion object {
        private val PNG_1X1: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    }
}
