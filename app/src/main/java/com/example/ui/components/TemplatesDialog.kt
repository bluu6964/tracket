package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.example.model.CarouselParams
import com.example.model.CarouselSpec
import com.example.model.CarouselTemplate
import com.example.model.LogoRevealSpec
import com.example.model.LogoRevealTemplate
import com.example.model.PopTitleSpec
import com.example.model.PopTitleTemplate
import com.example.model.WordPopSpec
import com.example.model.WordPopTemplate
import com.example.model.SavedTemplate
import com.example.model.at
import com.example.ui.theme.HeaderText
import com.example.ui.theme.TextSecondary

private val DialogFill = Color(0xFF10161B)

/**
 * The Templates picker (build 142; in-project since build 143): ready-made compositions the
 * user adds to the project that is OPEN. Templates live where layers are added — the
 * editor's + sheet — and choosing one inserts it into the current timeline as ONE Group
 * layer the user then customises in place (every picture in a template is a replaceable
 * slot). Four built-in templates: the Carousel photo ring, the Pop Title and Word Pop
 * text animations and the Logo Reveal logo animation. Below them, "My Templates" (Templates v2) lists the
 * groups the person saved themselves with a group's "Save as Template" tool — each can be
 * added the same way, or deleted (with its saved media copies). Build 154: tapping the
 * Carousel preview opens its insert-time settings (card count / turn time / direction)
 * and the preview plays exactly those settings; the other templates keep their one-tap add.
 */
