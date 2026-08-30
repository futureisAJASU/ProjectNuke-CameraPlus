package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val SideButtonSize: Dp = 56.dp
internal val ShutterOuterSize: Dp = 84.dp
internal val ShutterInnerSize: Dp = 64.dp
private val ModeTabsSpacing: Dp = 1.dp
private val TopOverlayHeight: Dp = 46.dp
private val TopOverlayHorizontalPadding: Dp = 12.dp
private val TopOverlayVerticalPadding: Dp = 2.dp
private val TopMiniButtonSize: Dp = 40.dp

@Composable
fun CameraTopOverlay(
    status: String,
    levelState: DeviceLevelState,
    selectedResolution: CaptureResolutionMode,
    onHideFocusAeControls: () -> Unit,
    onResolutionClick: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    meteringMode: MeteringMode = MeteringModeState.mode,
    onMeteringModeClick: () -> Unit = { MeteringModeState.cycle() }
) {
    val levelText = if (levelState.available) {
        "PITCH ${levelState.pitchDegrees.toInt()}°  ROLL ${levelState.rollDegrees.toInt()}°"
    } else {
        "LEVEL --"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                horizontal = TopOverlayHorizontalPadding,
                vertical = TopOverlayVerticalPadding
            )
            .height(TopOverlayHeight)
            .testTag("kepler.camera.overlay"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleMiniButton(label = "⚙", onClick = onSettings, testTag = "kepler.settings.open")

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .clickable(onClick = onHideFocusAeControls),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = status,
                color = Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("kepler.pipeline.status")
            )
            Text(
                text = levelText,
                color = Color.White.copy(alpha = 0.48f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        TopText(
            text = meteringMode.shortLabel,
            onClick = onMeteringModeClick
        )
        Spacer(modifier = Modifier.size(10.dp))
        TopText(
            text = when (selectedResolution) {
                CaptureResolutionMode.MP24_FUSION -> "24M Fusion"
                else -> selectedResolution.label
            },
            onClick = onResolutionClick,
            testTag = "kepler.camera.resolution"
        )
    }
}

@Composable
fun CircleMiniButton(label: String, onClick: () -> Unit, testTag: String? = null) {
    Box(
        modifier = Modifier
            .size(TopMiniButtonSize)
            .clip(CircleShape)
            .background(Color(0x80202229))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.14f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .let { modifier -> testTag?.let(modifier::testTag) ?: modifier },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall
        )
    }
}

@Composable
fun TopText(text: String, onClick: () -> Unit, testTag: String? = null) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .clickable(onClick = onClick)
            .let { modifier -> testTag?.let(modifier::testTag) ?: modifier }
    )
}

@Composable
fun ModeTabs(modifier: Modifier = Modifier) {
    val modes = listOf("인물 사진", "야간", "사진", "동영상", "더보기")
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ModeTabsSpacing)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            modes.forEach { mode ->
                val isPhoto = mode == "사진"
                Text(
                    text = mode,
                    color = if (isPhoto) {
                        Color.White
                    } else {
                        Color.White.copy(alpha = 0.28f)
                    },
                    style = if (isPhoto) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.labelLarge
                    }
                )
            }
        }
        Text(
            text = "사진 외 모드는 준비 중",
            color = Color.White.copy(alpha = 0.38f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
fun LandscapeModeTabs(
    layoutMode: CameraUiLayoutMode,
    modifier: Modifier = Modifier
) {
    val rotation = layoutMode.modeLabelRotationDegrees()
    val modes = listOf("인물 사진", "야간", "사진", "동영상", "더보기")
    // Each slot is a stable screen-space hit target. Only its Text content is
    // rotated; the lane and the five targets remain normal Compose geometry.
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LandscapeLayoutSpec.ModeSlotSpacingDp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEachIndexed { index, mode ->
            val isPhoto = mode == "사진"
            Box(
                modifier = Modifier
                    .size(
                        width = LandscapeLayoutSpec.ModeSlotWidthDp,
                        height = LandscapeLayoutSpec.ModeSlotHeightDp
                    )
                    .testTag("kepler.camera.mode.$index"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mode,
                    color = if (isPhoto) Color.White else Color.White.copy(alpha = 0.28f),
                    style = if (isPhoto) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation }
                )
            }
        }
    }
}

@Composable
fun ShutterButton(
    enabled: Boolean,
    isCapturing: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    LaunchedEffect(Unit) {
        android.util.Log.d(
            "KeplerShutter",
            "Rendering circular ShutterButton size=84dp inner=64dp"
        )
    }
    Box(
        modifier = Modifier
            .size(ShutterOuterSize)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.18f else 0.08f))
            .pointerInput(enabled, isCapturing) {
                if (enabled && !isCapturing) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { onLongPress() }
                    )
                }
            }
            .testTag("kepler.camera.shutter"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(ShutterInnerSize)
                .clip(CircleShape)
                .background(
                    if (enabled && !isCapturing) Color.White else Color(0xFFB8B8B8)
                )
        )
    }
}

@Composable
fun CameraSwitchButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(SideButtonSize)
            .clip(CircleShape)
            .background(Color(0xFF222229))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Centered refresh icon drawn independently of font baselines
        androidx.compose.foundation.Canvas(modifier = Modifier.size(28.dp)) {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) / 2f - 2.dp.toPx()
            // Outer circle
            drawCircle(
                color = Color(0xFF888888).copy(alpha = 0.9f),
                radius = radius,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
            )
            // Arrow head (right-pointing curved arrow top)
            val arrowTop = center.copy(y = center.y - radius * 0.45f)
            val arrowRight = center.copy(x = center.x + radius * 0.55f, y = center.y - radius * 0.15f)
            val arrowBottom = center.copy(y = center.y + radius * 0.15f)
            val arrowTopEnd = center.copy(x = center.x + radius * 0.55f, y = center.y - radius * 0.35f)
            drawLine(
                color = Color.White,
                start = arrowTop,
                end = arrowTopEnd,
                strokeWidth = 2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            // Small triangular arrowhead at arrowTopEnd
            val tip = arrowTopEnd
            val left = tip.copy(x = tip.x - 4.dp.toPx(), y = tip.y + 3.dp.toPx())
            val right = tip.copy(x = tip.x - 2.dp.toPx(), y = tip.y - 3.dp.toPx())
            drawLine(color = Color.White, start = tip, end = left, strokeWidth = 1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color = Color.White, start = tip, end = right, strokeWidth = 1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            // Bottom curved line (left side of circle) to complete refresh symbol
            val bottomLeft = center.copy(x = center.x - radius * 0.55f, y = center.y + radius * 0.35f)
            drawLine(
                color = Color(0xFF888888).copy(alpha = 0.9f),
                start = arrowBottom,
                end = bottomLeft,
                strokeWidth = 1.5.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}

@Composable
fun ResultThumbnail(bitmap: Bitmap?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(SideButtonSize)
            .clip(CircleShape)
            .background(Color(0xFF1A1A20))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.12f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .testTag("kepler.gallery.open"),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "최근 결과",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = "결과",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
