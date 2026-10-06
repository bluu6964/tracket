package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.EditorSurfaceVariant
import com.example.ui.theme.IconActive
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.util.bouncyClickable

@Composable
fun BottomToolbar(
    height: Dp,
    onAudioClick: () -> Unit,
    onTextClick: () -> Unit,
    onShapeClick: () -> Unit,
    onTransformClick: () -> Unit = {},
    onOverlayClick: () -> Unit,
    onStickersClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarItem("Audio", Icons.Default.MusicNote, "toolbar_audio_button", onAudioClick)
        ToolbarItem("Text", Icons.Default.TextFields, "toolbar_text_button", onTextClick)
        ToolbarItem("Shape", Icons.Default.Category, "toolbar_shape_button", onShapeClick)
        ToolbarItem("Transform", Icons.Default.OpenWith, "toolbar_transform_button", onTransformClick)
        ToolbarItem("Overlay", Icons.Default.AddPhotoAlternate, "toolbar_overlay_button", onOverlayClick)
        ToolbarItem("Stickers", Icons.Default.SentimentSatisfiedAlt, "toolbar_stickers_button", onStickersClick)
    }
}

@Composable
private fun ToolbarItem(
    label: String,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clipRounded()
            .bouncyClickable(pressedScale = 0.94f, hoverScale = 1.015f, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(EditorSurfaceVariant.copy(alpha = 0.82f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.055f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = IconActive, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = TextSecondary.copy(alpha = 0.95f), fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private fun Modifier.clipRounded() = this.background(Color.Transparent, RoundedCornerShape(14.dp))
