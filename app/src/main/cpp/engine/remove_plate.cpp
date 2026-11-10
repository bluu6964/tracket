#include "remove_plate.h"

#include <algorithm>
#include <cmath>

#include "homography.h"

namespace am {
namespace {

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float smooth(float a, float b, float x) {
    if (b <= a) return x >= b ? 1.f : 0.f;
    float t = (x - a) / (b - a);
    t = t < 0.f ? 0.f : (t > 1.f ? 1.f : t);
    return t * t * (3.f - 2.f * t);
}

// Grey picture sampled at (x, y) in pixels (pixel centres at +0.5), edges repeat.
float sampleGray(const float* g, int w, int h, float x, float y) {
    x -= 0.5f;
    y -= 0.5f;
    const int x0 = (int)std::floor(x), y0 = (int)std::floor(y);
    const float fx = x - (float)x0, fy = y - (float)y0;
    auto at = [&](int xx, int yy) { return g[(size_t)clampi(yy, 0, h - 1) * w + clampi(xx, 0, w - 1)]; };
    const float a = at(x0, y0), b = at(x0 + 1, y0), c = at(x0, y0 + 1), d = at(x0 + 1, y0 + 1);
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
}

// The painted area: 0 outside the picture.
float sampleMask(const uint8_t* m, int w, int h, float x, float y) {
    if (x < 0.f || y < 0.f || x > (float)w || y > (float)h) return 0.f;
    x -= 0.5f;
    y -= 0.5f;
    const int x0 = (int)std::floor(x), y0 = (int)std::floor(y);
    const float fx = x - (float)x0, fy = y - (float)y0;
    auto at = [&](int xx, int yy) { return (float)m[(size_t)clampi(yy, 0, h - 1) * w + clampi(xx, 0, w - 1)]; };
    const float a = at(x0, y0), b = at(x0 + 1, y0), c = at(x0, y0 + 1), d = at(x0 + 1, y0 + 1);
    return ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy) / 255.f;
}

void sampleRgb(const uint32_t* px, int w, int h, float x, float y, float* out) {
    x -= 0.5f;
    y -= 0.5f;
    const int x0 = (int)std::floor(x), y0 = (int)std::floor(y);
    const float fx = x - (float)x0, fy = y - (float)y0;
    const float wt[4] = {(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy};
    const int xs[4] = {x0, x0 + 1, x0, x0 + 1}, ys[4] = {y0, y0, y0 + 1, y0 + 1};
    out[0] = out[1] = out[2] = 0.f;
    for (int k = 0; k < 4; ++k) {
        const uint32_t p = px[(size_t)clampi(ys[k], 0, h - 1) * w + clampi(xs[k], 0, w - 1)];
        out[0] += wt[k] * (float)((p >> 16) & 255u);
        out[1] += wt[k] * (float)((p >> 8) & 255u);
        out[2] += wt[k] * (float)(p & 255u);
    }
}

// Square window dilation of a 0 / 1 picture.
std::vector<uint8_t> dilate(const std::vector<uint8_t>& src, int W, int H, int r) {
    if (r <= 0) return src;
    std::vector<uint8_t> tmp(src.size(), 0), out(src.size(), 0);
    std::vector<int> pre((size_t)std::max(W, H) + 1);
    for (int y = 0; y < H; ++y) {
        pre[0] = 0;
        for (int x = 0; x < W; ++x) pre[x + 1] = pre[x] + (src[(size_t)y * W + x] ? 1 : 0);
        for (int x = 0; x < W; ++x) {
            const int lo = std::max(0, x - r), hi = std::min(W - 1, x + r);
            tmp[(size_t)y * W + x] = pre[hi + 1] - pre[lo] > 0 ? 1 : 0;
        }
    }
    for (int x = 0; x < W; ++x) {
        pre[0] = 0;
        for (int y = 0; y < H; ++y) pre[y + 1] = pre[y] + (tmp[(size_t)y * W + x] ? 1 : 0);
        for (int y = 0; y < H; ++y) {
            const int lo = std::max(0, y - r), hi = std::min(H - 1, y + r);
            out[(size_t)y * W + x] = pre[hi + 1] - pre[lo] > 0 ? 1 : 0;
        }
    }
    return out;
}

// Box blur of one float channel, radius r, zero outside the picture.
void boxBlur(std::vector<float>& a, int W, int H, int r) {
    if (r <= 0) return;
    std::vector<float> tmp(a.size(), 0.f);
    std::vector<double> pre((size_t)std::max(W, H) + 1);
    const float inv = 1.f / (float)(2 * r + 1);
    for (int y = 0; y < H; ++y) {
        pre[0] = 0;
        for (int x = 0; x < W; ++x) pre[x + 1] = pre[x] + a[(size_t)y * W + x];
        for (int x = 0; x < W; ++x) {
            const int lo = std::max(0, x - r), hi = std::min(W - 1, x + r);
            tmp[(size_t)y * W + x] = (float)(pre[hi + 1] - pre[lo]) * inv;
        }
    }
    for (int x = 0; x < W; ++x) {
        pre[0] = 0;
        for (int y = 0; y < H; ++y) pre[y + 1] = pre[y] + tmp[(size_t)y * W + x];
        for (int y = 0; y < H; ++y) {
            const int lo = std::max(0, y - r), hi = std::min(H - 1, y + r);
            a[(size_t)y * W + x] = (float)(pre[hi + 1] - pre[lo]) * inv;
        }
    }
}

// Fills the unknown pixels (unk != 0) of an RGB float picture from the known ones: push-pull pyramid, then relaxation (the
// smooth "harmonic" fill: the colours around flow into the hole).
void inpaint(std::vector<float>& img, const std::vector<uint8_t>& unk, int W, int H) {
    struct Lvl {
        int w, h;
        std::vector<float> c;   // 3 per pixel
        std::vector<float> wt;  // confidence 0..1
    };
    std::vector<Lvl> L;
    L.push_back({W, H, img, std::vector<float>((size_t)W * H)});
    for (size_t i = 0; i < (size_t)W * H; ++i) L[0].wt[i] = unk[i] ? 0.f : 1.f;
    while (L.back().w > 1 || L.back().h > 1) {
        const Lvl& p = L.back();
        Lvl n;
        n.w = (p.w + 1) / 2;
        n.h = (p.h + 1) / 2;
        n.c.assign((size_t)n.w * n.h * 3, 0.f);
        n.wt.assign((size_t)n.w * n.h, 0.f);
        for (int y = 0; y < n.h; ++y)
            for (int x = 0; x < n.w; ++x) {
                float sw = 0.f, sc[3] = {0, 0, 0};
                int cnt = 0;
                for (int dy = 0; dy < 2; ++dy)
                    for (int dx = 0; dx < 2; ++dx) {
                        const int xx = 2 * x + dx, yy = 2 * y + dy;
                        if (xx >= p.w || yy >= p.h) continue;
                        ++cnt;
                        const size_t k = (size_t)yy * p.w + xx;
                        const float w = p.wt[k];
                        sw += w;
                        for (int c = 0; c < 3; ++c) sc[c] += w * p.c[k * 3 + c];
                    }
                const size_t o = (size_t)y * n.w + x;
                if (sw > 1e-6f) {
                    for (int c = 0; c < 3; ++c) n.c[o * 3 + c] = sc[c] / sw;
                    n.wt[o] = std::min(1.f, sw / (float)cnt);
                }
            }
        L.push_back(std::move(n));
    }
    {   // nothing known at all: grey
        Lvl& top = L.back();
        if (top.wt[0] <= 1e-6f) {
            for (int c = 0; c < 3; ++c) top.c[c] = 128.f;
        }
        top.wt[0] = 1.f;
    }
    for (int i = (int)L.size() - 2; i >= 0; --i) {
        Lvl& f = L[(size_t)i];
        const Lvl& c = L[(size_t)i + 1];
        for (int y = 0; y < f.h; ++y)
            for (int x = 0; x < f.w; ++x) {
                const size_t k = (size_t)y * f.w + x;
                const float w = f.wt[k];
                if (w >= 1.f) continue;
                const float px = ((float)x + 0.5f) * 0.5f - 0.5f, py = ((float)y + 0.5f) * 0.5f - 0.5f;
                const int x0 = (int)std::floor(px), y0 = (int)std::floor(py);
                const float fx = px - (float)x0, fy = py - (float)y0;
                float up[3] = {0, 0, 0};
                const float bw[4] = {(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy};
                const int bx[4] = {x0, x0 + 1, x0, x0 + 1}, by[4] = {y0, y0, y0 + 1, y0 + 1};
                for (int j = 0; j < 4; ++j) {
                    const size_t q = (size_t)clampi(by[j], 0, c.h - 1) * c.w + clampi(bx[j], 0, c.w - 1);
                    for (int ch = 0; ch < 3; ++ch) up[ch] += bw[j] * c.c[q * 3 + ch];
                }
                for (int ch = 0; ch < 3; ++ch) f.c[k * 3 + ch] = w * f.c[k * 3 + ch] + (1.f - w) * up[ch];
                f.wt[k] = 1.f;
            }
    }
    img = L[0].c;
    // relaxation on the hole only (Gauss-Seidel): every unknown pixel becomes the mean of its 4 neighbours
    for (int it = 0; it < 28; ++it) {
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                const size_t k = (size_t)y * W + x;
                if (!unk[k]) continue;
                const size_t l = (size_t)y * W + clampi(x - 1, 0, W - 1), r = (size_t)y * W + clampi(x + 1, 0, W - 1);
                const size_t u = (size_t)clampi(y - 1, 0, H - 1) * W + x, d = (size_t)clampi(y + 1, 0, H - 1) * W + x;
                for (int ch = 0; ch < 3; ++ch)
                    img[k * 3 + ch] = 0.25f * (img[l * 3 + ch] + img[r * 3 + ch] + img[u * 3 + ch] + img[d * 3 + ch]);
            }
    }
}

inline float lumaOf(const float* p) { return 0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]; }

struct Rng {
    uint32_t s = 2463534242u;
    float next() {  // 0..1
        s ^= s << 13;
        s ^= s >> 17;
        s ^= s << 5;
        return (float)(s & 0xFFFFFFu) / 16777216.f;
    }
    float gauss() { return (next() + next() + next() + next() - 2.f) * 1.7320508f; }  // ~N(0, 1)
};

}  // namespace

