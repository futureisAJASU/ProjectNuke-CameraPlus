package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
    var selectedTab by remember { mutableStateOf(0) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var confirmDeleteFailed by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val selectedJobs by remember(jobs, selectedIds) {
        derivedStateOf { jobs.filter { it.id in selectedIds } }
    }
    val visiblePhotoJobs by remember(jobs) {
        derivedStateOf { jobs.filterNot { it.isSourceOnlyJob() } }
    }
    val selectedTotalBytes by remember(selectedJobs) {
        derivedStateOf { selectedJobs.sumOf { it.storage.totalJobBytes } }
    }
    val selectedCleanableBytes by remember(selectedJobs) {
        derivedStateOf { selectedJobs.sumOf { it.storage.cleanableBytes } }
    }

    LaunchedEffect(refreshKey) {
        runCatching { withContext(Dispatchers.IO) { loadKeplerGalleryJobs(context) } }
            .onSuccess {
                jobs = it
                selectedIds = selectedIds.intersect(it.map { job -> job.id }.toSet())
                error = null
            }
            .onFailure { error = "${it.javaClass.simpleName}: ${it.message}" }
    }

    if (selectedTab == 1) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(galleryBackground)
                .padding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues())
        ) {
            GalleryTabs(selectedTab) { selectedTab = it }
            CacheJobsScreen(onBack = onBack)
        }
        return
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

    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("선택한 사진을 삭제하시겠습니까?") },
            text = { Text("사진과 관련된 RAW/YUV 원본, 합성 파일, 디버그 파일이 함께 삭제됩니다. 이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteSelected = false
                    val targets = selectedJobs.map { it.directory }
                    scope.launch {
                        val failed = withContext(Dispatchers.IO) {
                            targets.mapNotNull { dir ->
                                deleteKeplerGalleryJob(context, dir).exceptionOrNull()?.let {
                                    "${dir.absolutePath}: ${it.message}"
                                }
                            }
                        }
                        if (failed.isEmpty()) {
                            Toast.makeText(context, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                        } else {
                            deleteError = "일부 파일을 삭제하지 못했습니다.\n" + failed.joinToString("\n")
                            Toast.makeText(context, "일부 파일을 삭제하지 못했습니다.", Toast.LENGTH_LONG).show()
                        }
                        selectedIds = emptySet()
                        refreshKey++
                    }
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelected = false }) { Text("취소") }
            }
        )
    }

    if (confirmDeleteFailed) {
        AlertDialog(
            onDismissRequest = { confirmDeleteFailed = false },
            title = { Text("실패한 작업을 삭제하시겠습니까?") },
            text = { Text("실패한 RAW/YUV 작업 폴더와 관련 파일이 함께 삭제됩니다. 이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteFailed = false
                    val targets = jobs.filter { it.isFailedJob() }.map { it.directory }
                    scope.launch {
                        val failed = withContext(Dispatchers.IO) {
                            targets.mapNotNull { dir ->
                                deleteKeplerGalleryJob(context, dir).exceptionOrNull()?.let {
                                    "${dir.absolutePath}: ${it.message}"
                                }
                            }
                        }
                        if (failed.isEmpty()) {
                            Toast.makeText(context, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                        } else {
                            deleteError = "일부 파일을 삭제하지 못했습니다.\n" + failed.joinToString("\n")
                            Toast.makeText(context, "일부 파일을 삭제하지 못했습니다.", Toast.LENGTH_LONG).show()
                        }
                        selectedIds = emptySet()
                        refreshKey++
                    }
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFailed = false }) { Text("취소") }
            }
        )
    }

    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(galleryBackground)
            .padding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text("뒤로") }
            Button(onClick = { refreshKey++ }) { Text("새로고침") }
        }
        GalleryTabs(selectedTab) { selectedTab = it }
        GalleryStorageHeader(summarizeKeplerGalleryStorage(jobs))
        TextButton(onClick = { confirmDeleteFailed = true }) {
            Text("실패한 작업 삭제")
        }
        if (selectedIds.isNotEmpty()) {
            GallerySelectionBar(
                selectedCount = selectedIds.size,
                selectedBytes = selectedTotalBytes,
                cleanableBytes = selectedCleanableBytes,
                onClear = { selectedIds = emptySet() },
                onDelete = { confirmDeleteSelected = true }
            )
        }
        deleteError?.let { Text(it, color = Color(0xFFFFB4A9)) }
        Text(error ?: "${visiblePhotoJobs.size}개 항목, 최신순", color = galleryMuted)
        if (visiblePhotoJobs.isEmpty() && error == null) {
            Text("표시할 사진이 없습니다.", color = galleryMuted)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            gridItems(visiblePhotoJobs, key = { it.id }) { job ->
                GalleryJobCard(
                    job = job,
                    selected = job.id in selectedIds,
                    selectionMode = selectedIds.isNotEmpty(),
                    onOpen = {
                        if (selectedIds.isNotEmpty()) {
                            selectedIds = selectedIds.toggle(job.id)
                        } else {
                            selected = job
                        }
                    },
                    onLongPress = {
                        selectedIds = selectedIds + job.id
                    }
                )
            }
        }
    }
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

