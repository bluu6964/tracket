package com.example.model

import kotlinx.serialization.Serializable

/**
 * One-tap In / Out animations.
 *
 * An animation is NOT a separate effect: tapping one writes ordinary keyframes (with an easing curve)
 * into the layer, exactly like the keyframes the user would set by hand in Transform / Crop / Blend.
 * That is why every animation can be customised afterwards: move a diamond, change the easing curve,
 * add a keyframe in between - it is all the normal keyframe system.
 *
 * The keyframes of an animation carry a tag ([Keyframe.tag]) so the app knows which ones belong to
 * the In and which to the Out animation: tapping another animation replaces exactly those keyframes
 * and nothing the user made by hand outside the animation window.
 *
 * Animated properties: position, scale, rotation, opacity and crop, from any of the four directions.
 */

/** [Keyframe.tag] values. 0 = a keyframe the user made. */
const val ANIM_TAG_USER = 0
const val ANIM_TAG_IN = 1
const val ANIM_TAG_OUT = 2

/** Shortest animation, and the gap that is kept between the In and the Out animation. */
const val ANIM_MIN_MS = 100L
const val ANIM_GAP_MS = 50L
const val ANIM_DEFAULT_MS = 500L

enum class AnimSlot(val tag: Int, val label: String) {
    IN(ANIM_TAG_IN, "In"),
    OUT(ANIM_TAG_OUT, "Out")
}

/**
 * Direction. In: where the layer comes FROM. Out: where it goes TO.
 * For spins it is the sense of rotation (Left / Up = counter-clockwise, Right / Down = clockwise).
 */
@Serializable
enum class AnimDir(val label: String, val arrow: String) {
    LEFT("Left", "←"),
    RIGHT("Right", "→"),
    UP("Up", "↑"),
    DOWN("Down", "↓")
}

/** What the layer currently has applied in a slot (the keyframes are the truth, this is for the UI). */
@Serializable
data class ClipAnim(
    val preset: String,
    val dir: AnimDir,
    val durationMs: Long,
    val easing: Easing
)

/** Everything a preset needs to decide how the layer looks while it is "away". */
class AnimPlanCtx(
    val slot: AnimSlot,
    val dir: AnimDir,
    /** The layer as it looks when the animation is done (In) / starts (Out). */
    val base: TimelineClip,
    /** Canvas size in the same pixels as the Position values (preview pixels). */
    val canvasW: Float,
    val canvasH: Float
)

/**
 * The "away" value of every property the animation touches, and optionally replacements for the
 * "rest" value (used by the crop wipe so the box edge stays put).
 */
class AnimPlan(
    val away: Map<AnimProp, Pair<Float, Float>>,
    val rest: Map<AnimProp, Pair<Float, Float>> = emptyMap()
)

class AnimPreset(
    val id: String,
    val label: String,
    /** Directions the user can choose from; empty = the animation has no direction. */
    val dirs: List<AnimDir>,
    val defaultMs: Long,
    val easingIn: Easing,
    val easingOut: Easing,
    val plan: (AnimPlanCtx) -> AnimPlan
) {
    fun defaultEasing(slot: AnimSlot): Easing = if (slot == AnimSlot.IN) easingIn else easingOut
    fun defaultDir(): AnimDir = dirs.firstOrNull() ?: AnimDir.LEFT
}

object AnimPresets {

    private fun ease(name: String): Easing = Easing.PRESETS.first { it.first == name }.second

    private val ALL_DIRS = listOf(AnimDir.LEFT, AnimDir.RIGHT, AnimDir.UP, AnimDir.DOWN)
    private val SPIN_DIRS = listOf(AnimDir.RIGHT, AnimDir.LEFT)

    // ---- building blocks -------------------------------------------------------------

    /** Position that puts the layer completely outside the canvas on the side [dir]. */
    private fun offscreen(c: AnimPlanCtx): Pair<Float, Float> {
        val s = (c.base.scalePercent / 100f).coerceAtLeast(0.1f)
        val hx = c.canvasW / 2f * (1f + s)
        val hy = c.canvasH / 2f * (1f + s)
        val x = c.base.transformX
        val y = c.base.transformY
        return when (c.dir) {
            AnimDir.LEFT -> -hx to y
            AnimDir.RIGHT -> hx to y
            AnimDir.UP -> x to -hy
            AnimDir.DOWN -> x to hy
        }
    }

    /** Position moved a [fraction] of the canvas towards the side [dir]. */
    private fun nudged(c: AnimPlanCtx, fraction: Float): Pair<Float, Float> {
        val x = c.base.transformX
        val y = c.base.transformY
        return when (c.dir) {
            AnimDir.LEFT -> (x - c.canvasW * fraction) to y
            AnimDir.RIGHT -> (x + c.canvasW * fraction) to y
            AnimDir.UP -> x to (y - c.canvasH * fraction)
            AnimDir.DOWN -> x to (y + c.canvasH * fraction)
        }
    }

    private fun fade() = AnimProp.OPACITY to (0f to 0f)

