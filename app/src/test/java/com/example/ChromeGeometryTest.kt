package com.example

import com.example.model.ChromeGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Build 250 (UI migration stage 3) — pins the editor chrome's layout
 * law ([ChromeGeometry]) against fixtures hand-derived from the
 * build-249 Compose layouts: the controls bar's groups and badge, the
 * app header's right group, the layer header's zones and name column,
 * the selected header's 167 dp fixed sum and 180 dp name cap, the
 * multi-select bar's panel/tiles/SpaceEvenly boolean row, the shared
 * text placement and the ellipsis rule. Fixtures use density 2 so
 * every expected value is exact in float arithmetic.
 */
class ChromeGeometryTest {

    private val d = 2f

    // ── Shared text law ─────────────────────────────────────────────

    @Test
    fun baselineIsBoxTopMinusAscent() {
        assertEquals(130f, ChromeGeometry.baselinePx(100f, -30f), 0f)
        assertEquals(85f, ChromeGeometry.centeredBoxTopPx(100f, 30f), 0f)
    }

    @Test
    fun ellipsizeKeepsFittingText() {
        val measure = { s: String -> s.length * 10f }
        assertEquals("Layers", ChromeGeometry.ellipsize("Layers", 100f, measure))
        assertEquals("", ChromeGeometry.ellipsize("", 0f, measure))
    }

    @Test
    fun ellipsizeTruncatesToLongestFittingPrefix() {
        val measure = { s: String -> s.length * 10f }
        // cap 60: "…" costs 10, so 5 chars fit ("Layer…"), 6 do not.
        assertEquals("Layer…", ChromeGeometry.ellipsize("Layer name", 60f, measure))
        // cap below the ellipsis itself: the ellipsis is still returned.
        assertEquals("…", ChromeGeometry.ellipsize("Layer name", 5f, measure))
    }

    @Test
    fun ellipsizeNeverSplitsACombiningSequence() {
        // "a" + combining acute (U+0301) + "bcdef": the cut must not
        // strand the combining mark — prefixes are "a" + mark or longer.
        val text = "ábcdef"
        val measure = { s: String -> s.length * 10f }
        // cap 40: "…" (10) + 3 UTF-16 units = 40 — "a"+mark+"b" is a
        // valid cut (3 units); cutting after "a" alone (1 unit) would
        // also fit but is shorter, so the search lands on the longer.
        assertEquals("áb…", ChromeGeometry.ellipsize(text, 40f, measure))
    }

    // ── Controls bar ────────────────────────────────────────────────

    @Test
    fun controlsBarHeightIsTheCanvasDelta() {
        // Build 260 item 6: the editor screen's playControlsH and the
        // exact amount the canvas zone grows by — one constant, 34 dp.
        assertEquals(34f, ChromeGeometry.Controls.BAR_H_DP, 0f)
    }

    @Test
    fun controlsGroupsMirrorBuild249() {
        val c = ChromeGeometry.Controls
        // Bar 800x56 px: buttons (56 dp) centred → top 0.
        assertEquals(0f, c.btnTopPx(56f, d), 0f)
        assertEquals(24f, c.undoLeftPx(d), 0f)
        assertEquals(100f, c.redoLeftPx(d), 0f)
        assertEquals(176f, c.badgeLeftPx(d), 0f)
        assertEquals(372f, c.playLeftPx(800f, d), 0f) // (800 - 56) / 2
        assertEquals(568f, c.rightLeftPx(800f, d, 0), 0f) // 800 - 232
        assertEquals(644f, c.rightLeftPx(800f, d, 1), 0f)
        assertEquals(720f, c.rightLeftPx(800f, d, 2), 0f)
    }

    @Test
    fun controlsGlyphBoxesAreCentred() {
        val c = ChromeGeometry.Controls
        // 19 dp play glyph in a 28 dp button at density 2: inset 9 px.
        assertEquals(381f, c.glyphLeftPx(372f, 19f, d), 0f)
        assertEquals(9f, c.glyphTopPx(0f, 19f, d), 0f)
        // 17 dp glyph: inset 11 px.
        assertEquals(579f, c.glyphLeftPx(568f, 17f, d), 0f)
    }

