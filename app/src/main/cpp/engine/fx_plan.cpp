#include "fx_plan.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace am {

int fxParseOps(const float* flat, int n, FxOp* out) {
    int count = 0;
    if (!flat || !out) return 0;
    for (int i = 0; i + kFxStride <= n && count < kFxMaxOps; i += kFxStride) {
        const int t = (int)std::lround(flat[i]);
        // 55..58 are resolved by Kotlin before the engine (see FxType) and must never reach the shaders;
        // 59..63 are shaded / dedicated effects and are accepted explicitly.
        if ((t < FX_BLUR || t > FX_MAX) && t != FX_LONG_SHADOW && t != FX_COLOR_TUNE && t != FX_SOLID_COLOR &&
            t != FX_AM_MIRROR && t != FX_GLOW_SCAN) continue;
        FxOp& o = out[count++];
        o.type = t;
        for (int k = 0; k < 7; ++k) o.p[k] = flat[i + 1 + k];
    }
    return count;
}

float fxBlurSigma(float blurriness, float unit) {
    const float b = std::min(100.f, std::max(0.f, blurriness));
    return b * 0.6f * unit;  // 100 -> sigma 60 px at 1080 p
}

BlurPlan fxPlanBlur(float sigmaPx) {
    BlurPlan p;
    float s = std::max(0.f, sigmaPx);
    // keep the sigma of the small picture <= 8 px: the kernel then needs at most ~ 15 bilinear pairs
    while (s > 8.f && p.shift < 5) {
        s *= 0.5f;
        ++p.shift;
    }
    p.sigmaLow = s;
    return p;
}

GaussKernel fxGaussKernel(float sigma) {
    GaussKernel k;
    if (sigma < 0.05f) return k;
    int R = (int)std::ceil(3.5f * sigma);  // taps on each side
    R = std::max(1, std::min(R, 2 * kFxMaxTaps));
    std::vector<double> w;
    double sum = 0;
    for (int i = 0; i <= R; ++i) {
        const double v = std::exp(-0.5 * (double)i * i / ((double)sigma * sigma));
        w.push_back(v);
        sum += (i == 0) ? v : 2.0 * v;
    }
    for (auto& v : w) v /= sum;
    k.centre = (float)w[0];
    // pair (i, i+1) -> one fetch at the weighted position between the two texels
    for (int i = 1; i <= R && k.n < kFxMaxTaps; i += 2) {
        const double w1 = w[i];
        const double w2 = (i + 1 <= R) ? w[i + 1] : 0.0;
        const double ws = w1 + w2;
        k.weight[k.n] = (float)ws;
        k.offset[k.n] = (float)((i * w1 + (i + 1) * w2) / ws);
        ++k.n;
    }
    return k;
}

GlowPlan fxPlanGlow(const FxOp& op) {
    GlowPlan g;
    const float thr = std::min(100.f, std::max(0.f, op.p[0])) / 100.f;
    const float rad = std::min(100.f, std::max(0.f, op.p[1])) / 100.f;
    g.threshold = thr;
    g.knee = 0.08f + 0.12f * (1.f - thr);  // a gentle roll-off, wider at low thresholds
    g.spread = 0.35f + 0.50f * rad;
    g.intensity = std::min(300.f, std::max(0.f, op.p[2])) / 100.f;
    return g;
}

void fxChromaOf(float r, float g, float b, float* cb, float* cr) {
    const float y = 0.299f * r + 0.587f * g + 0.114f * b;
    *cb = (b - y) * 0.564f;
    *cr = (r - y) * 0.713f;
}

void fxChromaKeyed(float r, float g, float b, float* cb, float* cr) {
    fxChromaOf(r, g, b, cb, cr);
    const float v = std::max(0.2f, std::max(r, std::max(g, b)));
    *cb /= v;
    *cr /= v;
}

void fxHsvToRgb(float hue, float sat, float* r, float* g, float* b) {
    float h = std::fmod(hue, 360.f);
    if (h < 0.f) h += 360.f;
    h /= 60.f;
    const float s = std::min(1.f, std::max(0.f, sat));
    const int i = (int)std::floor(h) % 6;
    const float f = h - std::floor(h);
    const float p = 1.f - s, q = 1.f - s * f, t = 1.f - s * (1.f - f);
    switch (i) {
        case 0: *r = 1; *g = t; *b = p; break;
        case 1: *r = q; *g = 1; *b = p; break;
        case 2: *r = p; *g = 1; *b = t; break;
        case 3: *r = p; *g = q; *b = 1; break;
        case 4: *r = t; *g = p; *b = 1; break;
        default: *r = 1; *g = p; *b = q; break;
    }
}

