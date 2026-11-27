package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.VectorCmdBuilder
import com.example.engine.UiLabelAtlas
import com.example.engine.UiLabelStyle
import com.example.engine.rememberUiAtlas
import com.example.model.AspectRatio
import com.example.model.ChromeGeometry
import com.example.ui.theme.AmBase
import com.example.ui.theme.AmSlateHeader
import com.example.ui.theme.RefLime
import com.example.ui.theme.RefLimeInk
import com.example.ui.theme.RefPopoverBg
import com.example.ui.theme.RefPopoverBorder
import com.example.ui.theme.RefPopoverText
import com.example.ui.theme.RefSurface
import com.example.ui.theme.TextPrimary
import kotlin.math.roundToInt

/**
 * The APP BAR (build 214 — UI ARCHITECTURE REDESIGN): zone A of the editor
 * shell. Build 217 (Sarowar's HTML reference, copied exactly): the bar is
 * flat on the app background, padding 10. Left: ONLY the close button
 * (32 dp, r9, surface fill, white-4% border). Right group (gap 7): the
 * aspect-ratio icon button (32 dp), the resolution pill (32 dp tall, r9,
 * surface), and EXPORT as a 32 dp LIME icon button with the lime glow.
 * The popovers list the app's REAL ratios / export resolutions, the
 * current one lime-tinted. [aspectRatio] is kept for the callers; the
 * ratio itself is chosen in the sheet the resolution pill opens.
 *
 * Build 250 (UI migration stage 3): the bar is drawn on ONE native GL
 * surface — positions from [ChromeGeometry.Header] (the build-249
 * layout, host-tested), the pill label through the stage-2 label atlas,
 * the glyphs ported command-for-command from their build-249 per-glyph
 * canvases. The export glow stays a Compose shadow host under the
 * surface (the GL command set has no shadow primitive; in build 249
 * the shadow sat outside the press graphicsLayer too, so it does not
 * scale). Tap targets stay Compose with the original tags/descriptions
 * and press contract ([ChromePressBox]); the popovers stay Compose,
 * anchored to the touch boxes. Behaviour and pixels are build 249's.
 */
