package com.projectnuke.keplernightlab

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    var showCacheJobs by remember { mutableStateOf(false) }

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

    MaterialTheme(colorScheme = KeplerDarkScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            when {
                !hasCameraPermission -> PermissionScreen {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
                showDebug -> DebugScreenWrapper {
                    showDebug = false
                }
                showCacheJobs -> CacheJobsScreen {
                    showCacheJobs = false
                }
                else -> MainCameraScreen(
                    onOpenDebug = { showDebug = true },
                    onOpenCacheJobs = { showCacheJobs = true }
                )
            }
        }
    }
}

@Composable
fun PermissionScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "Kepler Camera",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "카메라 기능을 사용하려면 권한이 필요합니다.",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRequestPermission
            ) {
                Text("카메라 권한 허용")
            }
        }
    }
}
