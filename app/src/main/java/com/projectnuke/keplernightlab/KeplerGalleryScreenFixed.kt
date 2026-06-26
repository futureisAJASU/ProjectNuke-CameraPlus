package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private val galleryFixedBackground = Color(0xFF080A0F)
private val galleryFixedCard = Color(0xFF141821)
private val galleryFixedMuted = Color.White.copy(alpha = 0.65f)
private val galleryFixedSelectedBorder = Color.White
private val galleryFixedSelectedCard = Color(0xFF202020)
private val galleryFixedSelectedOverlay = Color.Black.copy(alpha = 0.28f)
private val galleryFixedUnselectedDim = Color.Black.copy(alpha = 0.18f)

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
        KeplerGalleryDetailScreenFixedV2(
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

@Composable
private fun GallerySelectionBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = "✓ 선택됨",
            color = Color.Black,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall
        )
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
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) galleryFixedSelectedBorder else Color.Transparent,
                shape = shape
            )
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        color = if (selected) galleryFixedSelectedCard else galleryFixedCard,
        shape = shape
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
            if (selectionMode && !selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(galleryFixedUnselectedDim)
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(galleryFixedSelectedOverlay)
                )
                GallerySelectionBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
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
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) galleryFixedSelectedBorder else Color.Transparent,
                shape = shape
            )
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        color = if (selected) galleryFixedSelectedCard else galleryFixedCard,
        shape = shape
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box {
                AsyncThumbnailImage(
                    file = job.finalPreviewFile,
                    maxDimension = 512,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentScale = ContentScale.Crop
                )
                if (selectionMode && !selected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(galleryFixedUnselectedDim)
                    )
                }
                if (selected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(galleryFixedSelectedOverlay)
                    )
                    GallerySelectionBadge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(7.dp)
                    )
                }
            }
            Text(modeLabelFixed(job), style = MaterialTheme.typography.titleMedium)
            Text("${formatTimestamp(job.createdAt)} | ${routeLabelFixed(job)}", color = galleryFixedMuted)
            Text(if (job.status.contains("COMPLETE")) resolutionTextFixed(job) else job.status)
            Text("파일 크기: ${job.storage.finalOutputSizeText}", color = galleryFixedMuted)
            if (selectionMode) {
                Text(
                    if (selected) "선택됨" else "선택 가능",
                    color = if (selected) galleryFixedSelectedBorder else galleryFixedMuted,
                    style = MaterialTheme.typography.labelLarge
                )
            }
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
    var isReprocessing by remember { mutableStateOf(false) }
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
            GalleryFixedReprocessSection(
                currentJob = currentJob,
                isReprocessing = isReprocessing,
                onStart = {
                    isReprocessing = true
                    scope.launch {
                        val result = reprocessKeplerGalleryJob(
                            context = context,
                            jobDir = currentJob.directory,
                            outputSettings = OutputSettingsStore.load(context),
                            onProgress = { actionStatus = it }
                        )
                        result.onSuccess {
                            actionStatus = "다시 합성되었습니다."
                            Toast.makeText(context, "다시 합성되었습니다.", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            actionStatus = it.message ?: "다시 합성하지 못했습니다."
                            Toast.makeText(context, "다시 합성하지 못했습니다.", Toast.LENGTH_LONG).show()
                        }
                        isReprocessing = false
                        refreshKey++
                    }
                }
            )
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
private fun GalleryFixedReprocessSection(
    currentJob: KeplerGalleryJobSummary,
    isReprocessing: Boolean,
    onStart: () -> Unit
) {
    val context = LocalContext.current
    var capability by remember(currentJob.id, currentJob.storage.totalJobBytes) {
        mutableStateOf<ReprocessCapability?>(null)
    }
    LaunchedEffect(currentJob.id, currentJob.storage.totalJobBytes) {
        capability = withContext(Dispatchers.IO) {
            detectReprocessCapability(context, currentJob.directory)
        }
    }
    val currentCapability = capability
    GalleryFixedSection("다시 합성") {
        if (currentCapability == null) {
            Text("원본 프레임 확인 중…", color = galleryFixedMuted)
        } else {
            GalleryFixedField("원본 프레임", "${currentCapability.sourceFrameCount}개")
            GalleryFixedField("최종 사진", if (currentCapability.finalOutputExists) "있음" else "없음")
            if (!currentCapability.canReprocess) {
                Text(currentCapability.reason, color = galleryFixedMuted)
            }
            Button(
                enabled = currentCapability.canReprocess && !isReprocessing,
                onClick = onStart
            ) {
                Text(if (isReprocessing) "다시 합성 중…" else "다시 합성하기")
            }
        }
    }
}

@Composable
private fun KeplerGalleryDetailScreenFixedV2(
    job: KeplerGalleryJobSummary,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentJob by remember(job.id) { mutableStateOf(job) }
    var actionStatus by remember { mutableStateOf<String?>(null) }
    var frameReviewItems by remember(job.id) { mutableStateOf<List<KeplerFrameReviewItem>>(emptyList()) }
    var frameSelectionMode by remember(job.id) { mutableStateOf(FrameSelectionMode.MANUAL) }
    var isReprocessing by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showReview by remember { mutableStateOf(false) }
    var showAiDialog by remember { mutableStateOf<FrameSelectionRecommendation?>(null) }

    LaunchedEffect(job.id, refreshKey) {
        currentJob = withContext(Dispatchers.IO) {
            loadKeplerGalleryJobs(context).firstOrNull { it.id == job.id }
        } ?: currentJob
        frameReviewItems = withContext(Dispatchers.IO) {
            loadFrameReviewItems(context, currentJob.directory).getOrElse { emptyList() }
        }
        frameSelectionMode = persistedFrameSelectionMode(currentJob.metadata ?: JSONObject())
            ?: FrameSelectionMode.MANUAL
    }

    if (showReview) {
        FrameReviewScreenFixed(
            job = currentJob,
            frames = frameReviewItems,
            onBack = {
                showReview = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        saveFrameSelection(currentJob.directory, frameSelectionMode, frameReviewItems)
                    }
                    refreshKey++
                }
            },
            onFramesChanged = {
                frameReviewItems = it
                frameSelectionMode = FrameSelectionMode.MANUAL
            }
        )
        return
    }

    showAiDialog?.let { recommendation ->
        AlertDialog(
            onDismissRequest = { showAiDialog = null },
            title = { Text("AI 추천") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(recommendation.summary)
                    Text("포함 ${recommendation.includedFrameIndices.size}개", color = galleryFixedMuted)
                    recommendation.excludedFrameReasons.entries.take(5).forEach { (index, reason) ->
                        Text("#$index 제외: $reason", color = galleryFixedMuted)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = applyRecommendationToReviewItems(frameReviewItems, recommendation)
                    frameReviewItems = updated
                    frameSelectionMode = FrameSelectionMode.AI_RECOMMENDED
                    showAiDialog = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            saveFrameSelection(currentJob.directory, FrameSelectionMode.AI_RECOMMENDED, updated)
                        }
                        actionStatus = recommendation.summary
                        refreshKey++
                    }
                }) { Text("추천 적용") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        frameReviewItems = applyRecommendationToReviewItems(frameReviewItems, recommendation)
                        frameSelectionMode = FrameSelectionMode.AI_RECOMMENDED
                        showAiDialog = null
                        showReview = true
                    }) { Text("직접 수정") }
                    TextButton(onClick = { showAiDialog = null }) { Text("취소") }
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(galleryFixedBackground)
            .padding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues())
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onBack) { Text("뒤로") }
            Button(onClick = onDeleted) { Text("목록 갱신") }
        }
        if (actionStatus != null) {
            Text(
                text = actionStatus.orEmpty(),
                color = galleryFixedMuted,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(currentJob.directory.name, style = MaterialTheme.typography.headlineSmall)
            }
            item {
                AsyncThumbnailImage(
                    file = currentJob.finalPreviewFile,
                    maxDimension = 1280,
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    contentScale = ContentScale.Fit,
                    contentDescription = "Final image"
                )
            }
            item { GalleryFixedQualitySection(currentJob) }
            item {
                GalleryFixedSection("프레임 선택") {
                    GalleryFixedField("원본 프레임", "${frameReviewItems.size}개")
                    GalleryFixedField("선택 포함", "${frameReviewItems.count { it.included }}개")
                    GalleryFixedField("선택 모드", frameSelectionMode.name)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                enabled = frameReviewItems.isNotEmpty(),
                                onClick = { showReview = true }
                            ) { Text("프레임 확인", textAlign = TextAlign.Center, maxLines = 1) }
                            Button(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                enabled = frameReviewItems.isNotEmpty(),
                                onClick = {
                                    scope.launch {
                                        val recommendation = withContext(Dispatchers.IO) {
                                            RuleBasedFrameSelectionAdvisor().recommend(currentJob, frameReviewItems)
                                        }
                                        val updated = applyRecommendationToReviewItems(frameReviewItems, recommendation)
                                        frameReviewItems = updated
                                        frameSelectionMode = FrameSelectionMode.AUTO_RULE_BASED
                                        withContext(Dispatchers.IO) {
                                            saveFrameSelection(currentJob.directory, FrameSelectionMode.AUTO_RULE_BASED, updated)
                                        }
                                        actionStatus = recommendation.summary
                                        refreshKey++
                                    }
                                }
                            ) { Text("자동 선택", textAlign = TextAlign.Center, maxLines = 1) }
                            Button(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                enabled = frameReviewItems.isNotEmpty(),
                                onClick = {
                                    scope.launch {
                                        showAiDialog = withContext(Dispatchers.IO) {
                                            AiFrameSelectionAdvisor().recommend(currentJob, frameReviewItems)
                                        }
                                    }
                                }
                            ) { Text("AI 추천", textAlign = TextAlign.Center, maxLines = 1) }
                        }
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = !isReprocessing && frameReviewItems.any { it.included },
                            onClick = {
                                isReprocessing = true
                                scope.launch {
                                    val selected = frameReviewItems.filter { it.included }.map { it.index }.toSet()
                                    withContext(Dispatchers.IO) {
                                        saveFrameSelection(currentJob.directory, frameSelectionMode, frameReviewItems)
                                    }
                                    val result = reprocessKeplerGalleryJob(
                                        context = context,
                                        jobDir = currentJob.directory,
                                        outputSettings = OutputSettingsStore.load(context),
                                        frameSelection = selected,
                                        onProgress = { actionStatus = it }
                                    )
                                    result.onSuccess {
                                        actionStatus = "다시 합성했습니다."
                                    }.onFailure {
                                        actionStatus = it.message ?: "다시 합성하지 못했습니다."
                                    }
                                    isReprocessing = false
                                    refreshKey++
                                }
                            }
                        ) {
                            Text(
                                if (isReprocessing) "다시 합성 중…" else "선택한 프레임으로 다시 합성",
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                    if (false) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = frameReviewItems.isNotEmpty(), onClick = { showReview = true }) { Text("프레임 확인") }
                        Button(
                            enabled = frameReviewItems.isNotEmpty(),
                            onClick = {
                                scope.launch {
                                    val recommendation = withContext(Dispatchers.IO) {
                                        RuleBasedFrameSelectionAdvisor().recommend(currentJob, frameReviewItems)
                                    }
                                    val updated = applyRecommendationToReviewItems(frameReviewItems, recommendation)
                                    frameReviewItems = updated
                                    frameSelectionMode = FrameSelectionMode.AUTO_RULE_BASED
                                    withContext(Dispatchers.IO) {
                                        saveFrameSelection(currentJob.directory, FrameSelectionMode.AUTO_RULE_BASED, updated)
                                    }
                                    actionStatus = recommendation.summary
                                    refreshKey++
                                }
                            }
                        ) { Text("자동 선택") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = frameReviewItems.isNotEmpty(), onClick = {
                            scope.launch {
                                showAiDialog = withContext(Dispatchers.IO) {
                                    AiFrameSelectionAdvisor().recommend(currentJob, frameReviewItems)
                                }
                            }
                        }) { Text("AI 추천") }
                        Button(
                            enabled = !isReprocessing && frameReviewItems.any { it.included },
                            onClick = {
                                isReprocessing = true
                                scope.launch {
                                    val selected = frameReviewItems.filter { it.included }.map { it.index }.toSet()
                                    withContext(Dispatchers.IO) {
                                        saveFrameSelection(currentJob.directory, frameSelectionMode, frameReviewItems)
                                    }
                                    val result = reprocessKeplerGalleryJob(
                                        context = context,
                                        jobDir = currentJob.directory,
                                        outputSettings = OutputSettingsStore.load(context),
                                        frameSelection = selected,
                                        onProgress = { actionStatus = it }
                                    )
                                    result.onSuccess {
                                        actionStatus = "다시 합성했습니다."
                                    }.onFailure {
                                        actionStatus = it.message ?: "다시 합성하지 못했습니다."
                                    }
                                    isReprocessing = false
                                    refreshKey++
                                }
                            }
                        ) { Text(if (isReprocessing) "다시 합성 중…" else "선택한 프레임으로 다시 합성") }
                    }
                    }
                }
            }
            item {
                GalleryFixedSection("요약") {
                    GalleryFixedField("모드", modeLabelFixed(currentJob))
                    GalleryFixedField("생성", formatTimestamp(currentJob.createdAt))
                    GalleryFixedField("상태", currentJob.status)
                    GalleryFixedField("해상도", resolutionTextFixed(currentJob))
                    GalleryFixedField("경로", currentJob.directory.absolutePath)
                }
            }
            item {
                Button(onClick = onDeleted) { Text("목록으로") }
            }
        }
    }
}

