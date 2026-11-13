package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.IconDisabled
import com.example.ui.theme.TimelineBlack
import com.example.ui.util.bouncyClickable

private val IconWhite = Color(0xFFF2F2F2)
private val BadgeOrange = Color(0xFFFF8C00)

/**
 * Top control bar, drawn to match the design reference (positions in dp from the reference):
 *   [undo] [redo] [Full]            [ play ]            [frame] [grid] [beat mark +]
 * All glyphs are drawn here (no stock icons) so stroke weight and proportions match.
 *
 * [onPreviousClick] / [onNextClick] are kept so the callers do not change; the reference design has no
 * previous / next buttons.
 */
@Composable
fun PlayControlsSection(
    height: Dp,
    isPlaying: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onFullScreenClick: () -> Unit,
    onResolutionClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Text inside the outlined pill next to redo ("Full", "1/2", "1/4"). */
    resolutionLabel: String = "Full",
    /** Grid icon (canvas / project format). */
    onGridClick: () -> Unit = {},
    /** Plus icon at the right end: puts a beat mark at the playhead. */
    onBeatMarkClick: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(TimelineBlack)
    ) {
        // ── Left: undo · redo · Full ───────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton44(enabled = canUndo, onClick = onUndoClick, tag = "undo_button", description = "Undo") {
                UndoGlyph(if (canUndo) IconWhite else IconDisabled, mirrored = false)
            }
            IconButton44(enabled = canRedo, onClick = onRedoClick, tag = "redo_button", description = "Redo") {
                UndoGlyph(if (canRedo) IconWhite else IconDisabled, mirrored = true)
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = "Preview resolution" }
                    .bouncyClickable(onClick = onResolutionClick)
                    .testTag("preview_resolution_button"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height(17.5.dp)
                        .border(1.9.dp, IconWhite, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = resolutionLabel,
                        color = IconWhite,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }

        // ── Centre: play / pause ───────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(44.dp)
                .semantics { contentDescription = if (isPlaying) "Pause" else "Play" }
                .bouncyClickable(pressedScale = 0.88f, onClick = onPlayPauseClick)
                .testTag("play_pause_button"),
            contentAlignment = Alignment.Center
        ) {
            PlayGlyph(isPlaying)
        }

        // ── Right: frame · grid · add layer ────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            IconButton44(enabled = true, onClick = onFullScreenClick, tag = "fullscreen_button", description = "Full Screen") {
                FrameGlyph()
            }
            IconButton44(enabled = true, onClick = onGridClick, tag = "grid_button", description = "Canvas") {
                GridGlyph()
            }
            IconButton44(enabled = true, onClick = onBeatMarkClick, tag = "beat_mark_button", description = "Beat mark") {
                BeatMarkGlyph()
            }
        }
    }
}

@Composable
private fun IconButton44(
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
    description: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .semantics { contentDescription = description }
            .bouncyClickable(enabled = enabled, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) { content() }
}

// ── Glyphs ───────────────────────────────────────────────────────────────

/** Undo arrow (a U-turn with a chevron head). [mirrored] = redo. 24 x 24 dp box. */
@Composable
private fun UndoGlyph(color: Color, mirrored: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        fun x(v: Float) = (if (mirrored) 24f - v else v) * u
        fun y(v: Float) = v * u
        val head = Path().apply {
            moveTo(x(8.4f), y(5.1f)); lineTo(x(3.9f), y(9.0f)); lineTo(x(8.4f), y(13.0f))
        }
        val body = Path().apply {
            moveTo(x(3.9f), y(9.0f))
            lineTo(x(15.2f), y(9.0f))
            // half circle on the right, centre (15.2, 13.95)
            cubicTo(x(18.9f), y(9.0f), x(20.6f), y(11.6f), x(20.6f), y(13.95f))
            cubicTo(x(20.6f), y(16.5f), x(18.7f), y(18.9f), x(15.2f), y(18.9f))
            lineTo(x(10.8f), y(18.9f))
        }
        val st = Stroke(width = 2.3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(head, color, style = st)
        drawPath(body, color, style = st)
    }
}

