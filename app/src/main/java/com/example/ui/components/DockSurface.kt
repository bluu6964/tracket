package com.example.ui.components

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.engine.UiLabelAtlas
import com.example.engine.UiLabelStyle
import com.example.engine.VectorCmdBuilder
import com.example.engine.rememberUiAtlas
import com.example.model.DockGeometry
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.RefCtrl
import com.example.ui.theme.RefSurface
import com.example.ui.util.amChromeSpec
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Build 249 (UI migration stage 2): the add-layer dock's scrolling row as
 * ONE native GL surface. Through build 248 the row was twelve Compose
 * tiles, nine of them carrying their own glyph TextureView; now a single
 * [NativeVectorCanvas] draws every tile (background, border, glyph,
 * label) from [DockGeometry]'s law, the three Material-icon glyphs and
 * all twelve labels blit from the UI atlas, and a row of invisible
 * Compose boxes keeps the tap targets — testTags, semantics and the
 * bouncyClickable gesture contract (Initial-pass down, touch-slop and
 * consumed checks, click on a clean lift) — exactly where they were.
 * The press scale is the same 180 ms FastOutSlowIn tween to 0.94, now
 * applied as the command builder's scale-about-centre transform instead
 * of a graphicsLayer.
 *
 * Build 259: the media tile's play triangle and the fx tile's two stars
 * blit from the atlas too. As flat-batch filled polygons they were
 * painted over by the tiles' opaque SDF fills (the renderer flushes flat
 * before SDF) — on device the Fx tile was empty and the media tile showed
 * only its outline. The atlas batch flushes last, so blits always land
 * on top; the media outline (an SDF stroke) stays a vector builder.
 *
 * What did NOT change: the tile order, labels, tags and actions; the
 * horizontal scroll (the GL surface is the full content width (632 dp since build 257) and
 * scrolls with its container, like the Compose row did); the Chain
 * section — [ChainAddPanel] still replaces this row wholesale while a
 * Chain layer is selected, untouched.
 */
