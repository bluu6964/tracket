package com.example.engine

import com.example.model.AnimProp
import com.example.model.TimelineClip
import com.example.model.renderAt
import com.example.model.unpackColor

/**
 * Preview version of [renderAt] (video, photo, text, shape and sticker layers): the keyframe + tracking maths runs in C++ (cpp/engine/layer_anim.cpp, the SAME
 * code the export uses), so the preview and the export can never drift apart.
 *
 * One native handle per layer is kept; it is rebuilt when the layer object changes (every edit makes a new
 * TimelineClip). Layers without keyframes / tracking are returned as they are, no native call.
 * Falls back to the Kotlin [renderAt] when the native library is missing.
 */
internal object NativeRenderAt {
    private class Entry(val clip: TimelineClip, val anim: NativeLayerAnim)

    private val cache = HashMap<String, Entry>()
    private const val MAX_ENTRIES = 48

    private fun animOf(c: TimelineClip): NativeLayerAnim? = synchronized(cache) {
        val e = cache[c.id]
        if (e != null && e.clip === c) return e.anim
        e?.anim?.close()
        cache.remove(c.id)
        if (cache.size >= MAX_ENTRIES) clearLocked()
        val a = NativeLayerAnim.create(c) ?: return null
        cache[c.id] = Entry(c, a)
        a
    }

    private fun clearLocked() {
        cache.values.forEach { it.anim.close() }
        cache.clear()
    }

    /** Frees every native handle (project closed). */
    fun clear() = synchronized(cache) { clearLocked() }

    /** Does the same as [renderAt]; [trackMs] = project time of the video picture really on screen. */
    fun renderAt(c: TimelineClip, timeMs: Long, trackMs: Long = timeMs): TimelineClip {
        var animated = c.trackFollow != null
        for ((_, l) in c.keyframes) {
            if (l.isEmpty()) continue
            animated = true
        }
        if (!animated) return c
        synchronized(cache) {
            val a = animOf(c) ?: return c.renderAt(timeMs, trackMs)
            val v = a.clipAt(timeMs, trackMs)
            val kf = c.keyframes
            fun col(p: AnimProp, i: Int, old: Long): Long =
                if (kf[p].isNullOrEmpty()) old else unpackColor(v[i], v[i + 1])
            // withProp also moves the shape fields, but only for a keyframed / tracked property.
            val moved = c.trackFollow != null || !kf[AnimProp.POSITION].isNullOrEmpty()
            val scaled = !kf[AnimProp.SCALE].isNullOrEmpty()
            // tracked zoom: shapes are sized by shapeScale, so it follows the layer's own size ratio
            val trackScaled = c.trackFollow?.scale != null && c.scalePercent > 0.01f
            return c.copy(
                transformX = v[0], transformY = v[1],
                shapeOffsetX = if (moved) v[0] else c.shapeOffsetX, shapeOffsetY = if (moved) v[1] else c.shapeOffsetY,
                transformZ = v[2],
                scalePercent = v[3],
                shapeScaleX = if (scaled) v[3] / 100f else if (trackScaled) c.shapeScaleX * v[3] / c.scalePercent else c.shapeScaleX,
                // A SCALE track's b (v[21]) is a shape's separate height scale; 0 = uniform, as before.
                shapeScaleY = if (scaled) (if (v[21] > 0f) v[21] / 100f else v[3] / 100f) else if (trackScaled) c.shapeScaleY * v[3] / c.scalePercent else c.shapeScaleY,
                rotationDeg = v[4],
                cropWidthPercent = v[5], cropHeightPercent = v[6],
                cropHorizontalPercent = v[7], cropVerticalPercent = v[8],
                opacity = v[9],
                speed = if (kf[AnimProp.SPEED].isNullOrEmpty()) c.speed else v[10],
                rotation3dX = v[11], rotation3dY = v[12],
                textColor = col(AnimProp.FILL_COLOR, 13, c.textColor),
                gradColor1 = col(AnimProp.GRAD_COLOR_1, 15, c.gradColor1),
                gradColor2 = col(AnimProp.GRAD_COLOR_2, 17, c.gradColor2),
                strokeTrimStart = if (kf[AnimProp.STROKE_TRIM].isNullOrEmpty()) c.strokeTrimStart else v[19],
                strokeTrimEnd = if (kf[AnimProp.STROKE_TRIM].isNullOrEmpty()) c.strokeTrimEnd else v[20]
            )
        }
    }
}
