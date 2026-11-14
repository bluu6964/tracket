package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DESIGN TOKENS — the app's settled design language: **simple BLACK & WHITE, CapCut-style**
 * (Sarowar's order 2026-11-13 ~20:30: "ui থেকে neon and glassy বাদ দিয়ে পুরো capcut এর মত
 * simple black white করো"). This SUPERSEDES the neon/glass redesign of builds 161/163
 * (mint→cyan and cyan→violet gradients, glass dome/sheen surfaces, the program-rendered
 * glow/glass PNG kit, BlendMode.Plus bloom) — all of it is gone from the chrome.
 *
 * THE LANGUAGE, strictly monochrome and FLAT:
 *  - Backgrounds: true/near black (#000–#0A0A0A); surfaces flat dark greys
 *    (#161616–#262626); hairline dividers in mid-grey / white-alpha.
 *  - Text/icons: white primary, grey secondary. No coloured accents in UI chrome.
 *  - The ONE emphasis mechanism: white vs grey. The primary action (Export) is a flat
 *    WHITE button with BLACK text; selected/active states are white (fill, border or
 *    text), inactive states grey.
 *  - No gradients, no glow, no glass: no gradient brushes, no dome light / top sheen,
 *    no additive bloom anywhere in chrome. The brush factories below survive only as
 *    call-site compatibility — every one returns a FLAT [SolidColor].
 *  - The single retained functional colour is [AmDanger] (destructive actions, kept
 *    since the batch-1 polish): a semantic signal, not an accent.
 *  - USER CONTENT colours are NOT chrome and are untouched by this layer: layer / fill /
 *    effect / template colours, timeline clip-type bars, colour-picker content.
 *
 * New UI code consumes these tokens; the older flat tokens in Color.kt carry the same
 * monochrome values (the legacy lime "Neon" aliases now resolve to white/grey).
 */

// ── Base & flat surfaces ─────────────────────────────────────────────────
/** App base: near-black, neutral (no navy tint). */
val AmBase = Color(0xFF0A0A0A)
/** Below-base pure black for recessed areas (canvas surround stays [CanvasBackground]). */
val AmBaseDeep = Color(0xFF000000)
/** Flat panel surface — sheets, bars, dialogs. */
val AmPanel = Color(0xFF161616)
/** Raised flat surface — discs, pills, chips sitting on a panel. */
val AmPanelHigh = Color(0xFF262626)
/** Default flat fill for small items drawn over the base. */
val AmGlassFill = Color(0xFF1C1C1C)
/** 1 px hairline divider/border on flat surfaces. */
val AmHairline = Color(0x1FFFFFFF)
/** Stronger hairline for emphasised flat edges. */
val AmHairlineStrong = Color(0x38FFFFFF)

// ── Accents — the white/grey mechanism (names kept for call-site stability) ──
/** The active/selected colour: plain white. (Was electric mint in the scrapped redesign.) */
val AmMint = Color(0xFFFFFFFF)
/** Mid grey — the dim end of the old primary pair. */
val AmMintDeep = Color(0xFFC8C8C8)
/** Near-white for glyph accents. (Was cyan.) */
val AmCyan = Color(0xFFE8E8E8)
/** Plain white. (Was bright cyan.) */
val AmCyanBright = Color(0xFFFFFFFF)
/** Light grey. (Was violet — strokes only in the old language, now just a grey.) */
val AmViolet = Color(0xFFCFCFCF)
/** Mid grey. (Was deep violet.) */
val AmVioletDeep = Color(0xFFA8A8A8)
/** The old lime identity, resolved to white like the rest of the chrome. */
val AmLime = NeonLime
/** Neutral light grey. (Was warm orange; elapsed-time accents are monochrome now.) */
val AmOrange = Color(0xFFC8C8C8)
/** Plain white. (Was hot pink; no chrome usage remains.) */
val AmPink = Color(0xFFFFFFFF)
/** Soft red for destructive actions — the ONE retained functional colour (since batch 1). */
val AmDanger = Color(0xFFFF6B6B)
/**
 * Magenta-pink — the ONE accent borrowed from Sarowar's effects-layout reference
 * (build 200): it colours ONLY the parameter ruler's thumb in the Effects
 * parameter editor. No other chrome may use it.
 */
val AmFxAccent = Color(0xFFFF2E7E)
/** Dark ink drawn ON TOP of white fills (Export label, Add/check badges). */
val AmInk = Color(0xFF0A0A0A)

// ── Text on the flat surfaces ────────────────────────────────────────────
/** Secondary text: neutral grey. */
val AmTextDim = Color(0xFFA6A6A6)
/** Tertiary text on flat surfaces. */
val AmTextFaint = Color(0xFF6E6E6E)

// ── Slider-specific ──────────────────────────────────────────────────────
/** Ruler tick, minor. */
val AmTickMinor = Color(0xFF3A3A3A)
/** Ruler tick, major. */
val AmTickMajor = Color(0xFF7A7A7A)

// ── Brush factories — FLAT. Every factory returns a SolidColor so existing
//    call sites render flat monochrome with zero gradient. ────────────────
/** The primary-action fill: flat white (Export pill, Add badge, selected tabs). */
fun amMintCyanBrush(): Brush = SolidColor(Color.White)

/** Flat panel fill (sheets/bars via glassSurface's default). */
fun amGlassBrush(): Brush = SolidColor(AmPanel)

/** Flat raised fill for small chips/pills sitting on a panel. */
fun amGlassChipBrush(): Brush = SolidColor(AmPanelHigh)

/** Selected-state border: flat white. */
fun amCyanVioletBrush(): Brush = SolidColor(Color.White)

// ── Corner scale — SQUARE (Sarowar's order 2026-11-14: the rounded corners
//    around every button and card GO — flat square, CapCut-simple). All chrome
//    corner tokens are 0.dp: buttons, cards, dialogs, sheets, panels, chips and
//    tiles render with sharp square corners. The token names survive so call
//    sites read as before. Shapes that are round BY FUNCTION are not corners
//    and are untouched: discs (play button, icon discs), colour swatches and
//    picker discs, slider thumbs, the export progress ring, and all user
//    CONTENT (media, template artwork, layer rendering). ───────────────────
/** Cards (dialogs and panels) — square since 2026-11-14. */
val AmCornerCard = 0.dp
/** Panels and sheets — square since 2026-11-14. */
val AmCornerPanel = 0.dp
/** Toolbar items and medium chips — square since 2026-11-14. */
val AmCornerItem = 0.dp
/** Small chips and readout pills — square since 2026-11-14. */
val AmCornerChip = 0.dp
/** Micro elements (readout boxes, badges) — square since 2026-11-14. */
val AmCornerMicro = 0.dp

// ── Type emphasis ────────────────────────────────────────────────────────
/** Tiny uppercase micro-label (10–11 sp, letter-spaced) — apply `.uppercase()` at the call site. */
val AmMicroLabel = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
/** Huge bold numeral — stat readouts, counters, big values. */
val AmBigNumeral = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp)
/** Emphasised control label (buttons, toolbar items). */
val AmControlLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
