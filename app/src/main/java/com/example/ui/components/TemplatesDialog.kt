package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.CounterEval
import com.example.engine.TemplateAssets
import com.example.model.CarouselParams
import com.example.model.CarouselSpec
import com.example.model.CarouselTemplate
import com.example.model.DonutChartSpec
import com.example.model.DonutChartTemplate
import com.example.model.GenieSpec
import com.example.model.GenieTemplate
import com.example.model.LogoRevealSpec
import com.example.model.LogoRevealTemplate
import com.example.model.PieCounterSpec
import com.example.model.PieCounterTemplate
import com.example.model.PopTitleSpec
import com.example.model.PopTitleTemplate
import com.example.model.SocialGrowthSpec
import com.example.model.SocialGrowthTemplate
import com.example.model.WordPopSpec
import com.example.model.WordPopTemplate
import com.example.model.SavedTemplate
import com.example.model.at
import com.example.model.flattenPathSegments
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
    onAddGenie: () -> Unit = {},
    onAddPieCounter: () -> Unit = {},
    onAddDonutChart: () -> Unit = {},
    onAddSocialGrowth: () -> Unit = {},
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
                Spacer(Modifier.height(18.dp))
                GeniePreview(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(
                    "Genie — Avatar Bar & Profile Cards",
                    color = HeaderText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
                Text(
                    "A pill stretches into a photo bar, then profile cards appear one by one · 16s · added as one group layer",
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAddGenie, modifier = Modifier.fillMaxWidth()) {
                    Text("Add to project")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lands in this project's timeline at the playhead as a single group. " +
                        "Every photo is a slot: open the group, tap a photo, then " +
                        "Color → Fill → Image and pick yours. Names and activities are " +
                        "plain text — retype them, Bengali included.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(18.dp))
                PieCounterPreview(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(
                    "Pie Counter — Percent Pie",
                    color = HeaderText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
                Text(
                    "A pie fills clockwise while the number counts 0% → 100% · 4s · added as one group layer",
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAddPieCounter, modifier = Modifier.fillMaxWidth()) {
                    Text("Add to project")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lands in this project's timeline at the playhead as a single group. " +
                        "The sweep and the count share one timing — retime the pie's angle " +
                        "keys and the number's counter together. Colours live in Color → Fill.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(18.dp))
                DonutChartPreview(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(
                    "Donut Chart — Segment Builder",
                    color = HeaderText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
                Text(
                    "Five segments build one by one, each with its % label and legend line · 10s · added as one group layer",
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAddDonutChart, modifier = Modifier.fillMaxWidth()) {
                    Text("Add to project")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lands in this project's timeline at the playhead as a single group. " +
                        "Retype the percentages and legend lines, recolour each segment in " +
                        "Color → Fill, and drag a segment's angle keys to change its share.",
                    color = TextSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(18.dp))
                SocialGrowthPreview(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(
                    "Social Growth — Follower Promo",
                    color = HeaderText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp
                )
                Text(
                    "Upload ring, CREATE → POST → ELEVATE titles, counters that climb over a checkmark grid · 10s · added as one group layer",
                    color = TextSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onAddSocialGrowth, modifier = Modifier.fillMaxWidth()) {
                    Text("Add to project")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lands in this project's timeline at the playhead as a single group. " +
                        "The profile photo is a slot (Color → Fill → Image); the counters " +
                        "are counter text layers — change their final numbers on the " +
                        "counter's keyframes.",
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

/** A Compose [Path] through a pie layer's evaluated outline (its normalised points scaled to [r]). */
private fun piePreviewPath(
    segs: List<com.example.model.PathSeg>,
    cx: Float,
    cy: Float,
    r: Float
): Path {
    val pts = flattenPathSegments(segs)
    val p = Path()
    for ((i, pt) in pts.withIndex()) {
        if (i == 0) p.moveTo(cx + pt.first * r, cy + pt.second * r)
        else p.lineTo(cx + pt.first * r, cy + pt.second * r)
    }
    if (pts.size > 2) p.close()
    return p
}

/**
 * Live preview of the Genie template: the template built headlessly at a nominal
 * 720x1280 and EVALUATED with the model's own evaluator ([at]) on the frame clock —
 * the pill stretching into the tilted bar, the avatar row scrolling, the four cards
 * cycling — looping every 16 s. Simplified like the other previews (no glow/streak
 * effects, the flower is dots, each card is drawn as one unit).
 */
@Composable
private fun GeniePreview(modifier: Modifier = Modifier) {
    val ins = remember {
        GenieTemplate.buildGroup(720f, 1280f, List(6) { "" }, List(4) { "" }, 0L, idBase = "preview")
    }
    val avatarArts = remember { (0 until 6).map { TemplateAssets.placeholderBitmap(it).asImageBitmap() } }
    val photoArts = remember { (0 until 4).map { TemplateAssets.portraitBitmap(it).asImageBitmap() } }
    var frameMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMs = it }
        }
    }
    val t = ((frameMs % GenieSpec.DURATION_MS) + GenieSpec.DURATION_MS) % GenieSpec.DURATION_MS
    val bg = ins.background.at(t)
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(bg.textColor.toInt()))
    ) {
        val density = LocalDensity.current
        val unit = with(density) { maxWidth.toPx() } / 720f
        val boxPx = 260f // the nominal shape box at 720 wide (720 * 130/360)
        // The bar (with its streak as a soft white wash under it).
        val bar = ins.bar.at(t)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(
                    x = with(density) { (bar.transformX * unit).toDp() },
                    y = with(density) { (bar.transformY * unit).toDp() }
                )
                .width(with(density) { (boxPx * bar.shapeScaleX * unit).toDp() })
                .height(with(density) { (boxPx * bar.shapeScaleY * unit).toDp() })
                .graphicsLayer {
                    alpha = bar.opacity / 100f
                    rotationZ = bar.rotationDeg
                }
                .background(Color.White, RoundedCornerShape(50))
        )
        // The flower: eight small dark dots riding the bar.
        Canvas(modifier = Modifier.matchParentSize()) {
            for (petal in ins.petals) {
                val p = petal.at(t)
                if (p.opacity <= 1f) continue
                drawCircle(
                    color = Color(0xFF202024),
                    radius = 5f * unit,
                    center = Offset(
                        size.width / 2f + p.transformX * unit,
                        size.height / 2f + p.transformY * unit
                    ),
                    alpha = p.opacity / 100f
                )
            }
        }
        // Selection rings, then avatars with their names.
        for (ring in ins.rings) {
            val r = ring.at(t)
            if (r.opacity <= 1f) continue
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = with(density) { (r.transformX * unit).toDp() },
                        y = with(density) { (r.transformY * unit).toDp() }
                    )
                    .size(with(density) { (boxPx * r.shapeScaleX * unit).toDp() })
                    .graphicsLayer { alpha = r.opacity / 100f }
                    .border(2.dp, Color(r.strokeColor.toInt()), CircleShape)
            )
        }
        for ((i, avatar) in ins.avatars.withIndex()) {
            val a = avatar.at(t)
            if (a.opacity <= 1f) continue
            Image(
                bitmap = avatarArts[i],
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = with(density) { (a.transformX * unit).toDp() },
                        y = with(density) { (a.transformY * unit).toDp() }
                    )
                    .size(with(density) { (boxPx * a.shapeScaleX * unit).toDp() })
                    .clip(CircleShape)
                    .graphicsLayer { alpha = a.opacity / 100f }
            )
            val n = ins.avatarNames[i].at(t)
            Text(
                text = ins.avatarNames[i].text ?: "",
                color = Color(0xFF2B2B30),
                fontWeight = FontWeight.SemiBold,
                fontSize = with(density) { (n.scalePercent / 100f * 0.11f * 720f * unit).toSp() },
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = with(density) { (n.transformX * unit).toDp() },
                        y = with(density) { (n.transformY * unit).toDp() }
                    )
                    .graphicsLayer {
                        alpha = n.opacity / 100f
                        rotationZ = n.rotationDeg
                    }
            )
        }
        // The four cards, each drawn as one unit (photo + name + invite pill).
        for ((i, card) in ins.cards.withIndex()) {
            val p = card.photo.at(t)
            if (p.opacity <= 1f) continue
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = with(density) { (p.transformX * unit).toDp() },
                        y = with(density) { (p.transformY * unit).toDp() }
                    )
                    .width(with(density) { (boxPx * p.shapeScaleX * unit).toDp() })
                    .height(with(density) { (boxPx * p.shapeScaleY * unit).toDp() })
                    .graphicsLayer {
                        alpha = p.opacity / 100f
                        rotationZ = p.rotationDeg
                        rotationX = p.rotation3dX
                        rotationY = p.rotation3dY
                        cameraDistance = 12f
                    }
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Image(
                    bitmap = photoArts[i],
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
                Text(
                    text = card.title.text ?: "",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontStyle = FontStyle.Italic,
                    fontSize = with(density) { (card.title.scalePercent / 100f * 0.11f * 720f * unit).toSp() },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = 10.dp, y = (-26).dp)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-10).dp, y = (-10).dp)
                        .background(Color(0x40FFFFFF), RoundedCornerShape(50))
                ) {
                    Text(
                        text = "Invite",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.offset(x = 8.dp, y = 3.dp)
                    )
                }
            }
        }
    }
}

