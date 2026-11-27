package com.example.ui.components

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.TextEffectIds
import com.example.engine.TextRevealValues
import com.example.engine.UiLabelAtlas
import com.example.engine.UiLabelStyle
import com.example.engine.rememberUiAtlas
import com.example.model.AnimPreset
import com.example.model.AnimPresets
import com.example.model.AnimSlot
import com.example.model.EditorGeometry
import com.example.model.FILL_NONE
import com.example.model.FILL_SOLID
import com.example.model.LayerType
import com.example.model.PanelGeometry
import com.example.model.TEXT_BG_NONE
import com.example.model.TEXT_BG_RECTANGLE
import com.example.model.TEXT_BG_ROUNDED
import com.example.model.TextEditGeometry
import com.example.model.TextPanelCatalog
import com.example.model.TextTemplates
import com.example.model.TimelineClip
import com.example.model.defaultTextLook
import com.example.model.textBgCornerApplies
import com.example.model.textBgShapeDef
import com.example.ui.text.FontCatalog
import com.example.ui.text.FontEntry
import com.example.ui.text.FontRepository
import com.example.ui.text.rememberFontFamily
import com.example.ui.theme.AmAccent
import com.example.ui.theme.AmBase
import com.example.ui.theme.AmInk
import com.example.ui.theme.AmPanel
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.TextPrimary
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The Text editor panel (builds 268/269 mechanics, build 271 UI).
 *
 * Mechanics (unchanged): a draggable bottom panel — the editor
 * canvas stays visible and LIVE above; the HEAD (grab pill + "Text"
 * + lime Done, exactly as build 269 shipped it) drags the height
 * 1:1 with no snap, the released height is session-remembered, the
 * panel lives in the editor's lower zone only, and IME handling is
 * the single adjustResize mechanism of build 269.
 *
 * Build 271 (Sarowar's order): everything BELOW the head is rebuilt
 * pixel-for-pixel from his HTML reference — the input row, the
 * primary TEXT tabs with the cyan underline, the sub-tab chips with
 * working search / bookmark / category filters, the reference
 * cards, circular colour rows with the conic wheel, the reference
 * sliders and pill toggles, in the reference tokens (sheet #121215,
 * card #222228, cyan #18D8E0). The geometry law is
 * [TextEditGeometry.Ref]; the catalog data (Aa presets, art
 * effects, bubble designs, categories, palettes) is
 * [TextPanelCatalog]. Every control drives a real stored field
 * (Size / Opacity / Glow / Case / bg border are new build-271
 * fields, rendered by all three painters in preview AND export).
 *
 * Keyboard state: while the main typing field holds focus (the
 * reference's kb-active state, bound to its main input), the sheet
 * body hides and the panel is exactly its chrome (head + input row
 * + primary tabs); the body returns at the remembered height when
 * the field loses focus. The inline search fields keep the body
 * visible so filtering stays usable while typing.
 *
 * Hosted islands, by platform law / proven limits only: the text
 * fields (Android IME), the template / font cards' styled text
 * (the app's exact multi-style painter [TextLayerContent]), the
 * bubble cards' mini previews and the effects' "ART" thumbs
 * (Compose text — the GL atlas blits one flat style), and the
 * colour picker dialog (already hosted app-wide).
 */
@Composable
fun TextEditPanel(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit,
    onEyedropper: ((Long) -> Unit) -> Unit,
    onClose: () -> Unit,
    /** The session's remembered panel height in dp (null = first open). */
    heightDp: Float?,
    /** Stores the released height (dp) for the rest of the session. */
    onHeightChange: (Float) -> Unit,
    /** The lower zone's height in dp — the panel may never exceed it. */
    availableHeightDp: Float,
    /** The keyboard's height inside that zone in dp (0 = closed). */
    imeBottomDp: Float,
    /** Applies an animation preset through the Animations tool's own
     *  ViewModel action; [durationMs] overrides the preset default. */
    onAnimApply: (slot: AnimSlot, preset: AnimPreset?, durationMs: Long?) -> Unit,
    /** Plays the applied animation of a slot, like the Animations tool does. */
    onAnimPreview: (slot: AnimSlot, durationMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    /** PATH-bearing shape layers of the project the text can attach to (build 191). */
    pathLayers: List<TimelineClip> = emptyList()
) {
    var text by remember(clip.id) { mutableStateOf(clip.text.orEmpty()) }
    val onText: (String) -> Unit = { v ->
        text = v
        onUpdate { it.copy(text = v, name = if (v.isBlank()) "Text" else "Text: ${v.take(24)}") }
    }
    var tab by remember(clip.id) { mutableStateOf(TextTab.TEMPLATES) }
    var fieldFocused by remember { mutableStateOf(false) }
    var inputExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val density = LocalDensity.current.density
    val pg = TextEditGeometry.Panel
    val minPx = pg.minHeightPx(density)
    val maxPx = pg.imeMaxHeightPx(availableHeightDp * density, imeBottomDp * density)
    val restingPx = pg.clampHeightPx(
        heightDp?.let { it * density }
            ?: pg.defaultHeightPx(availableHeightDp * density, density),
        minPx, maxPx
    )
    // The in-flight drag height: local so a drag move does not
    // recompose the whole editor screen; committed to the host (the
    // session memory) only on release — where it then stays.
    var dragPx by remember(clip.id) { mutableStateOf<Float?>(null) }
    // Build 271: the reference's kb-active collapse — while the main
    // typing field is focused (and not expanded into the multi-line
    // editor), the body hides and the panel is exactly its chrome.
    val collapsed = fieldFocused && !inputExpanded
    val shownPx = if (collapsed) pg.collapseHeightPx(density)
        else dragPx?.let { pg.clampHeightPx(it, minPx, maxPx) } ?: restingPx

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height((shownPx / density).dp)
            .clip(
                RoundedCornerShape(
                    topStart = TextEditGeometry.Ref.SHEET_RADIUS_DP.dp,
                    topEnd = TextEditGeometry.Ref.SHEET_RADIUS_DP.dp
                )
            )
            .background(AmBase)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        // The sheet's hairline top border (reference token).
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(TextEditGeometry.Ref.HAIRLINE_ARGB))
        )
        PanelHead(
            onDone = onClose,
            onDrag = { deltaY ->
                if (!collapsed) {
                    dragPx = pg.draggedHeightPx(dragPx ?: shownPx, deltaY, minPx, maxPx)
                }
            },
            onDragEnd = {
                dragPx?.let { onHeightChange(it / density) }
                dragPx = null
            }
        )
        RefInputRow(
            text = text,
            onText = onText,
            expanded = inputExpanded,
            onExpandedChange = { inputExpanded = it },
            onConfirm = onClose,
            onFocusChange = { fieldFocused = it }
        )
        RefPrimaryTabs(selected = tab) {
            tab = it
            focusManager.clearFocus()
        }
        if (!collapsed) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (tab) {
                    TextTab.TEMPLATES -> TemplatesTab(clip, onUpdate)
                    TextTab.FONTS -> FontsTab(clip, onUpdate)
                    TextTab.STYLES -> StylesTab(clip, onUpdate, pathLayers)
                    TextTab.EFFECTS -> EffectsTab(clip, onUpdate)
                    TextTab.ANIMATIONS -> AnimationsTab(clip, onUpdate, onAnimApply, onAnimPreview)
                    TextTab.BUBBLES -> BubblesTab(clip, onUpdate)
                }
            }
            // Build 276 (fresh design): the concept's full-width
            // bottom pill — it carries the panel's EXISTING Done /
            // close action (the same onClose as the head's Done pill
            // and the input row's ✓; editing stays live, nothing is
            // "applied" by it), so it is labelled Done, not Apply.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TextEditGeometry.Panel.DONE_BAR_H_DP.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Color(TextEditGeometry.Ref.CYAN_ARGB),
                            RoundedCornerShape(23.dp)
                        )
                        .clickable { onClose() }
                        .testTag("text_edit_done_bar"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Done",
                        color = Color(0xFF111114),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ── Panel chrome: the draggable head (build 269, unchanged) ───────

private enum class TextTab(val label: String) {
    TEMPLATES("Templates"), FONTS("Fonts"), STYLES("Styles"),
    EFFECTS("Effects"), ANIMATIONS("Animations"), BUBBLES("Bubbles")
}

/**
 * The panel's head: the grab pill on top, then "Text" + the lime
 * Done. The WHOLE strip is the drag surface (a tap on Done still
 * lands — the vertical-drag detector only takes over once the
 * finger actually moves vertically).
 */
@Composable
private fun PanelHead(
    onDone: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val g = TextEditGeometry.Panel
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(g.HEAD_H_DP.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
    ) {
        NativeVectorCanvas(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .width(g.GRAB_W_DP.dp)
                .height(g.GRAB_H_DP.dp),
            clearArgb = 0,
            drawKey = g,
            buildCmds = { w, h, _ ->
                addRoundRect(w / 2f, h / 2f, w, h, h / 2f, TxtDimArgb)
            }
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = TextEditGeometry.SCREEN_PAD_H_DP.dp,
                    end = TextEditGeometry.SCREEN_PAD_H_DP.dp,
                    top = 10.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlLabelLine("Text", UiLabelStyle.HEADER_NAME, TxtWhiteArgb)
            Spacer(Modifier.weight(1f))
            GlVioletPill(label = "Done", onClick = onDone, tag = "text_edit_done")
        }
    }
}

// ── Build 271: the reference input row ────────────────────────────

/**
 * The reference's text-entry row: the input box (#232329, radius
 * 10, focus = cyan border + #292931) with the expand icon inside at
 * the right (our existing multi-line expand toggle), and the 36dp
 * confirm ✓ (cyan check on white 7%) which closes the panel, like
 * Done. Hosted (BasicTextField) by the IME law.
 */
@Composable
private fun RefInputRow(
    text: String,
    onText: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onFocusChange: (Boolean) -> Unit
) {
    val r = TextEditGeometry.Ref
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = r.INPUT_PAD_H_DP.dp, end = r.INPUT_PAD_H_DP.dp,
                top = r.INPUT_PAD_TOP_DP.dp, bottom = r.INPUT_PAD_BOTTOM_DP.dp
            ),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .then(if (expanded) Modifier else Modifier.height(r.INPUT_BOX_H_DP.dp))
                .background(
                    Color(if (focused) r.INPUT_FOCUS_BG_ARGB else r.INPUT_BG_ARGB),
                    RoundedCornerShape(r.INPUT_RADIUS_DP.dp)
                )
                .border(
                    1.dp,
                    Color(if (focused) r.INPUT_FOCUS_BORDER_ARGB else r.INPUT_BORDER_ARGB),
                    RoundedCornerShape(r.INPUT_RADIUS_DP.dp)
                )
                .padding(horizontal = 12.dp, vertical = if (expanded) 9.dp else 0.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = text,
                onValueChange = onText,
                maxLines = if (expanded) 8 else 1,
                singleLine = !expanded,
                cursorBrush = SolidColor(Color(r.CYAN_ARGB)),
                textStyle = TextStyle(
                    color = Color(r.TEXT_ARGB),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight(500)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 30.dp)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocusChange(it.isFocused)
                    },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (text.isEmpty()) {
                            Text(
                                "Enter text...",
                                color = Color(r.PLACEHOLDER_ARGB),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight(500)
                            )
                        }
                        inner()
                    }
                }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(28.dp)
                    .clickable { onExpandedChange(!expanded) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Fullscreen, "Expand",
                    tint = Color(0xFF9D9DA8), modifier = Modifier.size(15.dp)
                )
            }
        }
        Spacer(Modifier.width(r.INPUT_GAP_DP.dp))
        Box(
            modifier = Modifier
                .size(r.CONFIRM_DP.dp)
                .background(
                    Color(r.CONFIRM_BG_ARGB),
                    RoundedCornerShape(r.CONFIRM_RADIUS_DP.dp)
                )
                .clickable { onConfirm() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Check, "Confirm",
                tint = Color(r.CYAN_ARGB), modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Build 271: primary tabs + sub-tab rows ────────────────────────

/**
 * The primary tabs: TEXT tabs (13.5sp — Medium inactive in
 * #8E8E98, SemiBold white active) with the 18×2.5dp cyan underline
 * centred under the active label; the row scrolls horizontally and
 * ends in the hairline.
 */
@Composable
private fun RefPrimaryTabs(selected: TextTab, onSelect: (TextTab) -> Unit) {
    val r = TextEditGeometry.Ref
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val tabs = TextTab.values().toList()
    fun resolveAll(): List<UiLabelAtlas.Entry?> = tabs.map { t ->
        atlas.resolve(
            t.label,
            if (t == selected) UiLabelStyle.TEXT_135_SEMI else UiLabelStyle.TEXT_135_MED
        )
    }
    var entries = resolveAll()
    if (atlas.overflowed) {
        atlas.reset()
        entries = resolveAll()
    }
    val resolved = entries
    val advances = resolved.map { it?.advance ?: 0f }
    val lefts = r.tabLeftsPx(advances, density)
    val contentW = r.tabsContentWPx(advances, density)
    val rowH = TextEditGeometry.Panel.TAB_ROW_H_DP.dp
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.width((contentW / density).dp).height(rowH)) {
                NativeVectorCanvas(
                    modifier = Modifier.fillMaxSize(),
                    clearArgb = 0,
                    atlas = atlas,
                    drawKey = listOf(resolved, selected, contentW, atlas.generation),
                    buildCmds = { w, h, dd ->
                        val pad = UiLabelAtlas.PAD.toFloat()
                        tabs.forEachIndexed { i, t ->
                            val e = resolved[i] ?: return@forEachIndexed
                            val active = t == selected
                            val style = if (active) UiLabelStyle.TEXT_135_SEMI
                                else UiLabelStyle.TEXT_135_MED
                            val textH = atlas.textHeightPx(style)
                            val fm = atlas.fontMetrics(style)
                            val top = EditorGeometry.centredTextTopPx(
                                r.TAB_PAD_TOP_DP * dd,
                                textH + r.TAB_LABEL_BOTTOM_DP * dd +
                                    r.UNDERLINE_H_DP * dd,
                                textH
                            )
                            val baseline = PanelGeometry.lineBaselinePx(top, textH, fm.ascent, fm.descent)
                            val (dstX, dstY) = EditorGeometry.textDst(
                                lefts[i], baseline, e.bearing, e.base, pad
                            )
                            addText(
                                e.index, dstX, dstY,
                                0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                                tintArgb = if (active) r.TEXT_ARGB else r.SECONDARY_ARGB
                            )
                            if (active) {
                                val uTop = top + textH + r.TAB_LABEL_BOTTOM_DP * dd
                                addRoundRect(
                                    r.underlineLeftPx(lefts[i], advances[i], dd) +
                                        r.UNDERLINE_W_DP * dd / 2f,
                                    uTop + r.UNDERLINE_H_DP * dd / 2f,
                                    r.UNDERLINE_W_DP * dd, r.UNDERLINE_H_DP * dd,
                                    r.UNDERLINE_RADIUS_DP * dd, r.CYAN_ARGB
                                )
                            }
                        }
                    }
                )
                tabs.forEachIndexed { i, t ->
                    Box(
                        Modifier
                            .offset(x = (lefts[i] / density).dp, y = 0.dp)
                            .width((advances[i] / density).dp + 8.dp)
                            .height(rowH)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelect(t) }
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(r.HAIRLINE_ARGB))
        )
    }
}

