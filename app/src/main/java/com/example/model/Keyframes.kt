package com.example.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Keyframe animation of the Transform properties (Alight Motion style).
 *
 * Every property keeps its own list of keyframes. A keyframe stores the value at a time that is
 * RELATIVE to the layer start, so moving the layer keeps the animation with it. Between two
 * keyframes the value is blended with the easing curve of the first one of the pair.
 *
 * As soon as a property has keyframes, the plain field on the clip (transformX, scalePercent, ...)
 * is only a fallback: what is drawn comes from [TimelineClip.at].
 */
@Serializable
enum class AnimProp(val isColor: Boolean = false) {
    POSITION, SCALE, ROTATION, POS_Z, ROT_X, ROT_Y,

    /** Colour keyframes (Color panel). The ARGB colour is packed exactly into [Keyframe.a] / [Keyframe.b]. */
    FILL_COLOR(true), GRAD_COLOR_1(true), GRAD_COLOR_2(true),

    /** Crop (Crop panel): box size (width, height in %) and box position (horizontal, vertical in %). */
    CROP_SIZE, CROP_POS,

    /** Layer opacity (Blend panel), 0..100. */
    OPACITY,

    /** Playback speed (0.1x..4x). Time-remaps the layer's media (see [sourceOffsetMs]). */
    SPEED,

    /** Stroke trim (draw-on): a = visible outline start, b = visible outline end, both 0..1 of the outline length. */
    STROKE_TRIM
}

/** ARGB colour -> (A,R) and (G,B) packed as two exact floats (each < 65536). */
fun packColor(argb: Long): Pair<Float, Float> {
    val a = (argb shr 24) and 0xFF
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (a * 256 + r).toFloat() to (g * 256 + b).toFloat()
}

fun unpackColor(pa: Float, pb: Float): Long {
    val x = pa.toInt().coerceIn(0, 65535)
    val y = pb.toInt().coerceIn(0, 65535)
    return ((x shr 8).toLong() shl 24) or ((x and 0xFF).toLong() shl 16) or
        ((y shr 8).toLong() shl 8) or (y and 0xFF).toLong()
}

/** Playhead closer than this to a keyframe counts as "on" that keyframe. */
const val KEYFRAME_TOL_MS = 40L

/**
 * Cubic bezier easing, same as CSS cubic-bezier(x1, y1, x2, y2): the curve goes from (0,0) to (1,1)
 * and the two control points shape it. x is time (0..1), y is progress and may leave 0..1
 * (overshoot, "back" easings).
 */
