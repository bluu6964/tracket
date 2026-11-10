#include "planar_track.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "homography.h"

namespace am {
namespace {

constexpr int kWin = 15;          // tracking window (odd)
constexpr int kMaxIter = 20;
constexpr float kEps = 0.03f;
constexpr float kMinEig = 4.f;    // weakest accepted corner (smallest eigenvalue per window pixel)
constexpr float kMinNcc = 0.5f;   // least patch similarity for a point to count as followed
constexpr float kFbTol = 1.5f;    // forward-backward error allowed, picture pixels
constexpr int kReseedEvery = 2;
constexpr int kMinStart = 8;
constexpr int kRansacIters = 160;

float sampleBilinear(const std::vector<float>& a, int w, int h, float x0, float y0) {
    const float x = x0 < 0.f ? 0.f : (x0 > w - 1.001f ? w - 1.001f : x0);
    const float y = y0 < 0.f ? 0.f : (y0 > h - 1.001f ? h - 1.001f : y0);
    const int xi = (int)x, yi = (int)y;
    const float fx = x - xi, fy = y - yi;
    const int i = yi * w + xi;
    return a[i] * (1.f - fx) * (1.f - fy) + a[i + 1] * fx * (1.f - fy) + a[i + w] * (1.f - fx) * fy +
           a[i + w + 1] * fx * fy;
}

uint32_t lcg(uint32_t& s) {
    s = s * 1664525u + 1013904223u;
    return s >> 8;
}


// Least-squares similarity (move + turn + scale) taking src[idx] to dst[idx], as a 3x3 matrix (same layout as a homography).
bool fitSimilarityLS(const float* src, const float* dst, const int* idx, int n, double* H) {
    if (n < 2) return false;
    double sx = 0, sy = 0, dx = 0, dy = 0;
    for (int k = 0; k < n; ++k) {
        sx += src[2 * idx[k]]; sy += src[2 * idx[k] + 1];
        dx += dst[2 * idx[k]]; dy += dst[2 * idx[k] + 1];
    }
    sx /= n; sy /= n; dx /= n; dy /= n;
    double dot = 0, crs = 0, nn = 0;
    for (int k = 0; k < n; ++k) {
        const double ax = src[2 * idx[k]] - sx, ay = src[2 * idx[k] + 1] - sy;
        const double bx = dst[2 * idx[k]] - dx, by = dst[2 * idx[k] + 1] - dy;
        dot += ax * bx + ay * by;
        crs += ax * by - ay * bx;
        nn += ax * ax + ay * ay;
    }
    if (nn < 1e-9) return false;
    const double a = dot / nn, b = crs / nn;
    const double sc = std::sqrt(a * a + b * b);
    if (sc < 0.5 || sc > 2.0) return false;
    H[0] = a;  H[1] = -b; H[2] = dx - (a * sx - b * sy);
    H[3] = b;  H[4] = a;  H[5] = dy - (b * sx + a * sy);
    H[6] = 0;  H[7] = 0;  H[8] = 1;
    return true;
}

// Shrinks / grows a quad about its centre.
void scaleQuad(const float* q, float k, float* out) {
    float cx = 0, cy = 0;
    for (int i = 0; i < 4; ++i) {
        cx += q[2 * i];
        cy += q[2 * i + 1];
    }
    cx *= 0.25f;
    cy *= 0.25f;
    for (int i = 0; i < 4; ++i) {
        out[2 * i] = cx + (q[2 * i] - cx) * k;
        out[2 * i + 1] = cy + (q[2 * i + 1] - cy) * k;
    }
}

bool pointInConvex(const float* q, float x, float y) {
    int sign = 0;
    for (int i = 0; i < 4; ++i) {
        const int j = (i + 1) & 3;
        const float cr = (q[2 * j] - q[2 * i]) * (y - q[2 * i + 1]) - (q[2 * j + 1] - q[2 * i + 1]) * (x - q[2 * i]);
        const int s = cr > 0.f ? 1 : (cr < 0.f ? -1 : 0);
        if (s == 0) continue;
        if (sign == 0) sign = s;
        else if (s != sign) return false;
    }
    return true;
}

}  // namespace

PlanarTracker::Pyramid PlanarTracker::buildPyramid(const float* gray, int w, int h) {
    Pyramid out;
    std::vector<float> data(gray, gray + (size_t)w * h);
    int cw = w, ch = h;
    while (true) {
        Level l;
        l.w = cw;
        l.h = ch;
        l.img = data;
        l.ix.assign((size_t)cw * ch, 0.f);
        l.iy.assign((size_t)cw * ch, 0.f);
        for (int y = 1; y < ch - 1; ++y) {
            const int row = y * cw;
            for (int x = 1; x < cw - 1; ++x) {
                l.ix[row + x] = (l.img[row + x + 1] - l.img[row + x - 1]) * 0.5f;
                l.iy[row + x] = (l.img[row + cw + x] - l.img[row - cw + x]) * 0.5f;
            }
        }
        out.push_back(std::move(l));
        if (out.size() >= 4 || std::min(cw, ch) < 64) break;
        const int w2 = cw / 2, h2 = ch / 2;
        std::vector<float> d2((size_t)w2 * h2);
        const std::vector<float>& src = out.back().img;
        for (int y = 0; y < h2; ++y) {
            const int r0 = 2 * y * cw, r1 = r0 + cw;
            for (int x = 0; x < w2; ++x) {
                const int i = 2 * x;
                d2[(size_t)y * w2 + x] = (src[r0 + i] + src[r0 + i + 1] + src[r1 + i] + src[r1 + i + 1]) * 0.25f;
            }
        }
        cw = w2;
        ch = h2;
        data = std::move(d2);
    }
    return out;
}

bool PlanarTracker::inQuad(float x, float y, float scale) const {
    if (global_) {   // the whole picture counts, wherever the carried quad is
        const float m = (float)(kWin / 2 + 2);
        return x >= m && y >= m && x <= (float)(w_ - 1) - m && y <= (float)(h_ - 1) - m;
    }
    float q[8];
    scaleQuad(quad_, scale, q);
    return pointInConvex(q, x, y);
}

int PlanarTracker::copyPoints(float* out, int maxPoints) const {
    const int m = std::min(n_, maxPoints);
    std::memcpy(out, pts_, sizeof(float) * 2 * m);
    return m;
}

// Where the point (x, y) of picture [a] is in picture [b]. False: no texture / left the picture / did not settle / changed look.
bool PlanarTracker::trackPoint(const Pyramid& a, const Pyramid& b, float x, float y, float* out) {
    float gx = 0.f, gy = 0.f;
    const int half = kWin / 2;
    for (int lv = (int)a.size() - 1; lv >= 0; --lv) {
        const float scale = 1.f / (float)(1 << lv);
        const float px = x * scale, py = y * scale;
        const Level& la = a[lv];
        const Level& lb = b[lv];
        float gxx = 0, gxy = 0, gyy = 0;
        int k = 0;
        for (int dy = -half; dy <= half; ++dy) {
            for (int dx = -half; dx <= half; ++dx) {
                const float t = sampleBilinear(la.img, la.w, la.h, px + dx, py + dy);
                const float ax = sampleBilinear(la.ix, la.w, la.h, px + dx, py + dy);
                const float ay = sampleBilinear(la.iy, la.w, la.h, px + dx, py + dy);
                tpl_[k] = t;
                tgx_[k] = ax;
                tgy_[k] = ay;
                gxx += ax * ax;
                gxy += ax * ay;
                gyy += ay * ay;
                ++k;
            }
        }
        const float det = gxx * gyy - gxy * gxy;
        if (lv == 0) {
            const float tr = gxx + gyy;
            const float disc = std::sqrt(std::max(0.f, tr * tr * 0.25f - det));
            const float minEig = (tr * 0.5f - disc) / (float)(kWin * kWin);
            if (minEig < kMinEig * 0.25f) return false;
        }
        if (det < 1e-6f) {
            if (lv > 0) {
                gx *= 2.f;
                gy *= 2.f;
            }
            continue;
        }
        float vx = 0.f, vy = 0.f;
        for (int iter = 0; iter < kMaxIter; ++iter) {
            const float qx = px + gx + vx, qy = py + gy + vy;
            if (qx < 0.f || qy < 0.f || qx > lb.w - 1.f || qy > lb.h - 1.f) return false;
            float bx = 0.f, by = 0.f;
            k = 0;
            for (int dy = -half; dy <= half; ++dy) {
                for (int dx = -half; dx <= half; ++dx) {
                    const float d = tpl_[k] - sampleBilinear(lb.img, lb.w, lb.h, qx + dx, qy + dy);
                    bx += d * tgx_[k];
                    by += d * tgy_[k];
                    ++k;
                }
            }
            const float ddx = (gyy * bx - gxy * by) / det;
            const float ddy = (-gxy * bx + gxx * by) / det;
            vx += ddx;
            vy += ddy;
            if (ddx * ddx + ddy * ddy < kEps * kEps) break;
        }
        gx += vx;
        gy += vy;
        if (lv > 0) {
            gx *= 2.f;
            gy *= 2.f;
        }
    }
    const float rx = x + gx, ry = y + gy;
    if (rx < 0.f || ry < 0.f || rx > a[0].w - 1.f || ry > a[0].h - 1.f) return false;

    // appearance check (normalised cross-correlation): a covered or changed spot is not followed
    const Level& l0 = b[0];
    float sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
    int k = 0;
    for (int dy = -half; dy <= half; ++dy) {
        for (int dx = -half; dx <= half; ++dx) {
            const float t = tpl_[k];
            const float q = sampleBilinear(l0.img, l0.w, l0.h, rx + dx, ry + dy);
            sa += t;
            sb += q;
            saa += t * t;
            sbb += q * q;
            sab += t * q;
            ++k;
        }
    }
    const float cnt = (float)(kWin * kWin);
    const float va = saa - sa * sa / cnt;
    const float vb = sbb - sb * sb / cnt;
    if (va <= 1e-3f || vb <= 1e-3f) return false;
    const float ncc = (sab - sa * sb / cnt) / std::sqrt(va * vb);
    if (ncc < kMinNcc) return false;
    out[0] = rx;
    out[1] = ry;
    return true;
}

// Adds Shi-Tomasi corners inside the (slightly shrunk) surface, keeping the ones already followed.
void PlanarTracker::detect(const Level& l) {
    float sq[8];
    if (global_) {
        const float fw = (float)(l.w - 1), fh = (float)(l.h - 1), m = 0.02f;
        const float r[8] = {fw * m, fh * m, fw * (1 - m), fh * m, fw * (1 - m), fh * (1 - m), fw * m, fh * (1 - m)};
        std::memcpy(sq, r, sizeof(sq));
    } else {
        scaleQuad(quad_, 0.92f, sq);
    }
    const int margin = kWin / 2 + 2;
    float minX = 1e30f, minY = 1e30f, maxX = -1e30f, maxY = -1e30f;
    for (int i = 0; i < 4; ++i) {
        minX = std::min(minX, sq[2 * i]);
        maxX = std::max(maxX, sq[2 * i]);
        minY = std::min(minY, sq[2 * i + 1]);
        maxY = std::max(maxY, sq[2 * i + 1]);
    }
    const int x0 = std::max(margin, (int)minX), y0 = std::max(margin, (int)minY);
    const int x1 = std::min(l.w - 1 - margin, (int)maxX), y1 = std::min(l.h - 1 - margin, (int)maxY);
    if (x1 <= x0 || y1 <= y0) return;
    const int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
    const int win = 2;
    std::vector<float> eig((size_t)bw * bh, 0.f);
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            if (!pointInConvex(sq, (float)x, (float)y)) continue;
            float gxx = 0, gxy = 0, gyy = 0;
            for (int yy = y - win; yy <= y + win; ++yy) {
                const int row = yy * l.w;
                for (int xx = x - win; xx <= x + win; ++xx) {
                    const float gx = l.ix[row + xx], gy = l.iy[row + xx];
                    gxx += gx * gx;
                    gxy += gx * gy;
                    gyy += gy * gy;
                }
            }
            const float tr = gxx + gyy;
            const float det = gxx * gyy - gxy * gxy;
            const float disc = std::sqrt(std::max(0.f, tr * tr * 0.25f - det));
            eig[(size_t)(y - y0) * bw + (x - x0)] = (tr * 0.5f - disc) / (float)((2 * win + 1) * (2 * win + 1));
        }
    }
    const float area = (float)quadArea(sq);
    const float cell = std::max(5.f, std::sqrt(std::max(area, 1.f)) / (global_ ? 19.f : 10.f));
    const int cols = (int)(bw / cell) + 1, rows = (int)(bh / cell) + 1;
    std::vector<float> bestEig((size_t)cols * rows, 0.f);
    std::vector<int> bestX((size_t)cols * rows, 0), bestY((size_t)cols * rows, 0);
    float maxEig = 0.f;
    for (int y = 0; y < bh; ++y) {
        for (int x = 0; x < bw; ++x) {
            const float e = eig[(size_t)y * bw + x];
            if (e <= 0.f) continue;
            const int ci = (int)(y / cell) * cols + (int)(x / cell);
            if (e > bestEig[ci]) {
                bestEig[ci] = e;
                bestX[ci] = x + x0;
                bestY[ci] = y + y0;
            }
            maxEig = std::max(maxEig, e);
        }
    }
    if (maxEig < kMinEig) return;
    const float floorE = std::max(kMinEig, maxEig * 0.05f);
    std::vector<int> order;
    for (int i = 0; i < cols * rows; ++i)
        if (bestEig[i] >= floorE) order.push_back(i);
    std::sort(order.begin(), order.end(), [&](int a, int b) { return bestEig[a] > bestEig[b]; });
    const float minD2 = cell * cell * 0.5f;
    for (int ci : order) {
        if (n_ >= maxPts()) break;
        const float px = (float)bestX[ci], py = (float)bestY[ci];
        bool close = false;
        for (int j = 0; j < n_; ++j) {
            const float ddx = pts_[2 * j] - px, ddy = pts_[2 * j + 1] - py;
            if (ddx * ddx + ddy * ddy < minD2) {
                close = true;
                break;
            }
        }
        if (close) continue;
        pts_[2 * n_] = px;
        pts_[2 * n_ + 1] = py;
        ++n_;
    }
}

