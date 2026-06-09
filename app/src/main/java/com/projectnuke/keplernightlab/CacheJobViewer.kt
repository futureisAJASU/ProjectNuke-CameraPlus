package com.projectnuke.keplernightlab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class KeplerJobSummary(
    val jobDir: File,
    val group: String,
    val folderName: String,
    val jobType: String,
    val status: String,
    val processStatus: String,
    val exportStatus: String,
    val requestedResolutionMode: String,
    val outputResolutionMode: String,
    val savedFrames: Int,
    val requestedFrames: Int,
    val partialCapture: Boolean,
    val memoryRiskLevel: String,
    val nativePostprocessUsed: String,
    val finalOutputFileName: String?,
    val modifiedAt: Long
)

data class AlignmentFrameSummary(
    val index: Int,
    val proxyDx: Int,
    val proxyDy: Int,
    val rawDx: Int,
    val rawDy: Int,
    val confidence: Double,
    val normalizedError: Double,
    val validProxyFraction: Double,
    val acceptedForMerge: Boolean,
    val rejectReason: String?,
    val finalFrameWeightScale: Double
)

data class JobFileEntry(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val extension: String,
    val lastModified: Long,
    val group: String
)

data class KeplerJobDetail(
    val jobDir: File,
    val job: JSONObject?,
    val jobJsonPretty: String,
    val jobJsonError: String?,
    val alignment: JSONObject?,
    val alignmentJsonPretty: String?,
    val alignmentJsonError: String?,
    val nativePostprocess: JSONObject?,
    val nativePostprocessJsonPretty: String?,
    val nativePostprocessJsonError: String?,
    val alignmentFrames: List<AlignmentFrameSummary>,
    val files: List<JobFileEntry>,
    val totalSizeBytes: Long,
    val finalOutputFile: File?,
    val previewFile: File?
)

private val inspectorBackground = Color(0xFF080A0F)
private val inspectorSurface = Color(0xFF121620)
private val inspectorSurfaceAlt = Color(0xFF191F2B)
private val inspectorMuted = Color.White.copy(alpha = 0.62f)
private val inspectorWarning = Color(0xFFFFB4A9)