bool RemovePlate::setMask(const float* quadUv, const uint8_t* mask, int mw, int mh, int picW, int picH, int maxSide) {
    plates_.clear();
    ringPts_.clear();
    mask_.clear();
    w_ = h_ = 0;
    if (!quadUv || !mask || mw < 2 || mh < 2 || picW < 2 || picH < 2 || maxSide < 16) return false;
    float qp[8], qm[8];
    for (int i = 0; i < 4; ++i) {
        qp[2 * i] = quadUv[2 * i] * (float)picW;
        qp[2 * i + 1] = quadUv[2 * i + 1] * (float)picH;
        qm[2 * i] = quadUv[2 * i] * (float)mw;
        qm[2 * i + 1] = quadUv[2 * i + 1] * (float)mh;
    }
    if (!quadConvex(qp, 4.f)) return false;
    double Hm[9];
    if (!squareToQuad(qm, Hm)) return false;

    // where the painted area lies on the surface (coarse scan of the surface)
    const int G = 96;
    int gx0 = G, gy0 = G, gx1 = -1, gy1 = -1, cnt = 0;
    for (int gy = 0; gy < G; ++gy)
        for (int gx = 0; gx < G; ++gx) {
            double x, y;
            hApply(Hm, ((double)gx + 0.5) / G, ((double)gy + 0.5) / G, &x, &y);
            if (sampleMask(mask, mw, mh, (float)x, (float)y) < 0.5f) continue;
            ++cnt;
            gx0 = std::min(gx0, gx);
            gx1 = std::max(gx1, gx);
            gy0 = std::min(gy0, gy);
            gy1 = std::max(gy1, gy);
        }
    if (cnt < 1) return false;
    const float s0 = (float)gx0 / G, s1 = (float)(gx1 + 1) / G, t0 = (float)gy0 / G, t1 = (float)(gy1 + 1) / G;
    const float ms = std::max(0.4f * (s1 - s0), 3.f / G), mt = std::max(0.4f * (t1 - t0), 3.f / G);
    dom_[0] = std::max(0.f, s0 - ms);
    dom_[1] = std::max(0.f, t0 - mt);
    dom_[2] = std::min(1.f, s1 + ms);
    dom_[3] = std::min(1.f, t1 + mt);
    const float ds = dom_[2] - dom_[0], dt = dom_[3] - dom_[1];
    if (ds < 0.01f || dt < 0.01f) return false;

    // plate size: 1 plate pixel = about 1 picture pixel of the surface
    const float wq = 0.5f * (std::hypot(qp[2] - qp[0], qp[3] - qp[1]) + std::hypot(qp[4] - qp[6], qp[5] - qp[7]));
    const float hq = 0.5f * (std::hypot(qp[6] - qp[0], qp[7] - qp[1]) + std::hypot(qp[4] - qp[2], qp[5] - qp[3]));
    const float pw = wq * ds, ph = hq * dt;
    float sc = std::min(1.f, (float)maxSide / std::max(pw, ph));
    w_ = clampi((int)std::lround(pw * sc), 8, maxSide);
    h_ = clampi((int)std::lround(ph * sc), 8, maxSide);
    while ((long)w_ * h_ > 700000L) {
        w_ = w_ * 9 / 10;
        h_ = h_ * 9 / 10;
    }
    w_ = std::max(w_, 8);
    h_ = std::max(h_, 8);

    mask_.assign((size_t)w_ * h_, 0.f);
    int inside = 0;
    for (int y = 0; y < h_; ++y)
        for (int x = 0; x < w_; ++x) {
            const double s = dom_[0] + ((double)x + 0.5) / w_ * ds, t = dom_[1] + ((double)y + 0.5) / h_ * dt;
            double mx, my;
            hApply(Hm, s, t, &mx, &my);
            const float v = sampleMask(mask, mw, mh, (float)mx, (float)my);
            mask_[(size_t)y * w_ + x] = v;
            if (v > 0.5f) ++inside;
        }
    if (inside < 1) return false;

    const int big = std::max(w_, h_);
    grow_ = std::max(2, big / 110);
    ringW_ = std::max(4, big / 50);

    // the ring around the flaw: the clean pixels that tell the light
    const size_t N = (size_t)w_ * h_;
    std::vector<uint8_t> mb(N), f1, f2;
    for (size_t i = 0; i < N; ++i) mb[i] = mask_[i] > 0.5f ? 1 : 0;
    f1 = dilate(mb, w_, h_, grow_);
    f2 = dilate(mb, w_, h_, grow_ + ringW_);
    std::vector<size_t> ring;
    for (size_t i = 0; i < N; ++i)
        if (f2[i] && !f1[i]) ring.push_back(i);
    const size_t stride = std::max<size_t>(1, ring.size() / 600);
    for (size_t k = 0; k < ring.size(); k += stride) {
        const int x = (int)(ring[k] % (size_t)w_), y = (int)(ring[k] / (size_t)w_);
        ringPts_.push_back(dom_[0] + ((float)x + 0.5f) / (float)w_ * ds);
        ringPts_.push_back(dom_[1] + ((float)y + 0.5f) / (float)h_ * dt);
    }
    return true;
}

