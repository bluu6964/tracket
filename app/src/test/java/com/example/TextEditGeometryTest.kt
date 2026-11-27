package com.example

import com.example.model.EditorGeometry
import com.example.model.TextEditGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Build 266 — pins the Text editor screen's layout law
 * ([TextEditGeometry]): the top bar's anchors, the preview's frame
 * width, the segmented rows tiling their row exactly, the swatch /
 * preset strips' cumulative layout, and the chip strip's hug-content
 * widths (via the shared pill-strip law).
 */
class TextEditGeometryTest {

    private val densities = listOf(1f, 2f, 2.625f, 3.5f)
    private val eps = 0.01f

    @Test
    fun topBarAnchorsStayInsideTheBar() {
        for (d in densities) {
            val g = TextEditGeometry.TopBar
            // The back box sits inside the bar's height and padding.
            assertTrue(g.BACK_BOX_DP < g.H_DP)
            assertEquals(
                (TextEditGeometry.SCREEN_PAD_H_DP + g.BACK_BOX_DP) * d,
                g.backCentreXPx(d) + g.BACK_BOX_DP * d / 2f, eps
            )
            // The title starts right after the back box.
            assertEquals(
                g.backCentreXPx(d) + g.BACK_BOX_DP * d / 2f + 4f * d,
                g.titleLeftPx(d), eps
            )
            // The Done pill's right edge is the screen padding.
            val rowW = 360f * d
            val pillW = EditorGeometry.ScreenChrome.pillWPx(80f, d)
            assertEquals(
                rowW - TextEditGeometry.SCREEN_PAD_H_DP * d,
                g.doneCentreXPx(rowW, pillW, d) + pillW / 2f, eps
            )
            assertEquals(g.H_DP * d / 2f, g.centreYPx(d), eps)
        }
    }

    @Test
    fun previewFrameIsThePaddedScreenWidth() {
        for (d in densities) {
            val screenW = 360f * d
            val frame = TextEditGeometry.Preview.frameWidthPx(screenW, d)
            assertEquals(screenW - 2f * TextEditGeometry.SCREEN_PAD_H_DP * d, frame, eps)
            assertTrue("frame must stay positive", frame > 0f)
        }
    }

    @Test
    fun segmentedCellsTileTheRowExactly() {
        val g = TextEditGeometry.Segmented
        for (d in densities) {
            for (count in listOf(1, 2, 3, 4)) {
                val rowW = 328f * d
                val cellW = g.cellWPx(rowW, count, d)
                assertTrue("cell width positive (count=$count)", cellW > 0f)
                // First cell at 0; last cell's right edge is the row width.
                assertEquals(0f, g.cellLeftPx(0, rowW, count, d), eps)
                val lastRight = g.cellLeftPx(count - 1, rowW, count, d) + cellW
                assertEquals("cells tile the row (count=$count)", rowW, lastRight, eps)
                // Cells are ordered and never overlap.
                for (i in 1 until count) {
                    val prevRight = g.cellLeftPx(i - 1, rowW, count, d) + cellW
                    assertTrue(g.cellLeftPx(i, rowW, count, d) >= prevRight - eps)
                }
                // Centre is the cell's middle.
                assertEquals(
                    g.cellLeftPx(0, rowW, count, d) + cellW / 2f,
                    g.cellCentreXPx(0, rowW, count, d), eps
                )
            }
        }
    }

    @Test
    fun segmentedRunIsCentredInItsCell() {
        val g = TextEditGeometry.Segmented
        val d = 2f
        val rowW = 328f * d
        val cellW = g.cellWPx(rowW, 3, d)
        // Label-only run: the label's centre is the cell's centre.
        val labelAdv = 60f
        val left = g.runLeftPx(0f, cellW, labelAdv, hasIcon = false, density = d)
        assertEquals(cellW / 2f, left + labelAdv / 2f, eps)
        // Icon+label run: the whole run (icon + gap + label) is centred.
        val runW = labelAdv + (g.ICON_DP + g.ICON_GAP_DP) * d
        val left2 = g.runLeftPx(0f, cellW, labelAdv, hasIcon = true, density = d)
        assertEquals(cellW / 2f, left2 + runW / 2f, eps)
    }

