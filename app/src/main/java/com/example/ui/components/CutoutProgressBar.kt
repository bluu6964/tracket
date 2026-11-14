package com.example.ui.components
import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Progress of the full-video cutout (RVM) with a Cancel button. */
@Composable
fun CutoutProgressBar(
    progress: Float,
    label: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String = "Cancel"
) {
    val p = progress.coerceIn(0f, 1f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RectangleShape)
            .background(Color(0xFF1C1C1C))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.example.ui.buddy.BuddyFace(
                mood = com.example.ui.buddy.BuddyMood.WORKING,
                modifier = Modifier.size(34.dp)
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            Text(
                text = "$label ${(p * 100).toInt()}%",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) { Text(actionLabel, color = Color(0xFF7FB4FF), fontSize = 13.sp) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RectangleShape)
                .background(Color(0xFF3A3A3A))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(p)
                    .height(5.dp)
                    .background(Color(0xFF4C8DFF))
            )
        }
    }
}
