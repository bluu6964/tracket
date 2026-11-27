// Build 224 — native timeline renderer. See timeline_render.h for the design.
#include "timeline_render.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace am {
namespace timeline {
namespace {

// ---- Colours (build-223 theme values; per-type bar colours come from Kotlin) ----
constexpr uint32_t kBg = 0xFF0A0E13u;
constexpr uint32_t kTickMajor = 0x57FFFFFFu;
constexpr uint32_t kTickMinor = 0x33FFFFFFu;
constexpr uint32_t kBeat = 0x80FFFFFFu;
constexpr uint32_t kKeyOrange = 0xFFF97316u;
constexpr uint32_t kRangeBand = 0x12FFFFFFu;
constexpr uint32_t kRangeEdge = 0x66FFFFFFu;
constexpr uint32_t kWhite = 0xFFFFFFFFu;
constexpr uint32_t kHairline = 0xFF232C38u;
constexpr uint32_t kEyeBg = 0xFF141B24u;
constexpr uint32_t kEyeTint = 0xFFA1A1AAu;
constexpr uint32_t kEyeHidden = 0xFF52525Bu;
constexpr uint32_t kChainBg = 0xFF0E1319u;
constexpr uint32_t kChainBgSel = 0xFF131920u;
constexpr uint32_t kChainBorder = 0xFF232C38u;
constexpr uint32_t kChainBridge = 0xFF33404Fu;
constexpr uint32_t kChainInk = 0xFF71717Au;
constexpr uint32_t kChainInkSel = 0xFFD4D4D8u;
constexpr uint32_t kFxBadge = 0xFF1E2732u;
constexpr uint32_t kChipBg = 0x8C000000u;   // black 55%
constexpr uint32_t kTeal = 0xFF00B8A9u;     // controller
constexpr uint32_t kGroupTeal = 0xFF14B8A6u;
constexpr uint32_t kLime = 0xFF18D8E0u;  // build 276: the accent is cyan now (multi-select check disc)
constexpr uint32_t kLimeInk = 0xFF111114u;
constexpr uint32_t kNullPink = 0xFFF9326Au;
constexpr uint32_t kKeyInk = 0xFFA1A1AAu;   // in-bar keyframe diamond rim (AmTextDim)

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

inline uint32_t mulAlpha(uint32_t argb, float m) {
    if (m >= 0.999f) return argb;
    uint32_t a = (uint32_t)((argb >> 24) * m + 0.5f);
    return (a << 24) | (argb & 0x00FFFFFFu);
}

inline uint32_t withAlpha(uint32_t argb, float alpha01) {
    uint32_t a = (uint32_t)(255.f * clampf(alpha01, 0.f, 1.f) + 0.5f);
    return (a << 24) | (argb & 0x00FFFFFFu);
}

// ---- Framebuffer + primitives (all writes bounds-checked; src-over onto an opaque canvas) ----
struct Fb {
    uint8_t* p = nullptr;
    int w = 0, h = 0, stride = 0;
    // Optional rounded-rect clip for bar contents (the bar's own shape).
    bool clipOn = false;
    float cx0 = 0, cy0 = 0, cx1 = 0, cy1 = 0, cr = 0;

    bool insideClip(float x, float y) const {
        if (!clipOn) return true;
        if (x < cx0 || x >= cx1 || y < cy0 || y >= cy1) return false;
        if (cr <= 0) return true;
        float qx = x < cx0 + cr ? cx0 + cr : (x > cx1 - cr ? cx1 - cr : x);
        float qy = y < cy0 + cr ? cy0 + cr : (y > cy1 - cr ? cy1 - cr : y);
        float dx = x - qx, dy = y - qy;
        return dx * dx + dy * dy <= cr * cr;
    }