    @Test
    fun controlsBadgeSizesFromTheLabel() {
        val c = ChromeGeometry.Controls
        // advance 40 px, text height 24 px at density 2.
        assertEquals(56f, c.badgeWidthPx(40f, d), 0f) // 40 + 8*2
        assertEquals(28f, c.badgeHeightPx(24f, d), 0f) // 24 + 2*2
        // Visual centred in the 56 px touch box at left 176.
        assertEquals(176f, c.badgeVisualLeftPx(40f, d), 0f) // 176 + (56-56)/2
        assertEquals(14f, c.badgeVisualTopPx(56f, 24f, d), 0f) // (56-28)/2
        assertEquals(16f, c.badgeTextTopPx(56f, 24f, d), 0f) // 14 + 1*2
    }

    @Test
    fun controlsDotSitsAtGlyphBottomEnd() {
        val c = ChromeGeometry.Controls
        // Keyframe glyph box: left 731, top 11, size 34 (17 dp at d=2) →
        // right 765, bottom 45; dot centre inset 5.5 px.
        assertEquals(759.5f, c.dotCenterXPx(765f, d), 0f)
        assertEquals(39.5f, c.dotCenterYPx(45f, d), 0f)
        assertEquals(5.5f, c.dotRadiusPx(d), 0f)
        assertEquals(4.5f, c.dotRingRadiusPx(d), 0f)
        assertEquals(54f, c.hairlineTopPx(56f, d), 0f)
    }

    // ── App header ──────────────────────────────────────────────────

    @Test
    fun headerRightGroupMirrorsBuild249() {
        val h = ChromeGeometry.Header
        // Build 260: buttons ×1.2 (38.4 dp). Bar 800x88 px (the 44 dp
        // header slot at d=2): button top = (88 - 76.8) / 2 = 5.6.
        assertEquals(5.6f, h.btnTopPx(88f, d), 0.001f)
        assertEquals(20f, h.closeLeftPx(d), 0f)
        // Build 276: Export is a pill — label advance 60 px → width
        // 60 + (12+19.2+5+12)*2 = 156.4; left = 800 - 20 - 156.4.
        assertEquals(156.4f, h.exportPillWidthPx(60f, d), 0.001f)
        assertEquals(623.6f, h.exportLeftPx(800f, 156.4f, d), 0.001f)
        assertEquals(647.6f, h.exportGlyphLeftPx(623.6f, d), 0.001f) // + 12*2
        assertEquals(696f, h.exportTextLeftPx(623.6f, d), 0.001f) // + (12+19.2+5)*2 = 72.4
        // Pill: advance 50 px → width 50 + 32.6*2 = 115.2.
        assertEquals(115.2f, h.pillWidthPx(50f, d), 0.001f)
        assertEquals(494.4f, h.pillLeftPx(800f, 115.2f, 156.4f, d), 0.001f) // 623.6 - 14 - 115.2
        assertEquals(403.6f, h.ratioLeftPx(494.4f, d), 0.001f) // 494.4 - 45.4*2
        assertEquals(514.4f, h.pillTextLeftPx(494.4f, d), 0f)
        assertEquals(572.4f, h.triLeftPx(494.4f, 50f, d), 0.001f) // 494.4 + 28 + 50
        assertEquals(34.4f, h.triTopPx(88f, d), 0.001f) // 5.6 + (38.4-9.6)
    }

    @Test
    fun headerFaceAndGlyphInsets() {
        val h = ChromeGeometry.Header
        assertEquals(74.8f, h.faceSizePx(d), 0.001f) // (38.4-1) * 2
        assertEquals(20.6f, h.faceRadiusPx(d), 0.001f) // (10.8-0.5) * 2
        // 16 dp glyph in a 38.4 dp button: inset 22.4 px at d=2.
        assertEquals(534.4f, h.glyphLeftPx(512f, 16f, d), 0.001f)
        assertEquals(28f, h.glyphTopPx(88f, 16f, d), 0.001f) // 5.6 + 22.4
        // 12 dp glyph: inset 26.4 px.
        assertEquals(46.4f, h.glyphLeftPx(20f, 12f, d), 0.001f)
    }

