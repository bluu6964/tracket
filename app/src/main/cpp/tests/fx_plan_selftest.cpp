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
    printf("%d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