    @Test
    fun swatchStripLayoutIsCumulative() {
        val g = TextEditGeometry.Swatches
        for (d in densities) {
            assertEquals(0f, g.contentWPx(0, d), eps)
            assertEquals(g.CELL_DP * d, g.contentWPx(1, d), eps)
            val n = 12
            // Last swatch's right edge is the content width.
            assertEquals(
                g.contentWPx(n, d),
                g.leftPx(n - 1, d) + g.CELL_DP * d, eps
            )
            // Centres march by cell + gap.
            for (i in 1 until n) {
                assertEquals(
                    (g.CELL_DP + g.GAP_DP) * d,
                    g.centreXPx(i, d) - g.centreXPx(i - 1, d), eps
                )
            }
        }
    }

    @Test
    fun presetStripLayoutIsCumulative() {
        val g = TextEditGeometry.Presets
        for (d in densities) {
            assertEquals(0f, g.contentWPx(0, d), eps)
            val n = 10
            assertEquals(
                g.contentWPx(n, d),
                g.leftPx(n - 1, d) + g.TILE_DP * d, eps
            )
            assertEquals(
                g.leftPx(2, d) + g.TILE_DP * d / 2f,
                g.centreXPx(2, d), eps
            )
        }
    }

    @Test
    fun chipStripWidthsAreHugContent() {
        val g = TextEditGeometry.Chips
        val d = 2.625f
        val advances = listOf(52f, 118f, 30f, 84f)
        val widths = advances.map { g.chipWPx(it, d) }
        // Each chip is exactly its advance + both paddings.
        advances.forEachIndexed { i, adv ->
            assertEquals(adv + 2f * g.PAD_H_DP * d, widths[i], eps)
        }
        // Lefts are cumulative with the strip gap; content width is
        // the last chip's right edge.
        val lefts = g.leftsPx(widths, d)
        assertEquals(0f, lefts[0], eps)
        for (i in 1 until widths.size) {
            assertEquals(lefts[i - 1] + widths[i - 1] + g.STRIP_GAP_DP * d, lefts[i], eps)
        }
        assertEquals(lefts.last() + widths.last(), g.contentWPx(widths, d), eps)
        assertTrue(abs(g.contentWPx(widths, d) - (widths.sum() + g.STRIP_GAP_DP * d * (widths.size - 1))) < eps)
    }

    // ── Build 267: every row must leave its controls a real slot ────
    //
    // The build-266 screen drew only the labels from Spacing down:
    // the label line's box took the whole row, so the weighted
    // slider / swatch strip / pill strip measured 0 wide and the
    // pinned tiles + Done pill fell off the right edge. These tests
    // pin the row laws the hosts lay out by: with realistic label
    // advances, every control's rect is positive and inside its row.

    @Test
    fun topBarTitleHugsAndDoneFits() {
        val g = TextEditGeometry.TopBar
        for (d in densities) {
            val rowW = 360f * d
            val titleAdv = 42f * d // "Text" in the header style
            val titleW = g.titleWPx(titleAdv, rowW)
            assertEquals(titleAdv, titleW, eps)
            val pillW = EditorGeometry.ScreenChrome.pillWPx(80f, d)
            val doneL = g.doneLeftPx(rowW, pillW, d)
            // The title ends well before the Done pill starts, and
            // the pill's right edge is the screen padding.
            assertTrue(
                "title must not reach Done (d=$d)",
                g.titleLeftPx(d) + titleW < doneL
            )
            assertEquals(rowW - TextEditGeometry.SCREEN_PAD_H_DP * d, doneL + pillW, eps)
            assertTrue("Done pill has real width (d=$d)", pillW > 0f)
        }
    }