@Composable
fun EditorHeader(
    height: Dp,
    aspectRatio: AspectRatio,
    onBackClick: () -> Unit,
    onAspectRatioClick: () -> Unit,
    onExportClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Text of the resolution pill: the app's REAL current export resolution, e.g. "1080P". */
    resolutionLabel: String = "1080P",
    /**
     * Build 217: the resolution pill's popover lists the app's REAL export
     * resolutions (the stored ExportSettings labels); the current one is
     * the lime-tinted active item.
     */
    resolutionOptions: List<String> = emptyList(),
    currentResolution: String = "",
    onResolutionPick: (String) -> Unit = {},
    /** Build 217: the aspect-ratio popover lists the app's REAL canvas ratios; the current one is active. */
    ratioOptions: List<AspectRatio> = emptyList(),
    onRatioPick: (AspectRatio) -> Unit = {}
) {
    val hg = ChromeGeometry.Header
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    var ratioMenuOpen by remember { mutableStateOf(false) }
    var resMenuOpen by remember { mutableStateOf(false) }

    fun resolveAll(): Pair<UiLabelAtlas.Entry?, UiLabelAtlas.Entry?> =
        atlas.resolve(resolutionLabel, UiLabelStyle.PILL) to
            atlas.resolve("Export", UiLabelStyle.EXPORT_PILL)
    var resolvedPair = resolveAll()
    if (atlas.overflowed) {
        atlas.reset()
        resolvedPair = resolveAll()
    }
    val pillEntry = resolvedPair.first
    val exportEntry = resolvedPair.second
    val pillTextH = atlas.textHeightPx(UiLabelStyle.PILL)
    val pillAscent = atlas.fontMetrics(UiLabelStyle.PILL).ascent
    val pillWidthDp = pillEntry?.let {
        (hg.pillWidthPx(it.advance, density) / density).dp
    } ?: 0.dp
    // Build 276: the Export pill's label metrics + width (the same law
    // the GL draw and the tap target share).
    val exportTextH = atlas.textHeightPx(UiLabelStyle.EXPORT_PILL)
    val exportAscent = atlas.fontMetrics(UiLabelStyle.EXPORT_PILL).ascent
    val exportPillWidthDp = exportEntry?.let {
        (hg.exportPillWidthPx(it.advance, density) / density).dp
    } ?: hg.BTN_DP.dp

    // Press scales: close, ratio, pill, export.
    val scales = remember { List(4) { Animatable(1f) } }
    val scaleValues = scales.map { it.value }
    val pad = UiLabelAtlas.PAD.toFloat()
    val faceFill = RefSurface.toArgb()
    val faceBorder = Color.White.copy(alpha = 0.04f).toArgb()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(AmBase)
            .testTag("editor_top_bar")
    ) {
        // The export button's glow: the build-249 shadow (5 dp, r9,
        // lime 25% ambient + spot), hosted under the surface at the
        // button's rect. It does not press-scale (in 249 the shadow
        // modifier sat outside bouncyClickable's graphicsLayer).
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .width(exportPillWidthDp)
                    .height(hg.BTN_DP.dp)
                    .shadow(
                        elevation = 5.dp, shape = RoundedCornerShape(percent = 50), clip = false,
                        ambientColor = RefLime.copy(alpha = 0.25f),
                        spotColor = RefLime.copy(alpha = 0.25f)
                    )
            )
        }

        NativeVectorCanvas(
            modifier = Modifier.fillMaxSize(),
            clearArgb = 0,
            atlas = atlas,
            drawKey = listOf(pillEntry, exportEntry, scaleValues, atlas.generation),
            buildCmds = { w, h, dd ->
                val btnPx = hg.BTN_DP * dd
                val btnT = hg.btnTopPx(h, dd)
                val cy = btnT + btnPx / 2f
                val faceSize = hg.faceSizePx(dd)
                val faceRadius = hg.faceRadiusPx(dd)
                val exportPillW = exportEntry?.let { hg.exportPillWidthPx(it.advance, dd) } ?: btnPx

                // Close: surface face + the X.
                run {
                    val left = hg.closeLeftPx(dd)
                    val cx = left + btnPx / 2f
                    setScaleAbout(cx, cy, scales[0].value)
                    addRoundRect(cx, cy, faceSize, faceSize, faceRadius, faceFill, dd, faceBorder)
                    setTranslate(
                        hg.glyphLeftPx(left, hg.GLYPH_CLOSE_DP, dd),
                        hg.glyphTopPx(h, hg.GLYPH_CLOSE_DP, dd)
                    )
                    glyphCloseXBody(hg.GLYPH_CLOSE_DP * dd, hg.GLYPH_CLOSE_DP * dd, dd)
                    clearTransform()
                }

                val pillW = pillEntry?.let { hg.pillWidthPx(it.advance, dd) } ?: 0f
                val pillLeft = hg.pillLeftPx(w, pillW, exportPillW, dd)

                // Aspect ratio: surface face + the corners glyph.
                run {
                    val left = hg.ratioLeftPx(pillLeft, dd)
                    val cx = left + btnPx / 2f
                    setScaleAbout(cx, cy, scales[1].value)
                    addRoundRect(cx, cy, faceSize, faceSize, faceRadius, faceFill, dd, faceBorder)
                    setTranslate(
                        hg.glyphLeftPx(left, hg.GLYPH_RATIO_DP, dd),
                        hg.glyphTopPx(h, hg.GLYPH_RATIO_DP, dd)
                    )
                    glyphRatioBody(hg.GLYPH_RATIO_DP * dd)
                    clearTransform()
                }

                // Resolution pill: surface face + label + triangle.
                pillEntry?.let { e ->
                    val cx = pillLeft + pillW / 2f
                    setScaleAbout(cx, cy, scales[2].value)
                    addRoundRect(
                        cx, cy, pillW - dd, faceSize, faceRadius,
                        faceFill, dd, faceBorder
                    )
                    val baseY = hg.pillTextTopPx(h, pillTextH, dd) - pillAscent
                    addText(
                        e.index, hg.pillTextLeftPx(pillLeft, dd) + e.bearing - pad, baseY - e.base,
                        0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                        tintArgb = TextPrimary.toArgb()
                    )
                    setTranslate(hg.triLeftPx(pillLeft, e.advance, dd), hg.triTopPx(h, dd))
                    glyphTriangleBody(hg.TRI_DP * dd)
                    clearTransform()
                }

                // Export (build 276): the filled accent PILL — the
                // download glyph + the word "Export", both in ink.
                run {
                    val left = hg.exportLeftPx(w, exportPillW, dd)
                    val cx = left + exportPillW / 2f
                    setScaleAbout(cx, cy, scales[3].value)
                    addRoundRect(cx, cy, exportPillW, btnPx, btnPx / 2f, RefLime.toArgb())
                    setTranslate(
                        hg.exportGlyphLeftPx(left, dd),
                        hg.glyphTopPx(h, hg.GLYPH_EXPORT_DP, dd)
                    )
                    glyphDownloadBody(hg.GLYPH_EXPORT_DP * dd, RefLimeInk.toArgb())
                    setTranslate(0f, 0f)
                    exportEntry?.let { e ->
                        val baseY = hg.pillTextTopPx(h, exportTextH, dd) - exportAscent
                        addText(
                            e.index, hg.exportTextLeftPx(left, dd) + e.bearing - pad, baseY - e.base,
                            0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                            tintArgb = RefLimeInk.toArgb()
                        )
                    }
                    clearTransform()
                }
            }
        )

        // Tap targets: the build-249 Row layout, invisible; the
        // popovers stay anchored to their buttons' boxes.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChromePressBox(
                scales[0], 0.92f, onBackClick, Modifier.size(hg.BTN_DP.dp),
                tag = "header_back_button", description = "Back to projects"
            )
            // Build 276 (fresh design): the app title at the left,
            // beside the back button (the concept's header).
            Spacer(Modifier.width(10.dp))
            Text(
                "Timeline Pro",
                color = TextPrimary,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    ChromePressBox(
                        scales[1], 0.92f,
                        { if (ratioOptions.isNotEmpty()) ratioMenuOpen = true else onAspectRatioClick() },
                        Modifier.size(hg.BTN_DP.dp),
                        tag = "header_ratio_button", description = "Aspect ratio"
                    )
                    DropdownMenu(
                        expanded = ratioMenuOpen,
                        onDismissRequest = { ratioMenuOpen = false },
                        modifier = Modifier
                            .background(RefPopoverBg, RoundedCornerShape(11.dp))
                            .border(1.dp, RefPopoverBorder, RoundedCornerShape(11.dp))
                    ) {
                        ratioOptions.forEach { option ->
                            val isCurrent = option.label == aspectRatio.label
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label,
                                        color = if (isCurrent) RefLime else RefPopoverText,
                                        fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                                    )
                                },
                                onClick = {
                                    ratioMenuOpen = false
                                    onRatioPick(option)
                                },
                                modifier = if (isCurrent) Modifier.background(
                                    RefLime.copy(alpha = 0.14f), RoundedCornerShape(7.dp)
                                ) else Modifier,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Box {
                    ChromePressBox(
                        scales[2], 0.95f, { resMenuOpen = true },
                        Modifier
                            .height(hg.BTN_DP.dp)
                            .width(pillWidthDp),
                        tag = "header_resolution_pill", description = "Export resolution"
                    )
                    DropdownMenu(
                        expanded = resMenuOpen,
                        onDismissRequest = { resMenuOpen = false },
                        modifier = Modifier
                            .background(RefPopoverBg, RoundedCornerShape(11.dp))
                            .border(1.dp, RefPopoverBorder, RoundedCornerShape(11.dp))
                    ) {
                        resolutionOptions.forEach { option ->
                            val isCurrent = option == currentResolution
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.substringBefore(' ').uppercase(),
                                        color = if (isCurrent) RefLime else RefPopoverText,
                                        fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                                    )
                                },
                                onClick = {
                                    resMenuOpen = false
                                    onResolutionPick(option)
                                },
                                modifier = if (isCurrent) Modifier.background(
                                    RefLime.copy(alpha = 0.14f), RoundedCornerShape(7.dp)
                                ) else Modifier,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                ChromePressBox(
                    scales[3], 0.94f, onExportClick,
                    Modifier.width(exportPillWidthDp).height(hg.BTN_DP.dp),
                    tag = "header_export_button", description = "Export"
                )
            }
        }
    }
}

