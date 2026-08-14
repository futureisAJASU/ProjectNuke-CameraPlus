package com.projectnuke.keplernightlab

import android.content.Context
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

internal enum class KeplerJobRecoveryClassification {
    RECOVERED,
    SKIP_ACTIVE_CURRENT_PROCESS,
    LEGACY_REQUIRES_RECONCILIATION,
    INTERRUPTED_PRE_COMMIT,
    LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL,
    PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION,
    PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL,
    PUBLIC_COMMIT_MISSING,
    PROCESSING_CLEANUP_REQUIRED,
    AMBIGUOUS_RECOVERY_REQUIRED,
    ORPHANED_JOB_METADATA,
    CORRUPT_JOB_METADATA,
    REPROCESS_QUARANTINED,
    ROOT_INSPECTION_FAILED,
    RECOVERY_FAILED
}

internal data class KeplerJobRecoveryResult(
    val jobDir: File,
    val classification: KeplerJobRecoveryClassification,
    val actions: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val quarantined: Boolean = false,
    val cleanupFailures: List<String> = emptyList()
)

internal data class KeplerRecoveryReport(
    val jobs: List<KeplerJobRecoveryResult>,
    val cacheCleanupFailures: List<String> = emptyList(),
    val completedAt: Long = System.currentTimeMillis()
)