float RemovePlate::ringLuma(const float* gray, int w, int h, const float* quadUv) const {
    if (!gray || w < 2 || h < 2 || !quadUv || ringPts_.size() < 16) return 0.f;
    float qp[8];
    for (int i = 0; i < 4; ++i) {
        qp[2 * i] = quadUv[2 * i] * (float)w;
        qp[2 * i + 1] = quadUv[2 * i + 1] * (float)h;
    }
    double H[9];
    if (!squareToQuad(qp, H)) return 0.f;
    double sum = 0;
    int n = 0;
    for (size_t i = 0; i + 1 < ringPts_.size(); i += 2) {
        double x, y;
        hApply(H, ringPts_[i], ringPts_[i + 1], &x, &y);
        if (!(x >= 0 && y >= 0 && x <= w && y <= h)) continue;
        sum += sampleGray(gray, w, h, (float)x, (float)y);
        ++n;
    }
    return n >= 8 ? (float)(sum / n) : 0.f;
}

bool RemovePlate::addFrame(const uint32_t* argb, int w, int h, const float* quadUv) {
    if (!argb || w < 2 || h < 2 || !quadUv || w_ <= 0) return false;
    float qp[8];
    for (int i = 0; i < 4; ++i) {
        qp[2 * i] = quadUv[2 * i] * (float)w;
        qp[2 * i + 1] = quadUv[2 * i + 1] * (float)h;
    }
    double H[9];
    if (!squareToQuad(qp, H)) return false;
    const float ds = dom_[2] - dom_[0], dt = dom_[3] - dom_[1];
    std::vector<float> plate((size_t)w_ * h_ * 3);
    for (int y = 0; y < h_; ++y)
        for (int x = 0; x < w_; ++x) {
            double fx, fy;
            hApply(H, dom_[0] + ((double)x + 0.5) / w_ * ds, dom_[1] + ((double)y + 0.5) / h_ * dt, &fx, &fy);
            if (!(std::fabs(fx) < 1e8 && std::fabs(fy) < 1e8)) {
                fx = fy = 0;
            }
            sampleRgb(argb, w, h, (float)fx, (float)fy, &plate[((size_t)y * w_ + x) * 3]);
        }
    plates_.push_back(std::move(plate));
    return true;
}

