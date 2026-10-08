package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.ui.text.FontCatalog
import com.example.ui.text.FontEntry
import com.example.ui.text.FontRepository
import com.example.ui.text.rememberFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

enum class TextTab(val title: String) {
    FONTS("Fonts"),
    STYLE("Style"),
    STROKE("Stroke"),
    SHADOW("Shadow"),
    BACKGROUND("Background"),
    SPACING("Spacing")
}

/**
 * Bottom panel of "Text edit": text box, tab chips (Fonts / Style / Stroke / Shadow / Background /
 * Spacing) and the controls of the active tab. Every change is applied to the layer at once, so the
 * canvas above shows it live.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextEditSheet(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit,
    onEyedropper: ((Long) -> Unit) -> Unit,
    fullHeight: Dp,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(TextTab.FONTS) }
    var text by remember(clip.id) { mutableStateOf(clip.text.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible

    Column(
        modifier = modifier
            // Rises above the keyboard; while it is open the panel shrinks to the text box + chips.
            .imePadding()
            .then(if (imeVisible) Modifier else Modifier.height(fullHeight))
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(SheetBg)
            // Swallow touches so nothing under the panel (timeline) can be hit.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 16.dp)
    ) {
        // Drag-handle look from the reference
        Box(
            modifier = Modifier
                .padding(top = 12.dp, bottom = 14.dp)
                .align(Alignment.CenterHorizontally)
                .width(50.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF4A4A4E))
        )

        TextField(
            value = text,
            onValueChange = { v ->
                text = v
                onUpdate { it.copy(text = v, name = if (v.isBlank()) "Text" else "Text: ${v.take(24)}") }
            },
            placeholder = { Text("Write Text", color = Color(0xFF8A8A8E), fontSize = 20.sp) },
            leadingIcon = {
                Icon(Icons.Default.TextFields, null, tint = TextPrimary, modifier = Modifier.size(30.dp))
            },
            maxLines = 3,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = FieldBg,
                unfocusedContainerColor = FieldBg,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .then(
                    if (focused) Modifier.border(2.dp, Color(0xFFBDBDBD), RoundedCornerShape(14.dp))
                    else Modifier
                )
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextTab.entries.forEach { tab ->
                SheetChip(
                    label = tab.title,
                    selected = tab == activeTab && !imeVisible,
                    onClick = { activeTab = tab }
                )
            }
        }

        // While the keyboard is open only the text box and the chips are shown.
        if (!imeVisible) {
            Spacer(Modifier.height(14.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (activeTab) {
                    TextTab.FONTS -> FontsTab(clip, onUpdate)
                    TextTab.STYLE -> TabColumn { StyleTab(clip, onUpdate, onEyedropper) }
                    TextTab.STROKE -> TabColumn { StrokeTab(clip, onUpdate, onEyedropper) }
                    TextTab.SHADOW -> TabColumn { ShadowTab(clip, onUpdate, onEyedropper) }
                    TextTab.BACKGROUND -> TabColumn { BackgroundTab(clip, onUpdate, onEyedropper) }
                    TextTab.SPACING -> TabColumn { SpacingTab(clip, onUpdate) }
                }
            }
        }
    }
}

@Composable
private fun TabColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) { content() }
}

@Composable
private fun SheetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) ChipSelectedBg else ChipBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        if (content != null) content() else {
            Text(
                label,
                color = if (selected) Color(0xFF1C1D20) else Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Fonts
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FontsTab(clip: TimelineClip, onUpdate: ((TimelineClip) -> TimelineClip) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var userFonts by remember { mutableStateOf(FontRepository.userFonts(context)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val name = FontRepository.importUserFont(context, uri)
            if (name != null) {
                userFonts = FontRepository.userFonts(context)
                onUpdate { it.copy(fontFamily = name) }
            }
        }
    }

    val groups = remember(query, userFonts) {
        val all = userFonts.map { FontEntry(it, FontCatalog.USER_CATEGORY) } + FontCatalog.all
        all.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .groupBy { it.category }
            .toList()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search Fonts", color = Color(0xFF8A8A8E), fontSize = 19.sp) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFFB0B0B4), modifier = Modifier.size(28.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = FieldBg,
                unfocusedContainerColor = FieldBg,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SheetChip(label = "System", selected = true, onClick = {})
            Box {
                SheetChip(
                    label = "Add font",
                    selected = false,
                    onClick = {
                        picker.launch(
                            arrayOf(
                                "font/ttf", "font/otf", "font/sfnt",
                                "application/x-font-ttf", "application/x-font-otf",
                                "application/octet-stream"
                            )
                        )
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add font", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 4.dp)
                        .size(11.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF1EA7FF))
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(groups, key = { it.first }) { (category, fonts) ->
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.height(46.dp).padding(end = 6.dp), contentAlignment = Alignment.CenterStart) {
                        Text(category, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    fonts.forEach { f ->
                        FontChip(
                            name = f.name,
                            selected = clip.fontFamily == f.name,
                            onClick = { onUpdate { it.copy(fontFamily = f.name) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FontChip(name: String, selected: Boolean, onClick: () -> Unit) {
    val family = rememberFontFamily(name)
    Box(
        modifier = Modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ChipBg)
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(name, color = Color.White, fontSize = 20.sp, fontFamily = family, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Style
// ---------------------------------------------------------------------------------------------

private val PreviewClip = TimelineClip(
    id = "preview", name = "", type = LayerType.TEXT, startMs = 0, durationMs = 1, color = 0,
    text = "Aa", fontWeight = 700, bgOpacity = 0f
)

@Composable
private fun StyleTab(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit,
    onEyedropper: ((Long) -> Unit) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(TextPresets) { preset ->
            val isCurrent = clip.textColor == preset.fill &&
                clip.textStrokeThickness == preset.strokeThickness &&
                clip.shadowOpacity == preset.shadowOpacity &&
                (preset.shadowOpacity == 0f || clip.shadowBlur == preset.shadowBlur)
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ChipBg)
                    .then(if (isCurrent) Modifier.border(2.dp, Color.White, RoundedCornerShape(14.dp)) else Modifier)
                    .clickable { onUpdate { preset.applyTo(it) } },
                contentAlignment = Alignment.Center
            ) {
                TextLayerContent(clip = preset.applyTo(PreviewClip), frameWidth = 290.dp)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    TextColorRow(
        selected = clip.textColor,
        onSelect = { c -> onUpdate { it.copy(textColor = c) } },
        onEyedropper = onEyedropper
    )

    Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Font weight", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.width(112.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(listOf(200, 400, 500, 600, 700, 800, 900)) { w ->
                Box(
                    modifier = Modifier
                        .width(66.dp)
                        .height(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (clip.fontWeight == w) ChipSelectedBg else ChipBg)
                        .clickable { onUpdate { it.copy(fontWeight = w) } },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$w",
                        color = if (clip.fontWeight == w) Color(0xFF1C1D20) else Color.White,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Style", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.width(76.dp))
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StyleToggle("I", clip.isItalic, Modifier.weight(1f), fontStyle = FontStyle.Italic) {
                onUpdate { it.copy(isItalic = !it.isItalic) }
            }
            StyleToggle("U", clip.isUnderline, Modifier.weight(1f), decoration = TextDecoration.Underline) {
                onUpdate { it.copy(isUnderline = !it.isUnderline) }
            }
            StyleToggle("S", clip.isStrikethrough, Modifier.weight(1f), decoration = TextDecoration.LineThrough) {
                onUpdate { it.copy(isStrikethrough = !it.isStrikethrough) }
            }
        }
    }
}

@Composable
private fun StyleToggle(
    glyph: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    fontStyle: FontStyle = FontStyle.Normal,
    decoration: TextDecoration = TextDecoration.None,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) ChipSelectedBg else ChipBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            glyph,
            color = if (on) Color(0xFF1C1D20) else Color.White,
            fontSize = 20.sp, fontWeight = FontWeight.Bold,
            fontStyle = fontStyle, textDecoration = decoration
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Stroke / Shadow / Background / Spacing
// ---------------------------------------------------------------------------------------------

@Composable
private fun StrokeTab(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit,
    onEyedropper: ((Long) -> Unit) -> Unit
) {
    TextRulerSlider("Thickness", clip.textStrokeThickness, { v -> onUpdate { it.copy(textStrokeThickness = v) } })
    TextColorRow(clip.textStrokeColor, { c -> onUpdate { it.copy(textStrokeColor = c) } }, onEyedropper)
}

@Composable
private fun ShadowTab(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit,
    onEyedropper: ((Long) -> Unit) -> Unit
) {
    TextRulerSlider("Opacity", clip.shadowOpacity, { v -> onUpdate { it.copy(shadowOpacity = v) } })
    TextRulerSlider("Blur", clip.shadowBlur, { v -> onUpdate { it.copy(shadowBlur = v) } })
    TextRulerSlider("Distance", clip.shadowDistance, { v -> onUpdate { it.copy(shadowDistance = v) } })
    TextRulerSlider("Angle", clip.shadowAngle, { v -> onUpdate { it.copy(shadowAngle = v) } }, max = 360f, unit = "°")
    TextColorRow(clip.shadowColor, { c -> onUpdate { it.copy(shadowColor = c) } }, onEyedropper)
}

@Composable
private fun BackgroundTab(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit,
    onEyedropper: ((Long) -> Unit) -> Unit
) {
    TextRulerSlider("Opacity", clip.bgOpacity, { v -> onUpdate { it.copy(bgOpacity = v) } })
    TextRulerSlider("Corner", clip.bgCorner, { v -> onUpdate { it.copy(bgCorner = v) } })
    TextRulerSlider("Padding", clip.bgPadding, { v -> onUpdate { it.copy(bgPadding = v) } })
    TextColorRow(clip.bgColor, { c ->
        // Picking a color for a hidden background turns it on so the change is visible.
        onUpdate { it.copy(bgColor = c, bgOpacity = if (it.bgOpacity == 0f) 100f else it.bgOpacity) }
    }, onEyedropper)
}

@Composable
private fun SpacingTab(clip: TimelineClip, onUpdate: ((TimelineClip) -> TimelineClip) -> Unit) {
    TextRulerSlider("Letter space", clip.letterSpacing, { v -> onUpdate { it.copy(letterSpacing = v) } }, min = -10f, max = 40f)
    TextRulerSlider("Line height", clip.lineHeightPercent, { v -> onUpdate { it.copy(lineHeightPercent = v) } }, min = 80f, max = 200f)
    Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Align", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.width(112.dp))
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AlignButton(Icons.Default.FormatAlignLeft, clip.textAlign == 0, Modifier.weight(1f)) {
                onUpdate { it.copy(textAlign = 0) }
            }
            AlignButton(Icons.Default.FormatAlignCenter, clip.textAlign == 1, Modifier.weight(1f)) {
                onUpdate { it.copy(textAlign = 1) }
            }
            AlignButton(Icons.Default.FormatAlignRight, clip.textAlign == 2, Modifier.weight(1f)) {
                onUpdate { it.copy(textAlign = 2) }
            }
        }
    }
}

@Composable
private fun AlignButton(icon: ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) ChipSelectedBg else ChipBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (on) Color(0xFF1C1D20) else Color.White, modifier = Modifier.size(26.dp))
    }
}
