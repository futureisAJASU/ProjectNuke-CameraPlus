package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import java.io.File

private val galleryFixedBackground = Color(0xFF080A0F)
private val galleryFixedCard = Color(0xFF141821)
private val galleryFixedMuted = Color.White.copy(alpha = 0.65f)

private const val TAB_PHOTOS_ONLY = 0
private const val TAB_INFO = 1
private const val TAB_DEBUG = 2

@Composable
fun KeplerGalleryScreenFixed(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf(emptyList<KeplerGalleryJobSummary>()) }
    var selectedJob by remember { mutableStateOf<KeplerGalleryJobSummary?>(null) }
    var selectedTab by remember { mutableIntStateOf(TAB_PHOTOS_ONLY) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var confirmDeleteFailed by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    val visiblePhotoJobs by remember(jobs) {
        derivedStateOf { jobs.filterNot { it.isSourceOnlyGalleryJob() } }
    }
    val selectedJobs by remember(jobs, selectedIds) {
        derivedStateOf { jobs.filter { it.id in selectedIds } }
    }
    val selectedTotalBytes by remember(selectedJobs) {
        derivedStateOf { selectedJobs.sumOf { it.storage.totalJobBytes } }
    }
    val selectedCleanableBytes by remember(selectedJobs) {
        derivedStateOf { selectedJobs.sumOf { it.storage.cleanableBytes } }
    }

    BackHandler {
        if (selectedIds.isNotEmpty()) selectedIds = emptySet() else onBack()
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

    selectedJob?.let { job ->
        KeplerGalleryDetailScreenFixed(
            job = job,
            onBack = { selectedJob = null },
            onDeleted = {
                selectedJob = null
                refreshKey++
            }
        )
        return
    }

    if (confirmDeleteSelected) {
        ConfirmDeleteJobsDialog(
            onDismiss = { confirmDeleteSelected = false },
            onConfirm = {
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
            }
        )
    }

    if (confirmDeleteFailed) {
        ConfirmFailedDeleteDialog(
            onDismiss = { confirmDeleteFailed = false },
            onConfirm = {
                confirmDeleteFailed = false
                val targets = jobs.filter { it.isFailedGalleryJob() }.map { it.directory }
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
            }
        )
    }

    if (selectedTab == TAB_DEBUG) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(galleryFixedBackground)
                .padding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues())
        ) {
            GalleryFixedTabs(selectedTab) { selectedTab = it }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                CacheJobsScreen(onBack = onBack)
            }
        }
        return
    }

    if (selectedTab == TAB_PHOTOS_ONLY) {
        GalleryPhotosOnlyGrid(
            visiblePhotoJobs = visiblePhotoJobs,
            selectedIds = selectedIds,
            error = error,
            onBack = onBack,
            onRefresh = { refreshKey++ },
            onSelectTab = { selectedTab = it },
            onOpen = { job ->
                if (selectedIds.isNotEmpty()) selectedIds = selectedIds.toggleGalleryId(job.id) else selectedJob = job
            },
            onLongPress = { job -> selectedIds = selectedIds + job.id },
            onClearSelection = { selectedIds = emptySet() },
            onDeleteSelection = { confirmDeleteSelected = true },
            selectedTotalBytes = selectedTotalBytes,
            selectedCleanableBytes = selectedCleanableBytes,
            deleteError = deleteError
        )
        return
    }

    GalleryInfoGrid(
        jobs = jobs,
        visiblePhotoJobs = visiblePhotoJobs,
        selectedIds = selectedIds,
        error = error,
        onBack = onBack,
        onRefresh = { refreshKey++ },
        onSelectTab = { selectedTab = it },
        onDeleteFailed = { confirmDeleteFailed = true },
        onOpen = { job ->
            if (selectedIds.isNotEmpty()) selectedIds = selectedIds.toggleGalleryId(job.id) else selectedJob = job
        },
        onLongPress = { job -> selectedIds = selectedIds + job.id },
        onClearSelection = { selectedIds = emptySet() },
        onDeleteSelection = { confirmDeleteSelected = true },
        selectedTotalBytes = selectedTotalBytes,
        selectedCleanableBytes = selectedCleanableBytes,
        deleteError = deleteError
    )
}