private fun KeplerGalleryJobSummary.isFailedJob(): Boolean {
    val process = metadata?.optString("processStatus").orEmpty()
    val export = metadata?.optString("exportStatus").orEmpty()
    val combined = listOf(status, process, export).joinToString(" ").uppercase()
    return combined.contains("FAILED") || combined.contains("FAILURE") || combined.contains("ERROR")
}

private fun KeplerGalleryJobSummary.isSourceOnlyJob(): Boolean =
    metadata?.optString("cleanupType") == "SOURCE_ONLY" ||
        metadata?.optBoolean("galleryDisplayUnavailable", false) == true ||
        metadata?.optBoolean("galleryVisible", true) == false

private fun cleanupTitle(type: KeplerJobCleanupType): String = when (type) {
    KeplerJobCleanupType.DEBUG_ONLY -> "디버그 파일을 정리하시겠습니까?"
    KeplerJobCleanupType.SOURCE_FRAMES_ONLY -> "원본 프레임을 삭제하시겠습니까?"
    KeplerJobCleanupType.FINAL_ONLY -> "최종 사진만 남기시겠습니까?"
    KeplerJobCleanupType.SOURCE_ONLY -> "원본만 남기시겠습니까?"
    KeplerJobCleanupType.FAILED_JOB_DELETE -> "실패한 작업을 삭제하시겠습니까?"
}

private fun cleanupBody(type: KeplerJobCleanupType): String = when (type) {
    KeplerJobCleanupType.DEBUG_ONLY -> "최종 사진은 유지하고, 비교 이미지와 진단용 파일만 삭제합니다."
    KeplerJobCleanupType.SOURCE_FRAMES_ONLY ->
        "원본 RAW/YUV 프레임을 삭제하면 이 작업을 다시 합성하거나 재처리할 수 없습니다. 최종 사진은 유지됩니다."
    KeplerJobCleanupType.FINAL_ONLY ->
        "최종 사진과 기본 정보만 남기고 원본 프레임, 중간 합성 파일, 디버그 파일을 삭제합니다. 이 작업은 되돌릴 수 없습니다."
    KeplerJobCleanupType.SOURCE_ONLY ->
        "최종 사진, 미리보기, 중간 합성 파일, 디버그 파일을 삭제하고 원본 프레임만 보존합니다. 갤러리에는 최종 사진이 표시되지 않을 수 있지만, 나중에 다시 합성할 수 있습니다."
    KeplerJobCleanupType.FAILED_JOB_DELETE ->
        "실패한 RAW/YUV 작업 폴더와 관련 파일이 함께 삭제됩니다. 이 작업은 되돌릴 수 없습니다."
}

@Composable
private fun GalleryTabs(selectedTab: Int, onSelect: (Int) -> Unit) {
    TabRow(selectedTabIndex = selectedTab, containerColor = galleryBackground) {
        Tab(selected = selectedTab == 0, onClick = { onSelect(0) }, text = { Text("사진") })
        Tab(selected = selectedTab == 1, onClick = { onSelect(1) }, text = { Text("디버그") })
    }
}

@Composable
private fun GalleryStorageHeader(summary: KeplerGalleryStorageSummary) {
    GallerySection("저장 공간") {
        GalleryField("전체 사용량", formatBytes(summary.totalBytes))
        GalleryField("최종 사진", formatBytes(summary.finalOutputBytes))
        GalleryField("원본 프레임", formatBytes(summary.sourceFrameBytes))
        GalleryField("중간 합성 파일", formatBytes(summary.intermediateBytes))
        GalleryField("디버그/진단 파일", formatBytes(summary.debugDiagnosticBytes))
        GalleryField("정리 가능 용량", formatBytes(summary.cleanableBytes))
        GalleryField("RAW 작업", formatBytes(summary.rawBytes))
        GalleryField("YUV 작업", formatBytes(summary.yuvBytes))
        GalleryField("디버그/캐시", formatBytes(summary.debugCacheBytes))
        GalleryField("작업 수", "${summary.jobCount}개")
    }
}

