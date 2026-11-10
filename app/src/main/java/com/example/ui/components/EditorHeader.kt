package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AspectRatio
import com.example.ui.theme.HeaderBarBorder
import com.example.ui.theme.HeaderBarFill
import com.example.ui.theme.HeaderButtonFill
import com.example.ui.theme.HeaderCyan
import com.example.ui.theme.HeaderPillFill
import com.example.ui.theme.HeaderText
import com.example.ui.theme.TimelineBlack
import com.example.ui.util.bouncyClickable

private val Ink = Color(0xFF05080A)

/**
 * Header drawn like the design reference: one rounded bar holding
 *   [X]   [crop-scissor icon]                 [1080P v]  [download | Export]
 * [aspectRatio] is kept for the callers; the ratio itself is chosen in the sheet the scissor icon and
 * the resolution pill open.
 */
@Composable
fun EditorHeader(
    height: Dp,
    aspectRatio: AspectRatio,
    onBackClick: () -> Unit,
    onAspectRatioClick: () -> Unit,
    /** The icon next to the close button: saves the current frame as a PNG. */
    onSaveFrameClick: () -> Unit,
    onExportClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Text of the resolution pill, e.g. "1080P". */
    resolutionLabel: String = "1080P",
    onResolutionClick: () -> Unit = onAspectRatioClick
) {
    val barH = (height - 4.dp).coerceAtMost(46.dp)
    val btnH = barH - 10.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(TimelineBlack)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barH)
                .background(HeaderBarFill, CircleShape)
                .border(1.dp, HeaderBarBorder, CircleShape)
        ) {
            // ── Close ──────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .width(btnH * 1.27f)
                    .height(btnH)
                    .clip(RoundedCornerShape(btnH * 0.45f))
                    .background(HeaderButtonFill)
                    .bouncyClickable(pressedScale = 0.92f, onClick = onBackClick)
                    .testTag("header_back_button"),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(15.dp)) {
                    val c = HeaderText
                    val w = 2.7.dp.toPx()
                    val m = w / 2f
                    drawLine(c, Offset(m, m), Offset(size.width - m, size.height - m), w, StrokeCap.Round)
                    drawLine(c, Offset(size.width - m, m), Offset(m, size.height - m), w, StrokeCap.Round)
                }
            }

            // ── Save frame icon (centre sits 104 dp from the bar's left edge): current frame -> PNG in the gallery ──
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = 104.dp - 20.dp)
                    .size(40.dp)
                    .bouncyClickable(onClick = onSaveFrameClick)
                    .testTag("header_save_frame_button"),
                contentAlignment = Alignment.Center
            ) { SaveFrameGlyph() }

            // ── Resolution pill + Export ───────────────────────────
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .height(btnH - 4.dp)
                        .clip(CircleShape)
                        .background(HeaderPillFill)
                        .bouncyClickable(pressedScale = 0.95f, onClick = onResolutionClick)
                        .padding(horizontal = 14.dp)
                        .testTag("header_resolution_pill"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(resolutionLabel, color = HeaderText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Canvas(Modifier.size(width = 8.dp, height = 5.5.dp)) {
                        val p = Path().apply {
                            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
                        }
                        drawPath(p, HeaderText)
                        drawPath(p, HeaderText, style = Stroke(1.2.dp.toPx(), join = StrokeJoin.Round))
                    }
                }
                Row(
                    modifier = Modifier
                        .height(btnH)
                        .clip(CircleShape)
                        .background(HeaderCyan)
                        .bouncyClickable(pressedScale = 0.94f, onClick = onExportClick)
                        .padding(horizontal = 11.dp)
                        .testTag("header_export_button"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DownloadGlyph()
                    Spacer(Modifier.width(9.dp))
                    Box(Modifier.width(1.dp).height(13.dp).background(Ink.copy(alpha = 0.85f)))
                    Spacer(Modifier.width(9.dp))
                    Text("Export", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

/** Camera (body, lens, top bump) - cyan, 28 dp: saves the current frame as a picture. */
@Composable
private fun SaveFrameGlyph() {
    Canvas(Modifier.size(28.dp)) {
        val u = size.width / 28f
        val c = HeaderCyan
        val st = Stroke(2.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val body = Path().apply {
            moveTo(3.5f * u, 9.5f * u)
            lineTo(8.2f * u, 9.5f * u); lineTo(10.2f * u, 6.2f * u); lineTo(17.8f * u, 6.2f * u); lineTo(19.8f * u, 9.5f * u)
            lineTo(24.5f * u, 9.5f * u)
            quadraticBezierTo(26f * u, 9.5f * u, 26f * u, 11f * u)
            lineTo(26f * u, 21.5f * u)
            quadraticBezierTo(26f * u, 23f * u, 24.5f * u, 23f * u)
            lineTo(3.5f * u, 23f * u)
            quadraticBezierTo(2f * u, 23f * u, 2f * u, 21.5f * u)
            lineTo(2f * u, 11f * u)
            quadraticBezierTo(2f * u, 9.5f * u, 3.5f * u, 9.5f * u)
            close()
        }
        drawPath(body, c, style = st)
        drawCircle(c, 4.6f * u, Offset(14f * u, 16.3f * u), style = st)
    }
}

/** Open-bottom rounded frame with crossed scissors (two rings) - cyan, 26 x 25 dp. */
@Composable
private fun ScissorFrameGlyph() {
    Canvas(Modifier.size(28.dp)) {
        val u = size.width / 28f
        val c = HeaderCyan
        val st = Stroke(2.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val l = 1.5f * u; val r = 26.5f * u; val t = 1.8f * u
        // frame: left side down, top with rounded corners, right side down
        val frame = Path().apply {
            moveTo(l, 11f * u); lineTo(l, 6.5f * u)
            quadraticBezierTo(l, t, 6f * u, t)
            lineTo(r - 4.5f * u, t)
            quadraticBezierTo(r, t, r, 6.5f * u)
            lineTo(r, 11f * u)
        }
        drawPath(frame, c, style = st)
        // blades crossing
        drawLine(c, Offset(8.6f * u, 7.5f * u), Offset(19.4f * u, 18f * u), 2.5f * u, StrokeCap.Round)
        drawLine(c, Offset(19.4f * u, 7.5f * u), Offset(8.6f * u, 18f * u), 2.5f * u, StrokeCap.Round)
        // rings
        drawCircle(c, 3.1f * u, Offset(6.3f * u, 22.2f * u), style = st)
        drawCircle(c, 3.1f * u, Offset(21.7f * u, 22.2f * u), style = st)
    }
}

/** Arrow down into a tray (dark on the cyan button), 19 dp wide. */
@Composable
private fun DownloadGlyph() {
    Canvas(Modifier.size(20.dp)) {
        val u = size.width / 20f
        val st = Stroke(2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val arrow = Path().apply {
            moveTo(10f * u, 1.6f * u); lineTo(10f * u, 11.8f * u)
            moveTo(5.6f * u, 7.6f * u); lineTo(10f * u, 12f * u); lineTo(14.4f * u, 7.6f * u)
        }
        drawPath(arrow, Ink, style = st)
        val tray = Path().apply {
            moveTo(1.6f * u, 12.5f * u); lineTo(1.6f * u, 16.2f * u)
            quadraticBezierTo(1.6f * u, 18.4f * u, 3.8f * u, 18.4f * u)
            lineTo(16.2f * u, 18.4f * u)
            quadraticBezierTo(18.4f * u, 18.4f * u, 18.4f * u, 16.2f * u)
            lineTo(18.4f * u, 12.5f * u)
        }
        drawPath(tray, Ink, style = st)
    }
}

/**
 * Header shown while a layer is selected (it replaces [EditorHeader]): back arrow (deselect), the layer's colour dot, its
 * NAME (tap = rename) and a line about the layer (type, time range, length, hidden / parent). Export etc. come back when
 * the layer is deselected.
 */
@Composable
fun LayerHeader(
    height: Dp,
    name: String,
    info: String,
    colorArgb: Long,
    onBack: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
    /** Eye button: show / hide the layer. */
    visible: Boolean = true,
    onToggleVisible: (() -> Unit)? = null
) {
    val barH = (height - 4.dp).coerceAtMost(46.dp)
    val btnH = barH - 10.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(TimelineBlack)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barH)
                .background(HeaderBarFill, CircleShape)
                .border(1.dp, HeaderBarBorder, CircleShape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back: leave the layer (same as the back arrow of the tools panel).
            Box(
                modifier = Modifier
                    .width(btnH * 1.27f)
                    .height(btnH)
                    .clip(RoundedCornerShape(btnH * 0.45f))
                    .background(HeaderButtonFill)
                    .bouncyClickable(pressedScale = 0.92f, onClick = onBack)
                    .testTag("layer_header_back"),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(15.dp)) {
                    val w = 2.7.dp.toPx()
                    val m = w / 2f
                    drawLine(HeaderText, Offset(size.width - m, m), Offset(m, size.height / 2f), w, StrokeCap.Round)
                    drawLine(HeaderText, Offset(m, size.height / 2f), Offset(size.width - m, size.height - m), w, StrokeCap.Round)
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(10.dp).background(Color(colorArgb.toInt()), CircleShape))
            Spacer(Modifier.width(8.dp))
            // Name + info: tapping them renames the layer.
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .bouncyClickable(pressedScale = 0.97f, onClick = onRename)
                    .padding(horizontal = 4.dp)
                    .testTag("layer_header_name"),
                verticalArrangement = Arrangement.Center
            ) {
                Text(name, color = HeaderText, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(info, color = HeaderText.copy(alpha = 0.6f), fontSize = 10.5.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (onToggleVisible != null) {
                Box(
                    modifier = Modifier
                        .size(btnH)
                        .clip(CircleShape)
                        .background(HeaderPillFill)
                        .bouncyClickable(pressedScale = 0.92f, onClick = onToggleVisible)
                        .testTag("layer_header_eye"),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(18.dp)) {
                        val u = size.width / 18f
                        val c = if (visible) HeaderCyan else HeaderText.copy(alpha = 0.5f)
                        val st = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val eye = Path().apply {
                            moveTo(1.5f * u, 9f * u)
                            quadraticBezierTo(9f * u, 1.5f * u, 16.5f * u, 9f * u)
                            quadraticBezierTo(9f * u, 16.5f * u, 1.5f * u, 9f * u)
                            close()
                        }
                        drawPath(eye, c, style = st)
                        drawCircle(c, 2.6f * u, Offset(9f * u, 9f * u), style = st)
                        if (!visible) drawLine(c, Offset(2.5f * u, 15.5f * u), Offset(15.5f * u, 2.5f * u), 1.8f * u, StrokeCap.Round)
                    }
                }
                Spacer(Modifier.width(6.dp))
            }
            // Rename button (pencil)
            Box(
                modifier = Modifier
                    .size(btnH)
                    .clip(CircleShape)
                    .background(HeaderPillFill)
                    .bouncyClickable(pressedScale = 0.92f, onClick = onRename)
                    .testTag("layer_header_rename"),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(16.dp)) {
                    val u = size.width / 16f
                    val st = Stroke(1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val p = Path().apply {
                        moveTo(2f * u, 14f * u); lineTo(2.8f * u, 10.6f * u); lineTo(11.4f * u, 2f * u)
                        lineTo(14f * u, 4.6f * u); lineTo(5.4f * u, 13.2f * u); close()
                    }
                    drawPath(p, HeaderCyan, style = st)
                }
            }
        }
    }
}
