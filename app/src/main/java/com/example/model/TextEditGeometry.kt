package com.example.model

/**
 * Build 266 — the Text editor screen's layout law (Sarowar's order:
 * the whole Text section UI redesigned, "simple and clean", drawn by
 * the C++ GL UI path like the rest of the app since builds 248–253).
 *
 * The screen is one calm full-screen surface: a top bar (back /
 * "Text" / Done), a preview block, the hosted typing field, and one
 * flat scrolling list of full-width rows grouped Font / Style /
 * Color / Alignment / Spacing / Stroke / Shadow / Background /
 * Reveal / Path. Every size the GL host draws comes from here; the
 * host keeps only layout, touch boxes, semantics and state wiring
 * (the stage-2/3/4/5 pattern). All functions work in raw pixels;
 * [density] converts the dp constants exactly as `Dp.toPx()` did.
 * Text placement inside a line box follows the shared law
 * ([EditorGeometry.centredTextTopPx] + [PanelGeometry.lineBaselinePx]).
 */
object TextEditGeometry {

    /** The screen's horizontal padding (every block and row). */
    const val SCREEN_PAD_H_DP = 16f

    // ── Top bar ─────────────────────────────────────────────────────

    object TopBar {
        const val H_DP = 52f

        /** The back arrow's square tap box. */
        const val BACK_BOX_DP = 36f

        /** The back arrow glyph's size inside its box. */
        const val BACK_ICON_DP = 20f

        /** The back box's centre x: padding + half the box. */
        fun backCentreXPx(density: Float): Float =
            (SCREEN_PAD_H_DP + BACK_BOX_DP / 2f) * density

        /** The title's left edge: padding + back box + the gap after it. */
        fun titleLeftPx(density: Float): Float =
            (SCREEN_PAD_H_DP + BACK_BOX_DP + 4f) * density

        /** The Done pill's centre x: its right edge is the screen padding. */
        fun doneCentreXPx(rowWPx: Float, pillWPx: Float, density: Float): Float =
            rowWPx - SCREEN_PAD_H_DP * density - pillWPx / 2f

        /** The Done pill's left edge. */
        fun doneLeftPx(rowWPx: Float, pillWPx: Float, density: Float): Float =
            doneCentreXPx(rowWPx, pillWPx, density) - pillWPx / 2f

        /**
         * The title's box width: the hug rule ([EditorGeometry.LabelLine])
         * — the title takes only its advance, so the bar's weighted
         * spacer, not the title, absorbs the free width and the Done
         * pill lands at the right edge. (Build 267: the title's box
         * previously spanned the whole bar and pushed Done out.)
         */
        fun titleWPx(titleAdvancePx: Float, rowWPx: Float): Float =
            EditorGeometry.LabelLine.boxWPx(titleAdvancePx, 0f, rowWPx)

        /** The bar's vertical centre (every element shares it). */
        fun centreYPx(density: Float): Float = H_DP * density / 2f
    }

    // ── Preview block ───────────────────────────────────────────────

    object Preview {
        const val H_DP = 132f
        const val CORNER_DP = 12f

        /**
         * The frame width handed to the text painter: the preview
         * surface spans the screen inside its horizontal padding, and
         * the painter's own sizes are fractions of this width (the
         * same rule the canvas uses).
         */
        fun frameWidthPx(screenWPx: Float, density: Float): Float =
            screenWPx - 2f * SCREEN_PAD_H_DP * density
    }

    /** Gap between the top bar and the preview block. */
    const val GAP_AFTER_BAR_DP = 4f

    /** Gap between the preview block and the typing field. */
    const val GAP_AFTER_PREVIEW_DP = 12f

    /** Gap between the typing field and the controls list. */
    const val GAP_AFTER_FIELD_DP = 10f

    // ── Group headers ───────────────────────────────────────────────

    object GroupHeader {
        /** The header line's box height. */
        const val H_DP = 26f

        /** Space above a header that follows another group. */
        const val GAP_BEFORE_DP = 22f

        /** Space below the header before its first row. */
        const val GAP_AFTER_DP = 6f
    }

    // ── Slider rows (label + the standard GL slider + value) ───────

    object SliderRow {
        const val H_DP = 44f

