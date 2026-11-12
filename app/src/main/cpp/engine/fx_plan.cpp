#include "fx_plan.h"

#include <algorithm>
#include <cmath>
#include <functional>
#include <vector>

namespace am {

int fxParseOps(const float* flat, int n, FxOp* out) {
    int count = 0;
    int owner = -1;  // index of the op an EXT group may extend: only the group right before it
    int extFoldedOp = -1;     // v173: the op whose EXT group was just folded (an EXT2 group may follow it)
    int extFoldedType = FX_NONE;  // ... and which EXT type that was
    if (!flat || !out) return 0;
    for (int i = 0; i + kFxStride <= n; i += kFxStride) {
        const int t = (int)std::lround(flat[i]);
        // v173: an EXT2 group carries the second extension (r) of an effect whose EXT group
        // immediately precedes it (only the extrude tier has one); anywhere else it is dropped.
        const int ext2Ext = t == FX_EXTRUDE_AM_EXT2 ? FX_EXTRUDE_AM_EXT
                          : t == FX_EXTRUDE_FLAT_AM_EXT2 ? FX_EXTRUDE_FLAT_AM_EXT
                          : t == FX_TURBULENCE2_AM_EXT2 ? FX_TURBULENCE2_AM_EXT
                          : t == FX_ELECTRIC_EDGES_AM_EXT2 ? FX_ELECTRIC_EDGES_AM_EXT
                          : t == FX_DEEP_GLOW2_AM_EXT2 ? FX_DEEP_GLOW2_AM_EXT
                                                         : FX_NONE;
        if (ext2Ext != FX_NONE) {
            if (extFoldedOp >= 0 && extFoldedType == ext2Ext) {
                for (int k = 0; k < 7; ++k) out[extFoldedOp].r[k] = flat[i + 1 + k];
            }
            extFoldedOp = -1;
            extFoldedType = FX_NONE;
            owner = -1;
            continue;
        }
        // v163: an EXT group carries the extended params (q) of the effect right before it. It is
        // folded into that op and never becomes an op itself; an EXT group anywhere else (or after
        // a different owner) is dropped.
        const int extOwner = t == FX_FOUR_COLOR_GRADIENT_EXT ? FX_FOUR_COLOR_GRADIENT
                           : t == FX_TURBULENT_DISPLACE_AM_EXT ? FX_TURBULENT_DISPLACE_AM
                           : t == FX_PIXELATE_AM_EXT ? FX_PIXELATE_AM
                           : t == FX_COLOR_BALANCE_AM_EXT ? FX_COLOR_BALANCE_AM
                           : t == FX_HEX_SHIFT_AM_EXT ? FX_HEX_SHIFT_AM
                           : t == FX_SOFT_GLOW_AM_EXT ? FX_SOFT_GLOW_AM
                           : t == FX_FILL_BEHIND_AM_EXT ? FX_FILL_BEHIND_AM
                           : t == FX_WAVE_WARP_AM_EXT ? FX_WAVE_WARP_AM
                           : t == FX_FRACTAL_WARP_AM_EXT ? FX_FRACTAL_WARP_AM
                           : t == FX_TURBULENT_DISPLACE3_AM_EXT ? FX_TURBULENT_DISPLACE3_AM
                           : t == FX_STREAKS_AM_EXT ? FX_STREAKS_AM
                           : t == FX_SIMPLE_STARS_AM_EXT ? FX_SIMPLE_STARS_AM
                           : t == FX_DOTS_AM_EXT ? FX_DOTS_AM
                           : t == FX_RIDGES_AM_EXT ? FX_RIDGES_AM
                           : t == FX_DISPLACE_MAP_RAD_AM_EXT ? FX_DISPLACE_MAP_RAD_AM
                           : t == FX_SMOOTH_BEVEL_AM_EXT ? FX_SMOOTH_BEVEL_AM
                           : t == FX_EXTRUDE_AM_EXT ? FX_EXTRUDE_AM
                           : t == FX_EXTRUDE_FLAT_AM_EXT ? FX_EXTRUDE_FLAT_AM
                           : t == FX_EDGE_GLOW_AM_EXT ? FX_EDGE_GLOW_AM
                           : t == FX_SPECTRALMAP_AM_EXT ? FX_SPECTRALMAP_AM
                           : t == FX_TURBULENCE2_AM_EXT ? FX_TURBULENCE2_AM
                           : t == FX_CHECKER2_AM_EXT ? FX_CHECKER2_AM
                           : t == FX_ELECTRIC_EDGES_AM_EXT ? FX_ELECTRIC_EDGES_AM
                           : t == FX_DEEP_GLOW2_AM_EXT ? FX_DEEP_GLOW2_AM
                                                     : FX_NONE;
        if (extOwner != FX_NONE) {
            if (owner >= 0 && out[owner].type == extOwner) {
                for (int k = 0; k < 7; ++k) out[owner].q[k] = flat[i + 1 + k];
                extFoldedOp = owner;
                extFoldedType = t;
            } else {
                extFoldedOp = -1;
                extFoldedType = FX_NONE;
            }
            owner = -1;
            continue;
        }
        if (count >= kFxMaxOps) break;
        // 55..58 are resolved by Kotlin before the engine (see FxType) and must never reach the shaders;
        // 59..63 and the v163 tier (64..74) are shaded / dedicated effects and are accepted explicitly.
        if ((t < FX_BLUR || t > FX_MAX) && t != FX_LONG_SHADOW && t != FX_COLOR_TUNE && t != FX_SOLID_COLOR &&
            t != FX_AM_MIRROR && t != FX_GLOW_SCAN && t != FX_LUMA_KEY && t != FX_FOUR_COLOR_GRADIENT &&
            t != FX_TURBULENT_DISPLACE_AM && t != FX_SWIRL_AM && t != FX_STRETCH_SEGMENT &&
            t != FX_HALFTONE_DOTS && t != FX_PIXELATE_AM && t != FX_REPLACE_COLOR &&
            t != FX_CHROMA_KEY_AM && t != FX_COLOR_BALANCE_AM && t != FX_COLOR_TEMP_AM &&
            t != FX_COLOR_TINT_AM && t != FX_SPIN_BLUR_AM && t != FX_OFFSET_AM &&
            t != FX_RADIAL_WIPE_AM && t != FX_FIND_EDGES_AM && t != FX_HEX_SHIFT_AM &&
            t != FX_SOFT_GLOW_AM && t != FX_FILL_BEHIND_AM &&
            t != FX_WAVE_WARP_AM && t != FX_CIRCULAR_RIPPLE_AM && t != FX_STRETCH_AM &&
            t != FX_SQUEEZE_AM && t != FX_FRACTAL_WARP_AM && t != FX_STRIPES_AM &&
            t != FX_TURBULENT_DISPLACE3_AM && t != FX_ROUGHEN_EDGES_AM && t != FX_STREAKS_AM &&
            t != FX_COLOR_HOT_AM && t != FX_COLORIZE_AM && t != FX_HIGHLIGHT_SHADOW_AM &&
            t != FX_SPOT_COLOR_AM && t != FX_DISSOLVE_AM && t != FX_SIMPLE_STARS_AM &&
            t != FX_DOTS_AM && t != FX_RIDGES_AM && t != FX_BEND_WARP_AM &&
            t != FX_DISPLACE_MAP_AM && t != FX_DISPLACE_MAP_RAD_AM && t != FX_SMOOTH_BEVEL_AM &&
            t != FX_LIGHT_GLOW_AM && t != FX_INNER_GLOW_AM && t != FX_DARK_GLOW_AM &&
            t != FX_EXTRUDE_AM && t != FX_EXTRUDE_FLAT_AM && t != FX_EDGE_GLOW_AM &&
            t != FX_THRESHOLD_AM && t != FX_SPECTRALMAP_AM && t != FX_CHANNELMAP_AM &&
            t != FX_HALFTONE_LINES_AM && t != FX_TURBULENCE2_AM && t != FX_CHECKER2_AM &&
            t != FX_MOSAIC_AM && t != FX_GLOW_AM && t != FX_INNER_BLUR_AM &&
            t != FX_BOX_BLUR3_AM && t != FX_FEATHER_AM && t != FX_ELECTRIC_EDGES_AM &&
            t != FX_SMOOTH_EDGES_AM && t != FX_GAUSSIAN_BLUR_AM && t != FX_DEEP_GLOW2_AM &&
            t != FX_DROP_SHADOW_AM) {
            owner = -1;
            extFoldedOp = -1;
            extFoldedType = FX_NONE;
            continue;
        }
        FxOp& o = out[count];
        o.type = t;
        for (int k = 0; k < 7; ++k) {
            o.p[k] = flat[i + 1 + k];
            o.q[k] = 0.f;
            o.r[k] = 0.f;
        }
        owner = count;
        extFoldedOp = -1;
        extFoldedType = FX_NONE;
        ++count;
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
        // v163: the shaded half of the tier (the rest are dedicated passes, FXG_NONE like FX_GLOW_SCAN).
        case FX_STRETCH_SEGMENT: r = {FXG_WARP, 12}; break;
        case FX_SWIRL_AM: r = {FXG_WARP, 13}; break;
        case FX_HALFTONE_DOTS: r = {FXG_WARP, 14}; break;
        case FX_REPLACE_COLOR: r = {FXG_COLOR, 17}; break;
        // v164: the parked tier (the dedicated passes stay FXG_NONE like FX_GLOW_SCAN).
        case FX_COLOR_BALANCE_AM: r = {FXG_COLOR, 18}; break;
        case FX_COLOR_TEMP_AM: r = {FXG_COLOR, 19}; break;
        case FX_COLOR_TINT_AM: r = {FXG_COLOR, 20}; break;
        case FX_SPIN_BLUR_AM: r = {FXG_BLUR2, 8}; break;
        case FX_OFFSET_AM: r = {FXG_WARP, 15}; break;
        case FX_RADIAL_WIPE_AM: r = {FXG_WARP, 16}; break;
        case FX_FIND_EDGES_AM: r = {FXG_STYLE, 14}; break;
        case FX_HEX_SHIFT_AM: r = {FXG_WARP, 17}; break;
        // v165 tier (the dedicated passes stay FXG_NONE like FX_GLOW_SCAN).
        case FX_WAVE_WARP_AM: r = {FXG_WARP, 18}; break;
        case FX_CIRCULAR_RIPPLE_AM: r = {FXG_WARP, 19}; break;
        case FX_STRETCH_AM: r = {FXG_WARP, 20}; break;
        case FX_SQUEEZE_AM: r = {FXG_WARP, 21}; break;
        case FX_FRACTAL_WARP_AM: r = {FXG_WARP, 22}; break;
        case FX_STRIPES_AM: r = {FXG_WARP, 23}; break;
        case FX_TURBULENT_DISPLACE3_AM: r = {FXG_WARP, 24}; break;
        case FX_ROUGHEN_EDGES_AM: r = {FXG_WARP, 25}; break;
        case FX_BEND_WARP_AM: r = {FXG_WARP, 26}; break;
        case FX_STREAKS_AM: r = {FXG_BLUR2, 9}; break;
        case FX_COLOR_HOT_AM: r = {FXG_COLOR, 21}; break;
        case FX_COLORIZE_AM: r = {FXG_COLOR, 22}; break;
        case FX_HIGHLIGHT_SHADOW_AM: r = {FXG_COLOR, 23}; break;
        case FX_SPOT_COLOR_AM: r = {FXG_COLOR, 24}; break;
        case FX_DISSOLVE_AM: r = {FXG_STYLE, 15}; break;
        case FX_SIMPLE_STARS_AM: r = {FXG_STYLE, 16}; break;
        case FX_DOTS_AM: r = {FXG_STYLE, 17}; break;
        case FX_RIDGES_AM: r = {FXG_STYLE, 18}; break;
        // v175 tier (the dedicated passes stay FXG_NONE like FX_GLOW_SCAN).
        case FX_THRESHOLD_AM: r = {FXG_COLOR, 25}; break;
        case FX_SPECTRALMAP_AM: r = {FXG_COLOR, 26}; break;
        case FX_HALFTONE_LINES_AM: r = {FXG_WARP, 27}; break;
        case FX_TURBULENCE2_AM: r = {FXG_STYLE, 19}; break;
        case FX_CHECKER2_AM: r = {FXG_STYLE, 20}; break;
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
        // v163 tier:
        case FX_LUMA_KEY: return p[3] < 0.5f && p[0] <= 0.f && p[1] >= 100.f;   // band covers every luma, no invert
        case FX_FOUR_COLOR_GRADIENT: return p[5] < 0.5f;   // alpha 0: the mix keeps the layer
        case FX_TURBULENT_DISPLACE_AM: return p[0] < 0.5f;   // intensity 0: the displacement is 0
        case FX_SWIRL_AM: return std::fabs(p[0]) < 0.5f;   // strength 0: no turn
        case FX_STRETCH_SEGMENT: return p[1] < 0.5f;   // stretch 0: the shift is ~0 (sminCubic residue < 1e-5)
        case FX_HALFTONE_DOTS: return false;   // always a two-colour posterize, even at strength 0
        case FX_PIXELATE_AM: return p[0] <= 1.f && p[4] < 0.5f && p[6] < 0.5f;   // 1 px blocks, no vignette / threshold
        case FX_REPLACE_COLOR: return p[4] < 0.5f;   // alpha 0: the mix keeps the pixel
        // v164 tier:
        case FX_CHROMA_KEY_AM: return false;   // a key always does something (no neutral state)
        case FX_COLOR_BALANCE_AM: {
            // every band 0 -> conv = (0 + 100) / 100 = 1 per channel
            bool zero = true;
            for (int i = 0; i < 7; ++i) zero = zero && std::fabs(p[i]) < 0.005f;
            return zero && std::fabs(o.q[0]) < 0.005f && std::fabs(o.q[1]) < 0.005f;
        }
        case FX_COLOR_TEMP_AM: return p[1] < 0.5f;   // strength 0: the mix keeps the pixel
        case FX_COLOR_TINT_AM: return p[1] < 0.5f;   // strength 0: the chroma mix is a no-op
        case FX_SPIN_BLUR_AM: return std::fabs(p[0]) < 0.005f;   // angle 0: every tap is the pixel
        case FX_OFFSET_AM: return std::fabs(p[0]) < 0.005f && std::fabs(p[1]) < 0.005f;   // no shift
        case FX_RADIAL_WIPE_AM: return p[0] <= 0.f;   // progress <= 0: fully visible, every direction
        case FX_FIND_EDGES_AM: return false;   // always a posterize of the gradient
        case FX_HEX_SHIFT_AM: return std::fabs(p[0]) < 0.00005f;   // strength 0: the sample is the pixel
        case FX_SOFT_GLOW_AM: return p[6] < 0.5f;   // alpha 0: blendInside is 0, the screen keeps the pixel
        case FX_DARK_GLOW_AM: return p[4] < 0.5f || p[1] <= 0.f;   // alpha 0, or no pixel under the threshold: the extract is empty
        case FX_EDGE_GLOW_AM: return false;   // the (blurred) edge buffer is added back even at blend 0, and gamma / brightness re-grade the base
        case FX_FILL_BEHIND_AM: return false;   // the edge / feather terms can bite even at strength 0
        // v165 tier:
        case FX_WAVE_WARP_AM: return std::fabs(p[3]) < 0.0005f;   // magnitude 0: the offset is 0
        case FX_CIRCULAR_RIPPLE_AM: return std::fabs(p[0]) < 0.0005f;   // strength 0: no offset
        case FX_STRETCH_AM: return std::fabs(p[0] - 1.f) < 0.0005f;   // scale 1: the sample is the pixel
        case FX_SQUEEZE_AM: return std::fabs(p[0]) < 0.0005f;   // strength 0: both divisors are 1
        case FX_FRACTAL_WARP_AM: return std::fabs(p[0]) < 0.0005f;   // magnitude 0: the sample is the pixel
        case FX_STRIPES_AM: return false;   // a band pattern always paints (or punches out)
        case FX_TURBULENT_DISPLACE3_AM: return std::fabs(p[0]) < 0.0005f;   // intensity 0: p is 0
        case FX_ROUGHEN_EDGES_AM: return std::fabs(p[0]) < 0.0005f;   // strength 0: p is 0, the sample is the pixel
        case FX_BEND_WARP_AM: return std::fabs(p[0]) < 0.0005f;   // amount 0: bend() is the identity branch
        case FX_STREAKS_AM: return std::fabs(p[0]) < 0.0005f || p[3] < 0.0005f;   // no velocity, or the mix keeps the original
        case FX_COLOR_HOT_AM: return false;   // the luma re-grade always bites
        case FX_COLORIZE_AM: return std::fabs(p[1]) < 0.0005f;   // amount 0: the chroma mix is a no-op
        case FX_HIGHLIGHT_SHADOW_AM: return std::fabs(p[0] - 1.f) < 0.0005f && std::fabs(p[1] - 1.f) < 0.0005f;   // 1 / 1 is GPUImage's neutral
        case FX_SPOT_COLOR_AM: return false;   // the desaturate side always differs somewhere
        case FX_DISSOLVE_AM: return p[0] >= 1.f;   // step(amount, r) is 1 everywhere (r < 1)
        case FX_SIMPLE_STARS_AM: return o.q[2] < 0.0005f;   // alpha 0: the final mix keeps the pixel
        case FX_DOTS_AM: return o.q[2] < 0.0005f && std::fabs(o.q[1] - 1.f) < 0.0005f;   // alpha 0 and fill 1: the output is the pixel
        case FX_RIDGES_AM: return false;   // the colour field always paints over the layer
        case FX_DISPLACE_MAP_AM: return false;   // the output is the backdrop even at offset 0 (the layer only drives it)
        case FX_DISPLACE_MAP_RAD_AM: return false;   // same: the output is the composite, not the layer
        case FX_SMOOTH_BEVEL_AM: return p[0] < 0.0005f;   // radius 0: AM's scan degenerates (a division by radius2)
        case FX_LIGHT_GLOW_AM: return p[6] < 0.0005f;   // alpha 0: the composite keeps the base
        case FX_INNER_GLOW_AM: return p[0] < 0.5f || p[1] < 0.0005f;   // AM: radius < 0.5 is a plain copy; alpha 0 mixes nothing
        case FX_EXTRUDE_AM: return false;   // the march replaces the picture even at rotate 0 (the box shades / reframes it)
        case FX_EXTRUDE_FLAT_AM: return false;   // same
        // v175 tier:
        case FX_THRESHOLD_AM: return false;   // a luma posterize always bites
        case FX_SPECTRALMAP_AM: return p[2] < 0.0005f;   // alpha 0: the mix keeps the pixel
        case FX_CHANNELMAP_AM:
            return p[0] < 0.5f && p[1] > 0.5f && p[1] < 1.5f && p[2] > 1.5f && p[2] < 2.5f &&
                   p[3] > 2.5f && p[3] < 3.5f;   // the identity routing R / G / B / A
        case FX_HALFTONE_LINES_AM: return false;   // a two-colour posterize, like dots
        case FX_TURBULENCE2_AM: return o.r[0] < 0.0005f;   // alpha 0: the mix keeps the pixel
        case FX_CHECKER2_AM: return false;   // the pattern always paints (or punches out)
        case FX_MOSAIC_AM: return p[0] <= 1.f;   // tiles of a pixel or less sample the pixel itself
        case FX_GLOW_AM: return p[1] < 0.0005f;   // alpha 0: outColor is 0 (radius < 0.5 is the def's own copy branch, checked in the pass)
        case FX_INNER_BLUR_AM: return p[0] < 0.00005f;   // strength 0: every box tap is the centre tap, the composite the pixel
        case FX_BOX_BLUR3_AM: return p[0] <= 0.0001f;   // the def's own passthrough
        case FX_FEATHER_AM: return p[0] <= 0.005f;   // the def's own passthrough
        case FX_ELECTRIC_EDGES_AM:
            return p[6] < 0.0005f && o.q[0] < 0.0005f && std::fabs(o.q[3] - 1.f) < 0.0005f;   // no edge, no glow, fill 1: c is 0
        case FX_SMOOTH_EDGES_AM: return false;   // the alpha cut reshapes even at strength 0 (dd = 0.05)
        // v176 tier:
        case FX_GAUSSIAN_BLUR_AM: return p[0] <= 0.f;   // sigma 0
        case FX_DEEP_GLOW2_AM:
            // strength 0: the def outputs the input (overd then scales it by its
            // alpha — an identity only while overd is off).
            return p[0] <= 0.00001f && !(std::lround(o.r[3]) & 1);
        case FX_DROP_SHADOW_AM:
            // The layer at alphac over the shadow: identity when the layer is
            // untouched at full weight and the shadow paints nothing.
            return p[5] >= 1.f && (p[4] <= 0.f || p[1] <= 0.f);
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

float fxAmGaussianSigma(float strength, float previewDiagPx) {
    // AM's gaussianblur kernel (its def, hunt round 3): numBlurPixelsPerSide =
    // strength / 2 * 1.14 / 4 / 3 * length(acPreviewSize) in half-res px, sigma =
    // that / 2.14596602 at half res — twice it at full res:
    // sigma = strength * length(previewSize) * 0.04427 (approx).
    if (strength <= 0.f) return 0.f;
    return strength * 0.5f * 1.14f / 4.f / 3.f * previewDiagPx / 2.14596602f * 2.f;
}

float fxGlowScanBlurSigma(float smoothingPct, float previewDiagPx) {
    // The scan's smoothing is AM's gaussianblur strength (stored x 100 in the slot).
    return fxAmGaussianSigma(smoothingPct / 100.f, previewDiagPx);
}

// ---- v163: Alight's next tier (CPU references of the recovered definitions) ----

namespace {
// GLSL smoothstep (the formula form, so a reversed edge pair still ramps, like AM's own shader
// relies on for Replace Color): t = clamp((x - e0) / (e1 - e0)), then the cubic.
float fxSstep(float e0, float e1, float x) {
    const float t = std::min(1.f, std::max(0.f, (x - e0) / (e1 - e0)));
    return t * t * (3.f - 2.f * t);
}
float fxClamp01(float v) { return std::min(1.f, std::max(0.f, v)); }
}  // namespace

float fxLumaKeyAlpha(float r, float g, float b, float low, float high, float feather, bool invert, bool weighted) {
    // AM's lumakey3 shader, line by line (the GPU twin is Renderer::fxLumaKey's shader).
    const float luma = weighted ? 0.2126f * r + 0.7152f * g + 0.0722f * b
                                : 0.3333f * r + 0.3334f * g + 0.3333f * b;
    const float fw = std::max(feather, 0.00001f);
    const float lowB = low * (1.f + feather) - 0.001f;
    const float lowA = lowB - fw;
    const float highA = high * (1.f + feather) - feather + 0.001f;
    const float highB = highA + fw;
    float alpha = std::min(fxSstep(lowA, lowB, luma), 1.f - fxSstep(highA, highB, luma));
    if (invert) alpha = 1.f - alpha;
    return alpha;
}

void fxReplaceColorMap(float r, float g, float b, float oldR, float oldG, float oldB,
                       float newR, float newG, float newB, float threshold, float feather,
                       float alpha, bool lockLuminance, float* outR, float* outG, float* outB) {
    // AM's replacecolor shader (group 0), line by line: YUV distance with the chroma counted x4
    // and the luma x0.5, a reversed smoothstep over the band, then either a plain colour shift or
    // a luminance-locked recolour. The GPU twin is kFxColor mode 17.
    auto toYuv = [](float cr, float cg, float cb, float* y, float* u, float* v) {
        *y = 0.299f * cr + 0.587f * cg + 0.114f * cb;
        *u = -0.14713f * cr - 0.28886f * cg + 0.436f * cb;
        *v = 0.615f * cr - 0.51499f * cg - 0.10001f * cb;
    };
    float oy, ou, ov, sy, su, sv;
    toYuv(oldR, oldG, oldB, &oy, &ou, &ov);
    toYuv(r, g, b, &sy, &su, &sv);
    const float dy = std::fabs(oy - sy) * 0.5f;
    const float du = std::fabs(ou - su) * 4.f;
    const float dv = std::fabs(ov - sv) * 4.f;
    const float diff = std::sqrt(dy * dy + du * du + dv * dv);
    const float fw = std::max(feather, 0.0005f);
    const float bb = std::max(threshold - fw, 0.f);
    const float aa = std::min(threshold + fw, 4.f);
    const float p = fxSstep(aa, std::min(bb, aa - 0.0005f), diff);
    float ar, ag, ab;
    if (lockLuminance) {
        float ny, nu, nv;
        toYuv(newR, newG, newB, &ny, &nu, &nv);
        ny = sy;  // the pixel keeps its own luminance
        ar = ny + 1.13983f * nv;
        ag = ny - 0.39465f * nu - 0.58060f * nv;
        ab = ny + 2.03211f * nu;
    } else {
        ar = r - oldR + newR;
        ag = g - oldG + newG;
        ab = b - oldB + newB;
    }
    const float m = fxClamp01(alpha);  // AM mixes by newcolor.a * alpha; newcolor.a is 1
    *outR = r + (ar - r) * p * m;      // = mix(r, mix(r, adj, p), alpha) expanded
    *outG = g + (ag - g) * p * m;
    *outB = b + (ab - b) * p * m;
}

void fxFourColorMap(float stX, float stY, const float cols[12], const float offs[8], float blend,
                    float alpha, int blendMode, float tr, float tg, float tb, float ta,
                    float* outR, float* outG, float* outB, float* outA) {
    // AM's fourcolorgradient shader (the GPU twin is Renderer::fxFourColorGradient's shader; the
    // 1/255 dither AM adds against banding is not reproduced here — it is below one 8-bit step).
    float cr = 0.f, cg = 0.f, cb = 0.f, wsum = 0.f;
    const float ex = 1.f / std::max(blend, 0.001f);
    for (int i = 0; i < 4; ++i) {
        const float px = offs[2 * i] / 500.f;
        const float py = -offs[2 * i + 1] / 500.f;
        const float dx = stX - px, dy = stY - py;
        const float t = std::sqrt(dx * dx + dy * dy);
        const float sw = fxSstep(0.f, 1.f, fxClamp01(1.f - t));
        const float pw = std::pow(sw, ex);
        cr += cols[3 * i] * pw;
        cg += cols[3 * i + 1] * pw;
        cb += cols[3 * i + 2] * pw;
        wsum += sw;
    }
    const float inv = 1.f / std::max(wsum, 1e-6f);
    cr *= inv;
    cg *= inv;
    cb *= inv;
    float rr, rg, rb, ra;
    if (blendMode == 1 || blendMode == 2) {
        const float ia = 1.f / std::max(ta, 1e-5f);  // AM divides by texColor.a (see fxMirrorMap)
        const float sr = tr * ia, sg = tg * ia, sb = tb * ia;
        if (blendMode == 1) {
            rr = sr * cr * ta; rg = sg * cg * ta; rb = sb * cb * ta;
        } else {
            rr = (1.f - (1.f - sr) * (1.f - cr)) * ta;
            rg = (1.f - (1.f - sg) * (1.f - cg)) * ta;
            rb = (1.f - (1.f - sb) * (1.f - cb)) * ta;
        }
        ra = ta;
    } else {
        rr = cr * ta; rg = cg * ta; rb = cb * ta; ra = ta;
    }
    const float m = fxClamp01(alpha);
    *outR = tr + (rr - tr) * m;
    *outG = tg + (rg - tg) * m;
    *outB = tb + (rb - tb) * m;
    *outA = ta + (ra - ta) * m;
}

float fxSnoise3(float x, float y, float z) {
    // Ashima simplex noise, the snoise(vec3) of AM's turbulentdisplace shader, transcribed line by
    // line (the GPU twin in Renderer::fxTurbulentAm's shader is the same transcription).
    auto mod289f = [](float v) { return v - std::floor(v * (1.f / 289.f)) * 289.f; };
    auto permute1 = [&](float v) { return mod289f(((v * 34.f) + 1.f) * v); };
    auto taylor = [](float r) { return 1.79284291400159f - 0.85373472095314f * r; };
    const float c1 = 1.f / 6.f, c2 = 1.f / 3.f;
    // i = floor(v + dot(v, C.yyy)); x0 = v - i + dot(i, C.xxx)
    const float s = (x + y + z) * c2;
    float ix = std::floor(x + s), iy = std::floor(y + s), iz = std::floor(z + s);
    const float t = (ix + iy + iz) * c1;
    const float x0[3] = {x - ix + t, y - iy + t, z - iz + t};
    // g = step(x0.yzx, x0.xyz); l = 1 - g; i1 = min(g, l.zxy); i2 = max(g, l.zxy)
    const float g[3] = {x0[0] >= x0[1] ? 1.f : 0.f, x0[1] >= x0[2] ? 1.f : 0.f, x0[2] >= x0[0] ? 1.f : 0.f};
    const float l[3] = {1.f - g[0], 1.f - g[1], 1.f - g[2]};
    const float i1[3] = {std::min(g[0], l[2]), std::min(g[1], l[0]), std::min(g[2], l[1])};
    const float i2[3] = {std::max(g[0], l[2]), std::max(g[1], l[0]), std::max(g[2], l[1])};
    const float x1[3] = {x0[0] - i1[0] + c1, x0[1] - i1[1] + c1, x0[2] - i1[2] + c1};
    const float x2[3] = {x0[0] - i2[0] + c2, x0[1] - i2[1] + c2, x0[2] - i2[2] + c2};
    const float x3[3] = {x0[0] - 0.5f, x0[1] - 0.5f, x0[2] - 0.5f};
    ix = mod289f(ix);
    iy = mod289f(iy);
    iz = mod289f(iz);
    float p[4];
    for (int k = 0; k < 4; ++k) {
        const float oz = k == 0 ? 0.f : k == 1 ? i1[2] : k == 2 ? i2[2] : 1.f;
        const float oy = k == 0 ? 0.f : k == 1 ? i1[1] : k == 2 ? i2[1] : 1.f;
        const float ox = k == 0 ? 0.f : k == 1 ? i1[0] : k == 2 ? i2[0] : 1.f;
        p[k] = permute1(permute1(permute1(iz + oz) + iy + oy) + ix + ox);
    }
    const float n_ = 0.142857142857f;
    const float nsx = 2.f * n_;            // ns = n_ * D.wyz - D.xzx = (2 n_, 0.5 n_ - 1, n_)
    const float nsy = 0.5f * n_ - 1.f;
    const float nsz = n_;
    float gx[4], gy[4];
    for (int k = 0; k < 4; ++k) {
        const float j = p[k] - 49.f * std::floor(p[k] * nsz * nsz);
        const float xf = std::floor(j * nsz);
        const float yf = std::floor(j - 7.f * xf);
        gx[k] = xf * nsx + nsy;
        gy[k] = yf * nsx + nsy;
    }
    const float h[4] = {1.f - std::fabs(gx[0]) - std::fabs(gy[0]), 1.f - std::fabs(gx[1]) - std::fabs(gy[1]),
                        1.f - std::fabs(gx[2]) - std::fabs(gy[2]), 1.f - std::fabs(gx[3]) - std::fabs(gy[3])};
    // b0 = (x.xy, y.xy), b1 = (x.zw, y.zw); s = floor(b) * 2 + 1; sh = -step(h, 0)
    const float b0[4] = {gx[0], gx[1], gy[0], gy[1]};
    const float b1[4] = {gx[2], gx[3], gy[2], gy[3]};
    float a0[4], a1[4];
    {
        const float s0[4] = {std::floor(b0[0]) * 2.f + 1.f, std::floor(b0[1]) * 2.f + 1.f,
                             std::floor(b0[2]) * 2.f + 1.f, std::floor(b0[3]) * 2.f + 1.f};
        const float s1[4] = {std::floor(b1[0]) * 2.f + 1.f, std::floor(b1[1]) * 2.f + 1.f,
                             std::floor(b1[2]) * 2.f + 1.f, std::floor(b1[3]) * 2.f + 1.f};
        const float sh[4] = {h[0] > 0.f ? 0.f : -1.f, h[1] > 0.f ? 0.f : -1.f,
                             h[2] > 0.f ? 0.f : -1.f, h[3] > 0.f ? 0.f : -1.f};
        // a0 = b0.xzyw + s0.xzyw * sh.xxyy; a1 = b1.xzyw + s1.xzyw * sh.zzww
        a0[0] = b0[0] + s0[0] * sh[0];
        a0[1] = b0[2] + s0[2] * sh[0];
        a0[2] = b0[1] + s0[1] * sh[1];
        a0[3] = b0[3] + s0[3] * sh[1];
        a1[0] = b1[0] + s1[0] * sh[2];
        a1[1] = b1[2] + s1[2] * sh[2];
        a1[2] = b1[1] + s1[1] * sh[3];
        a1[3] = b1[3] + s1[3] * sh[3];
    }
    const float p0[3] = {a0[0], a0[1], h[0]};
    const float p1[3] = {a0[2], a0[3], h[1]};
    const float p2[3] = {a1[0], a1[1], h[2]};
    const float p3[3] = {a1[2], a1[3], h[3]};
    auto dot3 = [](const float a[3], const float b[3]) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; };
    const float n0 = taylor(dot3(p0, p0)), n1 = taylor(dot3(p1, p1));
    const float n2 = taylor(dot3(p2, p2)), n3 = taylor(dot3(p3, p3));
    const float d0 = dot3(x0, x0), d1 = dot3(x1, x1), d2 = dot3(x2, x2), d3 = dot3(x3, x3);
    const float m0 = std::max(0.6f - d0, 0.f), m1 = std::max(0.6f - d1, 0.f);
    const float m2 = std::max(0.6f - d2, 0.f), m3 = std::max(0.6f - d3, 0.f);
    const float w0 = m0 * m0, w1 = m1 * m1, w2 = m2 * m2, w3 = m3 * m3;
    return 42.f * (w0 * w0 * (p0[0] * n0 * x0[0] + p0[1] * n0 * x0[1] + p0[2] * n0 * x0[2]) +
                   w1 * w1 * (p1[0] * n1 * x1[0] + p1[1] * n1 * x1[1] + p1[2] * n1 * x1[2]) +
                   w2 * w2 * (p2[0] * n2 * x2[0] + p2[1] * n2 * x2[1] + p2[2] * n2 * x2[2]) +
                   w3 * w3 * (p3[0] * n3 * x3[0] + p3[1] * n3 * x3[1] + p3[2] * n3 * x3[2]));
}

float fxTurbulence(float stX, float stY, int octaves, float parX, float parY, float evolution) {
    // AM's turbulence(): octave sum of |snoise|, amplitude halving, st doubling + parallax per octave.
    float value = 0.f;
    float amp = 0.5f;
    float sx = stX, sy = stY;
    const int n = std::min(9, std::max(1, octaves));
    for (int i = 0; i < n; ++i) {
        value += amp * std::fabs(fxSnoise3(sx, sy, evolution));
        sx = sx * 2.f + parX;
        sy = sy * 2.f + parY;
        amp *= 0.5f;
    }
    return value;
}

void fxStretchSegmentUv(float uvX, float uvY, float angleDeg, float stretch, float offset, float smooth,
                        float* outX, float* outY) {
    // AM's stretchsegment shader: v = (cos a, -sin a) (the first row of its rot()), dist along v from
    // the centre, the sample pulled by sminCubic(stretch, |dist|) against the sign of dist.
    const float a = angleDeg * 0.0174533f;
    const float vx = std::cos(a), vy = -std::sin(a);
    const float stx = uvX - 0.5f, sty = uvY - 0.5f;
    const float dist = stx * vx + sty * vy + offset / 1000.f;
    const float adj = stretch / 500.f;
    const float k = std::max(0.00001f, smooth * adj);
    const float ad = std::fabs(dist);
    const float h = std::max(k - std::fabs(adj - ad), 0.f) / k;
    const float d = std::min(adj, ad) - h * h * h * k / 6.f;
    const float sgn = dist > 0.f ? 1.f : dist < 0.f ? -1.f : 0.f;
    *outX = uvX + vx * d * -sgn;
    *outY = uvY + vy * d * -sgn;
}

void fxSwirlUv(float uvX, float uvY, float cX, float cY, float strength, float radius, int version,
               float aspect, float* outX, float* outY) {
    // AM's swirl4 / swirl3 shaders: relative UV, aspect correction (v4 scales the LONGER axis,
    // v3 always scales y), inside the radius a turn of Percent^2 * A * 8 * pi, A = 2 * strength
    // over the legal range (strength <= 0.5 always, so AM's first mix branch is the one taken).
    float convX = 1.f, convY = 1.f;
    if (version >= 4) {
        if (aspect > 1.f) convX = aspect; else convY = 1.f / std::max(aspect, 1e-6f);
    } else {
        convY = 1.f / std::max(aspect, 1e-6f);
    }
    float qx = (uvX - cX) * convX, qy = (uvY - cY) * convY;
    const float dist = std::sqrt(qx * qx + qy * qy);
    if (dist < radius && radius > 1e-6f) {
        const float percent = (radius - dist) / radius;
        const float A = strength <= 0.5f ? strength / 0.5f : 1.f - (strength - 0.5f) / 0.5f;
        const float theta = percent * percent * A * 8.f * 3.14159f;
        const float S = std::sin(theta), C = std::cos(theta);
        const float rx = qx * C - qy * S;
        const float ry = qx * S + qy * C;
        qx = rx;
        qy = ry;
    }
    *outX = qx / convX + cX;
    *outY = qy / convY + cY;
}

void fxHalftoneDotsUv(float uvX, float uvY, float amount, float angleDeg, float phase, float aspect,
                      float* outX, float* outY, float* absDistX, float* absDistY) {
    // AM's halftonedots shader: centre-relative, y aspect-corrected, rotated; the in-cell position
    // folds to the cell's centre-most point, which becomes the sample position (un-rotated back).
    const float rad = angleDeg * 0.0174533f;
    const float cs = std::cos(rad), sn = std::sin(rad);
    float stx = uvX - 0.5f, sty = (uvY - 0.5f) / std::max(aspect, 1e-6f);
    // st * rot (AM's expression, a row-vector product): (x c + y s, -x s + y c)
    const float rx = stx * cs + sty * sn, ry = -stx * sn + sty * cs;
    stx = rx;
    sty = ry;
    auto mod1 = [](float v) { return v - std::floor(v); };
    float dY = mod1(sty * amount);
    dY = dY < 0.5f ? -dY : 1.f - dY;
    *absDistY = std::fabs(dY);
    dY /= amount;
    const float m2 = (sty * amount + 0.5f) - 2.f * std::floor((sty * amount + 0.5f) / 2.f);  // mod(..., 2)
    const float xoffs = (m2 >= 1.f ? 1.f : 0.f) * phase;   // step(1, mod(...)) * phase
    float dX = mod1(stx * amount + xoffs);
    dX = dX < 0.5f ? -dX : 1.f - dX;
    *absDistX = std::fabs(dX);
    dX /= amount;
    stx += dX;
    sty += dY;
    const float c2 = std::cos(-rad), s2 = std::sin(-rad);
    const float bx = stx * c2 + sty * s2, by = -stx * s2 + sty * c2;
    *outX = bx + 0.5f;
    *outY = by * std::max(aspect, 1e-6f) + 0.5f;
}

void fxHalftoneDotsMap(float sampR, float sampG, float sampB, float sampA, float absDistX, float absDistY,
                       float strength, const float c1[3], const float c2[3], float texA,
                       float* outR, float* outG, float* outB, float* outA) {
    // AM's decision: p from the sample's luma + transparency + in-cell distance; the two colours
    // mixed by p, scaled by the fragment's own alpha (premultiplied out).
    const float lum = 0.2126f * sampR + 0.7152f * sampG + 0.0722f * sampB;
    const float cell = std::sqrt(absDistX * absDistX + absDistY * absDistY);
    const float p = fxSstep(0.4f, 0.6f, lum + (1.f - sampA) + cell + strength - 0.5f);
    *outR = (c1[0] + (c2[0] - c1[0]) * p) * texA;
    *outG = (c1[1] + (c2[1] - c1[1]) * p) * texA;
    *outB = (c1[2] + (c2[2] - c1[2]) * p) * texA;
    *outA = texA;
}

void fxPixelateCentre(float uvX, float uvY, float size, float stretchX, float stretchY, float angleDeg,
                      bool screenSpace, float offX, float offY, float picW, float picH,
                      float* outX, float* outY, float* distCenter) {
    // AM's pixelate2 shader: the fragment's position inside its block, rotated back, gives the
    // block centre to sample; distCenter drives the cubic vignette darkening.
    const float psx = size * stretchX / std::max(picW, 1.f);
    const float psy = size * stretchY / std::max(picH, 1.f);
    float stx = uvX, sty = uvY;
    if (!screenSpace) {
        stx -= 0.5f;
        sty -= 0.5f;
    }
    const float rad = angleDeg * 0.0174533f;
    const float cs = std::cos(rad), sn = std::sin(rad);
    {
        const float rx = stx * cs + sty * sn, ry = -stx * sn + sty * cs;
        stx = rx;
        sty = ry;
    }
    stx -= offX / 1000.f;
    sty -= offY / 1000.f;
    auto modv = [](float v, float m) { return v - m * std::floor(v / m); };
    float px = modv(stx, psx) - psx * 0.5f;
    float py = modv(sty, psy) - psy * 0.5f;
    const float c2 = std::cos(-rad), s2 = std::sin(-rad);
    {
        const float rx = px * c2 + py * s2, ry = -px * s2 + py * c2;
        px = rx;
        py = ry;
    }
    px += psx * 0.5f;
    py += psy * 0.5f;
    *outX = uvX - px + psx * 0.5f;
    *outY = uvY - py + psy * 0.5f;
    const float dx = px / psx - 0.5f, dy = py / psy - 0.5f;
    *distCenter = fxSstep(0.f, 1.f, std::sqrt(dx * dx + dy * dy));
}

float fxAutoPhase(int frame, float speed) { return (float)frame / 24.f * (speed / 100.f); }

float fxSplitPixels(float amount, float unit) {
    return std::min(100.f, std::max(0.f, amount)) / 100.f * 27.f * unit;  // 27 px = 2.5 % of 1080
}

float fxGrainCell(float size, float unit) {
    return (1.f + 3.f * std::min(100.f, std::max(0.f, size)) / 100.f) * std::max(0.25f, unit);
}

int fxGrainFrame(int64_t timeMs) { return (int)(timeMs * 24 / 1000); }

// ---- v164: Alight's parked tier (CPU references of the recovered definitions) ----

namespace {
// AM's rgb2yuv, applied the way its shaders apply it: yuv = rgb * M with M built column-major
// from (0.299, 0.587, 0.114 | -0.14713, -0.28886, 0.436 | 0.615, -0.51499, -0.10001), i.e.
// y = .299r + .587g + .114b, u = -.14713r - .28886g + .436b, v = .615r - .51499g - .10001b.
void fxAmToYuv(float r, float g, float b, float* y, float* u, float* v) {
    *y = 0.299f * r + 0.587f * g + 0.114f * b;
    *u = -0.14713f * r - 0.28886f * g + 0.436f * b;
    *v = 0.615f * r - 0.51499f * g - 0.10001f * b;
}
// AM's yuv2rgb (the matching inverse, same convention).
void fxAmToRgb(float y, float u, float v, float* r, float* g, float* b) {
    *r = y + 1.13983f * v;
    *g = y - 0.39465f * u - 0.58060f * v;
    *b = y + 2.03211f * u;
}
float fxClamp(float v, float lo, float hi) { return std::min(hi, std::max(lo, v)); }
}  // namespace

void fxChromaKeyAmMap(float pr, float pg, float pb, float pa, float kr, float kg, float kb,
                      float threshold, float feather, bool defringe, bool invert, bool border,
                      float* outR, float* outG, float* outB, float* outA) {
    // AM's chromakey shader (defs-batch3), line by line. The distance is measured on the
    // UNpremultiplied pixel against the key colour, luma counted a quarter.
    float ky, ku, kv, ty, tu, tv;
    fxAmToYuv(kr, kg, kb, &ky, &ku, &kv);
    if (pa <= 0.f) { ty = tu = tv = 0.f; } else { fxAmToYuv(pr / pa, pg / pa, pb / pa, &ty, &tu, &tv); }
    const float dy = (ky - ty) * 0.25f, du = ku - tu, dv = kv - tv;
    const float dist = std::sqrt(dy * dy + du * du + dv * dv);
    float alpha = fxSstep(threshold - feather * 0.5f, threshold + feather * 0.5f, dist);
    if (invert) alpha = 1.f - alpha;
    float r = pr, g = pg, b = pb, a = pa;
    if (defringe) {   // texColor -= keyColor * (1 - alpha), on the premultiplied pixel AND its alpha
        const float s = 1.f - alpha;
        r -= kr * s; g -= kg * s; b -= kb * s; a -= 1.f * s;
    }
    if (border) alpha = 0.f;
    *outR = r * alpha * a;
    *outG = g * alpha * a;
    *outB = b * alpha * a;
    *outA = alpha * a;
}

void fxColorBalanceAmMap(float r, float g, float b, const float bands[9], bool fixLum,
                         float* outR, float* outG, float* outB) {
    // AM's colorbalance shader, line by line: YUV band weights on the luma, the filter clamped
    // to +-100, a per-channel gain of (filter + 100) / 100, and fixLum restoring the input Y.
    float y, u, v;
    fxAmToYuv(r, g, b, &y, &u, &v);
    const float low = 1.f - fxSstep(0.222f, 0.444f, y);
    const float midLow = fxSstep(0.222f, 0.444f, y) * (y <= 0.555f ? 1.f : 0.f);
    const float midHigh = (1.f - fxSstep(0.555f, 0.777f, y)) * (y >= 0.555f ? 1.f : 0.f) * (y <= 0.777f ? 1.f : 0.f);
    const float high = fxSstep(0.555f, 0.777f, y);
    const float mid = midLow + midHigh;
    float conv[3];
    for (int i = 0; i < 3; ++i) {
        float f = bands[i] * low + bands[3 + i] * mid + bands[6 + i] * high;
        f = fxClamp(f, -100.f, 100.f);
        conv[i] = (f + 100.f) / 100.f;
    }
    float cy, cu, cv;
    fxAmToYuv(r * conv[0], g * conv[1], b * conv[2], &cy, &cu, &cv);
    if (fixLum) cy = y;
    fxAmToRgb(cy, cu, cv, outR, outG, outB);
}

void fxColorTempAmGain(float kelvin, float* outR, float* outG, float* outB) {
    // AM's colortemperature fit, line by line: two 3x3 rational fits split at 6500 K, mixed
    // back to white below 1000 K. The matrix is column-major in the def; m[col] / (T + m[col+3])
    // + m[col+6] per channel.
    static const float kLo[9] = {0.f, -2902.1955373783176f, -8257.7997278925690f,
                                 0.f, 1669.5803561666639f, 2575.2827530017594f,
                                 1.f, 1.3302673723350029f, 1.8993753891711275f};
    static const float kHi[9] = {1745.0425298314172f, 1216.6168361476490f, -8257.7997278925690f,
                                 -2666.3474220535695f, -2173.1012343082230f, 2575.2827530017594f,
                                 0.55995389139931482f, 0.70381203140554553f, 1.8993753891711275f};
    const float* m = kelvin <= 6500.f ? kLo : kHi;
    const float t = fxClamp(kelvin, 1000.f, 40000.f);
    float gain[3];
    for (int i = 0; i < 3; ++i) gain[i] = fxClamp(m[i] / (t + m[i + 3]) + m[i + 6], 0.f, 1.f);
    const float w = fxSstep(1000.f, 0.f, kelvin);   // AM's smoothstep(1000, 0, T): 1 at/below 1000 K
    *outR = gain[0] + (1.f - gain[0]) * w;
    *outG = gain[1] + (1.f - gain[1]) * w;
    *outB = gain[2] + (1.f - gain[2]) * w;
}

void fxColorTintAmMap(float pr, float pg, float pb, float pa, float hueDeg, float strength,
                      float* outR, float* outG, float* outB, float* outA) {
    // AM's colortint shader, line by line: the tint colour is hsv(hue, 1, 0.5); the pixel's
    // chroma (premultiplied, like AM samples) mixes toward the tint's chroma by strength * a.
    float hr, hg, hb;
    fxHsvToRgb(hueDeg, 1.f, &hr, &hg, &hb);
    hr *= 0.5f; hg *= 0.5f; hb *= 0.5f;
    float ty, tu, tv, y, u, v;
    fxAmToYuv(hr, hg, hb, &ty, &tu, &tv);
    fxAmToYuv(pr, pg, pb, &y, &u, &v);
    const float f = strength * pa;
    u += (tu - u) * f;
    v += (tv - v) * f;
    fxAmToRgb(y, u, v, outR, outG, outB);
    *outA = pa;
}

float fxOffsetFeather(float uvX, float uvY, float offX, float offY, float feather) {
    // AM's offset seam factor: how far the wrapped sample coordinate sits from the seam,
    // per axis, against the feather width the offset's own fractional part sets.
    if (feather < 0.0001f) return 1.f;
    auto axis = [](float uv, float off, float f) {
        const float sc = uv + off / 1000.f;
        const float wrapped = sc - std::floor(sc);
        float fo = (off / 1000.f) - std::floor(off / 1000.f);
        const float dist = (0.5f - std::fabs(fo - 0.5f)) * 2.f * f;
        const float m = std::min(wrapped, 1.f - wrapped);
        return fxSstep(0.f, dist, m);
    };
    // AM: sampleCoord = layerNorm + offset * (-1, +1) / 1000 (x negated), feather from offset/1000.
    return axis(uvX, -offX, feather) * axis(uvY, offY, feather);
}

void fxOffsetAmMap(float sr, float sg, float sb, float sa, float fr, float fg, float fb, float fa,
                   float featherAmt, float maskFactor,
                   float* outR, float* outG, float* outB, float* outA) {
    // AM's offset composite: the original, faded by the seam factor, under the shifted sample.
    const float k = 1.f - featherAmt;
    const float orr = sr * k, og = sg * k, ob = sb * k, oa = sa * k;
    const float s = maskFactor * (1.f - oa);
    *outR = orr + fr * s;
    *outG = og + fg * s;
    *outB = ob + fb * s;
    *outA = oa + fa * s;
}

float fxRadialWipeAmPp(float stX, float stY, float progress, float feather, int direction,
                       float repeat, float offsetDeg) {
    // AM's radialwipe shader, line by line (its smoothstep calls include reversed edges, so
    // the formula form fxSstep is used throughout, like AM's own implementation-defined GLSL).
    const float adjProgress = direction == 1 ? 360.f - progress : progress;
    float end = adjProgress;
    end -= 180.f;
    end *= (360.f + feather) / 360.f;
    end += 180.f;
    const float offsetRad = (offsetDeg - 90.f + (direction == 2 ? end * 0.5f / repeat : 0.f)) * 0.0174533f;
    const float co = std::cos(offsetRad), si = std::sin(offsetRad);
    const float rx = stX * co - stY * si, ry = stX * si + stY * co;   // st * offsetRot (v * mat2(c, -s, s, c))
    const float adjFeather = std::max(0.01f, feather * 0.0174533f);
    const float halfFeather = adjFeather * 0.5f;
    const float endRad = end * 0.0174533f;
    float a = std::atan2(ry, rx) + 3.14159f;
    a = std::fmod(a * repeat, 6.2831853072f);
    if (a < 0.f) a += 6.2831853072f;
    const float p0 = fxSstep(adjFeather, 0.f, a);
    const float p1 = fxSstep(endRad - halfFeather, endRad + halfFeather, a);
    const float p2 = fxSstep(endRad - 6.2831853072f + halfFeather, endRad - 6.2831853072f - halfFeather, a);
    float pp = adjProgress <= 0.0001f ? 1.f : adjProgress >= 359.999f ? 0.f
               : std::min(1.f, p1 + p0 - p2);
    if (direction == 1) pp = 1.f - pp;
    return pp;
}

float fxFindEdgesAmG(const float luma8[8], float threshold) {
    // AM's findedges Scharr sum (order: nw, n, ne, w, e, sw, s, se), line by line.
    const float nw = luma8[0], n = luma8[1], ne = luma8[2], w = luma8[3], e = luma8[4];
    const float sw = luma8[5], s = luma8[6], se = luma8[7];
    const float v = nw * 3.f + n * 10.f + ne * 3.f + sw * -3.f + s * -10.f + se * -3.f;
    const float h = nw * -3.f + w * -10.f + sw * -3.f + ne * 3.f + e * 10.f + se * 3.f;
    return fxClamp(std::sqrt(h * h + v * v) - threshold, 0.f, 1.f);
}

namespace {
// AM's hexshift cell hash, line by line (its constants pinned by the def).
float fxHexRand(float x, float y) {
    const float dt = x * 719.310f + y * 178.233f;
    const float sn = dt - 3.14f * std::floor(dt / 3.14f);   // GLSL mod() (floor-based), not fmod
    const float s = std::sin(sn) * 33758.5453f;
    return s - std::floor(s);
}
void fxHexTile(float stX, float stY, float* idX, float* idY) {
    // Only the cell id survives into main(); the uv / huv outputs are unused there.
    const float hrx = 1.f, hry = 1.73f;
    auto mod2 = [](float v, float m) { return v - m * std::floor(v / m); };
    const float gx = mod2(stX, hrx) - hrx * 0.5f, gy = mod2(stY, hry) - hry * 0.5f;
    const float ox = mod2(stX - hrx * 0.5f, hrx) - hrx * 0.5f, oy = mod2(stY - hry * 0.5f, hry) - hry * 0.5f;
    const bool first = (gx * gx + gy * gy) < (ox * ox + oy * oy);
    const float uvx = first ? gx : ox, uvy = first ? gy : oy;
    *idX = stX - uvx;
    *idY = stY - uvy;
}
}  // namespace

void fxHexShiftAmUv(float uvX, float uvY, float size, float stretchX, float stretchY,
                    float angleDeg, float seed, float strength, float posX, float posY,
                    float aspect, float* outX, float* outY) {
    // AM's hexshift main(), line by line, in layer space (see the fx_plan.h note).
    float stX = uvX - 0.5f, stY = uvY - 0.5f;
    stX *= aspect;
    const float scX = size / 100.f * stretchX, scY = size / 100.f * stretchY;
    stX /= scX; stY /= scY;
    const float rad = angleDeg * 0.0174533f;
    const float co = std::cos(rad), si = std::sin(rad);
    {   // st *= rot(angle): v * mat2(c, -s, s, c) = (c x - s y, s x + c y)
        const float x = stX * co - stY * si, y = stX * si + stY * co;
        stX = x; stY = y;
    }
    float px = (posX / 500.f) * 1.f, py = (posY / 500.f) * -1.f;
    {   // pos *= rot(-angle)
        const float c2 = std::cos(-rad), s2 = std::sin(-rad);
        const float x = px * c2 - py * s2, y = px * s2 + py * c2;
        px = x; py = y;
    }
    auto mod2 = [](float v, float m) { return v - m * std::floor(v / m); };
    const float modPosX = mod2(px, 2.f), modPosY = mod2(py, 2.f * 1.73f);
    const float stepPosX = px - modPosX, stepPosY = py - modPosY;
    stX -= modPosX; stY -= modPosY;
    float idX, idY;
    fxHexTile(stX, stY, &idX, &idY);
    idX -= stepPosX; idY -= stepPosY;
    idX += 1000.f; idY += 1000.f;
    const float dx = fxHexRand(idX, idY + 3937.7249f * (seed + 0.01f));
    const float dy = fxHexRand(idX + 328.7319f + 432.7249f * (seed + 0.01f), idY - 971.382f);
    float dvx = (dx - 0.5f) * 2.f, dvy = (dy - 0.5f) * 2.f;
    dvx *= aspect;
    float sx = uvX + dvx * strength, sy = uvY + dvy * strength;
    auto mirror = [](float u) {
        float m = u - 2.f * std::floor(u / 2.f);
        return 1.f - std::fabs(1.f - m);
    };
    *outX = mirror(sx);
    *outY = mirror(sy);
}

void fxSoftGlowAmMap(float tr, float tg, float tb, float ta, float br, float bg, float bb, float ba,
                     float brightness, float contrast, float highlights,
                     float colR, float colG, float colB, float blend, float alpha,
                     float* outR, float* outG, float* outB, float* outA) {
    // AM's softglow main(), the layer-local branch, line by line.
    if (ta <= 0.f) { *outR = *outG = *outB = *outA = 0.f; return; }
    float ar = std::min(br * brightness, 1.f), ag = std::min(bg * brightness, 1.f), ab = std::min(bb * brightness, 1.f);
    const float aa = ba;
    float luminance = std::min(1.f, (0.2126f * ar + 0.7152f * ag + 0.0722f * ab) * brightness);
    float cr = ar + (luminance * colR - ar) * blend;
    float cg = ag + (luminance * colG - ag) * blend;
    float cb = ab + (luminance * colB - ab) * blend;
    float ca = aa + (luminance * 1.f - aa) * blend;
    const float grey = luminance * highlights;
    cr += (luminance - cr) * grey;
    cg += (luminance - cg) * grey;
    cb += (luminance - cb) * grey;
    cr *= 1.f + (cr - 1.f) * contrast;
    cg *= 1.f + (cg - 1.f) * contrast;
    cb *= 1.f + (cb - 1.f) * contrast;
    ca *= 1.f;   // AM scales only .rgb by the contrast mix; blendInside's alpha is ca * alpha below
    const float ir = cr * alpha, ig = cg * alpha, ib = cb * alpha;
    float sr = tr / ta, sg = tg / ta, sb = tb / ta;
    float rr = 1.f - (1.f - sr) * (1.f - ir);
    float rg = 1.f - (1.f - sg) * (1.f - ig);
    float rb = 1.f - (1.f - sb) * (1.f - ib);
    *outA = fxClamp(ta, 0.f, 1.f);
    *outR = fxClamp(rr * ta, 0.f, 1.f);
    *outG = fxClamp(rg * ta, 0.f, 1.f);
    *outB = fxClamp(rb * ta, 0.f, 1.f);
    (void)ca;
}

void fxDarkGlowExtract(float r, float g, float b, float a, float threshold,
                       float colR, float colG, float colB, float blend,
                       float* outR, float* outG, float* outB, float* outA) {
    // AM's darkglow pass 0, line by line: dark pixels mix toward the (premultiplied) colour.
    const float luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    if (!(luminance < threshold)) { *outR = *outG = *outB = *outA = 0.f; return; }
    *outR = r + (colR * a - r) * blend;
    *outG = g + (colG * a - g) * blend;
    *outB = b + (colB * a - b) * blend;
    *outA = a;
}

void fxDarkGlowComposite(float baseR, float baseG, float baseB, float baseA,
                         float blurR, float blurG, float blurB, float blurA, float alpha,
                         float* outR, float* outG, float* outB, float* outA) {
    // AM's darkglow final composite: the blurred extract's straight colour times the base's
    // straight colour, over the base at min(base.a, extract.a) * alpha (its composite()).
    const float glowA = std::min(baseA, blurA) * alpha;
    float gr = 0.f, gg = 0.f, gb = 0.f;
    if (blurA > 0.0001f && baseA > 0.0001f) {
        gr = (blurR / blurA) * (baseR / baseA) * glowA;
        gg = (blurG / blurA) * (baseG / baseA) * glowA;
        gb = (blurB / blurA) * (baseB / baseA) * glowA;
    }
    const float inv = 1.f - glowA;
    *outR = baseR * inv + gr;
    *outG = baseG * inv + gg;
    *outB = baseB * inv + gb;
    *outA = baseA * inv + glowA;
}

float fxDarkGlowSigma(float strength, float previewDiagPx) {
    // Darkglow's def carries the host gaussianblur kernel verbatim (build 120), so
    // this is fxAmGaussianSigma — kept under its own name for its build-120 pins.
    return fxAmGaussianSigma(strength, previewDiagPx);
}

void fxEdgeGlowAmEdge(const float luma8[8], float cR, float cG, float cB, float cA,
                      float threshold, bool invert,
                      float* outR, float* outG, float* outB, float* outA) {
    // AM's findedges-edgeglow pass, line by line: g is the shared Scharr magnitude
    // (fxFindEdgesAmG — the two defs' kernels are identical), then the def's output
    // stage: the edge grey times the pixel itself, or (1 - g) times its alpha alone.
    const float g = fxFindEdgesAmG(luma8, threshold);
    if (invert) {
        const float v = (1.f - g) * cA;
        *outR = v;
        *outG = v;
        *outB = v;
        *outA = cA;
    } else {
        *outR = g * cR;
        *outG = g * cG;
        *outB = g * cB;
        *outA = cA;
    }
}

void fxEdgeGlowAmComposite(float baseR, float baseG, float baseB, float baseA,
                           float glowR, float glowG, float glowB, float glowA,
                           float spread, float gamma, float brightness,
                           float fillR, float fillG, float fillB, float blend,
                           float tintR, float tintG, float tintB, float tint,
                           float* outR, float* outG, float* outB, float* outA) {
    // AM's edgeglow final pass, line by line (its `fill` is a constant 1 in the def, so
    // mix(lum, 1, fill) is 1 and drops out). The alpha guards mirror the GLSL twin's
    // max(a, 0.0001): where the alpha is 0 the colour is 0 too, so the guarded quotient
    // is the value AM's own division produces wherever the pixel is visible at all.
    float gr = glowR / std::max(glowA, 0.0001f);
    float gg = glowG / std::max(glowA, 0.0001f);
    float gb = glowB / std::max(glowA, 0.0001f);
    float br = baseR / std::max(baseA, 0.0001f);
    float bg = baseG / std::max(baseA, 0.0001f);
    float bb = baseB / std::max(baseA, 0.0001f);
    const float gp = std::max(0.0001f, 3.f - spread);
    gr = std::pow(std::max(gr, 0.f), gp);
    gg = std::pow(std::max(gg, 0.f), gp);
    gb = std::pow(std::max(gb, 0.f), gp);
    const float invGamma = 1.f / std::max(gamma, 0.01f);
    br = std::pow(std::max(br, 0.f), invGamma);
    bg = std::pow(std::max(bg, 0.f), invGamma);
    bb = std::pow(std::max(bb, 0.f), invGamma);
    float y, u, v;
    fxAmToYuv(br, bg, bb, &y, &u, &v);
    y += brightness;
    fxAmToRgb(y, u, v, &br, &bg, &bb);
    br = fxClamp(br, 0.f, 1.f);
    bg = fxClamp(bg, 0.f, 1.f);
    bb = fxClamp(bb, 0.f, 1.f);
    const float lum = 0.2126f * gr + 0.7152f * gg + 0.0722f * gb;
    gr *= glowA;
    gg *= glowA;
    gb *= glowA;
    br *= baseA;
    bg *= baseA;
    bb *= baseA;
    const float factor = std::max(fxClamp(blend, 0.f, 1.f) * (1.f - lum), blend - 1.f);
    *outR = (br + (br * tintR - br) * tint) + (gr + (fillR * lum - gr) * factor);
    *outG = (bg + (bg * tintG - bg) * tint) + (gg + (fillG * lum - gg) * factor);
    *outB = (bb + (bb * tintB - bb) * tint) + (gb + (fillB * lum - gb) * factor);
    *outA = baseA + (glowA + (lum - glowA) * factor);
}

float fxEdgeGlowAmBlurSigma(float strength, float previewDiagPx) {
    // The host gaussianblur of the edge buffer at the def's strength ("Blur"): the
    // gaussianblur def's kernel at the frame diagonal.
    return fxAmGaussianSigma(strength, previewDiagPx);
}

void fxDeepGlow2Pixel(float texR, float texG, float texB, float texA,
                      float blurR, float blurG, float blurB, float blurA,
                      float blurLowR, float blurLowG, float blurLowB,
                      float blurHighR, float blurHighG, float blurHighB,
                      float strength, float oalpha, float lowThreshold, float highThreshold,
                      float feather, float calpha, float hue, float satX, float satY,
                      float colR, float colG, float colB, float alpb,
                      int flags, int bmode,
                      float* outR, float* outG, float* outB, float* outA) {
    // AM's deepglow2 composite, line by line (hunt round 3, defs-batch8). The blur
    // sample at the glow position is [blurMid]; the chromatic split reads the two
    // offset samples instead of the position one. (The def never reads the low
    // sample's blue or the high sample's red — those params are honoured by being
    // ignored, exactly as in the shader.)
    (void)blurLowB; (void)blurHighR;
    const float bR = texR * calpha, bG = texG * calpha, bB = texB * calpha, bA = texA * calpha;
    float cR = blurR, cG = blurG, cB = blurB;
    const float cA = blurA;
    if (flags & 4) {   // CHROM: the centre channel comes from the mid sample
        const int cc = (flags >> 5) & 3;
        if (cc == 0) { cR = blurR; cG = blurLowG; cB = blurHighB; }
        else if (cc == 1) { cR = blurLowR; cG = blurG; cB = blurHighB; }
        else { cR = blurLowR; cG = blurHighG; cB = blurB; }
    }
    float gR = cR, gG = cG, gB = cB, gA = cA;
    if (flags & 2) {   // GCE: pow 1/2.2, then the whole vec4 scaled by the alpha
        gR = std::pow(std::max(cR, 0.f), 1.f / 2.2f) * cA;
        gG = std::pow(std::max(cG, 0.f), 1.f / 2.2f) * cA;
        gB = std::pow(std::max(cB, 0.f), 1.f / 2.2f) * cA;
        gA = cA * cA;
    }
    // SATVIB + HUE in YUV (the def's matrices, via the shared pair).
    float y, u, v;
    fxAmToYuv(gR, gG, gB, &y, &u, &v);
    const float rad = hue * 0.0174533f;
    const float cs = std::cos(rad), sn = std::sin(rad);
    const float hu = u * cs + v * sn, hv = -u * sn + v * cs;
    u = hu; v = hv;
    const float origSat = std::min(1.f, std::sqrt(u * u + v * v) * 2.5f);
    const float su = u * satY, sv = v * satY;
    u = su + (u - su) * origSat;
    v = sv + (v - sv) * origSat;
    u *= (satX + 1.f);
    v *= (satX + 1.f);
    float sR, sG, sB;
    fxAmToRgb(y, u, v, &sR, &sG, &sB);
    const float satA = gA;
    const float inv = 1.f / std::max(satA, 0.0001f);   // the edgeglow-comp guard
    const float dR = sR * inv, dG = sG * inv, dB = sB * inv;
    const float luma = dR * 0.2126f + dG * 0.7152f + dB * 0.0722f;
    const float lowB = lowThreshold * (1.f + feather) - 0.0001f;
    const float lowA = lowB - std::max(feather, 0.00001f);
    const float highA = highThreshold * (1.f + feather) - feather + 0.0001f;
    const float highB = highA + std::max(feather, 0.00001f);
    auto sstep = [](float a, float b, float x) {
        const float t = fxClamp((x - a) / (b - a), 0.f, 1.f);
        return t * t * (3.f - 2.f * t);
    };
    const float lumafx = std::min(sstep(lowA, lowB, luma), 1.f - sstep(highA, highB, luma));
    float k = satA * oalpha;
    if (lowThreshold > 0.001f || highThreshold < 0.001f) k *= lumafx;   // the def's condition, verbatim
    float blR = dR * k, blG = dG * k, blB = dB * k, blA = k;
    if (flags & 8) {   // cblend: the glow colour mixed in, then scaled by the glow alpha
        const float bgR = (flags & 16) ? colR : blR + colR;
        const float bgG = (flags & 16) ? colG : blG + colG;
        const float bgB = (flags & 16) ? colB : blB + colB;
        blR = (blR + (bgR - blR) * alpb) * blA;
        blG = (blG + (bgG - blG) * alpb) * blA;
        blB = (blB + (bgB - blB) * alpb) * blA;
        blA = blA * blA;
    }
    float oR, oG, oB, oA;
    if (bmode == 0) {          // SCREEN through newLayer
        const float sR2 = 1.f - (1.f - bR) * (1.f - blR);
        const float sG2 = 1.f - (1.f - bG) * (1.f - blG);
        const float sB2 = 1.f - (1.f - bB) * (1.f - blB);
        oR = bR * (1.f - blA) + sR2;
        oG = bG * (1.f - blA) + sG2;
        oB = bB * (1.f - blA) + sB2;
        oA = bA * (1.f - blA) + blA;
    } else if (bmode == 1) {   // COLOR (dodge): a vec4 division, alpha included
        oR = bR / (1.f - blR);
        oG = bG / (1.f - blG);
        oB = bB / (1.f - blB);
        oA = bA / (1.f - blA);
    } else if (bmode == 3) {   // LAYER: newLayer
        oR = bR * (1.f - blA) + blR;
        oG = bG * (1.f - blA) + blG;
        oB = bB * (1.f - blA) + blB;
        oA = bA * (1.f - blA) + blA;
    } else {                   // LINEAR (2, and the def's default)
        oR = bR + blR;
        oG = bG + blG;
        oB = bB + blB;
        oA = bA + blA;
    }
    if (strength <= 0.00001f) { oR = texR; oG = texG; oB = texB; oA = texA; }
    if (flags & 1) { oR *= texA; oG *= texA; oB *= texA; oA *= texA; }   // overd
    *outR = oR; *outG = oG; *outB = oB; *outA = oA;
}

void fxDropShadowPixel(float baseR, float baseG, float baseB, float baseA, float blurA,
                       float colR, float colG, float colB, float colA,
                       float alpha, float alphac,
                       float* outR, float* outG, float* outB, float* outA) {
    // AM's dropshadow composite, line by line: the layer at alphac over the tinted
    // blurred alpha (the def's newLayer(shadow, texColor)).
    const float tR = baseR * alphac, tG = baseG * alphac, tB = baseB * alphac, tA = baseA * alphac;
    const float sR = colR * alpha * blurA, sG = colG * alpha * blurA;
    const float sB = colB * alpha * blurA, sA = colA * alpha * blurA;
    *outR = sR * (1.f - tA) + tR;
    *outG = sG * (1.f - tA) + tG;
    *outB = sB * (1.f - tA) + tB;
    *outA = sA * (1.f - tA) + tA;
}

void fxFillBehindAmBg(float br, float bg, float bb, float blurA, float saturation,
                      float tintR, float tintG, float tintB, float tintStrength, float gamma,
                      float* outR, float* outG, float* outB, float* outA) {
    // AM's fillbehind background pipeline, line by line (brightness is 0: no slot, see fx_plan.h).
    float r = 0.f, g = 0.f, b = 0.f;
    if (blurA > 0.f) { r = br / blurA; g = bg / blurA; b = bb / blurA; }
    float y, u, v, ty, tu, tv;
    fxAmToYuv(r, g, b, &y, &u, &v);
    fxAmToYuv(tintR, tintG, tintB, &ty, &tu, &tv);
    u *= (saturation + 1.f);
    v *= (saturation + 1.f);
    u += (tu - u) * tintStrength;
    v += (tv - v) * tintStrength;
    fxAmToRgb(y, u, v, &r, &g, &b);
    const float inv = 1.f / gamma;
    r = fxClamp(std::pow(std::max(r, 0.f), inv), 0.f, 1.f);
    g = fxClamp(std::pow(std::max(g, 0.f), inv), 0.f, 1.f);
    b = fxClamp(std::pow(std::max(b, 0.f), inv), 0.f, 1.f);
    *outR = r * blurA;
    *outG = g * blurA;
    *outB = b * blurA;
    *outA = blurA;
}

// ---- v165: Alight's tier 3 (the same maths as fx_shaders.h warp modes 18..26 / colour
// modes 21..24 / blur2 mode 9 / style modes 15..18 and the dedicated passes) ----
namespace {
float fxT3Clamp(float v, float lo, float hi) { return std::min(hi, std::max(lo, v)); }
// AM's hsv2rgb (the K-formula of the defs): hue 0..1, saturation 0..1, value 0..1.
void fxAmHsv(float h, float s, float v, float* r, float* g, float* b) {
    float hr, hg, hb;
    fxHsvToRgb(h * 360.f, 1.f, &hr, &hg, &hb);
    *r = v * ((1.f - s) + s * hr);
    *g = v * ((1.f - s) + s * hg);
    *b = v * ((1.f - s) + s * hb);
}
}  // namespace

void fxWaveWarpAmUv(float uvX, float uvY, float phase, float a1d, float m1, float m2, float a2d,
                    float damping, float dampingSpace, float dampingOrigin, float* outX, float* outY) {
    // AM's wavewarp2 shader, line by line (layer space: st = acLayerNorm = our uv).
    const float a1 = a1d * 0.0174533f;
    const float a2 = (a1d + a2d) * 0.0174533f;
    const float rvx = std::cos(a1), rvy = -std::sin(a1);
    const float rawP = uvX * rvx + uvY * rvy;
    float spaceDamp = 1.f;
    if (dampingSpace < 0.f) {
        spaceDamp = 1.f - (fxT3Clamp(std::fabs(rawP - dampingOrigin), 0.f, 1.f) * (0.f - dampingSpace));
    } else if (dampingSpace > 0.f) {
        spaceDamp = 1.f - ((1.f - fxT3Clamp(std::fabs(rawP - dampingOrigin), 0.f, 1.f)) * dampingSpace);
    }
    const float space = m1 * spaceDamp;
    const float p = (uvX * rvx + uvY * rvy) * space;
    const float ddist = space != 0.f ? std::fabs(p / space) : 0.f;
    float damp = 1.f;
    if (damping < 0.f) {
        damp = 1.f - (fxT3Clamp(std::fabs(ddist - dampingOrigin), 0.f, 1.f) * (0.f - damping));
    } else if (damping > 0.f) {
        damp = 1.f - ((1.f - fxT3Clamp(std::fabs(ddist - dampingOrigin), 0.f, 1.f)) * damping);
    }
    const float mag = (m2 * damp) / 100.f;
    const float s = std::sin(p + phase * 6.28318f);
    *outX = uvX + std::cos(a2) * mag * s;
    *outY = uvY + (-std::sin(a2)) * mag * s;
}

void fxCircularRippleAmUv(float uvX, float uvY, float strength, float radius, float frequency,
                          float feather, float phase, float cX, float cY, float aspectYX,
                          float* outX, float* outY) {
    // AM's circularripple2 shader, line by line, layer-anchored: st is the layer norm
    // relative to the layer centre, y corrected by the aspect (AM uses the screen's).
    const float featherSize = radius * 0.5f * feather;
    const float inner = std::max(0.f, radius - featherSize);
    const float outer = std::max(inner + 0.00001f, radius + featherSize);
    float sx = uvX - 0.5f + (-cX / 1000.f);
    float sy = uvY - 0.5f + (cY / 1000.f);
    sy *= aspectYX;
    const float dist = std::sqrt(sx * sx + sy * sy);
    const float damping = fxSstep(outer, inner, dist);
    const float k = std::sin(dist * frequency * 3.14159f * 2.f + phase * 3.14159f * 2.f) * strength / 2.f * damping;
    float nx = 0.f, ny = 0.f;
    if (dist > 1e-9f) { nx = sx / dist; ny = sy / dist; }
    sx += nx * k;
    sy += ny * k;
    sy /= aspectYX;
    *outX = sx + 0.5f - (-cX / 1000.f);
    *outY = sy + 0.5f - (cY / 1000.f);
}

void fxStretchAmUv(float uvX, float uvY, float scale, float angleDeg, float* outX, float* outY) {
    // AM's stretch shader: ((((uv - .5) * rot) * (1/scale, 1)) * invrot) + .5 with GLSL's
    // vec * mat (row-vector) convention: v * M = (v.x * M[0][0] + v.y * M[1][0], ...).
    const float rad = angleDeg * 0.0174533f;
    const float c = std::cos(rad), s = std::sin(rad);
    // rot columns are (c, -s) and (s, c): v * rot = (v.x * c + v.y * s, -v.x * s + v.y * c)
    float x = uvX - 0.5f, y = uvY - 0.5f;
    float rx = x * c + y * s;
    float ry = -x * s + y * c;
    rx *= (scale != 0.f ? 1.f / scale : 0.f);
    // invrot = rot(-rad): columns (c, s) and (-s, c): v * invrot = (v.x * c - v.y * s, v.x * s + v.y * c)
    *outX = rx * c - ry * s + 0.5f;
    *outY = rx * s + ry * c + 0.5f;
}

void fxStretchAmMap(float sr, float sg, float sb, float sa, float tr, float tg, float tb, float ta,
                    int version, float* outR, float* outG, float* outB, float* outA) {
    // AM's stretch shaders under contentOnly: the sample is first masked by the layer's
    // own alpha (S *= T.a); v2 keeps the masked sample alone, v1 composites the layer
    // under it.
    const float mr = sr * ta, mg = sg * ta, mb = sb * ta, ma = sa * ta;
    if (version == 1) {
        *outR = mr + tr * (1.f - ma);
        *outG = mg + tg * (1.f - ma);
        *outB = mb + tb * (1.f - ma);
        *outA = ma + ta * (1.f - ma);
    } else {
        *outR = mr; *outG = mg; *outB = mb; *outA = ma;
    }
}

void fxSqueezeAmUv(float uvX, float uvY, float strength, float* outX, float* outY) {
    // AM's squeeze shader, line by line: st in [-1, 1], the divisors clamped to [0, 2].
    const float str = strength / 2.f;
    float x = (uvX - 0.5f) * 2.f;
    float y = (uvY - 0.5f) * 2.f;
    float xdiv = fxT3Clamp(1.f + (1.f - y * y) * (-str), 0.f, 2.f);
    float ydiv = fxT3Clamp(1.f + (1.f - x * x) * str, 0.f, 2.f);
    if (xdiv != 0.f) x /= xdiv;
    if (ydiv != 0.f) y /= ydiv;
    *outX = x / 2.f + 0.5f;
    *outY = y / 2.f + 0.5f;
}

float fxValueNoise(float stX, float stY) {
    // McGuire value noise (AM's fractalwarp4 / dots2 helper), line by line.
    auto random = [](float x, float y) {
        const float d = x * 12.9898f + y * 78.233f;
        const float s = std::sin(d) * 43758.5453123f;
        return s - std::floor(s);
    };
    const float ix = std::floor(stX), iy = std::floor(stY);
    const float fx = stX - ix, fy = stY - iy;
    const float a = random(ix, iy);
    const float b = random(ix + 1.f, iy);
    const float c = random(ix, iy + 1.f);
    const float d = random(ix + 1.f, iy + 1.f);
    const float ux = fx * fx * (3.f - 2.f * fx);
    const float uy = fy * fy * (3.f - 2.f * fy);
    return (a + (b - a) * ux) + (c - a) * uy * (1.f - ux) + (d - b) * ux * uy;
}

float fxValueFbm(float stX, float stY, float parX, float parY, int octaves, float intensity) {
    float value = 0.f;
    float amplitude = 0.5f;
    float x = stX, y = stY;
    const int n = std::max(1, std::min(9, octaves));
    for (int i = 0; i < n; ++i) {
        value += amplitude * fxValueNoise(x, y);
        x = x * 2.f + parX;
        y = y * 2.f + parY;
        amplitude *= intensity;
    }
    return value;
}

void fxFractalWarpAmUv(float uvX, float uvY, float mag, float scale, float intensity, int octaves,
                       float offX, float offY, float parX, float parY, float aspect,
                       float* outX, float* outY) {
    // AM's fractalwarp4 shader, line by line (layer space): st.x aspect-corrected, the offs
    // point in /1000 with the (-1, +1) flip, the parallax point /200 with the same flip.
    float sx = uvX * aspect + offX / 1000.f * -1.f;
    float sy = uvY + offY / 1000.f;
    const float px = parX / 200.f * -1.f;
    const float py = parY / 200.f;
    const float dx = fxValueFbm((sx - 0.5f) * 3.f * scale + 0.5f, (sy - 0.5f) * 3.f * scale + 0.5f,
                                px, py, octaves, intensity);
    const float dy = fxValueFbm((sx + 25.3f - 0.5f) * 3.f * scale + 0.5f,
                                (sy + 12.9f - 0.5f) * 3.f * scale + 0.5f,
                                px, py, octaves, intensity);
    *outX = uvX + (dx - 0.5f) * mag;
    *outY = uvY + (dy - 0.5f) * mag;
}

float fxStripesAmBand(float uvX, float uvY, float picW, float picH, float amount, float width,
                      float angleDeg, float phase, float smoothing) {
    // AM's stripes shader, layer branch, line by line: st is the top-down pixel position
    // relative to the layer centre over the picture diagonal, rotated by the def's matrix
    // (vec * mat, row-vector), then banded.
    const float len = std::sqrt(picW * picW + picH * picH);
    float sx = (uvX * picW - picW / 2.f) / (len > 0.f ? len : 1.f);
    float sy = ((1.f - uvY) * picH - picH / 2.f) / (len > 0.f ? len : 1.f);
    const float a = angleDeg * 0.0174533f;
    const float sn = std::sin(a), cs = std::cos(a);
    // mat2(sin, -cos, cos, sin) has columns (sin, -cos) and (cos, sin):
    // v * M = (v.x * sin + v.y * cos, -v.x * cos + v.y * sin); only x is read afterwards.
    const float rx = sx * sn + sy * cs;
    float p = std::fmod(rx * amount + phase, 1.f);
    if (p < 0.f) p += 1.f;
    return fxSstep(width, width - smoothing, p) * fxSstep(0.f, smoothing, p);
}

void fxStripesAmMap(float tr, float tg, float tb, float ta, float colR, float colG, float colB,
                    float band, bool punchout, float* outR, float* outG, float* outB, float* outA) {
    if (punchout) {
        const float k = 1.f - band;
        *outR = tr * k; *outG = tg * k; *outB = tb * k; *outA = ta * k;
        return;
    }
    // c = colour * band, then c *= tex.a, out = c + tex * (1 - c.a); the colour's alpha is 1.
    const float ca = band * ta;
    *outR = colR * ca + tr * (1.f - ca);
    *outG = colG * ca + tg * (1.f - ca);
    *outB = colB * ca + tb * (1.f - ca);
    *outA = ca + ta * (1.f - ca);
}

namespace {
// AM's turbulence() input geometry of turbulentdisplace3 for a direction: the sampleCoord
// the def builds from st (aspect-corrected layer norm, position already added).
void fxTurb3SampleCoord(float stX, float stY, int direction, bool singleAxis, float* outX, float* outY) {
    if (!singleAxis) { *outX = stX - 0.5f; *outY = stY - 0.5f; return; }
    switch (direction) {
        case 0: *outX = 0.f; *outY = stY - 0.5f; return;
        case 1: *outX = stX - 0.5f; *outY = 0.f; return;
        case 2:
        case 4: *outX = stX - 0.5f; *outY = stY - 0.5f; return;
        case 5: {
            const float dx = stX - 0.5f, dy = stY - 0.5f;
            *outX = 0.f; *outY = std::sqrt(dx * dx + dy * dy);
            return;
        }
        default: {   // R (3 and anything else)
            const float tt = std::fabs(std::atan2(stX - 0.5f, stY - 0.5f) / 6.28318f) * 2.f;
            *outX = tt; *outY = 0.f;
            return;
        }
    }
}
}  // namespace

void fxTurbulentDisplace3AmUv(float uvX, float uvY, float intensity, float scale, float evolution,
                              float seed, int direction, int octaves, float offs,
                              float parX, float parY, float posX, float posY,
                              float stretchX, float stretchY, bool singleAxis, float aspect,
                              float* outX, float* outY, bool* inside) {
    // AM's turbulentdisplace3 shader, layer branch, line by line.
    float stX = uvX * aspect + posX * -1.f / 500.f;
    float stY = uvY + posY / 500.f;
    const float parrX = parX * -1.f / 100.f;
    const float parrY = parY / 100.f;
    const float svX = scale * stretchY;
    const float svY = scale * stretchX;
    float scX, scY;
    fxTurb3SampleCoord(stX, stY, direction, singleAxis, &scX, &scY);
    float p = fxTurbulence((scX - 0.5f) / svX + 0.5f, (scY - 0.5f) / svY + 0.5f, octaves,
                           parrX, parrY, evolution + 7391.935f * seed);
    p += (offs - 0.25f);
    p *= intensity * 2.f;
    float x = uvX, y = uvY;
    auto turbQ = [&]() {
        float q = fxTurbulence(scX / svX + 0.5f, scY / svY + 0.5f, octaves, parrX, parrY,
                               evolution + 3937.7249f * (seed + 6.3379f));
        q += (offs - 0.25f);
        q *= intensity * 2.f;
        return q;
    };
    switch (direction) {
        case 0: x += p; break;
        case 1: y += p; break;
        case 2: {
            const float q = turbQ();
            x += p;
            y += q;
            break;
        }
        case 4: {
            const float q = turbQ();
            const float dx = x - 0.5f, dy = y - 0.5f;
            const float len = std::sqrt(dx * dx + dy * dy);
            if (len > 1e-9f) { x += dx / len * p; y += dy / len * p; }
            const float cq = std::cos(q), sq = std::sin(q);
            const float nx = cq * x - sq * y;
            const float ny = sq * x + cq * y;
            x = nx; y = ny;
            break;
        }
        case 5: {
            const float cq = std::cos(p), sq = std::sin(p);
            const float nx = cq * x - sq * y;
            const float ny = sq * x + cq * y;
            x = nx; y = ny;
            break;
        }
        default: {   // R
            const float dx = x - 0.5f, dy = y - 0.5f;
            const float len = std::sqrt(dx * dx + dy * dy);
            if (len > 1e-9f) { x += dx / len * p; y += dy / len * p; }
            break;
        }
    }
    *outX = x;
    *outY = y;
    *inside = x >= 0.f && x <= 1.f && y >= 0.f && y <= 1.f;
}

void fxRoughenEdgesAmUv(float uvX, float uvY, float strength, float detail, float evolution,
                        float seed, int octaves, float aspect,
                        float* outX, float* outY, float* outP) {
    // AM's roughenedges shader: turbulentdisplace3's body with parr / position 0, direction
    // R, scaleVec = (detail * 5, detail * 5), offs 0.15, intensity = strength * 0.1 (and no
    // x2: its p *= intensity, where turbulentdisplace3 has intensity * 2).
    const float stX = uvX * aspect;
    const float stY = uvY;
    const float sv = detail * 5.f;
    float p = fxTurbulence((stX - 0.5f) * sv + 0.5f, (stY - 0.5f) * sv + 0.5f, octaves,
                           0.f, 0.f, evolution + 7391.935f * seed);
    p += (0.15f - 0.25f);
    p *= strength * 0.1f;
    const float dx = uvX - 0.5f, dy = uvY - 0.5f;
    const float len = std::sqrt(dx * dx + dy * dy);
    float x = uvX, y = uvY;
    if (len > 1e-9f) { x += dx / len * p; y += dy / len * p; }
    *outX = x;
    *outY = y;
    *outP = p;
}

void fxStreaksAmVelocity(float strength, float angleDeg, float texelX, float texelY,
                         float aspectYX, float* outVx, float* outVy, int* outSamples) {
    // AM's streaks shader: velocity from the angle, x corrected by the aspect (the screen's
    // in AM; the layer's is the stand-in), the count from the speed in texels.
    const float rad = angleDeg * 0.0174533f;
    float vx = std::cos(rad) * strength;
    float vy = -std::sin(rad) * strength;
    vx *= aspectYX;
    const float sx = texelX != 0.f ? vx / texelX : 0.f;
    const float sy = texelY != 0.f ? vy / texelY : 0.f;
    const float speed = std::sqrt(sx * sx + sy * sy);
    *outVx = vx;
    *outVy = vy;
    *outSamples = (int)fxT3Clamp(speed, 1.01f, 100.01f);
}

float fxStreaksAmCombine(float acc, float tap, int mode) {
    switch (mode) {
        case 0: return std::min(acc, tap);
        case 1: return std::max(acc, tap);
        case 3: return acc + tap;
        default: return acc;   // 2 = off
    }
}

float fxStreaksAmFinish(float acc, int mode, int nSamples) {
    return mode == 3 && nSamples > 0 ? acc / (float)nSamples : acc;
}

void fxColorHotAmMap(float r, float g, float b, const float colorDisc[3], const float tintDisc[3],
                     float* outR, float* outG, float* outB) {
    // AM's colorhot shader, line by line: the luminance is read off the sampled
    // (premultiplied) colour; the output is the blend colour (the caller multiplies by a).
    float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    const float gamma = colorDisc[2] + 1.f;
    lum = std::pow(std::max(lum, 0.f), 1.f / gamma);
    float dr, dg, db, lr, lg, lb;
    fxAmHsv(colorDisc[0] / 360.f, colorDisc[1], 1.f, &dr, &dg, &db);
    fxAmHsv(tintDisc[0] / 360.f, tintDisc[1], 1.f, &lr, &lg, &lb);
    const float cutoff = tintDisc[2] / 2.f + 0.5f;
    float br, bgc, bb;
    if (lum < cutoff) {
        br = dr; bgc = dg; bb = db;   // dark == base in AM's def: the low branch is constant
    } else {
        const float t = (1.f - cutoff) > 1e-6f ? (lum - cutoff) / (1.f - cutoff) : 0.f;
        br = dr + (lr - dr) * t; bgc = dg + (lg - dg) * t; bb = db + (lb - db) * t;
    }
    float y, u, v;
    fxAmToYuv(br, bgc, bb, &y, &u, &v);
    y = lum;
    u *= lum;
    v *= lum;
    fxAmToRgb(y, u, v, &br, &bgc, &bb);
    float shine = std::max(0.f, lum - 0.85f) * (1.f / 0.15f);
    shine = shine * shine * colorDisc[1];
    *outR = br + (1.f - br) * shine;
    *outG = bgc + (1.f - bgc) * shine;
    *outB = bb + (1.f - bb) * shine;
}

void fxColorizeAmMap(float pr, float pg, float pb, float pa, float hueDeg, float amount,
                     float* outR, float* outG, float* outB, float* outA) {
    // AM's colorize shader, line by line (the same maths as Color Tint's, with the
    // hue-disc's y as the amount).
    float tr, tg, tb;
    fxAmHsv(hueDeg / 360.f, 1.f, 0.5f, &tr, &tg, &tb);
    float ty, tu, tv, y, u, v;
    fxAmToYuv(tr, tg, tb, &ty, &tu, &tv);
    fxAmToYuv(pr, pg, pb, &y, &u, &v);
    const float k = amount * pa;
    u += (tu - u) * k;
    v += (tv - v) * k;
    fxAmToRgb(y, u, v, outR, outG, outB);
    *outA = pa;
}

void fxHighlightShadowAmMap(float r, float g, float b, float shadows, float highlights,
                            float* outR, float* outG, float* outB) {
    // AM's highlightshadow shader (GPUImage's formula), line by line. AM divides the
    // sampled colour by its luminance; a black pixel is guarded to black (AM gets NaN
    // there — the mixes below still run, on 0).
    const float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    const float cs = fxT3Clamp(shadows, 1.f, 2.f);
    const float shadow = fxT3Clamp(std::pow(std::max(lum, 0.f), 1.f / cs) -
                                   0.76f * std::pow(std::max(lum, 0.f), 2.f / cs) - lum, 0.f, 1.f);
    const float hden = std::max(2.f - highlights, 0.001f);
    const float hl = fxT3Clamp(1.f - (std::pow(std::max(1.f - lum, 0.f), 1.f / hden) -
                                      0.8f * std::pow(std::max(1.f - lum, 0.f), 2.f / hden)) - lum,
                               -1.f, 0.f);
    float rr = 0.f, rg = 0.f, rb = 0.f;
    if (lum > 1e-6f) {
        const float k = (lum + shadow + hl) / lum;
        rr = r * k; rg = g * k; rb = b * k;
    }
    const float cl = (lum - 0.5f) * 1.5f + 0.5f;
    const float whiteK = cl * cl * cl * (fxT3Clamp(highlights, 1.f, 2.f) - 1.f);
    rr += (1.f - rr) * whiteK; rg += (1.f - rg) * whiteK; rb += (1.f - rb) * whiteK;
    const float icl = 1.f - cl;
    const float blackK = icl * icl * icl * (1.f - fxT3Clamp(shadows, 0.f, 1.f));
    rr *= (1.f - blackK); rg *= (1.f - blackK); rb *= (1.f - blackK);
    *outR = rr; *outG = rg; *outB = rb;
}

void fxSpotColorAmMap(float pr, float pg, float pb, float pa, float spotR, float spotG, float spotB,
                      float threshold, float feather, float saturation, float choke,
                      float* outR, float* outG, float* outB, float* outA) {
    // AM's spotcolor2 shader (group 0), line by line.
    float sy, su, sv, ty, tu, tv;
    fxAmToYuv(spotR, spotG, spotB, &sy, &su, &sv);
    fxAmToYuv(pr, pg, pb, &ty, &tu, &tv);
    su *= choke; sv *= choke;
    tu *= choke; tv *= choke;
    const float dy = sy - ty, du = su - tu, dv = sv - tv;
    const float dist = std::sqrt(dy * dy + du * du + dv * dv);
    const float a = std::min(threshold + feather, 1.f);
    const float b = std::max(threshold - feather, 0.f);
    const float p = fxSstep(a, std::min(b, a - 0.01f), dist);
    float dr, dg, db;
    fxAmToRgb(ty, tu * saturation / 2.f, tv * saturation / 2.f, &dr, &dg, &db);
    *outR = dr + (pr - dr) * p;
    *outG = dg + (pg - dg) * p;
    *outB = db + (pb - db) * p;
    *outA = pa;
}

float fxDissolveAmHash(float uvX, float uvY, float picW, float picH, float seed) {
    // AM's dissolve shader: st = floor(top-down pixel position - centre px) / layer px,
    // the hash is AM's hexshift rand (same constants), r = rand(st * t).
    const float stX = std::floor((uvX - 0.5f) * picW) / (picW > 0.f ? picW : 1.f);
    const float stY = std::floor((0.5f - uvY) * picH) / (picH > 0.f ? picH : 1.f);
    float t = std::fmod(seed * 3197.3791f, 2.8f) + 2.7f;
    const float coX = stX * t, coY = stY * t;
    const float dt = coX * 719.310f + coY * 178.233f;
    const float sn = std::fmod(dt, 3.14f);
    const float r = std::sin(sn) * 33758.5453f;
    return r - std::floor(r);
}

namespace {
// simplestars2's rand / rand2 (the McGuire hash, NOT the value noise).
float fxStarRand(float x, float y) {
    const float s = std::sin(x * 12.9898f + y * 78.233f) * 43758.5453123f;
    return s - std::floor(s);
}
float fxStarField(float stX, float stY, float parX, float parY, float depth, float size,
                  float feather, float starCount) {
    const float adjSize = size * 0.001f;
    const float f = depth - std::floor(depth);
    const float layer = std::floor(depth);
    float p = 0.f;
    const int n = (int)std::ceil(starCount);
    for (int i = 1; i < n; ++i) {
        const float fi = (float)i;
        if (!(fi < starCount)) break;
        const float ux = stX * ((fi - f) / 9.f) + parX;
        const float uy = stY * ((fi - f) / 9.f) + parY;
        const float rx = fxStarRand(fi + layer, 2.904f);
        const float ry = fxStarRand((fi + layer) + 3.7321f * 7.1231f, 2.904f + 9.2517f * 6.9377f);
        const float sx = std::sin((ux + rx) * 20.f);
        const float sy = std::sin((uy + ry) * 20.f);
        const float adjFeather = adjSize * feather;
        const float star = fxSstep(1.f - adjSize - adjFeather, 1.f - adjSize, sx * sy);
        p += star * fxSstep(starCount, 1.f, fi - f);
    }
    return p;
}
}  // namespace

float fxSimpleStarsAmField(float uvX, float uvY, float scale, float starSize, float starFeather,
                           float starCount, float parX, float parY, float parZ, float aspect) {
    // AM's simplestars2 main: st flipped, centred, aspect-corrected, scaled; the parallax
    // point's xy runs /1000 negated, z /45 is the depth.
    float stX = uvX - 0.5f;
    float stY = (1.f - uvY) - 0.5f;
    stX *= aspect;
    if (scale != 0.f) { stX /= scale; stY /= scale; }
    return fxStarField(stX, stY, -parX / 1000.f, -parY / 1000.f, parZ / 45.f, starSize,
                       starFeather, starCount);
}

void fxSimpleStarsAmAdj(float colR, float colG, float colB, float p, float brightness,
                        float* outR, float* outG, float* outB, float* outA) {
    // adjColor = clamp(vec4(mix(colour + 0.15, white, 0.05) * p * brightness, 1), 0, 1) * p.
    auto ch = [&](float c) {
        const float m = (c + 0.15f) + (1.f - (c + 0.15f)) * 0.05f;
        return fxT3Clamp(m * p * brightness, 0.f, 1.f) * p;
    };
    *outR = ch(colR);
    *outG = ch(colG);
    *outB = ch(colB);
    *outA = fxT3Clamp(1.f, 0.f, 1.f) * p;
}

void fxSimpleStarsAmMap(float tr, float tg, float tb, float ta,
                        float ar, float ag, float ab, float aa, float p,
                        int blendMode, float alpha,
                        float* outR, float* outG, float* outB, float* outA) {
    // AM's simplestars2 compose, line by line; the result is mixed over the pixel by alpha.
    float rr, rg, rb, ra;
    if (blendMode == 1) {
        // vec4((tex.rgb / tex.a) * adj.rgb, 1) * tex.a
        const float inv = ta > 1e-5f ? 1.f / ta : 0.f;
        rr = (tr * inv) * ar * ta; rg = (tg * inv) * ag * ta; rb = (tb * inv) * ab * ta; ra = ta;
    } else if (blendMode == 2) {
        // vec4(1 - (1 - tex.rgb / tex.a) * (1 - adj.rgb), 1) * tex.a
        const float inv = ta > 1e-5f ? 1.f / ta : 0.f;
        rr = (1.f - (1.f - tr * inv) * (1.f - ar)) * ta;
        rg = (1.f - (1.f - tg * inv) * (1.f - ag)) * ta;
        rb = (1.f - (1.f - tb * inv) * (1.f - ab)) * ta;
        ra = ta;
    } else if (blendMode == 3) {
        const float k = 1.f - std::min(p, 1.f);
        rr = tr * k; rg = tg * k; rb = tb * k; ra = ta * k;
    } else {
        // result = adj * tex.a; result = tex * (1 - result.a) + result
        const float raa = aa * ta;
        rr = tr * (1.f - raa) + ar * ta;
        rg = tg * (1.f - raa) + ag * ta;
        rb = tb * (1.f - raa) + ab * ta;
        ra = ta * (1.f - raa) + raa;
    }
    *outR = tr + (rr - tr) * alpha;
    *outG = tg + (rg - tg) * alpha;
    *outB = tb + (rb - tb) * alpha;
    *outA = ta + (ra - ta) * alpha;
}

namespace {
// dots2's rand2v: the VALUE-noise pair (not a hash), AM's offsets.
void fxDotsRand2v(float x, float y, float* outX, float* outY) {
    *outX = fxValueNoise(x, y);
    *outY = fxValueNoise(x + 613.312f, y + 736.019f);
}
float fxDotsRandom(float x, float y) {
    const float s = std::sin(x * 12.9898f + y * 78.233f) * 43758.5453123f;
    return s - std::floor(s);
}
}  // namespace

void fxDotsAmField(float uvX, float uvY, float scale, float phase, float radius, float feather,
                   float density, float seed, float slack, float posX, float posY, float aspect,
                   float* intensity, float* colorPct) {
    // AM's dots2 shader, line by line.
    float stX = uvX * aspect - 0.5f - posX / 1000.f;
    float stY = uvY - 0.5f + posY / 1000.f;
    const float div = scale * 0.07f;
    if (div != 0.f) { stX /= div; stY /= div; }
    const float tileX = std::floor(stX), tileY = std::floor(stY);
    const float tpX = stX - tileX, tpY = stY - tileY;
    float maxDist = 100.f;
    float nearestTileX = 0.f, nearestTileY = 0.f, nearestK = 0.f;
    const int densitySteps = (int)std::max(1.f, density - 10.f);
    const float threshold = std::min(1.f, density / 11.f);
    const float actualPhase = phase * 6.28318f;
    for (int i = -1; i <= 1; ++i) {
        for (int j = -1; j <= 1; ++j) {
            for (int k = 0; k < densitySteps; ++k) {
                float px, py;
                fxDotsRand2v(tileX + 523.135f * (float)k + (float)i + 123.215f * seed,
                             tileY + 621.265f * (float)k + (float)j + 272.055f * seed, &px, &py);
                const float sl = std::max(0.00001f, slack);
                px = 0.5f + 0.5f * std::sin(actualPhase + 6.2831f * px) * sl;
                py = 0.5f + 0.5f * std::sin(actualPhase + 6.2831f * py) * sl;
                const float dx = (float)i + px - tpX;
                const float dy = (float)j + py - tpY;
                const float dist = std::sqrt(dx * dx + dy * dy);
                const float tix = (float)i + tileX, tiy = (float)j + tileY;
                const float ck = fxDotsRandom(tix + (float)k + 541.117f * (seed + 0.1f),
                                              tiy - (float)k + 163.713f * (seed + 0.1f));
                if (dist < maxDist && ck <= threshold) {
                    maxDist = dist;
                    nearestK = (float)k;
                    nearestTileX = tix;
                    nearestTileY = tiy;
                }
            }
        }
    }
    *colorPct = fxDotsRandom(nearestTileX + nearestK + 721.117f * seed,
                             nearestTileY - nearestK + 433.215f * seed);
    *intensity = fxSstep(1.f - radius, 1.f - radius * (1.f - feather), 1.f - maxDist);
}

void fxDotsAmMap(float tr, float tg, float tb, float ta, float rr, float rg, float rb, float ra,
                 float fill, int blendMode, float alpha,
                 float* outR, float* outG, float* outB, float* outA) {
    // AM's dots2 compose, line by line: over = tex * fill; the blend branches composite
    // rcolor (premultiplied) with over; the output mixes over -> result by alpha.
    const float orr = tr * fill, og = tg * fill, ob = tb * fill, oa = ta * fill;
    float resr, resg, resb, resa;
    if (blendMode == 1 || blendMode == 2) {
        const float tinv = ta > 1e-5f ? 1.f / ta : 0.f;
        const float rinv = ra > 1e-5f ? 1.f / ra : 0.f;
        float cr, cg, cb;
        if (blendMode == 1) {
            cr = (tr * tinv) * (rr * rinv);
            cg = (tg * tinv) * (rg * rinv);
            cb = (tb * tinv) * (rb * rinv);
        } else {
            cr = 1.f - (1.f - tr * tinv) * (1.f - rr * rinv);
            cg = 1.f - (1.f - tg * tinv) * (1.f - rg * rinv);
            cb = 1.f - (1.f - tb * tinv) * (1.f - rb * rinv);
        }
        resr = fxT3Clamp(cr, 0.f, 1.f) * ra * ta;
        resg = fxT3Clamp(cg, 0.f, 1.f) * ra * ta;
        resb = fxT3Clamp(cb, 0.f, 1.f) * ra * ta;
        resa = ra * ta;
        resr = fxT3Clamp(resr + orr * (1.f - resa), 0.f, 1.f);
        resg = fxT3Clamp(resg + og * (1.f - resa), 0.f, 1.f);
        resb = fxT3Clamp(resb + ob * (1.f - resa), 0.f, 1.f);
        resa = resa + oa * (1.f - resa);
    } else {
        resr = rr + orr * (1.f - ra);
        resg = rg + og * (1.f - ra);
        resb = rb + ob * (1.f - ra);
        resa = ra + oa * (1.f - ra);
    }
    *outR = orr + (resr - orr) * alpha;
    *outG = og + (resg - og) * alpha;
    *outB = ob + (resb - ob) * alpha;
    *outA = oa + (resa - oa) * alpha;
}

float fxRidgesAmField(float uvX, float uvY, float intensity, float scale, float evolution,
                      float seed, int octaves, float offX, float offY, float parX, float parY,
                      float stretchX, float stretchY, int version, float aspect) {
    // AM's ridges2 / ridges main + ridgedMF, line by line.
    float stX = uvX * aspect + offX * -1.f / 500.f;
    float stY = uvY + offY / 500.f;
    stX += std::fmod(821.921f * seed, 4.365f);
    stY += std::fmod(437.317f * seed, 7.701f);
    const float parrX = parX * -1.f / 100.f;
    const float parrY = parY / 100.f;
    float svX, svY;
    if (version == 1) {
        if (stretchX < 0.f) { svX = scale - stretchX; svY = scale; }
        else { svX = scale; svY = scale + stretchX; }
    } else {
        svX = scale * stretchX;
        svY = scale * stretchY;
    }
    float px, py;
    if (version == 1) {
        px = (stX - 0.5f) * svX + 0.5f;
        py = (stY - 0.5f) * svY + 0.5f;
    } else {
        px = (stX - 0.5f) / svX + 0.5f;
        py = (stY - 0.5f) / svY + 0.5f;
    }
    // ridgedMF: sum of ridge(snoise(p * freq, evo)) * amp * (1 + prev), freq x2, amp x0.5.
    float sum = 0.f;
    float freq = 1.f, amp = 0.5f, prev = 1.f;
    const int n = std::max(1, std::min(9, octaves));
    for (int i = 0; i < n; ++i) {
        float h = std::fabs(fxSnoise3(px * freq, py * freq, evolution));
        h = 0.9f - h;
        h = h * h;
        sum += h * amp;
        sum += h * amp * prev;
        prev = h;
        freq *= 2.f;
        px += parrX;
        py += parrY;
        amp *= 0.5f;
    }
    return sum * intensity;
}

namespace {
void fxBendCurve(float px, float py, float a, bool vertical, float* outX, float* outY) {
    // AM's bendwarp bend(), line by line (l = w = 1 at both call sites).
    if (std::fabs(a) < 0.001f) { *outX = px; *outY = py; return; }
    const float s = a > 0.f ? 1.f : -1.f;
    if (vertical) {
        const float ra = 0.5f / a;
        const float qx = px - ra;
        const float len = std::sqrt(qx * qx + py * py);
        *outX = (std::fabs(ra) - len) * s;
        *outY = ra * std::atan2(s * py, -s * qx);
    } else {
        const float ra = 0.5f / a;
        const float qy = py - ra;
        const float len = std::sqrt(px * px + qy * qy);
        *outX = ra * std::atan2(s * px, -s * qy);
        *outY = (std::fabs(ra) - len) * s;
    }
}
}  // namespace

void fxBendWarpAmUv(float uvX, float uvY, float amountDeg, float offset, int originType,
                    int oppositeType, float highLimit, float lowLimit, float aspect,
                    float* outX, float* outY, bool* inside) {
    // AM's bendwarp shader, line by line (layer space).
    const float adjOffset = offset * 0.99f;
    float ax = 0.f, ay = 0.f, bx = 0.f, by = 0.f, cx = 0.f, cy = 0.f;
    bool vertical = false;
    float angleVal = amountDeg;
    switch (originType) {
        case 0: ax = adjOffset; ay = 0.5f; bx = 1.f; by = 0.5f; cx = 0.f; cy = 0.5f; break;
        case 1: ax = 1.f - adjOffset; ay = 0.5f; bx = 0.f; by = 0.5f; cx = 1.f; cy = 0.5f; angleVal = -amountDeg; break;
        case 2: ax = 0.5f; ay = 1.f - adjOffset; bx = 0.5f; by = 0.f; cx = 0.5f; cy = 1.f; vertical = true; break;
        default: ax = 0.5f; ay = adjOffset; bx = 0.5f; by = 1.f; cx = 0.5f; cy = 0.f; vertical = true; angleVal = -amountDeg; break;
    }
    float a2bx = bx - ax, a2by = by - ay;
    const float check = a2bx * (uvX - ax) + a2by * (uvY - ay);
    if (check < 0.f) {
        if (oppositeType == 0 || adjOffset <= 0.f) {
            *outX = uvX; *outY = uvY; *inside = true;
            return;
        }
        if (adjOffset < lowLimit && lowLimit != 0.f) angleVal *= (adjOffset / lowLimit);
        a2bx = cx - ax; a2by = cy - ay;
        if (oppositeType == 2) angleVal = -angleVal;
    } else {
        if (adjOffset >= highLimit * 0.99f) {
            const float den = 0.99f - highLimit * 0.99f;
            if (den != 0.f) angleVal *= (1.f - (adjOffset - highLimit * 0.99f) / den);
        }
    }
    const float len = std::sqrt(a2bx * a2bx + a2by * a2by);
    angleVal = angleVal * 0.01745329252f * 0.5f / (len > 0.f ? len : 1.f);
    float rx, ry;
    if (vertical) {
        fxBendCurve((uvX - ax) * aspect, uvY - ay, angleVal, true, &rx, &ry);
        *outX = rx / aspect + ax;
        *outY = ry + ay;
    } else {
        fxBendCurve(uvX - ax, (uvY - ay) / aspect, angleVal, false, &rx, &ry);
        *outX = rx + ax;
        *outY = ry * aspect + ay;
    }
    *inside = *outX >= 0.f && *outX <= 1.f && *outY >= 0.f && *outY <= 1.f;
}

float fxDisplaceMapAmPick(float r, float g, float b, int type) {
    switch (type) {
        case 0: return 0.2126f * r + 0.7152f * g + 0.0722f * b;
        case 1: return r;
        case 2: return g;
        default: return b;
    }
}

float fxDisplaceMapAmShape(float p, bool fromCtr, bool invert) {
    if (fromCtr) p = (p - 0.5f) * 2.f;
    if (invert) p = 1.f - p;
    return p;
}

void fxDisplaceMapAmUv(float snX, float snY, float offX, float offY, float px, float py, float a,
                       float layerAspect, float* outX, float* outY) {
    // AM's displacemap shader: adjOffs = offs / 1000, x scaled by the layer aspect and
    // negated, then weighted by the picked channels and the pixel's own alpha.
    float ax = offX / 1000.f;
    float ay = offY / 1000.f;
    ax *= layerAspect * -1.f;
    ax *= px * a;
    ay *= py * a;
    *outX = snX + ax;
    *outY = snY + ay;
}

void fxDisplaceMapAmEdge(float uvX, float uvY, int edges, float texA, float* outX, float* outY,
                         float* tint, bool* inside) {
    // AM's displacemap edges branches: 0 repeat keeps the raw position and does NOT scale
    // by the pixel's alpha; 1 mirror-folds; 2 zeroes outside; 3 tiles; anything else raw.
    auto fold = [](float v) {
        const float m = v - 2.f * std::floor(v / 2.f);
        return 1.f - std::fabs(m - 1.f);
    };
    auto tile = [](float v) { return v - std::floor(v); };
    *inside = true;
    switch (edges) {
        case 0: *outX = uvX; *outY = uvY; *tint = 1.f; break;
        case 1: *outX = fold(uvX); *outY = fold(uvY); *tint = texA; break;
        case 2:
            *outX = uvX; *outY = uvY; *tint = texA;
            *inside = uvX >= 0.f && uvX <= 1.f && uvY >= 0.f && uvY <= 1.f;
            break;
        case 3: *outX = tile(uvX); *outY = tile(uvY); *tint = texA; break;
        default: *outX = uvX; *outY = uvY; *tint = texA; break;
    }
}

void fxDisplaceMapRadAmUv(float snX, float snY, float lcX, float lcY, float cX, float cY,
                          float offsAngleDeg, float offsRadius, float px, float py, float a,
                          float screenW, float screenH, float* outX, float* outY) {
    // AM's displacemaprad shader, line by line: around the layer centre (shifted by the
    // centre point), aspect-corrected, rotated by the picked angle, pushed out radially.
    const float adjA = offsAngleDeg * px * a;
    const float adjR = offsRadius * py * a;
    const float cenX = cX / 1000.f * -1.f;
    const float cenY = cY / 1000.f;
    float ux = snX - (lcX - cenX);
    float uy = snY - (lcY - cenY);
    const float asp = screenW != 0.f ? screenH / screenW : 1.f;
    uy *= asp;
    const float rad = adjA * 0.0174533f;
    const float cs = std::cos(rad), sn = std::sin(rad);
    float rx = ux * cs + uy * sn;
    float ry = -ux * sn + uy * cs;
    const float diag = std::sqrt(screenW * screenW + screenH * screenH);
    const float len = std::sqrt(rx * rx + ry * ry);
    if (len > 1e-9f && diag > 0.f) {
        const float k = adjR / diag;
        rx += rx / len * k;
        ry += ry / len * k;
    }
    ry /= asp;
    *outX = rx + (lcX - cenX);
    *outY = ry + (lcY - cenY);
}

void fxSmoothBevelAmEncode(float v, float* out0, float* out1) {
    // AM's smoothbevel encodeFloat: enc = fract((1, 255) * v); enc.x -= enc.y / 255.
    float e1 = 255.f * v;
    e1 = e1 - std::floor(e1);
    float e0 = v - std::floor(v);
    e0 -= e1 / 255.f;
    *out0 = e0;
    *out1 = e1;
}

float fxSmoothBevelAmDecode(float e0, float e1) {
    return e0 + e1 / 255.f;
}

void fxSmoothBevelAmLights(float tl, float tr, float bl, float br, float angleSdeg,
                           float angleDdeg, float elevS, float elevD, float spec,
                           float* outLumS, float* outLumD) {
    // AM's smoothbevel shading: v is the mean of the four corner vectors; the lights are
    // (cos a, -sin a, -elevation). pow of a negative dot is undefined in GLSL; clamped to
    // 0 here (a surface facing away from the specular light gets no shine).
    const float vx = (-bl + br - tl + tr) / 4.f;
    const float vy = (-bl - br + tl + tr) / 4.f;
    const float vz = -(bl + br + tl + tr) / 4.f;
    const float vl = std::sqrt(vx * vx + vy * vy + vz * vz);
    if (vl < 1e-9f) { *outLumS = 0.f; *outLumD = 0.f; return; }
    auto light = [&](float angleDeg, float elev, float* lx, float* ly, float* lz) {
        const float a = angleDeg * 0.0174533f;
        const float x = std::cos(a), y = -std::sin(a), z = -elev;
        const float l = std::sqrt(x * x + y * y + z * z);
        *lx = x / l; *ly = y / l; *lz = z / l;
    };
    float lx, ly, lz;
    light(angleSdeg, elevS, &lx, &ly, &lz);
    const float dotS = (vx * lx + vy * ly + vz * lz) / vl;
    light(angleDdeg, elevD, &lx, &ly, &lz);
    const float dotD = (vx * lx + vy * ly + vz * lz) / vl;
    *outLumS = std::pow(std::max(dotS, 0.f), spec);
    *outLumD = dotD;
}

void fxLightGlowAmPick(float r, float g, float b, float a, float threshold, float blend,
                       float colR, float colG, float colB,
                       float* outR, float* outG, float* outB, float* outA) {
    // AM's lightglow pass 0: over the threshold the pixel mixes toward the tint colour
    // (premultiplied by its own alpha); under it, black.
    const float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    if (lum > threshold) {
        *outR = r + (colR * a - r) * blend;
        *outG = g + (colG * a - g) * blend;
        *outB = b + (colB * a - b) * blend;
        *outA = a;
    } else {
        *outR = *outG = *outB = *outA = 0.f;
    }
}

float fxLightGlowAmKernel(float strength, float previewW, float previewH) {
    // AM's lightglow kernel size: strength / 2 * 1.14 / 4 / 3 * |preview size|.
    return strength / 2.f * 1.14f / 4.f / 3.f * std::sqrt(previewW * previewW + previewH * previewH);
}

void fxLightGlowAmComposite(float br, float bg, float bb, float ba,
                            float rr, float rg, float rb, float ra,
                            float intensity, float halo, float blend,
                            float colR, float colG, float colB, float alpha,
                            float* outR, float* outG, float* outB, float* outA) {
    // AM's lightglow pass 2 tail (after the blur): screen the unpremultiplied glow over
    // the base and composite, line by line.
    const float inv = 1.f / std::max(ra, 0.0001f);
    float ar = rr * inv * intensity;
    float ag = rg * inv * intensity;
    float ab = rb * inv * intensity;
    const float h = (1.f - fxSstep(0.f, 0.15f, std::fabs(ra - 0.15f))) * halo;
    const float hr = (ar / 0.15f * halo) + (colR * halo - ar / 0.15f * halo) * blend;
    const float hg = (ag / 0.15f * halo) + (colG * halo - ag / 0.15f * halo) * blend;
    const float hb = (ab / 0.15f * halo) + (colB * halo - ab / 0.15f * halo) * blend;
    ar += hr * h;
    ag += hg * h;
    ab += hb * h;
    const float binv = 1.f / std::max(ba, 0.0001f);
    const float sr = 1.f - (1.f - ar) * (1.f - br * binv);
    const float sg = 1.f - (1.f - ag) * (1.f - bg * binv);
    const float sb = 1.f - (1.f - ab) * (1.f - bb * binv);
    const float bA = std::min(ba, ra) * alpha;
    *outR = br * (1.f - bA) + sr * bA;
    *outG = bg * (1.f - bA) + sg * bA;
    *outB = bb * (1.f - bA) + sb * bA;
    *outA = ba * (1.f - bA) + bA;
}

float fxInnerGlowAmStride(float radius2) {
    // AM's inner-glow stride: q = max(radius2^1.24 / radius2, radius2 / 40).
    if (radius2 <= 0.f) return 1.f;
    const float a = std::pow(radius2, 1.24f) / radius2;
    const float b = radius2 / 40.f;
    return std::max(a, b);
}

float fxInnerGlowAmP(float radius2, float hardness, float nearestInside) {
    // AM: smoothstep(radius2 + 0.51, hardness * radius2 - 0.51, nearestInside) — REVERSED
    // edges (fxSstep's formula): 1 at the edge (distance 0), 0 a radius inside.
    return fxSstep(radius2 + 0.51f, hardness * radius2 - 0.51f, nearestInside);
}

void fxInnerGlowAmMap(float tr, float tg, float tb, float ta,
                      float cr, float cg, float cb, int blendMode, float alpha, float p,
                      float* outR, float* outG, float* outB, float* outA) {
    // AM's inner-glow pass 1 tail: blend the colour against the pixel's STRAIGHT colour,
    // re-premultiply by the pixel alpha, then mix(tex, result, alpha * p). AM divides by
    // the alpha unguarded; the guard only changes a=0 pixels, whose result is 0 either way.
    const float inv = 1.f / std::max(ta, 0.0001f);
    float rr, rg, rb;
    if (blendMode == 1) {
        rr = tr * inv * cr; rg = tg * inv * cg; rb = tb * inv * cb;
    } else if (blendMode == 2) {
        rr = 1.f - (1.f - tr * inv) * (1.f - cr);
        rg = 1.f - (1.f - tg * inv) * (1.f - cg);
        rb = 1.f - (1.f - tb * inv) * (1.f - cb);
    } else {
        rr = cr; rg = cg; rb = cb;
    }
    const float m = alpha * p;
    *outR = tr + (rr * ta - tr) * m;
    *outG = tg + (rg * ta - tg) * m;
    *outB = tb + (rb * ta - tb) * m;
    *outA = ta;   // the result's alpha is the pixel's own (vec4(rgb, 1) * tex.a)
}

// ---- Effects tier 4 (v175) ----
// Line-by-line ports of the defs staged in hunt-staging/defs-batch7 (recovered from two
// dumps — the AM APK assets and DGameXO/openmotion — shader- and structure-identical
// between them; the AM-dump copies are followed). AM texture space throughout: layer
// norm, origin top-left, y down — the samplers read AM space directly, like the
// extrude twin's.

void fxThresholdAmMap(float pr, float pg, float pb, float pa, float threshold, float feather,
                      bool invert, int blendMode, float* outR, float* outG, float* outB, float* outA) {
    // AM's threshold: the luma of the PREMULTIPLIED rgb decides a smoothstepped mask.
    const float lum = 0.2126f * pr + 0.7152f * pg + 0.0722f * pb;
    const float f = std::max(std::min(feather / 4.f, std::fabs(0.5f - threshold)), 0.00196f);
    const float t = (threshold - 0.5f) * (1.003f + 2.f * f) + 0.5f;
    float p = fxSstep(t - f, t + f, lum);
    if (invert) p = 1.f - p;
    if (blendMode == 1) {
        *outR = pr * p; *outG = pg * p; *outB = pb * p;
    } else if (blendMode == 2) {
        *outR = 1.f - (1.f - pr) * (1.f - p);
        *outG = 1.f - (1.f - pg) * (1.f - p);
        *outB = 1.f - (1.f - pb) * (1.f - p);
    } else {
        *outR = p; *outG = p; *outB = p;
    }
    *outA = pa;
}

float fxSpectralmapAmMapChannel(float p, float f) {
    const float t = (p + 10.f) - std::floor(p + 10.f);   // mod(p + 10, 1)
    return std::min(1.f, std::max(0.f, std::fabs(t - 0.5f) * 4.f - f));
}

void fxSpectralmapAmMap(float pr, float pg, float pb, float pa, float phase, float repeat,
                        float alpha, float squeeze, float saturation, bool reverse,
                        bool scaleBrightness, const float cols[9],
                        float* outR, float* outG, float* outB, float* outA) {
    // AM's spectralmap: a rainbow ramp over the luma, wrapped by mapChannel; the def's
    // rgb2yuv / yuv2rgb matrices are the pair fxAmToYuv / fxAmToRgb already carry.
    const float lum0 = 0.2126f * pr + 0.7152f * pg + 0.0722f * pb;
    const float bscale = scaleBrightness ? lum0 : 1.f;
    float lum = reverse ? 1.f - lum0 : lum0;
    lum = lum * repeat + phase;
    const float f = 0.333f + (0.1f - 0.333f) * squeeze;   // mix(0.333, 0.1, squeeze)
    auto mapChannel = [f](float p) { return fxSpectralmapAmMapChannel(p, f); };
    float rr = mapChannel(lum) * cols[0] + mapChannel(lum - 1.f / 3.f) * cols[3] +
               mapChannel(lum - 2.f / 3.f) * cols[6];
    float rg = mapChannel(lum) * cols[1] + mapChannel(lum - 1.f / 3.f) * cols[4] +
               mapChannel(lum - 2.f / 3.f) * cols[7];
    float rb = mapChannel(lum) * cols[2] + mapChannel(lum - 1.f / 3.f) * cols[5] +
               mapChannel(lum - 2.f / 3.f) * cols[8];
    float y, u, v;
    fxAmToYuv(rr, rg, rb, &y, &u, &v);
    u *= saturation + 1.f;
    v *= saturation + 1.f;
    fxAmToRgb(y, u, v, &rr, &rg, &rb);
    // result * vec4(bscale x3, 1) * texColor.a, mixed over the pixel by alpha.
    *outR = pr + (rr * bscale * pa - pr) * alpha;
    *outG = pg + (rg * bscale * pa - pg) * alpha;
    *outB = pb + (rb * bscale * pa - pb) * alpha;
    *outA = pa;
}

void fxChannelmapAmMap(float pr, float pg, float pb, float pa, int srcR, int srcG, int srcB,
                       int srcA, float* outR, float* outG, float* outB, float* outA) {
    // AM's channelmap: the luminance is read off the PREMULTIPLIED rgb first (the
    // def computes it before the divide), then un-premultiply (only when a > 0),
    // re-pick, re-premultiply by the NEW alpha. The def's maskFactor is 1 over the
    // layer's own picture.
    const float lum = 0.2126f * pr + 0.7152f * pg + 0.0722f * pb;
    float sr = pr, sg = pg, sb = pb;
    if (pa > 0.f) { sr /= pa; sg /= pa; sb /= pa; }
    auto pick = [sr, sg, sb, pa, lum](int src) {
        switch (src) {
            case 0: return sr; case 1: return sg; case 2: return sb;
            case 3: return pa; case 4: return lum; case 5: return 0.f;
            default: return 1.f;
        }
    };
    const float na = pick(srcA);
    *outR = pick(srcR) * na;
    *outG = pick(srcG) * na;
    *outB = pick(srcB) * na;
    *outA = na;
}

void fxHalftoneLinesUv(float uvX, float uvY, float amount, float angleDeg, float phase,
                       float aspect, float* outX, float* outY, float* absDist) {
    // AM's halftonelines: dots' fold on the y axis only, the phase raw in the mod.
    if (amount < 1e-6f) { *outX = uvX; *outY = uvY; *absDist = 0.f; return; }
    const float rad = angleDeg * 0.0174533f;
    const float cs = std::cos(rad), sn = std::sin(rad);
    const float stx = uvX - 0.5f, sty = (uvY - 0.5f) / std::max(aspect, 1e-6f);
    const float rx = stx * cs + sty * sn, ry = -stx * sn + sty * cs;   // st * rot
    float dY = (ry * amount + phase) - std::floor(ry * amount + phase);
    dY = dY < 0.5f ? -dY : 1.f - dY;
    *absDist = std::fabs(dY);
    dY /= amount;
    const float ny = ry + dY;
    const float c2 = std::cos(-rad), s2 = std::sin(-rad);
    *outX = rx * c2 + ny * s2 + 0.5f;
    *outY = (-rx * s2 + ny * c2) * std::max(aspect, 1e-6f) + 0.5f;
}

void fxHalftoneLinesMap(float sampR, float sampG, float sampB, float sampA, float absDist,
                        float strength, const float c1[3], const float c2[3], float texA,
                        float* outR, float* outG, float* outB, float* outA) {
    const float lum = 0.2126f * sampR + 0.7152f * sampG + 0.0722f * sampB;
    const float p = fxSstep(0.4f, 0.6f, lum + (1.f - sampA) + absDist + strength - 0.5f);
    *outR = (c1[0] + (c2[0] - c1[0]) * p) * texA;
    *outG = (c1[1] + (c2[1] - c1[1]) * p) * texA;
    *outB = (c1[2] + (c2[2] - c1[2]) * p) * texA;
    *outA = texA;
}

float fxTurbulence2AmField(float uvX, float uvY, float intensity, float scale, float evolution,
                           float seed, int octaves, float offX, float offY, float parX,
                           float parY, float stretchX, float stretchY, float aspect) {
    // AM's turbulence2 main: the turbulence() sum at the def's st (offs / parallax /
    // scale with the stretch components SWAPPED, verbatim), scaled by intensity; the
    // caller clamps p + bias before mixing the colours, as the def does.
    float stx = uvX * aspect + offX * -1.f / 500.f;
    float sty = uvY + offY * 1.f / 500.f;
    const float svx = scale * stretchY, svy = scale * stretchX;
    const float p = fxTurbulence((stx - 0.5f) / svx + 0.5f, (sty - 0.5f) / svy + 0.5f, octaves,
                                 parX * -1.f / 100.f, parY * 1.f / 100.f,
                                 evolution + 7391.935f * seed) * intensity;
    return p;
}

void fxTurbulence2AmMap(float tr, float tg, float tb, float ta, float colR, float colG,
                        float colB, int blendMode, float alpha,
                        float* outR, float* outG, float* outB, float* outA) {
    // The field colour mixed per blend mode, as vec4(rgb, 1) * tex.a, mixed by alpha.
    // (The bias clamp rides in the field twin's caller — see the style program, which
    // clamps p before mixing the colours; here col* is already the mixed colour.)
    float rr, rg, rb;
    if (blendMode == 1) {
        const float inv = 1.f / std::max(ta, 0.00001f);   // AM divides unguarded
        rr = tr * inv * colR; rg = tg * inv * colG; rb = tb * inv * colB;
    } else if (blendMode == 2) {
        const float inv = 1.f / std::max(ta, 0.00001f);
        rr = 1.f - (1.f - tr * inv) * (1.f - colR);
        rg = 1.f - (1.f - tg * inv) * (1.f - colG);
        rb = 1.f - (1.f - tb * inv) * (1.f - colB);
    } else {
        rr = colR; rg = colG; rb = colB;
    }
    *outR = tr + (rr * ta - tr) * alpha;
    *outG = tg + (rg * ta - tg) * alpha;
    *outB = tb + (rb * ta - tb) * alpha;
    *outA = ta;
}

float fxChecker2AmP(float uvX, float uvY, float amount, float angleDeg, float posX, float posY,
                    float stretchX, float stretchY, float aspect) {
    // AM's checker2 (layer mode): centre, aspect, position, rotate by the def's matrix
    // (mat2(sin, -cos, cos, sin) as columns), then the floored parity with the stretch
    // components swapped between the axes, verbatim.
    float stx = (uvX - 0.5f) * aspect - posX / 1200.f;
    float sty = (uvY - 0.5f) - posY / 1200.f;
    const float a = angleDeg * 0.0174533f;
    const float s = std::sin(a), c = std::cos(a);
    const float rx = s * stx + c * sty, ry = -c * stx + s * sty;
    const float px = std::floor(rx * amount / stretchY);
    const float py = std::floor(ry * amount / stretchX);
    const float m = (px + py) - 2.f * std::floor((px + py) / 2.f);   // mod(px + py, 2)
    return m <= 0.5f ? 1.f : 0.f;   // step(mod, 0.5)
}

void fxChecker2AmMap(float tr, float tg, float tb, float ta, float colR, float colG,
                     float colB, float p, bool punchout,
                     float* outR, float* outG, float* outB, float* outA) {
    if (punchout) {
        *outR = tr * (1.f - p); *outG = tg * (1.f - p); *outB = tb * (1.f - p);
        *outA = ta * (1.f - p);
        return;
    }
    // c = vec4(color * p, p); alpha = c.a * tex.a; out = c * alpha + tex * (1 - alpha).
    const float alpha = p * ta;
    *outR = colR * p * alpha + tr * (1.f - alpha);
    *outG = colG * p * alpha + tg * (1.f - alpha);
    *outB = colB * p * alpha + tb * (1.f - alpha);
    *outA = p * alpha + ta * (1.f - alpha);
}

float fxMosaicAmSnap(float coord, float adjPhase, float size, float layerDim) {
    // The def's centre-relative snap: coord - mod(coord + adjPhase / dim, size / dim).
    const float tile = size / std::max(layerDim, 1.f);
    const float c = coord - 0.5f;
    const float m0 = c + adjPhase / std::max(layerDim, 1.f);
    const float m = m0 - tile * std::floor(m0 / tile);
    return c - m + 0.5f;
}

int fxMosaicAmSamples(float tilePx, int pass) {
    if (pass == 0) return (int)std::min(100.01f, std::max(1.01f, tilePx / 2.f));
    return (int)std::min(100.01f, std::max(1.01f, tilePx));
}

void fxMosaicAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                     const FxExtrudeSampler& s, float out[4]) {
    // AM's mosaic, two passes: x-averages of the INPUT at the x-snapped coordinate,
    // then y-averages of the H-PASS at the y-snapped coordinate — except the second
    // pass's base tap, which reads the INPUT at the y-snapped position (the def's
    // inputImg / hblur split, verbatim).
    const float size = op.p[0];
    auto hAt = [&](float x, float y, float o[4]) {
        const float sx = fxMosaicAmSnap(x, -op.p[1], size, picW);
        const int n = fxMosaicAmSamples(size, 0);
        s.sample(s.ctx, 0, sx, y, o);
        for (int i = 1; i < n; ++i) {
            const float off = (size / picW) * ((float)i / (float)(n - 1) - 0.5f);
            float t[4];
            s.sample(s.ctx, 0, sx - off, y, t);
            for (int k = 0; k < 4; ++k) o[k] += t[k];
        }
        for (int k = 0; k < 4; ++k) o[k] /= (float)n;
    };
    const float sy = fxMosaicAmSnap(ay, op.p[2], size, picH);
    const int n = fxMosaicAmSamples(size, 1);
    s.sample(s.ctx, 0, ax, sy, out);
    for (int i = 1; i < n; ++i) {
        const float off = (size / picH) * ((float)i / (float)(n - 1) - 0.5f);
        float t[4];
        hAt(ax, sy - off, t);
        for (int k = 0; k < 4; ++k) out[k] += t[k];
    }
    for (int k = 0; k < 4; ++k) out[k] /= (float)n;
}

void fxGlowAmMap(float oR, float oG, float oB, float oA, float compR, float compG, float compB,
                 float nearestIn, float nearestOut, float radius2, float hardness, float alpha,
                 float colR, float colG, float colB, float colA, int blendMode,
                 float* outR, float* outG, float* outB, float* outA) {
    // AM's glow pass 1 tail: p runs from deep-inside (1 + pIn) to far-outside
    // (0.5 - pOut); outColor fades to 0 as 1 - p crosses the hardness smoothstep, and
    // the composite adds it behind the original. At p <= 0.0001 (AM's discard) the
    // smoothstep is 1 and outColor is 0, so the identity falls out of the maths.
    const float pIn = nearestIn / radius2, pOut = nearestOut / radius2;
    const float p = 0.5f + pIn - pOut;
    float br, bg, bb;
    if (blendMode == 1) {
        br = compR * colR; bg = compG * colG; bb = compB * colB;
    } else if (blendMode == 2) {
        br = 1.f - (1.f - compR) * (1.f - colR);
        bg = 1.f - (1.f - compG) * (1.f - colG);
        bb = 1.f - (1.f - compB) * (1.f - colB);
    } else {
        br = colR; bg = colG; bb = colB;
    }
    const float e0 = (1.f - 1.5f / radius2) * hardness;
    const float oc = 1.f - fxSstep(e0, 1.f, 1.f - p);
    const float ocr = br * alpha * oc, ocg = bg * alpha * oc, ocb = bb * alpha * oc;
    const float oca = alpha * oc * (blendMode == 0 ? colA : 1.f);
    const float w = 1.f - oA;
    *outR = oR + ocr * w;
    *outG = oG + ocg * w;
    *outB = oB + ocb * w;
    *outA = oA + oca * w;
}

int fxBoxSamples(float speed, bool symmetric) {
    if (symmetric) return (int)std::min(150.01f, std::max(2.01f, speed));
    return (int)std::min(100.01f, std::max(1.01f, speed));
}

namespace {
// One boxblur2 pass of AM's defs (inner blur's host): the base tap plus n-1 taps at
// velocity * (i / (n - 1) - 0.5), all divided by n. F is the source picture function.
template <typename F>
void boxBlur2Pass(const F& src, float u, float v, float velX, float velY, float picW, float picH,
                  float o[4]) {
    const float speed = std::sqrt((velX * picW) * (velX * picW) + (velY * picH) * (velY * picH));
    const int n = fxBoxSamples(speed, false);
    src(u, v, o);
    for (int i = 1; i < n; ++i) {
        const float f = (float)i / (float)(n - 1) - 0.5f;
        float t[4];
        src(u - velX * f, v - velY * f, t);
        for (int k = 0; k < 4; ++k) o[k] += t[k];
    }
    for (int k = 0; k < 4; ++k) o[k] /= (float)n;
}
// One symmetric box pass (boxblur3 / feather): the base tap plus pairs at
// +/- strengthNorm * i / (n - 1), all divided by 2n - 1.
template <typename F>
void boxSymPass(const F& src, float u, float v, bool horizontal, float strengthNorm,
                float resDim, float o[4]) {
    const int n = fxBoxSamples(strengthNorm * resDim, true);
    src(u, v, o);
    for (int i = 1; i < n; ++i) {
        const float off = strengthNorm * (float)i / (float)(n - 1);
        float t[4];
        if (horizontal) { src(u + off, v, t); for (int k = 0; k < 4; ++k) o[k] += t[k]; src(u - off, v, t); }
        else { src(u, v + off, t); for (int k = 0; k < 4; ++k) o[k] += t[k]; src(u, v - off, t); }
        for (int k = 0; k < 4; ++k) o[k] += t[k];
    }
    const float d = (float)(2 * n - 1);
    for (int k = 0; k < 4; ++k) o[k] /= d;
}
}  // namespace

void fxInnerBlurAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                        const FxExtrudeSampler& s, float out[4]) {
    // AM's innerblur: iter chains of boxblur2 (pass strength = strength / 4 / iter)
    // composited back over the chain input as un-premultiplied colour at the input's
    // alpha. Each chain's output feeds the next (the def's <iterations> element).
    const float strength = op.p[0];
    const int iter = std::min(6, std::max(1, (int)op.p[1]));
    const float per = strength / 4.f / (float)iter;
    using Fn = std::function<void(float, float, float*)>;
    Fn cur = [&s](float u, float v, float* o) { s.sample(s.ctx, 0, u, v, o); };
    for (int it = 0; it < iter; ++it) {
        Fn base = cur;
        Fn blur = [base, per, picW, picH](float u, float v, float* o) {
            float tmp[4];
            auto mid = [&base, per, picW, picH](float uu, float vv, float* oo) {
                boxBlur2Pass(base, uu, vv, per, 0.f, picW, picH, oo);
            };
            boxBlur2Pass(mid, u, v, 0.f, -per, picW, picH, tmp);
            for (int k = 0; k < 4; ++k) o[k] = tmp[k];
        };
        cur = [base, blur](float u, float v, float* o) {
            float b[4], t[4];
            blur(u, v, b);
            base(u, v, t);
            const float inv = 1.f / std::max(b[3], 0.0001f);   // AM guards only ~0
            o[0] = b[0] * inv * t[3];
            o[1] = b[1] * inv * t[3];
            o[2] = b[2] * inv * t[3];
            o[3] = t[3];
        };
    }
    cur(ax, ay, out);
}

