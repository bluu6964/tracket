// g++ -std=c++17 -O2 -Wall -Wextra -I../engine remove_plate_selftest.cpp ../engine/remove_plate.cpp && ./a.out   (no GL)
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "homography.h"
#include "remove_plate.h"

using namespace am;
static int fails = 0, checks = 0;
#define CHECK(c, ...) do { ++checks; if (!(c)) { ++fails; printf("FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static const int PW = 640, PH = 360;

static bool invert3(const double* m, double* o) {
    const double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    const double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    const double det = a * A + b * B + c * C;
    if (std::fabs(det) < 1e-18) return false;
    const double r = 1.0 / det;
    o[0] = A * r; o[1] = -(b * i - c * h) * r; o[2] = (b * f - c * e) * r;
    o[3] = B * r; o[4] = (a * i - c * g) * r; o[5] = -(a * f - c * d) * r;
    o[6] = C * r; o[7] = -(a * h - b * g) * r; o[8] = (a * e - b * d) * r;
    return true;
}

struct Scene {
    bool detail;          // high frequency texture
    float bx, by, br;     // the flaw (surface coordinates)
};

// The clean surface.
static void clean(bool detail, float s, float t, float* rgb) {
    float base = 90.f + 90.f * s + 40.f * t;
    if (detail) {
        const int cx = (int)std::floor(s * 40.f), cy = (int)std::floor(t * 40.f);
        base += ((cx + cy) & 1) ? 28.f : -28.f;
    } else {
        base += 12.f * std::sin(s * 9.f) * std::cos(t * 7.f);
    }
    rgb[0] = base;
    rgb[1] = base * 0.9f + 8.f;
    rgb[2] = base * 0.8f + 16.f;
}

// Renders a frame of the surface seen through the quad [q] (uv), with the flaw and a brightness factor.
static std::vector<uint32_t> render(const Scene& sc, const float* quadUv, float gain) {
    float qp[8];
    for (int i = 0; i < 4; ++i) { qp[2 * i] = quadUv[2 * i] * PW; qp[2 * i + 1] = quadUv[2 * i + 1] * PH; }
    double H[9], Hi[9];
    squareToQuad(qp, H);
    invert3(H, Hi);
    std::vector<uint32_t> img((size_t)PW * PH);
    for (int y = 0; y < PH; ++y)
        for (int x = 0; x < PW; ++x) {
            double s, t;
            hApply(Hi, x + 0.5, y + 0.5, &s, &t);
            float rgb[3] = {60, 60, 60};
            if (s >= 0 && s <= 1 && t >= 0 && t <= 1) {
                clean(sc.detail, (float)s, (float)t, rgb);
                const float dx = (float)s - sc.bx, dy = (float)t - sc.by;
                if (dx * dx + dy * dy < sc.br * sc.br) { rgb[0] = 20; rgb[1] = 20; rgb[2] = 25; }
            }
            auto cv = [&](float v) { v *= gain; return (uint32_t)(v < 0 ? 0 : (v > 255 ? 255 : (v + 0.5f))); };
            img[(size_t)y * PW + x] = 0xFF000000u | (cv(rgb[0]) << 16) | (cv(rgb[1]) << 8) | cv(rgb[2]);
        }
    return img;
}

static std::vector<float> grayOf(const std::vector<uint32_t>& img) {
    std::vector<float> g(img.size());
    for (size_t i = 0; i < img.size(); ++i)
        g[i] = 0.299f * ((img[i] >> 16) & 255) + 0.587f * ((img[i] >> 8) & 255) + 0.114f * (img[i] & 255);
    return g;
}

// The painted area on the picture of frame 0: a disk around the flaw (a bit larger), in a mask picture of 320 x 180.
static std::vector<uint8_t> paint(const float* quadUv, float bx, float by, float rad, int mw, int mh) {
    float qm[8];
    for (int i = 0; i < 4; ++i) { qm[2 * i] = quadUv[2 * i] * mw; qm[2 * i + 1] = quadUv[2 * i + 1] * mh; }
    double H[9], Hi[9];
    squareToQuad(qm, H);
    invert3(H, Hi);
    std::vector<uint8_t> m((size_t)mw * mh, 0);
    for (int y = 0; y < mh; ++y)
        for (int x = 0; x < mw; ++x) {
            double s, t;
            hApply(Hi, x + 0.5, y + 0.5, &s, &t);
            const double dx = s - bx, dy = t - by;
            if (dx * dx + dy * dy < rad * rad) m[(size_t)y * mw + x] = 255;
        }
    return m;
}

