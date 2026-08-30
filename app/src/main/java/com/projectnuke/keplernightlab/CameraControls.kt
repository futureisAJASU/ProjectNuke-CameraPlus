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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val modes = listOf("더보기", "동영상", "사진", "야간", "인물 사진")
    // Samsung-style landscape grammar: the mode rail is a stable vertical
    // screen-space column beside the shutter. Only the label content rotates;
    // no Row/Column or hit target is rotated.
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
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
                    color = if (isPhoto) Color.White else Color.White.copy(alpha = 0.34f),
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
fun LandscapeUtilityRail(
    selectedResolution: CaptureResolutionMode,
    onResolutionClick: () -> Unit,
    onSettings: () -> Unit,
    meteringMode: MeteringMode = MeteringModeState.mode,
    onMeteringModeClick: () -> Unit = { MeteringModeState.cycle() },
    modifier: Modifier = Modifier
) {
    // Keep containers upright and minimal. This rail intentionally carries
    // secondary controls off the preview, matching the stock-camera layout
    // philosophy without copying Samsung assets.
    Column(
        modifier = modifier
            .background(Color.Black)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CircleMiniButton(label = "⚙", onClick = onSettings, testTag = "kepler.settings.open")
        RailTextButton(
            text = meteringMode.shortLabel,
            onClick = onMeteringModeClick,
            testTag = "kepler.camera.metering"
        )
        RailTextButton(
            text = when (selectedResolution) {
                CaptureResolutionMode.MP24_FUSION -> "24M"
                else -> selectedResolution.label
            },
            onClick = onResolutionClick,
            testTag = "kepler.camera.resolution"
        )
    }
}

@Composable
private fun RailTextButton(
    text: String,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .width(50.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            softWrap = false
        )
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
            .background(Color(0xFF242429))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("kepler.camera.switch"),
        contentAlignment = Alignment.Center
    ) {
        // Single refresh/reprocess arrow. The action is not a physical camera
        // switch; a one-arrow glyph is visually cleaner and avoids the broken
        // double-arrow look that showed up in landscape screenshots.
        androidx.compose.foundation.Canvas(modifier = Modifier.size(31.dp)) {
            val stroke = 2.6.dp.toPx()
            val inset = 4.3.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(
                width = size.width - inset * 2f,
                height = size.height - inset * 2f
            )
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(
                color = Color.White,
                startAngle = 35f,
                sweepAngle = 285f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )

            val radius = arcSize.width / 2f
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val head = androidx.compose.ui.geometry.Offset(
                x = center.x + radius * 0.58f,
                y = center.y - radius * 0.76f
            )
            val wing = 5.2.dp.toPx()
            drawLine(
                color = Color.White,
                start = head,
                end = head + androidx.compose.ui.geometry.Offset(-wing, -0.5.dp.toPx()),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = head,
                end = head + androidx.compose.ui.geometry.Offset(-0.6.dp.toPx(), wing),
                strokeWidth = stroke,
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
