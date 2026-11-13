package com.example

import com.example.model.AnimDir
import com.example.model.ClipAnim
import com.example.model.ClipFx
import com.example.model.ClipMask
import com.example.model.Easing
import com.example.model.FILL_GRADIENT
import com.example.model.FILL_NONE
import com.example.model.LayerType
import com.example.model.MaskShape
import com.example.model.ParentLink
import com.example.model.TimelineClip
import com.example.model.UsedTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 166, item 5: the used-tool rule of the clip tool row — a tool reads "used"
 * (white in the B/W chrome) exactly when the layer carries a non-default trace of it.
 */
class UsedToolsTest {

    private fun clip() = TimelineClip(
        id = "c", name = "c", type = LayerType.VIDEO, startMs = 0L, durationMs = 5000L, color = 0L
    )

    @Test
    fun aFreshLayerHasNothingUsed() {
        assertEquals(emptySet<String>(), UsedTools.usedToolsOf(clip()))
        // A shape / text layer at its defaults is equally fresh.
        assertEquals(emptySet<String>(), UsedTools.usedToolsOf(clip().copy(type = LayerType.SHAPE, shapePattern = "RECT")))
        assertEquals(emptySet<String>(), UsedTools.usedToolsOf(clip().copy(type = LayerType.TEXT, text = "Hello")))
    }

    @Test
    fun eachStateFlipsExactlyItsTool() {
        val base = clip()
        fun used(c: TimelineClip) = UsedTools.usedToolsOf(c)
        assertEquals(setOf("effects"), used(base.copy(effects = listOf(ClipFx(type = "blur", values = listOf(10f))))))
        assertEquals(setOf("animations"), used(base.copy(animIn = ClipAnim("fade", AnimDir.LEFT, 500L, Easing(0f, 0f, 1f, 1f)))))
        assertEquals(setOf("animations"), used(base.copy(animOut = ClipAnim("fade", AnimDir.LEFT, 500L, Easing(0f, 0f, 1f, 1f)))))
        assertEquals(setOf("masks"), used(base.copy(masks = listOf(ClipMask(shape = MaskShape.ELLIPSE)))))
        assertEquals(setOf("blend"), used(base.copy(blendMode = "Screen")))
        assertEquals(setOf("blend"), used(base.copy(opacity = 55f)))
        assertEquals(setOf("crop"), used(base.copy(cropWidthPercent = 80f)))
        assertEquals(setOf("crop"), used(base.copy(cropHeightPercent = 80f)))
        assertEquals(setOf("volume"), used(base.copy(volume = 0.5f)))
        assertEquals(setOf("volume"), used(base.copy(isMuted = true)))
        assertEquals(setOf("cutout"), used(base.copy(cutoutDir = "/tmp/cutout")))
        assertEquals(setOf("parent"), used(base.copy(parent = ParentLink("p", 0f, 0f, 1f, 0f))))
    }

    @Test
    fun colorIsUsedOnlyOffTheDefaultFill() {
        val shape = clip().copy(type = LayerType.SHAPE, shapePattern = "RECT")
        assertEquals(setOf("color"), UsedTools.usedToolsOf(shape.copy(fillMode = FILL_GRADIENT)))
        assertEquals(setOf("color"), UsedTools.usedToolsOf(shape.copy(fillMode = FILL_NONE)))
        assertEquals(setOf("color"), UsedTools.usedToolsOf(shape.copy(textColor = 0xFFFF0000)))
        // The default white solid fill is not a use.
        assertTrue(UsedTools.usedToolsOf(shape).isEmpty())
    }

    @Test
    fun transformIsUsedWhenMovedScaledTurnedOrKeyframed() {
        val base = clip()
        assertEquals(setOf("transform"), UsedTools.usedToolsOf(base.copy(transformX = 12f)))
        assertEquals(setOf("transform"), UsedTools.usedToolsOf(base.copy(transformY = -3f)))
        assertEquals(setOf("transform"), UsedTools.usedToolsOf(base.copy(rotationDeg = 45f)))
        assertEquals(setOf("transform"), UsedTools.usedToolsOf(base.copy(scalePercent = 150f)))
        assertEquals(setOf("transform"), UsedTools.usedToolsOf(base.copy(rotation3dY = 20f)))
        assertEquals(setOf("transform"), UsedTools.usedToolsOf(base.copy(transformZ = 2f)))
        // A resting layer (centre, 100 %, no turn) is not transformed.
        assertTrue(UsedTools.usedToolsOf(base).isEmpty())
    }

    @Test
    fun severalToolsCombine() {
        val c = clip().copy(
            effects = listOf(ClipFx(type = "blur", values = listOf(5f))),
            masks = listOf(ClipMask(shape = MaskShape.RECT)),
            blendMode = "Multiply", transformX = 40f
        )
        assertEquals(setOf("effects", "masks", "blend", "transform"), UsedTools.usedToolsOf(c))
    }
}
