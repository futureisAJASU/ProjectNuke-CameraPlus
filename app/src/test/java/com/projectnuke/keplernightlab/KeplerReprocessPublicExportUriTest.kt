package com.projectnuke.keplernightlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Regressions for the Scenario B physical failure: the reprocess writers persist
 * `exportUri = export?.uriString ?: JSONObject.NULL`, and `JSONObject.optString` coerces that JSON
 * null into the literal string "null" — which naive non-blank/differs-from-original checks accept
 * as a "new URI". Public-export success must always be derived through
 * [ReprocessPublicExportState], never from the generic local-transaction [Result.success].
 */
@RunWith(RobolectricTestRunner::class)
class KeplerReprocessPublicExportUriTest {

    @Test
    fun jsonNullExportUri_isRejectedAsPublicUri() {
        assertNull(parsePublicExportUri(null))
        assertNull(parsePublicExportUri(""))
        assertNull(parsePublicExportUri("   "))
        assertNull(parsePublicExportUri("null"))
        assertNull(parsePublicExportUri("http://example.com/image.jpg"))
        assertNull(parsePublicExportUri("file:///storage/emulated/0/DCIM/Camera/image.jpg"))
        assertNull(parsePublicExportUri("content://com.example.gallery/app/image"))
        assertNull(parsePublicExportUri("content://media/external/images/media"))
        assertNotNull(parsePublicExportUri("content://media/external/images/media/4242"))
        assertNotNull(parsePublicExportUri("content://media/external/images/media/1"))
    }

    @Test
    fun localReprocessSuccess_publicExportNotCommitted_isNotVerifiedPublicSuccess() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease() ?: error("no lease")
            session.transferOwnership(transaction)
            val attemptId = UUID.randomUUID().toString()
            lease.claimProcessingAttempt(attemptId)
            File(directory, "merged_raw_final.png").writeText("local-result")
            KeplerJobMetadata.write(directory, KeplerJobMetadata.read(directory).apply {
                put("processingAttemptId", attemptId)
                put("processingMode", "CLASSIC_RAW")
                put("processingOutputCommitted", true)
                put("processingArtifactClaimAttemptId", attemptId)
                put("mergedRawFile", "merged_raw_final.png")
            })
            lease.releaseProcessingAttempt(attemptId)
            val outcome = ReprocessWorkerOutcome(
                result = Result.success(Unit),
                publicExportCommitted = false,
                export = null
            )
            val result = finalizeTransactionWithLease(
                transaction, lease, directory, ReprocessJobKind.RAW_FUSION,
                FinalOutputFormat.HEIF, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
                Result.success(outcome)
            )
            assertEquals(ReprocessFinalizationState.COMMITTED, result.state)
            assertTrue(
                "local transaction success must remain a generic reprocess success",
                result.result.isSuccess
            )

