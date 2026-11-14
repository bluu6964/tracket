package com.example.ui.components
import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmControlLabel
import com.example.ui.theme.AmCornerItem
import com.example.ui.theme.AmGlassFill
import com.example.ui.theme.AmHairline
import com.example.ui.theme.AmPanelHigh
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.amCyanVioletBrush
import com.example.ui.theme.amGlassBrush
import com.example.ui.util.bouncyClickable

/**
 * Shared components of the settled BLACK & WHITE design language (Sarowar's order
 * 2026-11-13 ~20:30 — CapCut-style simple black/white; see theme/DesignTokens.kt).
 * Everything here is FLAT: solid fills, one hairline border, no gradient, no glass
 * dome/sheen, no glow, no image assets. (Builds 161/163 drew these surfaces from a
 * program-rendered PNG kit + gradient brushes; that machinery is deleted — the kit
 * files and KitAssets.kt are gone, and the replacements below are plain Compose.)
 * Emphasis is white vs grey only: the primary action is a flat white pill.
 */

/**
 * The flat surface itself, as a modifier so existing button chains keep their shape:
 * flat fill → hairline border → clip. (The [sheen] parameter is retained for
 * call-site compatibility and ignored — flat surfaces have no sheen.)
 */
fun Modifier.glassSurface(
    shape: Shape,
    fill: Brush = amGlassBrush(),
    borderColor: Color = AmHairline,
    borderBrush: Brush? = null,
    borderWidth: Dp = 1.dp,
    sheen: Boolean = false
): Modifier {
    var m = this.background(fill, shape)
    m = if (borderBrush != null) m.border(borderWidth, borderBrush, shape)
        else m.border(borderWidth, borderColor, shape)
    m = m.clip(shape)
    return m
}

/** Flat round base (44 dp icon buttons, discs): solid raised grey + hairline. */
@Composable
fun FlatDisc(modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(AmPanelHigh, CircleShape)
            .border(1.dp, AmHairline, CircleShape)
            .clip(CircleShape)
    )
}

/** Flat square surface base (content surfaces that are NOT buttons — the fx-row
 *  icon badge, timeline trim handles): solid fill + hairline. Toolbar/tool-panel
 *  buttons stopped using it under Sarowar's boxless order 2026-11-14 (build 183). */
@Composable
fun FlatTile(modifier: Modifier = Modifier) {
    val shape = RectangleShape
    Box(
        modifier
            .background(AmGlassFill, shape)
            .border(1.dp, AmHairline, shape)
            .clip(shape)
    )
}

/** Flat square base (chips, lane pills, readouts): solid raised grey + hairline.
 *  Was a capsule (CircleShape) until Sarowar's square order 2026-11-14 — chips
 *  and pills are chrome, so they are square now like every other chrome shape. */
@Composable
fun FlatPill(modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(AmPanelHigh, RectangleShape)
            .border(1.dp, AmHairline, RectangleShape)
            .clip(RectangleShape)
    )
}

/** Flat white slider/playhead thumb: a plain white disc with a hairline dark edge. */
fun DrawScope.drawFlatThumb(center: Offset, radiusPx: Float) {
    drawCircle(Color.White, radius = radiusPx, center = center)
    drawCircle(
        Color.Black.copy(alpha = 0.35f),
        radius = radiusPx,
        center = center,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
    )
}

/** Round 44 dp icon button — BARE since Sarowar's boxless order 2026-11-14 (build 183):
 *  no disc under the glyph; the 44 dp touch target and the press scale stay. */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
    content: @Composable () -> Unit
) {
    var m = modifier.size(44.dp)
        .semantics { contentDescription = description }
        .bouncyClickable(enabled = enabled, onClick = onClick)
    if (tag != null) m = m.testTag(tag)
    Box(m, contentAlignment = Alignment.Center) {
        content()
    }
}

/**
 * The primary action button — BARE since Sarowar's boxless order 2026-11-14
 * (build 183: "পুরো box ই remove করে ফেলতে" — remove the whole box, CapCut-style).
 * It was a flat white square button until this build; now it renders ONLY its
 * content (icon + label) with the press scale as feedback. Content colours come
 * from the call site and are WHITE now (the white fill that carried dark ink is
 * gone). The name is kept so call sites read as before. Touch geometry is
 * unchanged: same padding, same row.
 */
@Composable
fun GradientPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp),
    content: @Composable RowScope.() -> Unit
) {
    var m = modifier
    if (tag != null) m = m.testTag(tag)
    Box(m, contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .bouncyClickable(pressedScale = 0.94f, onClick = onClick)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * Chip / tab. A CLICKABLE chip is BARE since Sarowar's boxless order 2026-11-14
 * (build 183): no pill, no border — the label alone carries the state (selected =
 * bright bold, unselected = dim). A null [onClick] renders a STATIC display chip
 * and keeps its flat pill surface (it is a readout, not a button).
 */
@Composable
fun GlassChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tag: String? = null
) {
    var m = modifier
    if (onClick != null) m = m.bouncyClickable(pressedScale = 0.95f, onClick = onClick)
    if (tag != null) m = m.testTag(tag)
    Box(m, contentAlignment = Alignment.Center) {
        if (onClick == null) {
            FlatPill(Modifier.matchParentSize())
            Box(
                Modifier
                    .matchParentSize()
                    .then(
                        if (selected) Modifier.border(1.dp, amCyanVioletBrush(), RectangleShape)
                        else Modifier.border(1.dp, AmHairline, RectangleShape)
                    )
            ) {}
        }
        Text(
            text,
            color = if (selected) TextPrimary else AmTextDim,
            fontSize = AmControlLabel.fontSize,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            letterSpacing = AmControlLabel.letterSpacing,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}
