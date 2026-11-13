package com.example.ui.components
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.filled.Palette
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import com.example.model.AspectRatio
import com.example.model.LayerType
import com.example.model.PreviewResolution
import com.example.model.TimelineTrack
import com.example.ui.theme.AmHairline
import com.example.ui.theme.AmPanel
import com.example.ui.theme.AmPanelHigh
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.EditorSurfaceVariant
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/** Export resolutions offered in the canvas sheet: label (parsed by the exporter) + short hint. */
val ExportResolutions = listOf(
    "180p" to "320 x 180",
    "360p" to "640 x 360",
    "480p SD" to "854 x 480",
    "720p HD" to "1280 x 720",
    "1080p FHD" to "1920 x 1080"
)

/** Export limits: short side in pixels (180p..1080p) and frame rate (24..60 fps). */
const val ExportMinResolution = 180
const val ExportMaxResolution = 1080
const val ExportMinFps = 24
const val ExportMaxFps = 60
val ExportFpsPresets = listOf(24, 25, 30, 50, 60)
val ExportQualities = listOf("Low", "Medium", "High (Recommended)", "Max")

/** "720p HD" / "540p" -> 720 / 540 (clamped to the allowed range). */
fun exportResolutionValue(label: String): Int =
    (Regex("\\d{3,4}").find(label)?.value?.toInt() ?: ExportMaxResolution)
        .coerceIn(ExportMinResolution, ExportMaxResolution)

/** Label for a short-side value: presets keep their name, anything else becomes e.g. "540p". */
fun exportResolutionLabel(value: Int): String {
    val v = value.coerceIn(ExportMinResolution, ExportMaxResolution)
    return ExportResolutions.firstOrNull { exportResolutionValue(it.first) == v }?.first ?: "${v}p"
}

private val CanvasSwatches = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFF1C1D20, 0xFF8A8A8A, 0xFFFF4444, 0xFFF5B342,
    0xFFFAE764, 0xFF1FD91F, 0xFF18E2E2, 0xFF3F4DF2, 0xFFD73FDA, 0xFF5BB5D9
)

