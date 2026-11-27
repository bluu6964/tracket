package com.example.model

/**
 * Build 250 (UI migration stage 3) — the editor chrome's layout law,
 * extracted from the build-249 Compose layouts so the controls bar, the
 * three headers and the multi-select bar can be rasterized by the C++
 * GL renderer as one surface each (the stage-2 dock pattern), with the
 * Compose host keeping only layout-sized touch boxes, semantics and
 * the popovers.
 *
 * All functions work in raw pixels; [density] converts the dp constants
 * exactly as Compose's `Dp.toPx()` did. Text placement follows the
 * stage-2 label law: a line's layout box is [textHeightPx] tall (the
 * atlas's ceil(descent − ascent)), and the baseline sits at
 * boxTop − ascent.
 */
object ChromeGeometry {

    // ── Shared text law ─────────────────────────────────────────────

    /** Baseline (px) of a single line whose layout-box top is [boxTopPx]; [ascentPx] is negative. */
    fun baselinePx(boxTopPx: Float, ascentPx: Float): Float = boxTopPx - ascentPx

    /** Layout-box top (px) of a line of [textHeightPx] centred on [centerYPx]. */
    fun centeredBoxTopPx(centerYPx: Float, textHeightPx: Float): Float =
        centerYPx - textHeightPx / 2f

