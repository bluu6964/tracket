package com.example.model

import kotlin.math.abs

/** All Transform properties of a layer at one moment (2D + Z + Pivot + 3D + Warp). */
data class TransformValues(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val scalePercent: Float = 100f,
    val rotationDeg: Float = 0f,
    val pivotX: Float = 0.5f,
    val pivotY: Float = 0.5f,
    val rot3dX: Float = 0f,
    val rot3dY: Float = 0f,
    val warp: Float = 0f
) {
    companion object {
        val Identity = TransformValues()
    }
}

/**
 * Speed curve of the segment that starts at a keyframe and ends at the next one.
 * Bezier control points (x1, y1, x2, y2) like CSS cubic-bezier; [isHold] keeps the value until the next keyframe.
 * Presets live in the companion; any custom (x1,y1,x2,y2) is a valid easing.
 */
data class KeyframeEasing(
    val x1: Float = 0f,
    val y1: Float = 0f,
    val x2: Float = 1f,
    val y2: Float = 1f,
    val isHold: Boolean = false,
    val label: String = "Custom"
) {
    /** Maps linear progress 0..1 to eased progress 0..1. */
    fun apply(t: Float): Float {
        val p = t.coerceIn(0f, 1f)
        if (isHold) return if (p >= 1f) 1f else 0f
        if (x1 == 0f && y1 == 0f && x2 == 1f && y2 == 1f) return p
        // Solve x(s) = p for s by bisection, then return y(s).
        var lo = 0f
        var hi = 1f
        var s = p
        repeat(24) {
            s = (lo + hi) / 2f
            if (bezier(s, x1, x2) < p) lo = s else hi = s
        }
        return bezier(s, y1, y2)
    }

    /** Cycles through built-in presets (used by the small easing icon tap). */
    fun next(): KeyframeEasing {
        val idx = PRESETS.indexOfFirst { it.sameCurve(this) }
        return if (idx < 0) LINEAR else PRESETS[(idx + 1) % PRESETS.size]
    }

    fun sameCurve(other: KeyframeEasing): Boolean =
        isHold == other.isHold &&
            abs(x1 - other.x1) < 0.001f && abs(y1 - other.y1) < 0.001f &&
            abs(x2 - other.x2) < 0.001f && abs(y2 - other.y2) < 0.001f

    companion object {
        val LINEAR = KeyframeEasing(0f, 0f, 1f, 1f, label = "Linear")
        val EASE_IN_OUT = KeyframeEasing(0.42f, 0f, 0.58f, 1f, label = "Ease in-out")
        val EASE_IN = KeyframeEasing(0.42f, 0f, 1f, 1f, label = "Ease in")
        val EASE_OUT = KeyframeEasing(0f, 0f, 0.58f, 1f, label = "Ease out")
        val HOLD = KeyframeEasing(0f, 0f, 1f, 1f, isHold = true, label = "Hold")
        /** Soft ease-in (bottom-left heavy). */
        val EASE_IN_SOFT = KeyframeEasing(0.55f, 0f, 1f, 1f, label = "Ease in soft")
        /** Soft ease-out (top-right heavy). */
        val EASE_OUT_SOFT = KeyframeEasing(0f, 0f, 0.45f, 1f, label = "Ease out soft")
        /** Strong S-curve. */
        val EASE_IN_OUT_STRONG = KeyframeEasing(0.7f, 0f, 0.3f, 1f, label = "Ease in-out strong")

        val PRESETS: List<KeyframeEasing> = listOf(
            LINEAR, EASE_IN_OUT, EASE_IN, EASE_OUT, HOLD
        )

        /** Side-panel presets shown in the cubic-bezier editor (matches Alight Motion layout). */
        val EDITOR_PRESETS: List<KeyframeEasing> = listOf(
            LINEAR,
            EASE_IN_SOFT,
            EASE_OUT_SOFT,
            EASE_IN_OUT,
            EASE_IN_OUT_STRONG,
            HOLD
        )

        private fun bezier(s: Float, c1: Float, c2: Float): Float {
            val u = 1f - s
            return 3f * u * u * s * c1 + 3f * u * s * s * c2 + s * s * s
        }
    }

    private fun bezier(s: Float, c1: Float, c2: Float): Float {
        val u = 1f - s
        return 3f * u * u * s * c1 + 3f * u * s * s * c2 + s * s * s
    }
}

/** One keyframe. [timeMs] is measured from the layer's start (so moving the layer moves its keyframes). */
data class TransformKeyframe(
    val timeMs: Long,
    val values: TransformValues,
    val easing: KeyframeEasing = KeyframeEasing.LINEAR
)

/** Two keyframes closer than this (ms) count as the same moment. */
const val KEYFRAME_TOLERANCE_MS = 20L

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun lerp(a: TransformValues, b: TransformValues, t: Float) = TransformValues(
    x = lerp(a.x, b.x, t),
    y = lerp(a.y, b.y, t),
    z = lerp(a.z, b.z, t),
    scalePercent = lerp(a.scalePercent, b.scalePercent, t),
    rotationDeg = lerp(a.rotationDeg, b.rotationDeg, t),
    pivotX = lerp(a.pivotX, b.pivotX, t),
    pivotY = lerp(a.pivotY, b.pivotY, t),
    rot3dX = lerp(a.rot3dX, b.rot3dX, t),
    rot3dY = lerp(a.rot3dY, b.rot3dY, t),
    warp = lerp(a.warp, b.warp, t)
)

/** The layer's own stored Transform (used when it has no keyframes). */
fun TimelineClip.staticTransform() = TransformValues(
    x = transformX,
    y = transformY,
    z = transformZ,
    scalePercent = scalePercent,
    rotationDeg = rotationDeg,
    pivotX = pivotX,
    pivotY = pivotY,
    rot3dX = rotation3dX,
    rot3dY = rotation3dY,
    warp = warpDistort
)