/**
 * Live preview of the Pie Counter: the template built headlessly at a nominal
 * 720x1200 and EVALUATED on the frame clock — the wedge paths come from the clips'
 * own evaluated outlines ([com.example.model.TimelineClip.pieSegmentsAt]) and the
 * number from the app's own counter evaluator — looping every 4 s.
 */
@Composable
private fun PieCounterPreview(modifier: Modifier = Modifier) {
    val ins = remember { PieCounterTemplate.buildGroup(720f, 1200f, 0L, idBase = "preview") }
    var frameMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMs = it }
        }
    }
    val t = ((frameMs % PieCounterSpec.DURATION_MS) + PieCounterSpec.DURATION_MS) % PieCounterSpec.DURATION_MS
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(3f / 5f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
    ) {
        val density = LocalDensity.current
        val unit = with(density) { maxWidth.toPx() } / 720f
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f + PieCounterSpec.PIE_CY_FRAC * 1200f * unit
            val r = PieCounterSpec.RADIUS_FRAC * 720f * unit
            drawPath(
                piePreviewPath(ins.fill.pieSegmentsAt(t), cx, cy, r),
                Brush.linearGradient(
                    listOf(Color(0xFF1E8CFF), Color(0xFFF4F8FF)),
                    start = Offset(cx - r, cy - r),
                    end = Offset(cx + r, cy + r)
                )
            )
            drawPath(
                piePreviewPath(ins.outline.pieSegmentsAt(t), cx, cy, r),
                Color(0xFFB9C4CE),
                style = Stroke(width = 1.5f)
            )
        }
        val pct = CounterEval.displayText(ins.percentText.text ?: "0%", ins.percentText, t) ?: "0%"
        Text(
            text = pct,
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = with(density) { (PieCounterSpec.TEXT_FONT_FRAC * 720f * unit).toSp() },
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = with(density) { (PieCounterSpec.TEXT_CY_FRAC * 1200f * unit).toDp() })
        )
    }
}