@Composable
fun CacheJobsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedJobDir by remember { mutableStateOf<File?>(null) }
    var jobs by remember { mutableStateOf(emptyList<KeplerJobSummary>()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) { loadKeplerJobSummaries(context) }
        }.onSuccess {
            jobs = it
        }.onFailure {
            loadError = "${it.javaClass.simpleName}: ${it.message}"
        }
        loading = false
    }

    selectedJobDir?.let { jobDir ->
        JobDetailScreen(
            jobDir = jobDir,
            onBack = { selectedJobDir = null }
        )
        return
    }

    BackHandler { onBack() }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(inspectorBackground)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBack) { Text("Back") }
                Button(onClick = { refreshKey++ }) { Text("Refresh") }
            }
            Text(
                text = "Cache / Jobs",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 14.dp)
            )
            Text(
                text = when {
                    loading -> "Loading jobs..."
                    loadError != null -> "Load failed: $loadError"
                    else -> "${jobs.size} jobs, newest first"
                },
                color = if (loadError == null) inspectorMuted else inspectorWarning,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        items(jobs, key = { it.jobDir.absolutePath }) { job ->
            JobSummaryCard(job) { selectedJobDir = job.jobDir }
        }

        if (!loading && jobs.isEmpty()) {
            item {
                InspectorSection("No jobs") {
                    Text(
                        "No job folders found under KeplerRawFusion, KeplerColorBurst, or KeplerYuvBurst.",
                        color = inspectorMuted
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun JobSummaryCard(job: KeplerJobSummary, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        color = inspectorSurface,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(job.folderName, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("${job.group} / ${job.jobType}", color = inspectorMuted)
            Text(
                "status=${job.status} | process=${job.processStatus} | export=${job.exportStatus}",
                color = Color.White.copy(alpha = 0.78f)
            )
            Text(
                "mode=${job.requestedResolutionMode} -> ${job.outputResolutionMode}",
                color = Color.White.copy(alpha = 0.78f)
            )
            Text(
                "frames=${job.savedFrames}/${job.requestedFrames} | partial=${job.partialCapture}",
                color = Color.White.copy(alpha = 0.78f)
            )
            Text(
                "memory=${job.memoryRiskLevel} | nativePostprocess=${job.nativePostprocessUsed}",
                color = if (job.memoryRiskLevel == "HIGH") inspectorWarning else Color.White.copy(alpha = 0.78f)
            )
            Text("final=${job.finalOutputFileName ?: "none"}", color = inspectorMuted)
        }
    }
}

@Composable
fun JobDetailScreen(jobDir: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var detail by remember(jobDir) { mutableStateOf<KeplerJobDetail?>(null) }
    var loading by remember(jobDir) { mutableStateOf(true) }
    var loadError by remember(jobDir) { mutableStateOf<String?>(null) }
    var refreshKey by remember(jobDir) { mutableIntStateOf(0) }
    var finalThumbnail by remember(jobDir) { mutableStateOf<Bitmap?>(null) }
    var previewThumbnail by remember(jobDir) { mutableStateOf<Bitmap?>(null) }
    var message by remember(jobDir) { mutableStateOf<String?>(null) }
    var showJobJson by remember(jobDir) { mutableStateOf(false) }
    var showAlignmentJson by remember(jobDir) { mutableStateOf(false) }
    var showNativeJson by remember(jobDir) { mutableStateOf(false) }

    LaunchedEffect(jobDir, refreshKey) {
        loading = true
        loadError = null
        val loaded = runCatching {
            withContext(Dispatchers.IO) { loadKeplerJobDetail(jobDir) }
        }.getOrElse {
            loadError = "${it.javaClass.simpleName}: ${it.message}"
            null
        }
        detail = loaded
        val loadedThumbnails = withContext(Dispatchers.IO) {
            val final = loaded?.finalOutputFile?.let { loadThumbnailSafe(it, 1280) }
            val preview = loaded?.previewFile?.takeUnless { it == loaded.finalOutputFile }?.let {
                loadThumbnailSafe(it, 960)
            }
            final to preview
        }
        finalThumbnail?.takeUnless { it.isRecycled }?.recycle()
        previewThumbnail?.takeUnless { it.isRecycled }?.recycle()
        finalThumbnail = loadedThumbnails.first
        previewThumbnail = loadedThumbnails.second
        loading = false
    }

    DisposableEffect(jobDir) {
        onDispose {
            finalThumbnail?.takeUnless { it.isRecycled }?.recycle()
            previewThumbnail?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    BackHandler { onBack() }
    val loaded = detail
    val job = loaded?.job

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(inspectorBackground)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onBack) { Text("Back") }
                Button(onClick = { refreshKey++ }) { Text("Refresh") }
                Button(
                    enabled = loaded != null,
                    onClick = {
                        copyText(clipboard, "job.json", loaded?.jobJsonPretty.orEmpty())
                        message = "Copied job.json"
                    }
                ) { Text("Copy job.json") }
                Button(
                    enabled = !loaded?.alignmentJsonPretty.isNullOrBlank(),
                    onClick = {
                        copyText(clipboard, "alignment.json", loaded?.alignmentJsonPretty.orEmpty())
                        message = "Copied alignment.json"
                    }
                ) { Text("Copy alignment.json") }
                Button(
                    enabled = !loaded?.nativePostprocessJsonPretty.isNullOrBlank(),
                    onClick = {
                        copyText(clipboard, "native_postprocess.json", loaded?.nativePostprocessJsonPretty.orEmpty())
                        message = "Copied native_postprocess.json"
                    }
                ) { Text("Copy native JSON") }
                Button(onClick = {
                    copyText(clipboard, "job folder", jobDir.absolutePath)
                    message = "Copied job folder path"
                }) { Text("Copy folder") }
                Button(
                    enabled = loaded != null,
                    onClick = {
                        copyText(clipboard, "job summary", buildShortJobSummary(jobDir, job))
                        message = "Copied short summary"
                    }
                ) { Text("Copy summary") }
            }
            Text(
                text = "Pipeline Inspector",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 14.dp)
            )
            Text(jobDir.name, color = inspectorMuted)
            when {
                loading -> Text("Loading job details...", color = inspectorMuted)
                loadError != null -> Text("Load failed: $loadError", color = inspectorWarning)
                message != null -> Text(message.orEmpty(), color = Color(0xFFA8D5BA))
            }
        }

        finalThumbnail?.let { bitmap ->
            item { ThumbnailCard("Final output", bitmap) }
        }
        previewThumbnail?.let { bitmap ->
            item { ThumbnailCard("Preview", bitmap) }
        }

        if (loaded != null) {
            item { SummarySection(jobDir, job) }
            item { CaptureSection(job) }
            item { HighResolutionSection(job) }
            item { AlignmentSection(job, loaded) }
            item { NativePostprocessSection(job, loaded) }
            item { ExportSection(job) }
            item { FilesSection(loaded) }
            item {
                RawJsonSection(
                    detail = loaded,
                    showJobJson = showJobJson,
                    onToggleJob = { showJobJson = !showJobJson },
                    showAlignmentJson = showAlignmentJson,
                    onToggleAlignment = { showAlignmentJson = !showAlignmentJson },
                    showNativeJson = showNativeJson,
                    onToggleNative = { showNativeJson = !showNativeJson }
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ThumbnailCard(title: String, bitmap: Bitmap) {
    InspectorSection(title) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = title,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(Color.Black, RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit
        )
        Text("${bitmap.width}x${bitmap.height} sampled thumbnail", color = inspectorMuted)
    }
}

@Composable
private fun SummarySection(jobDir: File, job: JSONObject?) {
    InspectorSection("Summary") {
        DetailField("job folder", jobDir.name)
        DetailField("jobType", job.value("jobType"))
        DetailField("status", job.value("status"))
        DetailField("processStatus", job.value("processStatus"))
        DetailField("exportStatus", job.value("exportStatus"))
        DetailField("createdAt", formatTimestamp(job.longValue("createdAt")))
        DetailField("capturedAt", formatTimestamp(job.longValue("capturedAt")))
        DetailField("processedAt", formatTimestamp(job.longValue("processedAt")))
        DetailField("exportedAt", formatTimestamp(job.longValue("exportedAt")))
        DetailField("finalOutputFormatSetting", job.value("finalOutputFormatSetting"))
        DetailField("exportDisplayName", job.value("exportDisplayName"))
        DetailField("exportVerified", job.value("exportVerified"))
    }
}

@Composable
private fun CaptureSection(job: JSONObject?) {
    InspectorSection("Capture") {
        listOf(
            "cameraId", "resolutionMode", "requestedResolutionMode", "actualInputResolutionMode",
            "outputResolutionMode", "selectedPlanReason", "rawWidth", "rawHeight", "inputWidth",
            "inputHeight", "usesMaximumResolution", "usesHighResolutionSlowPath", "requestedFrames",
            "attemptedFrames", "savedFrames", "receivedImages", "completedResults", "failedCaptures",
            "captureCompleteness", "partialCapture", "partialReason"
        ).forEach { DetailField(it, job.value(it)) }
    }
}

@Composable
private fun HighResolutionSection(job: JSONObject?) {
    InspectorSection("24MP / High-res Fusion") {
        listOf(
            "selected24MpStrategy", "nativePostprocessRequired", "nativePostprocessUsed",
            "nativePostprocessStatus", "nativePostprocessMetadataFile", "nativePostprocessRgbaFile",
            "fullSizeKotlinDemosaicUsed", "outputFallbackReason", "highResRawInputThresholdPixels",
            "highResRawInputThresholdMp", "memoryRiskLevel", "estimatedRawFusionMemoryMb",
            "outputWidth", "outputHeight"
        ).forEach { key ->
            val warning = key == "nativePostprocessStatus" &&
                job.value(key).contains("ERROR", ignoreCase = true)
            DetailField(key, job.value(key), warning)
        }
    }
}

@Composable
private fun AlignmentSection(job: JSONObject?, detail: KeplerJobDetail) {
    InspectorSection("Alignment") {
        listOf(
            "alignmentStatus", "nativeRawMerge", "alignmentFile", "alignmentError",
            "referenceFrameIndex", "referenceFrameReason", "nativeMergeVersion",
            "acceptedFrameCount", "rejectedFrameCount", "ghostSuppressionEnabled",
            "ghostRejectedSampleRatio", "referencePreservedPixelRatio", "mergeWarning"
        ).forEach { DetailField(it, job.value(it), it == "alignmentError" && job.value(it) != "none") }

        detail.alignment?.let { alignment ->
            Spacer(Modifier.height(4.dp))
            DetailField("type", alignment.value("type"))
            DetailField("downscale", alignment.value("downscale"))
            DetailField("searchRadius", alignment.value("searchRadius"))
            DetailField("referenceIndex", alignment.value("referenceIndex"))
            DetailField("nativeMergeVersion", alignment.value("nativeMergeVersion"))
            DetailField(
                "accepted / rejected frames",
                "${alignment.value("acceptedFrameCount")} / ${alignment.value("rejectedFrameCount")}"
            )
            DetailField("ghostRejectedSampleRatio", alignment.value("ghostRejectedSampleRatio"))
            DetailField("referencePreservedPixelRatio", alignment.value("referencePreservedPixelRatio"))
            DetailField("lowConfidenceFrameCount", alignment.value("lowConfidenceFrameCount"))
            DetailField("searchBoundaryHitCount", alignment.value("searchBoundaryHitCount"))
            DetailField("mergeWarning", alignment.value("mergeWarning"))
        }
        detail.alignmentFrames.forEach { frame ->
            val warning = frame.confidence < 0.4 || !frame.acceptedForMerge
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (warning) Color(0xFF4B2527) else inspectorSurfaceAlt,
                        RoundedCornerShape(10.dp)
                    )
                    .padding(9.dp)
            ) {
                Text(
                    "frame ${frame.index} - ${if (frame.acceptedForMerge) "ACCEPTED" else "REJECTED"}",
                    color = if (warning) inspectorWarning else Color.White
                )
                Text(
                    "raw dx=${frame.rawDx}, dy=${frame.rawDy} | proxy dx=${frame.proxyDx}, dy=${frame.proxyDy} | confidence=${"%.3f".format(Locale.US, frame.confidence)}",
                    color = inspectorMuted
                )
                Text(
                    "normalizedError=${"%.4f".format(Locale.US, frame.normalizedError)} | valid=${"%.3f".format(Locale.US, frame.validProxyFraction)} | weight=${"%.3f".format(Locale.US, frame.finalFrameWeightScale)}",
                    color = inspectorMuted
                )
                frame.rejectReason?.let { Text("rejectReason=$it", color = inspectorWarning) }
            }
        }
        detail.alignmentJsonError?.let { Text(it, color = inspectorWarning) }
    }
}

@Composable
private fun NativePostprocessSection(job: JSONObject?, detail: KeplerJobDetail) {
    val required = job.booleanValue("nativePostprocessRequired")
    InspectorSection("Native Postprocess") {
        val native = detail.nativePostprocess
        if (native != null) {
            listOf(
                "nativePostprocessVersion", "inputWidth", "inputHeight", "outputWidth",
                "outputHeight", "demosaic", "wbMode", "wbGainR", "wbGainG", "wbGainB",
                "wbFallback", "toneMap", "blackLift", "gamma", "shoulderStrength",
                "chromaDenoise", "chromaDenoiseStrength", "sharpen", "sharpenStrength",
                "darkSharpenSuppression", "status", "outputPath", "outputName",
                "outputFormat", "nativePostprocess", "highResRawInput"
            ).forEach { DetailField(it, native.value(it)) }
        } else {
            Text(
                if (required) {
                    "WARNING: native postprocess was required, but native_postprocess.json is missing or unreadable."
                } else {
                    "native_postprocess.json not present."
                },
                color = if (required) inspectorWarning else inspectorMuted
            )
        }
        detail.nativePostprocessJsonError?.let { Text(it, color = inspectorWarning) }
    }
}

@Composable
private fun ExportSection(job: JSONObject?) {
    InspectorSection("Export") {
        listOf(
            "exportStatus", "exportVerified", "exportUri", "exportDisplayName", "exportMimeType",
            "exportFormatRequested", "exportFormatUsed", "exportFallbackUsed", "exportFileSizeBytes",
            "rawSidecarRequested", "rawSidecarExportStatus", "rawSidecarExportedFiles",
            "rawSidecarError"
        ).forEach { DetailField(it, job.value(it)) }
    }
}

@Composable
private fun FilesSection(detail: KeplerJobDetail) {
    InspectorSection("Files") {
        DetailField("total job folder size", formatBytes(detail.totalSizeBytes))
        listOf("JSON", "RAW/DNG", "PNG/JPEG/HEIF", "RGBA/native", "other").forEach { group ->
            val groupFiles = detail.files.filter { it.group == group }
            if (groupFiles.isNotEmpty()) {
                Text(
                    group,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
                groupFiles.forEach { entry ->
                    DetailField(
                        entry.name,
                        "${formatBytes(entry.sizeBytes)} | ${entry.extension.ifBlank { "none" }} | ${formatTimestamp(entry.lastModified)}"
                    )
                }
            }
        }
    }
}

@Composable
private fun RawJsonSection(
    detail: KeplerJobDetail,
    showJobJson: Boolean,
    onToggleJob: () -> Unit,
    showAlignmentJson: Boolean,
    onToggleAlignment: () -> Unit,
    showNativeJson: Boolean,
    onToggleNative: () -> Unit
) {
    InspectorSection("Raw JSON") {
        JsonDisclosure("job.json", showJobJson, onToggleJob, detail.jobJsonPretty, detail.jobJsonError)
        if (detail.alignmentJsonPretty != null || detail.alignmentJsonError != null) {
            JsonDisclosure(
                "alignment.json",
                showAlignmentJson,
                onToggleAlignment,
                detail.alignmentJsonPretty.orEmpty(),
                detail.alignmentJsonError
            )
        }
        if (detail.nativePostprocessJsonPretty != null || detail.nativePostprocessJsonError != null) {
            JsonDisclosure(
                "native_postprocess.json",
                showNativeJson,
                onToggleNative,
                detail.nativePostprocessJsonPretty.orEmpty(),
                detail.nativePostprocessJsonError
            )
        }
    }
}

@Composable
private fun JsonDisclosure(
    name: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    text: String,
    error: String?
) {
    Button(onClick = onToggle) {
        Text("${if (expanded) "Hide" else "Show"} $name")
    }
    if (expanded) {
        Text(
            text = error ?: text.ifBlank { "none" },
            color = if (error == null) Color.White.copy(alpha = 0.84f) else inspectorWarning,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF05070A), RoundedCornerShape(10.dp))
                .padding(10.dp)
        )
    }
}

@Composable
private fun InspectorSection(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = inspectorSurface,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DetailField(label: String, value: String, warning: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = inspectorMuted, style = MaterialTheme.typography.bodySmall)
        Text(
            value.ifBlank { "none" },
            color = if (warning) inspectorWarning else Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

fun findKeplerJobDirectories(context: Context): List<File> {
    val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return emptyList()
    val roots = listOf(
        "KeplerRawFusion",
        "KeplerColorBurst",
        "KeplerYuvBurst",
        "KeplerRaw",
        "KeplerRawBurst"
    )
    return roots.flatMap { root ->
        File(picturesDir, root).listFiles()
            ?.filter { it.isDirectory && File(it, "job.json").exists() }
            .orEmpty()
    }.sortedByDescending { it.lastModified() }
}

fun loadKeplerJobSummaries(context: Context): List<KeplerJobSummary> {
    return findKeplerJobDirectories(context).map { dir ->
        val job = parseJsonFile(File(dir, "job.json")).first
        KeplerJobSummary(
            jobDir = dir,
            group = dir.parentFile?.name ?: "unknown",
            folderName = dir.name,
            jobType = job.value("jobType"),
            status = job.value("status"),
            processStatus = job.value("processStatus"),
            exportStatus = job.value("exportStatus"),
            requestedResolutionMode = job.firstValue("requestedResolutionMode", "resolutionMode"),
            outputResolutionMode = job.firstValue("outputResolutionMode", "resolutionMode"),
            savedFrames = job.intValue("savedFrames"),
            requestedFrames = job.intValue("requestedFrames"),
            partialCapture = job.booleanValue("partialCapture"),
            memoryRiskLevel = job.value("memoryRiskLevel"),
            nativePostprocessUsed = job.value("nativePostprocessUsed"),
            finalOutputFileName = job.firstFileName("finalFile", "finalNightFusionFile", "averageColorFile"),
            modifiedAt = dir.lastModified()
        )
    }.sortedByDescending { it.modifiedAt }
}

fun loadKeplerJobDetail(jobDir: File): KeplerJobDetail {
    val jobFile = File(jobDir, "job.json")
    val alignmentFile = File(jobDir, "alignment.json")
    val nativeFile = File(jobDir, "native_postprocess.json")
    val (job, jobError) = parseJsonFile(jobFile)
    val (alignment, alignmentError) = parseJsonFile(alignmentFile)
    val (nativePostprocess, nativeError) = parseJsonFile(nativeFile)
    val files = jobDir.listFiles()
        ?.filter { it.isFile }
        ?.map { file ->
            JobFileEntry(
                file = file,
                name = file.name,
                sizeBytes = file.length(),
                extension = file.extension.lowercase(Locale.US),
                lastModified = file.lastModified(),
                group = fileGroup(file)
            )
        }
        ?.sortedWith(compareBy<JobFileEntry> { fileGroupRank(it.group) }.thenBy { it.name })
        .orEmpty()
    val alignmentFrames = alignment?.optJSONArray("frames").toObjectList().mapIndexed { index, frame ->
        AlignmentFrameSummary(
            index = frame.optInt("index", index),
            proxyDx = frame.optInt("proxyDx", 0),
            proxyDy = frame.optInt("proxyDy", 0),
            rawDx = frame.optInt("rawDx", 0),
            rawDy = frame.optInt("rawDy", 0),
            confidence = frame.optDouble("confidence", 0.0),
            normalizedError = frame.optDouble("normalizedError", 0.0),
            validProxyFraction = frame.optDouble("validProxyFraction", 1.0),
            acceptedForMerge = if (frame.has("acceptedForMerge")) {
                frame.optBoolean("acceptedForMerge", false)
            } else {
                true
            },
            rejectReason = frame.optString("rejectReason", "")
                .takeIf { it.isNotBlank() && it != "null" },
            finalFrameWeightScale = frame.optDouble("finalFrameWeightScale", 1.0)
        )
    }
    return KeplerJobDetail(
        jobDir = jobDir,
        job = job,
        jobJsonPretty = readJsonFilePretty(jobFile),
        jobJsonError = jobError,
        alignment = alignment,
        alignmentJsonPretty = alignmentFile.takeIf { it.exists() }?.let(::readJsonFilePretty),
        alignmentJsonError = alignmentError,
        nativePostprocess = nativePostprocess,
        nativePostprocessJsonPretty = nativeFile.takeIf { it.exists() }?.let(::readJsonFilePretty),
        nativePostprocessJsonError = nativeError,
        alignmentFrames = alignmentFrames,
        files = files,
        totalSizeBytes = folderSizeBytes(jobDir),
        finalOutputFile = job.resolveExistingFile(jobDir, "finalFile", "finalNightFusionFile", "averageColorFile"),
        previewFile = job.resolveExistingFile(jobDir, "previewFile", "averageColorFile")
    )
}

fun readJsonFilePretty(file: File): String {
    if (!file.exists()) return "File missing: ${file.name}"
    val raw = runCatching { file.readText() }
        .getOrElse { return "Read failed: ${it.javaClass.simpleName}: ${it.message}" }
    return runCatching { JSONObject(raw).toString(2) }
        .getOrElse {
            runCatching { JSONArray(raw).toString(2) }.getOrDefault(raw)
        }
}

fun folderSizeBytes(file: File): Long {
    if (!file.exists()) return 0L
    if (file.isFile) return file.length()
    return file.listFiles()?.sumOf(::folderSizeBytes) ?: 0L
}

fun formatBytes(bytes: Long): String {
    val formatter = DecimalFormat("0.0")
    return when {
        bytes >= 1024L * 1024L * 1024L -> "${formatter.format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        bytes >= 1024L * 1024L -> "${formatter.format(bytes / (1024.0 * 1024.0))} MB"
        bytes >= 1024L -> "${formatter.format(bytes / 1024.0)} KB"
        else -> "$bytes B"
    }
}

fun formatTimestamp(ms: Long?): String {
    if (ms == null || ms <= 0L) return "none"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(ms))
    }.getOrDefault(ms.toString())
}

fun loadThumbnailSafe(file: File, maxSizePx: Int): Bitmap? {
    if (!file.exists() || maxSizePx <= 0) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSizePx) {
        sample *= 2
    }
    return runCatching {
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }.getOrNull()
}

private fun parseJsonFile(file: File): Pair<JSONObject?, String?> {
    if (!file.exists()) return null to null
    return runCatching { JSONObject(file.readText()) }
        .fold(
            onSuccess = { it to null },
            onFailure = { null to "${file.name}: ${it.javaClass.simpleName}: ${it.message}" }
        )
}

private fun JSONArray?.toObjectList(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        repeat(length()) { index -> optJSONObject(index)?.let(::add) }
    }
}

