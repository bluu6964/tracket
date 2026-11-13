package com.example.model

/**
 * What the Compose canvas draws as a layer's own adornment (Sarowar's display
 * correction, 2026-11-13 ~16:09).
 *
 *  - [NOTHING] — the canvas draws nothing for the layer.
 *  - [MARKER] — the dashed position marker (dashed box + cross) at the layer's place.
 *  - [SELECTION_BOX] — the standard white bounding box every layer gets when selected
 *    (SelectionFrameOverlay), sized by the layer's own box.
 *
 * This rule governs ONLY the adornment: layer pictures are drawn by the preview
 * engines and are not governed here.
 */
enum class CanvasMark { NOTHING, MARKER, SELECTION_BOX }

/**
 * The rule (layer type × selected → adornment):
 *
 *  - NULL and CONTROLLER layers show NOTHING while unselected — no dashed position
 *    marker at all. Selected, they get the standard white bounding box like any
 *    other layer (Sarowar: "সিলেক্ট না করলে স্ক্রিনে কিছুই দেখা যাবে না ক্যানভাসে,
 *    তবে সিলেক্ট করলে … একটা সাদা box আসবে").
 *  - GROUP keeps its green dashed marker in both states (unchanged — a selected
 *    group additionally gets the white box through the generic selected-layer path,
 *    exactly as before).
 *  - Every other layer kind: nothing extra while unselected, the white selection
 *    box while selected (unchanged).
 */
object CanvasMarkRule {

    fun markFor(type: LayerType, isSelected: Boolean): CanvasMark = when (type) {
        LayerType.NULL, LayerType.CONTROLLER ->
            if (isSelected) CanvasMark.SELECTION_BOX else CanvasMark.NOTHING
        LayerType.GROUP -> CanvasMark.MARKER
        else -> if (isSelected) CanvasMark.SELECTION_BOX else CanvasMark.NOTHING
    }
}
