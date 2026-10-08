package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.EditorSurfaceVariant
import com.example.ui.theme.IconActive
import com.example.ui.theme.TextPrimary
import com.example.ui.util.bouncyClickable

/** Tools shown while an AUDIO layer is selected: Split, Volume, Fade, Delete, Speed, Reverse, Duplicate. */
@Composable
fun AudioClipToolsPanel(
    height: Dp,
    onDeselect: () -> Unit,
    onSplit: () -> Unit,
    onVolume: () -> Unit,
    onFade: () -> Unit,
    onDelete: () -> Unit,
    onSpeed: () -> Unit,
    onReverse: () -> Unit,
    onDuplicate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tools = listOf(
        ClipToolItem("split", "Split", Icons.Default.ContentCut),
        ClipToolItem("volume", "Volume", Icons.AutoMirrored.Filled.VolumeUp),
        ClipToolItem("fade", "Fade", Icons.Default.Tonality),
        ClipToolItem("delete", "Delete", Icons.Default.Delete, isDestructive = true),
        ClipToolItem("speed", "Speed", Icons.Default.Speed),
        ClipToolItem("reverse", "Reverse", Icons.Default.FastRewind),
        ClipToolItem("duplicate", "Duplicate", Icons.Default.ContentCopy)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .padding(vertical = 4.dp)
            .testTag("audio_clip_tools_panel"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(start = 8.dp, end = 6.dp)
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(EditorSurfaceVariant.copy(alpha = 0.9f))
                .bouncyClickable(pressedScale = 0.88f, hoverScale = 1.08f, onClick = onDeselect)
                .testTag("audio_tools_back_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Exit audio edit",
                tint = IconActive,
                modifier = Modifier.size(24.dp)
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(32.dp)
                .background(Color(0xFF262C38))
        )

        LazyRow(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(tools) { tool ->
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .bouncyClickable(
                            pressedScale = 0.88f,
                            hoverScale = 1.08f,
                            onClick = {
                                when (tool.id) {
                                    "split" -> onSplit()
                                    "volume" -> onVolume()
                                    "fade" -> onFade()
                                    "delete" -> onDelete()
                                    "speed" -> onSpeed()
                                    "reverse" -> onReverse()
                                    "duplicate" -> onDuplicate()
                                }
                            }
                        )
                        .clip(RoundedCornerShape(13.dp))
                        .background(EditorSurfaceVariant.copy(alpha = 0.62f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("audio_tool_${tool.id}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = tool.label,
                        tint = if (tool.isDestructive) Color(0xFFFF5252) else IconActive,
                        modifier = Modifier.size(23.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = tool.label,
                        color = if (tool.isDestructive) Color(0xFFFF6E6E) else TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