/**
 * Opened from the aspect-ratio button in the header: canvas shape, canvas (background)
 * colour and export resolution. Changes are applied live; swipe down to close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AspectRatioSheet(
    currentRatio: AspectRatio,
    originalAspect: Float,
    onRatioSelected: (AspectRatio) -> Unit,
    canvasColor: Long,
    onCanvasColor: (Long) -> Unit,
    resolution: String,
    onResolution: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AmPanel
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
                .testTag("aspect_ratio_sheet")
        ) {
            SheetTitle("Aspect Ratio")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(AspectRatio.values()) { ratio ->
                    val selected = ratio.label == currentRatio.label
                    val shape = if (ratio.isOriginal && originalAspect > 0.05f) originalAspect else ratio.ratioFloat
                    Column(
                        modifier = Modifier
                            .width(100.dp)
                            .height(100.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Color(0xFFE8E4E4) else Color(0xFF3A3A3D))
                            .bouncyClickable(pressedScale = 0.94f) { onRatioSelected(ratio) }
                            .testTag("aspect_${ratio.label}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val tint = if (selected) Color(0xFF1C1D20) else Color(0xFFE5E5E7)
                        // A little outline with the real shape of the canvas.
                        Box(
                            modifier = Modifier.size(46.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val w = if (shape >= 1f) 44.dp else 44.dp * shape
                            val h = if (shape >= 1f) 44.dp / shape else 44.dp
                            Box(
                                Modifier
                                    .size(width = w, height = h)
                                    .border(2.5.dp, tint, RoundedCornerShape(3.dp))
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(ratio.label, color = tint, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SheetTitle("Canvas color")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    // Custom colour: shows the current colour when it is not one of the swatches.
                    val custom = CanvasSwatches.none { (it and 0xFFFFFFL) == (canvasColor and 0xFFFFFFL) }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (custom) Color(canvasColor) else Color(0xFF3A3A3D))
                            .border(
                                if (custom) 3.dp else 1.5.dp,
                                if (custom) Color.White else Color.White.copy(alpha = 0.7f),
                                RoundedCornerShape(12.dp)
                            )
                            .bouncyClickable(pressedScale = 0.9f) { showPicker = true }
                            .testTag("canvas_color_custom"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Palette, "Custom color", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
                items(CanvasSwatches) { c ->
                    val selected = (c and 0xFFFFFFL) == (canvasColor and 0xFFFFFFL)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(c))
                            .border(
                                if (selected) 3.dp else 1.dp,
                                if (selected) Color.White else Color.White.copy(alpha = 0.18f),
                                RoundedCornerShape(12.dp)
                            )
                            .bouncyClickable(pressedScale = 0.88f) { onCanvasColor(c) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SheetTitle("Resolution")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(ExportResolutions) { (label, detail) ->
                    val selected = label == resolution
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Color(0xFFE8E4E4) else Color(0xFF3A3A3D))
                            .bouncyClickable(pressedScale = 0.94f) { onResolution(label) }
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                            .testTag("resolution_$label"),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            label, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                            color = if (selected) Color(0xFF1C1D20) else Color(0xFFE5E5E7)
                        )
                        Text(
                            detail, fontSize = 12.sp,
                            color = if (selected) Color(0xFF55555A) else Color(0xFF9A9AA0)
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        ColorPickerDialog(
            initial = Color(canvasColor),
            onPick = { onCanvasColor(it.toArgb().toLong() and 0xFFFFFFFFL) },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp)
    )
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
        containerColor = AmPanel
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

/** Full-screen dark export screen (badge logo with "AM", big % and remaining time). */
@Composable
fun ExportModalDialog(
    isExporting: Boolean,
    isCompleted: Boolean,
    progress: Float,
    onStartExport: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    resolutionLabel: String = "1080p FHD",
    frameRate: Int = 30,
    onResolutionChange: (String) -> Unit = {},
    onFrameRateChange: (Int) -> Unit = {},
    quality: String = "High (Recommended)",
    onQualityChange: (String) -> Unit = {},
    bitrateKbps: Int = 0,
    onBitrateChange: (Int) -> Unit = {}
) {
    // Time the current export started (reset every time a new export begins).
    var startedAt by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(isExporting) {
        if (isExporting) startedAt = android.os.SystemClock.elapsedRealtime()
    }
    val remainingMs: Long? = if (isExporting && progress > 0.03f) {
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
        (elapsed * (1f - progress) / progress).toLong()
    } else null

    Dialog(
        onDismissRequest = { if (isExporting) onCancel() else onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isExporting,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!isExporting && !isCompleted) {
                    ExportSettingsPage(
                        resolutionLabel = resolutionLabel,
                        frameRate = frameRate,
                        quality = quality,
                        bitrateKbps = bitrateKbps,
                        onResolutionChange = onResolutionChange,
                        onFrameRateChange = onFrameRateChange,
                        onQualityChange = onQualityChange,
                        onBitrateChange = onBitrateChange,
                        onStartExport = onStartExport
                    )
                } else {
                Spacer(modifier = Modifier.weight(1f))
                // Buddy works while rendering and celebrates when it is done.
                com.example.ui.buddy.BuddyFace(
                    mood = if (isCompleted) com.example.ui.buddy.BuddyMood.CELEBRATE else com.example.ui.buddy.BuddyMood.WORKING,
                    modifier = Modifier.fillMaxWidth().height(340.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))

                when {
                    isCompleted -> {
                        Text("Export Complete!", color = Color.White, fontSize = 30.sp,
                            fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Saved to Movies/After Motion", color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EditorSurfaceVariant)
                            ) { Text("Done", color = Color.White, fontWeight = FontWeight.Bold) }
                            Button(
                                onClick = onShare,
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null,
                                    tint = EditorBackground, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share", color = EditorBackground, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    isExporting -> {
                        // Flat white progress ring (Stage 3, B/W language): a plain white
                        // arc on a faint white track, the % centred inside — no glow, no
                        // gradient, no coloured gauge. (The glowing donut-gauge concept
                        // from the scrapped neon redesign is dead; this is its flat
                        // monochrome replacement.)
                        Box(
                            modifier = Modifier.size(168.dp).testTag("export_progress_ring"),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val stroke = 10.dp.toPx()
                                val inset = stroke / 2f
                                val arcSize = Size(size.width - stroke, size.height - stroke)
                                drawArc(
                                    color = Color.White.copy(alpha = 0.15f),
                                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                                    topLeft = Offset(inset, inset), size = arcSize,
                                    style = Stroke(width = stroke)
                                )
                                drawArc(
                                    color = Color.White,
                                    startAngle = -90f,
                                    sweepAngle = progress.coerceIn(0f, 1f) * 360f,
                                    useCenter = false,
                                    topLeft = Offset(inset, inset), size = arcSize,
                                    style = Stroke(width = stroke)
                                )
                            }
                            Text(
                                "${(progress * 100).toInt()}%", color = Color.White, fontSize = 40.sp,
                                fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            "Remaining time approximately ${formatRemaining(remainingMs)}",
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Text(
                            "Please do not exit the application or lock the screen",
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                }
            }

            // Bottom tab with X: cancels a running export, otherwise closes the screen.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .width(96.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable { if (isExporting) onCancel() else onDismiss() }
                    .testTag("export_close"),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
    }
}

private fun formatRemaining(ms: Long?): String {
    val total = ((ms ?: 0L) / 1000L).coerceAtLeast(0L)
    return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}

private val ExportAccent = Color(0xFFFFFFFF)

/** Settings page: scrolling cards on top, "Export Now" pinned at the bottom (always visible). */
@Composable
private fun ColumnScope.ExportSettingsPage(
    resolutionLabel: String,
    frameRate: Int,
    quality: String,
    bitrateKbps: Int,
    onResolutionChange: (String) -> Unit,
    onFrameRateChange: (Int) -> Unit,
    onQualityChange: (String) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onStartExport: () -> Unit
) {
    val resValue = exportResolutionValue(resolutionLabel)
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        ExportBadge(modifier = Modifier.fillMaxWidth().height(120.dp))
        Text(
            "Export settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp)
        )

        ExportCard("Resolution", "${resValue}p", "180p – 1080p") {
            ExportSlider(
                value = resValue.toFloat(),
                range = ExportMinResolution.toFloat()..ExportMaxResolution.toFloat(),
                tag = "export_resolution_slider"
            ) { onResolutionChange(exportResolutionLabel(it.toInt())) }
            ExportChipRow {
                ExportResolutions.forEach { (label, _) ->
                    ExportChip(label.substringBefore(' '), exportResolutionValue(label) == resValue) {
                        onResolutionChange(label)
                    }
                }
            }
        }

        ExportCard("Frame rate", "$frameRate fps", "24 – 60 fps") {
            ExportSlider(
                value = frameRate.toFloat(),
                range = ExportMinFps.toFloat()..ExportMaxFps.toFloat(),
                tag = "export_fps_slider"
            ) { onFrameRateChange(it.toInt().coerceIn(ExportMinFps, ExportMaxFps)) }
            ExportChipRow {
                ExportFpsPresets.forEach { f ->
                    ExportChip("$f", f == frameRate) { onFrameRateChange(f) }
                }
            }
        }

        val custom = bitrateKbps > 0
        ExportCard(
            "Quality",
            if (custom) "%.1f Mbps".format(bitrateKbps / 1000f) else quality.substringBefore(' '),
            if (custom) "Custom bitrate" else "Automatic bitrate"
        ) {
            ExportChipRow {
                ExportQualities.forEach { q ->
                    ExportChip(q.substringBefore(' '), !custom && q == quality) {
                        onBitrateChange(0); onQualityChange(q)
                    }
                }
                ExportChip("Custom", custom) { if (!custom) onBitrateChange(8000) }
            }
            if (custom) {
                ExportSlider(
                    value = bitrateKbps.toFloat(),
                    range = 500f..50_000f,
                    tag = "export_bitrate_slider"
                ) { onBitrateChange(((it / 100f).toInt() * 100).coerceIn(500, 50_000)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0.5 Mbps", color = TextTertiary, fontSize = 11.sp)
                    Text("50 Mbps", color = TextTertiary, fontSize = 11.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }

    Spacer(modifier = Modifier.height(12.dp))
    Button(
        onClick = onStartExport,
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("export_now"),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
    ) {
        Text("Export Now", color = EditorBackground, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
    // Room for the round "close" tab at the bottom of the screen.
    Spacer(modifier = Modifier.height(68.dp))
}

@Composable
private fun ExportCard(title: String, value: String, hint: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AmPanel)
            .border(1.dp, AmHairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(hint, color = TextTertiary, fontSize = 11.sp)
            }
            Text(value, color = ExportAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun ExportSlider(value: Float, range: ClosedFloatingPointRange<Float>, tag: String, onChange: (Float) -> Unit) {
    androidx.compose.material3.Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        valueRange = range,
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = ExportAccent,
            activeTrackColor = ExportAccent,
            inactiveTrackColor = Color.White.copy(alpha = 0.15f)
        ),
        modifier = Modifier.fillMaxWidth().testTag(tag)
    )
}

@Composable
private fun ExportChipRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun ExportChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) Color(0xFF0A0A0A) else Color(0xFFE5E5E7),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) ExportAccent else Color.White.copy(alpha = 0.10f))
            .bouncyClickable(pressedScale = 0.94f) { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}


/**
 * Logo badge: white wavy seal with a ring of text and "AM" in the middle, plus a big faint
 * ring of text around it. Both rings turn slowly (opposite directions).
 */
@Composable
private fun ExportBadge(modifier: Modifier = Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "badge")
    val outerRot by t.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(40000, easing = androidx.compose.animation.core.LinearEasing)
        ), label = "outer"
    )
    val innerRot by t.animateFloat(
        0f, -360f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(20000, easing = androidx.compose.animation.core.LinearEasing)
        ), label = "inner"
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val badgeR = minOf(size.width, size.height) * 0.30f

            // Big faint outer ring text
            val outerR = minOf(size.width * 0.46f, size.height * 0.5f)
            drawRingText(
                text = "AFTER MOTION • PROFESSIONAL VIDEO EDITOR • ",
                cx = cx, cy = cy, radius = outerR, textSizePx = outerR * 0.26f,
                color = Color.White.copy(alpha = 0.13f), bold = true, rotationDeg = outerRot
            )

            // Wavy seal
            val path = Path()
            val lobes = 12
            val steps = 360
            for (i in 0..steps) {
                val a = (i / steps.toFloat()) * 2f * Math.PI.toFloat()
                val r = badgeR * (1f + 0.075f * kotlin.math.cos(lobes * a))
                val x = cx + r * kotlin.math.cos(a)
                val y = cy + r * kotlin.math.sin(a)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color = NeonCyan)

            // Small ring text inside the seal
            drawRingText(
                text = "PROFESSIONAL VIDEO EDITOR • AFTER MOTION • ",
                cx = cx, cy = cy, radius = badgeR * 0.80f, textSizePx = badgeR * 0.115f,
                color = Color.White, bold = true, rotationDeg = innerRot
            )

            // Logo blob (white with black outline) — "AM" is written on it
            val blobR = badgeR * 0.40f
            drawCircle(Color.White, radius = blobR, center = Offset(cx, cy))
            drawCircle(Color.Black, radius = blobR, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = badgeR * 0.03f))
        }
        Text(
            text = "AM", color = Color.Black, fontSize = 26.sp,
            fontWeight = FontWeight.Black, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        )
    }
}