ChromaPlan fxPlanChroma(const FxOp& op) {
    ChromaPlan c;
    const float sat = std::min(100.f, std::max(0.f, op.p[1])) / 100.f;
    float r, g, b;
    fxHsvToRgb(op.p[0], 1.f, &r, &g, &b);
    float cb, cr;
    fxChromaOf(r, g, b, &cb, &cr);  // chroma is linear in RGB and white has none: saturation just scales it
    const float len = std::sqrt(cb * cb + cr * cr);
    c.dirCb = len > 1e-6f ? cb / len : 0.f;
    c.dirCr = len > 1e-6f ? cr / len : 0.f;
    c.keyCb = cb * sat;
    c.keyCr = cr * sat;
    c.tol = 0.02f + 0.50f * std::min(100.f, std::max(0.f, op.p[2])) / 100.f;
    c.soft = 0.005f + 0.40f * std::min(100.f, std::max(0.f, op.p[3])) / 100.f;
    // Key colour with real chroma: the removed zone must never reach the grey axis (white / grey / black stay), whatever the
    // Similarity slider says. Grey-ish key: no such limit, the chroma distance counts 3x (other colours stay) and the
    // brightness decides (Brightness match).
    const float kmag = std::sqrt(c.keyCb * c.keyCb + c.keyCr * c.keyCr);
    c.cs = 1.f + 2.f * (1.f - std::min(1.f, kmag / 0.1f));
    if (kmag >= 0.1f) {
        c.tol = std::min(c.tol, 0.8f * kmag);
        c.soft = std::min(c.soft, 0.2f * kmag);
    }
    c.spill = std::min(100.f, std::max(0.f, op.p[4])) / 100.f;
    // a grey key has no colour direction to take out of the picture
    c.spill *= std::min(1.f, sat * 100.f / 30.f);
    c.keyV = std::min(100.f, std::max(0.f, op.p[5])) / 100.f;
    c.vw = std::min(100.f, std::max(0.f, op.p[6])) / 100.f;
    return c;
}

void fxKeyFromRgb(float r, float g, float b, float* hue, float* strength, float* keyV, float* match) {
    const float mx = std::max(r, std::max(g, b)), mn = std::min(r, std::min(g, b));
    const float d = mx - mn;
    float h = 0.f;
    if (d > 1e-6f) {
        if (mx == r) h = std::fmod((g - b) / d, 6.f);
        else if (mx == g) h = (b - r) / d + 2.f;
        else h = (r - g) / d + 4.f;
        h *= 60.f;
        if (h < 0.f) h += 360.f;
    }
    *hue = h;
    *strength = std::min(100.f, 100.f * d / std::max(0.2f, mx));
    *keyV = 100.f * mx;
    // a vivid colour is found by its chroma alone; a dull one (grey, white, black, pale skin) also by its brightness
    *match = 100.f * std::min(1.f, std::max(0.f, (20.f - *strength) / 12.f));
}

float fxChromaKeep(const ChromaPlan& pl, float cb, float cr, float v) {
    const float dv = (v - pl.keyV) * pl.vw;
    const float dcb = (cb - pl.keyCb) * pl.cs, dcr = (cr - pl.keyCr) * pl.cs;
    const float d = std::sqrt(dcb * dcb + dcr * dcr + dv * dv);
    float t = (d - pl.tol) / pl.soft;
    t = std::min(1.f, std::max(0.f, t));
    return t * t * (3.f - 2.f * t);
}

