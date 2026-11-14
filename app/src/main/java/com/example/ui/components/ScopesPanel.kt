package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ScopeData
import com.example.model.ScopeStats
import com.example.ui.theme.AmBase
import com.example.ui.theme.TextSecondary
import kotlin.math.min

/**
 * The video-scopes panel (build 196): RGB + luma histogram, luma waveform
 * and vectorscope of the composed preview frame at the playhead, computed by
 * the pure [ScopeStats] pass over the canvas surface's picture. Shown under
 * the play controls while the scopes toggle is on (its height comes out of
 * the canvas, like the onion-skin row — the sections below do not move).
 *
 * Chrome stays in the settled B/W language (dark plots, grey graticule, grey
 * labels); the TRACES carry data colours, classified like the onion-skin
 * tints and the marker gold — the histogram draws its channels in red /
 * green / blue with the luma as a white line, the waveform in the classic
 * pale-green phosphor, the vectorscope in a pale cyan phosphor with the six
 * saturated-colour targets as dim graticule crosses. An RGB parade is
 * deliberately not squeezed in: at phone width each of its three traces
 * would be ~35 dp tall and unreadable, so the panel ships the luma waveform
 * plus the full RGB histogram instead (build 196 notes).
 *
 * [data] is null when no frame could be read (surface gone / readback
 * failed): the cells then show their graticules and an honest "No frame" —
 * never a stale set of traces presented as current.
 */
val ScopesPanelHeight = 118.dp

private val PlotBg = Color(0xFF0D0D0D)
private val GridLine = Color(0xFF262626)
private val HistRed = Color(0xFFFF4444)
private val HistGreen = Color(0xFF51FF51)
private val HistBlue = Color(0xFF5599FF)
private val WaveTrace = Color(0xFFB7FFB7)
private val VecTrace = Color(0xFFA8FFF2)
private val VecTarget = Color(0xFF4A4A4A)

@Composable
fun ScopesPanel(data: ScopeData?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ScopesPanelHeight)
            .background(AmBase)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ScopeCell("Histogram", Modifier.weight(1f)) { HistogramPlot(data) }
        ScopeCell("Waveform", Modifier.weight(1f)) { WaveformPlot(data) }
        ScopeCell("Vectorscope", Modifier.weight(1f)) { VectorscopePlot(data) }
    }
}

@Composable
private fun ScopeCell(label: String, modifier: Modifier = Modifier, plot: @Composable () -> Unit) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 3.dp)) { plot() }
    }
}

/** The empty state shared by all three plots: graticule only, honestly labelled. */
@Composable
private fun EmptyPlot() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(PlotBg)
        drawRect(GridLine, style = Stroke(1f))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No frame", color = TextSecondary, fontSize = 10.sp)
    }
}

