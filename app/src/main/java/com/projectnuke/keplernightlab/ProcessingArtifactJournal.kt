package com.projectnuke.keplernightlab

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

internal enum class ProcessingArtifactJournalState {
    PREPARED,
    TEMP_WRITTEN,
    TEMP_VERIFIED,
    PRIOR_BACKED_UP,
    NEW_FINAL_MOVED,
    NEW_FINAL_VERIFIED,
    ADOPTED,
    ROLLBACK_STARTED,
    PRIOR_RESTORED,
    SETTLED
}

internal data class ProcessingArtifactJournal(
    val transactionId: String,
    val processingAttemptId: String?,
    val runtimeSessionId: String,
    val artifactType: String,
    val finalName: String,
    val tempName: String,
    val priorName: String,
    val verificationKind: String? = null,
    val expectedSizeBytes: Long? = null,
    val expectedSha256: String? = null,
    val priorExpectedSizeBytes: Long? = null,
    val priorExpectedSha256: String? = null,
    val state: ProcessingArtifactJournalState,
    val createdAt: Long,
    val updatedAt: Long
) {
    private fun fileFor(jobDir: File): File = Companion.fileFor(jobDir, transactionId)

    fun writeTo(jobDir: File) {
        require(NoFollowFileSystem.isRealDirectory(jobDir.toPath()))
        require(transactionId.matches(UUID_PATTERN))
        validateNames()
        KeplerJobMetadata.atomicWrite(fileFor(jobDir), toJson().toString(2))
    }

    fun transition(
        jobDir: File,
        next: ProcessingArtifactJournalState,
        verificationKindOverride: String? = verificationKind,
        expectedSizeBytesOverride: Long? = expectedSizeBytes,
        expectedSha256Override: String? = expectedSha256,
        priorExpectedSizeBytesOverride: Long? = priorExpectedSizeBytes,
        priorExpectedSha256Override: String? = priorExpectedSha256
    ): ProcessingArtifactJournal = copy(
        state = next,
        verificationKind = verificationKindOverride,
        expectedSizeBytes = expectedSizeBytesOverride,
        expectedSha256 = expectedSha256Override,
        priorExpectedSizeBytes = priorExpectedSizeBytesOverride,
        priorExpectedSha256 = priorExpectedSha256Override,
        updatedAt = System.currentTimeMillis()
    ).also { it.writeTo(jobDir) }

    fun deleteIfOwned(jobDir: File) {
        require(NoFollowFileSystem.isRealDirectory(jobDir.toPath()))
        val file = fileFor(jobDir)
        if (NoFollowFileSystem.isRealFile(file.toPath())) Files.deleteIfExists(file.toPath())
    }

    private fun validateNames() {
        listOf(finalName, tempName, priorName).forEach { name ->
            require(name.isNotBlank() && name != "." && name != "..")
            require(File(name).name == name && !name.contains('/') && !name.contains('\\') && !name.contains(':'))
        }
        require(verificationKind == null || verificationKind.matches(VERIFICATION_KIND_PATTERN))
        require(expectedSizeBytes == null || expectedSizeBytes >= 0L)
        require(expectedSha256 == null || expectedSha256.matches(SHA256_PATTERN))
        require(priorExpectedSizeBytes == null || priorExpectedSizeBytes >= 0L)
        require(priorExpectedSha256 == null || priorExpectedSha256.matches(SHA256_PATTERN))
    }

    fun toJson(): JSONObject = JSONObject()
        .put("transactionId", transactionId)
        .put("processingAttemptId", processingAttemptId ?: JSONObject.NULL)
        .put("runtimeSessionId", runtimeSessionId)
        .put("artifactType", artifactType)
        .put("finalFilename", finalName)
        .put("tempFilename", tempName)
        .put("priorFilename", priorName)
        .put("verificationKind", verificationKind ?: JSONObject.NULL)
        .put("expectedSizeBytes", expectedSizeBytes ?: JSONObject.NULL)
        .put("expectedSha256", expectedSha256 ?: JSONObject.NULL)
        .put("priorExpectedSizeBytes", priorExpectedSizeBytes ?: JSONObject.NULL)
        .put("priorExpectedSha256", priorExpectedSha256 ?: JSONObject.NULL)
        .put("state", state.name)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        private const val PREFIX = ".processing_tx_"
        private const val SUFFIX = ".json"
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
        private val VERIFICATION_KIND_PATTERN = Regex("[A-Z0-9_]{1,40}")

        fun create(
            jobDir: File,
            transactionId: String,
            processingAttemptId: String?,
            artifactType: String,
            finalName: String,
            tempName: String,
            priorName: String,
            now: Long = System.currentTimeMillis()
        ): ProcessingArtifactJournal = ProcessingArtifactJournal(
            transactionId, processingAttemptId, KeplerRuntimeSession.id, artifactType,
            finalName, tempName, priorName, null, null, null, null, null,
            ProcessingArtifactJournalState.PREPARED, now, now
        ).also { it.writeTo(jobDir) }

        fun fileFor(jobDir: File, transactionId: String): File =
            File(jobDir, "$PREFIX${transactionId.also { require(it.matches(UUID_PATTERN)) }}$SUFFIX")

        fun read(file: File): ProcessingArtifactJournal {
            require(NoFollowFileSystem.isRealFile(file.toPath()))
            val parent = file.parentFile ?: error("Processing journal parent missing")
            require(NoFollowFileSystem.isRealDirectory(parent.toPath()))
            val json = JSONObject(NoFollowFileSystem.readTextVerified(file))
            val transactionId = json.getString("transactionId")
            require(transactionId.matches(UUID_PATTERN))
            require(file.name == "$PREFIX$transactionId$SUFFIX")
            val state = ProcessingArtifactJournalState.valueOf(json.getString("state"))
            return ProcessingArtifactJournal(
                transactionId = transactionId,
                processingAttemptId = json.optString("processingAttemptId").takeIf { it.isNotBlank() && it != "null" },
                runtimeSessionId = json.getString("runtimeSessionId"),
                artifactType = json.getString("artifactType"),
                finalName = json.getString("finalFilename"),
                tempName = json.getString("tempFilename"),
                priorName = json.getString("priorFilename"),
                verificationKind = json.optString("verificationKind").takeIf { it.isNotBlank() && it != "null" },
                expectedSizeBytes = json.optLong("expectedSizeBytes").takeIf { json.has("expectedSizeBytes") && !json.isNull("expectedSizeBytes") },
                expectedSha256 = json.optString("expectedSha256").takeIf { it.isNotBlank() && it != "null" },
                priorExpectedSizeBytes = json.optLong("priorExpectedSizeBytes").takeIf { json.has("priorExpectedSizeBytes") && !json.isNull("priorExpectedSizeBytes") },
                priorExpectedSha256 = json.optString("priorExpectedSha256").takeIf { it.isNotBlank() && it != "null" },
                state = state,
                createdAt = json.getLong("createdAt"),
                updatedAt = json.getLong("updatedAt")
            ).also { it.validateNames() }
        }

        fun list(jobDir: File): List<File> = NoFollowFileSystem.requireDirectChildren(jobDir)
            .filter { it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) && NoFollowFileSystem.isRealFile(it.toPath()) }
    }
}