@Composable
internal fun DockGlRow(
    modifier: Modifier = Modifier,
    /** Build 276: while the effects stage is open, the Fx tile renders
     *  as the concept's active item — a filled accent disc, dark glyph. */
    fxActive: Boolean = false,
    onMediaClick: () -> Unit,
    onAudioClick: () -> Unit,
    onFxClick: () -> Unit,
    onTextClick: () -> Unit,
    onShapesClick: () -> Unit,
    onNullClick: () -> Unit,
    onStickerClick: () -> Unit,
    onVectorClick: () -> Unit,
    onDrawingClick: () -> Unit,
    onCameraClick: () -> Unit,
    onGroupClick: () -> Unit,
    onChainClick: () -> Unit,
    onSpaceClick: () -> Unit
) {
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val scrollState = rememberScrollState()

    val labels = DOCK_LABELS
    val tags = DOCK_TAGS
    // In DOCK_LABELS order (media, music, fx, text, shapes, sticker,
    // vector, drawing, null, camera, group, chain) — NOT parameter order.
    val clicks = listOf(
        onMediaClick, onAudioClick, onFxClick, onTextClick, onShapesClick, onStickerClick,
        onVectorClick, onDrawingClick, onNullClick, onCameraClick, onGroupClick, onChainClick,
        onSpaceClick
    )
    // The three Material-icon tiles (music, sticker, camera), in tile order.
    val iconPainters = listOf(
        rememberVectorPainter(Icons.Default.MusicNote),
        rememberVectorPainter(Icons.Default.SentimentSatisfiedAlt),
        rememberVectorPainter(Icons.Default.Videocam)
    )

    // Resolve every label + icon into the atlas BEFORE the snapshot is
    // built, so the build pass below only reads cached entries. Two-pass
    // on overflow (the TlLabelAtlas contract): reset and resolve again.
    fun resolveAll(): DockResolved {
        val labelEntries = labels.map { atlas.resolve(it, UiLabelStyle.DOCK) }
        val iconEntries = HashMap<Int, UiLabelAtlas.Entry?>()
        DOCK_ICON_TILES.forEachIndexed { n, tileIndex ->
            val iconPx = (DockGeometry.GLYPH_DP[tileIndex] * density).roundToInt()
            val painter = iconPainters[n]
            iconEntries[tileIndex] = atlas.resolveIcon("dock-icon:$tileIndex", iconPx, iconPx) { canvas, x, y ->
                val bmp = rasterizeIconBitmap(painter, iconPx, density)
                canvas.drawBitmap(bmp, x, y, null)
                bmp.recycle()
            }
        }
        // Build 259: the media tile's play triangle and the fx tile's star,
        // rasterized into the atlas like the icons (see DockGlyphs).
        val playPx = (DockGeometry.GLYPH_DP[0] * density).roundToInt()
        val playEntry = atlas.resolveIcon("dock-glyph:play", playPx, playPx) { canvas, x, y ->
            val bmp = rasterizePlayBitmap(playPx)
            canvas.drawBitmap(bmp, x, y, null)
            bmp.recycle()
        }
        val fxPx = (DockGeometry.GLYPH_DP[2] * density).roundToInt()
        val starPx = DockGeometry.fxStarEntryPx(fxPx.toFloat())
        val starEntry = atlas.resolveIcon("dock-glyph:star", starPx, starPx) { canvas, x, y ->
            val bmp = rasterizeStarBitmap(starPx)
            canvas.drawBitmap(bmp, x, y, null)
            bmp.recycle()
        }
        return DockResolved(labelEntries, iconEntries, playEntry, starEntry)
    }
    var resolved = resolveAll()
    if (atlas.overflowed) {
        atlas.reset()
        resolved = resolveAll()
    }
    val labelEntries = resolved.labels
    val iconEntries = resolved.icons
    val playEntry = resolved.play
    val starEntry = resolved.star

    val labelHPx = atlas.textHeightPx(UiLabelStyle.DOCK)
    val labelAscentPx = atlas.fontMetrics(UiLabelStyle.DOCK).ascent
    val columnHDp = (DockGeometry.columnHeightPx(labelHPx, density) / density).dp

    val scales = remember { List(DockGeometry.TILE_COUNT) { Animatable(1f) } }
    // Read in composition: each animation frame recomposes and reposts the
    // GL snapshot with the pressed tile's current scale (the ruler dial's
    // value read does the same).
    val scaleValues = scales.map { it.value }

    Box(modifier = modifier.horizontalScroll(scrollState)) {
        Box(
            Modifier
                .width(DockGeometry.contentWidthDp().dp)
                .fillMaxHeight()
        ) {
            NativeVectorCanvas(
                modifier = Modifier.fillMaxSize(),
                clearArgb = 0,
                atlas = atlas,
                drawKey = listOf(labelEntries, iconEntries, playEntry, starEntry, scaleValues, fxActive, atlas.generation),
                buildCmds = { _, h, d ->
                    buildDockCommands(
                        heightPx = h, density = d,
                        labelEntries = labelEntries, iconEntries = iconEntries,
                        playEntry = playEntry, starEntry = starEntry,
                        scaleValues = scaleValues, fxActive = fxActive,
                        labelHeightPx = labelHPx, labelAscentPx = labelAscentPx
                    )
                }
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = DockGeometry.PAD_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(DockGeometry.GAP_DP.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 0 until DockGeometry.TILE_COUNT) {
                    DockTouchTile(
                        tag = tags[i],
                        scale = scales[i],
                        onClick = clicks[i],
                        modifier = Modifier
                            .width(DockGeometry.TILE_DP.dp)
                            .height(columnHDp)
                    )
                }
            }
        }
    }
}

