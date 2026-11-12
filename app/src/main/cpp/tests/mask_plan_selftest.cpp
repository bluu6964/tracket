// g++ -std=c++17 -O2 -Wall -Wextra -I../engine mask_plan_selftest.cpp ../engine/mask_plan.cpp && ./a.out
// Optional: ./a.out dump out.txt  -> writes the coverage grid the GPU test (tests/mask_gpu_check.js) compares with.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#include "mask_plan.h"

using namespace am;
static int fails = 0, checks = 0;
#define CHECK(c, ...) do { ++checks; if (!(c)) { ++fails; printf("FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static std::vector<float> mk(int shape, int mode, float cx, float cy, float w, float h, float rot = 0, float feather = 0,
                             float expand = 0, float opacity = 100, float corner = 0, int sides = 5, float inner = 50,
                             bool invert = false) {
    std::vector<float> f(kMaskStride, 0.f);
    f[0] = (float)shape; f[1] = (float)mode; f[2] = invert ? 1.f : 0.f;
    f[3] = cx; f[4] = cy; f[5] = w; f[6] = h; f[7] = rot; f[8] = feather; f[9] = expand; f[10] = opacity;
    f[11] = corner; f[12] = (float)sides; f[13] = inner;
    return f;
}
static void cat(std::vector<float>& a, const std::vector<float>& b) { a.insert(a.end(), b.begin(), b.end()); }

static float cov1(const std::vector<float>& f, int W, int H, float x, float y) {
    MaskOp ops[kMaskMax];
    const int n = maskParseOps(f.data(), (int)f.size(), W, H, ops);
    return n ? maskEvaluate(ops, n, x, y) : -1.f;
}

