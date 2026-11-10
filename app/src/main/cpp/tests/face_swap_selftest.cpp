// g++ -std=c++17 -O2 -Wall -Wextra -I../engine face_swap_selftest.cpp -o fs && ./fs   (header-only)
#include <chrono>
#include <cmath>
#include <cstdio>
#include <vector>

#include "face_swap.h"

using namespace am;
static int fails = 0, checks = 0;
#define CHECK(c, ...) do { ++checks; if (!(c)) { ++fails; printf("FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static unsigned rs = 12345;
static double rnd() { rs = rs * 1664525u + 1013904223u; return ((rs >> 8) & 0xFFFF) / 65535.0; }

// A synthetic face layout: the oval numbers on an ellipse, the others scattered inside it (centre cx, cy, radii rx, ry).
static std::vector<float> layout(double cx, double cy, double rx, double ry, double rot, std::vector<float>* base = nullptr) {
    std::vector<float> lm(kFaceLm * 2, 0.f);
    std::vector<char> isOval(kFaceLm, 0);
    for (int k = 0; k < 36; ++k) {
        const double a = 2 * M_PI * k / 36.0;
        lm[2 * kFaceOval[k]] = (float)std::cos(a);
        lm[2 * kFaceOval[k] + 1] = (float)std::sin(a);
        isOval[kFaceOval[k]] = 1;
    }
    unsigned save = rs; rs = 777;   // the same scatter every call
    for (int i = 0; i < kFaceLm; ++i) {
        if (isOval[i]) continue;
        const double r = std::sqrt(rnd()) * 0.92, a = rnd() * 2 * M_PI;
        lm[2 * i] = (float)(r * std::cos(a));
        lm[2 * i + 1] = (float)(r * std::sin(a));
    }
    rs = save;
    if (base) *base = lm;
    std::vector<float> o(lm.size());
    for (int i = 0; i < kFaceLm; ++i) {
        const double x = lm[2 * i] * rx, y = lm[2 * i + 1] * ry;
        o[2 * i] = (float)(cx + x * std::cos(rot) - y * std::sin(rot));
        o[2 * i + 1] = (float)(cy + x * std::sin(rot) + y * std::cos(rot));
    }
    return o;
}

static void paint(std::vector<uint8_t>* p, int w, int h, int r0, int g0, int b0, int seed) {
    p->assign((size_t)w * h * 4, 255);
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
        const double v = 0.5 + 0.5 * std::sin(x * 0.11 + seed) * std::cos(y * 0.07 - seed);
        uint8_t* q = p->data() + ((size_t)y * w + x) * 4;
        q[0] = (uint8_t)std::min(255.0, r0 * (0.6 + 0.8 * v));
        q[1] = (uint8_t)std::min(255.0, g0 * (0.6 + 0.8 * (1 - v)));
        q[2] = (uint8_t)std::min(255.0, b0 * (0.7 + 0.6 * v));
    }
}