FxShaderRef fxShaderOf(int type) {
    FxShaderRef r;
    switch (type) {
        case FX_DIR_BLUR: r = {FXG_BLUR2, 0}; break;
        case FX_RADIAL_BLUR: r = {FXG_BLUR2, 1}; break;
        case FX_SHARPEN: r = {FXG_BLUR2, 2}; break;
        case FX_EXPOSURE: r = {FXG_COLOR, 0}; break;
        case FX_BRIGHT_CONTRAST: r = {FXG_COLOR, 1}; break;
        case FX_HUE_SAT: r = {FXG_COLOR, 2}; break;
        case FX_LEVELS: r = {FXG_COLOR, 3}; break;
        case FX_COLOR_BALANCE: r = {FXG_COLOR, 4}; break;
        case FX_TINT: r = {FXG_COLOR, 5}; break;
        case FX_POSTERIZE: r = {FXG_COLOR, 6}; break;
        case FX_WAVE: r = {FXG_WARP, 0}; break;
        case FX_TWIRL: r = {FXG_WARP, 1}; break;
        case FX_BULGE: r = {FXG_WARP, 2}; break;
        case FX_MIRROR: r = {FXG_WARP, 3}; break;
        case FX_KALEIDO: r = {FXG_WARP, 4}; break;
        case FX_TURBULENT: r = {FXG_WARP, 5}; break;
        case FX_MOSAIC: r = {FXG_STYLE, 0}; break;
        case FX_HALFTONE: r = {FXG_STYLE, 1}; break;
        case FX_SCANLINES: r = {FXG_STYLE, 2}; break;
        case FX_GLITCH: r = {FXG_STYLE, 3}; break;
        case FX_INVERT: r = {FXG_COLOR, 7}; break;
        case FX_BW: r = {FXG_COLOR, 8}; break;
        case FX_THRESHOLD: r = {FXG_COLOR, 9}; break;
        case FX_TEMPERATURE: r = {FXG_COLOR, 10}; break;
        case FX_SHADOW_HIGHLIGHT: r = {FXG_COLOR, 11}; break;
        case FX_LEAVE_COLOR: r = {FXG_COLOR, 12}; break;
        case FX_CINEMATIC: r = {FXG_COLOR, 13}; break;
        case FX_SOLARIZE: r = {FXG_COLOR, 14}; break;
        case FX_LENS_BLUR: r = {FXG_BLUR2, 3}; break;
        case FX_TILT_SHIFT: r = {FXG_BLUR2, 4}; break;
        case FX_SURFACE_BLUR: r = {FXG_BLUR2, 5}; break;
        case FX_LIGHT_RAYS: r = {FXG_BLUR2, 6}; break;
        case FX_RIPPLE: r = {FXG_WARP, 6}; break;
        case FX_LENS_DISTORT: r = {FXG_WARP, 7}; break;
        case FX_POLAR: r = {FXG_WARP, 8}; break;
        case FX_TILE: r = {FXG_WARP, 9}; break;
        case FX_SHAKE: r = {FXG_WARP, 10}; break;
        case FX_EMBOSS: r = {FXG_STYLE, 4}; break;
        case FX_FIND_EDGES: r = {FXG_STYLE, 5}; break;
        case FX_CARTOON: r = {FXG_STYLE, 6}; break;
        case FX_SKETCH: r = {FXG_STYLE, 7}; break;
        case FX_OIL: r = {FXG_STYLE, 8}; break;
        case FX_STROBE: r = {FXG_STYLE, 9}; break;
        case FX_VHS: r = {FXG_STYLE, 10}; break;
        case FX_OLD_FILM: r = {FXG_STYLE, 11}; break;
        case FX_LIGHT_LEAK: r = {FXG_STYLE, 12}; break;
        case FX_LENS_FLARE: r = {FXG_STYLE, 13}; break;
        case FX_LONG_SHADOW: r = {FXG_BLUR2, 7}; break;
        case FX_COLOR_TUNE: r = {FXG_COLOR, 15}; break;
        case FX_SOLID_COLOR: r = {FXG_COLOR, 16}; break;
        case FX_AM_MIRROR: r = {FXG_WARP, 11}; break;
        default: break;
    }
    return r;
}

