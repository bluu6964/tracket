package com.example.model

/**
 * The tools a Controller offers (Sarowar's scope correction, 2026-11-13 ~16:09 — this
 * supersedes build 146's full-panel parity).
 *
 * On a Controller ONLY these five tools work: **Transform, Effects, Blend, Color,
 * Animations** — they are exactly the edits that fan out to the connected layers (see
 * [ControllerFanOut]). Everything else a layer's panel shows (Crop, Masks, Parent,
 * Rename, the video tools …) does nothing meaningful for a multi-layer edit handle,
 * so it is not offered. Connecting layers stays where it always was: the Parent
 * picker, from the CHILD layer's side.
 *
 * The ids are ClipEditToolsPanel's tool ids; VideoEditorScreen passes this set as the
 * panel's `onlyTools` when a Controller is selected. Kept here, in the model, so the
 * harness pins the scope exactly.
 */
object ControllerPanel {

    /** The Controller's panel tools — exactly these five, no others. */
    val TOOLS: Set<String> = setOf("transform", "effects", "blend", "color", "animations")
}
