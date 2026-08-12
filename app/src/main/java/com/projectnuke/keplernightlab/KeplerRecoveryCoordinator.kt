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
    val quarantined: Boolean = false
)

internal data class KeplerRecoveryReport(
    val jobs: List<KeplerJobRecoveryResult>,
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
                        if (!force) completed = report
                        inFlight = null
                    }
                    future.complete(report)
                }
            }
        }
    }

    private fun scan(context: Context): KeplerRecoveryReport = recoverRoots(
        keplerGalleryRoots(context),
        ContextMediaStoreExportRecoveryAccess(context)
    ).also { runCatching { cleanStaleKeplerExportCacheFiles(context.cacheDir) } }

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
                .forEach { results += recoverOne(it, exportAccess) }
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
            val job = try {
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
            if (job.optString(ACTIVE_RUNTIME_SESSION_ID) == KeplerRuntimeSession.id) {
                return KeplerJobRecoveryResult(jobDir, KeplerJobRecoveryClassification.SKIP_ACTIVE_CURRENT_PROCESS)
            }
            val exportResults = exportAccess?.let { recoverMediaStoreExportJournals(jobDir, it) }.orEmpty()
            val exportFailure = exportResults.firstOrNull {
                it.classification == MediaStoreExportRecoveryClassification.AMBIGUOUS
            }
            if (exportFailure != null) {
                KeplerJobMetadata.update(jobDir) {
                    it.put("recoveryState", "AMBIGUOUS_RECOVERY_REQUIRED")
                        .put("recoveryMessage", exportFailure.message ?: "MediaStore export recovery requires manual review.")
                }
                return KeplerJobRecoveryResult(
                    jobDir,
                    KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED,
                    actions = exportResults.map { it.classification.name },
                    failures = listOfNotNull(exportFailure.message)
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
            val activeOperation = job.optString(ACTIVE_OPERATION_ID)
            val captureTemps = recoverCaptureOwnedTemps(jobDir, job, activeOperation.isNotBlank())
            if (activeOperation.isNotBlank()) {
                val artifactResults = recoverProcessingArtifactJournals(jobDir) { artifact ->
                    check(NoFollowFileSystem.digestVerified(artifact).size > 0L)
                }
                val artifactFailure = artifactResults.firstOrNull {
                    it.classification == ProcessingArtifactRecoveryClassification.AMBIGUOUS ||
                        it.classification == ProcessingArtifactRecoveryClassification.INVALID_JOURNAL
                }
                if (artifactFailure != null) {
                    KeplerJobMetadata.update(jobDir) {
                        it.put("recoveryState", "AMBIGUOUS_RECOVERY_REQUIRED")
                            .put("recoveryMessage", artifactFailure.message ?: "Artifact recovery requires manual review.")
                    }
                    return KeplerJobRecoveryResult(
                        jobDir,
                        KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED,
                        actions = metadataTemps.actions + captureTemps.deleted.map { "DELETED_$it" },
                        failures = metadataTemps.failures + captureTemps.failures + listOfNotNull(artifactFailure.message)
                    )
                }
                val localCommitted = job.optBoolean("processingOutputCommitted", false)
                val publicCommitted = job.optBoolean("galleryExportCommitted", false)
                val publicVerified = job.optBoolean("exportVerified", false)
                val classification = when {
                    publicVerified -> KeplerJobRecoveryClassification.PUBLIC_EXPORT_VERIFIED_PENDING_TERMINAL
                    publicCommitted -> KeplerJobRecoveryClassification.PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION
                    localCommitted -> KeplerJobRecoveryClassification.LOCAL_OUTPUT_COMMITTED_PENDING_TERMINAL
                    else -> KeplerJobRecoveryClassification.INTERRUPTED_PRE_COMMIT
                }
                KeplerJobMetadata.update(jobDir) {
                    it.put("recoveryState", classification.name)
                        .put("recoveryMessage", "Durable evidence reconciled after the previous process ended.")
                }
                return KeplerJobRecoveryResult(
                    jobDir,
                    classification,
                    actions = artifactResults.map { it.classification.name } + metadataTemps.actions + captureTemps.deleted.map { "DELETED_$it" },
                    failures = metadataTemps.failures + captureTemps.failures
                )
            }
            return KeplerJobRecoveryResult(
                jobDir,
                if (metadataTemps.classification == KeplerMetadataTempClassification.AMBIGUOUS) KeplerJobRecoveryClassification.AMBIGUOUS_RECOVERY_REQUIRED else KeplerJobRecoveryClassification.RECOVERED,
                actions = metadataTemps.actions,
                failures = metadataTemps.failures
            )
        } finally {
            lease.release()
        }
    }
}
