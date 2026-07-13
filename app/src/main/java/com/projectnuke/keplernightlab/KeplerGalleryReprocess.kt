package com.projectnuke.keplernightlab

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

typealias OutputSettings = FinalOutputFormat

data class ReprocessCapability(
    val canReprocess: Boolean,
    val jobKind: ReprocessJobKind,
    val reason: String,
    val sourceFrameCount: Int,
    val finalOutputExists: Boolean,
    val sourceFramesAvailable: Boolean
)

enum class ReprocessJobKind {
    RAW_FUSION,
    YUV_FUSION,
    COLOR_BURST,
    UNSUPPORTED
}

data class KeplerReprocessResult(
    val jobDir: File,
    val jobKind: ReprocessJobKind,
    val finalOutputFile: File?,
    val previewFile: File?,
    val bytesWritten: Long,
    val warnings: List<String>
)

suspend fun reprocessKeplerGalleryJob(
    context: Context,
    jobDir: File,
    outputSettings: OutputSettings,
    onProgress: (String) -> Unit
): Result<KeplerReprocessResult> = reprocessKeplerGalleryJob(
    context = context,
    jobDir = jobDir,
    outputSettings = outputSettings,
    frameSelection = null,
    onProgress = onProgress
)

suspend fun reprocessKeplerGalleryJob(
    context: Context,
    jobDir: File,
    outputSettings: OutputSettings,
    frameSelection: Set<Int>?,
    onProgress: (String) -> Unit
): Result<KeplerReprocessResult> = withContext(Dispatchers.IO) {
    suspend fun postProgress(message: String) {
        withContext(Dispatchers.Main) { onProgress(message) }
    }

    val target = runCatching { requireReprocessSafeJobDirectory(context, jobDir) }
        .getOrElse { return@withContext Result.failure(it) }
    val capability = detectReprocessCapability(context, target)
    if (!capability.canReprocess) {
        writeReprocessFailure(target, capability.reason)
        return@withContext Result.failure(IllegalStateException(capability.reason))
    }

    postProgress("원본 프레임 확인 중…")
    val job = loadJobJsonSafe(target)
    val kind = detectJobKind(target, job)
    val reviewItems = loadFrameReviewItems(context, target).getOrElse {
        writeReprocessFailure(target, "${it.javaClass.simpleName}: ${it.message}")
        return@withContext Result.failure(it)
    }
    val resolvedSelection = resolveFrameSelection(target, kind, reviewItems, frameSelection).getOrElse {
        writeReprocessFailure(target, "${it.javaClass.simpleName}: ${it.message}")
        return@withContext Result.failure(it)
    }
    if (resolvedSelection.size < requiredSelectedFrameCount(kind)) {
        val message = "선택한 원본 프레임이 부족하여 다시 합성할 수 없습니다."
        writeReprocessFailure(target, message)
        return@withContext Result.failure(IllegalStateException(message))
    }
    val selectionMode = resolveSelectionMode(job, frameSelection)
    val beforeFinals = finalOutputCandidates(target, job)
    // The worker may overwrite any existing artifact, including RAW/YUV fusion and
    // render-debug metadata. Snapshot every pre-existing file at transaction start.
    val backups = backupReprocessTransaction(
        target,
        target.listFiles()?.filter { it.isFile && isReprocessWorkerWritable(it) }.orEmpty()
    ).getOrElse {
        writeReprocessFailure(target, "Required reprocess backup failed: ${it.message}")
        return@withContext Result.failure(it)
    }
    postProgress("프레임 선택 적용 중…")
    saveFrameSelection(
        jobDir = target,
        mode = selectionMode,
        frames = applyFrameSelectionToItems(reviewItems, resolvedSelection, selectionMode)
    ).getOrElse {
        restoreBackups(target, backups).getOrThrow()
        writeReprocessFailure(target, "${it.javaClass.simpleName}: ${it.message}")
        return@withContext Result.failure(it)
    }

    val beforeBytes = beforeFinals.filter { it.isFile }.sumOf { it.length() }

    val pipelineResult = runCatching {
        withTimeout(10 * 60 * 1000L) {
            when (capability.jobKind) {
        ReprocessJobKind.RAW_FUSION ->
            awaitRawReprocess(context, target, outputSettings, resolvedSelection, ::postProgress)
        ReprocessJobKind.YUV_FUSION ->
            awaitYuvReprocess(context, target, outputSettings, resolvedSelection, ::postProgress)
        ReprocessJobKind.COLOR_BURST ->
            Result.failure(UnsupportedOperationException("ColorBurst 다시 합성은 아직 지원되지 않습니다."))
        ReprocessJobKind.UNSUPPORTED ->
            Result.failure(UnsupportedOperationException("지원하지 않는 작업 유형입니다."))
            }
        }
    }.getOrElse { Result.failure(it) }

    if (pipelineResult.isSuccess) {
        postProgress("갤러리 정보 갱신 중…")
        val updatedJob = loadJobJsonSafe(target)
        val finalFile = resolveReprocessFinalOutput(target, updatedJob)
        val previewFile = finalFile
        val afterBytes = finalOutputCandidates(target, updatedJob).filter { it.isFile }.sumOf { it.length() }
        val bytesWritten = (afterBytes - beforeBytes).coerceAtLeast(finalFile?.length() ?: 0L)
        writeReprocessSuccess(
            jobDir = target,
            jobKind = capability.jobKind,
            sourceFrameCount = resolvedSelection.size,
            finalOutputFile = finalFile,
            previewFile = previewFile,
            selectionMode = selectionMode,
            includedFrameIndices = resolvedSelection
        )
        deleteBackups(backups)
        Result.success(
            KeplerReprocessResult(
                jobDir = target,
                jobKind = capability.jobKind,
                finalOutputFile = finalFile,
                previewFile = previewFile,
                bytesWritten = bytesWritten,
                warnings = emptyList()
            )
        )
    } else {
        val error = pipelineResult.exceptionOrNull() ?: IllegalStateException("Unknown reprocess failure")
        val rollback = restoreBackups(target, backups)
        if (rollback.isFailure) {
            writeReprocessFailure(target, "${error.javaClass.simpleName}: ${error.message}; rollback failed: ${rollback.exceptionOrNull()?.message}")
            return@withContext Result.failure(rollback.exceptionOrNull() ?: error)
        }
        writeReprocessFailure(target, "${error.javaClass.simpleName}: ${error.message}")
        Result.failure(error)
    }
}

