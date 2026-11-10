package com.example.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.engine.NativePreviewEngine
import com.example.model.TimelineTrack

/**
 * The surface the C++ preview draws on: video / photo layers, text, shapes and stickers. Compose only adds the touch
 * boxes, the selection frame and, for an emoji on a phone without a colour emoji font, that one text layer.
 */
@Composable
internal fun NativePreviewSurface(
    engine: NativePreviewEngine,
    tracks: List<TimelineTrack>,
    playheadMs: () -> Long,
    isPlaying: Boolean,
    canvasArgb: Int,
    modifier: Modifier = Modifier
) {
    SideEffect {
        engine.update(tracks, playheadMs, canvasArgb)
        engine.setPlaying(isPlaying)
        engine.requestFrame()
    }
    EngineSurface(engine, remember { Any() }, modifier) { engine.captureView = java.lang.ref.WeakReference(it) }
}

/**
 * A full-screen editor (Crop, Edit shape) showing [tracks] through the same C++ engine as the canvas, on its own
 * surface: the engine draws on the newest surface and goes back to the canvas one when this leaves.
 */
@Composable
internal fun NativeEditorSurface(
    engine: NativePreviewEngine,
    tracks: List<TimelineTrack>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    DisposableEffect(engine) {
        onDispose {
            engine.setOverride(null)
            engine.requestFrame()
        }
    }
    SideEffect {
        engine.setOverride(tracks)
        engine.setPlaying(isPlaying)
        engine.requestFrame()
    }
    EngineSurface(engine, remember { Any() }, modifier)
}

@Composable
private fun EngineSurface(engine: NativePreviewEngine, owner: Any, modifier: Modifier, onView: (SurfaceView) -> Unit = {}) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                onView(this)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {}
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        engine.surfaceChanged(holder.surface, owner)
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        engine.surfaceDestroyed(owner)
                    }
                })
            }
        }
    )
}