void fxBoxBlur3AmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                       const FxExtrudeSampler& s, float out[4]) {
    // AM's boxblur3: the half-res symmetric averages, x then y (strength / 10, the
    // speeds at the downsampled resolution). The copy pass reads the layer directly.
    const float strength = op.p[0];
    if (strength <= 0.0001f) { s.sample(s.ctx, 0, ax, ay, out); return; }
    const float sn = strength / 10.f;
    auto ds = [&s](float u, float v, float* o) { s.sample(s.ctx, 0, u, v, o); };
    // The y pass runs over the x pass's picture; evaluate it as a function.
    auto hAt = [&ds, sn, picW](float u, float v, float* o) { boxSymPass(ds, u, v, true, sn, picW / 2.f, o); };
    boxSymPass(hAt, ax, ay, false, sn, picH / 2.f, out);
}

void fxFeatherAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                      const FxExtrudeSampler& s, float out[4]) {
    // AM's feather: iter chains of the quarter-res symmetric averages (strength /
    // 4 / iter); the output is the chain input times the blurred alpha squared.
    const float strength = op.p[0];
    if (strength <= 0.005f) { s.sample(s.ctx, 0, ax, ay, out); return; }
    const int iter = std::min(5, std::max(1, (int)op.p[1]));
    const float sn = strength / 4.f / (float)iter;
    using Fn = std::function<void(float, float, float*)>;
    Fn cur = [&s](float u, float v, float* o) { s.sample(s.ctx, 0, u, v, o); };
    for (int it = 0; it < iter; ++it) {
        Fn base = cur;
        cur = [base, sn, picW, picH](float u, float v, float* o) {
            auto hAt = [&base, sn, picW](float uu, float vv, float* oo) {
                boxSymPass(base, uu, vv, true, sn, picW / 4.f, oo);
            };
            float r[4], t[4];
            boxSymPass(hAt, u, v, false, sn, picH / 4.f, r);
            base(u, v, t);
            const float w = r[3] * r[3];
            for (int k = 0; k < 4; ++k) o[k] = t[k] * w;
        };
    }
    cur(ax, ay, out);
}

