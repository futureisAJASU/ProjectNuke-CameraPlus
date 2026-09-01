package com.projectnuke.keplernightlab

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * R4.2 fail-closed terminal-stable MAIN reconstruction suppression tests.
 *
 * Proves the strict [isModernTerminallySettledMainExport] predicate both directly (every
 * fail-closed counterexample) and end-to-end through [KeplerRecoveryCoordinator.recoverRoots]
 * (zero-write eligibility, missing/blank recoveryState regression, authoritative jobDir, and
 * downstream processing-artifact safety).
 */
@RunWith(RobolectricTestRunner::class)
class KeplerR4TerminalStableSuppressionTest {

    private class VerifiedAccess : MediaStoreExportRecoveryAccess {
        var inspections = 0
        override fun inspect(uri: Uri, journal: MediaStoreExportJournal): MediaStoreExportInspection {
            inspections++
            return MediaStoreExportInspection(exists = true, pending = false, verified = true)
        }
        override fun setPending(uri: Uri, pending: Boolean) = true
        override fun delete(uri: Uri) = true
    }

    private fun modernMainJournal(
        uri: String = URI,
        role: MediaStoreExportRole = MediaStoreExportRole.MAIN_IMAGE,
        state: MediaStoreExportState = MediaStoreExportState.VERIFIED,
        terminalMetadataPersisted: Boolean = true,
        ownerOperationId: String = OP,
        terminalOperationId: String = OP,
        displayName: String = DISPLAY_NAME,
        mimeType: String = MIME,
        exportAttemptId: String = "attempt-1"
    ): MediaStoreExportJournal = MediaStoreExportJournal(
        exportAttemptId = exportAttemptId,
        runtimeSessionId = "runtime-r4",
        jobIdentity = "job",
        role = role,
        frameIndex = null,
        displayName = displayName,
        relativePath = "Pictures/Kepler",
        mimeType = mimeType,
        collectionUri = "content://media/external/images/media",
        uri = uri,
        expectedSizeBytes = null,
        expectedSha256 = null,
        expectedWidth = null,
        expectedHeight = null,
        ownerOperationId = ownerOperationId,
        ownerRuntimeSessionId = "runtime-r4",
        terminalMetadataPersisted = terminalMetadataPersisted,
        terminalMetadataPersistedAt = if (terminalMetadataPersisted) 1L else null,
        terminalOperationId = terminalOperationId,
        state = state,
        createdAt = 1L,
        updatedAt = 2L
    )

    private fun eligibleJobJson(): JSONObject = JSONObject()
        .put("jobType", "YUV_NIGHT_FUSION")
        .put("currentPipelineStage", "COMPLETE")
        .put("galleryExportCommitted", true)
        .put("exportVerified", true)
        .put("exportUri", URI)
        .put("galleryPublicExportLinkage", URI)
        .put("exportDisplayName", DISPLAY_NAME)
        .put("exportMimeType", MIME)
        .put("recoveryState", "STABLE")
        .put(TERMINAL_OPERATION_ID, OP)

    private fun eligibleResult(): MediaStoreExportRecoveryResult =
        MediaStoreExportRecoveryResult("attempt-1", MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED)

    private fun assertEligible(
        job: JSONObject = eligibleJobJson(),
        journal: MediaStoreExportJournal? = modernMainJournal(),
        result: MediaStoreExportRecoveryResult? = eligibleResult(),
        terminalOperationId: String = OP,
        journals: List<MediaStoreExportJournal> = listOf(modernMainJournal()),
        jobDir: File = File("r4-yuv-helper")
    ) {
        assertEquals(
            "eligibility",
            true,
            isModernTerminallySettledMainExport(job, journal, result, terminalOperationId, journals, jobDir)
        )
    }

    private fun assertIneligible(
        job: JSONObject = eligibleJobJson(),
        journal: MediaStoreExportJournal? = modernMainJournal(),
        result: MediaStoreExportRecoveryResult? = eligibleResult(),
        terminalOperationId: String = OP,
        journals: List<MediaStoreExportJournal> = listOf(modernMainJournal()),
        jobDir: File = File("r4-yuv-helper")
    ) {
        assertEquals(
            "eligibility must fail closed",
            false,
            isModernTerminallySettledMainExport(job, journal, result, terminalOperationId, journals, jobDir)
        )
    }

    // ---- 1. Positive eligibility ----

    @Test
    fun eligibleModernTerminalSettledMainExport_returnsTrue() {
        assertEligible()
    }

