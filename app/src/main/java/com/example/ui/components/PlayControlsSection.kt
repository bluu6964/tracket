package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.engine.VectorCmdBuilder
import com.example.engine.UiLabelAtlas
import com.example.engine.UiLabelStyle
import com.example.engine.rememberUiAtlas
import com.example.model.ChromeGeometry
import com.example.ui.theme.AmBase
import com.example.ui.theme.AmPanel
import com.example.ui.theme.RefCtrl
import com.example.ui.theme.RefCtrlDisabled
import com.example.ui.theme.RefLime
import com.example.ui.theme.RefOrange
import kotlin.math.roundToInt

/**
 * THE CONTROLS BAR (build 217 — Sarowar's HTML reference, copied exactly):
 * one slim row on the app background, a #1A1A20 hairline under it. LEFT
 * group: Undo, Redo, the "Full" outline badge. CENTRE: Play / Pause.
 * RIGHT group: the Tools button (build 263 — it replaced the snap magnet
 * in the magnet's slot and opens the tools sheet the layer-count pill
 * used to open), the grid toggle, and Add-Keyframe with its orange dot.
 * Ctrl glyphs are #E4E4E7, an active toggle turns LIME, a disabled one
 * #45454D.
 *
 * [showEditorControls] false = a chrome-only host (CropView, audit S-3):
 * the editor-only right group is omitted and the host's own wired
 * controls (previous / next / fullscreen, the resolution badge) render
 * in the same language instead — nothing the host wires is dropped.
 *
 * Build 250 (UI migration stage 3): the whole bar is drawn on ONE
 * native GL surface — positions from [ChromeGeometry.Controls] (the
 * build-249 layout, host-tested), the badge label through the stage-2
 * label atlas, the Material undo/redo icons as atlas rasters, and the
 * drawn glyphs ported command-for-command from their build-249
 * per-glyph canvases (same unit math, placed with the builder's
 * translate + press-scale transform). The tap targets stay Compose:
 * invisible boxes in the original Row layout carrying the original
 * tags/descriptions and bouncyClickable's gesture contract
 * ([ChromePressBox]). Behaviour and pixels are build 249's.
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
    /** Text inside the outlined badge ("Full" in the editor; the preview-resolution label in chrome hosts). */
    resolutionLabel: String = "Full",
    /** Editor-only controls: false for chrome-only hosts (CropView) — they are omitted, not inert. */
    showEditorControls: Boolean = true,
    /** Kept for caller compatibility (build 214+): the tools disc that used these is gone. */
    toolsGridExpanded: Boolean = false,
    onToolsGridExpandedChange: (Boolean) -> Unit = {},
    toolsGridActive: Boolean = false,
    /**
     * Build 263: the Tools button — the controls bar's first right-group
     * button, in the slot the snap magnet held until now. It opens exactly
     * what the timeline's layer-count pill used to open (the tools sheet);
     * Snap itself remains a tile inside that sheet.
     */
    onToolsClick: () -> Unit = {},
    /** Build 217: the canvas grid toggle of the controls bar. */
    gridOn: Boolean = false,
    onGridClick: () -> Unit = {},
    /** Build 217: add a transform keyframe at the playhead (selected layer). */
    onAddKeyframeClick: () -> Unit = {}
) {
    val c = ChromeGeometry.Controls
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val badgeText = if (showEditorControls) "Full" else resolutionLabel

    val undoPainter = rememberVectorPainter(Icons.AutoMirrored.Filled.Undo)
    val redoPainter = rememberVectorPainter(Icons.AutoMirrored.Filled.Redo)

    fun resolveAll(): ControlsEntries {
        val g = (c.GLYPH_SMALL_DP * density).roundToInt()
        val badge = atlas.resolve(badgeText, UiLabelStyle.BADGE)
        val undo = atlas.resolveIcon("ctrl:undo", g, g) { cv, x, y ->
            val b = rasterizePainterBitmap(undoPainter, g, density)
            cv.drawBitmap(b, x, y, null)
            b.recycle()
        }
        val redo = atlas.resolveIcon("ctrl:redo", g, g) { cv, x, y ->
            val b = rasterizePainterBitmap(redoPainter, g, density)
            cv.drawBitmap(b, x, y, null)
            b.recycle()
        }
        return ControlsEntries(badge, undo, redo)
    }
    var entries = resolveAll()
    if (atlas.overflowed) {
        atlas.reset()
        entries = resolveAll()
    }
    val badgeTextH = atlas.textHeightPx(UiLabelStyle.BADGE)
    val badgeAscent = atlas.fontMetrics(UiLabelStyle.BADGE).ascent

    // One press Animatable per button, in layout order: undo, redo,
    // badge, play, then the right group (snap/prev, grid/next,
    // keyframe/fullscreen).
    val scales = remember { List(7) { Animatable(1f) } }
    val scaleValues = scales.map { it.value }
    val pad = UiLabelAtlas.PAD.toFloat()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(AmBase)
            .testTag("controls_bar")
    ) {
        NativeVectorCanvas(
            modifier = Modifier.fillMaxSize(),
            clearArgb = 0,
            atlas = atlas,
            drawKey = listOf(
                isPlaying, canUndo, canRedo, gridOn, showEditorControls,
                entries, scaleValues, atlas.generation
            ),
            buildCmds = { w, h, dd ->
                val btnPx = c.BTN_DP * dd
                val btnT = c.btnTopPx(h, dd)
                val cy = btnT + btnPx / 2f

                // Build 276 (fresh design): the bar sits on a rounded
                // slate band inset from the screen edges (the concept's
                // toolbar band); the buttons' law positions are
                // unchanged, and the band's edge replaces the old
                // bottom hairline as the bar's lower boundary.
                addRoundRect(
                    w / 2f, h / 2f, w - 16f * dd, h - 8f * dd, 16f * dd,
                    AmPanel.toArgb()
                )

                // Undo / redo: atlas icons; a disabled button's tint is
                // the disabled colour at bouncyClickable's 0.45 alpha.
                entries.undo?.let { e ->
                    setScaleAbout(c.undoLeftPx(dd) + btnPx / 2f, cy, scales[0].value)
                    addText(
                        e.index,
                        c.glyphLeftPx(c.undoLeftPx(dd), c.GLYPH_SMALL_DP, dd) - pad,
                        c.glyphTopPx(btnT, c.GLYPH_SMALL_DP, dd) - pad,
                        0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                        tintArgb = if (canUndo) RefCtrl.toArgb()
                            else RefCtrlDisabled.copy(alpha = 0.45f).toArgb()
                    )
                    clearTransform()
                }
                entries.redo?.let { e ->
                    setScaleAbout(c.redoLeftPx(dd) + btnPx / 2f, cy, scales[1].value)
                    addText(
                        e.index,
                        c.glyphLeftPx(c.redoLeftPx(dd), c.GLYPH_SMALL_DP, dd) - pad,
                        c.glyphTopPx(btnT, c.GLYPH_SMALL_DP, dd) - pad,
                        0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                        tintArgb = if (canRedo) RefCtrl.toArgb()
                            else RefCtrlDisabled.copy(alpha = 0.45f).toArgb()
                    )
                    clearTransform()
                }

                // Badge: the outlined pill + its centred label.
                entries.badge?.let { e ->
                    val cx = c.badgeLeftPx(dd) + btnPx / 2f
                    setScaleAbout(cx, cy, scales[2].value)
                    val bw = c.badgeWidthPx(e.advance, dd)
                    val bh = c.badgeHeightPx(badgeTextH, dd)
                    val vl = c.badgeVisualLeftPx(e.advance, dd)
                    val vt = c.badgeVisualTopPx(h, badgeTextH, dd)
                    addRoundRect(
                        vl + bw / 2f, vt + bh / 2f,
                        bw - c.BADGE_BORDER_DP * dd, bh - c.BADGE_BORDER_DP * dd,
                        (c.BADGE_CORNER_DP - c.BADGE_BORDER_DP / 2f) * dd,
                        0, c.BADGE_BORDER_DP * dd, RefCtrl.toArgb()
                    )
                    val baseY = c.badgeTextTopPx(h, badgeTextH, dd) - badgeAscent
                    addText(
                        e.index, vl + 4f * dd + e.bearing - pad, baseY - e.base,
                        0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                        tintArgb = RefCtrl.toArgb()
                    )
                    clearTransform()
                }

                // Play / pause, exactly centred.
                run {
                    val left = c.playLeftPx(w, dd)
                    setScaleAbout(left + btnPx / 2f, cy, scales[3].value)
                    setTranslate(
                        c.glyphLeftPx(left, c.GLYPH_PLAY_DP, dd),
                        c.glyphTopPx(btnT, c.GLYPH_PLAY_DP, dd)
                    )
                    glyphPlayBody(c.GLYPH_PLAY_DP * dd, c.GLYPH_PLAY_DP * dd, isPlaying)
                    clearTransform()
                }

                if (showEditorControls) {
                    // Tools button (build 263; the snap magnet's old slot).
                    run {
                        val left = c.rightLeftPx(w, dd, 0)
                        setScaleAbout(left + btnPx / 2f, cy, scales[4].value)
                        setTranslate(
                            c.glyphLeftPx(left, c.GLYPH_SMALL_DP, dd),
                            c.glyphTopPx(btnT, c.GLYPH_SMALL_DP, dd)
                        )
                        glyphToolsBody(c.GLYPH_SMALL_DP * dd)
                        clearTransform()
                    }
                    // Grid toggle.
                    run {
                        val left = c.rightLeftPx(w, dd, 1)
                        setScaleAbout(left + btnPx / 2f, cy, scales[5].value)
                        setTranslate(
                            c.glyphLeftPx(left, c.GLYPH_SMALL_DP, dd),
                            c.glyphTopPx(btnT, c.GLYPH_SMALL_DP, dd)
                        )
                        glyphGridBody(c.GLYPH_SMALL_DP * dd, gridOn)
                        clearTransform()
                    }
                    // Add keyframe: shield + the orange dot at its
                    // glyph box's bottom-end corner.
                    run {
                        val left = c.rightLeftPx(w, dd, 2)
                        val gl = c.glyphLeftPx(left, c.GLYPH_SMALL_DP, dd)
                        val gt = c.glyphTopPx(btnT, c.GLYPH_SMALL_DP, dd)
                        val g = c.GLYPH_SMALL_DP * dd
                        setScaleAbout(left + btnPx / 2f, cy, scales[6].value)
                        setTranslate(gl, gt)
                        glyphShieldBody(g)
                        setTranslate(0f, 0f)
                        val dotX = c.dotCenterXPx(gl + g, dd)
                        val dotY = c.dotCenterYPx(gt + g, dd)
                        addCircle(dotX, dotY, c.dotRadiusPx(dd), RefOrange.toArgb())
                        addCircle(
                            dotX, dotY, c.dotRingRadiusPx(dd),
                            0, c.dotStrokePx(dd), AmBase.toArgb()
                        )
                        clearTransform()
                    }
                } else {
                    // Previous / next frame (skip glyphs).
                    run {
                        val left = c.rightLeftPx(w, dd, 0)
                        setScaleAbout(left + btnPx / 2f, cy, scales[4].value)
                        setTranslate(
                            c.glyphLeftPx(left, c.GLYPH_SMALL_DP, dd),
                            c.glyphTopPx(btnT, c.GLYPH_SMALL_DP, dd)
                        )
                        glyphSkipBody(c.GLYPH_SMALL_DP * dd, mirrored = true)
                        clearTransform()
                    }
                    run {
                        val left = c.rightLeftPx(w, dd, 1)
                        setScaleAbout(left + btnPx / 2f, cy, scales[5].value)
                        setTranslate(
                            c.glyphLeftPx(left, c.GLYPH_SMALL_DP, dd),
                            c.glyphTopPx(btnT, c.GLYPH_SMALL_DP, dd)
                        )
                        glyphSkipBody(c.GLYPH_SMALL_DP * dd, mirrored = false)
                        clearTransform()
                    }
                    // Fullscreen (frame glyph).
                    run {
                        val left = c.rightLeftPx(w, dd, 2)
                        setScaleAbout(left + btnPx / 2f, cy, scales[6].value)
                        setTranslate(
                            c.glyphLeftPx(left, c.GLYPH_SMALL_DP, dd),
                            c.glyphTopPx(btnT, c.GLYPH_SMALL_DP, dd)
                        )
                        glyphFrameBody(c.GLYPH_SMALL_DP * dd)
                        clearTransform()
                    }
                }
            }
        )

        // Tap targets: the build-249 Row layout, invisible.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChromePressBox(
                scales[0], 0.9f, onUndoClick, Modifier.size(28.dp),
                enabled = canUndo, tag = "workspace_undo_button", description = "Undo"
            )
            Spacer(Modifier.size(10.dp))
            ChromePressBox(
                scales[1], 0.9f, onRedoClick, Modifier.size(28.dp),
                enabled = canRedo, tag = "workspace_redo_button", description = "Redo"
            )
            Spacer(Modifier.size(10.dp))
            ChromePressBox(
                scales[2], 0.9f,
                if (showEditorControls) onFullScreenClick else onResolutionClick,
                Modifier.size(28.dp),
                tag = if (showEditorControls) "fullscreen_button" else "preview_resolution_button",
                description = if (showEditorControls) "Full Screen" else "Preview resolution"
            )
            Spacer(Modifier.weight(1f))
            ChromePressBox(
                scales[3], 0.9f, onPlayPauseClick, Modifier.size(28.dp),
                tag = "play_pause_button", description = if (isPlaying) "Pause" else "Play"
            )
            Spacer(Modifier.weight(1f))
            if (showEditorControls) {
                ChromePressBox(
                    scales[4], 0.9f, onToolsClick, Modifier.size(28.dp),
                    tag = "workspace_tools_button", description = "Tools"
                )
                Spacer(Modifier.size(10.dp))
                ChromePressBox(
                    scales[5], 0.9f, onGridClick, Modifier.size(28.dp),
                    tag = "workspace_grid_toggle", description = "Grid"
                )
                Spacer(Modifier.size(10.dp))
                ChromePressBox(
                    scales[6], 0.9f, onAddKeyframeClick, Modifier.size(28.dp),
                    tag = "add_keyframe_button", description = "Add Keyframe"
                )
            } else {
                ChromePressBox(
                    scales[4], 0.9f, onPreviousClick, Modifier.size(28.dp),
                    tag = "previous_button", description = "Previous"
                )
                Spacer(Modifier.size(10.dp))
                ChromePressBox(
                    scales[5], 0.9f, onNextClick, Modifier.size(28.dp),
                    tag = "next_button", description = "Next"
                )
                Spacer(Modifier.size(10.dp))
                ChromePressBox(
                    scales[6], 0.9f, onFullScreenClick, Modifier.size(28.dp),
                    tag = "fullscreen_button", description = "Full Screen"
                )
            }
        }
    }
}