void fxElectricNoise(float x, float y, float out[2]) {
    // AM's electricedges noise (McGuire): value noise with the SHARP interpolant.
    auto hash = [](float px, float py, float o[2]) {
        auto fr = [](float v) { return v - std::floor(v); };
        const float d1 = px * 127.1f + py * 311.7f;
        const float d2 = px * 269.5f + py * 183.3f;
        o[0] = fr(std::sin(d1) * 43758.5453f);
        o[1] = fr(std::sin(d2) * 43758.5453f);
    };
    const float ix = std::floor(x), iy = std::floor(y);
    const float fx = x - ix, fy = y - iy;
    float a[2], b[2], c[2], d[2];
    hash(ix, iy, a); hash(ix + 1.f, iy, b); hash(ix, iy + 1.f, c); hash(ix + 1.f, iy + 1.f, d);
    for (int k = 0; k < 2; ++k)
        out[k] = (a[k] + (b[k] - a[k]) * fx) + (c[k] - a[k]) * fy * (1.f - fx) +
                 (d[k] - b[k]) * fx * fy;
}

void fxElectricEdgesAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                            const FxExtrudeSampler& s, float out[4]) {
    // AM's electricedges main pass, line by line (its `comp` input is dead in the def).
    const float distance = op.p[0], thickness = op.p[1], complexity = op.p[2], disort = op.p[3];
    const float alpha = op.p[6], glowAlpha = op.q[0], seed = op.q[1], evolution = op.q[2];
    const float fill = op.q[3], maskStrength = op.q[4], maskEvolution = op.q[5], maskScale = op.q[6];
    const float maskSeed = op.r[0];
    float c1[3], c2[3], maskCol[4];
    auto unpackColor = [](float packed, float rgb[3]) {
        const float pk = std::floor(packed + 0.5f);
        rgb[0] = std::fmod(std::floor(pk / 65536.f), 256.f) / 255.f;
        rgb[1] = std::fmod(std::floor(pk / 256.f), 256.f) / 255.f;
        rgb[2] = std::fmod(pk, 256.f) / 255.f;
    };
    unpackColor(op.p[4], c1);
    unpackColor(op.p[5], c2);
    unpackColor(op.r[1], maskCol);
    maskCol[3] = 1.f;   // the corpus's mask colours are opaque (the packed slot drops alpha)
    const float aspect = picW / std::max(picH, 1.f);
    float stx = (ax - 0.5f) * aspect, sty = ay - 0.5f;
    const float esx = 3.f * evolution + 62.5587f * seed, esy = 3.f * evolution + 137.1935f * seed;
    auto layer = [&](float ang, float mul, float o[2]) {
        const float cs = std::cos(ang), sn = std::sin(ang);
        // st * rot2d(ang) (AM's row-vector product): (c x + s y, -s x + c y)
        const float rx = cs * stx + sn * sty, ry = -sn * stx + cs * sty;
        fxElectricNoise(esx + rx * mul * complexity, esy + ry * mul * complexity, o);
    };
    float l1[2], l2[2], l3[2], l4[2];
    layer(0.1f, 2.f, l1); layer(0.3f, 4.f, l2); layer(1.42f, 8.f, l3); layer(2.534f, 16.f, l4);
    const float offX = std::fabs(std::fabs(l1[0] - l2[0]) - std::fabs(l3[0] - l4[0]));
    const float offY = std::fabs(std::fabs(l1[1] - l2[1]) - std::fabs(l3[1] - l4[1]));
    const float offLen = std::sqrt(offX * offX + offY * offY);
    float tex[4], mid[4], up[4], left[4];
    s.sample(s.ctx, 0, ax, ay, tex);
    s.sample(s.ctx, 1, ax, ay, mid);
    s.sample(s.ctx, 1, ax, ay - 4.f / std::max(picH, 1.f), up);
    s.sample(s.ctx, 1, ax - 4.f / std::max(picW, 1.f), ay, left);
    const float dx = mid[3] - left[3], dy = mid[3] - up[3];
    const float dl = std::sqrt(dx * dx + dy * dy);
    const float nx = dl > 1e-6f ? dx / dl : 0.f, ny = dl > 1e-6f ? dy / dl : 0.f;
    float blurred[4];
    s.sample(s.ctx, 1, ax + nx * offLen * (disort * 0.055f), ay + ny * offLen * (disort * 0.055f),
             blurred);
    const float turbBase = fxTurbulence((stx - 0.5f) / maskScale + 0.5f,
                                        (sty - 0.5f) / maskScale + 0.5f, 3, 0.f, 0.f,
                                        maskEvolution + 91.935f * maskSeed);
    const float turb = fxSstep(maskStrength - 0.22f, maskStrength - 0.12f, turbBase);
    const float suppress = fxSstep(maskStrength - 0.12f, maskStrength - 0.02f, turbBase);
    const float ttint = fxSstep(maskStrength - 0.02f, maskStrength - 0.001f, turbBase);
    const float p = blurred[3];
    const float dist = 1.f - (distance * 0.4f + 0.5f);
    const float eBase = 1.f - thickness * 0.9f * suppress;
    const float win = fxSstep(0.01f, 0.12f, p) * fxSstep(1.f, 0.999f, p);
    float pp = std::pow(1.f - std::fabs(p - dist), eBase * 30.f) * win;
    float qq = std::pow(1.f - std::fabs(p - dist), eBase * 25.f) * win;
    if (op.r[2] > 0.5f) {
        // Contours: st2 is st itself (the aspect-scaled centred position), y nudged
        // by the blurred alpha, rotated by the offs-weighted angle — as the def writes.
        float s2x = stx, s2y = sty;
        s2y += mid[3] * 0.1f;
        const float ca = offLen * disort * 1.1f * 0.2f + op.r[5] * 0.0174533f;
        const float cs = std::cos(ca), sn = std::sin(ca);
        const float rx = cs * s2x + sn * s2y, ry = -sn * s2x + cs * s2y;
        s2x = rx; s2y = ry;
        s2y += mid[3] * 0.1f;
        float lines = std::sin((s2y + op.r[4] + offY * disort * 0.015f) * op.r[3]);
        lines = std::min(1.f, std::max(0.f, lines)) * fxSstep(0.1f, 0.2f, mid[3]);
        pp += std::pow(lines, eBase * 30.f);
        qq += std::pow(lines, eBase * 25.f);
    }
    float c[4];
    c[0] = std::pow(pp, c1[0] > 0.f ? 1.f / c1[0] : 1e30f) * alpha;
    c[1] = std::pow(pp, c1[1] > 0.f ? 1.f / c1[1] : 1e30f) * alpha;
    c[2] = std::pow(pp, c1[2] > 0.f ? 1.f / c1[2] : 1e30f) * alpha;
    c[3] = pp * alpha;
    float g[4];
    g[0] = std::pow(qq, c2[0] > 0.f ? 1.f / c2[0] : 1e30f) * glowAlpha;
    g[1] = std::pow(qq, c2[1] > 0.f ? 1.f / c2[1] : 1e30f) * glowAlpha;
    g[2] = std::pow(qq, c2[2] > 0.f ? 1.f / c2[2] : 1e30f) * glowAlpha;
    g[3] = qq * glowAlpha;
    for (int k = 0; k < 4; ++k) c[k] = std::min(1.f, std::max(0.f, c[k] + g[k]));
    for (int k = 0; k < 4; ++k) c[k] *= (maskCol[k] * (1.f - ttint) + ttint) * turb;
    for (int k = 0; k < 4; ++k) out[k] = tex[k] * fill * (1.f - c[3]) + c[k];
}

