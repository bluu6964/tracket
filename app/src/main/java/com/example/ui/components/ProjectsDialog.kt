package com.example.ui.components
import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ProjectMeta
import com.example.ui.theme.AmDanger
import com.example.ui.theme.AmHairline
import com.example.ui.theme.AmInk
import com.example.ui.theme.AmPanel
import com.example.ui.theme.AmPanelHigh
import com.example.ui.theme.HeaderCyan
import com.example.ui.theme.HeaderText
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dialog surface: the flat panel token (was a hard-coded #111111). */
private val DialogFill = AmPanel

/** The project list: open, new, rename, delete. The open project is marked. */
@Composable
fun ProjectsDialog(
    projects: List<ProjectMeta>,
    currentId: String?,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    /** Save the project as a portable .ampro file (media included). */
    onExport: (ProjectMeta) -> Unit,
    /** Pick a .ampro file and add it as a new project. */
    onImport: () -> Unit,
    /** Files of the OPEN project that only the exporting phone had (build 111); 0 hides the button. */
    missingMediaCount: Int = 0,
    /** Open the replace-missing-media step for the open project. */
    onFixMissingMedia: () -> Unit = {}
) {
    var renaming by remember { mutableStateOf<ProjectMeta?>(null) }
    var deleting by remember { mutableStateOf<ProjectMeta?>(null) }
    val dateFmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogFill,
        shape = RectangleShape,
        title = { Text("Projects", color = HeaderText, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (projects.isEmpty()) {
                    item {
                        Text(
                            "No projects yet. Tap New project below to create one.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
                items(projects, key = { it.id }) { p ->
                    val isCurrent = p.id == currentId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RectangleShape)
                            .background(AmPanelHigh)
                            .border(1.dp, AmHairline, RectangleShape)
                            .clickable { onOpen(p.id) }
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.name,
                                color = if (isCurrent) HeaderCyan else HeaderText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${dateFmt.format(Date(p.updatedAt))}  ·  ${p.layerCount} layers  ·  ${formatDuration(p.durationMs)}" +
                                    if (isCurrent) "  ·  open" else "",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onExport(p) }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Filled.Share, contentDescription = "Export project file", tint = TextSecondary)
                        }
                        IconButton(onClick = { renaming = p }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Filled.Edit, contentDescription = "Rename", tint = TextSecondary)
                        }
                        IconButton(onClick = { deleting = p }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = TextSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (missingMediaCount > 0) {
                    TextButton(onClick = onFixMissingMedia) {
                        Text("Missing media ($missingMediaCount)", color = Color(0xFFFFB74D))
                    }
                }
                TextButton(onClick = onImport) { Text("Import", color = HeaderCyan) }
                // The primary action: a flat white pill (B/W language), not a text button.
                GradientPillButton(onClick = onNew) {
                    Text("New project", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = TextSecondary) } }
    )

    renaming?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = DialogFill,
        shape = RectangleShape,
            title = { Text("Rename", color = HeaderText) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it.take(60) }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) onRename(p.id, text)
                    renaming = null
                }) { Text("OK", color = HeaderCyan) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = TextSecondary) } }
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = DialogFill,
        shape = RectangleShape,
            title = { Text("Delete project?", color = HeaderText) },
            text = { Text("\"${p.name}\" will be removed. This cannot be undone.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(p.id)
                    deleting = null
                }) { Text("Delete", color = AmDanger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = TextSecondary) } }
        )
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).toInt()
    // Same rule as the ruler clock: from one hour on, "H:MM:SS" instead of minutes past 59.
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
