#!/usr/bin/env python3
"""Convert an animated GIF / WebP / APNG into a sticker frame folder.

usage: python3 make_animated_sticker.py <input> <output_folder> [fps] [max_px]

Writes frame_000.png ... and meta.json. Needs Pillow:  pip install pillow
Transparency is kept (RGBA). Frames are cropped to the same size and
resized so the longest side is max_px (default 512).
"""
import json
import os
import sys

from PIL import Image, ImageSequence


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    src, out = sys.argv[1], sys.argv[2]
    max_px = int(sys.argv[4]) if len(sys.argv) > 4 else 512

    im = Image.open(src)
    frames = []
    durations = []
    for f in ImageSequence.Iterator(im):
        frames.append(f.convert("RGBA"))
        durations.append(f.info.get("duration", 80) or 80)
    if not frames:
        sys.exit("no frames found")

    # Use the source's first frame size, scaled down if needed.
    w, h = frames[0].size
    scale = min(1.0, max_px / max(w, h))
    size = (max(1, round(w * scale)), max(1, round(h * scale)))
    os.makedirs(out, exist_ok=True)
    for i, fr in enumerate(frames):
        fr = fr.resize(size, Image.LANCZOS) if scale < 1.0 else fr
        fr.save(os.path.join(out, f"frame_{i:03d}.png"))

    if len(sys.argv) > 3:
        fps = float(sys.argv[3])
    else:
        avg_ms = sum(durations) / len(durations)
        fps = round(1000.0 / avg_ms, 2) if avg_ms > 0 else 12
    with open(os.path.join(out, "meta.json"), "w") as fp:
        json.dump({"fps": fps, "loop": True}, fp)
    print(f"{len(frames)} frames -> {out} (fps {fps}, {size[0]}x{size[1]})")


if __name__ == "__main__":
    main()
