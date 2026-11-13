package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FilterTiltShift
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.InvertColorsOff
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.MonochromePhotos
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.PanoramaFishEye
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WbIncandescent
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.BlurLinear
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.FxDef
import com.example.model.TimelineClip
import com.example.ui.theme.AmCyan
import com.example.ui.theme.AmHairline
import com.example.ui.theme.AmInk
import com.example.ui.theme.AmMicroLabel
import com.example.ui.theme.AmPanelHigh
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.amCyanVioletBrush
import com.example.ui.theme.amGlassBrush
import com.example.ui.theme.amMintCyanBrush
import com.example.ui.util.bouncyClickable

/**
 * Effects panel (same place and look as Transform / Animations: the timescale and the selected layer stay visible
 * above it, the canvas shows the result live). A row of categories, a row of effects (tap = add to the layer), then
 * the list of effects of the layer: tap a card to open its sliders. The sliders are the same ruler sliders as in
 * Transform ([RulerDialSlider]: drag the ruler, one tick = one step, a swipe glides).
 * The effects themselves are drawn by the C++ engine ([ClipFxLibrary.toNative]), the panel only edits the list.
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
    onPickColor: ((onPicked: (Long) -> Unit, onCancel: () -> Unit) -> Unit)? = null
) {
    if (clip == null) return
    val effects = clip.effects
    // The pick finishes later, so it must see the effect list of THEN, not the one of the moment of the tap.
    val latestEffects by rememberUpdatedState(effects)
    var category by remember { mutableStateOf(ClipFxLibrary.categories.first()) }
    var selectedId by remember(clip.id) { mutableStateOf(effects.lastOrNull()?.id) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("clip_effects_panel")
    ) {
        // Categories (the chips are wider than a small phone, so the row scrolls sideways;
        // while more categories sit off-screen the right edge fades — the tool-row cue of batch 1)
        val categoryScroll = rememberScrollState()
        Box {
        Row(
            modifier = Modifier.horizontalScroll(categoryScroll),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (cat in ClipFxLibrary.categories) {
                val on = cat == category
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .bouncyClickable(onClick = { category = cat })
                        .testTag("fx_category_$cat"),
                    contentAlignment = Alignment.Center
                ) {
                    KitGlassPill(Modifier.matchParentSize())
                    Box(
                        Modifier
                            .matchParentSize()
                            .then(
                                if (on) Modifier.border(1.dp, amCyanVioletBrush(), RoundedCornerShape(16.dp))
                                else Modifier.border(1.dp, AmHairline, RoundedCornerShape(16.dp))
                            )
                    ) {}
                    Text(
                        cat,
                        color = if (on) TextPrimary else AmTextDim,
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
        if (categoryScroll.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(30.dp)
                    .background(
                        Brush.horizontalGradient(listOf(Color.Transparent, EditorBackground))
                    )
            )
        }
        }
        Spacer(Modifier.height(12.dp))

        // Effects of this category: tap = add
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(ClipFxLibrary.all.filter { it.category == category }) { def ->
                FxTile(def = def, full = effects.size >= ClipFxLibrary.MAX_EFFECTS, onClick = {
                    if (effects.size < ClipFxLibrary.MAX_EFFECTS) {
                        val fx = ClipFxLibrary.create(def.id)
                        if (fx != null) {
                            selectedId = fx.id
                            onChange(effects + fx)
                        }
                    }
                })
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Applied",
                color = AmTextDim, style = AmMicroLabel,
                modifier = Modifier.weight(1f)
            )
            if (effects.isNotEmpty()) {
                Text("${effects.size} / ${ClipFxLibrary.MAX_EFFECTS}", color = AmTextDim, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (effects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(RoundedCornerShape(14.dp), fill = amGlassBrush())
                    .panelSheenStrip()
                    .padding(vertical = 18.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Nothing yet. Tap an effect above to add it to this layer.",
                    color = AmTextDim, fontSize = 13.sp
                )
            }
        }
        for (fx in effects) {
            val def = ClipFxLibrary.def(fx.type) ?: continue
            val selected = fx.id == selectedId
            Column(
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .fillMaxWidth()
                    .glassSurface(
                        RoundedCornerShape(14.dp),
                        fill = amGlassBrush(),
                        borderColor = AmHairline,
                        borderBrush = if (selected) amCyanVioletBrush() else null,
                        borderWidth = if (selected) 1.5.dp else 1.dp
                    )
                    .testTag("fx_row_${fx.type}")
            ) {
                // Header: icon, name, eye, remove. Tap = open / close the sliders.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) AmPanelHigh.copy(alpha = 0.6f) else Color.Transparent)
                        .bouncyClickable(pressedScale = 0.99f, onClick = { selectedId = if (selected) null else fx.id })
                        .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(34.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        KitGlassTile(Modifier.matchParentSize())
                        Icon(
                            fxIcon(def.id), contentDescription = null,
                            tint = if (!fx.enabled) TextSecondary else if (selected) AmCyan else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            def.name,
                            color = if (!fx.enabled) TextSecondary else if (selected) AmCyan else TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (!fx.enabled) Text("Off", color = TextSecondary, fontSize = 11.sp)
                    }
                    Icon(
                        if (fx.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (fx.enabled) "Turn off" else "Turn on",
                        tint = if (fx.enabled) TextPrimary else TextSecondary,
                        modifier = Modifier
                            .size(40.dp)
                            .bouncyClickable(onClick = {
                                onChange(effects.map { if (it.id == fx.id) it.copy(enabled = !it.enabled) else it })
                            })
                            .padding(10.dp)
                            .testTag("fx_toggle_${fx.type}")
                    )
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove",
                        tint = TextPrimary,
                        modifier = Modifier
                            .size(40.dp)
                            .bouncyClickable(onClick = {
                                if (selectedId == fx.id) selectedId = null
                                onChange(effects.filter { it.id != fx.id })
                            })
                            .padding(10.dp)
                            .testTag("fx_remove_${fx.type}")
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(if (selected) 180f else 0f)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (selected) {
                    Column(modifier = Modifier.padding(start = 14.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)) {
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
                                labelWidth = 118.dp,
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
                            KitGlassPill(Modifier.matchParentSize())
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = AmCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Reset", color = AmCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
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
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(amMintCyanBrush())
                .bouncyClickable(onClick = onPick)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("fx_key_pick"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Colorize, contentDescription = null, tint = AmInk, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text("Pick from picture", color = AmInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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

@Composable
private fun FxTile(def: FxDef, full: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(84.dp)
            .bouncyClickable(onClick = onClick)
            .testTag("fx_tile_${def.id}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(64.dp)) {
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center
            ) {
                KitGlassTile(Modifier.matchParentSize())
                Icon(
                    fxIcon(def.id), contentDescription = def.name,
                    tint = if (full) TextSecondary else Color.White, modifier = Modifier.size(28.dp)
                )
            }
            // small "+" badge: tap = add
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .then(
                        if (full) Modifier.background(Color(0xFF3A4058))
                        else Modifier.background(amMintCyanBrush())
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = if (full) TextSecondary else AmInk, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            def.name,
            color = if (full) TextSecondary else TextPrimary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