int main() {
    const int sw = 400, sh = 500, dw = 640, dh = 480;
    std::vector<uint8_t> src, dst, orig;
    paint(&src, sw, sh, 220, 120, 60, 1);      // warm, bright "photo"
    paint(&dst, dw, dh, 90, 110, 140, 5);      // cool, dark "video frame"
    orig = dst;
    const std::vector<float> sl = layout(200, 250, 130, 170, 0.0);
    // the video face: smaller, turned 15 degrees, somewhere else
    const std::vector<float> dl = layout(330, 230, 90, 120, 15 * M_PI / 180);

    FaceSource fs;
    CHECK(swapSetSource(&fs, src.data(), sw, sh, sl.data()), "source rejected");
    printf("triangles kept: %zu\n", fs.tri.size() / 3);
    CHECK(fs.tri.size() / 3 > 600, "too few triangles");

    // 1) no colour matching: a point inside the face shows the photo's colour at the same landmark
    {
        std::vector<uint8_t> d = dst;
        CHECK(swapApply(fs, d.data(), dw, dh, dl.data(), 0.f), "apply");
        int bad = 0, tested = 0;
        for (int i = 0; i < kFaceLm; ++i) {
            const double nx = (sl[2 * i] - 200) / 130.0, ny = (sl[2 * i + 1] - 250) / 170.0;
            if (nx * nx + ny * ny > 0.4) continue;   // the middle of the face, away from the soft edge
            const int x = (int)dl[2 * i], y = (int)dl[2 * i + 1];
            const int u = (int)sl[2 * i], v = (int)sl[2 * i + 1];
            ++tested;
            for (int c = 0; c < 3; ++c) if (std::abs((int)d[((size_t)y * dw + x) * 4 + c] - (int)src[((size_t)v * sw + u) * 4 + c]) > 12) { ++bad; break; }
        }
        printf("inner landmarks tested %d, off by >12: %d\n", tested, bad);
        CHECK(tested > 50 && bad <= tested / 10, "warp lands wrong: %d of %d", bad, tested);
        // far from the face nothing changed
        int changed = 0;
        for (int y = 0; y < dh; ++y) for (int x = 0; x < 120; ++x) for (int c = 0; c < 3; ++c) if (d[((size_t)y * dw + x) * 4 + c] != orig[((size_t)y * dw + x) * 4 + c]) ++changed;
        CHECK(changed == 0, "picture changed away from the face (%d)", changed);
    }
    // 2) full colour matching: the face takes the frame's tone
    {
        std::vector<uint8_t> d = dst;
        CHECK(swapApply(fs, d.data(), dw, dh, dl.data(), 1.f), "apply 1");
        double mf[3] = {0, 0, 0}, mo[3] = {0, 0, 0};
        int n = 0;
        for (int dy = -20; dy <= 20; ++dy) for (int dx = -20; dx <= 20; ++dx) {
            const int x = 330 + dx, y = 230 + dy;
            for (int c = 0; c < 3; ++c) { mf[c] += d[((size_t)y * dw + x) * 4 + c]; mo[c] += orig[((size_t)y * dw + x) * 4 + c]; }
            ++n;
        }
        for (int c = 0; c < 3; ++c) { mf[c] /= n; mo[c] /= n; }
        printf("face centre mean after match  %.0f %.0f %.0f   frame was %.0f %.0f %.0f   photo %d %d %d\n", mf[0], mf[1], mf[2], mo[0], mo[1], mo[2], 220, 120, 60);
        for (int c = 0; c < 3; ++c) CHECK(std::fabs(mf[c] - mo[c]) < 25, "tone channel %d: %.0f vs %.0f", c, mf[c], mo[c]);
        // and the picture is not just the old one
        int diff = 0;
        for (size_t i = 0; i < d.size(); i += 4) if (std::abs((int)d[i] - (int)orig[i]) > 6) ++diff;
        CHECK(diff > 5000, "face not pasted (%d)", diff);
    }
    // 3) soft edge: no hard jump between neighbouring pixels across the oval edge
    {
        std::vector<uint8_t> d = dst;
        swapApply(fs, d.data(), dw, dh, dl.data(), 1.f);
        int maxStep = 0;
        const int y = 230;
        for (int x = 200; x < 460; ++x) maxStep = std::max(maxStep, std::abs((int)d[((size_t)y * dw + x + 1) * 4] - (int)d[((size_t)y * dw + x) * 4]));
        printf("largest horizontal step across the face: %d\n", maxStep);
        CHECK(maxStep < 40, "hard edge %d", maxStep);
    }
    // 4) bad input
    {
        std::vector<uint8_t> d = dst;
        std::vector<float> bad = dl;
        bad[10] = NAN;
        CHECK(!swapApply(fs, d.data(), dw, dh, bad.data(), 1.f), "NaN refused");
        std::vector<float> tiny = layout(300, 200, 2, 2, 0);
        CHECK(!swapApply(fs, d.data(), dw, dh, tiny.data(), 1.f), "tiny face refused");
        FaceSource none;
        CHECK(!swapApply(none, d.data(), dw, dh, dl.data(), 1.f), "no source refused");
        std::vector<float> flat(kFaceLm * 2, 5.f);
        CHECK(!swapSetSource(&none, src.data(), sw, sh, flat.data()), "flat landmarks refused");
        // a face half outside the picture does not crash
        std::vector<float> edge = layout(20, 20, 90, 120, 0.3);
        swapApply(fs, d.data(), dw, dh, edge.data(), 1.f);
    }
    // 5) speed: one face of ~300 px
    {
        std::vector<uint8_t> d = dst;
        const std::vector<float> big = layout(320, 240, 170, 220, 0.2);
        const auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < 20; ++i) swapApply(fs, d.data(), dw, dh, big.data(), 0.9f);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / 20;
        printf("swapApply on a 340x440 px face: %.1f ms per frame\n", ms);
    }
    printf("%d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
