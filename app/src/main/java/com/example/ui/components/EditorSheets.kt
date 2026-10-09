package com.example.ui.components
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.example.ui.util.enterPop
import com.example.ui.util.bouncyClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.AspectRatio
import com.example.model.LayerType
import com.example.model.PreviewResolution
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.EditorSurface
import com.example.ui.theme.EditorSurfaceVariant
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AspectRatioSheet(
    currentRatio: AspectRatio,
    onRatioSelected: (AspectRatio) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = EditorSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "Canvas Aspect Ratio",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            AspectRatio.values().forEach { ratio ->
                val isSelected = ratio == currentRatio
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) EditorSurfaceVariant else Color.Transparent)
                        .bouncyClickable {
                            onRatioSelected(ratio)
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .border(1.5.dp, if (isSelected) NeonCyan else TextSecondary, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = ratio.label,
                                color = if (isSelected) NeonCyan else TextSecondary,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = ratio.label,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = ratio.iconDescription,
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolutionSheet(
    currentResolution: PreviewResolution,
    onResolutionSelected: (PreviewResolution) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EditorSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "Preview Resolution",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            PreviewResolution.values().forEach { res ->
                val isSelected = res == currentResolution
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) EditorSurfaceVariant else Color.Transparent)
                        .bouncyClickable {
                            onResolutionSelected(res)
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = res.label,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = res.detail,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
fun ExportModalDialog(
    isExporting: Boolean,
    progress: Float,
    onStartExport: () -> Unit,
    onDismiss: () -> Unit
) {
    var isCompleted by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(isExporting, progress) {
        if (!isExporting && progress >= 1f) isCompleted = true
    }

    Dialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !isExporting, dismissOnClickOutside = !isExporting)
    ) {
        Box(
            modifier = Modifier
                .enterPop()
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(EditorSurface)
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isCompleted) "Export Complete!" else if (isExporting) "Exporting Video..." else "Export Project",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (!isExporting) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Default.DownloadDone,
                        contentDescription = "Done",
                        tint = NeonGreen,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Video rendered in 1080p (30fps) successfully!",
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = EditorSurfaceVariant)
                        ) {
                            Text("Done", color = TextPrimary)
                        }
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share", color = Color.White)
                        }
                    }
                } else if (isExporting) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = NeonCyan,
                        trackColor = EditorSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        color = NeonCyan,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Encoding audio & multi-layer timeline frames...",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                } else {
                    // Export Preset info
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(EditorSurfaceVariant, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Resolution", color = TextSecondary, fontSize = 13.sp)
                            Text("1080p FHD (1920x1080)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Frame Rate", color = TextSecondary, fontSize = 13.sp)
                            Text("30 FPS", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Est. File Size", color = TextSecondary, fontSize = 13.sp)
                            Text("28.4 MB", color = NeonCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            onStartExport()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                    ) {
                        Text("Export Now", color = EditorBackground, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAudioSheet(
    onAddAudio: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EditorSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Add Audio Track", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(14.dp))

            val tracks = listOf(
                "Cinematic Ambient Beat" to "3:15",
                "Upbeat Vlog Pop" to "2:40",
                "Chill Lo-Fi Sunset" to "1:55",
                "Electric Groove Synth" to "2:10"
            )

            tracks.forEach { (title, length) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .bouncyClickable {
                            onAddAudio(title)
                            onDismiss()
                        }
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(length, color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                    Text("Add", color = NeonCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTextSheet(
    onAddText: (text: String, color: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var textInput by remember { mutableStateOf("আমার নতুন ভিডিও") }
    var selectedColor by remember { mutableStateOf(0xFFFFFFFF) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EditorSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Add Text Layer", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                label = { Text("Title / Caption", color = TextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = EditorSurfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Color Preset", color = TextSecondary, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(0xFFFFFFFF, 0xFF00F0FF, 0xFFFFEB3B, 0xFFFF4081, 0xFF00E676).forEach { colorValue ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(colorValue))
                            .border(
                                width = if (selectedColor == colorValue) 3.dp else 1.dp,
                                color = if (selectedColor == colorValue) Color.White else Color.Transparent,
                                shape = CircleShape
                            )
                            .bouncyClickable(pressedScale = 0.85f) { selectedColor = colorValue }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    if (textInput.isNotBlank()) {
                        onAddText(textInput, selectedColor)
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text("Add to Timeline", color = EditorBackground, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddShapeSheet(
    onAddShape: (name: String, pattern: String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EditorSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .padding(bottom = 26.dp)
        ) {
            Text(
                text = "Add Shape",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Choose a shape to add at the current playhead",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 14.dp)
            )

            val shapes = listOf(
                Triple("Circle", "CIRCLE", Color(0xFF5AC8FA)),
                Triple("Rounded Rectangle", "ROUNDED_SQUARE", Color(0xFF7C8CFF)),
                Triple("Wide Rectangle", "WIDE_ROUNDED_RECT", Color(0xFF34C759)),
                Triple("Square", "SQUARE", Color(0xFFFF9F0A)),
                Triple("Oval", "OVAL", Color(0xFFFF6B8A)),
                Triple("Star", "STAR", Color(0xFFFFD60A)),
                Triple("Pentagon", "PENTAGON", Color(0xFFBF5AF2))
            )

            shapes.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { (name, pattern, accent) ->
                        ShapePickerCard(
                            name = name,
                            pattern = pattern,
                            accent = accent,
                            onClick = { onAddShape(name, pattern); onDismiss() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun ShapePickerCard(
    name: String,
    pattern: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(EditorSurfaceVariant.copy(alpha = 0.82f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .bouncyClickable(pressedScale = 0.96f, hoverScale = 1.01f, onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(42.dp)) {
                val c = accent
                when (pattern) {
                    "CIRCLE" -> drawCircle(c)
                    "SQUARE" -> drawRect(c)
                    "ROUNDED_SQUARE", "ROUNDED_RECT" -> drawRoundRect(c, cornerRadius = CornerRadius(10.dp.toPx()))
                    "WIDE_ROUNDED_RECT" -> drawRoundRect(c, topLeft = Offset.Zero, size = Size(size.width, size.height * 0.62f), cornerRadius = CornerRadius(8.dp.toPx()))
                    "OVAL" -> drawOval(c)
                    "STAR" -> drawRegularPolygon(c, 5, innerRatio = 0.45f)
                    "PENTAGON" -> drawRegularPolygon(c, 5, innerRatio = 1f)
                    else -> drawCircle(c)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(name, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text("Add", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

private fun DrawScope.drawRegularPolygon(color: Color, points: Int, innerRatio: Float) {
    val path = Path()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val outer = size.minDimension / 2f
    val total = if (innerRatio < 1f) points * 2 else points
    for (i in 0 until total) {
        val angle = Math.toRadians(i * 360.0 / total - 90.0)
        val radius = if (innerRatio < 1f && i % 2 == 1) outer * innerRatio else outer
        val x = cx + radius * kotlin.math.cos(angle).toFloat()
        val y = cy + radius * kotlin.math.sin(angle).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

@Composable
fun FullscreenPlayerModal(
    aspectRatio: AspectRatio,
    currentPlayheadMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .enterPop()
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // Fullscreen Canvas Area
            CanvasArea(
                height = 360.dp,
                aspectRatio = aspectRatio,
                currentPlayheadMs = currentPlayheadMs,
                isPlaying = isPlaying,
                tracks = emptyList(),
                onTogglePlayPause = onTogglePlay,
                modifier = Modifier.fillMaxWidth()
            )

            // Top Bar Overlay
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit Fullscreen", tint = Color.White)
                }
                Text(
                    text = "${formatTimeCode(currentPlayheadMs)} / ${formatTimeCode(totalDurationMs)}",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Bottom Play Overlay
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .size(56.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}
