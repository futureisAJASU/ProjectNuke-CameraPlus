package com.projectnuke.keplernightlab

import android.content.Context
import java.io.File

/**
 * Phase 6 — explicit semantic local-storage operations. Historical
 * [KeplerJobCleanupType] names remain durable metadata vocabulary (with
 * migration support); this enum is the user-facing authority.
 */
enum class KeplerStorageAction {
    /** Remove canonical original/source frames and source-dependent frame cache. Reprocess unavailable. Public Gallery image untouched. */
    DELETE_SOURCES,

    /** Keep canonical originals and any local final result; delete recomputable intermediate/debug/cache artifacts. Reprocess remains available. */
    DELETE_DERIVED_CACHE,

    /** Keep canonical originals + required metadata; remove local final/preview/intermediate/debug/cache. Reprocess remains available. */
    KEEP_SOURCE_ONLY,

    /** Delete the entire Kepler app-owned job directory. The public Gallery image is untouched. */
    DELETE_LOCAL_JOB,

    /** Advanced debug/diagnostic artifact cleanup only. */
    DEBUG_ONLY
}

/** Durable legacy cleanup-type names are preserved for metadata migration truth.
 *  Returns null for legacy names that predate the explicit action model and have
 *  no semantic modern equivalent (never silently renamed). */
fun KeplerJobCleanupType.toStorageAction(): KeplerStorageAction? = when (this) {
    KeplerJobCleanupType.SOURCE_FRAMES_ONLY -> KeplerStorageAction.DELETE_SOURCES
    KeplerJobCleanupType.DERIVED_CACHE_ONLY -> KeplerStorageAction.DELETE_DERIVED_CACHE
    KeplerJobCleanupType.SOURCE_ONLY -> KeplerStorageAction.KEEP_SOURCE_ONLY
    KeplerJobCleanupType.FAILED_JOB_DELETE -> KeplerStorageAction.DELETE_LOCAL_JOB
    KeplerJobCleanupType.DEBUG_ONLY -> KeplerStorageAction.DEBUG_ONLY

    /** Legacy "keep final only" deletes sources too; it maps to no explicit modern action. */
    KeplerJobCleanupType.FINAL_ONLY -> null
}

fun KeplerStorageAction.toLegacyCleanupType(): KeplerJobCleanupType = when (this) {
    KeplerStorageAction.DELETE_SOURCES -> KeplerJobCleanupType.SOURCE_FRAMES_ONLY
    KeplerStorageAction.DELETE_DERIVED_CACHE -> KeplerJobCleanupType.FINAL_ONLY
    KeplerStorageAction.KEEP_SOURCE_ONLY -> KeplerJobCleanupType.SOURCE_ONLY
    KeplerStorageAction.DELETE_LOCAL_JOB -> KeplerJobCleanupType.FAILED_JOB_DELETE
    KeplerStorageAction.DEBUG_ONLY -> KeplerJobCleanupType.DEBUG_ONLY
}

/**
 * Phase 4 — explicit local/batch deletion outcome model. Partial filesystem
 * deletion is NEVER collapsed into success UX.
 */
sealed class LocalDeleteOutcome {
    /** Every requested path was removed; carries truthful freed-byte accounting. */
    data class Complete(val bytesFreed: Long) : LocalDeleteOutcome()

    /** Some paths remain on disk after the attempted deletion. */
    data class Partial(
        val failedPaths: List<String>,
        val bytesFreed: Long
    ) : LocalDeleteOutcome()

    /** Live ownership / recovery policy refused the destructive mutation. */
    data class Blocked(val reason: String) : LocalDeleteOutcome()

    /** Unexpected failure before or during deletion. */
    data class Failed(val error: Throwable) : LocalDeleteOutcome()
}

internal data class KeplerBatchDeleteEntry(
    val jobId: String,
    val directory: File,
    val outcome: LocalDeleteOutcome
)

