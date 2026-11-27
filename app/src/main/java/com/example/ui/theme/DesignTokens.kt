package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DESIGN TOKENS — the frontend-redesign language (build 206, Sarowar 2026-11-15).
 *
 * This supersedes the square + boxless law of builds 178/183. The new language,
 * from Sarowar's references (floating pill-nav video + editor concepts):
 *  - Dark near-black base; chrome stays black & white, with ONE accent:
 *    [AmAccentViolet] for primary actions and the tools-grid button.
 *  - FULLY ROUNDED surfaces: cards ~24 dp, sheets ~28 dp top corners,
 *    bars and buttons are full pills (capsules).
 *  - Bars FLOAT: pill-shaped bars with margins over the base, never
 *    full-width strips. Selected state = a filled lighter pill
 *    ([AmPillSelected]) holding icon + label.
 *  - Buttons have their pills back (this reverses build 183's boxless rule).
 *  - Panels/sheets live in normal layout flow — the floating look comes from
 *    margins and shape, never from overlays anchored over the canvas
 *    (the build-202 More-panel lesson).
 *  - Semantic colours kept: AmDanger (destructive), AmFxAccent (effects ruler
 *    only). User content colours (clips, effects, templates) are outside this
 *    layer. Existing token names are preserved so call sites stay compatible.
 */

// ── Base & flat surfaces ─────────────────────────────────────────────────
// THE unified palette (build 223, Sarowar's colour-balance order): every
// panel, sheet, dialog and chrome surface in the app uses these tokens —
// background #121215, surfaces of the #202026 family, ONE lime accent.
// (Builds 214–221 each brought a reference palette; 223 retired them all.)
//
// Build 276 (Sarowar's fresh-design migration): the palette shifts to the
// new design language — a blue-tinted near-black base #0A0E13, dark slate
// surfaces (#151B23 / #1C242E), and the ONE accent becomes cyan #18D8E0
// (the lime is retired; the token names stay so call sites are stable).
/** App base: near-black, blue-tinted. */
val AmBase = Color(0xFF0A0E13)
/** Deeper black for recessed / canvas surround. */
val AmBaseDeep = Color(0xFF06090D)
/** Primary panel surface — sheets, bottom bars, dialogs, the panel stage. */
val AmPanel = Color(0xFF151B23)
/** Raised surface — chips, selected pills, elevated cards. */
val AmPanelHigh = Color(0xFF151B23)
/** Slightly higher for interactive hover / pressed states. */
val AmPanelElevated = Color(0xFF1C242E)
/** Default fill for small chrome items on base. */
val AmGlassFill = Color(0xFF151B23)
/** Subtle 1px hairline (build 214: the architecture's hairline colour). */
val AmHairline = Color(0xFF232C38)
/** Stronger hairline for focused edges. */
val AmHairlineStrong = Color(0xFF2E3947)

// ── Accents — white / grey emphasis (names kept for compatibility) ───────
/** Active / selected: pure white. */
val AmMint = Color(0xFFF4F4F5)
/** Dimmed primary. */
val AmMintDeep = Color(0xFFA1A1AA)
/** Near-white glyph accent. */
val AmCyan = Color(0xFFE4E4E7)
/** Bright white. */
val AmCyanBright = Color(0xFFF4F4F5)
/** Light grey stroke / secondary accent. */
val AmViolet = Color(0xFFA1A1AA)
/** Mid grey. */
val AmVioletDeep = Color(0xFF71717A)
/** Legacy lime name → the build-276 cyan accent. */
val AmLime = Color(0xFF18D8E0)
/** Neutral light grey. */
val AmOrange = Color(0xFFF97316)
/** White. */
val AmPink = Color(0xFFF54BBF)
/** Soft red — only for destructive actions. */
val AmDanger = Color(0xFFFF5C5C)
/**
 * The accent in its Effects-ruler role (the fx dial's accent disc).
 * Same value as [AmAccent]; the separate name survives from build 214.
 */
val AmFxAccent = Color(0xFF18D8E0)
/** Ink drawn on white fills (Export label, badges). */
val AmInk = Color(0xFF111114)

// ── Build 206: the redesign's surfaces + accent ──────────────────────────
/**
 * The one accent: cyan #18D8E0 (build 276's fresh design; lime #D2FC32
 * was the editor's accent from build 217 to 275). It marks selection,
 * primary actions, slider fills and the dock's Add. The token keeps
 * its build-206 name so call sites stay compatible.
 */
val AmAccentViolet = Color(0xFF18D8E0)
/** Alias for new code: the same cyan accent, honestly named. */
val AmAccent = AmAccentViolet
/** Floating pill bars (transport, toolbars, header): the base itself. */
val AmBarSurface = Color(0xFF0A0E13)
/** The filled pill of a selected / active item inside a bar or grid (cyan wash). */
val AmPillSelected = Color(0x2418D8E0)
/** Card surface on the base (project cards, panel-stage panels). */
val AmCardSurface = Color(0xFF11161D)

/** Selected-layer header slate (build 211's role; value is the card surface). */
val AmSlateHeader = Color(0xFF11161D)

// ── Text hierarchy ───────────────────────────────────────────────────────
/** Secondary text. */
val AmTextDim = Color(0xFFA1A1AA)
/** Tertiary / disabled text. */
val AmTextFaint = Color(0xFF71717A)
/** Primary text (explicit token for new code). */
val AmTextPrimary = Color(0xFFF4F4F5)

// ── Slider / ruler ──────────────────────────────────────────────────────
val AmTickMinor = Color(0x33FFFFFF)
val AmTickMajor = Color(0x57FFFFFF)

// ── Brush factories (still flat SolidColor for compatibility) ────────────
fun amMintCyanBrush(): Brush = SolidColor(Color(0xFFF4F4F5))
fun amGlassBrush(): Brush = SolidColor(AmPanel)
fun amGlassChipBrush(): Brush = SolidColor(AmPanelHigh)
fun amCyanVioletBrush(): Brush = SolidColor(Color(0xFFF4F4F5))
/** The floating-bar fill (build 206). */
fun amBarBrush(): Brush = SolidColor(AmBarSurface)

// ── Corner scale — fully rounded (build 206 redesign) ────────────────────
// Bars and buttons are full pills (CircleShape at the call site); these
// tokens size the rounded surfaces. Functional round shapes (play disc,
// thumbs, etc.) stay circular and are not affected by these tokens.
/** Large cards / dialogs (build 214: cards are 20 dp). */
val AmCornerCard = 16.dp
/** Bottom sheets / the panel stage: top corners. */
val AmCornerSheet = 18.dp
/** Panels, major containers. */
val AmCornerPanel = 16.dp
/** Toolbar items, medium chips, buttons. */
val AmCornerItem = 7.dp
/** Small chips, readout pills. */
val AmCornerChip = 5.dp
/** Micro badges / tiny containers. */
val AmCornerMicro = 3.dp
/**
 * The bottom-sheet shape (build 207): [AmCornerSheet] top corners, square
 * bottom (the sheet sits on the screen edge). Every bottom sheet in the
 * UI layer (AppBottomSheet since build 226) uses this one shape.
 */
val AmSheetShape = RoundedCornerShape(topStart = AmCornerSheet, topEnd = AmCornerSheet)

// ── Spacing scale ────────────────────────────────────────────────────────
object AmSpace {
    val xxs: Dp = 2.dp
    val xs: Dp = 3.dp
    val sm: Dp = 6.dp
    val md: Dp = 10.dp
    val lg: Dp = 12.dp
    val xl: Dp = 16.dp
    val xxl: Dp = 20.dp
    val xxxl: Dp = 24.dp
}

// ── Elevation / stroke helpers ───────────────────────────────────────────
/** Standard content padding inside panels. */
val AmPanelPadding = 12.dp
/** Compact content padding (toolbars, dense lists). */
val AmPanelPaddingCompact = 8.dp
/** Minimum comfortable touch target. */
val AmTouchTarget = 36.dp

// ── Type emphasis ────────────────────────────────────────────────────────
/** Tiny uppercase micro-label — call site should apply .uppercase(). */
val AmMicroLabel = TextStyle(
    fontSize = 9.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.0.sp,
    color = AmTextDim
)
/** Large bold numeral for stats / counters. */
val AmBigNumeral = TextStyle(
    fontSize = 22.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-0.5).sp
)
/** Control / toolbar label. */
val AmControlLabel = TextStyle(
    fontSize = 10.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.2.sp
)
/** Section title inside sheets. */
val AmSectionTitle = TextStyle(
    fontSize = 11.5.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.1.sp
)
/** Body text for descriptions. */
val AmBody = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.Normal,
    letterSpacing = 0.15.sp,
    lineHeight = 16.sp
)
