package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

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
            rawFusionOutcomeTerminalKind(localCommittedOutcome(), cancellationRequested = false)
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
}