internal data class KeplerBatchDeleteResult(val entries: List<KeplerBatchDeleteEntry>) {
    val deletedEntries: List<KeplerBatchDeleteEntry>
        get() = entries.filter { it.outcome is LocalDeleteOutcome.Complete }
    val unresolvedEntries: List<KeplerBatchDeleteEntry>
        get() = entries.filter { it.outcome !is LocalDeleteOutcome.Complete }
    val totalBytesFreed: Long
        get() = entries.sumOf { entry ->
            when (val outcome = entry.outcome) {
                is LocalDeleteOutcome.Complete -> outcome.bytesFreed
                is LocalDeleteOutcome.Partial -> outcome.bytesFreed
                else -> 0L
            }
        }
}

/** One-line Korean reason for a non-deleted job in bulk UX. */
internal fun LocalDeleteOutcome.unresolvedReasonText(): String = when (this) {
    is LocalDeleteOutcome.Blocked -> reason
    is LocalDeleteOutcome.Partial -> "일부 파일을 삭제하지 못했습니다 (${failedPaths.size}개 경로)"
    is LocalDeleteOutcome.Failed -> error.message?.takeIf { it.isNotBlank() }
        ?: "${error.javaClass.simpleName} 오류"
    is LocalDeleteOutcome.Complete -> ""
}

/**
 * Phase 14 — settle every selected job independently. One blocked/failed job
 * never aborts unrelated safe jobs; per-job status is preserved verbatim so the
 * UI can keep unresolved IDs selected with a concise reason. Live ownership is
 * refused by the existing per-job lease authority — never a global lock.
 */
internal fun deleteKeplerGalleryJobsBatch(
    context: Context,
    jobDirectories: List<File>
): KeplerBatchDeleteResult {    val entries = jobDirectories.map { directory ->
        val outcome = try {
            deleteKeplerGalleryJob(context, directory).fold(
                onSuccess = { cleanup ->
                    when (cleanup.cleanupStatus) {
                        CleanupStatus.COMPLETE -> LocalDeleteOutcome.Complete(cleanup.bytesFreed)
                        CleanupStatus.PARTIAL -> LocalDeleteOutcome.Partial(cleanup.failedPaths, cleanup.bytesFreed)
                        CleanupStatus.FAILED -> LocalDeleteOutcome.Failed(
                            IllegalStateException(cleanup.metadataWarning ?: "삭제 결과를 확인할 수 없습니다.")
                        )
                    }
                },
                onFailure = { failure ->
                    when (failure) {
                        is JobRecoveryMutationBlockedException ->
                            LocalDeleteOutcome.Blocked(failure.message ?: "현재 이 작업을 삭제할 수 없습니다.")
                        is ProcessingAlreadyActiveException ->
                            LocalDeleteOutcome.Blocked("다른 작업이 이 항목을 사용 중입니다.")
                        is ProcessingCleanupRequiredException ->
                            LocalDeleteOutcome.Blocked("이전 처리 정리가 완료되지 않아 지금은 삭제할 수 없습니다.")
                        else ->
                            LocalDeleteOutcome.Failed(failure)
                    }
                }
            )
        } catch (fatal: Error) {
            throw fatal
        } catch (failure: Exception) {
            LocalDeleteOutcome.Failed(failure)
        }
        KeplerBatchDeleteEntry(
            jobId = directory.absolutePath,
            directory = directory,
            outcome = outcome
        )
    }
    return KeplerBatchDeleteResult(entries)
}

/** Bulk UX summary: "8개 중 6개를 삭제했습니다. 2개는 삭제하지 못했습니다." */
internal fun keplerBatchDeleteSummaryText(result: KeplerBatchDeleteResult): String? {
    if (result.entries.isEmpty()) return null
    val total = result.entries.size
    val deleted = result.deletedEntries.size
    val unresolved = result.unresolvedEntries.size
    return when {
        unresolved == 0 -> "${total}개를 삭제했습니다."
        deleted == 0 -> "선택한 ${total}개 항목을 삭제하지 못했습니다."
        else -> "${total}개 중 ${deleted}개를 삭제했습니다. ${unresolved}개는 삭제하지 못했습니다."
    }
}