bool fxIsIdentity(const FxOp& o) {
    const float* p = o.p;
    switch (o.type) {
        case FX_DIR_BLUR: return p[1] < 0.5f;
        case FX_RADIAL_BLUR: return p[0] < 0.5f;
        case FX_SHARPEN: return p[0] < 0.5f;
        case FX_EXPOSURE:
            return std::fabs(p[0]) < 0.005f && std::fabs(p[1]) < 0.5f && std::fabs(p[2] - 100.f) < 0.5f;
        case FX_BRIGHT_CONTRAST: return std::fabs(p[0]) < 0.5f && std::fabs(p[1]) < 0.5f;
        case FX_HUE_SAT:
            return std::fabs(p[0]) < 0.5f && std::fabs(p[1]) < 0.5f && std::fabs(p[2]) < 0.5f && std::fabs(p[3]) < 0.5f;
        case FX_LEVELS:
            return p[0] < 0.5f && std::fabs(p[1] - 255.f) < 0.5f && std::fabs(p[2] - 100.f) < 0.5f && p[3] < 0.5f &&
                   std::fabs(p[4] - 255.f) < 0.5f;
        case FX_COLOR_BALANCE: return p[1] < 0.5f && p[3] < 0.5f && p[5] < 0.5f;
        case FX_TINT: return p[4] < 0.5f;
        case FX_POSTERIZE: return p[1] < 0.5f || p[0] >= 255.f;
        case FX_WAVE: return p[0] < 0.5f;
        case FX_TWIRL: return std::fabs(p[0]) < 0.5f;
        case FX_BULGE: return std::fabs(p[0]) < 0.5f;
        case FX_TURBULENT: return p[0] < 0.5f;
        case FX_HALFTONE: return p[4] < 0.5f;
        case FX_SCANLINES: return p[0] < 0.5f && p[2] < 0.5f && p[3] < 0.5f;
        case FX_GLITCH: return p[0] < 0.5f;
        case FX_INVERT: return p[0] < 0.5f;
        case FX_BW: return p[3] < 0.5f;
        case FX_THRESHOLD: return p[2] < 0.5f;
        case FX_TEMPERATURE: return std::fabs(p[0]) < 0.5f && std::fabs(p[1]) < 0.5f;
        case FX_SHADOW_HIGHLIGHT: return p[0] < 0.5f && p[1] < 0.5f && std::fabs(p[3]) < 0.5f;
        case FX_LEAVE_COLOR: return p[3] < 0.5f;
        case FX_CINEMATIC: return p[0] < 0.5f;
        case FX_SOLARIZE: return p[1] < 0.5f;
        case FX_LENS_BLUR: return p[0] < 0.5f;
        case FX_TILT_SHIFT: return p[0] < 0.5f;
        case FX_SURFACE_BLUR: return p[0] < 0.5f;
        case FX_LIGHT_RAYS: return p[1] < 0.5f || p[2] < 0.5f;
        case FX_RIPPLE: return p[0] < 0.5f;
        case FX_LENS_DISTORT: return std::fabs(p[0]) < 0.5f && p[2] < 0.5f && std::fabs(p[1] - 100.f) < 0.5f;
        case FX_POLAR: return p[0] < 0.5f;
        case FX_TILE:
            return p[0] < 1.5f && p[1] < 1.5f && std::fabs(p[3]) < 0.5f && std::fabs(p[4]) < 0.5f && std::fabs(p[5]) < 0.5f && std::fabs(p[6]) < 0.5f;
        case FX_SHAKE: return p[0] < 0.5f && p[2] < 0.5f;
        case FX_EMBOSS: return p[0] < 0.5f;
        case FX_FIND_EDGES: return p[4] < 0.5f;
        case FX_CARTOON: return p[4] < 0.5f;
        case FX_SKETCH: return p[5] < 0.5f;
        case FX_OIL: return p[0] < 0.5f;
        case FX_STROBE: return p[1] < 0.5f;
        case FX_VHS: return p[0] < 0.5f;
        case FX_OLD_FILM: return p[0] < 0.5f && p[1] < 0.5f && p[2] < 0.5f && p[3] < 0.5f && p[4] < 0.5f && p[5] < 0.5f;
        case FX_LIGHT_LEAK: return p[0] < 0.5f;
        case FX_LENS_FLARE: return p[2] < 0.5f;
        case FX_COPY_BG: return p[0] < 0.5f;
        case FX_LONG_SHADOW: return p[0] < 0.05f || p[2] < 0.5f;
        case FX_COLOR_TUNE:
            return std::fabs(p[1]) < 0.5f && std::fabs(p[2]) < 0.5f && std::fabs(p[4]) < 0.5f && std::fabs(p[5]) < 0.5f;
        case FX_SOLID_COLOR: return p[3] < 0.5f;   // strength 0 changes nothing
        case FX_AM_MIRROR: return p[2] < 0.5f;   // alpha 0: the mix keeps the original everywhere
        case FX_GLOW_SCAN: return p[5] < 0.5f && std::fabs(p[4] - 100.f) < 0.5f;   // no glow mix, input unscaled
        default: return false;
    }
}

