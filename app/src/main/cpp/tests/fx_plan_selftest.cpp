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
    // 0. fxFirstTarget: a chained pass must never stage into the target its input lives in
    // (round-7 regression: an overlay's own-mask pass chained after its effect pass erased
    // every layer whose chain returned the picture to fx target 0 — the Graph bars).
    CHECK(fxFirstTarget(7, 7) == 1, "input is target 0 -> stage into 1");
    CHECK(fxFirstTarget(8, 7) == 0, "input is target 1 -> stage into 0");
    CHECK(fxFirstTarget(42, 7) == 0, "input is a plain texture -> stage into 0");
    CHECK(fxFirstTarget(0, 7) == 0, "no input -> stage into 0");
    CHECK(fxFirstTarget(7, 0) == 0, "no target 0 yet -> stage into 0");
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
    float flat[] = {1, 40, 0, 0, 0, 0, 0, 0, 999, 1, 1, 1, 1, 1, 0, 0, 3, 30, 90, 50, 0, 0, 0, 0};
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
        // The corpus's largest authored stack is 29 ops (blxze-brepizx-pack, build 130): it must
        // parse whole — the bound covers it with headroom (Queue 14h).
        float big[40 * kFxStride] = {0};
        for (int i = 0; i < 40; ++i) big[i * kFxStride] = (float)FX_GLITCH;
        FxOp bigOps[kFxMaxOps];
        CHECK(fxParseOps(big, 29 * kFxStride, bigOps) == 29, "29-op corpus stack parses whole");
        CHECK(kFxMaxOps >= 29, "op bound covers the corpus max stack");
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
        // The pre-blur calibration (v176): the host gaussianblur kernel at the frame
        // diagonal — smoothing % is AM strength x 100, sigma = strength x diag x
        // 0.04427 (diag 2202.9072 = 1920x1080). No cap any more (400% -> 390.08).
        CHECK(std::fabs(fxGlowScanBlurSigma(5.f, 2202.9072f) - 4.876037f) < 1e-3f, "glow scan blur sigma 0.05");
        CHECK(std::fabs(fxGlowScanBlurSigma(10.f, 2202.9072f) - 9.752073f) < 1e-3f, "glow scan blur sigma 0.10");
        CHECK(std::fabs(fxGlowScanBlurSigma(400.f, 2202.9072f) - 390.083f) < 5e-2f, "glow scan blur sigma uncapped");
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
            // EXT groups do not consume op slots: 11 plain + gradient(+ext) + 1 plain = 13 ops,
            // all kept (the array bound is 32 since build 130; under the old 12 this same array
            // filled the array exactly, which is how the check was written then).
            float many[14 * 8];
            for (int i = 0; i < 11; ++i) { many[i * 8] = (float)FX_BLUR; many[i * 8 + 1] = 10; for (int k = 2; k < 8; ++k) many[i * 8 + k] = 0; }
            many[11 * 8] = (float)FX_FOUR_COLOR_GRADIENT; for (int k = 1; k < 8; ++k) many[11 * 8 + k] = 0;
            many[12 * 8] = (float)FX_FOUR_COLOR_GRADIENT_EXT; for (int k = 1; k < 8; ++k) many[12 * 8 + k] = 0;
            many[13 * 8] = (float)FX_GRAIN; for (int k = 1; k < 8; ++k) many[13 * 8 + k] = 0;
            CHECK(fxParseOps(many, 14 * 8, ops) == 13, "ext does not consume an op slot");
            CHECK(ops[11].type == FX_FOUR_COLOR_GRADIENT && ops[12].type == FX_GRAIN, "ops after a folded ext stay in order");
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
        // Dark glow (build 120, the def's two pixel stages; expected values hand-computed
        // from def_darkglow.xml): the extract keeps dark pixels (luma 0.1177 < 0.3), at
        // blend 0 unchanged, at blend 0.25 mixed a quarter toward colour x alpha.
        fxDarkGlowExtract(0.2f, 0.1f, 0.05f, 1, 0.3f, 1, 85.f / 255.f, 102.f / 255.f, 0, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.2f) < 1e-5f && std::fabs(oG - 0.1f) < 1e-5f && oA == 1.f, "dark glow extract blend 0");
        fxDarkGlowExtract(0.2f, 0.1f, 0.05f, 1, 0.3f, 1, 85.f / 255.f, 102.f / 255.f, 0.25f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.4f) < 1e-4f && std::fabs(oG - 0.158333f) < 1e-4f && std::fabs(oB - 0.1375f) < 1e-4f, "dark glow extract blend");
        fxDarkGlowExtract(0.9f, 0.9f, 0.9f, 1, 0.3f, 1, 0, 0, 0.25f, &oR, &oG, &oB, &oA);
        CHECK(oA == 0.f && oR == 0.f, "dark glow extract drops bright pixels");
        fxDarkGlowExtract(0.1f, 0.05f, 0.02f, 0.5f, 0.3f, 1, 85.f / 255.f, 102.f / 255.f, 0.25f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.2f) < 1e-4f && std::fabs(oG - 0.079167f) < 1e-4f && std::fabs(oA - 0.5f) < 1e-5f, "dark glow extract translucent");
        // The composite: straight colours multiply, over the base at min(a) * alpha = 0.75.
        fxDarkGlowComposite(0.2f, 0.1f, 0.05f, 1, 0.4f, 0.158333f, 0.1375f, 1, 0.75f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.11f) < 1e-4f && std::fabs(oG - 0.036875f) < 1e-4f && std::fabs(oB - 0.017656f) < 1e-4f && oA == 1.f, "dark glow composite");
        fxDarkGlowComposite(0.2f, 0.1f, 0.05f, 1, 0, 0, 0, 0, 0.75f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.2f) < 1e-5f && oA == 1.f, "dark glow composite empty extract");
        // The kernel: strength 0.25 over a 1080x1920 scene -> sigma ~24.4 full-res px.
        CHECK(std::fabs(fxDarkGlowSigma(0.25f, 2202.9f) - 24.38f) < 0.05f, "dark glow sigma (%g)", fxDarkGlowSigma(0.25f, 2202.9f));
        CHECK(fxDarkGlowSigma(0, 2202.9f) == 0.f, "dark glow sigma 0");
        { FxOp dg{FX_DARK_GLOW_AM, {25, 30, 0xFF5566, 25, 75}};
          CHECK(!fxIsIdentity(dg), "dark glow defaults run");
          dg.p[4] = 0; CHECK(fxIsIdentity(dg), "dark glow alpha 0 is neutral");
          dg.p[4] = 75; dg.p[1] = 0; CHECK(fxIsIdentity(dg), "dark glow threshold 0 is neutral"); }
    }
    // ---- v165: tier 3 (AM defs, hunt round 3). Every expected value below was computed
    // by an independent port of the recovered def formulas (expected_t3.py), not by the
    // twins themselves. ----
    {
        float x, y, p;
        bool in;
        // Wave Warp (wavewarp2)
        fxWaveWarpAmUv(0.3f, 0.7f, 0.25f, 30, 20, 40, 15, 0.5f, -0.3f, 0.4f, &x, &y);
        CHECK(std::fabs(x - 0.305967f) < 2e-3f && std::fabs(y - 0.694033f) < 2e-3f, "wavewarp pin (%g %g)", x, y);
        fxWaveWarpAmUv(0.85f, 0.15f, 0.f, 90, 5, 25, -30, -0.8f, 0.6f, 0.1f, &x, &y);
        CHECK(std::fabs(x - 0.801892f) < 2e-3f && std::fabs(y - 0.233326f) < 2e-3f, "wavewarp pin 2 (%g %g)", x, y);
        // Circular Ripple (circularripple2 = circularripple)
        fxCircularRippleAmUv(0.7f, 0.3f, 0.2f, 0.6f, 3, 0.25f, 0.1f, 100, -50, 16.f / 9.f, &x, &y);
        CHECK(std::fabs(x - 0.704564f) < 2e-3f && std::fabs(y - 0.288590f) < 2e-3f, "ripple pin (%g %g)", x, y);
        fxCircularRippleAmUv(0.5f, 0.5f, 0.5f, 1.f, 2, 0.5f, 0.f, 0, 0, 1.f, &x, &y);
        CHECK(x == 0.5f && y == 0.5f, "ripple centre still");
        // Stretch (stretch2 / stretch)
        fxStretchAmUv(0.8f, 0.2f, 2.f, 30.f, &x, &y);
        CHECK(std::fabs(x - 0.752452f) < 2e-3f && std::fabs(y - 0.172548f) < 2e-3f, "stretch pin (%g %g)", x, y);
        fxStretchAmUv(0.33f, 0.66f, 1.f, 77.f, &x, &y);
        CHECK(std::fabs(x - 0.33f) < 1e-4f && std::fabs(y - 0.66f) < 1e-4f, "stretch scale 1 identity");
        float oR, oG, oB, oA;
        fxStretchAmMap(0.2f, 0.1f, 0.05f, 0.5f, 0.4f, 0.3f, 0.2f, 0.8f, 2, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.16f) < 1e-5f && std::fabs(oA - 0.4f) < 1e-5f, "stretch v2 contentOnly masks by T.a");
        fxStretchAmMap(0.2f, 0.1f, 0.05f, 0.5f, 0.4f, 0.3f, 0.2f, 0.8f, 1, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.4f) < 1e-5f && std::fabs(oA - 0.88f) < 1e-5f, "stretch v1 composites T under");
        // Squeeze
        fxSqueezeAmUv(0.75f, 0.25f, 0.8f, &x, &y);
        CHECK(std::fabs(x - 0.857143f) < 2e-3f && std::fabs(y - 0.307692f) < 2e-3f, "squeeze pin (%g %g)", x, y);
        fxSqueezeAmUv(0.1f, 0.9f, -1.5f, &x, &y);
        CHECK(std::fabs(x - 0.185039f) < 2e-3f && std::fabs(y - 1.047945f) < 2e-3f, "squeeze negative pin (%g %g)", x, y);
        // Value noise / fbm + Fractal Warp (fractalwarp4)
        CHECK(std::fabs(fxValueNoise(1.3f, 2.7f) - 0.770638f) < 3e-3f, "value noise pin");
        CHECK(std::fabs(fxValueNoise(-0.5f, 4.25f) - 0.165649f) < 3e-3f, "value noise pin 2");
        fxFractalWarpAmUv(0.4f, 0.6f, 0.3f, 1.5f, 0.6f, 3, 100, -200, 50, 25, 1.5f, &x, &y);
        CHECK(std::fabs(x - 0.428589f) < 3e-3f && std::fabs(y - 0.568801f) < 3e-3f, "fractal warp pin (%g %g)", x, y);
        // Stripes
        const float band = fxStripesAmBand(0.3f, 0.7f, 800, 600, 4, 0.5f, 20, 0.1f, 0.1f);
        CHECK(std::fabs(band - 0.783311f) < 2e-3f, "stripes band pin (%g)", band);
        fxStripesAmMap(0.4f, 0.3f, 0.2f, 0.8f, 1.f, 0.f, 0.f, 0.5f, false, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.64f) < 1e-5f && std::fabs(oA - 0.88f) < 1e-5f, "stripes compose");
        fxStripesAmMap(0.4f, 0.3f, 0.2f, 0.8f, 1.f, 0.f, 0.f, 0.5f, true, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.2f) < 1e-5f && std::fabs(oA - 0.4f) < 1e-5f, "stripes punchout");
        // Turbulent Displace 3 (directions XY / phi / R)
        fxTurbulentDisplace3AmUv(0.35f, 0.65f, 0.15f, 2, 0.7f, 3, 2, 3, 0.5f, 100, -50, 10, 20, 1.2f, 0.8f, false, 1.5f, &x, &y, &in);
        CHECK(std::fabs(x - 0.470030f) < 5e-3f && std::fabs(y - 0.777765f) < 5e-3f && in, "turb3 XY pin (%g %g)", x, y);
        fxTurbulentDisplace3AmUv(0.35f, 0.65f, 0.15f, 2, 0.7f, 3, 5, 3, 0.5f, 100, -50, 10, 20, 1.2f, 0.8f, true, 1.5f, &x, &y, &in);
        CHECK(std::fabs(x - 0.269863f) < 5e-3f && std::fabs(y - 0.687149f) < 5e-3f, "turb3 phi pin (%g %g)", x, y);
        fxTurbulentDisplace3AmUv(0.8f, 0.2f, 0.2f, 1, 0.f, 0, 3, 2, 0.5f, 0, 0, 0, 0, 1.f, 1.f, true, 1.f, &x, &y, &in);
        CHECK(std::fabs(x - 0.888809f) < 5e-3f && std::fabs(y - 0.111191f) < 5e-3f && in, "turb3 R pin (%g %g)", x, y);
        // Roughen Edges
        fxRoughenEdgesAmUv(0.2f, 0.8f, 1.2f, 0.8f, 0.3f, 2, 3, 1.5f, &x, &y, &p);
        CHECK(std::fabs(x - 0.188021f) < 5e-3f && std::fabs(y - 0.811979f) < 5e-3f, "roughen pin (%g %g)", x, y);
        // Streaks
        float vx, vy;
        int ns;
        fxStreaksAmVelocity(0.5f, 45, 1.f / 1920, 1.f / 1080, 1080.f / 1920, &vx, &vy, &ns);
        CHECK(std::fabs(vx - 0.198874f) < 2e-3f && std::fabs(vy - (-0.353553f)) < 2e-3f && ns == 100, "streaks velocity pin (%g %g %d)", vx, vy, ns);
        fxStreaksAmVelocity(0.001f, 0, 1.f / 100, 1.f / 100, 1.f, &vx, &vy, &ns);
        CHECK(ns == 1, "streaks tiny strength clamps to 1 sample");
        CHECK(fxStreaksAmCombine(0.5f, 0.2f, 0) == 0.2f && fxStreaksAmCombine(0.5f, 0.2f, 1) == 0.5f &&
              fxStreaksAmCombine(0.5f, 0.2f, 2) == 0.5f && std::fabs(fxStreaksAmCombine(0.5f, 0.2f, 3) - 0.7f) < 1e-6f,
              "streaks combine modes");
        CHECK(std::fabs(fxStreaksAmFinish(3.f, 3, 10) - 0.3f) < 1e-6f && fxStreaksAmFinish(3.f, 1, 10) == 3.f,
              "streaks finish divides only the average mode");
        // Colour Hot
        const float cd[3] = {200, 0.8f, 0.2f}, td[3] = {40, 0.9f, -0.1f};
        fxColorHotAmMap(0.3f, 0.15f, 0.05f, cd, td, &oR, &oG, &oB);
        CHECK(std::fabs(oR - 0.139178f) < 3e-3f && std::fabs(oG - 0.263775f) < 3e-3f && std::fabs(oB - 0.326077f) < 3e-3f,
              "colorhot pin (%g %g %g)", oR, oG, oB);
        // Colorize
        fxColorizeAmMap(0.5f, 0.25f, 0.1f, 0.8f, 120, 0.7f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.227923f) < 3e-3f && std::fabs(oG - 0.397924f) < 3e-3f && std::fabs(oB - 0.051929f) < 3e-3f && oA == 0.8f,
              "colorize pin (%g %g %g)", oR, oG, oB);
        // Highlights & Shadows
        fxHighlightShadowAmMap(0.4f, 0.2f, 0.1f, 1.5f, 1.4f, &oR, &oG, &oB);
        CHECK(std::fabs(oR - 0.460474f) < 3e-3f && std::fabs(oG - 0.230455f) < 3e-3f && std::fabs(oB - 0.115446f) < 3e-3f,
              "highlightshadow pin (%g %g %g)", oR, oG, oB);
        fxHighlightShadowAmMap(0.f, 0.f, 0.f, 1.5f, 1.4f, &oR, &oG, &oB);
        CHECK(std::fabs(oR - (-0.00625f)) < 2e-3f && std::fabs(oG - (-0.00625f)) < 2e-3f, "highlightshadow black: guarded division, white mix still applies");
        // Spot Colour
        fxSpotColorAmMap(0.9f, 0.1f, 0.05f, 1.f, 1, 0, 0, 0.15f, 0.05f, 1.f, 0.1f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.9f) < 1e-4f && std::fabs(oG - 0.1f) < 1e-4f, "spotcolour near keeps colour");
        fxSpotColorAmMap(0.1f, 0.8f, 0.2f, 1.f, 1, 0, 0, 0.15f, 0.05f, 1.f, 0.1f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.501185f) < 3e-3f && std::fabs(oG - 0.536185f) < 3e-3f && std::fabs(oB - 0.506185f) < 3e-3f,
              "spotcolour far desaturates (%g %g %g)", oR, oG, oB);
        // Dissolve
        CHECK(std::fabs(fxDissolveAmHash(0.333f, 0.777f, 640, 360, 2.5f) - 0.121094f) < 0.02f, "dissolve hash pin");
        // The hash's sine argument is ~10^3 rad, so its exact value is chaotic across
        // float32 sine implementations (AM's own pattern differs GPU to GPU the same
        // way; the dissolved fraction is what matters). Pin the structure instead:
        // range, determinism, constancy inside a pixel cell, seed sensitivity.
        const float dh = fxDissolveAmHash(0.9f, 0.1f, 1920, 1080, 0.5f);
        CHECK(dh >= 0.f && dh < 1.f && dh == fxDissolveAmHash(0.9f, 0.1f, 1920, 1080, 0.5f), "dissolve hash range + deterministic");
        CHECK(fxDissolveAmHash(0.9001f, 0.1001f, 1920, 1080, 0.5f) == fxDissolveAmHash(0.9003f, 0.1003f, 1920, 1080, 0.5f),
              "dissolve hash constant inside a pixel cell");
        CHECK(fxDissolveAmHash(0.9f, 0.1f, 1920, 1080, 0.6f) != dh, "dissolve hash follows the seed");
        // Simple Stars
        const float sf = fxSimpleStarsAmField(0.3f, 0.7f, 1.f, 25, 1.f, 20, 100, -50, 90, 1.5f);
        CHECK(std::fabs(sf - 0.213566f) < 3e-3f, "simplestars field pin (%g)", sf);
        float ar, ag, ab, aa;
        fxSimpleStarsAmAdj(0.2f, 0.4f, 0.6f, 0.5f, 1.2f, &ar, &ag, &ab, &aa);
        CHECK(std::fabs(ar - 0.11475f) < 2e-3f && std::fabs(aa - 0.5f) < 1e-5f, "simplestars adj pin (the def applies p twice) (%g %g)", ar, aa);
        fxSimpleStarsAmMap(0.4f, 0.3f, 0.2f, 0.8f, ar, ag, ab, aa, 0.5f, 2, 0.75f, &oR, &oG, &oB, &oA);
        CHECK(oR >= 0.f && oR <= 1.f && std::fabs(oA - 0.8f) < 1e-5f, "simplestars screen map keeps alpha");
        fxSimpleStarsAmMap(0.4f, 0.3f, 0.2f, 0.8f, ar, ag, ab, aa, 0.5f, 3, 1.f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.2f) < 1e-5f && std::fabs(oA - 0.4f) < 1e-5f, "simplestars punchout");
        // Dots
        float inten, cpct;
        fxDotsAmField(0.35f, 0.65f, 1.f, 0.f, 0.5f, 0.5f, 12, 1, 0.9f, 0, 0, 1.5f, &inten, &cpct);
        CHECK(std::fabs(inten - 1.f) < 3e-3f && std::fabs(cpct - 0.222656f) < 3e-3f, "dots field pin (%g %g)", inten, cpct);
        fxDotsAmField(0.8f, 0.2f, 2.f, 0.25f, 0.7f, 0.2f, 5, 3, 1.f, 100, -100, 1.f, &inten, &cpct);
        CHECK(std::fabs(inten - 0.062750f) < 3e-3f && std::fabs(cpct - 0.320313f) < 3e-3f, "dots field pin 2 (%g %g)", inten, cpct);
        // Ridges v2 / v1
        CHECK(std::fabs(fxRidgesAmField(0.3f, 0.6f, 0.8f, 1.5f, 0.4f, 2, 4, 50, -25, 10, 20, 1.2f, 0.7f, 2, 1.5f) - 0.265642f) < 5e-3f,
              "ridges v2 pin");
        CHECK(std::fabs(fxRidgesAmField(0.3f, 0.6f, 0.8f, 1.5f, 0.4f, 2, 4, 50, -25, 10, 20, -0.3f, 0.f, 1, 1.5f) - 0.679323f) < 5e-3f,
              "ridges v1 pin");
        // Bend Warp
        fxBendWarpAmUv(0.5f, 0.8f, 45, 0.5f, 3, 0, 0.75f, 0.25f, 1.5f, &x, &y, &in);
        CHECK(std::fabs(x - 0.545781f) < 3e-3f && std::fabs(y - 0.779790f) < 3e-3f && in, "bend pin (%g %g)", x, y);
        fxBendWarpAmUv(0.5f, 0.2f, 45, 0.5f, 3, 0, 0.75f, 0.25f, 1.5f, &x, &y, &in);
        CHECK(x == 0.5f && y == 0.2f, "bend before-start freeze");
        fxBendWarpAmUv(0.8f, 0.4f, -30, 0.3f, 0, 1, 0.75f, 0.25f, 1.f, &x, &y, &in);
        CHECK(std::fabs(x - 0.813410f) < 3e-3f && std::fabs(y - 0.497944f) < 3e-3f, "bend horizontal pin (%g %g)", x, y);
        // Displace Map
        CHECK(std::fabs(fxDisplaceMapAmPick(0.7f, 0.2f, 0.3f, 0) - (0.7f * 0.2126f + 0.2f * 0.7152f + 0.3f * 0.0722f)) < 1e-5f &&
              fxDisplaceMapAmPick(0.7f, 0.2f, 0.3f, 2) == 0.2f, "displacemap pick");
        CHECK(fxDisplaceMapAmShape(0.75f, true, false) == 0.5f && fxDisplaceMapAmShape(0.75f, false, true) == 0.25f &&
              fxDisplaceMapAmShape(0.75f, true, true) == 0.5f, "displacemap shape (fromctr first, then invert, like the def)");
        fxDisplaceMapAmUv(0.4f, 0.6f, 100, -50, 0.5f, 0.25f, 0.8f, 1.5f, &x, &y);
        CHECK(std::fabs(x - 0.34f) < 1e-4f && std::fabs(y - 0.59f) < 1e-4f, "displacemap uv pin (%g %g)", x, y);
        float tint;
        fxDisplaceMapAmEdge(1.3f, -0.2f, 1, 0.7f, &x, &y, &tint, &in);
        CHECK(std::fabs(x - 0.7f) < 1e-4f && std::fabs(y - 0.2f) < 1e-4f && tint == 0.7f && in, "displacemap mirror edge");
        fxDisplaceMapAmEdge(1.3f, -0.2f, 0, 0.7f, &x, &y, &tint, &in);
        CHECK(x == 1.3f && tint == 1.f, "displacemap repeat edge keeps raw");
        fxDisplaceMapAmEdge(1.3f, 0.5f, 2, 0.7f, &x, &y, &tint, &in);
        CHECK(!in, "displacemap transparent edge outside");
        fxDisplaceMapRadAmUv(0.6f, 0.4f, 0.5f, 0.5f, 0, 0, 90, 0.f, 1.f, 1.f, 1.f, 1920, 1080, &x, &y);
        CHECK(std::fabs(x - 0.443750f) < 2e-3f && std::fabs(y - 0.322222f) < 2e-3f, "displacemaprad rotates 90 deg (%g %g)", x, y);
        // Smooth Bevel packing + lights
        float e0, e1;
        fxSmoothBevelAmEncode(1234.5f / 16383.f, &e0, &e1);
        CHECK(std::fabs(fxSmoothBevelAmDecode(e0, e1) * 16383.f - 1234.5f) < 1.f, "smoothbevel encode/decode roundtrip (normalised by MAX_RADIUS)");
        fxSmoothBevelAmEncode(0.f, &e0, &e1);
        CHECK(fxSmoothBevelAmDecode(e0, e1) == 0.f, "smoothbevel zero roundtrip");
        float lumS, lumD;
        fxSmoothBevelAmLights(0.5f, 0.5f, 0.5f, 0.5f, 135, -45, 0.15f, 0.35f, 35, &lumS, &lumD);
        CHECK(lumS < 1e-6f && std::fabs(lumD - 0.330f) < 0.01f, "smoothbevel flat centre faces -z (%g %g)", lumS, lumD);
        fxSmoothBevelAmLights(0.f, 1.f, 0.f, 1.f, 135, -45, 0.15f, 0.35f, 35, &lumS, &lumD);
        CHECK(lumS >= 0.f && lumD > 0.f, "smoothbevel slope catches the directional light (%g %g)", lumS, lumD);
        // Light Glow
        fxLightGlowAmPick(0.9f, 0.9f, 0.9f, 1.f, 0.5f, 0.25f, 1.f, 0.5f, 0.f, &oR, &oG, &oB, &oA);
        CHECK(oR > 0.9f && oA == 1.f, "lightglow pick bright pixel");
        fxLightGlowAmPick(0.1f, 0.1f, 0.1f, 1.f, 0.5f, 0.25f, 1.f, 0.5f, 0.f, &oR, &oG, &oB, &oA);
        CHECK(oR == 0.f && oA == 0.f, "lightglow pick dark pixel to black");
        CHECK(std::fabs(fxLightGlowAmKernel(50, 1920, 1080) - 5231.905f) < 1.f, "lightglow kernel pin");
        CHECK(std::fabs(fxLightGlowAmKernel(10, 640, 640) - 429.921f) < 0.5f, "lightglow kernel pin 2");
        CHECK(fxLightGlowAmKernel(0, 1920, 1080) == 0.f, "lightglow kernel zero strength");
        // inner glow (found by the full-corpus sweep, not the id lists)
        CHECK(std::fabs(fxInnerGlowAmStride(100.f) - 3.0199f) < 0.01f, "innerglow stride mid");
        CHECK(std::fabs(fxInnerGlowAmStride(1000.f) - 25.f) < 0.01f, "innerglow stride large");
        CHECK(std::fabs(fxInnerGlowAmP(100.f, 0.2f, 0.f) - 1.f) < 1e-4f, "innerglow p at the edge");
        CHECK(std::fabs(fxInnerGlowAmP(100.f, 0.2f, 50.f) - 0.68133f) < 0.001f, "innerglow p mid");
        CHECK(fxInnerGlowAmP(100.f, 0.2f, 200.f) == 0.f, "innerglow p deep inside");
        fxInnerGlowAmMap(0.4f, 0.2f, 0.1f, 0.8f, 1.f, 1.f, 1.f, 2, 0.75f, 1.f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.7f) < 1e-4f && std::fabs(oG - 0.65f) < 1e-4f &&
              std::fabs(oB - 0.625f) < 1e-4f && std::fabs(oA - 0.8f) < 1e-4f, "innerglow screen map");
        fxInnerGlowAmMap(0.4f, 0.2f, 0.1f, 0.8f, 0.25f, 0.5f, 0.75f, 0, 0.5f, 0.5f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.35f) < 1e-4f && std::fabs(oG - 0.25f) < 1e-4f &&
              std::fabs(oB - 0.225f) < 1e-4f, "innerglow normal map");
        fxInnerGlowAmMap(0.4f, 0.2f, 0.1f, 0.8f, 0.5f, 0.5f, 0.5f, 1, 1.f, 1.f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.2f) < 1e-4f && std::fabs(oG - 0.1f) < 1e-4f &&
              std::fabs(oB - 0.05f) < 1e-4f, "innerglow multiply map");
        {
            FxOp ig; ig.type = FX_INNER_GLOW_AM;
            ig.p[0] = 0.4f; ig.p[1] = 0.75f;
            CHECK(fxIsIdentity(ig), "innerglow radius < 0.5 is identity");
            ig.p[0] = 50.f; ig.p[1] = 0.f;
            CHECK(fxIsIdentity(ig), "innerglow alpha 0 is identity");
            ig.p[1] = 0.75f;
            CHECK(!fxIsIdentity(ig), "innerglow live is not identity");
            FxShaderRef sr2 = fxShaderOf(FX_INNER_GLOW_AM);
            CHECK(sr2.group == FXG_NONE, "innerglow is a dedicated pass");
        }
        // parse folds + shader routing + identity for the new types
        float flat2[] = {90, 0.25f, 30, 20, 40, 15, 0.5f, -0.3f, 91, 0.4f, 1, 0, 0, 0, 0, 0,
                         115, 8, 0, 1, 2, 1, 0, 0};
        FxOp ops2[kFxMaxOps];
        CHECK(fxParseOps(flat2, 24, ops2) == 2, "tier3 parse count");
        CHECK(ops2[0].type == FX_WAVE_WARP_AM && ops2[0].q[0] == 0.4f && ops2[0].q[1] == 1.f, "wavewarp EXT folds into q");
        CHECK(ops2[1].type == FX_DISPLACE_MAP_AM, "displacemap parses");
        float flat3[] = {91, 0.4f, 1, 0, 0, 0, 0, 0};
        CHECK(fxParseOps(flat3, 8, ops2) == 0, "bare EXT op is dropped");
        FxShaderRef sr = fxShaderOf(FX_WAVE_WARP_AM);
        CHECK(sr.group == FXG_WARP && sr.mode == 18, "wavewarp routes to warp 18");
        sr = fxShaderOf(FX_STREAKS_AM);
        CHECK(sr.group == FXG_BLUR2 && sr.mode == 9, "streaks routes to blur2 9");
        sr = fxShaderOf(FX_COLOR_HOT_AM);
        CHECK(sr.group == FXG_COLOR && sr.mode == 21, "colorhot routes to colour 21");
        sr = fxShaderOf(FX_DOTS_AM);
        CHECK(sr.group == FXG_STYLE && sr.mode == 17, "dots routes to style 17");
        sr = fxShaderOf(FX_DISPLACE_MAP_AM);
        CHECK(sr.group == FXG_NONE, "displacemap is a dedicated pass");
        sr = fxShaderOf(FX_SMOOTH_BEVEL_AM);
        CHECK(sr.group == FXG_NONE, "smoothbevel is a dedicated pass");
        sr = fxShaderOf(FX_LIGHT_GLOW_AM);
        CHECK(sr.group == FXG_NONE, "lightglow is a dedicated pass");
        FxOp id{};
        id.type = FX_WAVE_WARP_AM; id.p[3] = 0.f;
        CHECK(fxIsIdentity(id), "wavewarp m2 0 is identity");
        id.type = FX_STRETCH_AM; id.p[0] = 1.f;
        CHECK(fxIsIdentity(id), "stretch scale 1 is identity");
        id.type = FX_DISPLACE_MAP_AM;
        CHECK(!fxIsIdentity(id), "displacemap is never identity (backdrop passthrough)");
        id.type = FX_HIGHLIGHT_SHADOW_AM; id.p[0] = 1.f; id.p[1] = 1.f;
        CHECK(fxIsIdentity(id), "highlightshadow 1/1 is identity");
        id.type = FX_SMOOTH_BEVEL_AM; id.p[0] = 0.f;
        CHECK(fxIsIdentity(id), "smoothbevel radius 0 is identity");
    }
    {
        // ---- extrude / extrude flat (v173) ----
        // The orientation matrix must reproduce the def's vertex-shader construction
        // literally: rotationAxisAngle's transposed fill, the product rz * rx * ry
        // (orient = identity), applied as the def's row-vector multiply. The reference
        // below rebuilds the mat4 exactly as the def's GLSL does.
        auto axisAngle = [](float ax, float ay, float az, float rad, float m[16]) {
            const float s = std::sin(rad), c = std::cos(rad), oc = 1.f - c;
            const float cols[16] = {
                oc * ax * ax + c, oc * ax * ay + az * s, oc * az * ax - ay * s, 0,
                oc * ax * ay - az * s, oc * ay * ay + c, oc * ay * az + ax * s, 0,
                oc * az * ax + ay * s, oc * ay * az - ax * s, oc * az * az + c, 0,
                0, 0, 0, 1};
            for (int i = 0; i < 16; ++i) m[i] = cols[i];
        };
        auto mul4 = [](const float A[16], const float B[16], float O[16]) {
            for (int col = 0; col < 4; ++col)
                for (int row = 0; row < 4; ++row) {
                    float v = 0;
                    for (int k = 0; k < 4; ++k) v += A[k * 4 + row] * B[col * 4 + k];
                    O[col * 4 + row] = v;
                }
        };
        const float triples[][3] = {{0, 0, 0}, {-44, -26, 0}, {0, 1000, 0}, {650, -359, 0}, {30, 45, 60}, {-120, 15, 200}};
        for (auto& tr : triples) {
            const float d = 0.0174533f;
            float mx[16], my[16], mz[16], t1[16], M[16];
            axisAngle(1, 0, 0, tr[0] * d, mx);
            axisAngle(0, 1, 0, tr[1] * d, my);
            axisAngle(0, 0, 1, tr[2] * d, mz);
            mul4(mz, mx, t1);
            mul4(t1, my, M);
            float m[9];
            fxExtrudeRotateMat(tr[0], tr[1], tr[2], m);
            const float v[3] = {0.35f, -0.72f, 1.25f};
            for (int i = 0; i < 3; ++i) {
                float defV = 0;
                for (int j = 0; j < 3; ++j) defV += v[j] * M[i * 4 + j];  // (v * M)_i of the def
                const float prodV = m[i * 3 + 0] * v[0] + m[i * 3 + 1] * v[1] + m[i * 3 + 2] * v[2];
                CHECK(std::fabs(defV - prodV) < 1e-4f, "extrude mat def-port mismatch");
            }
        }
        // hand-computed matrix pins
        float m9[9];
        fxExtrudeRotateMat(0, 0, 0, m9);
        CHECK(std::fabs(m9[0] - 1) < 1e-6 && std::fabs(m9[4] - 1) < 1e-6 && std::fabs(m9[8] - 1) < 1e-6,
              "extrude mat identity (toji's serialised orient)");
        fxExtrudeRotateMat(0, 90, 0, m9);   // = Ry(-90) in column-vector form: x axis -> (0,0,1), z axis -> (-1,0,0)
        CHECK(std::fabs(m9[2] + 1) < 1e-5 && std::fabs(m9[6] - 1) < 1e-5, "extrude mat ry90");
        fxExtrudeRotateMat(90, 90, 0, m9);  // order pin (the def's product, transposed): (1,0,0) -> (0,0,1)
        CHECK(std::fabs(m9[0]) < 1e-5 && std::fabs(m9[3]) < 1e-5 && std::fabs(m9[6] - 1) < 1e-5,
              "extrude mat order (def product rz*rx*ry, row-vector)");
        // The raymarch on a synthetic silhouette: an opaque white layer, its blur copy
        // identical (the sampler is in layer-norm, clamp-to-edge like the engine).
        auto samp = [](const void* ctx, int tex, float u, float v, float out[4]) {
            (void)ctx; (void)tex;
            u = std::min(1.f, std::max(0.f, u));
            v = std::min(1.f, std::max(0.f, v));
            (void)u; (void)v;
            out[0] = out[1] = out[2] = out[3] = 1.f;  // opaque white, premultiplied
        };
        FxExtrudeSampler S;
        S.sample = samp;
        FxOp ex;
        ex.type = FX_EXTRUDE_AM;
        ex.p[0] = 0.1f; ex.p[1] = 1.f; ex.p[2] = 1.f; ex.p[3] = 1.f; ex.p[6] = 0.f;  // depth, scale, ratios, flat shading
        ex.q[4] = 100.f; ex.q[5] = 500.f;                                            // position (0, 100, 500)
        ex.r[3] = 2660.f;                                                            // intensity 1 + strength 0.1, packed
        ex.r[6] = 410000.f;                                                          // lightDepth 100 + MAX_ITER 200, packed
        float out[4];
        fxExtrudeAmPixel(ex, false, 0.5f, 0.5f, 1000, 1000, S, out);
        CHECK(std::fabs(out[0] - 1) < 0.01f && std::fabs(out[3] - 1) < 0.01f,
              "extrude centre is the box face, opaque white");
        ex.q[5] = 2000.f;   // a far object: the frame corner's ray misses the box
        fxExtrudeAmPixel(ex, false, 0.02f, 0.02f, 1000, 1000, S, out);
        CHECK(out[3] == 0.f, "extrude missed ray is transparent");
        ex.q[5] = 500.f;
        // Turned 90 degrees about y, the box's side faces the camera; at the face
        // centre dz = 0.5, so the interior colour (green, blend 1) wins.
        ex.q[1] = 90.f;
        ex.p[4] = 1.f;
        ex.q[6] = 0x00FF00;
        fxExtrudeAmPixel(ex, false, 0.5f, 0.5f, 1000, 1000, S, out);
        CHECK(std::fabs(out[1] - 1) < 0.03f && out[0] < 0.03f && std::fabs(out[3] - 1) < 0.03f,
              "extrude side face is interior green");
        // Depth-map shading at identity: the march lands at d = 5.9 exactly, ds = 1.25,
        // so dd = 1 - (5.9 - 5 + 0.625) / 1.25 = -0.22.
        ex.q[1] = 0.f; ex.p[4] = 0.f; ex.p[6] = 3.f;
        fxExtrudeAmPixel(ex, false, 0.5f, 0.5f, 1000, 1000, S, out);
        CHECK(std::fabs(out[0] - (-0.22f)) < 0.02f && std::fabs(out[3] - 1) < 0.01f,
              "extrude depth-map centre value");
        // EXT2 folding: main + EXT + EXT2 fold into one op; a stray EXT2 is dropped.
        float flatE[] = {123, 0.1f, 1, 1, 1, 0, 0, 1,
                         124, -44, -26, 0, 0, 100, 500, 0x3366AA,
                         125, 0xAA3366, 0xFFFFFF, 12345, 2660, 10250, 30, 410000,
                         126, 0.05f, 1, 1, 1, 1, 1, 1,
                         127, 0, 90, 0, 0, 100, 500, 0,
                         128, 0x2C3EFF, 300, 0, 0, 0, 0, 0};
        FxOp eops[kFxMaxOps];
        CHECK(fxParseOps(flatE, 48, eops) == 2, "extrude parse count");
        CHECK(eops[0].type == FX_EXTRUDE_AM && eops[0].q[0] == -44.f && eops[0].r[6] == 410000.f,
              "extrude EXT+EXT2 fold into q/r");
        CHECK(eops[1].type == FX_EXTRUDE_FLAT_AM && eops[1].q[1] == 90.f && eops[1].r[1] == 300.f,
              "extrudeflat EXT+EXT2 fold into q/r");
        float flatX[] = {125, 1, 2, 3, 4, 5, 6, 7};
        CHECK(fxParseOps(flatX, 8, eops) == 0, "bare EXT2 is dropped");
        float flatY[] = {123, 0.1f, 1, 1, 1, 0, 0, 1, 125, 1, 2, 3, 4, 5, 6, 7};
        CHECK(fxParseOps(flatY, 16, eops) == 1 && eops[0].r[0] == 0.f, "EXT2 without its EXT is dropped");
        CHECK(fxShaderOf(FX_EXTRUDE_AM).group == FXG_NONE && fxShaderOf(FX_EXTRUDE_FLAT_AM).group == FXG_NONE,
              "extrude tier is a dedicated pass");
        FxOp eo;
        eo.type = FX_EXTRUDE_AM;
        CHECK(!fxIsIdentity(eo), "extrude is never identity");
        // Extrude Flat against its own def (build 136, defs-batch8): the flat march at
        // identity hits the box face (opaque white), and `inton` gates the interior
        // colour on the side faces — off, the side keeps the surface colour.
        FxOp fl;
        fl.type = FX_EXTRUDE_FLAT_AM;
        fl.p[0] = 0.1f; fl.p[1] = 1.f; fl.p[2] = 1.f; fl.p[3] = 1.f;   // depth, scale, stretch
        fl.p[4] = 1.f; fl.p[5] = 1.f; fl.p[6] = 1.f;                   // blends, inton ON
        fl.q[4] = 100.f; fl.q[5] = 500.f; fl.q[6] = 0x00FF00;          // position, interior green
        fl.r[0] = 0x00FF00; fl.r[1] = 300.f;                           // fade green too, MAX_ITER
        fxExtrudeAmPixel(fl, true, 0.5f, 0.5f, 1000, 1000, S, out);
        CHECK(std::fabs(out[0] - 1) < 0.01f && std::fabs(out[3] - 1) < 0.01f,
              "extrudeflat centre is the box face, opaque white");
        fl.q[1] = 90.f;   // side face at dz = 0.5, like the extrude pin above
        fxExtrudeAmPixel(fl, true, 0.5f, 0.5f, 1000, 1000, S, out);
        CHECK(std::fabs(out[1] - 1) < 0.03f && out[0] < 0.03f && std::fabs(out[3] - 1) < 0.03f,
              "extrudeflat side face is interior green (inton on)");
        fl.p[6] = 0.f;   // inton OFF: the def applies neither interior nor fade mix
        fxExtrudeAmPixel(fl, true, 0.5f, 0.5f, 1000, 1000, S, out);
        CHECK(out[0] > 0.9f && std::fabs(out[0] - out[1]) < 0.03f && std::fabs(out[1] - out[2]) < 0.03f &&
              std::fabs(out[3] - 1) < 0.03f,
              "extrudeflat side face keeps the surface colour (inton off)");
    }
    {   // Motion Blur 4 (AM's motionblur4 def; build 136): the sample-count law and the
        // scale / angle speeds, hand-computed from the def's three stages.
        CHECK(fxMb4Samples(40.f, 1.f, 100.f) == 40, "mb4 samples: speed 40 -> 40");
        CHECK(fxMb4Samples(0.4f, 1.f, 100.f) == 1, "mb4 samples: sub-texel speed -> samplesMin");
        CHECK(fxMb4Samples(500.f, 1.f, 100.f) == 100, "mb4 samples: speed clamps at samplesMax");
        CHECK(fxMb4Samples(1.99f, 1.f, 100.f) == 1, "mb4 samples: the count truncates like the def's int()");
        CHECK(fxMb4Samples(3.f, 2.f, 8.f) == 3, "mb4 samples: inside custom bounds");
        CHECK(fxMb4Samples(1.f, 2.f, 8.f) == 2, "mb4 samples: custom samplesMin floors the count");
        CHECK(std::fabs(fxMb4ScaleSpeed(0.2f, 1.f, 300.f) - 30.f) < 1e-4f, "mb4 scale speed: |svel*tune|*dist/2");
        CHECK(std::fabs(fxMb4ScaleSpeed(-0.2f, 2.f, 300.f) - 60.f) < 1e-4f, "mb4 scale speed: sign-free, tune scales");
        CHECK(std::fabs(fxMb4AngleSpeed(12.f, 1.f, 200.f) - 41.8879f) < 0.01f, "mb4 angle speed: arc length in texels");
        CHECK(fxMb4AngleSpeed(0.f, 1.f, 200.f) == 0.f, "mb4 angle speed: still layer");
        CHECK(fxShaderOf(FX_MB4_POS).group == FXG_BLUR2 && fxShaderOf(FX_MB4_POS).mode == 10 &&
              fxShaderOf(FX_MB4_SCALE).mode == 11 && fxShaderOf(FX_MB4_ANGLE).mode == 12,
              "mb4 passes are kFxBlur2 modes 10..12");
        float mb4[] = {180, 0.1f, -0.2f, 1.f, 1.f, 100.f, 0, 0,
                       181, 0.05f, 1.f, 50.f, 50.f, 1.f, 100.f, 0,
                       182, 12.f, 1.f, 50.f, 50.f, 1.f, 100.f, 0,
                       179, 100.f, 1, 1, 1, 1, 100, 0};
        FxOp mops[kFxMaxOps];
        CHECK(fxParseOps(mb4, 32, mops) == 3, "mb4 passes parse, the stored 179 is skipped");
        CHECK(mops[0].type == FX_MB4_POS && mops[1].type == FX_MB4_SCALE && mops[2].type == FX_MB4_ANGLE,
              "mb4 pass order is position, scale, angle");
    }
    {   // Edge Glow (AM's edgeglow def): the findedges-edgeglow output stage, the final
        // composite, and the blur calibration — hand-computed from the def's shader.
        float oR, oG, oB, oA;
        // A flat neighbourhood: g = 0 — the non-inverted stage outputs black at the
        // pixel's own alpha (the Graph bars' interior case: no edge, no contribution),
        // the inverted stage outputs (a, a, a, a).
        const float flat8[8] = {0.4f, 0.4f, 0.4f, 0.4f, 0.4f, 0.4f, 0.4f, 0.4f};
        fxEdgeGlowAmEdge(flat8, 0.3f, 0.5f, 0.1f, 1.f, 0.59f, false, &oR, &oG, &oB, &oA);
        CHECK(oR == 0.f && oG == 0.f && oB == 0.f && oA == 1.f, "edge glow flat non-invert (%g %g %g %g)", oR, oG, oB, oA);
        fxEdgeGlowAmEdge(flat8, 0.3f, 0.5f, 0.1f, 0.8f, 0.59f, true, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.8f) < 1e-6f && std::fabs(oA - 0.8f) < 1e-6f, "edge glow flat invert (%g %g)", oR, oA);
        // A vertical step of 0.35 (nw/ne order: nw, n, ne, w, e, sw, s, se): |h| = 16 *
        // 0.35 = 5.6 — past the Graph bars' 0.59 threshold g clamps to 1 and the stage
        // returns the pixel itself; at threshold 6 nothing survives.
        const float step8[8] = {0.f, 0.35f, 0.35f, 0.f, 0.35f, 0.f, 0.35f, 0.35f};
        fxEdgeGlowAmEdge(step8, 0.3f, 0.5f, 0.1f, 1.f, 0.59f, false, &oR, &oG, &oB, &oA);
        CHECK(oR == 0.3f && oG == 0.5f && oB == 0.1f && oA == 1.f, "edge glow step g=1 (%g %g %g)", oR, oG, oB);
        fxEdgeGlowAmEdge(step8, 0.3f, 0.5f, 0.1f, 1.f, 6.f, false, &oR, &oG, &oB, &oA);
        CHECK(oR == 0.f && oG == 0.f && oB == 0.f, "edge glow step threshold 6 (%g)", oR);
        fxEdgeGlowAmEdge(step8, 0.3f, 0.5f, 0.1f, 1.f, 0.59f, true, &oR, &oG, &oB, &oA);
        CHECK(oR == 0.f && oA == 1.f, "edge glow step invert g=1 (%g %g)", oR, oA);
        // Composite, neutral settings: the base survives the YUV round trip (the def's
        // truncated constants cost ~5e-6), a translucent base keeps its premultiply.
        fxEdgeGlowAmComposite(0.2f, 0.4f, 0.6f, 1.f, 0, 0, 0, 0,
                              1.5f, 1.f, 0.f, 0.f, 0.5333333f, 1.f, 0.f, 0, 0, 0, 0.f,
                              &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.2f) < 1e-4f && std::fabs(oG - 0.4f) < 1e-4f && std::fabs(oB - 0.6f) < 1e-4f && oA == 1.f,
              "edge glow composite neutral (%g %g %g %g)", oR, oG, oB, oA);
        fxEdgeGlowAmComposite(0.1f, 0.2f, 0.3f, 0.5f, 0, 0, 0, 0,
                              1.5f, 1.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0, 0, 0, 0.f,
                              &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.1f) < 1e-4f && std::fabs(oG - 0.2f) < 1e-4f && std::fabs(oB - 0.3f) < 1e-4f && oA == 0.5f,
              "edge glow composite translucent (%g %g %g %g)", oR, oG, oB, oA);
        // The blend factor branches on a black base with a 0.5-grey glow (spread 1.5:
        // pow = 0.353553, lum the same): blend 2 -> pure fillColor * lum; blend 0 ->
        // the glow itself added; blend 1 -> the (1 - lum) mix of the two.
        fxEdgeGlowAmComposite(0, 0, 0, 1.f, 0.5f, 0.5f, 0.5f, 1.f,
                              1.5f, 1.f, 0.f, 0.f, 0.5333333f, 1.f, 2.f, 0, 0, 0, 0.f,
                              &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR) < 1e-6f && std::fabs(oG - 0.1885618f) < 1e-5f && std::fabs(oB - 0.3535534f) < 1e-5f &&
              std::fabs(oA - 1.3535534f) < 1e-5f, "edge glow composite blend 2 (%g %g %g %g)", oR, oG, oB, oA);
        fxEdgeGlowAmComposite(0, 0, 0, 1.f, 0.5f, 0.5f, 0.5f, 1.f,
                              1.5f, 1.f, 0.f, 0.f, 0.5333333f, 1.f, 0.f, 0, 0, 0, 0.f,
                              &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.3535534f) < 1e-5f && std::fabs(oA - 2.f) < 1e-5f,
              "edge glow composite blend 0 (%g %g)", oR, oA);
        fxEdgeGlowAmComposite(0, 0, 0, 1.f, 0.5f, 0.5f, 0.5f, 1.f,
                              1.5f, 1.f, 0.f, 0.f, 0.5333333f, 1.f, 1.f, 0, 0, 0, 0.f,
                              &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.125f) < 1e-5f && std::fabs(oG - 0.2468951f) < 1e-5f && std::fabs(oB - 0.3535534f) < 1e-5f &&
              std::fabs(oA - 1.5821068f) < 1e-5f, "edge glow composite blend 1 (%g %g %g %g)", oR, oG, oB, oA);
        // Tint: 1 x black erases the base colour (alpha kept); 0.5 x black halves it;
        // spread 0 -> the glow is cubed (0.5^3 = 0.125, lum the same, blend 2 white).
        fxEdgeGlowAmComposite(0.2f, 0.4f, 0.6f, 1.f, 0, 0, 0, 0,
                              1.5f, 1.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0, 0, 0, 1.f,
                              &oR, &oG, &oB, &oA);
        CHECK(oR == 0.f && oG == 0.f && oB == 0.f && oA == 1.f, "edge glow composite tint black (%g %g %g %g)", oR, oG, oB, oA);
        fxEdgeGlowAmComposite(0.2f, 0.4f, 0.6f, 1.f, 0, 0, 0, 0,
                              1.5f, 1.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0, 0, 0, 0.5f,
                              &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.1f) < 1e-4f && std::fabs(oG - 0.2f) < 1e-4f && std::fabs(oB - 0.3f) < 1e-4f,
              "edge glow composite tint half (%g %g %g)", oR, oG, oB);
        fxEdgeGlowAmComposite(0, 0, 0, 1.f, 0.5f, 0.5f, 0.5f, 1.f,
                              0.f, 1.f, 0.f, 1.f, 1.f, 1.f, 2.f, 0, 0, 0, 0.f,
                              &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.125f) < 1e-5f && std::fabs(oA - 1.125f) < 1e-5f,
              "edge glow composite spread 0 (%g %g)", oR, oA);
        // Gamma 2 square-roots the base; brightness +0.25 lifts its Y by that much.
        fxEdgeGlowAmComposite(0.25f, 0.25f, 0.25f, 1.f, 0, 0, 0, 0,
                              1.5f, 2.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0, 0, 0, 0.f,
                              &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.5f) < 1e-4f && std::fabs(oG - 0.5f) < 1e-4f && std::fabs(oB - 0.5f) < 1e-4f,
              "edge glow composite gamma (%g %g %g)", oR, oG, oB);
        fxEdgeGlowAmComposite(0.25f, 0.25f, 0.25f, 1.f, 0, 0, 0, 0,
                              1.5f, 1.f, 0.25f, 0.f, 0.f, 0.f, 0.f, 0, 0, 0, 0.f,
                              &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.5f) < 1e-4f && std::fabs(oG - 0.5f) < 1e-4f && std::fabs(oB - 0.5f) < 1e-4f,
              "edge glow composite brightness (%g %g %g)", oR, oG, oB);
        // The blur calibration (v176): the host gaussianblur kernel at the frame
        // diagonal — strength 0 is the identity (the Graph bars' case), 0.3 ->
        // sigma 29.256 at 1080p, 2 -> 195.041 (no cap; the flat x 50 gave 15 / 100).
        CHECK(fxEdgeGlowAmBlurSigma(0.f, 2202.9072f) == 0.f &&
              std::fabs(fxEdgeGlowAmBlurSigma(0.3f, 2202.9072f) - 29.256220f) < 1e-3f &&
              std::fabs(fxEdgeGlowAmBlurSigma(2.f, 2202.9072f) - 195.041468f) < 1e-2f,
              "edge glow blur calibration");
        // Parse: main + EXT fold into one op; a bare EXT is dropped; never identity.
        float flatG[] = {129, 2.05f, 0.59f, 0, 0, 1.54f, 16777215, 0,
                         130, 1, 0, 0, 0, 0, 0, 0};
        FxOp gops[kFxMaxOps];
        CHECK(fxParseOps(flatG, 16, gops) == 1 && gops[0].type == FX_EDGE_GLOW_AM &&
              gops[0].p[0] == 2.05f && gops[0].p[5] == 16777215.f && gops[0].q[0] == 1.f,
              "edge glow EXT folds into q");
        float flatH[] = {130, 1, 2, 3, 4, 5, 6, 7};
        CHECK(fxParseOps(flatH, 8, gops) == 0, "bare edge glow EXT is dropped");
        FxOp go;
        go.type = FX_EDGE_GLOW_AM;
        CHECK(!fxIsIdentity(go), "edge glow is never identity");
        CHECK(fxShaderOf(FX_EDGE_GLOW_AM).group == FXG_NONE, "edge glow is a dedicated pass");
    }
    // ---- Tier 4 (build 129): the def-backed round-2 ids; pins are hand-computed
    // from the staged defs (hunt-staging/defs-batch7). ----
    {
        float oR, oG, oB, oA;
        // Threshold: luma 0.8 over threshold 0.71 lands past the band (t = 0.7115);
        // 0.2 under it; the band's exact midpoint at threshold 0.5 returns 0.5;
        // invert flips.
        fxThresholdAmMap(0.8f, 0.8f, 0.8f, 1.f, 0.71f, 0.f, false, 0, &oR, &oG, &oB, &oA);
        CHECK(oR == 1.f, "threshold above -> 1 (%g)", oR);
        fxThresholdAmMap(0.2f, 0.2f, 0.2f, 1.f, 0.71f, 0.f, false, 0, &oR, &oG, &oB, &oA);
        CHECK(oR == 0.f, "threshold below -> 0 (%g)", oR);
        fxThresholdAmMap(0.5f, 0.5f, 0.5f, 1.f, 0.5f, 0.f, false, 0, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.5f) < 1e-4f, "threshold midpoint (%g)", oR);
        fxThresholdAmMap(0.8f, 0.8f, 0.8f, 1.f, 0.71f, 0.f, true, 0, &oR, &oG, &oB, &oA);
        CHECK(oR == 0.f, "threshold invert (%g)", oR);
        // Spectral map: the channel curve and the full map of (0.2, 0.4, 0.6) with
        // the def's RGB colours (lum 0.37192 -> r 0.179, g 1, b 0.488; the twin
        // keeps the pixel's own alpha, like every colour-program mode).
        CHECK(std::fabs(fxSpectralmapAmMapChannel(0.25f, 1.f / 3.f) - 2.f / 3.f) < 1e-4f,
              "spectral channel curve");
        CHECK(fxSpectralmapAmMapChannel(0.5f, 1.f / 3.f) == 0.f, "spectral channel peak");
        const float cols[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        fxSpectralmapAmMap(0.2f, 0.4f, 0.6f, 1.f, 0.f, 1.f, 1.f, 0.f, 0.f, false, false,
                           cols, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.179f) < 0.002f && std::fabs(oG - 1.f) < 0.002f &&
              std::fabs(oB - 0.488f) < 0.002f && std::fabs(oA - 1.f) < 0.004f,
              "spectral map (%g %g %g %g)", oR, oG, oB, oA);
        // Channel map: constants / luma picks, re-premultiplied by the new alpha.
        // The luma is of the PREMULTIPLIED rgb (0.37192), read before the divide.
        fxChannelmapAmMap(0.2f, 0.4f, 0.6f, 0.8f, 6, 5, 4, 3, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.8f) < 1e-4f && oG == 0.f &&
              std::fabs(oB - 0.8f * 0.37192f) < 1e-3f && std::fabs(oA - 0.8f) < 1e-4f,
              "channelmap picks (%g %g %g %g)", oR, oG, oB, oA);
        fxChannelmapAmMap(0.2f, 0.4f, 0.6f, 0.f, 0, 1, 2, 3, &oR, &oG, &oB, &oA);
        CHECK(oR == 0.f && oA == 0.f, "channelmap transparent stays transparent");
        // Halftone lines: the displaced sample at a half-band point moves half a
        // band; the colour decision mixes at the band fold.
        float ux, uy, ad;
        fxHalftoneLinesUv(0.5f, 0.605f, 100.f, 0.f, 0.f, 1.f, &ux, &uy, &ad);
        CHECK(std::fabs(ux - 0.5f) < 1e-4f && std::fabs(uy - 0.61f) < 1e-4f &&
              std::fabs(ad - 0.5f) < 1e-4f, "halftone lines uv (%g %g %g)", ux, uy, ad);
        const float c1[3] = {0, 0, 0}, c2[3] = {1, 0.27f, 0.27f};
        fxHalftoneLinesMap(0.f, 0.f, 0.f, 1.f, 0.5f, 0.5f, c1, c2, 1.f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.5f) < 1e-4f, "halftone lines mid mix (%g)", oR);
        fxHalftoneLinesMap(0.9f, 0.9f, 0.9f, 0.f, 0.f, 0.5f, c1, c2, 0.8f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.8f) < 1e-4f, "halftone lines outside -> colour 2 (%g)", oR);
        // Turbulence 2: the field is deterministic and bounded; alpha 0 composes to
        // the pixel itself; blend 0 paints the field colour at the pixel's alpha.
        float f1 = fxTurbulence2AmField(0.3f, 0.7f, 1.f, 1.f, 0.f, 0.f, 6,
                                        0.f, 0.f, 0.f, 0.f, 1.f, 1.f, 1.f);
        float f2 = fxTurbulence2AmField(0.3f, 0.7f, 1.f, 1.f, 0.f, 0.f, 6,
                                        0.f, 0.f, 0.f, 0.f, 1.f, 1.f, 1.f);
        CHECK(f1 == f2 && f1 >= 0.f && f1 < 2.f, "turbulence2 field (%g)", f1);
        fxTurbulence2AmMap(0.2f, 0.4f, 0.6f, 0.8f, 0.5f, 0.5f, 0.5f, 0, 0.f, &oR, &oG, &oB, &oA);
        CHECK(oR == 0.2f && oA == 0.8f, "turbulence2 alpha 0 is the pixel");
        fxTurbulence2AmMap(0.2f, 0.4f, 0.6f, 0.8f, 0.5f, 0.5f, 0.5f, 0, 1.f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.4f) < 1e-4f && std::fabs(oA - 0.8f) < 1e-4f,
              "turbulence2 blend 0 premultiplies (%g %g)", oR, oA);
        // Checker 2: parity flips between neighbouring cells; the compose is the
        // def's c*alpha + tex*(1-alpha); punchout erases the coloured cells.
        CHECK(fxChecker2AmP(0.1f, 0.1f, 10.f, 0.f, 0.f, 0.f, 1.f, 1.f, 1.f) == 1.f, "checker2 cell on");
        CHECK(fxChecker2AmP(0.2f, 0.1f, 10.f, 0.f, 0.f, 0.f, 1.f, 1.f, 1.f) == 0.f, "checker2 cell off");
        fxChecker2AmMap(0.5f, 0.5f, 0.5f, 0.8f, 0.f, 0.f, 0.f, 1.f, false, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.1f) < 1e-4f && std::fabs(oA - 0.96f) < 1e-4f,
              "checker2 compose (%g %g)", oR, oA);
        fxChecker2AmMap(0.5f, 0.5f, 0.5f, 0.8f, 0.f, 0.f, 0.f, 1.f, true, &oR, &oG, &oB, &oA);
        CHECK(oA == 0.f, "checker2 punchout (%g)", oA);
        // Mosaic: the snap, the per-pass sample counts, and the gradient average.
        // The def's average is asymmetric, verbatim: the base tap sits at the
        // snapped tile edge and the loop taps centre on that same point, so a
        // linear field averages to 0.496 / 0.495 here, not the tile centre.
        CHECK(std::fabs(fxMosaicAmSnap(0.055f, 0.f, 10.f, 1000.f) - 0.05f) < 1e-5f, "mosaic snap");
        CHECK(fxMosaicAmSamples(10.f, 0) == 5 && fxMosaicAmSamples(10.f, 1) == 10,
              "mosaic sample counts");
        auto grad = [](const void*, int, float u, float v, float out[4]) {
            out[0] = u; out[1] = v; out[2] = 0.f; out[3] = 1.f;
        };
        FxExtrudeSampler GS; GS.sample = grad; GS.ctx = nullptr;
        FxOp mo{}; mo.type = FX_MOSAIC_AM; mo.p[0] = 10.f;
        float mout[4];
        fxMosaicAmPixel(mo, 0.55f, 0.55f, 100, 100, GS, mout);
        CHECK(std::fabs(mout[0] - 0.496f) < 0.002f && std::fabs(mout[1] - 0.495f) < 0.002f,
              "mosaic gradient average (%g %g)", mout[0], mout[1]);
        // Glow: deep inside (nearestIn = radius) the colour lands at full alpha;
        // far outside it vanishes and the pixel is its own.
        fxGlowAmMap(0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 100.f, 0.f, 100.f, 0.5f, 0.75f,
                    1.f, 1.f, 1.f, 1.f, 0, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.75f) < 1e-4f && std::fabs(oA - 0.75f) < 1e-4f,
              "glow inside (%g %g)", oR, oA);
        fxGlowAmMap(0.2f, 0.2f, 0.2f, 1.f, 0.f, 0.f, 0.f, 0.f, 100.f, 100.f, 0.5f, 0.75f,
                    1.f, 1.f, 1.f, 1.f, 0, &oR, &oG, &oB, &oA);
        CHECK(oR == 0.2f && oA == 1.f, "glow far outside is the pixel (%g %g)", oR, oA);
        // Box sample counts.
        CHECK(fxBoxSamples(0.5f, false) == 1 && fxBoxSamples(30.7f, false) == 30 &&
              fxBoxSamples(30.7f, true) == 30 && fxBoxSamples(500.f, true) == 150 &&
              fxBoxSamples(1.2f, true) == 2, "box sample counts");
        // The blur-family twins on a constant field: inner blur and box blur 3 are
        // the identity (the composite un-premultiplies and re-applies the alpha);
        // feather squares the averaged alpha.
        auto konst = [](const void*, int, float, float, float out[4]) {
            out[0] = 0.2f; out[1] = 0.4f; out[2] = 0.6f; out[3] = 0.8f;
        };
        FxExtrudeSampler KS; KS.sample = konst; KS.ctx = nullptr;
        FxOp ib{}; ib.type = FX_INNER_BLUR_AM; ib.p[0] = 0.4f; ib.p[1] = 2.f;
        fxInnerBlurAmPixel(ib, 0.3f, 0.3f, 16, 16, KS, mout);
        CHECK(std::fabs(mout[0] - 0.2f) < 1e-4f && std::fabs(mout[3] - 0.8f) < 1e-4f,
              "inner blur constant field (%g %g)", mout[0], mout[3]);
        FxOp bb{}; bb.type = FX_BOX_BLUR3_AM; bb.p[0] = 1.589f;
        fxBoxBlur3AmPixel(bb, 0.3f, 0.3f, 16, 16, KS, mout);
        CHECK(std::fabs(mout[0] - 0.2f) < 1e-4f && std::fabs(mout[3] - 0.8f) < 1e-4f,
              "box blur 3 constant field (%g %g)", mout[0], mout[3]);
        FxOp fe{}; fe.type = FX_FEATHER_AM; fe.p[0] = 0.15f; fe.p[1] = 1.f;  // one round: one a^2 composite
        fxFeatherAmPixel(fe, 0.3f, 0.3f, 16, 16, KS, mout);
        CHECK(std::fabs(mout[3] - 0.512f) < 1e-3f && std::fabs(mout[0] - 0.128f) < 1e-3f,
              "feather alpha squared (%g %g)", mout[0], mout[3]);
        // Electric edges: no edge, no glow — deep inside and far outside both
        // return the pixel (the smoothstep gates at both ends of the alpha).
        FxOp ee{}; ee.type = FX_ELECTRIC_EDGES_AM;
        ee.p[0] = 0.613f; ee.p[1] = 0.526f; ee.p[2] = 3.15f; ee.p[3] = -0.7f;
        ee.p[4] = 16711812.f; ee.p[5] = 16777215.f; ee.p[6] = 1.f;
        ee.q[0] = 0.5f; ee.q[3] = 1.f; ee.q[6] = 1.f; ee.r[5] = 20.f; ee.r[6] = 0.42f;
        auto edgeSamp = [](const void*, int tex, float, float, float out[4]) {
            if (tex == 0) { out[0] = 0.3f; out[1] = 0.3f; out[2] = 0.3f; out[3] = 0.9f; }
            else { out[0] = 0.f; out[1] = 0.f; out[2] = 0.f; out[3] = 1.f; }  // blurred: deep inside
        };
        FxExtrudeSampler ES; ES.sample = edgeSamp; ES.ctx = nullptr;
        fxElectricEdgesAmPixel(ee, 0.5f, 0.5f, 64, 64, ES, mout);
        CHECK(std::fabs(mout[0] - 0.3f) < 1e-4f && std::fabs(mout[3] - 0.9f) < 1e-4f,
              "electric edges deep inside is the pixel (%g %g)", mout[0], mout[3]);
        float nz[2];
        fxElectricNoise(3.7f, 9.1f, nz);
        CHECK(nz[0] >= -1.5f && nz[0] <= 1.5f && nz[1] >= -1.5f && nz[1] <= 1.5f,
              "electric noise range (%g %g)", nz[0], nz[1]);
        // Smooth edges: a blurred alpha past the cut keeps the pixel whole; under
        // it, the pixel is cut away.
        FxOp se{}; se.type = FX_SMOOTH_EDGES_AM; se.p[0] = 0.07f;
        auto seIn = [](float, float, float o[4]) { o[0] = 0.3f; o[1] = 0.3f; o[2] = 0.3f; o[3] = 0.6f; };
        auto seBlurHi = [](float, float, float o[4]) { o[0] = 0.f; o[1] = 0.f; o[2] = 0.f; o[3] = 0.6f; };
        auto seBlurLo = [](float, float, float o[4]) { o[0] = 0.f; o[1] = 0.f; o[2] = 0.f; o[3] = 0.4f; };
        fxSmoothEdgesAmPixel(se, 0.5f, 0.5f, 32, 32,
                             FxExtrudeSampler{[](const void*, int, float u, float v, float o[4]) {
                                 o[0] = 0.3f; o[1] = 0.3f; o[2] = 0.3f; o[3] = 0.6f; (void)u; (void)v; }, nullptr},
                             mout);
        (void)seIn; (void)seBlurHi; (void)seBlurLo;
        CHECK(std::fabs(mout[3] - 0.6f) < 1e-3f, "smooth edges keeps a solid pixel (%g)", mout[3]);
        // Parse + shader routing for the tier.
        float flatS[] = {132, 0, 1, 1, 0, 0, 0, 1, 133, 16777215, 65280, 255, 0, 0, 0, 0};
        FxOp tops[kFxMaxOps];
        CHECK(fxParseOps(flatS, 16, tops) == 1 && tops[0].type == FX_SPECTRALMAP_AM &&
              tops[0].q[0] == 16777215.f, "spectral EXT folds into q");
        float flatT[] = {136, 1, 1, 0, 0, 0, 16711812, 0,
                         137, 1, 2, 3, 4, 1, 1, 0,
                         138, 1, 6, 0, 0, 0, 0, 0};
        CHECK(fxParseOps(flatT, 24, tops) == 1 && tops[0].type == FX_TURBULENCE2_AM &&
              tops[0].q[0] == 1.f && tops[0].r[1] == 6.f, "turbulence EXT + EXT2 fold");
        float flatE[] = {146, 0.6f, 0.5f, 3, -0.7f, 16711812, 16777215, 1,
                         147, 0.5f, 0, 0, 1, 0, 0, 1,
                         148, 0, 15654357, 0, 20, 0, 0, 0.42f};
        CHECK(fxParseOps(flatE, 24, tops) == 1 && tops[0].type == FX_ELECTRIC_EDGES_AM &&
              tops[0].r[6] == 0.42f, "electric EXT + EXT2 fold");
        float flatC[] = {139, 10, 0, 2882406, 0, 0, 0, 0, 140, 1, 1, 0, 0, 0, 0, 0};
        CHECK(fxParseOps(flatC, 16, tops) == 1 && tops[0].type == FX_CHECKER2_AM &&
              tops[0].q[0] == 1.f, "checker EXT folds into q");
        float flatX[] = {138, 1, 6, 0, 0, 0, 0, 0};
        CHECK(fxParseOps(flatX, 8, tops) == 0, "bare EXT2 is dropped");
        CHECK(fxShaderOf(FX_THRESHOLD_AM).group == FXG_COLOR && fxShaderOf(FX_THRESHOLD_AM).mode == 25 &&
              fxShaderOf(FX_SPECTRALMAP_AM).mode == 26 &&
              fxShaderOf(FX_HALFTONE_LINES_AM).group == FXG_WARP && fxShaderOf(FX_HALFTONE_LINES_AM).mode == 27 &&
              fxShaderOf(FX_TURBULENCE2_AM).group == FXG_STYLE && fxShaderOf(FX_TURBULENCE2_AM).mode == 19 &&
              fxShaderOf(FX_CHECKER2_AM).mode == 20 &&
              fxShaderOf(FX_CHANNELMAP_AM).group == FXG_NONE && fxShaderOf(FX_MOSAIC_AM).group == FXG_NONE &&
              fxShaderOf(FX_GLOW_AM).group == FXG_NONE, "tier 4 shader routing");
        FxOp id{};
        id.type = FX_CHANNELMAP_AM;
        id.p[0] = 0.f; id.p[1] = 1.f; id.p[2] = 2.f; id.p[3] = 3.f;   // the identity selectors
        CHECK(fxIsIdentity(id), "channelmap identity selectors are identity");
        id.p[2] = 1.f;
        CHECK(!fxIsIdentity(id), "channelmap swapped selectors are not identity");
        id = FxOp{}; id.type = FX_MOSAIC_AM;
        CHECK(fxIsIdentity(id) && (id.p[0] = 2.f, !fxIsIdentity(id)), "mosaic identity at size <= 1");
        id = FxOp{}; id.type = FX_FEATHER_AM;
        CHECK(fxIsIdentity(id) && (id.p[0] = 0.15f, !fxIsIdentity(id)), "feather identity under its floor");
        id = FxOp{}; id.type = FX_SPECTRALMAP_AM;
        CHECK(fxIsIdentity(id), "spectral alpha 0 is identity");
    }
    // v176: Queue 15 top batch — the host gaussianblur calibration (fxAmGaussianSigma),
    // Deep Glow 2 (deepglow2 + deepglow_enc) and Drop Shadow. Every expected value was
    // hand-computed from the recovered defs in double precision before the port
    // (hunt-staging/pins-b132.txt).
    {
        const float D1080 = 2202.9072f;   // hypot(1920, 1080)
        // The kernel: sigma = strength x diag x 0.04427 (the def formula).
        CHECK(fxAmGaussianSigma(0.f, D1080) == 0.f, "gaussian sigma 0");
        CHECK(std::fabs(fxAmGaussianSigma(0.15f, D1080) - 14.628110f) < 1e-3f, "gaussian sigma 0.15 @1080p");
        CHECK(std::fabs(fxAmGaussianSigma(1.f, D1080) - 97.520734f) < 1e-3f, "gaussian sigma 1 @1080p");
        CHECK(std::fabs(fxAmGaussianSigma(2.f, D1080) - 195.041468f) < 1e-2f, "gaussian sigma 2 @1080p");
        CHECK(std::fabs(fxAmGaussianSigma(0.42f, 1468.6048f) - 27.305805f) < 1e-3f, "gaussian sigma 0.42 @720p");
        CHECK(std::fabs(fxAmGaussianSigma(0.3f, 979.6510f) - 13.010482f) < 1e-3f, "gaussian sigma 0.3 @480p");
        // Darkglow's sigma IS the same kernel (build 120's pins ride the delegate).
        CHECK(std::fabs(fxDarkGlowSigma(0.3f, D1080) - fxAmGaussianSigma(0.3f, D1080)) < 1e-6f, "darkglow sigma delegates");
        float oR, oG, oB, oA;
        // Deep Glow 2. Common: tex (0.10, 0.20, 0.30, 0.50), blur mid (0.40, 0.30, 0.20,
        // 0.60); strength 0.54, oalpha 0.62, low 0.1, high 1, feather 0.3, satvib (0, 1),
        // hue 360, calpha 1, colblend #FFA800 straight (1, 0.658824, 0), alpb 0.5,
        // bmode 2 (LINEAR), no switches.
        auto dg = [&](float tR, float tG, float tB, float tA,
                      float bR, float bG, float bB, float bA,
                      float lR, float lG, float lB, float hR, float hG, float hB,
                      float strength, float oalpha, float low, float high, float feather,
                      float calpha, float hue, float satX, float satY, int flags, int bmode) {
            fxDeepGlow2Pixel(tR, tG, tB, tA, bR, bG, bB, bA, lR, lG, lB, hR, hG, hB,
                             strength, oalpha, low, high, feather, calpha, hue, satX, satY,
                             1.f, 0.658824f, 0.f, 0.5f, flags, bmode, &oR, &oG, &oB, &oA);
        };
        dg(0.1f, 0.2f, 0.3f, 0.5f, 0.4f, 0.3f, 0.2f, 0.6f, 0, 0, 0, 0, 0, 0,
           0.54f, 0.62f, 0.1f, 1.f, 0.3f, 1.f, 360.f, 0.f, 1.f, 32, 2);
        CHECK(std::fabs(oR - 0.347999f) < 1e-3f && std::fabs(oG - 0.385999f) < 1e-3f &&
              std::fabs(oB - 0.424005f) < 1e-3f && std::fabs(oA - 0.872000f) < 1e-3f,
              "deepglow linear typical (%g %g %g %g)", oR, oG, oB, oA);
        // The gate fully closed (luma 0.01 far under the low ramp): the input returns.
        dg(0.1f, 0.2f, 0.3f, 0.5f, 0.01f, 0.01f, 0.01f, 0.9f, 0, 0, 0, 0, 0, 0,
           0.54f, 0.62f, 0.5f, 1.f, 0.05f, 1.f, 360.f, 0.f, 1.f, 32, 2);
        CHECK(std::fabs(oR - 0.1f) < 1e-5f && std::fabs(oA - 0.5f) < 1e-5f, "deepglow gate closed");
        // SCREEN + cblend ADD at oalpha 1.5, saturated blur.
        dg(0.1f, 0.2f, 0.3f, 0.5f, 0.8f, 0.6f, 0.4f, 1.f, 0, 0, 0, 0, 0, 0,
           0.54f, 1.5f, 0.1f, 1.f, 0.3f, 1.f, 360.f, 0.f, 1.f, 32 | 8, 0);
        CHECK(std::fabs(oR - 2.269997f) < 2e-3f && std::fabs(oG - 1.425288f) < 2e-3f &&
              std::fabs(oB - 0.555025f) < 2e-3f && std::fabs(oA - 1.625f) < 2e-3f,
              "deepglow screen cblend add (%g %g %g %g)", oR, oG, oB, oA);
        // cblend REPLACE + LAYER.
        dg(0.1f, 0.2f, 0.3f, 0.5f, 0.8f, 0.6f, 0.4f, 1.f, 0, 0, 0, 0, 0, 0,
           0.54f, 1.5f, 0.1f, 1.f, 0.3f, 1.f, 360.f, 0.f, 1.f, 32 | 8 | 16, 3);
        CHECK(std::fabs(oR - 1.524998f) < 2e-3f && std::fabs(oG - 0.919114f) < 2e-3f &&
              std::fabs(oB - 0.075018f) < 2e-3f && std::fabs(oA - 1.625f) < 2e-3f,
              "deepglow cblend replace layer (%g %g %g %g)", oR, oG, oB, oA);
        // GCE (gamma-compensated extraction: pow 1/2.2, alpha squared).
        dg(0.1f, 0.2f, 0.3f, 0.5f, 0.25f, 0.5f, 0.75f, 0.8f, 0, 0, 0, 0, 0, 0,
           0.54f, 0.62f, 0.1f, 1.f, 0.3f, 1.f, 360.f, 0.f, 1.f, 32 | 2, 2);
        CHECK(std::fabs(oR - 0.364131f) < 2e-3f && std::fabs(oG - 0.561950f) < 2e-3f &&
              std::fabs(oB - 0.735208f) < 2e-3f && std::fabs(oA - 0.896800f) < 2e-3f,
              "deepglow gce (%g %g %g %g)", oR, oG, oB, oA);
        // CHROM, centre channel R (0): R from mid, G from low, B from high.
        dg(0.1f, 0.2f, 0.3f, 0.5f, 0.2f, 0.8f, 0.2f, 0.6f, 0.9f, 0.1f, 0.1f, 0.1f, 0.1f, 0.9f,
           0.54f, 0.62f, 0.1f, 1.f, 0.3f, 1.f, 360.f, 0.f, 1.f, 4, 2);
        CHECK(std::fabs(oR - 0.224002f) < 1e-3f && std::fabs(oG - 0.261999f) < 1e-3f &&
              std::fabs(oB - 0.858002f) < 1e-3f && std::fabs(oA - 0.872f) < 1e-3f,
              "deepglow chrom cc0 (%g %g %g %g)", oR, oG, oB, oA);
        // CHROM, centre channel B (2): R from low, G from high, B from mid.
        dg(0.1f, 0.2f, 0.3f, 0.5f, 0.2f, 0.8f, 0.2f, 0.6f, 0.9f, 0.1f, 0.1f, 0.1f, 0.1f, 0.9f,
           0.54f, 0.62f, 0.1f, 1.f, 0.3f, 1.f, 360.f, 0.f, 1.f, 4 | 64, 2);
        CHECK(std::fabs(oR - 0.657998f) < 1e-3f && std::fabs(oG - 0.261997f) < 1e-3f &&
              std::fabs(oB - 0.424011f) < 1e-3f && std::fabs(oA - 0.872f) < 1e-3f,
              "deepglow chrom cc2 (%g %g %g %g)", oR, oG, oB, oA);
        // strength 0: the input; with overd: the input times its alpha.
        dg(0.1f, 0.2f, 0.3f, 0.5f, 0.4f, 0.3f, 0.2f, 0.6f, 0, 0, 0, 0, 0, 0,
           0.f, 0.62f, 0.1f, 1.f, 0.3f, 1.f, 360.f, 0.f, 1.f, 32, 2);
        CHECK(std::fabs(oR - 0.1f) < 1e-6f && std::fabs(oA - 0.5f) < 1e-6f, "deepglow strength 0");
        dg(0.1f, 0.2f, 0.3f, 0.5f, 0.4f, 0.3f, 0.2f, 0.6f, 0, 0, 0, 0, 0, 0,
           0.f, 0.62f, 0.1f, 1.f, 0.3f, 1.f, 360.f, 0.f, 1.f, 32 | 1, 2);
        CHECK(std::fabs(oR - 0.05f) < 1e-6f && std::fabs(oA - 0.25f) < 1e-6f, "deepglow strength 0 overd");
        // COLOR dodge (a vec4 division, alpha included).
        dg(0.2f, 0.2f, 0.2f, 1.f, 0.4f, 0.3f, 0.2f, 0.6f, 0, 0, 0, 0, 0, 0,
           0.54f, 0.25f, 0.1f, 1.f, 0.3f, 1.f, 360.f, 0.f, 1.f, 32, 1);
        CHECK(std::fabs(oR - 0.222222f) < 1e-3f && std::fabs(oG - 0.216216f) < 1e-3f &&
              std::fabs(oB - 0.210527f) < 1e-3f && std::fabs(oA - 1.176471f) < 1e-3f,
              "deepglow color dodge (%g %g %g %g)", oR, oG, oB, oA);
        // Hue 180 + satvib (0.5, 1.5).
        dg(0.1f, 0.2f, 0.3f, 0.5f, 0.4f, 0.3f, 0.2f, 0.6f, 0, 0, 0, 0, 0, 0,
           0.54f, 0.62f, 0.1f, 1.f, 0.3f, 1.f, 180.f, 0.5f, 1.5f, 32, 2);
        CHECK(std::fabs(oR - 0.192520f) < 2e-3f && std::fabs(oG - 0.421295f) < 2e-3f &&
              std::fabs(oB - 0.650058f) < 2e-3f && std::fabs(oA - 0.872f) < 1e-3f,
              "deepglow hue satvib (%g %g %g %g)", oR, oG, oB, oA);
        // The gate condition quirk, verbatim: low 0 / high 0.2 -> NO gating (the glow
        // survives at full strength); high 0.0005 -> gating applies and kills it.
        dg(0.1f, 0.2f, 0.3f, 0.5f, 0.9f, 0.9f, 0.9f, 1.f, 0, 0, 0, 0, 0, 0,
           0.54f, 0.62f, 0.f, 0.2f, 0.3f, 1.f, 360.f, 0.f, 1.f, 32, 2);
        CHECK(std::fabs(oR - 0.658000f) < 1e-3f && std::fabs(oA - 1.12f) < 1e-3f, "deepglow gate quirk off");
        dg(0.1f, 0.2f, 0.3f, 0.5f, 0.9f, 0.9f, 0.9f, 1.f, 0, 0, 0, 0, 0, 0,
           0.54f, 0.62f, 0.f, 0.0005f, 0.3f, 1.f, 360.f, 0.f, 1.f, 32, 2);
        CHECK(std::fabs(oR - 0.1f) < 1e-5f && std::fabs(oA - 0.5f) < 1e-5f, "deepglow gate quirk on");
        // calpha 0.5 + LAYER: the base is halved, the glow laid over it.
        dg(0.1f, 0.2f, 0.3f, 0.5f, 0.4f, 0.3f, 0.2f, 0.6f, 0, 0, 0, 0, 0, 0,
           0.54f, 0.62f, 0.1f, 1.f, 0.3f, 0.5f, 360.f, 0.f, 1.f, 32, 3);
        CHECK(std::fabs(oR - 0.279399f) < 1e-3f && std::fabs(oG - 0.248799f) < 1e-3f &&
              std::fabs(oB - 0.218205f) < 1e-3f && std::fabs(oA - 0.529f) < 1e-3f,
              "deepglow calpha layer (%g %g %g %g)", oR, oG, oB, oA);
        // Drop Shadow: shadowb = colour x alpha x blurA under the layer at alphac.
        fxDropShadowPixel(0.3f, 0.3f, 0.3f, 0.6f, 0.5f, 0.f, 0.f, 0.f, 186.f / 255.f, 0.75f, 1.f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.3f) < 1e-5f && std::fabs(oA - 0.709412f) < 1e-4f, "dropshadow grey (%g %g)", oR, oA);
        fxDropShadowPixel(0.f, 0.f, 0.f, 0.f, 0.8f, 1.f, 0.f, 0.f, 1.f, 0.3f, 1.f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.24f) < 1e-5f && std::fabs(oA - 0.24f) < 1e-5f, "dropshadow red on empty");
        fxDropShadowPixel(0.4f, 0.2f, 0.1f, 0.8f, 0.5f, 0.f, 0.f, 0.f, 186.f / 255.f, 0.75f, 0.5f, &oR, &oG, &oB, &oA);
        CHECK(std::fabs(oR - 0.2f) < 1e-5f && std::fabs(oG - 0.1f) < 1e-5f && std::fabs(oA - 0.564118f) < 1e-4f,
              "dropshadow alphac (%g %g %g %g)", oR, oG, oB, oA);
        // Parse: gaussianblur (150) plain; deepglow main + EXT + EXT2 fold into one
        // op; a bare EXT2 / EXT is dropped; dropshadow (154) plain. All dedicated
        // (FXG_NONE).
        float flatDg[] = {151, 0.54f, 0.62f, 0.1f, 1, 0.3f, 0.5f, 2,
                          152, 1, 360, 0, 1, 16711812, 0.15f, 0,
                          153, 8392704, 8392704, 8392704, 32, 1, 0, 0};
        FxOp vops[kFxMaxOps];
        CHECK(fxParseOps(flatDg, 24, vops) == 1 && vops[0].type == FX_DEEP_GLOW2_AM &&
              vops[0].q[4] == 16711812.f && vops[0].r[3] == 32.f && vops[0].r[4] == 1.f,
              "deepglow parse folds EXT + EXT2");
        float flatBare2[] = {153, 8392704, 8392704, 8392704, 32, 1, 0, 0};
        CHECK(fxParseOps(flatBare2, 8, vops) == 0, "bare deepglow EXT2 dropped");
        float flatBare1[] = {152, 1, 360, 0, 1, 16711812, 0.15f, 0};
        CHECK(fxParseOps(flatBare1, 8, vops) == 0, "bare deepglow EXT dropped");
        float flatGb[] = {150, 0.42f, 0, 0, 0, 0, 0, 0, 154, 0, 0.729412f, 8456192, 0.3f, 0.6f, 1, 0};
        CHECK(fxParseOps(flatGb, 16, vops) == 2 && vops[0].type == FX_GAUSSIAN_BLUR_AM &&
              vops[1].type == FX_DROP_SHADOW_AM && vops[1].p[2] == 8456192.f,
              "gaussianblur + dropshadow parse");
        CHECK(fxShaderOf(FX_GAUSSIAN_BLUR_AM).group == FXG_NONE &&
              fxShaderOf(FX_DEEP_GLOW2_AM).group == FXG_NONE &&
              fxShaderOf(FX_DROP_SHADOW_AM).group == FXG_NONE, "v176 types are dedicated passes");
        FxOp id{};
        id.type = FX_GAUSSIAN_BLUR_AM;
        CHECK(fxIsIdentity(id) && (id.p[0] = 0.4f, !fxIsIdentity(id)), "gaussian identity at strength 0");
        id = FxOp{}; id.type = FX_DEEP_GLOW2_AM; id.r[3] = 32.f;
        CHECK(fxIsIdentity(id), "deepglow strength 0 (no overd) is identity");
        id.r[3] = 33.f;
        CHECK(!fxIsIdentity(id), "deepglow strength 0 with overd is not identity");
        id = FxOp{}; id.type = FX_DROP_SHADOW_AM; id.p[5] = 1.f;
        CHECK(fxIsIdentity(id), "dropshadow alphac 1 + alpha 0 is identity");
        id.p[4] = 0.6f; id.p[1] = 0.73f;
        CHECK(!fxIsIdentity(id), "dropshadow painting is not identity");
    }
    {   // v177 Queue 15(f) tier-5 twins: pins hand-computed from the defs in
        // double precision (the design record carries the arithmetic).
        float o[4];
        auto konstFn = [](const void* ctx, int, float, float, float out[4]) {
            const float* c = (const float*)ctx;
            out[0] = c[0]; out[1] = c[1]; out[2] = c[2]; out[3] = c[3];
        };
        auto konstS = [&konstFn](float (&c)[4]) { return FxExtrudeSampler{konstFn, c}; };
        static float KC_BM[4] = {0.2f, 0.4f, 0.6f, 0.8f};
        static float KC_NS[4] = {0.6f, 0.3f, 0.9f, 1.f};
        static float KC_WH[4] = {1.f, 1.f, 1.f, 1.f};
        static float KC_GS[4] = {0.9f, 0.9f, 0.9f, 1.f};
        static float KC_GF[4] = {0.1f, 0.2f, 0.3f, 1.f};
        static float KC_LS[4] = {0.4f, 0.2f, 0.1f, 0.5f};
        static float KC_SP[4] = {0.1f, 0.2f, 0.3f, 0.4f};
        // bbmaker: opaque black background band; A: centred, start 30, angle 90
        // (toward +y): band 1 -> tex x tex.a. B: start 32.85 at y 0.95: outside
        // the band, only the background x tex.a remains.
        FxOp bm{}; bm.type = FX_BBMAKER_AM;
        bm.p[0] = 30.f; bm.p[1] = 90.f; bm.p[2] = 0.f; bm.p[3] = 1.f;
        bm.p[4] = 0.001f; bm.p[5] = 1.f; bm.p[6] = 0.f; bm.q[0] = 0.f; bm.q[1] = 1.f;
        FxExtrudeSampler BS = konstS(KC_BM);
        fxBbMakerPixel(bm, 0.5f, 0.5f, 100, 100, BS, o);
        CHECK(std::fabs(o[0] - 0.16f) < 1e-4f && std::fabs(o[3] - 0.768f) < 1e-4f,
              "bbmaker band (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        bm.p[0] = 32.85f;
        fxBbMakerPixel(bm, 0.5f, 0.95f, 100, 100, BS, o);
        CHECK(o[0] == 0.f && o[1] == 0.f && o[2] == 0.f && std::fabs(o[3] - 0.64f) < 1e-4f,
              "bbmaker outside band (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // unsharpmask: tex 0 = base, tex 1 = its gaussianblur buffer.
        FxExtrudeSampler US; US.sample = [](const void*, int tex, float, float, float out[4]) {
            if (tex == 0) { out[0] = 0.5f; out[1] = 0.25f; out[2] = 0.125f; out[3] = 0.8f; }
            else { out[0] = 0.2f; out[1] = 0.2f; out[2] = 0.2f; out[3] = 0.8f; }
        }; US.ctx = nullptr;
        FxOp um{}; um.type = FX_UNSHARP_MASK_AM; um.p[0] = 0.05f; um.p[1] = 0.5f; um.p[2] = 0.5f;
        fxUnsharpMaskPixel(um, 0.5f, 0.5f, 100, 100, US, o);
        CHECK(std::fabs(o[0] - 0.5000070f) < 1e-4f && std::fabs(o[1] - 0.2500012f) < 5e-5f &&
              std::fabs(o[2] - 0.1249983f) < 5e-5f && std::fabs(o[3] - 0.8f) < 1e-5f,
              "unsharp flat region (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        FxExtrudeSampler UB; UB.sample = [](const void*, int tex, float, float, float out[4]) {
            if (tex == 0) { out[0] = 0.5f; out[1] = 0.25f; out[2] = 0.125f; out[3] = 0.8f; }
            else { out[0] = 0.f; out[1] = 0.f; out[2] = 0.f; out[3] = 0.8f; }
        }; UB.ctx = nullptr;
        fxUnsharpMaskPixel(um, 0.5f, 0.5f, 100, 100, UB, o);
        CHECK(std::fabs(o[0] - 0.75f) < 1e-4f && std::fabs(o[1] - 0.375f) < 1e-4f &&
              std::fabs(o[2] - 0.1875f) < 1e-4f, "unsharp full edge (%g %g %g)", o[0], o[1], o[2]);
        // newsharpen on a constant field: the streak average is the field, so
        // blured = 0 and the screen composite returns the input (the def's
        // pass list: ds copies, H streak, V streak + composite, x iter).
        FxOp np{}; np.type = FX_NEWSHARPEN_AM;
        np.p[0] = 0.15f; np.p[3] = 0.1f; np.p[4] = 2.f;
        FxExtrudeSampler NS = konstS(KC_NS);
        fxNewSharpenAmPixel(np, 0.5f, 0.5f, 100, 100, NS, o);
        CHECK(std::fabs(o[0] - 0.6f) < 1e-4f && std::fabs(o[1] - 0.3f) < 1e-4f &&
              std::fabs(o[2] - 0.9f) < 1e-4f && std::fabs(o[3] - 1.f) < 1e-5f,
              "newsharpen constant (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        FxOp nw{}; nw.type = FX_NEWSHARPEN_AM;
        nw.p[0] = 0.15f; nw.p[1] = 1.f; nw.p[2] = 1.f; nw.p[3] = 0.1f; nw.p[4] = 2.f;
        fxNewSharpenAmPixel(nw, 0.5f, 0.5f, 100, 100,
                            konstS(KC_WH), o);
        CHECK(std::fabs(o[0]) < 1e-5f && std::fabs(o[2]) < 1e-5f && std::fabs(o[3] - 1.f) < 1e-5f,
              "newsharpen white only (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // A quadratic field: the streak average exceeds the centre by 2d^2/3,
        // so blured goes slightly negative and the screen composite dips.
        auto quad = [](const void*, int, float u, float, float out[4]) {
            out[0] = u * u; out[1] = u * u; out[2] = u * u; out[3] = 1.f;
        };
        fxNewSharpenAmPixel(np, 0.5f, 0.5f, 100, 100, FxExtrudeSampler{quad, nullptr}, o);
        CHECK(std::fabs(o[0] - 0.2499648f) < 2e-5f && std::fabs(o[3] - 1.f) < 1e-5f,
              "newsharpen quadratic (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // gradshadow (blend kind 0): mode 0 fill at the ramp midpoint.
        FxOp gs{}; gs.type = FX_GRADSHADOW_AM;
        gs.p[0] = 0.95f; gs.p[1] = 1.5f; gs.p[2] = 100.f; gs.p[3] = 90.f; gs.p[4] = 0.f;
        gs.p[5] = 1.f; gs.p[6] = 1.f; gs.q[0] = 0.f; gs.q[1] = 1.f; gs.q[2] = 1.f;
        fxAmBlendGenPixel(0, gs, 0.5f, 0.5f, 100, 100,
                          konstS(KC_GS), o);
        CHECK(std::fabs(o[0] - 0.382725f) < 5e-4f && std::fabs(o[3] - 1.f) < 1e-4f,
              "gradshadow ramp (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // gradientflow (kind 1): the corpus dark-blue palette, iTime 1.
        FxOp gf{}; gf.type = FX_GRADIENTFLOW_AM;
        gf.p[0] = 1.f; gf.p[2] = 8390656.f; gf.p[3] = 1.f; gf.p[5] = 1.f;
        gf.q[0] = 801.f; gf.q[1] = 7275.f; gf.q[2] = 4956.f; gf.q[3] = 51.f;
        gf.q[4] = 0.f; gf.q[5] = 22.f; gf.q[6] = 8394753.f; gf.r[0] = 1.f;
        fxAmBlendGenPixel(1, gf, 0.5f, 0.5f, 100, 100,
                          konstS(KC_GF), o);
        CHECK(std::fabs(o[0]) < 3e-3f && std::fabs(o[1] - 0.0489666f) < 3e-3f &&
              std::fabs(o[2] - 0.2882808f) < 3e-3f && std::fabs(o[3] - 1.f) < 1e-4f,
              "gradientflow palette (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // partx (kind 2): pinned at aStep-0 sample points, where the def's
        // fract hash chains are exact (off-axis points are float-chaotic in
        // the def itself — 10 x fract(aStep x fract(...) x 45.1) amplifies an
        // ulp into a different star field, on any implementation).
        FxOp px{}; px.type = FX_PARTX_AM;
        px.p[0] = 16777215.f; px.p[1] = 1.f; px.p[2] = 0.f; px.p[3] = 0.f;
        px.p[4] = 200.f; px.p[5] = 0.2f; px.p[6] = 1.f;
        px.q[0] = 0.f; px.q[1] = 1.f; px.q[2] = 0.f; px.q[3] = 8390656.f;
        px.q[4] = 8394753.f; px.q[5] = 1.f; px.q[6] = 3.f; px.r[0] = 1.f;
        fxAmBlendGenPixel(2, px, 0.7f, 0.5f, 100, 100,
                          konstS(KC_BM), o);
        CHECK(std::fabs(o[0] - 0.16f) < 1e-4f && std::fabs(o[1] - 0.32f) < 1e-4f &&
              std::fabs(o[2] - 0.48f) < 1e-4f && std::fabs(o[3] - 0.64f) < 1e-4f,
              "partx no star on axis (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        px.p[5] = 0.72f;
        fxAmBlendGenPixel(2, px, 0.9f, 0.49f, 100, 100,
                          konstS(KC_BM), o);
        CHECK(std::fabs(o[0] - 0.2031101f) < 5e-4f && std::fabs(o[1] - 0.3523326f) < 5e-4f &&
              std::fabs(o[2] - 0.5015551f) < 5e-4f && std::fabs(o[3] - 0.6507775f) < 5e-4f,
              "partx star (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // edgeline (kind 3): an x-luma ramp gives a pure vertical gradient.
        FxOp el{}; el.type = FX_EDGELINE_AM;
        el.p[0] = 1.5f; el.p[1] = 0.14f; el.p[2] = 3.99f; el.p[3] = 65535.f;
        el.p[4] = 1.f; el.p[5] = 0.f; el.p[6] = 1.f;
        el.q[0] = 1.f; el.q[1] = 0.f; el.q[2] = 1.f; el.q[3] = 1.f; el.q[4] = 0.f;
        auto ramp = [](const void*, int, float u, float, float out[4]) {
            out[0] = u; out[1] = u; out[2] = u; out[3] = 0.5f;
        };
        fxAmBlendGenPixel(3, el, 0.5f, 0.5f, 100, 100, FxExtrudeSampler{ramp, nullptr}, o);
        CHECK(std::fabs(o[0]) < 1e-4f && std::fabs(o[1] - 0.1191349f) < 5e-4f &&
              std::fabs(o[2] - 0.1191349f) < 5e-4f && std::fabs(o[3] - 0.125f) < 1e-4f,
              "edgeline ramp (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // lightsweep (kind 4): at the centre the sweep value is sin(0)^3 = 0.
        FxOp ls{}; ls.type = FX_LIGHTSWEEP_AM;
        ls.p[0] = 0.8f; ls.p[1] = -22.5f; ls.p[2] = 8390656.f; ls.p[3] = 0.53f; ls.p[5] = 3.f; ls.p[6] = 0.79f;
        ls.q[0] = 8394753.f; ls.q[1] = 0.f; ls.q[2] = 1.f; ls.q[3] = 16777215.f;
        ls.q[4] = 1.f; ls.q[5] = 1.f; ls.q[6] = 1.f; ls.r[0] = 0.21f;
        fxAmBlendGenPixel(4, ls, 0.5f, 0.5f, 100, 100,
                          konstS(KC_LS), o);
        CHECK(std::fabs(o[0] - 0.1949153f) < 5e-4f && std::fabs(o[1] - 0.0974576f) < 5e-4f &&
              std::fabs(o[3] - 0.25f) < 1e-4f, "lightsweep centre (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // 3colorgrad (kind 5): the flat ramp midpoint colours, mode 0.
        FxOp cg{}; cg.type = FX_COLORGRAD3_AM;
        cg.p[0] = 986895.f; cg.p[1] = 1.f; cg.p[2] = 0.f; cg.p[3] = 0.f;
        cg.p[4] = 986895.f; cg.p[5] = 1.f; cg.p[6] = 0.5f;
        cg.q[0] = 0.5f; cg.q[1] = 0.f; cg.q[2] = 0.6f; cg.q[3] = 0.f;
        cg.q[4] = 1.f; cg.q[5] = 0.f; cg.q[6] = 1.f; cg.r[0] = 1.f;
        fxAmBlendGenPixel(5, cg, 0.75f, 0.5f, 100, 100,
                          konstS(KC_BM), o);
        CHECK(std::fabs(o[0] - 0.0421647f) < 5e-4f && std::fabs(o[1] - 0.0592314f) < 5e-4f &&
              std::fabs(o[2] - 0.0762981f) < 5e-4f && std::fabs(o[3] - 0.4949330f) < 5e-4f,
              "3colorgrad ramp (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // deepglow v1: hue 360 = no rotation; the glow radius grows the alpha.
        FxOp d1{}; d1.type = FX_DEEP_GLOW_AM;
        d1.p[0] = 0.365f; d1.p[1] = 1.51f; d1.p[2] = 360.f; d1.p[3] = 1.f; d1.p[4] = 1.f;
        d1.p[5] = 0.5f; d1.p[6] = 11141120.f;
        d1.q[3] = 1.f; d1.q[4] = 8390656.f; d1.q[6] = 0.75f; d1.r[0] = 0.f; d1.r[1] = 1.f; d1.r[2] = 3.f; d1.r[3] = 0.f;
        FxExtrudeSampler D1; D1.sample = [](const void*, int tex, float, float, float out[4]) {
            if (tex == 0) { out[0] = 0.2f; out[1] = 0.1f; out[2] = 0.05f; out[3] = 0.6f; }
            else if (tex == 1) { out[0] = 0.1f; out[1] = 0.2f; out[2] = 0.3f; out[3] = 0.5f; }
            else { out[0] = 0.f; out[1] = 0.f; out[2] = 0.f; out[3] = 0.f; }
        }; D1.ctx = nullptr;
        fxDeepGlow1Pixel(d1, 0.25f, 0.25f, 100, 100, D1, o);
        CHECK(std::fabs(o[0] - 0.3281920f) < 5e-4f && std::fabs(o[1] - 0.3563814f) < 5e-4f &&
              std::fabs(o[2] - 0.4345754f) < 5e-4f && std::fabs(o[3] - 1.2409545f) < 5e-4f,
              "deepglow v1 (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // spill-cleanup: at a tiny strength the streaks return the blur, so the
        // pin isolates the final spill composite.
        FxOp sp{}; sp.type = FX_SPILL_CLEANUP_AM; sp.p[0] = 0.01f;
        FxExtrudeSampler SP; SP.sample = [](const void*, int tex, float, float, float out[4]) {
            if (tex == 0) { out[0] = 0.3f; out[1] = 0.6f; out[2] = 0.9f; out[3] = 0.5f; }
            else { out[0] = 0.15f; out[1] = 0.3f; out[2] = 0.45f; out[3] = 0.25f; }
        }; SP.ctx = nullptr;
        fxSpillCleanupAmPixel(sp, 0.5f, 0.5f, 100, 100, SP, o);
        CHECK(std::fabs(o[0] - 0.3375f) < 5e-4f && std::fabs(o[1] - 0.675f) < 5e-4f &&
              std::fabs(o[2] - 1.0125f) < 5e-4f && std::fabs(o[3] - 0.5625f) < 1e-4f,
              "spill final (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // The streak chain on a constant field: composite(blur, blur) twice.
        FxOp sp2{}; sp2.type = FX_SPILL_CLEANUP_AM; sp2.p[0] = 0.48f;
        fxSpillCleanupAmPixel(sp2, 0.5f, 0.5f, 100, 100,
                              konstS(KC_SP), o);
        CHECK(std::fabs(o[0] - 0.1583204f) < 5e-4f && std::fabs(o[1] - 0.3166408f) < 5e-4f &&
              std::fabs(o[2] - 0.4749612f) < 5e-4f && std::fabs(o[3] - 0.6332815f) < 5e-4f,
              "spill streak chain (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // Parse folds for the tier.
        float flatBm[] = {155, 30, 90, 0, 1, 0.001f, 1, 0, 156, 0, 1, 0, 0, 0, 0, 0};
        FxOp tops[kFxMaxOps];
        CHECK(fxParseOps(flatBm, 16, tops) == 1 && tops[0].type == FX_BBMAKER_AM &&
              tops[0].q[1] == 1.f, "bbmaker EXT folds into q");
        float flatGf[] = {161, 1, 0, 0, 1, 0, 1, 0, 162, 801, 7275, 4956, 51, 0, 22, 8394753,
                          163, 1, 0, 0, 0, 0, 0, 0};
        CHECK(fxParseOps(flatGf, 24, tops) == 1 && tops[0].type == FX_GRADIENTFLOW_AM &&
              tops[0].q[5] == 22.f && tops[0].r[0] == 1.f, "gradientflow folds EXT + EXT2");
        float flatD1[] = {175, 0.365f, 1.51f, 360, 1, 1, 0.5f, 11141120,
                          176, 0, 0, 0, 0, 0, 0, 0, 177, 0, 1, 3, 0, 0, 0, 0};
        CHECK(fxParseOps(flatD1, 24, tops) == 1 && tops[0].type == FX_DEEP_GLOW_AM &&
              tops[0].r[1] == 1.f, "deepglow folds EXT + EXT2");
        float flatPx[] = {164, 16777215, 1, 0, 0, 200, 0.2f, 1, 165, 1.39f, 1, 0, 8390656, 8394753, 1, 3,
                          166, 1, 0, 0, 0, 0, 0, 0};
        CHECK(fxParseOps(flatPx, 24, tops) == 1 && tops[0].type == FX_PARTX_AM &&
              tops[0].q[0] == 1.39f && tops[0].r[0] == 1.f, "partx folds EXT + EXT2 (EVO in q, alpha in r)");
        FxOp id2{};
        id2.type = FX_NEWSHARPEN_AM; id2.p[0] = 0.005f;
        CHECK(fxIsIdentity(id2), "newsharpen at 0.005 is identity (the def's gate)");
        id2.p[0] = 0.15f;
        CHECK(!fxIsIdentity(id2), "newsharpen at 0.15 is not identity");
        id2 = FxOp{}; id2.type = FX_PARTX_AM;
        CHECK(fxIsIdentity(id2), "partx with alpha 0 is identity");
        id2.r[0] = 1.f;
        CHECK(!fxIsIdentity(id2), "partx with alpha 1 is not identity");
        // opticcompesation (v179, build 141): the EXT group folds into q; the op is
        // never identity (the def's factor alpha is tex.a + 1 / (2 x iterations), so
        // even zero distortion is not the plain layer); an orphan EXT is dropped.
        float flatOc[] = {183, 10, 2.19f, 0, 0, 0, 1, 1, 184, 0, 0, 1, 1, 1, 0, 0};
        CHECK(fxParseOps(flatOc, 16, tops) == 1 && tops[0].type == FX_OPTIC_COMP_AM &&
              tops[0].q[4] == 1.f && tops[0].p[1] == 2.19f, "opticcomp EXT folds into q");
        FxOp oc{}; oc.type = FX_OPTIC_COMP_AM; oc.p[0] = 10.f;
        CHECK(!fxIsIdentity(oc), "opticcomp is never identity (factor alpha)");
        float flatOrphan[] = {184, 0, 0, 1, 1, 1, 0, 0};
        CHECK(fxParseOps(flatOrphan, 8, tops) == 0, "orphan opticcomp EXT dropped");
    }
    {   // v180 Queue 15(j) second-wave twins: pins hand-computed from the defs
        // (Ultra originals in hunt-staging/round5, TrueMotion SkSL in defs-batch8).
        float o[4];
        auto konstFn = [](const void* ctx, int, float, float, float out[4]) {
            const float* c = (const float*)ctx;
            out[0] = c[0]; out[1] = c[1]; out[2] = c[2]; out[3] = c[3];
        };
        auto konstS = [&konstFn](float (&c)[4]) { return FxExtrudeSampler{konstFn, c}; };
        static float KC_A[4] = {0.2f, 0.4f, 0.6f, 0.8f};
        static float KC_B[4] = {0.3f, 0.6f, 0.9f, 0.7f};
        static float KC_G[4] = {0.5f, 0.5f, 0.5f, 1.f};
        static float KC_W[4] = {1.f, 1.f, 1.f, 1.f};
        // negative on a constant field: invLum 0.637, unsharp neutral, contrast
        // about 0.5, the black / white remap: (0.6918 - 0.08) / 0.84 = 0.728333.
        FxOp ng{}; ng.type = FX_NEGATIVE_AM;
        ng.p[0] = 1.4f; ng.p[2] = 0.08f; ng.p[3] = 0.92f; ng.p[4] = 0.6f;
        fxNegativeAmPixel(ng, 0.5f, 0.5f, konstS(KC_A), 0.f, o);
        CHECK(std::fabs(o[0] - 0.728333f) < 1e-4f && std::fabs(o[3] - 0.8f) < 1e-5f,
              "negative constant (%g %g)", o[0], o[3]);
        ng.p[5] = 0.2f;   // grain at (0.5, 0.5), seed 0: hash 0.65661 -> +0.0313
        fxNegativeAmPixel(ng, 0.5f, 0.5f, konstS(KC_A), 0.f, o);
        CHECK(std::fabs(o[0] - 0.759655f) < 0.01f, "negative grain (%g)", o[0]);
        // tritone: lum 0.45375 -> shadows->midtones at 0.9075 (midtones 0x71533B).
        FxOp tr{}; tr.type = FX_TRITONE_AM;
        tr.p[0] = 16777215.f; tr.p[1] = 1.f; tr.p[2] = 0x71533B; tr.p[3] = 1.f;
        tr.p[4] = 0.f; tr.p[5] = 1.f; tr.p[6] = 0.f;
        { float tex[4] = {0.25f, 0.5f, 0.75f, 1.f};
          fxTritoneAmPixel(tr, tex, o); }
        CHECK(std::fabs(o[0] - 0.402147f) < 1e-4f && std::fabs(o[1] - 0.295382f) < 1e-4f &&
              std::fabs(o[2] - 0.209971f) < 1e-4f && std::fabs(o[3] - 1.f) < 1e-5f,
              "tritone mid ramp (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // unmult A: max 0.9 -> aBase 1, feather mixes toward the raw average 0.9
        // (aFeather 0.925), cleanup inert at cp 0 -> aOut 0.4625.
        FxOp uu{}; uu.type = FX_UNMULT_AM;
        uu.p[1] = 1.f; uu.p[2] = 10.f; uu.p[4] = 1.f;
        static float KC_U[4] = {0.6f, 0.3f, 0.9f, 0.5f};
        fxUnmultAmPixel(uu, 0.5f, 0.5f, 100, 100, konstS(KC_U), o);
        CHECK(std::fabs(o[0] - 0.6f) < 1e-5f && std::fabs(o[3] - 0.4625f) < 1e-4f,
              "unmult matte (%g %g)", o[0], o[3]);
        uu.p[6] = 1.f;   // previewMatte
        fxUnmultAmPixel(uu, 0.5f, 0.5f, 100, 100, konstS(KC_U), o);
        CHECK(std::fabs(o[0] - 0.4625f) < 1e-4f && std::fabs(o[3] - 0.4625f) < 1e-4f,
              "unmult preview (%g %g)", o[0], o[3]);
        // unmult B: dim constant, preserveLuma divides by the matte 0.163.
        FxOp ub{}; ub.type = FX_UNMULT_AM;
        ub.p[1] = 1.f; ub.p[2] = 10.f; ub.p[4] = 1.f; ub.p[5] = 1.f;
        static float KC_UB[4] = {0.1f, 0.05f, 0.02f, 1.f};
        fxUnmultAmPixel(ub, 0.5f, 0.5f, 100, 100, konstS(KC_UB), o);
        CHECK(std::fabs(o[0] - 0.613497f) < 1e-3f && std::fabs(o[1] - 0.306748f) < 1e-3f &&
              std::fabs(o[3] - 0.163f) < 1e-4f, "unmult preserve luma (%g %g %g)", o[0], o[1], o[3]);
        // denoise on a constant field: the average is the field; the tail
        // premultiplies by tex.a (alpha 1 leaves a at tex.a).
        FxOp dn{}; dn.type = FX_DENOISE_AM; dn.p[0] = 10.f; dn.p[1] = 1.5f; dn.p[2] = 1.f;
        static float KC_D[4] = {0.3f, 0.6f, 0.9f, 0.5f};
        fxDenoiseAmPixel(dn, 0.5f, 0.5f, konstS(KC_D), o);
        CHECK(std::fabs(o[0] - 0.15f) < 1e-4f && std::fabs(o[1] - 0.3f) < 1e-4f &&
              std::fabs(o[2] - 0.45f) < 1e-4f && std::fabs(o[3] - 0.5f) < 1e-4f,
              "denoise constant (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // minimax over a u-gradient: radius 1 at picW 100 -> neighbours at +-0.01.
        FxExtrudeSampler gradS; gradS.sample = [](const void*, int, float u, float, float out[4]) {
            out[0] = out[1] = out[2] = u; out[3] = 1.f;
        }; gradS.ctx = nullptr;
        FxOp mm{}; mm.type = FX_MINIMAX_AM; mm.p[0] = 1.f; mm.p[1] = 1.f; mm.p[2] = 4.f;
        fxMinimaxAmPixel(mm, 0, 0.5f, 0.5f, 100, 100, gradS, o);
        CHECK(std::fabs(o[0] - 0.51f) < 1e-4f, "minimax max (%g)", o[0]);
        mm.p[0] = 0.f;
        fxMinimaxAmPixel(mm, 0, 0.5f, 0.5f, 100, 100, gradS, o);
        CHECK(std::fabs(o[0] - 0.49f) < 1e-4f, "minimax min (%g)", o[0]);
        // keepEdge pass 1: the field is constant along v (blurred 0.5), the
        // original is 0.2 grey on tex 1; ef = min(1, 1 * 0.01 * 10) = 0.1.
        FxExtrudeSampler keS; keS.sample = [](const void*, int tex, float u, float, float out[4]) {
            if (tex == 0) { out[0] = out[1] = out[2] = u; out[3] = 1.f; }
            else { out[0] = out[1] = out[2] = 0.2f; out[3] = 1.f; }
        }; keS.ctx = nullptr;
        mm.p[0] = 1.f; mm.p[4] = 1.f;
        fxMinimaxAmPixel(mm, 1, 0.5f, 0.5f, 100, 100, keS, o);
        CHECK(std::fabs(o[0] - 0.23f) < 1e-4f && std::fabs(o[3] - 1.f) < 1e-5f,
              "minimax keepEdge (%g %g)", o[0], o[3]);
        // rgbglitch at progressb 0: the def's early-out returns the pixel.
        FxOp rg{}; rg.type = FX_RGBGLITCH_AM;
        fxRgbGlitchAmPixel(rg, 0.5f, 0.5f, konstS(KC_A), o);
        CHECK(o[0] == 0.2f && o[1] == 0.4f && o[2] == 0.6f && o[3] == 0.8f,
              "rgbglitch early-out (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // fisheye at power 0 (and at the centre for power 0.5): the sample is
        // the pixel itself; the write is src * (src.a * alpha).
        FxOp fe{}; fe.type = FX_FISHEYE_AM; fe.p[1] = 0.5f; fe.p[2] = 0.5f; fe.p[3] = 1.f; fe.p[4] = 1.f;
        fxFisheyeAmPixel(fe, 0.5f, 0.5f, 1.f, konstS(KC_A), o);
        CHECK(std::fabs(o[0] - 0.16f) < 1e-5f && std::fabs(o[3] - 0.8f) < 1e-5f,
              "fisheye power 0 (%g %g)", o[0], o[3]);
        fe.p[0] = 0.5f;
        fxFisheyeAmPixel(fe, 0.5f, 0.5f, 1.f, konstS(KC_A), o);
        CHECK(std::fabs(o[0] - 0.16f) < 1e-5f && std::fabs(o[3] - 0.8f) < 1e-5f,
              "fisheye centre (%g %g)", o[0], o[3]);
        // vortex on a constant field: mode 2 returns the pixel; mode 1 is the
        // def's composite: m = the per-channel vec4s / 3 (so m.rgb = oc / 3 and
        // m.a = mean(oc.rgb)), l = the Rec.709 luma of the centre channel's
        // blur (0.55788 here), out = l + m * (1 - l) in all four components.
        FxOp vx{}; vx.type = FX_VORTEX_BLUR_AM;
        vx.p[1] = 0.3f; vx.p[2] = 0.15f; vx.p[3] = 1.f; vx.p[4] = 2.f; vx.p[5] = 8390656.f;
        fxVortexBlurAmPixel(vx, 0.5f, 0.5f, 100, 100, konstS(KC_B), o);
        CHECK(std::fabs(o[0] - 0.3f) < 1e-4f && std::fabs(o[3] - 0.7f) < 1e-4f,
              "vortex mode 2 (%g %g)", o[0], o[3]);
        vx.p[4] = 1.f;
        fxVortexBlurAmPixel(vx, 0.5f, 0.5f, 100, 100, konstS(KC_B), o);
        CHECK(std::fabs(o[0] - 0.602092f) < 1e-3f && std::fabs(o[1] - 0.646304f) < 1e-3f &&
              std::fabs(o[2] - 0.690516f) < 1e-3f && std::fabs(o[3] - 0.823152f) < 1e-3f,
              "vortex mode 1 (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // refraction at frequency 0: the cos field is constant, dx = dy = 0,
        // alphaa = 1, the mix returns the pixel.
        FxOp rf{}; rf.type = FX_REFRACTION_AM;
        rf.p[0] = 1.f; rf.p[3] = 1.f; rf.p[5] = 2.4f;
        rf.q[0] = 0.5f; rf.q[1] = 8.f; rf.q[2] = 7.f; rf.q[3] = 60.f; rf.q[4] = 700.f;
        rf.q[5] = 0.012f; rf.q[6] = 200.f; rf.r[0] = 0.2f; rf.r[1] = 0.3f; rf.r[2] = 0.3f; rf.r[3] = 1.f;
        fxRefractionAmPixel(rf, 0.3f, 0.6f, 100, 100, 0.f, konstS(KC_A), o);
        CHECK(std::fabs(o[0] - 0.2f) < 1e-4f && std::fabs(o[3] - 0.8f) < 1e-4f,
              "refraction flat field (%g %g)", o[0], o[3]);
        // twitch: amplitude 0 (and the samples-1 guard) -> the pixel, alpha 1.
        FxOp tw{}; tw.type = FX_TWITCH_AM; tw.p[1] = 0.03f; tw.p[2] = 16.f;
        fxTwitchAmPixel(tw, 0.5f, 0.5f, 0.f, konstS(KC_A), o);
        CHECK(std::fabs(o[0] - 0.2f) < 1e-5f && std::fabs(o[3] - 1.f) < 1e-5f,
              "twitch still (%g %g)", o[0], o[3]);
        tw.p[0] = 0.3f; tw.p[2] = 1.f;
        fxTwitchAmPixel(tw, 0.5f, 0.5f, 0.f, konstS(KC_A), o);
        CHECK(std::fabs(o[0] - 0.2f) < 1e-5f && std::fabs(o[3] - 1.f) < 1e-5f,
              "twitch one tap (%g %g)", o[0], o[3]);
        // turbulent2 at intensity 0: the coordinate is the pixel's own.
        FxOp t2{}; t2.type = FX_TURBULENT2_AM;
        t2.p[2] = 4.f; t2.p[3] = 1.f; t2.p[6] = 1.f; t2.q[1] = 6.f;
        { float uv[2]; fxTurbulent2AmUv(t2, 0.3f, 0.7f, 1.f, uv);
          CHECK(std::fabs(uv[0] - 0.3f) < 1e-6f && std::fabs(uv[1] - 0.7f) < 1e-6f,
                "turbulent2 zero (%g %g)", uv[0], uv[1]); }
        // shapefield at density 0: every cell's size smoothsteps to 0 -> clear.
        FxOp sf{}; sf.type = FX_SHAPEFIELD_AM;
        sf.p[6] = 1.f; sf.q[0] = 0.5f; sf.q[4] = 15.f; sf.q[5] = 1.f; sf.q[6] = 1.f;
        sf.r[0] = 1.f; sf.r[3] = 16733610.f; sf.r[6] = 1.f;
        fxShapefieldAmPixel(sf, 0.5f, 0.5f, 100, 100, konstS(KC_A), o);
        CHECK(o[0] == 0.f && o[1] == 0.f && o[2] == 0.f && o[3] == 0.f,
              "shapefield empty (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // halftone_dots: grey 0.5 at the pattern centre -> m 0.5; white -> m 1;
        // white with dotsMode 1 -> m 0.
        FxOp ht{}; ht.type = FX_HALFTONE_DOTS_AM;
        ht.p[1] = 45.f; ht.p[2] = 33.f; ht.p[3] = 1.f; ht.p[4] = 4.f;
        ht.q[0] = 16777215.f; ht.q[4] = 1.f;
        fxHalftoneDotsAmPixel(ht, 0.5f, 0.5f, 100, 100, konstS(KC_G), o);
        CHECK(std::fabs(o[0] - 0.5f) < 1e-4f && std::fabs(o[3] - 1.f) < 1e-5f,
              "halftone mid (%g %g)", o[0], o[3]);
        fxHalftoneDotsAmPixel(ht, 0.5f, 0.5f, 100, 100, konstS(KC_W), o);
        CHECK(std::fabs(o[0] - 1.f) < 1e-4f, "halftone white (%g)", o[0]);
        ht.p[0] = 1.f;
        fxHalftoneDotsAmPixel(ht, 0.5f, 0.5f, 100, 100, konstS(KC_W), o);
        CHECK(std::fabs(o[0]) < 1e-4f && std::fabs(o[3] - 1.f) < 1e-5f,
              "halftone inverted (%g %g)", o[0], o[3]);
        // cc_glass on a constant field: normal (0, 0, 1); distant light at
        // direction 0 / height 50 -> diff 0.70711, spec 0.20526 at roughness
        // 0.05; final = c * (0.4 + 0.35355) + 0.10263.
        FxOp cg{}; cg.type = FX_CC_GLASS_AM;
        cg.p[1] = 20.f; cg.p[2] = 25.f; cg.p[5] = 100.f; cg.p[6] = 16777215.f;
        cg.q[0] = 50.f; cg.q[1] = 0.5f; cg.q[2] = 0.5f; cg.q[4] = 40.f; cg.q[5] = 50.f; cg.q[6] = 50.f;
        cg.r[0] = 0.05f;
        fxCcGlassAmPixel(cg, 0.5f, 0.5f, konstS(KC_A), o);
        CHECK(std::fabs(o[0] - 0.253341f) < 1e-3f && std::fabs(o[1] - 0.404052f) < 1e-3f &&
              std::fabs(o[2] - 0.554763f) < 1e-3f && std::fabs(o[3] - 0.8f) < 1e-5f,
              "cc_glass lit (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // bokeh on a constant field: the weighted average is the field, alpha 1.
        FxOp bk{}; bk.type = FX_BOKEH_AM;
        bk.p[1] = 1.f; bk.p[2] = 1.f; bk.p[3] = 0.02f; bk.p[4] = 0.5f; bk.p[5] = 4.f; bk.p[6] = 8.f;
        fxBokehAmPixel(bk, 0.5f, 0.5f, 1.f, konstS(KC_B), o);
        CHECK(std::fabs(o[0] - 0.3f) < 1e-4f && std::fabs(o[3] - 1.f) < 1e-5f,
              "bokeh constant (%g %g)", o[0], o[3]);
        // lensblurobs mode 0 on a constant field: (bokeh * bright) * tex.a.
        FxOp lb{}; lb.type = FX_LENSBLUR_OBS_AM;
        lb.p[0] = 10.f; lb.p[2] = 1.f; lb.p[3] = 1.f; lb.p[4] = 1.f;
        fxLensBlurObsAmPixel(lb, 0.5f, 0.5f, konstS(KC_B), o);
        CHECK(std::fabs(o[0] - 0.21f) < 1e-4f && std::fabs(o[1] - 0.42f) < 1e-4f &&
              std::fabs(o[2] - 0.63f) < 1e-4f && std::fabs(o[3] - 0.7f) < 1e-4f,
              "lensblurobs mode 0 (%g %g %g %g)", o[0], o[1], o[2], o[3]);
        // solid_wipe: inside the band the fill replaces (premultiplied by
        // tex.a); before start the pixel is untouched.
        FxOp sw{}; sw.type = FX_SOLID_WIPE_AM; sw.p[2] = 1.f; sw.p[5] = 1.f; sw.q[0] = 0.f;
        { float tex[4] = {0.2f, 0.4f, 0.6f, 0.8f};
          fxSolidWipeAmPixel(sw, 0.5f, 0.5f, tex, o);
          CHECK(o[0] == 0.f && o[1] == 0.f && std::fabs(o[3] - 0.8f) < 1e-5f,
                "solid wipe inside (%g %g)", o[0], o[3]);
          sw.p[4] = 0.3f;
          fxSolidWipeAmPixel(sw, 0.1f, 0.5f, tex, o);
          CHECK(o[0] == 0.2f && o[3] == 0.8f, "solid wipe outside (%g %g)", o[0], o[3]); }
        // softblooom: the kernel law, the extract gate, the composite.
        CHECK(std::fabs(fxSoftbloomAmKernel(1.f, 1920.f, 1080.f) - 104.638f) < 0.01f,
              "softbloom kernel (%g)", fxSoftbloomAmKernel(1.f, 1920.f, 1080.f));
        { float tex[4] = {0.9f, 0.9f, 0.9f, 1.f};
          fxSoftbloomAmExtract(tex, 0.15f, o);
          CHECK(std::fabs(o[0] - 0.9f) < 1e-5f && std::fabs(o[3] - 1.f) < 1e-5f,
                "softbloom extract bright (%g %g)", o[0], o[3]);
          float dark[4] = {0.1f, 0.1f, 0.1f, 1.f};
          fxSoftbloomAmExtract(dark, 0.15f, o);
          CHECK(o[0] == 0.f && std::fabs(o[3] - 1.f) < 1e-5f,
                "softbloom extract dark (%g %g)", o[0], o[3]); }
        FxOp sb{}; sb.type = FX_SOFTBLOOM_AM;
        sb.p[0] = 1.f; sb.p[1] = 1.5f; sb.p[2] = 0.15f; sb.p[5] = 1.f; sb.p[6] = 1.f;
        { float base[4] = {0.2f, 0.4f, 0.6f, 0.8f}, bloom[4] = {0.1f, 0.1f, 0.1f, 1.f};
          fxSoftbloomAmComposite(sb, base, bloom, o);
          CHECK(std::fabs(o[0] - 0.35f) < 1e-5f && std::fabs(o[2] - 0.75f) < 1e-5f &&
                std::fabs(o[3] - 0.8f) < 1e-5f, "softbloom composite (%g %g %g)", o[0], o[2], o[3]); }
        // chromabbr at amp 0: f = 1, the write is (src.rgb * tex.a, tex.a).
        FxOp ca{}; ca.type = FX_LENSD_AM; ca.q[1] = 1.f;
        fxChromabbrAmPixel(ca, 0.5f, 0.5f, 1.f, konstS(KC_A), o);
        CHECK(std::fabs(o[0] - 0.16f) < 1e-5f && std::fabs(o[3] - 0.8f) < 1e-5f,
              "chromabbr amp 0 (%g %g)", o[0], o[3]);
        // lensd main: numIters 2, aberration 0 -> cs 2, weights 2/3 + 2/3,
        // frag = (4/3 * 0.9) / 1.5 = 0.8; alpha 1 mixes fully, * tex.a 0.5.
        FxExtrudeSampler ldS; ldS.sample = [](const void*, int tex, float, float, float out[4]) {
            if (tex == 0) { out[0] = 0.3f; out[1] = 0.6f; out[2] = 0.9f; out[3] = 0.5f; }
            else { out[0] = out[1] = out[2] = 0.9f; out[3] = 1.f; }
        }; ldS.ctx = nullptr;
        FxOp ld{}; ld.type = FX_LENSD_AM;
        ld.p[3] = 0.2f; ld.p[5] = 2.f; ld.p[6] = 1.f; ld.q[0] = 1.f;
        fxLensdAmPixel(ld, 0.5f, 0.5f, 100.f, ldS, o);
        CHECK(std::fabs(o[0] - 0.4f) < 1e-4f && std::fabs(o[3] - 0.25f) < 1e-4f,
              "lensd spread (%g %g)", o[0], o[3]);
        // Parse folds + shader routing + identity for the whole wave.
        FxOp tops[kFxMaxOps];
        float flatRf[] = {194, 1, 1, 1, 1, 8390656, 2.4f, 6, 195, 0.5f, 8, 7, 60, 700, 0.012f, 200,
                          196, 0.2f, 0.3f, 0.3f, 1, 0, 0, 0};
        CHECK(fxParseOps(flatRf, 24, tops) == 1 && tops[0].type == FX_REFRACTION_AM &&
              tops[0].r[3] == 1.f && tops[0].q[4] == 700.f, "refraction folds EXT + EXT2");
        float flatSf[] = {200, 0, 0, 0, 0.3f, 0, 0, 1, 201, 0.5f, 0, 0.5f, 0, 15, 1, 1,
                          202, 1, 0, 0, 16733610, 5614335, 16755285, 1};
        CHECK(fxParseOps(flatSf, 24, tops) == 1 && tops[0].type == FX_SHAPEFIELD_AM &&
              tops[0].q[0] == 0.5f && tops[0].r[6] == 1.f, "shapefield folds EXT + EXT2");
        float flatCg[] = {205, 0, 20, 25, 0, 0, 100, 16777215, 206, 50, 0.5f, 0.5f, 0, 40, 50, 50,
                          207, 0.05f, 0, 0, 0, 0, 0, 0};
        CHECK(fxParseOps(flatCg, 24, tops) == 1 && tops[0].type == FX_CC_GLASS_AM &&
              tops[0].r[0] == 0.05f, "cc_glass folds EXT + EXT2");
        float flatUu[] = {187, 0, 1, 10, 0, 1, 0, 0, 188, 1, 0, 0, 0, 0, 0, 0};
        CHECK(fxParseOps(flatUu, 16, tops) == 1 && tops[0].type == FX_UNMULT_AM &&
              tops[0].q[0] == 1.f, "unmult folds EXT");
        float flatLd[] = {213, 0, -0.15f, 100, 0.2f, 1.2f, 50, 1, 214, 1, 1, 1, 0, 0, 0, 0};
        CHECK(fxParseOps(flatLd, 16, tops) == 1 && tops[0].type == FX_LENSD_AM &&
              tops[0].q[2] == 1.f, "lensd folds EXT");
        float flatSw[] = {210, 0, 1, 1, 0, 0, 1, 0, 211, 0, 0, 0, 0, 0, 0, 0};
        CHECK(fxParseOps(flatSw, 16, tops) == 1 && tops[0].type == FX_SOLID_WIPE_AM,
              "solid wipe folds EXT");
        float flatHt[] = {203, 0, 45, 33, 1, 4, 0, 0, 204, 16777215, 0, 0, 0, 1, 0, 0};
        CHECK(fxParseOps(flatHt, 16, tops) == 1 && tops[0].type == FX_HALFTONE_DOTS_AM &&
              tops[0].q[0] == 16777215.f, "halftone dots folds EXT");
        float flatT2[] = {198, 0.25f, 0, 4, 1, 0, 0, 1, 199, 0, 6, 0, 8390656, 8390656, 0, 0};
        CHECK(fxParseOps(flatT2, 16, tops) == 1 && tops[0].type == FX_TURBULENT2_AM &&
              tops[0].q[1] == 6.f, "turbulent2 folds EXT");
        float flatBare[] = {188, 1, 0, 0, 0, 0, 0, 0};
        CHECK(fxParseOps(flatBare, 8, tops) == 0, "bare unmult EXT dropped");
        CHECK(fxShaderOf(FX_NEGATIVE_AM).group == FXG_COLOR && fxShaderOf(FX_NEGATIVE_AM).mode == 27 &&
              fxShaderOf(FX_TRITONE_AM).mode == 28 && fxShaderOf(FX_DENOISE_AM).group == FXG_BLUR2 &&
              fxShaderOf(FX_DENOISE_AM).mode == 13 && fxShaderOf(FX_BOKEH_AM).mode == 14 &&
              fxShaderOf(FX_LENSBLUR_OBS_AM).mode == 15 && fxShaderOf(FX_VORTEX_BLUR_AM).mode == 16 &&
              fxShaderOf(FX_TWITCH_AM).mode == 17 && fxShaderOf(FX_RGBGLITCH_AM).group == FXG_WARP &&
              fxShaderOf(FX_RGBGLITCH_AM).mode == 28 && fxShaderOf(FX_FISHEYE_AM).mode == 29 &&
              fxShaderOf(FX_REFRACTION_AM).mode == 30 && fxShaderOf(FX_TURBULENT2_AM).mode == 31 &&
              fxShaderOf(FX_CC_GLASS_AM).mode == 32 && fxShaderOf(FX_SOLID_WIPE_AM).mode == 33 &&
              fxShaderOf(FX_SHAPEFIELD_AM).group == FXG_STYLE && fxShaderOf(FX_SHAPEFIELD_AM).mode == 21 &&
              fxShaderOf(FX_HALFTONE_DOTS_AM).mode == 22, "v180 shaded routing");
        CHECK(fxShaderOf(FX_UNMULT_AM).group == FXG_NONE && fxShaderOf(FX_MINIMAX_AM).group == FXG_NONE &&
              fxShaderOf(FX_SOFTBLOOM_AM).group == FXG_NONE && fxShaderOf(FX_LENSD_AM).group == FXG_NONE,
              "v180 dedicated types");
        FxOp idn{};
        idn.type = FX_NEGATIVE_AM;
        CHECK(!fxIsIdentity(idn), "negative is never identity");
        idn = FxOp{}; idn.type = FX_MINIMAX_AM;
        CHECK(fxIsIdentity(idn), "minimax radius 0 is identity");
        idn.p[1] = 5.f;
        CHECK(!fxIsIdentity(idn), "minimax radius 5 is not identity");
        idn = FxOp{}; idn.type = FX_RGBGLITCH_AM;
        CHECK(fxIsIdentity(idn), "rgbglitch progressb 0 is identity");
        idn = FxOp{}; idn.type = FX_SOLID_WIPE_AM;
        CHECK(fxIsIdentity(idn), "solid wipe alpha 0 is identity");
        idn = FxOp{}; idn.type = FX_SOFTBLOOM_AM;
        CHECK(fxIsIdentity(idn), "softbloom strength 0 is identity (the def's gate)");
        idn.p[0] = 1.f;
        CHECK(!fxIsIdentity(idn), "softbloom strength 1 is not identity");
        idn = FxOp{}; idn.type = FX_TURBULENT2_AM;
        CHECK(fxIsIdentity(idn), "turbulent2 intensity 0 is identity");
        idn = FxOp{}; idn.type = FX_FISHEYE_AM;
        CHECK(!fxIsIdentity(idn), "fisheye is never identity (the def's alpha tail)");
    }
    // ---- v181 (build 150): bccfilmglow + the EXT3 / EXT4 op-model extension ----
    {
        // The full five-bank chain folds into ONE op, bank by bank.
        FxOp tops[kFxMaxOps];
        float flatBc[] = {215, 1.5f, 2.5f, 3.5f, 4.5f, 5.5f, 6.5f, 7.5f,
                          216, 11, 12, 13, 14, 15, 16, 17,
                          217, 21, 22, 23, 24, 25, 26, 27,
                          218, 31, 32, 33, 34, 35, 36, 37,
                          219, 41, 42, 43, 44, 45, 46, 47};
        CHECK(fxParseOps(flatBc, 40, tops) == 1 && tops[0].type == FX_BCC_FILM_GLOW_AM,
              "bccfilmglow chain = one op");
        CHECK(tops[0].p[0] == 1.5f && tops[0].p[6] == 7.5f && tops[0].q[3] == 14.f &&
              tops[0].r[5] == 26.f && tops[0].s[0] == 31.f && tops[0].s[6] == 37.f &&
              tops[0].t[0] == 41.f && tops[0].t[4] == 45.f, "bccfilmglow five banks folded");
        // Round trip: re-encoding the parsed op reproduces the flat array exactly.
        { float re[40]; const int types[5] = {215, 216, 217, 218, 219};
          const float* banks[5] = {tops[0].p, tops[0].q, tops[0].r, tops[0].s, tops[0].t};
          for (int g = 0; g < 5; ++g) {
              re[g * 8] = (float)types[g];
              for (int k = 0; k < 7; ++k) re[g * 8 + 1 + k] = banks[g][k];
          }
          bool same = true;
          for (int k = 0; k < 40; ++k) same = same && re[k] == flatBc[k];
          CHECK(same, "bccfilmglow encode -> decode is the identity"); }
        // Chain discipline: a group out of order is dropped, its bank stays zero.
        float flatNoExt2[] = {215, 1, 1, 1, 1, 1, 1, 1, 216, 2, 2, 2, 2, 2, 2, 2,
                              218, 3, 3, 3, 3, 3, 3, 3};
        CHECK(fxParseOps(flatNoExt2, 24, tops) == 1 && tops[0].q[0] == 2.f &&
              tops[0].s[0] == 0.f, "EXT3 without its EXT2 is dropped");
        float flatNoExt3[] = {215, 1, 1, 1, 1, 1, 1, 1, 216, 2, 2, 2, 2, 2, 2, 2,
                              217, 3, 3, 3, 3, 3, 3, 3, 219, 4, 4, 4, 4, 4, 4, 4};
        CHECK(fxParseOps(flatNoExt3, 32, tops) == 1 && tops[0].r[0] == 3.f &&
              tops[0].t[0] == 0.f, "EXT4 without its EXT3 is dropped");
        float flatForeign[] = {194, 1, 1, 1, 1, 1, 1, 1, 195, 2, 2, 2, 2, 2, 2, 2,
                               196, 3, 3, 3, 3, 3, 3, 3, 218, 4, 4, 4, 4, 4, 4, 4};
        CHECK(fxParseOps(flatForeign, 32, tops) == 1 && tops[0].type == FX_REFRACTION_AM &&
              tops[0].s[0] == 0.f, "EXT3 after a foreign chain is dropped");
        float flatBare4[] = {219, 1, 1, 1, 1, 1, 1, 1};
        CHECK(fxParseOps(flatBare4, 8, tops) == 0, "bare EXT4 is dropped");
        // No regression under the extended model: the established chains fold
        // exactly as before and the new banks stay zero.
        float flatEx[] = {123, 1, 1, 1, 1, 1, 1, 1, 124, 2, 2, 2, 2, 2, 2, 2,
                          125, 3, 3, 3, 3, 3, 3, 3};
        CHECK(fxParseOps(flatEx, 24, tops) == 1 && tops[0].type == FX_EXTRUDE_AM &&
              tops[0].q[0] == 2.f && tops[0].r[0] == 3.f && tops[0].s[0] == 0.f &&
              tops[0].t[0] == 0.f, "extrude chain unchanged, s/t zero");
        CHECK(fxShaderOf(FX_BCC_FILM_GLOW_AM).group == FXG_NONE, "bccfilmglow is a dedicated chain");
        // Identity: glowOnly off + globalMix 0 only.
        FxOp idb{}; idb.type = FX_BCC_FILM_GLOW_AM;
        CHECK(fxIsIdentity(idb), "bccfilmglow globalMix 0 is identity");
        idb.t[4] = 1.f;
        CHECK(!fxIsIdentity(idb), "bccfilmglow globalMix 1 is not identity");
        idb.t[4] = 0.f; idb.t[2] = 1.f;
        CHECK(!fxIsIdentity(idb), "bccfilmglow glowOnly bypasses the mix: not identity");
        // The taps ladder.
        CHECK(fxBccFilmGlowAmTaps(1.f) == 8.f && fxBccFilmGlowAmTaps(2.f) == 14.f &&
              fxBccFilmGlowAmTaps(3.f) == 20.f && fxBccFilmGlowAmTaps(2.5f) == 20.f,
              "bccfilmglow taps ladder");
        // The corpus op (omniframe-hardtrekk-hero, dither zeroed for the numeric
        // pins: the fract(sin) hashes are float32 knife-edge, pinned structurally).
        FxOp bc{}; bc.type = FX_BCC_FILM_GLOW_AM;
        bc.p[0] = 0.01f; bc.p[1] = 80.f; bc.p[2] = 0.73f; bc.p[3] = 2.5f; bc.p[4] = 0.5f;
        bc.p[5] = 0.3f; bc.p[6] = 1.f;
        bc.q[0] = 0.5f; bc.q[1] = 0.7f; bc.q[2] = 0.8f; bc.q[3] = 1.f; bc.q[4] = 0.5f;
        bc.q[5] = 1.f; bc.q[6] = 16777215.f;
        bc.r[2] = 1.f; bc.r[3] = 0.7f; bc.r[5] = 16711680.f;
        bc.s[0] = 0.3f; bc.s[3] = 1.f; bc.s[6] = 5.f;
        bc.t[0] = 1.f; bc.t[1] = 2.f; bc.t[4] = 1.f;
        float o[4];
        { float src[4] = {0.9f, 0.6f, 0.3f, 1.f};
          fxBccFilmGlowAmExtract(bc, src, o);
          CHECK(std::fabs(o[0] - 0.250969f) < 5e-4f && std::fabs(o[1] - 0.167312f) < 5e-4f &&
                std::fabs(o[2] - 0.083656f) < 5e-4f && o[3] == 1.f,
                "bccfilmglow extract bright (%g %g %g)", o[0], o[1], o[2]);
          float dark[4] = {0.1f, 0.1f, 0.1f, 0.7f};
          fxBccFilmGlowAmExtract(bc, dark, o);
          CHECK(o[0] == 0.f && o[1] == 0.f && o[2] == 0.f && o[3] == 0.7f,
                "bccfilmglow extract dark (%g %g)", o[0], o[3]); }
        { float base[4] = {0.5f, 0.4f, 0.3f, 0.9f}, in[4] = {0.6f, 0.5f, 0.4f, 1.f},
                out_[4] = {0.3f, 0.2f, 0.1f, 1.f};
          fxBccFilmGlowAmComposite(bc, base, in, out_, 3.f, 7.f, 0.f, o);
          CHECK(std::fabs(o[0] - 0.518332f) < 5e-4f && std::fabs(o[1] - 0.415594f) < 5e-4f &&
                std::fabs(o[2] - 0.312935f) < 5e-4f && std::fabs(o[3] - 0.9f) < 1e-5f,
                "bccfilmglow composite corpus (%g %g %g)", o[0], o[1], o[2]); }
        // An active op across the blend switch (hand-computed from the def).
        FxOp ac = bc;
        ac.p[0] = 0.8f; ac.p[1] = 20.f; ac.p[2] = 0.5f;
        ac.r[0] = 0.4f; ac.r[1] = 0.2f; ac.r[2] = 1.5f; ac.r[4] = 0.6f; ac.r[6] = 0.5f;
        ac.s[3] = 1.2f; ac.s[4] = 20.f; ac.s[5] = 0.1f;
        { float base[4] = {0.35f, 0.4f, 0.3f, 0.9f}, in[4] = {0.6f, 0.5f, 0.4f, 1.f},
                out_[4] = {0.3f, 0.2f, 0.1f, 1.f};
          fxBccFilmGlowAmComposite(ac, base, in, out_, 3.f, 7.f, 0.f, o);
          CHECK(std::fabs(o[0] - 0.950665f) < 5e-4f && std::fabs(o[1] - 0.897580f) < 5e-4f &&
                std::fabs(o[2] - 0.612404f) < 5e-4f, "bccfilmglow composite add (%g %g %g)", o[0], o[1], o[2]);
          float base2[4] = {0.5f, 0.4f, 0.3f, 0.9f};
          ac.t[0] = 3.f;
          fxBccFilmGlowAmComposite(ac, base2, in, out_, 3.f, 7.f, 0.f, o);
          CHECK(std::fabs(o[0] - 0.706270f) < 5e-4f && std::fabs(o[1] - 0.538029f) < 5e-4f &&
                std::fabs(o[2] - 0.286008f) < 5e-4f, "bccfilmglow composite soft light (%g %g %g)", o[0], o[1], o[2]);
          ac.t[0] = 4.f;
          fxBccFilmGlowAmComposite(ac, base2, in, out_, 3.f, 7.f, 0.f, o);
          CHECK(std::fabs(o[0] - 0.953488f) < 5e-4f && std::fabs(o[1] - 0.803485f) < 5e-4f &&
                std::fabs(o[2] - 0.519344f) < 5e-4f, "bccfilmglow composite lighten (%g %g %g)", o[0], o[1], o[2]);
          ac.t[0] = 1.f; ac.t[2] = 1.f;
          fxBccFilmGlowAmComposite(ac, base2, in, out_, 3.f, 7.f, 0.f, o);
          CHECK(std::fabs(o[0] - 0.911621f) < 5e-4f && std::fabs(o[1] - 0.793383f) < 5e-4f &&
                std::fabs(o[2] - 0.563363f) < 5e-4f && o[3] == 1.f,
                "bccfilmglow glow only (%g %g %g %g)", o[0], o[1], o[2], o[3]);
          ac.t[2] = 0.f; ac.t[0] = 2.f; ac.t[1] = 3.f; ac.t[4] = 0.8f; ac.s[2] = 0.2f;
          fxBccFilmGlowAmComposite(ac, base2, in, out_, 3.f, 7.f, 0.f, o);
          CHECK(std::fabs(o[0] - 0.878953f) < 5e-4f && std::fabs(o[1] - 0.782423f) < 5e-4f &&
                std::fabs(o[2] - 0.595349f) < 5e-4f, "bccfilmglow composite screen + fade (%g %g %g)", o[0], o[1], o[2]);
          // globalMix 0 returns the base pixel exactly.
          ac.t[4] = 0.f;
          fxBccFilmGlowAmComposite(ac, base2, in, out_, 3.f, 7.f, 0.f, o);
          CHECK(o[0] == 0.5f && o[1] == 0.4f && o[2] == 0.3f && o[3] == 0.9f,
                "bccfilmglow globalMix 0 = base (%g %g)", o[0], o[3]);
          // Grain / dither are bounded noise over the same composite (structural:
          // their hash values are float32 knife-edge and are not pinned numerically).
          ac.t[4] = 1.f; ac.s[2] = 0.f;
          float clean[4];
          fxBccFilmGlowAmComposite(ac, base2, in, out_, 3.f, 7.f, 0.5f, clean);
          ac.s[1] = 0.5f;
          fxBccFilmGlowAmComposite(ac, base2, in, out_, 3.f, 7.f, 0.5f, o);
          CHECK(std::fabs(o[0] - clean[0]) <= 0.0401f && std::fabs(o[1] - clean[1]) <= 0.0401f &&
                std::fabs(o[2] - clean[2]) <= 0.0401f && o[3] == clean[3],
                "bccfilmglow grain bounded (%g vs %g)", o[0], clean[0]);
          ac.s[1] = 0.f; ac.t[3] = 0.008f;
          fxBccFilmGlowAmComposite(ac, base2, in, out_, 3.f, 7.f, 0.5f, o);
          CHECK(std::fabs(o[0] - clean[0]) <= 0.0041f && std::fabs(o[1] - clean[1]) <= 0.0041f &&
                std::fabs(o[2] - clean[2]) <= 0.0041f && o[3] == clean[3],
                "bccfilmglow dither bounded (%g vs %g)", o[0], clean[0]); }
    }
    printf("%d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