/** One process-wide, single-flight restart reconciliation owner. */
internal object KeplerRecoveryCoordinator {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KeplerRecoveryCoordinator").apply { isDaemon = true }
    }
    private val lock = Any()
    private var inFlight: CompletableFuture<KeplerRecoveryReport>? = null
    private var completed: KeplerRecoveryReport? = null

    fun requestStartup(context: Context): CompletableFuture<KeplerRecoveryReport> = request(context, force = false)

    fun recoverBeforeGallery(context: Context): KeplerRecoveryReport =
        request(context, force = false).get()

    /** Gallery refresh may explicitly request another bounded reconciliation pass. */
    fun reconcileAgain(context: Context): CompletableFuture<KeplerRecoveryReport> =
        request(context, force = true)

    private fun request(context: Context, force: Boolean): CompletableFuture<KeplerRecoveryReport> {
        synchronized(lock) {
            if (!force) completed?.let { return CompletableFuture.completedFuture(it) }
            inFlight?.let { return it }
            return CompletableFuture<KeplerRecoveryReport>().also { future ->
                inFlight = future
                executor.execute {
                    val report = try {
                        scan(context.applicationContext)
                    } catch (failure: Exception) {
                            KeplerRecoveryReport(
                                jobs = listOf(
                                    KeplerJobRecoveryResult(
                                        jobDir = context.filesDir,
                                        classification = KeplerJobRecoveryClassification.ROOT_INSPECTION_FAILED,
                                        failures = listOf("${failure.javaClass.simpleName}: ${failure.message}")
                                    )
                                )
                            )
                    }
                    synchronized(lock) {
                        completed = report
                        inFlight = null
                    }
                    future.complete(report)
                }
            }
        }
    }

    private fun scan(context: Context): KeplerRecoveryReport {
        val report = recoverRoots(
            keplerGalleryRoots(context),
            ContextMediaStoreExportRecoveryAccess(context)
        )
        val cacheCleanup = runCatching {
            cleanStaleKeplerExportCacheFilesDetailed(context.cacheDir)
        }.getOrElse { failure ->
            KeplerCacheCleanupResult(failures = listOf("Cache cleanup failed: ${failure.message}"))
        }
        return report.copy(cacheCleanupFailures = cacheCleanup.failures)
    }

    internal fun recoverRoots(
        roots: List<File>,
        exportAccess: MediaStoreExportRecoveryAccess? = null
    ): KeplerRecoveryReport {
        val results = mutableListOf<KeplerJobRecoveryResult>()
        roots.forEach { root ->
            val children = try {
                NoFollowFileSystem.requireDirectChildren(root)
            } catch (failure: Exception) {
                results += KeplerJobRecoveryResult(
                        jobDir = root,
                        classification = KeplerJobRecoveryClassification.ROOT_INSPECTION_FAILED,
                        failures = listOf("${failure.javaClass.simpleName}: ${failure.message}")
                )
                return@forEach
            }
            children
                .filter { NoFollowFileSystem.isRealDirectory(it.toPath()) && matchesJobPrefix(root, it.name) }
                .forEach { jobDir ->
                    try {
                        results += recoverOne(jobDir, exportAccess)
                    } catch (failure: Exception) {
                        results += KeplerJobRecoveryResult(
                            jobDir = jobDir,
                            classification = KeplerJobRecoveryClassification.RECOVERY_FAILED,
                            failures = listOf("${failure.javaClass.simpleName}: ${failure.message}")
                        )
                    }
                }
        }
        return KeplerRecoveryReport(results)
    }

    private fun recoverOne(
        jobDir: File,
        exportAccess: MediaStoreExportRecoveryAccess?
    ): KeplerJobRecoveryResult {
        val quarantineFailure = try {
            recoverValidatedQuarantine(jobDir)
            null
        } catch (failure: Exception) {
            failure
        }
        if (isReprocessQuarantined(jobDir)) {
            return KeplerJobRecoveryResult(
                jobDir = jobDir,
                classification = KeplerJobRecoveryClassification.REPROCESS_QUARANTINED,
                failures = listOfNotNull(quarantineFailure?.let { "${it.javaClass.simpleName}: ${it.message}" }),
                quarantined = true
            )
        }

        val lease = KeplerJobMetadata.acquireOperation(jobDir)
            ?: return KeplerJobRecoveryResult(jobDir, KeplerJobRecoveryClassification.SKIP_ACTIVE_CURRENT_PROCESS)
        try {
            val metadataTemps = reconcileJobMetadataWriteTemps(jobDir)
            var job = try {
                KeplerJobMetadata.read(jobDir)
            } catch (_: KeplerJobMetadataMissing) {
                return KeplerJobRecoveryResult(
                    jobDir,
                    if (metadataTemps.classification == KeplerMetadataTempClassification.AMBIGUOUS) KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED else KeplerJobRecoveryClassification.ORPHANED_JOB_METADATA,
                    actions = metadataTemps.actions,
                    failures = metadataTemps.failures
                )
            } catch (failure: Exception) {
                return KeplerJobRecoveryResult(
                    jobDir,
                    KeplerJobRecoveryClassification.CORRUPT_JOB_METADATA,
                    failures = listOf("${failure.javaClass.simpleName}: ${failure.message}")
                )
            }
            val activeOperation = job.optString(ACTIVE_OPERATION_ID)
            val activeOperationKind = job.optString(ACTIVE_OPERATION_KIND)
            val terminalOperationId = job.optString(TERMINAL_OPERATION_ID)
            val exportAuthorityOperation = activeOperation.ifBlank { terminalOperationId }
            val processingRecoveryOwnsAuthority = activeOperation.isNotBlank() && activeOperationKind in setOf(
                KeplerActiveOperationKind.PROCESSING_YUV.name,
                KeplerActiveOperationKind.PROCESSING_RAW.name,
                KeplerActiveOperationKind.SUPER_RESOLUTION.name
            )
            val invalidExportJournals = MediaStoreExportJournal.invalidFiles(jobDir)
            val currentExportJournals = MediaStoreExportJournal.list(jobDir)
                .filter { it.ownerOperationId == activeOperation }
            val activeStartedAt = job.optLong(ACTIVE_OPERATION_STARTED_AT, 0L)
            val invalidCurrentExportJournals = if (activeOperationKind == KeplerActiveOperationKind.PUBLIC_EXPORT.name) {
                invalidExportJournals.filter { activeStartedAt <= 0L || it.lastModified() > activeStartedAt }
            } else emptyList()
            val terminalResultAlreadyProven = activeOperation.isBlank() &&
                job.optString("currentPipelineStage") in setOf("COMPLETE", "PARTIAL", "FAILED", "CANCELLED") &&
                job.optBoolean("galleryExportCommitted", false) &&
                job.optBoolean("exportVerified", false) &&
                job.optString("exportUri").isNotBlank()
            val malformedExportBlocks = when {
                processingRecoveryOwnsAuthority -> false
                activeOperationKind == KeplerActiveOperationKind.PUBLIC_EXPORT.name ->
                    invalidCurrentExportJournals.isNotEmpty()
                else -> invalidExportJournals.isNotEmpty() && !terminalResultAlreadyProven
            }
            if (malformedExportBlocks) {
                KeplerJobMetadata.update(jobDir) {
                    it.put("recoveryState", "AMBIGUOUS_RECOVERY_REQUIRED")
                        .put("recoveryMessage", "미디어 저장소 내보내기 증거를 읽을 수 없어 보존했습니다.")
                }
                return KeplerJobRecoveryResult(
                    jobDir,
                    KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED,
                    failures = invalidExportJournals.map { "Invalid export journal: ${it.name}" }
                )
            }
            val exportResults = if (processingRecoveryOwnsAuthority) {
                emptyList()
            } else {
                exportAccess?.let { recoverMediaStoreExportJournals(jobDir, it) }.orEmpty()
            }
            val exportJournalsById = MediaStoreExportJournal.list(jobDir).associateBy { it.exportAttemptId }
            val recoveredMainVerified = exportResults.any { result ->
                val journal = exportJournalsById[result.attemptId]
                val verified = result.classification == MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED ||
                    result.classification == MediaStoreExportRecoveryClassification.PENDING_VERIFIED_AND_COMMITTED
                verified && journal?.role == MediaStoreExportRole.MAIN_IMAGE &&
                    (exportAuthorityOperation.isBlank() || journal.ownerOperationId == exportAuthorityOperation)
            }
            val recoveredMainCommit = exportResults.any { result ->
                val journal = exportJournalsById[result.attemptId]
                val verified = result.classification == MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED ||
                    result.classification == MediaStoreExportRecoveryClassification.PENDING_VERIFIED_AND_COMMITTED
                val committed = verified || result.classification == MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED
                committed && journal?.role == MediaStoreExportRole.MAIN_IMAGE &&
                    (exportAuthorityOperation.isBlank() || journal.ownerOperationId == exportAuthorityOperation)
            }
            val selectedExportTruth = recoveredMainCommit ||
                (activeOperationKind != KeplerActiveOperationKind.PUBLIC_EXPORT.name &&
                    job.optBoolean("galleryExportCommitted", false) && job.optString("exportUri").isNotBlank()) ||
                (terminalOperationId == activeOperation && activeOperation.isNotBlank() &&
                    job.optBoolean("galleryExportCommitted", false) && job.optString("exportUri").isNotBlank())
            val cleanupFailures = exportResults
                .filter { it.classification == MediaStoreExportRecoveryClassification.DELETE_FAILED }
                .map { it.message ?: "MediaStore cleanup failed for ${it.attemptId}." }
            val exportFailure = exportResults.firstOrNull {
                val journal = exportJournalsById[it.attemptId]
                val abandonedDebt = journal?.state == MediaStoreExportState.CLEANUP_REQUIRED
                val knownNoRow = journal?.state == MediaStoreExportState.INSERT_FAILED_NO_ROW ||
                    journal?.state == MediaStoreExportState.CLEANED
                !abandonedDebt && !knownNoRow && !selectedExportTruth &&
                    (it.classification == MediaStoreExportRecoveryClassification.AMBIGUOUS ||
                        it.classification == MediaStoreExportRecoveryClassification.INSERT_RESULT_UNKNOWN ||
                        it.classification == MediaStoreExportRecoveryClassification.DELETE_FAILED)
            }
            if (exportFailure != null) {
                KeplerJobMetadata.update(jobDir) {
                    it.put("recoveryState", "AMBIGUOUS_RECOVERY_REQUIRED")
                        .put("recoveryMessage", exportFailure.message ?: "내보내기 복구 증거를 확인할 수 없어 추가 확인이 필요합니다.")
                }
                return KeplerJobRecoveryResult(
                    jobDir,
                    KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED,
                    actions = exportResults.map { it.classification.name },
                    failures = listOfNotNull(exportFailure.message),
                    cleanupFailures = cleanupFailures
                )
            }
            if (exportResults.any {
                    it.classification == MediaStoreExportRecoveryClassification.PUBLIC_COMMIT_MISSING ||
                        it.classification == MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED
                }) {
                KeplerJobMetadata.update(jobDir) {
                    it.put("recoveryState", "PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION")
                        .put("recoveryMessage", "공개 내보내기 증거를 확인할 수 없어 복구가 필요합니다.")
                }
            }
            val missingMainCommit = exportResults.firstOrNull { result ->
                result.classification == MediaStoreExportRecoveryClassification.PUBLIC_COMMIT_MISSING &&
                    exportJournalsById[result.attemptId]?.role == MediaStoreExportRole.MAIN_IMAGE &&
                    (activeOperation.isBlank() || exportJournalsById[result.attemptId]?.ownerOperationId == activeOperation)
            }
            if (missingMainCommit != null) {
                KeplerJobMetadata.update(jobDir) {
                    it.put("recoveryState", "PUBLIC_COMMIT_MISSING")
                        .put("recoveryMessage", missingMainCommit.message ?: "커밋된 공개 결과를 찾을 수 없어 복구 증거로 보존했습니다.")
                }
                return KeplerJobRecoveryResult(
                    jobDir,
                    KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED,
                    actions = exportResults.map { it.classification.name },
                    failures = listOfNotNull(missingMainCommit.message),
                    cleanupFailures = cleanupFailures
                )
            }
            if (exportAuthorityOperation.isNotBlank() && exportResults.isNotEmpty()) {
                job = KeplerJobMetadata.update(jobDir) { current ->
                    reconstructMainExportEvidence(jobDir, current, exportAuthorityOperation, exportResults)
                }
            }
            if (exportResults.isNotEmpty() || activeOperation.isNotBlank()) {
                val recoveredAttemptIds = exportResults
                    .filter { result ->
                        result.classification == MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED ||
                            result.classification == MediaStoreExportRecoveryClassification.PENDING_VERIFIED_AND_COMMITTED
                    }
                    .filter { result ->
                        MediaStoreExportJournal.list(jobDir).any { journal ->
                            journal.exportAttemptId == result.attemptId &&
                                journal.role == MediaStoreExportRole.RAW_DNG_SIDECAR
                        }
                    }
                    .mapTo(mutableSetOf()) { it.attemptId }
                job = KeplerJobMetadata.update(jobDir) { current ->
                    reconstructRawSidecarJournalEvidence(
                        jobDir,
                        current,
                        MediaStoreExportJournal.list(jobDir),
                        recoveredAttemptIds
                    )
                }
            }
            if (terminalOperationId == activeOperation && activeOperation.isNotBlank() &&
                job.optString("currentPipelineStage") in setOf("COMPLETE", "PARTIAL", "FAILED", "CANCELLED")) {
                markMediaStoreExportJournalsTerminalPersisted(jobDir)
                check(KeplerJobMetadata.finalizeRecoveredTerminalOperation(jobDir, activeOperation, lease)) {
                    "Could not durably finalize terminal operation $activeOperation"
                }
                return KeplerJobRecoveryResult(
                    jobDir,
                    KeplerJobRecoveryClassification.RECOVERED,
                    actions = exportResults.map { it.classification.name },
                    cleanupFailures = cleanupFailures
                )
            }
            val captureTemps = recoverCaptureOwnedTemps(jobDir, job, activeOperation.isNotBlank())
            if (activeOperation.isBlank() && job.optString(PROCESSING_HANDOFF_OPERATION_ID).isNotBlank()) {
                check(KeplerJobMetadata.finalizeRecoveredProcessingHandoff(jobDir, lease)) {
                    "Could not durably finalize processing handoff"
                }
                return KeplerJobRecoveryResult(
                    jobDir,
                    KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT,
                    actions = captureTemps.deleted.map { "DELETED_$it" },
                    failures = metadataTemps.failures + captureTemps.failures
                )
            }
            val processingScan = ProcessingArtifactJournal.scan(jobDir)
            val processingEvidenceExists = processingScan.validJournals.isNotEmpty() ||
                processingScan.invalidFiles.isNotEmpty()
            if (activeOperation.isBlank() && processingEvidenceExists) {
                val artifactResults = recoverProcessingArtifactJournals(jobDir, job)
                val artifactFailure = artifactResults.firstOrNull {
                    it.classification == ProcessingArtifactRecoveryClassification.AMBIGUOUS ||
                        it.classification == ProcessingArtifactRecoveryClassification.INVALID_JOURNAL
                }
                if (artifactFailure != null) {
                    KeplerJobMetadata.update(jobDir) {
                        it.put("recoveryState", "AMBIGUOUS_RECOVERY_REQUIRED")
                            .put("recoveryMessage", "보존된 처리 파일 증거를 확인할 수 없어 추가 확인이 필요합니다.")
                    }
                    return KeplerJobRecoveryResult(
                        jobDir,
                        KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED,
                        actions = artifactResults.map { it.classification.name },
                        failures = listOfNotNull(artifactFailure.message)
                    )
                }
                val artifactCleanupDebt = artifactResults
                    .filter {
                        it.classification == ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT_WITH_CLEANUP_DEBT ||
                            it.classification == ProcessingArtifactRecoveryClassification.RESTORED_PRIOR_WITH_CLEANUP_DEBT ||
                            it.classification == ProcessingArtifactRecoveryClassification.SETTLED_NO_OUTPUT_WITH_CLEANUP_DEBT
                    }
                    .mapNotNull { it.message ?: "처리 파일 정리 결과를 확인할 수 없습니다." }
                if (artifactCleanupDebt.isNotEmpty()) {
                    val cleanupHistory = if (artifactResults.any {
                            it.classification == ProcessingArtifactRecoveryClassification.RESTORED_PRIOR ||
                                it.classification == ProcessingArtifactRecoveryClassification.RESTORED_PRIOR_WITH_CLEANUP_DEBT ||
                                it.classification == ProcessingArtifactRecoveryClassification.SETTLED_NO_OUTPUT_WITH_CLEANUP_DEBT
                        }) {
                        KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT.name
                    } else {
                        KeplerJobRecoveryClassification.LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL.name
                    }
                    check(KeplerJobMetadata.recordProcessingCleanupRequired(jobDir, null, artifactCleanupDebt, cleanupHistory)) {
                        "Could not durably record processing cleanup debt"
                    }
                    return KeplerJobRecoveryResult(
                        jobDir,
                        KeplerJobRecoveryClassification.PROCESSING_CLEANUP_REQUIRED,
                        actions = artifactResults.map { it.classification.name },
                        cleanupFailures = artifactCleanupDebt
                    )
                }
                if (job.optString("recoveryState") == PROCESSING_CLEANUP_REQUIRED) {
                    KeplerJobMetadata.clearProcessingCleanupRequired(jobDir)
                }
                val classification = if (job.optBoolean("processingOutputCommitted", false)) {
                    KeplerJobRecoveryClassification.LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL
                } else {
                    KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT
                }
                KeplerJobMetadata.update(jobDir) {
                    it.put("recoveryState", "STABLE")
                        .put("lastRecoveryClassification", classification.name)
                        .put("lastRecoveryMessage", "이전 실행에서 남은 로컬 처리 결과를 안전하게 확인했습니다.")
                        .put("recoveredAt", System.currentTimeMillis())
                        .remove("recoveryMessage")
                }
                return KeplerJobRecoveryResult(
                    jobDir,
                    classification,
                    actions = artifactResults.map { it.classification.name },
                    cleanupFailures = artifactResults.mapNotNull { it.message }
                )
            }
            if (activeOperation.isNotBlank()) {
                val artifactResults = recoverProcessingArtifactJournals(jobDir, job)
                val artifactFailure = artifactResults.firstOrNull {
                    it.classification == ProcessingArtifactRecoveryClassification.AMBIGUOUS ||
                        it.classification == ProcessingArtifactRecoveryClassification.INVALID_JOURNAL
                }
                if (artifactFailure != null) {
                    KeplerJobMetadata.update(jobDir) {
                        it.put("recoveryState", "AMBIGUOUS_RECOVERY_REQUIRED")
                            .put("recoveryMessage", "처리 파일 복구 증거를 확인할 수 없어 추가 확인이 필요합니다.")
                    }
                    return KeplerJobRecoveryResult(
                        jobDir,
                        KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED,
                        actions = metadataTemps.actions + captureTemps.deleted.map { "DELETED_$it" },
                        failures = metadataTemps.failures + captureTemps.failures + listOfNotNull(artifactFailure.message),
                        cleanupFailures = cleanupFailures
                    )
                }
                val localCommitted = job.optBoolean("processingOutputCommitted", false)
                val artifactCleanupDebt = artifactResults
                    .filter {
                        it.classification == ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT_WITH_CLEANUP_DEBT ||
                            it.classification == ProcessingArtifactRecoveryClassification.RESTORED_PRIOR_WITH_CLEANUP_DEBT ||
                            it.classification == ProcessingArtifactRecoveryClassification.SETTLED_NO_OUTPUT_WITH_CLEANUP_DEBT
                    }
                    .mapNotNull { it.message ?: "처리 파일 정리 결과를 확인할 수 없습니다." }
                val cleanupHistory = if (artifactResults.any {
                        it.classification == ProcessingArtifactRecoveryClassification.RESTORED_PRIOR ||
                            it.classification == ProcessingArtifactRecoveryClassification.RESTORED_PRIOR_WITH_CLEANUP_DEBT ||
                            it.classification == ProcessingArtifactRecoveryClassification.SETTLED_NO_OUTPUT_WITH_CLEANUP_DEBT
                    }) {
                    KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT.name
                } else {
                    KeplerJobRecoveryClassification.LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL.name
                }
                val publicCommitted = if (activeOperationKind == KeplerActiveOperationKind.PUBLIC_EXPORT.name) {
                    recoveredMainCommit
                } else {
                    job.optBoolean("galleryExportCommitted", false)
                }
                val publicVerified = if (activeOperationKind == KeplerActiveOperationKind.PUBLIC_EXPORT.name) {
                    recoveredMainVerified
                } else {
                    job.optBoolean("exportVerified", false)
                }
                val classification = when {
                    publicVerified -> KeplerJobRecoveryClassification.PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL
                    publicCommitted -> KeplerJobRecoveryClassification.PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION
                    localCommitted -> KeplerJobRecoveryClassification.LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL
                    else -> KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT
                }
                if (artifactCleanupDebt.isEmpty()) {
                    check(KeplerJobMetadata.finalizeRecoveredInterruptedOperation(
                        jobDir,
                        activeOperation,
                        classification,
                        "이전 실행에서 남은 처리 증거를 안전하게 확인했습니다.",
                        recoveryLease = lease
                    )) { "Could not durably finalize interrupted operation $activeOperation" }
                } else {
                    check(KeplerJobMetadata.recordProcessingCleanupRequired(jobDir, activeOperation, artifactCleanupDebt, cleanupHistory)) {
                        "Could not durably record processing cleanup debt"
                    }
                }
                return KeplerJobRecoveryResult(
                    jobDir,
                    if (artifactCleanupDebt.isEmpty()) classification else KeplerJobRecoveryClassification.PROCESSING_CLEANUP_REQUIRED,
                    actions = artifactResults.map { it.classification.name } + metadataTemps.actions + captureTemps.deleted.map { "DELETED_$it" },
                    failures = metadataTemps.failures + captureTemps.failures,
                    cleanupFailures = cleanupFailures + artifactCleanupDebt
                )
            }
            if (invalidExportJournals.isNotEmpty() && !terminalResultAlreadyProven) {
                KeplerJobMetadata.update(jobDir) {
                    it.put("recoveryState", "AMBIGUOUS_RECOVERY_REQUIRED")
                        .put("recoveryMessage", "미디어 저장소 내보내기 증거를 읽을 수 없어 보존했습니다.")
                }
                return KeplerJobRecoveryResult(
                    jobDir,
                    KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED,
                    failures = invalidExportJournals.map { "Invalid export journal: ${it.name}" },
                    cleanupFailures = cleanupFailures
                )
            }
            if (terminalResultAlreadyProven) {
                KeplerJobMetadata.update(jobDir) {
                    it.put("recoveryState", "STABLE").remove("recoveryMessage")
                }
            }
            if (activeOperation.isBlank() &&
                job.optBoolean("processingOutputCommitted", false) &&
                job.optString("currentPipelineStage") !in setOf("COMPLETE", "PARTIAL", "FAILED", "CANCELLED")) {
                KeplerJobMetadata.update(jobDir) {
                    it.put("recoveryState", "STABLE")
                        .put("lastRecoveryClassification", KeplerJobRecoveryClassification.LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL.name)
                        .put("lastRecoveryMessage", "이전 실행이 종료되기 전에 확인된 로컬 처리 결과를 보존했습니다.")
                        .put("recoveredAt", System.currentTimeMillis())
                        .remove("recoveryMessage")
                }
                return KeplerJobRecoveryResult(
                    jobDir,
                    KeplerJobRecoveryClassification.LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL,
                    cleanupFailures = cleanupFailures
                )
            }
            if (isLegacyActiveJob(job)) {
                val updatedAt = job.optLong("updatedAt", job.optLong("createdAt", 0L))
                val stale = updatedAt > 0L && System.currentTimeMillis() - updatedAt >= LEGACY_RECOVERY_AGE_MILLIS
                if (stale) {
                    KeplerJobMetadata.update(jobDir) {
                        it.put("status", "INTERRUPTED")
                            .put("processStatus", "INTERRUPTED")
                            .put("currentPipelineStage", "INTERRUPTED")
                            .put("recoveryState", "STABLE")
                            .put("lastRecoveryClassification", KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT.name)
                            .put("recoveredAt", System.currentTimeMillis())
                            .put("lastRecoveryMessage", "이전 실행의 작업 소유권을 확인할 수 없어 안전하게 중단된 작업으로 기록했습니다.")
                        it.remove("recoveryMessage")
                    }
                    return KeplerJobRecoveryResult(jobDir, KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT)
                }
                return KeplerJobRecoveryResult(jobDir, KeplerJobRecoveryClassification.LEGACY_REQUIRES_RECONCILIATION)
            }
            return KeplerJobRecoveryResult(
                jobDir,
                if (metadataTemps.classification == KeplerMetadataTempClassification.AMBIGUOUS) KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED else KeplerJobRecoveryClassification.RECOVERED,
                actions = metadataTemps.actions,
                failures = metadataTemps.failures,
                cleanupFailures = cleanupFailures
            )
        } finally {
            lease.release()
        }
    }

    private fun isLegacyActiveJob(job: org.json.JSONObject): Boolean {
        if (job.has(ACTIVE_RUNTIME_SESSION_ID) || job.has(ACTIVE_OPERATION_ID) ||
            job.has(PROCESSING_HANDOFF_OPERATION_ID)) return false
        val active = setOf(
            "CAPTURING", "PROCESSING", "EXPORTING", "YUV_ALIGNING", "YUV_MERGING",
            "YUV_DENOISE_SHARPEN", "YUV_EXPORTING", "RAW_PROCESSING_IN_PROGRESS"
        )
        return job.optString("status").uppercase() in active ||
            job.optString("processStatus").uppercase() in active ||
            job.optString("currentPipelineStage").uppercase() in active
    }

    private const val LEGACY_RECOVERY_AGE_MILLIS = 15 * 60 * 1000L
}
