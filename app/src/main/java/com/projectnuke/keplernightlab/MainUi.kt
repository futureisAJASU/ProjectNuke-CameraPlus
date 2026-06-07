package com.projectnuke.keplernightlab

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun KeplerAppRoot() {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var showDebug by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when {
                !hasCameraPermission -> {
                    PermissionScreen(
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    )
                }

                showDebug -> {
                    DebugScreenWrapper(
                        onBack = { showDebug = false }
                    )
                }

                else -> {
                    MainCameraScreen(
                        onOpenDebug = { showDebug = true }
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionScreen(
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Kepler Camera",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "카메라 기능을 사용하려면 권한이 필요합니다.",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onRequestPermission
        ) {
            Text("카메라 권한 허용")
        }
    }
}

@Composable
fun MainCameraScreen(
    onOpenDebug: () -> Unit
) {
    val context = LocalContext.current

    var status by remember {
        mutableStateOf("대기 중")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Kepler Camera",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "Night Fusion Lab",
            style = MaterialTheme.typography.titleMedium
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "현재 모드",
                    style = MaterialTheme.typography.labelLarge
                )

                Text(
                    text = "YUV Burst + Motion",
                    style = MaterialTheme.typography.titleLarge
                )

                Text(
                    text = "4프레임 YUV 캡처와 자이로/회전벡터 로그를 함께 저장합니다.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            onClick = {
                status = "Night Fusion 캡처 시작..."
                captureYuvBurstGrayWithMotion(
                    context = context,
                    cameraId = "0",
                    frameCount = 4
                ) { newStatus ->
                    status = newStatus
                }
            }
        ) {
            Text("Night Fusion 캡처")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                status = "최근 YUV Burst 평균 합성 시작..."
                averageLatestYuvBurstGray(context) { newStatus ->
                    status = newStatus
                }
            }
        ) {
            Text("최근 촬영 평균 합성")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    status = "RAW DNG 촬영 시작..."
                    captureSingleRawDng(
                        context = context,
                        cameraId = "0"
                    ) { newStatus ->
                        status = newStatus
                    }
                }
            ) {
                Text("RAW")
            }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    status = "RAW Burst 촬영 시작..."
                    captureRawBurstDng(
                        context = context,
                        cameraId = "0",
                        frameCount = 4
                    ) { newStatus ->
                        status = newStatus
                    }
                }
            ) {
                Text("RAW 4장")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "상태",
                    style = MaterialTheme.typography.labelLarge
                )

                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenDebug
        ) {
            Text("디버그 화면 열기")
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val deleted = deleteKeplerCache(context)
                status = "캐시 삭제 완료\n삭제된 파일/폴더: $deleted 개"
            }
        ) {
            Text("캐시 삭제")
        }
    }
}

@Composable
fun DebugScreenWrapper(
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            onClick = onBack
        ) {
            Text("← 메인 화면으로")
        }

        Box(
            modifier = Modifier.weight(1f)
        ) {
            KeplerNightLabApp()
        }
    }
}