@Composable
private fun GallerySelectionBar(
    selectedCount: Int,
    selectedBytes: Long,
    cleanableBytes: Long,
    onClear: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(color = galleryCard, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("선택됨: ${selectedCount}개")
            Text("선택한 항목 크기: ${formatBytes(selectedBytes)}", color = galleryMuted)
            Text("정리 가능 용량: ${formatBytes(cleanableBytes)}", color = galleryMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDelete) { Text("삭제") }
                TextButton(onClick = onClear) { Text("선택 해제") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryJobCard(
    job: KeplerGalleryJobSummary,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        color = if (selected) Color(0xFF263449) else galleryCard,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            job.finalPreviewFile?.let { file ->
                loadThumbnailSafe(file, 512)?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(120.dp).background(Color.Black),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Text(modeLabel(job), style = MaterialTheme.typography.titleMedium)
            Text("${formatTimestamp(job.createdAt)} | ${routeLabel(job)}", color = galleryMuted)
            Text(if (job.status.contains("COMPLETE")) resolutionText(job) else job.status)
            Text("파일 크기: ${job.storage.finalOutputSizeText}", color = galleryMuted)
            if (selectionMode) Text(if (selected) "선택됨" else "선택 가능", color = galleryMuted)
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
    var confirmCleanupType by remember { mutableStateOf<KeplerJobCleanupType?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var actionStatus by remember { mutableStateOf<String?>(null) }
    var showDebugInfo by remember { mutableStateOf(false) }
    var isReprocessing by remember { mutableStateOf(false) }
    var isAnalyzingQuality by remember { mutableStateOf(false) }
    var selectedFusionPreset by remember(job.id) {
        mutableStateOf(
            ClassicYuvFusionPreset.fromName(job.metadata?.optString("fusionPresetName"))
        )
    }
    var refreshKey by remember { mutableIntStateOf(0) }

    if (showDebugInfo) {
        JobDetailScreen(jobDir = currentJob.directory, onBack = { showDebugInfo = false })
        return
    }

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
            title = { Text("선택한 사진을 삭제하시겠습니까?") },
            text = { Text("사진과 관련된 RAW/YUV 원본, 합성 파일, 디버그 파일이 함께 삭제됩니다. 이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleteKeplerGalleryJob(context, currentJob.directory)
                        .onSuccess {
                            Toast.makeText(context, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                            onDeleted()
                        }
                        .onFailure {
                            deleteError = it.message ?: "일부 파일을 삭제하지 못했습니다."
                            Toast.makeText(context, "일부 파일을 삭제하지 못했습니다.", Toast.LENGTH_LONG).show()
                        }
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("취소") }
            }
        )
    }

    confirmCleanupType?.let { cleanupType ->
        val confirmText = if (cleanupType == KeplerJobCleanupType.SOURCE_ONLY) "원본만 남기기" else "삭제"
        AlertDialog(
            onDismissRequest = { confirmCleanupType = null },
            title = { Text(cleanupTitle(cleanupType)) },
            text = { Text(cleanupBody(cleanupType)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCleanupType = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            cleanupKeplerGalleryJob(context, currentJob.directory, cleanupType)
                        }
                        result.onSuccess { cleanup ->
                            val warning = cleanup.metadataWarning?.let { " job.json 경고: $it" }.orEmpty()
                            val failed = if (cleanup.failedPaths.isEmpty()) {
                                ""
                            } else {
                                "\n일부 파일을 삭제하지 못했습니다.\n" + cleanup.failedPaths.joinToString("\n")
                            }
                            actionStatus = "정리되었습니다. 확보된 용량: ${formatBytes(cleanup.bytesFreed)}$warning$failed"
                            Toast.makeText(context, "정리되었습니다.", Toast.LENGTH_SHORT).show()
                            refreshKey++
                        }.onFailure {
                            actionStatus = "정리하지 못했습니다: ${it.message}"
                            Toast.makeText(context, "일부 파일을 삭제하지 못했습니다.", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(confirmText) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCleanupType = null }) { Text("취소") }
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
        item {
            QualityDiagnosticSection(currentJob)
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
                GalleryField("파일 크기", currentJob.storage.finalOutputSizeText)
                GalleryField("작업 폴더 크기", currentJob.storage.totalJobSizeText)
                GalleryField("원본 프레임 크기", formatBytes(currentJob.storage.rawFramesBytes))
                GalleryField("디버그 파일 크기", formatBytes(currentJob.storage.debugFilesBytes))
                GalleryField("파일 개수", "${currentJob.storage.fileCount}개")
                GalleryField("Folder size", formatBytes(currentJob.folderSizeBytes))
                GalleryField("Final/export", if (currentJob.finalExportExists) "Available" else "Not found")
                GalleryField("Path", currentJob.directory.absolutePath)
                Button(onClick = { showDebugInfo = true }) {
                    Text("디버그 정보 보기")
                }
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
            GallerySection("저장공간 정리") {
                val sourceAvailable = currentJob.storage.rawFramesBytes + currentJob.storage.intermediateFilesBytes > 0L
                val debugAvailable = currentJob.storage.debugFilesBytes + currentJob.storage.previewFilesBytes + currentJob.storage.cacheFilesBytes > 0L
                if (!debugAvailable) {
                    Text("디버그 파일이 이미 정리되었습니다.", color = galleryMuted)
                }
                Button(
                    enabled = debugAvailable,
                    onClick = { confirmCleanupType = KeplerJobCleanupType.DEBUG_ONLY }
                ) { Text("디버그 파일만 정리") }
                if (!sourceAvailable) {
                    Text("원본 프레임이 이미 삭제되었습니다.", color = galleryMuted)
                }
                Button(
                    enabled = sourceAvailable && currentJob.finalExportExists,
                    onClick = { confirmCleanupType = KeplerJobCleanupType.SOURCE_FRAMES_ONLY }
                ) { Text("원본 프레임 삭제") }
                Button(
                    enabled = currentJob.finalExportExists && (sourceAvailable || debugAvailable),
                    onClick = { confirmCleanupType = KeplerJobCleanupType.FINAL_ONLY }
                ) { Text("최종 사진만 남기기") }
                Button(
                    enabled = sourceAvailable,
                    onClick = { confirmCleanupType = KeplerJobCleanupType.SOURCE_ONLY }
                ) { Text("원본만 남기기") }
                Button(onClick = { confirmDelete = true }) { Text("작업 전체 삭제") }
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

@Composable
private fun QualityDiagnosticSection(job: KeplerGalleryJobSummary) {
    val metadata = job.metadata
    val compareName = metadata?.optString("compareReferenceVsFinalFile").orEmpty()
        .ifBlank { metadata?.optString("yuvCompareReferenceVsFinalFile").orEmpty() }
        .ifBlank { metadata?.optString("qualityDiagnosticCompareFile").orEmpty() }
    val compareBitmap = remember(job.id, compareName) {
        if (compareName.isBlank()) null else loadThumbnailSafe(java.io.File(job.directory, compareName), 960)
    }
    GallerySection("품질 진단") {
        GalleryField("quality hint", metadata?.optString("fusionQualityHint").orEmpty().ifBlank { "none" })
        GalleryField("sharpness", qualitySummary(metadata, "Sharpness"))
        GalleryField("noise", qualitySummary(metadata, "NoiseEstimate"))
        GalleryField("sharpness drop", metadata.value("sharpnessDropReferenceToFused") + " / " + metadata.value("sharpnessDropFusedToFinal"))
        GalleryField("noise reduction", metadata.value("noiseReductionReferenceToFused") + " / " + metadata.value("noiseReductionFusedToFinal"))
        compareBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "reference vs final",
                modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black),
                contentScale = ContentScale.Fit
            )
        }
    }
}

private fun resolutionText(job: KeplerGalleryJobSummary): String {
    return if (job.width != null && job.height != null) "${job.width}x${job.height}" else "unknown"
}

private fun modeLabel(job: KeplerGalleryJobSummary): String = when (job.jobType) {
    "RAW_NIGHT_FUSION" -> "고품질 RAW"
    "YUV_NIGHT_FUSION" -> "빠른 야간"
    else -> job.jobType
}

private fun routeLabel(job: KeplerGalleryJobSummary): String {
    val route = job.metadata?.optString("finalZoomRoute").orEmpty()
        .ifBlank { job.metadata?.optString("captureRoute").orEmpty() }
    return when (route) {
        "OPTICAL" -> "3x 광학"
        "CROP" -> "3x 크롭"
        "MAIN_CROP" -> "3x"
        "MAIN_1X" -> "1x"
        else -> if ((job.metadata?.optDouble("requestedZoomRatio", 1.0) ?: 1.0) >= 2.9) {
            "3x"
        } else {
            "1x"
        }
    }
}

private fun formatMetric(value: Float): String = "%.3f".format(java.util.Locale.US, value)

private fun metadataSummary(job: JSONObject?): List<Pair<String, String>> {
    if (job == null) return listOf("job.json" to "Missing or unreadable")
    return listOf(
        "cameraId", "resolutionMode", "requestedResolutionMode", "actualInputResolutionMode",
        "outputResolutionMode", "processStatus", "exportStatus", "exportVerified",
        "cleanupApplied", "cleanupType", "cleanupAt", "bytesFreed",
        "remainingJobBytes", "sourceFramesAvailable", "finalOutputAvailable",
        "debugFilesAvailable", "canReprocess", "galleryVisible", "galleryDisplayUnavailable",
        "captureCompleteness", "partialCapture", "qualityScored", "qualityScoredAt",
        "qualityScoringVersion", "qualityScoringBackend", "qualitySummary",
        "fusionEngine", "fusionVersion", "referenceFrameIndex", "usedFrameCount",
        "excludedFrameCount", "skippedFrameCount", "ghostSuppressionUsed",
        "ghostRejectedPixelRatio", "processingTimeMs", "outputWidth", "outputHeight",
        "fusionPresetName", "fusionParamsVersion", "fusionParams",
        "debugArtifactStatus", "debugArtifactError", "fusionDebugFile",
        "fusedClassicPresetFile", "rawFusionEngine", "rawFusionVersion",
        "rawReferenceFrameIndex", "rawGhostSuppressionUsed", "rawOutlierRejectedRatio",
        "rawFusionProcessingTimeMs", "rawFusionDebugFile", "rawDebugArtifactStatus",
        "rawDebugArtifactError", "fusionQualityHint", "referenceSharpness",
        "fusedSharpness", "denoisedSharpness", "finalSharpness",
        "referenceNoiseEstimate", "fusedNoiseEstimate", "denoisedNoiseEstimate",
        "finalNoiseEstimate", "sharpnessDropReferenceToFused",
        "sharpnessDropFusedToFinal", "noiseReductionReferenceToFused",
        "noiseReductionFusedToFinal", "compareReferenceVsFinalFile",
        "yuvCompareReferenceVsFinalFile", "qualityDiagnosticNativeLimited"
    ).mapNotNull { key ->
        if (!job.has(key) || job.isNull(key)) null else key to job.get(key).toString()
    }
}

private fun qualitySummary(job: JSONObject?, suffix: String): String {
    if (job == null) return "none"
    return listOf("reference", "fused", "denoised", "final")
        .joinToString(" | ") { label ->
            val key = label + suffix
            "$label=${job.value(key)}"
        }
}

private fun JSONObject?.value(key: String): String {
    if (this == null || !has(key) || isNull(key)) return "none"
    return opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: "none"
}

private fun fusionDebugArtifactFiles(
    job: KeplerGalleryJobSummary
): List<Pair<String, java.io.File>> {
    val metadata = job.metadata ?: return emptyList()
    return listOf(
        "Reference frame" to metadata.optString("referenceFrameDebugFile", "reference_frame.png"),
        "Fused output" to metadata.optString("fusedClassicDebugFile", "fused_classic_yuv_v1.png"),
        "A/B comparison" to metadata.optString("comparisonDebugFile", "compare_reference_vs_fused.png"),
        "Reference vs final" to metadata.optString("compareReferenceVsFinalFile", "compare_reference_vs_final.png"),
        "YUV reference vs final" to metadata.optString("yuvCompareReferenceVsFinalFile", "yuv_compare_reference_vs_final.png"),
        "RAW reference" to metadata.optString("rawReferencePreviewFile", "raw_reference_preview.png"),
        "RAW fused" to metadata.optString("rawFusedPreviewFile", "raw_fused_classic_v1_preview.png"),
        "RAW A/B comparison" to metadata.optString("rawComparePreviewFile", "raw_compare_reference_vs_fused.png")
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
