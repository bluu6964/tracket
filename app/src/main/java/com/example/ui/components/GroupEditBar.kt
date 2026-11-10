package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown on the timeline while the person is INSIDE a group (Edit Group): the layers on screen are the layers of that group.
 * Tap it to go back out (the group is then selected again and works as one layer).
 */
@Composable
fun GroupEditBar(name: String, onExit: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE63F8F43))
            .clickable { onExit() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("group_edit_bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Exit group",
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "  $name",
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp)
        )
    }
}
