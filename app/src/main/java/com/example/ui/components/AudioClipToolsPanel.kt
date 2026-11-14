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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TrendingDown
import com.example.ui.theme.AmHairline
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.IconActive
import com.example.ui.theme.TextPrimary
import com.example.ui.util.bouncyClickable

/** Tools shown while an AUDIO layer is selected: Split, Volume, Fade, Delete, Speed, Reverse, Duplicate. */
@Composable
fun AudioClipToolsPanel(
    height: Dp,
    onDeselect: () -> Unit,
    onVolume: () -> Unit,
    onFade: () -> Unit,
    onSpeed: () -> Unit,
    onReverse: () -> Unit,
    modifier: Modifier = Modifier,
    /** Auto Captions: speech of this audio layer to text layers (whisper.cpp). */
    onCaptions: (() -> Unit)? = null,
    /** Detect beats: scans this layer's sound and drops beat marks on the ruler (build 193). */
    onDetectBeats: (() -> Unit)? = null,
    /** Ducking: this layer's volume lowers itself under another layer's loudness (build 194). */
    onDucking: (() -> Unit)? = null,
    /** Noise reduction: this layer's steady background noise is gated down (build 195). */
    onNoiseReduction: (() -> Unit)? = null,
    /** Rename the layer. */
    onRename: (() -> Unit)? = null
) {
    val tools = listOf(
        ClipToolItem("volume", "Volume", Icons.AutoMirrored.Filled.VolumeUp),
        ClipToolItem("fade", "Fade", Icons.Default.Tonality),
        ClipToolItem("speed", "Speed", Icons.Default.Speed),
        ClipToolItem("reverse", "Reverse", Icons.Default.FastRewind)
    ) + (if (onDetectBeats != null) listOf(ClipToolItem("beats", "Beats", Icons.Default.MusicNote))
    else emptyList()) + (if (onDucking != null) listOf(ClipToolItem("ducking", "Ducking", Icons.Default.TrendingDown))
    else emptyList()) + (if (onNoiseReduction != null) listOf(ClipToolItem("noise", "Noise", Icons.Default.GraphicEq))
    else emptyList()) + (if (onCaptions != null) listOf(ClipToolItem("captions", "Captions", Icons.Default.ClosedCaption))
    else emptyList()) + (if (onRename != null) listOf(ClipToolItem("rename", "Rename", Icons.Default.Edit))
    else emptyList())

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .drawBehind {
                // Hairline separating the tools row from the timeline (B/W language).
                drawLine(AmHairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            }
            .padding(vertical = 4.dp)
            .testTag("audio_clip_tools_panel"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(start = 8.dp, end = 6.dp)
                .size(46.dp)
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
                .background(AmHairline)
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
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .bouncyClickable(
                            pressedScale = 0.88f,
                            hoverScale = 1.08f,
                            onClick = {
                                when (tool.id) {
                                    "volume" -> onVolume()
                                    "fade" -> onFade()
                                    "speed" -> onSpeed()
                                    "reverse" -> onReverse()
                                    "beats" -> onDetectBeats?.invoke()
                                    "ducking" -> onDucking?.invoke()
                                    "noise" -> onNoiseReduction?.invoke()
                                    "captions" -> onCaptions?.invoke()
                                    "rename" -> onRename?.invoke()
                                }
                            }
                        )
                        .testTag("audio_tool_${tool.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = tool.label,
                        tint = IconActive,
                        modifier = Modifier.size(23.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = tool.label,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1
                    )
                    }
                }
            }
        }
    }
}