        /** Gap between the label and the slider. */
        const val LABEL_GAP_DP = 10f

        /** Gap between the slider and the value readout. */
        const val VALUE_GAP_DP = 10f

        /** The label box's width: the hug rule ([EditorGeometry.LabelLine]). */
        fun labelWPx(labelAdvancePx: Float, rowWPx: Float): Float =
            EditorGeometry.LabelLine.boxWPx(labelAdvancePx, 0f, rowWPx)

        /** The value readout's box width: the hug rule again. */
        fun valueWPx(valueAdvancePx: Float, rowWPx: Float): Float =
            EditorGeometry.LabelLine.boxWPx(valueAdvancePx, 0f, rowWPx)

        /** The slider's left edge: label box + the label gap. */
        fun sliderLeftPx(labelAdvancePx: Float, rowWPx: Float, density: Float): Float =
            labelWPx(labelAdvancePx, rowWPx) + LABEL_GAP_DP * density

        /**
         * The slider's width: the row minus the two hugged boxes and
         * the two gaps — the weighted span the host's Row leaves it.
         * This is the width build 266 lost (the label took the whole
         * row, leaving 0); it must stay positive for real labels.
         */
        fun sliderWPx(
            rowWPx: Float, labelAdvancePx: Float, valueAdvancePx: Float, density: Float
        ): Float =
            (rowWPx - labelWPx(labelAdvancePx, rowWPx) - LABEL_GAP_DP * density -
                VALUE_GAP_DP * density - valueWPx(valueAdvancePx, rowWPx)).coerceAtLeast(0f)

        /** The value readout's left edge. */
        fun valueLeftPx(
            rowWPx: Float, labelAdvancePx: Float, valueAdvancePx: Float, density: Float
        ): Float =
            sliderLeftPx(labelAdvancePx, rowWPx, density) +
                sliderWPx(rowWPx, labelAdvancePx, valueAdvancePx, density) +
                VALUE_GAP_DP * density
    }

    // ── Segmented rows (style toggles, alignment) ──────────────────
    //
    // Equal cells across the row's width with [GAP_DP] between them;
    // the cells tile the row exactly (first cell at 0, last cell's
    // right edge at the row width).

    object Segmented {
        const val H_DP = 40f
        const val GAP_DP = 6f
        const val CORNER_DP = 10f

        /** Icon size in an icon+label cell (alignment). */
        const val ICON_DP = 18f

        /** Gap between a cell's icon and its label. */
        const val ICON_GAP_DP = 5f

        /** One cell's width: the row split into [count] equal cells. */
        fun cellWPx(rowWPx: Float, count: Int, density: Float): Float =
            if (count <= 0) 0f else (rowWPx - GAP_DP * density * (count - 1)) / count

        /** A cell's left edge within its row. */
        fun cellLeftPx(index: Int, rowWPx: Float, count: Int, density: Float): Float =
            index * (cellWPx(rowWPx, count, density) + GAP_DP * density)

        /** A cell's centre x within its row. */
        fun cellCentreXPx(index: Int, rowWPx: Float, count: Int, density: Float): Float =
            cellLeftPx(index, rowWPx, count, density) + cellWPx(rowWPx, count, density) / 2f

        /**
         * The left edge of a centred icon+label run inside a cell:
         * the run is icon + gap + label, centred as one piece; with
         * no icon the run is the label alone.
         */
        fun runLeftPx(
            cellLeftPx: Float, cellWPx: Float,
            labelAdvancePx: Float, hasIcon: Boolean, density: Float
        ): Float {
            val runW = labelAdvancePx +
                if (hasIcon) (ICON_DP + ICON_GAP_DP) * density else 0f
            return cellLeftPx + (cellWPx - runW) / 2f
        }
    }

    // ── Colour rows (label + swatch strip + custom + eyedropper) ───

    object Swatches {
        /** One swatch tile (the old colour row's 34 dp square). */
        const val CELL_DP = 34f
        const val GAP_DP = 6f
        const val CORNER_DP = 8f

        /** The colour row's height (the old TextColorRow's 40 dp). */
        const val ROW_H_DP = 40f

        /** Gap between the strip and the pinned custom-colour tile. */
        const val PIN_GAP_DP = 6f

