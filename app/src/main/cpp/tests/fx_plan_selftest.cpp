// g++ -std=c++17 -O2 -Wall -Wextra -I../engine fx_plan_selftest.cpp ../engine/fx_plan.cpp && ./a.out
#include <cmath>
#include <cstdio>
#include <vector>

#include "fx_plan.h"

using namespace am;
static int fails = 0, checks = 0;
#define CHECK(c, ...) do { ++checks; if (!(c)) { ++fails; printf("FAIL line %d: ", __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

// Simulates what the GPU does: a bilinear fetch on a 1-D signal, then the kernel is mirrored.
static double bilinear(const std::vector<double>& s, double x) {  // x in texel units, centres at integers
    int i0 = (int)std::floor(x);
    double f = x - i0;
    auto at = [&](int i) { return (i >= 0 && i < (int)s.size()) ? s[i] : 0.0; };
    return at(i0) * (1 - f) + at(i0 + 1) * f;
}

int main() {
    // 1. linear-sampling kernel == discrete Gaussian
    for (float sigma : {0.6f, 1.f, 2.5f, 4.f, 8.f}) {
        GaussKernel k = fxGaussKernel(sigma);
        double tot = k.centre;
        for (int i = 0; i < k.n; ++i) tot += 2.0 * k.weight[i];
        CHECK(std::fabs(tot - 1.0) < 1e-5, "sigma %g weights sum %g", sigma, tot);
        CHECK(k.n <= kFxMaxTaps, "too many taps %d", k.n);
        // blur a noisy signal both ways
        std::vector<double> sig(200);
        for (size_t i = 0; i < sig.size(); ++i) sig[i] = std::sin(i * 0.37) + ((i * 2654435761u) % 97) / 97.0;
        const int R = (int)std::ceil(3.5f * sigma);
        double norm = 0;
        for (int i = -R; i <= R; ++i) norm += std::exp(-0.5 * i * i / ((double)sigma * sigma));
        for (int c : {60, 100, 140}) {
            double ref = 0;
            for (int i = -R; i <= R; ++i) ref += sig[c + i] * std::exp(-0.5 * i * i / ((double)sigma * sigma)) / norm;
            double got = sig[c] * k.centre;
            for (int i = 0; i < k.n; ++i) got += k.weight[i] * (bilinear(sig, c + k.offset[i]) + bilinear(sig, c - k.offset[i]));
            CHECK(std::fabs(ref - got) < 1e-4, "sigma %g at %d: ref %g got %g", sigma, c, ref, got);
        }
    }
    // 2. planning
    CHECK(fxPlanBlur(3.f).shift == 0, "small blur at full size");
    CHECK(fxPlanBlur(20.f).shift == 2 && std::fabs(fxPlanBlur(20.f).sigmaLow - 5.f) < 1e-4, "sigma 20 -> shift 2");
    CHECK(fxPlanBlur(1000.f).shift == 5, "shift capped");
    for (float s = 0.5f; s < 400.f; s *= 1.3f) {
        BlurPlan p = fxPlanBlur(s);
        GaussKernel k = fxGaussKernel(p.sigmaLow);
        CHECK(k.n < kFxMaxTaps || p.shift == 5, "sigma %g needs %d pairs", s, k.n);
        CHECK(std::fabs(p.sigmaLow * (1 << p.shift) - s) < 1e-3 * s || p.shift == 5, "sigma mismatch");
    }
    CHECK(fxBlurSigma(0, 1) == 0 && std::fabs(fxBlurSigma(100, 2) - 120.f) < 1e-4, "blur sigma");
    // 3. parse
    float flat[] = {1, 40, 0, 0, 0, 0, 0, 0, 99, 1, 1, 1, 1, 1, 0, 0, 3, 30, 90, 50, 0, 0, 0, 0};
    FxOp ops[kFxMaxOps];
    CHECK(fxParseOps(flat, 24, ops) == 2, "unknown type skipped");
    CHECK(ops[0].type == FX_BLUR && ops[1].type == FX_RGB_SPLIT && ops[1].p[1] == 90, "parse values");
    CHECK(fxParseOps(flat, 7, ops) == 0, "short array");
    // 4. glow plan monotonic
    FxOp g1{FX_GLOW, {60, 10, 100, 0, 0}}, g2{FX_GLOW, {60, 90, 100, 0, 0}};
    CHECK(fxPlanGlow(g2).spread > fxPlanGlow(g1).spread, "radius widens glow");
    CHECK(fxPlanGlow(g1).spread < 1.f && fxPlanGlow(g2).spread < 1.f, "spread < 1");
    // 5. misc
    CHECK(fxGrainFrame(999) == 23 && fxGrainFrame(1000) == 24, "grain frame");
    CHECK(fxGrainCell(0, 1) == 1.f && fxGrainCell(100, 1) == 4.f, "grain cell");
    CHECK(fxSplitPixels(100, 1) == 27.f, "split px");
    // 6. chroma key
    {
        float r, g, b;
        fxHsvToRgb(120, 1, &r, &g, &b);
        CHECK(r == 0 && g == 1 && b == 0, "hue 120 is green");
        fxHsvToRgb(240, 1, &r, &g, &b);
        CHECK(r == 0 && g == 0 && b == 1, "hue 240 is blue");
        fxHsvToRgb(0, 1, &r, &g, &b);
        CHECK(r == 1 && g == 0 && b == 0, "hue 0 is red");
        fxHsvToRgb(60, 0.5f, &r, &g, &b);
        CHECK(std::fabs(r - 1) < 1e-5 && std::fabs(g - 1) < 1e-5 && std::fabs(b - 0.5f) < 1e-5, "hue 60 sat .5");
        FxOp k{FX_CHROMA, {120, 60, 35, 15, 50}};
        ChromaPlan pl = fxPlanChroma(k);
        CHECK(pl.keyCb < 0 && pl.keyCr < 0, "green key sits at negative Cb / Cr");
        CHECK(std::fabs(pl.dirCb * pl.dirCb + pl.dirCr * pl.dirCr - 1.f) < 1e-5, "direction is a unit vector");
        float cb, cr;
        // a typical, dull green screen colour in several brightnesses is keyed out
        for (float v : {0.25f, 0.5f, 0.8f}) {
            fxChromaKeyed(0.30f * v, 0.80f * v, 0.32f * v, &cb, &cr);
            CHECK(fxChromaKeep(pl, cb, cr) < 0.02f, "green screen at brightness %g is removed (%g)", v, fxChromaKeep(pl, cb, cr));
        }
        // skin, white, black, grey, red, blue are kept
        const float keep[][3] = {{0.85f, 0.62f, 0.5f}, {1, 1, 1}, {0, 0, 0}, {0.5f, 0.5f, 0.5f}, {0.9f, 0.1f, 0.1f}, {0.1f, 0.2f, 0.9f}};
        for (auto& c : keep) {
            fxChromaKeyed(c[0], c[1], c[2], &cb, &cr);
            CHECK(fxChromaKeep(pl, cb, cr) > 0.98f, "colour %g %g %g is kept (%g)", c[0], c[1], c[2], fxChromaKeep(pl, cb, cr));
        }
        // more similarity removes more, more smoothness softens the edge
        FxOp a{FX_CHROMA, {120, 60, 10, 5, 0}}, bb{FX_CHROMA, {120, 60, 80, 5, 0}};
        fxChromaKeyed(0.45f, 0.65f, 0.40f, &cb, &cr);
        CHECK(fxChromaKeep(fxPlanChroma(bb), cb, cr) <= fxChromaKeep(fxPlanChroma(a), cb, cr), "similarity monotonic");
        FxOp hard{FX_CHROMA, {120, 60, 35, 0, 0}}, soft{FX_CHROMA, {120, 60, 35, 100, 0}};
        ChromaPlan ph = fxPlanChroma(hard), ps = fxPlanChroma(soft);
        CHECK(ps.soft > ph.soft && ps.tol == ph.tol, "smoothness widens the edge only");
        // blue key: a blue pixel is removed, green is kept
        FxOp bl{FX_CHROMA, {240, 60, 35, 15, 0}};
        ChromaPlan pb = fxPlanChroma(bl);
        fxChromaKeyed(0.15f, 0.25f, 0.8f, &cb, &cr);
        CHECK(fxChromaKeep(pb, cb, cr) < 0.02f, "blue screen removed");
        fxChromaKeyed(0.2f, 0.8f, 0.2f, &cb, &cr);
        CHECK(fxChromaKeep(pb, cb, cr) > 0.98f, "green kept with blue key");
        // spill: skin has no chroma towards green, so nothing is taken out of it
        fxChromaOf(0.85f, 0.62f, 0.5f, &cb, &cr);
        CHECK(pl.dirCb * cb + pl.dirCr * cr < 0.f, "skin is not pushed by the green spill removal");
        // parse accepts the new type
        float flat6[] = {6, 120, 60, 35, 15, 50, 0, 0};
        FxOp o6[kFxMaxOps];
        CHECK(fxParseOps(flat6, 8, o6) == 1 && o6[0].type == FX_CHROMA && o6[0].p[4] == 50, "parse chroma");
    }
    // 7. eyedropper: ANY picked colour becomes the key, and it is the colour that goes (not only green)
    {
        struct Px { float r, g, b; const char* name; };
        const Px picks[] = {{0.30f, 0.80f, 0.32f, "green"}, {0.1f, 0.2f, 0.9f, "blue"}, {0.9f, 0.1f, 0.2f, "red"},
                            {0.05f, 0.12f, 0.06f, "dark green"}, {0.9f, 0.8f, 0.1f, "yellow"}, {0.2f, 0.9f, 0.9f, "cyan"},
                            {0.85f, 0.62f, 0.5f, "skin"}, {1, 1, 1, "white"}, {0.5f, 0.5f, 0.5f, "grey"}, {0.02f, 0.02f, 0.02f, "black"},
                            {0.95f, 0.85f, 0.2f, "gold"}, {0.6f, 0.2f, 0.7f, "purple"}};
        auto keyed = [&](const Px& pick, const Px& pix) {
            float h, st, kv, m, cb, cr;
            fxKeyFromRgb(pick.r, pick.g, pick.b, &h, &st, &kv, &m);
            FxOp o{FX_CHROMA, {h, st, 35, 15, 0, kv, m}};
            ChromaPlan pl = fxPlanChroma(o);
            fxChromaKeyed(pix.r, pix.g, pix.b, &cb, &cr);
            return fxChromaKeep(pl, cb, cr, std::max(pix.r, std::max(pix.g, pix.b)));
        };
        for (auto& c : picks) {
            CHECK(keyed(c, c) < 0.001f, "picked %s is removed (%g)", c.name, keyed(c, c));
        }
        // the picked colour goes, clearly different colours stay
        for (auto& a : picks) for (auto& b : picks) {
            if (&a == &b) continue;
            const float dr = a.r - b.r, dg = a.g - b.g, db = a.b - b.b;
            if (std::sqrt(dr * dr + dg * dg + db * db) < 0.75f) continue;  // similar colours may go together
            // two shades of one vivid colour (green / dark green) are the same key by design: shadows go with the light
            float ha, sa, va, ma, hb, sb, vb, mb;
            fxKeyFromRgb(a.r, a.g, a.b, &ha, &sa, &va, &ma);
            fxKeyFromRgb(b.r, b.g, b.b, &hb, &sb, &vb, &mb);
            float dh = std::fabs(ha - hb);
            if (dh > 180.f) dh = 360.f - dh;
            if (sa > 20.f && sb > 20.f && dh < 25.f) continue;
            CHECK(keyed(a, b) > 0.9f, "picking %s must keep %s (%g)", a.name, b.name, keyed(a, b));
        }
        // a lit and a shadowed part of a coloured screen both go, also for a red or blue screen
        for (const Px& base : {picks[2], picks[1], picks[0]}) {
            for (float v : {0.35f, 0.6f, 1.f}) {
                Px shade{base.r * v, base.g * v, base.b * v, base.name};
                CHECK(keyed(base, shade) < 0.05f, "%s screen at brightness %g goes (%g)", base.name, v, keyed(base, shade));
            }
        }
        // white pick: light grey goes, yellow and skin stay; black pick: dark grey goes, dark red stays
        CHECK(keyed(picks[7], Px{0.93f, 0.93f, 0.93f, "light grey"}) < 0.05f, "white pick removes light grey");
        CHECK(keyed(picks[7], picks[4]) > 0.95f && keyed(picks[7], picks[6]) > 0.95f, "white pick keeps yellow and skin");
        CHECK(keyed(picks[9], Px{0.06f, 0.06f, 0.06f, "dark grey"}) < 0.05f, "black pick removes dark grey");
        CHECK(keyed(picks[9], Px{0.4f, 0.05f, 0.05f, "dark red"}) > 0.95f, "black pick keeps dark red");
        // a grey key does not push the colours of the kept pixels away (no spill direction)
        float h, st, kv, m;
        fxKeyFromRgb(0.5f, 0.5f, 0.5f, &h, &st, &kv, &m);
        CHECK(st == 0.f && m == 100.f, "grey: no strength, full brightness match");
        FxOp go{FX_CHROMA, {h, st, 35, 15, 100, kv, m}};
        CHECK(fxPlanChroma(go).spill == 0.f, "grey key has no spill removal");
        fxKeyFromRgb(0.30f, 0.80f, 0.32f, &h, &st, &kv, &m);
        CHECK(m == 0.f, "a vivid screen ignores brightness");
    }
    // ---- v140: shared shaded effects ----
    {
        // every new type has a program + mode, the old ones have none
        for (int t = FX_DIR_BLUR; t <= FX_LAST; ++t) {
            FxShaderRef r = fxShaderOf(t);
            CHECK(r.group >= FXG_BLUR2 && r.group <= FXG_STYLE, "type %d has a shader group", t);
            CHECK(r.mode >= 0 && r.mode <= 14, "type %d mode %d", t, r.mode);
        }
        CHECK(fxShaderOf(FX_BLUR).group == FXG_NONE && fxShaderOf(FX_CHROMA).group == FXG_NONE, "old effects keep their own passes");
        // no two effects share the same (group, mode)
        for (int a = FX_DIR_BLUR; a <= FX_LAST; ++a)
            for (int b = a + 1; b <= FX_LAST; ++b)
                CHECK(!(fxShaderOf(a).group == fxShaderOf(b).group && fxShaderOf(a).mode == fxShaderOf(b).mode), "types %d and %d collide", a, b);
        // parse: the new types are accepted, an unknown one is not, up to kFxMaxOps
        float flat[kFxMaxOps * kFxStride + 16] = {0};
        for (int i = 0; i < kFxMaxOps + 2; ++i) flat[i * kFxStride] = (float)FX_GLITCH;
        FxOp ops[kFxMaxOps];
        CHECK(fxParseOps(flat, (kFxMaxOps + 2) * kFxStride, ops) == kFxMaxOps, "parse stops at kFxMaxOps");
        float bad[8] = {(float)FX_MAX + 1, 1, 2, 3, 4, 5, 6, 7};
        CHECK(fxParseOps(bad, 8, ops) == 0, "unknown type skipped");
        // Motion Blur is Kotlin-resolved to a directional blur before it reaches the engine, so the
        // parser must skip it too (it sits above FX_MAX on purpose, like the unknown type above).
        float mb[8] = {(float)FX_MOTION_BLUR, 100, 0, 0, 0, 0, 0, 0};
        CHECK(fxParseOps(mb, 8, ops) == 0, "motion blur skipped by the parser");
        // Repeat is Kotlin-resolved to extra layer draws before it reaches the engine, so the parser
        // must skip it too (same rule as Motion Blur).
        float rp[8] = {(float)FX_REPEAT, 3, 0, 93, 0, 100, 100, 0};
        CHECK(fxParseOps(rp, 8, ops) == 0, "repeat skipped by the parser");
        // neutral sliders = no pass
        FxOp ex{FX_EXPOSURE, {0, 0, 100, 0}};
        CHECK(fxIsIdentity(ex), "exposure neutral");
        ex.p[0] = 1.f;
        CHECK(!fxIsIdentity(ex), "exposure +1 EV runs");
        FxOp lv{FX_LEVELS, {0, 255, 100, 0, 255}};
        CHECK(fxIsIdentity(lv), "levels neutral");
        lv.p[2] = 150.f;
        CHECK(!fxIsIdentity(lv), "levels gamma runs");
        // v149: every new effect has its own (group, mode) and a neutral setting that skips the pass
        CHECK(FX_LAST == 53 && FX_MAX == 54, "27 effects added after the glitch, Copy Background after them");
        { FxOp cb{FX_COPY_BG, {100}}; CHECK(!fxIsIdentity(cb), "copy background 100 runs"); cb.p[0] = 0; CHECK(fxIsIdentity(cb), "copy background 0 is neutral"); }
        for (int t = FX_INVERT; t <= FX_LAST; ++t) {
            FxOp zero{t, {0, 0, 0, 0, 0, 0, 0}};
            if (t == FX_LEVELS) continue;
            const bool idz = fxIsIdentity(zero);
            // all-zero sliders: either a no-op (amount 0) or a real effect, but it must not crash and must be deterministic
            CHECK(idz == fxIsIdentity(zero), "type %d identity is stable", t);
        }
        {
            FxOp inv{FX_INVERT, {100, 0}};
            CHECK(!fxIsIdentity(inv), "invert 100 runs");
            inv.p[0] = 0;
            CHECK(fxIsIdentity(inv), "invert 0 is neutral");
            FxOp tile{FX_TILE, {1, 1, 0, 0, 0, 0, 0}};
            CHECK(fxIsIdentity(tile), "1 x 1 tile, no phase / scroll = neutral");
            tile.p[0] = 2;
            CHECK(!fxIsIdentity(tile), "2 x 1 tile runs");
            tile.p[0] = 1;
            tile.p[5] = 10;
            CHECK(!fxIsIdentity(tile), "scrolling tile runs");
            FxOp ld{FX_LENS_DISTORT, {0, 100, 0, 50, 50}};
            CHECK(fxIsIdentity(ld), "lens distortion neutral");
            ld.p[2] = 20;
            CHECK(!fxIsIdentity(ld), "chromatic aberration runs");
            FxOp sh{FX_SHAKE, {0, 12, 0, 30, 50, 0}};
            CHECK(fxIsIdentity(sh), "shake with no amount / rotation is neutral");
            FxOp of{FX_OLD_FILM, {0, 0, 0, 0, 0, 0}};
            CHECK(fxIsIdentity(of), "old film all zero is neutral");
            of.p[1] = 30;
            CHECK(!fxIsIdentity(of), "old film dust runs");
            FxOp lr{FX_LIGHT_RAYS, {60, 60, 0, 50, 50, 36, 30}};
            CHECK(fxIsIdentity(lr), "light rays intensity 0 is neutral");
            FxOp fl{FX_LENS_FLARE, {30, 30, 0, 50, 60, 50, 36}};
            CHECK(fxIsIdentity(fl), "lens flare brightness 0 is neutral");
        }
        FxOp mo{FX_MOSAIC, {20, 0}};
        CHECK(!fxIsIdentity(mo), "mosaic always runs");
        // levels maths
        CHECK(std::fabs(fxLevelsMap(0.5f, 0, 255, 100, 0, 255) - 0.5f) < 1e-5f, "levels neutral = identity");
        CHECK(std::fabs(fxLevelsMap(0.25f, 64, 255, 100, 0, 255)) < 1e-5f, "input black 64 maps 0.25 to 0");
        CHECK(std::fabs(fxLevelsMap(0.5f, 0, 128, 100, 0, 255) - 1.f) < 0.01f, "input white 128 maps 0.5 to 1");
        CHECK(fxLevelsMap(0.25f, 0, 255, 200, 0, 255) > 0.25f, "gamma above 100 percent brightens");
        CHECK(std::fabs(fxLevelsMap(1.f, 0, 255, 100, 0, 128) - 128.f / 255.f) < 1e-5f, "output white 128");
        // brightness / contrast: mid grey is the pivot
        CHECK(std::fabs(fxBrightContrast(0.5f, 0, 80) - 0.5f) < 1e-5f, "contrast keeps mid grey");
        CHECK(fxBrightContrast(0.7f, 0, 50) > 0.7f && fxBrightContrast(0.3f, 0, 50) < 0.3f, "contrast spreads");
        CHECK(fxBrightContrast(0.5f, 40, 0) > 0.5f, "brightness lifts");
        CHECK(std::fabs(fxBrightContrast(0.2f, 0, -100) - 0.5f) < 1e-5f, "contrast -100 = flat grey");
        // posterize
        CHECK(fxPosterize(0.5f, 2) == 1.f || fxPosterize(0.5f, 2) == 0.f, "2 levels = black or white");
        CHECK(std::fabs(fxPosterize(0.34f, 4) - 1.f / 3.f) < 1e-5f, "4 levels step 1/3");
        CHECK(fxPosterize(1.f, 6) == 1.f && fxPosterize(0.f, 6) == 0.f, "ends stay");
        // animation phase: 24 frames = one second
        CHECK(std::fabs(fxAutoPhase(24, 100.f) - 1.f) < 1e-6f && fxAutoPhase(24, 0.f) == 0.f, "auto phase");
        // v160: the long shadow / colour tune are shaded types above FX_MAX: parsed and mapped, with
        // neutral settings that skip the pass; the Kotlin-resolved neighbours 57 / 58 stay skipped.
        {
            float ls[8] = {(float)FX_LONG_SHADOW, 20, 45, 60, 80, 0, 0, 0};
            CHECK(fxParseOps(ls, 8, ops) == 1 && ops[0].type == FX_LONG_SHADOW, "long shadow parsed");
            float ct[8] = {(float)FX_COLOR_TUNE, 240, 30, 0, 36, 0, 0, 0};
            CHECK(fxParseOps(ct, 8, ops) == 1 && ops[0].type == FX_COLOR_TUNE, "colour tune parsed");
            float rr[8] = {57, 4, 200, 0, 360, 0, 100, 100};
            CHECK(fxParseOps(rr, 8, ops) == 0, "repeat radial still skipped by the parser");
            float rs[8] = {58, 12, 400, 0, 0, 0, 20, 100};
            CHECK(fxParseOps(rs, 8, ops) == 0, "repeat scatter still skipped by the parser");
            CHECK(fxShaderOf(FX_LONG_SHADOW).group == FXG_BLUR2 && fxShaderOf(FX_LONG_SHADOW).mode == 7, "long shadow shader");
            CHECK(fxShaderOf(FX_COLOR_TUNE).group == FXG_COLOR && fxShaderOf(FX_COLOR_TUNE).mode == 15, "colour tune shader");
            // v161: solid colour — parsed like 59 / 60, mapped to kFxColor mode 16, with the CPU
            // reference doing the same mix the shader does.
            float sc[8] = {(float)FX_SOLID_COLOR, 184, 145, 255, 100, 0, 0, 0};
            CHECK(fxParseOps(sc, 8, ops) == 1 && ops[0].type == FX_SOLID_COLOR, "solid colour parsed");
            CHECK(fxShaderOf(FX_SOLID_COLOR).group == FXG_COLOR && fxShaderOf(FX_SOLID_COLOR).mode == 16, "solid colour shader");
            for (int t = FX_DIR_BLUR; t <= FX_LAST; ++t) {
                CHECK(!(fxShaderOf(t).group == FXG_COLOR && fxShaderOf(t).mode == 16), "type %d collides with solid colour", t);
            }
            {
                float r, g, b;
                fxSolidColorMap(0.2f, 0.4f, 0.6f, 184, 145, 255, 100, &r, &g, &b);
                CHECK(std::fabs(r - 184.f / 255.f) < 1e-6f && std::fabs(g - 145.f / 255.f) < 1e-6f && std::fabs(b - 1.f) < 1e-6f, "solid colour at full strength is the colour");
                fxSolidColorMap(0.2f, 0.4f, 0.6f, 184, 145, 255, 0, &r, &g, &b);
                CHECK(std::fabs(r - 0.2f) < 1e-6f && std::fabs(g - 0.4f) < 1e-6f && std::fabs(b - 0.6f) < 1e-6f, "solid colour at strength 0 is the pixel");
                fxSolidColorMap(0.f, 0.f, 0.f, 255, 255, 255, 50, &r, &g, &b);
                CHECK(std::fabs(r - 0.5f) < 1e-6f && std::fabs(g - 0.5f) < 1e-6f && std::fabs(b - 0.5f) < 1e-6f, "solid colour at half strength is the midpoint");
                FxOp s0{FX_SOLID_COLOR, {184, 145, 255, 0, 0, 0, 0}};
                CHECK(fxIsIdentity(s0), "solid colour strength 0 is neutral");
                s0.p[3] = 100;
                CHECK(!fxIsIdentity(s0), "solid colour runs");
            }
            for (int t = FX_DIR_BLUR; t <= FX_LAST; ++t) {
                CHECK(!(fxShaderOf(t).group == FXG_BLUR2 && fxShaderOf(t).mode == 7), "type %d collides with long shadow", t);
                CHECK(!(fxShaderOf(t).group == FXG_COLOR && fxShaderOf(t).mode == 15), "type %d collides with colour tune", t);
            }
            FxOp l0{FX_LONG_SHADOW, {0, 45, 60, 80, 0, 0, 0}};
            CHECK(fxIsIdentity(l0), "long shadow length 0 is neutral");
            l0.p[0] = 20;
            l0.p[2] = 0;
            CHECK(fxIsIdentity(l0), "long shadow opacity 0 is neutral");
            l0.p[2] = 60;
            CHECK(!fxIsIdentity(l0), "long shadow runs");
            FxOp c0{FX_COLOR_TUNE, {240, 0, 0, 36, 0, 0, 0}};
            CHECK(fxIsIdentity(c0), "colour tune neutral");
            c0.p[1] = 30;
            CHECK(!fxIsIdentity(c0), "colour tune lift strength runs");
            // colour tune maths: neutral = identity; lift works on dark pixels, gain on bright ones;
            // a pure luminance shift keeps a grey grey.
            float r, g, b;
            fxColorTuneMap(0.3f, 0.5f, 0.7f, 240, 0, 0, 36, 0, 0, &r, &g, &b);
            CHECK(std::fabs(r - 0.3f) < 1e-5f && std::fabs(g - 0.5f) < 1e-5f && std::fabs(b - 0.7f) < 1e-5f, "colour tune neutral = identity");
            fxColorTuneMap(0.1f, 0.1f, 0.1f, 240, 100, 0, 36, 0, 0, &r, &g, &b);
            CHECK(b > 0.3f && b > r && std::fabs(r - g) < 1e-5f, "lift pushes dark grey toward blue");
            float r2, g2, b2;
            fxColorTuneMap(0.9f, 0.9f, 0.9f, 240, 100, 0, 36, 0, 0, &r2, &g2, &b2);
            CHECK(b2 - 0.9f < b - 0.1f, "lift barely touches bright pixels");
            fxColorTuneMap(0.9f, 0.9f, 0.9f, 240, 0, 0, 240, 100, 0, &r, &g, &b);
            CHECK(b > 0.95f && r < 0.9f, "gain pushes bright grey toward blue");
            fxColorTuneMap(0.1f, 0.1f, 0.1f, 240, 0, 50, 36, 0, 0, &r, &g, &b);
            CHECK(std::fabs(r - g) < 1e-5f && std::fabs(g - b) < 1e-5f && r > 0.4f && r < 0.6f, "lift luminance lifts a dark grey evenly");
        }
    }
    // 13. Alight Mirror (62) + Glow Scan (63): parsing, dispatch, identity, and the CPU twins of
    // the two shaders (AM's recovered formulas).
    {
        float flat[16] = {62, 0, 0, 100, 0, 0, 0, 0, 63, 50, 10, 2, 5, 100, 100, 35071};
        FxOp ops[kFxMaxOps];
        CHECK(fxParseOps(flat, 16, ops) == 2 && ops[0].type == FX_AM_MIRROR && ops[1].type == FX_GLOW_SCAN &&
              ops[1].p[6] == 35071.f, "mirror + glow scan parsed");
        CHECK(fxShaderOf(FX_AM_MIRROR).group == FXG_WARP && fxShaderOf(FX_AM_MIRROR).mode == 11, "mirror is warp mode 11");
        CHECK(fxShaderOf(FX_GLOW_SCAN).group == FXG_NONE, "glow scan is a dedicated pass, not a shaded mode");
        for (int t = FX_DIR_BLUR; t <= FX_LAST; ++t)
            CHECK(!(fxShaderOf(t).group == FXG_WARP && fxShaderOf(t).mode == 11), "type %d collides with AM mirror", t);
        CHECK(!(fxShaderOf(FX_LONG_SHADOW).group == FXG_WARP && fxShaderOf(FX_LONG_SHADOW).mode == 11), "long shadow collides");
        CHECK(!(fxShaderOf(FX_COLOR_TUNE).group == FXG_WARP && fxShaderOf(FX_COLOR_TUNE).mode == 11), "colour tune collides");
        CHECK(!(fxShaderOf(FX_SOLID_COLOR).group == FXG_WARP && fxShaderOf(FX_SOLID_COLOR).mode == 11), "solid colour collides");
        FxOp m0{FX_AM_MIRROR, {0, 0, 0, 0, 0, 0, 0}};
        CHECK(fxIsIdentity(m0), "mirror alpha 0 is neutral");
        m0.p[2] = 100;
        CHECK(!fxIsIdentity(m0), "mirror alpha 100 runs");
        FxOp s0{FX_GLOW_SCAN, {50, 10, 2, 5, 100, 0, 35071}};
        CHECK(fxIsIdentity(s0), "glow scan alpha 0 at fill 100 is neutral");
        s0.p[5] = 100;
        CHECK(!fxIsIdentity(s0), "glow scan runs");
        s0.p[5] = 0;
        s0.p[4] = 50;
        CHECK(!fxIsIdentity(s0), "glow scan fill 50 still scales the input");

        float r, g, b, a;
        // Mirror blends (S and F premultiplied): Normal is the flipped pixel, mixed by alpha.
        fxMirrorMap(0.2f, 0.4f, 0.6f, 1.f, 0.9f, 0.8f, 0.7f, 1.f, 0, 1.f, &r, &g, &b, &a);
        CHECK(std::fabs(r - 0.9f) < 1e-6f && std::fabs(g - 0.8f) < 1e-6f && std::fabs(a - 1.f) < 1e-6f, "mirror normal = flipped");
        fxMirrorMap(0.2f, 0.4f, 0.6f, 1.f, 0.9f, 0.8f, 0.7f, 1.f, 0, 0.f, &r, &g, &b, &a);
        CHECK(std::fabs(r - 0.2f) < 1e-6f && std::fabs(b - 0.6f) < 1e-6f, "mirror alpha 0 = original");
        fxMirrorMap(0.2f, 0.4f, 0.6f, 1.f, 0.9f, 0.8f, 0.7f, 1.f, 0, 0.5f, &r, &g, &b, &a);
        CHECK(std::fabs(r - 0.55f) < 1e-6f, "mirror alpha 50 = midpoint");
        // Multiply: (S.rgb / S.a) * F.rgb, re-scaled by S.a — with S.a = 0.5 that is S.rgb * F.rgb.
        fxMirrorMap(0.5f, 0.25f, 0.f, 0.5f, 0.2f, 0.2f, 0.2f, 1.f, 1, 1.f, &r, &g, &b, &a);
        CHECK(std::fabs(r - 0.1f) < 1e-6f && std::fabs(g - 0.05f) < 1e-6f && std::fabs(a - 0.5f) < 1e-6f, "mirror multiply");
        // Screen on a straight 0.5 grey: (1 - (1 - 0.5)(1 - 0.5)) * 0.5 = 0.375.
        fxMirrorMap(0.25f, 0.25f, 0.25f, 0.5f, 0.5f, 0.5f, 0.5f, 1.f, 2, 1.f, &r, &g, &b, &a);
        CHECK(std::fabs(r - 0.375f) < 1e-6f && std::fabs(a - 0.5f) < 1e-6f, "mirror screen");
        // Over / Under are the two premultiplied composites.
        fxMirrorMap(0.2f, 0.f, 0.f, 0.5f, 0.f, 0.4f, 0.f, 0.5f, 3, 1.f, &r, &g, &b, &a);
        CHECK(std::fabs(r - 0.1f) < 1e-6f && std::fabs(g - 0.4f) < 1e-6f && std::fabs(a - 0.75f) < 1e-6f, "mirror over");
        fxMirrorMap(0.2f, 0.f, 0.f, 0.5f, 0.f, 0.4f, 0.f, 0.5f, 4, 1.f, &r, &g, &b, &a);
        CHECK(std::fabs(r - 0.2f) < 1e-6f && std::fabs(g - 0.2f) < 1e-6f && std::fabs(a - 0.75f) < 1e-6f, "mirror under");
        // A transparent original must not NaN through the unpremultiply.
        fxMirrorMap(0.f, 0.f, 0.f, 0.f, 0.5f, 0.5f, 0.5f, 1.f, 1, 1.f, &r, &g, &b, &a);
        CHECK(r == 0.f && g == 0.f && b == 0.f && a == 0.f, "mirror multiply on transparency stays 0");

        // Glow Scan: outside the band the output is the input scaled by fill.
        fxGlowScanMap(0.1f, 0.2f, 0.3f, 1.f, 0.1f, 0.2f, 0.3f, 1.f, 0.9f, 0.1f, 2.f, 0.f, 0.f, 0.f, 0.f, 1.f, 1.f, &r, &g, &b, &a);
        CHECK(std::fabs(r - 0.1f) < 1e-6f && std::fabs(g - 0.2f) < 1e-6f && std::fabs(a - 1.f) < 1e-6f, "glow scan outside the band");
        fxGlowScanMap(0.1f, 0.2f, 0.3f, 1.f, 0.1f, 0.2f, 0.3f, 1.f, 0.9f, 0.1f, 2.f, 0.f, 0.f, 0.f, 0.f, 0.5f, 1.f, &r, &g, &b, &a);
        CHECK(std::fabs(r - 0.05f) < 1e-6f && std::fabs(a - 0.5f) < 1e-6f, "glow scan fill scales the input");
        // Band centre (luma 0.5, threshold 0.5, radius 0.1): lin = 0, the glow is pow(x, 0) = 1
        // per channel, so base + glow saturates to white at alpha 1.
        fxGlowScanMap(0.5f, 0.5f, 0.5f, 1.f, 0.5f, 0.5f, 0.5f, 1.f, 0.5f, 0.1f, 2.f, 0.f, 0.f, 0.f, 0.f, 1.f, 1.f, &r, &g, &b, &a);
        CHECK(std::fabs(r - 1.f) < 1e-6f && std::fabs(g - 1.f) < 1e-6f && std::fabs(a - 1.f) < 1e-6f, "glow scan band centre saturates");
        // Inside the band near its edge (adjT 0.49, luma 0.5, radius 0.1): lin = 0.8, the glow
        // exponent is power * lin^2 = 1.28, glow = pow(0.5, 1.28) = 0.4118: out = 0.9118.
        fxGlowScanMap(0.5f, 0.5f, 0.5f, 1.f, 0.5f, 0.5f, 0.5f, 1.f, 0.536364f, 0.1f, 2.f, 0.f, 0.f, 0.f, 0.f, 1.f, 1.f, &r, &g, &b, &a);
        CHECK(std::fabs(r - 0.9118f) < 2e-3f && std::fabs(g - 0.9118f) < 2e-3f, "glow scan inside the band");
        // Blend 1 toward a red fill colour at the same spot: the glow's red is unchanged
        // (mix(0.5, 1 * luma, 0.99) = 0.5) but its green collapses to pow(0.005, 1.28) ~ 0.001.
        fxGlowScanMap(0.5f, 0.5f, 0.5f, 1.f, 0.5f, 0.5f, 0.5f, 1.f, 0.536364f, 0.1f, 2.f, 1.f, 0.f, 0.f, 1.f, 1.f, 1.f, &r, &g, &b, &a);
        CHECK(std::fabs(r - 0.9118f) < 2e-3f && std::fabs(g - 0.5011f) < 2e-3f && std::fabs(b - 0.5011f) < 2e-3f, "glow scan blend tints the glow");
        // Alpha 0 inside the band: the input (times fill) is kept.
        fxGlowScanMap(0.5f, 0.5f, 0.5f, 1.f, 0.5f, 0.5f, 0.5f, 1.f, 0.5f, 0.1f, 2.f, 0.f, 0.f, 0.f, 0.f, 1.f, 0.f, &r, &g, &b, &a);
        CHECK(std::fabs(r - 0.5f) < 1e-6f, "glow scan alpha 0 keeps the input");
        // The pre-blur calibration: AM strength x 50 (0.05 -> 2.5, 0.10 -> 5, capped at 100).
        CHECK(std::fabs(fxGlowScanBlurriness(5.f) - 2.5f) < 1e-6f, "glow scan blurriness 0.05");
        CHECK(std::fabs(fxGlowScanBlurriness(10.f) - 5.f) < 1e-6f, "glow scan blurriness 0.10");
        CHECK(std::fabs(fxGlowScanBlurriness(400.f) - 100.f) < 1e-6f, "glow scan blurriness capped");
    }
    // v163: Alight's next tier (64..74). The shaded ones map to kFxWarp modes 12..14 / kFxColor
    // mode 17; Luma Key, Four Color Gradient, Turbulent Displace and Pixelate are dedicated
    // passes (FXG_NONE). The EXT types (66 / 68 / 73) fold into their owner's q[] at parse time.
    {
        for (int t : {FX_LUMA_KEY, FX_FOUR_COLOR_GRADIENT, FX_TURBULENT_DISPLACE_AM, FX_SWIRL_AM,
                      FX_STRETCH_SEGMENT, FX_HALFTONE_DOTS, FX_PIXELATE_AM, FX_REPLACE_COLOR}) {
            float one[8] = {(float)t, 0, 0, 0, 0, 0, 0, 0};
            CHECK(fxParseOps(one, 8, ops) == 1 && ops[0].type == t, "v163 type %d parsed", t);
        }
        CHECK(fxShaderOf(FX_STRETCH_SEGMENT).group == FXG_WARP && fxShaderOf(FX_STRETCH_SEGMENT).mode == 12, "stretch segment shader");
        CHECK(fxShaderOf(FX_SWIRL_AM).group == FXG_WARP && fxShaderOf(FX_SWIRL_AM).mode == 13, "swirl shader");
        CHECK(fxShaderOf(FX_HALFTONE_DOTS).group == FXG_WARP && fxShaderOf(FX_HALFTONE_DOTS).mode == 14, "halftone dots shader");
        CHECK(fxShaderOf(FX_REPLACE_COLOR).group == FXG_COLOR && fxShaderOf(FX_REPLACE_COLOR).mode == 17, "replace colour shader");
        for (int t : {FX_LUMA_KEY, FX_FOUR_COLOR_GRADIENT, FX_TURBULENT_DISPLACE_AM, FX_PIXELATE_AM}) {
            CHECK(fxShaderOf(t).group == FXG_NONE, "v163 type %d is a dedicated pass", t);
        }
        for (int t = FX_DIR_BLUR; t <= FX_LAST; ++t) {
            CHECK(!(fxShaderOf(t).group == FXG_WARP && fxShaderOf(t).mode >= 12 && fxShaderOf(t).mode <= 14), "type %d collides with a v163 warp mode", t);
            CHECK(!(fxShaderOf(t).group == FXG_COLOR && fxShaderOf(t).mode == 17), "type %d collides with replace colour", t);
        }
        // EXT folding: owner + EXT = one op with q set; EXT alone / after another owner is dropped.
        {
            float grad[16] = {(float)FX_FOUR_COLOR_GRADIENT, 255, 65280, 16711680, 100, 75, 0, 0,
                              (float)FX_FOUR_COLOR_GRADIENT_EXT, 8390156, 10438156, 10438656, 8390656, 0, 0, 0};
            CHECK(fxParseOps(grad, 16, ops) == 1 && ops[0].type == FX_FOUR_COLOR_GRADIENT, "gradient + ext = one op");
            CHECK(ops[0].q[0] == 8390156.f && ops[0].q[3] == 8390656.f, "gradient ext folded into q");
            float extOnly[8] = {(float)FX_TURBULENT_DISPLACE_AM_EXT, 1, 2, 3, 4, 5, 6, 7};
            CHECK(fxParseOps(extOnly, 8, ops) == 0, "ext alone dropped");
            float wrong[16] = {(float)FX_BLUR, 20, 0, 0, 0, 0, 0, 0,
                               (float)FX_PIXELATE_AM_EXT, 1, 2, 3, 4, 5, 6, 7};
            CHECK(fxParseOps(wrong, 16, ops) == 1 && ops[0].type == FX_BLUR && ops[0].q[0] == 0.f, "ext after wrong owner dropped");
            // EXT groups do not consume op slots: 11 plain + gradient(+ext) + 1 plain = 12 ops.
            float many[14 * 8];
            for (int i = 0; i < 11; ++i) { many[i * 8] = (float)FX_BLUR; many[i * 8 + 1] = 10; for (int k = 2; k < 8; ++k) many[i * 8 + k] = 0; }
            many[11 * 8] = (float)FX_FOUR_COLOR_GRADIENT; for (int k = 1; k < 8; ++k) many[11 * 8 + k] = 0;
            many[12 * 8] = (float)FX_FOUR_COLOR_GRADIENT_EXT; for (int k = 1; k < 8; ++k) many[12 * 8 + k] = 0;
            many[13 * 8] = (float)FX_GRAIN; for (int k = 1; k < 8; ++k) many[13 * 8 + k] = 0;
            CHECK(fxParseOps(many, 14 * 8, ops) == kFxMaxOps, "ext does not consume an op slot");
        }
        // Identity rules.
        FxOp lk{FX_LUMA_KEY, {0, 100, 5, 0, 1, 0, 0}};
        CHECK(fxIsIdentity(lk), "luma key full band is neutral");
        lk.p[0] = 50;
        CHECK(!fxIsIdentity(lk), "luma key low 50 runs");
        lk.p[0] = 0; lk.p[3] = 1;
        CHECK(!fxIsIdentity(lk), "luma key invert runs");
        FxOp fg{FX_FOUR_COLOR_GRADIENT, {0, 0, 0, 0, 100, 0, 0}};
        CHECK(fxIsIdentity(fg), "gradient alpha 0 is neutral");
        fg.p[5] = 75;
        CHECK(!fxIsIdentity(fg), "gradient runs");
        FxOp td{FX_TURBULENT_DISPLACE_AM, {0, 332, 2, 450, 1, 0, 0}};
        CHECK(fxIsIdentity(td), "turbulent intensity 0 is neutral");
        td.p[0] = 15;
        CHECK(!fxIsIdentity(td), "turbulent runs");
        FxOp sw{FX_SWIRL_AM, {0, 80, 0, 0, 4, 0, 0}};
        CHECK(fxIsIdentity(sw), "swirl strength 0 is neutral");
        sw.p[0] = -11;
        CHECK(!fxIsIdentity(sw), "swirl runs");
        FxOp ss{FX_STRETCH_SEGMENT, {0, 0, 0, 0, 0, 0, 0}};
        CHECK(fxIsIdentity(ss), "stretch 0 is neutral");
        ss.p[1] = 568;
        CHECK(!fxIsIdentity(ss), "stretch runs");
        FxOp hd{FX_HALFTONE_DOTS, {209.5f, 0, 45, 50, 0, 0, 0}};
        CHECK(!fxIsIdentity(hd), "halftone dots always run (strength 0 still posterizes)");
        FxOp px{FX_PIXELATE_AM, {1, 100, 100, 0, 0, 0, 0}};
        CHECK(fxIsIdentity(px), "pixelate size 1 is neutral");
        px.p[0] = 25;
        CHECK(!fxIsIdentity(px), "pixelate runs");
        px.p[0] = 1; px.p[4] = 100;
        CHECK(!fxIsIdentity(px), "pixelate vignette alone runs");
        FxOp rc{FX_REPLACE_COLOR, {0, 255, 25, 25, 0, 0, 0}};
        CHECK(fxIsIdentity(rc), "replace colour alpha 0 is neutral");
        rc.p[4] = 100;
        CHECK(!fxIsIdentity(rc), "replace colour runs");

        float r, g, b, a;
        // Luma Key band maths (AM's edges: lowB = low (1 + f) - 0.001, lowA = lowB - f, ...).
        CHECK(std::fabs(fxLumaKeyAlpha(0.5f, 0.5f, 0.5f, 0.f, 1.f, 0.05f, false, true) - 1.f) < 1e-6f, "luma full band keeps");
        CHECK(std::fabs(fxLumaKeyAlpha(0.5f, 0.5f, 0.5f, 0.25f, 0.75f, 0.1f, false, true) - 1.f) < 1e-6f, "luma mid band keeps");
        CHECK(std::fabs(fxLumaKeyAlpha(0.2f, 0.2f, 0.2f, 0.25f, 0.75f, 0.1f, false, true) - 0.167648f) < 1e-4f, "luma feathered low edge");
        CHECK(std::fabs(fxLumaKeyAlpha(0.2f, 0.2f, 0.2f, 0.25f, 0.75f, 0.1f, true, true) - 0.832352f) < 1e-4f, "luma invert flips");
        CHECK(std::fabs(fxLumaKeyAlpha(1.f, 0.f, 0.f, 0.25f, 1.f, 0.05f, false, true) - 0.0014f) < 1e-3f, "luma weighted red is dark");
        CHECK(std::fabs(fxLumaKeyAlpha(1.f, 0.f, 0.f, 0.25f, 1.f, 0.05f, false, false) - 1.f) < 1e-6f, "luma uniform red passes");
        // Replace Color: the old colour itself is fully replaced; alpha 0 keeps the pixel.
        fxReplaceColorMap(0.f, 0.f, 0.f, 0, 0, 0, 1, 1, 1, 0.28f, 0.61f, 1.f, false, &r, &g, &b);
        CHECK(std::fabs(r - 1.f) < 1e-6f && std::fabs(g - 1.f) < 1e-6f && std::fabs(b - 1.f) < 1e-6f, "replace colour exact match");
        fxReplaceColorMap(0.37f, 0.72f, 0.85f, 0, 0, 0, 1, 1, 1, 0.28f, 0.61f, 0.f, false, &r, &g, &b);
        CHECK(std::fabs(r - 0.37f) < 1e-6f && std::fabs(b - 0.85f) < 1e-6f, "replace colour alpha 0 keeps");
        // The jj corpus profile (old black -> new black, threshold 1.5, feather 2, luminance
        // locked): a mid pixel keeps its luma and loses chroma in proportion to p = 0.781.
        fxReplaceColorMap(0.37f, 0.72f, 0.85f, 0, 0, 0, 0, 0, 0, 1.5f, 2.f, 1.f, true, &r, &g, &b);
        CHECK(std::fabs(r - 0.573189f) < 1e-4f && std::fabs(g - 0.649841f) < 1e-4f && std::fabs(b - 0.678332f) < 1e-4f, "replace colour luminance lock");
        // Four Color Gradient: at colour 1's anchor the colour is colour 1; at the centre of the
        // default anchors all four distances are equal, so the colour is the plain average.
        {
            float cols[12] = {1, 0, 0, 0, 1, 0, 0, 0, 1, 1, 0, 1};
            float offs[8] = {0, -500, 500, -500, 500, 0, 0, 0};
            fxFourColorMap(0.f, 1.f, cols, offs, 1.f, 1.f, 0, 0.2f, 0.2f, 0.2f, 1.f, &r, &g, &b, &a);
            CHECK(std::fabs(r - 1.f) < 1e-6f && std::fabs(g) < 1e-6f && std::fabs(a - 1.f) < 1e-6f, "gradient at anchor 1");
            fxFourColorMap(0.5f, 0.5f, cols, offs, 1.f, 1.f, 0, 0.2f, 0.2f, 0.2f, 1.f, &r, &g, &b, &a);
            CHECK(std::fabs(r - 0.5f) < 1e-6f && std::fabs(g - 0.25f) < 1e-6f && std::fabs(b - 0.5f) < 1e-6f, "gradient centre is the average");
            fxFourColorMap(0.5f, 0.5f, cols, offs, 1.f, 0.f, 0, 0.2f, 0.4f, 0.6f, 0.8f, &r, &g, &b, &a);
            CHECK(std::fabs(r - 0.2f) < 1e-6f && std::fabs(a - 0.8f) < 1e-6f, "gradient alpha 0 keeps the layer");
        }
        // Simplex noise: deterministic, bounded, and the turbulence sum is built on it.
        CHECK(std::fabs(fxSnoise3(0.f, 0.f, 0.f) - -0.412199f) < 1e-4f, "snoise origin pin");
        CHECK(std::fabs(fxSnoise3(0.5f, 0.5f, 4.5f) - -0.280650f) < 1e-4f, "snoise pin (the corpus evolution)");
        CHECK(std::fabs(fxSnoise3(1.25f, -0.75f, 0.33f) - -0.786940f) < 1e-4f, "snoise pin 2");
        CHECK(std::fabs(fxSnoise3(-3.2f, 2.8f, -1.5f) - 0.656085f) < 1e-4f, "snoise pin 3");
        CHECK(fxSnoise3(0.5f, 0.5f, 4.5f) == fxSnoise3(0.5f, 0.5f, 4.5f), "snoise deterministic");
        {
            float mx = 0.f;
            for (int i = 0; i < 4000; ++i) {
                const float v = fxSnoise3(i * 0.37f - 100.f, i * 0.11f - 40.f, i * 0.53f);
                if (std::fabs(v) > mx) mx = std::fabs(v);
            }
            CHECK(mx <= 1.0f, "snoise bounded by 1 (max %g)", mx);
        }
        CHECK(std::fabs(fxTurbulence(0.5f, 0.5f, 1, 0.f, 0.f, 4.5f) - 0.5f * std::fabs(fxSnoise3(0.5f, 0.5f, 4.5f))) < 1e-6f,
              "turbulence octave 1 = 0.5 |snoise|");
        CHECK(std::fabs(fxTurbulence(0.3f, 0.7f, 3, 0.1f, -0.2f, 1.5f) - 0.237614f) < 1e-4f, "turbulence 3-octave pin");
        // Stretch Segment: the centre line is fixed; inside the stretch band the sample collapses
        // onto it; outside, it shifts by the full stretch amount.
        {
            float ux, uy;
            fxStretchSegmentUv(0.5f, 0.5f, 0, 900, 0, 0, &ux, &uy);
            CHECK(std::fabs(ux - 0.5f) < 1e-6f && std::fabs(uy - 0.5f) < 1e-6f, "stretch centre fixed");
            fxStretchSegmentUv(0.9f, 0.5f, 0, 900, 0, 0, &ux, &uy);
            CHECK(std::fabs(ux - 0.5f) < 1e-4f, "stretch 900 collapses to the centre line");
            fxStretchSegmentUv(0.9f, 0.5f, 0, 100, 0, 0, &ux, &uy);
            CHECK(std::fabs(ux - 0.7f) < 1e-4f, "stretch 100 shifts by the stretch (0.2)");
            fxStretchSegmentUv(0.5f, 0.9f, 90, 900, 0, 0, &ux, &uy);
            CHECK(std::fabs(ux - 0.5f) < 1e-4f && std::fabs(uy - 0.5f) < 1e-4f, "stretch at angle 90 collapses vertically");
        }
        // Swirl: the centre is fixed; a quarter out at strength 0.155 / radius 0.8 turns by
        // percent^2 * 2T * 8 pi; outside the radius nothing moves.
        {
            float ux, uy;
            fxSwirlUv(0.5f, 0.5f, 0.5f, 0.5f, 0.155f, 0.8f, 4, 1.f, &ux, &uy);
            CHECK(std::fabs(ux - 0.5f) < 1e-6f && std::fabs(uy - 0.5f) < 1e-6f, "swirl centre fixed");
            fxSwirlUv(0.75f, 0.5f, 0.5f, 0.5f, 0.155f, 0.8f, 4, 1.f, &ux, &uy);
            CHECK(std::fabs(ux - 0.285694f) < 1e-4f && std::fabs(uy - 0.371264f) < 1e-4f, "swirl quarter pin");
            fxSwirlUv(0.5f, 1.1f, 0.5f, 0.5f, -0.111f, 0.51f, 3, 1.f, &ux, &uy);
            CHECK(std::fabs(ux - 0.5f) < 1e-6f && std::fabs(uy - 1.1f) < 1e-6f, "swirl outside the radius is identity");
            fxSwirlUv(0.75f, 0.5f, 0.5f, 0.5f, 0.f, 0.8f, 4, 1.f, &ux, &uy);
            CHECK(std::fabs(ux - 0.75f) < 1e-6f && std::fabs(uy - 0.5f) < 1e-6f, "swirl strength 0 is identity");
        }
        // Halftone Dots: at the picture centre (a cell centre at amount 209.5 / angle 45) the
        // sample is the pixel itself; the colour decision runs from sample luma + cell distance.
        {
            float ux, uy, ax, ay;
            fxHalftoneDotsUv(0.5f, 0.5f, 209.5f, 45, 0.5f, 16.f / 9.f, &ux, &uy, &ax, &ay);
            CHECK(std::fabs(ux - 0.5f) < 1e-4f && std::fabs(uy - 0.5f) < 1e-4f && std::fabs(ax) < 1e-4f && std::fabs(ay) < 1e-4f,
                  "halftone centre samples itself");
            float c1[3] = {1, 0, 0}, c2[3] = {1, 1, 1};
            fxHalftoneDotsMap(0.05f, 0.05f, 0.05f, 1.f, 0.f, 0.f, 0.416f, c1, c2, 1.f, &r, &g, &b, &a);
            CHECK(std::fabs(r - 1.f) < 1e-6f && std::fabs(g) < 1e-6f, "halftone dark at cell centre = colour 1");
            fxHalftoneDotsMap(0.9f, 0.9f, 0.9f, 1.f, 0.f, 0.f, 0.416f, c1, c2, 1.f, &r, &g, &b, &a);
            CHECK(std::fabs(r - 1.f) < 1e-6f && std::fabs(g - 1.f) < 1e-6f, "halftone bright = colour 2");
            fxHalftoneDotsMap(0.9f, 0.9f, 0.9f, 1.f, 0.f, 0.f, 0.416f, c1, c2, 0.5f, &r, &g, &b, &a);
            CHECK(std::fabs(a - 0.5f) < 1e-6f && std::fabs(r - 0.5f) < 1e-6f, "halftone scales by the pixel alpha");
        }
        // Pixelate: the sample is the block centre (blocks of 25 px on a 1000 px picture,
        // anchored at the layer centre in layer mode).
        {
            float ux, uy, dc;
            fxPixelateCentre(0.53f, 0.47f, 25, 1, 1, 0, false, 0, 0, 1000, 1000, &ux, &uy, &dc);
            CHECK(std::fabs(ux - 0.5375f) < 1e-5f && std::fabs(uy - 0.4625f) < 1e-5f, "pixelate block centre");
            CHECK(std::fabs(dc - 0.387266f) < 1e-4f, "pixelate vignette factor");
            fxPixelateCentre(0.51f, 0.51f, 25, 1, 1, 0, false, 0, 0, 1000, 1000, &ux, &uy, &dc);
            CHECK(std::fabs(ux - 0.5125f) < 1e-5f && std::fabs(uy - 0.5125f) < 1e-5f, "pixelate block centre near middle");
        }
    }
    // v164: the parked tier (75..89). Shaded: colour modes 18/19/20, blur2 mode 8, warp
    // modes 15/16/17, style mode 14; chroma key / soft glow / fill behind are dedicated
    // passes (FXG_NONE). EXT types (77 / 85 / 87 / 89) fold into their owner's q[].
    {
        for (int t : {FX_CHROMA_KEY_AM, FX_COLOR_BALANCE_AM, FX_COLOR_TEMP_AM, FX_COLOR_TINT_AM,
                      FX_SPIN_BLUR_AM, FX_OFFSET_AM, FX_RADIAL_WIPE_AM, FX_FIND_EDGES_AM,
                      FX_HEX_SHIFT_AM, FX_SOFT_GLOW_AM, FX_FILL_BEHIND_AM}) {
            float one[8] = {(float)t, 0, 0, 0, 0, 0, 0, 0};
            CHECK(fxParseOps(one, 8, ops) == 1 && ops[0].type == t, "v164 type %d parsed", t);
        }
        CHECK(fxShaderOf(FX_COLOR_BALANCE_AM).group == FXG_COLOR && fxShaderOf(FX_COLOR_BALANCE_AM).mode == 18, "colour balance shader");
        CHECK(fxShaderOf(FX_COLOR_TEMP_AM).group == FXG_COLOR && fxShaderOf(FX_COLOR_TEMP_AM).mode == 19, "colour temperature shader");
        CHECK(fxShaderOf(FX_COLOR_TINT_AM).group == FXG_COLOR && fxShaderOf(FX_COLOR_TINT_AM).mode == 20, "colour tint shader");
        CHECK(fxShaderOf(FX_SPIN_BLUR_AM).group == FXG_BLUR2 && fxShaderOf(FX_SPIN_BLUR_AM).mode == 8, "spin blur shader");
        CHECK(fxShaderOf(FX_OFFSET_AM).group == FXG_WARP && fxShaderOf(FX_OFFSET_AM).mode == 15, "offset shader");
        CHECK(fxShaderOf(FX_RADIAL_WIPE_AM).group == FXG_WARP && fxShaderOf(FX_RADIAL_WIPE_AM).mode == 16, "radial wipe shader");
        CHECK(fxShaderOf(FX_FIND_EDGES_AM).group == FXG_STYLE && fxShaderOf(FX_FIND_EDGES_AM).mode == 14, "find edges shader");
        CHECK(fxShaderOf(FX_HEX_SHIFT_AM).group == FXG_WARP && fxShaderOf(FX_HEX_SHIFT_AM).mode == 17, "hex shift shader");
        for (int t : {FX_CHROMA_KEY_AM, FX_SOFT_GLOW_AM, FX_FILL_BEHIND_AM}) {
            CHECK(fxShaderOf(t).group == FXG_NONE, "v164 type %d is a dedicated pass", t);
        }
        for (int t = FX_DIR_BLUR; t <= FX_LAST; ++t) {
            CHECK(!(fxShaderOf(t).group == FXG_WARP && fxShaderOf(t).mode >= 15 && fxShaderOf(t).mode <= 17), "type %d collides with a v164 warp mode", t);
            CHECK(!(fxShaderOf(t).group == FXG_COLOR && fxShaderOf(t).mode >= 18 && fxShaderOf(t).mode <= 20), "type %d collides with a v164 colour mode", t);
            CHECK(!(fxShaderOf(t).group == FXG_BLUR2 && fxShaderOf(t).mode == 8), "type %d collides with spin blur", t);
            CHECK(!(fxShaderOf(t).group == FXG_STYLE && fxShaderOf(t).mode == 14), "type %d collides with find edges", t);
        }
        // EXT folding for the new owners.
        {
            float cb[16] = {(float)FX_COLOR_BALANCE_AM, 100, -100, 100, 0, 0, 0, 0,
                            (float)FX_COLOR_BALANCE_AM_EXT, 0, 0, 1, 0, 0, 0, 0};
            CHECK(fxParseOps(cb, 16, ops) == 1 && ops[0].q[2] == 1.f, "colour balance ext folded");
            float hex[16] = {(float)FX_HEX_SHIFT_AM, 1, 127, 9, 100, 3508, 0.1f, 1,
                             (float)FX_HEX_SHIFT_AM_EXT, 0, 8390656, 0, 0, 0, 0, 0};
            CHECK(fxParseOps(hex, 16, ops) == 1 && ops[0].q[1] == 8390656.f, "hex shift ext folded");
            float orphan[8] = {(float)FX_SOFT_GLOW_AM_EXT, 0, 0, 0, 0, 0, 0, 0};
            CHECK(fxParseOps(orphan, 8, ops) == 0, "soft glow ext alone dropped");
        }
        // Identity rules.
        FxOp cb0{FX_COLOR_BALANCE_AM, {0, 0, 0, 0, 0, 0, 0}, {0, 0, 1, 0, 0, 0, 0}};
        CHECK(fxIsIdentity(cb0), "colour balance zero bands identity");
        cb0.p[0] = 100;
        CHECK(!fxIsIdentity(cb0), "colour balance band runs");
        FxOp ct0{FX_COLOR_TEMP_AM, {6.5f, 0, 0, 0, 0, 0, 0}};
        CHECK(fxIsIdentity(ct0), "colour temperature strength 0 identity");
        FxOp rw0{FX_RADIAL_WIPE_AM, {0, 0, 0, 1, 8390656, 0, 0}};
        CHECK(fxIsIdentity(rw0), "radial wipe progress 0 identity");
        rw0.p[0] = 360;
        CHECK(!fxIsIdentity(rw0), "radial wipe progress 360 runs");
        FxOp off0{FX_OFFSET_AM, {0, 0, 50, 1, 0, 0, 0}};
        CHECK(fxIsIdentity(off0), "offset zero identity");
        FxOp hs0{FX_HEX_SHIFT_AM, {0, 127, 9, 100, 3508, 0.1f, 1}};
        CHECK(fxIsIdentity(hs0), "hex shift strength 0 identity");
        FxOp sg0{FX_SOFT_GLOW_AM, {50, 100, 70, 100, 16777215, 100, 0}};
        CHECK(fxIsIdentity(sg0), "soft glow alpha 0 identity");
        // Chroma key: an exact key-colour pixel keys out; white against a black key stays.
        float oR, oG, oB, oA;
        fxChromaKeyAmMap(0, 0, 0, 1, 0, 0, 0, 0.1f, 0.05f, false, false, false, &oR, &oG, &oB, &oA);
        CHECK(oA == 0.f, "chroma key removes the key colour");
        fxChromaKeyAmMap(1, 1, 1, 1, 0, 0, 0, 0.1f, 0.05f, false, false, false, &oR, &oG, &oB, &oA);
        CHECK(oA == 1.f && oR == 1.f, "chroma key keeps a far colour");
        fxChromaKeyAmMap(1, 1, 1, 1, 0, 0, 0, 0.1f, 0.05f, false, true, false, &oR, &oG, &oB, &oA);
        CHECK(oA == 0.f, "chroma key invert flips the far colour out");
        // Colour temperature: 6500 K is nearly white (the fit's pivot); 1000 K is deep orange.
        float gR, gG, gB;
        fxColorTempAmGain(6500.f, &gR, &gG, &gB);
        CHECK(std::fabs(gR - 1.f) < 1e-6f && std::fabs(gG - 0.975f) < 0.002f && std::fabs(gB - 0.9894f) < 0.002f, "kelvin 6500 gain near white");
        fxColorTempAmGain(1000.f, &gR, &gG, &gB);
        CHECK(std::fabs(gR - 1.f) < 0.002f && std::fabs(gG - 0.2431f) < 0.002f && gB == 0.f, "kelvin 1000 gain");
        // Colour balance: zero bands = identity (up to the YUV roundtrip).
        float zeroBands[9] = {0, 0, 0, 0, 0, 0, 0, 0, 0};
        fxColorBalanceAmMap(0.3f, 0.5f, 0.7f, zeroBands, true, &oR, &oG, &oB);
        CHECK(std::fabs(oR - 0.3f) < 0.002f && std::fabs(oG - 0.5f) < 0.002f && std::fabs(oB - 0.7f) < 0.002f, "colour balance zero is identity");
        // Colour tint: grey + full red tint (hue 0, strength 1) -> the def's numbers.
        fxColorTintAmMap(0.5f, 0.5f, 0.5f, 1, 0, 1, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.8505f) < 0.002f && std::fabs(oG - 0.3505f) < 0.002f && std::fabs(oB - 0.3504f) < 0.002f, "colour tint red on grey");
        // Offset feather: zero at the seam when feathered, partial a quarter into a segment.
        CHECK(fxOffsetFeather(0.5f, 0.5f, 500, 0, 1) == 0.f, "offset feather seam");
        CHECK(std::fabs(fxOffsetFeather(0.75f, 0.5f, 500, 0, 1) - 0.15625f) < 1e-5f, "offset feather quarter in");
        // Offset composite: featherAmt 1 shows only the shifted sample; 0 mixes it over S.
        fxOffsetAmMap(0.2f, 0.2f, 0.2f, 0.5f, 0.6f, 0.6f, 0.6f, 1, 1, 1, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.6f) < 1e-5f && oA == 1.f, "offset feathered composite");
        fxOffsetAmMap(0.2f, 0.2f, 0.2f, 0.5f, 0.6f, 0.6f, 0.6f, 1, 0, 1, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.5f) < 1e-5f && oA == 1.f, "offset unfeathered composite");
        // Radial wipe at 180 degrees, offset 0: one side of the sweep is kept, the other cut.
        CHECK(fxRadialWipeAmPp(-1, 0, 180, 0, 0, 1, 0) == 1.f, "radial wipe keeps one side at 180");
        CHECK(fxRadialWipeAmPp(1, 0, 180, 0, 0, 1, 0) == 0.f, "radial wipe cuts the other side at 180");
        CHECK(fxRadialWipeAmPp(0.3f, 0.7f, 0, 0, 0, 1, 0) == 1.f, "radial wipe progress 0 keeps all");
        CHECK(fxRadialWipeAmPp(0.3f, 0.7f, 360, 0, 0, 1, 0) == 0.f, "radial wipe progress 360 cuts all");
        // Find edges: a flat field has no gradient; a hard step saturates it.
        float flat8[8] = {0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f};
        CHECK(fxFindEdgesAmG(flat8, 0) == 0.f, "find edges flat field");
        float step8[8] = {0, 0, 1, 0, 1, 0, 0, 1};
        CHECK(fxFindEdgesAmG(step8, 0) == 1.f, "find edges hard step");
        // Hex shift: strength 0 samples the pixel itself.
        float mX, mY;
        fxHexShiftAmUv(0.3f, 0.7f, 127, 0.09f, 1, 3508.5f, 0.1f, 0, 0, 0, 1.f, &mX, &mY);
        CHECK(std::fabs(mX - 0.3f) < 1e-5f && std::fabs(mY - 0.7f) < 1e-5f, "hex shift strength 0 identity");
        // Soft glow: grey over grey at blend 0 screens to 0.75.
        fxSoftGlowAmMap(0.5f, 0.5f, 0.5f, 1, 0.5f, 0.5f, 0.5f, 1, 1, 0, 0, 1, 1, 1, 0, 1, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.75f) < 1e-4f && oA == 1.f, "soft glow screen blend");
        // Fill behind: a straight-grey blur at gamma 1 / no tint keeps its colour.
        fxFillBehindAmBg(0.25f, 0.25f, 0.25f, 0.5f, 0, 0, 0, 0, 0, 1, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.25f) < 0.002f && std::fabs(oA - 0.5f) < 1e-5f, "fill behind bg passthrough");
    }
    printf("%d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
