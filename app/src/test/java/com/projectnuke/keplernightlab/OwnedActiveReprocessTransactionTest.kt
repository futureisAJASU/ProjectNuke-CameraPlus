package com.projectnuke.keplernightlab

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class OwnedActiveReprocessTransactionTest {

    private fun tempJob(): File = Files.createTempDirectory("kepler-owned-reprocess-").toFile().also {
        KeplerJobMetadata.write(it, JSONObject().put("jobType", "RAW_NIGHT_FUSION"))
    }

    private fun backup(directory: File, vararg files: Pair<String, String>): ReprocessTransaction {
        files.forEach { (name, contents) -> File(directory, name).writeText(contents) }
        return backupReprocessTransaction(directory, files.map { File(directory, it.first) }).getOrThrow()
    }

    @Test
    fun activeOwnedReprocessTransaction_canEnterPublicExportPhase() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transaction = backup(directory, "final.png" to "before")
            assertEquals(ReprocessTransactionState.ACTIVE, transaction.manifest.state)

            session.transferOwnership(transaction)

            assertTrue(isReprocessQuarantined(directory))

            val exactLease = lease
            assertTrue(isExactOwnedActiveReprocessTransaction(directory, exactLease))

            val operationId = KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = exactLease
            )
            assertNotNull(operationId)

            assertEquals(KeplerActiveOperationKind.PUBLIC_EXPORT, exactLease.currentDurableOperationKind())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun activeReprocess_externalReprocessBlocked() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transaction = backup(directory, "final.png" to "before")
            session.transferOwnership(transaction)

            assertTrue(isReprocessQuarantined(directory))

            val outcome = KeplerJobMetadata.inspectRecoveryMutationGate(
                directory,
                JobRecoveryMutationIntent.REPROCESS,
                ownerLease = null
            )
            assertEquals(JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE, outcome)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun activeReprocess_externalMetadataEditBlocked() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transaction = backup(directory, "final.png" to "before")
            session.transferOwnership(transaction)

            assertTrue(isReprocessQuarantined(directory))

            val outcome = KeplerJobMetadata.inspectRecoveryMutationGate(
                directory,
                JobRecoveryMutationIntent.METADATA_EDIT,
                ownerLease = null
            )
            assertEquals(JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE, outcome)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun activeReprocess_externalCleanupOrDeleteCannotBypassTransaction() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transaction = backup(directory, "final.png" to "before")
            session.transferOwnership(transaction)

            assertTrue(isReprocessQuarantined(directory))

            val cleanupOutcome = KeplerJobMetadata.inspectRecoveryMutationGate(
                directory,
                JobRecoveryMutationIntent.JOB_CLEANUP,
                ownerLease = null
            )
            assertEquals(JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE, cleanupOutcome)

            val deleteOutcome = KeplerJobMetadata.inspectRecoveryMutationGate(
                directory,
                JobRecoveryMutationIntent.JOB_DELETE,
                ownerLease = null
            )
            assertEquals(JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE, deleteOutcome)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun activeReprocess_wrongLease_cannotBypassQuarantine() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transaction = backup(directory, "final.png" to "before")
            session.transferOwnership(transaction)

            assertTrue(isReprocessQuarantined(directory))

            val foreignLease = JobOperationLease("foreign-key-" + System.currentTimeMillis())

            assertFalse(isExactOwnedActiveReprocessTransaction(directory, foreignLease))

            val outcome = KeplerJobMetadata.inspectRecoveryMutationGate(
                directory,
                JobRecoveryMutationIntent.REPROCESS,
                ownerLease = foreignLease
            )
            assertEquals(JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE, outcome)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun quarantinedOwnedTransaction_cannotEnterPublicExport() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transaction = backup(directory, "final.png" to "before")
            session.transferOwnership(transaction)

            writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED)

            assertFalse(isExactOwnedActiveReprocessTransaction(directory, lease))

            val exception = assertThrows(JobRecoveryMutationBlockedException::class.java) {
                KeplerJobMetadata.beginActiveOperation(
                    directory,
                    kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                    ownerLease = lease
                )
            }
            assertEquals(JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE, exception.outcome)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ownedActiveManifest_withDurableQuarantineMarker_cannotBypass() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transaction = backup(directory, "final.png" to "before")
            session.transferOwnership(transaction)

            assertEquals(ReprocessTransactionState.ACTIVE, transaction.manifest.state)

            writeQuarantineMarker(transaction)

            assertEquals(ReprocessTransactionState.ACTIVE, loadStrictManifest(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE))?.state)
            assertTrue(File(transaction.backupRoot, REPROCESS_QUARANTINE_MARKER).isFile)

            assertFalse(isExactOwnedActiveReprocessTransaction(directory, lease))

            val exception = assertThrows(JobRecoveryMutationBlockedException::class.java) {
                KeplerJobMetadata.beginActiveOperation(
                    directory,
                    kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                    ownerLease = lease
                )
            }
            assertEquals(JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE, exception.outcome)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ownedActiveReprocess_plusSecondActiveRoot_blocks() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transactionA = backup(directory, "final.png" to "before")
            session.transferOwnership(transactionA)

            val rootNameB = ".reprocess_backup_${System.currentTimeMillis()}_second"
            val rootB = File(directory, rootNameB)
            assertTrue(rootB.mkdirs())
            val manifestB = ReprocessTransactionManifest(
                transactionId = rootNameB.substringAfterLast("_"),
                createdAt = System.currentTimeMillis(),
                preExistingPaths = setOf(),
                backedUpPaths = setOf(),
                backupEntries = emptyMap(),
                state = ReprocessTransactionState.ACTIVE
            )
            KeplerJobMetadata.atomicWrite(File(rootB, REPROCESS_TX_MANIFEST_FILE), manifestB.toJson().toString(2))

            assertFalse(isExactOwnedActiveReprocessTransaction(directory, lease))

            val outcome = KeplerJobMetadata.inspectRecoveryMutationGate(
                directory,
                JobRecoveryMutationIntent.REPROCESS,
                ownerLease = lease
            )
            assertEquals(JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE, outcome)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ownedActiveReprocess_plusQuarantinedRoot_blocks() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transactionA = backup(directory, "final.png" to "before")
            session.transferOwnership(transactionA)

            val rootNameB = ".reprocess_backup_${System.currentTimeMillis()}_quarantined"
            val rootB = File(directory, rootNameB)
            assertTrue(rootB.mkdirs())
            val manifestB = ReprocessTransactionManifest(
                transactionId = rootNameB.substringAfterLast("_"),
                createdAt = System.currentTimeMillis(),
                preExistingPaths = setOf(),
                backedUpPaths = setOf(),
                backupEntries = emptyMap(),
                state = ReprocessTransactionState.QUARANTINED
            )
            KeplerJobMetadata.atomicWrite(File(rootB, REPROCESS_TX_MANIFEST_FILE), manifestB.toJson().toString(2))

            assertFalse(isExactOwnedActiveReprocessTransaction(directory, lease))

            val outcome = KeplerJobMetadata.inspectRecoveryMutationGate(
                directory,
                JobRecoveryMutationIntent.REPROCESS,
                ownerLease = lease
            )
            assertEquals(JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE, outcome)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ownedActiveReprocess_plusCorruptRoot_blocks() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transactionA = backup(directory, "final.png" to "before")
            session.transferOwnership(transactionA)

            val rootNameB = ".reprocess_backup_${System.currentTimeMillis()}_corrupt"
            val rootB = File(directory, rootNameB)
            assertTrue(rootB.mkdirs())
            File(rootB, REPROCESS_TX_MANIFEST_FILE).writeText("{corrupt json")

            assertFalse(isExactOwnedActiveReprocessTransaction(directory, lease))

            val outcome = KeplerJobMetadata.inspectRecoveryMutationGate(
                directory,
                JobRecoveryMutationIntent.REPROCESS,
                ownerLease = lease
            )
            assertEquals(JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE, outcome)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ownedActiveReprocess_plusFallbackMarker_blocks() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transaction = backup(directory, "final.png" to "before")
            session.transferOwnership(transaction)

            val fallbackMarker = File(directory, REPROCESS_FALLBACK_QUARANTINE_MARKER)
            KeplerJobMetadata.atomicWrite(fallbackMarker, fallbackIdentity(transaction).let { (id, root, created) ->
                JSONObject().put("transactionId", id).put("backupRoot", root).put("createdAt", created).toString()
            })

            assertFalse(isExactOwnedActiveReprocessTransaction(directory, lease))

            val outcome = KeplerJobMetadata.inspectRecoveryMutationGate(
                directory,
                JobRecoveryMutationIntent.REPROCESS,
                ownerLease = lease
            )
            assertEquals(JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE, outcome)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun committedTransaction_clearsOwnedReprocessBinding() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transaction = backup(directory, "final.png" to "before")
            session.transferOwnership(transaction)

            assertEquals(transaction.transactionId, lease.ownedReprocessTransactionId())

            writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
            lease.clearOwnedReprocessTransaction(transaction.transactionId)

            assertNull(lease.ownedReprocessTransactionId())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rolledBackTransaction_clearsOwnedReprocessBinding() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transaction = backup(directory, "final.png" to "before")
            session.transferOwnership(transaction)

            assertEquals(transaction.transactionId, lease.ownedReprocessTransactionId())

            writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
            lease.clearOwnedReprocessTransaction(transaction.transactionId)

            assertNull(lease.ownedReprocessTransactionId())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun quarantinedTransaction_bindingDoesNotPermitMutation() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transaction = backup(directory, "final.png" to "before")
            session.transferOwnership(transaction)

            writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED)

            assertEquals(transaction.transactionId, lease.ownedReprocessTransactionId())

            assertFalse(isExactOwnedActiveReprocessTransaction(directory, lease))

            val outcome = KeplerJobMetadata.inspectRecoveryMutationGate(
                directory,
                JobRecoveryMutationIntent.REPROCESS,
                ownerLease = lease
            )
            assertEquals(JobRecoveryMutationGateOutcome.BLOCKED_REPROCESS_QUARANTINE, outcome)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun endToEndReprocessPublicExportPhase_doesNotSelfQuarantine() = runBlocking {
        val directory = tempJob()
        try {
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease(JobRecoveryMutationIntent.REPROCESS)!!
            assertNotNull(lease)

            val transaction = backup(directory, "final.png" to "before")
            session.transferOwnership(transaction)

            val exactLease = lease

            val operationId = KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = exactLease
            )
            assertNotNull(operationId)

            assertEquals(KeplerActiveOperationKind.PUBLIC_EXPORT, exactLease.currentDurableOperationKind())
        } finally {
            directory.deleteRecursively()
        }
    }
}