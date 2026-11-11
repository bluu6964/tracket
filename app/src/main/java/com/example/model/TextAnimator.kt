package com.example.model

import kotlinx.serialization.Serializable

/**
 * One keyframe of an imported animator value. [t] is NORMALIZED: 0..1 of the layer's length
 * (this is how Alight Motion XML writes the keyframes of effect properties).
 */
@Serializable
data class AnimKey(val t: Float, val v: List<Float>)

/**
 * One value of an animator: a constant ([value]) or keyframes ([keys], sorted by t).
 * [type] is the Alight Motion type name: float, int, bool (0 / 1), vec2, color (packed with [packColor]: 2 floats).
 */
@Serializable
data class AnimValue(
    val type: String,
    val value: List<Float> = emptyList(),
    val keys: List<AnimKey> = emptyList()
)

/**
 * A text animator read from an Alight Motion XML (step 1 of the XML import: it is only STORED).
 * [effectId] is the Alight id, e.g. `com.alightcreative.effects.texttransform` (per character / word / line move, turn,
 * scale, fade). [props] keeps every property by its Alight name.
 * [com.example.engine.TextAnimEval] turns the `texttransform` one into per letter / word / line moves for the C++ text
 * engine (preview + export); [com.example.engine.TextProgressEval] turns the `textprogress` one into the shown
 * window of a typewriter reveal for the same engine; [com.example.engine.CounterEval] turns the `counter`
 * one into the counted number text ("Count Up/Down": each number in the text × scale + offset) that both
 * render paths substitute before drawing. (`randomdisplace` is NOT stored here: since v165 the
 * importer bakes it into the layer's position keys, for text layers like every other target.)
 * [refSize] = the Alight text `size` (scene px) of the layer it was read from: a move (`offset`) is in the layer's own
 * units, so it is kept relative to the font height (offset / refSize). 0 = unknown.
 */
@Serializable
data class TextAnimator(
    val effectId: String,
    val props: Map<String, AnimValue> = emptyMap(),
    val refSize: Float = 0f
)