        /** Gap between the row's label and the swatch strip. */
        const val LABEL_GAP_DP = 10f

        /** The label box's width: the hug rule ([EditorGeometry.LabelLine]). */
        fun labelWPx(labelAdvancePx: Float, rowWPx: Float): Float =
            EditorGeometry.LabelLine.boxWPx(labelAdvancePx, 0f, rowWPx)

        /** The strip's left edge: label box + the label gap. */
        fun stripLeftPx(labelAdvancePx: Float, rowWPx: Float, density: Float): Float =
            labelWPx(labelAdvancePx, rowWPx) + LABEL_GAP_DP * density

        /**
         * The strip's width: the row minus the hugged label, the
         * gaps and the two pinned tiles (custom + eyedropper) — the
         * weighted span the host's Row leaves it. Build 266 lost it
         * to the label (0); it must stay positive for real labels.
         */
        fun stripWPx(rowWPx: Float, labelAdvancePx: Float, density: Float): Float =
            (rowWPx - stripLeftPx(labelAdvancePx, rowWPx, density) -
                PIN_GAP_DP * density - CELL_DP * density -
                GAP_DP * density - CELL_DP * density).coerceAtLeast(0f)

        /** The pinned custom-colour tile's left edge. */
        fun customLeftPx(rowWPx: Float, labelAdvancePx: Float, density: Float): Float =
            stripLeftPx(labelAdvancePx, rowWPx, density) +
                stripWPx(rowWPx, labelAdvancePx, density) + PIN_GAP_DP * density

        /** The eyedropper tile's left edge (its right edge is the row's). */
        fun eyedropperLeftPx(rowWPx: Float, labelAdvancePx: Float, density: Float): Float =
            customLeftPx(rowWPx, labelAdvancePx, density) + CELL_DP * density + GAP_DP * density

        /** A swatch's left edge inside the strip. */
        fun leftPx(index: Int, density: Float): Float =
            index * (CELL_DP + GAP_DP) * density

        /** A swatch's centre x inside the strip. */
        fun centreXPx(index: Int, density: Float): Float =
            leftPx(index, density) + CELL_DP * density / 2f

        /** The strip's total content width for [count] swatches. */
        fun contentWPx(count: Int, density: Float): Float =
            if (count <= 0) 0f
            else count * CELL_DP * density + (count - 1) * GAP_DP * density
    }

    // ── Chip strips (font chips, font-weight pills) ────────────────
    //
    // Hug-content chips in one horizontally scrolling strip: each
    // chip is its label's advance + horizontal padding, all chips
    // share one height, [STRIP_GAP_DP] apart. (The shared pill-strip
    // law, [EditorGeometry.PillStrip], does the arithmetic; these
    // are the text screen's constants for it.)

    object Chips {
        const val H_DP = 36f
        const val PAD_H_DP = 12f
        const val STRIP_GAP_DP = 8f
        const val CORNER_DP = 10f

        /** The font chip's text size (the old chip's 15 sp, in its own face). */
        const val FONT_TEXT_SP = 15f

        /** Gap between the font-weight row's label and its pill strip. */
        const val LABEL_GAP_DP = 10f

        /** One chip's width from its label's advance. */
        fun chipWPx(advancePx: Float, density: Float): Float =
            advancePx + 2f * PAD_H_DP * density

        /**
         * The pill strip's slot width in the font-weight row: the
         * row minus the hugged label box and the gap — the weighted
         * span the host's Row leaves the strip. Build 266 lost it to
         * the label (0); it must stay positive for the real label.
         */
        fun stripSlotWPx(rowWPx: Float, labelAdvancePx: Float, density: Float): Float =
            (rowWPx - EditorGeometry.LabelLine.boxWPx(labelAdvancePx, 0f, rowWPx) -
                LABEL_GAP_DP * density).coerceAtLeast(0f)

        /** Chip left edges: cumulative widths + gaps, from [startPx]. */
        fun leftsPx(widths: List<Float>, density: Float, startPx: Float = 0f): List<Float> =
            EditorGeometry.PillStrip.itemLeftsPx(widths, STRIP_GAP_DP, density, startPx)

        /** The strip's total content width (chips + gaps). */
        fun contentWPx(widths: List<Float>, density: Float): Float =
            EditorGeometry.PillStrip.contentWPx(widths, STRIP_GAP_DP, density)
    }

