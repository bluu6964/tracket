// 2D homography maths shared by the planar tracker (planar_track.cpp) and the corner pin (quad_warp.h).
// Plain C++17, no GL / Android, so it is host-testable. H is row-major 3x3, maps (x, y, 1).
#pragma once
#include <cmath>
#include <cstdint>
#include <vector>

namespace am {

inline void hApply(const double* H, double x, double y, double* ox, double* oy, double* ow = nullptr) {
    const double w = H[6] * x + H[7] * y + H[8];
    const double X = H[0] * x + H[1] * y + H[2];
    const double Y = H[3] * x + H[4] * y + H[5];
    if (ow) *ow = w;
    if (std::fabs(w) < 1e-12) {
        *ox = *oy = 1e30;
        return;
    }
    *ox = X / w;
    *oy = Y / w;
}

// Gauss elimination with partial pivoting on the augmented n x (n + 1) matrix [a] (row-major). False = singular.
inline bool solveLinear(double* a, int n, double* x) {
    const int stride = n + 1;
    for (int c = 0; c < n; ++c) {
        int piv = c;
        for (int r = c + 1; r < n; ++r)
            if (std::fabs(a[r * stride + c]) > std::fabs(a[piv * stride + c])) piv = r;
        if (std::fabs(a[piv * stride + c]) < 1e-12) return false;
        if (piv != c)
            for (int k = 0; k < stride; ++k) std::swap(a[c * stride + k], a[piv * stride + k]);
        for (int r = c + 1; r < n; ++r) {
            const double f = a[r * stride + c] / a[c * stride + c];
            if (f == 0.0) continue;
            for (int k = c; k < stride; ++k) a[r * stride + k] -= f * a[c * stride + k];
        }
    }
    for (int r = n - 1; r >= 0; --r) {
        double s = a[r * stride + n];
        for (int k = r + 1; k < n; ++k) s -= a[r * stride + k] * x[k];
        x[r] = s / a[r * stride + r];
    }
    return true;
}

// Least-squares homography dst ~ H * src from the correspondences idx[0..n) (>= 4; 4 = exact). src / dst are x, y, x, y ...
// Hartley normalisation keeps the normal equations well conditioned. False when degenerate.
inline bool hFit(const float* src, const float* dst, const int* idx, int n, double* H) {
    if (n < 4) return false;
    double c1x = 0, c1y = 0, c2x = 0, c2y = 0;
    for (int k = 0; k < n; ++k) {
        const int i = idx[k];
        c1x += src[2 * i]; c1y += src[2 * i + 1];
        c2x += dst[2 * i]; c2y += dst[2 * i + 1];
    }
    c1x /= n; c1y /= n; c2x /= n; c2y /= n;
    double d1 = 0, d2 = 0;
    for (int k = 0; k < n; ++k) {
        const int i = idx[k];
        d1 += std::hypot(src[2 * i] - c1x, src[2 * i + 1] - c1y);
        d2 += std::hypot(dst[2 * i] - c2x, dst[2 * i + 1] - c2y);
    }
    d1 /= n; d2 /= n;
    if (d1 < 1e-6 || d2 < 1e-6) return false;
    const double s1 = std::sqrt(2.0) / d1, s2 = std::sqrt(2.0) / d2;
    double A[8 * 9] = {0};
    for (int k = 0; k < n; ++k) {
        const int i = idx[k];
        const double x = (src[2 * i] - c1x) * s1, y = (src[2 * i + 1] - c1y) * s1;
        const double u = (dst[2 * i] - c2x) * s2, v = (dst[2 * i + 1] - c2y) * s2;
        const double r1[9] = {x, y, 1, 0, 0, 0, -u * x, -u * y, u};
        const double r2[9] = {0, 0, 0, x, y, 1, -v * x, -v * y, v};
        for (int a = 0; a < 8; ++a)
            for (int b = 0; b < 9; ++b) A[a * 9 + b] += r1[a] * r1[b] + r2[a] * r2[b];
    }
    double h[8];
    if (!solveLinear(A, 8, h)) return false;
    const double Hn[9] = {h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0};
    // H = T2^-1 * Hn * T1, T1 = [s1 0 -s1 c1x; 0 s1 -s1 c1y; 0 0 1], T2^-1 = [1/s2 0 c2x; 0 1/s2 c2y; 0 0 1]
    const double T1[9] = {s1, 0, -s1 * c1x, 0, s1, -s1 * c1y, 0, 0, 1};
    const double T2i[9] = {1 / s2, 0, c2x, 0, 1 / s2, c2y, 0, 0, 1};
    double M[9], R[9];
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) {
            M[r * 3 + c] = Hn[r * 3] * T1[c] + Hn[r * 3 + 1] * T1[3 + c] + Hn[r * 3 + 2] * T1[6 + c];
        }
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) {
            R[r * 3 + c] = T2i[r * 3] * M[c] + T2i[r * 3 + 1] * M[3 + c] + T2i[r * 3 + 2] * M[6 + c];
        }
    if (std::fabs(R[8]) < 1e-12) return false;
    for (int k = 0; k < 9; ++k) H[k] = R[k] / R[8];
    for (int k = 0; k < 9; ++k)
        if (!std::isfinite(H[k])) return false;
    return true;
}

