package com.projectnuke.keplernightlab

import android.content.Context
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

internal enum class KeplerJobRecoveryClassification {
    RECOVERED,
    SKIP_ACTIVE_CURRENT_PROCESS,
    LEGACY_REQUIRES_RECONCILIATION,
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

    private fun scan(context: Context): KeplerRecoveryReport = recoverRoots(keplerGalleryRoots(context))

    internal fun recoverRoots(roots: List<File>): KeplerRecoveryReport {
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
                .forEach { results += recoverOne(it) }
        }
        return KeplerRecoveryReport(results)
    }

    private fun recoverOne(jobDir: File): KeplerJobRecoveryResult {
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
            val job = try {
                KeplerJobMetadata.read(jobDir)
            } catch (_: KeplerJobMetadataMissing) {
                return KeplerJobRecoveryResult(jobDir, KeplerJobRecoveryClassification.ORPHANED_JOB_METADATA)
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
            val activeOperation = job.optString(ACTIVE_OPERATION_ID)
            if (activeOperation.isNotBlank()) {
                return KeplerJobRecoveryResult(
                    jobDir,
                    KeplerJobRecoveryClassification.LEGACY_REQUIRES_RECONCILIATION,
                    actions = listOf("durable active operation requires evidence reconciliation")
                )
            }
            return KeplerJobRecoveryResult(jobDir, KeplerJobRecoveryClassification.RECOVERED)
        } finally {
            lease.release()
        }
    }
}