    @Test
    fun sliderRowsGiveTheSliderARealSpan() {
        val g = TextEditGeometry.SliderRow
        // (label advance dp, value advance dp) for the screen's rows:
        // Letter space / Line height / Thickness / Opacity / Blur /
        // Distance / Angle / Corner / Padding / Reveal start / end.
        val cases = listOf(82f to 24f, 76f to 32f, 62f to 24f, 52f to 32f, 30f to 32f, 55f to 36f)
        for (d in densities) {
            val rowW = 328f * d // 360 dp screen − the screen padding
            for ((labelDp, valueDp) in cases) {
                val labelAdv = labelDp * d
                val valueAdv = valueDp * d
                val sliderW = g.sliderWPx(rowW, labelAdv, valueAdv, d)
                assertTrue(
                    "slider span must be real (label=$labelDp d=$d)",
                    sliderW > 100f * d
                )
                // The three boxes + two gaps tile the row exactly.
                val sliderL = g.sliderLeftPx(labelAdv, rowW, d)
                assertEquals(g.labelWPx(labelAdv, rowW) + g.LABEL_GAP_DP * d, sliderL, eps)
                val valueL = g.valueLeftPx(rowW, labelAdv, valueAdv, d)
                assertEquals(sliderL + sliderW + g.VALUE_GAP_DP * d, valueL, eps)
                assertEquals(rowW, valueL + g.valueWPx(valueAdv, rowW), eps)
            }
        }
    }

    @Test
    fun colorRowsGiveTheStripAndPinnedTilesRealSlots() {
        val g = TextEditGeometry.Swatches
        for (d in densities) {
            val rowW = 328f * d
            val labelAdv = 34f * d // "Color"
            val stripW = g.stripWPx(rowW, labelAdv, d)
            // The strip shows at least two full swatches (it scrolls
            // for the rest) — build 266 showed none.
            assertTrue("strip must be real (d=$d)", stripW > 2f * g.CELL_DP * d)
            val stripL = g.stripLeftPx(labelAdv, rowW, d)
            assertEquals(g.labelWPx(labelAdv, rowW) + g.LABEL_GAP_DP * d, stripL, eps)
            // Pinned tiles: custom then eyedropper, ending flush
            // with the row's right edge, all inside the row.
            val customL = g.customLeftPx(rowW, labelAdv, d)
            assertEquals(stripL + stripW + g.PIN_GAP_DP * d, customL, eps)
            val dropL = g.eyedropperLeftPx(rowW, labelAdv, d)
            assertEquals(customL + g.CELL_DP * d + g.GAP_DP * d, dropL, eps)
            assertEquals(rowW, dropL + g.CELL_DP * d, eps)
            // The strip's own swatch layout starts at the strip's left.
            assertEquals(0f, g.leftPx(0, d), eps)
            assertTrue(g.contentWPx(12, d) > stripW) // 12 swatches scroll
        }
    }

    // ── Build 269: the Styles sub-sections + the Stroke rows ────
    //
    // Build 268's tab reorganisation dropped the manual Stroke
    // group (Thickness slider + stroke colour row) — no tab home.
    // These pins keep the sub-section list (Stroke restored after
    // Background) and the Stroke rows' control slots from ever
    // silently vanishing again.

    @Test
    fun stylesSectionsFollowCapCutOrder() {
        // Build 271: the reference's sub-tab list (Curve is not a
        // sub-tab in the reference — the Path controls ride at the
        // end of Spacing under a "Curve" label).
        assertEquals(
            listOf("Text", "Stroke", "Glow", "Background", "Shadow", "Spacing", "Bold Italic", "Case"),
            TextEditGeometry.Styles.SECTION_LABELS
        )
    }

    @Test
    fun strokeRowsGiveControlsRealSlots() {
        val sliders = TextEditGeometry.SliderRow
        val swatches = TextEditGeometry.Swatches
        for (d in densities) {
            val rowW = 328f * d // 360 dp screen − the screen padding
            // Thickness slider row ("Thickness" label, "100%" value):
            // the slider span is real and the boxes tile the row.
            val labelAdv = 62f * d
            val valueAdv = 32f * d
            val sliderW = sliders.sliderWPx(rowW, labelAdv, valueAdv, d)
            assertTrue("Stroke slider span must be real (d=$d)", sliderW > 100f * d)
            val sliderL = sliders.sliderLeftPx(labelAdv, rowW, d)
            assertEquals(
                sliders.labelWPx(labelAdv, rowW) + sliders.LABEL_GAP_DP * d, sliderL, eps
            )
            val valueL = sliders.valueLeftPx(rowW, labelAdv, valueAdv, d)
            assertEquals(sliderL + sliderW + sliders.VALUE_GAP_DP * d, valueL, eps)
            assertEquals(rowW, valueL + sliders.valueWPx(valueAdv, rowW), eps)
            // Stroke colour row ("Color" label): the swatch strip is
            // real and the custom + eyedropper tiles end flush with
            // the row's right edge.
            val colorAdv = 34f * d
            val stripW = swatches.stripWPx(rowW, colorAdv, d)
            assertTrue(
                "Stroke colour strip must be real (d=$d)",
                stripW > 2f * swatches.CELL_DP * d
            )
            val customL = swatches.customLeftPx(rowW, colorAdv, d)
            val dropL = swatches.eyedropperLeftPx(rowW, colorAdv, d)
            assertEquals(
                swatches.stripLeftPx(colorAdv, rowW, d) + stripW +
                    swatches.PIN_GAP_DP * d,
                customL, eps
            )
            assertEquals(customL + swatches.CELL_DP * d + swatches.GAP_DP * d, dropL, eps)
            assertEquals(rowW, dropL + swatches.CELL_DP * d, eps)
        }
    }