/**
 * Live preview of the Donut Chart: the template built headlessly at a nominal
 * 720x1280 and EVALUATED on the frame clock — each segment drawn from its clip's
 * own evaluated outline, the hole punched over them, labels and legend lines as
 * evaluated texts — looping every 10 s.
 */
@Composable
private fun DonutChartPreview(modifier: Modifier = Modifier) {
    val ins = remember { DonutChartTemplate.buildGroup(720f, 1280f, 0L, idBase = "preview") }
    var frameMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMs = it }
        }
    }
    val t = ((frameMs % DonutChartSpec.DURATION_MS) + DonutChartSpec.DURATION_MS) % DonutChartSpec.DURATION_MS
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
    ) {
        val density = LocalDensity.current
        val unit = with(density) { maxWidth.toPx() } / 720f
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f + DonutChartSpec.CY_FRAC * 1280f * unit
            val r = DonutChartSpec.R_FRAC * 720f * unit
            for (seg in ins.segments) {
                val (g1, g2) = DonutChartSpec.GRADIENTS[ins.segments.indexOf(seg)]
                drawPath(
                    piePreviewPath(seg.wedge.pieSegmentsAt(t), cx, cy, r),
                    Brush.linearGradient(
                        listOf(Color(g1.toInt()), Color(g2.toInt())),
                        start = Offset(cx - r, cy - r),
                        end = Offset(cx + r, cy + r)
                    )
                )
            }
            drawCircle(
                color = Color.Black,
                radius = DonutChartSpec.HOLE_FRAC * 720f * unit / 2f,
                center = Offset(cx, cy)
            )
        }
        val title = ins.title.at(t)
        Text(
            text = ins.title.text ?: "",
            color = Color(0xFF9A9A9A),
            fontWeight = FontWeight.SemiBold,
            fontSize = with(density) { (DonutChartSpec.TITLE_FONT_FRAC * 720f * unit).toSp() },
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = with(density) { (title.transformY * unit).toDp() })
                .graphicsLayer { alpha = title.opacity / 100f }
        )
        for (seg in ins.segments) {
            val l = seg.label.at(t)
            if (l.opacity > 1f) {
                Text(
                    text = seg.label.text ?: "",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = with(density) { (DonutChartSpec.LABEL_FONT_FRAC * 720f * unit).toSp() },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(
                            x = with(density) { (l.transformX * unit).toDp() },
                            y = with(density) { (l.transformY * unit).toDp() }
                        )
                        .graphicsLayer { alpha = l.opacity / 100f }
                )
            }
            val g = seg.legendText.at(t)
            if (g.opacity > 1f) {
                Text(
                    text = seg.legendText.text ?: "",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = with(density) { (DonutChartSpec.LEGEND_FONT_FRAC * 720f * unit).toSp() },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(
                            x = with(density) { (g.transformX * unit).toDp() },
                            y = with(density) { (g.transformY * unit).toDp() }
                        )
                        .graphicsLayer { alpha = g.opacity / 100f }
                )
            }
        }
    }
}