@Composable
private fun GalleryPhotosOnlyGrid(
    visiblePhotoJobs: List<KeplerGalleryJobSummary>,
    selectedIds: Set<String>,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onOpen: (KeplerGalleryJobSummary) -> Unit,
    onLongPress: (KeplerGalleryJobSummary) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    selectedTotalBytes: Long,
    selectedCleanableBytes: Long,
    deleteError: String?
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(118.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(galleryFixedBackground)
            .padding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 10.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onBack) { Text("뒤로") }
                    Button(onClick = onRefresh) { Text("새로고침") }
                }
                GalleryFixedTabs(TAB_PHOTOS_ONLY, onSelectTab)
                if (selectedIds.isNotEmpty()) {
                    GalleryFixedSelectionBar(
                        selectedCount = selectedIds.size,
                        selectedBytes = selectedTotalBytes,
                        cleanableBytes = selectedCleanableBytes,
                        onClear = onClearSelection,
                        onDelete = onDeleteSelection
                    )
                }
                deleteError?.let { Text(it, color = Color(0xFFFFB4A9)) }
                Text(error ?: "${visiblePhotoJobs.size}개 항목", color = galleryFixedMuted)
                if (visiblePhotoJobs.isEmpty() && error == null) {
                    Text("표시할 사진이 없습니다.", color = galleryFixedMuted)
                }
            }
        }
        items(visiblePhotoJobs, key = { it.id }) { job ->
            GalleryPhotoOnlyCard(
                job = job,
                selected = job.id in selectedIds,
                selectionMode = selectedIds.isNotEmpty(),
                onOpen = { onOpen(job) },
                onLongPress = { onLongPress(job) }
            )
        }
    }
}

@Composable
private fun GalleryInfoGrid(
    jobs: List<KeplerGalleryJobSummary>,
    visiblePhotoJobs: List<KeplerGalleryJobSummary>,
    selectedIds: Set<String>,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onDeleteFailed: () -> Unit,
    onOpen: (KeplerGalleryJobSummary) -> Unit,
    onLongPress: (KeplerGalleryJobSummary) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    selectedTotalBytes: Long,
    selectedCleanableBytes: Long,
    deleteError: String?
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(galleryFixedBackground)
            .padding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onBack) { Text("뒤로") }
                    Button(onClick = onRefresh) { Text("새로고침") }
                }
                GalleryFixedTabs(TAB_INFO, onSelectTab)
                GalleryFixedStorageHeader(summarizeKeplerGalleryStorage(jobs))
                TextButton(onClick = onDeleteFailed) { Text("실패한 작업 삭제") }
                if (selectedIds.isNotEmpty()) {
                    GalleryFixedSelectionBar(
                        selectedCount = selectedIds.size,
                        selectedBytes = selectedTotalBytes,
                        cleanableBytes = selectedCleanableBytes,
                        onClear = onClearSelection,
                        onDelete = onDeleteSelection
                    )
                }
                deleteError?.let { Text(it, color = Color(0xFFFFB4A9)) }
                Text(error ?: "${visiblePhotoJobs.size}개 항목, 최신순", color = galleryFixedMuted)
                if (visiblePhotoJobs.isEmpty() && error == null) {
                    Text("표시할 사진이 없습니다.", color = galleryFixedMuted)
                }
            }
        }
        items(visiblePhotoJobs, key = { it.id }) { job ->
            GalleryFixedJobCard(
                job = job,
                selected = job.id in selectedIds,
                selectionMode = selectedIds.isNotEmpty(),
                onOpen = { onOpen(job) },
                onLongPress = { onLongPress(job) }
            )
        }
    }
}

private fun Set<String>.toggleGalleryId(id: String): Set<String> =
    if (id in this) this - id else this + id

private fun KeplerGalleryJobSummary.isFailedGalleryJob(): Boolean {
    val process = metadata?.optString("processStatus").orEmpty()
    val export = metadata?.optString("exportStatus").orEmpty()
    val combined = listOf(status, process, export).joinToString(" ").uppercase()
    return combined.contains("FAILED") || combined.contains("FAILURE") || combined.contains("ERROR")
}

private fun KeplerGalleryJobSummary.isSourceOnlyGalleryJob(): Boolean =
    metadata?.optString("cleanupType") == "SOURCE_ONLY" ||
        metadata?.optBoolean("galleryDisplayUnavailable", false) == true ||
        metadata?.optBoolean("galleryVisible", true) == false

@Composable
private fun GalleryFixedTabs(selectedTab: Int, onSelect: (Int) -> Unit) {
    TabRow(selectedTabIndex = selectedTab, containerColor = galleryFixedBackground) {
        Tab(selected = selectedTab == TAB_PHOTOS_ONLY, onClick = { onSelect(TAB_PHOTOS_ONLY) }, text = { Text("사진만") })
        Tab(selected = selectedTab == TAB_INFO, onClick = { onSelect(TAB_INFO) }, text = { Text("정보") })
        Tab(selected = selectedTab == TAB_DEBUG, onClick = { onSelect(TAB_DEBUG) }, text = { Text("디버그") })
    }
}