void fxSmoothEdgesAmPixel(const FxOp& op, float ax, float ay, float picW, float picH,
                          const FxExtrudeSampler& s, float out[4]) {
    // AM's smoothedges: the directional streak composites (premultiplied over, the
    // accumulation direction flipping with the tap's side), x/y passes at acPass 1
    // reading the input, at acPass 2 reading HSTREAK (the def's y-pass quirk kept),
    // then the final alpha cut against the gaussian-blurred copy.
    const float strength = op.p[0];
    using Fn = std::function<void(float, float, float*)>;
    Fn input = [&s](float u, float v, float* o) { s.sample(s.ctx, 0, u, v, o); };
    auto streak = [](const Fn& src, bool horizontal, float dist, float picDim,
                     float u, float v, float* o) {
        const int n = (int)std::min(100.01f, std::max(1.01f, dist * picDim));
        float c[4] = {0.f, 0.f, 0.f, 0.f};
        for (int i = 1; i < n; ++i) {
            const float off = dist * ((float)i / (float)(n - 1) - 0.5f);
            float tc[4];
            if (horizontal) src(u + off, v, tc); else src(u, v + off, tc);
            if (off < 0.f) { for (int k = 0; k < 4; ++k) c[k] = c[k] * (1.f - tc[3]) + tc[k]; }
            else { const float w = 1.f - c[3]; for (int k = 0; k < 4; ++k) c[k] = tc[k] * w + c[k]; }
        }
        for (int k = 0; k < 4; ++k) o[k] = c[k];
    };
    auto composite = [](const float a[4], const float b[4], float o[4]) {
        for (int k = 0; k < 4; ++k) o[k] = a[k] * (1.f - b[3]) + b[k];
    };
    const float dist = strength * 0.05f;
    Fn hpass = [&](float u, float v, float* o) {
        float xs[4], ys[4], t[4], base[4];
        streak(input, true, dist, picW, u, v, xs);
        streak(input, false, dist, picH, u, v, ys);
        composite(xs, ys, t);
        input(u, v, base);
        composite(t, base, o);
    };
    Fn vpass = [&](float u, float v, float* o) {
        float xs[4], ys[4], t[4], base[4];
        streak(hpass, true, dist, picW, u, v, xs);
        streak(hpass, false, dist, picH, u, v, ys);
        composite(xs, ys, t);
        input(u, v, base);
        composite(t, base, o);
    };
    float base[4];
    if (op.p[1] > 0.5f) input(ax, ay, base); else vpass(ax, ay, base);
    float bl[4];
    s.sample(s.ctx, 1, ax, ay, bl);
    const float dd = 0.05f / ((1.f + strength) * (1.f + strength));
    const float w = fxSstep(0.5f - dd, 0.5f + dd, bl[3]);
    for (int k = 0; k < 4; ++k) out[k] = base[k] * w;
}