bool PlanarTracker::start(const float* gray, int w, int h, const float* quad8) {
    if (!gray || w < 32 || h < 32) return false;
    std::memcpy(quad_, quad8, sizeof(quad_));
    if (!quadConvex(quad_, 6.f)) return false;
    w_ = w;
    h_ = h;
    n_ = 0;
    sinceReseed_ = 0;
    misses_ = 0;
    inliers_ = 0;
    seed_ = 12345u;
    prev_ = buildPyramid(gray, w, h);
    detect(prev_[0]);
    startCount_ = n_;
    return n_ >= kMinStart;
}

bool PlanarTracker::step(const float* gray, int w, int h) {
    if (prev_.empty() || w != w_ || h != h_) return false;
    Pyramid b = buildPyramid(gray, w, h);
    const int minPts = global_ ? 15 : minPts;

    // 1) every corner forward, then back (forward-backward check)
    std::vector<float> dst((size_t)n_ * 2, 0.f);
    std::vector<char> ok((size_t)n_, 0);
    float fwd[2], res[2];
    const float maxJump = 0.25f * (float)std::max(w, h);
    int good = 0;
    for (int i = 0; i < n_; ++i) {
        const float x = pts_[2 * i], y = pts_[2 * i + 1];
        if (!trackPoint(prev_, b, x, y, fwd)) continue;
        if (!trackPoint(b, prev_, fwd[0], fwd[1], res)) continue;
        const float ex = res[0] - x, ey = res[1] - y;
        if (ex * ex + ey * ey > kFbTol * kFbTol) continue;
        if (std::fabs(fwd[0] - x) > maxJump || std::fabs(fwd[1] - y) > maxJump) continue;
        dst[2 * i] = fwd[0];
        dst[2 * i + 1] = fwd[1];
        ok[i] = 1;
        ++good;
    }
    if (good < minPts) return miss();

    // 2) RANSAC homography (deterministic seed), then refit on all inliers
    std::vector<int> valid;
    valid.reserve(good);
    for (int i = 0; i < n_; ++i)
        if (ok[i]) valid.push_back(i);
    const double thr = global_ ? std::max(1.2, 0.003 * std::max(w, h)) : std::max(1.6, 0.004 * std::max(w, h));
    const double thr2 = thr * thr;
    auto score = [&](const double* H, std::vector<int>* inl) {
        int c = 0;
        if (inl) inl->clear();
        for (int i : valid) {
            double X, Y, W;
            hApply(H, pts_[2 * i], pts_[2 * i + 1], &X, &Y, &W);
            if (W <= 0.0) continue;
            const double ex = X - dst[2 * i], ey = Y - dst[2 * i + 1];
            if (ex * ex + ey * ey <= thr2) {
                ++c;
                if (inl) inl->push_back(i);
            }
        }
        return c;
    };
    double bestH[9] = {0};
    int bestCount = 0;
    const int nv = (int)valid.size();
    for (int it = 0; it < kRansacIters; ++it) {
        double H[9];
        if (global_) {
            int s2[2] = {valid[lcg(seed_) % nv], valid[lcg(seed_) % nv]};
            if (s2[0] == s2[1]) continue;
            const float ddx = pts_[2 * s2[0]] - pts_[2 * s2[1]], ddy = pts_[2 * s2[0] + 1] - pts_[2 * s2[1] + 1];
            if (ddx * ddx + ddy * ddy < 0.04f * (float)std::min(w, h) * (float)std::min(w, h)) continue;   // too close: a noisy turn
            if (!fitSimilarityLS(pts_, dst.data(), s2, 2, H)) continue;
        } else {
            int s[4];
            bool dup = false;
            for (int k = 0; k < 4; ++k) {
                s[k] = valid[lcg(seed_) % nv];
                for (int j = 0; j < k; ++j)
                    if (s[j] == s[k]) dup = true;
            }
            if (dup) continue;
            // reject nearly collinear / tiny samples (source and target)
            bool bad = false;
            for (int a = 0; a < 4 && !bad; ++a) {
                for (int bb = a + 1; bb < 4 && !bad; ++bb) {
                    for (int c = bb + 1; c < 4 && !bad; ++c) {
                        const float* P = pts_;
                        const float* Q = dst.data();
                        auto cross = [](const float* A, int i, int j, int k) {
                            return std::fabs((A[2 * j] - A[2 * i]) * (A[2 * k + 1] - A[2 * i + 1]) -
                                             (A[2 * j + 1] - A[2 * i + 1]) * (A[2 * k] - A[2 * i]));
                        };
                        if (cross(P, s[a], s[bb], s[c]) < 30.f || cross(Q, s[a], s[bb], s[c]) < 30.f) bad = true;
                    }
                }
            }
            if (bad) continue;
            if (!hFit(pts_, dst.data(), s, 4, H)) continue;
        }
        const int c = score(H, nullptr);
        if (c > bestCount) {
            bestCount = c;
            std::memcpy(bestH, H, sizeof(H));
            if (c >= (int)(0.95 * nv)) break;
        }
    }
    if (bestCount < minPts) return miss();
    std::vector<int> inl;
    score(bestH, &inl);
    for (int round = 0; round < 3; ++round) {
        if ((int)inl.size() < 4) break;
        double H[9];
        if (!(global_ ? fitSimilarityLS(pts_, dst.data(), inl.data(), (int)inl.size(), H) : hFit(pts_, dst.data(), inl.data(), (int)inl.size(), H))) break;
        std::vector<int> next;
        const int c = score(H, &next);
        if (c < (int)inl.size() * 0.8 || c < minPts) break;  // the refit lost the surface: keep the earlier one
        std::memcpy(bestH, H, sizeof(H));
        inl = next;
    }
    if ((int)inl.size() < minPts) return miss();

    // 3) move the corners and check the result is still a believable surface
    float nq[8];
    for (int i = 0; i < 4; ++i) {
        double X, Y;
        hApply(bestH, quad_[2 * i], quad_[2 * i + 1], &X, &Y);
        nq[2 * i] = (float)X;
        nq[2 * i + 1] = (float)Y;
    }
    const double a0 = quadArea(quad_), a1 = quadArea(nq);
    bool valid4 = quadConvex(nq, 4.f) && a0 > 1.0 && a1 / a0 > 0.6 && a1 / a0 < 1.6;
    for (int i = 0; i < 8 && valid4; ++i) {
        const float lim = (i % 2 == 0) ? (float)w : (float)h;
        const float lo = global_ ? -6.f : -0.5f, hi = global_ ? 7.f : 1.5f;   // the carried frame may drift far in global mode
        if (!std::isfinite(nq[i]) || nq[i] < lo * lim || nq[i] > hi * lim) valid4 = false;
    }
    if (!valid4) return miss();
    misses_ = 0;
    std::memcpy(quad_, nq, sizeof(quad_));
    inliers_ = (int)inl.size();

    // 4) keep the inlier corners that are still on the surface
    int m = 0;
    float newPts[kMaxPoints * 2];
    for (int i : inl) {
        const float x = dst[2 * i], y = dst[2 * i + 1];
        if (!inQuad(x, y, 1.08f)) continue;
        newPts[2 * m] = x;
        newPts[2 * m + 1] = y;
        ++m;
    }
    std::memcpy(pts_, newPts, sizeof(float) * 2 * m);
    n_ = m;
    prev_ = std::move(b);

    // 5) too few corners left: look for new ones (not every frame)
    ++sinceReseed_;
    const int reseedBelow = std::max(minPts + 6, (int)(startCount_ * 0.85f));
    if (n_ < reseedBelow && sinceReseed_ >= kReseedEvery) {
        sinceReseed_ = 0;
        detect(prev_[0]);
    }
    return n_ >= minPts;
}

}  // namespace am