private fun fileGroup(file: File): String = when (file.extension.lowercase(Locale.US)) {
    "json" -> "JSON"
    "raw16", "dng" -> "RAW/DNG"
    "png", "jpg", "jpeg", "heic", "heif" -> "PNG/JPEG/HEIF"
    "rgba", "rgb", "bin" -> "RGBA/native"
    else -> "other"
}

private fun fileGroupRank(group: String): Int = when (group) {
    "JSON" -> 0
    "RAW/DNG" -> 1
    "PNG/JPEG/HEIF" -> 2
    "RGBA/native" -> 3
    else -> 4
}

private fun JSONObject?.value(key: String): String {
    val value = this?.opt(key)?.takeUnless { it == JSONObject.NULL } ?: return "none"
    return when (value) {
        is JSONArray -> value.toString()
        is JSONObject -> value.toString()
        else -> value.toString().ifBlank { "none" }
    }
}

private fun JSONObject?.firstValue(vararg keys: String): String {
    return keys.firstNotNullOfOrNull { key ->
        value(key).takeUnless { it == "none" }
    } ?: "none"
}

private fun JSONObject?.intValue(key: String): Int {
    if (this == null || !has(key) || isNull(key)) return 0
    return optInt(key, 0)
}

private fun JSONObject?.longValue(key: String): Long? {
    if (this == null || !has(key) || isNull(key)) return null
    return optLong(key, 0L).takeIf { it > 0L }
}

