# Tracket (v0.1.15)

Android video editor. v0.1 scope: import videos/photos, edit the main track
(trim, split, delete with ripple, duplicate, flip, mute), preview with ExoPlayer,
choose aspect ratio, export an H.264/AAC MP4 to Movies/Tracket.

Clip Transform (position, scale, rotation, pivot, 3D, warp) is active in the editor
preview. Shape layers can be added from the toolbar (editor + canvas preview).

### Layer system (lanes) — Alight Motion style
The timeline is a list of free lanes (lines). **One layer per line** — layers never sit side by side.
A new layer always gets its own lane on top. There is no limit on the number of lanes and no fixed
media/text/shape row. Lanes disappear when they become empty. The top lane is drawn on top.

**Layer handling:** long-press a layer on the timeline, then drag horizontally to move it in time
or vertically to reorder stacking (bring forward / send backward). Swipe the empty timeline area to
scrub with inertia.

**Export rule (v0.1.15):** Export works as soon as **any one layer** exists (video, photo, shape,
text, audio, …) — same idea as Alight Motion. If there is no video/photo media, a solid black
background of the project duration is used so the export still produces a valid MP4 (shapes/text
are still preview-only and not yet burned into the export).

Text layers (v0.1.11): the Text button adds a text layer at the playhead and opens "Text edit"
(same panel layout as Transform: live canvas in the middle). Tabs: Fonts (system + Google Fonts that are
downloaded once and cached, search, "Add font" for your own .ttf/.otf), Style (presets, color, weight,
italic/underline/strike), Stroke, Shadow, Background, Spacing (letter/line spacing, alignment).
The color eyedropper reads a pixel from the canvas. Re-open it with the Edit tool or by double-tapping
the text on the canvas. Text is shown in the editor preview; like shapes it is not rendered into the
exported video yet.
Audio layers (v0.1.12): the Audio button picks music/sound files from the phone. Each file becomes an
audio layer (it follows the lane rules and never runs past the end of the video). The layer shows a real
waveform and its length. Tools: Split, Volume (0-100%), Fade (in/out), Delete, Speed (0.25x-4x, pitch stays),
Duplicate. Reverse is not available yet. Audio plays in the preview together with the video sound
(lane mute and the master speaker also mute it) and is mixed into the exported MP4.
Preview/export still play media clips back to back (single video pipeline). Overlay,
sticker, crop, effects, animations and
blend are visible in the UI but disabled ("Coming in a later version").

## Run
1. Open the project in Android Studio and let Gradle sync.
2. Run on an emulator or device (minSdk 24).
3. Unit tests: `./gradlew testDebugUnitTest`

## Release signing (optional)
Set `STORE_PASSWORD`, `KEY_PASSWORD` and optionally `KEYSTORE_PATH`
(default `my-upload-key.jks` in the project root, key alias `upload`).
Without them the release build is unsigned.