    // ── Build 268: the panel height law ─────────────────────────
    //
    // Sarowar's rule: drag the head and the panel follows the finger
    // 1:1 and STAYS where it is released — no snap points anywhere.
    // These tests pin the pure law the host lays out by.

    @Test
    fun panelDragFollowsTheFingerOneToOne() {
        val g = TextEditGeometry.Panel
        for (d in densities) {
            val min = g.minHeightPx(d)
            val max = g.maxHeightPx(700f * d)
            val start = 400f * d
            // Finger moves up 30 dp (delta is negative: down = +):
            // the panel grows by exactly 30 dp — no rounding, no step.
            assertEquals(start + 30f * d, g.draggedHeightPx(start, -30f * d, min, max), eps)
            // Finger moves down 45.5 dp: the panel shrinks by exactly that.
            assertEquals(start - 45.5f * d, g.draggedHeightPx(start, 45.5f * d, min, max), eps)
            // A fractional delta lands on the exact fractional height
            // (a snap law would round this to a detent).
            assertEquals(start - 13.37f * d, g.draggedHeightPx(start, 13.37f * d, min, max), 0.1f)
        }
    }

    @Test
    fun panelDragClampsAtBothEnds() {
        val g = TextEditGeometry.Panel
        for (d in densities) {
            val min = g.minHeightPx(d)
            val max = g.maxHeightPx(700f * d)
            // A huge upward drag stops at the available height (the
            // lower zone — the canvas above is never covered).
            assertEquals(max, g.draggedHeightPx(400f * d, -100000f, min, max), eps)
            // A huge downward drag stops at the minimum: head +
            // typing field + tab row + about one control row + the
            // build-276 Done bar.
            assertEquals(min, g.draggedHeightPx(400f * d, 100000f, min, max), eps)
            assertEquals(
                (g.HEAD_H_DP + g.FIELD_H_DP + g.TAB_ROW_H_DP + g.MIN_CONTENT_DP + g.DONE_BAR_H_DP) * d,
                min, eps
            )
        }
    }

    @Test
    fun panelReleaseStaysExactlyThere() {
        val g = TextEditGeometry.Panel
        val d = 2.625f
        val min = g.minHeightPx(d)
        val max = g.maxHeightPx(700f * d)
        // Release = the dragged value passes the clamp unchanged, and
        // re-clamping the remembered height is a fixed point (the
        // host restores it verbatim on reopen).
        val released = g.draggedHeightPx(400f * d, -77.25f * d, min, max)
        assertEquals(released, g.clampHeightPx(released, min, max), eps)
        // A zero-delta drag (a tap on the head) never moves the panel.
        assertEquals(released, g.draggedHeightPx(released, 0f, min, max), eps)
    }

    @Test
    fun panelImeShrinksTheDragRange() {
        val g = TextEditGeometry.Panel
        val d = 2f
        val available = 700f * d
        val ime = 300f * d
        // With the keyboard up, the max is measured in the space
        // above the keyboard.
        assertEquals(available - ime, g.imeMaxHeightPx(available, ime), eps)
        assertEquals(available, g.imeMaxHeightPx(available, 0f), eps)
        // A height remembered from before the keyboard opened clamps
        // into the IME range; the minimum still wins over an inverted
        // range so the typing field stays visible.
        val min = g.minHeightPx(d)
        val clamped = g.clampHeightPx(650f * d, min, g.imeMaxHeightPx(available, ime))
        assertEquals(available - ime, clamped, eps)
        val inverted = g.clampHeightPx(650f * d, min, g.imeMaxHeightPx(100f * d, ime))
        assertEquals(min, inverted, eps)
    }