/** The dock's tiles, in the reference's order (build 218; build 262 adds the 3D Space tile at the end). */
private val DOCK_LABELS = listOf(
    "Video/Photo", "Music", "Fx", "Text", "Shapes", "Sticker",
    "Vector", "Drawing", "Null", "3D Camera", "Group", "Chain", "3D Space"
)
private val DOCK_TAGS = listOf(
    "toolbar_media_button", "toolbar_audio_button", "toolbar_fx_button",
    "toolbar_text_button", "toolbar_shapes_button", "toolbar_sticker_button",
    "toolbar_vector_button", "toolbar_drawing_button", "toolbar_null_button",
    "toolbar_camera_button", "toolbar_group_button", "toolbar_chain_button",
    "toolbar_space_button"
)

/** Tile indices whose glyph is a Material icon (music, sticker, camera). */
private val DOCK_ICON_TILES = listOf(1, 5, 9)

/** Build 259: the atlas-blitted dock glyphs' ink colours (their build-248 values). */
private val PLAY_INK_ARGB = Color(0xFFD4D4D8).toArgb()
private val FX_STAR_SMALL_ARGB = Color(0xFF71717A).toArgb()
private val FX_STAR_BIG_ARGB = Color(0xFFF4F4F5).toArgb()

/** Build 259: everything [DockGlRow] resolves into the atlas, in one holder. */
private class DockResolved(
    val labels: List<UiLabelAtlas.Entry?>,
    val icons: Map<Int, UiLabelAtlas.Entry?>,
    val play: UiLabelAtlas.Entry?,
    val star: UiLabelAtlas.Entry?
)

/**
 * Blit atlas entry [e] so its INK box lands at ([inkLeft], [inkTop]) with
 * edge [inkSize]: the entry rect (ink + PAD) scales by inkSize / inkW and
 * the pad offset scales with it. The builder's press-scale transform
 * applies on top, exactly as it does for the icon blits.
 */
private fun VectorCmdBuilder.blitInk(
    e: UiLabelAtlas.Entry, inkLeft: Float, inkTop: Float, inkSize: Float, tintArgb: Int
) {
    val pad = UiLabelAtlas.PAD.toFloat()
    val scale = if (e.inkW > 0f) inkSize / e.inkW else 1f
    addText(
        entry = e.index,
        dstX = inkLeft - pad * scale, dstY = inkTop - pad * scale,
        srcDX = 0f, srcDY = 0f,
        srcW = e.rectW.toFloat(), srcH = e.rectH.toFloat(),
        dstW = e.rectW * scale, dstH = e.rectH * scale,
        tintArgb = tintArgb
    )
}