    void blendPx(int x, int y, uint32_t argb, float cov) {
        if ((unsigned)x >= (unsigned)w || (unsigned)y >= (unsigned)h) return;
        if (!insideClip(x + 0.5f, y + 0.5f)) return;
        uint32_t a = (uint32_t)((argb >> 24) * clampf(cov, 0.f, 1.f) + 0.5f);
        if (a == 0) return;
        uint8_t* d = p + (size_t)y * stride + (size_t)x * 4;
        if (a >= 255) {
            d[0] = (uint8_t)(argb >> 16); d[1] = (uint8_t)(argb >> 8); d[2] = (uint8_t)argb; d[3] = 255;
            return;
        }
        uint32_t ia = 255 - a;
        d[0] = (uint8_t)(((argb >> 16 & 255) * a + d[0] * ia + 127) / 255);
        d[1] = (uint8_t)(((argb >> 8 & 255) * a + d[1] * ia + 127) / 255);
        d[2] = (uint8_t)(((argb & 255) * a + d[2] * ia + 127) / 255);
        d[3] = 255;
    }
};

void fillRect(Fb& fb, float x0, float y0, float x1, float y1, uint32_t argb) {
    int ix0 = std::max(0, (int)std::floor(x0)), iy0 = std::max(0, (int)std::floor(y0));
    int ix1 = std::min(fb.w, (int)std::ceil(x1)), iy1 = std::min(fb.h, (int)std::ceil(y1));
    // Build 264: the integer interior — pixels whose coverage is exactly 1
    // (x >= x0 and x + 1 <= x1, and the same for y) — is written without
    // the per-pixel coverage computation; only the fractional border ring
    // pays for it. Unclipped opaque interiors (section bands, hairlines)
    // write the same bytes blendPx writes at full alpha.
    int jx0 = std::max(ix0, (int)std::ceil(x0)), jy0 = std::max(iy0, (int)std::ceil(y0));
    int jx1 = std::min(ix1, (int)std::floor(x1 - 1.f) + 1);
    int jy1 = std::min(iy1, (int)std::floor(y1 - 1.f) + 1);
    if (jx1 < jx0) jx1 = jx0;
    if (jy1 < jy0) jy1 = jy0;
    const bool direct = !fb.clipOn && (argb >> 24) == 0xFFu;
    for (int y = jy0; y < jy1; ++y)
        for (int x = jx0; x < jx1; ++x) {
            if (direct) {
                uint8_t* d = fb.p + (size_t)y * fb.stride + (size_t)x * 4;
                d[0] = (uint8_t)(argb >> 16); d[1] = (uint8_t)(argb >> 8);
                d[2] = (uint8_t)argb; d[3] = 255;
            } else {
                fb.blendPx(x, y, argb, 1.f);
            }
        }
    for (int y = iy0; y < iy1; ++y)
        for (int x = ix0; x < ix1; ++x) {
            if (x >= jx0 && x < jx1 && y >= jy0 && y < jy1) continue;  // interior, done above
            // Exact coverage on the fractional edges.
            float cov = std::min({x + 1.f - x0, x1 - x, y + 1.f - y0, y1 - y, 1.f});
            if (cov > 0) fb.blendPx(x, y, argb, cov);
        }
}

// Signed distance to a rounded box with per-corner radii (iq's formula).
float sdRoundBox(float px, float py, float cx, float cy, float hx, float hy,
                 float rTL, float rTR, float rBR, float rBL) {
    float r = px < cx ? (py < cy ? rTL : rBL) : (py < cy ? rTR : rBR);
    float qx = std::fabs(px - cx) - (hx - r);
    float qy = std::fabs(py - cy) - (hy - r);
    float ax = std::max(qx, 0.f), ay = std::max(qy, 0.f);
    // Build 264: outside the four corner quadrants the length term is
    // exactly ax or ay (or 0), so the per-pixel sqrt — paid for every
    // pixel of every rounded rect / stroke, every frame — is only
    // computed where both components are non-zero.
    float len = (ax == 0.f) ? ay : (ay == 0.f) ? ax : std::sqrt(ax * ax + ay * ay);
    return std::min(std::max(qx, qy), 0.f) + len - r;
}

void fillRoundRect(Fb& fb, float x, float y, float w, float h,
                   float rTL, float rTR, float rBR, float rBL, uint32_t argb) {
    if (w <= 0 || h <= 0) return;
    float mr = std::min(w, h) / 2.f;
    rTL = std::min(rTL, mr); rTR = std::min(rTR, mr); rBR = std::min(rBR, mr); rBL = std::min(rBL, mr);
    int ix0 = std::max(0, (int)std::floor(x)), iy0 = std::max(0, (int)std::floor(y));
    int ix1 = std::min(fb.w, (int)std::ceil(x + w)), iy1 = std::min(fb.h, (int)std::ceil(y + h));
    float cx = x + w / 2.f, cy = y + h / 2.f;
    for (int j = iy0; j < iy1; ++j)
        for (int i = ix0; i < ix1; ++i) {
            float sd = sdRoundBox(i + 0.5f, j + 0.5f, cx, cy, w / 2.f, h / 2.f, rTL, rTR, rBR, rBL);
            float cov = clampf(0.5f - sd, 0.f, 1.f);
            if (cov > 0) fb.blendPx(i, j, argb, cov);
        }
}

void fillRoundRect(Fb& fb, float x, float y, float w, float h, float r, uint32_t argb) {
    fillRoundRect(fb, x, y, w, h, r, r, r, r, argb);
}

// Stroke hugging the INSIDE of the rounded rect's edge (Compose's border()).
void strokeRoundRectIn(Fb& fb, float x, float y, float w, float h, float r, float width, uint32_t argb) {
    if (w <= 0 || h <= 0 || width <= 0) return;
    r = std::min(r, std::min(w, h) / 2.f);
    int ix0 = std::max(0, (int)std::floor(x)), iy0 = std::max(0, (int)std::floor(y));
    int ix1 = std::min(fb.w, (int)std::ceil(x + w)), iy1 = std::min(fb.h, (int)std::ceil(y + h));
    float cx = x + w / 2.f, cy = y + h / 2.f;
    for (int j = iy0; j < iy1; ++j)
        for (int i = ix0; i < ix1; ++i) {
            float sd = sdRoundBox(i + 0.5f, j + 0.5f, cx, cy, w / 2.f, h / 2.f, r, r, r, r);
            float depth = -sd;  // >= 0 inside the edge, < 0 outside
            // Trapezoid: AA ramp at the outer edge, full until width-0.5, ramp out at the inner edge.
            float cov = clampf(std::min(depth + 0.5f, width - depth + 0.5f), 0.f, 1.f);
            if (cov > 0) fb.blendPx(i, j, argb, cov);
        }
}

// Stroke centred on the rounded rect's edge (the selection's outer ring).
void strokeRoundRectC(Fb& fb, float x, float y, float w, float h, float r, float width, uint32_t argb) {
    if (w <= 0 || h <= 0 || width <= 0) return;
    r = std::min(r, std::min(w, h) / 2.f);
    float pad = width / 2.f + 1.f;
    int ix0 = std::max(0, (int)std::floor(x - pad)), iy0 = std::max(0, (int)std::floor(y - pad));
    int ix1 = std::min(fb.w, (int)std::ceil(x + w + pad)), iy1 = std::min(fb.h, (int)std::ceil(y + h + pad));
    float cx = x + w / 2.f, cy = y + h / 2.f;
    for (int j = iy0; j < iy1; ++j)
        for (int i = ix0; i < ix1; ++i) {
            float sd = sdRoundBox(i + 0.5f, j + 0.5f, cx, cy, w / 2.f, h / 2.f, r, r, r, r);
            float cov = clampf(width / 2.f + 0.5f - std::fabs(sd), 0.f, 1.f);
            if (cov > 0) fb.blendPx(i, j, argb, cov);
        }
}

void drawLine(Fb& fb, float x0, float y0, float x1, float y1, float width, uint32_t argb) {
    float pad = width / 2.f + 1.f;
    int ix0 = std::max(0, (int)std::floor(std::min(x0, x1) - pad));
    int iy0 = std::max(0, (int)std::floor(std::min(y0, y1) - pad));
    int ix1 = std::min(fb.w, (int)std::ceil(std::max(x0, x1) + pad));
    int iy1 = std::min(fb.h, (int)std::ceil(std::max(y0, y1) + pad));
    float dx = x1 - x0, dy = y1 - y0;
    float len2 = dx * dx + dy * dy;
    // Build 264: axis-aligned lines — the waveform bars (one vertical line
    // per 3 dp step of every media bar, every frame) and the ruler ticks.
    // For a pixel whose centre lies within the segment's span, the nearest
    // point on the line is the pixel's own row/column, so the distance is
    // the exact perpendicular offset — no projection, no sqrt. Pixels past
    // the endpoints keep the general formula below.
    const bool vert = (dx == 0.f && dy != 0.f);
    const bool horz = (dy == 0.f && dx != 0.f);
    const float spanLo = vert ? std::min(y0, y1) : std::min(x0, x1);
    const float spanHi = vert ? std::max(y0, y1) : std::max(x0, x1);
    for (int j = iy0; j < iy1; ++j)
        for (int i = ix0; i < ix1; ++i) {
            float px = i + 0.5f, py = j + 0.5f;
            float dist;
            if (vert && py >= spanLo && py <= spanHi) {
                dist = std::fabs(px - x0);
            } else if (horz && px >= spanLo && px <= spanHi) {
                dist = std::fabs(py - y0);
            } else {
                float t = len2 > 0 ? clampf(((px - x0) * dx + (py - y0) * dy) / len2, 0.f, 1.f) : 0.f;
                float ex = x0 + t * dx - px, ey = y0 + t * dy - py;
                dist = std::sqrt(ex * ex + ey * ey);
            }
            float cov = clampf(width / 2.f + 0.5f - dist, 0.f, 1.f);
            if (cov > 0) fb.blendPx(i, j, argb, cov);
        }
}

void fillCircle(Fb& fb, float cx, float cy, float r, uint32_t argb) {
    int ix0 = std::max(0, (int)std::floor(cx - r - 1)), iy0 = std::max(0, (int)std::floor(cy - r - 1));
    int ix1 = std::min(fb.w, (int)std::ceil(cx + r + 1)), iy1 = std::min(fb.h, (int)std::ceil(cy + r + 1));
    for (int j = iy0; j < iy1; ++j)
        for (int i = ix0; i < ix1; ++i) {
            float dx = i + 0.5f - cx, dy = j + 0.5f - cy;
            float cov = clampf(r + 0.5f - std::sqrt(dx * dx + dy * dy), 0.f, 1.f);
            if (cov > 0) fb.blendPx(i, j, argb, cov);
        }
}

void strokeCircle(Fb& fb, float cx, float cy, float r, float width, uint32_t argb) {
    float pad = width / 2.f + 1.f;
    int ix0 = std::max(0, (int)std::floor(cx - r - pad)), iy0 = std::max(0, (int)std::floor(cy - r - pad));
    int ix1 = std::min(fb.w, (int)std::ceil(cx + r + pad)), iy1 = std::min(fb.h, (int)std::ceil(cy + r + pad));
    for (int j = iy0; j < iy1; ++j)
        for (int i = ix0; i < ix1; ++i) {
            float dx = i + 0.5f - cx, dy = j + 0.5f - cy;
            float cov = clampf(width / 2.f + 0.5f - std::fabs(std::sqrt(dx * dx + dy * dy) - r), 0.f, 1.f);
            if (cov > 0) fb.blendPx(i, j, argb, cov);
        }
}

struct Pt { float x, y; };

// Even-odd fill over one or more closed subpaths, 2x2 supersampled (icons, diamonds,
// triangles — small areas). Multiple subpaths punch holes by parity (the eye's iris).
void fillPaths(Fb& fb, const std::vector<std::vector<Pt>>& paths, uint32_t argb) {
    float minx = 1e18f, miny = 1e18f, maxx = -1e18f, maxy = -1e18f;
    for (auto& pts : paths)
        for (auto& q : pts) {
            minx = std::min(minx, q.x); miny = std::min(miny, q.y);
            maxx = std::max(maxx, q.x); maxy = std::max(maxy, q.y);
        }
    if (minx > maxx) return;
    int ix0 = std::max(0, (int)std::floor(minx)), iy0 = std::max(0, (int)std::floor(miny));
    int ix1 = std::min(fb.w, (int)std::ceil(maxx)), iy1 = std::min(fb.h, (int)std::ceil(maxy));
    auto inside = [&](float px, float py) {
        bool in = false;
        for (auto& pts : paths) {
            if (pts.size() < 3) continue;
            for (size_t i = 0, j = pts.size() - 1; i < pts.size(); j = i++) {
                float xi = pts[i].x, yi = pts[i].y, xj = pts[j].x, yj = pts[j].y;
                if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) in = !in;
            }
        }
        return in;
    };
    for (int j = iy0; j < iy1; ++j)
        for (int i = ix0; i < ix1; ++i) {
            int hits = 0;
            for (int sy = 0; sy < 2; ++sy)
                for (int sx = 0; sx < 2; ++sx)
                    if (inside(i + 0.25f + 0.5f * sx, j + 0.25f + 0.5f * sy)) ++hits;
            if (hits) fb.blendPx(i, j, argb, hits / 4.f);
        }
}

void fillPoly(Fb& fb, const std::vector<Pt>& pts, uint32_t argb) {
    fillPaths(fb, {pts}, argb);
}

std::vector<Pt> circlePath(float cx, float cy, float r, int steps = 28) {
    std::vector<Pt> out;
    for (int i = 0; i < steps; ++i) {
        float a = (float)i / steps * 6.2831853f;
        out.push_back({cx + r * std::cos(a), cy + r * std::sin(a)});
    }
    return out;
}

void strokePolyline(Fb& fb, const std::vector<Pt>& pts, bool close, float width, uint32_t argb) {
    if (pts.size() < 2) return;
    size_t n = close ? pts.size() : pts.size() - 1;
    for (size_t i = 0; i < n; ++i) {
        Pt a = pts[i], b = pts[(i + 1) % pts.size()];
        drawLine(fb, a.x, a.y, b.x, b.y, width, argb);
    }
}

// Flattened quadratic / cubic beziers for the hand-drawn bar glyphs.
std::vector<Pt> quad(Pt a, Pt c, Pt b, int steps = 10) {
    std::vector<Pt> out;
    for (int i = 0; i <= steps; ++i) {
        float t = i / (float)steps, u = 1 - t;
        out.push_back({u * u * a.x + 2 * u * t * c.x + t * t * b.x,
                       u * u * a.y + 2 * u * t * c.y + t * t * b.y});
    }
    return out;
}
std::vector<Pt> cubic(Pt a, Pt c1, Pt c2, Pt b, int steps = 14) {
    std::vector<Pt> out;
    for (int i = 0; i <= steps; ++i) {
        float t = i / (float)steps, u = 1 - t;
        out.push_back({u * u * u * a.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * b.x,
                       u * u * u * a.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * b.y});
    }
    return out;
}

}  // namespace

