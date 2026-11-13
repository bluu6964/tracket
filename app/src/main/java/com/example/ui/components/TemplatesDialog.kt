package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
import com.example.model.LogoRevealSpec
import com.example.model.LogoRevealTemplate
import com.example.model.PopTitleSpec
import com.example.model.PopTitleTemplate
import com.example.model.at
import com.example.ui.theme.HeaderText
import com.example.ui.theme.TextSecondary

private val DialogFill = Color(0xFF10161B)

/**
 * The Templates picker (build 142; in-project since build 143): ready-made compositions the
 * user adds to the project that is OPEN. Templates live where layers are added — the
 * editor's + sheet — and choosing one inserts it into the current timeline as ONE Group
 * layer the user then customises in place (every picture in a template is a replaceable
 * slot). Three templates: the Carousel photo ring, the Pop Title text animation and the
 * Logo Reveal logo animation.
 */
@Composable
fun TemplatesDialog(
    onDismiss: () -> Unit,
    onAddCarousel: () -> Unit,
    onAddPopTitle: () -> Unit,
    onAddLogoReveal: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogFill,
        title = { Text("Templates", color = HeaderText, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                CarouselPreview(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(
                    "Carousel — Photo Ring",
                    color = HeaderText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
                Text(
                    "8 photo slots rotating in a ring · 5s seamless loop · added as one group layer",
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAddCarousel, modifier = Modifier.fillMaxWidth()) {
                    Text("Add to project")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lands in this project's timeline at the playhead as a single group. " +
                        "To use your own photo: open the group, tap a card, then " +
                        "Color → Fill → Image and pick it.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(18.dp))
                PopTitlePreview(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(
                    "Pop Title — Text Animation",
                    color = HeaderText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
                Text(
                    "A title that pops in, holds, and fades out · 3s · added as one group layer",
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAddPopTitle, modifier = Modifier.fillMaxWidth()) {
                    Text("Add to project")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lands in this project's timeline at the playhead as a single group. " +
                        "Open the group and retype the text — the animation stays on your " +
                        "words, Bengali included.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(18.dp))
                LogoRevealPreview(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(
                    "Logo Reveal — Logo Animation",
                    color = HeaderText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
                Text(
                    "Your logo pops in and your brand name rises under it, then holds · 3s · added as one group layer",
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAddLogoReveal, modifier = Modifier.fillMaxWidth()) {
                    Text("Add to project")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lands in this project's timeline at the playhead as a single group. " +
                        "To use your own logo: open the group, tap the logo, then " +
                        "Color → Fill → Image and pick it. Retype the brand name — " +
                        "Bengali included. Stretch the group longer to hold the reveal.",
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
 * motion the inserted group will run. No bundled asset: the preview is drawn, like the template.
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

/**
 * Live preview of the Pop Title: the template built headlessly at a nominal 720x405 and
 * EVALUATED — the very clips an insert would create, run through the model's own keyframe
 * evaluator ([at]) on the frame clock — so what plays here is exactly what lands in the
 * project (same rise, same Back-out pop, same fade-out, looping every 3 s).
 */
@Composable
private fun PopTitlePreview(modifier: Modifier = Modifier) {
    val ins = remember { PopTitleTemplate.buildGroup(720f, 405f, 0L, idBase = "preview") }
    var frameMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMs = it }
        }
    }
    val t = ((frameMs % PopTitleSpec.DURATION_MS) + PopTitleSpec.DURATION_MS) % PopTitleSpec.DURATION_MS
    val text = ins.textLayer.at(t)
    val bar = ins.bar.at(t)
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2A3440), Color(0xFF12181F))))
    ) {
        val density = LocalDensity.current
        // Preview px per nominal stored px; the nominal shape box is 720 x 130/360 = 260.
        val unit = with(density) { maxWidth.toPx() } / 720f
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = with(density) { (bar.transformY * unit).toDp() })
                .width(with(density) { (260f * bar.shapeScaleX * unit).toDp() })
                .height(with(density) { (260f * bar.shapeScaleY * unit).toDp() })
                .graphicsLayer { alpha = bar.opacity / 100f }
                .background(Color(PopTitleSpec.ACCENT.toInt()), RoundedCornerShape(50))
        )
        Text(
            text = PopTitleSpec.SAMPLE_TEXT,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = with(density) { (0.11f * 720f * unit).toSp() },
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    alpha = text.opacity / 100f
                    scaleX = text.scalePercent / 100f
                    scaleY = text.scalePercent / 100f
                    translationY = text.transformY * unit
                }
        )
    }
}

/**
 * Live preview of the Logo Reveal: the template built headlessly at a nominal 720x405 and
 * EVALUATED — the very clips an insert would create, run through the model's own keyframe
 * evaluator ([at]) on the frame clock — so what plays here is exactly what lands in the
 * project (the logo's Back-out pop, the name rising in under it, then the hold, looping
 * every 3 s). The badge picture is the template's own generated placeholder.
 */
@Composable
private fun LogoRevealPreview(modifier: Modifier = Modifier) {
    val ins = remember { LogoRevealTemplate.buildGroup(720f, 405f, "", 0L, idBase = "preview") }
    val logoArt = remember { TemplateAssets.logoBitmap().asImageBitmap() }
    var frameMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMs = it }
        }
    }
    val t = ((frameMs % LogoRevealSpec.DURATION_MS) + LogoRevealSpec.DURATION_MS) % LogoRevealSpec.DURATION_MS
    val logo = ins.logoLayer.at(t)
    val text = ins.textLayer.at(t)
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2A3440), Color(0xFF12181F))))
    ) {
        val density = LocalDensity.current
        // Preview px per nominal stored px; the nominal shape box is 720 x 130/360 = 260.
        val unit = with(density) { maxWidth.toPx() } / 720f
        Image(
            bitmap = logoArt,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = with(density) { (logo.transformY * unit).toDp() })
                .size(with(density) { (260f * logo.shapeScaleX * unit).toDp() })
                .clip(RoundedCornerShape(20))
                .graphicsLayer { alpha = logo.opacity / 100f }
        )
        Text(
            text = LogoRevealSpec.SAMPLE_TEXT,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = with(density) { (0.11f * 720f * unit).toSp() },
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    alpha = text.opacity / 100f
                    translationY = text.transformY * unit
                }
        )
    }
}
