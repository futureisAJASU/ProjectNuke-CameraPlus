package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val galleryBackground = Color(0xFF080A0F)
private val galleryCard = Color(0xFF141821)
private val galleryMuted = Color.White.copy(alpha = 0.65f)

@Composable
fun KeplerGalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var jobs by remember { mutableStateOf(emptyList<KeplerGalleryJobSummary>()) }
    var selected by remember { mutableStateOf<KeplerGalleryJobSummary?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        runCatching { withContext(Dispatchers.IO) { loadKeplerGalleryJobs(context) } }
            .onSuccess { jobs = it; error = null }
            .onFailure { error = "${it.javaClass.simpleName}: ${it.message}" }
    }

    selected?.let { job ->
        KeplerGalleryDetailScreen(
            job = job,
            onBack = { selected = null },
            onDeleted = {
                selected = null
                refreshKey++
            }
        )
        return
    }

    BackHandler(onBack = onBack)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(galleryBackground)
            .padding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBack) { Text("Back") }
                Button(onClick = { refreshKey++ }) { Text("Refresh") }
            }
            Text("Kepler Job Gallery", style = MaterialTheme.typography.headlineMedium)
            Text(error ?: "${jobs.size} jobs, newest first", color = galleryMuted)
        }
        items(jobs, key = { it.id }) { job ->
            GalleryJobCard(job) { selected = job }
        }
        if (jobs.isEmpty() && error == null) {
            item { Text("No RAW or Color Fusion jobs found.", color = galleryMuted) }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun GalleryJobCard(job: KeplerGalleryJobSummary, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = galleryCard,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(job.jobType, style = MaterialTheme.typography.titleMedium)
            Text(job.directory.name, color = galleryMuted)
            Text("${formatTimestamp(job.createdAt)} | ${job.status}")
            Text("Frames ${job.savedFrames}/${job.requestedFrames} | ${resolutionText(job)}")
            Text("${formatBytes(job.folderSizeBytes)} | Final/export: ${if (job.finalExportExists) "yes" else "no"}")
        }
    }
}

