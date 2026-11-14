package com.example.ui.components
import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmCyan
import com.example.ui.theme.AmPanel
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.TextPrimary
import com.example.ui.util.bouncyClickable

/** Opened by the round "+" button and the add-layer icon: what kind of layer to add. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLayerSheet(
    onMedia: () -> Unit,
    onText: () -> Unit,
    onShape: () -> Unit,
    onAudio: () -> Unit,
    onSticker: () -> Unit,
    /** Ready-made compositions (build 143): opens the Templates picker. */
    onTemplates: () -> Unit = {},
    /** Freehand drawing (build 166): draw a stroke that becomes a shape layer. */
    onDraw: () -> Unit = {},
    /** Point-based Pen (build 177): tap places anchor points, drag pulls bezier handles — a vector path layer. */
    onPen: () -> Unit = {},
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AmPanel, shape = RectangleShape) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Add layer", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Item("Media", Icons.Default.AddPhotoAlternate, "add_sheet_media") { onDismiss(); onMedia() }
                Item("Text", Icons.Default.TextFields, "add_sheet_text") { onDismiss(); onText() }
                Item("Shape", Icons.Default.Category, "add_sheet_shape") { onDismiss(); onShape() }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Item("Audio", Icons.Default.MusicNote, "add_sheet_audio") { onDismiss(); onAudio() }
                Item("Stickers", Icons.Default.SentimentSatisfiedAlt, "add_sheet_sticker") { onDismiss(); onSticker() }
                Item("Templates", Icons.Default.Layers, "add_sheet_templates") { onDismiss(); onTemplates() }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Item("Draw", Icons.Default.Brush, "add_sheet_draw") { onDismiss(); onDraw() }
                Item("Pen", Icons.Default.Polyline, "add_sheet_pen") { onDismiss(); onPen() }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RowScope.Item(label: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .bouncyClickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        // BARE since the boxless order (build 183): icon + label only, no tile.
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, label, tint = AmCyan, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, color = AmTextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