    @Test
    fun panelImeOpenKeepsHeadFieldAndTabsVisible() {
        val g = TextEditGeometry.Panel
        for (d in densities) {
            // The build-269 scenario: an 800 dp screen's owned zone,
            // a 300 dp keyboard. The clamped height must still fit
            // head + field + tab row + the minimum content, and the
            // height never exceeds the IME range — the panel's top
            // stays at or below the zone's top (the canvas's bottom
            // edge), its bottom on the keyboard's top edge (the
            // host's padding), so the head and typing field are
            // always fully on screen.
            val zone = 800f * d
            val ime = 300f * d
            val imeMax = g.imeMaxHeightPx(zone, ime)
            assertEquals(zone - ime, imeMax, eps)
            assertTrue("IME max must fit the minimum panel (d=$d)", imeMax >= g.minHeightPx(d))
            assertTrue(
                "head + field + tab row must fit (d=$d)",
                imeMax >= (g.HEAD_H_DP + g.FIELD_H_DP + g.TAB_ROW_H_DP) * d
            )
            // A taller remembered height clamps INTO the IME range…
            val remembered = 620f * d
            assertEquals(imeMax, g.clampHeightPx(remembered, g.minHeightPx(d), imeMax), eps)
            // …and when the keyboard closes (ime = 0) the SAME
            // remembered height comes back verbatim — the IME clamp
            // is a display clamp only, never a rewrite of the
            // session's height.
            assertEquals(
                remembered,
                g.clampHeightPx(remembered, g.minHeightPx(d), g.imeMaxHeightPx(zone, 0f)),
                eps
            )
            // The IME range never exceeds the plain range: the
            // panel top can never rise above the zone's top.
            assertTrue(imeMax <= g.maxHeightPx(zone))
        }
    }

    @Test
    fun panelDefaultShowsTwoTemplateRows() {
        val g = TextEditGeometry.Panel
        val grid = TextEditGeometry.Grid
        val ref = TextEditGeometry.Ref
        // Build 271's derivation: sub-header + content top pad +
        // two nominal square cells + the row gap.
        val twoRows = (ref.SUBHEADER_H_DP + ref.CONTENT_PAD_TOP_DP +
            2f * ref.TEMPLATE_CELL_NOMINAL_DP + grid.GAP_DP)
        for (d in densities) {
            // The two-row height is exactly chrome + the build-276
            // Done bar + two card rows.
            assertEquals(
                g.chromeHeightPx(d) + g.DONE_BAR_H_DP * d + twoRows * d,
                g.twoTemplateRowsHeightPx(d), eps
            )
            // A roomy zone: the default's content viewport fits two
            // FULL template-card rows (build 268 opened at 62% of a
            // phone zone — below the minimum — and showed a sliver).
            val roomy = 600f * d
            val defRoomy = g.defaultHeightPx(roomy, d)
            assertTrue(
                "default must show two full rows (d=$d)",
                g.contentViewportPx(defRoomy, d) >= twoRows * d
            )
            // A zone of exactly the two-row height opens at it.
            val exact = g.twoTemplateRowsHeightPx(d)
            assertEquals(exact, g.defaultHeightPx(exact, d), eps)
            // A zone smaller than two rows opens at the zone max —
            // the best possible without touching the canvas.
            val small = 300f * d
            assertEquals(
                g.maxHeightPx(small), g.defaultHeightPx(small, d), eps
            )
        }
    }

    @Test
    fun panelDefaultHeightIsInsideTheRange() {
        val g = TextEditGeometry.Panel
        for (d in densities) {
            val available = 700f * d
            val def = g.defaultHeightPx(available, d)
            assertTrue(def >= g.minHeightPx(d))
            assertTrue(def <= g.maxHeightPx(available))
            assertEquals(available * g.DEFAULT_FRACTION, def, eps)
            // A zone smaller than the minimum still yields the minimum.
            assertEquals(g.minHeightPx(d), g.defaultHeightPx(100f * d, d), eps)
        }
    }