@Composable
private fun KeplerGalleryDetailScreen(
    job: KeplerGalleryJobSummary,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentJob by remember(job.id) { mutableStateOf(job) }
    var preview by remember(job.id) { mutableStateOf<Bitmap?>(null) }
    var debugPreviews by remember(job.id) { mutableStateOf(emptyList<Pair<String, Bitmap>>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var actionStatus by remember { mutableStateOf<String?>(null) }
    var isReprocessing by remember { mutableStateOf(false) }
    var isAnalyzingQuality by remember { mutableStateOf(false) }
    var selectedFusionPreset by remember(job.id) {
        mutableStateOf(
            ClassicYuvFusionPreset.fromName(job.metadata?.optString("fusionPresetName"))
        )
    }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(job.id, refreshKey) {
        val refreshedJob = withContext(Dispatchers.IO) {
            loadKeplerGalleryJobs(context).firstOrNull { it.id == job.id }
        } ?: currentJob
        val loadedPreview = withContext(Dispatchers.IO) {
            refreshedJob.finalPreviewFile?.let { loadThumbnailSafe(it, 1280) }
        }
        val loadedDebugPreviews = withContext(Dispatchers.IO) {
            fusionDebugArtifactFiles(refreshedJob).mapNotNull { (label, file) ->
                loadThumbnailSafe(file, 1280)?.let { label to it }
            }
        }
        currentJob = refreshedJob
        preview = loadedPreview
        debugPreviews = loadedDebugPreviews
    }
    DisposableEffect(preview) {
        val bitmap = preview
        onDispose { bitmap?.recycle() }
    }
    DisposableEffect(debugPreviews) {
        val bitmaps = debugPreviews.map { it.second }
        onDispose { bitmaps.forEach { it.recycle() } }
    }
    BackHandler(onBack = onBack)

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete job?") },
            text = { Text("Permanently delete ${currentJob.directory.name} and all source frames?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleteKeplerGalleryJob(context, currentJob.directory)
                        .onSuccess { onDeleted() }
                        .onFailure { deleteError = it.message ?: "Delete failed." }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(galleryBackground)
            .padding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBack) { Text("Back") }
                Button(onClick = { confirmDelete = true }) { Text("Delete") }
            }
            Text(currentJob.directory.name, style = MaterialTheme.typography.headlineSmall)
            deleteError?.let { Text(it, color = Color(0xFFFFB4A9)) }
            actionStatus?.let { Text(it, color = galleryMuted) }
        }
        preview?.let { bitmap ->
            item {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Final image",
                    modifier = Modifier.fillMaxWidth().height(260.dp).background(Color.Black),
                    contentScale = ContentScale.Fit
                )
            }
        }
        if (debugPreviews.isNotEmpty()) {
            item {
                GallerySection("Fusion Debug") {
                    debugPreviews.forEach { (label, bitmap) ->
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (label == "A/B comparison") 220.dp else 260.dp)
                                .background(Color.Black),
                            contentScale = ContentScale.Fit
                        )
                    }
                    fusionAlignmentSummary(currentJob.metadata).forEach { row ->
                        GalleryField("Alignment", row)
                    }
                }
            }
        }
        item {
            GallerySection("Summary") {
                val enabledCount = currentJob.frames.count { it.included && it.file != null }
                val excludedCount = currentJob.frames.count { !it.included }
                GalleryField("Type", currentJob.jobType)
                GalleryField("Created", formatTimestamp(currentJob.createdAt))
                GalleryField("Status", currentJob.status)
                GalleryField("Frames", "${currentJob.savedFrames}/${currentJob.requestedFrames}")
                GalleryField("Enabled / excluded", "$enabledCount / $excludedCount")
                GalleryField("Resolution", resolutionText(currentJob))
                GalleryField("Folder size", formatBytes(currentJob.folderSizeBytes))
                GalleryField("Final/export", if (currentJob.finalExportExists) "Available" else "Not found")
                GalleryField("Path", currentJob.directory.absolutePath)
                Button(
                    enabled = !isReprocessing && !isAnalyzingQuality,
                    onClick = {
                        isAnalyzingQuality = true
                        actionStatus = "Analyzing frame 1/${currentJob.frames.size}..."
                        analyzeKeplerFrameQuality(
                            jobDir = currentJob.directory,
                            onStatus = { actionStatus = it },
                            onComplete = {
                                isAnalyzingQuality = false
                                refreshKey++
                            }
                        )
                    }
                ) {
                    Text(if (isAnalyzingQuality) "Analyzing..." else "Analyze Frame Quality")
                }
                if (currentJob.jobType == "RAW_NIGHT_FUSION") {
                    Button(
                        enabled = !isReprocessing && !isAnalyzingQuality,
                        onClick = {
                            isReprocessing = true
                            actionStatus = "Starting RAW reprocess..."
                            reprocessRawJob(
                                context = context,
                                jobDir = currentJob.directory,
                                finalOutputFormat = OutputSettingsStore.load(context)
                            ) { status ->
                                actionStatus = status
                                if (
                                    status.startsWith("RAW reprocess complete") ||
                                    status.startsWith("RAW reprocess failed") ||
                                    status.startsWith("Not enough enabled frames")
                                ) {
                                    isReprocessing = false
                                    refreshKey++
                                }
                            }
                        }
                    ) { Text(if (isReprocessing) "Reprocessing..." else "Reprocess RAW") }
                } else {
                    Text("Classic YUV preset", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            ClassicYuvFusionPreset.NATURAL,
                            ClassicYuvFusionPreset.CLEAN
                        ).forEach { preset ->
                            TextButton(
                                enabled = !isReprocessing && !isAnalyzingQuality,
                                onClick = { selectedFusionPreset = preset }
                            ) {
                                Text(
                                    if (selectedFusionPreset == preset) {
                                        "[${preset.displayName}]"
                                    } else {
                                        preset.displayName
                                    }
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            ClassicYuvFusionPreset.SHARP,
                            ClassicYuvFusionPreset.NIGHT_BRIGHT
                        ).forEach { preset ->
                            TextButton(
                                enabled = !isReprocessing && !isAnalyzingQuality,
                                onClick = { selectedFusionPreset = preset }
                            ) {
                                Text(
                                    if (selectedFusionPreset == preset) {
                                        "[${preset.displayName}]"
                                    } else {
                                        preset.displayName
                                    }
                                )
                            }
                        }
                    }
                    Button(
                        enabled = !isReprocessing && !isAnalyzingQuality,
                        onClick = {
                            isReprocessing = true
                            actionStatus =
                                "YUV reprocess: ${selectedFusionPreset.displayName} preset..."
                            reprocessYuvJob(
                                context = context,
                                jobDir = currentJob.directory,
                                finalOutputFormat = OutputSettingsStore.load(context),
                                fusionParams = selectedFusionPreset.params
                            ) { status ->
                                actionStatus = status
                                if (
                                    status.startsWith("PIPELINE_COMPLETE: YUV reprocess") ||
                                    status.startsWith("PIPELINE_FAILED: YUV reprocess") ||
                                    status.startsWith("Not enough enabled YUV frames")
                                ) {
                                    isReprocessing = false
                                    refreshKey++
                                }
                            }
                        }
                    ) {
                        Text(
                            if (isReprocessing) "Reprocessing..."
                            else "Reprocess with preset"
                        )
                    }
                }
            }
        }
        item {
            GallerySection("Metadata") {
                metadataSummary(currentJob.metadata).forEach { (key, value) -> GalleryField(key, value) }
            }
        }
        items(currentJob.frames, key = { it.index }) { frame ->
            GallerySection("Frame ${frame.index}") {
                GalleryField("File", frame.fileName)
                GalleryField("Timestamp ns", frame.timestampNs?.toString() ?: "none")
                GalleryField("File exists", if (frame.file != null) "yes" else "no")
                GalleryField("State", if (frame.included) "Included" else "Excluded")
                frame.excludeReason?.let { GalleryField("Reason", it) }
                frame.qualityLabel?.let { GalleryField("Quality", it) }
                frame.qualityScore?.let { GalleryField("Quality score", formatMetric(it)) }
                frame.sharpnessScore?.let { GalleryField("Sharpness", formatMetric(it)) }
                frame.motionScore?.let { GalleryField("Motion", formatMetric(it)) }
                frame.exposureScore?.let { GalleryField("Exposure", formatMetric(it)) }
                frame.brightnessMean?.let { GalleryField("Brightness mean", formatMetric(it)) }
                frame.brightnessStdDev?.let { GalleryField("Brightness std dev", formatMetric(it)) }
                frame.clippedShadowRatio?.let { GalleryField("Clipped shadows", formatMetric(it)) }
                frame.clippedHighlightRatio?.let { GalleryField("Clipped highlights", formatMetric(it)) }
                frame.qualityReason?.let { GalleryField("Quality reason", it) }
                if (frame.recommendedExclude) {
                    Text(
                        "Recommended to exclude",
                        color = Color(0xFFFFB4A9),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(if (frame.included) "Included" else "Excluded")
                    Switch(
                        checked = frame.included,
                        onCheckedChange = { included ->
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        setFrameExcluded(
                                            currentJob.directory,
                                            frame.index,
                                            excluded = !included
                                        )
                                    }
                                }.onSuccess {
                                    actionStatus = if (included) {
                                        "Frame ${frame.index} included."
                                    } else {
                                        "Frame ${frame.index} excluded. Source file kept."
                                    }
                                    refreshKey++
                                }.onFailure {
                                    actionStatus = "Frame update failed: ${it.message}"
                                }
                            }
                        }
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun GallerySection(title: String, content: @Composable () -> Unit) {
    Surface(color = galleryCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun GalleryField(label: String, value: String) {
    Column {
        Text(label, color = galleryMuted, style = MaterialTheme.typography.labelMedium)
        Text(value.ifBlank { "none" })
    }
}

private fun resolutionText(job: KeplerGalleryJobSummary): String {
    return if (job.width != null && job.height != null) "${job.width}x${job.height}" else "unknown"
}

private fun formatMetric(value: Float): String = "%.3f".format(java.util.Locale.US, value)

private fun metadataSummary(job: JSONObject?): List<Pair<String, String>> {
    if (job == null) return listOf("job.json" to "Missing or unreadable")
    return listOf(
        "cameraId", "resolutionMode", "requestedResolutionMode", "actualInputResolutionMode",
        "outputResolutionMode", "processStatus", "exportStatus", "exportVerified",
        "captureCompleteness", "partialCapture", "qualityScored", "qualityScoredAt",
        "qualityScoringVersion", "qualityScoringBackend", "qualitySummary",
        "fusionEngine", "fusionVersion", "referenceFrameIndex", "usedFrameCount",
        "excludedFrameCount", "skippedFrameCount", "ghostSuppressionUsed",
        "ghostRejectedPixelRatio", "processingTimeMs", "outputWidth", "outputHeight",
        "fusionPresetName", "fusionParamsVersion", "fusionParams",
        "debugArtifactStatus", "debugArtifactError", "fusionDebugFile",
        "fusedClassicPresetFile"
    ).mapNotNull { key ->
        if (!job.has(key) || job.isNull(key)) null else key to job.get(key).toString()
    }
}

private fun fusionDebugArtifactFiles(
    job: KeplerGalleryJobSummary
): List<Pair<String, java.io.File>> {
    val metadata = job.metadata ?: return emptyList()
    return listOf(
        "Reference frame" to metadata.optString("referenceFrameDebugFile", "reference_frame.png"),
        "Fused output" to metadata.optString("fusedClassicDebugFile", "fused_classic_yuv_v1.png"),
        "A/B comparison" to metadata.optString("comparisonDebugFile", "compare_reference_vs_fused.png")
    ).mapNotNull { (label, name) ->
        java.io.File(job.directory, name).takeIf { name.isNotBlank() && it.isFile }?.let { label to it }
    }
}

private fun fusionAlignmentSummary(job: JSONObject?): List<String> {
    val alignments = job?.optJSONArray("fusionAlignmentSummary") ?: return emptyList()
    return buildList {
        repeat(alignments.length()) { index ->
            val item = alignments.optJSONObject(index) ?: return@repeat
            add(
                "frame=${item.optInt("frameIndex", index)} " +
                    "dx=${item.optInt("alignDx")} dy=${item.optInt("alignDy")} " +
                    "score=${"%.4f".format(java.util.Locale.US, item.optDouble("alignmentScore", 0.0))} " +
                    "weight=${"%.3f".format(java.util.Locale.US, item.optDouble("globalWeight", 0.0))} " +
                    "used=${item.optBoolean("used")} " +
                    "skip=${item.optString("skipReason").ifBlank { "none" }}"
            )
        }
    }
}