// Unit square (s, t in 0..1; (0,0) = TL, (1,0) = TR, (1,1) = BR, (0,1) = BL) -> quad p[8] (TL, TR, BR, BL; x, y, x, y ...).
// Closed form (Heckbert). False when the four points are degenerate (zero area / two corners together).
inline bool squareToQuad(const float* p, double* H) {
    const double x0 = p[0], y0 = p[1], x1 = p[2], y1 = p[3], x2 = p[4], y2 = p[5], x3 = p[6], y3 = p[7];
    const double sx = x0 - x1 + x2 - x3, sy = y0 - y1 + y2 - y3;
    double a, b, c, d, e, f, g, h;
    if (std::fabs(sx) < 1e-9 && std::fabs(sy) < 1e-9) {
        a = x1 - x0; b = x2 - x1; c = x0;
        d = y1 - y0; e = y2 - y1; f = y0;
        g = 0; h = 0;
    } else {
        const double dx1 = x1 - x2, dx2 = x3 - x2, dy1 = y1 - y2, dy2 = y3 - y2;
        const double del = dx1 * dy2 - dx2 * dy1;
        if (std::fabs(del) < 1e-9) return false;
        g = (sx * dy2 - dx2 * sy) / del;
        h = (dx1 * sy - sx * dy1) / del;
        a = x1 - x0 + g * x1; b = x3 - x0 + h * x3; c = x0;
        d = y1 - y0 + g * y1; e = y3 - y0 + h * y3; f = y0;
    }
    const double r[9] = {a, b, c, d, e, f, g, h, 1.0};
    for (int k = 0; k < 9; ++k) H[k] = r[k];
    return true;
}

// Convex, clockwise-or-anticlockwise but consistent, not degenerate. [minEdge] = shortest side allowed.
inline bool quadConvex(const float* q, float minEdge = 1.f) {
    double sign = 0;
    for (int i = 0; i < 4; ++i) {
        const int j = (i + 1) % 4, k = (i + 2) % 4;
        const double ex = q[2 * j] - q[2 * i], ey = q[2 * j + 1] - q[2 * i + 1];
        const double fx = q[2 * k] - q[2 * j], fy = q[2 * k + 1] - q[2 * j + 1];
        if (std::hypot(ex, ey) < minEdge) return false;
        const double cr = ex * fy - ey * fx;
        if (!std::isfinite(cr)) return false;
        if (std::fabs(cr) < 1e-6) return false;
        if (sign == 0) sign = cr > 0 ? 1 : -1;
        else if ((cr > 0 ? 1 : -1) != sign) return false;
    }
    return true;
}

inline double quadArea(const float* q) {
    double a = 0;
    for (int i = 0; i < 4; ++i) {
        const int j = (i + 1) % 4;
        a += (double)q[2 * i] * q[2 * j + 1] - (double)q[2 * j] * q[2 * i + 1];
    }
    return std::fabs(a) * 0.5;
}

}  // namespace am
