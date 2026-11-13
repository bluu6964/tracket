package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmCornerItem
import com.example.ui.theme.AmDanger
import com.example.ui.theme.AmHairline
import com.example.ui.theme.AmInk
import com.example.ui.theme.AmMint
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.IconActive
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.amGlassChipBrush
import com.example.ui.theme.amMintCyanBrush
import com.example.ui.util.bouncyClickable
import com.example.ui.util.neonCircleGlow

@Composable
fun BottomToolbar(
    height: Dp,
    onAddClick: () -> Unit,
    onNullClick: () -> Unit,
    onControllerClick: () -> Unit,
    onChainClick: () -> Unit,
    onCameraClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .drawBehind {
                // Hairline separating the footer from the timeline (redesign glass language).
                drawLine(AmHairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The add-layer "+" lives here since build 145 (Sarowar: in the bottom toolbar with
        // the other tools, not up by the timeline): it opens the same AddLayerSheet as before.
        // Same five items, same order — Stage 1 of the redesign only changes the look:
        // glass pills, and Add (the bar's primary action) carries the one gradient badge.
        ToolbarItem("Add", Icons.Default.Add, "toolbar_add_button", onAddClick, primary = true)
        ToolbarItem("Null", Icons.Default.CenterFocusWeak, "toolbar_null_button", onNullClick)
        ToolbarItem("Controller", Icons.Default.Tune, "toolbar_controller_button", onControllerClick)
        ToolbarItem("Chain", Icons.Default.ViewStream, "toolbar_chain_button", onChainClick)
        ToolbarItem("3D Camera", Icons.Default.Videocam, "toolbar_camera_button", onCameraClick)
    }
}

@Composable
private fun RowScope.ToolbarItem(
    label: String,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit,
    primary: Boolean = false
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .glassSurface(RoundedCornerShape(AmCornerItem), fill = amGlassChipBrush())
            .bouncyClickable(pressedScale = 0.94f, hoverScale = 1.015f, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (primary) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .neonCircleGlow(AmMint)
                    .background(amMintCyanBrush(), CircleShape)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, label, tint = AmInk, modifier = Modifier.size(21.dp))
            }
        } else {
            Box(
                modifier = Modifier.size(38.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, label, tint = IconActive, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = if (primary) TextPrimary else AmTextDim,
            fontSize = 10.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Shown while a Chain layer is selected: only ways to put layers INTO the chain (the chain has no picture of its own, so
 * no Effects / Transform / Animations here). A back arrow leaves the chain, the bin deletes it with the layers inside.
 */
@Composable
fun ChainAddPanel(
    height: Dp,
    onBack: () -> Unit,
    onMedia: () -> Unit,
    onText: () -> Unit,
    onShape: () -> Unit,
    onAudio: () -> Unit,
    onStickers: () -> Unit,
    onNull: () -> Unit,
    onController: () -> Unit,
    onCamera: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .padding(vertical = 5.dp)
            .testTag("chain_add_panel"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(start = 8.dp, end = 6.dp)
                .size(46.dp)
                .bouncyClickable(pressedScale = 0.88f, onClick = onBack)
                .testTag("chain_back_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Exit chain", tint = IconActive, modifier = Modifier.size(24.dp))
        }
        Box(Modifier.width(1.dp).height(32.dp).background(Color(0xFF262C38)))
        val chainListState = rememberLazyListState()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
        androidx.compose.foundation.lazy.LazyRow(
            state = chainListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item { ChainToolItem("Media", Icons.Default.AddPhotoAlternate, "chain_media", onMedia) }
            item { ChainToolItem("Text", Icons.Default.TextFields, "chain_text", onText) }
            item { ChainToolItem("Shape", Icons.Default.Category, "chain_shape", onShape) }
            item { ChainToolItem("Audio", Icons.Default.MusicNote, "chain_audio", onAudio) }
            item { ChainToolItem("Stickers", Icons.Default.SentimentSatisfiedAlt, "chain_stickers", onStickers) }
            item { ChainToolItem("Null", Icons.Default.CenterFocusWeak, "chain_null", onNull) }
            item { ChainToolItem("Controller", Icons.Default.Tune, "chain_controller", onController) }
            item { ChainToolItem("Camera", Icons.Default.Videocam, "chain_camera", onCamera) }
            item { ChainToolItem("Delete", Icons.Default.Delete, "chain_delete", onDelete) }
        }
        if (chainListState.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(30.dp)
                    .background(
                        Brush.horizontalGradient(listOf(Color.Transparent, EditorBackground))
                    )
            )
        }
        }
    }
}

/** A tool of [ChainAddPanel]: fixed width, so the row can scroll. Same glass pill as the toolbar's items. */
@Composable
private fun ChainToolItem(label: String, icon: ImageVector, testTag: String, onClick: () -> Unit) {
    val destructive = label == "Delete"
    Column(
        modifier = Modifier
            .width(62.dp)
            .fillMaxHeight()
            .glassSurface(RoundedCornerShape(AmCornerItem), fill = amGlassChipBrush())
            .bouncyClickable(pressedScale = 0.94f, hoverScale = 1.015f, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (destructive) AmDanger else IconActive, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = AmTextDim, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