// ---- Layout math ----
float ppmPx(const Snapshot& s) {
    float p = s.pxPerMs * s.density;
    // Build 227: never let a zero / negative / NaN zoom reach the divisions
    // below (xForMs, the ruler walk, the waveform loops). The JNI boundary
    // sanitizes the snapshot too; this is the renderer's own guarantee.
    return (p > 0.f) ? p : 0.045f;
}

float xForMs(const Snapshot& s, const View& v, int64_t ms) {
    return v.w / 2.f + (float)(ms - v.playheadMs) * ppmPx(s);
}

float laneTopPx(const Snapshot& s, const View& v, int32_t track) {
    float y = s.rulerHPx + s.topPadPx - v.scrollYPx;
    for (int32_t i = 0; i < track && i < (int32_t)s.tracks.size(); ++i)
        y += s.tracks[i].heightPx + s.gapPx;
    return y;
}

float contentHeightPx(const Snapshot& s) {
    float h = s.topPadPx + s.bottomPadPx;
    for (size_t i = 0; i < s.tracks.size(); ++i) {
        h += s.tracks[i].heightPx;
        if (i + 1 < s.tracks.size()) h += s.gapPx;
    }
    return h;
}

void barRectPx(const Snapshot& s, const View& v, int32_t track, int32_t clip,
               float* x, float* y, float* w, float* h) {
    const Track& t = s.tracks[track];
    const Clip& c = t.clips[clip];
    float top = laneTopPx(s, v, track);
    if (c.flags & kFlagChain) {
        *x = 0; *y = top; *w = (float)v.w; *h = t.heightPx;
        return;
    }
    float bx = xForMs(s, v, c.startMs);
    float bw = std::max((float)c.durMs * ppmPx(s), 30.f * s.density);
    if (c.flags & kFlagInChain) {
        *x = bx; *y = top + 3.f * s.density; *w = bw; *h = t.heightPx - 6.f * s.density;
    } else {
        *x = bx; *y = top; *w = bw; *h = t.heightPx;
    }
}

int64_t rulerStepMs(float ppm, float density) {
    static const int64_t kSteps[] = {200, 500, 1000, 2000, 5000, 10000, 30000, 60000};
    float minPitch = 7.f * density;
    for (int64_t st : kSteps)
        if ((float)st * ppm >= minPitch) return st;
    return kSteps[7];
}

float fadeGainAt(const Clip& c, int64_t relMs) {
    float g = 1.f;
    if (c.fadeInMs > 0 && relMs < c.fadeInMs)
        g = std::min(g, (float)std::max<int64_t>(relMs, 0) / (float)c.fadeInMs);
    int64_t left = c.durMs - relMs;
    if (c.fadeOutMs > 0 && left < c.fadeOutMs)
        g = std::min(g, (float)std::max<int64_t>(left, 0) / (float)c.fadeOutMs);
    return clampf(g, 0.f, 1.f);
}

float wavePeak(const Clip& c, float srcMs, float msPerBar) {
    if (c.peaks.empty()) return 0.f;
    const float kBucketMs = 50.f;  // AudioWaveform.BUCKET_MS
    int b0 = (int)(srcMs / kBucketMs);
    int b1 = (int)((srcMs + msPerBar) / kBucketMs);
    b0 = std::max(0, std::min(b0, (int)c.peaks.size() - 1));
    b1 = std::max(b0, std::min(b1, (int)c.peaks.size() - 1));
    float peak = 0.f;
    for (int b = b0; b <= b1; ++b) peak = std::max(peak, c.peaks[b]);
    return peak;
}

void thumbWindow(const Clip& c, float* u0, float* u1) {
    *u0 = 0.f;
    *u1 = 0.f;
    if (c.srcDurMs <= 0) return;
    const double dur = (double)c.srcDurMs;
    const double sp = c.speed > 0.f ? (double)c.speed : 1.0;
    double from = (double)c.trimStartMs / dur;
    double to = ((double)c.trimStartMs + (double)c.durMs * sp) / dur;
    from = std::max(0.0, std::min(from, 1.0));
    to = std::max(from, std::min(to, 1.0));
    *u0 = (float)from;
    *u1 = (float)to;
}

