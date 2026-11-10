# Stickers

Every sub-folder here is a **category** shown as a tab in the Sticker picker.

```
stickers/
  basic/                 <- category
    star.png             <- static sticker (PNG with transparency)
  love/
    heart.png
    heart_pulse_anim/    <- animated sticker = a FOLDER of frames
      meta.json          {"fps": 12, "loop": true}
      frame_000.png
      frame_001.png
      ...
```

Rules
- Static: PNG with a transparent background. Keep it at 512 px or less (smaller = faster).
- Animated: a folder with `frame_000.png, frame_001.png, ...` (same size, transparent
  background) and a `meta.json` containing `fps`. Frames are played at `fps`, looping.
  Preview and export use the same frame for the same time, so they match.
- To add an animated GIF or WebP, convert it once:
  `python3 tools/make_animated_sticker.py input.gif app/src/main/assets/stickers/<category>/<name>_anim`
- File and folder names: lower case, no spaces (use `_`).
- After adding files, rebuild the app; new stickers appear automatically.
