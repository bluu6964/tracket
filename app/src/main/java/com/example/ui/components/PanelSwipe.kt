package com.example.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

/**
 * Swipe a bottom panel (Transform / Color / Blending / Text / Audio / Crop ...) downwards with a
 * finger to close it (= back).
 *
 * It never fights the panel's own controls:
 *  - sliders, pads, drag handles, horizontal strips... consume their own drags, so those gestures
 *    are ignored here (the pointer is watched in the Final pass, after the children had their go);
 *  - a vertically scrolling list inside the panel scrolls first; only the leftover downward drag
 *    (list already at its top) closes the panel (nested scroll).
 *
 * [ignoreAbove]: touches that start higher than this (measured from this modifier's top edge) are
 * ignored, e.g. the crop canvas that sits above the Crop tools.
 */
fun Modifier.swipeDownToDismiss(
    enabled: Boolean = true,
    ignoreAbove: Dp = 0.dp,
    onDismiss: () -> Unit
): Modifier = composed {
    val latest by rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
    val thresholdPx = with(density) { 64.dp.toPx() }
    val ignorePx = with(density) { ignoreAbove.toPx() }

    val connection = androidx.compose.runtime.remember(thresholdPx) {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y > 0f && abs(available.y) > abs(available.x)) {
                    acc += available.y
                    if (acc >= thresholdPx) {
                        acc = 0f
                        latest()
                    }
                } else if (available.y < 0f) {
                    acc = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                acc = 0f
                return Velocity.Zero
            }
        }
    }

    if (!enabled) return@composed this

    this
        .nestedScroll(connection)
        .pointerInput(thresholdPx, ignorePx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.position.y < ignorePx) return@awaitEachGesture
                var dx = 0f
                var dy = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.changes.size != 1) return@awaitEachGesture
                    val change = event.changes[0]
                    if (!change.pressed) return@awaitEachGesture
                    // A child (slider, pad, strip, scroll list...) took this gesture: not ours.
                    if (change.isConsumed) return@awaitEachGesture
                    val d = change.positionChange()
                    dx += d.x
                    dy += d.y
                    if (dy < -thresholdPx / 4f) return@awaitEachGesture   // going up: not a dismiss
                    if (abs(dx) > thresholdPx / 2f && abs(dx) > abs(dy)) return@awaitEachGesture
                    if (dy >= thresholdPx && dy > abs(dx) * 2f) {
                        change.consume()
                        latest()
                        return@awaitEachGesture
                    }
                }
            }
        }
}