private fun JSONObject?.booleanValue(key: String): Boolean {
    if (this == null || !has(key) || isNull(key)) return false
    return optBoolean(key, false)
}

private fun JSONObject?.firstFileName(vararg keys: String): String? {
    return keys.firstNotNullOfOrNull { key ->
        value(key).takeUnless { it == "none" }
    }
}

private fun JSONObject?.resolveExistingFile(jobDir: File, vararg keys: String): File? {
    return firstFileName(*keys)?.let { File(jobDir, it) }?.takeIf { it.isFile }
}

private fun copyText(clipboard: ClipboardManager, label: String, text: String) {
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun buildShortJobSummary(jobDir: File, job: JSONObject?): String = buildString {
    appendLine("job=${jobDir.name}")
    appendLine("type=${job.value("jobType")}")
    appendLine("status=${job.value("status")}")
    appendLine("processStatus=${job.value("processStatus")}")
    appendLine("mode=${job.value("actualInputResolutionMode")} -> ${job.value("outputResolutionMode")}")
    appendLine("strategy=${job.value("selected24MpStrategy")}")
    appendLine("nativePostprocess=${job.value("nativePostprocessRequired")}/${job.value("nativePostprocessUsed")}")
    appendLine("output=${job.value("outputWidth")}x${job.value("outputHeight")}")
    append("final=${job.firstValue("finalFile", "finalNightFusionFile")}")
}
