// Host self-test for engine/overlay_pose.cpp: the numbers it gives the renderer for the upright picture of a text /
// shape layer must put every point where Compose's graphicsLayer (scale + flips + rotation about the pivot, 3D with
// perspective, then the translation) puts the same point of the layer.
//   g++ -std=c++17 -O1 -g -fsanitize=address,undefined -Wall -Wextra -I.. ../engine/overlay_pose.cpp overlay_pose_selftest.cpp -o overlay_pose_selftest && ./overlay_pose_selftest
#include <cmath>
#include <cstdio>
#include <cstdlib>

#include "engine/gl_math.h"
#include "engine/overlay_pose.h"

using namespace am;

static int g_fail = 0, g_checks = 0;
#define CHECK(cond)                                                                      \
    do {                                                                                 \
        ++g_checks;                                                                      \
        if (!(cond)) { ++g_fail; std::printf("FAIL line %d: %s\n", __LINE__, #cond); }   \
    } while (0)

static float rnd(float lo, float hi) { return lo + (hi - lo) * (float)std::rand() / (float)RAND_MAX; }

// Full homogeneous transform (Mat4::apply ignores w), model space -> canvas pixels before the viewport flip.
static void project(const Mat4& m, float x, float y, float* ox, float* oy) {
    const float X = m.m[0] * x + m.m[4] * y + m.m[12];
    const float Y = m.m[1] * x + m.m[5] * y + m.m[13];
    const float W = m.m[3] * x + m.m[7] * y + m.m[15];
    *ox = X / W;
    *oy = Y / W;
}

int main() {
    std::srand(7);
    const float CW = 1080.f, CH = 1920.f;
    for (int it = 0; it < 400; ++it) {
        OverlayPoseIn in;
        in.boxW = rnd(100.f, 900.f);
        in.boxH = rnd(60.f, 700.f);
        in.scale = rnd(0.2f, 3.f);
        in.flipH = std::rand() & 1;
        in.flipV = std::rand() & 1;
        in.rotationDeg = rnd(-180.f, 180.f);
        const bool three = (it % 3) != 0;
        in.rotX = three ? rnd(-70.f, 70.f) : 0.f;
        in.rotY = three ? rnd(-70.f, 70.f) : 0.f;
        in.pivotX = (it % 4 == 0) ? 0.5f : rnd(-0.2f, 1.2f);
        in.pivotY = (it % 4 == 0) ? 0.5f : rnd(-0.2f, 1.2f);
        in.transX = rnd(-300.f, 300.f);
        in.transY = rnd(-300.f, 300.f);
        in.perspD = 1000.f * 3.f;

        const OverlayPoseOut o = overlayPose(in);
        CHECK(o.is3d == three);

        // Reference: the layer itself (canvas-sized picture, unscaled, box at the centre), scale / flips / rotation
        // about the pivot, translation after - the formula Compose's graphicsLayer follows.
        const float px = (in.pivotX - 0.5f) * in.boxW, py = (in.pivotY - 0.5f) * in.boxH;
        const float sx = in.scale * (in.flipH ? -1.f : 1.f), sy = in.scale * (in.flipV ? -1.f : 1.f);
        Mat4 ref;
        if (o.is3d) {
            ref = layerModel3d(CW, CH, CW, CH, in.transX, in.transY, px, py, sx, sy, in.rotationDeg, in.rotX, in.rotY, in.perspD);
        } else {
            // flat: centre + T + P + Rz * S * (x - P)
            ref = Mat4::translate(CW * 0.5f + in.transX + px, CH * 0.5f + in.transY + py) * Mat4::rotateZ(in.rotationDeg) *
                  Mat4::scale(sx, sy) * Mat4::translate(-px, -py) * Mat4::scale(CW, CH);
        }
        // The upright picture, placed with the numbers under test.
        Mat4 mine;
        if (o.is3d) {
            mine = layerModel3d(CW, CH, CW, CH, o.baseX, o.baseY, o.pivotX, o.pivotY, 1.f, 1.f, o.rotationDeg, o.rotX, o.rotY, o.perspD);
        } else {
            mine = layerModel(CW, CH, CW, CH, o.offsetX, o.offsetY, 1.f, 1.f, o.rotationDeg);
        }
        for (int t = 0; t < 8; ++t) {
            // a point of the layer, in quad coordinates (-0.5 .. 0.5) of the canvas-sized picture
            const float u = rnd(-0.5f, 0.5f), v = rnd(-0.5f, 0.5f);
            float rx, ry, bx, by;
            project(ref, u, v, &rx, &ry);
            // the same point in the baked picture: scale and flips about the box centre (quad coordinates scale too)
            project(mine, u * sx, v * sy, &bx, &by);
            const float tol = 0.02f + 2e-4f * std::fabs(rx);
            CHECK(std::fabs(bx - rx) < tol && std::fabs(by - ry) < tol);
        }
    }
    // identity: centre pivot, no turn, no scale -> the picture stays where it is
    OverlayPoseIn id;
    id.boxW = 300; id.boxH = 200;
    const OverlayPoseOut o0 = overlayPose(id);
    CHECK(!o0.is3d && o0.offsetX == 0.f && o0.offsetY == 0.f && o0.rotationDeg == 0.f);
    // plain translation
    id.transX = 40; id.transY = -25;
    const OverlayPoseOut o1 = overlayPose(id);
    CHECK(std::fabs(o1.offsetX - 40.f) < 1e-4f && std::fabs(o1.offsetY + 25.f) < 1e-4f);
    // scale 2 about the top-left corner of a 300 x 200 box: the centre moves away from that corner by (150, 100)
    OverlayPoseIn sc;
    sc.boxW = 300; sc.boxH = 200; sc.scale = 2.f; sc.pivotX = 0.f; sc.pivotY = 0.f;
    const OverlayPoseOut o2 = overlayPose(sc);
    CHECK(std::fabs(o2.offsetX - 150.f) < 1e-3f && std::fabs(o2.offsetY - 100.f) < 1e-3f);

    std::printf("%d checks, %d failed\n", g_checks, g_fail);
    return g_fail ? 1 : 0;
}
