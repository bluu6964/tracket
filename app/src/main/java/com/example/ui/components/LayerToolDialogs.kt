package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AnimDir
import com.example.model.AnimPresets
import com.example.ui.theme.EditorSurfaceVariant
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

/** Asks for a new name of a layer. */
@Composable
fun RenameLayerDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename layer") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(60) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Transition into a layer: pick the kind (the In animations of the Animations panel), the direction and the length.
 * [onApply] gets the preset id, the direction and the length in ms; [onRemove] takes the In animation away again.
 */
@Composable
fun TransitionDialog(
    onApply: (presetId: String, dir: AnimDir, durationMs: Long) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val presets = AnimPresets.ALL
    var selectedId by remember { mutableStateOf(presets.first().id) }
    var dir by remember { mutableStateOf(presets.first().defaultDir()) }
    var seconds by remember { mutableFloatStateOf(0.8f) }
    val selected = presets.firstOrNull { it.id == selectedId } ?: presets.first()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transition") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "This layer comes in over the layer before it, and the earlier layer goes out the same way. If the earlier layer ends " +
                        "just before this one starts, this layer moves so the two overlap for the length below.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                presets.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { p ->
                            Choice(
                                label = p.label,
                                isOn = p.id == selectedId,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    selectedId = p.id
                                    dir = if (dir in p.dirs) dir else p.defaultDir()
                                    seconds = (p.defaultMs / 1000f).coerceIn(0.2f, 2f)
                                }
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (selected.dirs.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Direction", fontSize = 12.sp, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        selected.dirs.forEach { d ->
                            Choice(
                                label = d.arrow + " " + d.label,
                                isOn = d == dir,
                                modifier = Modifier.weight(1f),
                                onClick = { dir = d }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Length: ${(seconds * 10f).roundToInt() / 10f} s", fontSize = 12.sp, color = TextSecondary)
                Slider(value = seconds, onValueChange = { seconds = it }, valueRange = 0.2f..2f)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(selected.id, dir, (seconds * 1000f).roundToInt().toLong()) }) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun Choice(label: String, isOn: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(EditorSurfaceVariant)
            .border(BorderStroke(if (isOn) 1.5.dp else 0.dp, if (isOn) NeonCyan else EditorSurfaceVariant), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, color = if (isOn) NeonCyan else TextPrimary, maxLines = 1)
    }
}
