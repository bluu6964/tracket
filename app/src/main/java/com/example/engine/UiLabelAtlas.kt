package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.ceil

/**
 * Build 249 (UI migration stage 2): the UI GL renderer's label/icon atlas —
 * the [TlLabelAtlas] pattern applied to `ui_gl_renderer`. Every distinct
 * label is rasterized ONCE by Android's own text stack (system font,
 * fontScale, full Bengali/RTL shaping) into a shared atlas bitmap as white
 * glyphs on transparency; the native side only blits entries with a tint
 * (UI_CMD_TEXT). Icons that cannot be vector-drawn faithfully (the dock's
 * three Material icons) are rasterized into the same atlas through
 * [resolveIcon] — the same one-time rasterize-then-blit pattern.
 *
 * Threading: rasterization happens on the UI thread (during composition,
 * when command snapshots are built) under [lock]; the GL thread's upload
 * ([UiGlRuntime.syncUiAtlasOnGl]) holds the same lock across the pixel
 * copy, so an upload can never tear against a rasterize. When the atlas
 * fills up it is reset and rebuilt — callers resolve everything they need
 * for one snapshot, check [overflowed], and re-resolve after [reset]
 * (the two-pass pattern NativeTimelineEngine uses).
 */
enum class UiLabelStyle(
    val sp: Float,
    val weight: Int,
    val letterSpacingSp: Float = 0f,
    /** Build 252 (stage 5): the monospace family (the editors' value readouts). */
    val mono: Boolean = false,
    /** Build 252 (stage 5): tabular figures (the blend header's opacity value). */
    val tnum: Boolean = false
) {
    /** Dock tile labels: 9.5 sp SemiBold. */
    DOCK(9.5f, 600),

    /** Ruler playhead timecode: 10 sp Bold. */
    TIMECODE(10f, 700),

    /** Ruler total duration: 9.5 sp Medium. */
    TOTAL(9.5f, 500),

    /** Build 250 (stage 3): controls-bar badge ("Full" / resolution): 9.5 sp ExtraBold. */
    BADGE(9.5f, 800),

    /** Build 250 (stage 3): app-header resolution pill: 11 sp Bold. */
    PILL(11f, 700),

    /** Build 276 (fresh design): the header Export pill's label: 13 sp Bold. */
    EXPORT_PILL(13f, 700),

    /** Build 250 (stage 3): layer-header name: 15 sp SemiBold. */
    HEADER_NAME(15f, 600),

    /** Build 250 (stage 3): selected-header (build 219) name: 14.5 sp with 0.15 sp letter spacing. */
    SEL_NAME(14.5f, 400, letterSpacingSp = 0.15f),

    /** Build 250 (stage 3): multi-select bar count: 11.5 sp SemiBold. */
    MULTI_COUNT(11.5f, 600),

    /** Build 250 (stage 3): multi-select mini / boolean tile labels: 7.5 sp Medium. */
    MINI_LABEL(7.5f, 500),

    /** Build 251 (stage 4): tool tile / tools-grid labels: 9.5 sp Regular. */
    TILE_LABEL(9.5f, 400),

    /** Build 251 (stage 4): tool tile / tools-grid labels, used/active state: 9.5 sp Bold. */
    TILE_LABEL_BOLD(9.5f, 700),

    /** Build 251 (stage 4): tools-grid text glyphs (IN / OUT): 11 sp ExtraBold. */
    GRID_MARK(11f, 800),

    /** Build 251 (stage 4): tools-grid Preview text glyph: 10.5 sp ExtraBold. */
    GRID_PREVIEW(10.5f, 800),

    /** Build 251 (stage 4): pager tool labels at the auto-fit sizes: 7.5–10 sp Regular. */
    PAGER_L75(7.5f, 400),
    PAGER_L80(8f, 400),
    PAGER_L85(8.5f, 400),
    PAGER_L90(9f, 400),
    PAGER_L95(9.5f, 400),
    PAGER_L100(10f, 400),

    /** Build 251 (stage 4): pager badge text: 8 sp Bold, 0.3 sp letter spacing. */
    PAGER_BADGE(8f, 700, letterSpacingSp = 0.3f),

    /** Build 251 (stage 4): group edit bar name: 10.5 sp Regular. */
    GROUP_NAME(10.5f, 400),

    /** Build 251 (stage 4): clip-sheet titles: 14.5 sp Bold. */
    SHEET_TITLE(14.5f, 700),

    /** Build 251 (stage 4): clip-sheet row text: 12 sp Regular / Medium / SemiBold / Bold. */
    SHEET_ITEM(12f, 400),
    SHEET_ITEM_BOLD(12f, 700),
    SHEET_ITEM_MED(12f, 500),
    SHEET_ITEM_SEMI(12f, 600),

    /** Build 251 (stage 4): clip-sheet secondary text: 10.5 sp Regular. */
    SHEET_DESC(10.5f, 400),

    /** Build 252 (stage 5): editor row labels (RulerDialSlider): 12 sp Medium — build 257: compacted x0.78 to the 9.5 sp floor (build 240's chrome ratio, panel fonts). */
    ED_LABEL(9.5f, 500),

    /** Build 252 (stage 5): slider value readouts: 11.5 sp Monospace Bold — build 257: compacted x0.78 to the 9.5 sp floor. */
    ED_VALUE_MONO(9.5f, 700, mono = true),

    /** Build 252 (stage 5): the shared value header's readout: 13 sp Monospace Bold — build 257: compacted x0.78 to 10 sp. */
    VALUE_HEADER_MONO(10f, 700, mono = true),

    /** Build 252 (stage 5): editor screen titles: 14.5 sp Bold / 15 sp Bold (AppliedFx) — build 257: compacted x0.78 to 11.5 sp. */
    SCREEN_TITLE(11.5f, 700),
    SCREEN_TITLE_15(11.5f, 700),

    /** Build 252 (stage 5): header pill labels (Done / Add): 12 sp Bold. */
    PILL_TEXT(12f, 700),

    /** Build 252 (stage 5): section micro-labels (AmMicroLabel): 9 sp SemiBold, 1 sp tracking. */
    MICRO(9f, 600, letterSpacingSp = 1.0f),

    /** Build 252 (stage 5): 11.5 sp editor text family (tabs, hints, bodies). */
    ED_TEXT(11.5f, 400),
    ED_TEXT_MED(11.5f, 500),
    ED_TEXT_SEMI(11.5f, 600),
    ED_TEXT_BOLD(11.5f, 700),

    /** Build 252 (stage 5): 11 sp editor text family (chips, hints). */
    TEXT_11(11f, 400),
    TEXT_11_MED(11f, 500),
    TEXT_12(12f, 400),
    TEXT_11_BOLD(11f, 700),

    /** Build 252 (stage 5): 10.5 sp editor text family (small labels / hints). */
    SMALL_MED(10.5f, 500),
    SMALL_SEMI(10.5f, 600),
    SMALL_BOLD(10.5f, 700),

    /** Build 252 (stage 5): 10 sp plain text (strip labels). */
    TEXT_10(10f, 400),
    TEXT_10_SEMI(10f, 600),

    /** Build 252 (stage 5): animation curve chips: 8.5 sp Regular / Bold. */
    CURVE(8.5f, 400),
    CURVE_BOLD(8.5f, 700),

    /** Build 252 (stage 5): colour header hex readout: 13.5 sp Medium — build 257: compacted x0.78 to 10.5 sp. */
    HEX_BIG(10.5f, 500),

    /** Build 252 (stage 5): editor timecode rows: 10.5 sp Monospace Bold / Regular. */
    TIMECODE_MONO(10.5f, 700, mono = true),
    TIMECODE_MONO_MED(10.5f, 500, mono = true),
    TIMECODE_MONO_N(10.5f, 400, mono = true),

    /** Build 252 (stage 5): blend header opacity value: 12 sp SemiBold, tabular figures. */
    BLEND_VALUE(12f, 600, tnum = true),

    /** Build 252 (stage 5): big chip labels (shape editor): 13 sp Bold. */
    CHIP_BIG(13f, 700),
    FX_LABEL(13f, 600),
    SECTION_BOLD(10.5f, 700),
    CURVE_A(14f, 700),
    CONTROL_LABEL(10f, 500, letterSpacingSp = 0.2f),
    CONTROL_LABEL_BOLD(10f, 700, letterSpacingSp = 0.2f),
    SHAPE_STRIP_LABEL(10.5f, 500),
    NOTE_15(15f, 400),
    AEV_NAME(12.5f, 600),
    AEV_TILE(9.5f, 500),

    /** Build 252 (stage 5): effects editor title: 12.5 sp Bold. */
    TITLE_125(12.5f, 700),

    /** Build 252 (stage 5): transform rail axis badges: 8 sp Bold. */
    ED_BADGE(8f, 700),

    /** Build 253 (stage 6): the dialog/sheet action pill label: 12 sp SemiBold. */
    PILL_BTN(12f, 600),

    /** Build 253 (stage 6): AlertDialog title slot (headlineSmall): 24 sp Regular / Bold. */
    DLG_TITLE(24f, 400),
    DLG_TITLE_B(24f, 700),

    /**
     * Build 253 (stage 6): AlertDialog body-slot texts. The slot
     * provides bodyMedium (13 sp / 18 sp line, 0.2 sp tracking), so
     * every explicit size inside a dialog carries the 0.2 sp tracking
     * and the 18 sp line pitch (the hosts pass the pitch separately).
     */
    DLG_14_BOLD(14f, 700, letterSpacingSp = 0.2f),
    DLG_135_SEMI(13.5f, 600, letterSpacingSp = 0.2f),
    DLG_13(13f, 400, letterSpacingSp = 0.2f),
    DLG_125(12.5f, 400, letterSpacingSp = 0.2f),
    DLG_125_SEMI(12.5f, 600, letterSpacingSp = 0.2f),
    DLG_12(12f, 400, letterSpacingSp = 0.2f),
    DLG_12_SEMI(12f, 600, letterSpacingSp = 0.2f),
    DLG_115(11.5f, 400, letterSpacingSp = 0.2f),
    DLG_11(11f, 400, letterSpacingSp = 0.2f),
    DLG_11_SEMI(11f, 600, letterSpacingSp = 0.2f),
    DLG_10(10f, 400, letterSpacingSp = 0.2f),

    /** Build 253 (stage 6): sheet titles at SemiBold: 14.5 sp SemiBold. */
    SHEET_TITLE_SEMI(14.5f, 600),

    /** Build 253 (stage 6): 13 sp family (export cards, audio readouts, hex readout). */
    TEXT_11_SEMI(11f, 600),
    TEXT_13_SEMI(13f, 600),
    TEXT_13_BOLD(13f, 700),
    TEXT_13_MED(13f, 500),

    /** Build 253 (stage 6): aspect-ratio card labels: 13.5 sp Medium. */
    TEXT_135_MED(13.5f, 500),

    /** Build 271: the reference text panel's primary tabs: 13.5 sp SemiBold. */
    TEXT_135_SEMI(13.5f, 600),

    /** Build 271: the reference panel's sub-tabs: 12.5 sp Medium. */
    TEXT_125_MED(12.5f, 500),

    /** Build 271: the reference panel's slider value readouts: 11.5 sp SemiBold tabular. */
    TEXT_115_SEMI_TNUM(11.5f, 600, tnum = true),

    /** Build 253 (stage 6): 14 sp Bold (projects hero, onion stepper). */
    TEXT_14_BOLD(14f, 700),

    /** Build 253 (stage 6): export card value readout: 14.5 sp Bold. */
    TEXT_145_BOLD(14.5f, 700),

    /** Build 253 (stage 6): sheet / export page titles: 16 sp Bold, 17 sp Bold. */
    TITLE_16(16f, 700),
    TITLE_17(17f, 700),

    /** Build 253 (stage 6): export progress / complete headlines. */
    TEXT_22_BLACK(22f, 900),
    TEXT_30_BLACK(30f, 900),

    /** Build 253 (stage 6): project-card initial: 22 sp ExtraBold. */
    TEXT_22_XBOLD(22f, 800),

    /** Build 253 (stage 6): export complete sub-line: 12 sp Medium. */
    TEXT_12_MED(12f, 500),

    /** Build 253 (stage 6): 12.5 sp family (parent rows, export warning). */
    SEMI_125(12.5f, 600),
    TEXT_125(12.5f, 400),
    TEXT_125_BOLD(12.5f, 700),

    /** Build 253 (stage 6): panel section label: 9.5 sp SemiBold, 0.9 sp tracking. */
    SECTION_LABEL(9.5f, 600, letterSpacingSp = 0.9f),

    /** Build 253 (stage 6): onion-skin percent readout: 10 sp Bold. */
    TEXT_10_BOLD(10f, 700),

    /** Build 253 (stage 6): 9.5 sp Medium (shape names, project meta). */
    TEXT_95_MED(9.5f, 500),

    /** Build 253 (stage 6): project-card OPEN chip: 8 sp ExtraBold. */
    CHIP_8_XBOLD(8f, 800),

    /** Build 253 (stage 6): project-card duration badge: 9 sp SemiBold. */
    TEXT_9_SEMI(9f, 600);

    companion object {
        /** The pager label style for an auto-fit size (7.5–10 sp in 0.5 steps). */
        fun pagerLabel(sp: Float): UiLabelStyle = when (sp) {
            7.5f -> PAGER_L75
            8f -> PAGER_L80
            8.5f -> PAGER_L85
            9f -> PAGER_L90
            9.5f -> PAGER_L95
            else -> PAGER_L100
        }
    }
}