@Serializable
data class Easing(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {

    val isLinear: Boolean
        get() = abs(x1 - y1) < 1e-4f && abs(x2 - y2) < 1e-4f && abs(x1 - 1f / 3f) < 1e-3f && abs(x2 - 2f / 3f) < 1e-3f

    private fun bx(s: Float): Float {
        val u = 1f - s
        return 3f * u * u * s * x1 + 3f * u * s * s * x2 + s * s * s
    }

    private fun by(s: Float): Float {
        val u = 1f - s
        return 3f * u * u * s * y1 + 3f * u * s * s * y2 + s * s * s
    }

    private fun dbx(s: Float): Float {
        val u = 1f - s
        return 3f * u * u * x1 + 6f * u * s * (x2 - x1) + 3f * s * s * (1f - x2)
    }

    /** Point of the curve for the bezier parameter [s] (0..1), used to draw it. */
    fun pointAt(s: Float): Pair<Float, Float> = bx(s) to by(s)

    /** Progress (0..1, may overshoot) for the time fraction [t] (0..1). */
    fun apply(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        // Solve bx(s) = t: a few Newton steps, then bisection as the safe fallback.
        var s = t
        for (i in 0 until 6) {
            val err = bx(s) - t
            if (abs(err) < 1e-5f) return by(s)
            val d = dbx(s)
            if (abs(d) < 1e-6f) break
            s = (s - err / d).coerceIn(0f, 1f)
        }
        var lo = 0f
        var hi = 1f
        s = t
        for (i in 0 until 24) {
            val x = bx(s)
            if (abs(x - t) < 1e-5f) break
            if (x < t) lo = s else hi = s
            s = (lo + hi) / 2f
        }
        return by(s)
    }

    fun sameAs(o: Easing, eps: Float = 0.01f): Boolean =
        abs(x1 - o.x1) < eps && abs(y1 - o.y1) < eps && abs(x2 - o.x2) < eps && abs(y2 - o.y2) < eps

    companion object {
        val LINEAR = Easing(1f / 3f, 1f / 3f, 2f / 3f, 2f / 3f)

        /** Curves offered in the easing editor (name, curve). */
        val PRESETS: List<Pair<String, Easing>> = listOf(
            "Linear" to LINEAR,
            "Ease in" to Easing(0.42f, 0f, 1f, 1f),
            "Ease out" to Easing(0f, 0f, 0.58f, 1f),
            "Ease in-out" to Easing(0.42f, 0f, 0.58f, 1f),
            "Sine in" to Easing(0.47f, 0f, 0.745f, 0.715f),
            "Sine out" to Easing(0.39f, 0.575f, 0.565f, 1f),
            "Sine in-out" to Easing(0.445f, 0.05f, 0.55f, 0.95f),
            "Quad in" to Easing(0.55f, 0.085f, 0.68f, 0.53f),
            "Quad out" to Easing(0.25f, 0.46f, 0.45f, 0.94f),
            "Quad in-out" to Easing(0.455f, 0.03f, 0.515f, 0.955f),
            "Cubic in" to Easing(0.55f, 0.055f, 0.675f, 0.19f),
            "Cubic out" to Easing(0.215f, 0.61f, 0.355f, 1f),
            "Cubic in-out" to Easing(0.645f, 0.045f, 0.355f, 1f),
            "Quart in-out" to Easing(0.77f, 0f, 0.175f, 1f),
            "Expo in" to Easing(0.95f, 0.05f, 0.795f, 0.035f),
            "Expo out" to Easing(0.19f, 1f, 0.22f, 1f),
            "Back in" to Easing(0.6f, -0.2f, 0.735f, 0.045f),
            "Back out" to Easing(0.175f, 0.885f, 0.32f, 1.2f),
            "Back in-out" to Easing(0.68f, -0.2f, 0.265f, 1.2f)
        )
    }
}

/**
 * One keyframe. [timeMs] is relative to the layer start. [a] is the value (X for Position),
 * [b] is the second value (Y for Position, otherwise unused). [easing] shapes the way from this
 * keyframe to the next one.
 * Tapping an animation (Animations panel) writes ordinary keyframes marked with a [tag].
 */
@Serializable
data class Keyframe(
    val timeMs: Long,
    val a: Float,
    val b: Float = 0f,
    val easing: Easing = Easing.LINEAR,
    /** 0 = made by the user, [ANIM_TAG_IN] / [ANIM_TAG_OUT] = part of an In / Out animation (see AnimPresets.kt). */
    val tag: Int = 0
)

/** Value of a keyframe list at [relMs]: held before the first / after the last, eased in between. */
fun List<Keyframe>.valueAt(relMs: Long): Pair<Float, Float> {
    val first = first()
    if (relMs <= first.timeMs) return first.a to first.b
    val last = last()
    if (relMs >= last.timeMs) return last.a to last.b
    var i = 0
    while (i < size - 1 && this[i + 1].timeMs <= relMs) i++
    val k0 = this[i]
    val k1 = this[i + 1]
    val span = (k1.timeMs - k0.timeMs).coerceAtLeast(1L)
    val t = (relMs - k0.timeMs).toFloat() / span
    val e = if (k0.easing.isLinear) t else k0.easing.apply(t)
    return (k0.a + (k1.a - k0.a) * e) to (k0.b + (k1.b - k0.b) * e)
}

/**
 * Index of the keyframe that starts the segment the time is in (k[i] <= rel < k[i+1]),
 * or -1 when the time is outside every segment (before the first / at or after the last keyframe).
 */
fun List<Keyframe>.segmentIndexAt(relMs: Long): Int {
    if (size < 2) return -1
    for (i in 0 until size - 1) {
        if (relMs >= this[i].timeMs && relMs < this[i + 1].timeMs) return i
    }
    return -1
}

/** Index of the keyframe the time sits on (within [KEYFRAME_TOL_MS]), or -1. */
fun List<Keyframe>.indexOnTime(relMs: Long): Int {
    var best = -1
    var bestD = Long.MAX_VALUE
    for (i in indices) {
        val d = abs(this[i].timeMs - relMs)
        if (d <= KEYFRAME_TOL_MS && d < bestD) {
            best = i
            bestD = d
        }
    }
    return best
}

/**
 * Colour keyframes: same segment / easing logic as [valueAt], but every channel (A, R, G, B) is
 * blended on its own and the result is packed again (see [packColor]).
 */
fun List<Keyframe>.colorValueAt(relMs: Long): Pair<Float, Float> {
    val first = first()
    if (relMs <= first.timeMs) return first.a to first.b
    val last = last()
    if (relMs >= last.timeMs) return last.a to last.b
    var i = 0
    while (i < size - 1 && this[i + 1].timeMs <= relMs) i++
    val k0 = this[i]
    val k1 = this[i + 1]
    val span = (k1.timeMs - k0.timeMs).coerceAtLeast(1L)
    val t = (relMs - k0.timeMs).toFloat() / span
    val e = if (k0.easing.isLinear) t else k0.easing.apply(t)
    val c0 = unpackColor(k0.a, k0.b)
    val c1 = unpackColor(k1.a, k1.b)
    fun ch(shift: Int): Long {
        val v0 = ((c0 shr shift) and 0xFF).toFloat()
        val v1 = ((c1 shr shift) and 0xFF).toFloat()
        return (v0 + (v1 - v0) * e).toInt().coerceIn(0, 255).toLong()
    }
    return packColor((ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
}

fun TimelineClip.propValues(p: AnimProp): Pair<Float, Float> = when (p) {
    AnimProp.POSITION -> transformX to transformY
    AnimProp.SCALE -> scalePercent to 0f
    AnimProp.ROTATION -> rotationDeg to 0f
    AnimProp.POS_Z -> transformZ to 0f
    AnimProp.ROT_X -> rotation3dX to 0f
    AnimProp.ROT_Y -> rotation3dY to 0f
    AnimProp.FILL_COLOR -> packColor(textColor)
    AnimProp.GRAD_COLOR_1 -> packColor(gradColor1)
    AnimProp.GRAD_COLOR_2 -> packColor(gradColor2)
    AnimProp.CROP_SIZE -> cropWidthPercent to cropHeightPercent
    AnimProp.CROP_POS -> cropHorizontalPercent to cropVerticalPercent
    AnimProp.OPACITY -> opacity to 0f
    AnimProp.SPEED -> speed to 0f
    AnimProp.STROKE_TRIM -> strokeTrimStart to strokeTrimEnd
}

fun TimelineClip.withProp(p: AnimProp, a: Float, b: Float): TimelineClip = when (p) {
    AnimProp.POSITION -> copy(transformX = a, transformY = b, shapeOffsetX = a, shapeOffsetY = b)
    // SCALE: `a` is the width scale %; `b` > 0 is a separate height scale % (the Alight importer stores a
    // shape's animated height there, see composedSizeKeys). b = 0 (everything the editor / presets write)
    // keeps the uniform meaning: the height follows the width, exactly as before.
    AnimProp.SCALE -> copy(
        scalePercent = a, shapeScaleX = a / 100f,
        shapeScaleY = if (b > 0f) b / 100f else a / 100f
    )
    AnimProp.ROTATION -> copy(rotationDeg = a)
    AnimProp.POS_Z -> copy(transformZ = a)
    AnimProp.ROT_X -> copy(rotation3dX = a)
    AnimProp.ROT_Y -> copy(rotation3dY = a)
    AnimProp.FILL_COLOR -> copy(textColor = unpackColor(a, b))
    AnimProp.GRAD_COLOR_1 -> copy(gradColor1 = unpackColor(a, b))
    AnimProp.GRAD_COLOR_2 -> copy(gradColor2 = unpackColor(a, b))
    AnimProp.CROP_SIZE -> copy(cropWidthPercent = a, cropHeightPercent = b)
    AnimProp.CROP_POS -> copy(cropHorizontalPercent = a, cropVerticalPercent = b)
    AnimProp.OPACITY -> copy(opacity = a)
    AnimProp.SPEED -> copy(speed = a.coerceIn(MIN_SPEED, MAX_SPEED))
    AnimProp.STROKE_TRIM -> copy(strokeTrimStart = a.coerceIn(0f, 1f), strokeTrimEnd = b.coerceIn(0f, 1f))
}

/** True for an imported `.pie` wedge: its outline is rebuilt per frame from its angle tracks. */
val TimelineClip.hasPie: Boolean
    get() = pieStartKeys.isNotEmpty() && pieEndKeys.isNotEmpty() && pieRadius > 0f

/**
 * The pie wedge's normalised outline at [relMs] (ms from the layer's start): both angle tracks
 * are sampled with the ordinary keyframe sampler (the segment easings ride along) and the
 * outline is rebuilt from the evaluated angles by [PieOutline]. Empty when the def's wrap rule
 * closes the wedge at that instant — the layer draws nothing then, exactly as in Alight.
 * A non-pie layer keeps its stored segments.
 */
fun TimelineClip.pieSegmentsAt(relMs: Long): List<PathSeg> {
    if (!hasPie) return pathSegments
    val s = pieStartKeys.valueAt(relMs).first
    val e = pieEndKeys.valueAt(relMs).first
    return PieOutline.normalisedSegments(s.toDouble(), e.toDouble())
}

/**
 * The layer as it looks at project time [timeMs]: every keyframed property is replaced by its
 * animated value. Layers without keyframes are returned as they are (no copy).
 * Preview, selection frame and export all draw from this.
 */
fun TimelineClip.at(timeMs: Long): TimelineClip {
    if (keyframes.isEmpty()) {
        return if (hasPie) copy(pathSegments = pieSegmentsAt(timeMs - startMs)) else this
    }
    val rel = timeMs - startMs
    var c = this
    for ((p, list) in keyframes) {
        if (list.isEmpty()) continue
        val (a, b) = if (p.isColor) list.colorValueAt(rel) else list.valueAt(rel)
        c = c.withProp(p, a, b)
    }
    if (c.hasPie) c = c.copy(pathSegments = c.pieSegmentsAt(rel))
    return c
}

/** Sets the value at [relMs]: replaces the keyframe the time sits on, otherwise inserts a new one. */
fun TimelineClip.withKeyframe(p: AnimProp, relMs: Long, a: Float, b: Float): TimelineClip {
    val list = keyframes[p].orEmpty()
    val idx = list.indexOnTime(relMs)
    val next = if (idx >= 0) {
        list.toMutableList().also { it[idx] = it[idx].copy(a = a, b = b) }
    } else {
        (list + Keyframe(relMs, a, b)).sortedBy { it.timeMs }
    }
    return copy(keyframes = keyframes + (p to next))
}

/**
 * Removes one keyframe. Removing the last one of a property ends its animation: the layer keeps
 * the value that keyframe had.
 */
fun TimelineClip.withoutKeyframe(p: AnimProp, index: Int): TimelineClip {
    val list = keyframes[p] ?: return this
    if (index !in list.indices) return this
    val removed = list[index]
    val next = list.filterIndexed { i, _ -> i != index }
    return if (next.isEmpty()) {
        copy(keyframes = keyframes - p).withProp(p, removed.a, removed.b)
    } else {
        copy(keyframes = keyframes + (p to next))
    }
}

fun TimelineClip.withEasing(p: AnimProp, index: Int, easing: Easing): TimelineClip {
    val list = keyframes[p] ?: return this
    if (index !in list.indices) return this
    val next = list.toMutableList().also { it[index] = it[index].copy(easing = easing) }
    return copy(keyframes = keyframes + (p to next))
}

/** Moves every keyframe by [deltaMs] (the layer start moved: left trim / split). */
fun TimelineClip.withShiftedKeyframes(deltaMs: Long): TimelineClip {
    if ((keyframes.isEmpty() && masks.isEmpty() && !hasPie) || deltaMs == 0L) return this
    return copy(
        keyframes = keyframes.mapValues { (_, l) -> l.map { it.copy(timeMs = it.timeMs + deltaMs) } },
        masks = masks.shiftedKeys(deltaMs),
        pieStartKeys = pieStartKeys.map { it.copy(timeMs = it.timeMs + deltaMs) },
        pieEndKeys = pieEndKeys.map { it.copy(timeMs = it.timeMs + deltaMs) }
    )
}

/** Stretches the keyframe times (speed change: the layer got longer / shorter). */
fun TimelineClip.withScaledKeyframes(factor: Float): TimelineClip {
    if ((keyframes.isEmpty() && masks.isEmpty() && !hasPie) || abs(factor - 1f) < 1e-4f) return this
    return copy(
        keyframes = keyframes.mapValues { (_, l) -> l.map { it.copy(timeMs = (it.timeMs * factor).toLong()) } },
        masks = masks.scaledKeys(factor),
        pieStartKeys = pieStartKeys.map { it.copy(timeMs = (it.timeMs * factor).toLong()) },
        pieEndKeys = pieEndKeys.map { it.copy(timeMs = (it.timeMs * factor).toLong()) }
    )
}

/** Every distinct keyframe time of the layer (relative), for the diamonds on the layer strip. */
fun TimelineClip.keyframeTimes(): List<Long> =
    (keyframes.values.flatten().map { it.timeMs } + masks.flatMap { m -> m.keys.map { it.timeMs } } +
        pieStartKeys.map { it.timeMs } + pieEndKeys.map { it.timeMs }).distinct().sorted()

// =====================================================================================
// Speed: time remapping
// =====================================================================================

/** Allowed layer speed range. Source time advances exactly `speed` x timeline time. */
const val MIN_SPEED = 0.1f
const val MAX_SPEED = 4f

/** Speed at [relMs] (ms from the layer's start): the speed keyframes if any, else the base speed. */
fun TimelineClip.speedAt(relMs: Long): Float {
    val list = keyframes[AnimProp.SPEED].orEmpty()
    return if (list.isEmpty()) speed else list.valueAt(relMs).first
}

/**
 * How far into the source the layer has played after [relMs] of its own time: the integral of the
 * speed curve (midpoint rule, 20 ms steps). Without speed keyframes this is simply relMs * speed.
 */
fun TimelineClip.sourceOffsetMs(relMs: Long): Long {
    if (relMs <= 0L) return 0L
    val list = keyframes[AnimProp.SPEED].orEmpty()
    if (list.isEmpty()) return (relMs * speed).toLong()
    val step = 20L
    var acc = 0.0
    var t = 0L
    while (t < relMs) {
        val dt = minOf(step, relMs - t)
        acc += list.valueAt(t + dt / 2).first.toDouble() * dt
        t += dt
    }
    return acc.toLong()
}

/**
 * Constant speed the export uses for a layer. Without speed keyframes it is [TimelineClip.speed].
 * With keyframes the export keeps the exact length and the exact start / end in the source, but
 * uses the average speed over the layer (Media3 can only change speed by a constant).
 */
val TimelineClip.exportSpeed: Float
    get() {
        if (keyframes[AnimProp.SPEED].orEmpty().isEmpty()) return speed
        val span = sourceOffsetMs(durationMs)
        return (span.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(MIN_SPEED, MAX_SPEED)
    }

