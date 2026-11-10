package com.example

import com.example.model.AnimProp
import com.example.model.DEPTH_D
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.at
import com.example.model.shapeDrawScaleX
import com.example.model.shapeDrawScaleY
import com.example.model.withKeyframe
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shape painters' per-axis scale ([shapeDrawScaleX] / [shapeDrawScaleY]): X is the layer's
 * effective scale, Y carries the shape's own aspect (shapeScaleY / shapeScaleX) on top of it.
 */
class ShapeScaleTest {

    private fun shape() = TimelineClip(
        id = "s", name = "s", type = LayerType.SHAPE, startMs = 0, durationMs = 4000, color = 0xFF000000
    )

    @Test
    fun uniformShapeUsesTheLayerScaleOnBothAxes() {
        val c = shape().copy(scalePercent = 250f)  // shapeScaleX/Y stay 1: no aspect of its own
        assertEquals(2.5f, c.shapeDrawScaleX, 0.001f)
        assertEquals(2.5f, c.shapeDrawScaleY, 0.001f)
    }

    @Test
    fun aspectIsCarriedOnY() {
        // The importer's ST Intro "Rectangle 1": size 100x100 under transform scale (9.915, 5.577)
        // on a 1080p scene (shape box = 390 scene px) -> shapeScale (2.5423, 1.4300),
        // scalePercent = shapeScaleX x 100. The drawn size is the box times these factors.
        val box = 1080f * 130f / 360f
        val c = shape().copy(
            shapeScaleX = 991.5f / box, shapeScaleY = 557.7187f / box, scalePercent = 991.5f / box * 100f
        )
        assertEquals(991.5f / box, c.shapeDrawScaleX, 0.001f)
        assertEquals(557.7187f / box, c.shapeDrawScaleY, 0.001f)
    }

    @Test
    fun depthScalesBothAxesAndKeepsTheAspect() {
        val c = shape().copy(shapeScaleX = 2f, shapeScaleY = 1f, scalePercent = 200f, transformZ = DEPTH_D / 2f)
        // depthFactor at z = D / 2 is 2: the layer doubles on both axes, the 2:1 aspect is unchanged.
        assertEquals(4f, c.shapeDrawScaleX, 0.001f)
        assertEquals(2f, c.shapeDrawScaleY, 0.001f)
    }

    @Test
    fun keyframedScaleStaysUniform() {
        // A SCALE keyframe is the model's uniform scale (withProp sets both shape axes): an aspect
        // shape under one animates uniformly, and the paint factors agree at the keyframe times.
        val c = shape().copy(shapeScaleX = 2f, shapeScaleY = 1f, scalePercent = 200f)
            .withKeyframe(AnimProp.SCALE, 0, 200f, 0f)
            .withKeyframe(AnimProp.SCALE, 2000, 300f, 0f)
        val at1 = c.at(2000)
        assertEquals(3f, at1.shapeDrawScaleX, 0.001f)
        assertEquals(3f, at1.shapeDrawScaleY, 0.001f)
    }

    @Test
    fun missingAspectFallsBackToUniform() {
        // Degenerate stored data (shapeScaleX = 0): no ratio to take, so Y follows X.
        val c = shape().copy(shapeScaleX = 0f, shapeScaleY = 3f, scalePercent = 200f)
        assertEquals(2f, c.shapeDrawScaleX, 0.001f)
        assertEquals(2f, c.shapeDrawScaleY, 0.001f)
    }
}
