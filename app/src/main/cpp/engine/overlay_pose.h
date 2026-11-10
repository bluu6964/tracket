// Where a text / shape / sticker layer that is turned in 3D, or pivots around a point other than its centre, goes on
// the canvas. The layer is first drawn UPRIGHT into a canvas-sized picture (its scale and flips baked in, no rotation,
// no translation, box centred on the canvas centre); the renderer then places that picture with the same 3D matrix it
// uses for video / photo layers (LayerDraw, gl_math.h layerModel / layerModel3d). This file turns the layer's own
// numbers into those LayerDraw numbers. Pure C++: tests/overlay_pose_selftest.cpp.
//
// Compose (what the editor shows):  point' = centre + T + P + Persp( Rx Ry Rz * S * (x - P) ),  x = point of the box,
//   P = pivot inside the box, S = diag(s * fx, s * fy) (scale and flips about the pivot), T = translation (after depth).
// The upright picture holds b = S0 * x with S0 = S (scale and flips about the BOX CENTRE), so S * (x - P) = b + d - P,
//   d = P - S * P = ( Px * (1 - s * fx), Py * (1 - s * fy) ).
// layerModel3d gives  centre + base + pivot + Persp( R * (b - pivot) )  =>  pivot = P - d,  base = T + d.
// Flat (no 3D turn): layerModel gives centre + offset + Rz * b  =>  offset = T + P + Rz * (d - P).
#pragma once

namespace am {

struct OverlayPoseIn {
    float boxW = 0, boxH = 0;        // the layer's own box at scale 1, output pixels (text: laid-out box, shape: 130 dp)
    float scale = 1;                 // effective scale (layer scale * depth factor), > 0
    bool flipH = false, flipV = false;
    float rotationDeg = 0;           // clockwise
    float rotX = 0, rotY = 0;        // degrees, Compose convention
    float pivotX = 0.5f, pivotY = 0.5f;  // fractions of the box
    float transX = 0, transY = 0;    // translation after depth, output pixels
    float perspD = 0;                // camera distance, output pixels (0 = flat)
};

// Same fields as the renderer's LayerDraw (the picture is canvas-sized, scale 1, no crop).
struct OverlayPoseOut {
    float offsetX = 0, offsetY = 0;  // used when is3d == false
    float rotationDeg = 0;
    float rotX = 0, rotY = 0;
    float pivotX = 0, pivotY = 0;    // canvas pixels from the centre (3D only)
    float baseX = 0, baseY = 0;      // canvas pixels from the centre (3D only)
    float perspD = 0;
    bool is3d = false;
};

OverlayPoseOut overlayPose(const OverlayPoseIn& in);

}  // namespace am
