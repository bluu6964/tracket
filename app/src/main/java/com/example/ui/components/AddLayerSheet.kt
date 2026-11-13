package com.example.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
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
import com.example.ui.theme.EditorSurface
import com.example.ui.theme.EditorSurfaceVariant
import com.example.ui.theme.IconActive
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
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
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EditorSurface) {
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
        }
    }
}

@Composable
private fun RowScope.Item(label: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .background(EditorSurfaceVariant, RoundedCornerShape(14.dp))
            .bouncyClickable(onClick = onClick)
            .padding(vertical = 16.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = IconActive, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