/** Draw the whole dock (all twelve tiles) into this builder. */
private fun VectorCmdBuilder.buildDockCommands(
    heightPx: Float,
    density: Float,
    labelEntries: List<UiLabelAtlas.Entry?>,
    iconEntries: Map<Int, UiLabelAtlas.Entry?>,
    playEntry: UiLabelAtlas.Entry?,
    starEntry: UiLabelAtlas.Entry?,
    scaleValues: List<Float>,
    fxActive: Boolean,
    labelHeightPx: Float,
    labelAscentPx: Float
) {
    val tileFillArgb = RefSurface.toArgb()
    // Build 276: the active Fx tile's filled accent disc + dark ink.
    val tileActiveFillArgb = 0xFF18D8E0.toInt()
    val tileActiveInkArgb = 0xFF111114.toInt()
    val tileBorderArgb = Color.White.copy(alpha = 0.04f).toArgb()
    val labelArgb = AmTextDim.toArgb()
    val iconTintArgb = RefCtrl.toArgb()
    val padPx = UiLabelAtlas.PAD.toFloat()
    val colTop = DockGeometry.columnTopPx(heightPx, labelHeightPx, density)
    val borderPx = DockGeometry.BORDER_DP * density
    val tilePx = DockGeometry.TILE_DP * density

    for (i in 0 until DockGeometry.TILE_COUNT) {
        val tileLeft = DockGeometry.tileLeftPx(i, density)
        setScaleAbout(
            DockGeometry.pressCenterXPx(tileLeft, density),
            DockGeometry.pressCenterYPx(colTop, labelHeightPx, density),
            scaleValues[i]
        )
        // Tile box: the SDF stroke is centred on the primitive's edge, so
        // the box is inset by half the border width to put the 1 dp border
        // fully inside the tile box, where Compose's border modifier drew it.
        addRoundRect(
            cx = tileLeft + tilePx / 2f,
            cy = colTop + tilePx / 2f,
            width = tilePx - borderPx,
            height = tilePx - borderPx,
            cornerRadius = DockGeometry.CORNER_DP * density - borderPx / 2f,
            fillArgb = if (i == 2 && fxActive) tileActiveFillArgb else tileFillArgb,
            strokeWidth = borderPx,
            strokeArgb = tileBorderArgb
        )
        // Glyph: a vector builder in its centred box, or an atlas blit.
        val glyphDp = DockGeometry.GLYPH_DP[i]
        val glyphLeft = DockGeometry.glyphLeftPx(tileLeft, glyphDp, density)
        val glyphTop = DockGeometry.glyphTopPx(colTop, glyphDp, density)
        val gw = glyphDp * density
        val icon = iconEntries[i]
        if (icon != null) {
            addText(
                entry = icon.index,
                dstX = glyphLeft - padPx, dstY = glyphTop - padPx,
                srcDX = 0f, srcDY = 0f,
                srcW = icon.rectW.toFloat(), srcH = icon.rectH.toFloat(),
                tintArgb = iconTintArgb
            )
        } else if (i == 0) {
            // Build 259: the outline stays a vector builder (SDF stroke);
            // the play triangle is an atlas blit — as a flat-batch polygon
            // the tile's opaque SDF fill painted over it (see DockGlyphs).
            setTranslate(glyphLeft, glyphTop)
            dockMediaGlyph(gw)
            setTranslate(0f, 0f)
            playEntry?.let { blitInk(it, glyphLeft, glyphTop, gw, PLAY_INK_ARGB) }
        } else if (i == 2) {
            // Build 259: both fx sparkles as atlas blits (same cover-up;
            // the tile rendered completely empty). Centres are square
            // (c, c) in the 25-unit grid — see DockGeometry's fx law.
            starEntry?.let { e ->
                val (sl, st, ss) = DockGeometry.fxStarBlit(
                    glyphLeft, glyphTop, gw,
                    DockGeometry.FX_STAR_SMALL_C, DockGeometry.FX_STAR_SMALL_C,
                    DockGeometry.FX_STAR_SMALL_R
                )
                blitInk(e, sl, st, ss, if (fxActive) tileActiveInkArgb else FX_STAR_SMALL_ARGB)
                val (bl, bt, bs) = DockGeometry.fxStarBlit(
                    glyphLeft, glyphTop, gw,
                    DockGeometry.FX_STAR_BIG_C, DockGeometry.FX_STAR_BIG_C,
                    DockGeometry.FX_STAR_BIG_R
                )
                blitInk(e, bl, bt, bs, if (fxActive) tileActiveInkArgb else FX_STAR_BIG_ARGB)
            }
        } else {
            setTranslate(glyphLeft, glyphTop)
            when (i) {
                3 -> dockTextGlyph(gw)
                4 -> dockShapesGlyph(gw)
                6 -> dockVectorGlyph(gw)
                7 -> dockDrawingGlyph(gw)
                8 -> dockNullGlyph(gw)
                10 -> dockGroupGlyph(gw)
                11 -> dockChainGlyph(gw)
                12 -> dockSpaceGlyph(gw)
            }
            setTranslate(0f, 0f)
        }
        // Label: the atlas blit with build 257's auto-fit — the FULL
        // rect is always drawn; an over-wide label shrinks to fit its
        // tile instead of clipping mid-word.
        labelEntries[i]?.let { e ->
            val fit = DockGeometry.labelFitScale(e.advance, density)
            val (rectLeft, dstW, dstH) = DockGeometry.labelBlit(
                tileLeftPx = tileLeft, advancePx = e.advance, bearingPx = e.bearing,
                padPx = padPx, rectWPx = e.rectW.toFloat(), rectHPx = e.rectH.toFloat(),
                density = density
            )
            addText(
                entry = e.index,
                dstX = rectLeft,
                dstY = DockGeometry.labelBaselinePx(colTop, density, labelAscentPx) - e.base * fit,
                srcDX = 0f, srcDY = 0f,
                srcW = e.rectW.toFloat(), srcH = e.rectH.toFloat(),
                dstW = dstW, dstH = dstH,
                tintArgb = if (i == 2 && fxActive) tileActiveFillArgb else labelArgb
            )
        }
        clearTransform()
    }
}

