package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmPanel
import com.example.ui.theme.HeaderCyan
import com.example.ui.theme.HeaderText
import com.example.ui.theme.TextSecondary
import com.example.ui.util.bouncyClickable

/**
 * The layer colour-label picker (build 199, A2), in the marker dialog's language (square
 * dialog on AmPanel, bare text actions). The colour it sets is the timeline label colour
 * itself ([com.example.model.TimelineClip.color] — the same value the timeline bars render),
 * NOT a second colour model: the palette is a set of label colours plus "Default", which
 * restores the colour the layer type is created with (model.defaultClipColor).
 */
@Composable
fun LayerLabelDialog(
    /** The layer's current label colour (packed ARGB, as stored on the layer). */
    currentColor: Long,
    /** The colour this layer type is created with — the Default choice. */
    defaultColor: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmPanel,
        shape = RectangleShape,
        title = { Text("Layer label", color = HeaderText) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LABEL_PALETTE.take(6).forEach { c -> Swatch(c, currentColor, onPick) }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    LABEL_PALETTE.drop(6).forEach { c -> Swatch(c, currentColor, onPick) }
                }
                Text(
                    text = "The label colour tints the layer's timeline bar.",
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(defaultColor) }) {
                Text("Default", color = HeaderCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

/** One palette swatch: a flat square of the colour; the current one carries a white edge. */
@Composable
private fun Swatch(colorValue: Long, currentColor: Long, onPick: (Long) -> Unit) {
    val selected = colorValue == currentColor
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(Color(colorValue), RectangleShape)
            .then(
                if (selected) Modifier.border(2.dp, Color.White, RectangleShape) else Modifier
            )
            .bouncyClickable { onPick(colorValue) }
    )
}

/** Label colours offered beside Default: vivid, mutually distinct, readable as bar tints. */
private val LABEL_PALETTE: List<Long> = listOf(
    0xFFE53935, // red
    0xFFFB8C00, // orange
    0xFFFDD835, // yellow
    0xFF43A047, // green
    0xFF00ACC1, // teal-blue
    0xFF1E88E5, // blue
    0xFF8E24AA, // purple
    0xFFD81B60, // pink
    0xFF6D4C41, // brown
    0xFF757575, // grey
    0xFF2A4365, // video navy (a type default)
    0xFF3992CE  // text blue (a type default)
)