/** The close X (12 dp canvas in build 249). */
private fun VectorCmdBuilder.glyphCloseXBody(w: Float, h: Float, d: Float) {
    val cArgb = TextPrimary.toArgb()
    val sw = 2.2f * d
    val m = sw / 2f
    addLine(m, m, w - m, h - m, sw, cArgb, capRound = true)
    addLine(w - m, m, m, h - m, sw, cArgb, capRound = true)
}

/** The reference's aspect-ratio glyph: four frame corners, two dots and the crossed diagonals (16 dp). */
private fun VectorCmdBuilder.glyphRatioBody(w: Float) {
    val u = w / 24f
    val cArgb = TextPrimary.toArgb()
    val sw = 1.8f * u
    fun addCorner(pts: List<Pair<Float, Float>>) {
        val xs = FloatArray(pts.size) { pts[it].first * u }
        val ys = FloatArray(pts.size) { pts[it].second * u }
        addPolyline(xs, ys, pts.size, false, sw, cArgb, capRound = true)
    }
    addCorner(listOf(7f to 3f, 5f to 3f, 3f to 5f, 3f to 7f))
    addCorner(listOf(17f to 3f, 19f to 3f, 21f to 5f, 21f to 7f))
    addCorner(listOf(7f to 21f, 5f to 21f, 3f to 19f, 3f to 17f))
    addCorner(listOf(17f to 21f, 19f to 21f, 21f to 19f, 21f to 17f))
    addCircle(9.5f * u, 14.5f * u, 1.8f * u, 0, sw, cArgb)
    addCircle(14.5f * u, 14.5f * u, 1.8f * u, 0, sw, cArgb)
    addLine(10.8f * u, 13.2f * u, 15f * u, 8f * u, sw, cArgb, capRound = true)
    addLine(13.2f * u, 13.2f * u, 9f * u, 8f * u, sw, cArgb, capRound = true)
}

