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
    JOB_CLAIM_PERSISTED,
    ROLLBACK_STARTED,
    PRIOR_RESTORED,
    SETTLED
}

internal class ProcessingArtifactClaimConflictException(message: String) : IllegalStateException(message)

private val UNRESOLVED_AUTHORITATIVE_JOURNAL_STATES = setOf(
    ProcessingArtifactJournalState.PREPARED,
    ProcessingArtifactJournalState.TEMP_WRITTEN,
    ProcessingArtifactJournalState.TEMP_VERIFIED,
    ProcessingArtifactJournalState.PRIOR_BACKED_UP,
    ProcessingArtifactJournalState.NEW_FINAL_MOVED,
    ProcessingArtifactJournalState.NEW_FINAL_VERIFIED,
    ProcessingArtifactJournalState.ADOPTED,
    ProcessingArtifactJournalState.JOB_CLAIM_PERSISTED,
    ProcessingArtifactJournalState.ROLLBACK_STARTED,
    ProcessingArtifactJournalState.PRIOR_RESTORED
)

internal fun isUnresolvedAuthoritativeProcessingJournal(journal: ProcessingArtifactJournal): Boolean =
    journal.claimKey != null && journal.processingAttemptId != null &&
        journal.state in UNRESOLVED_AUTHORITATIVE_JOURNAL_STATES

