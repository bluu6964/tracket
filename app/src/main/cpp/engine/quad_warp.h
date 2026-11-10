// Corner pin: draws a layer's picture on an arbitrary 4-corner quad (perspective), and maps points of a (tracked) video
// picture to canvas pixels with exactly the matrix drawLayer uses. Header only, host-testable (tests/quad_warp_selftest.cpp).
#pragma once
#include <cmath>

#include "gl_math.h"
#include "homography.h"

namespace am {

// Model matrix (the part before Mat4::canvas) that sends the layer quad aPos (-0.5 .. 0.5, Y down) onto the canvas quad
// [q] = TL, TR, BR, BL in canvas pixels (origin top-left). The w row carries the perspective, so GL interpolates the
// picture coordinates perspective-correct. False when the quad is degenerate / not convex (the layer is then not drawn).
inline bool pinModel(const float* q, Mat4* out) {
    if (!quadConvex(q, 1.f)) return false;
    double H[9];
    if (!squareToQuad(q, H)) return false;
    Mat4 m = Mat4::identity();
    m.m[0] = (float)H[0]; m.m[1] = (float)H[3]; m.m[2] = 0.f; m.m[3] = (float)H[6];
    m.m[4] = (float)H[1]; m.m[5] = (float)H[4]; m.m[6] = 0.f; m.m[7] = (float)H[7];
    m.m[8] = 0.f; m.m[9] = 0.f; m.m[10] = 0.f; m.m[11] = 0.f;   // z dropped: layers are painted in order
    m.m[12] = (float)H[2]; m.m[13] = (float)H[5]; m.m[14] = 0.f; m.m[15] = (float)H[8];
    *out = m * Mat4::translate(0.5f, 0.5f);   // aPos -0.5..0.5 -> s, t 0..1
    return true;
}

// Point of the upright picture (u, v in 0..1, origin top-left) -> canvas pixels, for a layer that is drawn with [model]
// (layerModel / layerModel3d, before Mat4::canvas, possibly with flattenZ) and the visible window cx, cy, cw, ch (fractions).
inline bool pictureToCanvas(const Mat4& model, float cx, float cy, float cw, float ch, float u, float v, float* ox, float* oy) {
    const float ax = (u - cx) / cw - 0.5f;
    const float ay = (v - cy) / ch - 0.5f;
    const float X = model.m[0] * ax + model.m[4] * ay + model.m[12];
    const float Y = model.m[1] * ax + model.m[5] * ay + model.m[13];
    const float W = model.m[3] * ax + model.m[7] * ay + model.m[15];
    if (!(W > 1e-6f)) return false;
    *ox = X / W;
    *oy = Y / W;
    return true;
}

}  // namespace am
