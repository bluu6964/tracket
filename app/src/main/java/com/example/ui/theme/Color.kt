package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Professional dark palette — aligned with DesignTokens (2026-11-15 polish).
// Chrome stays monochrome CapCut-style. Clip-type and content colours remain
// slightly tinted so timeline layers stay scannable.

val EditorBackground = AmBase
val EditorSurface = AmPanelHigh
// Timeline lane controls (left pills)
val LanePill = Color(0xFF141B24)
val LanePillBorder = Color(0xFF232C38)
val EditorSurfaceVariant = AmPanelElevated
val EditorDivider = Color(0xFF232C38)
val GlassSurface = AmPanelHigh
val GlassHighlight = Color(0xFF1C242E)
// Canvas / project background: full black.
val CanvasBackground = AmBaseDeep
val AccentBlue = Color(0xFFF4F4F5)
val AccentBlueSoft = Color(0xFFA1A1AA)

// Timeline & Track colors
val TrackBackground = Color(0xFF0A0E13)
val TrackBorder = Color(0xFF232C38)
val PlayheadColor = Color(0xFFFFFFFF) // build 217: the playhead line is white (the reference ruler)
val PlayheadAccent = Color(0xFFFFFFFF)
val RulerTickColor = Color(0x57FFFFFF)
val RulerSubTickColor = Color(0x33FFFFFF)

// Clip type colors — muted, professional surfaces (content, not chrome).
// Build 276 (fresh design): video blue, audio green, text purple — the
// concept's lane assignment, using the palette's existing hues.
val ClipVideoColor = Color(0xFF1689F5)
val ClipAudioColor = Color(0xFF10B981)
val ClipShapeColor = Color(0xFF3EB1F7)
val ClipOverlayColor = Color(0xFFF54BBF)
val ClipTextColor = Color(0xFF8B5CF6)
val ClipStickerColor = Color(0xFFF54BBF)

// Legacy names kept for source compatibility. "Neon" family → white/grey.
val NeonCyan = AccentBlue
val NeonGreen = Color(0xFFF4F4F5)
val ElectricBlue = AccentBlue
val AccentAmber = Color(0xFFFF9F0A)
val SelectedClipBorder = Color(0xFFFFFFFF)
val TrimHandleColor = AccentBlue

// Text & Icons
val TextPrimary = Color(0xFFF4F4F5)
val TextSecondary = Color(0xFFA1A1AA)
val TextTertiary = Color(0xFF71717A)
val IconActive = Color(0xFFE4E4E7)
val IconDisabled = Color(0xFF52525B)

// Text/icon colour used on top of white fills
val OnAccent = AmInk

// ---- Former neon / gold look — chrome roles now white/grey ----
val NeonLime = Color(0xFF18D8E0)
val NeonLimeDeep = Color(0xFF0FA8B0)
val NeonLimeLight = Color(0x2418D8E0)
val GoldClip = Color(0xFFF7D060)
val GoldClipDeep = Color(0xFFD9A233)
val GoldThumbTop = Color(0xFFD99A1C)
val GoldThumbBottom = Color(0xFF8C5A06)
val PillTop = Color(0xFF151B23)
val PillBottom = Color(0xFF1C242E)
val TrimHandleTop = Color(0xFFFFFFFF)
val TrimHandleBottom = Color(0xFFE4E4E7)
val TimeDim = Color(0xFFA1A1AA)

// ---- Timeline reference ----
val TimelineBlack = AmBaseDeep
val RulerOrange = Color(0xFFF97316)      // elapsed (build 217: the reference orange)
val RulerGrey = Color(0x33FFFFFF)        // upcoming
val RulerTimePill = Color(0xFF131920)
val RulerTimeText = Color(0xFFF4F4F5)
val LanePillDark = Color(0xFF141B24)
val LaneEyeTint = Color(0xFFA1A1AA)
val LaneThumbWhite = Color(0xFFDEDEDE)
val ShapeBarTop = Color(0xFF3EB1F7)
val ShapeBarMid = Color(0xFF2D9BE0)
val ShapeBarBottom = Color(0xFF1689F5)

