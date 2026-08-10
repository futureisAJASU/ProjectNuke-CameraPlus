package com.projectnuke.keplernightlab

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID
import org.json.JSONObject

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
    RESTORE_FAILED
}

internal data class ProcessingArtifactSettlementRecord(
    val path: File,
    val role: ProcessingArtifactResourceRole,
    val status: ProcessingArtifactSettlementStatus,
    val failure: Throwable? = null
)

internal data class ProcessingResourceSettlementRecord(
    val resource: String,
    val status: String,
    val failure: Throwable? = null
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
    val priorBackupFile: File? = null
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
    } catch (failure: Throwable) {
        ProcessingArtifactSettlementRecord(file, role, ProcessingArtifactSettlementStatus.DELETE_FAILED, failure)
    }
}

internal fun commitProcessingArtifact(
    finalFile: File,
    writeTemp: (File) -> Unit,
    verifyFinal: (File) -> Unit,
    cancellation: KeplerPipelineCancellation? = null,
    onSettlement: ((ProcessingArtifactSettlementReport) -> Unit)? = null
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
        writeTemp(temp)
        check(Files.isRegularFile(temp.toPath(), LinkOption.NOFOLLOW_LINKS) && temp.length() > 0L) {
            "Artifact temp verification failed"
        }
        state = ProcessingArtifactState.TEMP_VERIFIED
        checkCancelled()

        if (existingRegularArtifact(finalFile)) {
            moveArtifact(finalFile, priorBackup)
            priorBackedUp = true
            state = ProcessingArtifactState.PRIOR_FINAL_BACKED_UP
        }

        checkCancelled()
        moveArtifact(temp, finalFile)
        newFinalCommitted = true
        state = ProcessingArtifactState.COMMITTED_FINAL

        // Once commit begins, cancellation cannot interrupt ownership. Verify
        // the actual final pathname and either adopt it or roll back safely.
        verifyFinal(finalFile)
        state = ProcessingArtifactState.FINAL_VERIFIED
        settlements += ProcessingArtifactSettlementRecord(
            finalFile,
            ProcessingArtifactResourceRole.ADOPTED_FINAL,
            ProcessingArtifactSettlementStatus.ADOPTED
        )
        state = ProcessingArtifactState.ADOPTED

        if (priorBackedUp) {
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
        val cleanupRecords = mutableListOf<ProcessingArtifactSettlementRecord>()
        if (newFinalCommitted) {
            cleanupRecords += settleProcessingArtifactPath(finalFile, ProcessingArtifactResourceRole.NEW_FINAL)
        } else {
            cleanupRecords += ProcessingArtifactSettlementRecord(
                finalFile,
                ProcessingArtifactResourceRole.NEW_FINAL,
                ProcessingArtifactSettlementStatus.NOT_ATTEMPTED
            )
        }
        cleanupRecords += settleProcessingArtifactPath(temp, ProcessingArtifactResourceRole.TEMPORARY)

        if (priorBackedUp) {
            try {
                moveArtifact(priorBackup, finalFile)
                verifyFinal(finalFile)
                priorRestored = true
                cleanupRecords += ProcessingArtifactSettlementRecord(
                    priorBackup,
                    ProcessingArtifactResourceRole.PRIOR_BACKUP,
                    ProcessingArtifactSettlementStatus.RESTORED
                )
                cleanupRecords += ProcessingArtifactSettlementRecord(
                    finalFile,
                    ProcessingArtifactResourceRole.RESTORED_PRIOR,
                    ProcessingArtifactSettlementStatus.RESTORED
                )
            } catch (restoreFailure: Throwable) {
                cleanupRecords += ProcessingArtifactSettlementRecord(
                    finalFile,
                    ProcessingArtifactResourceRole.RESTORED_PRIOR,
                    ProcessingArtifactSettlementStatus.RESTORED_UNVERIFIED,
                    restoreFailure
                )
            }
        }

        val allSettlements = settlements + cleanupRecords
        val cleanupFailure = allSettlements.firstOrNull { it.failure != null }?.failure
        if (cleanupFailure != null) {
            failure.addSuppressed(cleanupFailure)
            state = ProcessingArtifactState.CLEANUP_FAILED
        } else {
            state = ProcessingArtifactState.ROLLED_BACK
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
        if (failure is Error || failure is java.util.concurrent.CancellationException) throw failure
        throw ProcessingArtifactException(
            finalFile = finalFile,
            tempFile = temp,
            cleanupFailure = cleanupFailure,
            cause = failure,
            settlements = allSettlements,
            priorBackupFile = priorBackup.takeIf { priorBackedUp && it.exists() }
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
): ProcessingArtifactResult =
    commitProcessingArtifact(
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
            check(NoFollowFileSystem.digestVerified(committed).size > 0L) {
                "Copied artifact is empty"
            }
        }
    )

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