    // ── Style preset tiles ──────────────────────────────────────────

    object Presets {
        /** One preset tile (the old Style tab's 48 dp square). */
        const val TILE_DP = 48f
        const val GAP_DP = 10f
        const val CORNER_DP = 10f

        /** A tile's left edge inside the strip. */
        fun leftPx(index: Int, density: Float): Float =
            index * (TILE_DP + GAP_DP) * density

        /** A tile's centre x inside the strip. */
        fun centreXPx(index: Int, density: Float): Float =
            leftPx(index, density) + TILE_DP * density / 2f

        /** The strip's total content width for [count] tiles. */
        fun contentWPx(count: Int, density: Float): Float =
            if (count <= 0) 0f
            else count * TILE_DP * density + (count - 1) * GAP_DP * density
    }

    // ── Full-width action rows (Path attach / detach) ──────────────

    object ActionRow {
        const val H_DP = 44f

        /** The label's inset from the row's left edge. */
        const val PAD_H_DP = 14f
    }

    // ── Simple label/value rows (Path: "Layer" + the layer's name) ──

    object LabelRow {
        const val H_DP = 40f

        /** The label column's width (the old PathTab's 88 dp). */
        const val LABEL_W_DP = 88f
    }

    // ── Build 269: the Styles tab's sub-sections ───────────────────

    object Styles {
        /**
         * The Styles tab's sub-section chips, in display order —
         * the canonical list the host's StyleSection enum mirrors.
         * Build 268's tab reorganisation left the manual Stroke
         * group (Thickness slider + stroke colour row) with no home;
         * this list is pinned by TextEditGeometryTest so a
         * sub-section can never silently lose its place again.
         */
        val SECTION_LABELS: List<String> =
            listOf("Text", "Stroke", "Glow", "Background", "Shadow", "Spacing", "Bold Italic", "Case")
    }

    // ── Build 271: the reference panel's metrics ─────────────────
    //
    // Sarowar's HTML reference (ai_studio_code__57.html) is the spec
    // for everything below the panel head: tokens, input row,
    // primary text tabs, sub-tab chips, cards, circular swatches,
    // sliders and pill toggles. Reference px read as dp/sp 1:1.

    object Ref {
        // Tokens. Build 276 (fresh design): the slate family of the
        // new design language (sheet = the app base, cards slate).
        const val SHEET_ARGB: Int = 0xFF0A0E13.toInt()
        const val ELEVATED_ARGB: Int = 0xFF151B23.toInt()
        const val CARD_ARGB: Int = 0xFF1C242E.toInt()
        const val CARD_HOVER_ARGB: Int = 0xFF242F3B.toInt()
        const val HAIRLINE_ARGB: Int = 0x14FFFFFF
        const val TEXT_ARGB: Int = 0xFFF5F5F7.toInt()
        const val SECONDARY_ARGB: Int = 0xFF8E8E98.toInt()
        const val CYAN_ARGB: Int = 0xFF18D8E0.toInt()
        const val CARD_LABEL_ARGB: Int = 0xFFA6A6B2.toInt()
        const val EMPTY_ARGB: Int = 0xFF7C7C88.toInt()
        const val PIN_OFF_ARGB: Int = 0xFF7C7C88.toInt()
        const val PIN_ON_ARGB: Int = 0xFFFFCC00.toInt()

        // Sheet frame.
        const val SHEET_RADIUS_DP = 16f

        // Input row.
        const val INPUT_PAD_H_DP = 14f
        const val INPUT_PAD_TOP_DP = 11f
        const val INPUT_PAD_BOTTOM_DP = 7f
        const val INPUT_BOX_H_DP = 40f
        const val INPUT_RADIUS_DP = 10f
        const val INPUT_BG_ARGB: Int = 0xFF1A222C.toInt()
        const val INPUT_BORDER_ARGB: Int = 0x12FFFFFF
        const val INPUT_FOCUS_BG_ARGB: Int = 0xFF212B36.toInt()
        const val INPUT_FOCUS_BORDER_ARGB: Int = 0xB818D8E0.toInt()
        const val PLACEHOLDER_ARGB: Int = 0xFF71717B.toInt()
        const val INPUT_GAP_DP = 10f
        const val CONFIRM_DP = 36f
        const val CONFIRM_RADIUS_DP = 9f
        const val CONFIRM_BG_ARGB: Int = 0x12FFFFFF