    @Test
    fun tileGridsTileTheRowExactly() {
        val g = TextEditGeometry.Grid
        for (d in densities) {
            val rowW = 328f * d
            for (cols in listOf(2, 3)) {
                // The last cell's right edge is exactly the row width.
                assertEquals(rowW, g.lastCellRightPx(rowW, cols, d), eps)
                assertTrue(g.cellWPx(rowW, cols, d) > 0f)
                // Row-major lefts repeat per row.
                assertEquals(g.cellLeftPx(0, rowW, cols, d), g.cellLeftPx(cols, rowW, cols, d), eps)
                assertEquals(g.cellLeftPx(1, rowW, cols, d), g.cellLeftPx(cols + 1, rowW, cols, d), eps)
            }
            // Row counts: Templates 13 cards at 2 cols = 7 rows;
            // Effects 11 tiles at 3 cols = 4 rows; Bubbles 6 at 3 = 2.
            assertEquals(7, g.rowCount(13, 2))
            assertEquals(4, g.rowCount(11, 3))
            assertEquals(2, g.rowCount(6, 3))
            assertEquals(0, g.rowCount(0, 3))
        }
    }

    // ── Build 271: the reference panel's metrics ─────────────────

    @Test
    fun refPrimaryTabUnderlineIsCentredUnderItsLabel() {
        val g = TextEditGeometry.Ref
        for (d in densities) {
            val advances = listOf(70f, 40f, 38f, 42f, 66f, 48f).map { it * d }
            val lefts = g.tabLeftsPx(advances, d)
            assertEquals(g.TAB_PAD_H_DP * d, lefts[0], eps)
            for (i in 1 until advances.size) {
                assertEquals(
                    lefts[i - 1] + advances[i - 1] + g.TAB_GAP_DP * d, lefts[i], eps
                )
            }
            // The underline's centre is the label's centre.
            for (i in advances.indices) {
                val u = g.underlineLeftPx(lefts[i], advances[i], d)
                assertEquals(
                    lefts[i] + advances[i] / 2f,
                    u + g.UNDERLINE_W_DP * d / 2f, eps
                )
            }
            // Content width: last label's right edge + end padding.
            assertEquals(
                lefts.last() + advances.last() + g.TAB_PAD_H_DP * d,
                g.tabsContentWPx(advances, d), eps
            )
        }
    }

    @Test
    fun refSliderRowTilesExactly() {
        val g = TextEditGeometry.Ref
        for (d in densities) {
            val rowW = 360f * d
            val span = g.sliderSpanWPx(rowW, d)
            assertTrue("slider span must be real (d=$d)", span > 100f * d)
            // label + gap + span + gap + value = the row, exactly.
            assertEquals(
                rowW,
                g.SLIDER_LABEL_W_DP * d + g.SLIDER_GAP_DP * d + span +
                    g.SLIDER_GAP_DP * d + g.SLIDER_VALUE_W_DP * d,
                eps
            )
            assertEquals(
                (g.SLIDER_LABEL_W_DP + g.SLIDER_GAP_DP) * d,
                g.sliderLeftPx(d), eps
            )
            assertEquals(rowW - g.SLIDER_VALUE_W_DP * d, g.sliderValueLeftPx(rowW, d), eps)
            // The thumb mapping is a bijection between the span's
            // travel and 0..1 (thumb radius in from both ends).
            assertEquals(0f, g.sliderFraction(0f, span, d), eps)
            assertEquals(1f, g.sliderFraction(span, span, d), eps)
            assertEquals(
                0.5f,
                g.sliderFraction(g.sliderThumbXPx(0.5f, span, d), span, d), eps
            )
            assertEquals(
                span / 2f,
                g.sliderThumbXPx(0.5f, span, d), eps
            )
        }
    }

