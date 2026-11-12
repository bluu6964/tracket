#include "mask_plan.h"

#include <algorithm>
#include <cmath>

namespace am {
namespace {

constexpr float kPi = 3.14159265358979f;

inline float clampf(float v, float lo, float hi) { return std::min(hi, std::max(lo, v)); }

float sdRoundBox(float px, float py, float hw, float hh, float r) {
    const float qx = std::fabs(px) - hw + r;
    const float qy = std::fabs(py) - hh + r;
    const float ox = std::max(qx, 0.f), oy = std::max(qy, 0.f);
    return std::sqrt(ox * ox + oy * oy) + std::min(std::max(qx, qy), 0.f) - r;
}

// Ellipse distance estimate (Inigo Quilez): exact on the edge, good near it.
float sdEllipse(float px, float py, float a, float b) {
    const float k0 = std::sqrt((px / a) * (px / a) + (py / b) * (py / b));
    const float k1 = std::sqrt((px / (a * a)) * (px / (a * a)) + (py / (b * b)) * (py / (b * b)));
    if (k1 <= 1e-12f) return -std::min(a, b);
    return k0 * (k0 - 1.f) / k1;
}

// Star / regular polygon with circumradius 1 (Inigo Quilez). p: y up. Vertex points up.
float sdStar(float px, float py, float an, float anCos, float anSin, float enCos, float enSin) {
    const float len = std::sqrt(px * px + py * py);
    float a = std::atan2(px, py);
    const float step = 2.f * an;
    a = a - step * std::floor(a / step);  // GLSL mod()
    const float bn = a - an;
    float x = len * std::cos(bn);
    float y = len * std::fabs(std::sin(bn));
    x -= anCos;
    y -= anSin;
    const float dotp = x * enCos + y * enSin;
    const float t = clampf(-dotp, 0.f, anSin / enSin);
    x += enCos * t;
    y += enSin * t;
    const float l = std::sqrt(x * x + y * y);
    return x < 0.f ? -l : l;
}

float sdPolygon(const float* v, int n, float px, float py) {
    float dx = px - v[0], dy = py - v[1];
    float d = dx * dx + dy * dy;
    float s = 1.f;
    for (int i = 0, j = n - 1; i < n; j = i, i++) {
        const float ex = v[2 * j] - v[2 * i], ey = v[2 * j + 1] - v[2 * i + 1];
        const float wx = px - v[2 * i], wy = py - v[2 * i + 1];
        const float ee = ex * ex + ey * ey;
        const float h = ee > 1e-12f ? clampf((wx * ex + wy * ey) / ee, 0.f, 1.f) : 0.f;
        const float bx = wx - ex * h, by = wy - ey * h;
        d = std::min(d, bx * bx + by * by);
        const bool c1 = py >= v[2 * i + 1];
        const bool c2 = py < v[2 * j + 1];
        const bool c3 = ex * wy > ey * wx;
        if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) s = -s;
    }
    return s * std::sqrt(d);
}

}  // namespace

int maskFlattenPath(const float* c, int n, bool smooth, float* out) {
    if (!c || !out || n < 3) return 0;
    n = std::min(n, kMaskCtrlMax);
    if (!smooth) {
        for (int i = 0; i < n * 2; ++i) out[i] = c[i];
        return n;
    }
    const int spp = std::max(1, std::min(8, kMaskPtsMax / n));  // samples per span
    int cnt = 0;
    for (int i = 0; i < n; ++i) {
        const float* p0 = c + 2 * ((i + n - 1) % n);
        const float* p1 = c + 2 * i;
        const float* p2 = c + 2 * ((i + 1) % n);
        const float* p3 = c + 2 * ((i + 2) % n);
        for (int s = 0; s < spp; ++s) {
            const float t = (float)s / (float)spp, t2 = t * t, t3 = t2 * t;
            for (int k = 0; k < 2; ++k) {
                out[2 * cnt + k] = 0.5f * ((2.f * p1[k]) + (-p0[k] + p2[k]) * t +
                                           (2.f * p0[k] - 5.f * p1[k] + 4.f * p2[k] - p3[k]) * t2 +
                                           (-p0[k] + 3.f * p1[k] - 3.f * p2[k] + p3[k]) * t3);
            }
            ++cnt;
        }
    }
    return cnt;
}