bool RemovePlate::build(bool useFrames, std::vector<uint8_t>* rgba) {
    if (!rgba || plates_.empty() || w_ <= 0) return false;
    const int W = w_, H = h_;
    const size_t N = (size_t)W * H;
    std::vector<uint8_t> mb(N);
    for (size_t i = 0; i < N; ++i) mb[i] = mask_[i] > 0.5f ? 1 : 0;
    const std::vector<uint8_t> F = dilate(mb, W, H, grow_);
    const std::vector<uint8_t> F2 = dilate(mb, W, H, grow_ + ringW_);
    std::vector<uint8_t> ring(N);
    size_t ringN = 0;
    for (size_t i = 0; i < N; ++i) {
        ring[i] = (F2[i] && !F[i]) ? 1 : 0;
        ringN += ring[i];
    }
    const std::vector<float>& ref = plates_[0];

    // 1) "Surround": the clean pixels around the flaw flow in
    std::vector<float> S = ref;
    inpaint(S, F, W, H);
    std::vector<float> C = S;
    std::vector<float> reveal(N, 0.f);

    // 2) "Frames": what the other frames show where the reference has the flaw
    const int n = (int)plates_.size();
    if (useFrames && n >= 3) {
        std::vector<float> rl((size_t)n, 1.f);
        if (ringN > 0) {
            for (int k = 0; k < n; ++k) {
                double s = 0;
                for (size_t i = 0; i < N; ++i)
                    if (ring[i]) s += lumaOf(&plates_[(size_t)k][i * 3]);
                rl[(size_t)k] = (float)std::max(1.0, s / (double)ringN);
            }
        }
        std::vector<float> norm((size_t)n);
        for (int k = 0; k < n; ++k) norm[(size_t)k] = std::min(2.f, std::max(0.5f, rl[0] / rl[(size_t)k]));
        std::vector<float> med(N * 3, 0.f), diff(N, 0.f), tmp((size_t)n);
        for (size_t i = 0; i < N; ++i) {
            if (!F[i]) continue;
            float mx = 0.f;
            for (int c = 0; c < 3; ++c) {
                for (int k = 0; k < n; ++k) tmp[(size_t)k] = plates_[(size_t)k][i * 3 + c] * norm[(size_t)k];
                std::nth_element(tmp.begin(), tmp.begin() + n / 2, tmp.end());
                med[i * 3 + c] = tmp[(size_t)(n / 2)];
                mx = std::max(mx, std::fabs(med[i * 3 + c] - ref[i * 3 + c]));
            }
            diff[i] = mx;
        }
        boxBlur(diff, W, H, 2);   // lone pixels are noise / a small mis-registration, not "revealed"
        // How much of the painted area do the other frames change? A lot = the flaw is not glued to the surface, so the frames
        // are trusted all over the painted area (a pixel that does not change was clean anyway). Little = a flaw stuck on the
        // surface: nothing is revealed, the surround fills it.
        size_t paintedN = 0, changedN = 0;
        for (size_t i = 0; i < N; ++i)
            if (mb[i]) {
                ++paintedN;
                if (diff[i] > 30.f) ++changedN;
            }
        const float trust = smooth(0.08f, 0.35f, paintedN ? (float)changedN / (float)paintedN : 0.f);
        for (size_t i = 0; i < N; ++i) reveal[i] = F[i] ? smooth(18.f, 48.f, diff[i]) : 0.f;
        boxBlur(reveal, W, H, 2);
        for (size_t i = 0; i < N; ++i) {
            if (!F[i]) {
                reveal[i] = 0.f;
                continue;
            }
            const float r = std::min(1.f, std::max(reveal[i] * 1.6f, trust));
            reveal[i] = r;
            for (int c = 0; c < 3; ++c) C[i * 3 + c] = S[i * 3 + c] * (1.f - r) + med[i * 3 + c] * r;
        }
    }

    // 3) grain: the smooth fill is too clean next to the camera's noise; add the noise level of the ring
    if (ringN > 16) {
        std::vector<float> lum(N), mean(N);
        for (size_t i = 0; i < N; ++i) lum[i] = lumaOf(&ref[i * 3]);
        mean = lum;
        boxBlur(mean, W, H, 1);
        double s2 = 0;
        for (size_t i = 0; i < N; ++i)
            if (ring[i]) {
                const double d = lum[i] - mean[i] * 1.0;   // (the zero padding at the borders is rare in the ring)
                s2 += d * d;
            }
        float sg = (float)std::sqrt(s2 / (double)ringN) * 1.25f;   // the 3x3 mean takes some of the noise away again
        sg = std::min(sg, 12.f);
        Rng rng;
        for (size_t i = 0; i < N; ++i) {
            if (!F[i]) continue;
            const float a = sg * (1.f - reveal[i]);
            const float common = rng.gauss() * 0.8f;
            for (int c = 0; c < 3; ++c) C[i * 3 + c] += a * (common + 0.4f * rng.gauss());
        }
    }

    // 4) a soft edge: coverage = the flaw grown a little, blurred
    std::vector<float> A(N);
    for (size_t i = 0; i < N; ++i) A[i] = F[i] ? 1.f : 0.f;
    const int rb = std::max(1, grow_ / 3);
    for (int p = 0; p < 3; ++p) boxBlur(A, W, H, rb);

    rgba->assign(N * 4, 0);
    for (size_t i = 0; i < N; ++i) {
        const float a = std::min(1.f, std::max(0.f, A[i]));
        for (int c = 0; c < 3; ++c) {
            const float v = std::min(255.f, std::max(0.f, C[i * 3 + c])) * a;
            (*rgba)[i * 4 + c] = (uint8_t)std::lround(v);
        }
        (*rgba)[i * 4 + 3] = (uint8_t)std::lround(a * 255.f);
    }
    return true;
}

bool RemovePlate::subQuad(const float* quadUv, const float* dom, float* out8) {
    if (!quadUv || !dom || !out8) return false;
    if (!quadConvex(quadUv, 1e-4f)) return false;
    double H[9];
    if (!squareToQuad(quadUv, H)) return false;
    const double sx[4] = {dom[0], dom[2], dom[2], dom[0]}, sy[4] = {dom[1], dom[1], dom[3], dom[3]};
    for (int i = 0; i < 4; ++i) {
        double x, y;
        hApply(H, sx[i], sy[i], &x, &y);
        if (!(std::fabs(x) < 1e6 && std::fabs(y) < 1e6)) return false;
        out8[2 * i] = (float)x;
        out8[2 * i + 1] = (float)y;
    }
    return true;
}

}  // namespace am