/** The pill's dropdown triangle (8 dp canvas in build 249). */
private fun VectorCmdBuilder.glyphTriangleBody(w: Float) {
    val u = w / 12f
    addFilledPolygon(
        floatArrayOf(3f * u, 9f * u, 6f * u),
        floatArrayOf(4.5f * u, 4.5f * u, 8.5f * u),
        3, TextPrimary.toArgb()
    )
}

/** Arrow down into a tray (16 dp canvas in build 249), drawn in the Export button's content colour. */
private fun VectorCmdBuilder.glyphDownloadBody(w: Float, cArgb: Int) {
    val u = w / 20f
    val sw = 2.2f * u
    addLine(10f * u, 1.6f * u, 10f * u, 11.8f * u, sw, cArgb, capRound = true)
    addPolyline(
        floatArrayOf(5.6f * u, 10f * u, 14.4f * u),
        floatArrayOf(7.6f * u, 12f * u, 7.6f * u),
        3, false, sw, cArgb, capRound = true
    )
    addLine(1.6f * u, 12.5f * u, 1.6f * u, 16.2f * u, sw, cArgb, capRound = true)
    val twoThirds = 2f / 3f
    addCubic(
        1.6f * u, 16.2f * u,
        1.6f * u, (16.2f + twoThirds * 2.2f) * u,
        (3.8f - twoThirds * 2.2f) * u, 18.4f * u,
        3.8f * u, 18.4f * u, sw, cArgb, capRound = true
    )
    addLine(3.8f * u, 18.4f * u, 16.2f * u, 18.4f * u, sw, cArgb, capRound = true)
    addCubic(
        16.2f * u, 18.4f * u,
        (16.2f + twoThirds * 2.2f) * u, 18.4f * u,
        18.4f * u, (16.2f + twoThirds * 2.2f) * u,
        18.4f * u, 16.2f * u, sw, cArgb, capRound = true
    )
    addLine(18.4f * u, 16.2f * u, 18.4f * u, 12.5f * u, sw, cArgb, capRound = true)
}

