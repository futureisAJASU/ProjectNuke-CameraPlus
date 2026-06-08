package com.projectnuke.keplernightlab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DecimalFormat

data class CacheJobSummary(
    val dir: File,
    val group: String,
    val folderName: String,
    val jobType: String,
    val status: String,
    val processStatus: String,
    val sizeBytes: Long,
    val sourceFrames: Int,
    val requestedFrames: Int,
    val savedFrames: Int,
    val usedFrameCount: Int,
    val failedCaptures: Int,
    val captureCompleteness: String,
    val partialCapture: Boolean,
    val exportStatus: String,
    val exportVerified: Boolean,
    val finalOutputName: String?,
    val finalOutputFormatSetting: String,
    val exportFormatUsed: String,
    val publicDisplayName: String?,
    val rawSidecarExportStatus: String
)

@Composable
fun CacheJobsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var jobs by remember { mutableStateOf(emptyList<CacheJobSummary>()) }
    var status by remember { mutableStateOf("Loading jobs...") }
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    var selectedJobDir by remember { mutableStateOf<File?>(null) }

    fun refresh() {
        jobs = listKeplerCacheJobs(context)
        status = "Jobs: ${jobs.size}"
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    selectedJobDir?.let { jobDir ->
        JobDetailScreen(
            jobDir = jobDir,
            onBack = { selectedJobDir = null }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onBack) { Text("Back") }
            Button(onClick = { refresh() }) { Text("Refresh") }
        }

        Text(
            text = "Cache / Jobs",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(text = status, color = Color.White.copy(alpha = 0.72f))

        jobs.forEach { job ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedJobDir = job.dir }
                    .background(Color(0xFF12131A))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "${job.group} / ${job.folderName}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "type=${job.jobType}, status=${job.status}, process=${job.processStatus}",
                    color = Color.White.copy(alpha = 0.72f)
                )
                Text(
                    text = "size=${job.sizeBytes / 1024} KB, frames=${job.sourceFrames}, export=${job.exportStatus}, verified=${job.exportVerified}",
                    color = Color.White.copy(alpha = 0.72f)
                )
                Text(
                    text = "output=${job.finalOutputFormatSetting}, used=${job.exportFormatUsed}, public=${job.publicDisplayName ?: "none"}, rawSidecar=${job.rawSidecarExportStatus}",
                    color = Color.White.copy(alpha = 0.72f)
                )
                if (job.group == "KeplerRawFusion") {
                    Text(
                        text = "RAW Fusion · ${job.captureCompleteness} · used ${job.usedFrameCount}/${job.requestedFrames} · saved ${job.savedFrames}/${job.requestedFrames} · failed ${job.failedCaptures} · partial=${job.partialCapture}",
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
                Text(
                    text = "final=${job.finalOutputName ?: "none"}",
                    color = Color.White.copy(alpha = 0.72f)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (job.group == "KeplerRawFusion") {
                        Button(
                            onClick = {
                                status = "Reprocessing RAW ${job.folderName}..."
                                runCacheWorker(
                                    action = {
                                        val result = processRawFusionJob(context, job.dir) {}
                                        if (!result.success) error(result.errorMessage ?: "RAW process failed")
                                    },
                                    done = {
                                        status = "RAW reprocess done: ${job.folderName}"
                                        refresh()
                                    },
                                    failed = { error -> status = "RAW reprocess failed: $error" }
                                )
                            }
                        ) { Text("Reprocess RAW") }

                        Button(
                            enabled = job.finalOutputName != null,
                            onClick = {
                                status = "Exporting RAW ${job.folderName}..."
                                runCacheWorker(
                                    action = { exportExistingJobFinal(context, job.dir) },
                                    done = {
                                        status = "RAW export done"
                                        refresh()
                                    },
                                    failed = { error -> status = "RAW export failed: $error" }
                                )
                            }
                        ) { Text("Export") }

                        Button(
                            onClick = {
                                listOf("raw_fusion_preview.png").forEach { File(job.dir, it).delete() }
                                refresh()
                            }
                        ) { Text("Delete previews") }
                    }

                    if (job.group == "KeplerColorBurst") {
                        Button(
                            onClick = {
                                status = "Reprocessing ${job.folderName}..."
                                runCacheWorker(
                                    action = {
                                        processNightFusionJobV02Sync(job.dir) {}
                                    },
                                    done = {
                                        status = "Reprocess done: ${job.folderName}"
                                        refresh()
                                    },
                                    failed = { error -> status = "Reprocess failed: $error" }
                                )
                            }
                        ) { Text("Reprocess") }

                        Button(
                            enabled = job.finalOutputName != null,
                            onClick = {
                                status = "Exporting ${job.folderName}..."
                                runCacheWorker(
                                    action = {
                                        exportExistingJobFinal(context, job.dir)
                                    },
                                    done = {
                                        status = "Export done"
                                        refresh()
                                    },
                                    failed = { error -> status = "Export failed: $error" }
                                )
                            }
                        ) { Text("Export") }

                        Button(
                            enabled = job.exportVerified,
                            onClick = {
                                cleanupNightFusionJobAfterVerifiedExport(
                                    job.dir,
                                    CacheCleanupPolicy.DELETE_SOURCE_FRAMES_AFTER_VERIFIED_EXPORT
                                ) { status = it }
                                refresh()
                            }
                        ) { Text("Clean frames") }
                    }

                    Button(onClick = { pendingDelete = job.dir }) {
                        Text("Delete job")
                    }
                }

                if (pendingDelete == job.dir) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Confirm delete?", color = Color.White)
                        Button(
                            onClick = {
                                deleteDirectorySafely(job.dir)
                                pendingDelete = null
                                refresh()
                            }
                        ) { Text("Delete") }
                        Button(onClick = { pendingDelete = null }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
fun JobDetailScreen(
    jobDir: File,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val jobText = remember(jobDir) {
        runCatching { File(jobDir, "job.json").readText() }.getOrNull()
    }
    val jobObject = remember(jobText) {
        jobText?.let { runCatching { JSONObject(it) }.getOrNull() }
    }
    val jobParseError = remember(jobText, jobObject) {
        if (jobText != null && jobObject == null) "Invalid job.json" else null
    }
    val alignmentText = remember(jobDir) {
        runCatching {
            val file = File(jobDir, "alignment.json")
            if (file.exists()) file.readText() else null
        }.getOrNull()
    }
    val alignmentObject = remember(alignmentText) {
        alignmentText?.let { runCatching { JSONObject(it) }.getOrNull() }
    }
    val files = remember(jobDir) { sortedJobFiles(jobDir) }

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onBack) { Text("Back") }
            Text(
                text = jobDir.name,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
        }

        JobDetailSection("Summary") {
            DetailField("jobType", jobObject.stringOrDash("jobType"))
            DetailField("status", jobObject.stringOrDash("status"))
            DetailField("processStatus", jobObject.stringOrDash("processStatus"))
            DetailField("captureCompleteness", jobObject.stringOrDash("captureCompleteness"))
            DetailField("partialCapture", jobObject.boolOrDash("partialCapture"))
            DetailField("requestedFrames", jobObject.intOrDash("requestedFrames"))
            DetailField("savedFrames", jobObject.intOrDash("savedFrames"))
            DetailField("usedFrameCount", jobObject.intOrDash("usedFrameCount"))
            DetailField("failedCaptures", jobObject.intOrDash("failedCaptures"))
            DetailField("nativeRawMerge", jobObject.boolOrDash("nativeRawMerge"))
            DetailField("alignmentStatus", jobObject.stringOrDash("alignmentStatus"))
            DetailField("finalOutputFormatSetting", jobObject.stringOrDash("finalOutputFormatSetting"))
        }

        JobDetailSection("Export") {
            DetailField("exportStatus", jobObject.stringOrDash("exportStatus"))
            DetailField("exportVerified", jobObject.boolOrDash("exportVerified"))
            DetailField("exportDisplayName", jobObject.stringOrDash("exportDisplayName"))
            DetailField("exportMimeType", jobObject.stringOrDash("exportMimeType"))
            DetailField("exportFormatUsed", jobObject.stringOrDash("exportFormatUsed"))
            DetailField("exportFallbackUsed", jobObject.boolOrDash("exportFallbackUsed"))
            DetailField("exportFileSizeBytes", jobObject.longOrDash("exportFileSizeBytes"))
            DetailField("exportUri", jobObject.stringOrDash("exportUri"))
            DetailField("rawSidecarExportStatus", jobObject.stringOrDash("rawSidecarExportStatus"))
        }

        JobDetailSection("Capture") {
            DetailField("cameraId", jobObject.stringOrDash("cameraId"))
            DetailField("resolutionMode", jobObject.stringOrDash("resolutionMode"))
            DetailField("zoomRatio", jobObject.stringOrDash("zoomRatio"))
            DetailField("cropApplied", jobObject.boolOrDash("cropApplied"))
            DetailField("rawWidth", jobObject.intOrDash("rawWidth"))
            DetailField("rawHeight", jobObject.intOrDash("rawHeight"))
            DetailField("cfaPattern", jobObject.stringOrDash("cfaPattern"))
            DetailField("whiteLevelUsed", jobObject.stringOrDash("whiteLevelUsed"))
            DetailField("blackLevelUsed", jobObject.stringOrDash("blackLevelUsed"))
            DetailField("blackLevelSource", jobObject.stringOrDash("blackLevelSource"))
            DetailField("referenceFrameIndex", jobObject.stringOrDash("referenceFrameIndex"))
            DetailField("referenceFrameReason", jobObject.stringOrDash("referenceFrameReason"))
        }

        JobDetailSection("RAW / Fusion") {
            DetailField("mergedRawFile", jobObject.stringOrDash("mergedRawFile"))
            DetailField("mergedDngFile", jobObject.stringOrDash("mergedDngFile"))
            DetailField("previewFile", jobObject.stringOrDash("previewFile"))
            DetailField("finalFile", jobObject.stringOrDash("finalFile"))
            DetailField("alignmentFile", jobObject.stringOrDash("alignmentFile"))
            DetailField("alignmentError", jobObject.stringOrDash("alignmentError"))
            DetailField("rawFusionNotes", jobObject.stringOrDash("rawFusionNotes"))
        }

        if (alignmentObject != null || alignmentText != null) {
            JobDetailSection("Alignment details") {
                if (alignmentObject != null) {
                    DetailField("type", alignmentObject.stringOrDash("type"))
                    DetailField("downscale", alignmentObject.stringOrDash("downscale"))
                    DetailField("searchRadius", alignmentObject.stringOrDash("searchRadius"))
                    DetailField("referenceIndex", alignmentObject.stringOrDash("referenceIndex"))
                    val frames = alignmentObject.optJSONArray("frames")
                    if (frames != null && frames.length() > 0) {
                        repeat(frames.length()) { index ->
                            val frame = frames.optJSONObject(index) ?: return@repeat
                            DetailField(
                                "frame[$index]",
                                "dx=${frame.optInt("rawDx")}, dy=${frame.optInt("rawDy")}, conf=${frame.optDouble("confidence")}"
                            )
                        }
                    }
                } else {
                    Text(alignmentText ?: "-", color = Color.White.copy(alpha = 0.72f))
                }
            }
        }

        JobDetailSection("Files") {
            files.forEach { file ->
                DetailField(
                    file.name,
                    "${guessFileType(file)} · ${formatBytes(file.length())}"
                )
            }
        }

        JobDetailSection("Raw JSON") {
            if (jobObject != null) {
                Text(jobObject.toString(2), color = Color.White.copy(alpha = 0.84f))
            } else {
                Text(jobParseError ?: (jobText ?: "No job.json"), color = Color.White.copy(alpha = 0.84f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("job.json", jobObject?.toString(2) ?: (jobText ?: "")))
                    }
                ) { Text("Copy job.json") }
                Button(
                    enabled = !alignmentText.isNullOrBlank(),
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("alignment.json", alignmentObject?.toString(2) ?: alignmentText.orEmpty()))
                    }
                ) { Text("Copy alignment.json") }
                Button(
                    onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("folder", jobDir.absolutePath))
                    }
                ) { Text("Copy folder path") }
            }
        }

        JobDetailSection("Actions") {
            val canReprocessRaw = jobObject?.optString("jobType").orEmpty() == "RAW_NIGHT_FUSION"
            val canExport = files.any { it.name == jobObject?.optString("finalFile").orEmpty() || it.name == jobObject?.optString("finalNightFusionFile").orEmpty() }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(enabled = canReprocessRaw, onClick = {}) { Text("Reprocess RAW Fusion") }
                Button(enabled = canExport, onClick = {}) { Text("Export final again") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(enabled = false, onClick = {}) { Text("Delete preview files") }
                Button(enabled = false, onClick = {}) { Text("Delete job") }
            }
            Text(
                text = "TODO: hook existing actions here without duplicating destructive logic.",
                color = Color.White.copy(alpha = 0.58f)
            )
        }
    }
}

