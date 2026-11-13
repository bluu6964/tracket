package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.TemplateAssets
import com.example.model.CarouselSpec
import com.example.model.CarouselTemplate
import com.example.ui.theme.HeaderText
import com.example.ui.theme.TextSecondary

private val DialogFill = Color(0xFF10161B)

/**
 * The Templates section (v1, build 142): ready-made motion-graphics projects the user opens
 * as a NEW project and then customises — every picture in a template is a replaceable slot.
 * One template for now: the Carousel photo ring.
 */
@Composable
fun TemplatesDialog(
    onDismiss: () -> Unit,
    onUseCarousel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogFill,
        title = { Text("Templates", color = HeaderText, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                CarouselPreview(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(
                    "Carousel — Photo Ring",
                    color = HeaderText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
                Text(
                    "8 photo slots rotating in a ring · 5s seamless loop · 16:10",
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onUseCarousel, modifier = Modifier.fillMaxWidth()) {
                    Text("Use template")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Opens as a new project. To use your own photo: tap a card, then " +
                        "Color → Fill → Image and pick it.",
                    color = TextSecondary, fontSize = 12.sp
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = HeaderText) }
        }
    )
}

/**
 * Live preview of the carousel, animated with the template's own measured law
 * ([CarouselTemplate.centerSceneAt]) and its own placeholder pictures — what you see is the
 * motion the project will run. No bundled asset: the preview is drawn, like the template.
 */
@Composable
private fun CarouselPreview(modifier: Modifier = Modifier) {
    val placeholders = remember {
        (0 until CarouselSpec.CARD_COUNT).map { TemplateAssets.placeholderBitmap(it).asImageBitmap() }
    }
    var frameMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMs = it }
        }
    }
    val bgTop = Color(CarouselSpec.BG_TOP.toInt())
    val bgBottom = Color(CarouselSpec.BG_BOTTOM.toInt())
    Canvas(
        modifier = modifier
            .aspectRatio(CarouselSpec.SCENE_W / CarouselSpec.SCENE_H)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
    ) {
        val sx = size.width / CarouselSpec.SCENE_W
        val sy = size.height / CarouselSpec.SCENE_H
        // Grid (34 scene px, +8 luminance over the fill, like the generated background).
        val gridPaint = Color.White.copy(alpha = 0.10f)
        var gx = CarouselSpec.GRID_OFF_X
        while (gx < CarouselSpec.SCENE_W) {
            drawLine(gridPaint, Offset(gx * sx, 0f), Offset(gx * sx, size.height), strokeWidth = 1.5f * sx)
            gx += CarouselSpec.GRID_STEP
        }
        var gy = CarouselSpec.GRID_OFF_Y
        while (gy < CarouselSpec.SCENE_H) {
            drawLine(gridPaint, Offset(0f, gy * sy), Offset(size.width, gy * sy), strokeWidth = 1.5f * sx)
            gy += CarouselSpec.GRID_STEP
        }
        val t = ((frameMs % CarouselSpec.DURATION_MS) + CarouselSpec.DURATION_MS) % CarouselSpec.DURATION_MS
        val cardW = CarouselSpec.CARD_SIZE * sx
        val cardH = CarouselSpec.CARD_SIZE * sy
        val corner = CarouselSpec.CARD_CORNER * sx
        // Shadows first (below every card), then the cards.
        for (pass in 0..1) {
            for (i in 0 until CarouselSpec.CARD_COUNT) {
                val (cx, cy) = CarouselTemplate.centerSceneAt(t, i)
                val left = cx * sx - cardW / 2f
                val top = (cy + if (pass == 0) CarouselSpec.SHADOW_DY else 0f) * sy - cardH / 2f
                if (pass == 0) {
                    drawRoundRect(
                        color = Color.Black.copy(alpha = CarouselSpec.SHADOW_OPACITY / 100f),
                        topLeft = Offset(left, top),
                        size = Size(cardW, cardH),
                        cornerRadius = CornerRadius(corner, corner)
                    )
                } else {
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                Rect(Offset(left, top), Size(cardW, cardH)),
                                CornerRadius(corner, corner)
                            )
                        )
                    }
                    clipPath(path) {
                        drawImage(
                            image = placeholders[i],
                            dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(cardW.toInt(), cardH.toInt())
                        )
                    }
                }
            }
        }
    }
}