static void quadK(int k, float* q) {
    const float base[8] = {0.15f, 0.10f, 0.85f, 0.15f, 0.90f, 0.85f, 0.10f, 0.90f};
    const float jx = 0.012f * (float)((k * 7) % 5 - 2), jy = 0.010f * (float)((k * 3) % 5 - 2);
    for (int i = 0; i < 4; ++i) {
        const float sgn = (i % 2 == 0) ? 1.f : -1.f;
        q[2 * i] = base[2 * i] + jx + 0.004f * k * sgn;
        q[2 * i + 1] = base[2 * i + 1] + jy + 0.003f * k * sgn;
    }
}

// Error of the patch against the clean surface, over the painted pixels (patch pixels with alpha > 250).
static double patchError(const RemovePlate& rp, const std::vector<uint8_t>& rgba, bool detail, int* covered) {
    const float* d = rp.domain();
    double sum = 0;
    int n = 0;
    for (int y = 0; y < rp.plateH(); ++y)
        for (int x = 0; x < rp.plateW(); ++x) {
            const size_t i = (size_t)y * rp.plateW() + x;
            if (rgba[i * 4 + 3] < 250) continue;
            const float s = d[0] + ((float)x + 0.5f) / rp.plateW() * (d[2] - d[0]);
            const float t = d[1] + ((float)y + 0.5f) / rp.plateH() * (d[3] - d[1]);
            float want[3];
            clean(detail, s, t, want);
            sum += (std::fabs(rgba[i * 4] - want[0]) + std::fabs(rgba[i * 4 + 1] - want[1]) + std::fabs(rgba[i * 4 + 2] - want[2])) / 3.0;
            ++n;
        }
    *covered = n;
    return n ? sum / n : 1e9;
}

