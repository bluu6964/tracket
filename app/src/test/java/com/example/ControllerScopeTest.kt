package com.example

import com.example.model.AnimProp
import com.example.model.CanvasMark
import com.example.model.CanvasMarkRule
import com.example.model.ControllerFanOut
import com.example.model.ControllerPanel
import com.example.model.FILL_GRADIENT
import com.example.model.FILL_IMAGE
import com.example.model.FILL_SOLID
import com.example.model.Keyframe
import com.example.model.LayerType
import com.example.model.TimelineClip
import com.example.model.packColor
import com.example.model.unpackColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 155 (Sarowar's corrections, 2026-11-13 ~16:09): the Controller's panel scope,
 * the colour fan-out, and the Null / Controller canvas display rule.
 *
 * Scope: on a Controller ONLY Transform, Effects, Blend, Color and Animations work —
 * the five edits that fan out. Display: a Null or Controller layer draws NOTHING on
 * the canvas while unselected (no dashed position marker); selected, it gets the
 * standard white bounding box like any other layer. Groups keep their marker and
 * picture layers are unchanged.
 */
class ControllerScopeTest {

    // ---- The Controller's panel scope ------------------------------------------------

    @Test
    fun controllerPanelHasExactlyTheFiveTools() {
        assertEquals(
            setOf("transform", "effects", "blend", "color", "animations"),
            ControllerPanel.TOOLS
        )
        assertEquals(5, ControllerPanel.TOOLS.size)
    }

    // ---- The canvas display rule -----------------------------------------------------

    @Test
    fun nullAndControllerAreInvisibleUntilSelected() {
        for (type in listOf(LayerType.NULL, LayerType.CONTROLLER)) {
            assertEquals("unselected $type draws nothing", CanvasMark.NOTHING, CanvasMarkRule.markFor(type, false))
            assertEquals("selected $type gets the white box", CanvasMark.SELECTION_BOX, CanvasMarkRule.markFor(type, true))
        }
    }

    @Test
    fun groupKeepsItsMarkerInBothStates() {
        assertEquals(CanvasMark.MARKER, CanvasMarkRule.markFor(LayerType.GROUP, false))
        assertEquals(CanvasMark.MARKER, CanvasMarkRule.markFor(LayerType.GROUP, true))
    }

    @Test
    fun pictureLayersCanvasMarkUnchanged() {
        for (type in listOf(LayerType.SHAPE, LayerType.TEXT, LayerType.VIDEO, LayerType.STICKER)) {
            assertEquals("unselected $type", CanvasMark.NOTHING, CanvasMarkRule.markFor(type, false))
            assertEquals("selected $type", CanvasMark.SELECTION_BOX, CanvasMarkRule.markFor(type, true))
        }
    }

    // ---- The colour fan-out ----------------------------------------------------------

    private fun layer(
        id: String, type: LayerType,
        textColor: Long = 0xFFFFFFFF, fillMode: Int = FILL_SOLID,
        gradColor1: Long = 0xFF1FD91F, gradColor2: Long = 0xFFFFFFFF,
        fillImageUri: String? = null,
        controllerId: String? = null,
        keyframes: Map<AnimProp, List<Keyframe>> = emptyMap()
    ) = TimelineClip(
        id = id, name = id, type = type, startMs = 0L, durationMs = 5000L, color = 0L,
        textColor = textColor, fillMode = fillMode,
        gradColor1 = gradColor1, gradColor2 = gradColor2,
        fillImageUri = fillImageUri,
        controllerId = controllerId, keyframes = keyframes
    )

    @Test
    fun colourFanOutLandsIdenticallyOnShapeAndTextLayers() {
        // The Controller's Color edit: a gradient with new stops, new geometry and a
        // new solid colour. Both a connected shape and a connected text layer take
        // the Controller's whole fill state, identically.
        val ctrlB = layer("ctrl", LayerType.CONTROLLER)
        val ctrlA = ctrlB.copy(
            fillMode = FILL_GRADIENT,
            textColor = 0xFF778899,
            gradColor1 = 0xFF112233, gradColor2 = 0xFF445566,
            gradX1 = 0.25f, gradY1 = 0.25f, gradX2 = 0.75f, gradY2 = 0.75f
        )
        val shape = layer("s", LayerType.SHAPE, textColor = 0xFF000000, controllerId = "ctrl")
        val text = layer("t", LayerType.TEXT, textColor = 0xFF0000FF, controllerId = "ctrl")
        for (f in listOf(
            ControllerFanOut.fanned(shape, ctrlB, ctrlA, 100L),
            ControllerFanOut.fanned(text, ctrlB, ctrlA, 100L)
        )) {
            assertEquals("fill mode ${f.id}", FILL_GRADIENT, f.fillMode)
            assertEquals("solid colour ${f.id}", 0xFF778899, f.textColor)
            assertEquals("gradient stop 1 ${f.id}", 0xFF112233, f.gradColor1)
            assertEquals("gradient stop 2 ${f.id}", 0xFF445566, f.gradColor2)
            assertEquals("gradient geometry ${f.id}", 0.25f, f.gradX1, 0.001f)
            assertEquals("gradient geometry ${f.id}", 0.75f, f.gradY2, 0.001f)
        }
    }

    @Test
    fun colourFanOutReplacesAnImageFill() {
        // The Carousel case in miniature: a card whose fill is an image takes the
        // Controller's picked solid colour — mode and colour together, or the user
        // would see nothing change.
        val ctrlB = layer("ctrl", LayerType.CONTROLLER)
        val ctrlA = ctrlB.copy(textColor = 0xFFFF0000)
        val card = layer(
            "card", LayerType.SHAPE, fillMode = FILL_IMAGE,
            fillImageUri = "file:///imports/card1.png", controllerId = "ctrl"
        )
        val f = ControllerFanOut.fanned(card, ctrlB, ctrlA, 0L)
        assertEquals(FILL_SOLID, f.fillMode)
        assertEquals(0xFFFF0000, f.textColor)
        assertEquals(null, f.fillImageUri)
    }

    @Test
    fun colourFanOutSkipsLayersWithoutAColourConcept() {
        // A connected VIDEO layer has no fill to colour: a colour-only Controller
        // edit leaves it byte-identical (the same instance comes back).
        val ctrlB = layer("ctrl", LayerType.CONTROLLER)
        val ctrlA = ctrlB.copy(textColor = 0xFFFF0000, fillMode = FILL_GRADIENT, gradColor1 = 0xFF111111)
        val video = layer("v", LayerType.VIDEO, controllerId = "ctrl")
        assertTrue(ControllerFanOut.fanned(video, ctrlB, ctrlA, 0L) === video)
    }

    @Test
    fun colourFanOutLandsAsAKeyframeOnAColourKeyframedLayer() {
        // The editor's keyframe rule, for colours: a connected layer carrying
        // FILL_COLOR keyframes gets the Controller's colour as a keyframe at the
        // playhead; its stored fallback colour is untouched.
        val (rA, rB) = packColor(0xFFFF0000)
        val (bA, bB) = packColor(0xFF0000FF)
        val keyed = layer(
            "k", LayerType.TEXT, textColor = 0xFFFF0000, controllerId = "ctrl",
            keyframes = mapOf(
                AnimProp.FILL_COLOR to listOf(Keyframe(0L, rA, rB), Keyframe(4000L, bA, bB))
            )
        )
        val ctrlB = layer("ctrl", LayerType.CONTROLLER)
        val ctrlA = ctrlB.copy(textColor = 0xFF00FF00)
        val f = ControllerFanOut.fanned(keyed, ctrlB, ctrlA, 2000L)
        val keys = f.keyframes.getValue(AnimProp.FILL_COLOR)
        assertEquals(listOf(0L, 2000L, 4000L), keys.map { it.timeMs })
        assertEquals(0xFF00FF00, unpackColor(keys[1].a, keys[1].b))
        assertEquals("stored fallback untouched", 0xFFFF0000, f.textColor)
    }

    @Test
    fun anEditWithoutColourLeavesFillsUntouched() {
        // An opacity-only Controller edit must not disturb a connected layer's fill:
        // the fan-out's colour bundle only moves when the Controller's colour moved.
        val ctrlB = layer("ctrl", LayerType.CONTROLLER)
        val ctrlA = ctrlB.copy(opacity = 40f)
        val card = layer(
            "card", LayerType.SHAPE, fillMode = FILL_IMAGE,
            fillImageUri = "file:///imports/card1.png", controllerId = "ctrl"
        )
        val f = ControllerFanOut.fanned(card, ctrlB, ctrlA, 0L)
        assertEquals(40f, f.opacity, 0.001f)
        assertEquals(FILL_IMAGE, f.fillMode)
        assertEquals("file:///imports/card1.png", f.fillImageUri)
    }
}
