# Tracket – Phase 1 (real editor core)

Real now: media import (Photo Picker), ExoPlayer preview of the main track, trim/split,
delete (ripple), duplicate, flip + aspect ratio + mute in export, real MP4 export (Media3 Transformer).
Saved to Movies/Tracket.

New files: engine/EditorPlayer.kt, engine/VideoExporter.kt, engine/MediaImporter.kt
Media3 version: 1.10.0 (gradle/libs.versions.toml)

Not done yet (Phase 2+): per-clip speed, audio import/mix, overlay/text/shape layers in the
export, effects/blend/keyframes, saving projects, flip in preview.
NOTE: written without an Android SDK available, so it has not been compiled or run.