        // Primary tabs (text tabs, not pills).
        const val TAB_PAD_H_DP = 16f
        const val TAB_PAD_TOP_DP = 2f
        const val TAB_PAD_BOTTOM_DP = 10f
        const val TAB_GAP_DP = 20f
        const val TAB_LABEL_BOTTOM_DP = 6f
        const val UNDERLINE_W_DP = 18f
        const val UNDERLINE_H_DP = 2.5f
        const val UNDERLINE_RADIUS_DP = 2f

        /** A primary tab's left edge: padding + advances + gaps. */
        fun tabLeftsPx(advancesPx: List<Float>, density: Float): List<Float> {
            val out = ArrayList<Float>(advancesPx.size)
            var x = TAB_PAD_H_DP * density
            for (adv in advancesPx) {
                out.add(x)
                x += adv + TAB_GAP_DP * density
            }
            return out
        }

        /** The underline bar's left edge: centred under the label. */
        fun underlineLeftPx(tabLeftPx: Float, labelAdvancePx: Float, density: Float): Float =
            tabLeftPx + (labelAdvancePx - UNDERLINE_W_DP * density) / 2f

        /** The tabs row's total content width (for the scroll law). */
        fun tabsContentWPx(advancesPx: List<Float>, density: Float): Float =
            if (advancesPx.isEmpty()) 0f
            else tabLeftsPx(advancesPx, density).last() + advancesPx.last() +
                TAB_PAD_H_DP * density

        // Sub-tab rows.
        const val SUB_PAD_H_DP = 14f
        const val SUB_PAD_TOP_DP = 9f
        const val SUB_PAD_BOTTOM_DP = 7f
        const val SUB_GAP_DP = 14f
        /** Build 276: roomier pill chips (was 6 dp in the reference build). */
        const val SUB_ITEM_PAD_H_DP = 10f
        const val SUB_ITEM_PAD_V_DP = 3f
        /** Build 276: sub-tab chips are PILLS (radius = half the 24 dp item height). */
        const val SUB_CHIP_RADIUS_DP = 12f
        /** Build 276: the active chip is a FILLED accent pill (dark ink text). */
        const val SUB_ACTIVE_BG_ARGB: Int = 0xFF18D8E0.toInt()
        /** Build 276: an inactive chip's grey pill fill. */
        const val SUB_IDLE_BG_ARGB: Int = 0xFF242F3B.toInt()
        /** Build 276: ink on the active chip's accent fill. */
        const val SUB_ACTIVE_INK_ARGB: Int = 0xFF111114.toInt()
        const val SUB_ICON_DP = 17f
        const val SUBHEADER_H_DP = 34f

        /** A sub-tab item's width: advance + both side paddings. */
        fun subItemWPx(advancePx: Float, density: Float): Float =
            advancePx + 2f * SUB_ITEM_PAD_H_DP * density

        /** Sub-tab item lefts: cumulative with the row gap. */
        fun subLeftsPx(widthsPx: List<Float>, density: Float): List<Float> {
            val out = ArrayList<Float>(widthsPx.size)
            var x = 0f
            for (w in widthsPx) {
                out.add(x)
                x += w + SUB_GAP_DP * density
            }
            return out
        }

        // Inline search field.
        const val SEARCH_BG_ARGB: Int = 0xFF151B23.toInt()
        const val SEARCH_RADIUS_DP = 8f
        const val SEARCH_PAD_BOTTOM_DP = 8f

        // Content + cards.
        const val CONTENT_PAD_H_DP = 14f
        const val CONTENT_PAD_TOP_DP = 4f
        const val CONTENT_PAD_BOTTOM_DP = 16f
        /** Build 276: the concept's large rounded tool tiles. */
        const val CARD_RADIUS_DP = 16f
        const val CARD_BORDER_W_DP = 1.5f
        const val FONT_CARD_H_DP = 54f
        const val BUBBLE_ASPECT = 1.25f

        /** A bubble card's height from its cell width. */
        fun bubbleCardHPx(cellWPx: Float): Float = cellWPx / BUBBLE_ASPECT

