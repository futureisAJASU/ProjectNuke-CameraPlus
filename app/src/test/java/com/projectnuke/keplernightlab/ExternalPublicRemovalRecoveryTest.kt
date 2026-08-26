package com.projectnuke.keplernightlab

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

/**
 * External public-result removal lifecycle (Phases 1, 2, 3, 12, 15).
 *
 * A verified CURRENT public result that was manually removed from the system
 * Gallery is historical external removal — NOT recovery corruption:
 *   - terminal-stable VERIFIED evidence + missing row -> PUBLIC_RESULT_REMOVED -> STABLE;
 *   - unacknowledged in-flight exports keep the fail-closed PUBLIC_COMMIT_MISSING policy;
 *   - historical removals never override a newer verified current export;
 *   - local deletion/cleanup/reprocess never require the public row to exist.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalPublicRemovalRecoveryTest {

    private class FakeAccess(
        private val existingUris: Set<String>,
        private val pending: Boolean = false,
        private val verified: Boolean = true,
        private val inspectionFailed: Boolean = false
    ) : MediaStoreExportRecoveryAccess {
        override fun inspect(uri: Uri, journal: MediaStoreExportJournal) =
            MediaStoreExportInspection(
                exists = uri.toString() in existingUris,
                pending = pending,
                verified = verified,
                inspectionFailed = inspectionFailed
            )

        override fun setPending(uri: Uri, pending: Boolean) = true
        override fun delete(uri: Uri) = true
    }

    private fun recoveryRoot(label: String): Pair<File, File> {
        val base = Files.createTempDirectory(label).toFile()
        val root = File(base, "KeplerYuvFusion").apply { mkdirs() }
        val job = File(root, "KPL_YUV_FUSION_REMOVED_TEST").apply { mkdirs() }
        return root to job
    }

    private fun mainJournal(
        jobDir: File,
        uri: String,
        ownerOperationId: String? = null,
        terminallyStable: Boolean,
        createdAt: Long = System.currentTimeMillis()
    ): MediaStoreExportJournal {
        val created = MediaStoreExportJournal.create(
            jobDir = jobDir,
            role = MediaStoreExportRole.MAIN_IMAGE,
            frameIndex = null,
            displayName = "result.jpg",
            relativePath = "Pictures/Kepler",
            mimeType = "image/jpeg",
            collectionUri = Uri.parse("content://media/external/images/media"),
            ownerOperationId = ownerOperationId
        )
        var journal = created.transition(jobDir, MediaStoreExportState.VERIFIED, uri)
        journal = journal.copy(createdAt = createdAt, updatedAt = createdAt).writeTo(jobDir)
        return if (terminallyStable) journal.markTerminalPersisted(jobDir, ownerOperationId) else journal
    }

    private fun writeStableVerifiedJob(job: File, exportUri: String?) {
        val metadata = JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put("status", "COMPLETE")
            .put("currentPipelineStage", "COMPLETE")
            .put("recoveryState", "STABLE")
            .put("galleryExportCommitted", true)
            .put("exportVerified", true)
            .put("exportDisplayName", "result.jpg")
        if (exportUri != null) {
            metadata.put("exportUri", exportUri)
                .put("galleryPublicExportLinkage", exportUri)
        }
        KeplerJobMetadata.write(job, metadata)
    }

    /** Matrix 1: VERIFIED + terminal ACK + provider row missing -> PUBLIC_RESULT_REMOVED -> STABLE. */
    @Test
    fun verifiedTerminalRowMissing_classifiesRemoved_settlesStable_truthfulMetadata() {
        val (root, job) = recoveryRoot("removed-matrix1-")
        try {
            val uri = "content://media/external/images/media/7"
            writeStableVerifiedJob(job, uri)
            mainJournal(job, uri, terminallyStable = true)

            val report = KeplerRecoveryCoordinator.recoverRoots(
                listOf(root),
                FakeAccess(existingUris = emptySet())
            )
            assertEquals(
                KeplerJobRecoveryClassification.RECOVERED,
                report.jobs.single().classification
            )
            assertTrue(report.jobs.single().actions.contains("PUBLIC_RESULT_REMOVED"))

            val metadata = KeplerJobMetadata.read(job)
            assertEquals("STABLE", metadata.optString("recoveryState"))
            assertFalse(metadata.optBoolean("galleryExportCommitted", true))
            assertFalse(metadata.optBoolean("exportVerified", true))
            assertEquals("REMOVED_EXTERNALLY", metadata.optString("exportStatus"))
            assertFalse(metadata.optBoolean("publicResultAvailable", true))
            // History stays truthful: durable evidence is preserved, never downgraded.
            assertEquals(uri, metadata.optString("lastVerifiedExportUri"))
            assertEquals("result.jpg", metadata.optString("lastVerifiedExportDisplayName"))
            assertTrue(metadata.has("publicResultRemovedAt"))
            // The proven-absent row is no longer current-public-result authority.
            assertFalse(metadata.has("exportUri"))
            assertFalse(metadata.has("galleryPublicExportLinkage"))
            assertEquals("PUBLIC_RESULT_REMOVED", metadata.optString("lastRecoveryClassification"))
            // The journal itself keeps its durable VERIFIED cut.
            assertEquals(MediaStoreExportState.VERIFIED, MediaStoreExportJournal.list(job).single().state)
            assertTrue(MediaStoreExportJournal.list(job).single().terminalMetadataPersisted)
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    /** Idempotency: a second recovery pass settles STABLE again without regression to debt. */
    @Test
    fun externalRemoval_recoveryIsIdempotent_staysStable() {
        val (root, job) = recoveryRoot("removed-idem-")
        try {
            val uri = "content://media/external/images/media/7"
            writeStableVerifiedJob(job, uri)
            mainJournal(job, uri, terminallyStable = true)
            val access = FakeAccess(existingUris = emptySet())
            KeplerRecoveryCoordinator.recoverRoots(listOf(root), access)
            KeplerRecoveryCoordinator.recoverRoots(listOf(root), access)
            assertEquals("STABLE", KeplerJobMetadata.read(job).optString("recoveryState"))
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_DELETE)
            )
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    /** Matrix 2: VERIFIED without terminal ACK + row missing keeps fail-closed policy. */
    @Test
    fun verifiedWithoutTerminalAck_rowMissing_keepsPublicCommitMissingBlocked() {
        val (root, job) = recoveryRoot("removed-matrix2-")
        try {
            val uri = "content://media/external/images/media/8"
            writeStableVerifiedJob(job, uri)
            mainJournal(job, uri, terminallyStable = false)

            val report = KeplerRecoveryCoordinator.recoverRoots(
                listOf(root),
                FakeAccess(existingUris = emptySet())
            )
            assertEquals(
                KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED,
                report.jobs.single().classification
            )
            val metadata = KeplerJobMetadata.read(job)
            assertEquals("PUBLIC_COMMIT_MISSING", metadata.optString("recoveryState"))
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_PUBLIC_COMMIT_MISSING,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    /** Matrix 3: PUBLIC_COMMITTED_UNVERIFIED + row missing keeps the existing debt policy. */
    @Test
    fun committedUnverifiedRowMissing_keepsExistingDebtPolicy() {
        val (root, job) = recoveryRoot("removed-matrix3-")
        try {
            val uri = "content://media/external/images/media/9"
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("recoveryState", "STABLE")
                .put("galleryExportCommitted", true)
                .put("exportUri", uri))
            mainJournal(job, uri, terminallyStable = false)
                .transition(job, MediaStoreExportState.PUBLIC_COMMITTED)

            KeplerRecoveryCoordinator.recoverRoots(
                listOf(root),
                FakeAccess(existingUris = emptySet())
            )
            val metadata = KeplerJobMetadata.read(job)
            assertEquals("PUBLIC_COMMIT_MISSING", metadata.optString("recoveryState"))
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_PUBLIC_COMMIT_MISSING,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_DELETE)
            )
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    /**
     * Phase 2 regression: an old verified export removed externally must NEVER downgrade or
     * override a newer verified reprocess export that currently exists.
     */
    @Test
    fun oldVerifiedExportRemoved_newReprocessExportVerified_oldMissingDoesNotOverrideNew() {
        val (root, job) = recoveryRoot("removed-authority-")
        try {
            val newUri = "content://media/external/images/media/22"
            writeStableVerifiedJob(job, newUri)
            mainJournal(
                job,
                "content://media/external/images/media/7",
                terminallyStable = true,
                createdAt = 1_000L
            )
            mainJournal(job, newUri, terminallyStable = true, createdAt = 2_000L)

            KeplerRecoveryCoordinator.recoverRoots(
                listOf(root),
                FakeAccess(existingUris = setOf(newUri))
            )
            val metadata = KeplerJobMetadata.read(job)
            // The new export remains the single current authority.
            assertEquals(newUri, metadata.optString("exportUri"))
            assertEquals(newUri, metadata.optString("galleryPublicExportLinkage"))
            assertTrue(metadata.optBoolean("galleryExportCommitted", false))
            assertTrue(metadata.optBoolean("exportVerified", false))
            assertEquals("STABLE", metadata.optString("recoveryState"))
            // The historical removal was not written over current truth.
            assertFalse(metadata.has("lastVerifiedExportUri"))
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    /** Phase 2 regression: two historical missing exports cannot destabilize a verified current one. */
    @Test
    fun twoHistoricalMissingExports_currentVerifiedExportRemainsStable() {
        val (root, job) = recoveryRoot("removed-two-hist-")
        try {
            val currentUri = "content://media/external/images/media/30"
            writeStableVerifiedJob(job, currentUri)
            mainJournal(job, "content://media/external/images/media/10", terminallyStable = true, createdAt = 1_000L)
            mainJournal(job, "content://media/external/images/media/11", terminallyStable = true, createdAt = 1_500L)
            mainJournal(job, currentUri, terminallyStable = true, createdAt = 3_000L)

            KeplerRecoveryCoordinator.recoverRoots(
                listOf(root),
                FakeAccess(existingUris = setOf(currentUri))
            )
            val metadata = KeplerJobMetadata.read(job)
            assertEquals(currentUri, metadata.optString("exportUri"))
            assertEquals("STABLE", metadata.optString("recoveryState"))
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_DELETE)
            )
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    /**
     * Phases 3+12: after external removal settles STABLE, explicit local deletion, cleanup and
     * reprocess are all allowed without the public Gallery row.
     */
    @Test
    fun externalDeletion_doesNotBlockDeleteCleanupOrReprocess() {
        val (root, job) = recoveryRoot("removed-gate-")
        try {
            val uri = "content://media/external/images/media/40"
            writeStableVerifiedJob(job, uri)
            mainJournal(job, uri, terminallyStable = true)
            KeplerRecoveryCoordinator.recoverRoots(
                listOf(root),
                FakeAccess(existingUris = emptySet())
            )
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_DELETE)
            )
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_CLEANUP)
            )
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }

    /**
     * Phase 3 boundary: live ownership still blocks destructive local intents even when
     * public-export history debt alone would not.
     */
    @Test
    fun destructiveLocalIntents_stillBlockedByLiveOwnership() {
        val job = Files.createTempDirectory("removed-live-owner-").toFile()
        try {
            KeplerJobMetadata.write(job, JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, "live-op")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name))
            val lease = KeplerJobMetadata.acquireOperation(job)!!
            try {
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_DEAD_OPERATION,
                KeplerJobMetadata.inspectRecoveryMutationGate(job, JobRecoveryMutationIntent.JOB_DELETE)
            )
            } finally {
                lease.release()
            }
        } finally {
            job.deleteRecursively()
        }
    }

    /**
     * Matrix 6: a historically removed RAW sidecar row never erases the current verified
     * MAIN_IMAGE authority — role-aware settlement keeps the MAIN record stable.
     */
    @Test
    fun sidecarHistoricalMissing_doesNotEraseCurrentMainAuthority() {
        val (root, job) = recoveryRoot("removed-sidecar-")
        try {
            val mainUri = "content://media/external/images/media/50"
            writeStableVerifiedJob(job, mainUri)
            mainJournal(job, mainUri, terminallyStable = true)
            MediaStoreExportJournal.create(
                jobDir = job,
                role = MediaStoreExportRole.RAW_DNG_SIDECAR,
                frameIndex = 0,
                displayName = "frame_0000.dng",
                relativePath = "Pictures/Kepler",
                mimeType = "image/x-adobe-dng",
                collectionUri = Uri.parse("content://media/external/images/media")
            ).transition(job, MediaStoreExportState.VERIFIED, "content://media/external/images/media/51")
                .copy(updatedAt = 1L, createdAt = 1L).writeTo(job)
                .markTerminalPersisted(job, null)

            KeplerRecoveryCoordinator.recoverRoots(
                listOf(root),
                FakeAccess(existingUris = setOf(mainUri))
            )
            val metadata = KeplerJobMetadata.read(job)
            assertEquals(mainUri, metadata.optString("exportUri"))
            assertEquals("STABLE", metadata.optString("recoveryState"))
        } finally {
            root.parentFile?.deleteRecursively()
        }
    }
}
