package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Upload
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
import com.example.model.AspectRatio
import com.example.ui.theme.EditorBackground
import com.example.ui.theme.IconActive
import com.example.ui.theme.TextPrimary
import com.example.ui.util.bouncyClickable

@Composable
fun EditorHeader(
    height: Dp,
    aspectRatio: AspectRatio,
    onBackClick: () -> Unit,
    onAspectRatioClick: () -> Unit,
    onExportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(EditorBackground)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Back button with spring animation
        Box(
            modifier = Modifier
                .size(44.dp)
                .bouncyClickable(onClick = onBackClick)
                .testTag("header_back_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = IconActive,
                modifier = Modifier.size(24.dp)
            )
        }

        // Center: Aspect Ratio selector button with spring bounce
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .bouncyClickable(
                    pressedScale = 0.93f,
                    hoverScale = 1.06f,
                    onClick = onAspectRatioClick
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("header_aspect_ratio_button"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Aspect ratio frame icon
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(11.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Transparent)
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                    drawRect(
                        color = IconActive,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = aspectRatio.label,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Select Aspect Ratio",
                tint = IconActive,
                modifier = Modifier.size(18.dp)
            )
        }

        // Right: Export button with spring animation
        Box(
            modifier = Modifier
                .size(44.dp)
                .bouncyClickable(onClick = onExportClick)
                .testTag("header_export_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Upload,
                contentDescription = "Export Video",
                tint = IconActive,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
