# Tracket (v0.1.0)

Android video editor. v0.1 scope: import videos/photos, edit the main track
(trim, split, delete with ripple, duplicate, flip, mute), preview with ExoPlayer,
choose aspect ratio, export an H.264/AAC MP4 to Movies/Tracket.

Clip Transform (position, scale, rotation, pivot, 3D, warp) is active in the editor
preview. Shape layers can be added from the toolbar (editor + canvas preview).

### Layer system (lanes)
The timeline is a list of lanes (lines). There is no fixed media/text/shape row and no limit on
the number of lanes. A new layer goes into the lowest lane that is free for its whole time range;
if every lane is busy there, a new lane is created on top. Layers can sit side by side in one lane
but never on top of each other. Lanes disappear when they become empty. The top lane is drawn on top.
Text layers (v0.1.11): the Text button adds a text layer at the playhead and opens "Text edit"
(same panel layout as Transform: live canvas in the middle). Tabs: Fonts (system + Google Fonts that are
downloaded once and cached, search, "Add font" for your own .ttf/.otf), Style (presets, color, weight,
italic/underline/strike), Stroke, Shadow, Background, Spacing (letter/line spacing, alignment).
The color eyedropper reads a pixel from the canvas. Re-open it with the Edit tool or by double-tapping
the text on the canvas. Text is shown in the editor preview; like shapes it is not rendered into the
exported video yet.
Preview/export still play media clips back to back (single video pipeline). Overlay,
sticker, audio, crop, effects, animations and
blend are visible in the UI but disabled ("Coming in a later version").

## Run
1. Open the project in Android Studio and let Gradle sync.
2. Run on an emulator or device (minSdk 24).
3. Unit tests: `./gradlew testDebugUnitTest`

## Release signing (optional)
Set `STORE_PASSWORD`, `KEY_PASSWORD` and optionally `KEYSTORE_PATH`
(default `my-upload-key.jks` in the project root, key alias `upload`).
Without them the release build is unsigned.
