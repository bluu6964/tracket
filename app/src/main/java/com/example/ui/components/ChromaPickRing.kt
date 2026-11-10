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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.NeonCyan
import com.example.ui.util.bouncyClickable

/**
 * Average colour of a small square of the screen (2 * [half] + 1 pixels wide) around a window position, read from what
 * is on screen right now (video included). An average, not one pixel, because compressed video is noisy.
 */
fun pickAverageColorFromWindow(context: Context, xWindow: Int, yWindow: Int, half: Int, onResult: (Long?) -> Unit) {
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()
    if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        onResult(null)
        return
    }
    val n = half * 2 + 1
    val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val rect = Rect(xWindow - half, yWindow - half, xWindow + half + 1, yWindow + half + 1)
    runCatching {
        PixelCopy.request(activity.window, rect, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) {
                onResult(null)
            } else {
                var r = 0L
                var g = 0L
                var b = 0L
                for (y in 0 until n) for (x in 0 until n) {
                    val p = bitmap.getPixel(x, y)
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                }
                val c = (n * n).toLong()
                onResult(0xFF000000L or ((r / c) shl 16) or ((g / c) shl 8) or (b / c))
            }
        }, Handler(Looper.getMainLooper()))
    }.onFailure { onResult(null) }
}

/**
 * The Chroma Key eyedropper: a round ring on the canvas. Drag anywhere to move it (it follows the finger by the same
 * amount, like a mouse), or tap to put it there. The colour under the middle of the ring is read live and shown in the
 * swatch at the top; **Done** keeps it, **Cancel** abandons. The ring is hollow in the middle, so the pixels that are
 * read are the picture's, not the ring's.
 */
@Composable
fun ChromaPickRing(
    onConfirm: (Long) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var pos by remember { mutableStateOf<Offset?>(null) }
    var color by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val ringR = with(density) { 24.dp.toPx() }
    val line = with(density) { 3.dp.toPx() }
    val hair = with(density) { 1.5.dp.toPx() }
    val half = with(density) { 3.dp.toPx() }.toInt().coerceAtLeast(2)

    fun sample() {
        val p = pos ?: return
        if (busy) {
            dirty = true
            return
        }
        busy = true
        dirty = false
        pickAverageColorFromWindow(context, (origin.x + p.x).toInt(), (origin.y + p.y).toInt(), half) { c ->
            if (c != null) color = c
            busy = false
            if (dirty) sample()
        }
    }

    fun clamp(o: Offset) = Offset(o.x.coerceIn(0f, size.width.toFloat()), o.y.coerceIn(0f, size.height.toFloat()))

    LaunchedEffect(size) {
        if (pos == null && size.width > 0) {
            pos = Offset(size.width / 2f, size.height / 2f)
            sample()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInWindow()
                size = it.size
            }
            .pointerInput(Unit) {
                detectTapGestures { t ->
                    pos = clamp(t)
                    sample()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { sample() }) { change, drag ->
                    change.consume()
                    pos = clamp((pos ?: change.position) + drag)
                    sample()
                }
            }
            .testTag("chroma_pick_ring")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val p = pos ?: return@Canvas
            // dark outline + white ring: visible on light and dark pictures; nothing is drawn in the middle
            drawCircle(Color.Black.copy(alpha = 0.7f), radius = ringR + line, center = p, style = Stroke(width = hair))
            drawCircle(Color.White, radius = ringR, center = p, style = Stroke(width = line))
            drawCircle(Color.Black.copy(alpha = 0.7f), radius = ringR - line, center = p, style = Stroke(width = hair))
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color?.let { Color(it.toInt()) } ?: Color.DarkGray)
                    .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                    .testTag("chroma_pick_swatch")
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Cancel",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .bouncyClickable(onClick = onCancel)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("chroma_pick_cancel")
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Done",
                color = Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (color != null) NeonCyan else Color.Gray)
                    .bouncyClickable(onClick = { color?.let(onConfirm) })
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag("chroma_pick_done")
            )
        }
    }
}