/** One sub-tab row item: an icon action or a text chip. */
private class RefSubItem(
    val key: String,
    val label: String?,
    val icon: SubIcon?
)

private enum class SubIcon { SEARCH, BOOKMARK, BOOKMARK_ON }

/**
 * A tab's sub-header row (reference metrics): the search and
 * bookmark icon items lead where the tab has them, then the text
 * items — the active one white/SemiBold on the white-10% chip
 * (radius 6). The row scrolls horizontally.
 */
@Composable
private fun RefSubTabs(
    items: List<RefSubItem>,
    selectedKey: String,
    searchOpen: Boolean,
    onSelect: (RefSubItem) -> Unit
) {
    val r = TextEditGeometry.Ref
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val searchPainter = rememberVectorPainter(Icons.Default.Search)
    val bookmarkPainter = rememberVectorPainter(Icons.Default.BookmarkBorder)
    val bookmarkOnPainter = rememberVectorPainter(Icons.Default.Bookmark)
    fun resolveAll(): List<Pair<UiLabelAtlas.Entry?, UiLabelAtlas.Entry?>> =
        items.map { item ->
            val t = item.label?.let {
                atlas.resolve(
                    it,
                    if (item.key == selectedKey) UiLabelStyle.SEMI_125
                    else UiLabelStyle.TEXT_125_MED
                )
            }
            val ic = when (item.icon) {
                SubIcon.SEARCH -> atlas.iconEntry(
                    "refsub:search", searchPainter, (r.SUB_ICON_DP * density).roundToInt(), density
                )
                SubIcon.BOOKMARK -> atlas.iconEntry(
                    "refsub:bookmark", bookmarkPainter, (r.SUB_ICON_DP * density).roundToInt(), density
                )
                SubIcon.BOOKMARK_ON -> atlas.iconEntry(
                    "refsub:bookmark_on", bookmarkOnPainter, (r.SUB_ICON_DP * density).roundToInt(), density
                )
                null -> null
            }
            t to ic
        }
    var entries = resolveAll()
    if (atlas.overflowed) {
        atlas.reset()
        entries = resolveAll()
    }
    val resolved = entries
    val widths = items.mapIndexed { i, item ->
        val (te, ie) = resolved[i]
        when {
            te != null -> r.subItemWPx(te.advance, density)
            ie != null -> (r.SUB_ICON_DP + 2f * r.SUB_ITEM_PAD_H_DP) * density
            else -> 0f
        }
    }
    val lefts = r.subLeftsPx(widths, density)
    val contentW = if (widths.isEmpty()) 0f else lefts.last() + widths.last()
    val rowH = (r.SUB_PAD_TOP_DP + 24f + r.SUB_PAD_BOTTOM_DP).dp
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = r.SUB_PAD_H_DP.dp)
    ) {
        Box(Modifier.width((contentW / density).dp).height(rowH)) {
            NativeVectorCanvas(
                modifier = Modifier.fillMaxSize(),
                clearArgb = 0,
                atlas = atlas,
                drawKey = listOf(resolved, selectedKey, searchOpen, contentW, atlas.generation),
                buildCmds = { w, h, dd ->
                    val pad = UiLabelAtlas.PAD.toFloat()
                    val itemTop = r.SUB_PAD_TOP_DP * dd
                    val itemH = h - (r.SUB_PAD_TOP_DP + r.SUB_PAD_BOTTOM_DP) * dd
                    items.forEachIndexed { i, item ->
                        val (te, ie) = resolved[i]
                        val cx = lefts[i] + widths[i] / 2f
                        val cy = itemTop + itemH / 2f
                        if (te != null) {
                            // Build 276: every text chip is a pill —
                            // the active one filled accent, the rest grey.
                            addRoundRect(
                                cx, cy, widths[i], itemH,
                                r.SUB_CHIP_RADIUS_DP * dd,
                                if (item.key == selectedKey) r.SUB_ACTIVE_BG_ARGB
                                else r.SUB_IDLE_BG_ARGB
                            )
                        }
                        if (ie != null) {
                            val tint = when {
                                item.icon == SubIcon.SEARCH && searchOpen -> r.CYAN_ARGB
                                item.key == selectedKey -> r.TEXT_ARGB
                                else -> r.SECONDARY_ARGB
                            }
                            addText(
                                ie.index, cx - ie.rectW / 2f, cy - ie.rectH / 2f,
                                0f, 0f, ie.rectW.toFloat(), ie.rectH.toFloat(),
                                tintArgb = tint
                            )
                        }
                        if (te != null) {
                            val style = if (item.key == selectedKey) UiLabelStyle.SEMI_125
                                else UiLabelStyle.TEXT_125_MED
                            val textH = atlas.textHeightPx(style)
                            val fm = atlas.fontMetrics(style)
                            val top = EditorGeometry.centredTextTopPx(itemTop, itemH, textH)
                            val baseline = PanelGeometry.lineBaselinePx(top, textH, fm.ascent, fm.descent)
                            val (dstX, dstY) = EditorGeometry.textDst(
                                lefts[i] + r.SUB_ITEM_PAD_H_DP * dd, baseline, te.bearing, te.base, pad
                            )
                            addText(
                                te.index, dstX, dstY,
                                0f, 0f, te.rectW.toFloat(), te.rectH.toFloat(),
                                tintArgb = if (item.key == selectedKey) r.SUB_ACTIVE_INK_ARGB
                                    else r.SECONDARY_ARGB
                            )
                        }
                    }
                }
            )
            items.forEachIndexed { i, item ->
                Box(
                    Modifier
                        .offset(x = (lefts[i] / density).dp, y = 0.dp)
                        .width((widths[i] / density).dp)
                        .height(rowH)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(item) }
                )
            }
        }
    }
}

/** The inline filter field under a sub-tab row (hosted, IME law). */
@Composable
private fun RefSearchField(tabLabel: String, query: String, onQuery: (String) -> Unit) {
    val r = TextEditGeometry.Ref
    Box(
        Modifier
            .fillMaxWidth()
            .padding(
                start = r.SUB_PAD_H_DP.dp, end = r.SUB_PAD_H_DP.dp,
                bottom = r.SEARCH_PAD_BOTTOM_DP.dp
            )
            .background(Color(r.SEARCH_BG_ARGB), RoundedCornerShape(r.SEARCH_RADIUS_DP.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            cursorBrush = SolidColor(Color(r.CYAN_ARGB)),
            textStyle = TextStyle(color = Color(r.TEXT_ARGB), fontSize = 12.5.sp),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (query.isEmpty()) {
                        Text(
                            "Filter $tabLabel...",
                            color = Color(r.PLACEHOLDER_ARGB), fontSize = 12.5.sp
                        )
                    }
                    inner()
                }
            }
        )
    }
}

// ── Bookmarks (working, persisted) ────────────────────────────────

private object TextPanelBookmarks {
    private const val PREFS = "text_panel_bookmarks"

    fun load(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("keys", emptySet()) ?: emptySet()

    fun toggle(context: Context, key: String): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = (prefs.getStringSet("keys", emptySet()) ?: emptySet()).toMutableSet()
        if (!next.add(key)) next.remove(key)
        prefs.edit().putStringSet("keys", next).apply()
        return next
    }
}

