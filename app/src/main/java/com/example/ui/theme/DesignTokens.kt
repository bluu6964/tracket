package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DESIGN TOKENS — the foundation of the full UI redesign Sarowar ordered 2026-11-13.
 *
 * Two style sources, reconciled here in one place (see ROADMAP's UI/UX section for the
 * full record):
 *  1. **Sarowar's five reference images (primary):** near-black backgrounds, dark glass
 *     pills/cards, neon mint→cyan gradient rings and gauges with a soft bloom, huge bold
 *     white numerals, muted grey secondary text.
 *  2. **The Pinterest research brief** (`after-motion-dev/ui-redesign/PINTEREST-BRIEF.md`):
 *     its dominant signature — a cyan→violet gradient used as THIN STROKES AND GLOWS,
 *     never flat fills — is the SECONDARY gradient here, alongside the images' mint→cyan
 *     primary; glass = translucent panel + 1 px hairline light border + inner top
 *     highlight, panels lifted #161A2B–#1E2438 over a #0A0A12 base; hot pink is the
 *     danger/highlight accent; tiny uppercase letter-spaced micro-labels.
 *
 * RESTRAINT RULE (from the brief, binding on every stage): ONE glowing accent per panel —
 * the video preview stays the brightest thing on screen. Glow is for the primary action
 * or the active element only, never decoration everywhere.
 *
 * Stages: 1 (this build) tokens + core chrome (header, bottom toolbar, play controls,
 * shared glass components, ruler slider) — 2 panels + timeline — 3 dialogs incl. the
 * Export donut gauge. New UI code consumes these tokens; the older flat tokens in
 * Color.kt stay for surfaces their stage has not reached yet.
 */

// ── Base & glass surfaces ────────────────────────────────────────────────
/** App base: near-black with a deep-navy tint (Pinterest base #0A0A12). */
val AmBase = Color(0xFF0A0A12)
/** Below-base black for recessed areas (canvas surround stays [CanvasBackground]). */
val AmBaseDeep = Color(0xFF06060D)
/** Lifted glass panel, low end of the Pinterest #161A2B–#1E2438 range. */
val AmPanel = Color(0xFF161A2B)
/** Lifted glass panel, high end — gradient tops, raised chips. */
val AmPanelHigh = Color(0xFF1E2438)
/** Default glass fill for pills/buttons drawn over the base. */
val AmGlassFill = Color(0xFF181C2E)
/** 1 px hairline light border on glass (the brief's glass signature). */
val AmHairline = Color(0x1FFFFFFF)
/** Stronger hairline for selected/active glass edges. */
val AmHairlineStrong = Color(0x38FFFFFF)
/** Inner top highlight on glass: light at the top edge fading down. */
val AmTopSheen = Color(0x2AFFFFFF)

// ── Accents ──────────────────────────────────────────────────────────────
/** Electric mint — the reference images' green glow; PRIMARY accent. */
val AmMint = Color(0xFF00FFA3)
/** Deep mint for the dark end of the primary gradient. */
val AmMintDeep = Color(0xFF00C853)
/** Cyan — primary gradient's cool end; also the brief's gradient start. */
val AmCyan = Color(0xFF22D3EE)
/** Bright cyan for thin secondary-gradient strokes. */
val AmCyanBright = Color(0xFF00E5FF)
/** Violet — the secondary gradient's end (thin strokes/glows only, never fills). */
val AmViolet = Color(0xFF8B5CF6)
/** Deep violet for the secondary gradient's dark end. */
val AmVioletDeep = Color(0xFF7C3AED)
/** The app's lime identity (playhead, active/success) — kept from the old palette. */
val AmLime = NeonLime
/** Warm orange — gauge warnings / warm accent from the reference images. */
val AmOrange = AccentAmber
/** Hot pink — danger + highlight accent (Pinterest brief). */
val AmPink = Color(0xFFFF2E93)
/** Soft red used for destructive actions since batch 1 — kept for consistency. */
val AmDanger = Color(0xFFFF6B6B)
/** Dark ink drawn ON TOP of the bright gradient fills (Export label, Add badge). */
val AmInk = Color(0xFF04120C)

// ── Text on the navy glass ───────────────────────────────────────────────
/** Secondary text tuned to the navy glass (blue-grey, not neutral grey). */
val AmTextDim = Color(0xFF9BA3B8)
/** Tertiary text on glass. */
val AmTextFaint = Color(0xFF626A84)

// ── Slider-specific ──────────────────────────────────────────────────────
/** Ruler tick, minor. */
val AmTickMinor = Color(0xFF363C52)
/** Ruler tick, major. */
val AmTickMajor = Color(0xFF767E99)

// ── Gradient brushes (functions: a Brush is built per call site) ────────
/** PRIMARY gradient: mint → cyan. Fills for the one primary action per panel. */
fun amMintCyanBrush(): Brush = Brush.horizontalGradient(listOf(AmMint, AmCyan))

/** Primary gradient, vertical (badges, gauge tops). */
fun amMintCyanVerticalBrush(): Brush = Brush.verticalGradient(listOf(AmMint, AmCyan))

/** SECONDARY gradient: cyan → violet. Thin strokes, rings and glows ONLY (brief rule). */
fun amCyanVioletBrush(): Brush = Brush.horizontalGradient(listOf(AmCyanBright, AmViolet))

/** Secondary gradient as a sweep — progress rings/gauges (Stage 3 Export donut). */
fun amRingSweepBrush(): Brush =
    Brush.sweepGradient(listOf(AmMint, AmCyan, AmViolet, AmVioletDeep, AmMint))

/** Glass panel lift: high panel at the top edge down to the low panel. */
fun amGlassBrush(): Brush = Brush.verticalGradient(listOf(AmPanelHigh, AmPanel))

/** Lighter glass for small chips/pills sitting on a panel. */
fun amGlassChipBrush(): Brush =
    Brush.verticalGradient(listOf(Color(0xFF252B45), Color(0xFF1A1F33)))

// ── Corner scale ─────────────────────────────────────────────────────────
/** Cards (Stage 2/3 dialogs and panels): ~24 dp per the brief. */
val AmCornerCard = 24.dp
/** Panels and sheets. */
val AmCornerPanel = 20.dp
/** Toolbar items and medium chips. */
val AmCornerItem = 18.dp
/** Small chips and readout pills. */
val AmCornerChip = 12.dp
/** Micro elements (readout boxes, badges). */
val AmCornerMicro = 9.dp

// ── Glow specs (consumed by util/Glow.kt's neonGlow / neonCircleGlow) ────
/** Default glow spread for a primary action. */
val AmGlowRadius = 14.dp
/** Default glow strength — soft bloom, not a halo storm (restraint rule). */
const val AmGlowAlpha = 0.42f
/** Glow strength for small ring accents (play button ring). */
const val AmGlowAlphaSoft = 0.30f

// ── Type emphasis ────────────────────────────────────────────────────────
/** Tiny uppercase micro-label (10–11 sp, letter-spaced) — apply `.uppercase()` at the call site. */
val AmMicroLabel = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
/** Huge bold numeral — stat readouts, counters, big values from the reference images. */
val AmBigNumeral = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp)
/** Emphasised control label (buttons, toolbar items). */
val AmControlLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