internal fun isKnownProcessingArtifactClaimKey(job: JSONObject?, claimKey: String): Boolean {
    val mode = job?.optString("processingMode").orEmpty().uppercase()
    val jobType = job?.optString("jobType").orEmpty().uppercase()
    return when {
        mode == "CLASSIC_RAW" || jobType == "RAW" || jobType == "RAW_NIGHT_FUSION" -> claimKey == "mergedRawFile"
        mode == "SUPER_RESOLUTION" || jobType.contains("SUPER") -> claimKey == "superResolutionOutputFile"
        mode == "CLASSIC_YUV" || mode == "SINGLE_FRAME" ||
            jobType == "YUV_NIGHT_FUSION" || jobType == "YUV_SINGLE_FRAME" -> claimKey == "finalFile"
        else -> false
    }
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
    val priorSemanticVerified: Boolean = false,
    val adoptedResult: String? = null,
    val claimKey: String? = null,
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
        priorExpectedSha256Override: String? = priorExpectedSha256,
        priorSemanticVerifiedOverride: Boolean = priorSemanticVerified,
        adoptedResultOverride: String? = adoptedResult,
        claimKeyOverride: String? = claimKey
    ): ProcessingArtifactJournal = copy(
        state = next,
        verificationKind = verificationKindOverride,
        expectedSizeBytes = expectedSizeBytesOverride,
        expectedSha256 = expectedSha256Override,
        priorExpectedSizeBytes = priorExpectedSizeBytesOverride,
        priorExpectedSha256 = priorExpectedSha256Override,
        priorSemanticVerified = priorSemanticVerifiedOverride,
        adoptedResult = adoptedResultOverride,
        claimKey = claimKeyOverride,
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
        require(claimKey == null || claimKey.matches(CLAIM_KEY_PATTERN))
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
        .put("priorSemanticVerified", priorSemanticVerified)
        .put("adoptedResult", adoptedResult ?: JSONObject.NULL)
        .put("claimKey", claimKey ?: JSONObject.NULL)
        .put("state", state.name)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        private const val PREFIX = ".processing_tx_"
        private const val SUFFIX = ".json"
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
        private val VERIFICATION_KIND_PATTERN = Regex("[A-Z0-9_]{1,40}")
        private val CLAIM_KEY_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]{0,80}")

        fun create(
            jobDir: File,
            transactionId: String,
            processingAttemptId: String?,
            artifactType: String,
            finalName: String,
            tempName: String,
            priorName: String,
            now: Long = System.currentTimeMillis(),
            claimKey: String? = null
        ): ProcessingArtifactJournal {
            val existing = list(jobDir).mapNotNull { file -> runCatching { read(file) }.getOrNull() }
                .filter { isUnresolvedAuthoritativeProcessingJournal(it) &&
                    it.processingAttemptId == processingAttemptId && it.claimKey == claimKey }
            if (existing.isNotEmpty()) {
                throw ProcessingArtifactClaimConflictException(
                    "An unresolved processing artifact claim already exists for attempt=$processingAttemptId key=$claimKey"
                )
            }
            if (claimKey != null) {
                val job = runCatching { KeplerJobMetadata.read(jobDir) }.getOrNull()
                if (job != null && !isKnownProcessingArtifactClaimKey(job, claimKey)) {
                    throw ProcessingArtifactClaimConflictException(
                        "Claim key $claimKey is not valid for processing mode ${job.optString("processingMode")}"
                    )
                }
            }
            return ProcessingArtifactJournal(
            transactionId, processingAttemptId, KeplerRuntimeSession.id, artifactType,
            finalName, tempName, priorName, null, null, null, null, null, false, null,
            claimKey, ProcessingArtifactJournalState.PREPARED, now, now
            ).also { it.writeTo(jobDir) }
        }

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
                priorSemanticVerified = json.optBoolean("priorSemanticVerified", false),
                adoptedResult = json.optString("adoptedResult").takeIf { it.isNotBlank() && it != "null" },
                claimKey = json.optString("claimKey").takeIf { it.isNotBlank() && it != "null" },
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
    ADOPTED_CURRENT_WITH_CLEANUP_DEBT,
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
    job: JSONObject? = null
): List<ProcessingArtifactRecoveryResult> {
    val journalFiles = ProcessingArtifactJournal.list(jobDir)
    val parsedByFile = journalFiles.associateWith { journalFile ->
        runCatching { ProcessingArtifactJournal.read(journalFile) }
    }
    val parsedJournals = parsedByFile.values.mapNotNull { it.getOrNull() }
    val conflictingTransactions = parsedJournals
        .filter(::isUnresolvedAuthoritativeProcessingJournal)
        .groupBy { it.processingAttemptId to it.claimKey }
        .values
        .filter { it.size > 1 }
        .flatMap { it.map(ProcessingArtifactJournal::transactionId) }
        .toSet()
    val invalidClaimTransactions = parsedJournals
        .filter { it.claimKey != null && !isKnownProcessingArtifactClaimKey(job, it.claimKey) }
        .map { it.transactionId }
        .toSet()

    return journalFiles.map { journalFile ->
    val parsed = parsedByFile.getValue(journalFile)
    if (parsed.isFailure) {
        return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.INVALID_JOURNAL, parsed.exceptionOrNull()?.message)
    }
    val preflightJournal = parsed.getOrThrow()
    if (preflightJournal.transactionId in conflictingTransactions) {
        return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "conflicting authoritative processing artifact claims were preserved")
    }
    if (preflightJournal.transactionId in invalidClaimTransactions) {
        return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.INVALID_JOURNAL, "processing artifact claim key is not valid for this processing mode")
    }
    val journal = preflightJournal
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
            prior == null && journal.priorSemanticVerified && valid(final, prior = true) -> {
                if (!deleteExact(temp)) return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT_WITH_CLEANUP_DEBT, "restored prior verified but temporary cleanup failed")
                journal.transition(jobDir, ProcessingArtifactJournalState.PRIOR_RESTORED, adoptedResultOverride = "PRIOR_FINAL")
                    .transition(jobDir, ProcessingArtifactJournalState.SETTLED, adoptedResultOverride = "PRIOR_FINAL")
                    .deleteIfOwned(jobDir)
                ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.RESTORED_PRIOR)
            }
            final == null && valid(prior, prior = true) -> {
                val priorFile = prior ?: return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS)
                movePriorToFinal(priorFile, File(jobDir, journal.finalName))
                val restored = NoFollowFileSystem.resolveDirectChild(jobDir, journal.finalName, requireFile = true)
                if (!valid(restored, prior = true)) return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "restored prior failed verification")
                if (!deleteExact(temp)) return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "temporary cleanup failed after prior restoration")
                journal.transition(jobDir, ProcessingArtifactJournalState.PRIOR_RESTORED, adoptedResultOverride = "PRIOR_FINAL")
                    .transition(jobDir, ProcessingArtifactJournalState.SETTLED, adoptedResultOverride = "PRIOR_FINAL")
                    .deleteIfOwned(jobDir)
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
                if (journal.claimKey != null && journal.processingAttemptId != null) {
                    val claimDurable = job?.optString(journal.claimKey) == journal.finalName &&
                        job.optBoolean("processingOutputCommitted", false) &&
                        job.optString("processingAttemptId") == journal.processingAttemptId
                    val conflictingDurableClaim = job?.optBoolean("processingOutputCommitted", false) == true &&
                        job.optString("processingArtifactClaimAttemptId") == journal.processingAttemptId &&
                        job.optString(journal.claimKey).isNotBlank() &&
                        job.optString(journal.claimKey) != journal.finalName
                    if (conflictingDurableClaim) {
                        return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "durable processing claim conflicts with this artifact journal")
                    }
                    if (!claimDurable) {
                        val operationMatches = job != null &&
                            job.optString(ACTIVE_OPERATION_ID) == journal.processingAttemptId &&
                            job.optString(ACTIVE_OPERATION_KIND) in setOf(
                                KeplerActiveOperationKind.PROCESSING_YUV.name,
                                KeplerActiveOperationKind.PROCESSING_RAW.name,
                                KeplerActiveOperationKind.SUPER_RESOLUTION.name
                        )
                        val orphanClaimMatches = job != null &&
                            job.optString(ACTIVE_OPERATION_ID).isBlank() &&
                            journal.runtimeSessionId != KeplerRuntimeSession.id &&
                            job.optString("processingAttemptId") == journal.processingAttemptId &&
                            job.optString("processingArtifactClaimAttemptId").let {
                                it.isBlank() || it == journal.processingAttemptId
                            } &&
                            parsedJournals.none { competing ->
                                competing !== journal &&
                                    competing.claimKey != null &&
                                    competing.processingAttemptId != journal.processingAttemptId &&
                                    competing.updatedAt > journal.updatedAt
                            }
                        if ((!operationMatches && !orphanClaimMatches) ||
                            job?.optString("processingAttemptId") != journal.processingAttemptId) {
                            return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "adopted artifact claim identity is not current")
                        }
                        job.put(journal.claimKey, journal.finalName)
                            .put("processingOutputCommitted", true)
                            .put("processingArtifactClaimAttemptId", journal.processingAttemptId)
                        KeplerJobMetadata.update(jobDir) { current ->
                            current.put(journal.claimKey, journal.finalName)
                                .put("processingOutputCommitted", true)
                                .put("processingArtifactClaimAttemptId", journal.processingAttemptId)
                        }
                    }
                    journal.transition(jobDir, ProcessingArtifactJournalState.JOB_CLAIM_PERSISTED)
                    val tempSettled = deleteExact(temp)
                    val priorSettled = prior?.let { settleProcessingArtifactPath(it, ProcessingArtifactResourceRole.PRIOR_BACKUP).status != ProcessingArtifactSettlementStatus.DELETE_FAILED } ?: true
                    if (tempSettled && priorSettled) {
                        journal.transition(jobDir, ProcessingArtifactJournalState.SETTLED, adoptedResultOverride = "NEW_FINAL")
                            .deleteIfOwned(jobDir)
                    } else {
                        return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT_WITH_CLEANUP_DEBT, "processing claim persisted but cleanup remains outstanding")
                    }
                    return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT)
                }
                val tempSettled = deleteExact(temp)
                val priorSettlement = prior?.let {
                    settleProcessingArtifactPath(it, ProcessingArtifactResourceRole.PRIOR_BACKUP)
                }
                if (!tempSettled) {
                    ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT_WITH_CLEANUP_DEBT, "current final verified but temporary cleanup failed")
                } else if (priorSettlement != null && priorSettlement.status == ProcessingArtifactSettlementStatus.DELETE_FAILED) {
                    ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT_WITH_CLEANUP_DEBT, "current final verified but prior cleanup failed")
                } else {
                    journal.transition(jobDir, ProcessingArtifactJournalState.SETTLED, adoptedResultOverride = "NEW_FINAL").deleteIfOwned(jobDir)
                    ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT)
                }
            }
            valid(prior, prior = true) -> {
                val priorFile = prior ?: return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS)
                movePriorToFinal(priorFile, File(jobDir, journal.finalName))
                val restored = NoFollowFileSystem.resolveDirectChild(jobDir, journal.finalName, requireFile = true)
                if (!valid(restored, prior = true)) return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "restored prior failed verification")
                if (!deleteExact(temp)) return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "temporary cleanup failed after prior restoration")
                journal.transition(jobDir, ProcessingArtifactJournalState.PRIOR_RESTORED, adoptedResultOverride = "PRIOR_FINAL")
                    .transition(jobDir, ProcessingArtifactJournalState.SETTLED, adoptedResultOverride = "PRIOR_FINAL")
                    .deleteIfOwned(jobDir)
                ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.RESTORED_PRIOR)
            }
            else -> ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "no verifiable candidate")
        }
        ProcessingArtifactJournalState.PRIOR_RESTORED,
        ProcessingArtifactJournalState.JOB_CLAIM_PERSISTED,
        ProcessingArtifactJournalState.SETTLED -> {
            val priorResult = journal.adoptedResult == "PRIOR_FINAL" || journal.state == ProcessingArtifactJournalState.PRIOR_RESTORED
            if (!priorResult && journal.adoptedResult != "NEW_FINAL") {
                return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "settled journal has no adopted result")
            }
            if (journal.state == ProcessingArtifactJournalState.JOB_CLAIM_PERSISTED) {
                val claimDurable = job != null && journal.claimKey != null && journal.processingAttemptId != null &&
                    job.optString(journal.claimKey) == journal.finalName &&
                    job.optBoolean("processingOutputCommitted", false) &&
                    job.optString("processingAttemptId") == journal.processingAttemptId &&
                    job.optString("processingArtifactClaimAttemptId") == journal.processingAttemptId
                if (!claimDurable) {
                    return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "processing claim acknowledgement is not durable")
                }
                val tempSettled = deleteExact(temp)
                val priorSettled = prior?.let { settleProcessingArtifactPath(it, ProcessingArtifactResourceRole.PRIOR_BACKUP).status != ProcessingArtifactSettlementStatus.DELETE_FAILED } ?: true
                if (!tempSettled || !priorSettled) {
                    return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT_WITH_CLEANUP_DEBT, "processing claim acknowledged but cleanup remains outstanding")
                }
                journal.transition(jobDir, ProcessingArtifactJournalState.SETTLED, adoptedResultOverride = "NEW_FINAL")
            }
            if (priorResult && !journal.priorSemanticVerified) {
                return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "settled prior lacks semantic verification evidence")
            }
            if (valid(final, prior = priorResult)) journalFile.delete() else return@map ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.AMBIGUOUS, "settled journal final is not verifiable")
            if (priorResult) ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.RESTORED_PRIOR)
            else ProcessingArtifactRecoveryResult(journalFile, ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT)
        }
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
    if (prior && !journal.priorSemanticVerified) {
        error("Processing journal prior lacks semantic verification evidence")
    }
    when (journal.verificationKind?.uppercase()) {
        "PNG" -> verifyPngArtifact(file)
        "JPG", "JPEG" -> verifyJpegArtifact(file)
        "JSON" -> JSONObject(NoFollowFileSystem.readTextVerified(file))
        else -> Unit
    }
}