namespace {

// ---- Atlas text ----
const AtlasEntry* entry(const Atlas& a, int32_t idx) {
    if (idx < 0 || idx >= (int32_t)a.entries.size() || a.rgba.empty()) return nullptr;
    return &a.entries[idx];
}

// Blits an atlas entry with its ink centred at (cx, cy), tinted [tint] (RGB from tint, alpha from the atlas).
void blitCentered(Fb& fb, const Atlas& at, int32_t idx, float cx, float cy, uint32_t tint, float alphaMul) {
    const AtlasEntry* e = entry(at, idx);
    if (!e || e->w <= 0 || e->h <= 0) return;
    int dx = (int)std::lround(cx - e->w / 2.f), dy = (int)std::lround(cy - e->h / 2.f);
    uint32_t rgb = tint & 0x00FFFFFFu;
    float ta = ((tint >> 24) / 255.f) * alphaMul;
    for (int j = 0; j < e->h; ++j) {
        int sy = e->y + j;
        if (sy < 0 || sy >= at.h) continue;
        for (int i = 0; i < e->w; ++i) {
            int sx = e->x + i;
            if (sx < 0 || sx >= at.w) continue;
            uint8_t cov = at.rgba[((size_t)sy * at.w + sx) * 4 + 3];
            if (!cov) continue;
            fb.blendPx(dx + i, dy + j, (255u << 24) | rgb, cov / 255.f * ta);
        }
    }
}

// Blits an atlas entry with its ink box's left edge at [left], vertically centred at [cy].
void blitLeft(Fb& fb, const Atlas& at, int32_t idx, float left, float cy, uint32_t tint, float alphaMul) {
    const AtlasEntry* e = entry(at, idx);
    if (!e) return;
    blitCentered(fb, at, idx, left + e->w / 2.f, cy, tint, alphaMul);
}

float entryW(const Atlas& at, int32_t idx) {
    const AtlasEntry* e = entry(at, idx);
    return e ? (float)e->w : 0.f;
}
float entryH(const Atlas& at, int32_t idx) {
    const AtlasEntry* e = entry(at, idx);
    return e ? (float)e->h : 0.f;
}

// ---- Material-style glyphs drawn from primitives (24-unit grid, origin at (ox, oy), unit u px) ----

void drawEye(Fb& fb, float cx, float cy, float sizePx, bool off, uint32_t tint, float alphaMul) {
    uint32_t col = mulAlpha(tint, alphaMul);
    float u = sizePx / 24.f;
    float ox = cx - 12.f * u, oy = cy - 12.f * u;
    // The almond outline (two cubics), the iris ring and the pupil, even-odd filled.
    std::vector<Pt> almond = cubic({ox + 1.f * u, oy + 12.f * u}, {ox + 5.f * u, oy + 5.2f * u},
                                   {ox + 8.f * u, oy + 4.5f * u}, {ox + 12.f * u, oy + 4.5f * u});
    std::vector<Pt> top2 = cubic({ox + 12.f * u, oy + 4.5f * u}, {ox + 16.f * u, oy + 4.5f * u},
                                 {ox + 19.f * u, oy + 5.2f * u}, {ox + 23.f * u, oy + 12.f * u});
    std::vector<Pt> bot = cubic({ox + 23.f * u, oy + 12.f * u}, {ox + 19.f * u, oy + 18.8f * u},
                                {ox + 16.f * u, oy + 19.5f * u}, {ox + 12.f * u, oy + 19.5f * u});
    std::vector<Pt> bot2 = cubic({ox + 12.f * u, oy + 19.5f * u}, {ox + 8.f * u, oy + 19.5f * u},
                                 {ox + 5.f * u, oy + 18.8f * u}, {ox + 1.f * u, oy + 12.f * u});
    std::vector<Pt> shape;
    shape.insert(shape.end(), almond.begin(), almond.end());
    shape.insert(shape.end(), top2.begin() + 1, top2.end());
    shape.insert(shape.end(), bot.begin() + 1, bot.end());
    shape.insert(shape.end(), bot2.begin() + 1, bot2.end());
    // Even-odd: almond filled, the iris disc (r5) punched out; the pupil (r3) is then drawn
    // in the cell colour — in Compose it is a hole in the icon showing the cell beneath.
    fillPaths(fb, {shape, circlePath(cx, cy, 5.f * u)}, col);
    fillCircle(fb, cx, cy, 3.f * u, mulAlpha(kEyeBg, alphaMul));
    if (off) {
        // The eye-off slash.
        drawLine(fb, ox + 4.f * u, oy + 20.f * u, ox + 20.f * u, oy + 4.f * u, 1.9f * u, col);
    }
}

void drawLinkGlyph(Fb& fb, float cx, float cy, float sizePx, uint32_t tint, float alphaMul) {
    // The chain/link glyph (the same construction as the bars' "chain" kind): two overlapping
    // rounded-rect outlines in a 24-unit grid.
    uint32_t col = mulAlpha(tint, alphaMul);
    float u = sizePx / 24.f;
    float ox = cx - 12.f * u, oy = cy - 12.f * u;
    strokeRoundRectC(fb, ox + 3.5f * u, oy + 9.f * u, 10.f * u, 6.5f * u, 3.25f * u, 1.9f * u, col);
    strokeRoundRectC(fb, ox + 10.5f * u, oy + 9.f * u, 10.f * u, 6.5f * u, 3.25f * u, 1.9f * u, col);
}

void drawCheck(Fb& fb, float cx, float cy, float sizePx, uint32_t tint, float alphaMul) {
    uint32_t col = mulAlpha(tint, alphaMul);
    float u = sizePx / 24.f;
    float ox = cx - 12.f * u, oy = cy - 12.f * u;
    std::vector<Pt> pts = {{ox + 4.6f * u, oy + 12.6f * u}, {ox + 9.6f * u, oy + 17.4f * u}, {ox + 19.4f * u, oy + 7.2f * u}};
    strokePolyline(fb, pts, false, 2.3f * u, col);
}

void drawX(Fb& fb, float cx, float cy, float boxPx, float strokePx, uint32_t tint, float alphaMul) {
    uint32_t col = mulAlpha(tint, alphaMul);
    float m = strokePx / 2.f;
    float x0 = cx - boxPx / 2.f + m, y0 = cy - boxPx / 2.f + m;
    float x1 = cx + boxPx / 2.f - m, y1 = cy + boxPx / 2.f - m;
    drawLine(fb, x0, y0, x1, y1, strokePx, col);
    drawLine(fb, x1, y0, x0, y1, strokePx, col);
}

// The bars' type glyphs — a faithful port of BarTypeIcon (TimelineLayerPanel.kt), 24-unit grid.
void drawBarGlyph(Fb& fb, int32_t kind, float ox, float oy, float sizePx, uint32_t tint, float alphaMul) {
    uint32_t c = mulAlpha(tint, alphaMul);
    float u = sizePx / 24.f;
    auto X = [&](float v) { return ox + v * u; };
    auto Y = [&](float v) { return oy + v * u; };
    switch (kind) {
        case kGlyphText: {
            float w = 2.3f * u;
            drawLine(fb, X(5), Y(5), X(19), Y(5), w, c);
            drawLine(fb, X(12), Y(5), X(12), Y(19), w, c);
            drawLine(fb, X(9), Y(19), X(15), Y(19), w, c);
            break;
        }
        case kGlyphShape: {
            fillCircle(fb, X(9.5f), Y(9.5f), 5.f * u, mulAlpha(c, 0.65f));
            fillRoundRect(fb, X(9.5f), Y(9.5f), 10.5f * u, 10.5f * u, 2.5f * u, c);
            break;
        }
        case kGlyphFx: {
            auto star = [&](float scx, float scy, float r, uint32_t colr) {
                const float p[8][2] = {{0, -1}, {0.22f, -0.22f}, {1, 0}, {0.22f, 0.22f},
                                       {0, 1}, {-0.22f, 0.22f}, {-1, 0}, {-0.22f, -0.22f}};
                std::vector<Pt> pts;
                for (auto& q : p) pts.push_back({X(scx) + q[0] * r * u, Y(scy) + q[1] * r * u});
                fillPoly(fb, pts, colr);
            };
            star(8.5f, 8.5f, 5.5f, mulAlpha(c, 0.55f));
            star(15.5f, 15.5f, 7.f, c);
            break;
        }
        case kGlyphGroup: {
            strokeRoundRectC(fb, X(4), Y(4), 13.f * u, 13.f * u, 3.f * u, 1.8f * u, mulAlpha(c, 0.55f));
            strokeRoundRectC(fb, X(8), Y(8), 12.f * u, 12.f * u, 3.f * u, 1.8f * u, c);
            break;
        }
        case kGlyphNull: {
            fillRoundRect(fb, X(7), Y(7), 10.f * u, 10.f * u, 1.5f * u, c);
            const float corners[4][2] = {{4, 4}, {16.5f, 4}, {4, 16.5f}, {16.5f, 16.5f}};
            for (auto& q : corners)
                strokeRoundRectC(fb, X(q[0]), Y(q[1]), 3.5f * u, 3.5f * u, 1.f * u, 1.3f * u,
                                 mulAlpha(kNullPink, alphaMul));
            break;
        }
        case kGlyphSticker: {
            strokeCircle(fb, X(12), Y(12), 7.5f * u, 1.8f * u, c);
            fillCircle(fb, X(9.3f), Y(10), 1.1f * u, c);
            fillCircle(fb, X(14.7f), Y(10), 1.1f * u, c);
            auto smile = quad({X(8.8f), Y(13.8f)}, {X(12), Y(16.6f)}, {X(15.2f), Y(13.8f)});
            strokePolyline(fb, smile, false, 1.8f * u, c);
            break;
        }
        case kGlyphCamera: {
            fillRoundRect(fb, X(3.5f), Y(8), 11.5f * u, 8.5f * u, 2.f * u, c);
            fillPoly(fb, {{X(15), Y(11)}, {X(20.5f), Y(8.5f)}, {X(20.5f), Y(16)}, {X(15), Y(13.5f)}}, c);
            break;
        }
        case kGlyphVector: {
            std::vector<Pt> pts = {{X(5), Y(18)}, {X(9.5f), Y(7)}, {X(14.5f), Y(14)}, {X(19), Y(5.5f)}};
            strokePolyline(fb, pts, false, 1.7f * u, c);
            for (auto& q : pts) fillCircle(fb, q.x, q.y, 1.9f * u, c);
            break;
        }
        case kGlyphDrawing: {
            std::vector<Pt> path = cubic({X(4), Y(15)}, {X(7), Y(6)}, {X(10), Y(19)}, {X(13), Y(10)});
            std::vector<Pt> path2 = cubic({X(13), Y(10)}, {X(15), Y(5)}, {X(17.5f), Y(12)}, {X(20), Y(9)});
            path.insert(path.end(), path2.begin() + 1, path2.end());
            strokePolyline(fb, path, false, 1.9f * u, c);
            break;
        }
        case kGlyphVideo: {
            strokeRoundRectC(fb, X(4), Y(5.5f), 16.f * u, 13.f * u, 2.5f * u, 1.8f * u, c);
            drawLine(fb, X(9), Y(5.5f), X(9), Y(18.5f), 1.4f * u, c);
            drawLine(fb, X(15), Y(5.5f), X(15), Y(18.5f), 1.4f * u, c);
            break;
        }
        case kGlyphMusic: {
            fillCircle(fb, X(8.5f), Y(17), 2.6f * u, c);
            fillCircle(fb, X(17.5f), Y(15), 2.6f * u, c);
            drawLine(fb, X(11.1f), Y(17), X(11.1f), Y(6.5f), 1.8f * u, c);
            drawLine(fb, X(20.1f), Y(15), X(20.1f), Y(4.5f), 1.8f * u, c);
            drawLine(fb, X(11.1f), Y(6.5f), X(20.1f), Y(4.5f), 2.2f * u, c);
            break;
        }
        case kGlyphChain: {
            strokeRoundRectC(fb, X(3.5f), Y(9), 10.f * u, 6.5f * u, 3.25f * u, 1.9f * u, c);
            strokeRoundRectC(fb, X(10.5f), Y(9), 10.f * u, 6.5f * u, 3.25f * u, 1.9f * u, c);
            break;
        }
        default:
            fillCircle(fb, X(12), Y(12), 4.f * u, c);
            break;
    }
}

}  // namespace

