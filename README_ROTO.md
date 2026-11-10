# Roto Brush (EdgeSAM)

After Effects style Roto Brush: brush an object on a frame, EdgeSAM finds it, and the app follows it through the clip.
The result is saved as the layer's cutout masks (same format as the person cutout), so preview and C++ export use it as is.

## Model files (downloaded by the app on first use, about 38 MB)
Roto screen -> "Download models". Manual "Choose file" is only the fallback.
EdgeSAM is split in two ONNX models:
- encoder: picture -> embeddings (slow, runs once per frame)
- decoder: embeddings + brush points / box -> mask (fast, runs on every stroke)

Export both from the EdgeSAM repo (github.com/chongzhou96/EdgeSAM, ONNX export script in its `scripts/` folder; see its README for the command).
Then give them to the app in ONE of two ways:
1. Roto tool -> "Choose encoder" / "Choose decoder" (system file picker), or
2. put them in `app/src/main/assets/edgesam/` named `edgesam_encoder.onnx` and `edgesam_decoder.onnx`.

The input/output names of the decoder are discovered at run time (embeddings, point_coords, point_labels, optional
mask_input / has_mask_input / orig_im_size). The encoder is fed a 1024x1024 image normalised with the SAM mean/std
(RGB, 0..255 scale). If your export normalises inside the model, change `MEAN` / `STD` in `EdgeSamEngine.kt` to 0 / 1.

## Use
1. Select a video layer -> tool "Roto".
2. Scrub to a frame. Wait for "Analysing frame" to finish (encoder).
3. Green "+ Object": tap or paint over the object. Red "- Not object": paint over wrongly included areas. Cyan = current mask.
4. "Propagate & apply": follows the object forward and backward through the clip (10 mask frames per second at most).
5. Check the result. To fix a bad part: open Roto again is not needed before applying - scrub to the bad frame BEFORE propagating,
   brush there too (every brushed frame is a keyframe; propagation restarts from each keyframe).
6. Tap "Roto" (or "Cutout") again on the layer to remove the result.

## Files
- engine/EdgeSamEngine.kt - ONNX Runtime encoder + decoder, prompts from masks (box, inside points)
- engine/RotoBrushVideo.kt - keyframes + frame-by-frame propagation, writes m_00000.jpg ... like CutoutVideo
- ui/components/RotoBrushScreen.kt - full-screen brush editor
- Wiring: ClipEditToolsPanel (onRoto), VideoEditorScreen (rotoClipId + dialog)

## EdgeTAM
EdgeTAM (video tracker with memory) can replace the frame-by-frame `follow()` in RotoBrushVideo later; EdgeSAM already gives the
same brush flow. EdgeTAM needs its own ONNX export (image encoder, memory encoder, memory attention, decoder).
