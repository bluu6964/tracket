# Tracket (v0.1.0)

Android video editor. v0.1 scope: import videos/photos, edit the main track
(trim, split, delete with ripple, duplicate, flip, mute), preview with ExoPlayer,
choose aspect ratio, export an H.264/AAC MP4 to Movies/Tracket.

Clip Transform (position, scale, rotation, pivot, 3D, warp) is active in the editor
preview. Shape layers can be added from the toolbar (editor + canvas preview).

> **Known limitation:** the preview and the export are not identical yet. Transform and crop
> are preview-only, and only the main media track is encoded — text, overlay, sticker and
> audio layers are not exported at all. Flip and mute are the other way round: export-only.
> See [`ANALYSIS.md`](ANALYSIS.md) for the full review and the fix plan.

### Layer system (lanes)
The timeline is a list of lanes (lines). There is no fixed media/text/shape row and no limit on
the number of lanes. A new layer goes into the lowest lane that is free for its whole time range;
if every lane is busy there, a new lane is created on top. Layers can sit side by side in one lane
but never on top of each other. Lanes disappear when they become empty. The top lane is drawn on top.
Preview/export still play media clips back to back (single video pipeline). Text, overlay,
sticker, audio, crop, effects, animations and blend are **not implemented**; the buttons that
used to show a "Coming in a later version" placeholder have been removed with the rest of the
unreachable UI.

## Run
1. Open the project in Android Studio and let Gradle sync.
2. Run on an emulator or device (minSdk 24).
3. Unit tests: `gradle testDebugUnitTest`
   (the repo ships `gradle/wrapper/gradle-wrapper.properties` but no `gradlew` script, so use a
   local Gradle 9.3.1 or let Android Studio run them; CI does the same.)

## Release signing (optional)
Set `STORE_PASSWORD`, `KEY_PASSWORD` and optionally `KEYSTORE_PATH`
(default `my-upload-key.jks` in the project root, key alias `upload`).
Without them the release build is unsigned.

## Engine Architecture Roadmap

The current app will be developed in **Kotlin first**. We will not migrate the entire
editor to C++ before the Kotlin editor is feature-complete and stable.

### Phase 1 — Kotlin Editor

Build and stabilize the complete editor in Kotlin:

- Layer system: one layer per lane, layer reorder, timeline drag, trim/resize,
  select, lock, visibility, duplicate and delete.
- Timeline engine: accurate frame/millisecond conversion, playhead, zoom, scrubbing,
  snapping and multi-layer timeline behavior.
- Property system: position, scale, rotation, opacity, anchor and keyframes.
- Animation: linear, easing, Bézier, motion paths, and later spring/bounce/gravity physics.
- Compositing: image, video, text, shape, group, null and controller objects.
- Effects: blur, glow, color, shadow, transform effects and related editor effects.
- Export: MP4 rendering with resolution, FPS and audio support.

### Phase 2 — Kotlin Architecture Freeze

The Kotlin implementation should be structured around clear engine boundaries so the
UI does not depend directly on low-level rendering implementation details.

Recommended boundary:

    Kotlin UI / Android
            |
            v
      EditorEngine API
            |
      +-----+-----+----------------+
      |           |                |
    Timeline    Layer           Render
     Engine     Engine           Engine
            |
            v
      Animation / Project APIs

The UI, Android lifecycle, file picker, permissions, settings, templates, AI/API
communication and user interaction remain Kotlin responsibilities.

### Phase 3 — Kotlin to C++ Migration

After the Kotlin editor is stable, move performance-critical components to a native
C++ engine incrementally through JNI. This is a replacement of the implementation
behind the engine boundaries, not a full app rewrite.

Target C++ responsibilities:

- Timeline calculations and frame evaluation
- Layer composition
- Keyframe and animation evaluation
- Transform calculations
- Motion paths and physics
- Video decoding and frame processing
- GPU rendering/compositing
- Effects processing
- Audio processing
- Final video rendering/export

Kotlin remains responsible for:

- Compose/UI
- Timeline gestures and user interaction
- Project management
- Android integration and lifecycle
- File picker and permissions
- Notifications and settings
- Template browser
- AI/API communication
- Calling the native engine through JNI

Target architecture:

    Kotlin UI / Android
             |
             v
       EditorEngine API
             |
            JNI
             |
             v
       C++ Native Engine
             |
       +-----+-----+------+
       |     |     |      |
    Decode Render Effects Audio
             |
             v
       GPU / Native APIs

### Migration Principle

Do **not** prematurely rewrite the app in C++. First make the Kotlin version functional,
stable and well-tested. Keep the engine APIs and responsibilities clean from the beginning
so that C++ can later replace the heavy implementation without rebuilding the UI and
product architecture from scratch.
