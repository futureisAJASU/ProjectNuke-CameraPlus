package com.projectnuke.keplernightlab

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID
import java.util.concurrent.CancellationException
import org.json.JSONObject

internal class ProcessingArtifactSimulatedCrashForTest : Error("simulated process death")

@Volatile
internal var processingArtifactCrashAfterMoveIntentForTest: Boolean = false

@Volatile
internal var processingArtifactCrashAfterMoveForTest: Boolean = false

internal enum class ProcessingArtifactFailurePoint {
    PLANNED,
    JOURNAL_PREPARE,
    TEMP_WRITE,
    JOURNAL_TEMP_WRITTEN,
    TEMP_VERIFY,
    TEMP_DIGEST,
    JOURNAL_TEMP_VERIFIED,
    PRIOR_DIGEST,
    PRIOR_SEMANTIC_VERIFY,
    JOURNAL_PRIOR_BACKED_UP,
    PRIOR_MOVE,
    JOURNAL_FINAL_MOVE_INTENT,
    FINAL_MOVE,
    JOURNAL_FINAL_MOVED,
    FINAL_VERIFY,
    JOURNAL_FINAL_VERIFIED,
    JOURNAL_ADOPTED,
    PRIOR_BACKUP_CLEANUP,
    TEMP_CLEANUP,
    JOURNAL_SETTLED,
    JOURNAL_DELETE,
    ROLLBACK_JOURNAL_START,
    ROLLBACK_NEW_FINAL_CLEANUP,
    ROLLBACK_TEMP_CLEANUP,
    ROLLBACK_PRIOR_RESTORE_MOVE,
    ROLLBACK_PRIOR_VERIFY,
    ROLLBACK_JOURNAL_SETTLEMENT
}

internal enum class ProcessingArtifactState {
    PLANNED,
    TEMP_OWNED,
    TEMP_VERIFIED,
    PRIOR_FINAL_BACKED_UP,
    COMMITTED_FINAL,
    FINAL_VERIFIED,
    ADOPTED,
    ROLLED_BACK,
    CLEANUP_FAILED
}

internal enum class ProcessingArtifactResourceRole {
    TEMPORARY,
    NEW_FINAL,
    PRIOR_BACKUP,
    ADOPTED_FINAL,
    RESTORED_PRIOR
}

internal enum class ProcessingArtifactSettlementStatus {
    NOT_ATTEMPTED,
    ABSENT,
    DELETED,
    RESTORED,
    RESTORED_UNVERIFIED,
    ADOPTED,
    DELETE_FAILED,
    RESTORE_MOVE_FAILED
}

@Volatile
internal var processingArtifactDeleteFailureForTest: Boolean = false

@Volatile
internal var processingArtifactDeleteErrorForTest: Error? = null

internal data class ProcessingArtifactSettlementRecord(
    val path: File,
    val role: ProcessingArtifactResourceRole,
    val status: ProcessingArtifactSettlementStatus,
    val failure: Throwable? = null
)

internal data class ProcessingResourceSettlementRecord(
    val resource: String,
    val status: String,
    val failure: Throwable? = null,
    val identity: String? = null,
    val operation: String? = null
)

internal data class ProcessingArtifactResult(
    val finalFile: File,
    val state: ProcessingArtifactState,
    val cleanupFailure: Throwable? = null,
    val settlements: List<ProcessingArtifactSettlementRecord> = emptyList(),
    /** Whether a verified prior final existed and was moved into the transaction. */
    val hadPriorFinal: Boolean = false,
    /** Whether rollback restored and verified the prior final. */
    val priorFinalRestored: Boolean = false
)

internal data class ProcessingArtifactSettlementReport(
    val state: ProcessingArtifactState,
    val settlements: List<ProcessingArtifactSettlementRecord>,
    val cleanupFailure: Throwable?,
    val hadPriorFinal: Boolean,
    val priorFinalRestored: Boolean
)

/**
 * Raised when an artifact could not be committed or verified. All paths and
 * per-resource settlement evidence remain attached to the exception so a
 * caller can retain exact cleanup/recovery debt.
 */