/** The bookmark star pin on a card's top-right corner. */
@Composable
private fun RefCardPin(bookmarked: Boolean, onToggle: () -> Unit) {
    val r = TextEditGeometry.Ref
    Box(
        modifier = Modifier
            .size(r.PIN_DP.dp)
            .background(Color(r.PIN_BG_ARGB), RoundedCornerShape(r.PIN_RADIUS_DP.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (bookmarked) Icons.Default.Star else Icons.Outlined.StarBorder,
            "Bookmark",
            tint = Color(if (bookmarked) r.PIN_ON_ARGB else r.PIN_OFF_ARGB),
            modifier = Modifier.size(11.dp)
        )
    }
}

// ── Build 271: reference atoms ────────────────────────────────────

/** The reference slider value format: integers plain, steps of
 *  0.5/0.1 with one decimal, 0.05 with two — trailing zeros cut. */
private fun fmtRefVal(v: Float, step: Float, unit: String): String {
    val s = when {
        step >= 1f -> v.roundToInt().toString()
        step >= 0.1f -> String.format(Locale.US, "%.1f", v).trimEnd('0').trimEnd('.')
        else -> String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }
    return s + unit
}

/**
 * One reference slider row: the 70dp label (12sp Medium #8E8E98),
 * the slider (3.5dp #36363E track, 16dp white thumb with the 2dp
 * cyan ring), and the 42dp right-aligned tabular value. The value
 * is stepped here; drag and tap both move the thumb.
 */
@Composable
private fun RefSliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    unit: String,
    onValueChange: (Float) -> Unit,
    onValueFinished: (() -> Unit)? = null
) {
    val r = TextEditGeometry.Ref
    val density = LocalDensity.current.density
    val shown = value.coerceIn(min, max)
    val fraction = if (max > min) (shown - min) / (max - min) else 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = r.CONTENT_PAD_H_DP.dp,
                vertical = r.SLIDER_ROW_PAD_V_DP.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(r.SLIDER_LABEL_W_DP.dp)) {
            GlLabelLine(label, UiLabelStyle.SHEET_ITEM_MED, r.SECONDARY_ARGB)
        }
        Spacer(Modifier.width(r.SLIDER_GAP_DP.dp))
        BoxWithConstraints(Modifier.weight(1f).height(28.dp)) {
            val spanW = maxWidth.value * density
            fun applyAt(xPx: Float) {
                val f = r.sliderFraction(xPx, spanW, density)
                val raw = min + f * (max - min)
                val stepped = (min + ((raw - min) / step).roundToInt() * step)
                    .coerceIn(min, max)
                onValueChange(stepped)
            }
            NativeVectorCanvas(
                modifier = Modifier.fillMaxSize(),
                clearArgb = 0,
                drawKey = listOf(fraction),
                buildCmds = { w, h, dd ->
                    val cy = h / 2f
                    addRoundRect(
                        w / 2f, cy, w, r.SLIDER_TRACK_H_DP * dd,
                        r.SLIDER_TRACK_H_DP * dd / 2f, r.SLIDER_TRACK_ARGB
                    )
                    val tx = r.sliderThumbXPx(fraction, w, dd)
                    // Build 276: the concept's accent-filled track
                    // portion, up to the thumb.
                    if (tx > 0f) {
                        addRoundRect(
                            tx / 2f, cy, tx, r.SLIDER_TRACK_H_DP * dd,
                            r.SLIDER_TRACK_H_DP * dd / 2f, r.CYAN_ARGB
                        )
                    }
                    // Build 276: the thumb is a solid accent disc.
                    addCircle(
                        tx, cy, r.SLIDER_THUMB_DP * dd / 2f,
                        r.CYAN_ARGB, r.SLIDER_THUMB_BORDER_DP * dd, r.SHEET_ARGB
                    )
                }
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(spanW, min, max, step) {
                        detectDragGestures(
                            onDragStart = { applyAt(it.x) },
                            onDrag = { change, _ -> applyAt(change.position.x) },
                            onDragEnd = { onValueFinished?.invoke() },
                            onDragCancel = { onValueFinished?.invoke() }
                        )
                    }
            )
        }
        Spacer(Modifier.width(r.SLIDER_GAP_DP.dp))
        Box(
            Modifier.width(r.SLIDER_VALUE_W_DP.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            GlLabelLine(
                fmtRefVal(shown, step, unit),
                UiLabelStyle.TEXT_115_SEMI_TNUM, r.TEXT_ARGB
            )
        }
    }
}

/** One item of a reference colour row. */
private sealed class RefSwatch {
    object Wheel : RefSwatch()
    object Transparent : RefSwatch()
    class ColorSw(val argb: Long) : RefSwatch()
}

/** The conic wheel's slice colours: the CSS conic-gradient stops
 *  (red → yellow → green → teal → blue → purple → red) interpolated
 *  in RGB at each slice's mid-angle. */
private fun wheelSliceArgb(index: Int, slices: Int): Int {
    val stops = listOf(
        0f to 0xFFFF3B30, 60f to 0xFFFFCC00, 120f to 0xFF34C759,
        180f to 0xFF00C7BE, 240f to 0xFF007AFF, 300f to 0xFFAF52DE,
        360f to 0xFFFF3B30
    )
    val deg = (index + 0.5f) / slices * 360f
    var i = 0
    while (i < stops.size - 2 && stops[i + 1].first < deg) i++
    val (d0, c0) = stops[i]
    val (d1, c1) = stops[i + 1]
    val f = if (d1 > d0) (deg - d0) / (d1 - d0) else 0f
    fun ch(shift: Int): Int {
        val a = (c0 shr shift) and 0xFF
        val b = (c1 shr shift) and 0xFF
        return (a + (b - a) * f).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * A reference circular colour row: 26dp circles with the 2dp
 * white-14% border (white when selected), scrolling horizontally.
 * The wheel opens the colour picker; the transparent swatch is the
 * circle with the diagonal slash.
 */
@Composable
private fun RefColorRow(
    items: List<RefSwatch>,
    selected: RefSwatch?,
    onPick: (RefSwatch) -> Unit
) {
    val r = TextEditGeometry.Ref
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = r.CONTENT_PAD_H_DP.dp,
                vertical = 0.dp
            )
            .padding(top = r.SWATCH_PAD_TOP_DP.dp, bottom = r.SWATCH_PAD_BOTTOM_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(r.SWATCH_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            val isSel = when {
                item is RefSwatch.ColorSw && selected is RefSwatch.ColorSw ->
                    item.argb == selected.argb
                item is RefSwatch.Wheel -> selected is RefSwatch.Wheel
                item is RefSwatch.Transparent -> selected is RefSwatch.Transparent
                else -> false
            }
            val scale = remember { Animatable(1f) }
            Box(Modifier.size(r.SWATCH_DP.dp)) {
                NativeVectorCanvas(
                    modifier = Modifier.fillMaxSize(),
                    clearArgb = 0,
                    drawKey = listOf(item, isSel, scale.value),
                    buildCmds = { w, h, dd ->
                        setScaleAbout(w / 2f, h / 2f, scale.value)
                        val cx = w / 2f
                        val cy = h / 2f
                        val rad = w / 2f - dd
                        val borderW = r.SWATCH_BORDER_W_DP * dd
                        // Build 276: the selected swatch's ring is the accent.
                        val border = if (isSel) r.CYAN_ARGB else r.SWATCH_BORDER_ARGB
                        when (item) {
                            is RefSwatch.ColorSw ->
                                addCircle(cx, cy, rad, item.argb.toInt(), borderW, border)
                            is RefSwatch.Transparent -> {
                                addCircle(cx, cy, rad, 0xFF2A3542.toInt(), borderW, border)
                                val d = rad * 0.72f
                                addLine(
                                    cx - d, cy + d, cx + d, cy - d,
                                    2f * dd, 0xFFFF5F57.toInt()
                                )
                            }
                            is RefSwatch.Wheel -> {
                                val slices = 24
                                for (s in 0 until slices) {
                                    addArc(
                                        cx, cy, rad, rad,
                                        -90f + s * (360f / slices), 360f / slices + 1f,
                                        0f, wheelSliceArgb(s, slices), 0,
                                        capRound = false, closedPie = true
                                    )
                                }
                                addCircle(cx, cy, rad, 0, borderW, border)
                            }
                        }
                        clearTransform()
                    }
                )
                ChromePressBox(
                    scale, 0.85f, { onPick(item) },
                    Modifier.fillMaxSize(),
                    description = "Color"
                )
            }
        }
    }
}

/**
 * A reference pill strip: pills of min-width 48 / height 36 /
 * radius 8 on the card colour, 13sp Bold; selected = cyan border +
 * cyan-14% fill. Scrolls horizontally.
 */
@Composable
private fun RefPillStrip(
    items: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val r = TextEditGeometry.Ref
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    fun resolveAll(): List<UiLabelAtlas.Entry?> =
        items.map { atlas.resolve(it.first, UiLabelStyle.TEXT_13_BOLD) }
    var entries = resolveAll()
    if (atlas.overflowed) {
        atlas.reset()
        entries = resolveAll()
    }
    val resolved = entries
    val widths = resolved.map { r.pillWPx(it?.advance ?: 0f, density) }
    val lefts = r.pillLeftsPx(widths, density)
    val contentW = if (widths.isEmpty()) 0f else lefts.last() + widths.last()
    val scales = remember(items.map { it.first }) { items.map { Animatable(1f) } }
    val scaleValues = scales.map { it.value }
    Row(modifier.horizontalScroll(rememberScrollState())) {
        Box(
            Modifier
                .width((contentW / density).dp)
                .height(r.PILL_H_DP.dp)
        ) {
            NativeVectorCanvas(
                modifier = Modifier.fillMaxSize(),
                clearArgb = 0,
                atlas = atlas,
                drawKey = listOf(resolved, items.map { it.second }, scaleValues, atlas.generation),
                buildCmds = { w, h, dd ->
                    val pad = UiLabelAtlas.PAD.toFloat()
                    val style = UiLabelStyle.TEXT_13_BOLD
                    val textH = atlas.textHeightPx(style)
                    val fm = atlas.fontMetrics(style)
                    items.forEachIndexed { i, (label, sel) ->
                        val cx = lefts[i] + widths[i] / 2f
                        setScaleAbout(cx, h / 2f, scaleValues[i])
                        addRoundRect(
                            cx, h / 2f, widths[i], h, r.PILL_RADIUS_DP * dd,
                            if (sel) r.PILL_SEL_BG_ARGB else r.CARD_ARGB,
                            if (sel) 1f * dd else 0f,
                            if (sel) r.CYAN_ARGB else 0
                        )
                        val e = resolved[i]
                        if (e != null) {
                            val top = EditorGeometry.centredTextTopPx(0f, h, textH)
                            val baseline = PanelGeometry.lineBaselinePx(top, textH, fm.ascent, fm.descent)
                            val originX = EditorGeometry.centredOriginPx(cx, e.advance)
                            val (dstX, dstY) = EditorGeometry.textDst(
                                originX, baseline, e.bearing, e.base, pad
                            )
                            addText(
                                e.index, dstX, dstY,
                                0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                                tintArgb = r.TEXT_ARGB
                            )
                        }
                        clearTransform()
                    }
                }
            )
            items.forEachIndexed { i, _ ->
                ChromePressBox(
                    scales[i], 0.94f, { onSelect(i) },
                    Modifier
                        .offset(x = (lefts[i] / density).dp, y = 0.dp)
                        .width((widths[i] / density).dp)
                        .height(r.PILL_H_DP.dp),
                    description = items[i].first
                )
            }
        }
    }
}

/** The padded pill row (content margins + the row's vertical pad). */
@Composable
private fun RefPillRow(
    items: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit
) {
    val r = TextEditGeometry.Ref
    RefPillStrip(
        items, onSelect,
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = r.CONTENT_PAD_H_DP.dp,
                vertical = r.PILL_ROW_PAD_V_DP.dp
            )
    )
}

/** A full-width reference action button (pill form, flex:1). */
@Composable
private fun RefActionButton(label: String, onClick: () -> Unit) {
    val r = TextEditGeometry.Ref
    val scale = remember { Animatable(1f) }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = r.CONTENT_PAD_H_DP.dp, vertical = r.PILL_ROW_PAD_V_DP.dp)
            .height(r.PILL_H_DP.dp)
    ) {
        NativeVectorCanvas(
            modifier = Modifier.fillMaxSize(),
            clearArgb = 0,
            drawKey = listOf(scale.value),
            buildCmds = { w, h, dd ->
                setScaleAbout(w / 2f, h / 2f, scale.value)
                addRoundRect(w / 2f, h / 2f, w, h, r.PILL_RADIUS_DP * dd, r.CARD_ARGB)
                clearTransform()
            }
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GlLabelLine(label, UiLabelStyle.TEXT_13_BOLD, r.TEXT_ARGB)
        }
        ChromePressBox(scale, 0.97f, onClick, Modifier.fillMaxSize(), description = label)
    }
}

