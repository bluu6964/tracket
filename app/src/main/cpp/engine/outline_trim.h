// Pure arc-length maths for trimming an outline by length — the geometry behind Alight Motion's
// `drawing.progress` on a text layer (the glyph outlines draw on progressively). An outline contour
// is a chain of line / conic / cubic segments in glyph space (doubles; text_engine.cpp feeds it
// FreeType's 26.6 coordinates, but the unit is the caller's). No FreeType in here: the engine
// converts FT_Outline -> segments (FT_Outline_Decompose) and the clipped segments -> FT_Outline.
// Splits are exact de Casteljau cuts, so a trimmed outline keeps the true bezier shapes.
// Host test: tests/text_selftest.cpp (testOutlineTrim).
#pragma once
#include <cmath>
#include <vector>

namespace am {
namespace text {

/** One outline segment: (x[0], y[0]) is the start; the end is index 1 (line), 2 (conic) or 3 (cubic). */
struct OSeg {
    int kind = 0;  // 0 = line, 1 = conic (quadratic), 2 = cubic
    double x[4] = {0, 0, 0, 0};
    double y[4] = {0, 0, 0, 0};
    int n() const { return kind == 0 ? 2 : kind == 1 ? 3 : 4; }
    double sx() const { return x[0]; }
    double sy() const { return y[0]; }
    double ex() const { return x[n() - 1]; }
    double ey() const { return y[n() - 1]; }
};

/** Point on the segment at parameter t (0..1). */
inline void segPoint(const OSeg& s, double t, double& px, double& py) {
    const double u = 1.0 - t;
    if (s.kind == 0) {
        px = s.x[0] * u + s.x[1] * t;
        py = s.y[0] * u + s.y[1] * t;
    } else if (s.kind == 1) {
        const double a = u * u, b = 2 * u * t, c = t * t;
        px = a * s.x[0] + b * s.x[1] + c * s.x[2];
        py = a * s.y[0] + b * s.y[1] + c * s.y[2];
    } else {
        const double a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, d = t * t * t;
        px = a * s.x[0] + b * s.x[1] + c * s.x[2] + d * s.x[3];
        py = a * s.y[0] + b * s.y[1] + c * s.y[2] + d * s.y[3];
    }
}

/** Splits the segment at parameter t into [first, second] (de Casteljau); t is clamped to [0, 1]. */
inline void segSplit(const OSeg& s, double t, OSeg& first, OSeg& second) {
    t = t < 0 ? 0 : t > 1 ? 1 : t;
    first = s;
    second = s;
    if (s.kind == 0) {
        double mx, my;
        segPoint(s, t, mx, my);
        first.x[1] = mx; first.y[1] = my;
        second.x[0] = mx; second.y[0] = my;
    } else if (s.kind == 1) {
        const double ax = s.x[0] + (s.x[1] - s.x[0]) * t, ay = s.y[0] + (s.y[1] - s.y[0]) * t;
        const double bx = s.x[1] + (s.x[2] - s.x[1]) * t, by = s.y[1] + (s.y[2] - s.y[1]) * t;
        double mx, my;
        segPoint(s, t, mx, my);
        first.x[1] = ax; first.y[1] = ay; first.x[2] = mx; first.y[2] = my;
        second.x[0] = mx; second.y[0] = my; second.x[1] = bx; second.y[1] = by;
    } else {
        const double ax = s.x[0] + (s.x[1] - s.x[0]) * t, ay = s.y[0] + (s.y[1] - s.y[0]) * t;
        const double bx = s.x[1] + (s.x[2] - s.x[1]) * t, by = s.y[1] + (s.y[2] - s.y[1]) * t;
        const double cx = s.x[2] + (s.x[3] - s.x[2]) * t, cy = s.y[2] + (s.y[3] - s.y[2]) * t;
        const double dx = ax + (bx - ax) * t, dy = ay + (by - ay) * t;
        const double ex = bx + (cx - bx) * t, ey = by + (cy - by) * t;
        double mx, my;
        segPoint(s, t, mx, my);
        first.x[1] = ax; first.y[1] = ay; first.x[2] = dx; first.y[2] = dy; first.x[3] = mx; first.y[3] = my;
        second.x[0] = mx; second.y[0] = my; second.x[1] = ex; second.y[1] = ey; second.x[2] = cx; second.y[2] = cy;
    }
}

namespace detail {
inline double segLengthRec(const OSeg& s, int depth) {
    const int n = s.n();
    double poly = 0;
    for (int i = 0; i + 1 < n; ++i) poly += std::hypot(s.x[i + 1] - s.x[i], s.y[i + 1] - s.y[i]);
    const double chord = std::hypot(s.ex() - s.sx(), s.ey() - s.sy());
    if (depth <= 0 || poly - chord < 0.25) return (poly + chord) * 0.5;
    OSeg a, b;
    segSplit(s, 0.5, a, b);
    return segLengthRec(a, depth - 1) + segLengthRec(b, depth - 1);
}
}  // namespace detail

/** Arc length of one segment (adaptive subdivision; exact for lines). */
inline double segLength(const OSeg& s) {
    if (s.kind == 0) return std::hypot(s.ex() - s.sx(), s.ey() - s.sy());
    return detail::segLengthRec(s, 10);
}

/** Total arc length of one contour (a chained segment list). */
inline double contourLength(const std::vector<OSeg>& segs) {
    double total = 0;
    for (const OSeg& s : segs) total += segLength(s);
    return total;
}

/** Parameter t at which the segment reaches arc length [len] from its start (bisection). */
inline double segParamAtLength(const OSeg& s, double len) {
    const double total = segLength(s);
    if (len <= 0) return 0;
    if (len >= total) return 1;
    double lo = 0, hi = 1;
    for (int i = 0; i < 40; ++i) {
        const double mid = (lo + hi) * 0.5;
        OSeg head, rest;
        segSplit(s, mid, head, rest);
        if (segLength(head) < len) lo = mid; else hi = mid;
    }
    return (lo + hi) * 0.5;
}

/**
 * The sub-chain of one contour covering arc lengths [a, b] of that contour (clamped to the
 * contour's length; empty when the window misses the contour). Segment order and shapes are
 * preserved; the first / last segments are cut exactly at the window edges.
 */
inline std::vector<OSeg> clipContour(const std::vector<OSeg>& segs, double a, double b) {
    std::vector<OSeg> out;
    if (b <= a) return out;
    double off = 0;
    for (const OSeg& s : segs) {
        const double len = segLength(s);
        const double s0 = off, s1 = off + len;
        off = s1;
        if (len <= 0 || s1 <= a || s0 >= b) continue;
        const double la = a > s0 ? a - s0 : 0;        // window inside this segment, segment-local
        const double lb = b < s1 ? b - s0 : len;
        if (la <= 0 && lb >= len) { out.push_back(s); continue; }
        OSeg cur = s;
        if (la > 0) {
            OSeg drop, rest;
            segSplit(cur, segParamAtLength(cur, la), drop, rest);
            cur = rest;
        }
        if (lb - la < segLength(cur)) {
            OSeg take, drop;
            segSplit(cur, segParamAtLength(cur, lb - la), take, drop);
            cur = take;
        }
        out.push_back(cur);
    }
    return out;
}

}  // namespace text
}  // namespace am
