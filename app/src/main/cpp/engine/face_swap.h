// Light face swap (no neural network): the face of a SOURCE photo is bent onto the face in a video frame along the 468-point
// face mesh (MediaPipe Face Landmarker), matched to the frame's colour and light, and mixed in with a soft edge.
//
//  1. swapSetSource: a Delaunay triangulation of the source photo's 468 landmarks, kept inside the face oval. The same triangle
//     list (vertex numbers) is used for every frame, so source triangle k always lands on target triangle k.
//  2. swapApply: every triangle is drawn from the photo into a warp layer (affine per triangle), the face oval of the frame
//     (a little smaller) is the mask, blurred for a soft edge; the layer's mean / spread of colour is moved to the frame's
//     around the face; mask * layer + (1 - mask) * frame.
// Plain C++17, no Android / GL: host-testable (tests/face_swap_selftest.cpp). Pictures are RGBA8 (any row order, as long as the
// landmarks use the same one); landmarks are x, y pairs in pixels with the origin at the top-left CORNER of the picture.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace am {

constexpr int kFaceLm = 468;

// MediaPipe face mesh: the outline of the face (36 points, in order around it).
constexpr int kFaceOval[36] = {10,  338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377,
                               152, 148, 176, 149, 150, 136, 172, 58,  132, 93,  234, 127, 162, 21,  54,  103, 67,  109};

struct FaceSource {
    int w = 0, h = 0;
    std::vector<uint8_t> rgba;
    std::vector<float> lm;      // 468 * 2
    std::vector<int> tri;       // 3 landmark numbers per triangle
    bool ok = false;
};