    private fun scaled(c: AnimPlanCtx, factor: Float) = AnimProp.SCALE to ((c.base.scalePercent * factor) to 0f)

    /** +1 = clockwise, -1 = counter-clockwise. */
    private fun spinSense(dir: AnimDir): Float = if (dir == AnimDir.RIGHT || dir == AnimDir.DOWN) 1f else -1f

    private fun rotated(c: AnimPlanCtx, degrees: Float): Pair<AnimProp, Pair<Float, Float>> {
        // In: arrives turning in the chosen sense (starts behind it). Out: leaves turning in that sense.
        val sign = if (c.slot == AnimSlot.IN) -1f else 1f
        return AnimProp.ROTATION to ((c.base.rotationDeg + sign * spinSense(c.dir) * degrees) to 0f)
    }

    /**
     * Crop wipe: the visible box grows from (In) / shrinks to (Out) the edge [dir]. The crop box is
     * (size %, position %): position 0 = box at the left / top edge, 100 = at the right / bottom edge.
     */
    private fun wipe(c: AnimPlanCtx): AnimPlan {
        val minP = 5f // the renderer never crops below 5 %
        val b = c.base
        val bw = b.cropWidthPercent
        val bh = b.cropHeightPercent
        val l0 = (1f - bw / 100f) * (b.cropHorizontalPercent / 100f)
        val t0 = (1f - bh / 100f) * (b.cropVerticalPercent / 100f)
        fun pos(edgeStart: Float, size: Float): Float {
            val room = 1f - size / 100f
            return if (room < 1e-3f) 50f else (edgeStart / room * 100f).coerceIn(0f, 100f)
        }
        var w = bw
        var h = bh
        var px = b.cropHorizontalPercent
        var py = b.cropVerticalPercent
        when (c.dir) {
            AnimDir.LEFT -> { w = minP; px = pos(l0, w) }
            AnimDir.RIGHT -> { w = minP; px = pos(l0 + bw / 100f - w / 100f, w) }
            AnimDir.UP -> { h = minP; py = pos(t0, h) }
            AnimDir.DOWN -> { h = minP; py = pos(t0 + bh / 100f - h / 100f, h) }
        }
        // A full-size box looks the same at any position: keep the edge-anchored position at rest too,
        // so the edge does not drift while the box grows.
        val restX = if (bw >= 99.9f && (c.dir == AnimDir.LEFT || c.dir == AnimDir.RIGHT)) px else b.cropHorizontalPercent
        val restY = if (bh >= 99.9f && (c.dir == AnimDir.UP || c.dir == AnimDir.DOWN)) py else b.cropVerticalPercent
        return AnimPlan(
            away = mapOf(
                AnimProp.CROP_SIZE to (w to h),
                AnimProp.CROP_POS to (px to py)
            ),
            rest = mapOf(AnimProp.CROP_POS to (restX to restY))
        )
    }

    // ---- the catalogue ---------------------------------------------------------------

    val ALL: List<AnimPreset> = listOf(
        AnimPreset("fade", "Fade", emptyList(), 500L, ease("Ease out"), ease("Ease in")) {
            AnimPlan(mapOf(fade()))
        },
        AnimPreset("slide", "Slide", ALL_DIRS, 500L, ease("Cubic out"), ease("Cubic in")) {
            AnimPlan(mapOf(AnimProp.POSITION to offscreen(it)))
        },
        AnimPreset("slide_fade", "Slide Fade", ALL_DIRS, 600L, ease("Cubic out"), ease("Cubic in")) {
            AnimPlan(mapOf(AnimProp.POSITION to nudged(it, 0.2f), fade()))
        },
        AnimPreset("bounce", "Bounce", ALL_DIRS, 700L, ease("Back out"), ease("Back in")) {
            AnimPlan(mapOf(AnimProp.POSITION to offscreen(it)))
        },
        AnimPreset("zoom", "Zoom", emptyList(), 500L, ease("Cubic out"), ease("Cubic in")) {
            AnimPlan(mapOf(scaled(it, 0f)))
        },
        AnimPreset("zoom_fade", "Zoom Fade", emptyList(), 600L, ease("Cubic out"), ease("Cubic in")) {
            AnimPlan(mapOf(scaled(it, 0.5f), fade()))
        },
        AnimPreset("zoom_out", "Zoom Out", emptyList(), 600L, ease("Cubic out"), ease("Cubic in")) {
            AnimPlan(mapOf(scaled(it, 1.6f), fade()))
        },
        AnimPreset("pop", "Pop", emptyList(), 600L, ease("Back out"), ease("Back in")) {
            AnimPlan(mapOf(scaled(it, 0f)))
        },
        AnimPreset("spin", "Spin", SPIN_DIRS, 700L, ease("Cubic out"), ease("Cubic in")) {
            AnimPlan(mapOf(rotated(it, 180f), fade()))
        },
        AnimPreset("spin_zoom", "Spin Zoom", SPIN_DIRS, 800L, ease("Cubic out"), ease("Cubic in")) {
            AnimPlan(mapOf(rotated(it, 360f), scaled(it, 0f)))
        },
        AnimPreset("wipe", "Wipe", ALL_DIRS, 600L, ease("Cubic out"), ease("Cubic in")) { wipe(it) }
    )

