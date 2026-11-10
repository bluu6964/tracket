// g++ -std=c++17 -O1 -g -fsanitize=address,undefined -Wall -Wextra -I.. gl_math_selftest.cpp -o gl_math_selftest && ./gl_math_selftest
// Host test of the 3D layer matrix (engine/gl_math.h layerModel3d): compared with the flat 2D model, perspective numbers,
// rotation direction, pivot, and the layer_anim eval fields that feed it.
#include <cmath>
#include <cstdio>

#include "engine/gl_math.h"
#include "engine/layer_anim.h"

using namespace am;

static int fails = 0, checks = 0;
static void near(double v, double want, double tol, const char* what) {
    ++checks;
    if (std::fabs(v - want) > tol) { ++fails; std::printf("FAIL %s: got %.4f want %.4f\n", what, v, want); }
}

// Pixel position (canvas px, y down) of a quad corner (u, v in -0.5..0.5) through a full mvp, as the GPU does it:
// clip = Canvas * [Zflat *] model * (u, v, 0, 1), divide by w, back from NDC to pixels. Returns w.
static float project(const Mat4& model, bool flat, float W, float H, float u, float v, float* px, float* py) {
    Mat4 m = Mat4::canvas(W, H) * (flat ? Mat4::flattenZ() : Mat4::identity()) * model;
    const float x = m.m[0] * u + m.m[4] * v + m.m[12];
    const float y = m.m[1] * u + m.m[5] * v + m.m[13];
    const float w = m.m[3] * u + m.m[7] * v + m.m[15];
    *px = (x / w * 0.5f + 0.5f) * W;
    *py = (0.5f - y / w * 0.5f) * H;
    return w;
}

