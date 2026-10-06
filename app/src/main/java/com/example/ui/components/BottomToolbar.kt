package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.IconActive
import com.example.ui.theme.TextPrimary
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
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarItem(
            label = "Audio",
            icon = Icons.Default.MusicNote,
            testTag = "toolbar_audio_button",
            onClick = onAudioClick
        )

        ToolbarItem(
            label = "Text",
            icon = Icons.Default.TextFields,
            testTag = "toolbar_text_button",
            onClick = onTextClick
        )

        ToolbarItem(
            label = "Shape",
            icon = Icons.Default.Category,
            testTag = "toolbar_shape_button",
            onClick = onShapeClick
        )

        ToolbarItem(
            label = "Transform",
            icon = Icons.Default.OpenWith,
            testTag = "toolbar_transform_button",
            onClick = onTransformClick
        )

        ToolbarItem(
            label = "Overlay",
            icon = Icons.Default.AddPhotoAlternate,
            testTag = "toolbar_overlay_button",
            onClick = onOverlayClick
        )

        ToolbarItem(
            label = "Stickers",
            icon = Icons.Default.SentimentSatisfiedAlt,
            testTag = "toolbar_stickers_button",
            onClick = onStickersClick
        )
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
            .fillMaxHeight()
            .bouncyClickable(
                pressedScale = 0.88f,
                hoverScale = 1.08f,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon with small plus badge overlay
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = IconActive,
                modifier = Modifier.size(22.dp)
            )

            // Plus badge at top-right
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .offset(x = 6.dp, y = (-6).dp)
                    .background(EditorBackground, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = IconActive,
                    modifier = Modifier.size(9.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = label,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal
        )
    }
}
