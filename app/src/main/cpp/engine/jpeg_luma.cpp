#include "jpeg_luma.h"

#include <climits>
#include <cmath>
#include <cstring>

namespace am {
namespace {

const uint8_t kZigzag[64] = {0,  1,  8,  16, 9,  2,  3,  10, 17, 24, 32, 25, 18, 11, 4,  5,
                             12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6,  7,  14, 21, 28,
                             35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
                             58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};

struct HuffTable {
    bool present = false;
    uint8_t vals[256] = {0};
    int mincode[17] = {0};
    int maxcode[17] = {0};
    int valptr[17] = {0};
};

struct Component {
    int id = 0;
    int h = 1, v = 1;
    int tq = 0;
    int dcTable = 0, acTable = 0;
    int pred = 0;
};

// Reads the entropy-coded segment bit by bit (0xFF00 -> 0xFF, markers feed zero bits).
struct BitReader {
    const uint8_t* p;
    const uint8_t* end;
    uint32_t cur = 0;
    int n = 0;
    bool marker = false;

    int bit() {
        if (n == 0) {
            uint32_t b = 0;
            if (!marker && p < end) {
                b = *p;
                if (b == 0xFF) {
                    const uint8_t next = (p + 1 < end) ? p[1] : 0xD9;
                    if (next == 0x00) {
                        p += 2;  // stuffed zero byte
                    } else {
                        marker = true;  // real marker: stay on it, deliver zeros
                        b = 0;
                    }
                } else {
                    ++p;
                }
            }
            cur = b;
            n = 8;
        }
        --n;
        return (int)((cur >> n) & 1);
    }

    int receive(int s) {
        int v = 0;
        for (int i = 0; i < s; ++i) v = (v << 1) | bit();
        return v;
    }

