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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tonality
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
    onEffects: () -> Unit,
    onAnimations: () -> Unit,
    onColor: () -> Unit,
    onTransform: () -> Unit,
    onCrop: () -> Unit,
    onBlend: () -> Unit,
    modifier: Modifier = Modifier,
    /** Video layers with sound: volume (0-200 %) and mute of this layer. */
    onVolume: (() -> Unit)? = null,
    /** Cutout test (ML Kit): keeps the person, removes the background. */
    onCutout: (() -> Unit)? = null,
    /** Roto Brush (video layers only): brush an object, EdgeSAM follows it through the clip (After Effects style). */
    onRoto: (() -> Unit)? = null,
    /** Motion tracking (video layers only): follow an object with a circle. */
    onTrack: (() -> Unit)? = null,
    /** Remove (video layers only): covers a flaw on a flat surface with a clean patch (planar track, Mocha-style). */
    onRemove: (() -> Unit)? = null,
    /** Stabilize (video layers only): holds a flat surface still (planar track, Mocha-style). */
    onStabilize: (() -> Unit)? = null,
    /** Auto Captions (video layers): speech to text layers (whisper.cpp). */
    onCaptions: (() -> Unit)? = null,
    /** Face AI (video layers): face tracking tools, first one = blur on face / eyes / nose / lips. */
    onFaceAi: (() -> Unit)? = null,
    onFaceSwap: (() -> Unit)? = null,
    /** Masks (video / photo layers): shapes that hide parts of the layer. */
    onMasks: (() -> Unit)? = null,
    /** Only set for text layers: adds an "Edit" tool that opens Text edit. */
    onEditText: (() -> Unit)? = null,
    /** Parent: pick a Null object this layer follows. */
    onParent: (() -> Unit)? = null,
    /** Only set for a real Group layer: takes the layers out of the group again. */
    onUngroup: (() -> Unit)? = null,
    /** Only set for a real Group layer: goes inside it to edit its layers one by one (Edit Group). */
    onEditGroup: (() -> Unit)? = null,
    /** Rename the layer. */
    onRename: (() -> Unit)? = null,
    /** Video layers: freeze frame (holds the picture at the playhead). */
    onFreeze: (() -> Unit)? = null,
    /** Picture layers: a transition into this layer (fade / slide / zoom ... over the layer before it). */
    onTransition: (() -> Unit)? = null,
    /**
     * Null / 3D Camera layers have no picture, so only the tools that do something for them are shown (like the audio panel):
     * when set, tools whose id is not in this set are left out. null = every tool the layer type offers.
     */
    onlyTools: Set<String>? = null
) {
    val allTools = (if (onEditGroup != null) listOf(ClipToolItem("edit_group", "Edit group", Icons.Default.FolderOpen))
    else emptyList()) + (if (onEditText != null) listOf(ClipToolItem("edit_text", "Edit", Icons.Default.Edit))
    else emptyList()) + listOf(
        ClipToolItem("effects", "Effects", Icons.Default.AutoAwesome),
        ClipToolItem("animations", "Animations", Icons.Default.Animation),
        ClipToolItem("color", "Color", Icons.Default.Palette),
        ClipToolItem("transform", "Transform", Icons.Default.OpenWith),
        ClipToolItem("crop", "Crop", Icons.Default.Crop),
        ClipToolItem("blend", "Blend", Icons.Default.Layers)
    ) + (if (onVolume != null) listOf(ClipToolItem("volume", "Volume", Icons.AutoMirrored.Filled.VolumeUp))
    else emptyList()) + (if (onMasks != null) listOf(ClipToolItem("masks", "Masks", Icons.Default.Tonality))
    else emptyList()) + (if (onCutout != null) listOf(ClipToolItem("cutout", "Cutout", Icons.Default.Person))
    else emptyList()) + (if (onRoto != null) listOf(ClipToolItem("roto", "Roto", Icons.Default.Brush))
    else emptyList()) + (if (onTrack != null) listOf(ClipToolItem("track", "Track", Icons.Default.GpsFixed))
    else emptyList()) + (if (onRemove != null) listOf(ClipToolItem("remove", "Remove", Icons.Default.AutoFixHigh))
    else emptyList()) + (if (onStabilize != null) listOf(ClipToolItem("stabilize", "Stabilize", Icons.Default.CenterFocusStrong))
    else emptyList()) + (if (onCaptions != null) listOf(ClipToolItem("captions", "Captions", Icons.Default.ClosedCaption))
    else emptyList()) + (if (onFaceAi != null) listOf(ClipToolItem("face_ai", "Face AI", Icons.Default.Face))
    else emptyList()) + (if (onFaceSwap != null) listOf(ClipToolItem("face_swap", "Face swap", Icons.Default.SwapHoriz))
    else emptyList()) + (if (onParent != null) listOf(ClipToolItem("parent", "Parent", Icons.Default.AccountTree))
    else emptyList()) + (if (onUngroup != null) listOf(ClipToolItem("ungroup", "Ungroup", Icons.Default.LayersClear))
    else emptyList()) + (if (onTransition != null) listOf(ClipToolItem("transition", "Transition", Icons.Default.Shuffle))
    else emptyList()) + (if (onFreeze != null) listOf(ClipToolItem("freeze", "Freeze", Icons.Default.Image))
    else emptyList()) + (if (onRename != null) listOf(ClipToolItem("rename", "Rename", Icons.Default.Edit))
    else emptyList())
    val tools = if (onlyTools == null) allTools else allTools.filter { it.id in onlyTools }

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
                                    "effects" -> onEffects()
                                    "animations" -> onAnimations()
                                    "color" -> onColor()
                                    "transform" -> onTransform()
                                    "crop" -> onCrop()
                                    "blend" -> onBlend()
                                    "cutout" -> onCutout?.invoke()
                                    "roto" -> onRoto?.invoke()
                                    "track" -> onTrack?.invoke()
                                    "remove" -> onRemove?.invoke()
                                    "stabilize" -> onStabilize?.invoke()
                                    "captions" -> onCaptions?.invoke()
                                    "face_ai" -> onFaceAi?.invoke()
                                    "face_swap" -> onFaceSwap?.invoke()
                                    "volume" -> onVolume?.invoke()
                                    "masks" -> onMasks?.invoke()
                                    "parent" -> onParent?.invoke()
                                    "ungroup" -> onUngroup?.invoke()
                                    "edit_group" -> onEditGroup?.invoke()
                                    "transition" -> onTransition?.invoke()
                                    "freeze" -> onFreeze?.invoke()
                                    "rename" -> onRename?.invoke()
                                }
                            }
                        )
                        .clip(RoundedCornerShape(13.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("clip_tool_${tool.id}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val tint = if (tool.isDestructive) IconActive else IconActive
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = tool.label,
                        tint = tint,
                        modifier = Modifier.size(23.dp)
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = tool.label,
                        color = if (tool.isDestructive) TextPrimary else TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
