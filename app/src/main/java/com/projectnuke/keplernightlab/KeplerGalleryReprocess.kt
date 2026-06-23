package com.projectnuke.keplernightlab

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
): Result<KeplerReprocessResult> {
    val target = runCatching { requireReprocessSafeJobDirectory(context, jobDir) }
        .getOrElse { return Result.failure(it) }
    val capability = detectReprocessCapability(context, target)
    if (!capability.canReprocess) {
        writeReprocessFailure(target, capability.reason)
        return Result.failure(IllegalStateException(capability.reason))
    }

    onProgress("원본 프레임 확인 중…")
    val beforeFinals = finalOutputCandidates(target, loadJobJsonSafe(target))
    val backups = backupFinalOutputs(target, beforeFinals)
    val beforeBytes = beforeFinals.filter { it.isFile }.sumOf { it.length() }

    val pipelineResult = when (capability.jobKind) {
        ReprocessJobKind.RAW_FUSION -> awaitRawReprocess(context, target, outputSettings, onProgress)
        ReprocessJobKind.YUV_FUSION -> awaitYuvReprocess(context, target, outputSettings, onProgress)
        ReprocessJobKind.COLOR_BURST ->
            Result.failure(UnsupportedOperationException("ColorBurst 다시 합성은 아직 지원되지 않습니다."))
        ReprocessJobKind.UNSUPPORTED ->
            Result.failure(UnsupportedOperationException("지원하지 않는 작업 유형입니다."))
    }

    return pipelineResult.fold(
        onSuccess = {
            onProgress("갤러리 정보 갱신 중…")
            val job = loadJobJsonSafe(target)
            val finalFile = resolveReprocessFinalOutput(target, job)
            val previewFile = finalFile
            val afterBytes = finalOutputCandidates(target, job).filter { it.isFile }.sumOf { it.length() }
            val bytesWritten = (afterBytes - beforeBytes).coerceAtLeast(finalFile?.length() ?: 0L)
            writeReprocessSuccess(
                jobDir = target,
                jobKind = capability.jobKind,
                sourceFrameCount = capability.sourceFrameCount,
                finalOutputFile = finalFile,
                previewFile = previewFile
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
        },
        onFailure = { error ->
            restoreBackups(backups)
            writeReprocessFailure(target, "${error.javaClass.simpleName}: ${error.message}")
            Result.failure(error)
        }
    )
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
                reason = if (canRun) "RAW 원본 프레임으로 다시 합성할 수 있습니다." else "원본 프레임이 삭제되어 다시 합성할 수 없습니다.",
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
                reason = if (canRun) "YUV 원본 프레임으로 다시 합성할 수 있습니다." else "원본 프레임이 삭제되어 다시 합성할 수 없습니다.",
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
    onProgress: (String) -> Unit
): Result<Unit> = suspendCancellableCoroutine { continuation ->
    var lastStatus = ""
    reprocessRawJob(context, jobDir, outputSettings) { status ->
        lastStatus = status
        onProgress(if (status.contains("Exporting", ignoreCase = true)) "최종 사진 저장 중…" else "RAW 합성 중…")
        if (!continuation.isActive) return@reprocessRawJob
        when {
            status.startsWith("RAW reprocess complete") -> continuation.resume(Result.success(Unit))
            status.startsWith("RAW reprocess failed") ||
                status.startsWith("RAW reprocess export failed") ||
                status.startsWith("Not enough enabled frames") ->
                continuation.resume(Result.failure(IllegalStateException(status)))
        }
    }
    continuation.invokeOnCancellation {
        if (lastStatus.isNotBlank()) writeReprocessFailure(jobDir, "취소됨: $lastStatus")
    }
}

private suspend fun awaitYuvReprocess(
    context: Context,
    jobDir: File,
    outputSettings: OutputSettings,
    onProgress: (String) -> Unit
): Result<Unit> = suspendCancellableCoroutine { continuation ->
    var lastStatus = ""
    reprocessYuvJob(context, jobDir, outputSettings) { status ->
        lastStatus = status
        onProgress(if (status.contains("export", ignoreCase = true)) "최종 사진 저장 중…" else "YUV 합성 중…")
        if (!continuation.isActive) return@reprocessYuvJob
        when {
            status.startsWith("PIPELINE_COMPLETE: YUV reprocess") -> continuation.resume(Result.success(Unit))
            status.startsWith("PIPELINE_FAILED: YUV reprocess") ||
                status.startsWith("Not enough enabled YUV frames") ->
                continuation.resume(Result.failure(IllegalStateException(status)))
        }
    }
    continuation.invokeOnCancellation {
        if (lastStatus.isNotBlank()) writeReprocessFailure(jobDir, "취소됨: $lastStatus")
    }
}

private fun requireReprocessSafeJobDirectory(context: Context, jobDirectory: File): File {
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
        File(pictures, "KeplerColorBurst")
    )
}

private fun matchesReprocessJobPrefix(root: File, name: String): Boolean = when (root.name) {
    "KeplerRawFusion" -> name.startsWith("KPL_RAW_FUSION_")
    "KeplerYuvFusion" -> name.startsWith("KPL_YUV_FUSION_")
    "KeplerColorBurst" -> name.startsWith("KPL_COLOR_BURST_")
    else -> false
}

private fun detectJobKind(jobDir: File, job: JSONObject): ReprocessJobKind {
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
        ?.filter { it.isFile && isReprocessSourceFrame(it, kind) }
        ?.size
        ?: 0
}

private fun isReprocessSourceFrame(file: File, kind: ReprocessJobKind): Boolean {
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

private fun loadJobJsonSafe(jobDir: File): JSONObject =
    File(jobDir, JOB_JSON_FILE_NAME).takeIf { it.isFile }?.let { file ->
        runCatching { JSONObject(file.readText()) }.getOrNull()
    } ?: JSONObject()

private fun finalOutputCandidates(jobDir: File, job: JSONObject): List<File> {
    val names = listOf(
        job.optString("galleryDisplayFile"),
        job.optString("galleryThumbnailFile"),
        job.optString("finalNightFusionFile"),
        job.optString("finalFile"),
        job.optString("outputFile"),
        job.optString("nativePostprocessRgbaFile"),
        "raw_fusion_final.png",
        "sharpened_night_fusion.png"
    ).filter { it.isNotBlank() && it != "null" }.distinct()
    return names.map { File(jobDir, it) }
}

private fun resolveReprocessFinalOutput(jobDir: File, job: JSONObject): File? =
    finalOutputCandidates(jobDir, job)
        .filter { it.isFile && it.length() > 0L }
        .maxByOrNull { it.lastModified() }

private data class ReprocessBackup(val original: File, val backup: File)

private fun backupFinalOutputs(jobDir: File, files: List<File>): List<ReprocessBackup> =
    files.filter { it.isFile }.mapNotNull { file ->
        val backup = File(jobDir, ".${file.name}.reprocess_backup")
        runCatching {
            file.copyTo(backup, overwrite = true)
            ReprocessBackup(file, backup)
        }.getOrNull()
    }

private fun restoreBackups(backups: List<ReprocessBackup>) {
    backups.forEach { backup ->
        runCatching {
            if (backup.backup.isFile) backup.backup.copyTo(backup.original, overwrite = true)
        }
    }
    deleteBackups(backups)
}

private fun deleteBackups(backups: List<ReprocessBackup>) {
    backups.forEach { backup -> runCatching { backup.backup.delete() } }
}

private fun writeReprocessSuccess(
    jobDir: File,
    jobKind: ReprocessJobKind,
    sourceFrameCount: Int,
    finalOutputFile: File?,
    previewFile: File?
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