/**
 * A reference card: card-colour fill, radius 9, the 1.5px cyan
 * border when selected, press scale 0.96. Tabs that need the
 * bookmark pin compose [RefCardPin] inside the content themselves
 * (it must sit above the press box).
 */
@Composable
private fun RefCardBox(
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val r = TextEditGeometry.Ref
    val scale = remember { Animatable(1f) }
    Box(modifier.clip(RoundedCornerShape(r.CARD_RADIUS_DP.dp))) {
        NativeVectorCanvas(
            modifier = Modifier.fillMaxSize(),
            clearArgb = 0,
            drawKey = listOf(selected, scale.value),
            buildCmds = { w, h, dd ->
                setScaleAbout(w / 2f, h / 2f, scale.value)
                addRoundRect(
                    w / 2f, h / 2f, w, h, r.CARD_RADIUS_DP * dd, r.CARD_ARGB,
                    if (selected) r.CARD_BORDER_W_DP * dd else 0f,
                    if (selected) r.CYAN_ARGB else 0
                )
                clearTransform()
            }
        )
        ChromePressBox(scale, 0.96f, onClick, Modifier.fillMaxSize(), description = "Card")
        // Content composes ABOVE the press box: the styled text is
        // inert (taps fall through to the press box), while a
        // bookmark pin inside the content keeps its own tap.
        content()
    }
}

/** The reference empty state line. */
@Composable
private fun RefEmptyText(text: String) {
    val r = TextEditGeometry.Ref
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = r.EMPTY_PAD_V_DP.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color(r.EMPTY_ARGB), fontSize = 12.5.sp)
    }
}

/** A wrapped note line (the sections' explanatory texts). */
@Composable
private fun RefNoteText(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = TextEditGeometry.Ref.CONTENT_PAD_H_DP.dp, vertical = 4.dp)
    ) {
        GlTextBlock(
            text = text,
            style = UiLabelStyle.ED_TEXT,
            colorArgb = TxtDimArgb,
            modifier = Modifier.fillMaxWidth(),
            align = TextAlign.Start
        )
    }
}

/** A tab's scrolling content column (reference content padding). */
@Composable
private fun RefContentColumn(content: @Composable () -> Unit) {
    val r = TextEditGeometry.Ref
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = r.CONTENT_PAD_H_DP.dp, end = r.CONTENT_PAD_H_DP.dp,
                top = r.CONTENT_PAD_TOP_DP.dp, bottom = r.CONTENT_PAD_BOTTOM_DP.dp
            )
    ) {
        content()
    }
}

// ── Colours (the build-217 tokens, as ARGB for the GL draws) ──────

private val TxtSurfaceArgb = AmPanel.toArgb()
private val TxtAccentArgb = AmAccent.toArgb()
private val TxtInkArgb = AmInk.toArgb()
private val TxtWhiteArgb = TextPrimary.toArgb()
private val TxtDimArgb = AmTextDim.toArgb()

// ── Templates tab ─────────────────────────────────────────────────

/**
 * Templates: the sub-header (search / bookmark / Trending / Black
 * Friday / Classic / NEW — all working) over the 4-column square
 * grid: the Default tile first, then the [TextTemplates] bundles,
 * each card rendering the USER's text in the bundle's look (the
 * hosted [TextLayerContent] painter). The reference's filter rule
 * ([TextPanelCatalog.refFilter]): bookmark view = bookmarked only,
 * a search query searches all by name, Trending shows all, other
 * categories filter by the bundle's tags.
 */