// ---- Extrude / Extrude Flat (v173) ----
// A line-by-line port of AM's s3d-extrude fragment shader (the def staged in
// hunt-staging/defs-batch6, byte-identical in both recovered dumps). The maths runs in
// AM's own space: layer-norm coordinates (origin top-left, y down), the object centred
// at position/100, the camera ray from (0, 1, -1) through the frame point. The def's
// host inputs map as: acLayerSize = the layer picture's pixels, acLayerNorm = (ax, ay),
// orient = identity (the only orientation ever serialised), and acLayerToScreen (l2s)
// = the identity (the pass runs on the layer's own picture), so texture fetches are
// direct layer-norm samples. Extrude Flat runs the same march with the Flat shading
// branch and no light uniforms (its own differences are only the param schema).

void fxExtrudeRotateMat(float rxDeg, float ryDeg, float rzDeg, float m[9]) {
    const double d = 0.017453292519943295;  // the def's 0.0174533, at double precision
    const double rx = rxDeg * d, ry = ryDeg * d, rz = rzDeg * d;
    const double cx = std::cos(rx), sx = std::sin(rx);
    const double cy = std::cos(ry), sy = std::sin(ry);
    const double cz = std::cos(rz), sz = std::sin(rz);
    // The def's rotateAndOrient = rz * rx * ry (standard matrices — its axis-angle
    // fill, read as GLSL columns, IS the standard Rodrigues matrix), applied to ROW
    // vectors: p' = p * (Rz*Rx*Ry). The column-vector equivalent is the transpose,
    // (Rz*Rx*Ry)^T — that is what m holds (row-major), so world = m * local and the
    // def's local = m * (world - centre) as well (both of the def's uses multiply by
    // the same rotateAndOrient). Rx*Ry first, then Rz, then the transpose.
    const double a[9] = {cy, 0, sy, sx * sy, cx, -sx * cy, -cx * sy, sx, cx * cy};  // Rx*Ry, row-major
    const double b[9] = {cz, -sz, 0, sz, cz, 0, 0, 0, 1};                          // Rz
    double p[9];
    for (int row = 0; row < 3; ++row)
        for (int col = 0; col < 3; ++col) {
            double v = 0;
            for (int k = 0; k < 3; ++k) v += b[row * 3 + k] * a[k * 3 + col];
            p[row * 3 + col] = v;
        }
    for (int row = 0; row < 3; ++row)
        for (int col = 0; col < 3; ++col) m[row * 3 + col] = (float)p[col * 3 + row];
}