fun detectReprocessCapability(context: Context, jobDir: File): ReprocessCapability {
    val target = runCatching { requireReprocessSafeJobDirectory(context, jobDir) }.getOrNull()
        ?: return ReprocessCapability(
            canReprocess = false,
            jobKind = ReprocessJobKind.UNSUPPORTED,
            reason = "지원하지 않는 작업 유형입니다.",
            sourceFrameCount = 0,
            finalOutputExists = false,
            sourceFramesAvailable = false
        )
    val job = loadJobJsonSafe(target)
    val kind = detectJobKind(target, job)
    val finalExists = finalOutputCandidates(target, job).any { it.isFile && it.length() > 0L }
    val sourceCount = countActualSourceFrames(target, job, kind)
    val sourceAvailable = sourceCount > 0
    return when (kind) {
        ReprocessJobKind.RAW_FUSION -> {
            val canRun = sourceCount >= MIN_RAW_FUSION_FRAMES
            ReprocessCapability(
                canReprocess = canRun,
                jobKind = kind,
                reason = if (canRun) {
                    "RAW 원본 프레임으로 다시 합성할 수 있습니다."
                } else {
                    "원본 프레임이 부족하여 다시 합성할 수 없습니다."
                },
                sourceFrameCount = sourceCount,
                finalOutputExists = finalExists,
                sourceFramesAvailable = sourceAvailable
            )
        }
        ReprocessJobKind.YUV_FUSION -> {
            val canRun = sourceCount >= 2
            ReprocessCapability(
                canReprocess = canRun,
                jobKind = kind,
                reason = if (canRun) {
                    "YUV 원본 프레임으로 다시 합성할 수 있습니다."
                } else {
                    "원본 프레임이 부족하여 다시 합성할 수 없습니다."
                },
                sourceFrameCount = sourceCount,
                finalOutputExists = finalExists,
                sourceFramesAvailable = sourceAvailable
            )
        }
        ReprocessJobKind.COLOR_BURST -> ReprocessCapability(
            canReprocess = false,
            jobKind = kind,
            reason = "ColorBurst 다시 합성은 아직 지원되지 않습니다.",
            sourceFrameCount = sourceCount,
            finalOutputExists = finalExists,
            sourceFramesAvailable = sourceAvailable
        )
        ReprocessJobKind.UNSUPPORTED -> ReprocessCapability(
            canReprocess = false,
            jobKind = kind,
            reason = "지원하지 않는 작업 유형입니다.",
            sourceFrameCount = sourceCount,
            finalOutputExists = finalExists,
            sourceFramesAvailable = sourceAvailable
        )
    }
}

