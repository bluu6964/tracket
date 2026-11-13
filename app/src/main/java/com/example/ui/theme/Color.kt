package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// After Motion dark palette. Since Sarowar's 2026-11-13 ~20:30 order the whole UI is
// simple BLACK & WHITE (CapCut-style): the token layer's near-black base (#0A0A0A — see
// theme/DesignTokens.kt, AmBase) and flat greys; the legacy lime "Neon" aliases below
// now resolve to white/grey so every older chrome surface follows the same language.
val EditorBackground = Color(0xFF0A0A0A)
val EditorSurface = Color(0xFF2E2E2E)
// Timeline lane controls (left pills)
val LanePill = Color(0xFF1E1E1E)
val LanePillBorder = Color(0xFF333333)
val EditorSurfaceVariant = Color(0xFF383838)
val EditorDivider = Color(0xFF474747)
val GlassSurface = Color(0xFF303030)
val GlassHighlight = Color(0xFFFFFFFF)
// Canvas / project background: full black.
val CanvasBackground = Color(0xFF000000)
val AccentBlue = Color(0xFFFFFFFF)
val AccentBlueSoft = Color(0xFFD8D8D8)

// Timeline & Track colors
val TrackBackground = Color(0xFF1C1C1C)
val TrackBorder = Color(0xFF424242)
val PlayheadColor = Color(0xFFF5F5F7)
val PlayheadAccent = Color(0xFFFFFFFF)
val RulerTickColor = Color(0xFFBDBDBD)
val RulerSubTickColor = Color(0xFF5C5C5C)

// Clip type colors — muted, professional surfaces
val ClipVideoColor = Color(0xFF27384C)
val ClipAudioColor = Color(0xFF20382D)
val ClipShapeColor = Color(0xFF1D3440)
val ClipOverlayColor = Color(0xFF38253A)
val ClipTextColor = Color(0xFF3992CE)
val ClipStickerColor = Color(0xFF3A2A20)

// Legacy names kept so existing editor code remains source-compatible. The "Neon"
// family was the old lime accent; in the B/W language every one resolves to white/grey.
val NeonCyan = AccentBlue
val NeonGreen = Color(0xFFFFFFFF)
val ElectricBlue = AccentBlue
val AccentAmber = Color(0xFFFF9F0A)
val SelectedClipBorder = Color(0xFFF2F2F7)
val TrimHandleColor = AccentBlue

// Text & Icons
val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFFA1A1A6)
val TextTertiary = Color(0xFF6E6E73)
val IconActive = Color(0xFFE5E5EA)
val IconDisabled = Color(0xFF5A5A5A)

// Text/icon colour used on top of the lime accent
val OnAccent = Color(0xFF0F0F0F)

// ---- Former neon lime / gold look — chrome roles now white/grey (B/W order) ----
val NeonLime = Color(0xFFFFFFFF)
val NeonLimeDeep = Color(0xFF8A8A8A)
val NeonLimeLight = Color(0xFFE8E8E8)
val GoldClip = Color(0xFFF7D060)
val GoldClipDeep = Color(0xFFD9A233)
val GoldThumbTop = Color(0xFFD99A1C)
val GoldThumbBottom = Color(0xFF8C5A06)
val PillTop = Color(0xFF2C2C2C)
val PillBottom = Color(0xFF161616)
val TrimHandleTop = Color(0xFF404040)
val TrimHandleBottom = Color(0xFF2E2E2E)
val TimeDim = Color(0xFF8E8E8E)

// ---- Reference timeline look (measured from the design screenshot) ----
val TimelineBlack = Color(0xFF000000)
val RulerOrange = Color(0xFFF5F5F5)      // elapsed ruler bars (was orange; B/W: white vs grey)
val RulerGrey = Color(0xFF595959)        // upcoming ruler bars
val RulerTimePill = Color(0xFF131311)
val RulerTimeText = Color(0xFFEDEDED)
val LanePillDark = Color(0xFF080806)     // eye + thumbnail pill
val LaneEyeTint = Color(0xFFC8C8C8)
val LaneThumbWhite = Color(0xFFDEDEDE)
val ShapeBarTop = Color(0xFFA0E24C)      // shape layer bar: top -> bottom gradient
val ShapeBarMid = Color(0xFF80CE22)
val ShapeBarBottom = Color(0xFF69BD07)
val FabCyan = Color(0xFFFFFFFF)
val FabBlueDeep = Color(0xFF3A3A3A)
val FabBlueMid = Color(0xFF6A6A6A)
val FabBlueLight = Color(0xFFC8C8C8)

// ---- Reference header (pill bar) — neutralised to flat greys (B/W order) ----
val HeaderBarFill = Color(0xFF0B0B0B)
val HeaderBarBorder = Color(0xFF2A2A2A)
val HeaderButtonFill = Color(0xFF1D1D1D)
val HeaderPillFill = Color(0xFF222222)
val HeaderCyan = Color(0xFFFFFFFF)
val HeaderText = Color(0xFFEEF2F7)
