# Tracket (v0.1.31)

**v0.1.31:** Transform keyframes. The diamond on the layer strip adds a keyframe at the playhead (tap again on a keyframe to remove it). Once a layer has keyframes, every Transform edit (move pad, scale, rotation, pivot, 3D, warp, or dragging on the canvas) writes a keyframe at the playhead, and the controls follow the playhead. The curve icon next to the diamond cycles the speed curve of the keyframe under the playhead (Linear, Ease in-out, Ease in, Ease out, Hold). Keyframes show as diamonds on the layer strip; the preview and the exported shape/text layers play the animation. Reset clears keyframes. Split and trim keep keyframes on the same moment of the layer.

**v0.1.30:** Transform 2D: Horizontal and Vertical sliders merged into one "Swipe here to move layer" pad with X/Y readouts.

**v0.1.29:** squarer layers (2dp corners), left layer controls have no background (eye/check + thumbnail only), selected layers (single or multi-select) get an inner shadow instead of a border, lanes are shorter (30-36dp).

**v0.1.28:** background #07051B; new layer design (gradient bodies with light top edge, labels, grip, rounded selection border, midnight lane pills with bordered rounded thumbnails).

**v0.1.27:** multi-select — long-press a layer's left control to check layers (tap to check/uncheck, Back to leave); a bar at the bottom of the timeline adapts to the playhead: inside layers = delete-left / split / delete-right, before them = extend start / move start to playhead, after them = extend end / move end to playhead; right side = align starts / arrange one after another / align ends.

**v0.1.20:** background #270423, no success pop-ups (only errors), thumbnail look-ahead of one screen (fixes periodic swipe stutter).

**v0.1.18:** fast spring entrance (enterPop) on every dialog, Text edit and Transform panel; all remaining taps use bouncyClickable; faster tab cross-fades.

**v0.1.17 – animations:** springy lane reorder/add slide, layer lift while dragging, animated selection border, fast slide+fade toolbar swap.

**v0.1.16 – smoothness:** the playhead no longer recomposes the whole editor every frame (it is read only in
draw/layout phases), the timeline scrolls with a graphicsLayer translation, playback moves the playhead once per
display frame (not every 33 ms), swipe detection measures the touch slop from finger-down (slow swipes work),
a layer drag no longer scrubs the timeline at the same time, scrubbing seeks/audio sync are throttled, and
`bouncyClickable` reacts on the first touch frame with a spring (no more ~100 ms press delay in scrollables).


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