private suspend fun awaitRawReprocess(
    context: Context,
    jobDir: File,
    outputSettings: OutputSettings,
    frameSelection: Set<Int>,
    postProgress: suspend (String) -> Unit
): Result<Unit> = suspendCancellableCoroutine { continuation ->
    var lastStatus = ""
    val cancellation = KeplerPipelineCancellationToken()
    val progressScope = CoroutineScope(continuation.context)
    reprocessRawJob(context, jobDir, outputSettings, frameSelection, cancellation) { status ->
        lastStatus = status
        progressScope.launch {
            postProgress(
                if (status.contains("Exporting", ignoreCase = true)) {
                    "최종 사진 저장 중…"
                } else {
                    "RAW 합성 중…"
                }
            )
        }
        if (!continuation.isActive) return@reprocessRawJob
        when {
            status.startsWith("RAW reprocess complete") -> continuation.resume(Result.success(Unit))
            status.startsWith("RAW reprocess failed") ||
                status.startsWith("RAW reprocess export failed") ||
                status.startsWith("PIPELINE_CANCELLED") ||
                status.startsWith("Not enough enabled frames") ->
                continuation.resume(Result.failure(IllegalStateException(status)))
        }
    }
    continuation.invokeOnCancellation {
        cancellation.cancel()
        if (lastStatus.isNotBlank()) writeReprocessFailure(jobDir, "취소됨: $lastStatus")
    }
}

private suspend fun awaitYuvReprocess(
    context: Context,
    jobDir: File,
    outputSettings: OutputSettings,
    frameSelection: Set<Int>,
    postProgress: suspend (String) -> Unit
): Result<Unit> = suspendCancellableCoroutine { continuation ->
    var lastStatus = ""
    val cancellation = KeplerPipelineCancellationToken()
    val progressScope = CoroutineScope(continuation.context)
    reprocessYuvJob(context, jobDir, outputSettings, selectedFrameIndices = frameSelection, cancellation = cancellation) { status ->
        lastStatus = status
        progressScope.launch {
            postProgress(
                if (status.contains("export", ignoreCase = true)) {
                    "최종 사진 저장 중…"
                } else {
                    "YUV 합성 중…"
                }
            )
        }
        if (!continuation.isActive) return@reprocessYuvJob
        when {
            status.startsWith("PIPELINE_COMPLETE: YUV reprocess") -> continuation.resume(Result.success(Unit))
            status.startsWith("PIPELINE_FAILED: YUV reprocess") ||
                status.startsWith("PIPELINE_CANCELLED") ||
                status.startsWith("Not enough enabled YUV frames") ->
                continuation.resume(Result.failure(IllegalStateException(status)))
        }
    }
    continuation.invokeOnCancellation {
        cancellation.cancel()
        if (lastStatus.isNotBlank()) writeReprocessFailure(jobDir, "취소됨: $lastStatus")
    }
}

