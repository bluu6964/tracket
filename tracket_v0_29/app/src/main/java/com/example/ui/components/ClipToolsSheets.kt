package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.EditorSurface
import com.example.ui.theme.EditorSurfaceVariant
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.util.bouncyClickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipEffectsSheet(
    currentEffect: String?,
    onEffectSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val effects = listOf(
        "None",
        "VHS Glitch",
        "Cyber Neon",
        "RGB Split",
        "Cinema 90s",
        "Lens Flare",
        "Film Grain",
        "Dream Glow"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EditorSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Video Effects", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(14.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(effects) { effect ->
                    val isSelected = effect == (currentEffect ?: "None")
                    Column(
                        modifier = Modifier
                            .width(80.dp)
                            .bouncyClickable(onClick = {
                                onEffectSelect(effect)
                                onDismiss()
                            }),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) NeonCyan.copy(alpha = 0.2f) else EditorSurfaceVariant)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) NeonCyan else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = if (isSelected) NeonCyan else Color.White, modifier = Modifier.size(28.dp))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(effect, color = TextPrimary, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipAnimationsSheet(
    currentAnim: String?,
    onAnimSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val animations = listOf(
        "None",
        "Fade In",
        "Zoom In",
        "Slide Up",
        "Shake",
        "Pulse",
        "Spin",
        "Bounce"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EditorSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Clip Animation", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(14.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(animations) { anim ->
                    val isSelected = anim == (currentAnim ?: "None")
                    Column(
                        modifier = Modifier
                            .width(80.dp)
                            .bouncyClickable(onClick = {
                                onAnimSelect(anim)
                                onDismiss()
                            }),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) NeonCyan.copy(alpha = 0.2f) else EditorSurfaceVariant)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) NeonCyan else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayCircleOutline, contentDescription = null, tint = if (isSelected) NeonCyan else Color.White, modifier = Modifier.size(28.dp))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(anim, color = TextPrimary, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipReplaceSheet(
    onMediaSelected: (drawableResId: Int, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    val mediaOptions = listOf(
        Triple(R.drawable.sample_portrait_1791121452621, "Portrait Vlog", "1080p 60fps"),
        Triple(R.drawable.sample_nature_1791121466670, "Nature Canopy", "4K 30fps"),
        Triple(R.drawable.sample_cyan_dots_1791121437975, "Cyan Graphic", "Alpha Overlay")
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EditorSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Replace Media", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(14.dp))

            mediaOptions.forEach { (res, name, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .bouncyClickable(onClick = {
                            onMediaSelected(res, name)
                            onDismiss()
                        })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = res),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(desc, color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipBlendSheet(
    currentBlend: String,
    onBlendSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val blendModes = listOf("Normal", "Screen", "Multiply", "Overlay", "Lighten", "Darken", "Color Dodge")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EditorSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Blending Mode", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(14.dp))

            blendModes.forEach { mode ->
                val isSelected = mode == currentBlend
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) EditorSurfaceVariant else Color.Transparent)
                        .bouncyClickable(onClick = {
                            onBlendSelected(mode)
                            onDismiss()
                        })
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(mode, color = TextPrimary, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipCropSheet(
    onCropRatioSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val cropRatios = listOf("Free", "1:1", "16:9", "9:16", "4:3", "21:9")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EditorSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Crop Ratio", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(14.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(cropRatios) { ratio ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(EditorSurfaceVariant)
                            .bouncyClickable(onClick = {
                                onCropRatioSelected(ratio)
                                onDismiss()
                            })
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(ratio, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
