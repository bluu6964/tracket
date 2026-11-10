package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.EditorSurface
import com.example.ui.theme.EditorSurfaceVariant
import com.example.ui.theme.IconActive
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.util.bouncyClickable

/**
 * Parent picker: the layer follows the move / turn / scale of the chosen Null object.
 * [nulls] = (id, name) of the Null objects it may use (no loops), [currentId] = the parent now (null = none).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentSheet(
    layerName: String,
    nulls: List<Pair<String, String>>,
    currentId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EditorSurface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
                .testTag("parent_sheet")
        ) {
            Text("Parent of $layerName", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "The layer follows the move, turn and size of its Null object.",
                color = TextSecondary, fontSize = 12.sp
            )
            Spacer(Modifier.height(14.dp))
            Row1("None", null, currentId == null) { onPick(null) }
            for ((id, name) in nulls) {
                Spacer(Modifier.height(8.dp))
                Row1(name, id, currentId == id) { onPick(id) }
            }
            if (nulls.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "No Null object yet. Add one using the Null button in the timeline toolbar.",
                    color = TextSecondary, fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun Row1(name: String, id: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(EditorSurfaceVariant, RoundedCornerShape(12.dp))
            .bouncyClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .testTag("parent_pick_${id ?: "none"}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (id != null) Icon(Icons.Default.CenterFocusWeak, null, tint = IconActive, modifier = Modifier.size(22.dp))
        Text(name, color = if (selected) NeonCyan else TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, null, tint = NeonCyan, modifier = Modifier.size(20.dp))
    }
}