@Composable
private fun FrameReviewScreenFixed(
    job: KeplerGalleryJobSummary,
    frames: List<KeplerFrameReviewItem>,
    onBack: () -> Unit,
    onFramesChanged: (List<KeplerFrameReviewItem>) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(148.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(galleryFixedBackground)
            .padding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues())
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onBack) { Text("뒤로") }
                }
                Text(job.directory.name, style = MaterialTheme.typography.titleMedium)
                Text("포함 ${frames.count { it.included }} / 전체 ${frames.size}", color = galleryFixedMuted)
            }
        }
        items(frames, key = { it.index }) { item ->
            Surface(
                color = if (item.included) galleryFixedSelectedCard else galleryFixedCard,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, if (item.included) Color.White else galleryFixedMuted, RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = { onFramesChanged(toggleFrameReviewItem(frames, item.index)) },
                        onLongClick = { onFramesChanged(toggleFrameReviewItem(frames, item.index)) }
                    )
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                        AsyncThumbnailImage(
                            file = item.thumbnailFile,
                            maxDimension = 480,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            contentDescription = item.fileName
                        )
                        if (!item.included) {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                        }
                        Text(
                            text = if (item.included) "포함" else "제외",
                            color = if (item.included) Color.Black else Color.White,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .background(if (item.included) Color.White else Color.Black.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text("#${item.index}", style = MaterialTheme.typography.labelLarge)
                    Text(item.fileName, color = galleryFixedMuted)
                    Text(item.quality?.label ?: "품질 미평가", color = galleryFixedMuted)
                    Text(
                        text = item.reason ?: "사유 없음",
                        color = galleryFixedMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { onFramesChanged(toggleFrameReviewItem(frames, item.index)) }) {
                        Text(if (item.included) "제외" else "포함")
                    }
                }
            }
        }
    }
}

private fun toggleFrameReviewItem(
    frames: List<KeplerFrameReviewItem>,
    index: Int
): List<KeplerFrameReviewItem> = frames.map { frame ->
    if (frame.index != index) {
        frame
    } else {
        val included = !frame.included
        frame.copy(
            included = included,
            userDecision = if (included) FrameUserDecision.INCLUDE else FrameUserDecision.EXCLUDE,
            reason = if (included) frame.reason else frame.reason ?: "사용자 제외"
        )
    }
}

private fun applyRecommendationToReviewItems(
    frames: List<KeplerFrameReviewItem>,
    recommendation: FrameSelectionRecommendation
): List<KeplerFrameReviewItem> = frames.map { frame ->
    val included = frame.index in recommendation.includedFrameIndices
    frame.copy(
        included = included,
        recommendedInclude = included,
        userDecision = if (included) FrameUserDecision.AUTO else FrameUserDecision.EXCLUDE,
        reason = if (included) frame.reason else recommendation.excludedFrameReasons[frame.index] ?: frame.reason
    )
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
