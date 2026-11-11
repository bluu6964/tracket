// Host self-test for engine/layer_anim.cpp (keyframes, easing, speed curve, tracking, layer pose):
//   g++ -std=c++17 -O1 -g -fsanitize=address,undefined -Wall -Wextra -I.. ../engine/layer_anim.cpp layer_anim_selftest.cpp -o layer_anim_selftest && ./layer_anim_selftest
// The first block repeats test/.../KeyframesTest.kt with the same numbers, the rest checks pose / tracking maths.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "engine/layer_anim.h"

using namespace am;

static int g_fail = 0, g_checks = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        ++g_checks;                                                              \
        if (!(cond)) { ++g_fail; std::printf("FAIL line %d: %s\n", __LINE__, #cond); } \
    } while (0)
static bool near(float a, float b, float tol) { return std::fabs(a - b) <= tol; }

static Key K(int64_t t, float a, float b = 0.f, Easing e = Easing()) { Key k; k.timeMs = t; k.a = a; k.b = b; k.easing = e; return k; }

static LayerAnim clip() {  // like KeyframesTest.clip(): starts at 1000, 4000 ms long
    LayerAnim c;
    c.st.startMs = 1000;
    c.st.durationMs = 4000;
    return c;
}

// Independent reference for the bezier: bisection in double precision.
static float refEase(const Easing& e, double t) {
    if (t <= 0) return 0;
    if (t >= 1) return 1;
    auto bx = [&](double s) { double u = 1 - s; return 3 * u * u * s * e.x1 + 3 * u * s * s * e.x2 + s * s * s; };
    auto by = [&](double s) { double u = 1 - s; return 3 * u * u * s * e.y1 + 3 * u * s * s * e.y2 + s * s * s; };
    double lo = 0, hi = 1;
    for (int i = 0; i < 80; ++i) { double m = (lo + hi) / 2; if (bx(m) < t) lo = m; else hi = m; }
    return (float)by((lo + hi) / 2);
}

int main() {
    // ---- KeyframesTest.kt ----
    {   // linear_midpoint_holdBeforeAndAfter
        std::vector<Key> l = {K(0, 0.f), K(1000, 100.f)};
        CHECK(near(valueAt(l, 500).a, 50.f, 0.2f));
        CHECK(valueAt(l, -300).a == 0.f);
        CHECK(valueAt(l, 5000).a == 100.f);
    }
    {   // easeIn_isSlowerThanLinearInTheFirstHalf
        Easing ein; ein.x1 = 0.42f; ein.y1 = 0.f; ein.x2 = 1.f; ein.y2 = 1.f;
        std::vector<Key> l = {K(0, 0.f, 0.f, ein), K(1000, 100.f)};
        CHECK(valueAt(l, 500).a < 40.f);
        CHECK(near(valueAt(l, 1000).a, 100.f, 0.01f));
    }
    {   // easing endpoints exact for every preset, monotonic for ease in-out
        const float P[][4] = {{0.42f,0,1,1},{0,0,0.58f,1},{0.42f,0,0.58f,1},{0.47f,0,0.745f,0.715f},{0.39f,0.575f,0.565f,1},
            {0.445f,0.05f,0.55f,0.95f},{0.55f,0.085f,0.68f,0.53f},{0.25f,0.46f,0.45f,0.94f},{0.455f,0.03f,0.515f,0.955f},
            {0.55f,0.055f,0.675f,0.19f},{0.215f,0.61f,0.355f,1},{0.645f,0.045f,0.355f,1},{0.77f,0,0.175f,1},
            {0.95f,0.05f,0.795f,0.035f},{0.19f,1,0.22f,1},{0.6f,-0.2f,0.735f,0.045f},{0.175f,0.885f,0.32f,1.2f},{0.68f,-0.2f,0.265f,1.2f}};
        for (auto& p : P) {
            Easing e; e.x1 = p[0]; e.y1 = p[1]; e.x2 = p[2]; e.y2 = p[3];
            CHECK(e.apply(0.f) == 0.f);
            CHECK(e.apply(1.f) == 1.f);
            for (int i = 1; i < 100; ++i) CHECK(near(e.apply(i / 100.f), refEase(e, i / 100.0), 2e-3f));
        }
        Easing e; e.x1 = 0.42f; e.y1 = 0.f; e.x2 = 0.58f; e.y2 = 1.f;
        float prev = -1.f;
        for (int i = 0; i <= 20; ++i) { float v = e.apply(i / 20.f); CHECK(v >= prev - 1e-4f); prev = v; }
    }
    {   // at_usesTimeRelativeToLayerStart (through eval): scale 100->200, position (0,0)->(100,-50), clip at 1000
        LayerAnim c = clip();
        c.keys[P_SCALE] = {K(0, 100.f), K(2000, 200.f)};
        c.keys[P_POSITION] = {K(0, 0.f, 0.f), K(2000, 100.f, -50.f)};
        c.finalize();
        FrameParams f = c.eval(2000, 1000, 500, 0.f);  // 1000 ms into the layer, previewWidth unknown -> k = 1
        CHECK(near(f.scaleX, 1.5f, 0.005f));
        CHECK(near(f.offsetX, 50.f, 0.5f));
        CHECK(near(f.offsetY, -25.f, 0.5f));
    }
    {   // crop_sizeAndPositionAreAnimated
        LayerAnim c = clip();
        c.keys[P_CROP_SIZE] = {K(0, 100.f, 100.f), K(1000, 50.f, 40.f)};
        c.keys[P_CROP_POS] = {K(0, 50.f, 50.f), K(1000, 0.f, 100.f)};
        c.finalize();
        FrameParams f = c.eval(1500, 1000, 500, 0.f);
        CHECK(near(f.cropW, 0.75f, 0.002f));
        CHECK(near(f.cropH, 0.70f, 0.002f));
        CHECK(near(f.cropX, (1.f - 0.75f) * 0.25f, 0.002f));
        CHECK(near(f.cropY, (1.f - 0.70f) * 0.75f, 0.002f));
    }
    {   // scale_bComponentIsTheSeparateHeightScale (build 93): a SCALE track's b reaches clipAt as
        // scaleYPercent; without b it stays 0 (the height follows the width, as before).
        LayerAnim c = clip();
        c.keys[P_SCALE] = {K(0, 100.f, 50.f), K(2000, 200.f, 80.f)};
        c.finalize();
        ClipValues v = c.clipAt(2000, 2000);  // 1000 ms into the layer: halfway through both components
        CHECK(near(v.scalePercent, 150.f, 0.01f));
        CHECK(near(v.scaleYPercent, 65.f, 0.01f));
        LayerAnim plain = clip();
        plain.keys[P_SCALE] = {K(0, 100.f), K(2000, 200.f)};
        plain.finalize();
        CHECK(plain.clipAt(2000, 2000).scaleYPercent == 0.f);
    }
    {   // opacity_isAnimatedWithEasing
        LayerAnim c = clip();
        c.keys[P_OPACITY] = {K(0, 0.f), K(1000, 100.f)};
        c.finalize();
        CHECK(near(c.eval(1500, 100, 100, 0.f).opacity, 0.5f, 0.005f));
        CHECK(near(c.eval(9000, 100, 100, 0.f).opacity, 1.0f, 1e-5f));
    }
    {   // speed_sourceOffsetFollowsCurve
        LayerAnim plain = clip(); plain.st.speed = 2.f; plain.finalize();
        CHECK(plain.sourceOffsetMs(500) == 1000);
        CHECK(plain.exportSpeed() == 2.f);
        LayerAnim c = clip();
        c.keys[P_SPEED] = {K(0, 1.f), K(1000, 3.f)};
        c.finalize();
        CHECK(std::llabs(c.sourceOffsetMs(500) - 750) <= 15);
        CHECK(std::llabs(c.sourceOffsetMs(1000) - 2000) <= 15);
        CHECK(near(c.exportSpeed(), 2.75f, 0.02f));
    }
    // ---- source time as the exporter loop computes it ----
    {
        LayerAnim c = clip(); c.st.trimStartMs = 300; c.st.speed = 2.f; c.finalize();
        CHECK(c.sourceMsAt(1000) == 300);
        CHECK(c.sourceMsAt(1500) == 300 + 1000);
        CHECK(c.sourceMsAt(1333) == 300 + 666);  // 333 * 2 = 666 (truncation like Float.toLong)
    }
    // ---- layerPose ----
    {   // plain, centre pivot, preview == export width: offset = position
        LayerAnim c = clip(); c.st.transformX = 12.f; c.st.transformY = -7.f; c.finalize();
        FrameParams f = c.eval(1000, 1080, 1920, 1080.f);
        CHECK(near(f.offsetX, 12.f, 1e-4f) && near(f.offsetY, -7.f, 1e-4f) && near(f.scaleX, 1.f, 1e-6f));
        f = c.eval(1000, 1080, 1920, 540.f);  // preview half as wide -> moves doubled
        CHECK(near(f.offsetX, 24.f, 1e-4f) && near(f.offsetY, -14.f, 1e-4f));
    }
    {   // pivot on the right edge, scale 2, no rotation: offsetX = pvx - 2*pvx
        LayerAnim c = clip(); c.st.pivotX = 1.f; c.st.scalePercent = 200.f; c.finalize();
        FrameParams f = c.eval(1000, 1000, 500, 0.f);
        CHECK(near(f.offsetX, 500.f - 1000.f, 1e-3f) && near(f.offsetY, 0.f, 1e-3f));
    }
    {   // pivot right edge, rotation 90 deg, scale 1: rx = 0, ry = 500
        LayerAnim c = clip(); c.st.pivotX = 1.f; c.st.rotationDeg = 90.f; c.finalize();
        FrameParams f = c.eval(1000, 1000, 500, 0.f);
        CHECK(near(f.offsetX, 500.f, 1e-2f) && near(f.offsetY, -500.f, 1e-2f) && f.rotationDeg == 90.f);
    }
    {   // flips + depth: z = 500 -> x2 ; z at / past the camera (>= 980) -> x50 and not drawn (opacity 0)
        LayerAnim c = clip(); c.st.transformZ = 500.f; c.st.flipH = true; c.finalize();
        FrameParams f = c.eval(1000, 100, 100, 0.f);
        CHECK(near(f.scaleX, -2.f, 1e-5f) && near(f.scaleY, 2.f, 1e-5f) && f.opacity > 0.f);
        c.st.transformZ = 5000.f; c.st.flipH = false; c.st.flipV = true; c.finalize();
        f = c.eval(1000, 100, 100, 0.f);
        CHECK(near(f.scaleX, 50.f, 1e-3f) && near(f.scaleY, -50.f, 1e-3f) && f.opacity == 0.f);
    }
    {   // depth also scales the position (perspective): z = 500 -> move (100, -50) becomes (200, -100)
        LayerAnim c = clip(); c.st.transformX = 100.f; c.st.transformY = -50.f; c.st.transformZ = 500.f; c.finalize();
        FrameParams f = c.eval(1000, 1000, 500, 0.f);
        CHECK(near(f.offsetX, 200.f, 1e-3f) && near(f.offsetY, -100.f, 1e-3f));
    }
    {   // scale never below 0.01
        LayerAnim c = clip(); c.st.scalePercent = 0.f; c.finalize();
        CHECK(near(c.eval(1000, 100, 100, 0.f).scaleX, 0.01f, 1e-7f));
    }
    // ---- tracking: video frame at 1000 / 1033 / 1066 ms, held until the next one (2 ms tolerance) ----
    {
        LayerAnim c = clip();
        c.trackTimes = {1000, 1033, 1066};
        c.trackDx = {0.f, 10.f, 20.f};
        c.trackDy = {0.f, -1.f, -2.f};
        c.st.transformX = 5.f;
        c.finalize();
        auto ox = [&](int64_t t) { return c.eval(t, 100, 100, 0.f).offsetX; };
        CHECK(near(ox(500), 5.f, 1e-4f));     // before the first frame: first entry
        CHECK(near(ox(1000), 5.f, 1e-4f));
        CHECK(near(ox(1030), 5.f, 1e-4f));    // 1030 + 2 = 1032 < 1033
        CHECK(near(ox(1031), 15.f, 1e-4f));   // 1031 + 2 = 1033
        CHECK(near(ox(1063), 15.f, 1e-4f));   // 1063 + 2 = 1065 < 1066
        CHECK(near(ox(1064), 25.f, 1e-4f));   // 1064 + 2 = 1066
        CHECK(near(ox(1070), 25.f, 1e-4f));
        CHECK(near(ox(9999), 25.f, 1e-4f));   // after the last: held
        CHECK(near(c.eval(1070, 100, 100, 0.f).offsetY, -2.f, 1e-4f));
    }
    {   // tracking offset is added to the KEYFRAMED position
        LayerAnim c = clip();
        c.keys[P_POSITION] = {K(0, 0.f, 0.f), K(1000, 100.f, 0.f)};
        c.trackTimes = {0}; c.trackDx = {7.f}; c.trackDy = {0.f};
        c.finalize();
        CHECK(near(c.eval(1500, 100, 100, 0.f).offsetX, 57.f, 0.5f));
    }
    // ---- clipAt (preview renderAt): clip fields at a time, tracking taken from its OWN time ----
    {
        LayerAnim c = clip();
        c.keys[P_POSITION] = {K(0, 0.f, 0.f), K(1000, 100.f, -40.f)};
        c.keys[P_SCALE] = {K(0, 100.f), K(1000, 200.f)};
        c.keys[P_ROTATION] = {K(0, 0.f), K(1000, 90.f)};
        c.keys[P_POS_Z] = {K(0, 0.f), K(1000, 400.f)};
        c.keys[P_CROP_SIZE] = {K(0, 100.f, 100.f), K(1000, 50.f, 40.f)};
        c.keys[P_CROP_POS] = {K(0, 50.f, 50.f), K(1000, 0.f, 100.f)};
        c.keys[P_OPACITY] = {K(0, 100.f), K(1000, 0.f)};
        c.keys[P_SPEED] = {K(0, 1.f), K(1000, 9.f)};  // clamped to 4 like withProp
        c.trackTimes = {1000, 1500};
        c.trackDx = {0.f, 10.f};
        c.trackDy = {0.f, 5.f};
        c.finalize();
        ClipValues v = c.clipAt(1500, 1000);  // animation at 500 ms, picture of 1000 ms -> offset (0, 0)
        CHECK(near(v.posX, 50.f, 0.3f) && near(v.posY, -20.f, 0.3f));
        CHECK(near(v.scalePercent, 150.f, 0.5f) && near(v.rotationDeg, 45.f, 0.3f) && near(v.posZ, 200.f, 0.5f));
        CHECK(near(v.cropWPercent, 75.f, 0.3f) && near(v.cropHPercent, 70.f, 0.3f));
        CHECK(near(v.cropXPercent, 25.f, 0.3f) && near(v.cropYPercent, 75.f, 0.3f));
        CHECK(near(v.opacityPercent, 50.f, 0.3f) && v.speed == 4.f);
        v = c.clipAt(1500, 1500);  // the picture of 1500 ms is on screen: + (10, 5)
        CHECK(near(v.posX, 60.f, 0.3f) && near(v.posY, -15.f, 0.3f));
        // Same animation as the export evaluates at the same time (offsets differ only by the pose maths).
        FrameParams f = c.eval(1500, 1000, 500, 0.f);
        CHECK(near(f.rotationDeg, v.rotationDeg, 1e-4f));
        CHECK(near(f.opacity, v.opacityPercent / 100.f, 1e-4f));
        // No keyframes: the plain fields come back untouched.
        LayerAnim p = clip(); p.st.transformX = 3.f; p.st.scalePercent = 80.f; p.st.speed = 2.f; p.finalize();
        ClipValues q = p.clipAt(1200, 1200);
        CHECK(q.posX == 3.f && q.scalePercent == 80.f && q.speed == 2.f && q.opacityPercent == 100.f);
    }

    // ---- colour + 3D rotation keys (text / shape layers) ----
    {
        auto pack = [](int a, int r, int g, int b, float& pa, float& pb) { pa = (float)(a * 256 + r); pb = (float)(g * 256 + b); };
        float a0, b0, a1, b1;
        pack(255, 0, 0, 0, a0, b0);      // opaque black
        pack(255, 255, 100, 51, a1, b1); // R 255, G 100, B 51
        LayerAnim c = clip();
        c.keys[P_FILL_COLOR] = {K(0, a0, b0), K(1000, a1, b1)};
        c.keys[P_ROT_X] = {K(0, 0.f), K(1000, 60.f)};
        c.keys[P_ROT_Y] = {K(0, 10.f), K(1000, -10.f)};
        c.finalize();
        ClipValues v = c.clipAt(1500, 1500);  // half way
        const int x = (int)v.fillA, y = (int)v.fillB;
        CHECK((x >> 8) == 255 && (x & 255) == 127);   // R 127.5 -> 127 (toInt)
        CHECK((y >> 8) == 50 && (y & 255) == 25);     // G 50, B 25.5 -> 25
        CHECK(near(v.rotX, 30.f, 0.3f) && near(v.rotY, 0.f, 0.3f));
        v = c.clipAt(9000, 9000);  // held after the last key
        CHECK(v.fillA == a1 && v.fillB == b1);
        v = c.clipAt(0, 0);
        CHECK(v.fillA == a0 && v.fillB == b0);
        LayerAnim p = clip(); p.st.rotation3dX = 15.f; p.finalize();
        CHECK(p.clipAt(1200, 1200).rotX == 15.f);
    }

    // ---- tracking turn + zoom (v141): added to the layer's own rotation, multiplied into its scale ----
    {
        LayerAnim c = clip();
        c.st.rotationDeg = 10.f;
        c.st.scalePercent = 80.f;
        c.trackTimes = {1000, 1033};
        c.trackDx = {0.f, 4.f};
        c.trackDy = {0.f, 0.f};
        c.trackRot = {0.f, 30.f};
        c.trackScale = {1.f, 1.5f};
        c.finalize();
        ClipValues v0 = c.clipAt(1000, 1000);
        CHECK(near(v0.rotationDeg, 10.f, 1e-4f) && near(v0.scalePercent, 80.f, 1e-4f));
        ClipValues v1 = c.clipAt(1040, 1040);
        CHECK(near(v1.rotationDeg, 40.f, 1e-4f) && near(v1.scalePercent, 120.f, 1e-4f) && near(v1.posX, 4.f, 1e-4f));
        FrameParams f1 = c.eval(1040, 100, 100, 0.f);
        CHECK(near(f1.rotationDeg, 40.f, 1e-4f) && near(f1.scaleX, 1.2f, 1e-4f) && near(f1.scaleY, 1.2f, 1e-4f));
        // a layer tracked without turn / zoom keeps its own values
        LayerAnim d = clip();
        d.st.rotationDeg = 10.f;
        d.trackTimes = {1000}; d.trackDx = {1.f}; d.trackDy = {1.f};
        d.finalize();
        CHECK(near(d.clipAt(1000, 1000).rotationDeg, 10.f, 1e-4f) && near(d.eval(1000, 100, 100, 0.f).scaleX, 1.f, 1e-4f));
    }

    // ---- unsorted keys are sorted by finalize ----
    {
        LayerAnim c = clip();
        c.keys[P_ROTATION] = {K(1000, 90.f), K(0, 0.f)};
        c.finalize();
        CHECK(near(c.eval(1500, 100, 100, 0.f).rotationDeg, 45.f, 0.2f));
    }

    std::printf(g_fail ? "layer_anim_selftest: %d of %d checks FAILED\n" : "layer_anim_selftest: all %d checks OK\n",
                g_fail ? g_fail : g_checks, g_checks);
    return g_fail ? 1 : 0;
}