int main() {
    const float W = 1000, H = 1000, cw = 1000, ch = 1000;  // the picture fills the canvas: the quad is 1000 x 1000 px, edges at +-500
    {   // no 3D turn: identical to the flat model with the pivot compensation folded into the offset
        const float scaleX = 0.8f, scaleY = -0.8f, rot = 25.f;
        const float baseX = 30, baseY = -45, pvx = 120, pvy = -60;
        const float c = std::cos(rot * 3.14159265358979f / 180.f), s = std::sin(rot * 3.14159265358979f / 180.f);
        const float rx = scaleX * (c * pvx) - scaleY * (s * pvy) * 0.f;
        (void)rx;
        // flat model: offset = base + pivot - R * S * pivot  (S = diag(scaleX, scaleY), then R)
        const float sx = scaleX * pvx, sy = scaleY * pvy;
        const float offX = baseX + pvx - (c * sx - s * sy), offY = baseY + pvy - (s * sx + c * sy);
        const Mat4 flat = layerModel(W, H, cw, ch, offX, offY, scaleX, scaleY, rot);
        const Mat4 d3 = layerModel3d(W, H, cw, ch, baseX, baseY, pvx, pvy, scaleX, scaleY, rot, 0.f, 0.f, 1000.f);
        const float uv[4][2] = {{-.5f, -.5f}, {.5f, -.5f}, {-.5f, .5f}, {.5f, .5f}};
        for (auto& q : uv) {
            float ax, ay, bx, by;
            project(flat, false, W, H, q[0], q[1], &ax, &ay);
            project(d3, true, W, H, q[0], q[1], &bx, &by);
            near(bx, ax, 0.01, "3D matrix with no turn == 2D model (x)");
            near(by, ay, 0.01, "3D matrix with no turn == 2D model (y)");
        }
    }
    {   // rotationY(+60) (Android direction: the right edge goes AWAY), camera 1000 px, pivot at the centre, 1000 x 1000 quad
        const Mat4 m = layerModel3d(W, H, cw, ch, 0, 0, 0, 0, 1.f, 1.f, 0.f, 0.f, 60.f, 1000.f);
        float x, y;
        const float wR = project(m, true, W, H, 0.5f, 0.f, &x, &y);   // right edge middle: x = 500, z = +500 sin 60 (away)
        const double zr = 500.0 * std::sin(60.0 * 3.14159265358979 / 180.0);
        near(wR, 1.0 + zr / 1000.0, 1e-4, "right edge w = 1 + z / d");
        near(x, 500 + 500.0 * 0.5 / (1.0 + zr / 1000.0), 0.05, "right edge x (perspective divide)");
        near(y, 500, 0.01, "right edge y");
        float xl, yl;
        const float wL = project(m, true, W, H, -0.5f, 0.f, &xl, &yl);
        near(wL, 1.0 - zr / 1000.0, 1e-4, "left edge w");
        near(xl, 500 - 500.0 * 0.5 / (1.0 - zr / 1000.0), 0.05, "left edge x");
        ++checks; if (!(wR > wL)) { ++fails; std::printf("FAIL rotationY(+): right edge must be farther than the left edge\n"); }
        float xt, yt;
        const float wT = project(m, true, W, H, 0.f, -0.5f, &xt, &yt);  // top middle stays on the axis
        near(wT, 1.0, 1e-5, "top middle is on the rotation axis");
        near(xt, 500, 0.01, "top middle x");
        near(yt, 0, 0.01, "top middle y");
    }
    {   // rotationX(+50) (Android direction): the TOP edge goes away, the bottom edge comes toward the viewer
        const Mat4 m = layerModel3d(W, H, cw, ch, 0, 0, 0, 0, 1.f, 1.f, 0.f, 50.f, 0.f, 1000.f);
        float x, y;
        const float wTop = project(m, true, W, H, 0.f, -0.5f, &x, &y);
        const float wBot = project(m, true, W, H, 0.f, 0.5f, &x, &y);
        ++checks; if (!(wTop > 1.f && wBot < 1.f)) { ++fails; std::printf("FAIL rotationX(+): top farther, bottom nearer (w %.3f %.3f)\n", wTop, wBot); }
    }
    {   // the pivot stays where it is (at any turn) and the perspective centre is the pivot
        const Mat4 m = layerModel3d(W, H, cw, ch, 20, -10, 150, 80, 1.f, 1.f, 10.f, 35.f, -40.f, 800.f);
        // quad point that sits on the pivot: pivot in px relative to the centre = (150, 80); quad is 1000 wide -> u = 150/1000, v = 80/1000
        float x, y;
        const float w = project(m, true, W, H, 150.f / 1000.f, 80.f / 1000.f, &x, &y);
        near(w, 1.0, 1e-4, "pivot point has w = 1");
        near(x, 500 + 20 + 150, 0.02, "pivot x stays");
        near(y, 500 - 10 + 80, 0.02, "pivot y stays");
    }
    {   // layer_anim eval gives the 3D fields (position after depth, pivot, camera, keyframed turn)
        LayerAnim a;
        a.st.durationMs = 2000;
        a.st.transformX = 100; a.st.transformY = -40; a.st.transformZ = 0;
        a.st.pivotX = 0.75f; a.st.pivotY = 0.5f;
        a.st.rotation3dX = 15.f; a.st.rotation3dY = -25.f;
        a.finalize();
        FrameParams f = a.eval(0, 1000, 500, 500.f);  // k = 2
        near(f.rotX, 15, 1e-5, "eval rotX"); near(f.rotY, -25, 1e-5, "eval rotY");
        near(f.pivotX, 250, 1e-4, "eval pivotX (0.25 * canvas width)"); near(f.pivotY, 0, 1e-4, "eval pivotY");
        near(f.baseX, 200, 1e-4, "eval baseX = position * k"); near(f.baseY, -80, 1e-4, "eval baseY");
        near(f.perspD, 2000, 1e-3, "eval camera distance = 1000 * k");
        a.st.transformZ = 500;  // nearer the camera: position and scale get the depth factor 2
        a.finalize();
        FrameParams g = a.eval(0, 1000, 500, 500.f);
        near(g.baseX, 400, 1e-3, "baseX after depth");
        near(g.scaleX, 2.0, 1e-4, "scale after depth");
        Key k1; k1.timeMs = 0; k1.a = 0; Key k2; k2.timeMs = 1000; k2.a = 90;
        a.keys[P_ROT_Y] = {k1, k2};
        a.finalize();
        near(a.eval(500, 1000, 500, 500.f).rotY, 45, 0.5, "keyframed rotY (linear, halfway)");
    }
    std::printf("gl_math_selftest: %d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