internal fun requireReprocessSafeJobDirectory(context: Context, jobDirectory: File): File {
    val target = jobDirectory.canonicalFile
    require(target.isDirectory) { "Job directory no longer exists." }
    val allowed = reprocessSafeRoots(context).any { root ->
        target.parentFile == root.canonicalFile && matchesReprocessJobPrefix(root, target.name)
    }
    require(allowed) { "Refusing to modify directory outside known Kepler job roots." }
    return target
}

private fun reprocessSafeRoots(context: Context): List<File> {
    val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return emptyList()
    return listOf(
        File(pictures, "KeplerRawFusion"),
        File(pictures, "KeplerYuvFusion"),
        File(pictures, "KeplerColorBurst"),
        File(pictures, "KeplerSuperRes")
    )
}

private fun matchesReprocessJobPrefix(root: File, name: String): Boolean = when (root.name) {
    "KeplerRawFusion" -> name.startsWith("KPL_RAW_FUSION_")
    "KeplerYuvFusion" -> name.startsWith("KPL_YUV_FUSION_")
    "KeplerColorBurst" -> name.startsWith("KPL_COLOR_BURST_")
    "KeplerSuperRes" -> name.startsWith("KPL_SUPER_RES_")
    else -> false
}

internal fun detectJobKind(jobDir: File, job: JSONObject): ReprocessJobKind {
    val rawType = job.optString("jobType").uppercase(Locale.US)
    return when {
        rawType == "RAW_NIGHT_FUSION" || jobDir.name.startsWith("KPL_RAW_FUSION_") -> ReprocessJobKind.RAW_FUSION
        rawType == "YUV_NIGHT_FUSION" || jobDir.name.startsWith("KPL_YUV_FUSION_") -> ReprocessJobKind.YUV_FUSION
        jobDir.name.startsWith("KPL_COLOR_BURST_") -> ReprocessJobKind.COLOR_BURST
        else -> ReprocessJobKind.UNSUPPORTED
    }
}

private fun countActualSourceFrames(jobDir: File, job: JSONObject, kind: ReprocessJobKind): Int {
    val frames = job.optJSONArray("frames")
    val fromMetadata = buildSet {
        if (frames != null) {
            repeat(frames.length()) { index ->
                val frame = frames.optJSONObject(index) ?: return@repeat
                val name = when (kind) {
                    ReprocessJobKind.RAW_FUSION -> frame.optString("raw16File")
                        .ifBlank { frame.optString("dngFile") }
                        .ifBlank { frame.optString("file") }
                    ReprocessJobKind.YUV_FUSION, ReprocessJobKind.COLOR_BURST -> frame.optString("file")
                        .ifBlank { frame.optString("yuvFile") }
                        .ifBlank { frame.optString("nv21File") }
                    ReprocessJobKind.UNSUPPORTED -> frame.optString("file")
                }
                if (
                    name.isNotBlank() &&
                    frame.optBoolean("enabled", true) &&
                    !frame.optBoolean("excludedByUser", false)
                ) {
                    File(jobDir, name).takeIf { it.isFile && isReprocessSourceFrame(it, kind) }?.let { add(it.canonicalFile) }
                }
            }
        }
    }
    if (fromMetadata.isNotEmpty()) return fromMetadata.size
    return jobDir.listFiles()
        ?.count { it.isFile && isReprocessSourceFrame(it, kind) }
        ?: 0
}

internal fun isReprocessSourceFrame(file: File, kind: ReprocessJobKind): Boolean {
    val name = file.name.lowercase(Locale.US)
    if (!name.startsWith("frame_")) return false
    return when (kind) {
        ReprocessJobKind.RAW_FUSION -> name.endsWith(".raw16") || name.endsWith(".dng")
        ReprocessJobKind.YUV_FUSION -> name.endsWith(".png") || name.endsWith(".yuv") ||
            name.endsWith(".nv21") || name.endsWith(".yuv420")
        ReprocessJobKind.COLOR_BURST -> name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
        ReprocessJobKind.UNSUPPORTED -> false
    }
}

internal fun loadJobJsonSafe(jobDir: File): JSONObject =
    File(jobDir, JOB_JSON_FILE_NAME).takeIf { it.isFile }?.let {
        runCatching { KeplerJobMetadata.read(jobDir) }.getOrNull()
    } ?: JSONObject()

