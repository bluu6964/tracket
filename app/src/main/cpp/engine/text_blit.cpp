#include "text_blit.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace am {
namespace text {

namespace {

inline void overPx(uint8_t* d, float r, float g, float b, float a) {  // premultiplied float 0..255 source
    const float inv = 1.f - a / 255.f;
    d[0] = (uint8_t)std::min(255.f, r + d[0] * inv + 0.5f);
    d[1] = (uint8_t)std::min(255.f, g + d[1] * inv + 0.5f);
    d[2] = (uint8_t)std::min(255.f, b + d[2] * inv + 0.5f);
    d[3] = (uint8_t)std::min(255.f, a + d[3] * inv + 0.5f);
}

bool nearInt(float v) { return std::fabs(v - std::round(v)) < 1e-4f; }

}  // namespace

void blit(uint8_t* dst, int dstW, int dstH, int dstStride, const Image& src, const BlitParams& p) {
    if (!dst || src.w <= 0 || src.h <= 0 || p.alpha <= 0.f) return;
    const Affine& m = p.srcToDst;
    const float al = std::min(1.f, p.alpha);
    const float cl = std::max(0.f, p.clipL), ct = std::max(0.f, p.clipT);
    const float cr = std::min((float)src.w, p.clipR), cb = std::min((float)src.h, p.clipB);
    if (cr <= cl || cb <= ct) return;

    const bool translateOnly = std::fabs(m.a - 1.f) < 1e-6f && std::fabs(m.d - 1.f) < 1e-6f &&
                               std::fabs(m.b) < 1e-6f && std::fabs(m.c) < 1e-6f && nearInt(m.e) && nearInt(m.f) &&
                               nearInt(cl) && nearInt(ct) && nearInt(cr) && nearInt(cb);
    if (translateOnly) {
        const int ox = (int)std::lround(m.e), oy = (int)std::lround(m.f);
        const int x0 = std::max((int)cl, -ox), x1 = std::min((int)cr, dstW - ox);
        const int y0 = std::max((int)ct, -oy), y1 = std::min((int)cb, dstH - oy);
        for (int y = y0; y < y1; ++y) {
            const uint8_t* s = &src.px[((size_t)y * src.w + x0) * 4];
            uint8_t* d = dst + (size_t)(y + oy) * dstStride + (size_t)(x0 + ox) * 4;
            for (int x = x0; x < x1; ++x, s += 4, d += 4) {
                if (s[3] == 0) continue;
                if (al >= 0.9999f && s[3] == 255) { std::memcpy(d, s, 4); continue; }
                overPx(d, s[0] * al, s[1] * al, s[2] * al, s[3] * al);
            }
        }
        return;
    }

    const float det = m.a * m.d - m.b * m.c;
    if (std::fabs(det) < 1e-9f) return;
    const float ia = m.d / det, ib = -m.b / det, ic = -m.c / det, id = m.a / det;
    // bounding box of the transformed source rectangle
    float minX = 1e30f, minY = 1e30f, maxX = -1e30f, maxY = -1e30f;
    const float cx[4] = {cl, cr, cl, cr}, cy[4] = {ct, ct, cb, cb};
    for (int i = 0; i < 4; ++i) {
        const float X = m.a * cx[i] + m.c * cy[i] + m.e, Y = m.b * cx[i] + m.d * cy[i] + m.f;
        minX = std::min(minX, X); maxX = std::max(maxX, X);
        minY = std::min(minY, Y); maxY = std::max(maxY, Y);
    }
    const int x0 = std::max(0, (int)std::floor(minX) - 1), x1 = std::min(dstW, (int)std::ceil(maxX) + 1);
    const int y0 = std::max(0, (int)std::floor(minY) - 1), y1 = std::min(dstH, (int)std::ceil(maxY) + 1);
    for (int y = y0; y < y1; ++y) {
        uint8_t* d = dst + (size_t)y * dstStride + (size_t)x0 * 4;
        for (int x = x0; x < x1; ++x, d += 4) {
            const float px = x + 0.5f - m.e, py = y + 0.5f - m.f;
            const float sx = ia * px + ic * py, sy = ib * px + id * py;  // source coordinates of the pixel centre
            if (sx < cl || sx >= cr || sy < ct || sy >= cb) continue;
            const float fx = sx - 0.5f, fy = sy - 0.5f;
            const int ix = (int)std::floor(fx), iy = (int)std::floor(fy);
            const float tx = fx - ix, ty = fy - iy;
            float acc[4] = {0, 0, 0, 0};
            for (int j = 0; j < 2; ++j) {
                for (int i = 0; i < 2; ++i) {
                    int qx = ix + i, qy = iy + j;
                    if (p.clampEdges) {
                        qx = std::min(src.w - 1, std::max(0, qx));
                        qy = std::min(src.h - 1, std::max(0, qy));
                    } else if (qx < 0 || qy < 0 || qx >= src.w || qy >= src.h) continue;
                    const float w = (i ? tx : 1.f - tx) * (j ? ty : 1.f - ty);
                    const uint8_t* s = &src.px[((size_t)qy * src.w + qx) * 4];
                    for (int k = 0; k < 4; ++k) acc[k] += s[k] * w;
                }
            }
            if (acc[3] <= 0.f) continue;
            overPx(d, acc[0] * al, acc[1] * al, acc[2] * al, acc[3] * al);
        }
    }
}

namespace {
// One box blur pass along rows (horizontal) with half-width r; outside = 0.
void boxH(const uint8_t* in, uint8_t* out, int w, int h, int r) {
    const float inv = 1.f / (2 * r + 1);
    for (int y = 0; y < h; ++y) {
        const uint8_t* s = in + (size_t)y * w;
        uint8_t* o = out + (size_t)y * w;
        int sum = 0;
        for (int x = -r; x <= r; ++x) if (x >= 0 && x < w) sum += s[x];
        for (int x = 0; x < w; ++x) {
            o[x] = (uint8_t)std::min(255.f, sum * inv + 0.5f);
            const int add = x + r + 1, sub = x - r;
            if (add < w) sum += s[add];
            if (sub >= 0) sum -= s[sub];
        }
    }
}
void boxV(const uint8_t* in, uint8_t* out, int w, int h, int r) {
    const float inv = 1.f / (2 * r + 1);
    for (int x = 0; x < w; ++x) {
        int sum = 0;
        for (int y = -r; y <= r; ++y) if (y >= 0 && y < h) sum += in[(size_t)y * w + x];
        for (int y = 0; y < h; ++y) {
            out[(size_t)y * w + x] = (uint8_t)std::min(255.f, sum * inv + 0.5f);
            const int add = y + r + 1, sub = y - r;
            if (add < h) sum += in[(size_t)add * w + x];
            if (sub >= 0) sum -= in[(size_t)sub * w + x];
        }
    }
}
}  // namespace

void blurAlpha(std::vector<uint8_t>& a, int w, int h, float sigma) {
    if (sigma < 0.3f || w <= 0 || h <= 0 || a.size() < (size_t)w * h) return;
    const int n = 3;
    const float wIdeal = std::sqrt(12.f * sigma * sigma / n + 1.f);
    int wl = (int)std::floor(wIdeal);
    if (wl % 2 == 0) --wl;
    if (wl < 1) wl = 1;
    const int wu = wl + 2;
    const float mIdeal = (12.f * sigma * sigma - n * wl * wl - 4.f * n * wl - 3.f * n) / (-4.f * wl - 4.f);
    const int m = (int)std::lround(mIdeal);
    std::vector<uint8_t> tmp(a.size());
    for (int i = 0; i < n; ++i) {
        const int size = i < m ? wl : wu;
        const int r = (size - 1) / 2;
        if (r <= 0) continue;
        boxH(a.data(), tmp.data(), w, h, r);
        boxV(tmp.data(), a.data(), w, h, r);
    }
}

}  // namespace text
}  // namespace am