    @Test
    fun refSwatchRowIsCumulative() {
        val g = TextEditGeometry.Ref
        for (d in densities) {
            val lefts = g.swatchLeftsPx(12, d)
            assertEquals(0f, lefts[0], eps)
            for (i in 1 until 12) {
                assertEquals(
                    (g.SWATCH_DP + g.SWATCH_GAP_DP) * d,
                    lefts[i] - lefts[i - 1], eps
                )
            }
            assertEquals(
                lefts.last() + g.SWATCH_DP * d,
                g.swatchContentWPx(12, d), eps
            )
            assertEquals(0f, g.swatchContentWPx(0, d), eps)
        }
    }

    @Test
    fun refPillWidthsAreHugContentWithAMinimum() {
        val g = TextEditGeometry.Ref
        for (d in densities) {
            // A short label ("B") floors at the 48dp minimum.
            assertEquals(g.PILL_MIN_W_DP * d, g.pillWPx(8f * d, d), eps)
            // A long label is its advance + both paddings.
            assertEquals(90f * d + 2f * g.PILL_PAD_H_DP * d, g.pillWPx(90f * d, d), eps)
            val widths = listOf(48f, 80f, 60f).map { it * d }
            val lefts = g.pillLeftsPx(widths, d)
            assertEquals(0f, lefts[0], eps)
            for (i in 1 until widths.size) {
                assertEquals(
                    lefts[i - 1] + widths[i - 1] + g.PILL_GAP_DP * d, lefts[i], eps
                )
            }
        }
    }

    @Test
    fun refBubbleCardAspectIsTheReferenceRatio() {
        val g = TextEditGeometry.Ref
        for (d in densities) {
            val cell = 80f * d
            assertEquals(cell / 1.25f, g.bubbleCardHPx(cell), eps)
        }
    }

    @Test
    fun refCollapseHeightIsExactlyTheChrome() {
        val g = TextEditGeometry.Panel
        for (d in densities) {
            // Kb-active: head + input row + primary tabs, no body.
            assertEquals(g.chromeHeightPx(d), g.collapseHeightPx(d), eps)
            assertEquals(
                (g.HEAD_H_DP + g.FIELD_H_DP + g.TAB_ROW_H_DP) * d,
                g.collapseHeightPx(d), eps
            )
            // The collapse is below the minimum (which adds one
            // content row) — it is a distinct keyboard state, and
            // the resting height is never rewritten by it.
            assertTrue(g.collapseHeightPx(d) < g.minHeightPx(d))
        }
    }

    @Test
    fun refSubTabItemsTileWithTheRowGap() {
        val g = TextEditGeometry.Ref
        for (d in densities) {
            val advances = listOf(52f, 84f, 44f, 30f).map { it * d }
            val widths = advances.map { g.subItemWPx(it, d) }
            advances.forEachIndexed { i, adv ->
                assertEquals(adv + 2f * g.SUB_ITEM_PAD_H_DP * d, widths[i], eps)
            }
            val lefts = g.subLeftsPx(widths, d)
            assertEquals(0f, lefts[0], eps)
            for (i in 1 until widths.size) {
                assertEquals(
                    lefts[i - 1] + widths[i - 1] + g.SUB_GAP_DP * d, lefts[i], eps
                )
            }
        }
    }

    @Test
    fun weightStripSlotIsRealAndPillsTile() {
        val g = TextEditGeometry.Chips
        for (d in densities) {
            val rowW = 328f * d
            val labelAdv = 72f * d // "Font weight"
            val slot = g.stripSlotWPx(rowW, labelAdv, d)
            assertTrue("weight strip slot must be real (d=$d)", slot > 100f * d)
            // The seven weight pills (GlPillStrip law, padH 14, gap 6):
            // lefts cumulative, first pill fully inside the slot.
            val advances = List(7) { 26f * d }
            val widths = EditorGeometry.PillStrip.itemWidthsPx(advances, 14f, d)
            val lefts = EditorGeometry.PillStrip.itemLeftsPx(widths, 6f, d)
            assertEquals(0f, lefts[0], eps)
            assertTrue("first pill fits the slot (d=$d)", widths[0] < slot)
            for (i in 1 until widths.size) {
                assertEquals(lefts[i - 1] + widths[i - 1] + 6f * d, lefts[i], eps)
            }
            assertEquals(
                EditorGeometry.PillStrip.contentWPx(widths, 6f, d),
                lefts.last() + widths.last(), eps
            )
        }
    }
}