@Composable
private fun TemplatesTab(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit
) {
    val context = LocalContext.current
    var subTab by remember(clip.id) { mutableStateOf("Trending") }
    var searchOpen by remember(clip.id) { mutableStateOf(false) }
    var query by remember(clip.id) { mutableStateOf("") }
    var bookmarks by remember { mutableStateOf(TextPanelBookmarks.load(context)) }
    var selectedName by remember(clip.id) { mutableStateOf<String?>(null) }

    val cards: List<Triple<String, Set<String>, (TimelineClip) -> TimelineClip>> =
        listOf(
            Triple(
                "Default", TextPanelCatalog.DEFAULT_TEMPLATE_CATS,
                { c: TimelineClip -> defaultTextLook(c) }
            )
        ) + TextTemplates.map { t ->
            Triple(
                t.name, TextPanelCatalog.TEMPLATE_CATS[t.name] ?: emptySet(),
                { c: TimelineClip -> t.applyTo(c) }
            )
        }
    val shown = cards.filter { (name, cats, _) ->
        TextPanelCatalog.refFilter(
            cats, subTab, if (searchOpen) query.trim() else "",
            name, bookmarks.contains("tpl:$name")
        )
    }
    Column(Modifier.fillMaxSize()) {
        RefSubTabs(
            items = listOf(
                RefSubItem("search", null, SubIcon.SEARCH),
                RefSubItem(
                    "bookmark", null,
                    if (subTab == "bookmark") SubIcon.BOOKMARK_ON else SubIcon.BOOKMARK
                )
            ) + listOf("Trending", "Black Friday", "Classic", "NEW").map {
                RefSubItem(it, it, null)
            },
            selectedKey = subTab,
            searchOpen = searchOpen
        ) { item ->
            when (item.key) {
                "search" -> searchOpen = !searchOpen
                "bookmark" -> subTab = "bookmark"
                else -> subTab = item.key
            }
        }
        if (searchOpen) {
            RefSearchField("Templates", query) { query = it }
        }
        RefContentColumn {
            if (shown.isEmpty()) {
                RefEmptyText("No templates found")
            } else {
                shown.chunked(4).forEach { rowCards ->
                    Row(horizontalArrangement = Arrangement.spacedBy(TextEditGeometry.Grid.GAP_DP.dp)) {
                        rowCards.forEach { (name, _, apply) ->
                            val bmKey = "tpl:$name"
                            RefCardBox(
                                selected = selectedName == name,
                                modifier = Modifier.weight(1f).aspectRatio(1f),
                                onClick = {
                                    selectedName = name
                                    onUpdate(apply)
                                }
                            ) {
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                    TextLayerContent(
                                        clip = apply(clip),
                                        frameWidth = maxWidth,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                    if (name != "Default") {
                                        Box(
                                            Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(TextEditGeometry.Ref.PIN_INSET_DP.dp)
                                        ) {
                                            RefCardPin(bookmarks.contains(bmKey)) {
                                                bookmarks = TextPanelBookmarks.toggle(context, bmKey)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        repeat(4 - rowCards.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(TextEditGeometry.Grid.GAP_DP.dp))
                }
            }
        }
    }
}

// ── Fonts tab ─────────────────────────────────────────────────────

/** A catalogue font's reference categories. */
private fun fontRefCats(entry: FontEntry, downloaded: Boolean): Set<String> {
    val cats = mutableSetOf<String>()
    when {
        entry.category == FontCatalog.USER_CATEGORY -> cats += "My Fonts"
        entry.name == "Roboto" -> { cats += "My Fonts"; cats += "Trending" }
        entry.name == "Serif" -> { cats += "My Fonts"; cats += "Classic" }
        entry.name == "Monospace" -> { cats += "My Fonts"; cats += "Classic" }
        entry.name == "Cursive" -> { cats += "My Fonts"; cats += "NEW" }
        entry.category == "Display" -> { cats += "Trending"; cats += "NEW" }
        entry.category == "Sans Serif" -> cats += "Trending"
        entry.category == "Serif" -> cats += "Classic"
        entry.category == "Handwriting" -> cats += "NEW"
        entry.category == "Mono" -> cats += "Classic"
        entry.category == "Bangla" -> { cats += "NEW"; cats += "Trending" }
    }
    if (downloaded && entry.category != FontCatalog.USER_CATEGORY) cats += "My Fonts"
    return cats
}

/**
 * Fonts: the weight pill row at the tab's top (kept from build 268
 * — the reference has no weight row; it rides as one slim reference
 * pill row), the sub-header (search / bookmark / My Fonts /
 * Trending / Classic / NEW — all working), and the 3-column grid
 * of 54dp cards: the font's name in its own face, the download
 * badge (arrow → cyan check once the face is on the device) and
 * the bookmark pin. Add font + the device-only note ride below the
 * grid (app functionality the reference doesn't carry).
 */
@Composable
private fun FontsTab(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var subTab by remember(clip.id) { mutableStateOf("My Fonts") }
    var searchOpen by remember(clip.id) { mutableStateOf(false) }
    var query by remember(clip.id) { mutableStateOf("") }
    var bookmarks by remember { mutableStateOf(TextPanelBookmarks.load(context)) }
    var userFonts by remember { mutableStateOf(FontRepository.userFonts(context)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val name = FontRepository.importUserFont(context, uri)
            if (name != null) {
                userFonts = FontRepository.userFonts(context)
                onUpdate { it.copy(fontFamily = name) }
            }
        }
    }

    val all = remember(userFonts) {
        userFonts.map { FontEntry(it, FontCatalog.USER_CATEGORY) } + FontCatalog.all
    }
    val shown = all.filter { entry ->
        val downloaded = FontRepository.resolveNow(context, entry.name) != null
        when {
            query.isNotBlank() -> entry.name.contains(query.trim(), ignoreCase = true)
            subTab == "bookmark" -> bookmarks.contains("font:${entry.name}")
            else -> fontRefCats(entry, downloaded).contains(subTab)
        }
    }
    Column(Modifier.fillMaxSize()) {
        // The weight row: a slim label + the reference pill strip.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = TextEditGeometry.Ref.CONTENT_PAD_H_DP.dp,
                    vertical = TextEditGeometry.Ref.PILL_ROW_PAD_V_DP.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlLabelLine("Weight", UiLabelStyle.SHEET_ITEM_MED, TextEditGeometry.Ref.SECONDARY_ARGB)
            Spacer(Modifier.width(10.dp))
            RefPillStrip(
                items = listOf(200, 400, 500, 600, 700, 800, 900).map {
                    "$it" to (clip.fontWeight == it)
                },
                onSelect = { i ->
                    val w = listOf(200, 400, 500, 600, 700, 800, 900)[i]
                    onUpdate { it.copy(fontWeight = w) }
                },
                modifier = Modifier.weight(1f)
            )
        }
        RefSubTabs(
            items = listOf(
                RefSubItem("search", null, SubIcon.SEARCH),
                RefSubItem(
                    "bookmark", null,
                    if (subTab == "bookmark") SubIcon.BOOKMARK_ON else SubIcon.BOOKMARK
                )
            ) + listOf("My Fonts", "Trending", "Classic", "NEW").map {
                RefSubItem(it, it, null)
            },
            selectedKey = subTab,
            searchOpen = searchOpen
        ) { item ->
            when (item.key) {
                "search" -> searchOpen = !searchOpen
                "bookmark" -> subTab = "bookmark"
                else -> subTab = item.key
            }
        }
        if (searchOpen) {
            RefSearchField("Fonts", query) { query = it }
        }
        RefContentColumn {
            if (shown.isEmpty()) {
                RefEmptyText("No fonts found")
            } else {
                shown.chunked(3).forEach { rowFonts ->
                    Row(horizontalArrangement = Arrangement.spacedBy(TextEditGeometry.Grid.GAP_DP.dp)) {
                        rowFonts.forEach { f ->
                            FontRefTile(
                                name = f.name,
                                selected = clip.fontFamily == f.name,
                                bookmarked = bookmarks.contains("font:${f.name}"),
                                onToggleBookmark = {
                                    bookmarks = TextPanelBookmarks.toggle(context, "font:${f.name}")
                                },
                                modifier = Modifier.weight(1f)
                            ) { onUpdate { it.copy(fontFamily = f.name) } }
                        }
                        repeat(3 - rowFonts.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(TextEditGeometry.Grid.GAP_DP.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            RefPillStrip(
                items = listOf("+ Add font" to false),
                onSelect = {
                    picker.launch(
                        arrayOf(
                            "font/ttf", "font/otf", "font/sfnt",
                            "application/x-font-ttf", "application/x-font-otf",
                            "application/octet-stream"
                        )
                    )
                }
            )
            Spacer(Modifier.height(8.dp))
            RefNoteText("Fonts you add are stored on this device only — they don't travel with the project file.")
        }
    }
}

/** One Fonts-grid tile (54dp): the name in its own face, the
 *  download badge bottom-left, the bookmark pin top-right. */
@Composable
private fun FontRefTile(
    name: String,
    selected: Boolean,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val r = TextEditGeometry.Ref
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val atlas = rememberUiAtlas()
    val entry = rememberFontChipEntry(atlas, name)
    val downloaded = FontRepository.resolveNow(context, name) != null
    val dlPainter = rememberVectorPainter(Icons.Default.Download)
    val okPainter = rememberVectorPainter(Icons.Default.Check)
    fun resolveBadge(): UiLabelAtlas.Entry? = atlas.iconEntry(
        if (downloaded) "fontbadge:ok" else "fontbadge:dl",
        if (downloaded) okPainter else dlPainter,
        (13f * density).roundToInt(), density
    )
    var badge = resolveBadge()
    if (atlas.overflowed) {
        atlas.reset()
        badge = resolveBadge()
    }
    val badgeEntry = badge
    val scale = remember { Animatable(1f) }
    Box(modifier.height(r.FONT_CARD_H_DP.dp).clip(RoundedCornerShape(r.CARD_RADIUS_DP.dp))) {
        NativeVectorCanvas(
            modifier = Modifier.fillMaxSize(),
            clearArgb = 0,
            atlas = atlas,
            drawKey = listOf(entry, selected, badgeEntry, downloaded, scale.value, atlas.generation),
            buildCmds = { w, h, dd ->
                setScaleAbout(w / 2f, h / 2f, scale.value)
                addRoundRect(
                    w / 2f, h / 2f, w, h, r.CARD_RADIUS_DP * dd, r.CARD_ARGB,
                    if (selected) r.CARD_BORDER_W_DP * dd else 0f,
                    if (selected) r.CYAN_ARGB else 0
                )
                val e = entry
                if (e != null) {
                    addText(
                        e.index,
                        w / 2f - e.rectW / 2f, h / 2f - e.rectH / 2f,
                        0f, 0f, e.rectW.toFloat(), e.rectH.toFloat(),
                        tintArgb = r.TEXT_ARGB
                    )
                }
                val b = badgeEntry
                if (b != null) {
                    addText(
                        b.index,
                        r.BADGE_INSET_DP * dd, h - r.BADGE_INSET_DP * dd - b.rectH,
                        0f, 0f, b.rectW.toFloat(), b.rectH.toFloat(),
                        tintArgb = if (downloaded) r.CYAN_ARGB else r.PIN_OFF_ARGB
                    )
                }
                clearTransform()
            }
        )
        ChromePressBox(scale, 0.96f, onClick, Modifier.fillMaxSize(), description = name)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(r.PIN_INSET_DP.dp)
        ) {
            RefCardPin(bookmarked, onToggleBookmark)
        }
    }
}

// ── Styles tab ────────────────────────────────────────────────────

private enum class StyleSection(val label: String) {
    TEXT("Text"), STROKE("Stroke"), GLOW("Glow"), BACKGROUND("Background"),
    SHADOW("Shadow"), SPACING("Spacing"), BOLD_ITALIC("Bold Italic"), CASE("Case")
}

/** A scrolling content column WITHOUT horizontal padding — the
 *  Styles / Animations rows carry the reference padding themselves. */
@Composable
private fun RefScrollColumn(content: @Composable () -> Unit) {
    val r = TextEditGeometry.Ref
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = r.CONTENT_PAD_TOP_DP.dp, bottom = r.CONTENT_PAD_BOTTOM_DP.dp)
    ) {
        content()
    }
}

/** A colour section: the reference row + the wheel's picker dialog. */
@Composable
private fun RefColorSection(
    items: List<RefSwatch>,
    selected: RefSwatch?,
    pickerInitial: Long,
    onPick: (RefSwatch) -> Unit,
    onCustom: (Long) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    RefColorRow(items, selected) { item ->
        if (item is RefSwatch.Wheel) showPicker = true else onPick(item)
    }
    if (showPicker) {
        ColorPickerDialog(
            initial = Color(pickerInitial),
            onPick = { onCustom(it.toArgb().toLong() and 0xFFFFFFFFL) },
            onDismiss = { showPicker = false }
        )
    }
}

private fun fxSwatches(): List<RefSwatch> =
    listOf(RefSwatch.Transparent, RefSwatch.Wheel) +
        TextPanelCatalog.FX_COLORS.map { RefSwatch.ColorSw(it) }

private fun fxSelected(current: Long, off: Boolean, palette: List<Long>): RefSwatch? = when {
    off -> RefSwatch.Transparent
    current in palette -> RefSwatch.ColorSw(current)
    else -> RefSwatch.Wheel
}

/**
 * Styles: the reference's Aa preset row, the sub-tab row (Text /
 * Stroke / Glow / Background / Shadow / Spacing / Bold Italic /
 * Case — the canonical list is [TextEditGeometry.Styles
 * .SECTION_LABELS]) and the section's controls. The reference has
 * no Curve sub-tab: the text-on-path controls ride at the END of
 * Spacing under a "Curve" label (stated deviation — no control is
 * lost). Slider unit mappings are in [TextPanelCatalog]'s doc.
 */
@Composable
private fun StylesTab(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit,
    pathLayers: List<TimelineClip>
) {
    var section by remember(clip.id) { mutableStateOf(StyleSection.TEXT) }
    var aaSel by remember(clip.id) { mutableStateOf(-1) }
    Column(Modifier.fillMaxSize()) {
        AaPresetRow(aaSel) { i ->
            aaSel = i
            val preset = TextPanelCatalog.AA_PRESETS[i]
            onUpdate { preset.applyTo(it) }
        }
        RefSubTabs(
            items = StyleSection.values().map { RefSubItem(it.label, it.label, null) },
            selectedKey = section.label,
            searchOpen = false
        ) { item ->
            section = StyleSection.values().first { it.label == item.key }
        }
        RefScrollColumn {
            when (section) {
                StyleSection.TEXT -> {
                    val palette = TextPanelCatalog.TEXT_COLORS
                    RefColorSection(
                        items = listOf(RefSwatch.Wheel) + palette.map { RefSwatch.ColorSw(it) },
                        selected = when {
                            clip.fillMode == FILL_NONE -> null
                            clip.textColor in palette -> RefSwatch.ColorSw(clip.textColor)
                            else -> RefSwatch.Wheel
                        },
                        pickerInitial = clip.textColor,
                        onPick = { item ->
                            if (item is RefSwatch.ColorSw) {
                                onUpdate { it.copy(textColor = item.argb, fillMode = FILL_SOLID) }
                            }
                        },
                        onCustom = { c ->
                            onUpdate { it.copy(textColor = c, fillMode = FILL_SOLID) }
                        }
                    )
                    RefSliderRow(
                        "Size", clip.textSizePercent / 5f, 12f, 44f, 1f, "px",
                        onValueChange = { v -> onUpdate { it.copy(textSizePercent = v * 5f) } }
                    )
                    RefSliderRow(
                        "Opacity", clip.textOpacity, 10f, 100f, 5f, "%",
                        onValueChange = { v -> onUpdate { it.copy(textOpacity = v) } }
                    )
                }
                StyleSection.STROKE -> {
                    RefColorSection(
                        items = fxSwatches(),
                        selected = fxSelected(
                            clip.textStrokeColor, clip.textStrokeThickness <= 0f,
                            TextPanelCatalog.FX_COLORS
                        ),
                        pickerInitial = clip.textStrokeColor,
                        onPick = { item ->
                            when (item) {
                                is RefSwatch.Transparent ->
                                    onUpdate { it.copy(textStrokeThickness = 0f) }
                                is RefSwatch.ColorSw -> onUpdate {
                                    it.copy(
                                        textStrokeColor = item.argb,
                                        textStrokeThickness =
                                            if (it.textStrokeThickness <= 0f) 19f
                                            else it.textStrokeThickness
                                    )
                                }
                                else -> Unit
                            }
                        },
                        onCustom = { c ->
                            onUpdate {
                                it.copy(
                                    textStrokeColor = c,
                                    textStrokeThickness =
                                        if (it.textStrokeThickness <= 0f) 19f
                                        else it.textStrokeThickness
                                )
                            }
                        }
                    )
                    RefSliderRow(
                        "Thickness", (clip.textStrokeThickness / 12.5f).coerceIn(0.5f, 4f),
                        0.5f, 4f, 0.5f, "px",
                        onValueChange = { v ->
                            onUpdate { it.copy(textStrokeThickness = v * 12.5f) }
                        }
                    )
                }
                StyleSection.GLOW -> {
                    RefColorSection(
                        items = fxSwatches(),
                        selected = fxSelected(
                            clip.glowColor, clip.glowColor == 0L,
                            TextPanelCatalog.FX_COLORS
                        ),
                        pickerInitial = if (clip.glowColor != 0L) clip.glowColor else 0xFF18D8E0,
                        onPick = { item ->
                            when (item) {
                                is RefSwatch.Transparent ->
                                    onUpdate { it.copy(glowColor = 0L) }
                                is RefSwatch.ColorSw ->
                                    onUpdate { it.copy(glowColor = item.argb) }
                                else -> Unit
                            }
                        },
                        onCustom = { c -> onUpdate { it.copy(glowColor = c) } }
                    )
                    RefSliderRow(
                        "Radius", clip.glowRadius / 5f, 2f, 28f, 1f, "px",
                        onValueChange = { v ->
                            onUpdate {
                                it.copy(
                                    glowRadius = v * 5f,
                                    glowColor = if (it.glowColor == 0L) 0xFF18D8E0
                                        else it.glowColor
                                )
                            }
                        }
                    )
                }
                StyleSection.BACKGROUND -> BackgroundSection(clip, onUpdate)
                StyleSection.SHADOW -> {
                    RefColorSection(
                        items = fxSwatches(),
                        selected = fxSelected(
                            clip.shadowColor, clip.shadowOpacity <= 0f,
                            TextPanelCatalog.FX_COLORS
                        ),
                        pickerInitial = clip.shadowColor,
                        onPick = { item ->
                            when (item) {
                                is RefSwatch.Transparent ->
                                    onUpdate { it.copy(shadowOpacity = 0f) }
                                is RefSwatch.ColorSw -> onUpdate {
                                    it.copy(
                                        shadowColor = item.argb,
                                        shadowOpacity =
                                            if (it.shadowOpacity <= 0f) 100f
                                            else it.shadowOpacity
                                    )
                                }
                                else -> Unit
                            }
                        },
                        onCustom = { c ->
                            onUpdate {
                                it.copy(
                                    shadowColor = c,
                                    shadowOpacity =
                                        if (it.shadowOpacity <= 0f) 100f
                                        else it.shadowOpacity
                                )
                            }
                        }
                    )
                    RefSliderRow(
                        "Opacity", clip.shadowOpacity, 0f, 100f, 1f, "%",
                        onValueChange = { v -> onUpdate { it.copy(shadowOpacity = v) } }
                    )
                    RefSliderRow(
                        "Blur", clip.shadowBlur / 10f, 0f, 20f, 1f, "px",
                        onValueChange = { v -> onUpdate { it.copy(shadowBlur = v * 10f) } }
                    )
                    RefSliderRow(
                        "Offset", clip.shadowDistance / 12.5f, 0f, 12f, 0.5f, "px",
                        onValueChange = { v ->
                            onUpdate { it.copy(shadowDistance = v * 12.5f) }
                        }
                    )
                }
                StyleSection.SPACING -> {
                    RefSliderRow(
                        "Character", clip.letterSpacing / 5f, -2f, 14f, 1f, "px",
                        onValueChange = { v -> onUpdate { it.copy(letterSpacing = v * 5f) } }
                    )
                    RefSliderRow(
                        "Line", clip.lineHeightPercent / 100f, 0.9f, 2.2f, 0.05f, "x",
                        onValueChange = { v ->
                            onUpdate { it.copy(lineHeightPercent = v * 100f) }
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier.padding(
                            horizontal = TextEditGeometry.Ref.CONTENT_PAD_H_DP.dp
                        )
                    ) {
                        GlLabelLine(
                            "Curve", UiLabelStyle.TEXT_11_SEMI,
                            TextEditGeometry.Ref.SECONDARY_ARGB
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    PathSection(clip, pathLayers, onUpdate)
                }
                StyleSection.BOLD_ITALIC -> {
                    var prevWeight by remember(clip.id) { mutableStateOf<Int?>(null) }
                    val boldOn = prevWeight != null
                    RefPillRow(
                        listOf(
                            "B" to boldOn,
                            "I" to clip.isItalic,
                            "U" to clip.isUnderline,
                            "S" to clip.isStrikethrough
                        )
                    ) { i ->
                        when (i) {
                            0 -> if (boldOn) {
                                val w = prevWeight!!
                                prevWeight = null
                                onUpdate { it.copy(fontWeight = w) }
                            } else {
                                prevWeight = clip.fontWeight
                                onUpdate { it.copy(fontWeight = 800) }
                            }
                            1 -> onUpdate { it.copy(isItalic = !it.isItalic) }
                            2 -> onUpdate { it.copy(isUnderline = !it.isUnderline) }
                            3 -> onUpdate { it.copy(isStrikethrough = !it.isStrikethrough) }
                        }
                    }
                    RefPillRow(
                        listOf(
                            "Left" to (clip.textAlign == 0),
                            "Center" to (clip.textAlign == 1),
                            "Right" to (clip.textAlign == 2)
                        )
                    ) { i -> onUpdate { it.copy(textAlign = i) } }
                }
                StyleSection.CASE -> {
                    RefPillRow(
                        listOf(
                            "TT" to (clip.textCase == 1),
                            "tt" to (clip.textCase == 2),
                            "Tt" to (clip.textCase == 3),
                            "Default" to (clip.textCase == 0)
                        )
                    ) { i ->
                        val mode = listOf(1, 2, 3, 0)[i]
                        onUpdate { it.copy(textCase = mode) }
                    }
                }
            }
        }
    }
}

/** The Aa preset row (Styles): 40×36 tiles, the reference's 6. */
@Composable
private fun AaPresetRow(selected: Int, onSelect: (Int) -> Unit) {
    val r = TextEditGeometry.Ref
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = r.CONTENT_PAD_H_DP.dp)
            .padding(top = r.AA_PAD_TOP_DP.dp, bottom = r.AA_PAD_BOTTOM_DP.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(r.AA_GAP_DP.dp)
    ) {
        TextPanelCatalog.AA_PRESETS.forEachIndexed { i, preset ->
            AaTile(preset, isNone = i == 0, selected = selected == i) { onSelect(i) }
        }
    }
}

@Composable
private fun AaTile(
    preset: TextPanelCatalog.AaPreset,
    isNone: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val r = TextEditGeometry.Ref
    val scale = remember { Animatable(1f) }
    Box(
        Modifier
            .width(r.AA_W_DP.dp)
            .height(r.AA_H_DP.dp)
            .clip(RoundedCornerShape(r.AA_RADIUS_DP.dp))
    ) {
        NativeVectorCanvas(
            modifier = Modifier.fillMaxSize(),
            clearArgb = 0,
            drawKey = listOf(selected, preset.bg, scale.value),
            buildCmds = { w, h, dd ->
                setScaleAbout(w / 2f, h / 2f, scale.value)
                addRoundRect(
                    w / 2f, h / 2f, w, h, r.AA_RADIUS_DP * dd,
                    preset.bg?.toInt() ?: r.CARD_ARGB,
                    if (selected) r.CARD_BORDER_W_DP * dd else 0f,
                    if (selected) r.CYAN_ARGB else 0
                )
                clearTransform()
            }
        )
        if (isNone) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val rad = 7.dp.toPx()
                drawCircle(
                    Color(r.SECONDARY_ARGB), radius = rad, center = c,
                    style = Stroke(width = 1.6.dp.toPx())
                )
                drawLine(
                    Color(r.SECONDARY_ARGB),
                    Offset(c.x - rad * 0.7f, c.y + rad * 0.7f),
                    Offset(c.x + rad * 0.7f, c.y - rad * 0.7f),
                    strokeWidth = 1.6.dp.toPx()
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (preset.stroke != null) {
                    Text(
                        "Aa",
                        style = TextStyle(
                            color = Color(preset.stroke),
                            fontSize = 14.sp,
                            fontWeight = FontWeight(800),
                            drawStyle = Stroke(width = 2.5f, join = StrokeJoin.Round)
                        )
                    )
                }
                Text(
                    "Aa",
                    style = TextStyle(
                        color = Color(preset.fill),
                        fontSize = 14.sp,
                        fontWeight = FontWeight(800),
                        shadow = preset.shadow?.let {
                            Shadow(Color(it), Offset(1.5f, 1.5f), 0f)
                        }
                    )
                )
            }
        }
        ChromePressBox(scale, 0.96f, onClick, Modifier.fillMaxSize(), description = "Style preset")
    }
}

/** The Background section's pill state, derived from the clip. */
private fun deriveBgStyle(clip: TimelineClip): Int = when {
    clip.bgShape == TEXT_BG_NONE || clip.bgOpacity <= 0f -> 0
    clip.bgShape == TEXT_BG_ROUNDED -> 1
    clip.bgShape == TEXT_BG_RECTANGLE && clip.bgCorner >= 24f -> 3
    else -> 2
}

/**
 * Styles → Background: the reference's pill toggles (None / Dark
 * Pill / Highlight / Rounded) + its 6-colour row (picking a colour
 * with None active switches to Highlight, as the reference does).
 * Below them, the app's own Opacity / Padding / Corner sliders ride
 * along (the reference has no such sliders; they are kept so no
 * build-268 control is lost — stated deviation).
 */
@Composable
private fun BackgroundSection(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit
) {
    var bgStyle by remember(clip.id) { mutableStateOf(deriveBgStyle(clip)) }
    RefPillRow(
        listOf(
            "None" to (bgStyle == 0),
            "Dark Pill" to (bgStyle == 1),
            "Highlight" to (bgStyle == 2),
            "Rounded" to (bgStyle == 3)
        )
    ) { i ->
        bgStyle = i
        when (i) {
            0 -> onUpdate { it.copy(bgShape = TEXT_BG_NONE, bgOpacity = 0f) }
            1 -> onUpdate { c ->
                if (c.bgColor == 0xFFFFE600L) {
                    c.copy(
                        bgShape = TEXT_BG_ROUNDED, bgCorner = 100f,
                        bgColor = 0xFF000000, bgOpacity = 78f
                    )
                } else {
                    c.copy(
                        bgShape = TEXT_BG_ROUNDED, bgCorner = 100f,
                        bgOpacity = if (c.bgOpacity == 0f) 100f else c.bgOpacity
                    )
                }
            }
            2 -> onUpdate { c ->
                c.copy(
                    bgShape = TEXT_BG_RECTANGLE, bgCorner = 8f, bgOpacity = 100f,
                    fillMode = FILL_SOLID,
                    textColor = if (c.bgColor == 0xFF000000L) 0xFFFFFFFF else 0xFF000000
                )
            }
            3 -> onUpdate { c ->
                c.copy(
                    bgShape = TEXT_BG_RECTANGLE, bgCorner = 32f, bgOpacity = 100f,
                    fillMode = FILL_SOLID, textColor = 0xFF000000
                )
            }
        }
    }
    RefColorRow(
        items = TextPanelCatalog.BG_COLORS.map { RefSwatch.ColorSw(it) },
        selected = if (bgStyle != 0) RefSwatch.ColorSw(clip.bgColor) else null
    ) { item ->
        if (item is RefSwatch.ColorSw) {
            val c = item.argb
            val wasNone = bgStyle == 0
            if (wasNone) bgStyle = 2
            onUpdate { cl ->
                val base = cl.copy(
                    bgColor = c,
                    bgOpacity = if (cl.bgOpacity == 0f) 100f else cl.bgOpacity,
                    bgShape = if (cl.bgShape == TEXT_BG_NONE) TEXT_BG_RECTANGLE else cl.bgShape
                )
                if (wasNone || bgStyle == 2 || bgStyle == 3) {
                    base.copy(
                        fillMode = FILL_SOLID,
                        textColor = if (c == 0xFF000000L) 0xFFFFFFFF else 0xFF000000
                    )
                } else base
            }
        }
    }
    RefSliderRow(
        "Opacity", clip.bgOpacity, 0f, 100f, 1f, "%",
        onValueChange = { v -> onUpdate { it.copy(bgOpacity = v) } }
    )
    RefSliderRow(
        "Padding", clip.bgPadding, 0f, 100f, 1f, "%",
        onValueChange = { v -> onUpdate { it.copy(bgPadding = v) } }
    )
    if (textBgCornerApplies(clip.bgShape)) {
        RefSliderRow(
            "Corner", clip.bgCorner, 0f, 100f, 1f, "%",
            onValueChange = { v -> onUpdate { it.copy(bgCorner = v) } }
        )
    } else if (clip.bgShape != TEXT_BG_NONE) {
        RefNoteText("Corner follows the bubble's own outline for this shape — choose Rectangle or Rounded in Bubbles to use the Corner slider.")
    }
}

// ── Reveal section ────────────────────────────────────────────────

/**
 * Reveal group (typewriter window, Alight `textprogress`): a static
 * start/end window of the text that is shown. An imported keyframed
 * reveal is preserved — the sliders stay hidden while it is
 * animated, and only an explicit tap replaces it with a static
 * window frozen at the finished state (see TextRevealValues).
 */
@Composable
private fun RevealSection(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit
) {
    val reveal = clip.textAnimators.firstOrNull { it.effectId == TextEffectIds.TEXTPROGRESS }
    if (TextRevealValues.isKeyframed(reveal)) {
        RefNoteText("Animated reveal — the window is keyframed in Alight Motion, so it changes over time.")
        RefActionButton("Use static window") {
            val (s, e) = TextRevealValues.windowAt(reveal, 1f)
            onUpdate { it.copy(textAnimators = TextRevealValues.withStaticWindow(it.textAnimators, s, e)) }
        }
    } else {
        val (revealStart, revealEnd) = TextRevealValues.windowAt(reveal, 0f)
        RefSliderRow(
            "Reveal start", revealStart * 100f, 0f, 100f, 1f, "%",
            onValueChange = { v ->
                onUpdate {
                    it.copy(
                        textAnimators = TextRevealValues.withStaticWindow(
                            it.textAnimators, v / 100f, revealEnd
                        )
                    )
                }
            }
        )
        RefSliderRow(
            "Reveal end", revealEnd * 100f, 0f, 100f, 1f, "%",
            onValueChange = { v ->
                onUpdate {
                    it.copy(
                        textAnimators = TextRevealValues.withStaticWindow(
                            it.textAnimators, revealStart, v / 100f
                        )
                    )
                }
            }
        )
    }
}

// ── Path section (text-on-path, build 191) ────────────────────────

/**
 * Path group: attach this text to a PATH shape layer — the glyphs
 * then follow that layer's outline, evaluated live. The text follows
 * the path even when the path layer is hidden; hiding is not
 * detaching. While attached, the Start offset slider moves the text
 * along the path (a share of the path's whole length); text longer
 * than the path is cut at the path's end.
 */
@Composable
private fun PathSection(
    clip: TimelineClip,
    pathLayers: List<TimelineClip>,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit
) {
    val r = TextEditGeometry.Ref
    val attachedId = clip.textPathLayerId
    val attached = pathLayers.firstOrNull { it.id == attachedId }
    when {
        attached != null -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = r.CONTENT_PAD_H_DP.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlLabelLine("Layer", UiLabelStyle.SHEET_ITEM_MED, r.SECONDARY_ARGB)
                Spacer(Modifier.width(12.dp))
                GlLabelLine(
                    attached.name, UiLabelStyle.TEXT_13_BOLD, r.TEXT_ARGB,
                    modifier = Modifier.weight(1f),
                    ellipsize = true
                )
            }
            RefSliderRow(
                "Start offset", clip.textPathStart * 100f, 0f, 100f, 1f, "%",
                onValueChange = { v -> onUpdate { it.copy(textPathStart = v / 100f) } }
            )
            RefActionButton("Detach from path") {
                onUpdate { it.copy(textPathLayerId = null) }
            }
        }
        attachedId != null -> {
            RefNoteText("The path layer this text was attached to is gone.")
            RefActionButton("Detach") { onUpdate { it.copy(textPathLayerId = null) } }
        }
        pathLayers.isEmpty() -> {
            RefNoteText("No path layer in this project yet. Draw one with the Pen tool first, then attach this text to it here.")
        }
        else -> {
            RefNoteText("Attach to a path layer — the text will follow its outline:")
            pathLayers.forEach { layer ->
                RefActionButton(layer.name) {
                    onUpdate { it.copy(textPathLayerId = layer.id) }
                }
            }
        }
    }
}

// ── Effects tab ───────────────────────────────────────────────────

/** The ∅ glyph (None tiles), drawn in Compose strokes. */
@Composable
private fun NoneGlyph(sizeDp: Float = 22f) {
    val r = TextEditGeometry.Ref
    Canvas(Modifier.size(sizeDp.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val rad = size.minDimension / 2f * 0.78f
        val sw = 1.6.dp.toPx()
        drawCircle(
            Color(r.SECONDARY_ARGB), radius = rad, center = c,
            style = Stroke(width = sw)
        )
        drawLine(
            Color(r.SECONDARY_ARGB),
            Offset(c.x - rad * 0.7f, c.y + rad * 0.7f),
            Offset(c.x + rad * 0.7f, c.y - rad * 0.7f),
            strokeWidth = sw
        )
    }
}

/**
 * An effect card's "ART" thumb: the word ART at 17sp/900 in the
 * effect's own style, rendered from the SAME data the apply writes
 * (fill or gradient brush, stroke underlay, glow/shadow) — what
 * the thumb shows is what applying does.
 */
@Composable
private fun ArtThumb(effect: TextPanelCatalog.ArtEffect) {
    val base = TextStyle(fontSize = 17.sp, fontWeight = FontWeight(900))
    Box(contentAlignment = Alignment.Center) {
        if (effect.stroke != null) {
            Text(
                "ART",
                style = base.copy(
                    color = Color(effect.stroke),
                    drawStyle = Stroke(width = 3f, join = StrokeJoin.Round)
                )
            )
        }
        val shadow = when {
            effect.glow != null -> Shadow(Color(effect.glow), Offset.Zero, 9f)
            effect.shadowColor != null -> Shadow(
                Color(effect.shadowColor), Offset(2.5f, 2.5f),
                effect.shadowBlur / 10f
            )
            else -> null
        }
        if (effect.grad1 != null && effect.grad2 != null) {
            Text(
                "ART",
                style = base.copy(
                    brush = Brush.verticalGradient(
                        listOf(Color(effect.grad1), Color(effect.grad2))
                    ),
                    shadow = shadow
                )
            )
        } else {
            Text("ART", style = base.copy(color = Color(effect.fill), shadow = shadow))
        }
    }
}

/**
 * Effects: the sub-header (search / bookmark / Trending / Black
 * Friday / Classic / NEW — all working) over the 4-column square
 * ART grid: None first, then the reference's art effects, each
 * built from real engine fields (see [TextPanelCatalog
 * .ART_EFFECTS] — gradient effects are 2-stop, Pink 3D keeps its
 * dark offset layer only; both stated there). No name labels under
 * the cards — the reference shows none. The text layer's layer-FX
 * are NOT here; they stay in the layer's own Effects tool.
 */
@Composable
private fun EffectsTab(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit
) {
    val context = LocalContext.current
    var subTab by remember(clip.id) { mutableStateOf("Trending") }
    var searchOpen by remember(clip.id) { mutableStateOf(false) }
    var query by remember(clip.id) { mutableStateOf("") }
    var bookmarks by remember { mutableStateOf(TextPanelBookmarks.load(context)) }
    val q = if (searchOpen) query.trim() else ""
    val showNone = TextPanelCatalog.refFilter(
        setOf("Trending", "Black Friday", "Classic", "NEW"),
        subTab, q, "None", bookmarked = false
    )
    val shown = TextPanelCatalog.ART_EFFECTS.filter { fx ->
        TextPanelCatalog.refFilter(
            fx.cats, subTab, q, fx.name, bookmarks.contains("ef:${fx.id}")
        )
    }
    Column(Modifier.fillMaxSize()) {
        RefSubTabs(
            items = listOf(
                RefSubItem("search", null, SubIcon.SEARCH),
                RefSubItem(
                    "bookmark", null,
                    if (subTab == "bookmark") SubIcon.BOOKMARK_ON else SubIcon.BOOKMARK
                )
            ) + listOf("Trending", "Black Friday", "Classic", "NEW").map {
                RefSubItem(it, it, null)
            },
            selectedKey = subTab,
            searchOpen = searchOpen
        ) { item ->
            when (item.key) {
                "search" -> searchOpen = !searchOpen
                "bookmark" -> subTab = "bookmark"
                else -> subTab = item.key
            }
        }
        if (searchOpen) {
            RefSearchField("Effects", query) { query = it }
        }
        RefContentColumn {
            val tiles: List<TextPanelCatalog.ArtEffect?> =
                (if (showNone) listOf(null) else emptyList()) + shown
            if (tiles.isEmpty()) {
                RefEmptyText("No effects found")
            } else {
                tiles.chunked(4).forEach { rowTiles ->
                    Row(horizontalArrangement = Arrangement.spacedBy(TextEditGeometry.Grid.GAP_DP.dp)) {
                        rowTiles.forEach { fx ->
                            if (fx == null) {
                                RefCardBox(
                                    selected = clip.textEffect == "",
                                    modifier = Modifier.weight(1f).aspectRatio(1f),
                                    onClick = {
                                        onUpdate {
                                            it.copy(
                                                fillMode = FILL_SOLID, textColor = 0xFFFFFFFF,
                                                textStrokeThickness = 0f,
                                                shadowOpacity = 60f, shadowBlur = 30f,
                                                shadowDistance = 10f, shadowAngle = 45f,
                                                shadowColor = 0xFF000000,
                                                glowColor = 0L, textEffect = ""
                                            )
                                        }
                                    }
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        NoneGlyph()
                                    }
                                }
                            } else {
                                val bmKey = "ef:${fx.id}"
                                RefCardBox(
                                    selected = clip.textEffect == fx.id,
                                    modifier = Modifier.weight(1f).aspectRatio(1f),
                                    onClick = { onUpdate { fx.applyTo(it) } }
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        ArtThumb(fx)
                                    }
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(TextEditGeometry.Ref.PIN_INSET_DP.dp)
                                    ) {
                                        RefCardPin(bookmarks.contains(bmKey)) {
                                            bookmarks = TextPanelBookmarks.toggle(context, bmKey)
                                        }
                                    }
                                }
                            }
                        }
                        repeat(4 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(TextEditGeometry.Grid.GAP_DP.dp))
                }
            }
        }
    }
}

// ── Animations tab ────────────────────────────────────────────────

/** A preset's line icon (the reference's card glyphs, simplified
 *  to the same stroke language). */
@Composable
private fun AnimIcon(presetId: String) {
    val col = Color(0xFFE4E4EC)
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.6.dp.toPx()
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
            drawLine(col, Offset(w * x0, h * y0), Offset(w * x1, h * y1), strokeWidth = sw)
        when (presetId) {
            "fade" -> drawRect(
                col,
                topLeft = Offset(w * 0.2f, h * 0.2f),
                size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.6f),
                style = Stroke(
                    sw,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(4f, 3f)
                    )
                )
            )
            "slide", "slide_fade" -> {
                line(0.78f, 0.5f, 0.3f, 0.5f)
                line(0.46f, 0.3f, 0.28f, 0.5f)
                line(0.46f, 0.7f, 0.28f, 0.5f)
                if (presetId == "slide_fade") line(0.86f, 0.24f, 0.86f, 0.76f)
            }
            "bounce" -> {
                line(0.16f, 0.78f, 0.84f, 0.78f)
                drawArc(
                    col, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(w * 0.28f, h * 0.3f),
                    size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.48f),
                    style = Stroke(sw)
                )
                drawCircle(col, radius = 2.2.dp.toPx(), center = Offset(w * 0.5f, h * 0.3f))
            }
            "zoom", "zoom_fade" -> {
                drawRect(
                    col,
                    topLeft = Offset(w * 0.28f, h * 0.28f),
                    size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.44f),
                    style = Stroke(sw)
                )
                line(0.14f, 0.14f, 0.26f, 0.26f)
                line(0.86f, 0.14f, 0.74f, 0.26f)
                line(0.14f, 0.86f, 0.26f, 0.74f)
                line(0.86f, 0.86f, 0.74f, 0.74f)
            }
            "zoom_out" -> {
                drawRect(
                    col,
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.68f),
                    style = Stroke(sw)
                )
                line(0.34f, 0.34f, 0.44f, 0.44f)
                line(0.66f, 0.34f, 0.56f, 0.44f)
                line(0.34f, 0.66f, 0.44f, 0.56f)
                line(0.66f, 0.66f, 0.56f, 0.56f)
            }
            "pop" -> {
                for (a in 0 until 8) {
                    val ang = a * Math.PI / 4.0
                    val r0 = 0.14f
                    val r1 = if (a % 2 == 0) 0.3f else 0.22f
                    line(
                        0.5f + (kotlin.math.cos(ang) * r0).toFloat(),
                        0.5f + (kotlin.math.sin(ang) * r0).toFloat(),
                        0.5f + (kotlin.math.cos(ang) * r1).toFloat(),
                        0.5f + (kotlin.math.sin(ang) * r1).toFloat()
                    )
                }
            }
            "spin", "spin_zoom" -> {
                drawArc(
                    col, startAngle = -40f, sweepAngle = 280f, useCenter = false,
                    topLeft = Offset(w * 0.2f, h * 0.2f),
                    size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.6f),
                    style = Stroke(sw)
                )
                line(0.82f, 0.1f, 0.8f, 0.32f)
                line(0.82f, 0.1f, 0.62f, 0.2f)
            }
            "wipe" -> {
                drawRect(
                    col,
                    topLeft = Offset(w * 0.16f, h * 0.24f),
                    size = androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.52f),
                    style = Stroke(sw)
                )
                line(0.42f, 0.24f, 0.42f, 0.76f)
                line(0.42f, 0.5f, 0.68f, 0.5f)
                line(0.58f, 0.38f, 0.7f, 0.5f)
                line(0.58f, 0.62f, 0.7f, 0.5f)
            }
            else -> drawRect(
                col,
                topLeft = Offset(w * 0.22f, h * 0.22f),
                size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.56f),
                style = Stroke(sw)
            )
        }
    }
}

