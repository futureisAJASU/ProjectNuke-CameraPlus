package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class RawFatalBoundaryTest {
    private fun request() = NativeMergeRequest(
        frameInputs = emptyList(),
        exposureScales = FloatArray(0),
        frameWeights = FloatArray(0),
        width = 1,
        height = 1,
        cfa = 0,
        blackLevel = 0,
        whiteLevel = 1,
        referenceIndex = 0,
        mergedRawFile = File("merged.raw16"),
        alignmentFile = File("alignment.json")
    )

    private fun localCommittedOutcome(): RawFusionPublicExportOutcome =
        RawFusionPublicExportOutcome.UncommittedFailure(
            base = RawFusionProcessResult(
                success = false,
                mergedRawFile = File("merged.raw16"),
                mergedDngFile = null,
                previewPngFile = null,
                finalPngFile = File("final.png"),
                errorMessage = "public export failed",
                outputCommitted = true
            ),
            finalOutputFormat = FinalOutputFormat.JPEG,
            currentLocalPreview = null,
            currentLocalOutput = File("final.png"),
            currentError = "public export failed"
        )

    @Test
    fun nativeOrdinaryFailureKeepsExistingStatusContract() {
        try {
            rawFusionNativeMergeForTest = { throw IllegalStateException("native ordinary failure") }
            val status = runNativeRawMerge(request())
            assertEquals("ERROR: IllegalStateException: native ordinary failure", status)
        } finally {
            rawFusionNativeMergeForTest = null
        }
    }

    @Test
    fun nativeFatalFailurePropagatesInsteadOfBecomingStatusText() {
        val fatal = AssertionError("native fatal failure")
        try {
            rawFusionNativeMergeForTest = { throw fatal }
            try {
                runNativeRawMerge(request())
                throw AssertionError("fatal native failure was swallowed")
            } catch (failure: AssertionError) {
                assertSame(fatal, failure)
            }
        } finally {
            rawFusionNativeMergeForTest = null
        }
    }

    @Test
    fun localCommittedPublicExportFailureMapsToPartialTerminalKind() {
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            rawFusionOutcomeTerminalKind(
                localCommittedOutcome(),
                cancellationRequested = false,
                currentAttemptLocalResult = true
            )
        )
    }

    @Test
    fun currentClaimMapsNullOutcomeToPartialInsteadOfFailure() {
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            rawFusionOutcomeTerminalKind(
                outcome = null,
                cancellationRequested = false,
                currentAttemptLocalResult = true
            )
        )
    }

    @Test
    fun currentClaimMapsCancellationToPartialInsteadOfNoResultCancellation() {
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            rawFusionOutcomeTerminalKind(
                outcome = null,
                cancellationRequested = true,
                currentAttemptLocalResult = true
            )
        )
    }

    @Test
    fun exactMergedRawClaimRemainsUsableWhenRendererHasNoBitmap() {
        val directory = Files.createTempDirectory("raw-merged-claim-").toFile()
        try {
            val merged = File(directory, "merged.raw16").apply { writeBytes(byteArrayOf(1, 2)) }
            val outcome = RawFusionPublicExportOutcome.UncommittedFailure(
                base = RawFusionProcessResult(
                    success = false,
                    mergedRawFile = merged,
                    mergedDngFile = null,
                    previewPngFile = null,
                    finalPngFile = null,
                    errorMessage = "renderer failed",
                    outputCommitted = true
                ),
                finalOutputFormat = FinalOutputFormat.JPEG,
                currentLocalPreview = null,
                currentLocalOutput = merged,
                currentError = "renderer failed"
            )
            val policy = deriveRawFusionOutcomePolicy(outcome, cancellationRequested = false)
            assertTrue(policy.hasCurrentLocalResult)
            assertEquals("PARTIAL", policy.durablePipelineStage)
            assertEquals(CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL, policy.cameraTerminalKind)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun noCurrentClaimStillMapsCancellationToCancellation() {
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.CANCELLED,
            rawFusionOutcomeTerminalKind(
                outcome = null,
                cancellationRequested = true,
                currentAttemptLocalResult = false
            )
        )
    }

    @Test
    fun verifiedWarningMapsToPartialWhileKeepingPublicResult() {
        val export = GalleryExportResult(
            success = true,
            uriString = "content://media/warned",
            displayName = "warned.jpg",
            mimeType = "image/jpeg",
            fileSizeBytes = 1L,
            formatUsed = OutputFormat.JPEG,
            fallbackUsed = false,
            errorMessage = null
        )
        val outcome = RawFusionPublicExportOutcome.VerifiedSuccess(
            base = RawFusionProcessResult(true, null, null, null, null, null),
            finalOutputFormat = FinalOutputFormat.JPEG,
            export = export,
            sidecar = null,
            currentLocalPreview = null,
            currentLocalOutput = null,
            currentWarning = "sidecar warning"
        )
        val policy = deriveRawFusionOutcomePolicy(outcome, cancellationRequested = false)
        assertEquals("PARTIAL", policy.durablePipelineStage)
        assertEquals(CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL, policy.cameraTerminalKind)
        assertTrue(policy.publicCommitted)
        assertTrue(policy.publicVerified)
        assertEquals(ReprocessTerminalDisposition.COMMITTED_PARTIAL, outcome.disposition)
    }

    @Test
    fun verifiedWarningWriterPersistsPartialAndRetainsCommittedUri() {
        val directory = Files.createTempDirectory("raw-verified-warning-writer-").toFile()
        try {
            KeplerJobMetadata.write(directory, org.json.JSONObject()
                .put(ACTIVE_OPERATION_ID, "operation-current"))
            val export = GalleryExportResult(
                success = true,
                uriString = "content://media/warned-writer",
                displayName = "warned-writer.jpg",
                mimeType = "image/jpeg",
                fileSizeBytes = 1L,
                formatUsed = OutputFormat.JPEG,
                fallbackUsed = false,
                errorMessage = null
            )
            updateRawPublicExportOutcome(
                directory,
                RawFusionPublicExportOutcome.VerifiedSuccess(
                    base = RawFusionProcessResult(true, null, null, null, null, null),
                    finalOutputFormat = FinalOutputFormat.JPEG,
                    export = export,
                    sidecar = null,
                    currentLocalPreview = null,
                    currentLocalOutput = null,
                    currentWarning = "optional sidecar warning"
                )
            )

            val job = KeplerJobMetadata.read(directory)
            assertEquals("PARTIAL", job.getString("currentPipelineStage"))
            assertEquals("NIGHT_FUSION_COMPLETE_PARTIAL", job.getString("processStatus"))
            assertEquals("content://media/warned-writer", job.getString("exportUri"))
            assertEquals("operation-current", job.getString(TERMINAL_OPERATION_ID))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun verifiedWithoutWarningMapsToCanonicalComplete() {
        val export = GalleryExportResult(
            success = true,
            uriString = "content://media/complete",
            displayName = "complete.jpg",
            mimeType = "image/jpeg",
            fileSizeBytes = 1L,
            formatUsed = OutputFormat.JPEG,
            fallbackUsed = false,
            errorMessage = null
        )
        val outcome = RawFusionPublicExportOutcome.VerifiedSuccess(
            base = RawFusionProcessResult(true, null, null, null, null, null),
            finalOutputFormat = FinalOutputFormat.JPEG,
            export = export,
            sidecar = null,
            currentLocalPreview = null,
            currentLocalOutput = null
        )
        val policy = deriveRawFusionOutcomePolicy(outcome, cancellationRequested = false)
        assertEquals("COMPLETE", policy.durablePipelineStage)
        assertEquals(CameraPipelineEvent.Terminal.Kind.COMPLETE, policy.cameraTerminalKind)
    }

    @Test
    fun verifiedPendingPostWorkNeverMapsToComplete() {
        val export = GalleryExportResult(
            success = true,
            uriString = "content://media/pending",
            displayName = "pending.jpg",
            mimeType = "image/jpeg",
            fileSizeBytes = 1L,
            formatUsed = OutputFormat.JPEG,
            fallbackUsed = false,
            errorMessage = null
        )
        val outcome = RawFusionPublicExportOutcome.VerifiedPendingPostWork(
            base = RawFusionProcessResult(true, null, null, null, null, null),
            finalOutputFormat = FinalOutputFormat.JPEG,
            export = export,
            currentLocalPreview = null,
            currentLocalOutput = null
        )

        val policy = deriveRawFusionOutcomePolicy(outcome, cancellationRequested = false)

        assertEquals("PARTIAL", policy.durablePipelineStage)
        assertEquals(CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL, policy.cameraTerminalKind)
    }

    @Test
    fun missingCurrentLocalOutputDoesNotMapClaimFlagToPartial() {
        val outcome = RawFusionPublicExportOutcome.UncommittedFailure(
            base = RawFusionProcessResult(
                success = false,
                mergedRawFile = File("merged.raw16"),
                mergedDngFile = null,
                previewPngFile = null,
                finalPngFile = File("missing-final.png"),
                errorMessage = "local output disappeared",
                outputCommitted = true
            ),
            finalOutputFormat = FinalOutputFormat.JPEG,
            currentLocalPreview = null,
            currentLocalOutput = null,
            currentError = "local output disappeared"
        )

        assertEquals(
            CameraPipelineEvent.Terminal.Kind.FAILED,
            rawFusionOutcomeTerminalKind(outcome, cancellationRequested = false)
        )
    }

    @Test
    fun verifiedPostExportCancellationDoesNotMapToComplete() {
        val export = GalleryExportResult(
            success = true,
            uriString = "content://media/current",
            displayName = "current.jpg",
            mimeType = "image/jpeg",
            fileSizeBytes = 1L,
            formatUsed = OutputFormat.JPEG,
            fallbackUsed = false,
            errorMessage = null
        )
        val outcome = RawFusionPublicExportOutcome.VerifiedWithPostExportCancellation(
            base = RawFusionProcessResult(true, null, null, null, File("final.png"), null),
            finalOutputFormat = FinalOutputFormat.JPEG,
            export = export,
            sidecar = null,
            currentLocalPreview = null,
            currentLocalOutput = File("final.png")
        )
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            rawFusionOutcomeTerminalKind(outcome, cancellationRequested = true)
        )
        assertEquals(ReprocessTerminalDisposition.COMMITTED_PARTIAL, outcome.disposition)
    }

    @Test
    fun rawMetadataFatalContractPreservesOriginalFatal() {
        val fatal = OutOfMemoryError("metadata fatal")
        try {
            wrapMetadataIntegrityFailure(originalFailure = fatal) {
                throw IllegalStateException("ordinary metadata failure")
            }
            throw AssertionError("fatal metadata failure was wrapped")
        } catch (failure: OutOfMemoryError) {
            assertSame(fatal, failure)
        }
    }

    @Test
    fun rawMetadataFatalAfterOrdinaryFailurePreservesBothCauses() {
        val ordinary = IllegalStateException("processing failure")
        val fatal = AssertionError("metadata fatal")
        try {
            wrapMetadataIntegrityFailure(originalFailure = ordinary) {
                throw fatal
            }
            throw AssertionError("fatal metadata failure did not escape")
        } catch (failure: AssertionError) {
            assertSame(fatal, failure)
            assertTrue(failure.suppressed.any { it === ordinary })
        }
    }
}