/**
 * Live preview of the Social Growth template: the template built headlessly at a
 * nominal 720x1280 and EVALUATED on the frame clock — titles, counters (through
 * the app's own counter evaluator), the trim-drawn ring and the popping tiles —
 * looping every 10 s. Simplified: the checkmarks are not drawn here.
 */
@Composable
private fun SocialGrowthPreview(modifier: Modifier = Modifier) {
    val ins = remember { SocialGrowthTemplate.buildGroup(720f, 1280f, "", 0L, idBase = "preview") }
    val avatarArt = remember { TemplateAssets.placeholderBitmap(3).asImageBitmap() }
    var frameMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameMs = it }
        }
    }
    val t = ((frameMs % SocialGrowthSpec.DURATION_MS) + SocialGrowthSpec.DURATION_MS) % SocialGrowthSpec.DURATION_MS
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
    ) {
        val density = LocalDensity.current
        val unit = with(density) { maxWidth.toPx() } / 720f
        val boxPx = 260f
        for (title in ins.titles) {
            val c = title.at(t)
            if (c.opacity <= 1f) continue
            Text(
                text = title.text ?: "",
                color = Color(title.textColor.toInt()),
                fontWeight = FontWeight.ExtraBold,
                fontSize = with(density) { (SocialGrowthSpec.TITLE_FONT_FRAC * 720f * unit).toSp() },
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = with(density) { (c.transformY * unit).toDp() })
                    .graphicsLayer { alpha = c.opacity / 100f }
            )
        }
        val pill = ins.uploadPill.at(t)
        if (pill.opacity > 1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = with(density) { (pill.transformY * unit).toDp() })
                    .width(with(density) { (boxPx * pill.shapeScaleX * unit).toDp() })
                    .height(with(density) { (boxPx * pill.shapeScaleY * unit).toDp() })
                    .graphicsLayer { alpha = pill.opacity / 100f }
                    .background(Color(0xFF15151A), RoundedCornerShape(50))
            )
            Text(
                text = "UPLOAD",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = with(density) { (pill.transformY * unit).toDp() })
                    .graphicsLayer { alpha = pill.opacity / 100f }
            )
        }
        val ring = ins.ring.at(t)
        if (ring.opacity > 1f) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f + SocialGrowthSpec.RING_Y_FRAC * 1280f * unit
                val r = SocialGrowthSpec.RING_D_FRAC * 720f * unit / 2f
                drawArc(
                    color = Color(0xFF2EE66B),
                    startAngle = -90f + ring.strokeTrimStart * 360f,
                    sweepAngle = (ring.strokeTrimEnd - ring.strokeTrimStart) * 360f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = 3f),
                    alpha = ring.opacity / 100f
                )
            }
        }
        val av = ins.avatar.at(t)
        if (av.opacity > 1f) {
            Image(
                bitmap = avatarArt,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = with(density) { (av.transformX * unit).toDp() },
                        y = with(density) { (av.transformY * unit).toDp() }
                    )
                    .size(with(density) { (boxPx * av.shapeScaleX * unit).toDp() })
                    .clip(CircleShape)
                    .graphicsLayer { alpha = av.opacity / 100f }
            )
        }
        for (count in listOf(ins.postsCount, ins.followersCount)) {
            val c = count.at(t)
            if (c.opacity <= 1f) continue
            Text(
                text = CounterEval.displayText(count.text ?: "0", count, t) ?: "0",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = with(density) { (SocialGrowthSpec.COUNT_FONT_FRAC * 720f * unit).toSp() },
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = with(density) { (c.transformX * unit).toDp() },
                        y = with(density) { (c.transformY * unit).toDp() }
                    )
                    .graphicsLayer { alpha = c.opacity / 100f }
            )
        }
        for (label in listOf(ins.postsLabel, ins.followersLabel)) {
            val c = label.at(t)
            if (c.opacity <= 1f) continue
            Text(
                text = label.text ?: "",
                color = Color(0xFFB9B9C0),
                fontWeight = FontWeight.SemiBold,
                fontSize = with(density) { (SocialGrowthSpec.LABEL_FONT_FRAC * 720f * unit).toSp() },
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = with(density) { (c.transformX * unit).toDp() },
                        y = with(density) { (c.transformY * unit).toDp() }
                    )
                    .graphicsLayer { alpha = c.opacity / 100f }
            )
        }
        for (tile in ins.tiles) {
            val c = tile.at(t)
            if (c.opacity <= 1f) continue
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = with(density) { (c.transformX * unit).toDp() },
                        y = with(density) { (c.transformY * unit).toDp() }
                    )
                    .size(with(density) { (boxPx * c.shapeScaleX * unit).toDp() })
                    .graphicsLayer { alpha = c.opacity / 100f }
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF081C0D), Color(0xFF23C75C))),
                        RoundedCornerShape(8.dp)
                    )
            )
        }
    }
}
