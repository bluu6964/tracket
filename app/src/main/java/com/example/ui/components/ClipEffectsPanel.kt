package com.example.ui.components
import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.BlurLinear
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.FilterTiltShift
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.InvertColorsOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MonochromePhotos
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PanoramaFishEye
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.WbIncandescent
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.FxDef
import com.example.model.FxLists
import com.example.model.FxTileArt
import com.example.model.TimelineClip
import com.example.ui.theme.AmDanger
import com.example.ui.theme.AmHairlineStrong
import com.example.ui.theme.AmPanelHigh
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.util.bouncyClickable
import kotlin.math.roundToInt

/** The browser's three standing chips, ahead of the library categories. */
private const val FX_CHIP_ALL = "All"
private const val FX_CHIP_FAVORITES = "Favorites"
private const val FX_CHIP_RECENT = "Recent"

private enum class FxPanelMode { APPLIED, BROWSER, EDITOR }

/**
 * Effects panel — build 200 rebuilds its layout after Sarowar's reference video
 * (EFFECTS-LAYOUT-SPEC.md), in three states over the layer's effect stack:
 *
 * 1. **Applied Effects** (home): a wide "Add effect" button, then the applied
 *    effects as full-width rows — expand chevron + name on the left, eye
 *    (enable/disable) and a drag grip on the right; dragging the grip reorders
 *    the stack. Tapping a row opens its parameter editor. Empty state = the
 *    reference's helper text.
 * 2. **Effect browser** ("Add effect"): category chips (All / Favorites /
 *    Recent / the [ClipFxLibrary] categories; selected = white fill, black
 *    text) over a 4-column grid of procedural artwork tiles. Tapping a tile
 *    APPLIES the effect and selects the tile (white border + sliders badge);
 *    tapping the badge — or re-tapping the selected tile — opens the applied
 *    instance's parameter editor. While a tile is selected the screen shows
 *    the bookmark / info buttons at the preview's bottom-left (the panel
 *    reports the selection through [onBrowserSelectionChange]).
 * 3. **Parameter editor**: one ruler row per parameter — the shared
 *    [RulerDialSlider] with the reference's one borrowed accent, a magenta
 *    needle — plus the per-effect Reset and a Remove action.
 *
 * Nothing about the effect MODEL changed: every edit rides the existing
 * [onChange] funnel into `setClipEffects` (one undo step for the whole panel
 * session, pushed by the screen's beginAdjust; Controller fan-out included),
 * the 32-effect cap and its honest limit state stay, and effect values past
 * the declared params (Motion Blur's flags, keyframed tails) are preserved
 * verbatim by the slider writes, exactly as before.
 */