    // ── Layer header ────────────────────────────────────────────────

    @Test
    fun layerHeaderZonesAndColumn() {
        val l = ChromeGeometry.LayerHeader
        // Build 260: zone ×1.2 = 43.2 dp. Slot 96 px tall (48 dp) →
        // zone = 43.2 dp = 86.4 px, top = 4.8.
        assertEquals(43.2f, l.zoneDp(48f), 0.001f)
        assertEquals(4.8f, l.zoneTopPx(96f, d), 0.001f)
        assertEquals(8f, l.backLeftPx(d), 0f)
        assertEquals(94.4f, l.slotLeftPx(d, 96f), 0.001f) // (4+43.2)*2
        // W=800, 3 icon zones: slot = 800 - 16 - 86.4*4 = 438.4.
        assertEquals(438.4f, l.slotWidthPx(800f, 96f, d, 3), 0.001f)
        assertEquals(98.4f, l.contentLeftPx(d, 96f), 0.001f)
        assertEquals(418.4f, l.contentWidthPx(800f, 96f, d, 3), 0.001f) // 438.4 - 20
        // Trailing zones start right after the slot.
        assertEquals(532.8f, l.zoneLeftPx(800f, 96f, d, 3, 0), 0.001f)
        assertEquals(619.2f, l.zoneLeftPx(800f, 96f, d, 3, 1), 0.001f)
        assertEquals(705.6f, l.zoneLeftPx(800f, 96f, d, 3, 2), 0.001f)
    }

    @Test
    fun layerHeaderColumnAndUnderline() {
        val l = ChromeGeometry.LayerHeader
        // Text height 40 px: renameable column = 40 + 3*2 = 46, top = 25.
        assertEquals(46f, l.columnHeightPx(40f, d, true), 0f)
        assertEquals(40f, l.columnHeightPx(40f, d, false), 0f)
        assertEquals(25f, l.columnTopPx(96f, 40f, d, true), 0f)
        assertEquals(69f, l.underlineTopPx(96f, 40f, d), 0f) // 25 + 40 + 4
        // Tools circle: (26.4 - 1.2) / 2 * 2 = 25.2 px radius.
        assertEquals(25.2f, l.toolsCircleRadiusPx(d), 0.001f)
    }

    @Test
    fun layerHeaderZoneShrinksToShortSlots() {
        val l = ChromeGeometry.LayerHeader
        assertEquals(30f, l.zoneDp(30f), 0f)
        assertEquals(0f, l.zoneTopPx(60f, d), 0f)
    }

    // ── Selected header (219) ───────────────────────────────────────

    @Test
    fun selHeaderFixedSumAndNameCap() {
        val s = ChromeGeometry.SelHeader
        assertEquals(20f, s.backLeftPx(d), 0f)
        // Build 260: buttons ×1.2 — btn = 22.8 + 2*4.8 = 32.4 dp,
        // delete btn = 21.6 + 9.6 = 31.2; fixed sum = 188.4 dp.
        assertEquals(104.8f, s.nameBoxLeftPx(d), 0.001f) // (10 + 32.4 + 10) * 2
        // W=800: name wrap = 800 - 188.4*2 = 423.2 → capped at 180 dp = 360.
        assertEquals(423.2f, s.nameBoxWidthPx(800f, d), 0.001f)
        assertEquals(360f, s.columnWidthPx(800f, d), 0f)
        assertEquals(548f, s.layersLeftPx(800f, d), 0.001f) // 104.8 + 423.2 + 20
        assertEquals(632.8f, s.deleteLeftPx(800f, d), 0.001f) // 548 + 42.4*2
        assertEquals(715.2f, s.moreLeftPx(800f, d), 0.001f) // 632.8 + 41.2*2
        // Narrow screen W=500: wrap = 500-376.8 = 123.2 < cap.
        assertEquals(123.2f, s.columnWidthPx(500f, d), 0.001f)
    }