namespace {

struct Ctx {
    const Snapshot& s;
    const Atlas& at;
    const ThumbAtlas& th;
    const View& v;
    float d;    // density (px per dp)
    float ppm;  // px per ms
    float dp(float v) const { return v * d; }
};

void clearAll(Fb& fb, uint32_t argb) {
    uint8_t r = (uint8_t)(argb >> 16), g = (uint8_t)(argb >> 8), b = (uint8_t)argb;
    for (int y = 0; y < fb.h; ++y) {
        uint8_t* row = fb.p + (size_t)y * fb.stride;
        for (int x = 0; x < fb.w; ++x) {
            row[x * 4] = r; row[x * 4 + 1] = g; row[x * 4 + 2] = b; row[x * 4 + 3] = 255;
        }
    }
}

// ---- Waveforms (AudioClipBody / VideoWaveformStrip ports) ----

void drawAudioWave(Fb& fb, const Ctx& c, const Clip& cl, float x, float y, float w, float h, float alphaMul) {
    float step = c.dp(3), bw = c.dp(1.5f);
    float midY = y + h * 0.62f, maxHalf = h * 0.30f;
    float msPerBar = step / c.ppm * cl.speed;
    float localFrom = std::max(0.f, -x);
    float localTo = std::min(w, (float)fb.w - x);
    float vol = clampf(cl.volume, 0.f, 1.f);
    for (float bx = std::floor(localFrom / step) * step; bx < localTo; bx += step) {
        float srcMs = (float)cl.trimStartMs + (bx / c.ppm) * cl.speed;
        float level = cl.peaks.empty() ? 0.12f : wavePeak(cl, srcMs, msPerBar);
        float half = std::max(maxHalf * level, c.dp(1));
        int64_t rel = (int64_t)(bx / c.ppm);
        float hh = std::max(half * vol * fadeGainAt(cl, rel), c.dp(1));
        drawLine(fb, x + bx, midY - hh, x + bx, midY + hh, bw, mulAlpha(kWhite, 0.9f * alphaMul));
    }
}

void drawVideoWave(Fb& fb, const Ctx& c, const Clip& cl, float x, float y, float w, float h, float alphaMul) {
    if (cl.peaks.empty() || (cl.flags & kFlagMuted)) return;
    float stripH = h / 2.f;
    // The strip's black gradient (transparent -> black 55%) over the bottom half.
    for (float row = y + stripH; row < y + h; row += 1.f) {
        float a = 0.55f * (row - (y + stripH) + 1.f) / stripH;
        fillRect(fb, x, row, x + w, row + 1.f, mulAlpha(withAlpha(0xFF000000u, a), alphaMul));
    }
    float step = c.dp(3), bw = c.dp(1.5f);
    float baseY = y + h - c.dp(2), maxH = stripH - c.dp(4);
    float msPerBar = step / c.ppm * cl.speed;
    float localFrom = std::max(0.f, -x);
    float localTo = std::min(w, (float)fb.w - x);
    float vol = clampf(cl.volume, 0.f, 1.f);
    for (float bx = std::floor(localFrom / step) * step; bx < localTo; bx += step) {
        float srcMs = (float)cl.trimStartMs + (bx / c.ppm) * cl.speed;
        float peak = wavePeak(cl, srcMs, msPerBar);
        float hh = std::max(maxH * peak * vol, c.dp(1));
        drawLine(fb, x + bx, baseY, x + bx, baseY - hh, bw, mulAlpha(kWhite, 0.9f * alphaMul));
    }
}

// A text chip on the black-55% pill (name chip / audio duration badge).
void drawChip(Fb& fb, const Ctx& c, int32_t atlasIdx, float x, float y, float padHdp, float padVdp, float alphaMul) {
    if (atlasIdx < 0) return;
    float ew = entryW(c.at, atlasIdx), eh = entryH(c.at, atlasIdx);
    if (ew <= 0) return;
    float cw = ew + padHdp * 2 * c.d, ch = eh + padVdp * 2 * c.d;
    fillRoundRect(fb, x, y, cw, ch, c.dp(5), mulAlpha(kChipBg, alphaMul));
    blitCentered(fb, c.at, atlasIdx, x + cw / 2.f, y + ch / 2.f, kWhite, alphaMul);
}

// ---- Filmstrip thumbnails (build 259) ----

// Blits the clip's thumbnail strip window into the bar rect, bilinear,
// through blendPx (so the bar's rounded clip applies). The strip covers
// the FULL source range; the bar shows the thumbWindow slice of it, so
// the frame tiles tile along the lane and stretch / crop with the time
// scale. Returns false when the strip is not ready yet — the caller then
// draws the placeholder shading. No allocations: fixed per-pixel math
// over the bar's visible rect only.
bool drawFilmstrip(Fb& fb, const Ctx& c, const Clip& cl, float x, float y, float w, float h, float alphaMul) {
    if (cl.thumb < 0 || cl.thumb >= (int32_t)c.th.entries.size() || c.th.rgba.empty()) return false;
    const AtlasEntry& e = c.th.entries[(size_t)cl.thumb];
    if (e.w <= 0 || e.h <= 0) return false;
    float u0, u1;
    thumbWindow(cl, &u0, &u1);
    if (u1 <= u0) return false;
    int ix0 = std::max(0, (int)std::floor(x)), iy0 = std::max(0, (int)std::floor(y));
    int ix1 = std::min(fb.w, (int)std::ceil(x + w)), iy1 = std::min(fb.h, (int)std::ceil(y + h));
    if (ix1 <= ix0 || iy1 <= iy0) return false;
    // Source span in texel-index space (texel k's centre sits at k): dst
    // pixel centres map linearly onto it, clamped to the entry's own
    // first/last texel centres so neighbouring strips never bleed in.
    const float sx0 = (float)e.x + u0 * (float)e.w - 0.5f;
    const float sx1 = (float)e.x + u1 * (float)e.w - 0.5f;
    const float sy0 = (float)e.y - 0.5f;
    const float sy1 = (float)(e.y + e.h) - 0.5f;
    const float loX = (float)e.x, hiX = (float)(e.x + e.w - 1);
    const float loY = (float)e.y, hiY = (float)(e.y + e.h - 1);
    uint8_t rgb[3];
    for (int j = iy0; j < iy1; ++j) {
        float sy = clampf(sy0 + ((j + 0.5f - y) / h) * (sy1 - sy0), loY, hiY);
        int y0 = (int)std::floor(sy);
        int y1 = std::min(y0 + 1, (int)hiY);
        float fy = sy - (float)y0;
        const uint8_t* row0 = &c.th.rgba[(size_t)y0 * (size_t)c.th.w * 4];
        const uint8_t* row1 = &c.th.rgba[(size_t)y1 * (size_t)c.th.w * 4];
        for (int i = ix0; i < ix1; ++i) {
            float sx = clampf(sx0 + ((i + 0.5f - x) / w) * (sx1 - sx0), loX, hiX);
            int x0 = (int)std::floor(sx);
            int x1 = std::min(x0 + 1, (int)hiX);
            float fx = sx - (float)x0;
            const uint8_t* p00 = row0 + (size_t)x0 * 4;
            const uint8_t* p10 = row0 + (size_t)x1 * 4;
            const uint8_t* p01 = row1 + (size_t)x0 * 4;
            const uint8_t* p11 = row1 + (size_t)x1 * 4;
            for (int ch = 0; ch < 3; ++ch) {
                float top = (float)p00[ch] + ((float)p10[ch] - (float)p00[ch]) * fx;
                float bot = (float)p01[ch] + ((float)p11[ch] - (float)p01[ch]) * fx;
                rgb[ch] = (uint8_t)(top + (bot - top) * fy + 0.5f);
            }
            fb.blendPx(i, j,
                       (255u << 24) | ((uint32_t)rgb[0] << 16) | ((uint32_t)rgb[1] << 8) | (uint32_t)rgb[2],
                       alphaMul);
        }
    }
    return true;
}

// ---- One clip bar ----
void drawClip(Fb& fb, const Ctx& c, int32_t ti, int32_t ci, bool asDragged) {
    const Snapshot& s = c.s;
    const Track& tr = s.tracks[ti];
    const Clip& cl = tr.clips[ci];
    if (cl.flags & kFlagChain) return;  // the section itself is drawn by the track pass
    float x, y, w, h;
    barRectPx(s, c.v, ti, ci, &x, &y, &w, &h);
    if (asDragged) { x += c.v.dragDx; y += c.v.dragDy; }
    if (x + w < -64 || x > fb.w + 64 || y + h < 0 || y > fb.h) return;
    float alphaMul = (cl.flags & kFlagHidden) ? 0.35f : 1.f;
    bool inChain = (cl.flags & kFlagInChain) != 0;
    bool selected = (cl.flags & kFlagSelected) != 0;
    bool multiSel = (cl.flags & kFlagMulti) != 0;
    float barR = inChain ? c.dp(6) : (s.comfort ? c.dp(8) : c.dp(7));
    float cy = y + h / 2.f;

    if (cl.flags & kFlagController) {
        // Hollow teal (the settled Controller treatment): no fill but a faint wash when selected.
        if (selected) fillRoundRect(fb, x, y, w, h, barR, mulAlpha(withAlpha(kTeal, 0.14f), alphaMul));
        strokeRoundRectIn(fb, x, y, w, h, barR, c.dp(selected ? 2 : 1.5f), mulAlpha(kTeal, alphaMul));
        blitLeft(fb, c.at, cl.label, x + c.dp(9), cy, kTeal, alphaMul);
        return;
    }

    // Selection outer ring (behind the body, 2 dp outside it).
    if (selected || multiSel) {
        float o = c.dp(2.f);
        strokeRoundRectC(fb, x - o, y - o, w + 2 * o, h + 2 * o, barR + o, c.dp(1),
                         mulAlpha(withAlpha(kWhite, 0.25f), alphaMul));
    }
    // Body.
    fillRoundRect(fb, x, y, w, h, barR, mulAlpha(cl.color, alphaMul));

    // Contents, clipped to the bar's rounded shape (Compose clips the bar box).
    fb.clipOn = true; fb.cx0 = x; fb.cy0 = y; fb.cx1 = x + w; fb.cy1 = y + h; fb.cr = barR;
    if ((cl.flags & kFlagVideo) && !inChain) {
        // Build 259: real filmstrip thumbnails once the clip's strip has
        // been extracted; until then the build-224 fake cell shading over
        // the body stays as the placeholder.
        if (!drawFilmstrip(fb, c, cl, x, y, w, h, alphaMul)) {
            float cellW = c.dp(34);
            int i0 = std::max(0, (int)std::floor(-x / cellW));
            for (float cellX = x + i0 * cellW, i = (float)i0; cellX < x + w && cellX < fb.w; cellX += cellW, i += 1) {
                float a = ((int)i % 2 == 0) ? 0.11f : 0.045f;
                fillRect(fb, cellX, y, std::min(cellX + cellW, x + w), y + h,
                         mulAlpha(withAlpha(kWhite, a), alphaMul));
                if (i > 0) fillRect(fb, cellX - c.dp(0.5f), y, cellX + c.dp(0.5f), y + h,
                                    mulAlpha(withAlpha(0xFF000000u, 0.30f), alphaMul));
            }
        }
        if (cl.flags & kFlagHasUri) drawVideoWave(fb, c, cl, x, y, w, h, alphaMul);
    } else if ((cl.flags & kFlagAudio) && !inChain) {
        drawAudioWave(fb, c, cl, x, y, w, h, alphaMul);
        drawChip(fb, c, cl.badge, x + c.dp(3), y + c.dp(2), 3, 1, alphaMul);
    } else {
        // Capsule bar: type glyph + label.
        float labelX = x + c.dp(8);
        if (cl.kind != kGlyphNone) {
            drawBarGlyph(fb, cl.kind, x + c.dp(8), cy - c.dp(6.5f), c.dp(13), kWhite, alphaMul);
            labelX += c.dp(13) + c.dp(5);
        }
        blitLeft(fb, c.at, cl.label, labelX, cy, kWhite, alphaMul);
    }
    // Name chip on media bars.
    if ((cl.flags & (kFlagVideo | kFlagAudio)) && !inChain)
        drawChip(fb, c, cl.chip, x + c.dp(3), y + c.dp(2), 4, 1, alphaMul);
    // Group member edge.
    if ((cl.flags & kFlagGroupEdge) && !inChain)
        fillRect(fb, x, y, x + c.dp(3), y + h, mulAlpha(kGroupTeal, alphaMul));
    // In-bar keyframe diamonds (selected layer).
    if (selected && !inChain) {
        for (int64_t t : cl.keysRelMs) {
            if (t < 0 || t > cl.durMs) continue;
            float kx = x + (float)t * c.ppm;
            float half = c.dp(6), halfW = half * 0.8f;
            fillPoly(fb, {{kx, cy - half}, {kx + halfW, cy}, {kx, cy + half}, {kx - halfW, cy}},
                     mulAlpha(kKeyInk, alphaMul));
            float ih = half - c.dp(1.5f), ihw = halfW - c.dp(1.5f);
            fillPoly(fb, {{kx, cy - ih}, {kx + ihw, cy}, {kx, cy + ih}, {kx - ihw, cy}},
                     mulAlpha(kWhite, alphaMul));
        }
    }
    fb.clipOn = false;

    // Drag handle (three white lines) / chain child's delete x.
    bool sectionSel = tr.chainClip >= 0 && tr.chainClip < (int32_t)tr.clips.size() &&
                      (tr.clips[tr.chainClip].flags & kFlagSelected);
    if (inChain) {
        if (selected || sectionSel) {
            float boxX = x + w - c.dp(4) - c.dp(24), boxY = y + (h - c.dp(24)) / 2.f;
            float vx = boxX + c.dp(5), vy = boxY + c.dp(5);
            fillRoundRect(fb, vx, vy, c.dp(14), c.dp(14), c.dp(4),
                          mulAlpha(withAlpha(0xFF000000u, 0.35f), alphaMul));
            drawX(fb, vx + c.dp(7), vy + c.dp(7), c.dp(8), c.dp(1.5f), kWhite, alphaMul);
        }
    } else if (!(cl.flags & kFlagChain)) {
        float rightX = x + w - c.dp(8);
        float startY = cy - c.dp(3.9f);
        for (int i = 0; i < 3; ++i)
            fillRoundRect(fb, rightX - c.dp(14), startY + i * c.dp(3.3f), c.dp(14), c.dp(1.3f),
                          c.dp(0.65f), mulAlpha(withAlpha(kWhite, 0.55f), alphaMul));
    }
    // Selection border (inside the edge).
    if (selected || multiSel)
        strokeRoundRectIn(fb, x, y, w, h, barR, c.dp(1.5f), mulAlpha(kWhite, alphaMul));
    // fx badge.
    if ((cl.flags & kFlagFx) && !inChain) {
        float bcx = x + c.dp(1.5f) + c.dp(7), bcy = y - c.dp(1.5f) + c.dp(7), br = c.dp(7);
        fillCircle(fb, bcx, bcy, br, mulAlpha(kFxBadge, alphaMul));
        strokeCircle(fb, bcx, bcy, br - c.dp(0.6f), c.dp(1.2f), mulAlpha(kBg, alphaMul));
        if (cl.flags & kFlagAudio) { /* audio's badge slot holds the duration chip instead */ }
        else if (cl.badge >= 0) blitCentered(fb, c.at, cl.badge, bcx, bcy, kWhite, alphaMul);
    }
}

// Trim handle (white pill) + fade knob for the selected clip, drawn after its track.
void drawTrimHandle(Fb& fb, const Ctx& c, bool left, float barX, float laneTop, float rowH) {
    float boxX = left ? barX - c.dp(26) : barX;
    float pillX = boxX + c.dp(26 - 7) / 2.f;
    float pillY = laneTop + c.dp(2), pillH = rowH - c.dp(4);
    fillRoundRect(fb, pillX, pillY, c.dp(7), pillH, c.dp(4), kWhite);
    strokeRoundRectIn(fb, pillX, pillY, c.dp(7), pillH, c.dp(4), c.dp(1), 0x0F000000u);
    float lineH = pillH * 0.52f;
    fillRoundRect(fb, pillX + c.dp(2.7f), pillY + (pillH - lineH) / 2.f, c.dp(1.6f), lineH, c.dp(0.8f), kLimeInk);
}

void drawFadeKnob(Fb& fb, const Ctx& c, bool left, float cx, float cy) {
    fillCircle(fb, cx, cy, c.dp(6.5f), kWhite);
    float s = c.dp(13);
    std::vector<Pt> tri;
    if (left) tri = {{cx - 0.14f * s, cy - 0.2f * s}, {cx + 0.18f * s, cy}, {cx - 0.14f * s, cy + 0.2f * s}};
    else tri = {{cx + 0.14f * s, cy - 0.2f * s}, {cx - 0.18f * s, cy}, {cx + 0.14f * s, cy + 0.2f * s}};
    fillPoly(fb, tri, 0xFF111111u);
}

}  // namespace

