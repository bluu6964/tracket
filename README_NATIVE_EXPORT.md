# Native export migration status — Stage 2

## Added in this stage

- Native H.264 surface encoder and MP4 muxer based on NDK `MediaCodec` / `MediaMuxer`.
- `NativeExportEncoder.submitFrame(ptsNs)` allows the existing GLES renderer to compose arbitrary texture layers and submit them directly to the encoder surface.
- Monotonic presentation-timestamp validation and cleanup fix so a successfully finalized MP4 is not deleted when the JNI session is destroyed.
- `NativeTimelineVideoExporter` is an **experimental video-only path** that schedules visible video clips on the timeline, seeks/decodes each active layer through `DecoderPool` + `VideoLayerSource`, composites them in GLES, and writes H.264/MP4. It supports basic position, scale, rotation, opacity and flips.

## Deliberately not switched on by default

`VideoExporter.kt` remains the production exporter. The experimental native timeline path rejects projects containing audio, still images, text, shapes, stickers or other non-video layers instead of silently dropping them. Keyframed transforms/opacity, crop, blend modes, speed effects beyond basic source-time seeking, cutout, face blur, and audio mixing/fades are not yet feature-parity implemented.

Do not expect or advertise a multi-fold speedup yet: performance depends on decoder scheduling, surface synchronization, device hardware and effects. Benchmark identical clips on-device before switching the default exporter.

## Threading and API

Run `NativeTimelineVideoExporter.exportVideoOnly` on a worker thread. The GL/decoder operations are marshalled onto its dedicated `GlThread`. Native surface encoding/muxing requires Android API 26+; the existing app can still target API 24 because the muxer/surface-input functions are resolved dynamically.

## Verification status

`git diff --check` is not applicable because this extracted project has no `.git` directory. This workspace has no Gradle wrapper script and no Android SDK/NDK configured, so Android compilation and device playback tests could not be run here. Before production use, build with Android SDK/NDK, export a test clip on an API 26+ ARM64 device, verify the MP4 with `MediaMetadataRetriever`/`MediaExtractor`, and compare frame count, duration, visual layers and audio sync against Media3 export.


## Build-log fixes (2026-10-19)

- Fixed `NativeTimelineVideoExporter.exportVideoOnly()` to return the result of the `GlThread.use` block, addressing Kotlin's “Missing return statement” error.
- Removed globally forced `-march=armv8.2-a+dotprod+fp16` and `GGML_CPU_ARM_ARCH` overrides from CMake. The native build now targets the portable Android `arm64-v8a` baseline rather than assuming dot-product/FP16 support or passing ARM flags into host compiler probes.
- These are source-level fixes; an Android SDK/NDK build still needs to run in GitHub Actions to confirm compilation end-to-end.

## Step 1 of "everything in C++": cutout + face blur (v100)

* `engine/jpeg_luma.cpp` - dependency-free baseline-JPEG decoder that returns only the luma plane
  (the masks are grey JPEGs, so Y == mask value). Progressive JPEG is rejected on purpose.
  Host test: `tests/jpeg_luma_selftest.cpp`.
* `engine/renderer_fx.cpp` - `Renderer::loadMask()` (R8 texture per mask slot) and `Renderer::applyFx()`:
  upright copy of the decoded frame -> face blur (4x4 box reduce, 13-tap Gaussian H+V on the small
  picture, mixed through the mask) -> cutout (mask x alpha, or flattened onto the canvas colour for the
  lowest layer). Same shaders / numbers as the old Media3 `FaceBlurEffect` / `CutoutMaskEffect`.
* `NativeProjectExporter.unsupportedReason()` no longer rejects cutout / face blur. If a mask cannot be
  decoded the export throws and `VideoExporter` still falls back to Media3.
* Still in Kotlin (next steps): photo / sticker loading, shapes, text, the frame loop, keyframes.

## Step 2 of "everything in C++": keyframes + tracking + layer pose (v102)

* `engine/layer_anim.{h,cpp}` - port of `Keyframes.kt` (Easing, valueAt, sourceOffsetMs, exportSpeed),
  `MotionTrackModel.kt` (TrackFollow.indexAt, renderAt) and `LayerTransform.kt` (layerPose) + the old
  `layerParams()`. Pure C++, no GL. `engine/anim_jni.cpp` + `engine/NativeLayerAnim.kt` (Kotlin) hand one clip over
  once; the frame loop then asks `eval(timeMs)` for offset / scale / rotation / opacity / crop and `sourceMs(timeMs)`.