namespace {

struct ExtrudeCtx {
    float mat[9];      // R (column-vector convention)
    float center[3];   // OBJECT_CENTER = position / 100
    float width, height, depth;
    float strength;
    int iter;
    float scale;
    // shading
    int shadingType;
    float interior[3], interiorBlend;
    float fade[3], fadeBlend;
    float lightPos[3];  // (lightDir.x/2, -lightDir.y/2, -lightDepth*2), the def's formula
    float lightColor[3];
    float lightIntensity, shine, spec, ambient;
    bool flat;
    const FxExtrudeSampler* sampler;
};

inline float clampf(float v, float lo, float hi) { return std::min(hi, std::max(lo, v)); }
inline float saturatef(float v) { return clampf(v, 0.f, 1.f); }
// The def's smoothstep calls include reversed edges (smoothstep(30, 9, d)); GLSL
// evaluates the formula, so the twin does too.
inline float sstep(float e0, float e1, float x) {
    const float t = saturatef((x - e0) / (e1 - e0));
    return t * t * (3.f - 2.f * t);
}
inline void sampleTex(const ExtrudeCtx& c, int tex, float u, float v, float out[4]) {
    if (c.sampler && c.sampler->sample) c.sampler->sample(c.sampler->ctx, tex, u, v, out);
    else out[0] = out[1] = out[2] = out[3] = 0.f;
}

// sdExtrude of the def: (safe step, depth fraction) at a LOCAL point (already centred
// and rotated). r = (width, height, depth); the box half extents are r * (2, 2, 1).
inline void extrudeSd(const ExtrudeCtx& c, const float p[3], float* outStep, float* outZ) {
    const float st[2] = {(p[0] / c.width + 1.f) / 2.f, (p[1] / c.height + 1.f) / 2.f};
    float texC[4], blurC[4];
    sampleTex(c, 0, st[0], st[1], texC);
    if (c.flat) { blurC[0] = texC[0]; blurC[1] = texC[1]; blurC[2] = texC[2]; blurC[3] = texC[3]; }
    else sampleTex(c, 1, st[0], st[1], blurC);
    const float t = std::max(saturatef(blurC[3] * 2.f), texC[3]);
    const float z = (p[2] / c.depth + 1.f) / 2.f;
    const float maxdim = std::sqrt(c.width * c.width + c.height * c.height + c.depth * c.depth);
    const float td = (1.f - t) * (maxdim * 2.f * (1.f + c.strength)) / (float)c.iter;
    // sdBox(p, r * vec3(2, 2, 1))
    const float q[3] = {std::fabs(p[0]) - c.width * 2.f, std::fabs(p[1]) - c.height * 2.f,
                        std::fabs(p[2]) - c.depth};
    const float ax = std::max(q[0], 0.f), ay = std::max(q[1], 0.f), az = std::max(q[2], 0.f);
    const float box = std::sqrt(ax * ax + ay * ay + az * az) +
                      std::min(std::max(q[0], std::max(q[1], q[2])), 0.f);
    *outStep = std::max(td, box);
    *outZ = z;
}

inline void extrudeDist(const ExtrudeCtx& c, const float wp[3], float* outStep, float* outZ) {
    const float d[3] = {wp[0] - c.center[0], wp[1] - c.center[1], wp[2] - c.center[2]};
    const float lp[3] = {c.mat[0] * d[0] + c.mat[1] * d[1] + c.mat[2] * d[2],
                         c.mat[3] * d[0] + c.mat[4] * d[1] + c.mat[5] * d[2],
                         c.mat[6] * d[0] + c.mat[7] * d[1] + c.mat[8] * d[2]};
    extrudeSd(c, lp, outStep, outZ);
}

}  // namespace