/** Draws [text] repeated around a circle so it fills the whole circumference exactly. */
private fun DrawScope.drawRingText(
    text: String, cx: Float, cy: Float, radius: Float, textSizePx: Float,
    color: Color, bold: Boolean, rotationDeg: Float
) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        this.textSize = textSizePx
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        textAlign = android.graphics.Paint.Align.LEFT
    }
    val circumference = (2.0 * Math.PI * radius).toFloat()
    val unit = paint.measureText(text)
    val repeats = (circumference / unit).toInt().coerceAtLeast(1)
    val full = text.repeat(repeats)
    val natural = paint.measureText(full)
    paint.letterSpacing = ((circumference - natural) / full.length / textSizePx)
    val path = android.graphics.Path().apply {
        addCircle(cx, cy, radius, android.graphics.Path.Direction.CW)
    }
    drawIntoCanvas { c ->
        c.nativeCanvas.save()
        c.nativeCanvas.rotate(rotationDeg, cx, cy)
        c.nativeCanvas.drawTextOnPath(full, path, 0f, 0f, paint)
        c.nativeCanvas.restore()
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
        containerColor = AmPanel
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
        containerColor = AmPanel
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

private data class ShapeOption(val name: String, val pattern: String)

private val ShapeOptions = listOf(
    ShapeOption("Circle", "CIRCLE"),
    ShapeOption("Oval", "OVAL"),
    ShapeOption("Square", "SQUARE"),
    ShapeOption("Rounded", "ROUNDED_SQUARE"),
    ShapeOption("Wide Rect", "WIDE_ROUNDED_RECT"),
    ShapeOption("Pentagon", "PENTAGON"),
    ShapeOption("Star", "STAR")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddShapeSheet(
    onAddShape: (name: String, pattern: String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AmPanel,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Shapes",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .bouncyClickable(pressedScale = 0.88f) { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.07f))
            )
            Spacer(Modifier.height(14.dp))

            ShapeOptions.chunked(4).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { opt ->
                        ShapePickerCard(
                            name = opt.name,
                            pattern = opt.pattern,
                            onClick = { onAddShape(opt.name, opt.pattern); onDismiss() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(12.dp))
            }

            Text(
                text = "Tap a shape to add it at the playhead",
                color = TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun ShapePickerCard(
    name: String,
    pattern: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tile = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier.bouncyClickable(pressedScale = 0.94f, hoverScale = 1.0f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(tile)
                .background(AmPanelHigh)
                .border(1.dp, Color.White.copy(alpha = 0.08f), tile),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize(0.52f)) {
                val c = Color(0xFFEDEDED)
                when (pattern) {
                    "CIRCLE" -> drawCircle(c)
                    "SQUARE" -> drawRect(c)
                    "ROUNDED_SQUARE", "ROUNDED_RECT" ->
                        drawRoundRect(c, cornerRadius = CornerRadius(size.minDimension * 0.24f))
                    "WIDE_ROUNDED_RECT" -> {
                        val h = size.height * 0.62f
                        drawRoundRect(
                            c,
                            topLeft = Offset(0f, (size.height - h) / 2f),
                            size = Size(size.width, h),
                            cornerRadius = CornerRadius(h * 0.22f)
                        )
                    }
                    "OVAL" -> {
                        val h = size.height * 0.68f
                        drawOval(c, topLeft = Offset(0f, (size.height - h) / 2f), size = Size(size.width, h))
                    }
                    "STAR" -> drawRegularPolygon(c, 5, innerRatio = 0.45f)
                    "PENTAGON" -> drawRegularPolygon(c, 5, innerRatio = 1f)
                    else -> drawCircle(c)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
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
    /** The level being edited — the same tracks the inline canvas receives (its touch / overlay layer). */
    tracks: List<TimelineTrack>,
    /** Every layer of the project — the C++ picture and the empty-project check, as on the inline canvas. */
    nativeTracks: List<TimelineTrack>,
    /** The C++ preview engine, shared with the inline canvas (it draws on the newest surface, back on dismiss). */
    nativePreview: com.example.engine.NativePreviewEngine?,
    /** Live playhead reader (the screen's playheadProvider): the canvas follows playback, not the opening frame. */
    playheadMs: () -> Long,
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
            // Fullscreen Canvas Area: the SAME project state as the inline canvas (this call used to pass
            // tracks = emptyList() and no engine, so the fullscreen preview always rendered an empty
            // project: the "No layers yet" hint over black, in a fixed 360 dp band). No height is
            // passed, so the canvas fills the dialog and the project frame is fitted to the full screen.
            CanvasArea(
                aspectRatio = aspectRatio,
                playheadMs = playheadMs,
                isPlaying = isPlaying,
                tracks = tracks,
                nativeTracks = nativeTracks,
                nativePreview = nativePreview,
                onTogglePlayPause = onTogglePlay,
                modifier = Modifier.fillMaxSize()
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