void Renderer::draw(uint8_t* dst, int32_t dstW, int32_t dstH, int32_t stride, const View& v) const {
    if (!dst || dstW <= 0 || dstH <= 0) return;
    Fb fb{dst, dstW, dstH, stride};
    const Snapshot& s = snap_;
    Ctx c{s, atlas_, thumbAtlas_, v, s.density, ppmPx(s)};
    float d = c.d;
    float R = s.rulerHPx;
    float cx = v.w / 2.f;

    clearAll(fb, kBg);

    // ---- Ruler: ticks, range band, beat marks, keyframe diamonds ----
    {
        float areaStart = c.dp(6), areaEnd = v.w - c.dp(6);
        int64_t step = rulerStepMs(c.ppm, d);
        int64_t majorEvery = step * 5;
        int64_t halfSpan = (int64_t)(cx / c.ppm) + step;
        int64_t firstTick = std::max<int64_t>(v.playheadMs - halfSpan, 0) / step * step;
        int64_t lastTick = v.playheadMs + halfSpan;
        int64_t lastMs = s.totalMs > 0 ? s.totalMs : INT64_MAX / 4;
        for (int64_t t = firstTick; t <= lastTick && t <= lastMs; t += step) {
            float x = xForMs(s, v, t);
            if (x < areaStart || x > areaEnd) continue;
            if (t % majorEvery == 0)
                drawLine(fb, x, c.dp(3), x, c.dp(16), c.dp(1.2f), kTickMajor);
            else
                drawLine(fb, x, c.dp(3), x, c.dp(9), c.dp(1), kTickMinor);
        }
        if (s.hasRange && s.rangeOutMs > s.rangeInMs) {
            float xIn = xForMs(s, v, s.rangeInMs), xOut = xForMs(s, v, s.rangeOutMs);
            float bl = std::max(xIn, areaStart), br = std::min(xOut, areaEnd);
            if (br > bl) fillRect(fb, bl, 0, br, R, kRangeBand);
            if (xIn >= areaStart && xIn <= areaEnd)
                drawLine(fb, xIn, 0, xIn, R, c.dp(1.6f), kRangeEdge);
            if (xOut >= areaStart && xOut <= areaEnd)
                drawLine(fb, xOut, 0, xOut, R, c.dp(1.6f), kRangeEdge);
        }
        for (int64_t m : s.beatMs) {
            float x = xForMs(s, v, m);
            if (x < areaStart || x > areaEnd) continue;
            fillRoundRect(fb, x - c.dp(1.25f), c.dp(3), c.dp(2.5f), c.dp(15), c.dp(1.25f), kBeat);
        }
        for (int64_t kt : s.rulerKeysMs) {
            float x = xForMs(s, v, kt);
            if (x < areaStart || x > areaEnd) continue;
            float half = c.dp(2.5f), kcy = c.dp(10);
            fillPoly(fb, {{x, kcy - half}, {x + half, kcy}, {x, kcy + half}, {x - half, kcy}}, kKeyOrange);
        }
        // Needle + the ruler's own line segment (below the timecode).
        fillRoundRect(fb, cx - c.dp(1), c.dp(2), c.dp(2), c.dp(11), c.dp(1), kWhite);
        drawLine(fb, cx, c.dp(29), cx, R, c.dp(2), kWhite);
    }

    // ---- Lanes ----
    int32_t selTrack = -1, selClip = -1;
    for (int32_t ti = 0; ti < (int32_t)s.tracks.size(); ++ti) {
        const Track& tr = s.tracks[ti];
        float top = laneTopPx(s, v, ti);
        if (top > dstH || top + tr.heightPx < R) continue;
        // Chain section band + bridges.
        if (tr.chainClip >= 0 && tr.chainClip < (int32_t)tr.clips.size()) {
            const Clip& chain = tr.clips[tr.chainClip];
            bool chainSel = (chain.flags & kFlagSelected) != 0;
            float alphaMul = (chain.flags & kFlagHidden) ? 0.35f : 1.f;
            fillRect(fb, 0, top, (float)dstW, top + tr.heightPx,
                     mulAlpha(chainSel ? kChainBgSel : kChainBg, alphaMul));
            float hair = c.dp(chainSel ? 1.5f : 1.f);
            uint32_t hairCol = mulAlpha(chainSel ? kWhite : kChainBorder, alphaMul);
            fillRect(fb, 0, top, (float)dstW, top + hair, hairCol);
            fillRect(fb, 0, top + tr.heightPx - hair, (float)dstW, top + tr.heightPx, hairCol);
            // Bridges between adjacent children.
            std::vector<const Clip*> kids;
            for (auto& cl : tr.clips) if (cl.flags & kFlagInChain) kids.push_back(&cl);
            std::sort(kids.begin(), kids.end(),
                      [](const Clip* a, const Clip* b) { return a->startMs < b->startMs; });
            float midY = top + tr.heightPx / 2.f;
            for (size_t i = 0; i + 1 < kids.size(); ++i) {
                float x1 = xForMs(s, v, kids[i]->startMs + kids[i]->durMs);
                float x2 = xForMs(s, v, kids[i + 1]->startMs);
                if (x2 > x1) fillRect(fb, x1, midY - c.dp(1), x2, midY + c.dp(1),
                                      mulAlpha(kChainBridge, alphaMul));
            }
            if (kids.empty()) {
                uint32_t ink = chainSel ? kChainInkSel : kChainInk;
                drawLinkGlyph(fb, c.dp(42) + c.dp(6), top + tr.heightPx / 2.f, c.dp(12), ink, alphaMul);
                blitLeft(fb, atlas_, s.chainLabel, c.dp(42) + c.dp(12) + c.dp(5),
                         top + tr.heightPx / 2.f, ink, alphaMul);
            }
            // The selected section's delete x (screen right edge).
            if (chainSel) {
                float boxCx = dstW - c.dp(10), boxCy = top + tr.heightPx / 2.f;
                fillRoundRect(fb, boxCx - c.dp(8), boxCy - c.dp(8), c.dp(16), c.dp(16), c.dp(5),
                              withAlpha(0xFF000000u, 0.45f));
                drawX(fb, boxCx, boxCy, c.dp(8), c.dp(1.5f), kWhite, 1.f);
            }
        }
        for (int32_t ci = 0; ci < (int32_t)tr.clips.size(); ++ci) {
            if (ti == v.dragTrack && ci == v.dragClip) continue;
            drawClip(fb, c, ti, ci, false);
            if (selTrack < 0 && (tr.clips[ci].flags & kFlagSelected)) { selTrack = ti; selClip = ci; }
        }
        // Trim handles + fade knobs of the selected clip ride on its own track.
        if (selTrack == ti && selClip >= 0) {
            const Clip& sc = tr.clips[selClip];
            if (!(sc.flags & kFlagLocked) && !(sc.flags & kFlagChain)) {
                float bx, by, bw, bh;
                barRectPx(s, v, ti, selClip, &bx, &by, &bw, &bh);
                drawTrimHandle(fb, c, true, bx, top, tr.heightPx);
                drawTrimHandle(fb, c, false, bx + bw, top, tr.heightPx);
                if (sc.flags & kFlagAudio) {
                    drawFadeKnob(fb, c, true, bx + (float)sc.fadeInMs * c.ppm, top);
                    drawFadeKnob(fb, c, false, bx + bw - (float)sc.fadeOutMs * c.ppm, top);
                }
            }
        }
    }
    // The dragged clip rides on top of the lanes (under the rail, like Compose).
    if (v.dragTrack >= 0 && v.dragClip >= 0 && v.dragTrack < (int32_t)s.tracks.size() &&
        v.dragClip < (int32_t)s.tracks[v.dragTrack].clips.size())
        drawClip(fb, c, v.dragTrack, v.dragClip, true);

    // ---- Left rail (eye cells), drawn over the lanes ----
    float railW = s.railWPx;
    for (int32_t ti = 0; ti < (int32_t)s.tracks.size(); ++ti) {
        const Track& tr = s.tracks[ti];
        if (tr.clips.empty()) continue;
        float top = laneTopPx(s, v, ti);
        if (top > dstH || top + tr.heightPx < R) continue;
        bool hasChain = tr.chainClip >= 0 && tr.chainClip < (int32_t)tr.clips.size();
        int32_t headIdx = hasChain ? tr.chainClip : 0;
        const Clip& head = tr.clips[headIdx];
        bool headHidden = (head.flags & kFlagHidden) != 0;
        float cellCy = top + tr.heightPx / 2.f;
        if (hasChain && !s.multiMode) {
            bool chainSel = (head.flags & kFlagSelected) != 0;
            uint32_t tint = headHidden ? kEyeHidden : (chainSel ? kWhite : kEyeTint);
            fillRect(fb, 0, top, railW, top + tr.heightPx, kEyeBg);
            float hair = c.dp(chainSel ? 1.5f : 1.f);
            uint32_t hairCol = chainSel ? kWhite : kChainBorder;
            fillRect(fb, railW - c.dp(1), top, railW, top + tr.heightPx, hairCol);
            fillRect(fb, 0, top, railW, top + hair, hairCol);
            fillRect(fb, 0, top + tr.heightPx - hair, railW, top + tr.heightPx, hairCol);
            float colTop = top + (tr.heightPx - c.dp(25)) / 2.f;
            drawEye(fb, railW / 2.f, colTop + c.dp(8), c.dp(14), headHidden, tint, 1.f);
            drawLinkGlyph(fb, railW / 2.f, colTop + c.dp(20.5f), c.dp(9), tint, 1.f);
        } else {
            fillRoundRect(fb, 0, top, railW, tr.heightPx, 0, c.dp(7), c.dp(7), 0, kEyeBg);
            if (s.multiMode) {
                if (head.flags & kFlagMulti) {
                    fillCircle(fb, railW / 2.f, cellCy, c.dp(9), kLime);
                    drawCheck(fb, railW / 2.f, cellCy, c.dp(13), kLimeInk, 1.f);
                } else {
                    strokeCircle(fb, railW / 2.f, cellCy, c.dp(8), c.dp(1.5f), 0x66A1A1AAu);
                }
            } else {
                drawEye(fb, railW / 2.f, cellCy, c.dp(15), headHidden,
                        headHidden ? kEyeHidden : kEyeTint, 1.f);
            }
        }
    }

    // ---- Playhead line through the lanes (soft dark underlay + 2 dp white) ----
    drawLine(fb, cx, R, cx, dstH - c.dp(2), c.dp(7), 0x59000000u);
    drawLine(fb, cx, R, cx, dstH - c.dp(2), c.dp(2), kWhite);

    // ---- Ruler texts + bottom hairline ----
    {
        // Timecode from the digit atlas: "MM:SS:CC" (formatTimeCode).
        if (atlas_.digit[0] >= 0) {
            int64_t ms = std::max<int64_t>(v.playheadMs, 0);
            int64_t totalSec = ms / 1000;
            char buf[24];
            snprintf(buf, sizeof(buf), "%02lld:%02lld:%02lld",
                     (long long)(totalSec / 60), (long long)(totalSec % 60),
                     (long long)((ms % 1000) / 10));
            float totalW = 0;
            for (const char* p = buf; *p; ++p)
                totalW += atlas_.digitAdv[*p == ':' ? 10 : *p - '0'];
            float sx = cx - totalW / 2.f;
            for (const char* p = buf; *p; ++p) {
                int di = *p == ':' ? 10 : *p - '0';
                const AtlasEntry* e = entry(atlas_, atlas_.digit[di]);
                if (e) {
                    // Blit with the baseline on digitBaseY: ink top = baseY - entry.base.
                    // Build 227: per-pixel source bounds checks, mirroring
                    // blitCentered — build 224 indexed the atlas unchecked
                    // here, so an entry rect extending past the bitmap would
                    // read out of bounds.
                    int dx = (int)std::lround(sx + (atlas_.digitAdv[di] - e->w) / 2.f);
                    int dy = (int)std::lround(atlas_.digitBaseY - e->base);
                    for (int j = 0; j < e->h; ++j) {
                        int sy = e->y + j;
                        if (sy < 0 || sy >= atlas_.h) continue;
                        for (int i = 0; i < e->w; ++i) {
                            int sxp = e->x + i;
                            if (sxp < 0 || sxp >= atlas_.w) continue;
                            uint8_t cov = atlas_.rgba[((size_t)sy * atlas_.w + sxp) * 4 + 3];
                            if (cov) fb.blendPx(dx + i, dy + j, 0xFFF4F4F5u, cov / 255.f);
                        }
                    }
                }
                sx += atlas_.digitAdv[di];
            }
        }
        // Total duration, bottom-right.
        if (s.totalLabel >= 0) {
            float ew = entryW(atlas_, s.totalLabel), eh = entryH(atlas_, s.totalLabel);
            blitCentered(fb, atlas_, s.totalLabel, dstW - c.dp(6) - ew / 2.f,
                         R - c.dp(3) - eh / 2.f, 0xFF71717Au, 1.f);
        }
        fillRect(fb, 0, R - c.dp(1), (float)dstW, R, kHairline);
    }
}