float fxLevelsMap(float v, float inBlack, float inWhite, float gammaPct, float outBlack, float outWhite) {
    const float ib = std::min(254.f, std::max(0.f, inBlack)) / 255.f;
    const float iw = std::min(255.f, std::max(ib * 255.f + 1.f, inWhite)) / 255.f;
    const float g = std::min(500.f, std::max(10.f, gammaPct)) / 100.f;
    float t = std::min(1.f, std::max(0.f, (v - ib) / (iw - ib)));
    t = std::pow(t, 1.f / g);
    const float ob = std::min(255.f, std::max(0.f, outBlack)) / 255.f;
    const float ow = std::min(255.f, std::max(0.f, outWhite)) / 255.f;
    return ob + t * (ow - ob);
}

float fxBrightContrast(float v, float brightness, float contrast) {
    const float c = std::min(100.f, std::max(-100.f, contrast)) / 100.f;
    const float slope = c >= 0.f ? 1.f / (1.f - 0.98f * c) : 1.f + c;
    const float b = std::min(100.f, std::max(-100.f, brightness)) / 100.f;
    return (v - 0.5f) * slope + 0.5f + b * 0.5f;
}

float fxPosterize(float v, float levels) {
    const float n = std::min(32.f, std::max(2.f, std::round(levels)));
    const float x = std::min(1.f, std::max(0.f, v));
    return std::floor(x * (n - 1.f) + 0.5f) / (n - 1.f);
}

void fxColorTuneMap(float r, float g, float b, float liftHue, float liftStrength, float liftLum,
                    float gainHue, float gainStrength, float gainLum, float* outR, float* outG, float* outB) {
    // The same maths as kFxColor mode 15 (keep the two in step): LW is that program's luminance, a disc
    // push is the hue colour with its own luminance removed (so the push alone never brightens), weighted
    // by Alight's published curves, and the luminance sliders add a plain grey shift of the same weight.
    const float lwR = 0.2126f, lwG = 0.7152f, lwB = 0.0722f;
    const float y = lwR * r + lwG * g + lwB * b;
    const float wL = (1.f - y) * (1.f - y);
    const float wG = y * y;
    float hr, hg, hb;
    fxHsvToRgb(liftHue, 1.f, &hr, &hg, &hb);
    const float ll = lwR * hr + lwG * hg + lwB * hb;
    const float ls = std::min(100.f, std::max(0.f, liftStrength)) / 100.f * wL;
    float gr, gg, gb;
    fxHsvToRgb(gainHue, 1.f, &gr, &gg, &gb);
    const float gl = lwR * gr + lwG * gg + lwB * gb;
    const float gs = std::min(100.f, std::max(0.f, gainStrength)) / 100.f * wG;
    const float dl = std::min(100.f, std::max(-100.f, liftLum)) / 100.f * wL +
                     std::min(100.f, std::max(-100.f, gainLum)) / 100.f * wG;
    auto cl = [](float v) { return std::min(1.f, std::max(0.f, v)); };
    *outR = cl(r + ((hr - ll) * ls + (gr - gl) * gs) * 0.45f + dl);
    *outG = cl(g + ((hg - ll) * ls + (gg - gl) * gs) * 0.45f + dl);
    *outB = cl(b + ((hb - ll) * ls + (gb - gl) * gs) * 0.45f + dl);
}

void fxSolidColorMap(float r, float g, float b, float cr, float cg, float cb, float strength,
                     float* outR, float* outG, float* outB) {
    // The same maths as kFxColor mode 16 (keep the two in step): a plain mix in straight space; the
    // shader premultiplies by the pixel's own alpha afterwards, so the silhouette is the picture's.
    const float s = std::min(100.f, std::max(0.f, strength)) / 100.f;
    auto cl = [](float v) { return std::min(1.f, std::max(0.f, v)); };
    auto ch = [](float v) { return std::min(255.f, std::max(0.f, v)) / 255.f; };
    *outR = cl(r + (ch(cr) - r) * s);
    *outG = cl(g + (ch(cg) - g) * s);
    *outB = cl(b + (ch(cb) - b) * s);
}

