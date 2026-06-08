package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import org.json.JSONObject
import java.io.File

data class CacheJobSummary(
    val dir: File,
    val group: String,
    val folderName: String,
    val jobType: String,
    val status: String,
    val processStatus: String,
    val sizeBytes: Long,
    val sourceFrames: Int,
    val exportStatus: String,
    val exportVerified: Boolean,
    val finalOutputName: String?
)

@Composable
fun CacheJobsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var jobs by remember { mutableStateOf(emptyList<CacheJobSummary>()) }
    var status by remember { mutableStateOf("Loading jobs...") }
    var pendingDelete by remember { mutableStateOf<File?>(null) }

    fun refresh() {
        jobs = listKeplerCacheJobs(context)
        status = "Jobs: ${jobs.size}"
    }

    LaunchedEffect(Unit) {
        refresh()
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
        exportStatus = job.optString("exportStatus", "none"),
        exportVerified = job.optBoolean("exportVerified", false),
        finalOutputName = listOf(
            job.optString("finalNightFusionFile", ""),
            job.optString("finalFile", "")
        ).firstOrNull { it.isNotBlank() }
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
    val result = exportNightFusionBitmapToGallery(
        context = context,
        bitmap = bitmap,
        displayNameBase = "Kepler_${jobDir.name}",
        requestedFormat = OutputFormat.HEIF
    )
    bitmap.recycle()
    if (!result.success || result.uriString.isNullOrBlank()) error(result.errorMessage ?: "Export failed")
    val verified = verifyGalleryExport(context, result.uriString)
    val updated = JSONObject(job.toString())
        .put("exportStatus", if (verified) "EXPORTED" else "EXPORT_UNVERIFIED")
        .put("exportVerified", verified)
        .put("exportUri", result.uriString)
        .put("exportDisplayName", result.displayName)
        .put("exportMimeType", result.mimeType)
        .put("exportFormatRequested", OutputFormat.HEIF.label)
        .put("exportFormatUsed", result.formatUsed.label)
        .put("exportFallbackUsed", result.fallbackUsed)
        .put("exportFileSizeBytes", result.fileSizeBytes)
        .put("exportedAt", System.currentTimeMillis())
    jobFile.writeText(updated.toString(2))
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