@Composable
private fun GalleryFixedStorageHeader(summary: KeplerGalleryStorageSummary) {
    GalleryFixedSection("저장 공간") {
        GalleryFixedField("전체 사용량", formatBytes(summary.totalBytes))
        GalleryFixedField("최종 사진", formatBytes(summary.finalOutputBytes))
        GalleryFixedField("원본 프레임", formatBytes(summary.sourceFrameBytes))
        GalleryFixedField("중간 합성 파일", formatBytes(summary.intermediateBytes))
        GalleryFixedField("디버그/진단 파일", formatBytes(summary.debugDiagnosticBytes))
        GalleryFixedField("정리 가능 용량", formatBytes(summary.cleanableBytes))
        GalleryFixedField("RAW 작업", formatBytes(summary.rawBytes))
        GalleryFixedField("YUV 작업", formatBytes(summary.yuvBytes))
        GalleryFixedField("작업 수", "${summary.jobCount}개")
    }
}

@Composable
private fun GalleryFixedSelectionBar(
    selectedCount: Int,
    selectedBytes: Long,
    cleanableBytes: Long,
    onClear: () -> Unit,
    onDelete: () -> Unit
) {
    GalleryFixedSection("선택") {
        GalleryFixedField("선택됨", "${selectedCount}개")
        GalleryFixedField("선택한 항목 크기", formatBytes(selectedBytes))
        GalleryFixedField("정리 가능 용량", formatBytes(cleanableBytes))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDelete) { Text("삭제") }
            TextButton(onClick = onClear) { Text("선택 해제") }
        }
    }
}