    // ---- 2. Missing / blank recoveryState (R4.2 corrective) ----

    @Test
    fun missingRecoveryState_failsClosed() {
        val job = eligibleJobJson()
        job.remove("recoveryState")
        assertIneligible(job = job)
    }

    @Test
    fun blankRecoveryState_failsClosed() {
        val job = eligibleJobJson().put("recoveryState", "")
        assertIneligible(job = job)
    }

    // ---- 3. MediaStore result reality ----

    @Test
    fun nullResult_failsClosed() = assertIneligible(result = null)

    @Test
    fun nullJournal_failsClosed() = assertIneligible(journal = null)

    @Test
    fun resultNotPublicVerified_failsClosed() {
        assertIneligible(result = MediaStoreExportRecoveryResult(
            "attempt-1",
            MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED
        ))
    }

    @Test
    fun verificationDiagnosticPresent_failsClosed() {
        assertIneligible(result = MediaStoreExportRecoveryResult(
            "attempt-1",
            MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED,
            verificationDiagnosticReason = GalleryExportVerificationReason.FORMAT_MISMATCH
        ))
    }

    // ---- 4. Journal durable contract ----

    @Test
    fun nonMainRole_failsClosed() =
        assertIneligible(journal = modernMainJournal(role = MediaStoreExportRole.RAW_DNG_SIDECAR))

    @Test
    fun journalStateNotVerified_failsClosed() =
        assertIneligible(journal = modernMainJournal(state = MediaStoreExportState.PUBLIC_COMMITTED))

    @Test
    fun legacyTerminalMetadataNotPersisted_failsClosed() =
        assertIneligible(journal = modernMainJournal(terminalMetadataPersisted = false))

    @Test
    fun blankTerminalOperationId_failsClosed() =
        assertIneligible(terminalOperationId = "")

    @Test
    fun ownerOperationIdMismatch_failsClosed() {
        val journal = modernMainJournal(ownerOperationId = "other-op")
        assertIneligible(journal = journal, journals = listOf(journal))
    }

    @Test
    fun journalTerminalOperationIdMismatch_failsClosed() {
        val journal = modernMainJournal(terminalOperationId = "other-op")
        assertIneligible(journal = journal, journals = listOf(journal))
    }

    // ---- 5. Metadata durable contract ----

