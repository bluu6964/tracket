package com.example

import com.example.engine.SolidColorEval
import com.example.model.AnimProp
import com.example.model.ClipFx
import com.example.model.ClipFxLibrary
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.packColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * SolidColorEval: a keyframed Solid Color (the importer's values[4] flag) takes its colour per
 * frame from the clip's FILL_COLOR track; everything else passes through untouched. The track
 * values here are the corpus' one keyframed instance (the file "3" Outline: white -> green at
 * t = 0.694326 / 0.705640 of its 5833 ms).
 */
class SolidColorEvalTest {

    private fun clipWith(keys: List<Keyframe>?): TimelineClip {
        val (wa, wb) = packColor(0xFFFFFFFFL)
        val (ga, gb) = packColor(0xFF14FD6AL)
        val track = keys ?: listOf(
            Keyframe(4051L, wa, wb),
            Keyframe(4117L, ga, gb)
        )
        return TimelineClip(
            id = "o1", name = "Outline", type = LayerType.SHAPE, startMs = 0L, durationMs = 5833L,
            color = 0xFF000000L,
            keyframes = mapOf(AnimProp.FILL_COLOR to track)
        )
    }

    private fun fx(flag: Float) = ClipFx(
        type = ClipFxLibrary.SOLID_COLOR, values = listOf(255f, 255f, 255f, 100f, flag)
    )

    @Test
    fun flaggedFxFollowsTheColourTrack() {
        val clip = clipWith(null)
        val before = SolidColorEval.resolve(listOf(fx(1f)), clip, 2000L).single()
        assertEquals(listOf(255f, 255f, 255f, 100f, 1f), before.values)   // still white
        val after = SolidColorEval.resolve(listOf(fx(1f)), clip, 5000L).single()
        assertEquals(listOf(20f, 253f, 106f, 100f, 1f), after.values)     // #14fd6a
    }

    @Test
    fun unflaggedFxAndMissingTrackPassThrough() {
        val clip = clipWith(null)
        val plain = listOf(fx(0f))
        assertSame(plain, SolidColorEval.resolve(plain, clip, 5000L))
        val noTrack = clipWith(emptyList())
        val flagged = listOf(fx(1f))
        assertSame(flagged, SolidColorEval.resolve(flagged, noTrack, 5000L))
    }

    @Test
    fun solidColorIsAShadedNativeEffect() {
        // The panel definition must reach the engine: native type 61 (kFxColor mode 16).
        val arr = ClipFxLibrary.toNative(listOf(fx(0f)))!!
        assertEquals(61f, arr[0], 0.001f)
        assertEquals(255f, arr[1], 0.001f)
        assertEquals(100f, arr[4], 0.001f)   // strength rides at values[3] -> params[3]
    }
}