namespace swap_detail {

struct P { double x, y; };

inline bool inPoly(const std::vector<P>& poly, double x, double y) {
    bool in = false;
    for (size_t i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
        if (((poly[i].y > y) != (poly[j].y > y)) && (x < (poly[j].x - poly[i].x) * (y - poly[i].y) / (poly[j].y - poly[i].y) + poly[i].x)) in = !in;
    }
    return in;
}

// Bowyer-Watson Delaunay triangulation. Returns triangles as index triples into [pts].
inline std::vector<int> delaunay(const std::vector<P>& pts) {
    const int n = (int)pts.size();
    double minx = 1e30, miny = 1e30, maxx = -1e30, maxy = -1e30;
    for (const P& p : pts) { minx = std::min(minx, p.x); miny = std::min(miny, p.y); maxx = std::max(maxx, p.x); maxy = std::max(maxy, p.y); }
    const double d = std::max(maxx - minx, maxy - miny) * 20.0 + 1.0, cx = 0.5 * (minx + maxx), cy = 0.5 * (miny + maxy);
    std::vector<P> v = pts;
    v.push_back({cx - d, cy - d});
    v.push_back({cx + d, cy - d});
    v.push_back({cx, cy + d});
    struct T { int a, b, c; double ox, oy, r2; bool bad; };
    auto circum = [&](int a, int b, int c, T* t) {
        const double ax = v[a].x, ay = v[a].y, bx = v[b].x, by = v[b].y, ccx = v[c].x, ccy = v[c].y;
        const double dd = 2 * (ax * (by - ccy) + bx * (ccy - ay) + ccx * (ay - by));
        if (std::fabs(dd) < 1e-12) { t->ox = t->oy = 0; t->r2 = 1e300; return; }
        t->ox = ((ax * ax + ay * ay) * (by - ccy) + (bx * bx + by * by) * (ccy - ay) + (ccx * ccx + ccy * ccy) * (ay - by)) / dd;
        t->oy = ((ax * ax + ay * ay) * (ccx - bx) + (bx * bx + by * by) * (ax - ccx) + (ccx * ccx + ccy * ccy) * (bx - ax)) / dd;
        t->r2 = (ax - t->ox) * (ax - t->ox) + (ay - t->oy) * (ay - t->oy);
    };
    std::vector<T> tris;
    {
        T t{n, n + 1, n + 2, 0, 0, 0, false};
        circum(t.a, t.b, t.c, &t);
        tris.push_back(t);
    }
    for (int i = 0; i < n; ++i) {
        const double px = v[i].x, py = v[i].y;
        std::vector<std::pair<int, int>> edges;
        for (T& t : tris) {
            const double dx = px - t.ox, dy = py - t.oy;
            t.bad = dx * dx + dy * dy < t.r2 * (1.0 - 1e-12);
            if (t.bad) { edges.push_back({t.a, t.b}); edges.push_back({t.b, t.c}); edges.push_back({t.c, t.a}); }
        }
        tris.erase(std::remove_if(tris.begin(), tris.end(), [](const T& t) { return t.bad; }), tris.end());
        for (size_t e = 0; e < edges.size(); ++e) {
            bool shared = false;
            for (size_t f = 0; f < edges.size(); ++f)
                if (e != f && edges[e].first == edges[f].second && edges[e].second == edges[f].first) { shared = true; break; }
            if (shared) continue;
            T t{edges[e].first, edges[e].second, i, 0, 0, 0, false};
            circum(t.a, t.b, t.c, &t);
            tris.push_back(t);
        }
    }
    std::vector<int> out;
    for (const T& t : tris) {
        if (t.a >= n || t.b >= n || t.c >= n) continue;
        out.push_back(t.a); out.push_back(t.b); out.push_back(t.c);
    }
    return out;
}

inline void bilinear(const uint8_t* p, int w, int h, double u, double v, float* o) {
    u -= 0.5; v -= 0.5;   // pixel centres
    u = std::min(std::max(u, 0.0), (double)(w - 1));
    v = std::min(std::max(v, 0.0), (double)(h - 1));
    const int x0 = (int)u, y0 = (int)v, x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
    const float fx = (float)(u - x0), fy = (float)(v - y0);
    const uint8_t* a = p + ((size_t)y0 * w + x0) * 4;
    const uint8_t* b = p + ((size_t)y0 * w + x1) * 4;
    const uint8_t* c = p + ((size_t)y1 * w + x0) * 4;
    const uint8_t* d = p + ((size_t)y1 * w + x1) * 4;
    for (int k = 0; k < 3; ++k) o[k] = (a[k] * (1 - fx) + b[k] * fx) * (1 - fy) + (c[k] * (1 - fx) + d[k] * fx) * fy;
}

inline void boxBlur(std::vector<float>* m, int w, int h, int r) {
    if (r < 1) return;
    std::vector<float> t((size_t)w * h);
    for (int y = 0; y < h; ++y) {   // horizontal
        double acc = 0;
        for (int x = -r; x <= r; ++x) acc += (*m)[(size_t)y * w + std::min(std::max(x, 0), w - 1)];
        for (int x = 0; x < w; ++x) {
            t[(size_t)y * w + x] = (float)(acc / (2 * r + 1));
            acc += (*m)[(size_t)y * w + std::min(x + r + 1, w - 1)] - (*m)[(size_t)y * w + std::max(x - r, 0)];
        }
    }
    for (int x = 0; x < w; ++x) {   // vertical
        double acc = 0;
        for (int y = -r; y <= r; ++y) acc += t[(size_t)std::min(std::max(y, 0), h - 1) * w + x];
        for (int y = 0; y < h; ++y) {
            (*m)[(size_t)y * w + x] = (float)(acc / (2 * r + 1));
            acc += t[(size_t)std::min(y + r + 1, h - 1) * w + x] - t[(size_t)std::max(y - r, 0) * w + x];
        }
    }
}

}  // namespace swap_detail

// Sets the face to paste: [rgba] w x h, [lm] = 468 landmarks (pixels). False when the landmarks are unusable.
inline bool swapSetSource(FaceSource* s, const uint8_t* rgba, int w, int h, const float* lm) {
    using namespace swap_detail;
    s->ok = false;
    if (!s || !rgba || !lm || w < 16 || h < 16) return false;
    s->w = w;
    s->h = h;
    s->rgba.assign(rgba, rgba + (size_t)w * h * 4);
    s->lm.assign(lm, lm + kFaceLm * 2);
    std::vector<P> pts((size_t)kFaceLm);
    for (int i = 0; i < kFaceLm; ++i) {
        if (!std::isfinite(lm[2 * i]) || !std::isfinite(lm[2 * i + 1])) return false;
        // a hair of deterministic jitter keeps four-on-a-circle cases from confusing the triangulation
        pts[i] = {lm[2 * i] + 1e-4 * ((i * 37) % 11), lm[2 * i + 1] + 1e-4 * ((i * 53) % 13)};
    }
    std::vector<P> oval(36);
    for (int i = 0; i < 36; ++i) oval[i] = pts[kFaceOval[i]];
    std::vector<int> all = delaunay(pts);
    s->tri.clear();
    for (size_t t = 0; t + 2 < all.size(); t += 3) {
        const P& a = pts[all[t]], &b = pts[all[t + 1]], &c = pts[all[t + 2]];
        const double cx = (a.x + b.x + c.x) / 3, cy = (a.y + b.y + c.y) / 3;
        if (!inPoly(oval, cx, cy)) continue;
        const double area = std::fabs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y));
        if (area < 1e-3) continue;
        s->tri.push_back(all[t]); s->tri.push_back(all[t + 1]); s->tri.push_back(all[t + 2]);
    }
    s->ok = s->tri.size() >= 3 * 200;   // a real face gives ~800 triangles
    return s->ok;
}