@Composable
fun TemplatesDialog(
    onDismiss: () -> Unit,
    onAddCarousel: (CarouselParams) -> Unit,
    onAddPopTitle: () -> Unit,
    onAddLogoReveal: () -> Unit,
    onAddWordPop: () -> Unit,
    /** The person's own saved templates ("Save as Template", Templates v2), newest first. */
    userTemplates: List<SavedTemplate> = emptyList(),
    onAddUserTemplate: (SavedTemplate) -> Unit = {},
    onDeleteUserTemplate: (SavedTemplate) -> Unit = {}
) {
    var pendingDelete by remember { mutableStateOf<SavedTemplate?>(null) }
    // The Carousel's insert-time settings (build 154): tapping the Carousel preview opens
    // the small settings step; the defaults are the reference's own numbers, so a plain
    // "Add to project" inserts exactly what builds 142-146 inserted.
    var carouselSettingsOpen by remember { mutableStateOf(false) }
    var carouselCards by remember { mutableIntStateOf(CarouselSpec.CARD_COUNT) }
    var carouselSeconds by remember {
        mutableIntStateOf((CarouselSpec.DURATION_MS / 1000L).toInt())
    }
    var carouselClockwise by remember { mutableStateOf(true) }
    val carouselParams = CarouselParams(
        cardCount = carouselCards,
        durationMs = carouselSeconds * 1000L,
        clockwise = carouselClockwise
    )
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
                CarouselPreview(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { carouselSettingsOpen = !carouselSettingsOpen },
                    params = carouselParams
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Carousel — Photo Ring",
                    color = HeaderText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
                Text(
                    "$carouselCards photo slots rotating in a ring · one turn in " +
                        "${carouselSeconds}s · ${if (carouselClockwise) "clockwise" else "counter-clockwise"} · " +
                        "added as one group layer",
                    color = TextSecondary, fontSize = 13.sp
                )
                if (carouselSettingsOpen) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Cards", color = HeaderText, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { if (carouselCards > CarouselSpec.MIN_CARDS) carouselCards-- }
                        ) { Text("−", fontSize = 18.sp) }
                        Text("$carouselCards", color = HeaderText, fontSize = 15.sp)
                        TextButton(
                            onClick = { if (carouselCards < CarouselSpec.MAX_CARDS) carouselCards++ }
                        ) { Text("+", fontSize = 18.sp) }
                    }
                    Text(
                        "One turn: ${carouselSeconds}s",
                        color = HeaderText, fontSize = 14.sp
                    )
                    Slider(
                        value = carouselSeconds.toFloat(),
                        onValueChange = { carouselSeconds = kotlin.math.round(it).toInt() },
                        valueRange = (CarouselSpec.MIN_DURATION_MS / 1000f)..(CarouselSpec.MAX_DURATION_MS / 1000f),
                        steps = 7,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Direction", color = HeaderText, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { carouselClockwise = true }) {
                            Text(
                                "Clockwise",
                                fontWeight = if (carouselClockwise) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        TextButton(onClick = { carouselClockwise = false }) {
                            Text(
                                "Counter-clockwise",
                                fontWeight = if (!carouselClockwise) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onAddCarousel(carouselParams) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add to project")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lands in this project's timeline at the playhead as a single group. " +
                        "Tap the preview to change the card count, turn time and direction. " +
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
                Spacer(Modifier.height(18.dp))
                WordPopPreview(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(
                    "Word Pop — Text Animation",
                    color = HeaderText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
                Text(
                    "Words pop in one by one, hold, and fade out together · 3s · added as one group layer",
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAddWordPop, modifier = Modifier.fillMaxWidth()) {
                    Text("Add to project")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lands in this project's timeline at the playhead as a single group. " +
                        "Every word is its own layer: open the group, tap a word and " +
                        "retype it — Bengali included — or move, retime and recolour " +
                        "each word on its own.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    "My Templates",
                    color = HeaderText, fontWeight = FontWeight.Bold, fontSize = 17.sp
                )
                Spacer(Modifier.height(4.dp))
                if (userTemplates.isEmpty()) {
                    Text(
                        "Nothing saved yet. Build a group, select it, and tap " +
                            "“Save as Template” in its tools — it will appear here and can " +
                            "be added to any project.",
                        color = TextSecondary, fontSize = 13.sp
                    )
                }
                for (tpl in userTemplates) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        tpl.name,
                        color = HeaderText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                    )
                    Text(
                        "Your saved group · ${tpl.clips.size} layer${if (tpl.clips.size == 1) "" else "s"} · " +
                            "added as one group layer",
                        color = TextSecondary, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onAddUserTemplate(tpl) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add to project")
                    }
                    TextButton(onClick = { pendingDelete = tpl }) {
                        Text("Delete template", color = TextSecondary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = HeaderText) }
        }
    )
    pendingDelete?.let { tpl ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = DialogFill,
            title = { Text("Delete template?", color = HeaderText, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "“${tpl.name}” will be removed from My Templates, with its saved pictures. " +
                        "Groups you already added to projects are not changed.",
                    color = TextSecondary, fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; onDeleteUserTemplate(tpl) }) {
                    Text("Delete", color = HeaderText)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel", color = HeaderText) }
            }
        )
    }
}

/**
 * Live preview of the carousel, animated with the template's own measured law
 * ([CarouselTemplate.centerSceneAt]) and its own placeholder pictures — what you see is the
 * motion the inserted group will run. No bundled asset: the preview is drawn, like the template.
 * Build 154: the preview runs the insert's chosen [params] (card count / turn duration /
 * direction) — it evaluates the same law the builder bakes, at the chosen ring radius.
 */
@Composable
private fun CarouselPreview(modifier: Modifier = Modifier, params: CarouselParams = CarouselParams()) {
    val placeholders = remember(params.cardCount) {
        (0 until params.cardCount).map { TemplateAssets.placeholderBitmap(it).asImageBitmap() }
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
        val t = ((frameMs % params.durationMs) + params.durationMs) % params.durationMs
        val cardW = CarouselSpec.CARD_SIZE * sx
        val cardH = CarouselSpec.CARD_SIZE * sy
        val corner = CarouselSpec.CARD_CORNER * sx
        // Shadows first (below every card), then the cards.
        for (pass in 0..1) {
            for (i in 0 until params.cardCount) {
                val (cx, cy) = CarouselTemplate.centerSceneAt(t, i, params)
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

/**
 * Live preview of the Word Pop: the template built headlessly at a nominal 720x405 and
 * EVALUATED — the very clips an insert would create, run through the model's own keyframe
 * evaluator ([at]) on the frame clock — so what plays here is exactly what lands in the
 * project (each word popping in in turn on its own layer, the hold, the shared fade-out,
 * looping every 3 s). Each word is drawn centred on its evaluated position, the way the
 * text painters centre a layer's line on the layer's position.
 */
@Composable
private fun WordPopPreview(modifier: Modifier = Modifier) {
    val ins = remember { WordPopTemplate.buildGroup(720f, 405f, 0L, idBase = "preview") }
    var frameMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMs = it }
        }
    }
    val t = ((frameMs % WordPopSpec.DURATION_MS) + WordPopSpec.DURATION_MS) % WordPopSpec.DURATION_MS
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2A3440), Color(0xFF12181F))))
    ) {
        val density = LocalDensity.current
        // Preview px per nominal stored px.
        val unit = with(density) { maxWidth.toPx() } / 720f
        for (word in ins.wordLayers) {
            val w = word.at(t)
            Text(
                text = word.text ?: "",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = with(density) { (0.11f * 720f * unit).toSp() },
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = with(density) { (w.transformX * unit).toDp() },
                        y = with(density) { (w.transformY * unit).toDp() }
                    )
                    .graphicsLayer {
                        alpha = w.opacity / 100f
                        scaleX = w.scalePercent / 100f
                        scaleY = w.scalePercent / 100f
                    }
            )
        }
    }
}