internal class ProcessingArtifactException(
    val finalFile: File,
    val tempFile: File,
    val cleanupFailure: Throwable?,
    cause: Throwable,
    val settlements: List<ProcessingArtifactSettlementRecord> = emptyList(),
    val priorBackupFile: File? = null,
    val failurePoint: ProcessingArtifactFailurePoint = ProcessingArtifactFailurePoint.PLANNED,
    val rollbackFailurePoint: ProcessingArtifactFailurePoint? = null
) : IllegalStateException("Processing artifact transaction failed for ${finalFile.absolutePath}", cause)

private fun existingRegularArtifact(file: File): Boolean {
    val path = file.toPath()
    check(!Files.isSymbolicLink(path)) { "Artifact destination must not be a symbolic link" }
    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
}

private fun moveArtifact(source: File, destination: File) {
    check(!Files.isSymbolicLink(destination.toPath())) {
        "Artifact destination must not be a symbolic link"
    }
    try {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

internal fun settleProcessingArtifactPath(
    file: File,
    role: ProcessingArtifactResourceRole = ProcessingArtifactResourceRole.TEMPORARY
): ProcessingArtifactSettlementRecord {
    return try {
        if (processingArtifactDeleteFailureForTest) {
            return ProcessingArtifactSettlementRecord(
                file,
                role,
                ProcessingArtifactSettlementStatus.DELETE_FAILED,
                IllegalStateException("Injected processing artifact cleanup failure")
            )
        }
        processingArtifactDeleteErrorForTest?.let { throw it }
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            ProcessingArtifactSettlementRecord(file, role, ProcessingArtifactSettlementStatus.ABSENT)
        } else if (Files.isSymbolicLink(file.toPath())) {
            val failure = IllegalStateException("Refusing to delete symlink artifact ${file.absolutePath}")
            ProcessingArtifactSettlementRecord(file, role, ProcessingArtifactSettlementStatus.DELETE_FAILED, failure)
        } else if (Files.deleteIfExists(file.toPath())) {
            ProcessingArtifactSettlementRecord(file, role, ProcessingArtifactSettlementStatus.DELETED)
        } else {
            ProcessingArtifactSettlementRecord(
                file,
                role,
                ProcessingArtifactSettlementStatus.DELETE_FAILED,
                IllegalStateException("Could not delete artifact ${file.absolutePath}")
            )
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Error) {
        throw failure
    } catch (failure: Exception) {
        ProcessingArtifactSettlementRecord(file, role, ProcessingArtifactSettlementStatus.DELETE_FAILED, failure)
    }
}

internal fun commitProcessingArtifact(
    finalFile: File,
    writeTemp: (File) -> Unit,
    verifyFinal: (File) -> Unit,
    cancellation: KeplerPipelineCancellation? = null,
    onSettlement: ((ProcessingArtifactSettlementReport) -> Unit)? = null,
    processingAttemptId: String? = null,
    claimKey: String? = null,
    move: (File, File) -> Unit = ::moveArtifact
): ProcessingArtifactResult {
    val parent = finalFile.parentFile ?: error("Artifact parent is missing")
    require(NoFollowFileSystem.isRealDirectory(parent.toPath())) { "Artifact parent must be a real directory" }
    check(!Files.isSymbolicLink(finalFile.toPath())) { "Artifact destination must not be a symbolic link" }

    val temp = File(parent, ".${finalFile.name}.${UUID.randomUUID()}.tmp")
    val priorBackup = File(parent, ".${finalFile.name}.${UUID.randomUUID()}.prior")
    val settlements = mutableListOf<ProcessingArtifactSettlementRecord>()
    var state = ProcessingArtifactState.PLANNED
    var priorBackedUp = false
    var newFinalCommitted = false
    var priorRestored = false
    var finalAdopted = false
    var failurePoint = ProcessingArtifactFailurePoint.PLANNED
    var rollbackFailurePoint: ProcessingArtifactFailurePoint? = null

    var journal: ProcessingArtifactJournal
    try {
        journal = ProcessingArtifactJournal.create(
            jobDir = parent,
            transactionId = UUID.randomUUID().toString(),
            processingAttemptId = processingAttemptId,
            artifactType = finalFile.extension.ifBlank { "UNKNOWN" },
            finalName = finalFile.name,
            tempName = temp.name,
            priorName = priorBackup.name,
            claimKey = claimKey
        )
    } catch (failure: Throwable) {
        if (failure is ProcessingArtifactSimulatedCrashForTest) throw failure
        if (failure is Error || failure is CancellationException) throw failure
        if (failure is ProcessingArtifactClaimConflictException) throw failure
        throw ProcessingArtifactException(
            finalFile = finalFile,
            tempFile = temp,
            cleanupFailure = null,
            cause = failure,
            settlements = emptyList(),
            priorBackupFile = null,
            failurePoint = ProcessingArtifactFailurePoint.JOURNAL_PREPARE,
            rollbackFailurePoint = null
        )
    }

    fun journalTransition(
        next: ProcessingArtifactJournalState,
        evidence: NoFollowFileSystem.StreamDigest? = null,
        priorSemanticVerified: Boolean = journal.priorSemanticVerified,
        adoptedResult: String? = journal.adoptedResult,
        noOutputDisposition: String? = journal.noOutputDisposition,
        claimKey: String? = journal.claimKey
    ) {
        journal = journal.transition(
            parent,
            next,
            verificationKindOverride = evidence?.let { finalFile.extension.uppercase().ifBlank { "BINARY" } } ?: journal.verificationKind,
            expectedSizeBytesOverride = evidence?.size ?: journal.expectedSizeBytes,
            expectedSha256Override = evidence?.sha256 ?: journal.expectedSha256,
            priorSemanticVerifiedOverride = priorSemanticVerified,
            adoptedResultOverride = adoptedResult,
            noOutputDispositionOverride = noOutputDisposition,
            claimKeyOverride = claimKey
        )
    }

    fun notifySettlement(report: ProcessingArtifactSettlementReport) {
        try {
            onSettlement?.invoke(report)
        } catch (observerFailure: Throwable) {
            // Settlement observers are diagnostic only and must never replace
            // the primary transaction result/failure.
        }
    }

    fun checkCancelled() {
        cancellation?.throwIfCancelled()
    }

    try {
        checkCancelled()
        check(!Files.exists(temp.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Artifact temp already exists" }
        state = ProcessingArtifactState.TEMP_OWNED
        failurePoint = ProcessingArtifactFailurePoint.TEMP_WRITE
        writeTemp(temp)
        failurePoint = ProcessingArtifactFailurePoint.JOURNAL_TEMP_WRITTEN
        journalTransition(ProcessingArtifactJournalState.TEMP_WRITTEN)
        check(Files.isRegularFile(temp.toPath(), LinkOption.NOFOLLOW_LINKS) && temp.length() > 0L) { "Artifact temp verification failed" }
        failurePoint = ProcessingArtifactFailurePoint.TEMP_VERIFY
        verifyFinal(temp)
        failurePoint = ProcessingArtifactFailurePoint.TEMP_DIGEST
        val tempEvidence = NoFollowFileSystem.digestVerified(temp)
        state = ProcessingArtifactState.TEMP_VERIFIED
        failurePoint = ProcessingArtifactFailurePoint.JOURNAL_TEMP_VERIFIED
        journalTransition(ProcessingArtifactJournalState.TEMP_VERIFIED, tempEvidence)
        checkCancelled()

        if (existingRegularArtifact(finalFile)) {
            failurePoint = ProcessingArtifactFailurePoint.PRIOR_DIGEST
            val priorEvidence = NoFollowFileSystem.digestVerified(finalFile)
            failurePoint = ProcessingArtifactFailurePoint.PRIOR_SEMANTIC_VERIFY
            val priorSemanticEvidence = try {
                verifyFinal(finalFile)
                true
            } catch (failure: Error) {
                throw failure
            } catch (_: Exception) {
                false
            }
            failurePoint = ProcessingArtifactFailurePoint.JOURNAL_PRIOR_BACKED_UP
            journal = journal.transition(
                parent,
                ProcessingArtifactJournalState.PRIOR_BACKED_UP,
                priorExpectedSizeBytesOverride = priorEvidence.size,
                priorExpectedSha256Override = priorEvidence.sha256,
                priorSemanticVerifiedOverride = priorSemanticEvidence
            )
            failurePoint = ProcessingArtifactFailurePoint.PRIOR_MOVE
            move(finalFile, priorBackup)
            priorBackedUp = true
            state = ProcessingArtifactState.PRIOR_FINAL_BACKED_UP
        }

        checkCancelled()
        failurePoint = ProcessingArtifactFailurePoint.JOURNAL_FINAL_MOVE_INTENT
        journalTransition(ProcessingArtifactJournalState.NEW_FINAL_MOVE_STARTED)
        if (processingArtifactCrashAfterMoveIntentForTest) throw ProcessingArtifactSimulatedCrashForTest()
        failurePoint = ProcessingArtifactFailurePoint.FINAL_MOVE
        move(temp, finalFile)
        newFinalCommitted = true
        state = ProcessingArtifactState.COMMITTED_FINAL
        if (processingArtifactCrashAfterMoveForTest) throw ProcessingArtifactSimulatedCrashForTest()
        failurePoint = ProcessingArtifactFailurePoint.JOURNAL_FINAL_MOVED
        journalTransition(ProcessingArtifactJournalState.NEW_FINAL_MOVED)

        // Once commit begins, cancellation cannot interrupt ownership. Verify
        // the actual final pathname and either adopt it or roll back safely.
        failurePoint = ProcessingArtifactFailurePoint.FINAL_VERIFY
        verifyFinal(finalFile)
        state = ProcessingArtifactState.FINAL_VERIFIED
        failurePoint = ProcessingArtifactFailurePoint.JOURNAL_FINAL_VERIFIED
        journalTransition(ProcessingArtifactJournalState.NEW_FINAL_VERIFIED)
        settlements += ProcessingArtifactSettlementRecord(
            finalFile,
            ProcessingArtifactResourceRole.ADOPTED_FINAL,
            ProcessingArtifactSettlementStatus.ADOPTED
        )
        state = ProcessingArtifactState.ADOPTED
        finalAdopted = true
        failurePoint = ProcessingArtifactFailurePoint.JOURNAL_ADOPTED
        journalTransition(ProcessingArtifactJournalState.ADOPTED, adoptedResult = "NEW_FINAL", claimKey = claimKey)

        if (priorBackedUp) {
            failurePoint = ProcessingArtifactFailurePoint.PRIOR_BACKUP_CLEANUP
            val backupSettlement = settleProcessingArtifactPath(priorBackup, ProcessingArtifactResourceRole.PRIOR_BACKUP)
            settlements += backupSettlement
            if (backupSettlement.status == ProcessingArtifactSettlementStatus.DELETE_FAILED) {
                state = ProcessingArtifactState.CLEANUP_FAILED
            }
        }
        settlements += ProcessingArtifactSettlementRecord(
            temp,
            ProcessingArtifactResourceRole.TEMPORARY,
            ProcessingArtifactSettlementStatus.ABSENT
        )
        val result = ProcessingArtifactResult(
            finalFile = finalFile,
            state = state,
            cleanupFailure = settlements.firstOrNull { it.failure != null }?.failure,
            settlements = settlements.toList(),
            hadPriorFinal = priorBackedUp,
            priorFinalRestored = false
        )
        if (claimKey == null) {
            failurePoint = ProcessingArtifactFailurePoint.JOURNAL_SETTLED
            journalTransition(ProcessingArtifactJournalState.SETTLED, adoptedResult = "NEW_FINAL")
            failurePoint = ProcessingArtifactFailurePoint.JOURNAL_DELETE
            journal.deleteIfOwned(parent)
        }
        notifySettlement(
            ProcessingArtifactSettlementReport(
                state = result.state,
                settlements = result.settlements,
                cleanupFailure = result.cleanupFailure,
                hadPriorFinal = result.hadPriorFinal,
                priorFinalRestored = result.priorFinalRestored
            )
        )
        return result
    } catch (failure: Throwable) {
        if (failure is ProcessingArtifactSimulatedCrashForTest) throw failure
        val capturedFailurePoint = failurePoint
        // ADOPTED is a durable ownership boundary.  Optional cleanup or
        // settlement failures after it must retain the current final and the
        // journal evidence; this is not a rollback cut.
        if (finalAdopted) {
            val adoptedCleanup = mutableListOf<ProcessingArtifactSettlementRecord>()
            var fatalAdoptedCleanupFailure: Error? = null
            var cancellationAdoptedCleanupFailure: CancellationException? = null
            failurePoint = ProcessingArtifactFailurePoint.TEMP_CLEANUP
            try {
                adoptedCleanup += settleProcessingArtifactPath(temp, ProcessingArtifactResourceRole.TEMPORARY)
            } catch (secondary: Throwable) {
                if (secondary !== failure) {
                    if (secondary is Error && failure !is Error) {
                        secondary.addSuppressed(failure)
                        fatalAdoptedCleanupFailure = secondary
                    } else if (secondary is CancellationException && failure !is Error && failure !is CancellationException) {
                        secondary.addSuppressed(failure)
                        cancellationAdoptedCleanupFailure = secondary
                    } else {
                        failure.addSuppressed(secondary)
                    }
                }
                adoptedCleanup += ProcessingArtifactSettlementRecord(
                    temp,
                    ProcessingArtifactResourceRole.TEMPORARY,
                    ProcessingArtifactSettlementStatus.DELETE_FAILED,
                    secondary
                )
            }
            adoptedCleanup += ProcessingArtifactSettlementRecord(
                temp,
                ProcessingArtifactResourceRole.TEMPORARY,
                ProcessingArtifactSettlementStatus.ABSENT
            )
            val adoptedSettlements = settlements + adoptedCleanup
            val adoptedCleanupFailure = adoptedSettlements.firstOrNull { it.failure != null }?.failure
            state = ProcessingArtifactState.CLEANUP_FAILED
            notifySettlement(
                ProcessingArtifactSettlementReport(
                    state = state,
                    settlements = adoptedSettlements,
                    cleanupFailure = adoptedCleanupFailure,
                    hadPriorFinal = priorBackedUp,
                    priorFinalRestored = false
                )
            )
            fatalAdoptedCleanupFailure?.let { throw it }
            if (failure is Error || failure is CancellationException) throw failure
            cancellationAdoptedCleanupFailure?.let { throw it }
            throw ProcessingArtifactException(
                finalFile = finalFile,
                tempFile = temp,
                cleanupFailure = adoptedCleanupFailure,
                cause = failure,
                settlements = adoptedSettlements,
                priorBackupFile = priorBackup.takeIf { priorBackedUp && it.exists() },
                failurePoint = capturedFailurePoint,
                rollbackFailurePoint = null
            )
        }
        failurePoint = ProcessingArtifactFailurePoint.ROLLBACK_JOURNAL_START
        var rollbackSecondaryFailure: Throwable? = null
        fun captureRollbackFailure(secondary: Throwable, point: ProcessingArtifactFailurePoint) {
            if (secondary === failure) return
            if (rollbackFailurePoint == null) {
                rollbackFailurePoint = point
            }
            rollbackSecondaryFailure = combineSettlementFailure(rollbackSecondaryFailure, secondary)
        }
        try {
            journalTransition(ProcessingArtifactJournalState.ROLLBACK_STARTED)
        } catch (secondary: Throwable) {
            captureRollbackFailure(secondary, ProcessingArtifactFailurePoint.ROLLBACK_JOURNAL_START)
        }
        val cleanupRecords = mutableListOf<ProcessingArtifactSettlementRecord>()
        if (newFinalCommitted) {
            failurePoint = ProcessingArtifactFailurePoint.ROLLBACK_NEW_FINAL_CLEANUP
            try {
                cleanupRecords += settleProcessingArtifactPath(finalFile, ProcessingArtifactResourceRole.NEW_FINAL)
            } catch (secondary: Throwable) {
                captureRollbackFailure(secondary, ProcessingArtifactFailurePoint.ROLLBACK_NEW_FINAL_CLEANUP)
                cleanupRecords += ProcessingArtifactSettlementRecord(
                    finalFile,
                    ProcessingArtifactResourceRole.NEW_FINAL,
                    ProcessingArtifactSettlementStatus.DELETE_FAILED,
                    secondary
                )
            }
        } else {
            cleanupRecords += ProcessingArtifactSettlementRecord(
                finalFile,
                ProcessingArtifactResourceRole.NEW_FINAL,
                ProcessingArtifactSettlementStatus.NOT_ATTEMPTED
            )
        }
        failurePoint = ProcessingArtifactFailurePoint.ROLLBACK_TEMP_CLEANUP
        try {
            cleanupRecords += settleProcessingArtifactPath(temp, ProcessingArtifactResourceRole.TEMPORARY)
        } catch (secondary: Throwable) {
            captureRollbackFailure(secondary, ProcessingArtifactFailurePoint.ROLLBACK_TEMP_CLEANUP)
            cleanupRecords += ProcessingArtifactSettlementRecord(
                temp,
                ProcessingArtifactResourceRole.TEMPORARY,
                ProcessingArtifactSettlementStatus.DELETE_FAILED,
                secondary
            )
        }

        if (priorBackedUp) {
            var restoreMoveSucceeded = false
            failurePoint = ProcessingArtifactFailurePoint.ROLLBACK_PRIOR_RESTORE_MOVE
            try {
                move(priorBackup, finalFile)
                restoreMoveSucceeded = true
            } catch (restoreMoveFailure: Throwable) {
                captureRollbackFailure(restoreMoveFailure, ProcessingArtifactFailurePoint.ROLLBACK_PRIOR_RESTORE_MOVE)
                cleanupRecords += ProcessingArtifactSettlementRecord(
                    priorBackup,
                    ProcessingArtifactResourceRole.PRIOR_BACKUP,
                    ProcessingArtifactSettlementStatus.RESTORE_MOVE_FAILED,
                    restoreMoveFailure
                )
            }
            if (restoreMoveSucceeded) {
                failurePoint = ProcessingArtifactFailurePoint.ROLLBACK_PRIOR_VERIFY
                try {
                    verifyFinal(finalFile)
                    priorRestored = true
                    cleanupRecords += ProcessingArtifactSettlementRecord(
                        priorBackup,
                        ProcessingArtifactResourceRole.PRIOR_BACKUP,
                        ProcessingArtifactSettlementStatus.ABSENT
                    )
                } catch (restoreVerificationFailure: Throwable) {
                    captureRollbackFailure(restoreVerificationFailure, ProcessingArtifactFailurePoint.ROLLBACK_PRIOR_VERIFY)
                    cleanupRecords += ProcessingArtifactSettlementRecord(
                        finalFile,
                        ProcessingArtifactResourceRole.RESTORED_PRIOR,
                        ProcessingArtifactSettlementStatus.RESTORED_UNVERIFIED,
                        restoreVerificationFailure
                    )
                }
                if (priorRestored) {
                    failurePoint = ProcessingArtifactFailurePoint.ROLLBACK_JOURNAL_SETTLEMENT
                    try {
                        journalTransition(ProcessingArtifactJournalState.PRIOR_RESTORED, adoptedResult = "PRIOR_FINAL")
                        cleanupRecords += ProcessingArtifactSettlementRecord(
                            finalFile,
                            ProcessingArtifactResourceRole.RESTORED_PRIOR,
                            ProcessingArtifactSettlementStatus.RESTORED
                        )
                    } catch (journalFailure: Throwable) {
                        captureRollbackFailure(journalFailure, ProcessingArtifactFailurePoint.ROLLBACK_JOURNAL_SETTLEMENT)
                        cleanupRecords += ProcessingArtifactSettlementRecord(
                            finalFile,
                            ProcessingArtifactResourceRole.RESTORED_PRIOR,
                            ProcessingArtifactSettlementStatus.RESTORE_MOVE_FAILED,
                            journalFailure
                        )
                    }
                }
            }
        }

        val allSettlements = settlements + cleanupRecords
        val cleanupFailure = allSettlements.firstOrNull { it.failure != null }?.failure
        if (cleanupFailure != null) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            state = ProcessingArtifactState.CLEANUP_FAILED
        } else {
            state = ProcessingArtifactState.ROLLED_BACK
            if (priorRestored || !priorBackedUp) {
                failurePoint = ProcessingArtifactFailurePoint.ROLLBACK_JOURNAL_SETTLEMENT
                try {
                    journalTransition(
                        if (priorRestored) ProcessingArtifactJournalState.PRIOR_RESTORED else ProcessingArtifactJournalState.SETTLED,
                        adoptedResult = if (priorRestored) "PRIOR_FINAL" else "NO_OUTPUT",
                        noOutputDisposition = when {
                            priorRestored -> null
                            finalFile.isFile -> "PREVIOUS_FINAL_UNTOUCHED"
                            else -> "FINAL_ABSENT"
                        }
                    )
                    journal.deleteIfOwned(parent)
                } catch (secondary: Throwable) {
                    captureRollbackFailure(secondary, ProcessingArtifactFailurePoint.ROLLBACK_JOURNAL_SETTLEMENT)
                }
            }
        }
        notifySettlement(
            ProcessingArtifactSettlementReport(
                state = state,
                settlements = allSettlements,
                cleanupFailure = cleanupFailure,
                hadPriorFinal = priorBackedUp,
                priorFinalRestored = priorRestored
            )
        )
        val rollbackFailure = combineSettlementFailure(rollbackSecondaryFailure, cleanupFailure)
        val combinedFailure = combineSettlementFailure(failure, rollbackFailure)
        if (combinedFailure is Error || combinedFailure is CancellationException) throw combinedFailure
        throw ProcessingArtifactException(
            finalFile = finalFile,
            tempFile = temp,
            cleanupFailure = rollbackFailure,
            cause = failure,
            settlements = allSettlements,
            priorBackupFile = priorBackup.takeIf { priorBackedUp && it.exists() },
            failurePoint = capturedFailurePoint,
            rollbackFailurePoint = rollbackFailurePoint
        )
    }
}

// Binary/source compatibility for callers compiled before the diagnostic
// settlement observer was added. The observer-aware overload remains the
// authoritative implementation.
internal fun commitProcessingArtifact(
    finalFile: File,
    writeTemp: (File) -> Unit,
    verifyFinal: (File) -> Unit,
    cancellation: KeplerPipelineCancellation?
): ProcessingArtifactResult = commitProcessingArtifact(
    finalFile = finalFile,
    writeTemp = writeTemp,
    verifyFinal = verifyFinal,
    cancellation = cancellation,
    onSettlement = null
)

internal fun writeVerifiedJsonArtifact(
    finalFile: File,
    text: String,
    onSettlement: ((ProcessingArtifactSettlementReport) -> Unit)? = null
): ProcessingArtifactResult =
    commitProcessingArtifact(
        finalFile = finalFile,
        onSettlement = onSettlement,
        writeTemp = { temp ->
            FileOutputStream(temp).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        },
        verifyFinal = { committed ->
            val verified = NoFollowFileSystem.readTextVerified(committed)
            check(verified.isNotBlank()) { "JSON artifact is empty" }
            check(verified.trim().let { it.startsWith("{") && it.endsWith("}") }) {
                "JSON artifact is not an object"
            }
            JSONObject(verified)
        }
    )

internal fun copyVerifiedArtifact(
    sourceFile: File,
    finalFile: File,
    onSettlement: ((ProcessingArtifactSettlementReport) -> Unit)? = null
): ProcessingArtifactResult {
    val sourceEvidence = NoFollowFileSystem.digestVerified(sourceFile)
    return commitProcessingArtifact(
        finalFile = finalFile,
        onSettlement = onSettlement,
        writeTemp = { temp ->
            FileOutputStream(temp).use { output ->
                NoFollowFileSystem.copyVerified(sourceFile, output)
                output.flush()
                output.fd.sync()
            }
        },
        verifyFinal = { committed ->
            val committedEvidence = NoFollowFileSystem.digestVerified(committed)
            check(committedEvidence.size == sourceEvidence.size) { "Copied artifact size mismatch" }
            check(committedEvidence.sha256.equals(sourceEvidence.sha256, ignoreCase = true)) {
                "Copied artifact digest mismatch"
            }
        }
    )
}

internal fun verifyPngArtifact(file: File, expectedWidth: Int? = null, expectedHeight: Int? = null) {
    val prefix = NoFollowFileSystem.digestVerified(file).prefix
    check(prefix.size >= 8 && prefix.copyOf(8).contentEquals(
        byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    )) { "Invalid PNG artifact ${file.name}" }
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    check(NoFollowFileSystem.decodeBitmapVerified(file, bounds) != null) {
        "PNG decode failed ${file.name}"
    }
    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "PNG dimensions are invalid" }
    expectedWidth?.let { check(bounds.outWidth == it) { "PNG width mismatch" } }
    expectedHeight?.let { check(bounds.outHeight == it) { "PNG height mismatch" } }
}

internal fun verifyJpegArtifact(file: File, expectedWidth: Int? = null, expectedHeight: Int? = null) {
    val prefix = NoFollowFileSystem.digestVerified(file).prefix
    check(prefix.size >= 2 && prefix[0] == 0xFF.toByte() && prefix[1] == 0xD8.toByte()) {
        "Invalid JPEG artifact ${file.name}"
    }
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    check(NoFollowFileSystem.decodeBitmapVerified(file, bounds) != null) {
        "JPEG decode failed ${file.name}"
    }
    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "JPEG dimensions are invalid" }
    expectedWidth?.let { check(bounds.outWidth == it) { "JPEG width mismatch" } }
    expectedHeight?.let { check(bounds.outHeight == it) { "JPEG height mismatch" } }
}
