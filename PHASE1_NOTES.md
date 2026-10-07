# Tracket – implementation status

## Phase 1: real editor core

- Media import through Android Photo Picker, with best-effort persistable URI permissions.
- ExoPlayer preview for the main video track, including split/trim offsets and horizontal/vertical flip.
- Main-track split, ripple delete, duplicate, and project aspect ratio controls.
- MP4 export through Media3 Transformer to `Movies/Tracket` (MediaStore on Android 10+).
- Current project autosave/restore in app-private JSON storage.
- Media3 version: 1.10.0 (`gradle/libs.versions.toml`).

## Not implemented yet

- Audio file import/mixing and secondary-track compositing in preview/export.
- Exporting text, shape, sticker, and overlay layers.
- Per-clip speed, advanced effects/blend modes/keyframes, and multiple named projects.
- Frame-rate/quality/format controls in the export UI.

The editor currently exports only URI-backed items on the main track. Some toolbar/sheet options are visual prototypes and do not affect the rendered output.
