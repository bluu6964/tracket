// g++ -std=c++17 -O2 -Wall -Wextra -I../engine stabilize_selftest.cpp && ./a.out   (header-only, no GL)
#include <cmath>
#include <cstdio>
#include <vector>

#include "stabilize.h"

using namespace am;
static int fails = 0, checks = 0;
#define CHECK(c, ...) do { ++checks; if (!(c)) { ++fails; printf("FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

int main() {
    // the surface drifts by (dx, dy) and turns a little: the picture must move back
    const float base[8] = {0.2f, 0.2f, 0.8f, 0.22f, 0.78f, 0.8f, 0.18f, 0.78f};
    const int n = 6;
    std::vector<float> q((size_t)n * 8), out((size_t)n * 8);
    const float dx[n] = {0, 0.01f, -0.02f, 0.03f, 0.015f, -0.01f}, dy[n] = {0, -0.01f, 0.02f, 0.01f, -0.03f, 0.02f};
    for (int i = 0; i < n; ++i)
        for (int k = 0; k < 4; ++k) {
            q[i * 8 + 2 * k] = base[2 * k] + dx[i];
            q[i * 8 + 2 * k + 1] = base[2 * k + 1] + dy[i];
        }
    float z = stabilizeQuads(q.data(), n, false, 1.f, out.data());
    CHECK(std::fabs(z - 1.f) < 1e-6f, "zoom %f", z);
    for (int i = 0; i < n; ++i) {
        const float want[8] = {-dx[i], -dy[i], 1 - dx[i], -dy[i], 1 - dx[i], 1 - dy[i], -dx[i], 1 - dy[i]};
        for (int k = 0; k < 8; ++k) CHECK(std::fabs(out[i * 8 + k] - want[k]) < 1e-4f, "frame %d value %d: %f vs %f", i, k, out[i * 8 + k], want[k]);
    }
    // the tracked corners, moved by the picture's move, land on the reference corners (perspective turn included)
    {
        std::vector<float> r((size_t)3 * 8), o(24);
        const float q0[8] = {0.2f, 0.2f, 0.8f, 0.2f, 0.8f, 0.8f, 0.2f, 0.8f};
        const float q1[8] = {0.25f, 0.18f, 0.84f, 0.26f, 0.8f, 0.85f, 0.22f, 0.78f};
        for (int k = 0; k < 8; ++k) { r[k] = q0[k]; r[8 + k] = q1[k]; r[16 + k] = q0[k]; }
        CHECK(stabilizeQuads(r.data(), 3, false, 1.f, o.data()) >= 1.f, "stabilize");
        // picture corner c of frame 1 sits at out corner; surface corner of frame 1 as a picture point must map to q0.
        // Check via the picture quad: the point q1[k] (a picture point) is carried to q0[k]  <=>  it is at the same place
        // in the picture quad's own frame: bilinear-free check with the unit square -> out quad homography.
        double H[9];
        CHECK(squareToQuad(o.data() + 8, H), "H");
        for (int k = 0; k < 4; ++k) {
            double x, y;
            hApply(H, q1[2 * k], q1[2 * k + 1], &x, &y);
            CHECK(std::fabs(x - q0[2 * k]) < 1e-3 && std::fabs(y - q0[2 * k + 1]) < 1e-3, "corner %d -> %f %f want %f %f", k, x, y, q0[2 * k], q0[2 * k + 1]);
        }
    }
    // auto zoom: the canvas stays covered on every frame
    z = stabilizeQuads(q.data(), n, true, 1.f, out.data());
    CHECK(z > 1.f && z < 1.5f, "auto zoom %f", z);
    for (int i = 0; i < n; ++i)
        for (int k = 0; k < 4; ++k) {
            const double cu[8] = {0, 0, 1, 0, 1, 1, 0, 1};
            CHECK(stab_detail::inside(out.data() + 8 * i, cu[2 * k], cu[2 * k + 1]), "frame %d corner %d uncovered", i, k);
        }
    // a still camera needs no zoom; bad input is refused; a degenerate frame keeps the last good one
    {
        std::vector<float> s(16), o(16);
        for (int i = 0; i < 2; ++i) for (int k = 0; k < 8; ++k) s[i * 8 + k] = base[k];
        CHECK(std::fabs(stabilizeQuads(s.data(), 2, true, 1.f, o.data()) - 1.f) < 1e-6f, "still camera");
        CHECK(stabilizeQuads(nullptr, 2, true, 1.f, o.data()) < 0.f, "null");
        for (int k = 0; k < 8; ++k) s[8 + k] = 0.3f;
        CHECK(stabilizeQuads(s.data(), 2, false, 1.f, o.data()) >= 1.f, "degenerate frame");
        for (int k = 0; k < 8; ++k) CHECK(std::fabs(o[8 + k] - o[k]) < 1e-6f, "kept the last good frame");
    }
    printf("%d checks, %d failed\n", checks, fails);
        // ---- auto stabilize (camera path smoothing) ----
    {
        // a slow pan (0.1 over 120 frames) + shake (+-0.01 move, +-0.5 deg turn) + tracker noise on the corners
        const int N = 120;
        const float aspect = 16.f / 9.f;
        std::vector<float> Q((size_t)N * 8), O((size_t)N * 8);
        const float full[8] = {0.02f, 0.02f, 0.98f, 0.02f, 0.98f, 0.98f, 0.02f, 0.98f};
        unsigned rs = 7;
        auto rnd = [&]() { rs = rs * 1664525u + 1013904223u; return ((rs >> 8) & 0xFFFF) / 65535.f * 2.f - 1.f; };
        std::vector<double> shakeX(N), shakeY(N), pan(N);
        for (int i = 0; i < N; ++i) {
            pan[i] = 0.1 * i / (N - 1);
            shakeX[i] = 0.01 * rnd();
            shakeY[i] = 0.01 * rnd();
            const double th = 0.0087 * rnd();
            for (int k = 0; k < 4; ++k) {
                const double x = (full[2 * k] - 0.5) * aspect, y = full[2 * k + 1] - 0.5;
                const double xr = x * std::cos(th) - y * std::sin(th), yr = x * std::sin(th) + y * std::cos(th);
                Q[i * 8 + 2 * k] = (float)((xr / aspect + 0.5) + pan[i] + shakeX[i] + 0.001 * rnd());
                Q[i * 8 + 2 * k + 1] = (float)((yr + 0.5) + shakeY[i] + 0.001 * rnd());
            }
        }
        float z = stabilizeSmooth(Q.data(), N, aspect, 12.f, true, 1.f, O.data());
        CHECK(z >= 1.f && z < 1.3f, "smooth zoom %f", z);
        // frame-to-frame jitter of the output centre vs the input centre
        auto cen = [&](const float* a, int i, int c) { return 0.25f * (a[i * 8 + c] + a[i * 8 + 2 + c] + a[i * 8 + 4 + c] + a[i * 8 + 6 + c]); };
        double jin = 0, jout = 0;
        for (int i = 2; i < N; ++i) {
            const double din = cen(Q.data(), i, 0) - 2 * cen(Q.data(), i - 1, 0) + cen(Q.data(), i - 2, 0);
            const double dout = cen(O.data(), i, 0) - 2 * cen(O.data(), i - 1, 0) + cen(O.data(), i - 2, 0);
            jin += din * din; jout += dout * dout;
        }
        // the picture is moved against the camera: input centre moves the same way as the camera, so compare the corrected path
        // (camera + correction) which should be smooth
        double jc = 0;
        for (int i = 2; i < N; ++i) {
            auto cam = [&](int j) { return cen(Q.data(), j, 0) - 0.5 + cen(O.data(), j, 0) - 0.5; };
            const double d = cam(i) - 2 * cam(i - 1) + cam(i - 2);
            jc += d * d;
        }
        printf("jitter (2nd diff energy) in %.3g, corrected %.3g\n", jin, jc);
        CHECK(jc < jin * 0.05, "not smooth enough: %g vs %g", jc, jin);
        // tripod: the whole path is held
        z = stabilizeSmooth(Q.data(), N, aspect, 0.f, false, 1.f, O.data());
        CHECK(z >= 0.f, "tripod");
        for (int i = 0; i < N; ++i) {
            const double cx = cen(Q.data(), i, 0) + cen(O.data(), i, 0) - 1.0;
            CHECK(std::fabs(cx - (cen(Q.data(), 0, 0) - 0.5)) < 0.002, "tripod frame %d centre %f", i, cx);
        }
        CHECK(stabilizeSmooth(Q.data(), 0, aspect, 5.f, true, 1.f, O.data()) < 0.f, "n=0");
        CHECK(stabilizeSmooth(Q.data(), 1, aspect, 5.f, true, 1.f, O.data()) >= 1.f, "n=1");
        // a still camera stays still, no zoom
        std::vector<float> S((size_t)10 * 8), SO((size_t)10 * 8);
        for (int i = 0; i < 10; ++i) for (int k = 0; k < 8; ++k) S[i * 8 + k] = full[k];
        CHECK(std::fabs(stabilizeSmooth(S.data(), 10, aspect, 4.f, true, 1.f, SO.data()) - 1.f) < 1e-5f, "still zoom");
        for (int i = 0; i < 10; ++i) for (int k = 0; k < 8; ++k) CHECK(std::fabs(SO[i * 8 + k] - ((k % 2 == 0) ? (k / 2 == 1 || k / 2 == 2) : (k / 2 >= 2))) < 1e-4f, "still pic %d %d", i, k);
    }
    return fails ? 1 : 0;
}
