// Stabilize (Mocha-style): holds a tracked flat surface still. The planar track gives the 4 corners of the surface on every
// frame; each frame's picture is moved (with perspective) so those corners go back to where they were on the first frame.
// The result is the 4 corners the PICTURE must be drawn on (the corner pin of the layer on itself). Header only, host-testable
// (tests/stabilize_selftest.cpp). Quads are u, v pairs (0..1 of the upright picture): TL, TR, BR, BL.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <vector>

#include "homography.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace am {

namespace stab_detail {

inline bool invert3(const double* m, double* o) {
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

inline void mul3(const double* a, const double* b, double* o) {
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
}

// Is (x, y) inside the convex quad q (any consistent turn)?
inline bool inside(const float* q, double x, double y) {
    int sign = 0;
    for (int i = 0; i < 4; ++i) {
        const int j = (i + 1) % 4;
        const double cr = (q[2 * j] - q[2 * i]) * (y - q[2 * i + 1]) - (q[2 * j + 1] - q[2 * i + 1]) * (x - q[2 * i]);
        const int s = cr > 0 ? 1 : (cr < 0 ? -1 : 0);
        if (s == 0) continue;
        if (sign == 0) sign = s;
        else if (s != sign) return false;
    }
    return true;
}

}  // namespace stab_detail

// Zooms the n picture quads (n * 8, about the centre) so no empty edge shows. [autoZoom]: least factor that keeps the canvas
// covered on every frame (+1 %, at most 2.5); otherwise [fixedZoom]. Returns the zoom used (>= 1).
inline float applyZoom(float* out, int n, bool autoZoom, float fixedZoom) {
    using namespace stab_detail;
    float zoom = fixedZoom < 1.f ? 1.f : fixedZoom;
    if (autoZoom) {
        zoom = 1.f;
        const double cu[8] = {0, 0, 1, 0, 1, 1, 0, 1};
        for (int i = 0; i < n; ++i) {
            const float* q = out + 8 * i;
            auto covers = [&](double s) {
                float sq[8];
                for (int k = 0; k < 4; ++k) {
                    sq[2 * k] = (float)(0.5 + (q[2 * k] - 0.5) * s);
                    sq[2 * k + 1] = (float)(0.5 + (q[2 * k + 1] - 0.5) * s);
                }
                for (int k = 0; k < 4; ++k)
                    if (!inside(sq, cu[2 * k], cu[2 * k + 1])) return false;
                return true;
            };
            if (covers(1.0)) continue;
            double lo = 1.0, hi = 3.0;
            if (!covers(hi)) { zoom = 3.f; break; }
            for (int it = 0; it < 24; ++it) {
                const double mid = 0.5 * (lo + hi);
                if (covers(mid)) hi = mid; else lo = mid;
            }
            if ((float)hi > zoom) zoom = (float)hi;
        }
        zoom = zoom > 1.f ? zoom * 1.01f : 1.f;
        if (zoom > 2.5f) zoom = 2.5f;
    }
    if (zoom > 1.f)
        for (int i = 0; i < n * 4; ++i) {
            out[2 * i] = 0.5f + (out[2 * i] - 0.5f) * zoom;
            out[2 * i + 1] = 0.5f + (out[2 * i + 1] - 0.5f) * zoom;
        }
    return zoom;
}

// [quads] = n * 8 (the tracked surface per frame, frame 0 = the reference). [out] = n * 8: the corners of the picture per frame
// (frame 0: the picture itself, zoomed). [autoZoom]: zoom about the centre by the least factor that keeps the canvas picture
// covered on every frame (no empty edges); otherwise [fixedZoom]. Returns the zoom used (>= 1), -1 on bad input.
inline float stabilizeQuads(const float* quads, int n, bool autoZoom, float fixedZoom, float* out) {
    using namespace stab_detail;
    if (!quads || !out || n < 1) return -1.f;
    double H0[9];
    if (!squareToQuad(quads, H0)) return -1.f;
    const double pic[8] = {0, 0, 1, 0, 1, 1, 0, 1};
    for (int i = 0; i < n; ++i) {
        double Hi[9], Hinv[9], M[9];
        bool ok = squareToQuad(quads + 8 * i, Hi) && invert3(Hi, Hinv);
        if (ok) mul3(H0, Hinv, M);   // frame i picture (uv) -> reference picture (uv): Hi^-1 to surface, H0 to the reference
        for (int k = 0; k < 4 && ok; ++k) {
            double x, y;
            hApply(M, pic[2 * k], pic[2 * k + 1], &x, &y);
            if (!(std::fabs(x) < 1e4 && std::fabs(y) < 1e4)) ok = false;
            out[8 * i + 2 * k] = (float)x;
            out[8 * i + 2 * k + 1] = (float)y;
        }
        if (!ok) {   // bad frame: keep the last good one (or the plain picture)
            for (int k = 0; k < 8; ++k) out[8 * i + k] = i > 0 ? out[8 * (i - 1) + k] : (float)pic[k];
        }
    }
    return applyZoom(out, n, autoZoom, fixedZoom);
}


// ---- Auto stabilize (no points): smooths the camera path instead of locking it --------------------------------------------
// [quads] = n * 8: the tracked frame quad per frame (frame 0 = reference, uv 0..1 of the upright picture). Each frame's camera
// move is reduced to a SIMILARITY (move + turn + scale, 4 numbers) measured about the picture centre; a perspective warp would
// only copy the tracker's noise into the picture (the "waves"). The 4 numbers are low-passed with a Gaussian of [sigmaFrames]
// frames (<= 0: the camera is held on the reference frame, a tripod) and the picture is moved by the difference between the
// smoothed and the real camera: only the shake goes, the slow pan stays. [aspect] = picture width / height, so a turn stays a
// turn and is not squeezed. Output as stabilizeQuads. Returns the zoom used (>= 1), -1 on bad input.
namespace stab_detail {

struct Sim {   // frame-0 point p -> frame-i point: c + t + s * R(th) * (p - c), all in aspect space (x = u * aspect, y = v)
    double tx = 0, ty = 0, th = 0, ls = 0;
};

// Least-squares similarity taking p[0..3] to q[0..3] (8 floats each, aspect space), about the centre (cx, cy).
inline bool fitSim(const double* p, const double* q, double cx, double cy, Sim* o) {
    double pcx = 0, pcy = 0, qcx = 0, qcy = 0;
    for (int k = 0; k < 4; ++k) { pcx += p[2 * k]; pcy += p[2 * k + 1]; qcx += q[2 * k]; qcy += q[2 * k + 1]; }
    pcx *= 0.25; pcy *= 0.25; qcx *= 0.25; qcy *= 0.25;
    double dot = 0, crs = 0, nn = 0;
    for (int k = 0; k < 4; ++k) {
        const double ax = p[2 * k] - pcx, ay = p[2 * k + 1] - pcy, bx = q[2 * k] - qcx, by = q[2 * k + 1] - qcy;
        dot += ax * bx + ay * by;
        crs += ax * by - ay * bx;
        nn += ax * ax + ay * ay;
    }
    if (nn < 1e-12) return false;
    const double a = dot / nn, b = crs / nn;   // z = a + ib = s * e^(i th)
    const double s = std::sqrt(a * a + b * b);
    if (!(s > 1e-3)) return false;
    o->th = std::atan2(b, a);
    o->ls = std::log(s);
    // where the centre goes: q = qc + z (p - pc)
    const double ex = cx - pcx, ey = cy - pcy;
    o->tx = qcx + (a * ex - b * ey) - cx;
    o->ty = qcy + (b * ex + a * ey) - cy;
    return true;
}

inline void simApply(const Sim& m, double cx, double cy, double x, double y, double* ox, double* oy) {
    const double s = std::exp(m.ls), a = s * std::cos(m.th), b = s * std::sin(m.th);
    const double dx = x - cx, dy = y - cy;
    *ox = cx + m.tx + a * dx - b * dy;
    *oy = cy + m.ty + b * dx + a * dy;
}

// The inverse of simApply: frame-i point -> frame-0 point.
inline void simInvert(const Sim& m, double cx, double cy, double x, double y, double* ox, double* oy) {
    const double s = std::exp(-m.ls), a = s * std::cos(-m.th), b = s * std::sin(-m.th);
    const double dx = x - cx - m.tx, dy = y - cy - m.ty;
    *ox = cx + a * dx - b * dy;
    *oy = cy + b * dx + a * dy;
}

}  // namespace stab_detail

inline float stabilizeSmooth(const float* quads, int n, float aspect, float sigmaFrames, bool autoZoom, float fixedZoom, float* out) {
    using namespace stab_detail;
    if (!quads || !out || n < 1 || !(aspect > 0.01f && aspect < 100.f)) return -1.f;
    const double cx = 0.5 * aspect, cy = 0.5;
    auto toAsp = [&](const float* q, double* d) {
        for (int k = 0; k < 4; ++k) { d[2 * k] = (double)q[2 * k] * aspect; d[2 * k + 1] = q[2 * k + 1]; }
    };
    double p0[8];
    toAsp(quads, p0);
    std::vector<Sim> cam((size_t)n);
    std::vector<char> good((size_t)n, 0);
    Sim last;
    for (int i = 0; i < n; ++i) {
        double qi[8];
        toAsp(quads + 8 * i, qi);
        Sim m;
        if (fitSim(p0, qi, cx, cy, &m) && std::fabs(m.tx) < 50 && std::fabs(m.ty) < 50) { last = m; good[i] = 1; }
        else if (i > 0) m = last;   // a bad frame holds the last good camera
        else m = Sim();
        // unwrap the turn so it never jumps by 2 pi between neighbours
        if (i > 0) {
            const double pr = cam[i - 1].th;
            while (m.th - pr > M_PI) m.th -= 2 * M_PI;
            while (m.th - pr < -M_PI) m.th += 2 * M_PI;
        }
        cam[i] = m;
    }
    // Gaussian low-pass of the 4 numbers (edges: the window is cut and renormalised)
    std::vector<Sim> sm((size_t)n);
    if (sigmaFrames > 0.01f && n > 1) {
        const int rad = std::max(1, (int)std::ceil(3.0 * sigmaFrames));
        std::vector<double> wgt((size_t)rad + 1);
        for (int d = 0; d <= rad; ++d) wgt[d] = std::exp(-0.5 * (double)d * d / ((double)sigmaFrames * sigmaFrames));
        for (int i = 0; i < n; ++i) {
            double sw = 0, tx = 0, ty = 0, th = 0, ls = 0;
            const int a = std::max(0, i - rad), b = std::min(n - 1, i + rad);
            for (int j = a; j <= b; ++j) {
                const double w = wgt[std::abs(j - i)];
                sw += w; tx += w * cam[j].tx; ty += w * cam[j].ty; th += w * cam[j].th; ls += w * cam[j].ls;
            }
            sm[i].tx = tx / sw; sm[i].ty = ty / sw; sm[i].th = th / sw; sm[i].ls = ls / sw;
        }
    } else {
        for (int i = 0; i < n; ++i) sm[i] = Sim();   // tripod: the reference camera
    }
    // picture corner -> where the smoothed camera would have it: S_i( C_i^-1 (corner) )
    const double pic[8] = {0, 0, 1, 0, 1, 1, 0, 1};
    for (int i = 0; i < n; ++i) {
        for (int k = 0; k < 4; ++k) {
            double fx, fy, gx, gy, ux, uy;
            simInvert(cam[i], cx, cy, pic[2 * k] * aspect, pic[2 * k + 1], &fx, &fy);
            simApply(sm[i], cx, cy, fx, fy, &gx, &gy);
            ux = gx / aspect; uy = gy;
            out[8 * i + 2 * k] = (float)ux;
            out[8 * i + 2 * k + 1] = (float)uy;
        }
    }
    return applyZoom(out, n, autoZoom, fixedZoom);
}

}  // namespace am
