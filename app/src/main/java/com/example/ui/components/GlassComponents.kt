package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
import com.example.ui.theme.AmGlowAlpha
import com.example.ui.theme.AmGlowAlphaSoft
import com.example.ui.theme.AmGlowRadius
import com.example.ui.theme.AmHairline
import com.example.ui.theme.AmHairlineStrong
import com.example.ui.theme.AmMint
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.AmTopSheen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.amCyanVioletBrush
import com.example.ui.theme.amGlassBrush
import com.example.ui.theme.amGlassChipBrush
import com.example.ui.theme.amMintCyanBrush
import com.example.ui.util.bouncyClickable
import com.example.ui.util.neonCircleGlow
import com.example.ui.util.neonGlow

/**
 * Shared glass components of the redesign (Stage 1) — every surface consumes the
 * token layer in [com.example.ui.theme.DesignTokens]. Glass, per the Pinterest brief:
 * a translucent lifted fill + a 1 px hairline light border + an inner top highlight
 * (the sheen drawn over the content's top edge). Restraint rule: at most ONE glowing
 * element per panel — glow parameters exist only on the primary-action components.
 */

/**
 * The glass surface itself, as a modifier so existing button chains keep their shape:
 * gradient fill → hairline border → clip → top sheen over whatever is drawn inside.
 * [borderBrush] (e.g. the primary gradient for the play ring) replaces [borderColor].
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
            val sheenH = 12.dp.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(AmTopSheen, Color.Transparent),
                    startY = 0f,
                    endY = sheenH
                ),
                size = Size(size.width, sheenH)
            )
        }
    }
    return m
}

/** Round 44 dp glass icon button — the chrome's standard small button. */
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
    if (glowColor != null) m = m.neonCircleGlow(glowColor, alpha = AmGlowAlphaSoft)
    m = m.glassSurface(CircleShape, fill = amGlassChipBrush())
        .semantics { contentDescription = description }
        .bouncyClickable(enabled = enabled, onClick = onClick)
    if (tag != null) m = m.testTag(tag)
    Box(m, contentAlignment = Alignment.Center) { content() }
}

/**
 * The primary action: mint→cyan gradient fill with the panel's one soft glow.
 * Content is laid out in a Row; [contentPadding] sits inside the click target.
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
        .neonGlow(glowColor, radius = AmGlowRadius, corner = 20.dp, alpha = AmGlowAlpha)
        .background(amMintCyanBrush(), CircleShape)
        .clip(CircleShape)
        .bouncyClickable(pressedScale = 0.94f, onClick = onClick)
        .padding(contentPadding)
    if (tag != null) m = m.testTag(tag)
    Row(m, verticalAlignment = Alignment.CenterVertically, content = content)
}

/**
 * Glass chip / tab. Selected = cyan→violet gradient hairline (the brief's secondary
 * gradient, strokes only) + brighter label; unselected = plain glass + dim label.
 * A null [onClick] renders a static chip.
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
        .glassSurface(
            CircleShape,
            fill = amGlassChipBrush(),
            borderColor = if (selected) AmHairlineStrong else AmHairline,
            borderBrush = if (selected) amCyanVioletBrush() else null
        )
    if (onClick != null) m = m.bouncyClickable(pressedScale = 0.95f, onClick = onClick)
    if (tag != null) m = m.testTag(tag)
    Box(m.padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = if (selected) TextPrimary else AmTextDim,
            fontSize = AmControlLabel.fontSize,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            letterSpacing = AmControlLabel.letterSpacing,
            maxLines = 1
        )
    }
}