        /** The nominal template cell at the reference's 360dp width
         *  (the two-row default law's fixed point). */
        const val TEMPLATE_CELL_NOMINAL_DP = 77f

        // Card pins (bookmark star / download badge).
        const val PIN_DP = 18f
        const val PIN_INSET_DP = 4f
        const val PIN_RADIUS_DP = 4f
        const val PIN_BG_ARGB: Int = 0x73000000
        const val BADGE_INSET_DP = 5f

        // Circular colour rows.
        const val SWATCH_DP = 26f
        const val SWATCH_GAP_DP = 11f
        const val SWATCH_BORDER_W_DP = 2f
        const val SWATCH_BORDER_ARGB: Int = 0x24FFFFFF
        const val SWATCH_PAD_TOP_DP = 6f
        const val SWATCH_PAD_BOTTOM_DP = 12f

        /** Swatch lefts: cumulative with the swatch gap. */
        fun swatchLeftsPx(count: Int, density: Float): List<Float> =
            (0 until count).map { it * (SWATCH_DP + SWATCH_GAP_DP) * density }

        /** The swatch row's total content width. */
        fun swatchContentWPx(count: Int, density: Float): Float =
            if (count <= 0) 0f
            else count * SWATCH_DP * density + (count - 1) * SWATCH_GAP_DP * density

        // Reference sliders.
        const val SLIDER_LABEL_W_DP = 70f
        const val SLIDER_GAP_DP = 12f
        const val SLIDER_VALUE_W_DP = 42f
        const val SLIDER_TRACK_H_DP = 3.5f
        const val SLIDER_TRACK_ARGB: Int = 0xFF2A3542.toInt()
        /** Build 276: the concept's large accent thumb. */
        const val SLIDER_THUMB_DP = 22f
        const val SLIDER_THUMB_BORDER_DP = 2f
        const val SLIDER_ROW_PAD_V_DP = 6f

        /** The slider's span: the row minus label, value and gaps —
         *  the three boxes + two gaps tile the row exactly. */
        fun sliderSpanWPx(rowWPx: Float, density: Float): Float =
            rowWPx - (SLIDER_LABEL_W_DP + SLIDER_VALUE_W_DP +
                2f * SLIDER_GAP_DP) * density

        /** The slider's left edge (after label + gap). */
        fun sliderLeftPx(density: Float): Float =
            (SLIDER_LABEL_W_DP + SLIDER_GAP_DP) * density

        /** The value readout's left edge (after slider + gap). */
        fun sliderValueLeftPx(rowWPx: Float, density: Float): Float =
            rowWPx - SLIDER_VALUE_W_DP * density

        /** The value a thumb-centre x maps to (the thumb travels
         *  from one thumb-radius to span − radius). */
        fun sliderFraction(xPx: Float, spanWPx: Float, density: Float): Float {
            val r = SLIDER_THUMB_DP * density / 2f
            val travel = (spanWPx - 2f * r).coerceAtLeast(1f)
            return ((xPx - r) / travel).coerceIn(0f, 1f)
        }

        /** The thumb centre x for a fraction. */
        fun sliderThumbXPx(fraction: Float, spanWPx: Float, density: Float): Float {
            val r = SLIDER_THUMB_DP * density / 2f
            return r + fraction.coerceIn(0f, 1f) * (spanWPx - 2f * r).coerceAtLeast(0f)
        }

        // Pill toggles.
        const val PILL_MIN_W_DP = 48f
        const val PILL_H_DP = 36f
        const val PILL_PAD_H_DP = 12f
        /** Build 276: pill toggles are true pills (radius = half the height). */
        const val PILL_RADIUS_DP = 18f
        const val PILL_GAP_DP = 8f
        const val PILL_ROW_PAD_V_DP = 6f
        const val PILL_SEL_BG_ARGB: Int = 0x2418D8E0

        /** A pill's width: advance + padding, floored at the min. */
        fun pillWPx(advancePx: Float, density: Float): Float =
            maxOf(PILL_MIN_W_DP * density, advancePx + 2f * PILL_PAD_H_DP * density)