@Composable
fun ClipEffectsPanel(
    clip: TimelineClip?,
    onChange: (List<ClipFx>) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Chroma Key eyedropper: asks the screen to let the person move the ring over the picture. `onPicked` gets the colour
     * (0xAARRGGBB), `onCancel` is called when the pick is abandoned (back, panel closed, Cancel).
     */
    onPickColor: ((onPicked: (Long) -> Unit, onCancel: () -> Unit) -> Unit)? = null,
    /** Bookmarked effect ids (per-user, persisted by the screen; see [FxLists]). */
    favorites: List<String> = emptyList(),
    /** Most-recently-applied effect ids, newest first (persisted by the screen). */
    recent: List<String> = emptyList(),
    /** Records an application so the screen can push the id onto [recent]. */
    onApplied: (String) -> Unit = {},
    /** The screen's header title: "Applied Effects", or the edited effect's name in the editor. */
    onTitleChange: (String) -> Unit = {},
    /** The browser's selected effect id (null outside the browser) — drives the preview's bookmark / info buttons. */
    onBrowserSelectionChange: (String?) -> Unit = {}
) {
    if (clip == null) return
    val effects = clip.effects
    // The pick finishes later, so it must see the effect list of THEN, not the one of the moment of the tap.
    val latestEffects by rememberUpdatedState(effects)
    var mode by remember(clip.id) { mutableStateOf(FxPanelMode.APPLIED) }
    var chip by remember(clip.id) { mutableStateOf(FX_CHIP_ALL) }
    var browserSelDefId by remember(clip.id) { mutableStateOf<String?>(null) }
    var lastAppliedInstance by remember(clip.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var editingId by remember(clip.id) { mutableStateOf<String?>(null) }
    val full = effects.size >= ClipFxLibrary.MAX_EFFECTS

    val editingFx = effects.firstOrNull { it.id == editingId }
    val editingDef = editingFx?.let { ClipFxLibrary.def(it.type) }

    LaunchedEffect(mode, editingDef?.name) {
        onTitleChange(
            if (mode == FxPanelMode.EDITOR && editingDef != null) editingDef.name else "Applied Effects"
        )
    }
    LaunchedEffect(mode, browserSelDefId) {
        onBrowserSelectionChange(if (mode == FxPanelMode.BROWSER) browserSelDefId else null)
    }
    // The edited effect vanished (undo, remove): fall back to the applied list.
    LaunchedEffect(effects, mode) {
        if (mode == FxPanelMode.EDITOR && effects.none { it.id == editingId }) {
            editingId = null
            mode = FxPanelMode.APPLIED
        }
    }

    fun applyDef(def: FxDef) {
        if (latestEffects.size >= ClipFxLibrary.MAX_EFFECTS) return
        val fx = ClipFxLibrary.create(def.id) ?: return
        lastAppliedInstance = lastAppliedInstance + (def.id to fx.id)
        browserSelDefId = def.id
        onApplied(def.id)
        onChange(latestEffects + fx)
    }

    fun openEditorForInstance(fxId: String) {
        editingId = fxId
        mode = FxPanelMode.EDITOR
    }

    fun openEditorForDef(defId: String) {
        val inst = lastAppliedInstance[defId]?.let { id -> latestEffects.firstOrNull { it.id == id } }
            ?: latestEffects.lastOrNull { it.type == defId }
        if (inst != null) openEditorForInstance(inst.id)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("clip_effects_panel")
    ) {
        when (mode) {
            FxPanelMode.APPLIED -> {
                // ---- Add effect: the wide button; at the cap it turns into the honest limit state ----
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(if (full) AmPanelHigh.copy(alpha = 0.45f) else AmPanelHigh)
                        .then(
                            if (full) Modifier
                            else Modifier.bouncyClickable(onClick = { mode = FxPanelMode.BROWSER })
                        )
                        .testTag("fx_add_effect"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (full) "Effect limit reached" else "Add effect",
                        color = if (full) AmTextDim else TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${effects.size} / ${ClipFxLibrary.MAX_EFFECTS}",
                        color = AmTextDim,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 14.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (effects.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "See applied effects here, reorder them by dragging, and control their parameters",
                            color = AmTextDim,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // ---- Applied rows with grip drag-reorder (writes the same list funnel) ----
                    var draggingId by remember(clip.id) { mutableStateOf<String?>(null) }
                    var dragAccum by remember(clip.id) { mutableFloatStateOf(0f) }
                    val rowStepPx = with(LocalDensity.current) { 58.dp.toPx() } // 52 dp row + 6 dp gap
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scrollState, enabled = draggingId == null),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (fx in effects) {
                            val def = ClipFxLibrary.def(fx.type) ?: continue
                            AppliedFxRow(
                                def = def,
                                fx = fx,
                                isDragging = fx.id == draggingId,
                                dragOffsetPx = if (fx.id == draggingId) dragAccum else 0f,
                                onOpen = { openEditorForInstance(fx.id) },
                                onToggleEnabled = {
                                    onChange(latestEffects.map {
                                        if (it.id == fx.id) it.copy(enabled = !it.enabled) else it
                                    })
                                },
                                onDragStart = {
                                    draggingId = fx.id
                                    dragAccum = 0f
                                },
                                onDrag = { dy ->
                                    val list = latestEffects
                                    val idx = list.indexOfFirst { it.id == fx.id }
                                    if (idx >= 0) {
                                        dragAccum += dy
                                        if (dragAccum > rowStepPx && idx < list.lastIndex) {
                                            val next = list.toMutableList()
                                            next[idx] = list[idx + 1]
                                            next[idx + 1] = list[idx]
                                            onChange(next)
                                            dragAccum -= rowStepPx
                                        } else if (dragAccum < -rowStepPx && idx > 0) {
                                            val next = list.toMutableList()
                                            next[idx] = list[idx - 1]
                                            next[idx - 1] = list[idx]
                                            onChange(next)
                                            dragAccum += rowStepPx
                                        }
                                    }
                                },
                                onDragEnd = {
                                    draggingId = null
                                    dragAccum = 0f
                                }
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            FxPanelMode.BROWSER -> {
                // ---- Header row: collapse back to the applied list ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .bouncyClickable(onClick = { mode = FxPanelMode.APPLIED })
                            .padding(vertical = 6.dp, horizontal = 4.dp)
                            .testTag("fx_browser_back"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Back to applied effects",
                            tint = TextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Add effect", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    if (full) {
                        Text(
                            "Limit ${ClipFxLibrary.MAX_EFFECTS} reached",
                            color = AmTextDim,
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // ---- Category chips: selected = white fill / black text ----
                val chips = listOf(FX_CHIP_ALL, FX_CHIP_FAVORITES, FX_CHIP_RECENT) + ClipFxLibrary.categories
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (c in chips) {
                        val on = c == chip
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .background(if (on) Color.White else AmPanelHigh)
                                .bouncyClickable(onClick = { chip = c })
                                .testTag("fx_category_$c"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                c,
                                color = if (on) Color.Black else TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val defs = when (chip) {
                    FX_CHIP_ALL -> ClipFxLibrary.all
                    FX_CHIP_FAVORITES -> FxLists.favoriteDefs(ClipFxLibrary.all, favorites)
                    FX_CHIP_RECENT -> FxLists.recentDefs(ClipFxLibrary.all, recent)
                    else -> ClipFxLibrary.all.filter { it.category == chip }
                }
                if (defs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            when (chip) {
                                FX_CHIP_FAVORITES ->
                                    "No favorites yet — tap an effect to select it, then the bookmark button by the preview to keep it here."
                                FX_CHIP_RECENT ->
                                    "Nothing applied yet — effects you add will appear here, newest first."
                                else -> "No effects in this category."
                            },
                            color = AmTextDim,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(defs, key = { it.id }) { def ->
                            val selected = def.id == browserSelDefId
                            FxGridTile(
                                def = def,
                                selected = selected,
                                dimmed = full && !selected,
                                onClick = {
                                    if (selected) openEditorForDef(def.id) else applyDef(def)
                                },
                                onBadgeClick = { openEditorForDef(def.id) }
                            )
                        }
                    }
                }
            }

            FxPanelMode.EDITOR -> {
                val fx = editingFx
                val def = editingDef
                if (fx == null || def == null) {
                    // The fallback effect above switches back to APPLIED; render nothing meanwhile.
                    Box(Modifier.fillMaxSize())
                } else {
                    // ---- Back row + Remove ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .bouncyClickable(onClick = { mode = FxPanelMode.APPLIED })
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                                .testTag("fx_editor_back"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowLeft,
                                contentDescription = "Back to applied effects",
                                tint = TextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Applied Effects", color = AmTextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.weight(1f))
                        Row(
                            modifier = Modifier
                                .bouncyClickable(onClick = {
                                    onChange(latestEffects.filter { it.id != fx.id })
                                    editingId = null
                                    mode = FxPanelMode.APPLIED
                                })
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                                .testTag("fx_remove_${fx.type}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove effect", tint = AmDanger, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Remove", color = AmDanger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (fx.type == ClipFxLibrary.CHROMA_KEY && onPickColor != null) {
                            KeyPickerRow(
                                swatch = Color(
                                    ClipFxLibrary.keySwatchArgb(
                                        fx.values.getOrNull(0) ?: def.params[0].default,
                                        fx.values.getOrNull(1) ?: def.params[1].default,
                                        fx.values.getOrNull(5) ?: def.params[5].default
                                    ).toInt()
                                ),
                                onPick = {
                                    val id = fx.id
                                    val wasOn = fx.enabled
                                    // Show the picture without the key while picking, or the screen would already be see-through.
                                    onChange(latestEffects.map { if (it.id == id) it.copy(enabled = false) else it })
                                    onPickColor(
                                        { argb ->
                                            val key = ClipFxLibrary.keyFromRgb(argb)
                                            onChange(latestEffects.map {
                                                if (it.id != id) it else {
                                                    val vals = it.values.toMutableList()
                                                    while (vals.size < def.params.size) vals.add(def.params[vals.size].default)
                                                    vals[0] = key[0]
                                                    vals[1] = key[1]
                                                    vals[5] = key[2]
                                                    vals[6] = key[3]
                                                    it.copy(enabled = true, values = vals)
                                                }
                                            })
                                        },
                                        {
                                            onChange(latestEffects.map { if (it.id == id) it.copy(enabled = wasOn) else it })
                                        }
                                    )
                                }
                            )
                        }
                        def.params.forEachIndexed { idx, p ->
                            val value = (fx.values.getOrNull(idx) ?: p.default).coerceIn(p.min, p.max)
                            RulerDialSlider(
                                label = p.name,
                                labelWidth = 104.dp,
                                value = value,
                                onValueChange = { v ->
                                    // Keep values past the declared params (Motion Blur stores its
                                    // usePos / useScale / useAngle flags there); rebuilding from the
                                    // params alone would silently drop them.
                                    val vals = (0 until maxOf(def.params.size, fx.values.size)).map { i ->
                                        if (i == idx) v else (fx.values.getOrNull(i) ?: def.params.getOrNull(i)?.default ?: 0f)
                                    }
                                    onChange(effects.map { if (it.id == fx.id) it.copy(values = vals) else it })
                                },
                                min = p.min,
                                max = p.max,
                                unit = p.unit,
                                isPercentage = false,
                                step = p.step,
                                accentNeedle = true,
                                modifier = Modifier.testTag("fx_slider_${fx.type}_$idx")
                            )
                        }
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .bouncyClickable(onClick = {
                                    val vals = def.params.map { it.default } + fx.values.drop(def.params.size)
                                    onChange(effects.map { if (it.id == fx.id) it.copy(values = vals) else it })
                                })
                                .testTag("fx_reset_${fx.type}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Reset", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

/** One applied-effect row: chevron + name (tap = parameter editor), eye, drag grip. */
@Composable
private fun AppliedFxRow(
    def: FxDef,
    fx: ClipFx,
    isDragging: Boolean,
    dragOffsetPx: Float,
    onOpen: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .offset { IntOffset(0, dragOffsetPx.roundToInt()) }
            .zIndex(if (isDragging) 1f else 0f)
            .background(AmPanelHigh)
            .then(
                if (isDragging) Modifier.border(1.dp, AmHairlineStrong, RectangleShape)
                else Modifier
            )
            .testTag("fx_row_${fx.type}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .bouncyClickable(pressedScale = 0.99f, onClick = onOpen)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                def.name,
                color = if (fx.enabled) TextPrimary else TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!fx.enabled) {
                Spacer(Modifier.width(8.dp))
                Text("Off", color = TextSecondary, fontSize = 11.sp)
            }
        }
        Icon(
            if (fx.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = if (fx.enabled) "Turn off" else "Turn on",
            tint = if (fx.enabled) TextPrimary else TextSecondary,
            modifier = Modifier
                .size(44.dp)
                .bouncyClickable(onClick = onToggleEnabled)
                .padding(11.dp)
                .testTag("fx_toggle_${fx.type}")
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .pointerInput(fx.id) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { change, amount ->
                            change.consume()
                            onDrag(amount.y)
                        }
                    )
                }
                .testTag("fx_grip_${fx.type}"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Drag to reorder",
                tint = TextSecondary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** One browser tile: procedural artwork + name; the selected tile gets the white border + sliders badge. */
@Composable
private fun FxGridTile(
    def: FxDef,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    onBadgeClick: () -> Unit
) {
    val tileShape = RoundedCornerShape(10.dp) // the ARTWORK's corners (content, as in the reference); chrome stays square
    Column(
        modifier = Modifier
            .bouncyClickable(onClick = onClick)
            .testTag("fx_tile_${def.id}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(tileShape)
                .then(if (selected) Modifier.border(2.dp, Color.White, tileShape) else Modifier)
        ) {
            FxTileArtwork(def = def, modifier = Modifier.fillMaxSize())
            if (dimmed) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(22.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .bouncyClickable(onClick = onBadgeClick)
                        .testTag("fx_badge_${def.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "Edit parameters", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            def.name,
            color = if (dimmed) TextSecondary else TextPrimary,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Category hues (deep-space family bases) for the procedural tile artwork. */
private data class FxPalette(val deepHue: Float, val glowHue: Float)

private fun fxPalette(category: String): FxPalette = when (category) {
    "Blur" -> FxPalette(258f, 285f)
    "Color" -> FxPalette(318f, 18f)
    "Light" -> FxPalette(38f, 52f)
    "Lens" -> FxPalette(205f, 225f)
    "Distort" -> FxPalette(278f, 145f)
    "Stylize" -> FxPalette(332f, 190f)
    "Texture" -> FxPalette(14f, 350f)
    "Keying" -> FxPalette(150f, 172f)
    "Backdrop" -> FxPalette(232f, 308f)
    else -> FxPalette(245f, 200f)
}

/**
 * A tile's artwork, drawn procedurally (zero assets, zero APK growth): a deep-space
 * gradient field in the category's hue family, three soft nebula blobs and a star
 * field placed by the effect's stable seed ([FxTileArt]), the category motif —
 * the effect's own library icon — in white at the centre. The per-effect hue
 * shift (±24°) keeps tiles of one category distinguishable, never blank or grey.
 */
@Composable
private fun FxTileArtwork(def: FxDef, modifier: Modifier = Modifier) {
    val palette = fxPalette(def.category)
    val seed = remember(def.id) { FxTileArt.seed(def.id) }
    val shift = remember(def.id) { FxTileArt.hueShiftDeg(def.id) }
    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun hue(base: Float) = (base + shift + 360f) % 360f
            val deep = Color.hsv(hue(palette.deepHue), 0.82f, 0.13f)
            val mid = Color.hsv(hue(palette.glowHue), 0.72f, 0.34f)
            val glow = Color.hsv(hue(palette.glowHue + 24f), 0.88f, 0.92f)
            drawRect(brush = Brush.verticalGradient(listOf(mid, deep)))
            for (i in 0 until 3) {
                val cx = w * (0.15f + 0.7f * FxTileArt.unit(seed, i * 7 + 1))
                val cy = h * (0.15f + 0.7f * FxTileArt.unit(seed, i * 7 + 2))
                val r = size.minDimension * (0.30f + 0.35f * FxTileArt.unit(seed, i * 7 + 3))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.38f), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = r
                    ),
                    center = Offset(cx, cy),
                    radius = r
                )
            }
            for (i in 0 until 16) {
                val sx = w * FxTileArt.unit(seed, 100 + i * 3)
                val sy = h * FxTileArt.unit(seed, 101 + i * 3)
                val sr = (0.6f + FxTileArt.unit(seed, 102 + i * 3)) * 1.1.dp.toPx()
                drawCircle(
                    Color.White.copy(alpha = 0.30f + 0.45f * FxTileArt.unit(seed, 103 + i * 3)),
                    radius = sr,
                    center = Offset(sx, sy)
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                fxIcon(def.id), contentDescription = null,
                tint = Color.White, modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * The browser's info sheet (build 200): the selected effect's name, category and the
 * library's own one-line description ([FxDef.blurb] — written for every def), plus its
 * parameter set. In the marker dialog's language (square dialog on AmPanel, bare actions).
 */
@Composable
fun FxInfoDialog(def: FxDef, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.example.ui.theme.AmPanel,
        shape = RectangleShape,
        title = {
            Text(def.name, color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(def.category, color = AmTextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (def.blurb.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(def.blurb, color = TextPrimary, fontSize = 13.sp)
                }
                if (def.params.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Parameters: " + def.params.joinToString(", ") { it.name },
                        color = AmTextDim,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Close", color = TextPrimary)
            }
        },
        modifier = Modifier.testTag("fx_info_dialog")
    )
}

/** Chroma Key: the key colour as a swatch and the button that starts the ring eyedropper on the picture. */
@Composable
private fun KeyPickerRow(swatch: Color, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(swatch)
                .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                .testTag("fx_key_swatch")
        )
        Spacer(Modifier.width(12.dp))
        // BARE (boxless order, build 183): no white fill under the label any more.
        Row(
            modifier = Modifier
                .bouncyClickable(onClick = onPick)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("fx_key_pick"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Colorize, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text("Pick from picture", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun fxIcon(id: String): ImageVector = when (id) {
    ClipFxLibrary.BLUR -> Icons.Default.BlurOn
    ClipFxLibrary.GLOW -> Icons.Default.LightMode
    ClipFxLibrary.RGB_SPLIT -> Icons.Default.Layers
    ClipFxLibrary.VIGNETTE -> Icons.Default.Vignette
    ClipFxLibrary.GRAIN -> Icons.Default.Grain
    ClipFxLibrary.CHROMA_KEY -> Icons.Default.Palette
    ClipFxLibrary.DIR_BLUR -> Icons.Default.BlurLinear
    ClipFxLibrary.RADIAL_BLUR -> Icons.Default.BlurCircular
    ClipFxLibrary.SHARPEN -> Icons.Default.Details
    ClipFxLibrary.EXPOSURE -> Icons.Default.Exposure
    ClipFxLibrary.BRIGHT_CONTRAST -> Icons.Default.Contrast
    ClipFxLibrary.HUE_SAT -> Icons.Default.ColorLens
    ClipFxLibrary.LEVELS -> Icons.Default.Tune
    ClipFxLibrary.COLOR_BALANCE -> Icons.Default.InvertColors
    ClipFxLibrary.TINT -> Icons.Default.Gradient
    ClipFxLibrary.POSTERIZE -> Icons.Default.FilterBAndW
    ClipFxLibrary.WAVE -> Icons.Default.Waves
    ClipFxLibrary.TWIRL -> Icons.Default.Autorenew
    ClipFxLibrary.BULGE -> Icons.Default.ZoomIn
    ClipFxLibrary.MIRROR -> Icons.Default.Flip
    ClipFxLibrary.KALEIDOSCOPE -> Icons.Default.FilterVintage
    ClipFxLibrary.TURBULENT -> Icons.Default.Shuffle
    ClipFxLibrary.MOSAIC -> Icons.Default.GridOn
    ClipFxLibrary.HALFTONE -> Icons.Default.Texture
    ClipFxLibrary.SCANLINES -> Icons.Default.Tv
    ClipFxLibrary.GLITCH -> Icons.Default.BrokenImage
    ClipFxLibrary.INVERT -> Icons.Default.InvertColorsOff
    ClipFxLibrary.BW -> Icons.Default.MonochromePhotos
    ClipFxLibrary.THRESHOLD -> Icons.Default.Adjust
    ClipFxLibrary.TEMPERATURE -> Icons.Default.Thermostat
    ClipFxLibrary.SHADOW_HIGHLIGHT -> Icons.Default.Brightness6
    ClipFxLibrary.LEAVE_COLOR -> Icons.Default.Opacity
    ClipFxLibrary.CINEMATIC -> Icons.Default.Movie
    ClipFxLibrary.SOLARIZE -> Icons.Default.WbSunny
    ClipFxLibrary.LENS_BLUR -> Icons.Default.Lens
    ClipFxLibrary.TILT_SHIFT -> Icons.Default.FilterTiltShift
    ClipFxLibrary.SURFACE_BLUR -> Icons.Default.Face
    ClipFxLibrary.LIGHT_RAYS -> Icons.Default.WbIncandescent
    ClipFxLibrary.RIPPLE -> Icons.Default.PanoramaFishEye
    ClipFxLibrary.LENS_DISTORT -> Icons.Default.CenterFocusStrong
    ClipFxLibrary.POLAR -> Icons.Default.DonutLarge
    ClipFxLibrary.TILE -> Icons.Default.Apps
    ClipFxLibrary.SHAKE -> Icons.Default.Vibration
    ClipFxLibrary.EMBOSS -> Icons.Default.Terrain
    ClipFxLibrary.FIND_EDGES -> Icons.Default.CropFree
    ClipFxLibrary.CARTOON -> Icons.Default.Brush
    ClipFxLibrary.SKETCH -> Icons.Default.Edit
    ClipFxLibrary.OIL -> Icons.Default.FormatPaint
    ClipFxLibrary.STROBE -> Icons.Default.FlashOn
    ClipFxLibrary.VHS -> Icons.Default.Videocam
    ClipFxLibrary.OLD_FILM -> Icons.Default.MovieFilter
    ClipFxLibrary.LIGHT_LEAK -> Icons.Default.WbTwilight
    ClipFxLibrary.LENS_FLARE -> Icons.Default.Flare
    ClipFxLibrary.COPY_BG -> Icons.Default.ContentCopy
    else -> Icons.Default.AutoAwesome
}