@Composable
private fun AsyncThumbnailImage(
    file: File?,
    maxDimension: Int,
    modifier: Modifier,
    contentScale: ContentScale,
    contentDescription: String? = null
) {
    var bitmap by remember(file?.absolutePath, maxDimension) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file?.absolutePath, maxDimension) {
        bitmap = null
        bitmap = withContext(Dispatchers.IO) {
            file?.let { loadThumbnailSafe(it, maxDimension) }
        }
    }
    Box(modifier = modifier.background(Color.Black)) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryPhotoOnlyCard(
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
        color = if (selected) Color(0xFF263449) else galleryFixedCard,
        shape = RoundedCornerShape(10.dp)
    ) {
        Box {
            AsyncThumbnailImage(
                file = job.finalPreviewFile,
                maxDimension = 384,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(128.dp),
                contentScale = ContentScale.Crop
            )
            if (selectionMode) {
                Text(
                    text = if (selected) "선택됨" else "선택 가능",
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(6.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryFixedJobCard(
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
        color = if (selected) Color(0xFF263449) else galleryFixedCard,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            AsyncThumbnailImage(
                file = job.finalPreviewFile,
                maxDimension = 512,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentScale = ContentScale.Crop
            )
            Text(modeLabelFixed(job), style = MaterialTheme.typography.titleMedium)
            Text("${formatTimestamp(job.createdAt)} | ${routeLabelFixed(job)}", color = galleryFixedMuted)
            Text(if (job.status.contains("COMPLETE")) resolutionTextFixed(job) else job.status)
            Text("파일 크기: ${job.storage.finalOutputSizeText}", color = galleryFixedMuted)
            if (selectionMode) Text(if (selected) "선택됨" else "선택 가능", color = galleryFixedMuted)
        }
    }
}

@Composable
private fun KeplerGalleryDetailScreenFixed(
    job: KeplerGalleryJobSummary,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentJob by remember(job.id) { mutableStateOf(job) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmCleanupType by remember { mutableStateOf<KeplerJobCleanupType?>(null) }
    var showDebugInfo by remember { mutableStateOf(false) }
    var actionStatus by remember { mutableStateOf<String?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    if (showDebugInfo) {
        JobDetailScreen(jobDir = currentJob.directory, onBack = { showDebugInfo = false })
        return
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(job.id, refreshKey) {
        currentJob = withContext(Dispatchers.IO) {
            loadKeplerGalleryJobs(context).firstOrNull { it.id == job.id }
        } ?: currentJob
    }

    if (confirmDelete) {
        ConfirmDeleteJobsDialog(
            onDismiss = { confirmDelete = false },
            onConfirm = {
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
            }
        )
    }

    confirmCleanupType?.let { cleanupType ->
        AlertDialog(
            onDismissRequest = { confirmCleanupType = null },
            title = { Text(cleanupTitleFixed(cleanupType)) },
            text = { Text(cleanupBodyFixed(cleanupType)) },
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
                }) { Text(if (cleanupType == KeplerJobCleanupType.SOURCE_ONLY) "원본만 남기기" else "삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmCleanupType = null }) { Text("취소") } }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(galleryFixedBackground)
            .padding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBack) { Text("뒤로") }
                Button(onClick = { confirmDelete = true }) { Text("삭제") }
            }
            Text(currentJob.directory.name, style = MaterialTheme.typography.headlineSmall)
            deleteError?.let { Text(it, color = Color(0xFFFFB4A9)) }
            actionStatus?.let { Text(it, color = galleryFixedMuted) }
        }
        item {
            AsyncThumbnailImage(
                file = currentJob.finalPreviewFile,
                maxDimension = 1280,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentScale = ContentScale.Fit,
                contentDescription = "Final image"
            )
        }
        item { GalleryFixedQualitySection(currentJob) }
        item {
            GalleryFixedSection("요약") {
                GalleryFixedField("모드", modeLabelFixed(currentJob))
                GalleryFixedField("생성", formatTimestamp(currentJob.createdAt))
                GalleryFixedField("상태", currentJob.status)
                GalleryFixedField("프레임", "${currentJob.savedFrames}/${currentJob.requestedFrames}")
                GalleryFixedField("해상도", resolutionTextFixed(currentJob))
                GalleryFixedField("파일 크기", currentJob.storage.finalOutputSizeText)
                GalleryFixedField("작업 폴더 크기", currentJob.storage.totalJobSizeText)
                GalleryFixedField("원본 프레임 크기", formatBytes(currentJob.storage.rawFramesBytes))
                GalleryFixedField("디버그 파일 크기", formatBytes(currentJob.storage.debugFilesBytes))
                GalleryFixedField("파일 개수", "${currentJob.storage.fileCount}개")
                GalleryFixedField("경로", currentJob.directory.absolutePath)
                Button(onClick = { showDebugInfo = true }) { Text("디버그 정보 보기") }
            }
        }
        item {
            GalleryFixedSection("저장공간 정리") {
                val sourceAvailable = currentJob.storage.rawFramesBytes + currentJob.storage.intermediateFilesBytes > 0L
                val debugAvailable = currentJob.storage.debugFilesBytes + currentJob.storage.previewFilesBytes + currentJob.storage.cacheFilesBytes > 0L
                if (!debugAvailable) Text("디버그 파일이 이미 정리되었습니다.", color = galleryFixedMuted)
                Button(
                    enabled = debugAvailable,
                    onClick = { confirmCleanupType = KeplerJobCleanupType.DEBUG_ONLY }
                ) { Text("디버그 파일만 정리") }
                if (!sourceAvailable) Text("원본 프레임이 이미 삭제되었습니다.", color = galleryFixedMuted)
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
                TextButton(enabled = false, onClick = {}) { Text("다시 합성하기는 아직 지원되지 않습니다.") }
                Button(onClick = { confirmDelete = true }) { Text("작업 전체 삭제") }
            }
        }
        item {
            GalleryFixedSection("메타데이터") {
                metadataSummaryFixed(currentJob.metadata).forEach { (key, value) ->
                    GalleryFixedField(key, value)
                }
            }
        }
    }
}

@Composable
private fun ConfirmDeleteJobsDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("선택한 사진을 삭제하시겠습니까?") },
        text = { Text("사진과 관련된 RAW/YUV 원본, 합성 파일, 디버그 파일이 함께 삭제됩니다. 이 작업은 되돌릴 수 없습니다.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("삭제") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun ConfirmFailedDeleteDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("실패한 작업을 삭제하시겠습니까?") },
        text = { Text("실패한 RAW/YUV 작업 폴더와 관련 파일이 함께 삭제됩니다. 이 작업은 되돌릴 수 없습니다.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("삭제") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun GalleryFixedSection(title: String, content: @Composable () -> Unit) {
    Surface(color = galleryFixedCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun GalleryFixedField(label: String, value: String) {
    Column {
        Text(label, color = galleryFixedMuted, style = MaterialTheme.typography.labelMedium)
        Text(value.ifBlank { "none" })
    }
}

@Composable
private fun GalleryFixedQualitySection(job: KeplerGalleryJobSummary) {
    val metadata = job.metadata
    val compareName = metadata?.optString("compareReferenceVsFinalFile").orEmpty()
        .ifBlank { metadata?.optString("yuvCompareReferenceVsFinalFile").orEmpty() }
        .ifBlank { metadata?.optString("qualityDiagnosticCompareFile").orEmpty() }
    GalleryFixedSection("품질 진단") {
        GalleryFixedField("quality hint", metadata?.optString("fusionQualityHint").orEmpty().ifBlank { "none" })
        GalleryFixedField("sharpness", qualitySummaryFixed(metadata, "Sharpness"))
        GalleryFixedField("noise", qualitySummaryFixed(metadata, "NoiseEstimate"))
        GalleryFixedField("sharpness drop", metadata.valueFixed("sharpnessDropReferenceToFused") + " / " + metadata.valueFixed("sharpnessDropFusedToFinal"))
        GalleryFixedField("noise reduction", metadata.valueFixed("noiseReductionReferenceToFused") + " / " + metadata.valueFixed("noiseReductionFusedToFinal"))
        if (compareName.isNotBlank()) {
            AsyncThumbnailImage(
                file = File(job.directory, compareName),
                maxDimension = 960,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentScale = ContentScale.Fit,
                contentDescription = "reference vs final"
            )
        }
    }
}

private fun resolutionTextFixed(job: KeplerGalleryJobSummary): String =
    if (job.width != null && job.height != null) "${job.width}x${job.height}" else "unknown"

private fun modeLabelFixed(job: KeplerGalleryJobSummary): String = when (job.jobType) {
    "RAW_NIGHT_FUSION" -> "고품질 RAW"
    "YUV_NIGHT_FUSION" -> "빠른 야간"
    else -> job.jobType
}

private fun routeLabelFixed(job: KeplerGalleryJobSummary): String {
    val route = job.metadata?.optString("finalZoomRoute").orEmpty()
        .ifBlank { job.metadata?.optString("captureRoute").orEmpty() }
    return when (route) {
        "OPTICAL" -> "3x 광학"
        "CROP" -> "3x 크롭"
        "MAIN_1X" -> "1x"
        else -> if ((job.metadata?.optDouble("requestedZoomRatio", 1.0) ?: 1.0) >= 2.9) "3x" else "1x"
    }
}

private fun cleanupTitleFixed(type: KeplerJobCleanupType): String = when (type) {
    KeplerJobCleanupType.DEBUG_ONLY -> "디버그 파일을 정리하시겠습니까?"
    KeplerJobCleanupType.SOURCE_FRAMES_ONLY -> "원본 프레임을 삭제하시겠습니까?"
    KeplerJobCleanupType.FINAL_ONLY -> "최종 사진만 남기시겠습니까?"
    KeplerJobCleanupType.SOURCE_ONLY -> "원본만 남기시겠습니까?"
    KeplerJobCleanupType.FAILED_JOB_DELETE -> "실패한 작업을 삭제하시겠습니까?"
}

private fun cleanupBodyFixed(type: KeplerJobCleanupType): String = when (type) {
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

private fun metadataSummaryFixed(job: JSONObject?): List<Pair<String, String>> {
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
        "fusionPresetName", "fusionParamsVersion", "rawFusionEngine", "rawFusionVersion",
        "fusionQualityHint", "referenceSharpness", "fusedSharpness", "denoisedSharpness",
        "finalSharpness", "referenceNoiseEstimate", "fusedNoiseEstimate",
        "denoisedNoiseEstimate", "finalNoiseEstimate", "sharpnessDropReferenceToFused",
        "sharpnessDropFusedToFinal", "noiseReductionReferenceToFused",
        "noiseReductionFusedToFinal", "compareReferenceVsFinalFile",
        "yuvCompareReferenceVsFinalFile", "qualityDiagnosticNativeLimited"
    ).mapNotNull { key ->
        if (!job.has(key) || job.isNull(key)) null else key to job.get(key).toString()
    }
}

private fun qualitySummaryFixed(job: JSONObject?, suffix: String): String {
    if (job == null) return "none"
    return listOf("reference", "fused", "denoised", "final")
        .joinToString(" | ") { label ->
            val key = label + suffix
            "$label=${job.valueFixed(key)}"
        }
}

private fun JSONObject?.valueFixed(key: String): String {
    if (this == null || !has(key) || isNull(key)) return "none"
    return opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: "none"
}
