// Slow-motion frame interpolation (no library): makes the frames BETWEEN two decoded frames.
//
// Method: both pictures are reduced to a small grey pyramid; a coarse-to-fine block matching (8x8 blocks every 4 px, search around
// the coarser level's guess, a small pull towards that guess so flat areas follow their neighbours) gives the motion A -> B on a
// grid, refined to sub-pixel at the finest level and median-filtered. The new frame at time f (0..1) then takes every pixel from
// A moved back by f * motion and from B moved forward by (1 - f) * motion and mixes the two; where the two disagree (something was
// uncovered, the motion was lost) it falls back to the nearer real frame instead of showing a ghost.
// Plain C++17, no GL / Android: host-testable (tests/frame_interp_selftest.cpp). Pictures are RGBA8, row 0 = any (same for both).
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>

namespace am {

struct FlowField {
    int gw = 0, gh = 0;          // grid nodes
    float ox = 0, oy = 0;        // picture position (px) of node (0, 0)
    float sx = 1, sy = 1;        // picture distance (px) between nodes
    std::vector<float> dx, dy;   // motion A -> B in picture pixels, at the nodes
};

namespace interp_detail {

struct Gray {
    int w = 0, h = 0;
    std::vector<uint8_t> p;
    inline int at(int x, int y) const {
        x = x < 0 ? 0 : (x >= w ? w - 1 : x);
        y = y < 0 ? 0 : (y >= h ? h - 1 : y);
        return p[(size_t)y * w + x];
    }
};

inline Gray grayFromRgba(const uint8_t* rgba, int w, int h, int lw, int lh) {
    Gray g;
    g.w = lw;
    g.h = lh;
    g.p.resize((size_t)lw * lh);
    for (int y = 0; y < lh; ++y) {
        const int y0 = (int)((int64_t)y * h / lh), y1 = std::max(y0 + 1, (int)((int64_t)(y + 1) * h / lh));
        for (int x = 0; x < lw; ++x) {
            const int x0 = (int)((int64_t)x * w / lw), x1 = std::max(x0 + 1, (int)((int64_t)(x + 1) * w / lw));
            int sum = 0, n = 0;
            for (int yy = y0; yy < y1 && yy < h; ++yy)
                for (int xx = x0; xx < x1 && xx < w; ++xx) {
                    const uint8_t* q = rgba + ((size_t)yy * w + xx) * 4;
                    sum += (q[0] * 77 + q[1] * 150 + q[2] * 29) >> 8;
                    ++n;
                }
            g.p[(size_t)y * lw + x] = (uint8_t)(n ? sum / n : 0);
        }
    }
    return g;
}

inline Gray halve(const Gray& s) {
    Gray g;
    g.w = std::max(1, s.w / 2);
    g.h = std::max(1, s.h / 2);
    g.p.resize((size_t)g.w * g.h);
    for (int y = 0; y < g.h; ++y)
        for (int x = 0; x < g.w; ++x)
            g.p[(size_t)y * g.w + x] = (uint8_t)((s.at(2 * x, 2 * y) + s.at(2 * x + 1, 2 * y) + s.at(2 * x, 2 * y + 1) + s.at(2 * x + 1, 2 * y + 1) + 2) >> 2);
    return g;
}

constexpr int kBlock = 8, kStride = 4;

struct Grid {
    int gw = 0, gh = 0;
    std::vector<float> dx, dy;
};

inline Grid makeGrid(const Gray& g) {
    Grid r;
    r.gw = std::max(1, (g.w - kBlock) / kStride + 1);
    r.gh = std::max(1, (g.h - kBlock) / kStride + 1);
    r.dx.assign((size_t)r.gw * r.gh, 0.f);
    r.dy.assign((size_t)r.gw * r.gh, 0.f);
    return r;
}

// Bilinear read of a grid at (gx, gy) in node units.
inline void sampleGrid(const Grid& g, float gx, float gy, float* ox, float* oy) {
    gx = std::min(std::max(gx, 0.f), (float)(g.gw - 1));
    gy = std::min(std::max(gy, 0.f), (float)(g.gh - 1));
    const int x0 = (int)gx, y0 = (int)gy, x1 = std::min(x0 + 1, g.gw - 1), y1 = std::min(y0 + 1, g.gh - 1);
    const float fx = gx - x0, fy = gy - y0;
    auto at = [&](const std::vector<float>& v, int x, int y) { return v[(size_t)y * g.gw + x]; };
    *ox = (at(g.dx, x0, y0) * (1 - fx) + at(g.dx, x1, y0) * fx) * (1 - fy) + (at(g.dx, x0, y1) * (1 - fx) + at(g.dx, x1, y1) * fx) * fy;
    *oy = (at(g.dy, x0, y0) * (1 - fx) + at(g.dy, x1, y0) * fx) * (1 - fy) + (at(g.dy, x0, y1) * (1 - fx) + at(g.dy, x1, y1) * fx) * fy;
}

inline int sad(const Gray& a, const Gray& b, int bx, int by, int ox, int oy) {
    int s = 0;
    for (int y = 0; y < kBlock; ++y)
        for (int x = 0; x < kBlock; ++x) s += std::abs(a.at(bx + x, by + y) - b.at(bx + x + ox, by + y + oy));
    return s;
}

// One pyramid level: for every node, the best offset near the guess (the parent's flow, doubled).
inline Grid matchLevel(const Gray& a, const Gray& b, const Grid* parent, int range, bool subpixel) {
    Grid g = makeGrid(a);
    const int lambda = 10;   // cost of one pixel away from the guess (the block holds 64 pixels)
    for (int j = 0; j < g.gh; ++j) {
        for (int i = 0; i < g.gw; ++i) {
            const int bx = i * kStride, by = j * kStride;
            float px = 0, py = 0;
            if (parent) {
                sampleGrid(*parent, ((float)(bx + kBlock / 2) * 0.5f - kBlock / 2) / kStride, ((float)(by + kBlock / 2) * 0.5f - kBlock / 2) / kStride, &px, &py);
                px *= 2.f;
                py *= 2.f;
            }
            const int cx = (int)std::lround(px), cy = (int)std::lround(py);
            int best = 1 << 30, bdx = cx, bdy = cy;
            for (int v = -range; v <= range; ++v) {
                for (int u = -range; u <= range; ++u) {
                    const int ox = cx + u, oy = cy + v;
                    const int cost = sad(a, b, bx, by, ox, oy) + lambda * (int)(std::fabs(ox - px) + std::fabs(oy - py)) + (parent ? 0 : lambda / 2 * (std::abs(ox) + std::abs(oy)));
                    if (cost < best) { best = cost; bdx = ox; bdy = oy; }
                }
            }
            float fx = (float)bdx, fy = (float)bdy;
            if (subpixel) {   // parabola through the three costs along each axis
                auto par = [&](int c0, int c1, int c2) {
                    const int den = c0 - 2 * c1 + c2;
                    if (den <= 0) return 0.f;
                    return std::min(0.5f, std::max(-0.5f, 0.5f * (float)(c0 - c2) / (float)den));
                };
                const int c1 = sad(a, b, bx, by, bdx, bdy);
                fx += par(sad(a, b, bx, by, bdx - 1, bdy), c1, sad(a, b, bx, by, bdx + 1, bdy));
                fy += par(sad(a, b, bx, by, bdx, bdy - 1), c1, sad(a, b, bx, by, bdx, bdy + 1));
            }
            g.dx[(size_t)j * g.gw + i] = fx;
            g.dy[(size_t)j * g.gw + i] = fy;
        }
    }
    return g;
}

inline void medianFilter(Grid* g) {
    Grid src = *g;
    auto med9 = [](float* v) { std::nth_element(v, v + 4, v + 9); return v[4]; };
    for (int j = 0; j < g->gh; ++j)
        for (int i = 0; i < g->gw; ++i) {
            float vx[9], vy[9];
            int n = 0;
            for (int dj = -1; dj <= 1; ++dj)
                for (int di = -1; di <= 1; ++di) {
                    const int ii = std::min(std::max(i + di, 0), g->gw - 1), jj = std::min(std::max(j + dj, 0), g->gh - 1);
                    vx[n] = src.dx[(size_t)jj * g->gw + ii];
                    vy[n] = src.dy[(size_t)jj * g->gw + ii];
                    ++n;
                }
            g->dx[(size_t)j * g->gw + i] = med9(vx);
            g->dy[(size_t)j * g->gw + i] = med9(vy);
        }
}

}  // namespace interp_detail

// The motion between two pictures [a] -> [b] (w x h RGBA8). False when a picture is too small.
inline bool interpEstimateFlow(const uint8_t* a, const uint8_t* b, int w, int h, FlowField* out) {
    using namespace interp_detail;
    if (!a || !b || !out || w < 32 || h < 32) return false;
    const float k = std::max(1.f, (float)std::max(w, h) / 320.f);   // work at ~320 px on the long side
    const int lw = std::max(kBlock * 2, (int)std::lround(w / k)), lh = std::max(kBlock * 2, (int)std::lround(h / k));
    Gray a0 = grayFromRgba(a, w, h, lw, lh), b0 = grayFromRgba(b, w, h, lw, lh);
    Gray a1 = halve(a0), b1 = halve(b0), a2 = halve(a1), b2 = halve(b1);
    Grid g2 = matchLevel(a2, b2, nullptr, 5, false);
    Grid g1 = matchLevel(a1, b1, &g2, 2, false);
    Grid g0 = matchLevel(a0, b0, &g1, 2, true);
    medianFilter(&g0);
    out->gw = g0.gw;
    out->gh = g0.gh;
    out->sx = (float)kStride * (float)w / (float)lw;
    out->sy = (float)kStride * (float)h / (float)lh;
    out->ox = (kBlock * 0.5f) * (float)w / (float)lw;
    out->oy = (kBlock * 0.5f) * (float)h / (float)lh;
    const float mx = (float)w / (float)lw, my = (float)h / (float)lh;
    out->dx = g0.dx;
    out->dy = g0.dy;
    for (auto& v : out->dx) v *= mx;
    for (auto& v : out->dy) v *= my;
    return true;
}

namespace interp_detail {

inline void sampleRgba(const uint8_t* p, int w, int h, float x, float y, float* o) {
    x = std::min(std::max(x, 0.f), (float)(w - 1));
    y = std::min(std::max(y, 0.f), (float)(h - 1));
    const int x0 = (int)x, y0 = (int)y, x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
    const float fx = x - x0, fy = y - y0;
    const uint8_t* p00 = p + ((size_t)y0 * w + x0) * 4;
    const uint8_t* p10 = p + ((size_t)y0 * w + x1) * 4;
    const uint8_t* p01 = p + ((size_t)y1 * w + x0) * 4;
    const uint8_t* p11 = p + ((size_t)y1 * w + x1) * 4;
    for (int c = 0; c < 4; ++c)
        o[c] = (p00[c] * (1 - fx) + p10[c] * fx) * (1 - fy) + (p01[c] * (1 - fx) + p11[c] * fx) * fy;
}

inline void renderRows(const uint8_t* a, const uint8_t* b, int w, int h, const FlowField& fl, float f, uint8_t* out, int y0, int y1) {
    Grid g;
    g.gw = fl.gw;
    g.gh = fl.gh;
    for (int y = y0; y < y1; ++y) {
        const float gy = (y - fl.oy) / fl.sy;
        for (int x = 0; x < w; ++x) {
            const float gx = (x - fl.ox) / fl.sx;
            // bilinear flow at (x, y)
            const float cx = std::min(std::max(gx, 0.f), (float)(fl.gw - 1)), cy = std::min(std::max(gy, 0.f), (float)(fl.gh - 1));
            const int i0 = (int)cx, j0 = (int)cy, i1 = std::min(i0 + 1, fl.gw - 1), j1 = std::min(j0 + 1, fl.gh - 1);
            const float tx = cx - i0, ty = cy - j0;
            auto at = [&](const std::vector<float>& v, int i, int j) { return v[(size_t)j * fl.gw + i]; };
            const float dx = (at(fl.dx, i0, j0) * (1 - tx) + at(fl.dx, i1, j0) * tx) * (1 - ty) + (at(fl.dx, i0, j1) * (1 - tx) + at(fl.dx, i1, j1) * tx) * ty;
            const float dy = (at(fl.dy, i0, j0) * (1 - tx) + at(fl.dy, i1, j0) * tx) * (1 - ty) + (at(fl.dy, i0, j1) * (1 - tx) + at(fl.dy, i1, j1) * tx) * ty;
            float sa[4], sb[4];
            sampleRgba(a, w, h, x - f * dx, y - f * dy, sa);
            sampleRgba(b, w, h, x + (1.f - f) * dx, y + (1.f - f) * dy, sb);
            // do the two moved pictures agree? (luma difference)
            const float la = sa[0] * 0.30f + sa[1] * 0.59f + sa[2] * 0.11f, lb = sb[0] * 0.30f + sb[1] * 0.59f + sb[2] * 0.11f;
            const float bad = std::min(1.f, std::max(0.f, (std::fabs(la - lb) - 28.f) / 48.f));
            float plain[4];
            const uint8_t* near = (f < 0.5f ? a : b) + ((size_t)y * w + x) * 4;
            for (int c = 0; c < 4; ++c) plain[c] = near[c];
            uint8_t* o = out + ((size_t)y * w + x) * 4;
            for (int c = 0; c < 4; ++c) {
                const float mix = sa[c] * (1.f - f) + sb[c] * f;
                const float v = mix * (1.f - bad) + plain[c] * bad;
                o[c] = (uint8_t)std::min(255.f, std::max(0.f, v + 0.5f));
            }
        }
    }
}

}  // namespace interp_detail

// The picture at time f (0 = a, 1 = b) between [a] and [b] (w x h RGBA8), given the motion a -> b. [out] = w * h * 4 bytes.
inline void interpRender(const uint8_t* a, const uint8_t* b, int w, int h, const FlowField& fl, float f, uint8_t* out, int threads = 0) {
    f = std::min(std::max(f, 0.f), 1.f);
    if (f <= 0.001f) { std::memcpy(out, a, (size_t)w * h * 4); return; }
    if (f >= 0.999f) { std::memcpy(out, b, (size_t)w * h * 4); return; }
    int n = threads > 0 ? threads : (int)std::thread::hardware_concurrency();
    n = std::max(1, std::min(n, 8));
    if (n == 1 || h < 64) { interp_detail::renderRows(a, b, w, h, fl, f, out, 0, h); return; }
    std::vector<std::thread> pool;
    for (int t = 0; t < n; ++t) {
        const int y0 = (int)((int64_t)h * t / n), y1 = (int)((int64_t)h * (t + 1) / n);
        pool.emplace_back([=, &fl]() { interp_detail::renderRows(a, b, w, h, fl, f, out, y0, y1); });
    }
    for (auto& t : pool) t.join();
}

}  // namespace am