/**
 * One tile's invisible tap target: bouncyClickable's gesture contract
 * mirrored line for line (Initial-pass down, slop + consumed checks,
 * click on a clean lift, semantics onClick for TalkBack/tests), except
 * the press Animatable is shared with the GL draw instead of driving a
 * graphicsLayer — the tile itself is pixels on the dock surface now.
 */
@Composable
private fun DockTouchTile(
    tag: String,
    scale: Animatable<Float, AnimationVector1D>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val latestOnClick by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .semantics(mergeDescendants = false) {
                onClick(action = { latestOnClick(); true })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    scope.launch {
                        scale.animateTo(0.94f, amChromeSpec())
                    }
                    var clicked = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        if (!change.pressed) {
                            change.consume()
                            clicked = true
                            break
                        }
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    }
                    scope.launch {
                        scale.animateTo(1f, amChromeSpec())
                    }
                    if (clicked) latestOnClick()
                }
            }
            .testTag(tag)
    )
}

/**
 * Build 259: the media tile's play triangle as a standalone bitmap (the
 * glyph box's size; white fill — the atlas shader reads only the alpha
 * coverage, the blit tint supplies the colour). The same 24-unit points
 * the old flat-batch polygon used: (10.2, 9.3) / (15.2, 12) / (10.2, 14.7).
 */
private fun rasterizePlayBitmap(sizePx: Int): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    val u = sizePx / 24f
    val path = android.graphics.Path()
    path.moveTo(10.2f * u, 9.3f * u)
    path.lineTo(15.2f * u, 12f * u)
    path.lineTo(10.2f * u, 14.7f * u)
    path.close()
    canvas.drawPath(path, paint)
    return bmp
}

/** Build 259: the fx star ([DOCK_STAR_PTS], unit radius) filling its square bitmap. */
private fun rasterizeStarBitmap(sizePx: Int): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    val path = android.graphics.Path()
    DOCK_STAR_PTS.forEachIndexed { i, pt ->
        val px = (pt.first * 0.5f + 0.5f) * sizePx
        val py = (pt.second * 0.5f + 0.5f) * sizePx
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    canvas.drawPath(path, paint)
    return bmp
}

/**
 * Rasterize a Material icon painter into a standalone bitmap (its own
 * size, transparent background). The vector's paths carry the shape in
 * their alpha; the atlas tint supplies the colour at blit time.
 */
private fun rasterizeIconBitmap(painter: VectorPainter, sizePx: Int, density: Float): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    CanvasDrawScope().draw(
        density = Density(density),
        layoutDirection = LayoutDirection.Ltr,
        canvas = ComposeCanvas(bmp.asImageBitmap()),
        size = Size(sizePx.toFloat(), sizePx.toFloat())
    ) {
        with(painter) { draw(Size(sizePx.toFloat(), sizePx.toFloat())) }
    }
    // A fully transparent result means the painter draw failed — surface it
    // in the log instead of shipping a silently blank tile glyph.
    var anyAlpha = false
    val row = IntArray(sizePx)
    for (y in 0 until sizePx) {
        bmp.getPixels(row, 0, sizePx, 0, y, sizePx, 1)
        if (row.any { (it ushr 24) != 0 }) {
            anyAlpha = true
            break
        }
    }
    if (!anyAlpha) Log.w("DockGlRow", "icon rasterization produced a blank bitmap (${sizePx}px)")
    return bmp
}