// ---- Hit testing (the same layout math; zones mirror the Compose gesture owners) ----
Zone Renderer::hitTest(float x, float y, const View& v, int32_t* track, int32_t* clip) const {
    const Snapshot& s = snap_;
    *track = -1; *clip = -1;
    float d = s.density;
    float R = s.rulerHPx;
    auto dp = [&](float val) { return val * d; };

    if (y < R) return kZoneRuler;

    int32_t selT = -1, selC = -1;
    for (int32_t ti = 0; ti < (int32_t)s.tracks.size() && selT < 0; ++ti)
        for (int32_t ci = 0; ci < (int32_t)s.tracks[ti].clips.size(); ++ci)
            if (s.tracks[ti].clips[ci].flags & kFlagSelected) { selT = ti; selC = ci; break; }
    if (selT >= 0) {
        const Clip& sc = s.tracks[selT].clips[selC];
        if (sc.flags & kFlagAudio) {
            float bx, by, bw, bh;
            barRectPx(s, v, selT, selC, &bx, &by, &bw, &bh);
            float top = laneTopPx(s, v, selT);
            float kx1 = bx + (float)sc.fadeInMs * ppmPx(s);
            float kx2 = bx + bw - (float)sc.fadeOutMs * ppmPx(s);
            if (std::fabs(x - kx1) <= dp(12) && std::fabs(y - top) <= dp(12)) {
                *track = selT; *clip = selC; return kZoneFadeL;
            }
            if (std::fabs(x - kx2) <= dp(12) && std::fabs(y - top) <= dp(12)) {
                *track = selT; *clip = selC; return kZoneFadeR;
            }
        }
    }

    for (int32_t ti = 0; ti < (int32_t)s.tracks.size(); ++ti) {
        const Track& tr = s.tracks[ti];
        float top = laneTopPx(s, v, ti);
        if (y < top || y >= top + tr.heightPx) continue;
        *track = ti;
        if (x < s.railWPx) {
            bool hasChain = tr.chainClip >= 0 && tr.chainClip < (int32_t)tr.clips.size();
            int32_t headIdx = hasChain ? tr.chainClip : (tr.clips.empty() ? -1 : 0);
            if (headIdx < 0) return kZoneEmpty;
            *clip = headIdx;
            if (hasChain && !s.multiMode) {
                float colTop = top + (tr.heightPx - dp(25)) / 2.f;
                if (x >= s.railWPx / 2.f - dp(8) && x <= s.railWPx / 2.f + dp(8) &&
                    y >= colTop && y <= colTop + dp(16))
                    return kZoneEye;
            } else {
                float cy = top + tr.heightPx / 2.f;
                if (x >= s.railWPx / 2.f - dp(12) && x <= s.railWPx / 2.f + dp(12) &&
                    y >= cy - dp(12) && y <= cy + dp(12))
                    return kZoneEye;
            }
            return kZoneRail;
        }
        // The selected chain section's delete x.
        if (tr.chainClip >= 0 && tr.chainClip < (int32_t)tr.clips.size() &&
            (tr.clips[tr.chainClip].flags & kFlagSelected)) {
            float boxCy = top + tr.heightPx / 2.f;
            if (x >= v.w - dp(22) && std::fabs(y - boxCy) <= dp(12)) {
                *clip = tr.chainClip;
                return kZoneChainX;
            }
        }
        // Trim handles of the selected clip (in this track).
        if (ti == selT && selC >= 0) {
            const Clip& sc = s.tracks[selT].clips[selC];
            if (!(sc.flags & kFlagLocked) && !(sc.flags & kFlagChain)) {
                float bx, by, bw, bh;
                barRectPx(s, v, selT, selC, &bx, &by, &bw, &bh);
                if (x >= bx - dp(26) && x < bx) { *clip = selC; return kZoneTrimL; }
                if (x >= bx + bw && x < bx + bw + dp(26)) { *clip = selC; return kZoneTrimR; }
            }
        }
        // Bars (topmost last: children over the chain, later clips over earlier).
        for (int32_t ci = (int32_t)tr.clips.size() - 1; ci >= 0; --ci) {
            float bx, by, bw, bh;
            barRectPx(s, v, ti, ci, &bx, &by, &bw, &bh);
            if (x >= bx && x < bx + bw && y >= by && y < by + bh) {
                const Clip& cl = tr.clips[ci];
                if (cl.flags & kFlagInChain) {
                    bool sectionSel = tr.chainClip >= 0 &&
                        tr.chainClip < (int32_t)tr.clips.size() &&
                        (tr.clips[tr.chainClip].flags & kFlagSelected);
                    if ((cl.flags & kFlagSelected) || sectionSel) {
                        float boxX = bx + bw - dp(4) - dp(24);
                        float boxY = by + (bh - dp(24)) / 2.f;
                        if (x >= boxX && x < boxX + dp(24) && y >= boxY && y < boxY + dp(24)) {
                            *clip = ci;
                            return kZoneChildX;
                        }
                    }
                }
                *clip = ci;
                return kZoneBar;
            }
        }
        return kZoneEmpty;
    }
    return kZoneEmpty;
}

}  // namespace timeline
}  // namespace am
