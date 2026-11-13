package com.example

import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** The effect list the Effects panel offers must match the C++ engine (am::FxType in cpp/engine/fx_plan.h). */
class ClipFxLibraryTest {

    @Test fun idsAndNativeTypesAreUnique() {
        val all = ClipFxLibrary.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.nativeType }.toSet().size)
    }

    @Test fun nativeTypesCoverTheEngineRange() {
        // FX_BLUR (1) .. FX_COPY_BG (54), plus FX_MOTION_BLUR (55), FX_REPEAT (56), FX_REPEAT_RADIAL
        // (57) and FX_REPEAT_SCATTER (58): every type the engine knows has a tile, none outside that
        // range. 55..58 are the odd ones: Kotlin resolves them before they reach the engine, and
        // fxParseOps skips them (they sit above FX_MAX). FX_LONG_SHADOW (59), FX_COLOR_TUNE (60),
        // FX_SOLID_COLOR (61), FX_AM_MIRROR (62) and FX_GLOW_SCAN (63) sit above FX_MAX too, but
        // they DO run in the engine: fxParseOps accepts them explicitly. The v163 tier adds
        // FX_LUMA_KEY (64), FX_FOUR_COLOR_GRADIENT (65), FX_TURBULENT_DISPLACE_AM (67),
        // FX_SWIRL_AM (69), FX_STRETCH_SEGMENT (70), FX_HALFTONE_DOTS (71), FX_PIXELATE_AM (72)
        // and FX_REPLACE_COLOR (74); 66 / 68 / 73 are their EXT groups, which have no tile —
        // fxParseOps folds them into their owner's q[] instead. The v164 tier adds
        // FX_CHROMA_KEY_AM (75), FX_COLOR_BALANCE_AM (76), FX_COLOR_TEMP_AM (78),
        // FX_COLOR_TINT_AM (79), FX_SPIN_BLUR_AM (80), FX_OFFSET_AM (81),
        // FX_RADIAL_WIPE_AM (82), FX_FIND_EDGES_AM (83), FX_HEX_SHIFT_AM (84),
        // FX_SOFT_GLOW_AM (86) and FX_FILL_BEHIND_AM (88); 77 / 85 / 87 / 89 are their EXT
        // groups, folded the same way. The v165 tier adds FX_WAVE_WARP_AM (90),
        // FX_CIRCULAR_RIPPLE_AM (92), FX_STRETCH_AM (93), FX_SQUEEZE_AM (94),
        // FX_FRACTAL_WARP_AM (95), FX_STRIPES_AM (97), FX_TURBULENT_DISPLACE3_AM (98),
        // FX_ROUGHEN_EDGES_AM (100), FX_STREAKS_AM (101), FX_COLOR_HOT_AM (103),
        // FX_COLORIZE_AM (104), FX_HIGHLIGHT_SHADOW_AM (105), FX_SPOT_COLOR_AM (106),
        // FX_DISSOLVE_AM (107), FX_SIMPLE_STARS_AM (108), FX_DOTS_AM (110),
        // FX_RIDGES_AM (112), FX_BEND_WARP_AM (114), FX_DISPLACE_MAP_AM (115),
        // FX_DISPLACE_MAP_RAD_AM (116), FX_SMOOTH_BEVEL_AM (118),
        // FX_LIGHT_GLOW_AM (120) and FX_INNER_GLOW_AM (121); 91 / 96 / 99 / 102 / 109 /
        // 111 / 113 / 117 / 119 are their EXT groups. Build 120 adds FX_DARK_GLOW_AM (122),
        // a dedicated pass chain with no EXT group. Build 127 adds FX_EXTRUDE_AM (123) and
        // FX_EXTRUDE_FLAT_AM (126), dedicated raymarch passes; 124 / 127 are their EXT
        // groups and 125 / 128 their EXT2 groups. Build 128 adds FX_EDGE_GLOW_AM (129),
        // a dedicated three-pass chain; 130 is its EXT group. Build 129 (v175) adds the
        // effects tier 4: FX_THRESHOLD_AM (131), FX_SPECTRALMAP_AM (132),
        // FX_CHANNELMAP_AM (134), FX_HALFTONE_LINES_AM (135), FX_TURBULENCE2_AM (136),
        // FX_CHECKER2_AM (139), FX_MOSAIC_AM (141), FX_GLOW_AM (142),
        // FX_INNER_BLUR_AM (143), FX_BOX_BLUR3_AM (144), FX_FEATHER_AM (145),
        // FX_ELECTRIC_EDGES_AM (146) and FX_SMOOTH_EDGES_AM (149); 133 / 137 / 140 /
        // 147 are their EXT groups and 138 / 148 their EXT2 groups.
        // Build 132 (v176) adds FX_GAUSSIAN_BLUR_AM (150), FX_DEEP_GLOW2_AM (151)
        // and FX_DROP_SHADOW_AM (154); 152 / 153 are Deep Glow 2's EXT / EXT2 groups.
        // Build 134 (v177, tier 5) adds FX_BBMAKER_AM (155), FX_UNSHARPMASK_AM (157),
        // FX_NEWSHARPEN_AM (158), FX_GRADSHADOW_AM (159), FX_GRADIENTFLOW_AM (161),
        // FX_PARTX_AM (164), FX_EDGELINE_AM (167), FX_LIGHTSWEEP_AM (169),
        // FX_COLORGRAD3_AM (172), FX_DEEP_GLOW_AM (175) and FX_SPILL_CLEANUP_AM (178);
        // 156 / 160 / 162 / 165 / 168 / 170 / 173 / 176 are their EXT groups and
        // 163 / 166 / 171 / 174 / 177 their EXT2 groups, folded the same way.
        // Build 136 adds FX_MOTION_BLUR4 (179, the resolved marker like 55) and its
        // three per-frame stages FX_MB4_POS / _SCALE / _ANGLE (180 / 181 / 182).
        // Build 141 (v179) adds FX_OPTIC_COMP_AM (183); 184 is its EXT group.
        // Build 147 (v180, Queue 15j) adds the second-wave ports: FX_NEGATIVE_AM (185),
        // FX_TRITONE_AM (186), FX_UNMULT_AM (187), FX_DENOISE_AM (189),
        // FX_MINIMAX_AM (190), FX_RGBGLITCH_AM (191), FX_FISHEYE_AM (192),
        // FX_VORTEX_BLUR_AM (193), FX_REFRACTION_AM (194), FX_TWITCH_AM (197),
        // FX_TURBULENT2_AM (198), FX_SHAPEFIELD_AM (200), FX_HALFTONE_DOTS_AM (203),
        // FX_CC_GLASS_AM (205), FX_BOKEH_AM (208), FX_LENSBLUR_OBS_AM (209),
        // FX_SOLID_WIPE_AM (210), FX_SOFTBLOOM_AM (212) and FX_LENSD_AM (213);
        // 188 / 195 / 199 / 201 / 204 / 206 / 211 / 214 are their EXT groups and
        // 196 / 202 / 207 their EXT2 groups, folded the same way.
        // Build 150 (v181) adds FX_BCC_FILM_GLOW_AM (215), a dedicated five-pass
        // chain; 216 / 217 / 218 / 219 are its EXT / EXT2 / EXT3 / EXT4 groups,
        // folded the same way (the EXT3 / EXT4 banks exist for this one effect).
        // Build 167 (round 4 banked ports) adds FX_PAGECURL_AM (220),
        // FX_TILESHIFT2_AM (223), FX_FRACTAL_WARP3_AM (225) and FX_REPEAT_LINE
        // (227, Kotlin-resolved like 56..58, never reaching the shaders); 221 /
        // 222 are Page Curl's EXT / EXT2 groups and 224 / 226 the Tile Shift 2 /
        // Fractal Warp 3 EXT groups, folded the same way.
        // Build 171 (round-5 union port) adds FX_RIPPLELIGHT_AM (228); 229 is
        // its EXT group, folded the same way.
        // Build 172 (the smaller-fry batch) adds FX_GRADIENT_OVERLAY_AM (230);
        // 231 is its EXT group, folded the same way.
        assertEquals(
            ((1..65) + 67 + (69..72) + 74 + 75 + 76 + (78..84) + 86 + 88 +
                90 + 92 + 93 + 94 + 95 + 97 + 98 + 100 + 101 + (103..108) + 110 + 112 +
                114 + 115 + 116 + 118 + 120 + 121 + 122 + 123 + 126 + 129 +
                131 + 132 + 134 + 135 + 136 + 139 + 141 + 142 + 143 + 144 + 145 + 146 + 149 +
                150 + 151 + 154 + 155 + 157 + 158 + 159 + 161 + 164 + 167 + 169 + 172 + 175 + 178 + 179 + 180 + 181 + 182 + 183 +
                185 + 186 + 187 + 189 + 190 + 191 + 192 + 193 + 194 + 197 + 198 + 200 + 203 + 205 + 208 + 209 + 210 + 212 + 213 + 215 +
                220 + 223 + 225 + 227 + 228 + 230).toList(),
            ClipFxLibrary.all.map { it.nativeType }.sorted()
        )
    }

    @Test fun extendedEffectsEmitTwoGroups() {
        val g = ClipFxLibrary.create(ClipFxLibrary.FOUR_COLOR_GRADIENT)!!
        assertEquals(14, g.values.size)   // 7 params + the 7 EXT values (AM's anchor points)
        val flat = ClipFxLibrary.toNative(listOf(g))!!
        assertEquals(16, flat.size)
        assertEquals(65f, flat[0], 0f)
        assertEquals(66f, flat[8], 0f)   // the EXT group rides right behind its owner
        assertEquals(ClipFxLibrary.packPoint(0f, -500f), flat[9], 0f)
        assertEquals(ClipFxLibrary.packPoint(0f, 0f), flat[12], 0f)
        // An EXT group does not consume one of the MAX_EFFECTS slots.
        val one = ClipFxLibrary.create(ClipFxLibrary.MOSAIC)!!
        val many = List(ClipFxLibrary.MAX_EFFECTS - 1) { one.copy(id = "e$it") } + g
        assertEquals(ClipFxLibrary.MAX_EFFECTS * 8 + 8, ClipFxLibrary.toNative(many)!!.size)
        // Turbulent Displace and Pixelate carry zeroed EXT defaults from create().
        val t = ClipFxLibrary.create(ClipFxLibrary.TURBULENT_DISPLACE_AM)!!
        assertEquals(14, t.values.size)
        assertEquals(68f, ClipFxLibrary.toNative(listOf(t))!![8], 0f)
        val p = ClipFxLibrary.create(ClipFxLibrary.PIXELATE_AM)!!
        assertEquals(73f, ClipFxLibrary.toNative(listOf(p))!![8], 0f)
    }

    @Test fun packPointPacksWholeUnitsExactly() {
        // (x + 2048) * 4096 + (y + 2048), exact in a float inside +-2047.
        assertEquals(8390156f, ClipFxLibrary.packPoint(0f, -500f), 0f)
        assertEquals(10438656f, ClipFxLibrary.packPoint(500f, 0f), 0f)
        assertEquals(0f, ClipFxLibrary.packPoint(-2048f, -2048f), 0f)
        assertEquals(16777215f, ClipFxLibrary.packPoint(2047f, 2047f), 0f)
    }

    @Test fun longShadowAndColorTuneReachTheEngine() {
        // Unlike Motion Blur / Repeat, the v160 effects run in the shared shader programs, so
        // toNative must emit them (fxParseOps accepts 59 / 60 explicitly).
        val ls = ClipFxLibrary.create(ClipFxLibrary.LONG_SHADOW)!!
        val flatLs = ClipFxLibrary.toNative(listOf(ls))!!
        assertEquals(59f, flatLs[0], 0f)
        assertEquals(20f, flatLs[1], 0f)   // default length
        val ct = ClipFxLibrary.create(ClipFxLibrary.COLOR_TUNE)!!
        val flatCt = ClipFxLibrary.toNative(listOf(ct))!!
        assertEquals(60f, flatCt[0], 0f)
        assertEquals(240f, flatCt[1], 0f)  // default lift hue
    }

    @Test fun motionBlurIsSkippedByToNativeButCountsAsAnEffect() {
        val mb = ClipFxLibrary.create(ClipFxLibrary.MOTION_BLUR)!!
        assertEquals(listOf(100f), mb.values)
        assertNull(ClipFxLibrary.toNative(listOf(mb)))   // needs a frame time: MotionBlurEval.resolve first
        assertTrue(ClipFxLibrary.hasEffects(listOf(mb)))
        assertFalse(ClipFxLibrary.hasEffects(listOf(mb.copy(enabled = false))))
        assertFalse(ClipFxLibrary.hasEffects(emptyList()))
    }

    @Test fun repeatIsSkippedByToNativeButCountsAsAnEffect() {
        val rp = ClipFxLibrary.create(ClipFxLibrary.REPEAT)!!
        assertEquals(listOf(2f, 0f, 0f, 0f, 100f, 100f), rp.values)
        assertNull(ClipFxLibrary.toNative(listOf(rp)))   // needs the draw loop: RepeatEval.copiesOf first
        assertTrue(ClipFxLibrary.hasEffects(listOf(rp)))
        assertFalse(ClipFxLibrary.hasEffects(listOf(rp.copy(enabled = false))))
        // A Repeat next to a shader effect still emits the shader effect only.
        val blur = ClipFxLibrary.create(ClipFxLibrary.BLUR)!!
        val flat = ClipFxLibrary.toNative(listOf(rp, blur))!!
        assertEquals(8, flat.size)
        assertEquals(1f, flat[0], 0f)
    }

    @Test fun repeatRadialAndScatterAreSkippedByToNativeButCountAsEffects() {
        val rr = ClipFxLibrary.create(ClipFxLibrary.REPEAT_RADIAL)!!
        assertEquals(listOf(4f, 200f, 0f, 360f, 0f, 100f, 100f), rr.values)
        assertNull(ClipFxLibrary.toNative(listOf(rr)))   // needs the draw loop: RepeatEval.copiesOf first
        assertTrue(ClipFxLibrary.hasEffects(listOf(rr)))
        val rs = ClipFxLibrary.create(ClipFxLibrary.REPEAT_SCATTER)!!
        assertEquals(listOf(12f, 400f, 0f, 0f, 0f, 20f, 100f), rs.values)
        assertNull(ClipFxLibrary.toNative(listOf(rs)))
        assertTrue(ClipFxLibrary.hasEffects(listOf(rs)))
        assertFalse(ClipFxLibrary.hasEffects(listOf(rs.copy(enabled = false))))
    }

    @Test fun everyEffectHasValidParams() {
        for (d in ClipFxLibrary.all) {
            // 1..9 params: Repeat Line is the only def with more than seven (count,
            // position, offset, angle, scale, alpha, phase are all first-class sliders
            // there); the panel renders every param, the bound is only the catalogue
            // invariant that no def grows unbounded.
            assertTrue("${d.id} has 1..9 params", d.params.size in 1..9)
            assertTrue("${d.id} category exists", d.category in ClipFxLibrary.categories)
            for (p in d.params) {
                assertTrue("${d.id}/${p.name} min < max", p.min < p.max)
                assertTrue("${d.id}/${p.name} default in range", p.default >= p.min && p.default <= p.max)
                assertTrue("${d.id}/${p.name} step > 0", p.step > 0f)
                // the ruler works in whole steps from min: the default must sit exactly on a tick
                val ticks = (p.default - p.min) / p.step
                assertTrue("${d.id}/${p.name} default is on a tick", abs(ticks - ticks.roundToInt()) < 1e-3f)
                val maxTicks = (p.max - p.min) / p.step
                assertTrue("${d.id}/${p.name} max is on a tick", abs(maxTicks - maxTicks.roundToInt()) < 1e-3f)
            }
        }
    }

    @Test fun everyCategoryHasEffects() {
        for (c in ClipFxLibrary.categories) {
            assertTrue("$c is empty", ClipFxLibrary.all.any { it.category == c })
        }
    }

    @Test fun createUsesDefaults() {
        val fx = ClipFxLibrary.create(ClipFxLibrary.LEVELS)
        assertNotNull(fx)
        assertEquals(listOf(0f, 255f, 100f, 0f, 255f), fx!!.values)
        assertNull(ClipFxLibrary.create("nope"))
    }

    @Test fun toNativeWritesGroupsOfEight() {
        val list = listOf(
            ClipFxLibrary.create(ClipFxLibrary.GLITCH)!!,
            ClipFxLibrary.create(ClipFxLibrary.EXPOSURE)!!.copy(values = listOf(1.5f, 10f, 120f, 25f)),
            ClipFxLibrary.create(ClipFxLibrary.BLUR)!!.copy(enabled = false)
        )
        val flat = ClipFxLibrary.toNative(list)!!
        assertEquals(16, flat.size)  // the disabled one is left out
        assertEquals(26f, flat[0], 0f)
        assertEquals(10f, flat[8], 0f)
        assertEquals(1.5f, flat[9], 0f)
        assertEquals(25f, flat[12], 0f)
        assertEquals(0f, flat[13], 0f)  // params the effect does not have are 0
        assertNull(ClipFxLibrary.toNative(emptyList()))
    }

    @Test fun toNativeStopsAtTheLimit() {
        val one = ClipFxLibrary.create(ClipFxLibrary.MOSAIC)!!
        val many = List(ClipFxLibrary.MAX_EFFECTS + 5) { one.copy(id = "e$it") }
        assertEquals(ClipFxLibrary.MAX_EFFECTS * 8, ClipFxLibrary.toNative(many)!!.size)
    }

    @Test fun chromaKeyEyedropperStillWorks() {
        val k = ClipFxLibrary.keyFromRgb(0xFF00FF00L)  // pure green
        assertEquals(120f, k[0], 1e-3f)
        assertEquals(100f, k[1], 1e-3f)
        val fx: ClipFx = ClipFxLibrary.create(ClipFxLibrary.CHROMA_KEY)!!
        assertEquals(7, fx.values.size)
    }
}
