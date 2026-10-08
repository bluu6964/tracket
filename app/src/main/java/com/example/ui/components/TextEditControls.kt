package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.TextPrimary
import com.example.ui.util.bouncyClickable
import java.util.Locale

/** Colors used by the Text edit panel (taken from the reference screenshots). */
internal val SheetBg = Color(0xFF1C1D20)
internal val FieldBg = Color(0xFF2F3033)
internal val ChipBg = Color(0xFF3A3A3D)
internal val ChipSelectedBg = Color(0xFFE8E4E4)

private val Swatches = listOf(
    0xFF9C27B0, 0xFFE91E63, 0xFFF44336, 0xFFFFFFFF, 0xFF9E9E9E, 0xFF000000,
    0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50, 0xFF00E5FF, 0xFF2196F3, 0xFF3F51B5
)

/**
 * "Color" row: scrollable swatches, then the palette button (full picker) and the eyedropper
 * (pick a color from the canvas) pinned on the right.
 */
@Composable
fun TextColorRow(
    selected: Long,
    onSelect: (Long) -> Unit,
    onEyedropper: ((Long) -> Unit) -> Unit,
    label: String = "Color",
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    val isCustom = Swatches.none { it == selected }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.width(76.dp))
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(Swatches) { c ->
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(c))
                        .then(
                            if (c == selected) Modifier.border(2.5.dp, Color.White, RoundedCornerShape(11.dp))
                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(11.dp))
                        )
                        .clickable { onSelect(c) }
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (isCustom) Color(selected) else Color.Transparent)
                .border(2.dp, Color.White.copy(alpha = if (isCustom) 1f else 0.85f), RoundedCornerShape(11.dp))
                .clickable { showPicker = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Palette, "Custom color", tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(ChipBg)
                .clickable { onEyedropper(onSelect) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Colorize, "Pick from canvas", tint = TextPrimary, modifier = Modifier.size(22.dp))
        }
    }

    if (showPicker) {
        ColorPickerDialog(
            initial = Color(selected),
            onPick = { onSelect(it.toArgb().toLong() and 0xFFFFFFFFL) },
            onDismiss = { showPicker = false }
        )
    }
}

/** Full color picker: saturation/value square, hue bar, opacity bar and a hex readout. */
@Composable
fun ColorPickerDialog(
    initial: Color,
    onPick: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val hsv = remember(initial) {
        val a = FloatArray(3)
        android.graphics.Color.colorToHSV(initial.toArgb(), a)
        a
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var alpha by remember { mutableFloatStateOf(initial.alpha) }

    val current = Color(android.graphics.Color.HSVToColor((alpha * 255).toInt(), floatArrayOf(hue, sat, value)))
    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(SheetBg)
                .padding(18.dp)
        ) {
            // Saturation (x) / value (y)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            fun set(o: Offset) {
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                            }
                            detectTapGestures { set(it) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val p = Offset(sat * size.width, (1f - value) * size.height)
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = p,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    drawCircle(Color.Black.copy(alpha = 0.4f), radius = 11.dp.toPx(), center = p,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
            }
            Spacer(Modifier.height(14.dp))
            // Hue
            PickerBar(
                fraction = hue / 360f,
                brush = Brush.horizontalGradient(
                    (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }
                ),
                onFraction = { hue = (it * 360f).coerceIn(0f, 359.9f) }
            )
            Spacer(Modifier.height(12.dp))
            // Opacity
            PickerBar(
                fraction = alpha,
                brush = Brush.horizontalGradient(listOf(current.copy(alpha = 0f), current.copy(alpha = 1f))),
                onFraction = { alpha = it.coerceIn(0f, 1f) },
                checker = true
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(current)
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    String.format(Locale.US, "#%08X", current.toArgb()),
                    color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFFA1A1A6)) }
                TextButton(onClick = { onPick(current); onDismiss() }) {
                    Text("OK", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PickerBar(
    fraction: Float,
    brush: Brush,
    onFraction: (Float) -> Unit,
    checker: Boolean = false
) {
    val update by rememberUpdatedState(onFraction)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .pointerInput(Unit) { detectTapGestures { update((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    update((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        if (checker) {
            val cell = 8.dp.toPx()
            var yi = 0
            var y = 0f
            while (y < size.height) {
                var xi = 0
                var x = 0f
                while (x < size.width) {
                    drawRect(
                        if ((xi + yi) % 2 == 0) Color(0xFF8A8A8A) else Color(0xFFCFCFCF),
                        topLeft = Offset(x, y), size = Size(cell, cell)
                    )
                    x += cell; xi++
                }
                y += cell; yi++
            }
        }
        drawRect(brush)
        val cx = fraction * size.width
        drawCircle(Color.White, radius = size.height / 2f - 1.dp.toPx(), center = Offset(cx.coerceIn(size.height / 2f, size.width - size.height / 2f), size.height / 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
    }
}

/**
 * Ruler-style slider matching the reference ("Thickness |||||| 0%"): ticks across the bar and a
 * red needle at the current value. Drag or tap to change.
 */
@Composable
fun TextRulerSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float = 0f,
    max: Float = 100f,
    unit: String = "%",
    modifier: Modifier = Modifier
) {
    val update by rememberUpdatedState(onValueChange)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.width(112.dp))
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(min, max) {
                    detectTapGestures { update(min + (it.x / size.width).coerceIn(0f, 1f) * (max - min)) }
                }
                .pointerInput(min, max) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        update(min + (change.position.x / size.width).coerceIn(0f, 1f) * (max - min))
                    }
                }
        ) {
            val ticks = 20
            val pad = 6.dp.toPx()
            val usable = size.width - pad * 2
            for (i in 0..ticks) {
                val x = pad + usable * i / ticks
                val major = i % 5 == 0
                val h = if (major) size.height * 0.62f else size.height * 0.36f
                drawLine(
                    color = if (major) Color(0xFFE0E0E0) else Color(0xFF8A8A8E),
                    start = Offset(x, (size.height - h) / 2f),
                    end = Offset(x, (size.height + h) / 2f),
                    strokeWidth = (if (major) 2.5f else 1.8f).dp.toPx()
                )
            }
            val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
            val nx = pad + usable * frac
            drawLine(
                color = Color(0xFFFF2D6F),
                start = Offset(nx, size.height * 0.12f),
                end = Offset(nx, size.height * 0.88f),
                strokeWidth = 4.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
        Text(
            text = String.format(Locale.US, "%d%s", value.toInt(), unit),
            color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(64.dp)
        )
    }
}

/** Reads the color of one pixel from what is currently on screen (video included). */
fun pickColorFromWindow(context: Context, xWindow: Int, yWindow: Int, onResult: (Long?) -> Unit) {
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()
    if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        onResult(null)
        return
    }
    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val rect = Rect(xWindow, yWindow, xWindow + 1, yWindow + 1)
    runCatching {
        PixelCopy.request(activity.window, rect, bitmap, { result ->
            onResult(
                if (result == PixelCopy.SUCCESS) (bitmap.getPixel(0, 0).toLong() and 0xFFFFFFFFL or 0xFF000000L)
                else null
            )
        }, Handler(Looper.getMainLooper()))
    }.onFailure { onResult(null) }
}