private data class ControlsEntries(
    val badge: UiLabelAtlas.Entry?,
    val undo: UiLabelAtlas.Entry?,
    val redo: UiLabelAtlas.Entry?
)

// ── Glyph bodies ────────────────────────────────────────────────────
// Command-for-command ports of the build-249 per-glyph
// NativeVectorCanvas draws (identical unit math); the caller places
// them with the builder's translate + press-scale transform, so the
// coordinates here are glyph-box-local, exactly as they were on their
// own canvases.

/** Soft rounded play triangle, or two bars while playing (19 dp, controls colour). */
private fun VectorCmdBuilder.glyphPlayBody(w: Float, h: Float, isPlaying: Boolean) {
    val u = w / 24f
    val cArgb = RefCtrl.toArgb()
    if (isPlaying) {
        val bw = 4.4f * u
        val bh = 17f * u
        val cy = h / 2f
        addRoundRect(w / 2f - 6.4f * u + bw / 2f, cy, bw, bh, 1.8f * u, cArgb)
        addRoundRect(w / 2f + 2.0f * u + bw / 2f, cy, bw, bh, 1.8f * u, cArgb)
    } else {
        val r = 1.6f * u
        val tw = 14.6f * u - 2 * r
        val th = 17.2f * u - 2 * r
        val x0 = w / 2f - 14.6f * u / 2f + r + 0.6f * u
        val y0 = (h - th) / 2f
        val xs = floatArrayOf(x0, x0 + tw, x0)
        val ys = floatArrayOf(y0, y0 + th / 2f, y0 + th)
        addFilledPolygon(xs, ys, 3, cArgb)
        addPolyline(xs, ys, 3, true, 2 * r, cArgb, capRound = true)
    }
}