/** Copy of the layer with [v] stored as its fixed Transform (keeps shape mirrors in sync). */
fun TimelineClip.withStaticTransform(v: TransformValues) = copy(
    transformX = v.x,
    transformY = v.y,
    transformZ = v.z,
    scalePercent = v.scalePercent,
    rotationDeg = v.rotationDeg,
    pivotX = v.pivotX,
    pivotY = v.pivotY,
    rotation3dX = v.rot3dX,
    rotation3dY = v.rot3dY,
    warpDistort = v.warp,
    shapeOffsetX = v.x,
    shapeOffsetY = v.y,
    shapeScaleX = v.scalePercent / 100f,
    shapeScaleY = v.scalePercent / 100f
)

/** Transform of the layer at timeline time [absTimeMs]: interpolated between keyframes, or the fixed values. */
fun TimelineClip.transformAt(absTimeMs: Long): TransformValues {
    val ks = keyframes
    if (ks.isEmpty()) return staticTransform()
    val t = absTimeMs - startMs
    if (t <= ks.first().timeMs) return ks.first().values
    if (t >= ks.last().timeMs) return ks.last().values
    val i = ks.indexOfLast { it.timeMs <= t }
    val a = ks[i]
    val b = ks[i + 1]
    val span = (b.timeMs - a.timeMs).toFloat().coerceAtLeast(1f)
    val progress = a.easing.apply((t - a.timeMs) / span)
    return lerp(a.values, b.values, progress)
}

/** Index of the keyframe at timeline time [absTimeMs] (within [KEYFRAME_TOLERANCE_MS]), or -1. */
fun TimelineClip.keyframeIndexAt(absTimeMs: Long): Int {
    val t = absTimeMs - startMs
    var best = -1
    var bestDist = Long.MAX_VALUE
    keyframes.forEachIndexed { i, k ->
        val d = abs(k.timeMs - t)
        if (d <= KEYFRAME_TOLERANCE_MS && d < bestDist) {
            best = i
            bestDist = d
        }
    }
    return best
}

/** Adds a keyframe at [absTimeMs] with [values], or updates the one already there (its easing is kept). */
fun TimelineClip.withKeyframeAt(absTimeMs: Long, values: TransformValues): TimelineClip {
    val idx = keyframeIndexAt(absTimeMs)
    if (idx >= 0) {
        return copy(keyframes = keyframes.mapIndexed { i, k -> if (i == idx) k.copy(values = values) else k })
    }
    val t = (absTimeMs - startMs).coerceIn(0L, durationMs.coerceAtLeast(0L))
    val added = (keyframes + TransformKeyframe(timeMs = t, values = values)).sortedBy { it.timeMs }
    return copy(keyframes = added)
}

/** Removes the keyframe at [absTimeMs]. If it was the last one, its values become the fixed Transform. */
fun TimelineClip.withoutKeyframeAt(absTimeMs: Long): TimelineClip {
    val idx = keyframeIndexAt(absTimeMs)
    if (idx < 0) return this
    val removed = keyframes[idx]
    val rest = keyframes.filterIndexed { i, _ -> i != idx }
    return if (rest.isEmpty()) {
        copy(keyframes = emptyList()).withStaticTransform(removed.values)
    } else {
        copy(keyframes = rest)
    }
}

/** Switches the speed curve of the keyframe at [absTimeMs] to the next preset. */
fun TimelineClip.withNextEasingAt(absTimeMs: Long): TimelineClip {
    val idx = keyframeIndexAt(absTimeMs)
    if (idx < 0) return this
    return copy(keyframes = keyframes.mapIndexed { i, k -> if (i == idx) k.copy(easing = k.easing.next()) else k })
}

/** Sets a custom (or preset) easing on the keyframe at [absTimeMs]. */
fun TimelineClip.withEasingAt(absTimeMs: Long, easing: KeyframeEasing): TimelineClip {
    val idx = keyframeIndexAt(absTimeMs)
    if (idx < 0) return this
    return copy(keyframes = keyframes.mapIndexed { i, k -> if (i == idx) k.copy(easing = easing) else k })
}

/**
 * Keeps keyframes on the same picture content when the layer's START moves by [deltaMs]
 * without the content moving (trim from the left: deltaMs > 0 cuts, < 0 extends).
 */
fun TimelineClip.withKeyframesShiftedForStart(deltaMs: Long): TimelineClip {
    if (keyframes.isEmpty() || deltaMs == 0L) return this
    return copy(keyframes = keyframes.map { it.copy(timeMs = it.timeMs - deltaMs) })
}

/**
 * Splits the keyframes at [splitAbsMs] into (left half, right half).
 * Both halves get a keyframe on the cut with the exact value there, so the motion does not jump.
 */
fun TimelineClip.splitKeyframes(splitAbsMs: Long): Pair<List<TransformKeyframe>, List<TransformKeyframe>> {
    if (keyframes.isEmpty()) return emptyList<TransformKeyframe>() to emptyList()
    val cut = splitAbsMs - startMs
    val atCut = transformAt(splitAbsMs)
    val prevEasing = keyframes.lastOrNull { it.timeMs <= cut }?.easing ?: KeyframeEasing.LINEAR

    val left = keyframes.filter { it.timeMs < cut - KEYFRAME_TOLERANCE_MS } +
        TransformKeyframe(timeMs = cut, values = atCut)
    val right = listOf(TransformKeyframe(timeMs = 0L, values = atCut, easing = prevEasing)) +
        keyframes.filter { it.timeMs > cut + KEYFRAME_TOLERANCE_MS }.map { it.copy(timeMs = it.timeMs - cut) }
    return left to right
}
