package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmControlLabel
import com.example.ui.theme.AmHairline
import com.example.ui.theme.AmHairlineStrong
import com.example.ui.theme.AmMint
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.AmTopSheen
import com.example.ui.theme.AmViolet
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.amCyanVioletBrush
import com.example.ui.theme.amGlassBrush
import com.example.ui.theme.amMintCyanBrush
import com.example.ui.util.bouncyClickable

/**
 * Shared glass components of the redesign — every surface consumes the token layer
 * in [com.example.ui.theme.DesignTokens]. Since build 162 the RICH surfaces are
 * hybrid: program-rendered kit assets (see KitAssets.kt — glossy glass bases and
 * volumetric glow, which flat Compose brushes could not reach and Sarowar rejected
 * as "AI-looking") under code-drawn text / icons / interaction. Wide code-drawn
 * surfaces still use [glassSurface], now with a dome light + a bottom inner line.
 * Restraint rule: at most ONE glowing element per panel.
 */

/**
 * The glass surface itself, as a modifier so existing button chains keep their shape:
 * gradient fill → hairline border → clip → light overlay over whatever is drawn
 * inside (a radial dome light from above centre, the top sheen band, and a dark
 * bottom inner line — the three cues that keep a wide flat fill reading as glass).
 * [borderBrush] (e.g. the primary gradient) replaces [borderColor].
 */
fun Modifier.glassSurface(
    shape: Shape,
    fill: Brush = amGlassBrush(),
    borderColor: Color = AmHairline,
    borderBrush: Brush? = null,
    borderWidth: Dp = 1.dp,
    sheen: Boolean = true
): Modifier {
    var m = this.background(fill, shape)
    m = if (borderBrush != null) m.border(borderWidth, borderBrush, shape)
        else m.border(borderWidth, borderColor, shape)
    m = m.clip(shape)
    if (sheen) {
        m = m.drawWithContent {
            drawContent()
            // dome light: a soft radial wash falling from above the top edge
            drawRect(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width / 2f, -size.height * 0.4f),
                    radius = size.width * 0.75f
                ),
                size = size
            )
            val sheenH = 12.dp.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(AmTopSheen, Color.Transparent),
                    startY = 0f,
                    endY = sheenH
                ),
                size = Size(size.width, sheenH)
            )
            // bottom inner line: the glass thickness cue at the base
            val y = size.height - 1.dp.toPx()
            drawLine(
                Color.Black.copy(alpha = 0.22f),
                Offset(size.width * 0.08f, y),
                Offset(size.width * 0.92f, y),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
    return m
}

/** Round 44 dp glass icon button: the kit's glossy disc under the glyph. */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
    glowColor: Color? = null,
    content: @Composable () -> Unit
) {
    var m = modifier.size(44.dp)
        .semantics { contentDescription = description }
        .bouncyClickable(enabled = enabled, onClick = onClick)
    if (tag != null) m = m.testTag(tag)
    Box(m, contentAlignment = Alignment.Center) {
        if (glowColor != null) {
            KitGlowRing(
                Modifier.size(58.dp),
                violet = glowColor == AmViolet,
                alpha = 0.85f
            )
        }
        KitGlassDisc(Modifier.fillMaxSize())
        content()
    }
}

/**
 * The primary action: the kit's mint glow pill behind a mint→cyan gradient fill.
 * Content is laid out in a Row; [contentPadding] sits inside the click target.
 * ([glowColor] is retained for call-site compatibility; the kit's glow is mint.)
 */
@Composable
fun GradientPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    glowColor: Color = AmMint,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp),
    content: @Composable RowScope.() -> Unit
) {
    var m = modifier
    if (tag != null) m = m.testTag(tag)
    Box(m, contentAlignment = Alignment.Center) {
        KitGlowPill(Modifier.matchParentSize(), alpha = 0.95f)
        Row(
            Modifier
                .background(amMintCyanBrush(), CircleShape)
                .clip(CircleShape)
                .bouncyClickable(pressedScale = 0.94f, onClick = onClick)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * Glass chip / tab: the kit's glass pill under the label. Selected = cyan→violet
 * gradient hairline (the brief's secondary gradient, strokes only) + brighter
 * label; unselected = plain hairline + dim label. A null [onClick] renders static.
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
        KitGlassPill(Modifier.matchParentSize())
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (selected) Modifier.border(1.dp, amCyanVioletBrush(), CircleShape)
                    else Modifier.border(1.dp, AmHairline, CircleShape)
                )
        ) {}
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
