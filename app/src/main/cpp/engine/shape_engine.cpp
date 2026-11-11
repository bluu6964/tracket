#include "shape_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "text_blit.h"

namespace am {
namespace shape {

namespace {

const float kDeg2Rad = 0.017453292519943295f;

struct Rgba { float a, r, g, b; };  // straight, 0..255

Rgba fromArgb(uint32_t c) {
    return {(float)((c >> 24) & 255), (float)((c >> 16) & 255), (float)((c >> 8) & 255), (float)(c & 255)};
}

Rgba lerpArgb(uint32_t c1, uint32_t c2, float t) {
    t = std::min(1.f, std::max(0.f, t));
    auto ch = [&](int sh) {
        const float x1 = (float)((c1 >> sh) & 255), x2 = (float)((c2 >> sh) & 255);
        return x1 + (x2 - x1) * t;
    };
    return {ch(24), ch(16), ch(8), ch(0)};
}

// Same maths as text_engine.cpp gradientAt / pictureAt (which build the Android shaders of buildGradientShader /
// buildImageShader); here the block is the shape box: bw = bh = 2 * base, origin = (-base, -base).
Rgba gradientAt(const Request& rq, float bw, float bh, float x, float y) {
    const float x1 = rq.gx1 * bw, y1 = rq.gy1 * bh;
    float x2 = rq.gx2 * bw;
    const float y2 = rq.gy2 * bh;
    if (rq.gradType == 1) {
        const float r = std::max(1.f, std::hypot(x2 - x1, y2 - y1));
        return lerpArgb(rq.grad1, rq.grad2, std::hypot(x - x1, y - y1) / r);
    }
    if (rq.gradType == 2) {
        const float twoPi = 6.28318530718f;
        const float start = std::atan2(y2 - y1, x2 - x1);
        float a = std::atan2(y - y1, x - x1) - start;
        a = std::fmod(a, twoPi);
        if (a < 0.f) a += twoPi;
        const float f = a / twoPi;
        return lerpArgb(rq.grad1, rq.grad2, f < 0.5f ? f * 2.f : (1.f - f) * 2.f);
    }
    if (x1 == x2 && y1 == y2) x2 += 1.f;
    const float dx = x2 - x1, dy = y2 - y1;
    return lerpArgb(rq.grad1, rq.grad2, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy));
}

Rgba sampleFill(const Request& rq, float sx, float sy) {
    const int W = rq.fillW, H = rq.fillH;
    if (sx < 0.f || sy < 0.f || sx > (float)W || sy > (float)H) return {0, 0, 0, 0};
    const float fx = std::min((float)(W - 1), std::max(0.f, sx - 0.5f));
    const float fy = std::min((float)(H - 1), std::max(0.f, sy - 0.5f));
    const int x0 = (int)fx, y0 = (int)fy, x1 = std::min(W - 1, x0 + 1), y1 = std::min(H - 1, y0 + 1);
    const float tx = fx - x0, ty = fy - y0;
    float acc[4] = {0, 0, 0, 0};
    const int xs[2] = {x0, x1}, ys[2] = {y0, y1};
    const float wx[2] = {1.f - tx, tx}, wy[2] = {1.f - ty, ty};
    for (int j = 0; j < 2; ++j)
        for (int i = 0; i < 2; ++i) {
            const uint8_t* q = rq.fillPix + (size_t)ys[j] * rq.fillStride + (size_t)xs[i] * 4;
            const float w = wx[i] * wy[j];
            for (int k = 0; k < 4; ++k) acc[k] += q[k] * w;
        }
    if (acc[3] <= 0.5f) return {0, 0, 0, 0};
    const float inv = 255.f / acc[3];
    return {acc[3], std::min(255.f, acc[0] * inv), std::min(255.f, acc[1] * inv), std::min(255.f, acc[2] * inv)};
}

Rgba pictureAt(const Request& rq, float bw, float bh, float x, float y) {
    const float sw = (float)rq.fillW, sh = (float)rq.fillH;
    if (rq.fillImgMode == 2) return sampleFill(rq, x / bw * sw, y / bh * sh);
    if (rq.fillImgMode == 1) {
        const float k = std::min(bw / sw, bh / sh);
        return sampleFill(rq, (x - (bw - sw * k) * 0.5f) / k, (y - (bh - sh * k) * 0.5f) / k);
    }
    const float k = std::max(bw / sw, bh / sh);
    return sampleFill(rq, x / k + (sw - bw / k) * 0.5f, y / k + (sh - bh / k) * 0.5f);
}

struct Poly { float x[10], y[10]; int n = 0; };

Poly regularPolygon(int kind, float base) {
    Poly p;
    const bool star = kind == STAR;
    p.n = star ? 10 : 5;
    for (int i = 0; i < p.n; ++i) {
        const float a = (float)((i * 360.0 / p.n - 90.0) * 0.017453292519943295);
        const float r = (star && (i & 1)) ? base * 0.45f : base;
        p.x[i] = r * std::cos(a);
        p.y[i] = r * std::sin(a);
    }
    return p;
}

// Exact signed distance to a closed polygon (even-odd inside test), negative inside.
float polygonDistance(const Poly& p, float x, float y) {
    float d2 = 1e30f;
    bool inside = false;
    for (int i = 0, j = p.n - 1; i < p.n; j = i++) {
        const float ax = p.x[j], ay = p.y[j], bx = p.x[i], by = p.y[i];
        const float ex = bx - ax, ey = by - ay, wx = x - ax, wy = y - ay;
        const float t = std::min(1.f, std::max(0.f, (wx * ex + wy * ey) / (ex * ex + ey * ey)));
        const float dx = wx - ex * t, dy = wy - ey * t;
        d2 = std::min(d2, dx * dx + dy * dy);
        if (((ay > y) != (by > y)) && (x < (bx - ax) * (y - ay) / (by - ay) + ax)) inside = !inside;
    }
    const float d = std::sqrt(d2);
    return inside ? -d : d;
}

// ROUNDED corner radii (local units): the X radius, and the Y radius (cornerY <= 0 = the X one).
// Both are clamped to the box, like the historical single radius.
inline void cornerRadii(float base, float corner, float cornerY, float& rx, float& ry) {
    rx = std::min(base, corner > 0.f ? corner : base * 0.25f);
    ry = std::min(base, cornerY > 0.f ? cornerY : rx);
}

// Arc length of one quarter of an ellipse with semi-axes (a, b): Ramanujan's approximation of the
// full perimeter, quartered. Exact for a circle (a == b).
inline float quarterEllipseArc(float a, float b) {
    const float s = a + b;
    if (s <= 0.f) return 0.f;
    const float h = ((a - b) / s) * ((a - b) / s);
    return 3.14159265358979f * s * (1.f + 3.f * h / (10.f + std::sqrt(4.f - 3.f * h))) * 0.25f;
}

/**
 * Signed distance of the local point (x, y) to the outline, negative inside, in local units. Rectangle, triangle and
 * pentagon use "largest edge plane distance": exact inside / along the edges and the outline of its offsets is mitered,
 * which is what Paint.Join.MITER (Android's default, miter limit 4) draws for a stroke. Circle, rounded rectangle: exact.
 * Star: exact distance to the polygon (a stroke has round outer tips; Canvas miters them). Ellipse: first-order estimate.
 * A rounded rectangle with PER-AXIS radii uses that same first-order estimate in the corner quadrants (its arc is an
 * ellipse in local space); everywhere else it stays exact, and a single radius (cornerY <= 0) is exact throughout.
 */
float outlineDistance(int kind, float base, float corner, float cornerY, const Poly* poly, float x, float y) {
    switch (kind) {
        case RECT:
            return std::max(std::fabs(x), std::fabs(y)) - base;
        case ROUNDED: {
            float rx, ry;
            cornerRadii(base, corner, cornerY, rx, ry);
            const float qx = std::fabs(x) - (base - rx), qy = std::fabs(y) - (base - ry);
            if (qx > 0.f && qy > 0.f) {
                // Corner quadrant: the arc is an ellipse of semi-axes (rx, ry) around the corner
                // centre; first-order distance (the OVAL kind's trick). Exact when rx == ry.
                const float l = std::hypot(qx / rx, qy / ry);
                const float g = std::hypot(qx / (rx * rx), qy / (ry * ry));
                return (l - 1.f) * l / g;
            }
            if (qx > 0.f) return qx - rx;
            if (qy > 0.f) return qy - ry;
            return std::max(qx - rx, qy - ry);
        }
        case OVAL: {
            const float a = base * 1.2f, b = base * 0.75f;
            const float k0 = std::hypot(x / a, y / b);
            const float k1 = std::hypot(x / (a * a), y / (b * b));
            if (k1 < 1e-9f) return -std::min(a, b);
            return k0 * (k0 - 1.f) / k1;
        }
        case TRIANGLE: {
            // apex (0,-base), base corners (+-base, base)
            const float inv = 0.4472135955f;  // 1 / sqrt(5)
            const float bottom = y - base;
            const float right = (2.f * x - y - base) * inv;
            const float left = (-2.f * x - y - base) * inv;
            return std::max(bottom, std::max(right, left));
        }
        case PENTAGON: {
            const float apothem = base * 0.80901699437f;  // cos 36 deg
            float m = -1e30f;
            for (int i = 0; i < 5; ++i) {
                const float a = (float)((-54.0 + 72.0 * i) * 0.017453292519943295);
                m = std::max(m, std::cos(a) * x + std::sin(a) * y - apothem);
            }
            return m;
        }
        case STAR:
            return poly ? polygonDistance(*poly, x, y) : 0.f;
        default:
            return std::hypot(x, y) - base;
    }
}

/**
 * Arc-length fraction (0..1) of the outline point closest to the local point (x, y) — the parameter the stroke
 * trim gates on. Only meaningful near the outline (inside the stroke band), where the closest point is unique
 * except at corners, whose two answers agree. The traversal matches the Canvas fallback (LayerPainter): it runs
 * clockwise (screen space, y down) from the outline's path start — circle / oval at the +x axis, rectangle /
 * rounded rectangle at the top-left corner along the top edge, triangle at its apex, polygons at their first
 * (top) vertex. Alight's own path starts are not documented; for a line (trimmed as a RECT fill) the fraction is
 * exact, for closed shapes the start point is this convention.
 */
float outlineParam(int kind, float base, float corner, float cornerY, const Poly* poly, float x, float y) {
    const float twoPi = 6.28318530718f;
    switch (kind) {
        case RECT: {
            // Closest edge: the smaller inset wins. Start at the top-left corner, then top, right, bottom, left.
            const float dx = base - std::fabs(x), dy = base - std::fabs(y);
            float s;
            if (dx < dy) {  // left / right edge
                s = (x > 0.f) ? (2.f * base + (y + base)) : (6.f * base + (base - y));
            } else {  // top / bottom edge
                s = (y < 0.f) ? (x + base) : (4.f * base + (base - x));
            }
            return s / (8.f * base);
        }
        case ROUNDED: {
            float rx, ry;
            cornerRadii(base, corner, cornerY, rx, ry);
            const float ex = base - rx, ey = base - ry;  // straight edges run -e..e on their own axis
            const float arc = quarterEllipseArc(rx, ry); // one corner arc (a quarter ellipse)
            const float total = 4.f * ex + 4.f * ey + 4.f * arc;
            const float ax = std::fabs(x), ay = std::fabs(y);
            float s;
            if (ax > ex && ay > ey) {  // corner arc of this quadrant; centres at (+-ex, +-ey)
                // Elliptic angle around the corner centre; the arc is walked angle-proportionally
                // (exact for a circle, i.e. whenever rx == ry).
                float phi = std::atan2((y - (y > 0.f ? ey : -ey)) / ry, (x - (x > 0.f ? ex : -ex)) / rx);  // -pi..pi
                if (x > 0.f && y < 0.f) {         // top-right: phi -pi/2..0, after the top edge
                    s = 2.f * ex + (phi + 1.57079632679f) / 1.57079632679f * arc;
                } else if (x > 0.f) {             // bottom-right: phi 0..pi/2, after top edge + arc + right edge
                    s = 2.f * ex + arc + 2.f * ey + phi / 1.57079632679f * arc;
                } else if (y > 0.f) {             // bottom-left: phi pi/2..pi
                    s = 4.f * ex + 2.f * ey + 2.f * arc + (phi - 1.57079632679f) / 1.57079632679f * arc;
                } else {                          // top-left: phi -pi..-pi/2 -> pi..3pi/2, the last piece
                    if (phi < 0.f) phi += twoPi;
                    s = 4.f * ex + 4.f * ey + 3.f * arc + (phi - 3.14159265359f) / 1.57079632679f * arc;
                }
            } else if (ax > ex) {   // left / right edge
                s = (x > 0.f) ? (2.f * ex + arc + (y + ey)) : (4.f * ex + 2.f * ey + 3.f * arc + (ey - y));
            } else if (ay > ey) {   // top / bottom edge
                s = (y < 0.f) ? (x + ex) : (2.f * ex + 2.f * ey + 2.f * arc + (ex - x));
            } else if (ax >= ay) {   // inside the inner rect (off the trim band): the old heuristic
                s = (x > 0.f) ? (2.f * ex + arc + (y + ey)) : (4.f * ex + 2.f * ey + 3.f * arc + (ey - y));
            } else {
                s = (y < 0.f) ? (x + ex) : (2.f * ex + 2.f * ey + 2.f * arc + (ex - x));
            }
            return total > 0.f ? s / total : 0.f;
        }
        case TRIANGLE: {
            // apex A (0,-base) -> B (base,base) -> C (-base,base); the closest edge has the largest plane value
            // (the same planes as outlineDistance).
            const float inv = 0.4472135955f;  // 1 / sqrt(5)
            const float bottom = y - base;
            const float right = (2.f * x - y - base) * inv;
            const float left = (-2.f * x - y - base) * inv;
            const float diag = base * 2.2360679775f;  // base * sqrt(5)
            const float total = 2.f * diag + 2.f * base;
            float s;
            if (right >= bottom && right >= left) {
                const float t = std::min(1.f, std::max(0.f, ((x + 2.f * (y + base)) / (5.f * base))));
                s = t * diag;
            } else if (bottom >= left) {
                s = diag + (base - x);
            } else {
                const float t = std::min(1.f, std::max(0.f, ((x - 2.f * y + 3.f * base) / (5.f * base))));
                s = diag + 2.f * base + t * diag;
            }
            return s / total;
        }
        case PENTAGON: {
            // Vertices at -90 + 72i degrees (first tip up), edges in vertex order; edge i's outward normal
            // sits at -54 + 72i degrees (the planes of outlineDistance), the closest edge has the largest value.
            const float apothem = base * 0.80901699437f;
            int best = 0;
            float bestV = -1e30f;
            for (int i = 0; i < 5; ++i) {
                const float a = (float)((-54.0 + 72.0 * i) * 0.017453292519943295);
                const float v = std::cos(a) * x + std::sin(a) * y - apothem;
                if (v > bestV) { bestV = v; best = i; }
            }
            const float a0 = (float)((best * 72.0 - 90.0) * 0.017453292519943295);
            const float a1 = (float)(((best + 1) * 72.0 - 90.0) * 0.017453292519943295);
            const float ex = base * std::cos(a1) - base * std::cos(a0), ey = base * std::sin(a1) - base * std::sin(a0);
            const float wx = x - base * std::cos(a0), wy = y - base * std::sin(a0);
            const float t = std::min(1.f, std::max(0.f, (wx * ex + wy * ey) / (ex * ex + ey * ey)));
            return ((float)best + t) / 5.f;
        }
        case STAR: {
            if (!poly) return 0.f;
            float bestD2 = 1e30f, bestS = 0.f, acc = 0.f, total = 0.f;
            for (int i = 0; i < poly->n; ++i) {
                const int j = (i + 1) % poly->n;
                const float ax2 = poly->x[i], ay2 = poly->y[i], bx2 = poly->x[j], by2 = poly->y[j];
                const float ex = bx2 - ax2, ey = by2 - ay2, wx = x - ax2, wy = y - ay2;
                const float len = std::hypot(ex, ey);
                const float t = len > 0.f ? std::min(1.f, std::max(0.f, (wx * ex + wy * ey) / (len * len))) : 0.f;
                const float ddx = wx - ex * t, ddy = wy - ey * t;
                const float d2 = ddx * ddx + ddy * ddy;
                if (d2 < bestD2) { bestD2 = d2; bestS = acc + t * len; }
                acc += len;
                total = acc;
            }
            return total > 0.f ? bestS / total : 0.f;
        }
        default: {  // CIRCLE, OVAL: angle from the +x axis, clockwise in y-down space
            float u = std::atan2(y, x) / twoPi;
            if (u < 0.f) u += 1.f;
            return u;
        }
    }
}

inline float cover(float distPx) { return std::min(1.f, std::max(0.f, 0.5f - distPx)); }

inline void overPx(uint8_t* d, float r, float g, float b, float a) {  // premultiplied 0..255 source
    const float inv = 1.f - a / 255.f;
    d[0] = (uint8_t)std::min(255.f, r + d[0] * inv + 0.5f);
    d[1] = (uint8_t)std::min(255.f, g + d[1] * inv + 0.5f);
    d[2] = (uint8_t)std::min(255.f, b + d[2] * inv + 0.5f);
    d[3] = (uint8_t)std::min(255.f, a + d[3] * inv + 0.5f);
}

}  // namespace

bool drawShape(uint8_t* dst, int dstW, int dstH, int dstStride, const Request& rq) {
    if (!dst || dstW <= 0 || dstH <= 0 || !(rq.base > 0.f) || !(rq.scale > 0.f)) return false;
    if (rq.alpha <= 0.f) return true;
    const float alpha = std::min(1.f, rq.alpha);
    const float sw = std::max(0.f, rq.strokeW);
    const bool strokeOn = sw > 0.f && ((rq.strokeArgb >> 24) & 255) > 0;
    const bool pic = rq.fillKind == 2;
    const bool fillOn = rq.fillOn && !(pic && !rq.fillPix);
    if (!fillOn && !strokeOn) return true;

    const float base = rq.base;
    const float boxW = 2.f * base, boxH = 2.f * base;
    const float ang = rq.rotationDeg * kDeg2Rad;
    const float cs = std::cos(ang), sn = std::sin(ang);
    // Per-axis layer scale (the shape's own aspect): scaleY <= 0 means the uniform scale on both axes.
    const float sx = rq.scale;
    const float sy = rq.scaleY > 0.f ? rq.scaleY : rq.scale;
    const float minK = std::min(sx, sy);

    // Bounding box of the (stroke-grown) outline on the frame: the local half extents per axis (the
    // oval is wider than tall), grown by the stroke and the anti-aliasing margin, scaled per axis.
    const float grow = sw * 1.3f + 2.f / minK;
    const float halfX = (rq.kind == OVAL ? base * 1.2f : base) + grow;
    const float halfY = (rq.kind == OVAL ? base * 0.75f : base) + grow;
    float minX = 1e30f, minY = 1e30f, maxX = -1e30f, maxY = -1e30f;
    for (int i = 0; i < 4; ++i) {
        const float lx = (i & 1 ? halfX : -halfX) * sx, ly = (i & 2 ? halfY : -halfY) * sy;
        const float X = rq.cx + cs * lx - sn * ly, Y = rq.cy + sn * lx + cs * ly;
        minX = std::min(minX, X); maxX = std::max(maxX, X);
        minY = std::min(minY, Y); maxY = std::max(maxY, Y);
    }
    const int x0 = std::max(0, (int)std::floor(minX)), x1 = std::min(dstW, (int)std::ceil(maxX));
    const int y0 = std::max(0, (int)std::floor(minY)), y1 = std::min(dstH, (int)std::ceil(maxY));

    float cl = -base, ct = -base, cr = base, cb = base;
    if (rq.hasCrop) {
        cl = -base + rq.crop[0] * boxW; ct = -base + rq.crop[1] * boxH;
        cr = -base + rq.crop[2] * boxW; cb = -base + rq.crop[3] * boxH;
    }
    Poly polyStore;
    const Poly* poly = nullptr;
    if (rq.kind == STAR) { polyStore = regularPolygon(STAR, base); poly = &polyStore; }
    const Rgba fillStraight = fromArgb(rq.fillArgb);
    const Rgba strokeStraight = fromArgb(rq.strokeArgb);
    const float sk = strokeStraight.a / 255.f;

    for (int y = y0; y < y1; ++y) {
        uint8_t* d = dst + (size_t)y * dstStride + (size_t)x0 * 4;
        for (int x = x0; x < x1; ++x, d += 4) {
            // pixel centre -> local coordinates: inverse of translate, rotate, scale, flip
            const float px = x + 0.5f - rq.cx, py = y + 0.5f - rq.cy;
            float lx = (cs * px + sn * py) / sx;
            float ly = (-sn * px + cs * py) / sy;
            lx *= rq.flipX < 0.f ? -1.f : 1.f;
            ly *= rq.flipY < 0.f ? -1.f : 1.f;

            float clipCov = 1.f;
            if (rq.hasCrop) {
                // The crop planes are local axis planes: across them one local unit is exactly sx
                // frame px in x and sy in y (the rotation preserves the gradient's length).
                clipCov = cover((cl - lx) * sx) * cover((lx - cr) * sx) *
                          cover((ct - ly) * sy) * cover((ly - cb) * sy);
                if (clipCov <= 0.f) continue;
            }
            const float s = outlineDistance(rq.kind, base, rq.corner, rq.cornerY, poly, lx, ly);
            // Frame px per local unit along the outline normal: with a non-uniform scale the same
            // local distance covers sx px in x and sy in y, so the factor follows the SDF gradient
            // (1 / |(gx/sx, gy/sy)|). Outside the anti-aliasing band every cover is saturated and a
            // plain mean does; inside it the gradient costs two extra SDF samples. Uniform scale
            // keeps the historical factor exactly.
            float pxK = sx;
            if (sy != sx) {
                pxK = std::sqrt(sx * sy);
                if (std::fabs(s) < sw * 0.5f + 2.5f / minK) {
                    const float e = 0.75f;
                    const float gx = (outlineDistance(rq.kind, base, rq.corner, rq.cornerY, poly, lx + e, ly) - s) / e;
                    const float gy = (outlineDistance(rq.kind, base, rq.corner, rq.cornerY, poly, lx, ly + e) - s) / e;
                    const float n = std::sqrt((gx / sx) * (gx / sx) + (gy / sy) * (gy / sy));
                    if (n > 1e-9f) pxK = 1.f / n;
                }
            }
            const bool trimActive = rq.trimStart > 0.f || rq.trimEnd < 1.f;

            float outR = 0.f, outG = 0.f, outB = 0.f, outA = 0.f;  // premultiplied 0..255
            if (fillOn) {
                float cf = cover(s * pxK);
                // An imported line (trimFill): its fill is the path, drawn along local x.
                if (cf > 0.f && trimActive && rq.trimFill && rq.kind == RECT) {
                    const float u = (lx + base) / (2.f * base);
                    if (u < rq.trimStart || u > rq.trimEnd) cf = 0.f;
                }
                if (cf > 0.f) {
                    Rgba col = fillStraight;
                    if (rq.fillKind == 1) col = gradientAt(rq, boxW, boxH, lx + base, ly + base);
                    else if (pic) col = pictureAt(rq, boxW, boxH, lx + base, ly + base);
                    const float a = col.a * cf / 255.f;
                    outR = col.r * a; outG = col.g * a; outB = col.b * a; outA = col.a * cf;
                }
            }
            if (strokeOn) {
                float cstroke = cover((std::fabs(s) - sw * 0.5f) * pxK);
                if (cstroke > 0.f && trimActive && !rq.trimFill) {
                    const float u = outlineParam(rq.kind, base, rq.corner, rq.cornerY, poly, lx, ly);
                    if (u < rq.trimStart || u > rq.trimEnd) cstroke = 0.f;
                }
                if (cstroke > 0.f) {
                    const float a = sk * cstroke;  // 0..1
                    const float inv = 1.f - a;
                    outR = strokeStraight.r * a + outR * inv;
                    outG = strokeStraight.g * a + outG * inv;
                    outB = strokeStraight.b * a + outB * inv;
                    outA = 255.f * a + outA * inv;
                }
            }
            if (outA <= 0.f) continue;
            const float k = clipCov * alpha;
            overPx(d, outR * k, outG * k, outB * k, outA * k);
        }
    }
    return true;
}

bool drawSticker(uint8_t* dst, int dstW, int dstH, int dstStride, const StickerRequest& rq) {
    if (!dst || !rq.pix || rq.w <= 0 || rq.h <= 0 || !(rq.scale > 0.f) || !(rq.halfW > 0.f) || !(rq.halfH > 0.f))
        return false;
    if (rq.alpha <= 0.f) return true;
    text::Image img;
    img.alloc(rq.w, rq.h);
    for (int y = 0; y < rq.h; ++y)
        std::memcpy(&img.px[(size_t)y * rq.w * 4], rq.pix + (size_t)y * rq.stride, (size_t)rq.w * 4);

    // source pixel -> local box [-halfW, halfW] x [-halfH, halfH] -> flip + scale -> rotate -> translate
    const float ang = rq.rotationDeg * kDeg2Rad;
    const float cs = std::cos(ang), sn = std::sin(ang);
    const float sx = rq.scale * (rq.flipX < 0.f ? -1.f : 1.f), sy = rq.scale * (rq.flipY < 0.f ? -1.f : 1.f);
    const float kx = 2.f * rq.halfW / rq.w, ky = 2.f * rq.halfH / rq.h;
    text::BlitParams bp;
    bp.srcToDst.a = cs * sx * kx;
    bp.srcToDst.b = sn * sx * kx;
    bp.srcToDst.c = -sn * sy * ky;
    bp.srcToDst.d = cs * sy * ky;
    const float ox = sx * -rq.halfW, oy = sy * -rq.halfH;
    bp.srcToDst.e = rq.cx + cs * ox - sn * oy;
    bp.srcToDst.f = rq.cy + sn * ox + cs * oy;
    bp.alpha = std::min(1.f, rq.alpha);
    bp.clampEdges = true;  // like an Android bitmap: the edge pixel is repeated, a sticker does not fade at its border
    text::blit(dst, dstW, dstH, dstStride, img, bp);
    return true;
}

}  // namespace shape
}  // namespace am
