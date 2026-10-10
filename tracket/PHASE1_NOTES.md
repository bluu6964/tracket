# Tracket – Phase 1 (real editor core)

Real now: media import (Photo Picker), ExoPlayer preview of the main track, trim/split,
delete (ripple), duplicate, flip + aspect ratio + mute in export, real MP4 export (Media3 Transformer).
Saved to Movies/Tracket.

New files: engine/EditorPlayer.kt, engine/VideoExporter.kt, engine/MediaImporter.kt
Media3 version: 1.10.0 (gradle/libs.versions.toml)

Not done yet (Phase 2+): per-clip speed, audio import/mix, overlay/text/shape layers in the
export, effects/blend/keyframes, saving projects, flip in preview.
NOTE: written without an Android SDK available, so it has not been compiled or run.

## Cleanup pass (2026-10-08)
Removed the UI that could not be reached from any button (crop view, shape editor, applied
effects, and the effects/animations/replace/blend/crop/text/audio sheets) together with the
model fields and ViewModel methods that only those screens used (~2,850 lines). The export
dialog now shows the real output size, splitting reports failure instead of always claiming
success, the back button no longer claims a save that does not happen, and the undo history is
capped at 50 steps. See `ANALYSIS.md` for what is still missing.