/** Skip triangle + bar (previous / next) — chrome hosts only. */
private fun VectorCmdBuilder.glyphSkipBody(w: Float, mirrored: Boolean) {
    val u = w / 24f
    val cArgb = RefCtrl.toArgb()
    fun x(v: Float) = (if (mirrored) 24f - v else v) * u
    addLine(x(5f), 5.5f * u, x(5f), 18.5f * u, 2.4f * u, cArgb, capRound = true)
    val xs = floatArrayOf(x(9f), x(19f), x(9f))
    val ys = floatArrayOf(6f * u, 12f * u, 18f * u)
    addFilledPolygon(xs, ys, 3, cArgb)
    addPolyline(xs, ys, 3, true, 1.6f * u, cArgb, capRound = true)
}

/** Four corners + two overlapping layers — the fullscreen glyph (chrome hosts). */
private fun VectorCmdBuilder.glyphFrameBody(w: Float) {
    val u = w / 24f
    val cArgb = RefCtrl.toArgb()
    val a = 4.7f * u
    val b = 19.3f * u
    addLine(a, a, b, a, 1.4f * u, cArgb, capRound = true)
    addLine(a, b, b, b, 1.4f * u, cArgb, capRound = true)
    addLine(a, a, a, b, 1.4f * u, cArgb, capRound = true)
    addLine(b, a, b, b, 1.4f * u, cArgb, capRound = true)
    for (cx in listOf(a, b)) for (cy in listOf(a, b)) addCircle(cx, cy, 1.95f * u, cArgb)
    addRoundRect((8.7f + 4.2f) * u, (12.5f + 2.45f) * u, 8.4f * u, 4.9f * u, 2.4f * u, Color(0xFFA1A1AA).toArgb())
    addRoundRect((7.0f + 4.0f) * u, (6.9f + 2.5f) * u, 8.0f * u, 5.0f * u, 2.5f * u, cArgb, 1.2f * u, AmBase.toArgb())
}