/** Quarter graticule shared by the histogram and the waveform. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.graticule() {
    drawRect(PlotBg)
    for (i in 1..3) {
        val x = size.width * i / 4f
        val y = size.height * i / 4f
        drawLine(GridLine, Offset(x, 0f), Offset(x, size.height), 1f)
        drawLine(GridLine, Offset(0f, y), Offset(size.width, y), 1f)
    }
    drawRect(GridLine, style = Stroke(1f))
}

@Composable
private fun HistogramPlot(data: ScopeData?) {
    if (data == null) {
        EmptyPlot()
        return
    }
    Canvas(Modifier.fillMaxSize()) {
        graticule()
        val peak = maxOf(
            data.histR.maxOrNull() ?: 0, data.histG.maxOrNull() ?: 0,
            data.histB.maxOrNull() ?: 0, data.histY.maxOrNull() ?: 0
        ).coerceAtLeast(1)
        val cols = size.width.toInt().coerceAtLeast(1)
        // Peak-preserving aggregation: a display column shows the tallest
        // bin it covers, so a narrow spike is never averaged away.
        fun heights(hist: IntArray): FloatArray {
            val out = FloatArray(cols)
            for (dx in 0 until cols) {
                val b0 = dx * ScopeStats.BINS / cols
                val b1 = maxOf(b0 + 1, (dx + 1) * ScopeStats.BINS / cols)
                var m = 0
                for (b in b0 until b1) if (hist[b] > m) m = hist[b]
                out[dx] = size.height - (m.toFloat() / peak) * size.height
            }
            return out
        }
        fun fillPath(hist: IntArray): Path {
            val hs = heights(hist)
            return Path().apply {
                moveTo(0f, size.height)
                for (dx in 0 until cols) lineTo(dx + 0.5f, hs[dx])
                lineTo(size.width, size.height)
                close()
            }
        }
        drawPath(fillPath(data.histR), HistRed, alpha = 0.5f)
        drawPath(fillPath(data.histG), HistGreen, alpha = 0.5f)
        drawPath(fillPath(data.histB), HistBlue, alpha = 0.5f)
        val ly = heights(data.histY)
        val line = Path().apply {
            moveTo(0.5f, ly[0])
            for (dx in 1 until cols) lineTo(dx + 0.5f, ly[dx])
        }
        drawPath(line, Color.White, alpha = 0.95f, style = Stroke(1.2f))
    }
}

@Composable
private fun WaveformPlot(data: ScopeData?) {
    if (data == null) {
        EmptyPlot()
        return
    }
    Canvas(Modifier.fillMaxSize()) {
        graticule()
        val cols = size.width.toInt().coerceAtLeast(1)
        // The brightest a display column can be: every analysis pixel in it
        // at the same level. Density alpha grows with the count's share.
        val perCol = data.height * maxOf(1, data.width / cols)
        val faint = ArrayList<Offset>()
        val bright = ArrayList<Offset>()
        for (dx in 0 until cols) {
            val x0 = dx * data.width / cols
            val x1 = maxOf(x0 + 1, (dx + 1) * data.width / cols)
            for (level in 0 until ScopeStats.BINS) {
                var count = 0
                for (x in x0 until x1) count += data.wave[x * ScopeStats.BINS + level]
                if (count == 0) continue
                val y = size.height - (level + 0.5f) / ScopeStats.BINS * size.height
                val p = Offset(dx + 0.5f, y)
                if (count.toFloat() / perCol >= 0.25f) bright += p else faint += p
            }
        }
        if (faint.isNotEmpty()) drawPoints(faint, PointMode.Points, WaveTrace, alpha = 0.3f, strokeWidth = 1.6f, cap = StrokeCap.Round)
        if (bright.isNotEmpty()) drawPoints(bright, PointMode.Points, WaveTrace, alpha = 0.95f, strokeWidth = 1.6f, cap = StrokeCap.Round)
    }
}

@Composable
private fun VectorscopePlot(data: ScopeData?) {
    if (data == null) {
        EmptyPlot()
        return
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(PlotBg)
        drawRect(GridLine, style = Stroke(1f))
        val side = min(size.width, size.height)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = side / 2f - 2f
        if (radius <= 4f) return@Canvas
        drawCircle(GridLine, radius, Offset(cx, cy), style = Stroke(1f))
        drawCircle(GridLine, radius * 0.5f, Offset(cx, cy), style = Stroke(1f))
        drawLine(GridLine, Offset(cx - radius, cy), Offset(cx + radius, cy), 1f)
        drawLine(GridLine, Offset(cx, cy - radius), Offset(cx, cy + radius), 1f)
        // The six fully-saturated primaries / secondaries as graticule
        // crosses, at the positions the same BT.601 matrix puts them.
        val targets = listOf(
            ScopeStats.cb(255, 0, 0) to ScopeStats.cr(255, 0, 0),
            ScopeStats.cb(255, 0, 255) to ScopeStats.cr(255, 0, 255),
            ScopeStats.cb(0, 0, 255) to ScopeStats.cr(0, 0, 255),
            ScopeStats.cb(0, 255, 255) to ScopeStats.cr(0, 255, 255),
            ScopeStats.cb(0, 255, 0) to ScopeStats.cr(0, 255, 0),
            ScopeStats.cb(255, 255, 0) to ScopeStats.cr(255, 255, 0)
        )
        for ((u, v) in targets) {
            val tx = cx + (u / ScopeStats.VEC_FULL_SCALE) * radius
            val ty = cy - (v / ScopeStats.VEC_FULL_SCALE) * radius
            drawLine(VecTarget, Offset(tx - 3f, ty), Offset(tx + 3f, ty), 1.2f)
            drawLine(VecTarget, Offset(tx, ty - 3f), Offset(tx, ty + 3f), 1.2f)
        }
        // Density: faint pass for every occupied bin, bright pass where a
        // bin holds a real share of the densest bin.
        val peak = (data.vec.maxOrNull() ?: 0).coerceAtLeast(1)
        val faint = ArrayList<Offset>()
        val bright = ArrayList<Offset>()
        val grid = ScopeStats.VEC_GRID
        for (by in 0 until grid) {
            for (bx in 0 until grid) {
                val count = data.vec[by * grid + bx]
                if (count == 0) continue
                val fx = (bx + 0.5f) / grid * 2f - 1f
                val fy = (by + 0.5f) / grid * 2f - 1f
                val p = Offset(cx + fx * radius, cy + fy * radius)
                if (count.toFloat() / peak >= 0.25f) bright += p else faint += p
            }
        }
        if (faint.isNotEmpty()) drawPoints(faint, PointMode.Points, VecTrace, alpha = 0.3f, strokeWidth = 2f, cap = StrokeCap.Round)
        if (bright.isNotEmpty()) drawPoints(bright, PointMode.Points, VecTrace, alpha = 0.95f, strokeWidth = 2f, cap = StrokeCap.Round)
    }
}