// Pastes the source face onto the face with landmarks [lm] (468, pixels) in the picture [dst] (w x h RGBA8), in place.
// [strength] 0..1: how much of the colour / light of the frame is put on the pasted face (1 = fully matched).
inline bool swapApply(const FaceSource& s, uint8_t* dst, int w, int h, const float* lm, float strength = 0.9f) {
    using namespace swap_detail;
    if (!s.ok || !dst || !lm || w < 16 || h < 16) return false;
    for (int i = 0; i < kFaceLm * 2; ++i) if (!std::isfinite(lm[i])) return false;
    // box of the face oval (grown a little), clipped to the picture
    double minx = 1e30, miny = 1e30, maxx = -1e30, maxy = -1e30, ccx = 0, ccy = 0;
    std::vector<P> oval(36);
    for (int i = 0; i < 36; ++i) {
        oval[i] = {lm[2 * kFaceOval[i]], lm[2 * kFaceOval[i] + 1]};
        minx = std::min(minx, oval[i].x); maxx = std::max(maxx, oval[i].x);
        miny = std::min(miny, oval[i].y); maxy = std::max(maxy, oval[i].y);
        ccx += oval[i].x / 36; ccy += oval[i].y / 36;
    }
    const double fw = maxx - minx, fh = maxy - miny;
    if (fw < 12 || fh < 12 || fw > w * 4.0 || fh > h * 4.0) return false;
    const int pad = (int)(0.15 * std::max(fw, fh)) + 4;
    const int bx0 = std::max(0, (int)std::floor(minx) - pad), by0 = std::max(0, (int)std::floor(miny) - pad);
    const int bx1 = std::min(w, (int)std::ceil(maxx) + pad), by1 = std::min(h, (int)std::ceil(maxy) + pad);
    const int bw = bx1 - bx0, bh = by1 - by0;
    if (bw < 8 || bh < 8) return false;

    // 1) the warp layer: every triangle of the source face drawn at the frame's landmark positions
    std::vector<float> layer((size_t)bw * bh * 3, 0.f);
    std::vector<uint8_t> has((size_t)bw * bh, 0);
    for (size_t t = 0; t + 2 < s.tri.size(); t += 3) {
        const int i0 = s.tri[t], i1 = s.tri[t + 1], i2 = s.tri[t + 2];
        const double x0 = lm[2 * i0], y0 = lm[2 * i0 + 1], x1 = lm[2 * i1], y1 = lm[2 * i1 + 1], x2 = lm[2 * i2], y2 = lm[2 * i2 + 1];
        const double den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (std::fabs(den) < 1e-9) continue;
        const int xa = std::max(bx0, (int)std::floor(std::min({x0, x1, x2}))), xb = std::min(bx1 - 1, (int)std::ceil(std::max({x0, x1, x2})));
        const int ya = std::max(by0, (int)std::floor(std::min({y0, y1, y2}))), yb = std::min(by1 - 1, (int)std::ceil(std::max({y0, y1, y2})));
        const double u0 = s.lm[2 * i0], v0 = s.lm[2 * i0 + 1], u1 = s.lm[2 * i1], v1 = s.lm[2 * i1 + 1], u2 = s.lm[2 * i2], v2 = s.lm[2 * i2 + 1];
        const double eps = 0.02;   // a hair outside the triangle: no cracks between neighbours
        for (int y = ya; y <= yb; ++y) {
            for (int x = xa; x <= xb; ++x) {
                const double px = x + 0.5, py = y + 0.5;
                const double l0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / den;
                const double l1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / den;
                const double l2 = 1.0 - l0 - l1;
                if (l0 < -eps || l1 < -eps || l2 < -eps) continue;
                float c[3];
                bilinear(s.rgba.data(), s.w, s.h, l0 * u0 + l1 * u1 + l2 * u2, l0 * v0 + l1 * v1 + l2 * v2, c);
                const size_t k = (size_t)(y - by0) * bw + (x - bx0);
                layer[3 * k] = c[0]; layer[3 * k + 1] = c[1]; layer[3 * k + 2] = c[2];
                has[k] = 1;
            }
        }
    }

    // 2) the mask: the face oval, a little smaller, softened
    std::vector<P> inner(36);
    for (int i = 0; i < 36; ++i) inner[i] = {ccx + (oval[i].x - ccx) * 0.93, ccy + (oval[i].y - ccy) * 0.93};
    std::vector<float> mask((size_t)bw * bh, 0.f);
    for (int y = 0; y < bh; ++y)
        for (int x = 0; x < bw; ++x) {
            const size_t k = (size_t)y * bw + x;
            if (has[k] && inPoly(inner, bx0 + x + 0.5, by0 + y + 0.5)) mask[k] = 1.f;
        }
    boxBlur(&mask, bw, bh, std::max(2, (int)(0.045 * std::max(fw, fh))));
    boxBlur(&mask, bw, bh, std::max(2, (int)(0.03 * std::max(fw, fh))));

    // 3) colour / light of the frame onto the pasted face (mean and spread per channel, inside the solid part of the mask)
    double mw[3] = {0, 0, 0}, mt[3] = {0, 0, 0}, vw[3] = {0, 0, 0}, vt[3] = {0, 0, 0};
    long n = 0;
    for (int y = 0; y < bh; ++y)
        for (int x = 0; x < bw; ++x) {
            const size_t k = (size_t)y * bw + x;
            if (mask[k] < 0.9f) continue;
            const uint8_t* d = dst + ((size_t)(by0 + y) * w + (bx0 + x)) * 4;
            for (int c = 0; c < 3; ++c) { mw[c] += layer[3 * k + c]; mt[c] += d[c]; }
            ++n;
        }
    float gain[3] = {1, 1, 1}, bias[3] = {0, 0, 0};
    if (n > 50 && strength > 0.f) {
        for (int c = 0; c < 3; ++c) { mw[c] /= n; mt[c] /= n; }
        for (int y = 0; y < bh; ++y)
            for (int x = 0; x < bw; ++x) {
                const size_t k = (size_t)y * bw + x;
                if (mask[k] < 0.9f) continue;
                const uint8_t* d = dst + ((size_t)(by0 + y) * w + (bx0 + x)) * 4;
                for (int c = 0; c < 3; ++c) { const double a = layer[3 * k + c] - mw[c], b = d[c] - mt[c]; vw[c] += a * a; vt[c] += b * b; }
            }
        const float st = std::min(1.f, std::max(0.f, strength));
        for (int c = 0; c < 3; ++c) {
            const double sw = std::sqrt(vw[c] / n) + 1.0, stt = std::sqrt(vt[c] / n) + 1.0;
            const double g = std::min(1.5, std::max(0.65, stt / sw));
            const double gg = 1.0 + (g - 1.0) * st;                 // spread: only part of the way when strength < 1
            gain[c] = (float)gg;
            bias[c] = (float)(mt[c] * st + mw[c] * (1.0 - st) - mw[c] * gg);   // mean towards the frame's
        }
    }

    // 4) mix
    for (int y = 0; y < bh; ++y)
        for (int x = 0; x < bw; ++x) {
            const size_t k = (size_t)y * bw + x;
            const float m = mask[k];
            if (m <= 0.002f) continue;
            uint8_t* d = dst + ((size_t)(by0 + y) * w + (bx0 + x)) * 4;
            for (int c = 0; c < 3; ++c) {
                const float f = std::min(255.f, std::max(0.f, layer[3 * k + c] * gain[c] + bias[c]));
                d[c] = (uint8_t)std::min(255.f, std::max(0.f, d[c] * (1.f - m) + f * m + 0.5f));
            }
        }
    return true;
}

}  // namespace am