/** The Tools wrench (build 263): an open-end wrench — a notched ring at the top-left running into a diagonal handle (17 dp, controls colour; an action button, not a toggle). */
private fun VectorCmdBuilder.glyphToolsBody(w: Float) {
    val u = w / 24f
    val cArgb = RefCtrl.toArgb()
    // The ring: a near-full arc whose gap faces the handle's root.
    addArc(
        cx = 8.6f * u, cy = 8.6f * u,
        rx = 4.6f * u, ry = 4.6f * u,
        startDeg = 55f, sweepDeg = 250f,
        width = 2.7f * u, strokeArgb = cArgb, capRound = true, closedPie = false
    )
    // The handle: from the ring's lower-right down to the corner.
    addLine(11.6f * u, 11.6f * u, 19.4f * u, 19.4f * u, 3.6f * u, cArgb, capRound = true)
}

/** The reference's grid glyph: a rounded square divided in thirds both ways (17 dp). Lime while on. */
private fun VectorCmdBuilder.glyphGridBody(w: Float, on: Boolean) {
    val u = w / 24f
    val cArgb = (if (on) RefLime else RefCtrl).toArgb()
    addRoundRect(12f * u, 12f * u, 16f * u, 16f * u, 3f * u, 0, 1.8f * u, cArgb)
    addLine(9f * u, 4f * u, 9f * u, 20f * u, 1.5f * u, cArgb, capRound = false)
    addLine(15f * u, 4f * u, 15f * u, 20f * u, 1.5f * u, cArgb, capRound = false)
    addLine(4f * u, 9f * u, 20f * u, 9f * u, 1.5f * u, cArgb, capRound = false)
    addLine(4f * u, 15f * u, 20f * u, 15f * u, 1.5f * u, cArgb, capRound = false)
}

