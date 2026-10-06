# Tracket (v0.1.0)

Android video editor. v0.1 scope: import videos/photos, edit the main track
(trim, split, delete with ripple, duplicate, flip, mute), preview with ExoPlayer,
choose aspect ratio, export an H.264/AAC MP4 to Movies/Tracket.

Clip Transform (position, scale, rotation, pivot, 3D, warp) is active in the editor
preview. Text, shape, overlay, sticker, audio, crop, effects, animations and
blend are visible in the UI but disabled ("Coming in a later version").

## Run
1. Open the project in Android Studio and let Gradle sync.
2. Run on an emulator or device (minSdk 24).
3. Unit tests: `./gradlew testDebugUnitTest`

## Release signing (optional)
Set `STORE_PASSWORD`, `KEY_PASSWORD` and optionally `KEYSTORE_PATH`
(default `my-upload-key.jks` in the project root, key alias `upload`).
Without them the release build is unsigned.
