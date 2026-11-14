package com.example

import com.example.model.AnimProp
import com.example.model.ClipFx
import com.example.model.Easing
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.copyEffects
import com.example.model.deepCopyKeyframes
import com.example.model.withFreshEffectIds
import com.example.model.withKeyframe
import com.example.model.withPastedKeyframes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 176 — the clipboard payload rules (model/ClipClipboard.kt): keyframe paste
 * anchors the copied set's earliest key on the playhead, replaces only inside each
 * pasted prop's span, and the copies are deep.
 */
class ClipClipboardTest {

    private fun clip(durationMs: Long = 5000) = TimelineClip(
        id = "a", name = "a", type = LayerType.SHAPE, startMs = 1000, durationMs = durationMs, color = 0xFF000000
    )

    private val easeIn = Easing(0.42f, 0f, 1f, 1f)

    private fun copied(): Map<AnimProp, List<Keyframe>> = mapOf(
        AnimProp.POSITION to listOf(
            Keyframe(200, 10f, 20f, easeIn),
            Keyframe(700, 30f, 40f)
        ),
        AnimProp.ROTATION to listOf(Keyframe(400, 45f))
    )

    @Test
    fun paste_anchorsEarliestKeyAtTheAnchor_offsetsEasingsValuesPreserved() {
        val out = clip().withPastedKeyframes(copied(), anchorRelMs = 1000)
        val pos = out.keyframes.getValue(AnimProp.POSITION)
        assertEquals(listOf(1000L, 1500L), pos.map { it.timeMs })
        assertEquals(10f, pos[0].a, 0f)
        assertEquals(20f, pos[0].b, 0f)
        assertEquals(30f, pos[1].a, 0f)
        assertTrue(pos[0].easing.sameAs(easeIn))
        val rot = out.keyframes.getValue(AnimProp.ROTATION)
        assertEquals(listOf(1200L), rot.map { it.timeMs })
        assertEquals(45f, rot[0].a, 0f)
    }

    @Test
    fun paste_replacesOnlyInsideTheSpan_otherKeysAndPropsUntouched() {
        val target = clip().copy(
            keyframes = mapOf(
                AnimProp.POSITION to listOf(
                    Keyframe(500, 1f, 1f),
                    Keyframe(1200, 2f, 2f),
                    Keyframe(3000, 3f, 3f)
                ),
                AnimProp.OPACITY to listOf(Keyframe(800, 50f))
            )
        )
        // POSITION-only paste spanning [1000, 1500] after the shift.
        val posOnly = mapOf(
            AnimProp.POSITION to listOf(Keyframe(200, 10f, 20f), Keyframe(700, 30f, 40f))
        )
        val out = target.withPastedKeyframes(posOnly, anchorRelMs = 1000)
        assertEquals(
            listOf(500L, 1000L, 1500L, 3000L),
            out.keyframes.getValue(AnimProp.POSITION).map { it.timeMs }
        )
        // The key at 1200 (inside the span) is gone; 500 and 3000 (outside) kept their values.
        assertEquals(1f, out.keyframes.getValue(AnimProp.POSITION)[0].a, 0f)
        assertEquals(3f, out.keyframes.getValue(AnimProp.POSITION)[3].a, 0f)
        // A prop that was not copied is untouched.
        assertEquals(listOf(800L), out.keyframes.getValue(AnimProp.OPACITY).map { it.timeMs })
    }

    @Test
    fun paste_anchorIsCoercedIntoTheLayer() {
        val before = clip(durationMs = 2000).withPastedKeyframes(copied(), anchorRelMs = -50)
        assertEquals(0L, before.keyframes.getValue(AnimProp.POSITION).first().timeMs)
        val after = clip(durationMs = 2000).withPastedKeyframes(copied(), anchorRelMs = 99999)
        assertEquals(2000L, after.keyframes.getValue(AnimProp.POSITION).first().timeMs)
    }

    @Test
    fun copy_isDeep_laterEditsOfTheSourceDoNotLeakIntoTheClipboard() {
        val source = clip().copy(keyframes = copied())
        val clipboard = source.keyframes.deepCopyKeyframes()
        val edited = source.withKeyframe(AnimProp.POSITION, 900, 99f, 99f)
        assertTrue(edited.keyframes.getValue(AnimProp.POSITION).any { it.timeMs == 900L })
        val out = clip().withPastedKeyframes(clipboard, anchorRelMs = 0)
        assertFalse(out.keyframes.getValue(AnimProp.POSITION).any { it.a == 99f })
        assertEquals(listOf(0L, 500L), out.keyframes.getValue(AnimProp.POSITION).map { it.timeMs })
    }

    @Test
    fun paste_emptyClipboardSet_isANoOp() {
        val target = clip().copy(keyframes = mapOf(AnimProp.OPACITY to listOf(Keyframe(100, 40f))))
        val out = target.withPastedKeyframes(emptyMap(), anchorRelMs = 500)
        assertEquals(target.keyframes, out.keyframes)
    }

    @Test
    fun effects_copyKeepsIdsAndValues_pasteReIds() {
        val stack = listOf(
            ClipFx(id = "x", type = "gaussian_blur", values = listOf(1f, 2f, 3f)),
            ClipFx(id = "y", type = "mirror_am", enabled = false, values = (0..20).map { it.toFloat() })
        )
        val copiedStack = stack.copyEffects()
        assertEquals(listOf("x", "y"), copiedStack.map { it.id })
        assertEquals(stack[1].values, copiedStack[1].values)
        assertFalse(copiedStack[1].values === stack[1].values)

        val pasted = copiedStack.withFreshEffectIds()
        assertEquals(2, pasted.map { it.id }.distinct().size)
        assertFalse(pasted.any { it.id == "x" || it.id == "y" })
        assertEquals("gaussian_blur", pasted[0].type)
        assertEquals(false, pasted[1].enabled)
        // The keyframed param tail (values past the head) rides along whole.
        assertEquals((0..20).map { it.toFloat() }, pasted[1].values)
    }
}
