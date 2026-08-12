package com.projectnuke.keplernightlab

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

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
    val state: ProcessingArtifactJournalState,
    val createdAt: Long,
    val updatedAt: Long
) {
    private fun fileFor(jobDir: File): File = Companion.fileFor(jobDir, transactionId)

    fun writeTo(jobDir: File) {
        validateNames()
        KeplerJobMetadata.atomicWrite(fileFor(jobDir), toJson().toString(2))
    }

    fun transition(jobDir: File, next: ProcessingArtifactJournalState): ProcessingArtifactJournal =
        copy(state = next, updatedAt = System.currentTimeMillis()).also { it.writeTo(jobDir) }

    fun deleteIfOwned(jobDir: File) {
        val file = fileFor(jobDir)
        if (NoFollowFileSystem.isRealFile(file.toPath())) Files.deleteIfExists(file.toPath())
    }

    private fun validateNames() {
        listOf(finalName, tempName, priorName).forEach { name ->
            require(name.isNotBlank() && name != "." && name != "..")
            require(File(name).name == name && !name.contains('/') && !name.contains('\\'))
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("transactionId", transactionId)
        .put("processingAttemptId", processingAttemptId ?: JSONObject.NULL)
        .put("runtimeSessionId", runtimeSessionId)
        .put("artifactType", artifactType)
        .put("finalFilename", finalName)
        .put("tempFilename", tempName)
        .put("priorFilename", priorName)
        .put("state", state.name)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        private const val PREFIX = ".processing_tx_"
        private const val SUFFIX = ".json"

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
            finalName, tempName, priorName, ProcessingArtifactJournalState.PREPARED, now, now
        ).also { it.writeTo(jobDir) }

        fun fileFor(jobDir: File, transactionId: String): File =
            File(jobDir, "$PREFIX$transactionId$SUFFIX")

        fun read(file: File): ProcessingArtifactJournal {
            val json = JSONObject(NoFollowFileSystem.readTextVerified(file))
            val state = ProcessingArtifactJournalState.valueOf(json.getString("state"))
            return ProcessingArtifactJournal(
                transactionId = json.getString("transactionId"),
                processingAttemptId = json.optString("processingAttemptId").takeIf { it.isNotBlank() && it != "null" },
                runtimeSessionId = json.getString("runtimeSessionId"),
                artifactType = json.getString("artifactType"),
                finalName = json.getString("finalFilename"),
                tempName = json.getString("tempFilename"),
                priorName = json.getString("priorFilename"),
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
    jobDir: File,
    verifyArtifact: (File) -> Unit
): List<ProcessingArtifactRecoveryResult> = ProcessingArtifactJournal.list(jobDir).map { journalFile ->
    val journal = runCatching { ProcessingArtifactJournal.read(journalFile) }.getOrElse {
        return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.INVALID_JOURNAL, it.message)
    }
    val final = NoFollowFileSystem.resolveDirectChild(jobDir, journal.finalName, requireFile = true)
    val temp = NoFollowFileSystem.resolveDirectChild(jobDir, journal.tempName, requireFile = true)
    val prior = NoFollowFileSystem.resolveDirectChild(jobDir, journal.priorName, requireFile = true)
    fun valid(file: File?): Boolean = file != null && runCatching { verifyArtifact(file) }.isSuccess
    fun deleteExact(file: File?) { file?.let { settleProcessingArtifactPath(it) } }
    when (journal.state) {
        ProcessingArtifactJournalState.PREPARED,
        ProcessingArtifactJournalState.TEMP_WRITTEN,
        ProcessingArtifactJournalState.TEMP_VERIFIED -> {
            if (final != null && valid(final)) {
                deleteExact(temp)
                journal.transition(jobDir, ProcessingArtifactJournalState.SETTLED).deleteIfOwned(jobDir)
                ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT)
            } else {
                deleteExact(temp)
                journal.transition(jobDir, ProcessingArtifactJournalState.SETTLED).deleteIfOwned(jobDir)
                ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.SETTLED_TEMP)
            }
        }
        ProcessingArtifactJournalState.PRIOR_BACKED_UP,
        ProcessingArtifactJournalState.ROLLBACK_STARTED -> when {
            final == null && valid(prior) -> {
                val priorFile = prior ?: return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS)
                Files.move(priorFile.toPath(), File(jobDir, journal.finalName).toPath(), StandardCopyOption.REPLACE_EXISTING)
                val restored = NoFollowFileSystem.resolveDirectChild(jobDir, journal.finalName, requireFile = true)
                if (!valid(restored)) return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "restored prior failed verification")
                deleteExact(temp)
                journal.transition(jobDir, ProcessingArtifactJournalState.PRIOR_RESTORED).transition(jobDir, ProcessingArtifactJournalState.SETTLED).deleteIfOwned(jobDir)
                ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.RESTORED_PRIOR)
            }
            valid(final) -> {
                deleteExact(temp)
                ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT, "current final preserved; prior evidence retained")
            }
            else -> ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "neither final nor prior is verifiable")
        }
        ProcessingArtifactJournalState.NEW_FINAL_MOVED,
        ProcessingArtifactJournalState.NEW_FINAL_VERIFIED,
        ProcessingArtifactJournalState.ADOPTED -> when {
            valid(final) -> {
                deleteExact(temp)
                deleteExact(prior)
                journal.transition(jobDir, ProcessingArtifactJournalState.SETTLED).deleteIfOwned(jobDir)
                ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT)
            }
            valid(prior) -> {
                val priorFile = prior ?: return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS)
                Files.move(priorFile.toPath(), File(jobDir, journal.finalName).toPath(), StandardCopyOption.REPLACE_EXISTING)
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