class UiLabelAtlas(
    val density: Float,
    val fontScale: Float
) {
    /** Guards the bitmap, the shelf state and the entry list (see class doc). */
    val lock = Any()

    val bitmap: Bitmap = Bitmap.createBitmap(ATLAS_W, ATLAS_H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paints = HashMap<UiLabelStyle, TextPaint>()
    private val cache = HashMap<Pair<UiLabelStyle, String>, Int>()
    private val iconCache = HashMap<String, Int>()
    private val digitTables = HashMap<UiLabelStyle, DigitTable>()
    private val entries = ArrayList<Entry>()
    private var shelfX = 0
    private var shelfY = 0
    private var shelfH = 0

    var overflowed = false
        private set

    /** Bumped by [reset]; the GL sync re-uploads whenever it (or the count) moves. */
    var generation = 0
        private set

    val entryCount: Int get() = entries.size

    /**
     * One atlas entry. The rect is the bitmap region (ink box + PAD margin);
     * [base] is the baseline's distance from the rect's top, [bearing] the
     * ink's left edge relative to the text's draw origin, [advance] the
     * laid-out width Compose would measure — everything a host needs to
     * place a blit exactly where a Compose Text would put its ink.
     */
    data class Entry(
        val index: Int,
        val rectX: Int, val rectY: Int, val rectW: Int, val rectH: Int,
        val inkW: Float, val inkH: Float,
        val base: Float, val bearing: Float, val advance: Float
    )

    /** The reserved per-character table for a style (indices 0..9 = '0'..'9', 10 = ':'). */
    class DigitTable(val indices: IntArray, val advances: FloatArray)

    fun entry(index: Int): Entry? = entries.getOrNull(index)

    private fun paint(style: UiLabelStyle): TextPaint = paints.getOrPut(style) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.textSize = style.sp * density * fontScale
        p.typeface = typefaceFor(style)
        // Paint letter spacing is an em fraction of the text size.
        if (style.letterSpacingSp != 0f) p.letterSpacing = style.letterSpacingSp / style.sp
        // Build 252: tabular figures for the readouts Compose marks "tnum".
        if (style.tnum) p.fontFeatureSettings = "\"tnum\""
        p
    }

    private fun typefaceFor(style: UiLabelStyle): Typeface {
        // Mirrors TlLabelAtlas: the classic create() overloads (the
        // (String, Int, Boolean) overload does not resolve in this project).
        // Build 252: the monospace styles resolve in the monospace family,
        // exactly as Compose's FontFamily.Monospace does.
        if (style.mono) {
            return Typeface.create(
                "monospace",
                if (style.weight >= 700) Typeface.BOLD else Typeface.NORMAL
            )
        }
        val family = when {
            style.weight >= 700 -> "sans-serif"
            style.weight >= 500 -> "sans-serif-medium"
            else -> "sans-serif"
        }
        val baseStyle = if (style.weight >= 700) Typeface.BOLD else Typeface.NORMAL
        return Typeface.create(family, baseStyle)
    }

    /** The advance width Compose's text layout measures for [text] in [style]. */
    fun measure(text: String, style: UiLabelStyle): Float = paint(style).measureText(text)

    fun fontMetrics(style: UiLabelStyle): Paint.FontMetrics = paint(style).fontMetrics

    /** The single-line layout height (px, rounded up like a paragraph's). */
    fun textHeightPx(style: UiLabelStyle): Float {
        val fm = fontMetrics(style)
        return ceil(fm.descent - fm.ascent)
    }

    /**
     * The atlas entry for [text] in [style], rasterizing it on first use.
     * Null when the text is empty or the atlas overflowed (the caller then
     * resets and re-resolves its whole set).
     */
    fun resolve(text: String, style: UiLabelStyle): Entry? {
        if (text.isEmpty() || overflowed) return null
        synchronized(lock) {
            val key = style to text
            cache[key]?.let { return entries[it] }
            val idx = rasterizeText(text, style)
            if (idx < 0) return null
            cache[key] = idx
            return entries[idx]
        }
    }

    /**
     * The atlas entry for a rasterized icon of [widthPx] x [heightPx]:
     * [draw] paints it into the atlas canvas at the given origin (the slot's
     * ink position, PAD inside the entry rect). Cached by [key]; the same
     * null-on-overflow contract as [resolve].
     */
    fun resolveIcon(
        key: String, widthPx: Int, heightPx: Int,
        draw: (Canvas, Float, Float) -> Unit
    ): Entry? {
        if (overflowed || widthPx <= 0 || heightPx <= 0) return null
        synchronized(lock) {
            iconCache[key]?.let { return entries[it] }
            val slot = reserveSlot(widthPx + 2 * PAD, heightPx + 2 * PAD) ?: return null
            draw(canvas, (slot[0] + PAD).toFloat(), (slot[1] + PAD).toFloat())
            val e = Entry(
                index = entries.size,
                rectX = slot[0], rectY = slot[1],
                rectW = widthPx + 2 * PAD, rectH = heightPx + 2 * PAD,
                inkW = widthPx.toFloat(), inkH = heightPx.toFloat(),
                base = heightPx.toFloat(), bearing = 0f, advance = widthPx.toFloat()
            )
            entries.add(e)
            iconCache[key] = e.index
            return e
        }
    }

    /**
     * The digit table for [style] ('0'..'9' + ':'), rasterized on first use —
     * the per-frame timecode blits one entry per character, like the
     * timeline's digit atlas. Null on overflow (reset + retry).
     */
    fun digits(style: UiLabelStyle): DigitTable? {
        digitTables[style]?.let { return it }
        if (overflowed) return null
        val chars = listOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':')
        val indices = IntArray(chars.size) { -1 }
        val advances = FloatArray(chars.size)
        for ((i, ch) in chars.withIndex()) {
            val e = resolve(ch.toString(), style) ?: return null
            indices[i] = e.index
            advances[i] = e.advance
        }
        val table = DigitTable(indices, advances)
        digitTables[style] = table
        return table
    }

    /** The entry rects as one flat array (x, y, w, h per entry) for the JNI upload. */
    fun rectArray(): IntArray {
        val out = IntArray(entries.size * 4)
        for ((i, e) in entries.withIndex()) {
            out[i * 4] = e.rectX
            out[i * 4 + 1] = e.rectY
            out[i * 4 + 2] = e.rectW
            out[i * 4 + 3] = e.rectH
        }
        return out
    }

    fun reset() {
        synchronized(lock) {
            bitmap.eraseColor(Color.TRANSPARENT)
            cache.clear()
            iconCache.clear()
            digitTables.clear()
            entries.clear()
            shelfX = 0
            shelfY = 0
            shelfH = 0
            overflowed = false
            generation++
        }
    }

    /** Reserve a shelf slot of [w] x [h]; null (and [overflowed] set) when it does not fit. */
    private fun reserveSlot(w: Int, h: Int): IntArray? {
        if (w <= 0 || h <= 0 || w > ATLAS_W) {
            overflowed = true
            return null
        }
        if (shelfX + w > ATLAS_W) {
            shelfX = 0
            shelfY += shelfH
            shelfH = 0
        }
        if (shelfY + h > ATLAS_H) {
            overflowed = true
            return null
        }
        val x = shelfX
        val y = shelfY
        shelfX += w
        shelfH = maxOf(shelfH, h)
        return intArrayOf(x, y)
    }

    private fun rasterizeText(text: String, style: UiLabelStyle): Int {
        val p = paint(style)
        val bounds = Rect()
        p.getTextBounds(text, 0, text.length, bounds)
        val w = bounds.width() + 2 * PAD
        val h = bounds.height() + 2 * PAD
        if (w <= 2 * PAD || h <= 2 * PAD) return -1
        val slot = reserveSlot(w, h) ?: return -1
        val x = slot[0]
        val y = slot[1]
        // Draw with the ink box's top-left at (x + PAD, y + PAD) — the same
        // placement TlLabelAtlas uses.
        canvas.drawText(text, (x + PAD - bounds.left).toFloat(), (y + PAD - bounds.top).toFloat(), p)
        val e = Entry(
            index = entries.size,
            rectX = x, rectY = y, rectW = w, rectH = h,
            inkW = bounds.width().toFloat(), inkH = bounds.height().toFloat(),
            base = (PAD - bounds.top).toFloat(),
            bearing = bounds.left.toFloat(),
            advance = p.measureText(text)
        )
        entries.add(e)
        return e.index
    }

    companion object {
        const val ATLAS_W = 2048
        const val ATLAS_H = 1024
        const val PAD = 2
    }
}

/**
 * The one shared [UiLabelAtlas] for the app's density/fontScale. Recreated
 * when either changes (entries are re-rasterized lazily by the hosts).
 */
object UiAtlasHolder {
    @Volatile
    private var current: UiLabelAtlas? = null

    fun get(density: Float, fontScale: Float): UiLabelAtlas {
        current?.let { if (it.density == density && it.fontScale == fontScale) return it }
        synchronized(this) {
            current?.let { if (it.density == density && it.fontScale == fontScale) return it }
            val created = UiLabelAtlas(density, fontScale)
            current = created
            return created
        }
    }
}

/** The shared UI atlas for this composition's density/fontScale. */
@Composable
fun rememberUiAtlas(): UiLabelAtlas {
    val density = LocalDensity.current
    return UiAtlasHolder.get(density.density, density.fontScale)
}