    @Test
    fun selHeaderColumnAndButtons() {
        val s = ChromeGeometry.SelHeader
        // Slot 116 px (58 dp), text height 40: column = 46, top = 35.
        assertEquals(46f, s.columnHeightPx(40f, d), 0f)
        assertEquals(35f, s.columnTopPx(116f, 40f, d), 0f)
        assertEquals(79f, s.underlineTopPx(116f, 40f, d), 0f) // 35 + 40 + 4
        // Buttons at pad 4.8: a 19 dp glyph's btn = 28.6 dp (57.2 px)
        // → top 29.4; an 18 dp glyph's btn = 27.6 dp → top 30.4.
        assertEquals(29.4f, s.btnTopPx(116f, 19f, d), 0.001f)
        assertEquals(30.4f, s.btnTopPx(116f, 18f, d), 0.001f)
        assertEquals(39f, s.glyphTopPx(116f, 19f, d), 0f) // (116 - 38) / 2
        assertEquals(103.6f, s.glyphLeftPx(94f, d), 0.001f) // + 4.8 dp
    }

    // ── Multi-select bar ────────────────────────────────────────────

    @Test
    fun multiPanelAndRows() {
        val m = ChromeGeometry.Multi
        assertEquals(44f, m.totalHeightDp(false), 0f)
        assertEquals(80f, m.totalHeightDp(true), 0f)
        assertEquals(12f, m.panelLeftPx(d), 0f)
        assertEquals(8f, m.panelTopPx(d), 0f)
        assertEquals(776f, m.panelWidthPx(800f, d), 0f)
        assertEquals(72f, m.panelHeightPx(false, d), 0f) // 88 - 16
        assertEquals(774f, m.panelFaceWidthPx(800f, d), 0f)
        assertEquals(31f, m.panelFaceRadiusPx(d), 0f) // (16 - 0.5) * 2
        assertEquals(32f, m.contentLeftPx(d), 0f)
        assertEquals(736f, m.contentWidthPx(800f, d), 0f)
        assertEquals(44f, m.row1CenterYPx(d), 0f)
        assertEquals(112f, m.boolRowCenterYPx(d), 0f)
    }

    @Test
    fun multiTilesAndLabels() {
        val m = ChromeGeometry.Multi
        // Label height 19 px (7.5 sp at d=2, rounded): tile = 40 + 19 = 59.
        assertEquals(59f, m.tileHeightPx(19f, d), 0f)
        assertEquals(14.5f, m.tileTopPx(44f, 19f, d), 0f)
        // Build 260: Done hugs the right edge: 32 + 736 - 64 = 704;
        // Delete at 704 - 76 = 628; All at 628 - 76 = 552.
        assertEquals(704f, m.doneTileLeftPx(800f, d), 0f)
        assertEquals(628f, m.deleteTileLeftPx(800f, d), 0f)
        assertEquals(552f, m.allTileLeftPx(800f, d), 0f)
        // Mini glyph (20 dp) centred in the 32 dp tile: +6 dp = 12 px.
        assertEquals(716f, m.glyphLeftPx(704f, 32f, 20f, d), 0f)
        assertEquals(18.5f, m.glyphTopPx(14.5f, d), 0f)
        assertEquals(50.5f, m.labelTopPx(14.5f, d), 0f)
        // Label advance 30 px in a 64 px tile: box left = tile + 17.
        assertEquals(721f, m.labelBoxLeftPx(704f, 32f, 30f, d), 0f)
    }

    @Test
    fun multiBooleanRowIsSpaceEvenly() {
        val m = ChromeGeometry.Multi
        // Content 736 px, tiles 4 * 96 = 384 → gap = (736-384)/5 = 70.4.
        assertEquals(70.4f, m.boolGapPx(800f, d), 0.001f)
        assertEquals(102.4f, m.boolTileLeftPx(800f, d, 0), 0.001f)
        assertEquals(268.8f, m.boolTileLeftPx(800f, d, 1), 0.001f)
        // Boolean glyph (22 dp) centred in the 48 dp tile: +13 dp = 26 px.
        assertEquals(128.4f, m.glyphLeftPx(102.4f, 48f, 22f, d), 0.001f)
    }
}