    fun byId(id: String?): AnimPreset? = ALL.firstOrNull { it.id == id }
}

// =====================================================================================
// Applying / removing
// =====================================================================================

/** Length (ms) of the keyframes of [slot] on the layer, 0 when the slot has none. */
fun TimelineClip.animSpanMs(slot: AnimSlot): Long {
    val times = keyframes.values.flatMap { l -> l.filter { it.tag == slot.tag }.map { it.timeMs } }
    return if (times.isEmpty()) 0L else times.max() - times.min()
}

/** Longest the animation of [slot] may be: what is left of the layer after the other slot. */
fun TimelineClip.maxAnimMs(slot: AnimSlot): Long {
    val other = if (slot == AnimSlot.IN) AnimSlot.OUT else AnimSlot.IN
    val otherSpan = animSpanMs(other)
    val used = if (otherSpan > 0L) otherSpan + ANIM_GAP_MS else 0L
    return (durationMs - used).coerceAtLeast(0L)
}

/** Removes the animation of [slot] (its keyframes only; the layer keeps its normal values). */
fun TimelineClip.withoutClipAnim(slot: AnimSlot): TimelineClip {
    val cleaned = keyframes
        .mapValues { (_, l) -> l.filter { it.tag != slot.tag } }
        .filterValues { it.isNotEmpty() }
    return copy(
        keyframes = cleaned,
        animIn = if (slot == AnimSlot.IN) null else animIn,
        animOut = if (slot == AnimSlot.OUT) null else animOut
    )
}

/**
 * Applies a preset to the layer in one go: the animation of [slot] is replaced by keyframes that
 * make the layer come from (In) / go to (Out) the "away" state over [durationMs]. [easing] shapes the
 * curve (null = the preset's own curve). The canvas size is in preview pixels (Position units).
 */
fun TimelineClip.withClipAnim(
    slot: AnimSlot,
    preset: AnimPreset,
    dir: AnimDir,
    durationMs: Long,
    easing: Easing?,
    canvasW: Float,
    canvasH: Float
): TimelineClip {
    val c0 = withoutClipAnim(slot)
    val room = c0.maxAnimMs(slot)
    if (room < ANIM_MIN_MS) return c0
    val d = durationMs.coerceIn(ANIM_MIN_MS, room)
    val dur = c0.durationMs
    val anchor = if (slot == AnimSlot.IN) d else dur - d
    val base = c0.at(c0.startMs + anchor)
    val curve = easing ?: preset.defaultEasing(slot)
    val plan = preset.plan(AnimPlanCtx(slot, dir, base, canvasW, canvasH))

    val winStart = if (slot == AnimSlot.IN) 0L else dur - d
    val winEnd = if (slot == AnimSlot.IN) d else dur

    var kfs = c0.keyframes
    for ((prop, away) in plan.away) {
        val rest = plan.rest[prop] ?: base.propValues(prop)
        val mine = if (slot == AnimSlot.IN) {
            listOf(
                Keyframe(0L, away.first, away.second, curve, slot.tag),
                Keyframe(d, rest.first, rest.second, Easing.LINEAR, slot.tag)
            )
        } else {
            listOf(
                Keyframe(dur - d, rest.first, rest.second, curve, slot.tag),
                Keyframe(dur, away.first, away.second, Easing.LINEAR, slot.tag)
            )
        }
        // Anything the user keyed inside the animation window is replaced by the animation.
        val kept = kfs[prop].orEmpty().filter { it.timeMs < winStart || it.timeMs > winEnd }
        kfs = kfs + (prop to (kept + mine).sortedBy { it.timeMs })
    }
    val applied = ClipAnim(preset.id, dir, d, curve)
    return c0.copy(
        keyframes = kfs,
        animIn = if (slot == AnimSlot.IN) applied else c0.animIn,
        animOut = if (slot == AnimSlot.OUT) applied else c0.animOut
    )
}

/**
 * Layer start trimmed by [d] ms (positive = shorter). The In animation stays at the layer's start,
 * everything else (the Out animation and the user's keyframes) stays where it was in project time.
 */
fun TimelineClip.withTrimmedStartKeyframes(d: Long): TimelineClip {
    if (keyframes.isEmpty() || d == 0L) return this
    return copy(keyframes = keyframes.mapValues { (_, l) ->
        l.map { if (it.tag == ANIM_TAG_IN) it else it.copy(timeMs = it.timeMs - d) }
    })
}

/** The layer got [delta] ms longer (shorter when negative) at its end: the Out animation follows the end. */
fun TimelineClip.withEndMoved(delta: Long): TimelineClip {
    if (keyframes.isEmpty() || delta == 0L) return this
    return copy(keyframes = keyframes.mapValues { (_, l) ->
        l.map { if (it.tag == ANIM_TAG_OUT) it.copy(timeMs = it.timeMs + delta) else it }
    })
}