        /** Pill lefts: cumulative with the pill gap. */
        fun pillLeftsPx(widthsPx: List<Float>, density: Float): List<Float> {
            val out = ArrayList<Float>(widthsPx.size)
            var x = 0f
            for (w in widthsPx) {
                out.add(x)
                x += w + PILL_GAP_DP * density
            }
            return out
        }

        // Aa preset tiles.
        const val AA_W_DP = 40f
        const val AA_H_DP = 36f
        const val AA_RADIUS_DP = 12f
        const val AA_GAP_DP = 8f
        const val AA_PAD_TOP_DP = 10f
        const val AA_PAD_BOTTOM_DP = 2f

        // Card labels (under Animations cards).
        const val CARD_LABEL_GAP_DP = 5f

        // Empty state.
        const val EMPTY_PAD_V_DP = 28f
    }

    // ── Build 268: the draggable bottom panel ──────────────────────
    //
    // The Text editor is a bottom panel over the editor: the canvas
    // above stays visible and live, and the panel's HEAD (grab pill +
    // "Text" title + Done) drags vertically. Sarowar's rule, verbatim
    // in effect: drag up → the panel rises; drag down → it sinks; it
    // STAYS exactly where the finger releases it. There is NO snap
    // point, detent or spring anywhere in this law — [draggedHeightPx]
    // is a pure clamp of the finger's own delta, and the released
    // height is the height, remembered by the host for the session.

    object Panel {
        /** The head strip: grab-pill zone + the title / Done row. */
        const val HEAD_H_DP = 48f

        /** The grab pill the finger takes hold of. */
        const val GRAB_W_DP = 40f
        const val GRAB_H_DP = 4f

        /** The input row's full height (build 271: the reference's
         *  padding 11 + the 40dp input box + padding 7). */
        const val FIELD_H_DP = 58f

        /** The spacer between the typing field and the tab row. */
        const val FIELD_GAP_DP = 6f

        /** The tab row under the typing field. */
        const val TAB_ROW_H_DP = 40f

        /** Minimum visible content under the tabs: about one row. */
        const val MIN_CONTENT_DP = 44f

        /**
         * Build 276 (fresh design): the bottom Done bar — the
         * concept's full-width accent pill, carrying the panel's
         * existing Done/close action (10 dp pads + a 46 dp pill).
         * It is part of the panel's chrome for every height law
         * except the keyboard collapse (the bar hides with the body).
         */
        const val DONE_BAR_H_DP = 66f

        /** First-open height: this share of the available height. */
        const val DEFAULT_FRACTION = 0.62f

        /** The smallest panel: head + field + tab row + one row + the Done bar. */
        fun minHeightPx(density: Float): Float =
            (HEAD_H_DP + FIELD_H_DP + TAB_ROW_H_DP + MIN_CONTENT_DP + DONE_BAR_H_DP) * density

        /**
         * The most room the panel may take: the height the host hands
         * it (the editor's lower zone — the canvas's invariant height
         * is never touched; the panel grows over the lower zone only).
         */
        fun maxHeightPx(availablePx: Float): Float = availablePx.coerceAtLeast(0f)

        /**
         * The max height while the keyboard is up: the drag range is
         * measured in the space above the keyboard, so the IME inset
         * comes off the available height.
         *
         * Build 269 (Sarowar's phone report on 268): this law and
         * the host's bottom IME padding are the ONLY IME
         * compensation in the app — the Activity declares
         * adjustResize, so the system NEVER pans or resizes the
         * window behind the app's back. On 268 the manifest declared
         * no softInputMode, the edge-to-edge window defaulted to
         * adjustPan, and the system's pan stacked ON TOP of this
         * clamp + the host padding: the panel flew up by a second
         * keyboard height, head and typing field off the top of the
         * screen. Do not add another inset mechanism anywhere.
         */
        fun imeMaxHeightPx(availablePx: Float, imeBottomPx: Float): Float =
            (availablePx - imeBottomPx).coerceAtLeast(0f)

        /**
         * The clamp both the drag and the remembered height go
         * through. When the range inverts (IME taller than the zone),
         * the minimum wins: head + field + one row stay visible.
         */
        fun clampHeightPx(heightPx: Float, minPx: Float, maxPx: Float): Float =
            heightPx.coerceIn(minPx, maxOf(maxPx, minPx))