            val job = KeplerJobMetadata.read(directory)
            val state = ReprocessPublicExportState.fromDurableMetadata(job)
            assertFalse(
                "local success must not be a verified public success",
                state.isVerifiedPublicSuccess
            )
            assertEquals(GalleryExportCommitState.NOT_COMMITTED, state.commitState)
            assertNull("JSON null exportUri must parse to no public URI", state.uri)
            assertTrue(
                "writer must persist JSON null, not the string \"null\"",
                job.isNull("exportUri")
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun publicCommittedUnverified_requiresRecoveryBeforeScenarioBSuccess() {
        val directory = tempJob()
        try {
            KeplerJobMetadata.write(directory, KeplerJobMetadata.read(directory).apply {
                put("processStatus", "REPROCESS_PARTIAL")
                put("currentPipelineStage", "PARTIAL")
                put("reprocessStatus", "PARTIAL")
                put("exportStatus", "COMMITTED_UNVERIFIED")
                put("exportCommitState", GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name)
                put("exportVerified", false)
                put("galleryExportCommitted", true)
                put("exportUri", "content://media/external/images/media/777")
            })
            val state = ReprocessPublicExportState.fromDurableMetadata(KeplerJobMetadata.read(directory))
            assertFalse(
                "committed-unverified is not yet a verified public success",
                state.isVerifiedPublicSuccess
            )
            assertEquals(GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED, state.commitState)
            assertNotNull(state.uri)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun verifiedExport_requiresRealContentUri() {
        val directory = tempJob()
        try {
            KeplerJobMetadata.write(directory, KeplerJobMetadata.read(directory).apply {
                put("processStatus", "REPROCESS_COMPLETE")
                put("currentPipelineStage", "COMPLETE")
                put("reprocessStatus", "COMPLETE")
                put("exportStatus", "EXPORTED")
                put("exportCommitState", GalleryExportCommitState.VERIFIED.name)
                put("exportVerified", true)
                put("galleryExportCommitted", true)
            })
            assertFalse(
                "VERIFIED state without any URI is not a public success",
                ReprocessPublicExportState.fromDurableMetadata(KeplerJobMetadata.read(directory))
                    .isVerifiedPublicSuccess
            )
            KeplerJobMetadata.write(directory, KeplerJobMetadata.read(directory).apply {
                put("exportUri", "content://media/external/images/media/888")
            })
            val state = ReprocessPublicExportState.fromDurableMetadata(KeplerJobMetadata.read(directory))
            assertTrue(state.isVerifiedPublicSuccess)
            assertEquals("content://media/external/images/media/888", state.uri.toString())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun verifiedExportTransaction_writesVerifiedPublicContract() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease() ?: error("no lease")
            session.transferOwnership(transaction)
            val attemptId = UUID.randomUUID().toString()
            lease.claimProcessingAttempt(attemptId)
            File(directory, "merged_raw_final.png").writeText("local-result")
            KeplerJobMetadata.write(directory, KeplerJobMetadata.read(directory).apply {
                put("processingAttemptId", attemptId)
                put("processingMode", "CLASSIC_RAW")
                put("processingOutputCommitted", true)
                put("processingArtifactClaimAttemptId", attemptId)
                put("mergedRawFile", "merged_raw_final.png")
            })
            lease.releaseProcessingAttempt(attemptId)
            val export = GalleryExportResult(
                success = true,
                uriString = "content://media/external/images/media/999",
                displayName = "IMG_0001.heif",
                mimeType = "image/heif",
                fileSizeBytes = 42L,
                formatUsed = OutputFormat.HEIF,
                fallbackUsed = false,
                errorMessage = null
            )
            val outcome = ReprocessWorkerOutcome(
                result = Result.success(Unit),
                publicExportCommitted = true,
                exportVerified = true,
                export = export
            )
            val result = finalizeTransactionWithLease(
                transaction, lease, directory, ReprocessJobKind.RAW_FUSION,
                FinalOutputFormat.HEIF, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
                Result.success(outcome)
            )
            assertEquals(ReprocessFinalizationState.COMMITTED, result.state)
            assertTrue(result.result.isSuccess)

            val job = KeplerJobMetadata.read(directory)
            val state = ReprocessPublicExportState.fromDurableMetadata(job)
            assertTrue(state.isVerifiedPublicSuccess)
            assertEquals(GalleryExportCommitState.VERIFIED, state.commitState)
            assertEquals("content://media/external/images/media/999", state.uri.toString())
            assertEquals("content://media/external/images/media/999", job.optString("exportUri"))
            assertFalse(KeplerJobMetadata.isOperationActive(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun historicalRemovedA_newVerifiedB_isCurrent() {
        val directory = tempJob()
        try {
            KeplerJobMetadata.write(directory, KeplerJobMetadata.read(directory).apply {
                put("recoveryState", "STABLE")
                put("lastRecoveryClassification", "PUBLIC_RESULT_REMOVED")
                put("lastVerifiedExportUri", "content://media/external/images/media/1")
                put("exportStatus", "EXPORTED")
                put("exportCommitState", GalleryExportCommitState.VERIFIED.name)
                put("exportVerified", true)
                put("galleryExportCommitted", true)
                put("exportUri", "content://media/external/images/media/2")
                put("publicResultAvailable", true)
            })
            val state = ReprocessPublicExportState.fromDurableMetadata(KeplerJobMetadata.read(directory))
            assertTrue(state.isVerifiedPublicSuccess)
            assertEquals("content://media/external/images/media/2", state.uri.toString())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun historicalRemovedA_doesNotBlockNewExportAttempt() {
        val directory = tempJob()
        try {
            KeplerJobMetadata.write(directory, KeplerJobMetadata.read(directory).apply {
                put("recoveryState", "STABLE")
                put("lastRecoveryClassification", "PUBLIC_RESULT_REMOVED")
                put("lastRecoveryMessage", "externally removed")
                put("exportStatus", "REMOVED_EXTERNALLY")
                put("publicResultAvailable", false)
                put("lastVerifiedExportUri", "content://media/external/images/media/1")
            })
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(directory, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun scenarioBDiagnostic_containsCommitStateAndError() {
        val directory = tempJob()
        try {
            KeplerJobMetadata.write(directory, KeplerJobMetadata.read(directory).apply {
                put("processStatus", "REPROCESS_PARTIAL")
                put("currentPipelineStage", "PARTIAL")
                put("reprocessStatus", "PARTIAL")
                put("reprocessError", "Reprocess worker verification failed")
                put("exportStatus", "EXPORT_FAILED")
                put("exportCommitState", GalleryExportCommitState.NOT_COMMITTED.name)
                put("exportVerified", false)
                put("exportUri", JSONObject.NULL)
            })
            val diagnostic = JSONObject(buildReprocessPublicExportDiagnostic(
                directory,
                "content://media/external/images/media/1",
                reprocessTransactionSucceeded = true,
                reprocessResultWarnings = listOf("warning-a")
            ))
            assertEquals("NOT_COMMITTED", diagnostic.getString("exportCommitState"))
            assertEquals("EXPORT_FAILED", diagnostic.getString("exportStatus"))
            assertEquals("PARTIAL", diagnostic.getString("reprocessStatus"))
            assertEquals("Reprocess worker verification failed", diagnostic.getString("reprocessError"))
            assertTrue(diagnostic.getBoolean("exportUriHas"))
            assertTrue(diagnostic.getBoolean("exportUriIsNull"))
            assertEquals("null", diagnostic.getString("exportUriRaw"))
            assertTrue(diagnostic.isNull("exportUriParsed"))
            assertEquals("content://media/external/images/media/1", diagnostic.getString("originalExportUri"))
            assertTrue(diagnostic.getBoolean("reprocessTransactionSucceeded"))
            assertEquals("warning-a", diagnostic.getJSONArray("reprocessResultWarnings").getString(0))
            assertEquals(0, diagnostic.getJSONArray("mainImageJournals").length())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun tempJob(): File = Files.createTempDirectory("kepler-public-uri-").toFile().also {
        KeplerJobMetadata.write(it, JSONObject().put("jobType", "RAW_NIGHT_FUSION"))
    }

    private fun backup(directory: File, vararg files: Pair<String, String>): ReprocessTransaction {
        files.forEach { (name, contents) -> File(directory, name).writeText(contents) }
        return backupReprocessTransaction(directory, files.map { File(directory, it.first) }).getOrThrow()
    }
}
