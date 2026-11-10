// g++ -std=c++17 -O2 -Wall -Wextra -pthread -I../engine frame_interp_selftest.cpp && ./a.out   (header-only)
#include <cmath>
#include <cstdio>
#include <vector>

#include "frame_interp.h"

using namespace am;
static int fails = 0, checks = 0;
#define CHECK(c, ...) do { ++checks; if (!(c)) { ++fails; printf("FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static float hash2(int x, int y) {
    uint32_t h = (uint32_t)x * 374761393u + (uint32_t)y * 668265263u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return (float)((h ^ (h >> 16)) & 0xFFFF) / 65535.f;
}
static float noise(float x, float y) {
    float sum = 0, amp = 1, tot = 0, f = 1.f / 24.f;
    for (int o = 0; o < 4; ++o) {
        const float fx = x * f, fy = y * f;
        const int xi = (int)std::floor(fx), yi = (int)std::floor(fy);
        const float tx = fx - xi, ty = fy - yi;
        const float sx = tx * tx * (3 - 2 * tx), sy = ty * ty * (3 - 2 * ty);
        const float a = hash2(xi, yi), b = hash2(xi + 1, yi), c = hash2(xi, yi + 1), d = hash2(xi + 1, yi + 1);
        sum += amp * ((a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy);
        tot += amp; amp *= 0.55f; f *= 2.1f;
    }
    return 255.f * sum / tot;
}

// scene at time t (0..1): the background moves by (bx, by) px per unit time, a square "person" moves by (px, py) on its own
static void scene(double t, int w, int h, std::vector<uint8_t>* o, double bx, double by, double px, double py) {
    o->assign((size_t)w * h * 4, 255);
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
        float v = noise((float)(x - bx * t) + 300.f, (float)(y - by * t) + 700.f);
        const double cx = 0.5 * w + px * t, cy = 0.5 * h + py * t;
        if (std::fabs(x - cx) < w * 0.12 && std::fabs(y - cy) < h * 0.2) v = noise((float)(x - px * t) * 1.4f + 50.f, (float)(y - py * t) * 1.4f + 9000.f);
        uint8_t* q = o->data() + ((size_t)y * w + x) * 4;
        q[0] = (uint8_t)v; q[1] = (uint8_t)(v * 0.9f + 10); q[2] = (uint8_t)(v * 0.8f + 20); q[3] = 255;
    }
}
static double psnr(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b, int w, int h) {
    double se = 0; size_t n = 0;
    for (int y = 12; y < h - 12; ++y) for (int x = 12; x < w - 12; ++x)
        for (int c = 0; c < 3; ++c) { const double d = (double)a[((size_t)y * w + x) * 4 + c] - b[((size_t)y * w + x) * 4 + c]; se += d * d; ++n; }
    se /= (double)n;
    return se < 1e-9 ? 99.0 : 10.0 * std::log10(255.0 * 255.0 / se);
}

int main() {
    const int w = 360, h = 640;
    struct Case { const char* name; double bx, by, px, py; double minGain; };
    const Case cases[] = {
        {"slow pan 6 px/frame", 6, -2, 6, -2, 3.0},
        {"fast pan 30 px/frame", 30, 12, 30, 12, 3.0},
        {"pan + person moving the other way", 14, 3, -22, 10, 1.5},
    };
    for (const Case& c : cases) {
        std::vector<uint8_t> A, B, T, I, blend((size_t)w * h * 4);
        scene(0, w, h, &A, c.bx, c.by, c.px, c.py);
        scene(1, w, h, &B, c.bx, c.by, c.px, c.py);
        FlowField fl;
        CHECK(interpEstimateFlow(A.data(), B.data(), w, h, &fl), "flow");
        for (double f : {0.25, 0.5, 0.75}) {
            scene(f, w, h, &T, c.bx, c.by, c.px, c.py);
            I.assign((size_t)w * h * 4, 0);
            interpRender(A.data(), B.data(), w, h, fl, (float)f, I.data());
            for (size_t k = 0; k < blend.size(); ++k) blend[k] = (uint8_t)(A[k] * (1 - f) + B[k] * f + 0.5);
            const double pi = psnr(I, T, w, h), pb = psnr(blend, T, w, h);
            printf("%-36s f=%.2f  interpolated %.1f dB   plain cross-fade %.1f dB\n", c.name, f, pi, pb);
            CHECK(pi > pb + c.minGain, "%s f=%.2f: %.1f vs %.1f", c.name, f, pi, pb);
        }
    }
    // f = 0 / 1 give the real frames; tiny pictures are refused
    {
        std::vector<uint8_t> A, B, O((size_t)w * h * 4);
        scene(0, w, h, &A, 5, 0, 5, 0); scene(1, w, h, &B, 5, 0, 5, 0);
        FlowField fl;
        interpEstimateFlow(A.data(), B.data(), w, h, &fl);
        interpRender(A.data(), B.data(), w, h, fl, 0.f, O.data());
        CHECK(O == A, "f=0 is a");
        interpRender(A.data(), B.data(), w, h, fl, 1.f, O.data());
        CHECK(O == B, "f=1 is b");
        CHECK(!interpEstimateFlow(A.data(), B.data(), 8, 8, &fl), "tiny");
        // no motion: the result is the picture
        interpEstimateFlow(A.data(), A.data(), w, h, &fl);
        interpRender(A.data(), A.data(), w, h, fl, 0.5f, O.data());
        CHECK(psnr(O, A, w, h) > 40, "still picture %.1f", psnr(O, A, w, h));
    }
    printf("%d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