int main(int argc, char** argv) {
    const int W = 1920, H = 1080;
    // 1. rectangle: inside / outside / edge antialias
    {
        auto r = mk(MS_RECT, MM_ADD, 0.5f, 0.5f, 0.5f, 0.5f);
        CHECK(cov1(r, W, H, 960, 540) > 0.999f, "rect centre");
        CHECK(cov1(r, W, H, 100, 100) < 0.001f, "rect outside");
        CHECK(cov1(r, W, H, 960 + 480 - 0.5f, 540) > 0.5f, "rect just inside the edge");
        CHECK(cov1(r, W, H, 960 + 480 + 0.5f, 540) < 0.5f, "rect just outside the edge");
        const float e = cov1(r, W, H, 960 + 480, 540);
        CHECK(std::fabs(e - 0.5f) < 0.01f, "edge is half covered, got %g", e);
    }
    // 2. rounded corners cut the corner point
    {
        auto r = mk(MS_RECT, MM_ADD, 0.5f, 0.5f, 0.5f, 0.5f, 0, 0, 0, 100, 100);
        CHECK(cov1(r, W, H, 960 + 479, 540 + 269) < 0.01f, "rounded corner removed");
        CHECK(cov1(r, W, H, 960 + 200, 540) > 0.99f, "rounded rect still solid inside");
    }
    // 3. ellipse
    {
        auto r = mk(MS_ELLIPSE, MM_ADD, 0.5f, 0.5f, 0.4f, 0.8f);
        CHECK(cov1(r, W, H, 960, 540) > 0.99f, "ellipse centre");
        CHECK(cov1(r, W, H, 960 + 383, 540) > 0.9f, "ellipse inside near x radius");
        CHECK(cov1(r, W, H, 960 + 390, 540) < 0.1f, "ellipse outside x radius");
        CHECK(cov1(r, W, H, 960, 540 + 450) < 0.1f, "ellipse outside y radius");
        CHECK(cov1(r, W, H, 960 + 300, 540 + 300) < 0.01f, "ellipse corner is empty");
    }
    // 4. rotation turns a thin rectangle
    {
        auto r = mk(MS_RECT, MM_ADD, 0.5f, 0.5f, 0.6f, 0.05f, 90);
        CHECK(cov1(r, W, H, 960, 540 + 300) > 0.99f, "rotated 90: tall now (below)");
        CHECK(cov1(r, W, H, 960 + 300, 540) < 0.01f, "rotated 90: not wide any more");
    }
    // 5. feather: smooth ramp, monotonic, 0.5 on the edge
    {
        auto r = mk(MS_RECT, MM_ADD, 0.5f, 0.5f, 0.5f, 0.5f, 0, 50);
        float prev = 2.f;
        bool mono = true;
        for (int dx = -150; dx <= 150; dx += 5) {
            const float v = cov1(r, W, H, 960 + 480 + dx, 540);
            if (v > prev + 1e-5f) mono = false;
            prev = v;
        }
        CHECK(mono, "feather ramp falls monotonically");
        CHECK(std::fabs(cov1(r, W, H, 960 + 480, 540) - 0.5f) < 0.01f, "feather: edge stays 0.5");
        CHECK(cov1(r, W, H, 960 + 480 - 100, 540) > 0.9f, "feather: well inside is solid");
    }
    // 6. expansion grows / shrinks
    {
        auto g = mk(MS_RECT, MM_ADD, 0.5f, 0.5f, 0.5f, 0.5f, 0, 0, 20);   // + 0.05 * H = 54 px
        auto s = mk(MS_RECT, MM_ADD, 0.5f, 0.5f, 0.5f, 0.5f, 0, 0, -20);
        CHECK(cov1(g, W, H, 960 + 480 + 40, 540) > 0.99f, "expanded rect covers 40 px outside");
        CHECK(cov1(s, W, H, 960 + 480 - 40, 540) < 0.01f, "shrunk rect lost 40 px inside");
    }
    // 7. invert + opacity
    {
        auto r = mk(MS_RECT, MM_ADD, 0.5f, 0.5f, 0.5f, 0.5f, 0, 0, 0, 100, 0, 5, 50, true);
        CHECK(cov1(r, W, H, 960, 540) < 0.001f && cov1(r, W, H, 10, 10) > 0.999f, "invert");
        auto o = mk(MS_RECT, MM_ADD, 0.5f, 0.5f, 0.5f, 0.5f, 0, 0, 0, 40);
        CHECK(std::fabs(cov1(o, W, H, 960, 540) - 0.4f) < 1e-3f, "opacity 40 %%");
        auto z = mk(MS_RECT, MM_ADD, 0.5f, 0.5f, 0.5f, 0.5f, 0, 0, 0, 0);
        MaskOp ops[kMaskMax];
        CHECK(maskParseOps(z.data(), (int)z.size(), W, H, ops) == 0, "opacity 0 is skipped");
    }
    // 8. polygon: triangle has its vertex up, hexagon is round-ish, star is spiky
    {
        auto t = mk(MS_POLYGON, MM_ADD, 0.5f, 0.5f, 0.5f, 0.5f, 0, 0, 0, 100, 0, 3);
        const float R = 270.f;  // min(hw,hh) = 270
        CHECK(cov1(t, W, H, 960, 540 - R + 12) > 0.99f, "triangle: top vertex region is filled");
        CHECK(cov1(t, W, H, 960 + 0.5f * R * 1.6f, 540 - R + 12) < 0.01f, "triangle: top corners are empty");
        CHECK(cov1(t, W, H, 960, 540 + R * 0.5f - 6) > 0.99f, "triangle: flat bottom edge (apothem 0.5 R)");
        CHECK(cov1(t, W, H, 960, 540 + R * 0.5f + 6) < 0.01f, "triangle: nothing below the bottom edge");
        auto st = mk(MS_STAR, MM_ADD, 0.5f, 0.5f, 0.5f, 0.5f, 0, 0, 0, 100, 0, 5, 40);
        CHECK(cov1(st, W, H, 960, 540 - R + 10) > 0.99f, "star: top spike tip filled");
        // between two spikes (angle 36 deg from up) at 0.8 R must be empty for a 40 % star
        const float a = 36.f * 3.14159265f / 180.f;
        CHECK(cov1(st, W, H, 960 + std::sin(a) * R * 0.8f, 540 - std::cos(a) * R * 0.8f) < 0.01f, "star: notch is empty");
        CHECK(cov1(st, W, H, 960, 540) > 0.99f, "star centre");
        auto hex = mk(MS_POLYGON, MM_ADD, 0.5f, 0.5f, 0.28125f, 0.5f, 0, 0, 0, 100, 0, 6);  // square box: regular hexagon
        CHECK(cov1(hex, W, H, 960 + R * 0.8f, 540) > 0.99f && cov1(hex, W, H, 960 + R * 0.92f, 540) < 0.01f, "hexagon extent (flat side at 0.866 R)");
        // 100 % inner star == polygon
        auto s100 = mk(MS_STAR, MM_ADD, 0.5f, 0.5f, 0.28125f, 0.5f, 0, 0, 0, 100, 0, 6, 100);
        bool same = true;
        for (int i = 0; i < 40; ++i) {
            const float x = 700 + i * 13.f, y = 300 + i * 11.f;
            if (std::fabs(cov1(s100, W, H, x, y) - cov1(hex, W, H, x, y)) > 1e-3f) same = false;
        }
        CHECK(same, "star with inner 100 %% equals the polygon");
    }
    // 9. line and mirror
    {
        auto l = mk(MS_LINE, MM_ADD, 0.5f, 0.5f, 1, 1);
        CHECK(cov1(l, W, H, 960, 540 - 50) > 0.99f && cov1(l, W, H, 960, 540 + 50) < 0.01f, "line keeps the upper side");
        auto l2 = mk(MS_LINE, MM_ADD, 0.5f, 0.5f, 1, 1, 180);
        CHECK(cov1(l2, W, H, 960, 540 + 50) > 0.99f && cov1(l2, W, H, 960, 540 - 50) < 0.01f, "line rotated 180 keeps the lower side");
        auto m = mk(MS_MIRROR, MM_ADD, 0.5f, 0.5f, 1, 0.2f);
        CHECK(cov1(m, W, H, 100, 540 + 50) > 0.99f && cov1(m, W, H, 100, 540 + 130) < 0.01f, "mirror band");
    }
    // 10. pen path: a triangle, polygon and smooth
    {
        auto p = mk(MS_PATH, MM_ADD, 0.5f, 0.5f, 0.5f, 0.5f);
        p[14] = 3;
        const float c[6] = {0.f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f};
        for (int i = 0; i < 6; ++i) p[16 + i] = c[i];
        CHECK(cov1(p, W, H, 960, 540 + 100) > 0.99f, "path triangle inside");
        CHECK(cov1(p, W, H, 960 + 440, 540 - 200) < 0.01f, "path triangle outside");
        p[15] = 1;
        CHECK(cov1(p, W, H, 960, 540 + 100) > 0.99f, "smooth path inside");
        float out[kMaskPtsMax * 2];
        CHECK(maskFlattenPath(c, 3, true, out) == 24 && maskFlattenPath(c, 3, false, out) == 3, "flatten counts");
        std::vector<float> many(32);
        for (int i = 0; i < 16; ++i) { many[2 * i] = std::cos(i * 0.39f) * 0.4f; many[2 * i + 1] = std::sin(i * 0.39f) * 0.4f; }
        CHECK(maskFlattenPath(many.data(), 16, true, out) == 96, "16 control points -> 96 samples");
        auto bad = mk(MS_PATH, MM_ADD, 0.5f, 0.5f, 0.5f, 0.5f);
        bad[14] = 2;
        MaskOp ops[kMaskMax];
        CHECK(maskParseOps(bad.data(), (int)bad.size(), W, H, ops) == 0, "pen with 2 points is skipped");
        // concave pen (arrow) keeps its notch empty
        auto arrow = mk(MS_PATH, MM_ADD, 0.5f, 0.5f, 0.5f, 0.5f);
        arrow[14] = 4;
        const float a4[8] = {-0.5f, -0.5f, 0.5f, -0.5f, 0.f, 0.f, -0.5f, 0.5f};
        for (int i = 0; i < 8; ++i) arrow[16 + i] = a4[i];
        CHECK(cov1(arrow, W, H, 960 + 200, 540) < 0.01f && cov1(arrow, W, H, 960 - 200, 540 - 100) > 0.99f, "concave pen");
    }
    // 11. combine modes
    {
        std::vector<float> f;
        cat(f, mk(MS_RECT, MM_ADD, 0.4f, 0.5f, 0.4f, 0.5f));       // A: x 0.2..0.6
        cat(f, mk(MS_RECT, MM_SUBTRACT, 0.6f, 0.5f, 0.4f, 0.5f));  // B: x 0.4..0.8
        const float xa = 0.25f * W, xab = 0.5f * W, xb = 0.7f * W, y = 0.5f * H;
        CHECK(cov1(f, W, H, xa, y) > 0.99f && cov1(f, W, H, xab, y) < 0.01f && cov1(f, W, H, xb, y) < 0.01f, "add then subtract");
        f.clear();
        cat(f, mk(MS_RECT, MM_ADD, 0.4f, 0.5f, 0.4f, 0.5f));
        cat(f, mk(MS_RECT, MM_INTERSECT, 0.6f, 0.5f, 0.4f, 0.5f));
        CHECK(cov1(f, W, H, xa, y) < 0.01f && cov1(f, W, H, xab, y) > 0.99f && cov1(f, W, H, xb, y) < 0.01f, "intersect = overlap");
        f.clear();
        cat(f, mk(MS_RECT, MM_ADD, 0.4f, 0.5f, 0.4f, 0.5f));
        cat(f, mk(MS_RECT, MM_DIFFERENCE, 0.6f, 0.5f, 0.4f, 0.5f));
        CHECK(cov1(f, W, H, xa, y) > 0.99f && cov1(f, W, H, xab, y) < 0.01f && cov1(f, W, H, xb, y) > 0.99f, "difference = xor");
        f.clear();
        cat(f, mk(MS_ELLIPSE, MM_SUBTRACT, 0.5f, 0.5f, 0.3f, 0.3f));  // first mask subtracts: works on the whole picture
        CHECK(cov1(f, W, H, 960, 540) < 0.01f && cov1(f, W, H, 100, 100) > 0.99f, "first mask subtract = hole in the whole picture");
        // more than 8 masks: extra ones are dropped
        f.clear();
        for (int i = 0; i < 10; ++i) cat(f, mk(MS_RECT, MM_ADD, 0.5f, 0.5f, 0.2f, 0.2f));
        MaskOp ops[kMaskMax];
        CHECK(maskParseOps(f.data(), (int)f.size(), W, H, ops) == kMaskMax, "max 8 masks");
        // junk input
        CHECK(maskParseOps(nullptr, 0, W, H, ops) == 0 && maskParseOps(f.data(), 10, W, H, ops) == 0, "junk input");
    }
    // 11b. the Graph bar wipe, end to end (round-7 regression): the flat array below is the
    // verbatim output of the real importer + OverlayMasks.remap for bar "1" of the Graph preset
    // (7e363d75) at its settled time, against a simulated fx region of 316 x 435 — the array the
    // engines hand this parser on device. Alight's settled bar shows the bottom 0.279 of the
    // bar's box; the unit-box wipe state (pre-fix) covered only 0.098 of it here.
    {
        const float ops[] = {0.f, 0.f, 0.f, 0.5f, 0.6522781f, 0.16208549f, 0.7831157f, 90.f,
                             0.f, 0.f, 100.f, 0.f, 5.f, 45.f, 0.f, 0.f};
        MaskOp mo[kMaskMax];
        const int n = maskParseOps(ops, 48, 316, 435, mo);
        CHECK(n == 1, "graph bar: one mask parsed, got %d", n);
        // the bar's box inside that region (fractions from the same audit)
        const double bx = 0.39719102 * 316, by = 0.28879613 * 435, bw = 0.20561787 * 316, bh = 0.4224078 * 435;
        int covered = 0, total = 0;
        double ySum = 0;
        for (int y = 0; y < 435; ++y)
            for (int x = 0; x < 316; ++x) {
                if (x < bx || x >= bx + bw || y < by || y >= by + bh) continue;
                ++total;
                if (maskEvaluate(mo, n, x + 0.5f, y + 0.5f) > 0.5f) { ++covered; ySum += (y - by) / bh; }
            }
        const double frac = (double)covered / total;
        CHECK(std::fabs(frac - 0.279) < 0.01, "graph bar: covered fraction %g, want ~0.279", frac);
        CHECK(std::fabs(ySum / covered - 0.86) < 0.02, "graph bar: band centroid %g, want ~0.86", ySum / covered);
        CHECK(maskEvaluate(mo, n, (float)(bx + bw / 2), (float)(by + bh - 2)) > 0.99f, "graph bar: bottom covered");
        CHECK(maskEvaluate(mo, n, (float)(bx + bw / 2), (float)(by + 2)) < 0.01f, "graph bar: top open");
    }
    // 11c. layer-mask cuts (build 138): an Alight Mask layer intersects (DST_IN), an Exclude Mask
    // layer subtracts (DST_OUT); a stack applies its cuts in turn. Coverages below are the mask
    // layers' rendered alphas at a pixel (1 inside a hard-edged shape, 0 outside).
    {
        // Intersect alone: the picture keeps where the mask is, scaled by its coverage.
        CHECK(std::fabs(layerMaskCut(0.8f, 0.5f, false) - 0.4f) < 1e-6f, "intersect cut scales by coverage");
        CHECK(layerMaskCut(1.f, 1.f, false) == 1.f && layerMaskCut(1.f, 0.f, false) == 0.f, "intersect binary");
        // Subtract ALONE (24 of the corpus's 30 exclude layers sit in no Mask stack — e.g.
        // proyek-baru-1's exclude in its 932..2032 ms window): the target minus the shape.
        CHECK(layerMaskCut(1.f, 1.f, true) == 0.f, "exclude alone punches the hole");
        CHECK(layerMaskCut(1.f, 0.f, true) == 1.f, "exclude alone leaves the outside untouched");
        CHECK(std::fabs(layerMaskCut(0.8f, 0.5f, true) - 0.4f) < 1e-6f, "exclude cut scales by one-minus-coverage");
        // The wym-prst-268 pattern (one Mask circle + one Exclude circle over one image, x5):
        // kept inside the mask only, removed where the exclude overlaps, gone outside the mask.
        const float covME[2] = {1.f, 1.f};
        const bool excME[2] = {false, true};
        CHECK(layerMaskCutStack(1.f, covME, excME, 2) == 0.f, "wym: inside mask AND exclude -> removed");
        const float covM[2] = {1.f, 0.f};
        CHECK(layerMaskCutStack(1.f, covM, excME, 2) == 1.f, "wym: inside mask only -> kept");
        const float covE[2] = {0.f, 1.f};
        CHECK(layerMaskCutStack(1.f, covE, excME, 2) == 0.f, "wym: outside mask -> gone");
        // For hard-edged coverages the cut order in the stack cannot matter:
        // (mask - exclude) == (exclude applied first, then mask).
        const bool excEM[2] = {true, false};
        const float covBoth[2] = {1.f, 1.f};
        CHECK(layerMaskCutStack(1.f, covBoth, excEM, 2) == layerMaskCutStack(1.f, covBoth, excME, 2),
              "binary cuts commute");
        // Two excludes remove the UNION of their shapes (screen union of the removals).
        const float covEE[2] = {0.5f, 0.5f};
        const bool excEE[2] = {true, true};
        CHECK(std::fabs(layerMaskCutStack(1.f, covEE, excEE, 2) - 0.25f) < 1e-6f,
              "two excludes: kept = (1-e1)(1-e2)");
        // An empty stack cuts nothing.
        CHECK(layerMaskCutStack(0.7f, covEE, excEE, 0) == 0.7f, "empty stack is identity");
    }
    // 12. optional dump for the GPU comparison (one config per line group, see tests/mask_gpu_check.js)
    if (argc >= 3 && std::strcmp(argv[1], "dump") == 0) {
        FILE* fp = std::fopen(argv[2], "w");
        const int w = 192, h = 108;
        struct Cfg { const char* name; std::vector<float> f; };
        std::vector<Cfg> cfgs;
        cfgs.push_back({"rect_round_feather", mk(MS_RECT, MM_ADD, 0.45f, 0.5f, 0.5f, 0.6f, 20, 30, 5, 100, 40)});
        cfgs.push_back({"ellipse_rot", mk(MS_ELLIPSE, MM_ADD, 0.5f, 0.5f, 0.7f, 0.4f, -30, 10, 0, 80)});
        cfgs.push_back({"star7", mk(MS_STAR, MM_ADD, 0.5f, 0.5f, 0.6f, 0.8f, 15, 5, 0, 100, 0, 7, 45)});
        cfgs.push_back({"hex_inv", mk(MS_POLYGON, MM_ADD, 0.5f, 0.5f, 0.5f, 0.7f, 0, 0, -10, 100, 0, 6, 100, true)});
        cfgs.push_back({"line", mk(MS_LINE, MM_ADD, 0.5f, 0.45f, 1, 1, 25, 20)});
        cfgs.push_back({"mirror", mk(MS_MIRROR, MM_ADD, 0.5f, 0.5f, 1, 0.3f, 10, 8)});
        {
            auto p = mk(MS_PATH, MM_ADD, 0.5f, 0.5f, 0.6f, 0.7f, 20, 6);
            p[14] = 5; p[15] = 1;
            const float c[10] = {0.f, -0.5f, 0.5f, -0.1f, 0.3f, 0.5f, -0.3f, 0.5f, -0.5f, -0.1f};
            for (int i = 0; i < 10; ++i) p[16 + i] = c[i];
            cfgs.push_back({"pen_smooth", p});
        }
        {
            std::vector<float> f;
            cat(f, mk(MS_RECT, MM_ADD, 0.4f, 0.5f, 0.4f, 0.6f, 0, 10));
            cat(f, mk(MS_ELLIPSE, MM_DIFFERENCE, 0.6f, 0.5f, 0.4f, 0.6f, 0, 10));
            cat(f, mk(MS_STAR, MM_SUBTRACT, 0.5f, 0.5f, 0.2f, 0.3f, 0, 0, 0, 100, 0, 5, 40));
            cfgs.push_back({"combo", f});
        }
        for (auto& c : cfgs) {
            MaskOp ops[kMaskMax];
            const int n = maskParseOps(c.f.data(), (int)c.f.size(), w, h, ops);
            std::fprintf(fp, "%s %d %d %d\n", c.name, w, h, n);
            for (int i = 0; i < n; ++i) {
                const MaskOp& o = ops[i];
                std::fprintf(fp, "%d %d %d %.6f %.6f %.6f %.6f %.7f %.7f %.6f %.6f %.6f %.6f %.7f %.7f %.7f %.7f %.7f %d", o.shape, o.mode,
                             o.invert ? 1 : 0, o.cx, o.cy, o.hw, o.hh, o.cosR, o.sinR, o.feather, o.expand, o.opacity, o.corner,
                             o.anRad, o.anCos, o.anSin, o.enCos, o.enSin, o.nPts);
                for (int k = 0; k < o.nPts * 2; ++k) std::fprintf(fp, " %.5f", o.pts[k]);
                std::fprintf(fp, "\n");
            }
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x) std::fprintf(fp, "%.5f ", maskEvaluate(ops, n, x + 0.5f, y + 0.5f));
            std::fprintf(fp, "\n");
        }
        std::fclose(fp);
    }
    printf("%d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