* Host test (PC, 1879 checks, ASan/UBSan clean): `tests/layer_anim_selftest.cpp` - same numbers as `KeyframesTest.kt`
  plus pose, depth scale, flips, tracking index (2 ms tolerance) and a bezier cross-check against a double bisection.
* **Behaviour change:** crop keyframes (CROP_SIZE / CROP_POS) are now animated in the native export, like the preview.
  The old native code used the static crop of the clip.
* Every 30th frame the old Kotlin maths runs too; a difference > 0.01 is logged: logcat tag
  `NativeProjectExporter`, text `anim parity MISMATCH`. Remove `checkAnimParity` once a phone run shows none.
* Not built here (no NDK / kotlinc): GitHub Actions has to compile it. The JNI file was only syntax-checked
  against a stub `jni.h`.
* Still not exported by either exporter: `rotation3dX/Y`, `warpDistort`.

## v103: muxer fix
`AMediaMuxer_new` takes an `int fd`, but `NativeExportEncoder` passed the file PATH (resolved by dlsym with a wrong
`const char*` signature) -> the muxer was created on a garbage fd and `AMediaMuxer_start` failed ("Could not start MP4
muxer"), so every native export fell back to Media3. Now the output file is opened (O_RDWR) and the fd is passed and
closed after the muxer is deleted. The fallback toast now starts with the reason ("Native failed: ...") so the
2-line toast no longer cuts it off.

## v104: photo loading in C++
* `engine/image_loader.{h,cpp}`: NDK `AImageDecoder` (libjnigraphics, resolved with dlsym, API 30+). JPEG / PNG / WebP /
  HEIF, EXIF orientation applied, long side limited to 2560, RGBA8888 premultiplied (same as a Bitmap).
  `render_jni.cpp: nativeLoadImage` uploads the pixels as a GL_TEXTURE_2D. Kotlin: `NativeRenderer.loadPhoto(fd, maxSide)`.
* `NativeProjectExporter`: Android 11+ tries the C++ decoder first; on API < 30 or any failure the old Kotlin path
  (ImageDecoder / BitmapFactory + GLUtils) runs, so nothing gets worse. (stb_image was not an option: no network here,
  and a home-made decoder would not read HEIC / WebP.)
* Test: `tests/image_loader_selftest.cpp` covers only the size maths; the decoder itself needs a phone.
* Check on a phone: a photo layer (try one portrait photo from the camera, EXIF-rotated) must look the same as before.

## v105: export plan in C++, clean-up
* `engine/export_plan.{h,cpp}` + `export_plan_jni.cpp` + Kotlin `ExportPlanNative.kt`: frame count, video bitrate and the
  choice of clips that go into the sound mix (visible / has file / not muted / lane mute only for audio layers / photos
  have no sound / at least 100 ms inside the video; audio layers keep volume + fades, video layers volume 1).
  Host test `tests/export_plan_selftest.cpp` (22 checks).
* Removed: the Kotlin `layerParams`, the 30-frame parity log (`checkAnimParity`) and `bitsPerPixel`.
* Deliberately NOT moved: the frame loop itself. JNI calls per frame cost microseconds next to decode / GL work, and the
  loop still needs Android parts (text / shape / sticker via Canvas, ContentResolver, the clip model).

## v106: speed
* Measured on a phone: Media3 ~10 s, native minutes. Found in the code: the native encoder waited the full 10 ms for
  "no more output" after EVERY video frame and after every 1024-sample audio chunk (`drain` with a 10 ms timeout), i.e.
  >= 25 ms idle per frame. Now `drain` takes only what is ready (timeout 0) while running; the end of the stream still waits.
* After an export two short toasts (and a logcat line `Native export ...`) show the time per phase: decode, overlays, encode,
  audio, finish, plus decoder evictions / software fallbacks / errors. If it is still slow, send that line.

## v109: audio speed
* Measured (31 s, 1080p30): total 32.6 s = decode 7.1 + encode 7.4 + audio 15.5 (+ overlays 0, finish 0.2). Audio was the
  biggest part: `AudioFileSource::decodeMore` queued ONE compressed packet and then waited up to 5 ms for its output,
  ~1450 times for 31 s of AAC. Now it queues every free input buffer, takes everything that is ready and waits only while
  nothing has come out yet.
* New counters (`prof.h`, `NativeExportEncoder.profNs`): a third toast shows `audio: decode | encode | rest`.
* If audio is still the bottleneck: run the audio pump on its own thread (needs a mutex around the muxer).

## v110: "export, not live" hints
* Video encoder and video decoder are first configured with `priority = 1` (best effort, not real time) and
  `operating-rate = 32767` (as fast as the chip can). Bitrate, profile and I-frame interval are unchanged, so the picture
  quality is the same. If a chip rejects them, the codec is configured again without (logcat: "retrying without speed hints").
* Next possible step: audio pump on its own thread (overlaps with the video work).

## v117: right-to-left text in C++ (step 5, part 4)
* Levels come from Kotlin: `bidiLevelsOf(text)` (TextNative.kt) runs `java.text.Bidi` per paragraph (direction = first strong letter, else LTR, like Android's default) and hands the level of every code point + one RTL flag per paragraph to `nativeDraw`. The engine does no Unicode bidi tables of its own.
* `text_engine.cpp`: runs are split by font AND level; HarfBuzz direction = level parity (it mirrors brackets and joins Arabic); an RTL run's clusters are put back in logical order, the line breaker works on logical order, then `visualOrder` (UAX #9 rule L2) reorders each line; trailing spaces stay at the line end. In an RTL paragraph start / end alignment are swapped (Android ALIGN_NORMAL / OPPOSITE).
* Without levels a right-to-left letter still makes `draw` return false (Canvas), and so does a level array of the wrong length. `nativeTextEligible` no longer refuses RTL. Emoji (and other colour symbols) still go to Canvas.
* Host test: 70 checks (16 new, `testRtl`: mirrored order, Arabic shaping, mixed LTR/RTL line, two paragraphs with wrapping), ASan/UBSan clean. Android compile: GitHub Actions.
* Letter spacing is applied to Arabic too (it can break joining visually): keep it at 0 for Arabic.
* Check on a phone: Arabic (e.g. سلام), Hebrew, mixed Bengali/Arabic/English with digits and brackets; preview must equal export; right / left alignment buttons in an RTL paragraph.

## v116: gradient + picture fill for text in C++ (step 5, part 3)
* `text_engine.cpp: compositeFill` colours every covered pixel from the position in the text block (maxTextW wide, all lines high, same origin as Android's shader in `LayerPainter.drawText`): linear / radial / sweep gradient (`gradientAt`, same maths as `buildGradientShader`) and picture fill FILL / FIT / STRETCH (`pictureAt`, bilinear). Outline, shadow, underline and strike use it too.
* JNI: `nativeDraw(..., p[37], c[6], fillBitmap)`; Kotlin `NativeTextPainter` no longer refuses gradient / image fill. Export waits for the picture (`FillImages.loadSync`); the preview uses `FillImages.peek` and redraws ~300 ms later when the picture has arrived.
* `nativeTextEligible` accepts gradient / image fill now. Still Canvas: right-to-left scripts and emoji.
* Host test: 54 checks (9 new, `testFill`), ASan/UBSan clean. Android compile: GitHub Actions.
* Check on a phone: text with linear / radial / sweep gradient and with a picture fill (FILL, FIT, STRETCH) must look the same in the preview, in the export and as before; gradient handles still drag.

## v115: text in the C++ preview (step 5, part 2)
* `NativePreviewEngine.drawTextLayers`: text layers are drawn by `NativeTextPainter` (same C++ code as the export) into one frame-sized bitmap -> one GL texture above all pictures; redrawn only when the layers or fonts change.
* `nativeTextEligible(clip)` (TextNative.kt) decides per layer: solid/no fill, no RTL, no emoji, Android 10+, engine built. Eligible layers: Compose text is hidden (`hideContent`, alpha 0) and only keeps size, selection, drag and tap; others stay Compose.
* Known difference: shapes and stickers are still Compose, so they always sit above native text (the export draws them in lane order).
* Check on a phone: native preview ON, text with a Google font and Bengali conjuncts must look like the export; drag, double-tap edit and keyframed text must still work.

## v114: text layers in C++ (step 5, first part)
* New: `engine/text_engine.{h,cpp}` (HarfBuzz shaping + FreeType glyphs + line breaking + outline / shadow / background box +
  underline / strike), `text_blit.{h,cpp}` (affine blit onto the overlay bitmap, alpha blur), `text_layout.{h,cpp}` (wrapping,
  alignment, Android's extra line spacing), `text_jni.cpp`, Kotlin `engine/TextNative.kt` (`NativeTextPainter`).
* It draws straight into the overlay bitmap (`AndroidBitmap_lockPixels`), in the same order as the Canvas painter, so shapes /
  stickers / blend groups keep working. `LayerPainter.draw` asks `NativeTextPainter` first; if it answers false the old Canvas
  code runs. Nothing can get worse: gradient / image fill, right-to-left text, emoji, Android < 10 or a missing font -> Canvas.
* **Fixes a mismatch:** the Canvas export always used the system font; the preview uses the layer's chosen font. The native
  text uses the chosen font file (downloaded Google font / user font), system fonts through `AFontMatcher` (Android 10+),
  and the system fallback chain for missing glyphs (Bengali letters in a Latin-only font, etc.). Variable system fonts: axes are applied.
* Sources: FreeType (VER-2-13-3) and HarfBuzz (10.1.0) are cloned by `.github/workflows/build.yml` into `cpp/freetype` and
  `cpp/harfbuzz` (like whisper.cpp). Without them the engine builds as a stub and `TextNative.isAvailable` is false.
* Host test (45 checks, ASan/UBSan clean, real FreeType + HarfBuzz, Latin + Bengali): see the header of `tests/text_selftest.cpp`.
  NOT done here: Android compile (no NDK) - GitHub Actions has to build it; matcher (`AFontMatcher`) only runs on a phone.
* Still Canvas / Kotlin: gradient and image fill on text, shapes, stickers. Preview text is still Compose.
* Check on a phone: text with a Google font (e.g. "Hind Siliguri") must now look like the preview in the exported video;
  Bengali conjuncts (ক্ষ, স্ত্র), outline, shadow, background box, rotation, crop, keyframed text.

## v119: shapes and stickers in C++ (Canvas-free export, step 1)
* `engine/shape_engine.{h,cpp}` + `shape_jni.cpp` + Kotlin `ShapeNative.kt` (`NativeShapePainter`). Pure C++ (no FreeType / HarfBuzz, always built).
  Shapes (circle, rectangle, rounded rectangle, oval, triangle) are rasterised per pixel from a signed distance (1 px anti-aliasing): same
  box (65 dp * k), same order (translate, rotate, scale, flip), solid / gradient (linear, radial, sweep) / picture fill (fill, fit, stretch),
  stroke (scales with the layer; mitered corners like Paint.Join.MITER), crop clip and layer opacity as `LayerPainter.drawShape`.
  Stickers: bilinear affine blit (`text::blit`, new `clampEdges` option = edge pixel repeated like an Android bitmap) of the frame from
  `StickerLibrary.frameFor`.
* `LayerPainter.draw` asks `nativeShape` first and falls back to the Canvas code when it answers false (library missing, bitmap not
  ARGB_8888). `NativeProjectExporter` switches it on when `ShapeNative.isAvailable`. The Media3 exporter (`BlendEffect`) has no native painter,
  so it still draws with Canvas.
* Known small differences to Canvas: the edge anti-aliasing is a linear distance ramp (not exact area coverage); a rotated sticker has hard
  edges where the picture itself ends (stickers normally have transparent borders); the oval outline is a first-order distance estimate.
* Host test: `tests/shape_selftest.cpp`, 38 checks (areas of all five shapes, rotation, flip, scale, ring areas, mitered stroke, opacity,
  crop, gradient, picture fill, sticker position / flip / rotation / opacity), ASan/UBSan clean. The JNI file was only syntax-checked
  against stub headers; the Android build runs in GitHub Actions.
* Not changed: the preview still draws shapes and stickers with Compose (so they sit above native text there).
* Check on a phone: export a project with each shape (fill, gradient, picture fill, stroke, crop, rotation, flip, opacity, keyframes) and an
  animated sticker; compare with the preview and with the previous version.

## v120: shapes and stickers in the C++ preview
* `NativePreviewEngine.drawTextLayers` now draws text, shape and sticker layers into the one frame-sized picture above the video / photo
  layers (bottom lane first, then by Z). Shapes / stickers use the same `NativeShapePainter` as the export, so preview == export.
  A sticker's animation frame is part of the redraw key; a picture fill that is still loading redraws ~300 ms later (like text).
* `nativeShapeEligible(clip)` (ShapeNative.kt) decides per layer: library built, no 3D rotation, pivot at the centre (C++ and the export always
  turn around the centre). Eligible layers: the Compose copy is hidden (`alpha 0`, `hideContent`) and only keeps size, selection and drag.
  Others stay Compose.
* **Shape outlines now follow the editor canvas (Compose)**, which is what the user sees: Oval and any unknown pattern = circle, Wide Rect = full
  box with 10 dp corners, Rounded = 16 dp corners, Pentagon and Star are drawn (the old export drew these three as circles / an ellipse).
  New C++ kinds PENTAGON (mitered, exact planes) and STAR (exact polygon distance), `Request::corner`. `TRIANGLE` (not in the picker) unchanged.
  Change `ShapeNative.kt` (kind mapping) if you want a real wide oval: both preview and export follow it.
* Host test: 44 checks (6 new: pentagon, star, corner), ASan/UBSan clean.
* Still Compose / Media3 in the preview: blend modes, cutout, face blur (the whole preview falls back to Media3 when one exists), sound.
* Check on a phone: turn "C++ preview" ON (canvas label shows "C++ preview: ON"), add a shape of every kind, an animated sticker, drag / select them,
  compare with the export. Pentagon / Star: tips up; stroke on the star has round tips (Canvas miters them).

## v121: 3D rotation (X / Y) with perspective in the C++ renderer, preview AND export
* `gl_math.h`: `Mat4::rotateX / rotateY / perspective / flattenZ` and `layerModel3d`:
  `point' = centre + position + pivot + Persp( Rx * Ry * Rz * S * (point - pivot) )` - Compose's order (translate, rotateX, rotateY,
  rotateZ, scale, translate(-pivot)). The perspective centre is the pivot. Camera distance = the SAME camera as the Z depth
  (`DEPTH_D` = 1000 preview px, scaled to the export size), so Z, tilt and perspective form one 3D space. z is dropped after the divide
  (layers are painted in order, no depth buffer); GL clips the part of a tilted layer that passes behind the camera.
* `Renderer::drawLayer` uses it when `LayerDraw::is3d()` (perspD > 0 and rotX / rotY not both 0). Otherwise the old flat code runs unchanged,
  so projects without 3D turns render exactly as before. Blend modes, face blur / cutout, crop, flip and opacity all go through `drawLayer`, so
  they work on turned layers too.
* `layer_anim.cpp: eval` returns 7 more numbers (17 floats): rotX, rotY (static or keyframed `ROT_X` / `ROT_Y`), pivot (px from the canvas centre),
  base position after depth (px, no pivot compensation) and the camera distance. JNI / Kotlin: `LayerParams` has rotX, rotY, pivotX, pivotY,
  baseX, baseY, perspD; the renderer params array is 36 floats.
* Direction convention (Compose / Matrix3D): positive rotationY brings the RIGHT edge toward the viewer, positive rotationX the TOP edge.
  If a phone shows the opposite of the Compose preview, change the defaults of `signX` / `signY` in `layerModel3d` (gl_math.h) from 1 to -1 for that axis.
* Host test `tests/gl_math_selftest.cpp`, 31 checks (no turn == the old 2D matrix at any pivot / flip / rotation, perspective numbers of a
  60 deg turn, pivot fixed, rotation direction, eval fields, keyframed turn), ASan/UBSan clean; `layer_anim_selftest` still 1895 OK.
* Text / shape / sticker layers with a 3D turn: not in C++ (preview: Compose draws them, `nativeTextEligible` / `nativeShapeEligible` say no;
  export: drawn flat). Warp (`warpDistort`) is NOT done: the field exists but no editor control and no definition of its effect.
* Check on a phone: video / photo layer with Rotate X / Y (Transform panel, also with keyframes), pivot moved off-centre, Z toward / away,
  compare the C++ preview with the Compose preview and with the export; a layer must keep its pivot point fixed while it turns.

## v122: fixes after the first phone test of v121
* **Shapes / stickers too small in the C++ preview** (the export was right): the preview read the screen density from `PreviewFrameRef.density`,
  which is only set when an export starts (1.0 before), so the 130 dp box was drawn 65 px. `NativePreviewEngine` now uses
  `resources.displayMetrics.density`. (Text was not affected: its sizes are fractions of the frame width.)
* **3D direction was the opposite of the Compose preview.** Android's positive rotationY moves the right edge AWAY and positive rotationX moves
  the top edge AWAY (the opposite of the plain right-handed matrices). `layerModel3d` now defaults `signX = signY = -1`; the v121 note about
  the direction above is superseded by this. `gl_math_selftest` follows the new direction (31 checks OK).

## v123: portrait video sideways in the C++ preview
* Phone test: a portrait video (rotation metadata 90) was shown turned 90 degrees too far with "C++ preview: ON", upright with it off.
  Likely cause: the track format (with `rotation-degrees`) is given to the codec, and on many devices the codec then writes the turn into the
  SurfaceTexture transform matrix itself; `layerTexMatrix` added the metadata turn a second time (`README_ENGINE.md` assumed decoders do not rotate).
* `VideoLayerSource.drawRotation`: after every new frame the matrix is inspected (`remainingRotation`): 90 / 270 turned = off-diagonal terms
  m1 / m4 dominate, 180 turned = m0 < 0 and m5 > 0. If the matrix already holds the turn, the renderer applies 0, otherwise the metadata value.
  Nothing changes when the matrix is plain, so devices / files that were right stay right. All draw calls (preview, export, applyFx, probe) use it.
* Logcat tag `VideoLayerSource`, one line per clip: `rotation metadata 90, matrix already turned: true/false, renderer applies ..., m0 m1 m4 m5`.
  If the picture is still sideways, send that line.

## v124 - blend modes, cutout and face blur in the C++ preview
- `NativePreviewEngine` now draws what the export draws: video / photo layers with blend modes (`drawLayerBlended`, a matte
  on the lowest layer behaves like Normal), cutout and face blur (`applyFx`, masks from `NativeFxMasks`, shared with the
  exporter; in the preview a mask that cannot be read only skips the effect for that frame).
- Text / shape / sticker layers are grouped like in the export (normal layers share one picture, a blended layer gets its
  own and is drawn with `blendTexture`, opacity applied by the blend shader).
- `NativePreview.eligible` only returns false now for a blended text / shape / sticker layer that the C++ painters cannot
  draw (it would have to be blended in Compose, which cannot blend with the SurfaceView) and for photos below Android 11.
- No C++ change: the renderer passes were already used by the export.

## v125 - colour emoji in the C++ text engine (step 1 of "everything in C++")
- `text_engine.cpp` now draws emoji. Android's emoji font (Noto Color Emoji, OEM copies) is a bitmap font: no outlines, every glyph
  is a PNG in the `CBDT` table, indexed by `CBLC`. FreeType is built without libpng, so the two tables are read by hand
  (`loadColorTables`, `cbFind`, `colorGlyph`; index formats 1-3, image formats 17 / 18 / 19) and the PNG is decoded by the system
  (`am::decodeEncodedRgba` in `image_loader.cpp`, AImageDecoder, so **Android 11+**). Decoded pictures are cached (192 glyphs).
- Emoji sequences are found first (`markEmojiSequences`: variation selector FE0F, skin tones, keycaps, tag characters, ZWJ joins,
  flags) and each whole sequence is shaped by the emoji font, so families, flags and skin tones come out as one glyph. A heart or
  sun without FE0F stays a normal text glyph.
- The pictures keep their own colours (no fill tint, gradient or outline), are drawn on top of the fill, and cast the layer's
  shadow. The pictures are scaled from the 109 px strike (halved first when drawn small, to stay sharp).
- `TextNative.emojiAvailable` (JNI `nativeEmojiAvailable`) = decoder present AND the device's emoji font is a readable bitmap font.
  `nativeTextEligible` returns it for text with emoji, so on a phone where it is false (Android 10, or an emoji font that is not
  CBDT, for example a COLRv1 font) nothing changes: Compose / Canvas draw the layer as before.
- Result: a text layer with emoji no longer makes the preview fall back to Media3 when it has a blend mode, and the export draws
  the emoji with the same code as the preview.
- Host test: `tests/text_selftest.cpp` (157 checks, ASan / UBSan clean). It compares the hand-written CBDT reader with FreeType
  (+ libpng) pixel by pixel for 8 glyphs, and checks flags / families / skin tones / keycaps / FE0F clusters, scale, rotation, crop,
  shadow, and the "no decoder -> false" path. Build line now needs `-lpng`.
- NOT tested here: the Android compile and a real phone. Logcat tag `AmText`: line "emoji native available = true/false".
- Still to do for "everything in C++": 3D-rotated text / shapes and non-centre pivots, full-screen editors, audio (step 3b).