// ---- Build 215: neon timeline bars (Sarowar's reference, neon-bars.html) ----
// Solid vivid bar bodies, every one carrying the same white crosshatch texture.
// Build 276 (fresh design): the concept's lane assignment — VIDEO blue,
// AUDIO green, TEXT purple — rotated within the palette's existing hues.
val NeonBarPink = Color(0xFF8B5CF6)    // TEXT (build 276: purple)
val NeonBarBlue = Color(0xFF1689F5)    // VIDEO (build 276: blue)
val NeonBarViolet = Color(0xFFF54BBF)  // STICKER / FX (build 217 reference pink)
val NeonBarCyan = Color(0xFF1689F5)    // IMAGE (build 276: blue, same as video)
val NeonBarOrange = Color(0xFFF54BBF)  // OVERLAY (build 217 reference pink)
val NeonBarGreen = Color(0xFF10B981)   // AUDIO (build 276: green)
val NeonBarIndigo = Color(0xFF3A1AE8)  // GROUP / adjustment
val NeonBarGold = Color(0xFF3EB1F7)    // SHAPE (build 217 reference sky)
/** Build 216: a video clip's filmstrip body (dark) under its frame thumbs. */
val ClipFilmBody = Color(0xFF1689F5)
/** Build 276: the accent (was the build-216 reference lime). */
val LimeAccent = Color(0xFF18D8E0)
/** Build 216: light active wash for toggles (cyan since build 276). */
val LimeWash = Color(0x2418D8E0)
/** Build 216: named-marker orange on the ruler. */
val MarkerOrange = Color(0xFFF97316)
val FabCyan = Color(0xFF18D8E0)
val FabBlueDeep = Color(0xFF151B23)
val FabBlueMid = Color(0xFFA1A1AA)
val FabBlueLight = Color(0xFFF4F4F5)

// ---- Header ----
val HeaderBarFill = Color(0xFF0A0E13)
val HeaderBarBorder = Color(0xFF232C38)
val HeaderButtonFill = Color(0xFF151B23)
val HeaderPillFill = Color(0xFF151B23)
val HeaderCyan = Color(0xFFF4F4F5)
val HeaderText = Color(0xFFF4F4F5)

// ---- Build 217: Sarowar's dark + lime HTML reference (ai_studio_code__33.html) ----
/** App background of the reference (build 276: the fresh design's base). */
val RefBg = Color(0xFF0A0E13)
/** Raised surface of the reference (icon buttons, dock boxes). */
val RefSurface = Color(0xFF151B23)
/** Hover / pressed surface of the reference. */
val RefSurfaceHover = Color(0xFF1C242E)
/** The accent (build 217's lime; build 276's cyan). */
val RefLime = Color(0xFF18D8E0)
/** Ink on the accent (export glyph). */
val RefLimeInk = Color(0xFF111114)
/** Popover card of the reference (top-bar menus). */
val RefPopoverBg = Color(0xFF131920)
/** Popover border of the reference. */
val RefPopoverBorder = Color(0xFF2E3947)
/** Popover item text of the reference. */
val RefPopoverText = Color(0xFFD4D4D8)
/** Timeline eye-button fill of the reference. */
val RefEyeBg = Color(0xFF141B24)
/** Hidden layer's eye colour in the reference. */
val RefEyeHidden = Color(0xFF52525B)
/** Null layer bar of the reference. */
val RefNullPink = Color(0xFFF9326A)
/** Transform-box blue of the reference (border) and its handle blue. */
val RefFrameBlue = Color(0xFF3EB1F7)
val RefHandleBlue = Color(0xFF1689F5)
/** Keyframe / dot orange of the reference. */
val RefOrange = Color(0xFFF97316)
/** Controls-bar glyph colour and its disabled colour in the reference. */
val RefCtrl = Color(0xFFE4E4E7)
val RefCtrlDisabled = Color(0xFF45454D)
/** Layer-count pill of the reference. */
val RefPillBg = Color(0xFF131920)
val RefPillBorder = Color(0xFF2E3947)
/** fx badge fill of the reference. */
val RefFxBadge = Color(0xFF1E2732)

/** Build 218 (Sarowar's HTML reference v3, ai_studio_code__39.html): the
 *  layer-bar colours the new reference changes or adds. Media / music /
 *  text / shape / null / overlay keep their build-217 hexes. */
/** Sticker bar — the reference's amber (was the fx magenta in 217). */
val ClipStickerAmber = Color(0xFFF59E0B)
/** Vector (Pen) bar — the reference's cyan. */
val ClipVectorCyan = Color(0xFF06B6D4)
/** Drawing (freehand) bar — the reference's rose. */
val ClipDrawingRose = Color(0xFFFB7185)
/** 3D Camera bar — the reference's indigo (a flat bar now, not the quiet hollow). */
val ClipCameraIndigo = Color(0xFF6366F1)
/** 3D Space bar (build 262) — deep violet, distinct from the camera's indigo. */
val ClipSpaceViolet = Color(0xFF7C4DFF)
/** Group bar — the reference's teal (was indigo in 217). */
val ClipGroupTeal = Color(0xFF14B8A6)
/** Chain section fill (build 218) and its selected fill. */
val ChainSectionBg = Color(0xFF0E1319)
val ChainSectionBgSelected = Color(0xFF131920)
/** Chain section top/bottom hairlines (build 218). */
val ChainSectionBorder = Color(0xFF232C38)
/** Chain bridge line between adjacent children (build 218). */
val ChainBridge = Color(0xFF33404F)
/** Empty-chain label ink (build 218); brighter while the section is selected. */
val ChainEmptyInk = Color(0xFF71717A)
val ChainEmptyInkSelected = Color(0xFFD4D4D8)
/** Modal card border of the reference. */
val RefCardBorder = Color(0xFF2B3644)
