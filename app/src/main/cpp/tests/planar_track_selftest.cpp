// Host test of the planar tracker + homography maths.
// g++ -std=c++17 -O2 -Wall -Wextra -I../engine planar_track_selftest.cpp ../engine/planar_track.cpp && ./a.out
#include <cmath>
#include <cstdio>
#include <vector>

#include "homography.h"
#include "planar_track.h"

using namespace am;
static int fails = 0, checks = 0;
#define CHECK(c, ...) do { ++checks; if (!(c)) { ++fails; printf("FAIL %s:%d ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static float hash2(int x, int y) {
    uint32_t h = (uint32_t)x * 374761393u + (uint32_t)y * 668265263u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return (float)((h ^ (h >> 16)) & 0xFFFF) / 65535.f;
}
// smooth value noise, several octaves: 0..255
static float noise(float x, float y) {
    float sum = 0, amp = 1, tot = 0, f = 1.f / 24.f;
    for (int o = 0; o < 4; ++o) {
        const float fx = x * f, fy = y * f;
        const int xi = (int)std::floor(fx), yi = (int)std::floor(fy);
        const float tx = fx - xi, ty = fy - yi;
        const float sx = tx * tx * (3 - 2 * tx), sy = ty * ty * (3 - 2 * ty);
        const float a = hash2(xi, yi), b = hash2(xi + 1, yi), c = hash2(xi, yi + 1), d = hash2(xi + 1, yi + 1);
        sum += amp * ((a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy);
        tot += amp;
        amp *= 0.55f;
        f *= 2.1f;
    }
    return 255.f * sum / tot;
}

int main() {
    // ---- homography maths ----
    {
        const float sq[8] = {10, 20, 300, 40, 280, 200, 30, 180};
        double H[9];
        CHECK(squareToQuad(sq, H), "squareToQuad");
        const double st[4][2] = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i = 0; i < 4; ++i) {
            double x, y;
            hApply(H, st[i][0], st[i][1], &x, &y);
            CHECK(std::fabs(x - sq[2 * i]) < 1e-6 && std::fabs(y - sq[2 * i + 1]) < 1e-6, "corner %d -> %f %f", i, x, y);
        }
        // centre of a perspective quad is NOT the average of the corners' midpoint in general, but must be inside
        double cx, cy;
        hApply(H, 0.5, 0.5, &cx, &cy);
        CHECK(cx > 30 && cx < 280 && cy > 40 && cy < 200, "centre inside");
        // fit recovers a known homography from 40 exact points
        const double Hk[9] = {1.1, 0.12, 15, -0.08, 0.95, -9, 0.0004, -0.0002, 1};
        std::vector<float> s, d;
        std::vector<int> idx;
        for (int i = 0; i < 40; ++i) {
            const float x = 20 + 11.f * (i % 8), y = 15 + 13.f * (i / 8);
            double X, Y;
            hApply(Hk, x, y, &X, &Y);
            s.push_back(x); s.push_back(y); d.push_back((float)X); d.push_back((float)Y);
            idx.push_back(i);
        }
        double Hf[9];
        CHECK(hFit(s.data(), d.data(), idx.data(), 40, Hf), "hFit");
        for (int k = 0; k < 9; ++k) CHECK(std::fabs(Hf[k] - Hk[k]) < 1e-3 * (1 + std::fabs(Hk[k])), "H[%d] %g vs %g", k, Hf[k], Hk[k]);
        const float bad[8] = {0, 0, 10, 10, 20, 20, 5, 5};
        CHECK(!quadConvex(bad), "collinear is not convex");
    }

    // ---- tracker on a synthetic video: a plane moving with perspective, static cluttered background ----
    const int W = 480, H = 270, N = 90;
    const float planeQuad[8] = {120, 60, 360, 70, 350, 210, 110, 200};  // first-frame surface (corners in the picture)
    std::vector<float> frame((size_t)W * H);
    auto motion = [&](int k, double* M) {  // plane(first frame) -> frame k, smooth drift + turn + zoom + perspective
        const double t = k / (double)(N - 1);
        const double ang = 0.18 * std::sin(t * 3.0) + 0.1 * t, sc = 1.0 + 0.18 * t, tx = 40 * t, ty = 14 * std::sin(t * 4.0);
        const double cx = 240, cy = 135, c = std::cos(ang) * sc, s = std::sin(ang) * sc;
        const double A[9] = {c, -s, cx + tx - c * cx + s * cy, s, c, cy + ty - s * cx - c * cy, 0, 0, 1};
        const double P[9] = {1, 0, 0, 0, 1, 0, 0.0007 * std::sin(t * 2.5), -0.0004 * t, 1};
        for (int r = 0; r < 3; ++r) for (int q = 0; q < 3; ++q) M[r * 3 + q] = A[r * 3] * P[q] + A[r * 3 + 1] * P[3 + q] + A[r * 3 + 2] * P[6 + q];
    };
    auto render = [&](int k) {
        double M[9];
        motion(k, M);
        // inverse of M
        const double a = M[0], b = M[1], c = M[2], d = M[3], e = M[4], f = M[5], g = M[6], h = M[7], i = M[8];
        const double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        const double inv[9] = {(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
                               (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
                               (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det};
        float q[8];
        for (int j = 0; j < 4; ++j) { double X, Y; hApply(M, planeQuad[2 * j], planeQuad[2 * j + 1], &X, &Y); q[2 * j] = (float)X; q[2 * j + 1] = (float)Y; }
        for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
            double u, v;
            hApply(inv, x, y, &u, &v);
            // inside the (moved) surface? test in plane space
            bool inside = u >= 100 && u <= 380 && v >= 40 && v <= 230;
            frame[(size_t)y * W + x] = inside ? noise((float)u + 1000.f, (float)v + 500.f) : noise((float)x * 0.9f + 77.f, (float)y * 0.9f + 3000.f) * 0.8f + 20.f;
        }
        (void)q;
    };
    // plane region test uses 100..380 x 40..230, which contains planeQuad (120..360 x 60..210)
    PlanarTracker tr;
    render(0);
    CHECK(tr.start(frame.data(), W, H, planeQuad), "start finds corners (n=%d)", tr.pointCount());
    printf("start points: %d\n", tr.pointCount());
    double worst = 0, sum = 0;
    int lost = -1;
    for (int k = 1; k < N; ++k) {
        render(k);
        if (!tr.step(frame.data(), W, H)) { lost = k; break; }
        double M[9];
        motion(k, M);
        double err = 0;
        for (int j = 0; j < 4; ++j) {
            double X, Y;
            hApply(M, planeQuad[2 * j], planeQuad[2 * j + 1], &X, &Y);
            err = std::fmax(err, std::hypot(X - tr.quad()[2 * j], Y - tr.quad()[2 * j + 1]));
        }
        worst = std::fmax(worst, err);
        sum += err;
        if (k % 15 == 0) printf("frame %2d  worst corner error %.2f px  points %d inliers %d\n", k, err, tr.pointCount(), tr.lastInliers());
    }
    CHECK(lost < 0, "surface lost at frame %d", lost);
    printf("worst corner error over the clip: %.2f px, mean of frame maxima %.2f px\n", worst, sum / (N - 1));
    CHECK(worst < 4.0, "worst corner error %.2f px", worst);

    // ---- global mode (auto stabilize): the whole picture, camera shake + pan + turn + zoom, a moving person in front ----
    {
        PlanarTracker g;
        g.setGlobal(true);
        const float fullQ[8] = {W * 0.03f, H * 0.03f, W * 0.97f, H * 0.03f, W * 0.97f, H * 0.97f, W * 0.03f, H * 0.97f};
        unsigned rs = 99;
        auto rnd = [&]() { rs = rs * 1664525u + 1013904223u; return ((rs >> 8) & 0xFFFF) / 65535.0 * 2.0 - 1.0; };
        std::vector<double> sx(N), sy(N), sa(N);
        for (int k = 0; k < N; ++k) { sx[k] = 3.0 * rnd(); sy[k] = 3.0 * rnd(); sa[k] = 0.01 * rnd(); }
        auto cam = [&](int k, double* M) {   // frame-0 picture -> frame k, similarity about the centre
            const double t = k / (double)(N - 1);
            const double ang = 0.05 * t + sa[k], sc = 1.0 + 0.08 * t, tx = 30 * t + sx[k], ty = 6 * std::sin(t * 3) + sy[k];
            const double cx = W / 2.0, cy = H / 2.0, c = std::cos(ang) * sc, s2 = std::sin(ang) * sc;
            const double A[9] = {c, -s2, cx + tx - c * cx + s2 * cy, s2, c, cy + ty - s2 * cx - c * cy, 0, 0, 1};
            for (int j = 0; j < 9; ++j) M[j] = A[j];
        };
        auto renderG = [&](int k) {
            double M[9];
            cam(k, M);
            const double det = M[0] * M[4] - M[1] * M[3];
            for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
                const double dx = x - M[2], dy = y - M[5];
                const double u = (M[4] * dx - M[1] * dy) / det, v = (-M[3] * dx + M[0] * dy) / det;
                float val = noise((float)u + 200.f, (float)v + 900.f);
                // a "person": a textured block moving on its own, ~13 % of the picture
                const int bx = 180 + (int)(60 * std::sin(k * 0.09)), by = 70 + (int)(25 * std::cos(k * 0.07));
                if (x >= bx && x < bx + 110 && y >= by && y < by + 150) val = noise((float)x * 1.3f + k * 2.f, (float)y * 1.3f + 4000.f);
                frame[(size_t)y * W + x] = val;
            }
        };
        renderG(0);
        CHECK(g.start(frame.data(), W, H, fullQ), "global start (n=%d)", g.pointCount());
        printf("global start points: %d\n", g.pointCount());
        double gworst = 0, pex1 = 0, pex2 = 0, pey1 = 0, pey2 = 0, jit = 0;
        int jn = 0;
        int glost = -1;
        for (int k = 1; k < N; ++k) {
            renderG(k);
            if (!g.step(frame.data(), W, H)) { glost = k; break; }
            double M[9];
            cam(k, M);
            for (int j = 0; j < 4; ++j) {
                double X, Y;
                hApply(M, fullQ[2 * j], fullQ[2 * j + 1], &X, &Y);
                gworst = std::fmax(gworst, std::hypot(X - g.quad()[2 * j], Y - g.quad()[2 * j + 1]));
            }
            {   // error of corner 0 (x, y), for the jitter measure
                double X, Y;
                hApply(M, fullQ[0], fullQ[1], &X, &Y);
                const double ex = g.quad()[0] - X, ey = g.quad()[1] - Y;
                if (k > 2) { const double d2 = (ex - 2 * pex1 + pex2); const double d2y = (ey - 2 * pey1 + pey2); jit += d2 * d2 + d2y * d2y; ++jn; }
                pex2 = pex1; pey2 = pey1; pex1 = ex; pey1 = ey;
                if (k % 10 == 0) printf("  frame %d corner0 error %.2f %.2f\n", k, ex, ey);
            }
        }
        CHECK(glost < 0, "global lost at frame %d", glost);
        printf("global worst corner error: %.2f px (points %d), frame-to-frame jitter (rms 2nd diff) %.3f px\n", gworst, g.pointCount(), std::sqrt(jit / std::max(1, jn)));
        CHECK(std::sqrt(jit / std::max(1, jn)) < 0.5, "tracker jitter too high");
        CHECK(gworst < 8.0, "global worst corner error %.2f px (a steady offset is harmless, jitter is checked above)", gworst);
    }

    // ---- a plain wall cannot be tracked ----
    {
        std::vector<float> flat((size_t)W * H, 128.f);
        PlanarTracker t2;
        CHECK(!t2.start(flat.data(), W, H, planeQuad), "flat picture must be refused");
        const float tiny[8] = {10, 10, 11, 10, 11, 11, 10, 11};
        render(0);
        PlanarTracker t3;
        CHECK(!t3.start(frame.data(), W, H, tiny), "tiny quad refused");
    }
    printf("%d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