int maskParseOps(const float* f, int n, int W, int H, MaskOp* out) {
    int count = 0;
    if (!f || !out || W <= 0 || H <= 0) return 0;
    for (int i = 0; i + kMaskStride <= n && count < kMaskMax; i += kMaskStride) {
        const float* m = f + i;
        MaskOp o;
        o.shape = (int)std::lround(m[0]);
        if (o.shape < MS_RECT || o.shape > MS_PATH) continue;
        o.mode = (int)std::lround(m[1]);
        if (o.mode < MM_ADD || o.mode > MM_DIFFERENCE) o.mode = MM_ADD;
        o.invert = m[2] > 0.5f;
        o.opacity = clampf(m[10], 0.f, 100.f) / 100.f;
        if (o.opacity <= 0.001f) continue;
        o.cx = m[3] * (float)W;
        o.cy = m[4] * (float)H;
        o.hw = std::max(0.5f, std::fabs(m[5]) * (float)W * 0.5f);
        o.hh = std::max(0.5f, std::fabs(m[6]) * (float)H * 0.5f);
        const float rad = m[7] * kPi / 180.f;
        o.cosR = std::cos(rad);
        o.sinR = std::sin(rad);
        o.feather = clampf(m[8], 0.f, 100.f) / 100.f * 0.10f * (float)H;
        o.expand = clampf(m[9], -100.f, 100.f) / 100.f * 0.25f * (float)H;
        o.corner = clampf(m[11], 0.f, 100.f) / 100.f * std::min(o.hw, o.hh);
        const int sides = std::max(3, std::min(12, (int)std::lround(m[12])));
        const float inner = o.shape == MS_POLYGON ? 100.f : clampf(m[13], 5.f, 100.f);
        o.anRad = kPi / (float)sides;
        o.anCos = std::cos(o.anRad);
        o.anSin = std::sin(o.anRad);
        const float rho = o.anCos * inner / 100.f;  // inner radius / outer radius
        const float en = std::atan2(o.anSin, o.anCos - rho);
        o.enCos = std::cos(en);
        o.enSin = std::sin(en);
        if (o.shape == MS_PATH) {
            const int nc = std::max(0, std::min(kMaskCtrlMax, (int)std::lround(m[14])));
            if (nc < 3) continue;
            float flat[kMaskPtsMax * 2];
            const int np = maskFlattenPath(m + 16, nc, m[15] > 0.5f, flat);
            if (np < 3) continue;
            // local box -> picture pixels (scale by the full box, turn, move)
            for (int k = 0; k < np; ++k) {
                const float lx = flat[2 * k] * o.hw * 2.f;
                const float ly = flat[2 * k + 1] * o.hh * 2.f;
                o.pts[2 * k] = o.cx + lx * o.cosR - ly * o.sinR;
                o.pts[2 * k + 1] = o.cy + lx * o.sinR + ly * o.cosR;
            }
            o.nPts = np;
        }
        out[count++] = o;
    }
    return count;
}

float maskShapeDistance(const MaskOp& m, float x, float y) {
    if (m.shape == MS_PATH) return sdPolygon(m.pts, m.nPts, x, y);
    const float dx = x - m.cx, dy = y - m.cy;
    const float lx = dx * m.cosR + dy * m.sinR;
    const float ly = -dx * m.sinR + dy * m.cosR;
    switch (m.shape) {
        case MS_RECT: return sdRoundBox(lx, ly, m.hw, m.hh, m.corner);
        case MS_ELLIPSE: return sdEllipse(lx, ly, m.hw, m.hh);
        case MS_POLYGON:
        case MS_STAR: {
            const float s = std::min(m.hw, m.hh);
            return sdStar(lx / m.hw, -ly / m.hh, m.anRad, m.anCos, m.anSin, m.enCos, m.enSin) * s;
        }
        case MS_LINE: return ly;
        case MS_MIRROR: return std::fabs(ly) - m.hh;
        default: return 1e9f;
    }
}

float maskCoverage(const MaskOp& m, float x, float y) {
    const float d = maskShapeDistance(m, x, y) - m.expand;
    const float w = std::max(2.f * m.feather, 1.f);
    const float t = clampf(0.5f - d / w, 0.f, 1.f);
    float cov = t * t * (3.f - 2.f * t);
    if (m.invert) cov = 1.f - cov;
    return cov * m.opacity;
}

float maskCombine(int mode, float prev, float cov) {
    switch (mode) {
        case MM_SUBTRACT: return prev * (1.f - cov);
        case MM_INTERSECT: return prev * cov;
        case MM_DIFFERENCE: return prev + cov - 2.f * prev * cov;
        default: return prev + cov - prev * cov;
    }
}

float maskInitial(const MaskOp* ops, int n) {
    if (!ops || n <= 0) return 1.f;
    return (ops[0].mode == MM_ADD || ops[0].mode == MM_DIFFERENCE) ? 0.f : 1.f;
}

float maskEvaluate(const MaskOp* ops, int n, float x, float y) {
    float acc = maskInitial(ops, n);
    for (int i = 0; i < n; ++i) acc = maskCombine(ops[i].mode, acc, maskCoverage(ops[i], x, y));
    return acc;
}

float layerMaskCut(float alpha, float coverage, bool exclude) {
    return exclude ? alpha * (1.f - coverage) : alpha * coverage;
}

float layerMaskCutStack(float alpha, const float* coverages, const bool* excludes, int n) {
    float acc = alpha;
    for (int i = 0; i < n; ++i) acc = layerMaskCut(acc, coverages[i], excludes[i]);
    return acc;
}

}  // namespace am