void fxMirrorMap(float sr, float sg, float sb, float sa, float fr, float fg, float fb, float fa,
                 int blendMode, float alpha, float* outR, float* outG, float* outB, float* outA) {
    // The same maths as kFxWarp mode 11 (keep the two in step): AM's five blend branches over the
    // premultiplied picture, then mix(original, result, alpha).
    float rr, rg, rb, ra;
    const float inv = 1.f / std::max(sa, 1e-5f);   // AM divides by texColor.a (see fx_plan.h)
    switch (blendMode) {
        case 1:   // Multiply: vec4((texColor.rgb / texColor.a) * color.rgb, 1) * texColor.a
            rr = sr * fr; rg = sg * fg; rb = sb * fb; ra = sa;
            break;
        case 2:   // Screen: vec4(1 - (1 - texColor.rgb / texColor.a) * (1 - color.rgb), 1) * texColor.a
            rr = (1.f - (1.f - sr * inv) * (1.f - fr)) * sa;
            rg = (1.f - (1.f - sg * inv) * (1.f - fg)) * sa;
            rb = (1.f - (1.f - sb * inv) * (1.f - fb)) * sa;
            ra = sa;
            break;
        case 3:   // Over: the flipped picture composited over the original
            rr = sr * (1.f - fa) + fr; rg = sg * (1.f - fa) + fg; rb = sb * (1.f - fa) + fb; ra = sa * (1.f - fa) + fa;
            break;
        case 4:   // Under: the original composited over the flipped picture
            rr = fr * (1.f - sa) + sr; rg = fg * (1.f - sa) + sg; rb = fb * (1.f - sa) + sb; ra = fa * (1.f - sa) + sa;
            break;
        default:  // Normal: the flipped picture as it is
            rr = fr; rg = fg; rb = fb; ra = fa;
            break;
    }
    const float a = std::min(1.f, std::max(0.f, alpha));
    *outR = sr + (rr - sr) * a;
    *outG = sg + (rg - sg) * a;
    *outB = sb + (rb - sb) * a;
    *outA = sa + (ra - sa) * a;
}

void fxGlowScanMap(float inR, float inG, float inB, float inA, float baseR, float baseG, float baseB, float baseA,
                   float threshold, float radius, float power, float fillR, float fillG, float fillB,
                   float blend, float fill, float alpha, float* outR, float* outG, float* outB, float* outA) {
    // The same maths as the fxGlowScan shader (keep the two in step): AM's recovered formula.
    float mr = inR * fill, mg = inG * fill, mb = inB * fill, ma = inA * fill;
    const float luma = 0.2126f * baseR + 0.7152f * baseG + 0.0722f * baseB;
    const float adjT = threshold * (1.f + radius) - radius;
    if (luma >= adjT && luma <= adjT + radius && radius > 1e-6f) {
        const float halfR = radius * 0.5f;
        const float lin = std::fabs((luma - adjT - halfR) / halfR);
        const float ex = power * lin * lin;
        const float bm = std::min(1.f, std::max(0.f, blend)) * 0.99f;
        auto pw = [](float v, float e) { return std::pow(std::max(v, 0.f), e); };
        const float gr = pw(baseR + (fillR * luma - baseR) * bm, ex);
        const float gg = pw(baseG + (fillG * luma - baseG) * bm, ex);
        const float gb = pw(baseB + (fillB * luma - baseB) * bm, ex);
        const float ga = pw(baseA + (1.f * luma - baseA) * bm, ex);   // AM's fillColor alpha is 1
        const float a = std::min(1.f, std::max(0.f, alpha));
        mr += (baseR + gr - mr) * a;
        mg += (baseG + gg - mg) * a;
        mb += (baseB + gb - mb) * a;
        ma += (baseA + ga - ma) * a;
    }
    *outA = std::min(1.f, std::max(0.f, ma));
    auto cl = [&](float v) { return std::min(*outA, std::max(0.f, v)); };   // premultiplied invariant
    *outR = cl(mr);
    *outG = cl(mg);
    *outB = cl(mb);
}

float fxGlowScanBlurriness(float smoothingPct) {
    // AM strength x 50, the product clamped to the app's blur range (the applyGaussianBlur rule).
    return std::min(100.f, std::max(0.f, smoothingPct * 0.5f));
}

float fxAutoPhase(int frame, float speed) { return (float)frame / 24.f * (speed / 100.f); }

float fxSplitPixels(float amount, float unit) {
    return std::min(100.f, std::max(0.f, amount)) / 100.f * 27.f * unit;  // 27 px = 2.5 % of 1080
}

float fxGrainCell(float size, float unit) {
    return (1.f + 3.f * std::min(100.f, std::max(0.f, size)) / 100.f) * std::max(0.25f, unit);
}

int fxGrainFrame(int64_t timeMs) { return (int)(timeMs * 24 / 1000); }

}  // namespace am