/** One Animations grid cell: the square card + the name under it. */
@Composable
private fun AnimCard(
    label: String,
    presetId: String?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val r = TextEditGeometry.Ref
    Column(modifier) {
        RefCardBox(
            selected = selected,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            onClick = onClick
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (presetId == null) NoneGlyph() else AnimIcon(presetId)
            }
        }
        Spacer(Modifier.height(r.CARD_LABEL_GAP_DP.dp))
        Text(
            label,
            color = Color(r.CARD_LABEL_ARGB),
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Animations: the sub-header (search / In / Out / Loop) over the
 * 4-column preset grid (line icon + name under the card). In/Out
 * populate from [AnimPresets] as in build 268; applying routes
 * through the Animations tool's own ViewModel action with the
 * Duration slider's length (0.3–2.0s — dragging it with a preset
 * applied re-applies that preset at the new length and previews on
 * release). Loop has no expressible looping preset in the app's
 * machinery (In/Out slots only), so it shows the honest empty note,
 * as in build 268. Below: the text Reveal controls.
 */
@Composable
private fun AnimationsTab(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit,
    onAnimApply: (slot: AnimSlot, preset: AnimPreset?, durationMs: Long?) -> Unit,
    onAnimPreview: (slot: AnimSlot, durationMs: Long) -> Unit
) {
    var subTab by remember(clip.id) { mutableStateOf("In") }
    var searchOpen by remember(clip.id) { mutableStateOf(false) }
    var query by remember(clip.id) { mutableStateOf("") }
    var durSec by remember(clip.id) {
        mutableStateOf(
            ((clip.animIn?.durationMs ?: clip.animOut?.durationMs ?: 700L)
                .toFloat() / 1000f).coerceIn(0.3f, 2f)
        )
    }
    Column(Modifier.fillMaxSize()) {
        RefSubTabs(
            items = listOf(RefSubItem("search", null, SubIcon.SEARCH)) +
                listOf("In", "Out", "Loop").map { RefSubItem(it, it, null) },
            selectedKey = subTab,
            searchOpen = searchOpen
        ) { item ->
            if (item.key == "search") searchOpen = !searchOpen else subTab = item.key
        }
        if (searchOpen) {
            RefSearchField("Animations", query) { query = it }
        }
        RefScrollColumn {
            if (subTab == "Loop") {
                RefEmptyText("No looping animations in the catalogue yet")
            } else {
                val slot = if (subTab == "In") AnimSlot.IN else AnimSlot.OUT
                val applied = if (slot == AnimSlot.IN) clip.animIn else clip.animOut
                val q = if (searchOpen) query.trim() else ""
                val presets = AnimPresets.ALL.filter {
                    q.isEmpty() || it.label.contains(q, ignoreCase = true)
                }
                val showNone = q.isEmpty() || "None".contains(q, ignoreCase = true)
                Box(
                    Modifier.padding(
                        horizontal = TextEditGeometry.Ref.CONTENT_PAD_H_DP.dp
                    )
                ) {
                    Column {
                        val cells: List<AnimPreset?> =
                            (if (showNone) listOf(null) else emptyList()) + presets
                        if (cells.isEmpty()) {
                            RefEmptyText("No animations found")
                        } else {
                            cells.chunked(4).forEach { rowCells ->
                                Row(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(TextEditGeometry.Grid.GAP_DP.dp)
                                ) {
                                    rowCells.forEach { p ->
                                        if (p == null) {
                                            AnimCard(
                                                label = "None", presetId = null,
                                                selected = applied == null,
                                                modifier = Modifier.weight(1f)
                                            ) { onAnimApply(slot, null, null) }
                                        } else {
                                            AnimCard(
                                                label = p.label, presetId = p.id,
                                                selected = applied?.preset == p.id,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                val ms = (durSec * 1000).toLong()
                                                onAnimApply(slot, p, ms)
                                                onAnimPreview(slot, ms)
                                            }
                                        }
                                    }
                                    repeat(4 - rowCells.size) { Spacer(Modifier.weight(1f)) }
                                }
                                Spacer(Modifier.height(TextEditGeometry.Grid.GAP_DP.dp))
                            }
                        }
                    }
                }
                RefSliderRow(
                    "Duration", durSec, 0.3f, 2f, 0.1f, "s",
                    onValueChange = { v ->
                        durSec = v
                        val ap = applied
                        if (ap != null) {
                            val p = AnimPresets.ALL.firstOrNull { it.id == ap.preset }
                            if (p != null) onAnimApply(slot, p, (v * 1000).toLong())
                        }
                    },
                    onValueFinished = {
                        if (applied != null) {
                            onAnimPreview(slot, (durSec * 1000).toLong())
                        }
                    }
                )
            }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.padding(
                    horizontal = TextEditGeometry.Ref.CONTENT_PAD_H_DP.dp
                )
            ) {
                GlLabelLine(
                    "Reveal", UiLabelStyle.TEXT_11_SEMI,
                    TextEditGeometry.Ref.SECONDARY_ARGB
                )
            }
            Spacer(Modifier.height(6.dp))
            RevealSection(clip, onUpdate)
        }
    }
}

