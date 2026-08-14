package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PublicExportTerminalTruthTest {
    @Test
    fun nightFusionCommittedAndVerifiedInterruptionPublishesPartialTruth() {
        val evidence = OwnedPublicExportEvidence("night", committed = true, verified = true, uri = "content://night")
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            publicExportInterruptionTerminalKind(evidence, cancellationRequested = true)
        )
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            publicExportInterruptionTerminalKind(evidence, cancellationRequested = false)
        )
    }

    @Test
    fun superResolutionCommittedButUnverifiedInterruptionPublishesPartialTruth() {
        val evidence = OwnedPublicExportEvidence("super-resolution", committed = true, verified = false, uri = "content://sr")
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            publicExportInterruptionTerminalKind(evidence, cancellationRequested = true)
        )
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            publicExportInterruptionTerminalKind(evidence, cancellationRequested = false)
        )
    }

    @Test
    fun preCommitInterruptionPreservesCancellationAndFailureKinds() {
        val evidence = OwnedPublicExportEvidence("pre-commit", committed = false, verified = false, uri = null)
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.CANCELLED,
            publicExportInterruptionTerminalKind(evidence, cancellationRequested = true)
        )
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.FAILED,
            publicExportInterruptionTerminalKind(evidence, cancellationRequested = false)
        )
    }

    @Test
    fun completedCurrentExportFallbackRemainsPartialWhenOwnerWasAlreadyCleared() {
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            publicExportInterruptionTerminalKind(
                evidence = null,
                cancellationRequested = false,
                committedFallback = true
            )
        )
    }

    @Test
    fun committedLocalOutputKeepsCancellationAndFailureAsPartialTruth() {
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            publicExportInterruptionTerminalKind(
                evidence = OwnedPublicExportEvidence("local", committed = false, verified = false, uri = null),
                cancellationRequested = true,
                requiredOutputCommitted = true
            )
        )
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
            publicExportInterruptionTerminalKind(
                evidence = OwnedPublicExportEvidence("local", committed = false, verified = false, uri = null),
                cancellationRequested = false,
                requiredOutputCommitted = true
            )
        )
    }

    @Test
    fun noCommittedOutputKeepsPreCommitCancellationAndFailureDistinct() {
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.CANCELLED,
            publicExportInterruptionTerminalKind(
                evidence = null,
                cancellationRequested = true,
                requiredOutputCommitted = false
            )
        )
        assertEquals(
            CameraPipelineEvent.Terminal.Kind.FAILED,
            publicExportInterruptionTerminalKind(
                evidence = null,
                cancellationRequested = false,
                requiredOutputCommitted = false
            )
        )
    }
}