    // Drop the rest of the current byte and move behind the next RSTn marker.
    bool restart() {
        n = 0;
        marker = false;
        while (p + 1 < end) {
            if (p[0] == 0xFF && p[1] >= 0xD0 && p[1] <= 0xD7) {
                p += 2;
                return true;
            }
            ++p;
        }
        return false;
    }
};

inline int extend(int v, int s) { return v < (1 << (s - 1)) ? v - (1 << s) + 1 : v; }

int decodeHuff(BitReader& br, const HuffTable& t) {
    int code = 0;
    for (int l = 1; l <= 16; ++l) {
        code = (code << 1) | br.bit();
        if (t.maxcode[l] >= 0 && code <= t.maxcode[l]) {
            const int idx = t.valptr[l] + code - t.mincode[l];
            if (idx < 0 || idx > 255) return -1;
            return t.vals[idx];
        }
    }
    return -1;
}

void buildHuff(HuffTable& t, const uint8_t* bits /*[1..16]*/) {
    int code = 0, k = 0;
    for (int l = 1; l <= 16; ++l) {
        t.valptr[l] = k;
        t.mincode[l] = code;
        code += bits[l];
        t.maxcode[l] = bits[l] ? code - 1 : -1;
        k += bits[l];
        code <<= 1;
    }
    t.present = true;
}

struct IdctBasis {
    float b[8][8];  // b[u][x] = C(u) / 2 * cos((2x + 1) u pi / 16)
    IdctBasis() {
        for (int u = 0; u < 8; ++u)
            for (int x = 0; x < 8; ++x)
                b[u][x] = (u == 0 ? 0.70710678118f : 1.f) * 0.5f * std::cos((2 * x + 1) * u * 3.14159265358979f / 16.f);
    }
};

void idct8x8(const int* coef /*natural order*/, uint8_t* out, int stride) {
    static const IdctBasis kB;
    float tmp[64];
    for (int v = 0; v < 8; ++v) {
        for (int x = 0; x < 8; ++x) {
            float s = 0.f;
            for (int u = 0; u < 8; ++u) s += (float)coef[v * 8 + u] * kB.b[u][x];
            tmp[v * 8 + x] = s;
        }
    }
    for (int y = 0; y < 8; ++y) {
        for (int x = 0; x < 8; ++x) {
            float s = 0.f;
            for (int v = 0; v < 8; ++v) s += tmp[v * 8 + x] * kB.b[v][y];
            int r = (int)std::floor(s + 128.5f);
            out[y * stride + x] = (uint8_t)(r < 0 ? 0 : (r > 255 ? 255 : r));
        }
    }
}

bool decodeBlock(BitReader& br, const HuffTable& dc, const HuffTable& ac, int& pred, const int* qzz, int* coef) {
    memset(coef, 0, 64 * sizeof(int));
    const int s = decodeHuff(br, dc);
    if (s < 0 || s > 16) return false;
    const int diff = s ? extend(br.receive(s), s) : 0;
    pred += diff;
    coef[0] = pred * qzz[0];
    int k = 1;
    while (k < 64) {
        const int rs = decodeHuff(br, ac);
        if (rs < 0) return false;
        const int r = rs >> 4, sz = rs & 15;
        if (sz == 0) {
            if (r == 15) { k += 16; continue; }
            break;  // end of block
        }
        k += r;
        if (k > 63) return false;
        coef[kZigzag[k]] = extend(br.receive(sz), sz) * qzz[k];
        ++k;
    }
    return true;
}

}  // namespace

bool decodeJpegLuma(const uint8_t* d, size_t size, std::vector<uint8_t>* yOut, int* wOut, int* hOut) {
    if (!d || size < 4 || d[0] != 0xFF || d[1] != 0xD8 || !yOut || !wOut || !hOut) return false;

    int quant[4][64] = {{0}};
    bool quantOk[4] = {false, false, false, false};
    HuffTable dcT[4], acT[4];
    Component comp[3];
    int ncomp = 0, width = 0, height = 0, restartInterval = 0;
    bool haveFrame = false;

    size_t pos = 2;
    while (pos + 2 <= size) {
        if (d[pos] != 0xFF) { ++pos; continue; }
        const uint8_t m = d[pos + 1];
        if (m == 0xFF) { ++pos; continue; }
        pos += 2;
        if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7) || m == 0x00) continue;
        if (m == 0xD9) break;
        if (pos + 2 > size) return false;
        const size_t len = ((size_t)d[pos] << 8) | d[pos + 1];
        if (len < 2 || pos + len > size) return false;
        const uint8_t* seg = d + pos + 2;
        const size_t slen = len - 2;