    @Test
    fun activeOperationPresent_failsClosed() {
        assertIneligible(job = eligibleJobJson()
            .put(ACTIVE_OPERATION_ID, "live-op")
            .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
    }

    @Test
    fun processingHandoffPresent_failsClosed() {
        assertIneligible(job = eligibleJobJson().put(PROCESSING_HANDOFF_OPERATION_ID, "handoff-op"))
    }

    @Test
    fun nonTerminalPipelineStage_failsClosed() {
        assertIneligible(job = eligibleJobJson().put("currentPipelineStage", "PROCESSING"))
    }

    @Test
    fun recoveryStateNotStable_failsClosed() {
        assertIneligible(job = eligibleJobJson().put("recoveryState", "PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL"))
    }

    @Test
    fun recoveryMessagePresent_failsClosed() {
        assertIneligible(job = eligibleJobJson().put("recoveryMessage", "debt"))
    }

    @Test
    fun galleryExportCommittedFalse_failsClosed() {
        assertIneligible(job = eligibleJobJson().put("galleryExportCommitted", false))
    }

    @Test
    fun exportVerifiedFalse_failsClosed() {
        assertIneligible(job = eligibleJobJson().put("exportVerified", false))
    }

    // ---- 6. Structural linkage & field agreement ----

    @Test
    fun exportUriBlank_failsClosed() {
        assertIneligible(job = eligibleJobJson().put("exportUri", ""))
    }

    @Test
    fun exportUriNullLiteral_failsClosed() {
        assertIneligible(job = eligibleJobJson().put("exportUri", "null"))
    }

    @Test
    fun linkageMismatch_failsClosed() {
        assertIneligible(job = eligibleJobJson().put("galleryPublicExportLinkage", "content://media/other"))
    }

    @Test
    fun journalUriMismatch_failsClosed() {
        val journal = modernMainJournal(uri = "content://media/other")
        assertIneligible(journal = journal, journals = listOf(journal))
    }

    @Test
    fun displayNameMismatch_failsClosed() {
        val journal = modernMainJournal(displayName = "other.jpg")
        assertIneligible(journal = journal, journals = listOf(journal))
    }

    @Test
    fun mimeTypeMismatch_failsClosed() {
        val journal = modernMainJournal(mimeType = "image/png")
        assertIneligible(journal = journal, journals = listOf(journal))
    }

    // ---- 7. Scope: unresolved non-MAIN journal debt ----

    @Test
    fun unresolvedNonMainJournalDebt_failsClosed() {
        val main = modernMainJournal()
        val sidecar = modernMainJournal(
            role = MediaStoreExportRole.RAW_DNG_SIDECAR,
            exportAttemptId = "attempt-sidecar",
            state = MediaStoreExportState.VERIFIED
        )
        assertIneligible(journal = main, journals = listOf(main, sidecar))
    }

    @Test
    fun cleanedNonMainJournal_isNotDebt() {
        val main = modernMainJournal()
        val sidecar = modernMainJournal(
            role = MediaStoreExportRole.RAW_DNG_SIDECAR,
            exportAttemptId = "attempt-sidecar",
            state = MediaStoreExportState.CLEANED
        )
        assertEligible(journal = main, journals = listOf(main, sidecar))
    }

    // ---- 8. Scope: RAW / sidecar isolation ----

    @Test
    fun rawSidecarRecoveryApplies_failsClosed() {
        assertIneligible(jobDir = File("KPL_RAW_FUSION_r4"))
    }

    @Test
    fun rawJobType_failsClosed() {
        assertIneligible(job = eligibleJobJson().put("jobType", "RAW_NIGHT_FUSION"))
    }

    @Test
    fun rawDngFrame_failsClosed() {
        val frames = org.json.JSONArray().put(JSONObject().put("frameIndex", 0).put("dngFile", "frame.dng"))
        assertIneligible(job = eligibleJobJson().put("frames", frames))
    }

    // ---- 9. End-to-end: eligible zero-write suppression ----

    @Test
    fun eligibleModernTerminalYuv_verifiedInspectionRuns_zeroWrites() {
        val parent = Files.createTempDirectory("r4-root-").toFile()
        val root = File(parent, "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_r4_eligible").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(job, eligibleJobJson())
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, DISPLAY_NAME, "Pictures/Kepler",
                MIME, Uri.parse("content://media/external/images/media"), ownerOperationId = OP
            ).transition(job, MediaStoreExportState.VERIFIED, URI).markTerminalPersisted(job, OP)

            val metadataBefore = File(job, JOB_JSON_FILE_NAME).readText()
            val journalFile = MediaStoreExportJournal.list(job).single().let { MediaStoreExportJournal.fileFor(job, it.exportAttemptId) }
            val journalBefore = journalFile.readText()

            val access = VerifiedAccess()
            val writesBefore = KeplerJobMetadata.atomicWriteCount
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), access)

            assertEquals(1, access.inspections)
            assertEquals(KeplerJobRecoveryClassification.RECOVERED, report.jobs.single().classification)
            assertEquals(writesBefore, KeplerJobMetadata.atomicWriteCount)
            assertEquals(metadataBefore, File(job, JOB_JSON_FILE_NAME).readText())
            assertEquals(journalBefore, journalFile.readText())
            assertTrue(report.jobs.single().actions.isEmpty())
            assertTrue(report.jobs.single().failures.isEmpty())
            assertTrue(report.jobs.single().cleanupFailures.isEmpty())
            assertEquals("STABLE", KeplerJobMetadata.read(job).getString("recoveryState"))
        } finally {
            parent.deleteRecursively()
        }
    }

    // ---- 10. End-to-end: missing / blank recoveryState still reconstructs ----

    @Test
    fun missingRecoveryState_reconstructsTwoWrites() {
        verifyReconstructionStillRuns(recoveryState = null)
    }

    @Test
    fun blankRecoveryState_reconstructsTwoWrites() {
        verifyReconstructionStillRuns(recoveryState = "")
    }

    private fun verifyReconstructionStillRuns(recoveryState: String?) {
        val parent = Files.createTempDirectory("r4-root-").toFile()
        val root = File(parent, "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_r4_reconstruct").apply { mkdirs() }
        try {
            val metadata = eligibleJobJson()
            metadata.remove("recoveryState")
            if (recoveryState != null) metadata.put("recoveryState", recoveryState)
            KeplerJobMetadata.write(job, metadata)
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, DISPLAY_NAME, "Pictures/Kepler",
                MIME, Uri.parse("content://media/external/images/media"), ownerOperationId = OP
            ).transition(job, MediaStoreExportState.VERIFIED, URI).markTerminalPersisted(job, OP)

            val writesBefore = KeplerJobMetadata.atomicWriteCount
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), VerifiedAccess())

            assertEquals(KeplerJobRecoveryClassification.RECOVERED, report.jobs.single().classification)
            assertEquals(writesBefore + 2, KeplerJobMetadata.atomicWriteCount)
            assertEquals("STABLE", KeplerJobMetadata.read(job).getString("recoveryState"))
            assertFalse(KeplerJobMetadata.read(job).has("recoveryMessage"))
        } finally {
            parent.deleteRecursively()
        }
    }

    // ---- 11. End-to-end: authoritative jobDir (no / wrong metadata path) ----

    @Test
    fun noJobDirAbsolutePath_stillEligible_zeroWrites() {
        runAuthoritativeJobDirCase(includeWrongPath = false)
    }

    @Test
    fun wrongJobDirAbsolutePath_ignored_zeroWrites() {
        runAuthoritativeJobDirCase(includeWrongPath = true)
    }

    private fun runAuthoritativeJobDirCase(includeWrongPath: Boolean) {
        val parent = Files.createTempDirectory("r4-root-").toFile()
        val root = File(parent, "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_r4_jobdir").apply { mkdirs() }
        try {
            val metadata = eligibleJobJson()
            if (includeWrongPath) metadata.put("jobDirAbsolutePath", "/nonexistent/wrong/directory")
            KeplerJobMetadata.write(job, metadata)
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, DISPLAY_NAME, "Pictures/Kepler",
                MIME, Uri.parse("content://media/external/images/media"), ownerOperationId = OP
            ).transition(job, MediaStoreExportState.VERIFIED, URI).markTerminalPersisted(job, OP)

            val writesBefore = KeplerJobMetadata.atomicWriteCount
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), VerifiedAccess())

            assertEquals(KeplerJobRecoveryClassification.RECOVERED, report.jobs.single().classification)
            assertEquals(writesBefore, KeplerJobMetadata.atomicWriteCount)
            assertEquals("STABLE", KeplerJobMetadata.read(job).getString("recoveryState"))
        } finally {
            parent.deleteRecursively()
        }
    }

    // ---- 12. End-to-end: legacy terminal evidence isolation ----

    @Test
    fun legacyTerminalMetadataNotPersisted_reconstructsTwoWrites() {
        val parent = Files.createTempDirectory("r4-root-").toFile()
        val root = File(parent, "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_r4_legacy").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(job, eligibleJobJson())
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, DISPLAY_NAME, "Pictures/Kepler",
                MIME, Uri.parse("content://media/external/images/media"), ownerOperationId = OP
            ).transition(job, MediaStoreExportState.VERIFIED, URI)

            val writesBefore = KeplerJobMetadata.atomicWriteCount
            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), VerifiedAccess())

            assertEquals(KeplerJobRecoveryClassification.RECOVERED, report.jobs.single().classification)
            assertEquals(writesBefore + 2, KeplerJobMetadata.atomicWriteCount)
            assertEquals("STABLE", KeplerJobMetadata.read(job).getString("recoveryState"))
        } finally {
            parent.deleteRecursively()
        }
    }

    // ---- 13. End-to-end: processing-artifact safety (invalid evidence not bypassed) ----

    @Test
    fun invalidProcessingJournal_stillFailClosed_ambiguous() {
        val parent = Files.createTempDirectory("r4-root-").toFile()
        val root = File(parent, "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_r4_processing").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(job, eligibleJobJson())
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, DISPLAY_NAME, "Pictures/Kepler",
                MIME, Uri.parse("content://media/external/images/media"), ownerOperationId = OP
            ).transition(job, MediaStoreExportState.VERIFIED, URI).markTerminalPersisted(job, OP)
            File(job, ".processing_tx_corrupt.json").writeText("not-json")

            val report = KeplerRecoveryCoordinator.recoverRoots(listOf(root), VerifiedAccess())

            assertEquals(
                "MAIN reconstruction must not mask downstream invalid processing evidence",
                KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED,
                report.jobs.single().classification
            )
            assertEquals("AMBIGUOUS_RECOVERY_REQUIRED", KeplerJobMetadata.read(job).getString("recoveryState"))
            assertTrue(File(job, ".processing_tx_corrupt.json").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    private companion object {
        const val OP = "terminal-op-r4"
        const val URI = "content://media/external/images/media/99100"
        const val DISPLAY_NAME = "result.jpg"
        const val MIME = "image/jpeg"
    }
}