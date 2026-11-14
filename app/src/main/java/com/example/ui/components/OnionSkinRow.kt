package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.OnionSkin
import com.example.model.OnionSkinSettings
import com.example.ui.theme.AmBase
import com.example.ui.theme.IconDisabled
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.util.bouncyClickable
import kotlin.math.roundToInt

/**
 * The onion-skin settings row (build 190): shown under the play controls while the
 * ghosts are on. Ghost count per side (1–3, a bare − / + stepper) and the nearest
 * ghost's opacity (a slider; farther ghosts fade from it by the plan's falloff).
 * Everything bare in the settled B/W language — states read as white vs grey.
 * The step between ghosts is always one frame at the project rate, so there is
 * no step control here.
 */
@Composable
fun OnionSkinRow(
    settings: OnionSkinSettings,
    onCountChange: (Int) -> Unit,
    onOpacityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(AmBase)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Ghosts", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        StepperButton(
            label = "−",
            description = "Fewer ghost frames",
            tag = "onion_count_minus",
            enabled = settings.countPerSide > 1,
            onClick = { onCountChange(settings.countPerSide - 1) }
        )
        Text(
            text = settings.countPerSide.toString(),
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(18.dp)
        )
        StepperButton(
            label = "+",
            description = "More ghost frames",
            tag = "onion_count_plus",
            enabled = settings.countPerSide < OnionSkin.MAX_PER_SIDE,
            onClick = { onCountChange(settings.countPerSide + 1) }
        )
        Spacer(Modifier.width(18.dp))
        Text("Opacity", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Slider(
            value = settings.opacity,
            onValueChange = onOpacityChange,
            valueRange = OnionSkin.MIN_OPACITY..OnionSkin.MAX_OPACITY,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0xFF3A3A3A),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Ghost opacity" }
                .testTag("onion_opacity_slider")
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${(settings.opacity * 100f).roundToInt()}%",
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(34.dp)
        )
    }
}

/** A bare − / + stepper key: no box (build 183), grey when it can go no further. */
@Composable
private fun StepperButton(
    label: String,
    description: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .semantics { contentDescription = description }
            .bouncyClickable(enabled = enabled, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) TextPrimary else IconDisabled,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}