private fun finalOutputCandidates(jobDir: File, job: JSONObject): List<File> {
    val names = listOf(
        job.optString("galleryDisplayFile"),
        job.optString("galleryThumbnailFile"),
        job.optString("finalNightFusionFile"),
        job.optString("finalFile"),
        job.optString("outputFile"),
        "raw_fusion_final.png",
        "sharpened_night_fusion.png"
    ).filter { it.isNotBlank() && it != "null" }.distinct()
    return names.map { File(jobDir, it) }
        .filter { it.extension.lowercase(Locale.US) in setOf("png", "jpg", "jpeg", "heic", "webp") }
}

private fun resolveReprocessFinalOutput(jobDir: File, job: JSONObject): File? =
    finalOutputCandidates(jobDir, job)
        .filter { it.isFile && it.length() > 0L }
        .maxByOrNull { it.lastModified() }

private data class ReprocessBackup(
    val original: File,
    val backup: File,
    val existingNames: Set<String> = emptySet()
)

private fun backupReprocessTransaction(jobDir: File, files: List<File>): Result<List<ReprocessBackup>> {
    val root = File(jobDir, ".reprocess_backup_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}")
    return runCatching {
        check(root.mkdirs()) { "Could not create reprocess backup directory." }
        val metadata = File(jobDir, JOB_JSON_FILE_NAME)
        check(metadata.isFile) { "job.json is required for rollback." }
        val existingNames = jobDir.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet().orEmpty()
        (listOf(metadata) + files.filter { it.isFile }.map { it.canonicalFile }.distinctBy { it.path }).map { original ->
            val backup = File(root, original.name)
            original.copyTo(backup, overwrite = false)
            check(backup.isFile && backup.length() == original.length()) { "Backup verification failed for ${original.name}" }
            ReprocessBackup(original, backup, existingNames)
        }
    }.onFailure {
        root.listFiles()?.forEach { it.delete() }
        root.delete()
    }
}

private fun restoreBackups(jobDir: File, backups: List<ReprocessBackup>): Result<Unit> = runCatching {
    val originalNames = backups.firstOrNull()?.existingNames.orEmpty()
    jobDir.listFiles()?.filter { it.isFile && it.name !in originalNames }?.forEach { runCatching { it.delete() } }
    backups.forEach { backup ->
        check(backup.backup.isFile) { "Missing rollback backup: ${backup.original.name}" }
        backup.backup.copyTo(backup.original, overwrite = true)
    }
    check(deleteBackups(backups)) { "Could not clean rollback backup directory." }
}

private fun deleteBackups(backups: List<ReprocessBackup>): Boolean {
    val root = backups.firstOrNull()?.backup?.parentFile ?: return true
    backups.forEach { backup -> if (backup.backup.exists() && !backup.backup.delete()) return false }
    return !root.exists() || root.delete()
}

private fun isReprocessWorkerWritable(file: File): Boolean {
    val name = file.name.lowercase(Locale.US)
    if (name.startsWith("frame_") && (
            name.endsWith(".raw16") || name.endsWith(".dng") ||
            name.endsWith(".yuv") || name.endsWith(".nv21") || name.endsWith(".yuv420") ||
            name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
        )) return false
    return true
}