@Composable
private fun JobDetailSection(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF12131A),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            content()
        }
    }
}

@Composable
private fun DetailField(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
        Text(value.ifBlank { "-" }, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

fun listKeplerCacheJobs(context: Context): List<CacheJobSummary> {
    val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return emptyList()
    val roots = listOf("KeplerRawFusion", "KeplerColorBurst", "KeplerRaw", "KeplerRawBurst")

    return roots.flatMap { rootName ->
        File(picturesDir, rootName)
            .listFiles()
            ?.filter { it.isDirectory }
            ?.map { dir -> summarizeCacheJob(rootName, dir) }
            .orEmpty()
    }.sortedByDescending { it.dir.lastModified() }
}

private fun summarizeCacheJob(group: String, dir: File): CacheJobSummary {
    val jobFile = File(dir, "job.json")
    val job = runCatching {
        if (jobFile.exists()) JSONObject(jobFile.readText()) else JSONObject()
    }.getOrDefault(JSONObject())

    return CacheJobSummary(
        dir = dir,
        group = group,
        folderName = dir.name,
        jobType = job.optString("jobType", group),
        status = job.optString("status", "unknown"),
        processStatus = job.optString("processStatus", "none"),
        sizeBytes = directorySize(dir),
        sourceFrames = dir.listFiles()?.count {
            it.name.matches(Regex("frame_\\d+_color\\.png")) || it.name.matches(Regex("frame_\\d+\\.raw16"))
        } ?: 0,
        requestedFrames = job.optInt("requestedFrames", 0),
        savedFrames = job.optInt("savedFrames", 0),
        usedFrameCount = job.optInt("usedFrameCount", job.optInt("savedFrames", 0)),
        failedCaptures = job.optInt("failedCaptures", 0),
        captureCompleteness = job.optString("captureCompleteness", "unknown"),
        partialCapture = job.optBoolean("partialCapture", false),
        exportStatus = job.optString("exportStatus", "none"),
        exportVerified = job.optBoolean("exportVerified", false),
        finalOutputName = listOf(
            job.optString("finalNightFusionFile", ""),
            job.optString("finalFile", "")
        ).firstOrNull { it.isNotBlank() },
        finalOutputFormatSetting = job.optString("finalOutputFormatSetting", "HEIF"),
        exportFormatUsed = job.optString("exportFormatUsed", "none"),
        publicDisplayName = job.optString("exportDisplayName", "").ifBlank { null },
        rawSidecarExportStatus = job.optString("rawSidecarExportStatus", "NOT_REQUESTED")
    )
}

private fun exportExistingJobFinal(context: Context, jobDir: File) {
    val jobFile = File(jobDir, "job.json")
    val job = JSONObject(jobFile.readText())
    val finalName = listOf(
        job.optString("finalNightFusionFile", ""),
        job.optString("finalFile", "")
    ).firstOrNull { it.isNotBlank() } ?: error("No final file")
    val finalFile = File(jobDir, finalName)
    val bitmap = BitmapFactory.decodeFile(finalFile.absolutePath)
        ?: error("Final image missing or invalid")
    val finalOutputFormat = FinalOutputFormat.entries.firstOrNull {
        it.name == job.optString("finalOutputFormatSetting", FinalOutputFormat.HEIF.name)
    } ?: FinalOutputFormat.HEIF
    val result = exportNightFusionBitmapToGallery(
        context = context,
        bitmap = bitmap,
        displayNameBase = "Kepler_${jobDir.name}",
        requestedFormat = requestedOutputFormatForSetting(finalOutputFormat)
    )
    bitmap.recycle()
    if (!result.success || result.uriString.isNullOrBlank()) error(result.errorMessage ?: "Export failed")
    val verified = verifyGalleryExport(context, result.uriString)
    val rawSidecarResult = if (finalOutputFormat.shouldExportRawSidecar && job.optString("jobType").contains("RAW")) {
        exportRawSidecarsToPublicStorage(context, jobDir, "Kepler_${jobDir.name}")
    } else {
        null
    }
    updateExportMetadata(
        jobDir = jobDir,
        export = result,
        verified = verified,
        finalOutputFormat = finalOutputFormat,
        rawSidecarResult = rawSidecarResult,
        rawSidecarIgnored = finalOutputFormat.shouldExportRawSidecar && !job.optString("jobType").contains("RAW")
    )
}

private fun runCacheWorker(
    action: () -> Unit,
    done: () -> Unit,
    failed: (String) -> Unit
) {
    val thread = HandlerThread("KeplerCacheJobWorker").apply { start() }
    Handler(thread.looper).post {
        try {
            action()
            Handler(android.os.Looper.getMainLooper()).post(done)
        } catch (e: Exception) {
            Handler(android.os.Looper.getMainLooper()).post {
                failed("${e.javaClass.simpleName}: ${e.message}")
            }
        } finally {
            thread.quitSafely()
        }
    }
}

private fun directorySize(file: File): Long {
    if (!file.exists()) return 0L
    if (file.isFile) return file.length()
    return file.listFiles()?.sumOf { directorySize(it) } ?: 0L
}

private fun deleteDirectorySafely(dir: File): Boolean {
    if (!dir.exists() || !dir.isDirectory) return false
    dir.listFiles()?.forEach { child ->
        if (child.isDirectory) {
            deleteDirectorySafely(child)
        } else {
            child.delete()
        }
    }
    return dir.delete()
}

private fun JSONObject?.stringOrDash(key: String): String = this?.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.ifBlank { "-" } ?: "-"
private fun JSONObject?.intOrDash(key: String): String = this?.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString() ?: "-"
private fun JSONObject?.longOrDash(key: String): String = this?.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString() ?: "-"
private fun JSONObject?.boolOrDash(key: String): String = this?.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString() ?: "-"

private fun sortedJobFiles(jobDir: File): List<File> {
    fun rank(file: File): Int = when {
        file.name == "job.json" -> 0
        file.name == "raw_fusion_final.png" || file.name.endsWith(".heic") || file.name.endsWith(".jpg") || file.name.endsWith(".jpeg") || file.name.endsWith(".png") -> 1
        file.name == "alignment.json" -> 2
        file.name.endsWith(".raw16") || file.name.endsWith(".dng") -> 3
        else -> 4
    }
    return jobDir.listFiles()
        ?.sortedWith(compareBy<File> { rank(it) }.thenBy { it.name })
        .orEmpty()
}

private fun guessFileType(file: File): String = when {
    file.name == "job.json" -> "job.json"
    file.name == "alignment.json" -> "alignment.json"
    file.extension.equals("raw16", ignoreCase = true) -> "raw16"
    file.extension.equals("dng", ignoreCase = true) -> "dng"
    file.extension.equals("png", ignoreCase = true) -> "png"
    file.extension.equals("heic", ignoreCase = true) -> "heic"
    file.extension.equals("jpg", ignoreCase = true) || file.extension.equals("jpeg", ignoreCase = true) -> "jpg"
    else -> file.extension.ifBlank { "file" }
}

private fun formatBytes(bytes: Long): String {
    val df = DecimalFormat("0.0")
    return when {
        bytes >= 1024L * 1024L -> "${df.format(bytes / (1024.0 * 1024.0))} MB"
        bytes >= 1024L -> "${df.format(bytes / 1024.0)} KB"
        else -> "$bytes B"
    }
}
