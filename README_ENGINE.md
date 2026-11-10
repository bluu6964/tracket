# After Motion – native engine: decoder pool (step 1) + OpenGL renderer (step 2)

Copy the `app/` folder of this zip over your project's `app/` folder (new files + 1 changed CMakeLists.txt).

## One change in app/build.gradle.kts
Register the CMake file always (not only when whisper.cpp exists):

```kotlin
// replace:  if (file("src/main/cpp/whisper.cpp/CMakeLists.txt").exists()) { externalNativeBuild { cmake { path = ... } } }
externalNativeBuild {
  cmake { path = file("src/main/cpp/CMakeLists.txt") }
}
```
The patched `cpp/CMakeLists.txt` builds `libam_engine.so` first; whisper is optional exactly as before
(missing whisper.cpp = warning, not an error).

## Files
- `cpp/engine/decoder_pool.{h,cpp}` – the pool (NDK AMediaCodec / AMediaExtractor).
- `cpp/engine/decoder_jni.cpp` – JNI bridge. `cpp/engine/CMakeLists.txt` – builds `libam_engine.so`.
- `java/.../engine/DecoderPool.kt` – Kotlin API.

## What it does
- Max N hardware decoders alive (default 3). Clip number N+1 evicts the least recently used clip's
  decoder; that clip re-creates it on its next use and continues at the right frame.
- Hardware decoder cannot be created/configured/keeps failing -> software decoder (`c2.android.*`).
- Bigger input buffers (`max-input-size` >= 1 MB), one retry, then software fallback, on mid-stream faults.
- `seek(clip, us)`: exact nearest frame; scrubbing forward < 0.75 s does not re-seek; asking for the frame
  already on screen costs nothing (even after eviction).
- `next(clip)`: sequential frames for export; survives eviction without skipping/duplicating a frame.

## Usage
```kotlin
val pool = DecoderPool(maxHardware = 3)
val clip = pool.open(context, uri, Surface(surfaceTexture))   // per layer: its own SurfaceTexture
// render/worker thread:
when (pool.seek(clip, timeUs)) {
    DecoderPool.OK -> { /* wait onFrameAvailable, then surfaceTexture.updateTexImage() */ }
    DecoderPool.BUSY -> { /* all slots busy, retry in a few ms */ }
    DecoderPool.END_OF_STREAM -> { /* keep last frame */ }
    else -> { /* clip failed */ }
}
```
Rules: one SurfaceTexture per clip; call `updateTexImage()` before asking for the next frame; never
call from the UI thread; `pool.close()` only when no call is running.

## Tested / not tested
Compiled and run on a PC against a fake of the NDK media API (AddressSanitizer, UBSan, ThreadSanitizer clean):
LRU eviction never exceeds the limit, exact seeks, sequential decode across evictions, software fallback,
6 threads on 2 decoder slots. NOT yet run on a real phone / with the real NDK. First on-device checks:
`pool.stats()` (evictions / fallbacks / errors) and logcat tag `AmDecoderPool`.

Known limits: frame duration comes from the file's frame rate (variable-frame-rate files can be off by
one frame when scrubbing); no HDR / tone mapping yet; the pool does not do audio.


---
# Step 2: EGL / OpenGL ES 3 renderer (preview AND export use the same code)

## New files
- `cpp/engine/egl_core.{h,cpp}` – EGL context (GLES3, "recordable" config for encoder surfaces, pbuffer bootstrap).
- `cpp/engine/renderer.{h,cpp}` – draws layers: external (decoder) or 2D textures, fit + offset + scale +
  rotation + opacity, premultiplied alpha blending. `cpp/engine/gl_math.h` – matrices.
- `cpp/engine/render_jni.cpp`, `java/.../engine/NativeRenderer.kt` (`NativeRenderer`, `GlThread`, `LayerParams`),
  `java/.../engine/VideoLayerSource.kt` (decoder clip -> SurfaceTexture -> texture, hand-shake included),
  `java/.../engine/EngineSelfTest.kt`.
- Changed: `cpp/engine/decoder_pool.*` – `seek` now returns `SAME` (1) when the frame is already on the
  surface (nothing to wait for), `OK` (0) when a new frame was queued.

## Frame loop (all on ONE GL thread)
```kotlin
val gl = GlThread()
gl.call {
    val r = NativeRenderer.create()!!
    r.setOutput(previewSurface)               // preview;  for export: r.setOutput(encoderInputSurface)
    val v = VideoLayerSource.open(context, uri, r, pool)!!
    // per frame:
    v.seek(timeUs)                            // decode + wait + updateTexImage
    r.beginFrame(0f, 0f, 0f, 1f)
    r.drawLayer(v.textureId, true, v.texMatrix, v.contentWidth, v.contentHeight, v.clip.rotation,
                LayerParams().apply { scaleX = 0.5f; scaleY = 0.5f; rotationDeg = 10f; opacity = 0.8f })
    r.endFrame(ptsNs)                         // ptsNs = frame time in ns for the encoder, -1 for preview
}
```
Layers are drawn bottom to top between `beginFrame` and `endFrame`. Canvas = output surface size.

## Test it on the phone FIRST (2 minutes)
Call from any debug button, off the UI thread, with a video Uri:
```kotlin
Thread { Log.i("AmSelfTest", EngineSelfTest.run(context, videoUri)) }.start()
```
It checks: renderer starts, a real frame comes out as pixels (non-black %, brightness), and the situation
that broke your export - SIX layers of one file on 3 hardware decoders - with pool stats
(evictions / fallbacks / errors). Send me the log lines (tag `AmSelfTest`, plus `AmDecoderPool`, `AmEgl`, `AmRenderer`).

## Tested here / not tested
- PC: all matrix / layout math (fit, offset, scale, 90 deg rotation, video-rotation texture coordinates),
  decoder pool logic (unchanged result after the SAME change; ASan/UBSan/TSan clean).
- Compiled against stub EGL/GLES headers only (syntax + types). NOT run with real OpenGL, and NOT built with
  the real NDK. Shaders are compiled for the first time on your phone - a compile/link error shows up in
  logcat under `AmRenderer`. Video rotation (portrait phone videos) is the most likely place for an
  upside-down / sideways picture on the first run; tell me what you see.

## Not in this step
Encoder (surface -> H.264 -> MP4), audio, effects / blend modes (the renderer is built for them: one
shader pass per effect is the next addition), text / shape layers.