void fxExtrudeAmPixel(const FxOp& op, bool flat, float ax, float ay, float picW, float picH,
                      const FxExtrudeSampler& s, float out[4]) {
    ExtrudeCtx c;
    c.flat = flat;
    c.sampler = &s;
    // Unpack the op (the renderer's uniform unpacking mirrors this).
    auto unpackColor = [](float packed, float rgb[3]) {
        const int pk = (int)std::lround(clampf(packed, 0.f, 16777215.f));
        rgb[0] = ((pk >> 16) & 0xFF) / 255.f;
        rgb[1] = ((pk >> 8) & 0xFF) / 255.f;
        rgb[2] = (pk & 0xFF) / 255.f;
    };
    c.depth = op.p[0];
    c.scale = op.p[1];
    const float ratioX = op.p[2], ratioY = op.p[3];
    c.interiorBlend = op.p[4];
    c.fadeBlend = op.p[5];
    c.shadingType = flat ? 0 : (int)std::lround(op.p[6]);
    fxExtrudeRotateMat(op.q[0], op.q[1], op.q[2], c.mat);
    c.center[0] = op.q[3] / 100.f;
    c.center[1] = op.q[4] / 100.f;
    c.center[2] = op.q[5] / 100.f;
    unpackColor(op.q[6], c.interior);
    unpackColor(op.r[0], c.fade);
    if (flat) {
        c.strength = 0.1f;  // unused by the flat march except the step size: the def default
        c.iter = std::max(1, (int)std::lround(op.r[1]));
        c.lightIntensity = c.shine = c.spec = c.ambient = 0.f;
        c.lightPos[0] = c.lightPos[1] = c.lightPos[2] = 0.f;
        c.lightColor[0] = c.lightColor[1] = c.lightColor[2] = 0.f;
    } else {
        unpackColor(op.r[1], c.lightColor);
        const int pq = (int)std::lround(clampf(op.r[2], 0.f, 16777215.f));
        const float ldx = (float)(pq / 4096 - 2048), ldy = (float)(pq % 4096 - 2048);
        const int li = (int)std::lround(op.r[3]);
        c.lightIntensity = (li % 256) / 100.f;
        c.strength = (li / 256) / 100.f;
        const int sa = (int)std::lround(op.r[4]);
        c.shine = (sa / 128) / 100.f;
        c.ambient = (sa % 128) / 100.f;
        c.spec = op.r[5];
        const int dm = (int)std::lround(op.r[6]);
        const float lightDepth = (dm % 2048) / 2.f - 100.f;
        c.iter = std::max(1, dm / 2048);
        c.lightPos[0] = ldx / 2.f;
        c.lightPos[1] = -ldy / 2.f;
        c.lightPos[2] = -lightDepth * 2.f;
    }
    const float mx = std::max(picW, picH);
    c.width = ratioX * 0.5f * picW / mx;
    c.height = ratioY * 0.5f * picH / mx;

    // main() of the def.
    float stx = ax - 0.5f, sty = ay - 0.5f;
    if (picW < picH) stx *= picW / picH; else sty *= picH / picW;
    stx /= c.scale;
    sty /= c.scale;
    stx /= 5.8f;
    sty /= 5.8f;
    const float ro[3] = {0.f, 1.f, -1.f};
    float rd[3] = {stx, sty, 1.f};
    const float rl = std::sqrt(rd[0] * rd[0] + rd[1] * rd[1] + rd[2] * rd[2]);
    rd[0] /= rl; rd[1] /= rl; rd[2] /= rl;
    float d = 0.f, dz = -1.f;
    for (int i = 0; i < c.iter; ++i) {
        const float p[3] = {ro[0] + rd[0] * d, ro[1] + rd[1] * d, ro[2] + rd[2] * d};
        float step, zz;
        extrudeDist(c, p, &step, &zz);
        d += step;
        if (step < 1e-7f || d > 10000.f) { dz = zz; break; }
    }
    const float p[3] = {ro[0] + rd[0] * d, ro[1] + rd[1] * d, ro[2] + rd[2] * d};
    // calcNormal: forward differences of the LOCAL distance, e = 0.01. The def reads
    // dinf.x (the step) of three offset points and subtracts it from d0.
    float d0s, d0z;
    extrudeDist(c, p, &d0s, &d0z);
    float n[3];
    {
        const float e = 0.01f;
        const float off[3][3] = {{-e, 0, 0}, {0, -e, 0}, {0, 0, -e}};
        // The def offsets in WORLD space; dist() centres + rotates internally, so the
        // offsets ride along (the GLSL port does the same).
        for (int k = 0; k < 3; ++k) {
            const float pp[3] = {p[0] + off[k][0], p[1] + off[k][1], p[2] + off[k][2]};
            float ss, szz;
            extrudeDist(c, pp, &ss, &szz);
            n[k] = d0s - ss;
        }
        const float nl = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (nl > 1e-12f) { n[0] /= nl; n[1] /= nl; n[2] /= nl; }
    }
    // The surface texture coordinate: the hit point in the def's local space — the
    // same rotateAndOrient multiply dist() uses (the def applies it identically in
    // both places; it is not an inverse transform).
    const float lc[3] = {p[0] - c.center[0], p[1] - c.center[1], p[2] - c.center[2]};
    const float loc[3] = {c.mat[0] * lc[0] + c.mat[1] * lc[1] + c.mat[2] * lc[2],
                          c.mat[3] * lc[0] + c.mat[4] * lc[1] + c.mat[5] * lc[2],
                          c.mat[6] * lc[0] + c.mat[7] * lc[1] + c.mat[8] * lc[2]};
    const float tc[2] = {(loc[0] / c.width + 1.f) / 2.f, (loc[1] / c.height + 1.f) / 2.f};
    const float amask = (c.width < 0.0105f || c.height < 0.0105f) ? 0.f : 1.f;
    float baseC[4], blurC[4];
    sampleTex(c, 0, tc[0], tc[1], baseC);
    if (flat) { blurC[0] = baseC[0]; blurC[1] = baseC[1]; blurC[2] = baseC[2]; blurC[3] = baseC[3]; }
    else sampleTex(c, 1, tc[0], tc[1], blurC);
    float tex[4];
    if (blurC[3] > baseC[3] && blurC[3] > 0.25f) {
        for (int k = 0; k < 4; ++k) tex[k] = blurC[k] / blurC[3];
    } else if (baseC[3] > 0.25f) {
        for (int k = 0; k < 4; ++k) tex[k] = baseC[k] / baseC[3];
    } else {
        for (int k = 0; k < 4; ++k) tex[k] = baseC[k];
    }
    if (dz > 0.001f && dz < 0.999f) {
        for (int k = 0; k < 3; ++k) tex[k] = tex[k] + (c.interior[k] * tex[3] - tex[k]) * c.interiorBlend;
        for (int k = 0; k < 3; ++k) tex[k] = tex[k] + (c.fade[k] * tex[3] - tex[k]) * (c.fadeBlend * dz);
    }
    // applyShading(tex, p, n, d) — n is the LOCAL normal, p the world hit point.
    const float a = sstep(30.f, 9.f, d) * sstep(0.3f, 0.2f, n[2]);
    float res[4];
    if (c.shadingType == 1 || c.shadingType == 2) {
        float lv[3] = {c.lightPos[0], c.lightPos[1], c.lightPos[2]};
        if (c.shadingType == 1) { lv[0] -= p[0]; lv[1] -= p[1]; lv[2] -= p[2]; }
        const float ll = std::sqrt(lv[0] * lv[0] + lv[1] * lv[1] + lv[2] * lv[2]);
        if (ll > 1e-12f) { lv[0] /= ll; lv[1] /= ll; lv[2] /= ll; }
        const float li = n[0] * lv[0] + n[1] * lv[1] + n[2] * lv[2];
        float surf[3];
        for (int k = 0; k < 3; ++k)
            surf[k] = (tex[k] * c.ambient + tex[k] * c.lightColor[k] * li * (1.f - c.ambient)) * c.lightIntensity;
        const float sp = std::pow(std::max(li, 0.f), c.spec);  // GLSL pow(l<0) is undefined; AM's look is 0 there
        const float t = c.shine * sp;
        for (int k = 0; k < 3; ++k) surf[k] = surf[k] * (1.f - c.shine * 0.1f) * (1.f - t) + t;
        for (int k = 0; k < 3; ++k) res[k] = surf[k] * a;
        res[3] = tex[3] * a;
    } else if (c.shadingType == 3 || c.shadingType == 5) {
        const float ds = std::max(std::max(c.width, c.height), c.depth) * 2.5f;
        const float dd = 1.f - ((d - c.center[2] + ds / 2.f) / ds);
        if (c.shadingType == 3) { res[0] = res[1] = res[2] = dd * a; res[3] = a; }
        else {
            res[0] = (n[0] / 2.f + 0.5f) * dd * a;
            res[1] = (n[1] / 2.f + 0.5f) * dd * a;
            res[2] = (n[2] / 2.f + 0.5f) * dd * a;
            res[3] = a;
        }
    } else if (c.shadingType == 4) {
        res[0] = (n[0] / 2.f + 0.5f) * a;
        res[1] = (n[1] / 2.f + 0.5f) * a;
        res[2] = (n[2] / 2.f + 0.5f) * a;
        res[3] = a;
    } else {
        for (int k = 0; k < 3; ++k) res[k] = tex[k] * a;
        res[3] = tex[3] * a;
    }
    for (int k = 0; k < 4; ++k) out[k] = res[k] * amask;
}

}  // namespace am
