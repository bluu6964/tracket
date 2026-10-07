# Tracket (v0.1.0)

Android video editor. v0.1 scope: import videos/photos, edit the main track
(trim, split, delete with ripple, duplicate, flip, mute), preview with ExoPlayer,
choose aspect ratio, export an H.264/AAC MP4 to Movies/Tracket.

Clip Transform (position, scale, rotation, pivot, 3D, warp) is active in the editor
preview. Shape layers can be added from the toolbar (editor + canvas preview).

### Layer system (updated)
The timeline is a true layer stack: **one layer per line**. Every layer gets its own row even when
its time range does not overlap another layer. Rows can be dragged up/down to change stacking order.
A selected layer can be dragged left/right to move it forward/backward in time, and its left/right
edge handles resize the layer. Every drag/resize gesture is one undo step.

Each row has fixed controls on the left: visibility (eye), layer thumbnail/type, lock, and a drag
handle. Hidden layers stay in the timeline but are excluded from the canvas and export.

Preview/export still use the existing media pipeline. Text, overlay, sticker, audio, crop, effects,
animations and blend remain available in the UI where implemented.

## Run
1. Open the project in Android Studio and let Gradle sync.
2. Run on an emulator or device (minSdk 24).
3. Unit tests: `./gradlew testDebugUnitTest`

## Release signing (optional)
Set `STORE_PASSWORD`, `KEY_PASSWORD` and optionally `KEYSTORE_PATH`
(default `my-upload-key.jks` in the project root, key alias `upload`).
Without them the release build is unsigned.