        /**
         * One drag step: the finger moved [dragDeltaYPx] (down =
         * positive), so the panel height changes by exactly the
         * opposite — 1:1, no stepping, no rounding, NO SNAP. On
         * release nothing else runs: this value is the resting
         * height, and the host remembers it for the session.
         */
        fun draggedHeightPx(
            currentPx: Float, dragDeltaYPx: Float, minPx: Float, maxPx: Float
        ): Float = clampHeightPx(currentPx - dragDeltaYPx, minPx, maxPx)

        /** The panel's own chrome above the tab content viewport
         *  (build 271: head + input row + primary tabs — the input
         *  row carries its own padding, so no gap term). */
        fun chromeHeightPx(density: Float): Float =
            (HEAD_H_DP + FIELD_H_DP + TAB_ROW_H_DP) * density

        /** The tab content's viewport height at [heightPx] (above the
         *  chrome and below it the build-276 Done bar). */
        fun contentViewportPx(heightPx: Float, density: Float): Float =
            (heightPx - chromeHeightPx(density) - DONE_BAR_H_DP * density).coerceAtLeast(0f)

        /**
         * Build 271: the keyboard-open height. While the main typing
         * field holds focus (the reference's kb-active state, bound
         * to its main input only), the sheet body hides and the
         * panel is exactly its chrome.
         */
        fun collapseHeightPx(density: Float): Float = chromeHeightPx(density)

        /**
         * The panel height at which the Templates tab shows TWO
         * FULL template-card rows (build 269's law, re-derived for
         * build 271's 4-column square cards): chrome + the
         * sub-header row + the content top pad + two nominal cells
         * and the gap between them.
         */
        fun twoTemplateRowsHeightPx(density: Float): Float =
            chromeHeightPx(density) + DONE_BAR_H_DP * density +
                (Ref.SUBHEADER_H_DP + Ref.CONTENT_PAD_TOP_DP +
                    2f * Ref.TEMPLATE_CELL_NOMINAL_DP + Grid.GAP_DP) * density

        /**
         * The first-open height, before any drag is remembered:
         * [DEFAULT_FRACTION] of the owned zone, floored at the
         * two-template-row height so the first open is never the
         * build-268 sliver (on a phone-sized zone 62% lands below
         * the minimum and the content was a 44 dp strip), and
         * clamped to the zone — a zone smaller than two rows simply
         * opens at its max; the canvas is never touched.
         */
        fun defaultHeightPx(availablePx: Float, density: Float): Float =
            clampHeightPx(
                maxOf(
                    availablePx * DEFAULT_FRACTION,
                    twoTemplateRowsHeightPx(density)
                ),
                minHeightPx(density), maxHeightPx(availablePx)
            )
    }

    // ── Build 268: tile grids (Templates / Fonts / Effects / Bubbles) ──
    //
    // Equal cells tiling the padded row width exactly, [GAP_DP]
    // apart, in row-major order — the same tiling discipline as
    // [Segmented], generalised to a grid.

    object Grid {
        const val GAP_DP = 8f

        /** One Templates card's height (the host's card box). */
        const val TEMPLATE_CARD_H_DP = 96f

        /** One cell's width: the row split into [columns] cells. */
        fun cellWPx(rowWPx: Float, columns: Int, density: Float): Float =
            if (columns <= 0) 0f
            else (rowWPx - GAP_DP * density * (columns - 1)) / columns

        /** A cell's left edge (its column's slot). */
        fun cellLeftPx(index: Int, rowWPx: Float, columns: Int, density: Float): Float {
            if (columns <= 0) return 0f
            val col = index % columns
            return col * (cellWPx(rowWPx, columns, density) + GAP_DP * density)
        }

        /** Rows needed for [count] cells at [columns] per row. */
        fun rowCount(count: Int, columns: Int): Int =
            if (count <= 0 || columns <= 0) 0 else (count + columns - 1) / columns

        /** The last cell's right edge must be the row width (tiling). */
        fun lastCellRightPx(rowWPx: Float, columns: Int, density: Float): Float =
            if (columns <= 0) 0f
            else cellLeftPx(columns - 1, rowWPx, columns, density) +
                cellWPx(rowWPx, columns, density)
    }
}