        if (m == 0xDB) {  // DQT
            size_t i = 0;
            while (i < slen) {
                const int pq = seg[i] >> 4, tq = seg[i] & 15;
                ++i;
                if (tq > 3 || pq > 1) return false;
                const size_t need = pq ? 128 : 64;
                if (i + need > slen) return false;
                for (int k = 0; k < 64; ++k) {
                    quant[tq][k] = pq ? ((seg[i + 2 * k] << 8) | seg[i + 2 * k + 1]) : seg[i + k];
                }
                quantOk[tq] = true;
                i += need;
            }
        } else if (m == 0xC4) {  // DHT
            size_t i = 0;
            while (i + 17 <= slen) {
                const int tc = seg[i] >> 4, th = seg[i] & 15;
                if (tc > 1 || th > 3) return false;
                uint8_t bits[17] = {0};
                int total = 0;
                for (int l = 1; l <= 16; ++l) { bits[l] = seg[i + l]; total += bits[l]; }
                i += 17;
                if (total > 256 || i + (size_t)total > slen) return false;
                HuffTable& t = tc ? acT[th] : dcT[th];
                memcpy(t.vals, seg + i, (size_t)total);
                buildHuff(t, bits);
                i += (size_t)total;
            }
        } else if (m == 0xDD) {  // DRI
            if (slen < 2) return false;
            restartInterval = (seg[0] << 8) | seg[1];
        } else if (m == 0xC0 || m == 0xC1) {  // SOF0 / SOF1: baseline / extended sequential
            if (slen < 6 || seg[0] != 8) return false;
            height = (seg[1] << 8) | seg[2];
            width = (seg[3] << 8) | seg[4];
            ncomp = seg[5];
            if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return false;
            if (ncomp != 1 && ncomp != 3) return false;
            if (slen < 6 + (size_t)ncomp * 3) return false;
            for (int i = 0; i < ncomp; ++i) {
                comp[i].id = seg[6 + i * 3];
                comp[i].h = seg[7 + i * 3] >> 4;
                comp[i].v = seg[7 + i * 3] & 15;
                comp[i].tq = seg[8 + i * 3];
                if (comp[i].h < 1 || comp[i].h > 4 || comp[i].v < 1 || comp[i].v > 4 || comp[i].tq > 3) return false;
            }
            haveFrame = true;
        } else if (m >= 0xC2 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
            return false;  // progressive, lossless, arithmetic ...: not supported
        } else if (m == 0xDA) {  // SOS: the entropy-coded data follows
            if (!haveFrame || slen < 1) return false;
            const int ns = seg[0];
            if (ns != ncomp || slen < 1 + (size_t)ns * 2 + 3) return false;
            for (int i = 0; i < ns; ++i) {
                const int cs = seg[1 + i * 2], tt = seg[2 + i * 2];
                if (cs != comp[i].id) return false;  // components must come in frame order (always the case)
                comp[i].dcTable = tt >> 4;
                comp[i].acTable = tt & 15;
                if (comp[i].dcTable > 3 || comp[i].acTable > 3) return false;
                if (!dcT[comp[i].dcTable].present || !acT[comp[i].acTable].present || !quantOk[comp[i].tq]) return false;
            }

            int hmax = 1, vmax = 1;
            if (ncomp == 3) {
                for (int i = 0; i < 3; ++i) {
                    if (comp[i].h > hmax) hmax = comp[i].h;
                    if (comp[i].v > vmax) vmax = comp[i].v;
                }
            } else {
                comp[0].h = comp[0].v = 1;  // a single-component scan is one block per MCU
            }
            if (comp[0].h != hmax || comp[0].v != vmax) return false;  // luma must be the full-resolution plane

            const int mcusX = (width + 8 * hmax - 1) / (8 * hmax);
            const int mcusY = (height + 8 * vmax - 1) / (8 * vmax);
            const int planeW = mcusX * 8 * comp[0].h;
            const int planeH = mcusY * 8 * comp[0].v;
            std::vector<uint8_t> plane((size_t)planeW * (size_t)planeH, 0);

            BitReader br;
            br.p = d + pos + len;
            br.end = d + size;
            for (int i = 0; i < ncomp; ++i) comp[i].pred = 0;

            int coef[64];
            long mcuCount = 0;
            for (int my = 0; my < mcusY; ++my) {
                for (int mx = 0; mx < mcusX; ++mx) {
                    if (restartInterval > 0 && mcuCount > 0 && mcuCount % restartInterval == 0) {
                        if (!br.restart()) return false;
                        for (int i = 0; i < ncomp; ++i) comp[i].pred = 0;
                    }
                    ++mcuCount;
                    for (int ci = 0; ci < ncomp; ++ci) {
                        Component& c = comp[ci];
                        for (int bv = 0; bv < c.v; ++bv) {
                            for (int bh = 0; bh < c.h; ++bh) {
                                if (!decodeBlock(br, dcT[c.dcTable], acT[c.acTable], c.pred, quant[c.tq], coef)) {
                                    return false;
                                }
                                if (ci == 0) {
                                    const int x0 = (mx * c.h + bh) * 8;
                                    const int y0 = (my * c.v + bv) * 8;
                                    idct8x8(coef, plane.data() + (size_t)y0 * planeW + x0, planeW);
                                }
                            }
                        }
                    }
                }
            }

            yOut->resize((size_t)width * (size_t)height);
            for (int y = 0; y < height; ++y) {
                memcpy(yOut->data() + (size_t)y * width, plane.data() + (size_t)y * planeW, (size_t)width);
            }
            *wOut = width;
            *hOut = height;
            return true;
        }
        pos += len;
    }
    return false;
}

}  // namespace am