internal enum class ProcessingArtifactRecoveryClassification {
    SETTLED_TEMP,
    RESTORED_PRIOR,
    ADOPTED_CURRENT,
    AMBIGUOUS,
    INVALID_JOURNAL
}

internal data class ProcessingArtifactRecoveryResult(
    val journalFile: File,
    val classification: ProcessingArtifactRecoveryClassification,
    val message: String? = null
)

internal fun recoverProcessingArtifactJournals(
    jobDir: File
): List<ProcessingArtifactRecoveryResult> = ProcessingArtifactJournal.list(jobDir).map { journalFile ->
    val journal = runCatching { ProcessingArtifactJournal.read(journalFile) }.getOrElse {
        return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.INVALID_JOURNAL, it.message)
    }
    val final = NoFollowFileSystem.resolveDirectChild(jobDir, journal.finalName, requireFile = true)
    val temp = NoFollowFileSystem.resolveDirectChild(jobDir, journal.tempName, requireFile = true)
    val prior = NoFollowFileSystem.resolveDirectChild(jobDir, journal.priorName, requireFile = true)
    fun valid(file: File?, prior: Boolean = false): Boolean = file != null && runCatching {
        verifyProcessingArtifactRecovery(journal, file, prior)
    }.isSuccess
    fun deleteExact(file: File?): Boolean = file == null || settleProcessingArtifactPath(file).status != ProcessingArtifactSettlementStatus.DELETE_FAILED
    fun movePriorToFinal(source: File, destination: File) {
        require(NoFollowFileSystem.isRealFile(source.toPath()))
        require(!java.nio.file.Files.isSymbolicLink(destination.toPath()))
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    when (journal.state) {
        ProcessingArtifactJournalState.PREPARED,
        ProcessingArtifactJournalState.TEMP_WRITTEN,
        ProcessingArtifactJournalState.TEMP_VERIFIED -> {
            if (final != null && valid(final)) {
                if (!deleteExact(temp)) return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "temporary cleanup failed")
                journal.transition(jobDir, ProcessingArtifactJournalState.SETTLED).deleteIfOwned(jobDir)
                ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT)
            } else {
                if (!deleteExact(temp)) return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "temporary cleanup failed")
                journal.transition(jobDir, ProcessingArtifactJournalState.SETTLED).deleteIfOwned(jobDir)
                ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.SETTLED_TEMP)
            }
        }
        ProcessingArtifactJournalState.PRIOR_BACKED_UP,
        ProcessingArtifactJournalState.ROLLBACK_STARTED -> when {
            final == null && valid(prior, prior = true) -> {
                val priorFile = prior ?: return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS)
                movePriorToFinal(priorFile, File(jobDir, journal.finalName))
                val restored = NoFollowFileSystem.resolveDirectChild(jobDir, journal.finalName, requireFile = true)
                if (!valid(restored, prior = true)) return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "restored prior failed verification")
                if (!deleteExact(temp)) return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "temporary cleanup failed after prior restoration")
                journal.transition(jobDir, ProcessingArtifactJournalState.PRIOR_RESTORED).transition(jobDir, ProcessingArtifactJournalState.SETTLED).deleteIfOwned(jobDir)
                ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.RESTORED_PRIOR)
            }
            valid(final) -> {
                if (!deleteExact(temp)) return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "temporary cleanup failed")
                val priorSettlement = prior?.let {
                    settleProcessingArtifactPath(it, ProcessingArtifactResourceRole.PRIOR_BACKUP)
                }
                if (priorSettlement != null && priorSettlement.status == ProcessingArtifactSettlementStatus.DELETE_FAILED) {
                    ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "current final verified but prior cleanup failed")
                } else {
                    journal.transition(jobDir, ProcessingArtifactJournalState.SETTLED).deleteIfOwned(jobDir)
                    ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT)
                }
            }
            else -> ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "neither final nor prior is verifiable")
        }
        ProcessingArtifactJournalState.NEW_FINAL_MOVED,
        ProcessingArtifactJournalState.NEW_FINAL_VERIFIED,
        ProcessingArtifactJournalState.ADOPTED -> when {
            valid(final) -> {
                deleteExact(temp)
                val priorSettlement = prior?.let {
                    settleProcessingArtifactPath(it, ProcessingArtifactResourceRole.PRIOR_BACKUP)
                }
                if (priorSettlement != null && priorSettlement.status == ProcessingArtifactSettlementStatus.DELETE_FAILED) {
                    ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "current final verified but prior cleanup failed")
                } else {
                    journal.transition(jobDir, ProcessingArtifactJournalState.SETTLED).deleteIfOwned(jobDir)
                    ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT)
                }
            }
            valid(prior, prior = true) -> {
                val priorFile = prior ?: return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS)
                movePriorToFinal(priorFile, File(jobDir, journal.finalName))
                val restored = NoFollowFileSystem.resolveDirectChild(jobDir, journal.finalName, requireFile = true)
                if (!valid(restored, prior = true)) return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "restored prior failed verification")
                if (!deleteExact(temp)) return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "temporary cleanup failed after prior restoration")
                journal.transition(jobDir, ProcessingArtifactJournalState.PRIOR_RESTORED).transition(jobDir, ProcessingArtifactJournalState.SETTLED).deleteIfOwned(jobDir)
                ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.RESTORED_PRIOR)
            }
            else -> ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "no verifiable candidate")
        }
        ProcessingArtifactJournalState.PRIOR_RESTORED,
        ProcessingArtifactJournalState.SETTLED -> {
            if (valid(final)) journalFile.delete() else return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "settled journal final is not verifiable")
            ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.RESTORED_PRIOR)
        }
    }
}

private fun verifyProcessingArtifactRecovery(journal: ProcessingArtifactJournal, file: File, prior: Boolean) {
    val digest = NoFollowFileSystem.digestVerified(file)
    val expectedSize = if (prior) journal.priorExpectedSizeBytes else journal.expectedSizeBytes
    val expectedSha256 = if (prior) journal.priorExpectedSha256 else journal.expectedSha256
    check(expectedSize != null && expectedSha256 != null) {
        "Processing journal has no durable verification evidence"
    }
    check(digest.size == expectedSize) { "Processing artifact size mismatch" }
    check(digest.sha256.equals(expectedSha256, ignoreCase = true)) { "Processing artifact digest mismatch" }
    when (journal.verificationKind?.uppercase()) {
        "PNG" -> verifyPngArtifact(file)
        "JPG", "JPEG" -> verifyJpegArtifact(file)
        "JSON" -> JSONObject(NoFollowFileSystem.readTextVerified(file))
        else -> Unit
    }
}