int main() {
    // 1) a flaw glued to a smooth surface (a stain): the fill from the surround reproduces the clean surface
    {
        Scene sc{false, 0.5f, 0.5f, 0.06f};
        float q0[8];
        quadK(0, q0);
        RemovePlate rp;
        auto mask = paint(q0, 0.5, 0.5, 0.075, 320, 180);
        CHECK(rp.setMask(q0, mask.data(), 320, 180, PW, PH, 512), "setMask");
        CHECK(rp.plateW() >= 8 && rp.plateH() >= 8, "plate size");
        const float* d = rp.domain();
        CHECK(d[0] < 0.5f && d[2] > 0.5f && d[1] < 0.5f && d[3] > 0.5f, "window around the flaw: %f %f %f %f", d[0], d[1], d[2], d[3]);
        CHECK(d[2] - d[0] < 0.6f && d[3] - d[1] < 0.6f, "window is a part of the surface");
        for (int k = 0; k < 5; ++k) {
            float q[8];
            quadK(k, q);
            auto img = render(sc, q, 1.f);
            CHECK(rp.addFrame(img.data(), PW, PH, q), "addFrame");
        }
        std::vector<uint8_t> rgba;
        CHECK(rp.build(true, &rgba), "build");
        CHECK(rgba.size() == (size_t)rp.plateW() * rp.plateH() * 4, "rgba size");
        int cov = 0;
        const double e = patchError(rp, rgba, false, &cov);
        CHECK(cov > 200, "covered pixels %d", cov);
        CHECK(e < 7.0, "stain: mean error %f (grain included)", e);
        // the centre of the plate is fully covered, the corners of the plate are untouched
        const size_t c = ((size_t)rp.plateH() / 2 * rp.plateW() + rp.plateW() / 2) * 4;
        CHECK(rgba[c + 3] == 255, "centre alpha %d", rgba[c + 3]);
        CHECK(rgba[3] == 0, "corner alpha %d", rgba[3]);
        // premultiplied: no colour channel above alpha
        bool ok = true;
        for (size_t i = 0; i < rgba.size(); i += 4)
            for (int k = 0; k < 3; ++k)
                if (rgba[i + k] > rgba[i + 3]) ok = false;
        CHECK(ok, "premultiplied");
        // the stain (value ~20) is gone
        CHECK(rgba[c] > 70, "stain still there: %d", rgba[c]);
    }

    // 2) a flaw that moves over a detailed surface: the frames bring the detail back, the surround cannot
    {
        float q0[8];
        quadK(0, q0);
        auto sceneAt = [&](int k) {
            const float cx[9] = {0.50f, 0.30f, 0.70f, 0.30f, 0.70f, 0.50f, 0.25f, 0.75f, 0.50f};
            const float cy[9] = {0.50f, 0.30f, 0.30f, 0.70f, 0.70f, 0.25f, 0.50f, 0.50f, 0.75f};
            return Scene{true, cx[k], cy[k], 0.06f};
        };
        auto mask = paint(q0, 0.5, 0.5, 0.075, 320, 180);
        double err[2];
        for (int mode = 0; mode < 2; ++mode) {
            RemovePlate rp;
            CHECK(rp.setMask(q0, mask.data(), 320, 180, PW, PH, 512), "setMask");
            for (int k = 0; k < 9; ++k) {
                float q[8];
                quadK(0, q);   // a locked camera keeps the test about the median, not about the tracker
                auto img = render(sceneAt(k), q, 1.f);
                CHECK(rp.addFrame(img.data(), PW, PH, q), "addFrame");
            }
            std::vector<uint8_t> rgba;
            CHECK(rp.build(mode == 1, &rgba), "build");
            int cov = 0;
            err[mode] = patchError(rp, rgba, true, &cov);
            CHECK(cov > 200, "covered %d", cov);
        }
        printf("moving flaw on a detailed surface: surround error %.2f, frames error %.2f\n", err[0], err[1]);
        CHECK(err[1] < 10.0, "frames error %f", err[1]);
        CHECK(err[1] < err[0] * 0.7, "frames (%f) must beat the surround (%f)", err[1], err[0]);
    }

    // 3) light: the ring luma follows the brightness of the frame
    {
        Scene sc{false, 0.5f, 0.5f, 0.06f};
        float q0[8];
        quadK(0, q0);
        auto mask = paint(q0, 0.5, 0.5, 0.075, 320, 180);
        RemovePlate rp;
        CHECK(rp.setMask(q0, mask.data(), 320, 180, PW, PH, 512), "setMask");
        const auto g1 = grayOf(render(sc, q0, 1.f));
        const auto g2 = grayOf(render(sc, q0, 1.3f));
        float q3[8];
        quadK(3, q3);
        const auto g3 = grayOf(render(sc, q3, 0.8f));
        const float l1 = rp.ringLuma(g1.data(), PW, PH, q0), l2 = rp.ringLuma(g2.data(), PW, PH, q0), l3 = rp.ringLuma(g3.data(), PW, PH, q3);
        CHECK(l1 > 20.f, "ring luma %f", l1);
        CHECK(std::fabs(l2 / l1 - 1.3f) < 0.03f, "gain 1.3 -> %f", l2 / l1);
        CHECK(std::fabs(l3 / l1 - 0.8f) < 0.05f, "gain 0.8 on another camera place -> %f", l3 / l1);
    }

    // 4) bad input
    {
        RemovePlate rp;
        float q0[8];
        quadK(0, q0);
        std::vector<uint8_t> empty(320 * 180, 0);
        CHECK(!rp.setMask(q0, empty.data(), 320, 180, PW, PH, 512), "empty paint refused");
        const float bow[8] = {0.1f, 0.1f, 0.9f, 0.9f, 0.9f, 0.1f, 0.1f, 0.9f};
        std::vector<uint8_t> full(320 * 180, 255);
        CHECK(!rp.setMask(bow, full.data(), 320, 180, PW, PH, 512), "bow-tie corners refused");
        std::vector<uint8_t> out;
        CHECK(!rp.build(true, &out), "build without frames");
    }

    // 5) subQuad: the whole window is the quad itself, a part of it lies on the quad's edges
    {
        float q[8] = {0.15f, 0.10f, 0.85f, 0.20f, 0.90f, 0.85f, 0.10f, 0.90f};
        const float whole[4] = {0, 0, 1, 1};
        float o[8];
        CHECK(RemovePlate::subQuad(q, whole, o), "subQuad");
        for (int i = 0; i < 8; ++i) CHECK(std::fabs(o[i] - q[i]) < 1e-4f, "whole window corner %d: %f vs %f", i, o[i], q[i]);
        const float part[4] = {0.25f, 0.f, 0.75f, 1.f};
        CHECK(RemovePlate::subQuad(q, part, o), "subQuad part");
        // top-left of the part lies on the top edge (TL -> TR)
        const double cr = (double)(q[2] - q[0]) * (o[1] - q[1]) - (double)(q[3] - q[1]) * (o[0] - q[0]);
        CHECK(std::fabs(cr) < 1e-4, "on the top edge: %f", cr);
        const float zero[8] = {0.3f, 0.3f, 0.3f, 0.3f, 0.3f, 0.3f, 0.3f, 0.3f};
        CHECK(!RemovePlate::subQuad(zero, whole, o), "degenerate quad refused");
    }

    printf("%d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
