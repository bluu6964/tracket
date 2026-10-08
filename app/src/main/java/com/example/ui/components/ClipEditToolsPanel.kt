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
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.ui.util.bouncyClickable

data class ClipToolItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val isDestructive: Boolean = false
)

@Composable
fun ClipEditToolsPanel(
    height: Dp,
    onDeselect: () -> Unit,
    onSplit: () -> Unit,
    onEffects: () -> Unit,
    onAnimations: () -> Unit,
    onCopy: () -> Unit,
    onReplace: () -> Unit,
    onColor: () -> Unit,
    onTransform: () -> Unit,
    onCrop: () -> Unit,
    onDelete: () -> Unit,
    onBlend: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
    modifier: Modifier = Modifier,
    /** Only set for text layers: adds an "Edit" tool that opens Text edit. */
    onEditText: (() -> Unit)? = null
) {
    val tools = (if (onEditText != null) listOf(ClipToolItem("edit_text", "Edit", Icons.Default.Edit))
    else emptyList()) + listOf(
        ClipToolItem("split", "Split", Icons.Default.ContentCut),
        ClipToolItem("effects", "Effects", Icons.Default.AutoAwesome),
        ClipToolItem("animations", "Animations", Icons.Default.Animation),
        ClipToolItem("copy", "Copy", Icons.Default.ContentCopy),
        ClipToolItem("replace", "Replace", Icons.Default.SwapHoriz),
        ClipToolItem("color", "Color", Icons.Default.Palette),
        ClipToolItem("transform", "Transform", Icons.Default.OpenWith),
        ClipToolItem("crop", "Crop", Icons.Default.Crop),
        ClipToolItem("delete", "Delete", Icons.Default.Delete, isDestructive = true),
        ClipToolItem("blend", "Blend", Icons.Default.Layers),
        ClipToolItem("flip_h", "Flip H", Icons.Default.Flip),
        ClipToolItem("flip_v", "Flip V", Icons.Default.FlipCameraAndroid)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .padding(vertical = 4.dp)
            .testTag("clip_edit_tools_panel"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Left Back Button (matching screenshot) to deselect clip and return to main toolbar
        Box(
            modifier = Modifier
                .padding(start = 8.dp, end = 6.dp)
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(EditorSurfaceVariant.copy(alpha = 0.9f))
                .bouncyClickable(
                    pressedScale = 0.88f,
                    hoverScale = 1.08f,
                    onClick = onDeselect
                )
                .testTag("clip_tools_back_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Exit Clip Edit",
                tint = IconActive,
                modifier = Modifier.size(24.dp)
            )
        }

        // Vertical divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(32.dp)
                .background(Color(0xFF262C38))
        )

        // 2. Horizontal Scrolling Tools Panel (matching screenshot)
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
                                    "edit_text" -> onEditText?.invoke()
                                    "split" -> onSplit()
                                    "effects" -> onEffects()
                                    "animations" -> onAnimations()
                                    "copy" -> onCopy()
                                    "replace" -> onReplace()
                                    "color" -> onColor()
                                    "transform" -> onTransform()
                                    "crop" -> onCrop()
                                    "delete" -> onDelete()
                                    "blend" -> onBlend()
                                    "flip_h" -> onFlipHorizontal()
                                    "flip_v" -> onFlipVertical()
                                }
                            }
                        )
                        .clip(RoundedCornerShape(13.dp))
                        .background(EditorSurfaceVariant.copy(alpha = 0.62f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("clip_tool_${tool.id}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val tint = if (tool.isDestructive) Color(0xFFFF5252) else IconActive
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = tool.label,
                        tint = tint,
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