/** Soft rounded play triangle, or two bars while playing. */
@Composable
private fun PlayGlyph(isPlaying: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        if (isPlaying) {
            val bw = 4.4f * u
            val bh = 17f * u
            val top = (size.height - bh) / 2f
            drawRoundRect(Color.White, Offset(size.width / 2f - 6.4f * u, top), Size(bw, bh), CornerRadius(1.8f * u))
            drawRoundRect(Color.White, Offset(size.width / 2f + 2.0f * u, top), Size(bw, bh), CornerRadius(1.8f * u))
        } else {
            // 14.6 x 17.2 dp triangle with rounded corners (fill + round-joined stroke of the same colour)
            val r = 1.6f * u
            val w = 14.6f * u - 2 * r
            val h = 17.2f * u - 2 * r
            val x0 = size.width / 2f - 14.6f * u / 2f + r + 0.6f * u   // optical centre: triangle is heavier on the left
            val y0 = (size.height - h) / 2f
            val p = Path().apply {
                moveTo(x0, y0); lineTo(x0 + w, y0 + h / 2f); lineTo(x0, y0 + h); close()
            }
            drawPath(p, Color.White)
            drawPath(p, Color.White, style = Stroke(width = 2 * r, join = StrokeJoin.Round))
        }
    }
}

/** Four corner nodes joined by a frame, two overlapping rounded layers inside. */
@Composable
private fun FrameGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val c = Color(0xFFF2F2F2)
        val a = 4.7f * u
        val b = 19.3f * u
        drawLine(c, Offset(a, a), Offset(b, a), 1.4f * u, StrokeCap.Round)
        drawLine(c, Offset(a, b), Offset(b, b), 1.4f * u, StrokeCap.Round)
        drawLine(c, Offset(a, a), Offset(a, b), 1.4f * u, StrokeCap.Round)
        drawLine(c, Offset(b, a), Offset(b, b), 1.4f * u, StrokeCap.Round)
        for (cx in listOf(a, b)) for (cy in listOf(a, b)) drawCircle(c, 1.95f * u, Offset(cx, cy))
        // lower-right layer first, upper-left one on top with a thin black gap
        drawRoundRect(Color(0xFFE9E9E9), Offset(8.7f * u, 12.5f * u), Size(8.4f * u, 4.9f * u), CornerRadius(2.4f * u))
        drawRoundRect(Color.Black, Offset(6.4f * u, 6.3f * u), Size(9.1f * u, 6.1f * u), CornerRadius(3f * u), style = Stroke(1.2f * u))
        drawRoundRect(Color(0xFFEFEFEF), Offset(7.0f * u, 6.9f * u), Size(8.0f * u, 5.0f * u), CornerRadius(2.5f * u))
    }
}

/** Rounded outline with a 4 x 3 grid of small squares. */
@Composable
private fun GridGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val c = Color(0xFFF2F2F2)
        val w = 20.7f * u - 2.2f * u
        val h = 19f * u - 2.2f * u
        val left = (size.width - 20.7f * u) / 2f + 1.1f * u
        val top = (size.height - 19f * u) / 2f + 1.1f * u
        drawRoundRect(c, Offset(left, top), Size(w, h), CornerRadius(4.6f * u), style = Stroke(2.2f * u))
        val sq = 2.3f * u
        val cx0 = size.width / 2f - 1.5f * 3.85f * u
        val cy0 = size.height / 2f - 4.4f * u
        for (r in 0..2) for (k in 0..3) {
            drawRoundRect(
                c,
                Offset(cx0 + k * 3.85f * u - sq / 2f, cy0 + r * 4.4f * u - sq / 2f),
                Size(sq, sq), CornerRadius(0.5f * u)
            )
        }
    }
}

/** Cyan diamond (the mark on the ruler) with a white plus beside it: adds a beat mark. */
@Composable
private fun BeatMarkGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val cyan = Color(0xFF3DDCFF)
        val d = Path().apply {
            moveTo(10f * u, 3.2f * u); lineTo(16.6f * u, 10f * u); lineTo(10f * u, 16.8f * u); lineTo(3.4f * u, 10f * u); close()
        }
        drawPath(d, cyan)
        drawPath(d, cyan, style = Stroke(1.6f * u, join = StrokeJoin.Round))
        // plus, lower right
        val px = 17.4f * u
        val py = 17.4f * u
        val arm = 3.6f * u
        drawLine(Color.White, Offset(px - arm, py), Offset(px + arm, py), 2.1f * u, StrokeCap.Round)
        drawLine(Color.White, Offset(px, py - arm), Offset(px, py + arm), 2.1f * u, StrokeCap.Round)
    }
}