    /**
     * The ellipsized form of [text] for a box of [capPx]: [text] itself
     * when it fits, otherwise the longest prefix (cut only at code-point
     * boundaries that do not split a combining sequence) whose advance
     * with the "…" appended still fits — the Android layout's rule,
     * reproduced over the caller's [measure] (the atlas paint's
     * measureText, so shaping and letter spacing agree).
     */
    fun ellipsize(text: String, capPx: Float, measure: (String) -> Float): String {
        if (text.isEmpty() || measure(text) <= capPx) return text
        val ell = "…"
        if (measure(ell) > capPx) return ell
        // Candidate cut points: UTF-16 indices that start a code point
        // which is not a combining mark (a cut never strands a mark).
        val cuts = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val type = Character.getType(cp)
            if (i > 0 &&
                type != Character.NON_SPACING_MARK.toInt() &&
                type != Character.ENCLOSING_MARK.toInt() &&
                type != Character.COMBINING_SPACING_MARK.toInt()
            ) {
                cuts.add(i)
            }
            i += Character.charCount(cp)
        }
        cuts.add(text.length)
        var lo = 0
        var hi = cuts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (measure(text.substring(0, cuts[mid]) + ell) <= capPx) lo = mid else hi = mid - 1
        }
        return text.substring(0, cuts[lo]) + ell
    }

    // ── Controls bar (PlayControlsSection) ──────────────────────────

    object Controls {
        /**
         * The controls bar's laid-out height in the editor screen —
         * the single source for the screen's playControlsH and for
         * build 260 item 6's canvas delta (the canvas zone grows by
         * EXACTLY this much, funded by the layer panel).
         */
        const val BAR_H_DP = 34f
        const val BTN_DP = 28f
        const val GLYPH_PLAY_DP = 19f
        const val GLYPH_SMALL_DP = 17f
        const val SPACER_DP = 10f
        const val PAD_DP = 12f

        /** The 28 dp buttons are centred vertically in the bar. */
        fun btnTopPx(heightPx: Float, density: Float): Float =
            (heightPx - BTN_DP * density) / 2f

        fun undoLeftPx(density: Float): Float = PAD_DP * density

        fun redoLeftPx(density: Float): Float = (PAD_DP + BTN_DP + SPACER_DP) * density

        fun badgeLeftPx(density: Float): Float =
            (PAD_DP + 2f * (BTN_DP + SPACER_DP)) * density

        /** The centre button is exactly centred (both side groups are 104 dp). */
        fun playLeftPx(widthPx: Float, density: Float): Float =
            (widthPx - BTN_DP * density) / 2f

        /** Right-group button [index] (0 = first): the group is 104 dp wide at the right edge. */
        fun rightLeftPx(widthPx: Float, density: Float, index: Int): Float =
            widthPx - (PAD_DP + 3f * BTN_DP + 2f * SPACER_DP) * density +
                index * (BTN_DP + SPACER_DP) * density

        /** A glyph box centred inside its 28 dp button. */
        fun glyphLeftPx(btnLeftPx: Float, glyphDp: Float, density: Float): Float =
            btnLeftPx + (BTN_DP - glyphDp) * density / 2f

        fun glyphTopPx(btnTopPx: Float, glyphDp: Float, density: Float): Float =
            btnTopPx + (BTN_DP - glyphDp) * density / 2f

        /**
         * The badge's visual width: text advance + horizontal padding
         * 4+4. The 1.5 dp border is draw-only in Compose (it adds no
         * layout size and is stroked inside the box's bounds).
         */
        fun badgeWidthPx(advancePx: Float, density: Float): Float =
            advancePx + 8f * density

        /** The badge's visual height: text layout height + vertical padding 1+1. */
        fun badgeHeightPx(textHeightPx: Float, density: Float): Float =
            textHeightPx + 2f * density

        /** The badge visual is centred inside its 28 dp touch box. */
        fun badgeVisualLeftPx(advancePx: Float, density: Float): Float =
            badgeLeftPx(density) + (BTN_DP * density - badgeWidthPx(advancePx, density)) / 2f

        fun badgeVisualTopPx(heightPx: Float, textHeightPx: Float, density: Float): Float =
            btnTopPx(heightPx, density) +
                (BTN_DP * density - badgeHeightPx(textHeightPx, density)) / 2f

        /** The badge text's layout-box top: visual top + the 1 dp top padding. */
        fun badgeTextTopPx(heightPx: Float, textHeightPx: Float, density: Float): Float =
            badgeVisualTopPx(heightPx, textHeightPx, density) + 1f * density

        /** The badge border: 1.5 dp, rounded 5, stroked inside the visual box. */
        const val BADGE_BORDER_DP = 1.5f
        const val BADGE_CORNER_DP = 5f

        /** The keyframe badge dot: 5.5 dp at the glyph box's bottom-end corner. */
        fun dotCenterXPx(glyphRightPx: Float, density: Float): Float =
            glyphRightPx - 2.75f * density

        fun dotCenterYPx(glyphBottomPx: Float, density: Float): Float =
            glyphBottomPx - 2.75f * density

        /** The dot's fill radius: the full 5.5 dp disc. */
        fun dotRadiusPx(density: Float): Float = 2.75f * density

        /** The dot's 1 dp ring: stroked at radius 2.25 so it lands inside the disc. */
        fun dotRingRadiusPx(density: Float): Float = 2.25f * density

        fun dotStrokePx(density: Float): Float = 1f * density

        /** The bottom hairline's top edge. */
        fun hairlineTopPx(heightPx: Float, density: Float): Float = heightPx - 1f * density
    }

    // ── App header (EditorHeader) ───────────────────────────────────

    object Header {
        // Build 260 (Sarowar's order): the header buttons grow ×1.2 —
        // box 32 → 38.4 dp, corner 9 → 10.8, glyphs 12 → 14.4 (close)
        // and 16 → 19.2 (ratio / export), pill triangle 8 → 9.6.
        // Pads and gaps keep their build-249 sizes.
        const val BTN_DP = 38.4f
        const val CORNER_DP = 10.8f
        const val BORDER_DP = 1f
        const val PAD_DP = 10f
        const val GAP_DP = 7f
        const val GLYPH_CLOSE_DP = 14.4f
        const val GLYPH_RATIO_DP = 19.2f
        const val GLYPH_EXPORT_DP = 19.2f
        const val TRI_DP = 9.6f

        // Build 276 (fresh design): Export is a filled accent PILL —
        // glyph + the word "Export" — not a square icon button. Its
        // height stays BTN_DP; its width is the label-driven law below
        // and its corner radius is half the height (a true pill).
        const val EXPORT_PAD_START_DP = 12f
        const val EXPORT_GAP_DP = 5f
        const val EXPORT_PAD_END_DP = 12f

        fun btnTopPx(heightPx: Float, density: Float): Float =
            (heightPx - BTN_DP * density) / 2f

        fun closeLeftPx(density: Float): Float = PAD_DP * density

        /** The Export pill's width: start pad + glyph + gap + label advance + end pad. */
        fun exportPillWidthPx(advancePx: Float, density: Float): Float =
            advancePx + (EXPORT_PAD_START_DP + GLYPH_EXPORT_DP + EXPORT_GAP_DP + EXPORT_PAD_END_DP) * density

        /** The Export pill's left edge (its right edge is the header padding). */
        fun exportLeftPx(widthPx: Float, exportPillWidthPx: Float, density: Float): Float =
            widthPx - PAD_DP * density - exportPillWidthPx

        /** The Export glyph's box left inside the pill. */
        fun exportGlyphLeftPx(exportLeftPx: Float, density: Float): Float =
            exportLeftPx + EXPORT_PAD_START_DP * density

        /** The Export label's layout-box left inside the pill. */
        fun exportTextLeftPx(exportLeftPx: Float, density: Float): Float =
            exportLeftPx + (EXPORT_PAD_START_DP + GLYPH_EXPORT_DP + EXPORT_GAP_DP) * density

        /** Pill width: start padding 10 + text advance + gap 4 + triangle [TRI_DP] + end padding 9. */
        fun pillWidthPx(advancePx: Float, density: Float): Float =
            advancePx + (10f + 4f + TRI_DP + 9f) * density

        fun pillLeftPx(widthPx: Float, pillWidthPx: Float, exportPillWidthPx: Float, density: Float): Float =
            exportLeftPx(widthPx, exportPillWidthPx, density) - GAP_DP * density - pillWidthPx

        fun ratioLeftPx(pillLeftPx: Float, density: Float): Float =
            pillLeftPx - (GAP_DP + BTN_DP) * density

        /** The pill text's layout-box left (its top comes from vertical centring in the 32 dp pill). */
        fun pillTextLeftPx(pillLeftPx: Float, density: Float): Float =
            pillLeftPx + 10f * density

        fun pillTextTopPx(heightPx: Float, textHeightPx: Float, density: Float): Float =
            btnTopPx(heightPx, density) + (BTN_DP * density - textHeightPx) / 2f

        /** The dropdown triangle's [TRI_DP] box: after text + 4 dp gap, centred vertically. */
        fun triLeftPx(pillLeftPx: Float, advancePx: Float, density: Float): Float =
            pillLeftPx + 14f * density + advancePx

        fun triTopPx(heightPx: Float, density: Float): Float =
            btnTopPx(heightPx, density) + (BTN_DP - TRI_DP) * density / 2f

        /** A button glyph's box (centred in the 32 dp button). */
        fun glyphLeftPx(btnLeftPx: Float, glyphDp: Float, density: Float): Float =
            btnLeftPx + (BTN_DP - glyphDp) * density / 2f

        fun glyphTopPx(heightPx: Float, glyphDp: Float, density: Float): Float =
            btnTopPx(heightPx, density) + (BTN_DP - glyphDp) * density / 2f

        /** Face size for a 32 dp button: the 1 dp border sits inside (dock stroke rule). */
        fun faceSizePx(density: Float): Float = (BTN_DP - BORDER_DP) * density

        fun faceRadiusPx(density: Float): Float = (CORNER_DP - BORDER_DP / 2f) * density
    }

    // ── Layer header (LayerHeader, incl. the multi-select variant) ──

    object LayerHeader {
        const val ROW_PAD_DP = 4f
        const val NAME_PAD_START_DP = 2f
        const val NAME_PAD_END_DP = 8f
        const val UNDERLINE_GAP_DP = 2f
        const val UNDERLINE_H_DP = 1f
        // Build 260 (Sarowar's order): the layer header's buttons grow
        // ×1.2 — zone 36 → 43.2 dp, tools circle 22 → 26.4, glyphs
        // back 13 → 15.6, copy 16 → 19.2, delete 17 → 20.4, more
        // 14 → 16.8.
        const val TOOLS_CIRCLE_DP = 26.4f
        const val TOOLS_CIRCLE_STROKE_DP = 1.2f
        const val ZONE_MAX_DP = 43.2f
        const val GLYPH_BACK_DP = 15.6f
        const val GLYPH_COPY_DP = 19.2f
        const val GLYPH_DELETE_DP = 20.4f
        const val GLYPH_MORE_DP = 16.8f

        /** Icon zone edge: [ZONE_MAX_DP], never taller than the slot itself. */
        fun zoneDp(heightDp: Float): Float = minOf(ZONE_MAX_DP, heightDp)

        fun zoneTopPx(heightPx: Float, density: Float): Float =
            (heightPx - zoneDp(heightPx / density) * density) / 2f

        fun backLeftPx(density: Float): Float = ROW_PAD_DP * density

        /** The name column's slot: after the back zone, before the trailing icon zones. */
        fun slotLeftPx(density: Float, heightPx: Float): Float =
            (ROW_PAD_DP + zoneDp(heightPx / density)) * density

        fun slotWidthPx(widthPx: Float, heightPx: Float, density: Float, iconZones: Int): Float =
            widthPx - 2f * ROW_PAD_DP * density -
                zoneDp(heightPx / density) * density * (1 + iconZones)

        /** The name's content box (the column's start/end padding removed). */
        fun contentLeftPx(density: Float, heightPx: Float): Float =
            slotLeftPx(density, heightPx) + NAME_PAD_START_DP * density

        fun contentWidthPx(widthPx: Float, heightPx: Float, density: Float, iconZones: Int): Float =
            slotWidthPx(widthPx, heightPx, density, iconZones) -
                (NAME_PAD_START_DP + NAME_PAD_END_DP) * density

        /** Column height: the name line + (underline gap + underline) when renameable. */
        fun columnHeightPx(textHeightPx: Float, density: Float, renameable: Boolean): Float =
            textHeightPx + if (renameable) (UNDERLINE_GAP_DP + UNDERLINE_H_DP) * density else 0f

        fun columnTopPx(heightPx: Float, textHeightPx: Float, density: Float, renameable: Boolean): Float =
            (heightPx - columnHeightPx(textHeightPx, density, renameable)) / 2f

        /** The underline's top edge (full content width, under the name). */
        fun underlineTopPx(heightPx: Float, textHeightPx: Float, density: Float): Float =
            columnTopPx(heightPx, textHeightPx, density, true) +
                textHeightPx + UNDERLINE_GAP_DP * density

        /** Trailing icon zone [index] (0 = first present, in duplicate/delete/tools order). */
        fun zoneLeftPx(
            widthPx: Float, heightPx: Float, density: Float, iconZones: Int, index: Int
        ): Float =
            slotLeftPx(density, heightPx) +
                slotWidthPx(widthPx, heightPx, density, iconZones) +
                index * zoneDp(heightPx / density) * density

        /** A glyph box centred in its zone. */
        fun glyphLeftPx(zoneLeftPx: Float, heightPx: Float, glyphDp: Float, density: Float): Float =
            zoneLeftPx + (zoneDp(heightPx / density) - glyphDp) * density / 2f

        fun glyphTopPx(heightPx: Float, glyphDp: Float, density: Float): Float =
            (heightPx - glyphDp * density) / 2f

        /** Tools circle: the 1.2 dp ring sits inside the [TOOLS_CIRCLE_DP] disc (dock stroke rule). */
        fun toolsCircleRadiusPx(density: Float): Float =
            (TOOLS_CIRCLE_DP - TOOLS_CIRCLE_STROKE_DP) * density / 2f
    }

    // ── Selected header (SelectedHeader219) ─────────────────────────

    object SelHeader {
        const val PAD_DP = 10f
        const val GAP_DP = 10f
        // Build 260 (Sarowar's order): the selected header's buttons
        // grow ×1.2 — glyphs 19 → 22.8 (back / layers / more) and
        // 18 → 21.6 (delete), button padding 4 → 4.8.
        const val BTN_GLYPH_DP = 22.8f
        const val DELETE_GLYPH_DP = 21.6f
        const val BTN_PAD_DP = 4.8f
        const val NAME_MAX_DP = 180f
        const val UNDERLINE_GAP_DP = 2f
        const val UNDERLINE_H_DP = 1f

        /** A SelIconBtn's edge: glyph + [BTN_PAD_DP] padding on both sides. */
        fun btnSizeDp(glyphDp: Float): Float = glyphDp + 2f * BTN_PAD_DP

        fun backLeftPx(density: Float): Float = PAD_DP * density

        fun nameBoxLeftPx(density: Float): Float =
            (PAD_DP + btnSizeDp(BTN_GLYPH_DP) + GAP_DP) * density

        /**
         * The name wrap's width: the row's fixed parts — 2 pads +
         * 4 gaps + the back / layers / more buttons + the delete
         * button (188.4 dp since build 260; 167 dp before).
         */
        fun nameBoxWidthPx(widthPx: Float, density: Float): Float =
            widthPx - (2f * PAD_DP + 4f * GAP_DP +
                3f * btnSizeDp(BTN_GLYPH_DP) + btnSizeDp(DELETE_GLYPH_DP)) * density

        /** The name column's width: the wrap's width capped at the reference's 180 dp. */
        fun columnWidthPx(widthPx: Float, density: Float): Float =
            minOf(nameBoxWidthPx(widthPx, density), NAME_MAX_DP * density)

        fun columnHeightPx(textHeightPx: Float, density: Float): Float =
            textHeightPx + (UNDERLINE_GAP_DP + UNDERLINE_H_DP) * density

        fun columnTopPx(heightPx: Float, textHeightPx: Float, density: Float): Float =
            (heightPx - columnHeightPx(textHeightPx, density)) / 2f

        fun underlineTopPx(heightPx: Float, textHeightPx: Float, density: Float): Float =
            columnTopPx(heightPx, textHeightPx, density) +
                textHeightPx + UNDERLINE_GAP_DP * density

        fun layersLeftPx(widthPx: Float, density: Float): Float =
            nameBoxLeftPx(density) + nameBoxWidthPx(widthPx, density) + GAP_DP * density

        fun deleteLeftPx(widthPx: Float, density: Float): Float =
            layersLeftPx(widthPx, density) +
                (btnSizeDp(BTN_GLYPH_DP) + GAP_DP) * density

        fun moreLeftPx(widthPx: Float, density: Float): Float =
            deleteLeftPx(widthPx, density) +
                (btnSizeDp(DELETE_GLYPH_DP) + GAP_DP) * density

        /** A button's touch-box top: the button is centred vertically in the slot. */
        fun btnTopPx(heightPx: Float, glyphDp: Float, density: Float): Float =
            (heightPx - btnSizeDp(glyphDp) * density) / 2f

        /** A glyph's box: 4 dp inside its button, centred vertically in the slot. */
        fun glyphLeftPx(btnLeftPx: Float, density: Float): Float =
            btnLeftPx + BTN_PAD_DP * density

        fun glyphTopPx(heightPx: Float, glyphDp: Float, density: Float): Float =
            (heightPx - glyphDp * density) / 2f
    }

    // ── Multi-select bar (MultiSelectBar) ───────────────────────────

    object Multi {
        const val OUTER_PAD_H_DP = 6f
        const val OUTER_PAD_V_DP = 4f
        const val INNER_PAD_H_DP = 10f
        const val INNER_PAD_V_DP = 2f
        const val PANEL_CORNER_DP = 16f
        const val PANEL_BORDER_DP = 1f
        const val ROW1_H_DP = 32f
        const val BOOL_ROW_H_DP = 36f
        const val TILE_W_DP = 32f
        const val TILE_GAP_DP = 6f
        const val GLYPH_W_DP = 20f
        const val GLYPH_H_DP = 16f
        const val TILE_PAD_V_DP = 2f
        const val BOOL_TILE_W_DP = 48f
        const val BOOL_GLYPH_W_DP = 22f

        /** Total bar height: 44 dp, plus the 36 dp boolean row when shown. */
        fun totalHeightDp(showBooleanRow: Boolean): Float =
            44f + if (showBooleanRow) BOOL_ROW_H_DP else 0f

        fun panelLeftPx(density: Float): Float = OUTER_PAD_H_DP * density

        fun panelTopPx(density: Float): Float = OUTER_PAD_V_DP * density

        fun panelWidthPx(widthPx: Float, density: Float): Float =
            widthPx - 2f * OUTER_PAD_H_DP * density

        fun panelHeightPx(showBooleanRow: Boolean, density: Float): Float =
            totalHeightDp(showBooleanRow) * density - 2f * OUTER_PAD_V_DP * density

        /** Face size for the panel: the 1 dp border sits inside (dock stroke rule). */
        fun panelFaceWidthPx(widthPx: Float, density: Float): Float =
            panelWidthPx(widthPx, density) - PANEL_BORDER_DP * density

        fun panelFaceHeightPx(showBooleanRow: Boolean, density: Float): Float =
            panelHeightPx(showBooleanRow, density) - PANEL_BORDER_DP * density

        fun panelFaceRadiusPx(density: Float): Float =
            (PANEL_CORNER_DP - PANEL_BORDER_DP / 2f) * density

        fun contentLeftPx(density: Float): Float =
            (OUTER_PAD_H_DP + INNER_PAD_H_DP) * density

        fun contentWidthPx(widthPx: Float, density: Float): Float =
            widthPx - 2f * (OUTER_PAD_H_DP + INNER_PAD_H_DP) * density

        /** The first row's centre: outer pad + inner pad + half the 32 dp row. */
        fun row1CenterYPx(density: Float): Float =
            (OUTER_PAD_V_DP + INNER_PAD_V_DP + ROW1_H_DP / 2f) * density

        /** The boolean row's centre (its row starts right under the first row). */
        fun boolRowCenterYPx(density: Float): Float =
            (OUTER_PAD_V_DP + INNER_PAD_V_DP + ROW1_H_DP + BOOL_ROW_H_DP / 2f) * density

        /** A mini/boolean tile's height: vertical padding 2+2 + glyph 16 + the label line. */
        fun tileHeightPx(textHeightPx: Float, density: Float): Float =
            (2f * TILE_PAD_V_DP + GLYPH_H_DP) * density + textHeightPx

        fun tileTopPx(centerYPx: Float, textHeightPx: Float, density: Float): Float =
            centerYPx - tileHeightPx(textHeightPx, density) / 2f

        /**
         * Mini tile lefts (build 260: the bar gains a Done tile — it
         * hugs the content's right edge, Delete sits 6 dp before it,
         * All 6 dp before Delete).
         */
        fun doneTileLeftPx(widthPx: Float, density: Float): Float =
            contentLeftPx(density) + contentWidthPx(widthPx, density) - TILE_W_DP * density

        fun deleteTileLeftPx(widthPx: Float, density: Float): Float =
            doneTileLeftPx(widthPx, density) - (TILE_GAP_DP + TILE_W_DP) * density

        fun allTileLeftPx(widthPx: Float, density: Float): Float =
            deleteTileLeftPx(widthPx, density) - (TILE_GAP_DP + TILE_W_DP) * density

        /** A tile's glyph box: centred horizontally in the tile, under the top padding. */
        fun glyphLeftPx(tileLeftPx: Float, tileWidthDp: Float, glyphWidthDp: Float, density: Float): Float =
            tileLeftPx + (tileWidthDp - glyphWidthDp) * density / 2f

        fun glyphTopPx(tileTopPx: Float, density: Float): Float =
            tileTopPx + TILE_PAD_V_DP * density

        /** A tile label's layout-box top: right under the glyph box. */
        fun labelTopPx(tileTopPx: Float, density: Float): Float =
            tileTopPx + (TILE_PAD_V_DP + GLYPH_H_DP) * density

        /** A tile label's layout-box left: the line is centred in the tile. */
        fun labelBoxLeftPx(tileLeftPx: Float, tileWidthDp: Float, advancePx: Float, density: Float): Float =
            tileLeftPx + (tileWidthDp * density - advancePx) / 2f

        /** Boolean tiles: SpaceEvenly — equal gaps before, between and after the 48 dp tiles. */
        fun boolGapPx(widthPx: Float, density: Float): Float =
            (contentWidthPx(widthPx, density) - 4f * BOOL_TILE_W_DP * density) / 5f

        fun boolTileLeftPx(widthPx: Float, density: Float, index: Int): Float =
            contentLeftPx(density) + boolGapPx(widthPx, density) +
                index * (BOOL_TILE_W_DP * density + boolGapPx(widthPx, density))
    }
}
