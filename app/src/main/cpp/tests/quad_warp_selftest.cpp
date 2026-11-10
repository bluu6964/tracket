// g++ -std=c++17 -O2 -Wall -Wextra -I../engine quad_warp_selftest.cpp && ./a.out   (header-only, no GL)
#include <cmath>
#include <cstdio>

#include "quad_warp.h"

using namespace am;
static int fails = 0, checks = 0;
#define CHECK(c, ...) do { ++checks; if (!(c)) { ++fails; printf("FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static void apply(const Mat4& m, float x, float y, float* ox, float* oy) {
    const float X = m.m[0] * x + m.m[4] * y + m.m[12], Y = m.m[1] * x + m.m[5] * y + m.m[13], W = m.m[3] * x + m.m[7] * y + m.m[15];
    *ox = X / W;
    *oy = Y / W;
}

int main() {
    // 1) pinModel sends the layer corners (aPos = +-0.5) to the quad corners, perspective included
    const float q[8] = {100, 60, 520, 90, 480, 400, 80, 330};
    Mat4 pm;
    CHECK(pinModel(q, &pm), "pinModel");
    const float ap[4][2] = {{-0.5f, -0.5f}, {0.5f, -0.5f}, {0.5f, 0.5f}, {-0.5f, 0.5f}};
    for (int i = 0; i < 4; ++i) {
        float x, y;
        apply(pm, ap[i][0], ap[i][1], &x, &y);
        CHECK(std::fabs(x - q[2 * i]) < 0.05f && std::fabs(y - q[2 * i + 1]) < 0.05f, "corner %d -> %f %f", i, x, y);
    }
    // the centre of the picture is where the diagonals cross (not the mean of the corners)
    {
        float x, y;
        apply(pm, 0.f, 0.f, &x, &y);
        // intersection of TL-BR and TR-BL
        const double x1 = q[0], y1 = q[1], x2 = q[4], y2 = q[5], x3 = q[2], y3 = q[3], x4 = q[6], y4 = q[7];
        const double d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        const double ix = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / d;
        const double iy = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / d;
        CHECK(std::fabs(x - ix) < 0.1 && std::fabs(y - iy) < 0.1, "centre %f %f vs diagonals %f %f", x, y, ix, iy);
    }
    // 2) degenerate quads are refused
    const float bow[8] = {0, 0, 100, 100, 100, 0, 0, 100};   // bow-tie
    const float line[8] = {0, 0, 50, 0, 100, 0, 150, 0};
    CHECK(!pinModel(bow, &pm), "bow-tie refused");
    CHECK(!pinModel(line, &pm), "line refused");

    // 3) pictureToCanvas == model * aPos, flat and 3D, with a crop
    const float W = 1080, H = 1920;
    const float cwid = 0.8f, chei = 0.6f, cx = 0.1f, cy = 0.25f;
    const float cW = 1000.f * 1.78f, cH = 1000.f;  // picture shape 16:9
    Mat4 flat = layerModel(W, H, cW * cwid, cH * chei, 30.f, -45.f, 1.2f, 1.2f, 17.f);
    Mat4 d3 = Mat4::flattenZ() * layerModel3d(W, H, cW * cwid, cH * chei, 20.f, -10.f, 50.f, 30.f, 1.f, 1.f, 8.f, 25.f, -15.f, 3000.f);
    const Mat4* ms[2] = {&flat, &d3};
    for (int k = 0; k < 2; ++k) {
        const float pu[4][2] = {{0.1f, 0.25f}, {0.9f, 0.25f}, {0.9f, 0.85f}, {0.1f, 0.85f}};  // the visible window corners
        for (int i = 0; i < 4; ++i) {
            float x1 = 0, y1 = 0, x2 = 0, y2 = 0;
            CHECK(pictureToCanvas(*ms[k], cx, cy, cwid, chei, pu[i][0], pu[i][1], &x1, &y1), "place");
            apply(*ms[k], ap[i][0], ap[i][1], &x2, &y2);
            CHECK(std::fabs(x1 - x2) < 0.05f && std::fabs(y1 - y2) < 0.05f, "mode %d corner %d: %f %f vs %f %f", k, i, x1, y1, x2, y2);
        }
    }
    // 4) pin to the corners of a flat layer == drawing that layer (affine case, e.g. the picture pinned on itself)
    {
        float c4[8];
        for (int i = 0; i < 4; ++i) apply(flat, ap[i][0], ap[i][1], &c4[2 * i], &c4[2 * i + 1]);
        Mat4 pin;
        CHECK(pinModel(c4, &pin), "pin of a flat layer");
        for (float t = -0.4f; t <= 0.4f; t += 0.2f) {
            float a, b, c, d;
            apply(pin, t, -t * 0.7f, &a, &b);
            apply(flat, t, -t * 0.7f, &c, &d);
            CHECK(std::fabs(a - c) < 0.05f && std::fabs(b - d) < 0.05f, "interior point %f", t);
        }
    }
    printf("%d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