/** The reference's Add-Keyframe glyph: a filled shield with a plus cut out of it (17 dp). */
private fun VectorCmdBuilder.glyphShieldBody(w: Float) {
    val u = w / 24f
    val pts = mutableListOf<Pair<Float, Float>>()
    pts.add(12f * u to 2.5f * u)
    pts.add(18.5f * u to 6f * u)
    pts.add(18.5f * u to 14.2f * u)
    fun addCubicPts(x0: Float, y0: Float, c1x: Float, c1y: Float, c2x: Float, c2y: Float, x1: Float, y1: Float) {
        for (i in 1..10) {
            val t = i / 10f
            val m = 1f - t
            val px = m * m * m * x0 + 3f * m * m * t * c1x + 3f * m * t * t * c2x + t * t * t * x1
            val py = m * m * m * y0 + 3f * m * m * t * c1y + 3f * m * t * t * c2y + t * t * t * y1
            pts.add(px to py)
        }
    }
    addCubicPts(18.5f * u, 14.2f * u, 18.5f * u, 17.5f * u, 15.8f * u, 19.9f * u, 12f * u, 21.5f * u)
    addCubicPts(12f * u, 21.5f * u, 8.2f * u, 19.9f * u, 5.5f * u, 17.5f * u, 5.5f * u, 14.2f * u)
    pts.add(5.5f * u to 6f * u)
    val xs = FloatArray(pts.size) { pts[it].first }
    val ys = FloatArray(pts.size) { pts[it].second }
    addFilledPolygon(xs, ys, pts.size, RefCtrl.toArgb())
    val cutArgb = AmBase.toArgb()
    addLine(12f * u, 8f * u, 12f * u, 14f * u, 2f * u, cutArgb, capRound = true)
    addLine(9f * u, 11f * u, 15f * u, 11f * u, 2f * u, cutArgb, capRound = true)
}