// ── Bubbles tab ───────────────────────────────────────────────────

/**
 * One bubble design's mini preview — a Compose swatch of the
 * design (the real render is the engine's bg pass over the same
 * fields; the Tag preview omits the reference's gold left bar
 * because the real bg has none — see [TextPanelCatalog]).
 */
@Composable
private fun BubblePreview(design: TextPanelCatalog.BubbleDesign) {
    val sample = design.sample
    val textStyle = TextStyle(fontSize = 9.5.sp, fontWeight = FontWeight(700))
    Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
        when (design.id) {
            "none" -> NoneGlyph(20f)
            "speech" -> Box(
                Modifier
                    .background(Color(0xFFFFFFFF), RoundedCornerShape(14.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(sample, style = textStyle.copy(color = Color(0xFF111111)))
            }
            "bang" -> {
                val def = remember { com.example.model.textBgShapeDef(com.example.model.TEXT_BG_BANG) }
                Box(contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(64.dp, 40.dp)) {
                        val sub = def?.subs?.firstOrNull()
                        if (sub != null) {
                            val path = androidx.compose.ui.graphics.Path()
                            val pts = sub.pts
                            if (pts.size >= 6) {
                                path.moveTo(
                                    (pts[0] + 1f) / 2f * size.width,
                                    (pts[1] + 1f) / 2f * size.height
                                )
                                var i = 2
                                while (i + 1 < pts.size) {
                                    path.lineTo(
                                        (pts[i] + 1f) / 2f * size.width,
                                        (pts[i + 1] + 1f) / 2f * size.height
                                    )
                                    i += 2
                                }
                                path.close()
                                drawPath(path, Color(0xFFFFFFFF))
                            }
                        }
                    }
                    Text(sample, style = textStyle.copy(color = Color(0xFF111111), fontWeight = FontWeight(800)))
                }
            }
            "neon" -> Box(
                Modifier
                    .background(Color(0xFF0C0E14), RoundedCornerShape(percent = 50))
                    .border(1.5.dp, Color(0xFF18D8E0), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text(sample, style = textStyle.copy(color = Color(0xFF18D8E0), fontWeight = FontWeight(600)))
            }
            "tag" -> Box(
                Modifier
                    .background(Color(0xFFF6EAD4), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(sample, style = textStyle.copy(color = Color(0xFF2B2118)))
            }
            "ribbon" -> Box(
                Modifier
                    .background(Color(0xFFFF7A00), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(sample, style = textStyle.copy(color = Color(0xFFFFFFFF)))
            }
            "glass" -> Box(
                Modifier
                    .background(Color(0x1FFFFFFF), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(sample, style = textStyle.copy(color = Color(0xFFFFFFFF), fontWeight = FontWeight(600)))
            }
            "blue" -> Box(
                Modifier
                    .background(Color(0xFFD9F2FF), RoundedCornerShape(12.dp))
                    .border(1.5.dp, Color(0xFF6EC1E4), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(sample, style = textStyle.copy(color = Color(0xFF0B3C5D)))
            }
            "sale" -> Box(
                Modifier
                    .background(Color(0xFFFF2E54), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(sample, style = textStyle.copy(color = Color(0xFFFFFFFF), fontWeight = FontWeight(800)))
            }
        }
    }
}

/**
 * Bubbles: no sub-header — the 4-column grid (aspect 1.25) of the
 * reference's designs: None, then Speech / Bang / Neon / Tag /
 * Ribbon / Glass / Blue / Sale, each a bundle over (bgShape,
 * bgColor, bg border, textColor) applied through onUpdate. The
 * manual background controls live in Styles → Background now.
 */
@Composable
private fun BubblesTab(
    clip: TimelineClip,
    onUpdate: ((TimelineClip) -> TimelineClip) -> Unit
) {
    RefContentColumn {
        val designs = listOf(TextPanelCatalog.BUBBLE_NONE) + TextPanelCatalog.BUBBLE_DESIGNS
        designs.chunked(4).forEach { rowDesigns ->
            Row(horizontalArrangement = Arrangement.spacedBy(TextEditGeometry.Grid.GAP_DP.dp)) {
                rowDesigns.forEach { design ->
                    RefCardBox(
                        selected = TextPanelCatalog.bubbleMatches(clip, design),
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(TextEditGeometry.Ref.BUBBLE_ASPECT),
                        onClick = {
                            val apply = design.applyTo
                            if (apply == null) {
                                onUpdate {
                                    it.copy(
                                        bgShape = TEXT_BG_NONE, bgOpacity = 0f,
                                        bgBorderColor = 0L, bgBorderWidth = 0f
                                    )
                                }
                            } else {
                                onUpdate(apply)
                            }
                        }
                    ) {
                        BubblePreview(design)
                    }
                }
                repeat(4 - rowDesigns.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(TextEditGeometry.Grid.GAP_DP.dp))
        }
    }
}

// ── Font chip rasters (the Fonts grid's own-face names) ───────────

/** File typefaces for the chip rasters, loaded once per font file. */
private val fontChipTypefaces = ConcurrentHashMap<String, Typeface>()

/** The typeface a font name's chip is rasterized in (its own face). */
private fun typefaceForFont(context: android.content.Context, name: String): Typeface =
    when (name) {
        "Roboto", "System", "" -> Typeface.DEFAULT
        "Serif" -> Typeface.SERIF
        "Monospace" -> Typeface.MONOSPACE
        "Cursive" -> Typeface.create("cursive", Typeface.NORMAL)
        else -> {
            val file = FontRepository.fileOrNull(context, name)
            if (file == null) {
                Typeface.DEFAULT
            } else {
                fontChipTypefaces.getOrPut(file.absolutePath) {
                    runCatching { Typeface.createFromFile(file) }.getOrNull() ?: Typeface.DEFAULT
                }
            }
        }
    }

/**
 * The atlas entry for one font chip: the font's name rasterized in
 * its own face by Android's text stack (the atlas pattern — native
 * only blits it). [rememberFontFamily] keeps the old behaviour: an
 * unseen Google font downloads when first shown; until its file is
 * here the name rasterizes in the default face, and the entry's key
 * flips when the real face arrives.
 */
@Composable
private fun rememberFontChipEntry(atlas: UiLabelAtlas, name: String): UiLabelAtlas.Entry? {
    val context = LocalContext.current
    rememberFontFamily(name)
    val density = LocalDensity.current.density
    val fontScale = LocalDensity.current.fontScale
    val ready = FontRepository.resolveNow(context, name) != null
    fun resolveFont(): UiLabelAtlas.Entry? {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = typefaceForFont(context, name)
        paint.textSize = TextEditGeometry.Chips.FONT_TEXT_SP * density * fontScale
        paint.color = android.graphics.Color.WHITE
        val adv = paint.measureText(name)
        if (adv <= 0f) return null
        val fm = paint.fontMetrics
        val inkH = ceil(fm.descent - fm.ascent).toInt().coerceAtLeast(1)
        val key = "fontchip:$name:${if (ready) "ready" else "def"}:${paint.textSize}"
        return atlas.resolveIcon(key, ceil(adv).toInt().coerceAtLeast(1), inkH) { cv, x, y ->
            cv.drawText(name, x, y - fm.ascent, paint)
        }
    }
    var entry = resolveFont()
    if (atlas.overflowed) {
        atlas.reset()
        entry = resolveFont()
    }
    return entry
}