/**
 * Header shown while a layer is selected (it replaces [EditorHeader]).
 *
 * Build 211 (Sarowar, with his Alight Motion reference): a single-row
 * dark SLATE bar ([AmSlateHeader]) filling the header slot edge to
 * edge, holding ONLY, left to right: a back arrow (deselect); the
 * layer's NAME alone — large white, single line, ellipsised, with the
 * reference's thin underline beneath it (tap = the rename dialog);
 * then duplicate, delete and the circled three-dots (the tools-panel
 * toggle, white ring while open). The multi-select header is the same
 * bar with back + the count only (no underline, no icons — the count
 * is not a field). Same slot height as [EditorHeader] — the canvas
 * never moves (CANVAS LAW).
 *
 * Build 250 (UI migration stage 3): the bar is drawn on ONE native GL
 * surface — positions from [ChromeGeometry.LayerHeader], the name
 * through the stage-2 atlas (ellipsized with the atlas paint, the
 * Android rule), the Material icons as atlas rasters, the chevron
 * ported command-for-command. Tap targets stay Compose with the
 * original tags/descriptions and press contract. The rename text
 * INPUT stays Compose/Android, as everywhere (settled law) — only
 * this bar's drawing migrated.
 */
@Composable
fun LayerHeader(
    height: Dp,
    name: String,
    onBack: () -> Unit,
    /** Name tap: rename the layer. Null = the name is not a rename affordance (multi-select header). */
    onRename: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Duplicate button (overlapping squares): duplicates the layer in place. */
    onDuplicate: (() -> Unit)? = null,
    /** Delete button (trash): deletes the layer (the caller's action carries its undo semantics). */
    onDelete: (() -> Unit)? = null,
    /** Circled three-dots button: toggles the selected layer's tools panel. */
    onToolsClick: (() -> Unit)? = null,
    /** Whether the tools panel is currently open (the dots button's ring state). */
    toolsPanelOpen: Boolean = false
) {
    val lg = ChromeGeometry.LayerHeader
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val renameable = onRename != null
    val iconZones = listOf(onDuplicate, onDelete, onToolsClick).count { it != null }

    val copyPainter = rememberVectorPainter(Icons.Default.ContentCopy)
    val deletePainter = rememberVectorPainter(Icons.Default.Delete)
    val morePainter = rememberVectorPainter(Icons.Default.MoreHoriz)

    val nameTextH = atlas.textHeightPx(UiLabelStyle.HEADER_NAME)
    val nameAscent = atlas.fontMetrics(UiLabelStyle.HEADER_NAME).ascent

    // Press scales: back, name, duplicate, delete, tools.
    val scales = remember { List(5) { Animatable(1f) } }
    val scaleValues = scales.map { it.value }
    val pad = UiLabelAtlas.PAD.toFloat()
    val zone = minOf(lg.ZONE_MAX_DP.dp, height)
    val columnHDp = (lg.columnHeightPx(nameTextH, density, renameable) / density).dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(AmSlateHeader)
            .testTag("layer_header")
    ) {
        // The bar is full-width, so the constraints give the surface
        // width up front: the name's ellipsized form (the Android rule,
        // over the atlas paint) is resolved against the same law the
        // draw uses.
        val slotWidthPx = maxWidth.value * density
        fun resolveAll(): LayerHeaderEntries {
            val copyPx = (lg.GLYPH_COPY_DP * density).roundToInt()
            val deletePx = (lg.GLYPH_DELETE_DP * density).roundToInt()
            val morePx = (lg.GLYPH_MORE_DP * density).roundToInt()
            val copy = if (onDuplicate != null) atlas.resolveIcon("lh:copy", copyPx, copyPx) { cv, x, y ->
                val b = rasterizePainterBitmap(copyPainter, copyPx, density)
                cv.drawBitmap(b, x, y, null)
                b.recycle()
            } else null
            val delete = if (onDelete != null) atlas.resolveIcon("lh:delete", deletePx, deletePx) { cv, x, y ->
                val b = rasterizePainterBitmap(deletePainter, deletePx, density)
                cv.drawBitmap(b, x, y, null)
                b.recycle()
            } else null
            val more = if (onToolsClick != null) atlas.resolveIcon("lh:more", morePx, morePx) { cv, x, y ->
                val b = rasterizePainterBitmap(morePainter, morePx, density)
                cv.drawBitmap(b, x, y, null)
                b.recycle()
            } else null
            val cap = lg.contentWidthPx(slotWidthPx, height.value * density, density, iconZones)
            val shown = ChromeGeometry.ellipsize(name, cap) { s ->
                atlas.measure(s, UiLabelStyle.HEADER_NAME)
            }
            val nameEntry = atlas.resolve(shown, UiLabelStyle.HEADER_NAME)
            return LayerHeaderEntries(nameEntry, shown, copy, delete, more)
        }
        var entries = resolveAll()
        if (atlas.overflowed) {
            atlas.reset()
            entries = resolveAll()
        }
        NativeVectorCanvas(
            modifier = Modifier.fillMaxSize(),
            clearArgb = 0,
            atlas = atlas,
            drawKey = listOf(
                entries, toolsPanelOpen, iconZones, scaleValues, atlas.generation
            ),
            buildCmds = { w, h, dd ->
                val zonePx = lg.zoneDp(h / dd) * dd
                val cy = h / 2f

                // Back chevron.
                run {
                    val left = lg.backLeftPx(dd)
                    setScaleAbout(left + zonePx / 2f, cy, scales[0].value)
                    setTranslate(
                        lg.glyphLeftPx(left, h, lg.GLYPH_BACK_DP, dd),
                        lg.glyphTopPx(h, lg.GLYPH_BACK_DP, dd)
                    )
                    glyphChevronBody(lg.GLYPH_BACK_DP * dd, lg.GLYPH_BACK_DP * dd, dd)
                    clearTransform()
                }

                // Name (+ underline when renameable).
                run {
                    val contentLeft = lg.contentLeftPx(dd, h)
                    val contentW = lg.contentWidthPx(w, h, dd, iconZones)
                    if (renameable) {
                        setScaleAbout(contentLeft + contentW / 2f, cy, scales[1].value)
                    }
                    entries.name?.let { e ->
                        val colTop = lg.columnTopPx(h, nameTextH, dd, renameable)
                        addText(
                            e.index, contentLeft + e.bearing - pad,
                            colTop - nameAscent - e.base,
                            0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                            tintArgb = TextPrimary.toArgb()
                        )
                        if (renameable) {
                            addRect(
                                contentLeft, lg.underlineTopPx(h, nameTextH, dd),
                                contentW, lg.UNDERLINE_H_DP * dd,
                                TextPrimary.copy(alpha = 0.45f).toArgb()
                            )
                        }
                    }
                    clearTransform()
                }

                // Trailing zones in duplicate / delete / tools order.
                var zoneIndex = 0
                if (onDuplicate != null) {
                    val left = lg.zoneLeftPx(w, h, dd, iconZones, zoneIndex++)
                    val cx = left + zonePx / 2f
                    setScaleAbout(cx, cy, scales[2].value)
                    entries.copy?.let { e ->
                        addText(
                            e.index,
                            lg.glyphLeftPx(left, h, lg.GLYPH_COPY_DP, dd) - pad,
                            lg.glyphTopPx(h, lg.GLYPH_COPY_DP, dd) - pad,
                            0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                            tintArgb = TextPrimary.toArgb()
                        )
                    }
                    clearTransform()
                }
                if (onDelete != null) {
                    val left = lg.zoneLeftPx(w, h, dd, iconZones, zoneIndex++)
                    val cx = left + zonePx / 2f
                    setScaleAbout(cx, cy, scales[3].value)
                    entries.delete?.let { e ->
                        addText(
                            e.index,
                            lg.glyphLeftPx(left, h, lg.GLYPH_DELETE_DP, dd) - pad,
                            lg.glyphTopPx(h, lg.GLYPH_DELETE_DP, dd) - pad,
                            0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                            tintArgb = TextPrimary.toArgb()
                        )
                    }
                    clearTransform()
                }
                if (onToolsClick != null) {
                    val left = lg.zoneLeftPx(w, h, dd, iconZones, zoneIndex)
                    val cx = left + zonePx / 2f
                    setScaleAbout(cx, cy, scales[4].value)
                    addCircle(
                        cx, cy, lg.toolsCircleRadiusPx(dd), 0,
                        lg.TOOLS_CIRCLE_STROKE_DP * dd,
                        if (toolsPanelOpen) TextPrimary.toArgb()
                        else TextPrimary.copy(alpha = 0.75f).toArgb()
                    )
                    entries.more?.let { e ->
                        addText(
                            e.index,
                            lg.glyphLeftPx(left, h, lg.GLYPH_MORE_DP, dd) - pad,
                            lg.glyphTopPx(h, lg.GLYPH_MORE_DP, dd) - pad,
                            0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                            tintArgb = TextPrimary.toArgb()
                        )
                    }
                    clearTransform()
                }
            }
        )

        // Tap targets: the build-249 Row layout, invisible.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChromePressBox(
                scales[0], 0.92f, onBack, Modifier.size(zone),
                tag = "layer_header_back", description = "Back to layers"
            )
            Box(
                Modifier
                    .weight(1f)
                    .padding(start = 2.dp, end = 8.dp)
                    .height(columnHDp)
            ) {
                if (onRename != null) {
                    ChromePressBox(
                        scales[1], 0.98f, onRename, Modifier.fillMaxSize(),
                        tag = "layer_header_name", description = "Rename layer"
                    )
                } else {
                    Box(Modifier.fillMaxSize().testTag("layer_header_name"))
                }
            }
            if (onDuplicate != null) {
                ChromePressBox(
                    scales[2], 0.92f, onDuplicate, Modifier.size(zone),
                    tag = "layer_header_duplicate", description = "Duplicate layer"
                )
            }
            if (onDelete != null) {
                ChromePressBox(
                    scales[3], 0.92f, onDelete, Modifier.size(zone),
                    tag = "layer_header_delete", description = "Delete layer"
                )
            }
            if (onToolsClick != null) {
                ChromePressBox(
                    scales[4], 0.92f, onToolsClick, Modifier.size(zone),
                    tag = "layer_header_tools",
                    description = if (toolsPanelOpen) "Close layer tools" else "Layer tools"
                )
            }
        }
    }
}

private data class LayerHeaderEntries(
    val name: UiLabelAtlas.Entry?,
    val shownName: String,
    val copy: UiLabelAtlas.Entry?,
    val delete: UiLabelAtlas.Entry?,
    val more: UiLabelAtlas.Entry?
)

/** The layer header's back chevron (13 dp canvas in build 249). */
private fun VectorCmdBuilder.glyphChevronBody(w: Float, h: Float, d: Float) {
    val sw = 2.2f * d
    val m = sw / 2f
    val cArgb = TextPrimary.toArgb()
    addLine(w - m, m, m, h / 2f, sw, cArgb, capRound = true)
    addLine(m, h / 2f, w - m, h - m, sw, cArgb, capRound = true)
}