private fun writeReprocessSuccess(
    jobDir: File,
    jobKind: ReprocessJobKind,
    sourceFrameCount: Int,
    finalOutputFile: File?,
    previewFile: File?,
    selectionMode: FrameSelectionMode,
    includedFrameIndices: Set<Int>
) {
    val job = loadJobJsonSafe(jobDir)
    job.put("processStatus", "REPROCESS_COMPLETE")
        .put("reprocessStatus", "COMPLETE")
        .put("reprocessAt", nowIso8601())
        .put("reprocessEngine", when (jobKind) {
            ReprocessJobKind.RAW_FUSION -> "raw_fusion_reprocess_v1"
            ReprocessJobKind.YUV_FUSION -> "yuv_fusion_reprocess_v1"
            ReprocessJobKind.COLOR_BURST -> "color_burst_reprocess_v1"
            ReprocessJobKind.UNSUPPORTED -> "unsupported"
        })
        .put("frameSelectionMode", selectionMode.name)
        .put("includedFrameIndices", JSONArray(includedFrameIndices.sorted()))
        .put("reprocessSourceFrameCount", sourceFrameCount)
        .put("finalOutputAvailable", finalOutputFile?.isFile == true)
        .put("galleryVisible", true)
        .put("galleryDisplayUnavailable", false)
        .put("canReprocess", sourceFrameCount > 0)
    if (job.optString("cleanupType") == "SOURCE_ONLY") {
        job.put("cleanupType", "REPROCESSED_FROM_SOURCE_ONLY")
    }
    finalOutputFile?.let {
        job.put("galleryDisplayFile", it.name)
            .put("galleryThumbnailFile", previewFile?.name ?: it.name)
    }
    putReprocessAvailability(jobDir, job, sourceFrameCount, finalOutputFile)
    saveJobJson(jobDir, job)
}

private fun writeReprocessFailure(jobDir: File, error: String) {
    val job = loadJobJsonSafe(jobDir)
    job.put("reprocessStatus", "FAILED")
        .put("reprocessError", error)
        .put("reprocessAt", nowIso8601())
    saveJobJson(jobDir, job)
}

private fun putReprocessAvailability(
    jobDir: File,
    job: JSONObject,
    sourceFrameCount: Int,
    finalOutputFile: File?
) {
    val debugAvailable = jobDir.walkTopDown().any { file ->
        file.isFile && file.name != JOB_JSON_FILE_NAME && file.name.lowercase(Locale.US).let { name ->
            name.contains("debug") || name.contains("compare") || name.endsWith(".log")
        }
    }
    job.put("sourceFramesAvailable", sourceFrameCount > 0)
        .put("debugFilesAvailable", debugAvailable)
        .put("finalOutputAvailable", finalOutputFile?.isFile == true)
}

private fun nowIso8601(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())

private suspend fun resolveFrameSelection(
    jobDir: File,
    kind: ReprocessJobKind,
    frames: List<KeplerFrameReviewItem>,
    explicitSelection: Set<Int>?
): Result<Set<Int>> = runCatching {
    if (explicitSelection != null) {
        return@runCatching explicitSelection
            .filter { index -> frames.any { it.index == index && it.file.isFile && it.file.length() > 0L } }
            .toSet()
    }

    val job = loadJobJsonSafe(jobDir)
    val persisted = persistedIncludedFrameIndices(job)
        .filter { index -> frames.any { it.index == index && it.file.isFile && it.file.length() > 0L } }
        .toSet()
    if (persisted.isNotEmpty()) return@runCatching persisted

    val recommendation = RuleBasedFrameSelectionAdvisor().recommend(null, frames)
    val recommended = recommendation.includedFrameIndices
        .filter { index -> frames.any { it.index == index && it.file.isFile && it.file.length() > 0L } }
        .toSet()
    if (recommended.size >= requiredSelectedFrameCount(kind)) {
        recommended
    } else {
        frames.filter { it.file.isFile && it.file.length() > 0L }
            .sortedByDescending { it.quality?.overallScore ?: 0.5f }
            .take(requiredSelectedFrameCount(kind))
            .map { it.index }
            .toSet()
    }
}

private fun resolveSelectionMode(job: JSONObject, explicitSelection: Set<Int>?): FrameSelectionMode {
    return persistedFrameSelectionMode(job)
        ?: if (explicitSelection != null) FrameSelectionMode.MANUAL else FrameSelectionMode.AUTO_RULE_BASED
}

private fun requiredSelectedFrameCount(kind: ReprocessJobKind): Int = when (kind) {
    ReprocessJobKind.RAW_FUSION -> MIN_RAW_FUSION_FRAMES
    ReprocessJobKind.YUV_FUSION -> 2
    ReprocessJobKind.COLOR_BURST,
    ReprocessJobKind.UNSUPPORTED -> Int.MAX_VALUE
}
