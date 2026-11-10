// Keyframes, motion tracking and layer pose of ONE video / photo layer, evaluated per export frame.
// Port of model/Keyframes.kt (valueAt, Easing, sourceOffsetMs, exportSpeed), model/MotionTrackModel.kt
// (TrackFollow.indexAt, renderAt) and engine/LayerTransform.kt (layerPose) + NativeProjectExporter.layerParams.
// Pure C++ (no Android / GL), so it is tested on a PC: tests/layer_anim_selftest.cpp.
#pragma once
#include <cstdint>
#include <vector>

namespace am {

struct Easing {
    float x1 = 1.f / 3.f, y1 = 1.f / 3.f, x2 = 2.f / 3.f, y2 = 2.f / 3.f;
    bool isLinear() const;
    float apply(float t) const;  // progress (may overshoot) for the time fraction t in 0..1
};

struct Key {
    int64_t timeMs = 0;  // relative to the layer start
    float a = 0.f, b = 0.f;
    Easing easing;
};

// Properties of a layer. Colour props hold the ARGB colour packed in (a, b) exactly like Kotlin packColor.
enum Prop { P_POSITION = 0, P_SCALE, P_ROTATION, P_POS_Z, P_CROP_SIZE, P_CROP_POS, P_OPACITY, P_SPEED,
            // text / shape / sticker layers (preview only, the export pose ignores them):
            P_ROT_X, P_ROT_Y, P_FILL_COLOR, P_GRAD_COLOR_1, P_GRAD_COLOR_2,
            // shape layers: stroke trim (draw-on), a = outline start, b = outline end, 0..1 of the outline length
            P_STROKE_TRIM, P_COUNT };

struct KeyValue { float a, b; };

// Value of a sorted, non-empty key list at relMs: held before the first / after the last, eased between.
KeyValue valueAt(const std::vector<Key>& keys, int64_t relMs);
// Same for colour keys: every channel (A, R, G, B) blended on its own (Kotlin List<Keyframe>.colorValueAt).
KeyValue colorValueAt(const std::vector<Key>& keys, int64_t relMs);

// Static (un-animated) values of a layer, the fallback when a property has no keyframes.
struct LayerStatic {
    int64_t startMs = 0, durationMs = 0, trimStartMs = 0;
    float transformX = 0, transformY = 0, transformZ = 0;
    float scalePercent = 100, rotationDeg = 0, pivotX = 0.5f, pivotY = 0.5f;
    float cropWidthPercent = 100, cropHeightPercent = 100, cropHorizontalPercent = 50, cropVerticalPercent = 50;
    float opacity = 100;  // percent
    float speed = 1;
    float rotation3dX = 0, rotation3dY = 0;
    float strokeTrimStart = 0, strokeTrimEnd = 1;  // outline fractions, see P_STROKE_TRIM
    bool flipH = false, flipV = false;
};

// Result for one frame, in output pixels. Same meaning as Kotlin LayerParams (+ opacity as 0..1).
struct FrameParams {
    float offsetX, offsetY, scaleX, scaleY, rotationDeg, opacity;
    float cropX, cropY, cropW, cropH;
    // 3D (v121): turn around the pivot with the same camera as the Z depth. baseX / baseY = position after depth in
    // output pixels (no pivot compensation: the 3D matrix rotates around the pivot itself), pivotX / pivotY = pivot
    // relative to the canvas centre in output pixels, perspD = camera distance in output pixels.
    float rotX = 0, rotY = 0;
    float pivotX = 0, pivotY = 0;
    float baseX = 0, baseY = 0;
    float perspD = 0;
};

// The layer's own fields at one frame, i.e. what Kotlin renderAt(timeMs, trackMs) returns: keyframes applied
// (TimelineClip.at) + the tracking offset on the position. Percent / pixel units of the clip, NOT export pixels.
struct ClipValues {
    float posX, posY, posZ, scalePercent, rotationDeg;
    float cropWPercent, cropHPercent, cropXPercent, cropYPercent;
    float opacityPercent, speed;
    float rotX, rotY;
    // Packed colour (a, b) at the time; only meaningful when the layer has keyframes for that colour.
    float fillA, fillB, grad1A, grad1B, grad2A, grad2B;
    // Stroke trim at the time (outline fractions); the statics when the layer has no trim keyframes.
    float strokeTrimStart = 0, strokeTrimEnd = 1;
};

class LayerAnim {
public:
    LayerStatic st;
    std::vector<Key> keys[P_COUNT];
    // Motion tracking (one entry per tracked video frame). Empty = not tracked.
    std::vector<int64_t> trackTimes;
    std::vector<float> trackDx, trackDy;
    // Optional (v141): the tracked object's turn (degrees, relative to the first frame) and zoom (ratio, 1 = first
    // frame) per entry. Empty = not followed; otherwise the same size as trackTimes.
    std::vector<float> trackRot, trackScale;

    // Call once after filling the fields: computes exportSpeed.
    void finalize();

    float exportSpeed() const { return exportSpeed_; }
    int64_t sourceOffsetMs(int64_t relMs) const;
    // Source time shown at project time timeMs: trimStart + (timeMs - start) * exportSpeed (same as the exporter loop).
    int64_t sourceMsAt(int64_t timeMs) const;

    // Preview: same as Kotlin TimelineClip.renderAt(timeMs, trackMs). trackMs = project time of the video picture
    // really on screen (may differ from timeMs while playing / scrubbing).
    ClipValues clipAt(int64_t timeMs, int64_t trackMs) const;

    // previewWidthPx = PreviewFrameRef.widthPx (<= 0: unknown, factor 1). outW / outH = export canvas.
    FrameParams eval(int64_t timeMs, int outW, int outH, float previewWidthPx) const;

private:
    float exportSpeed_ = 1.f;
    int trackIndexAt(int64_t t) const;
};

}  // namespace am
