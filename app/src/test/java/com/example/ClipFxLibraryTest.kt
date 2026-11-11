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
        // fxParseOps skips them (they sit above FX_MAX).
        assertEquals((1..58).toList(), ClipFxLibrary.all.map { it.nativeType }.sorted())
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
            assertTrue("${d.id} has 1..7 params", d.params.size in 1..7)
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
